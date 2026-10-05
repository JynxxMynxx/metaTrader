package dev.zenith.trader.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.*;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.*;
import java.util.*;
import java.util.function.Predicate;
import static com.zenith.Globals.*;
import static dev.zenith.trader.VillagerTraderPlugin.META_CONFIG;

/** One authoritative post-trade pipeline. Inventory is re-read after each acknowledged action. */
public final class SortingCycle {
    private final MetaTrader owner;
    private PathingRequestFuture path;
    private RequestFuture action = RequestFuture.immediateFuture(true);
    private BlockPos opened;
    private int wait, settle, unchanged, openSettle;
    private int before = -1;
    private String pendingStage;
    private boolean breaking;
    private int transferBefore = -1, stalledTransfers;
    private Predicate<ItemStack> transferFilter;
    private boolean transferRetrieval;
    private long pickupDeadline;
    private int resumeScan;
    private boolean partialScanDone;
    private final StationaryWorldEdit edit = new StationaryWorldEdit();

    public SortingCycle(MetaTrader owner) { this.owner = owner; }
    public boolean inventoryIdle() {
        if (!action.isCompleted() || breaking) return false;
        if (SupplyRestock.count("minecraft:shulker_shell") < 16 || SupplyRestock.count("minecraft:chest") < 16) {
            if (opened != null) { close(); return false; }
            return true;
        }
        return path == null && opened == null;
    }

    public void resetRuntime() {
        edit.cancel();
        path = null; opened = null; action = RequestFuture.immediateFuture(true);
        wait = settle = unchanged = openSettle = 0; before = -1; pendingStage = null; breaking = false;
        transferFilter = null; stalledTransfers=0;
        pickupDeadline=0; resumeScan=0; partialScanDone=false;
        owner.endProtectedWorldEdit();
    }

    public void start(boolean nonstackable) {
        resetRuntime();
        META_CONFIG.sortingError = "";
        META_CONFIG.sortingReplacement = null;
        META_CONFIG.sortingRule = "";
        META_CONFIG.sortingResumeStage = "";
        if (nonstackable && (META_CONFIG.unstackableShulkerLocation.equals(BlockPos.ZERO)
            || META_CONFIG.unstackableFinalStorageArea.first.equals(BlockPos.ZERO))) {
            fail("Configure unstackableShulkerLocation and unstackableFinalStorageArea before sorting"); return;
        }
        stage(nonstackable ? "NON_MATERIALS" : "LEFTOVERS");
    }

    public boolean tick(boolean nonstackable) {
        edit.tick();
        if (breaking && path != null && path.isCompleted() && !path.isAccepted()) { fail("Stationary block operation failed or player moved; re-position and retry"); return false; }
        if (!META_CONFIG.sortingError.isEmpty()) return false;
        if (!action.isCompleted()) return false;
        if (!action.getNow()) { fail("Inventory action was rejected"); return false; }
        if (settle > 0) { settle--; return false; }
        if (transferFilter != null) {
            int now=count(transferFilter);
            if ((transferRetrieval ? now>transferBefore : now<transferBefore)) stalledTransfers=0;
            else if (++stalledTransfers >= 3) { fail("Inventory transfer produced no confirmed progress"); return false; }
            transferFilter=null;
        }
        if (pendingStage != null) {
            String next = pendingStage; pendingStage = null; stage(next);
        }
        try {
            if (META_CONFIG.sortingReplacement != null) { replace(nonstackable); return false; }
            return switch (META_CONFIG.sortingStage) {
                case "LEFTOVERS" -> { leftovers(); yield false; }
                case "MATERIALS" -> { materials(false); yield false; }
                case "CRAFT" -> { craft(Math.max(0, META_CONFIG.shulkersToCraftForStackableSort - count(SortingCycle::emptyBox)), "STACK_INPUT"); yield false; }
                case "STACK_INPUT" -> { stackInput(); yield false; }
                case "STACK_DEPOSIT" -> { deposit(false); yield false; }
                case "COLLECT" -> { collect(); yield false; }
                case "STORE" -> { store(); yield false; }
                case "RETURN" -> { returnBoxes(); yield false; }
                case "NON_MATERIALS" -> { materials(true); yield false; }
                case "NON_INPUT" -> { nonInput(); yield false; }
                case "NON_DEPOSIT" -> { deposit(true); yield false; }
                case "RETURN_MATERIALS" -> { returnMaterials(); yield false; }
                case "DONE" -> true;
                default -> throw new IllegalStateException("Unknown sorting stage " + META_CONFIG.sortingStage);
            };
        } catch (RuntimeException e) { fail(e.getMessage()); return false; }
    }

    private void leftovers() {
        int target = META_CONFIG.shulkersToCraftForStackableSort;
        if (target < 1) { fail("Stackable craft target must be positive"); return; }
        if (count(SortingCycle::emptyBox) >= target || META_CONFIG.sortingCursor >= META_CONFIG.leftoverEmptyShulkerChests.size()) {
            stage("MATERIALS"); return;
        }
        if (!open(META_CONFIG.leftoverEmptyShulkerChests.get(META_CONFIG.sortingCursor))) return;
        if (transfer(true, SortingCycle::emptyBox, target - count(SortingCycle::emptyBox))) return;
        advance();
    }

    private void materials(boolean non) {
        finishMaterials(non);
    }

    private void recordSource(String item) { META_CONFIG.sortingIngredientSources.put(item, opened); saveConfigAsync(); }
    private void finishMaterials(boolean non) {
        if (non && !META_CONFIG.sortingResumeStage.isEmpty()) {
            String next=META_CONFIG.sortingResumeStage; META_CONFIG.sortingResumeStage="";
            META_CONFIG.sortingCursor=META_CONFIG.sortingResumeCursor; setStageKeepingCursor(next);
        } else stage(non ? "NON_INPUT" : "CRAFT");
    }

    private void craft(int amount, String next) {
        if (amount == 0) { setStageKeepingCursor(next); return; }
        if (count(s -> named(s, "minecraft:shulker_shell")) < 2 || count(s -> named(s, "minecraft:chest")) < 1) {
            fail("Out of shulker ingredients; refill configured supplies and retry"); return;
        }
        if (freeSlots() < 1) { fail("No inventory slot available for a crafted shulker"); return; }
        var tables = ContainerLocator.craftingTables();
        if (tables.isEmpty()) { fail("No loaded crafting table within local search range"); return; }
        if (!open(tables.getFirst())) return;
        var c = container();
        if (c.getType() != ContainerType.CRAFTING) { fail("Crafting table did not open a crafting screen"); return; }
        int now = count(SortingCycle::emptyBox);
        if (before == now && ++unchanged > 3) { fail("Crafting recipe produced no acknowledged shulker"); return; }
        if (before != now) unchanged = 0;
        before = now;
        submit(List.of(new PlaceRecipe(c.getContainerId(), "minecraft:shulker_box", false),
            new ShiftClick(c.getContainerId(), 0, ShiftClickItemAction.LEFT_CLICK)));
    }

    private List<Map.Entry<String, BlockPos>> inputs() {
        var result = new ArrayList<Map.Entry<String, BlockPos>>();
        META_CONFIG.stackableShulkingInputChests.forEach((item, positions) -> positions.forEach(p -> result.add(Map.entry(item, p))));
        return result;
    }

    private void stackInput() {
        var sources = inputs();
        if (META_CONFIG.sortingCursor >= sources.size()) { stage("COLLECT"); return; }
        var source = sources.get(META_CONFIG.sortingCursor);
        META_CONFIG.sortingRule = source.getKey();
        if (count(s -> matches(s, source.getKey())) > 0) { setStageKeepingCursor("STACK_DEPOSIT"); return; }
        if (!open(source.getValue())) return;
        if (transfer(true, s -> matches(s, source.getKey()), Integer.MAX_VALUE, 2)) return;
        if (has(true, s -> matches(s, source.getKey()))) { fail("No inventory capacity for stackable inputs"); return; }
        advance();
    }

    private List<String> nonRules() {
        var rules = new ArrayList<>(META_CONFIG.outputShulkerLocations.keySet().stream()
            .filter(k -> ItemRegistry.REGISTRY.get(k) != null && ItemRegistry.REGISTRY.get(k).stackSize() == 1
                && !k.equals("minecraft:enchanted_book")).toList());
        rules.addAll(META_CONFIG.outputShulkerLocationEnchantments.keySet());
        return rules;
    }

    private List<BlockPos> dumpChests() { return chests(List.of(META_CONFIG.nonStackableDumpChestBounds)); }

    private void nonInput() {
        var rules = nonRules();
        if (META_CONFIG.sortingRule.isEmpty()) {
            if (rules.isEmpty()) { fail("No non-stackable output rules configured"); return; }
            META_CONFIG.sortingRule = rules.getFirst(); saveConfigAsync();
        }
        String rule = META_CONFIG.sortingRule;
        if (count(s -> matches(s, rule)) > 0) { setStageKeepingCursor("NON_DEPOSIT"); return; }
        var sources = dumpChests();
        if (sources.isEmpty()) { fail("No loaded dump chests found; load the dump region before running"); return; }
        if (META_CONFIG.sortingCursor >= sources.size()) {
            if (!rule.equals("VERIFY") && World.getBlock(META_CONFIG.unstackableShulkerLocation).name().endsWith("shulker_box")) {
                if (!open(META_CONFIG.unstackableShulkerLocation)) return;
                if (has(true,s -> present(s) && !matches(s,rule))) {fail("Unstackable working shulker contains another item/enchantment");return;}
                if (has(true, SortingCycle::present)) {
                    owner.diagnostic("INCOMPLETE_SHULKER",rule,META_CONFIG.unstackableShulkerLocation);
                    META_CONFIG.sortingReplacement=META_CONFIG.unstackableShulkerLocation;
                    META_CONFIG.sortingStage="BREAK_PARTIAL"; saveConfigAsync(); close(); return;
                }
            }
            int next = rules.indexOf(rule) + 1;
            if (rule.equals("VERIFY")) { stage("RETURN"); return; }
            if (next < rules.size()) {
                partialScanDone=false; resumeScan=0;
                META_CONFIG.sortingRule = rules.get(next); stage("NON_INPUT");
            } else {
                // Final pass verifies that no unknown/unroutable items remain.
                META_CONFIG.sortingRule = "VERIFY"; stage("NON_INPUT");
            }
            return;
        }
        if (!open(sources.get(META_CONFIG.sortingCursor))) return;
        if (rule.equals("VERIFY")) {
            if (has(true, SortingCycle::present)) { fail("Dump contains unconfigured items or newly arrived items; inspect/refill rules and retry"); return; }
            advance();
            if (META_CONFIG.sortingCursor >= sources.size()) stage("RETURN");
            return;
        }
        if (transfer(true, s -> matches(s, rule), Integer.MAX_VALUE, 2)) return;
        if (has(true, s -> matches(s, rule))) { fail("No inventory capacity for non-stackable inputs"); return; }
        advance();
    }

    private void deposit(boolean non) {
        String rule = META_CONFIG.sortingRule;
        if (count(s -> matches(s, rule)) == 0 && opened == null) { setStageKeepingCursor(non ? "NON_INPUT" : "STACK_INPUT"); return; }
        var positions = non ? List.of(META_CONFIG.unstackableShulkerLocation) : META_CONFIG.outputShulkerLocations.get(rule);
        if (positions == null || positions.isEmpty()) { fail("No output shulker for " + rule); return; }
        var target = ContainerLocator.nearest(positions);
        if (non && !World.getBlock(target).name().endsWith("shulker_box")) {
            if (!partialScanDone) {
                if (count(s -> partialForRule(s,rule)) > 0) { partialScanDone=true; close(); return; }
                var stored=chests(List.of(META_CONFIG.unstackableFinalStorageArea));
                if (resumeScan < stored.size()) {
                    if (!open(stored.get(resumeScan))) return;
                    if (count(s -> partialForRule(s,rule)) > 0) { partialScanDone=true; close(); return; }
                    if (transfer(true,s -> partialForRule(s,rule),1)) return;
                    resumeScan++; close(); return;
                }
                partialScanDone=true;
            }
            if (count(s -> partialForRule(s,rule)) == 0 && count(SortingCycle::emptyBox) == 0) {
                if (count(s -> named(s,"minecraft:shulker_shell")) < 2 || count(s -> named(s,"minecraft:chest")) < 1) {
                    META_CONFIG.sortingResumeCursor=META_CONFIG.sortingCursor; META_CONFIG.sortingResumeStage="NON_DEPOSIT"; stage("NON_MATERIALS"); return;
                }
                craft(1,"NON_DEPOSIT"); return;
            }
            META_CONFIG.sortingReplacement=target; META_CONFIG.sortingStage="PLACE"; close(); saveConfigAsync(); return;
        }
        if (!open(target)) return;
        if (container().getType() != ContainerType.SHULKER_BOX) { fail("Expected a pre-placed shulker at " + target); return; }
        if (has(true, s -> present(s) && !matches(s, rule))) { fail("Output shulker contains another item/enchantment at " + target); return; }
        if (transfer(false, s -> matches(s, rule), Integer.MAX_VALUE)) return;
        if (count(s -> matches(s, rule)) == 0 && !full(container())) { setStageKeepingCursor(non ? "NON_INPUT" : "STACK_INPUT"); return; }
        if (!full(container())) { fail("Shulker transfer stalled despite available capacity"); return; }
        META_CONFIG.sortingReplacement = target; META_CONFIG.sortingStage="BREAK"; saveConfigAsync(); close();
    }

    private void replace(boolean non) {
        var p = META_CONFIG.sortingReplacement;
        if (path != null && !path.isCompleted()) return;
        if (!World.isChunkLoadedBlockPos(p.x(), p.z())) {
            if (path == null || path.isCompleted()) path = BARITONE.pathTo(p.x(), p.y(), p.z());
            return;
        }
        String block = World.getBlock(p).name();
        String work=META_CONFIG.sortingStage;
        if (work.equals("PICKUP") || work.equals("PICKUP_PARTIAL")) {
            owner.endProtectedWorldEdit(); breaking=false;
            var player=CACHE.getPlayerCache().getThePlayer().blockPos();
            if (player.x()!=p.x() || player.z()!=p.z() || Math.abs(player.y()-p.y())>1) {
                path=BARITONE.pathTo(p.x(),p.y(),p.z()); return;
            }
            if (pickupDeadline==0) pickupDeadline=System.nanoTime()+2_000_000_000L;
            if (count(SortingCycle::isBox)<=META_CONFIG.sortingPickupBefore && System.nanoTime()<pickupDeadline) return;
            if (count(SortingCycle::isBox)<=META_CONFIG.sortingPickupBefore) owner.diagnostic("SHULKER_PICKUP_FAILED",META_CONFIG.sortingRule,p);
            pickupDeadline=0; path=null;
            META_CONFIG.sortingStage=work.equals("PICKUP_PARTIAL") ? "STORE_PARTIAL" : "STORE_PICKUP";
            META_CONFIG.sortingStorageCursor=0; saveConfigAsync(); return;
        }
        if (work.equals("STORE_PICKUP") || work.equals("STORE_PARTIAL")) {
            if (!storePicked(non,work.equals("STORE_PARTIAL"))) return;
            if (non) {
                META_CONFIG.sortingReplacement=null;
                partialScanDone=false; resumeScan=0;
                setStageKeepingCursor(work.equals("STORE_PARTIAL") ? "NON_INPUT" : "NON_DEPOSIT");
            } else { META_CONFIG.sortingStage="PLACE"; saveConfigAsync(); }
            return;
        }
        if (block.endsWith("shulker_box")) {
            // After a confirmed placement, resume normal protected pathing.
            if (META_CONFIG.sortingStage.equals("REPLACED")) {
                owner.endProtectedWorldEdit(); breaking = false; path = null;
                META_CONFIG.sortingReplacement = null;
                META_CONFIG.sortingStage = non ? "NON_DEPOSIT" : "STACK_DEPOSIT";
                saveConfigAsync(); return;
            }
            int pick = toolSlot();
            if (pick < 0) { fail("A pickaxe is required to break full shulkers"); return; }
            if (!breaking) {
                if (freeSlots()<1) { fail("No inventory slot available to pick up a broken shulker"); return; }
                META_CONFIG.sortingStand=CACHE.getPlayerCache().getThePlayer().blockPos();
                META_CONFIG.sortingPickupBefore=count(SortingCycle::isBox);
                owner.beginProtectedWorldEdit();
                BARITONE.stop();
                submit(InventoryHotbar.selectSlot(pick));
                saveConfigAsync(); breaking = true; return;
            }
            path = edit.start(p, null); settle = 5; return;
        }
        owner.endProtectedWorldEdit();
        breaking=false;
        if (!block.equals("minecraft:air") && !block.equals("minecraft:cave_air")) { fail("Replacement position is obstructed: " + p); return; }
        if (work.equals("BREAK") || work.equals("BREAK_PARTIAL")) {
            path=null;
            META_CONFIG.sortingStage=work.equals("BREAK_PARTIAL") ? "PICKUP_PARTIAL" : "PICKUP";
            saveConfigAsync(); return;
        }
        if (count(SortingCycle::emptyBox) == 0) {
            if (!non || count(s -> partialForRule(s,META_CONFIG.sortingRule)) == 0) {
                craft(1, "PLACE"); return;
            }
        }
        if (container().getContainerId()!=0) { close(); return; }
        var player=CACHE.getPlayerCache().getThePlayer().blockPos();
        if (Math.abs(player.x()-p.x())+Math.abs(player.z()-p.z())>3 || Math.abs(player.y()-p.y())>2) {
            var stand=META_CONFIG.sortingStand;
            if(stand==null || Math.abs(stand.x()-p.x())+Math.abs(stand.z()-p.z())>3) stand=new BlockPos(p.x()+1,p.y(),p.z());
            path=BARITONE.pathTo(stand.x(),stand.y(),stand.z()); return;
        }
        if (player.x()==p.x() && player.z()==p.z()) {
            var stand=META_CONFIG.sortingStand;
            if (stand==null || (stand.x()==p.x() && stand.z()==p.z())) stand=new BlockPos(p.x()+1,p.y(),p.z());
            path=BARITONE.pathTo(stand.x(),stand.y(),stand.z()); return;
        }
        var inv=CACHE.getPlayerCache().getPlayerInventory();
        int boxSlot=-1;
        for(int i=9;i<=44;i++) if(non && partialForRule(inv.get(i),META_CONFIG.sortingRule)) {boxSlot=i;break;}
        if(boxSlot<0) for(int i=9;i<=44;i++) if(emptyBox(inv.get(i))) {boxSlot=i;break;}
        if(boxSlot<0) {fail("No suitable shulker available for placement");return;}
        // PlaceBlock selects the first stack of its item ID, so put the chosen
        // partial/empty box first rather than accidentally placing another box.
        if(boxSlot!=9) {
            submit(List.of(new ClickItem(0,boxSlot,ClickItemAction.LEFT_CLICK),new ClickItem(0,9,ClickItemAction.LEFT_CLICK),new ClickItem(0,boxSlot,ClickItemAction.LEFT_CLICK))); return;
        }
        opened=null; path=null;
        owner.beginProtectedWorldEdit();
        META_CONFIG.sortingStage = "REPLACED"; saveConfigAsync();
        path = edit.start(p, ItemRegistry.REGISTRY.get(inv.get(9).getId()));
        breaking = true; settle = 5;
    }

    private boolean storePicked(boolean non, boolean partial) {
        String rule=META_CONFIG.sortingRule;
        Predicate<ItemStack> filter=s -> boxForRule(s,rule) && (partial ? !filledBox(s) : filledBox(s));
        if(count(filter)==0) { close(); return true; }
        var regions=non ? List.of(META_CONFIG.unstackableFinalStorageArea) : META_CONFIG.storeChestBounds.get(rule);
        if(regions==null) {fail("No final storage for " + rule);return false;}
        var targets=new ArrayList<>(chests(regions));
        if(partial) Collections.reverse(targets);
        if(META_CONFIG.sortingStorageCursor>=targets.size()) {fail("Final storage missing or full for " + rule);return false;}
        if(!open(targets.get(META_CONFIG.sortingStorageCursor))) return false;
        if(transfer(false,filter,Integer.MAX_VALUE)) return false;
        META_CONFIG.sortingStorageCursor++; close(); saveConfigAsync(); return false;
    }

    private void store() {
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        ItemStack box = null;
        for (int i=9;i<=44;i++) if (filledBox(inv.get(i))) { box=inv.get(i); break; }
        if (box == null) { stage("RETURN"); return; }
        var contents = contents(box);
        String item = ItemRegistry.REGISTRY.get(contents.stream().filter(SortingCycle::present).findFirst().orElseThrow().getId()).name();
        if (contents.stream().filter(SortingCycle::present).anyMatch(s -> !named(s,item))) { fail("Mixed filled shulker cannot be stored by item rule"); return; }
        var regions = META_CONFIG.storeChestBounds.get(item);
        if (regions == null) { fail("No final storeChestBounds for " + item); return; }
        var targets = chests(regions);
        if (targets.isEmpty()) { fail("No loaded final storage chest for " + item); return; }
        int cursor = META_CONFIG.containerCursor.getOrDefault("sort:" + item, 0);
        if (cursor >= targets.size()) { fail("Final storage region is full for " + item); return; }
        if (!open(targets.get(cursor))) return;
        if (transfer(false, s -> filledBox(s) && contents(s).stream().filter(SortingCycle::present).allMatch(c -> named(c,item)), Integer.MAX_VALUE)) return;
        META_CONFIG.containerCursor.put("sort:" + item, cursor+1); saveConfigAsync(); close();
    }

    private void collect() {
        if (META_CONFIG.sortingCursor >= META_CONFIG.stackableShulkerOutputChests.size()) { stage("STORE"); return; }
        if (!open(META_CONFIG.stackableShulkerOutputChests.get(META_CONFIG.sortingCursor))) return;
        if (transfer(true, SortingCycle::filledBox, Integer.MAX_VALUE, 2)) return;
        if (has(true, SortingCycle::filledBox)) { close(); pendingStage="STORE"; return; }
        advance();
    }

    private void returnBoxes() {
        if (count(SortingCycle::emptyBox) == 0) { stage("RETURN_MATERIALS"); return; }
        if (META_CONFIG.sortingCursor >= META_CONFIG.leftoverEmptyShulkerChests.size()) { fail("Leftover empty-shulker storage missing or full"); return; }
        if (!open(META_CONFIG.leftoverEmptyShulkerChests.get(META_CONFIG.sortingCursor))) return;
        if (transfer(false, SortingCycle::emptyBox, Integer.MAX_VALUE)) return;
        advance();
    }

    private void returnMaterials() {
        META_CONFIG.sortingIngredientSources.clear(); stage("DONE");
    }

    private List<BlockPos> chests(List<dev.zenith.trader.MetaTraderConfig.Region> regions) {
        var result = new LinkedHashSet<BlockPos>();
        for (var r : regions) for (var p : r.positions()) {
            if (!World.isChunkLoadedBlockPos(p.x(),p.z())) continue;
            var b = World.getBlock(p).name();
            if (b.equals("minecraft:chest") || b.equals("minecraft:trapped_chest")) result.add(p);
        }
        return storageOrder(result);
    }

    public static List<BlockPos> storageOrder(Collection<BlockPos> positions) {
        // South to north within each east-to-west column, then the next Y layer.
        return positions.stream().sorted(Comparator.comparingInt(BlockPos::y)
            .thenComparing(Comparator.comparingInt(BlockPos::x).reversed())
            .thenComparing(Comparator.comparingInt(BlockPos::z).reversed())).toList();
    }

    private boolean open(BlockPos p) {
        owner.endProtectedWorldEdit();
        if (opened != null && !opened.equals(p)) { close(); return false; }
        if (opened == null) {
            if (container().getContainerId() != 0) { close(); return false; }
            opened=p; path=BARITONE.rightClickBlock(p.x(),p.y(),p.z()); wait=0; openSettle=6; return false;
        }
        if (path != null && !path.isCompleted()) {
            if (++wait > 1200) fail("Timed out reaching container " + p);
            return false;
        }
        if (container().getContainerId() == 0) {
            if (++wait > 300) fail("Container did not open at " + p);
            return false;
        }
        if (openSettle > 0) { openSettle--; return false; }
        return true;
    }

    private com.zenith.cache.data.inventory.Container container() { return CACHE.getPlayerCache().getInventoryCache().getOpenContainer(); }

    private boolean has(boolean top, Predicate<ItemStack> predicate) {
        var c=container(); int split=c.getSize()-36;
        for(int i=top?0:split;i<(top?split:c.getSize());i++) if(predicate.test(c.getItemStack(i))) return true;
        return false;
    }

    private boolean transfer(boolean retrieval, Predicate<ItemStack> filter, int max) { return transfer(retrieval,filter,max,0); }
    private boolean transfer(boolean retrieval, Predicate<ItemStack> filter, int max, int reserve) {
        if (max <= 0 || (retrieval && freeSlots() <= reserve)) return false;
        var c=container(); int split=c.getSize()-36, id=c.getContainerId();
        int source=-1, dest=-1;
        for(int i=retrieval?0:split;i<(retrieval?split:c.getSize());i++) if(filter.test(c.getItemStack(i))) { source=i;break; }
        if(source<0) return false;
        var stack=c.getItemStack(source);
        int capacity=0;
        for(int i=retrieval?split:0;i<(retrieval?c.getSize():split);i++) {
            var d=c.getItemStack(i);
            if (!present(d)) {dest=i;capacity=ItemRegistry.REGISTRY.get(stack.getId()).stackSize();break;}
            if (d.getId()==stack.getId() && Objects.equals(d.getDataComponents(),stack.getDataComponents()) && d.getAmount()<ItemRegistry.REGISTRY.get(d.getId()).stackSize()) {
                dest=i;capacity=ItemRegistry.REGISTRY.get(d.getId()).stackSize()-d.getAmount();break;
            }
        }
        if(dest<0) return false;
        // Whole stacks only when under the exact material target; otherwise one item.
        var actions=new ArrayList<InventoryAction>();
        actions.add(new ClickItem(id,source,ClickItemAction.LEFT_CLICK));
        boolean whole=stack.getAmount()<=max && stack.getAmount()<=capacity;
        actions.add(new ClickItem(id,dest,whole?ClickItemAction.LEFT_CLICK:ClickItemAction.RIGHT_CLICK));
        if(!whole) actions.add(new ClickItem(id,source,ClickItemAction.LEFT_CLICK));
        transferBefore=count(filter); transferFilter=filter; transferRetrieval=retrieval;
        submit(actions); return true;
    }

    private void submit(List<? extends InventoryAction> actions) {
        action=INVENTORY.submit(InventoryActionRequest.builder().owner(owner)
            .priority(com.zenith.feature.pathfinder.Baritone.getPriority()+100).actions(new ArrayList<>(actions)).build());
        settle=5;
    }
    private void close() {
        var c=container();
        if(c.getContainerId()!=0) submit(List.of(new CloseContainer(c.getContainerId())));
        opened=null; path=null; wait=0;
    }
    private void stage(String next) { close(); META_CONFIG.sortingStage=next; META_CONFIG.sortingCursor=0; before=-1; unchanged=0; saveConfigAsync(); }
    private void setStageKeepingCursor(String next) { close(); META_CONFIG.sortingStage=next; saveConfigAsync(); }
    private void advance() { close(); META_CONFIG.sortingCursor++; saveConfigAsync(); }
    private void fail(String reason) { edit.cancel(); owner.endProtectedWorldEdit(); BARITONE.stop(); META_CONFIG.sortingError=Objects.requireNonNullElse(reason,"Sorting failed"); saveConfigAsync(); owner.diagnostic("SORT_FAILED",META_CONFIG.sortingError); }
    private int count(Predicate<ItemStack> filter) { int n=0; var inv=CACHE.getPlayerCache().getPlayerInventory(); for(int i=9;i<=44;i++) if(filter.test(inv.get(i))) n+=inv.get(i).getAmount();return n; }
    private int freeSlots() { int n=0;var inv=CACHE.getPlayerCache().getPlayerInventory();for(int i=9;i<=44;i++) if(!present(inv.get(i))) n++;return n; }
    private int toolSlot() {var inv=CACHE.getPlayerCache().getPlayerInventory();for(int i=9;i<=44;i++) if(present(inv.get(i)) && ItemRegistry.REGISTRY.get(inv.get(i).getId()).name().endsWith("_pickaxe"))return i;return -1;}
    public static boolean present(ItemStack s) {return s!=null && s!=Container.EMPTY_STACK && s.getAmount()>0;}
    public static boolean named(ItemStack s,String name) {return present(s) && ItemRegistry.REGISTRY.get(s.getId())!=null && ItemRegistry.REGISTRY.get(s.getId()).name().equals(name);}
    private static boolean isBox(ItemStack s) {return present(s) && ItemRegistry.REGISTRY.get(s.getId()).name().endsWith("shulker_box");}
    public static List<ItemStack> contents(ItemStack s) {if(!present(s)||s.getDataComponents()==null)return List.of();var list=s.getDataComponents().get(DataComponentTypes.CONTAINER);return list==null?List.of():list;}
    public static boolean emptyBox(ItemStack s) {return isBox(s)&&contents(s).stream().noneMatch(SortingCycle::present);}
    public static boolean boxForRule(ItemStack s,String rule) {return isBox(s)&&contents(s).stream().anyMatch(SortingCycle::present)&&contents(s).stream().filter(SortingCycle::present).allMatch(c -> matches(c,rule));}
    public static boolean partialForRule(ItemStack s,String rule) {return boxForRule(s,rule)&&!filledBox(s);}
    public static boolean filledBox(ItemStack s) {return isBox(s)&&contents(s).size()==27&&contents(s).stream().allMatch(c->present(c)&&c.getAmount()==ItemRegistry.REGISTRY.get(c.getId()).stackSize());}
    private boolean full(Container c) {int n=c.getSize()-36;if(n!=27)return false;for(int i=0;i<n;i++){var s=c.getItemStack(i);if(!present(s)||s.getAmount()<ItemRegistry.REGISTRY.get(s.getId()).stackSize())return false;}return true;}
    public static boolean matches(ItemStack s,String rule) {
        String[] p=rule.split("\\|");if(!named(s,p[0]))return false;if(p.length==1)return true;
        if(s.getDataComponents()==null)return false;
        var ench=s.getDataComponents().get(DataComponentTypes.STORED_ENCHANTMENTS);
        var e=EnchantmentRegistry.REGISTRY.get(p[1]);
        return ench!=null&&e!=null&&ench.getEnchantments().getOrDefault(e.id(),0)==Integer.parseInt(p[2]);
    }
}
