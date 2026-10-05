package dev.zenith.trader.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.event.client.ClientBotTick;
import com.zenith.module.api.Module;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.DropItem;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction;
import com.zenith.util.RequestFuture;
import dev.zenith.trader.MetaTraderConfig;
import dev.zenith.trader.VillagerTraderConfig;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static dev.zenith.trader.VillagerTraderPlugin.META_CONFIG;
import static com.zenith.Globals.saveConfigAsync;
import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.MODULE;
import static dev.zenith.trader.VillagerTraderPlugin.PLUGIN_CONFIG;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import dev.zenith.trader.MetaTraderConfig.Region;
import dev.zenith.trader.MetaTraderConfig.GroundTravelMode;

/**
 * Persistent cycle controller. Trading remains in VillagerTrader; this module
 * owns the higher-level cycle and records progress so a restart can resume.
 */
public class MetaTrader extends Module {
    private Phase phase = Phase.TRADING;
    private int stationIndex;
    private com.zenith.feature.pathfinder.PathingRequestFuture stationPathingFuture;
    private RequestFuture cleanupFuture = RequestFuture.rejected;
    private int testBaseline;
    private int testCursor;
    private int testWait;
    private int transferNoProgress;
    private boolean testOpened;
    private RequestFuture testAction = RequestFuture.rejected;
    private boolean originalAllowBreak;
    private boolean originalAllowPlace;
    private boolean worldEditOverride;
    private int environmentSnapshotTicks;
    private final SortingCycle sorting = new SortingCycle(this);
    private final SupplyRestock supplies = new SupplyRestock(this);
    private boolean raidTrip;
    private boolean supplyWork;
    private final java.util.LinkedHashMap<String,Integer> savedCompleted = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<String,Integer> savedPurchased = new java.util.LinkedHashMap<>();
    private boolean standalonePreviousEnabled;
    private final RaidCycle raid = new RaidCycle(this);
    private boolean startupRaid = true;
    private StringBuilder diagnosticSession;
    private long diagnosticLastTick;

    public enum Phase { TRADING, STACKABLE_SORT, NONSTACKABLE_SORT, COMPLETE }

    @Override
    public void onEnable() {
        var autoLog=com.zenith.Globals.CONFIG.client.extra.utility.actions.autoDisconnect;
        autoLog.enabled=true;
        autoLog.healthDisconnect=true;
        autoLog.health=10;
        autoLog.onTotemPop=true;
        autoLog.cancelAutoReconnect=true;
        MODULE.get(com.zenith.module.impl.AutoDisconnect.class).syncEnabledFromConfig();
        originalAllowBreak = com.zenith.Globals.CONFIG.client.extra.pathfinder.allowBreak;
        originalAllowPlace = com.zenith.Globals.CONFIG.client.extra.pathfinder.allowPlace;
        worldEditOverride = false;
        setWorldEditOverride(false);
        resetRuntime();
    }

    @Override
    public boolean enabledSetting() {
        return META_CONFIG.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            EventConsumer.of(ClientBotTick.Starting.class, e -> resetRuntime()),
            EventConsumer.of(ClientBotTick.Stopped.class, e -> resetRuntime()),
            EventConsumer.of(ClientBotTick.class, e -> tick())
            , EventConsumer.of(com.zenith.event.module.TotemPopEvent.class, e -> {
                var autoLog=com.zenith.Globals.CONFIG.client.extra.utility.actions.autoDisconnect;
                if(autoLog.enabled && autoLog.onTotemPop && e.entityId()==CACHE.getPlayerCache().getEntityId())
                    com.zenith.Proxy.getInstance().disconnect("[AutoDisconnect] MetaTrader: first totem popped");
            })
        );
    }

    @Override
    public void onDisable() {
        raid.cancel();
        BARITONE.stop();
        sorting.resetRuntime();
        endProtectedWorldEdit();
        com.zenith.Globals.CONFIG.client.extra.pathfinder.allowBreak = originalAllowBreak;
        com.zenith.Globals.CONFIG.client.extra.pathfinder.allowPlace = originalAllowPlace;
        saveConfigAsync();
    }

    /**
     * MetaTrader's normal safety posture is no breaking or placing. This is
     * deliberately separate from the normal pathfinder settings so a user
     * cannot accidentally leave the hall destructible after a work phase.
     */
    private void setWorldEditOverride(boolean enabled) {
        worldEditOverride = enabled;
        com.zenith.Globals.CONFIG.client.extra.pathfinder.allowBreak = enabled;
        com.zenith.Globals.CONFIG.client.extra.pathfinder.allowPlace = enabled;
    }

    public void beginProtectedWorldEdit() {
        if (!worldEditOverride) setWorldEditOverride(true);
    }

    public void endProtectedWorldEdit() {
        if (worldEditOverride) setWorldEditOverride(false);
    }

    private void resetRuntime() {
        // A bot-tick restart (reconnect or a player taking over) restarts a flight test from its own
        // first stage instead of turning it into a full raid.
        if (META_CONFIG.testMode.equals("flightToRaid") || META_CONFIG.testMode.equals("flightToHall"))
            raid.resetFlightTest(META_CONFIG.testMode.equals("flightToHall"));
        else if (META_CONFIG.testMode.equals("raidStandalone")) raid.resetStandalone();
        else raid.reset();
        startupRaid = true;
        raidTrip = false;
        supplies.reset();
        supplyWork=false;
        sorting.resetRuntime();
        try {
            phase = Phase.valueOf(META_CONFIG.cyclePhase);
        } catch (IllegalArgumentException e) {
            phase = Phase.TRADING;
        }
        stationIndex = META_CONFIG.cycleStationIndex;
        stationPathingFuture = null;
    }

    private void tick() {
        sessionLog("tick mode=" + META_CONFIG.testMode + " phase=" + phase + " pos=" + CACHE.getPlayerCache().getThePlayer().blockPos());
        emitEnvironmentSnapshot();
        if (!META_CONFIG.testMode.isEmpty()) {
            processTest();
            return;
        }
        if (!META_CONFIG.enabled) return;
        if (raidTrip) { processRaidRefresh(); return; }
        if (supplyWork || MODULE.get(VillagerTrader.class).inventoryIdle() && sorting.inventoryIdle()) {
            supplyWork = true;
            if (!supplies.tick()) return;
            supplyWork = false;
        }
        if (phase != Phase.TRADING && (SupplyRestock.count("minecraft:shulker_shell") < 16 || SupplyRestock.count("minecraft:chest") < 16)) return;
        if (phase == Phase.TRADING && META_CONFIG.raidFarmEnabled && (startupRaid || raidTrip || needsHeroRefresh())) {
            processRaidRefresh();
            return;
        }
        if (MODULE.get(VillagerTrader.class).inventoryIdle() && sorting.inventoryIdle()) cleanupAmbientInventory();
        if (phase == Phase.TRADING && !atHall()) {
            BARITONE.pathTo(META_CONFIG.hallReturn.x(), META_CONFIG.hallReturn.y(), META_CONFIG.hallReturn.z());
            return;
        }
        if (phase == Phase.TRADING && allTradesComplete()) {
            PLUGIN_CONFIG.enabled = false;
            MODULE.get(VillagerTrader.class).syncEnabledFromConfig();
            sorting.start(false);
            transition(Phase.STACKABLE_SORT, 0);
        }
        switch (phase) {
            case STACKABLE_SORT -> { if (sorting.tick(false)) { sorting.start(true); transition(Phase.NONSTACKABLE_SORT, 0); } }
            case NONSTACKABLE_SORT -> { if (sorting.tick(true)) transition(Phase.COMPLETE, 0); }
            case COMPLETE -> beginNextCycle();
            default -> { }
        }
    }

    private void emitEnvironmentSnapshot() {
        if (!META_CONFIG.loggingEnabled || !META_CONFIG.environmentDiagnostics) return;
        if (++environmentSnapshotTicks < Math.max(1, META_CONFIG.environmentSnapshotIntervalTicks)) return;
        environmentSnapshotTicks = 0;
        var player = CACHE.getPlayerCache().getThePlayer();
        var pos = player.blockPos();
        var open = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        var goal = BARITONE.currentGoal();
        info("META_ENV mode={} phase={} test={} pos=({}, {}, {}) alive={} vehicle={} vehicleId={} openContainer={} openType={} goal={} hall={} raidFarm={} allowBreak={} allowPlace={} inventory={}",
            META_CONFIG.environmentMode, phase, META_CONFIG.testMode, pos.x(), pos.y(), pos.z(), player.isAlive(),
            player.isInVehicle(), player.getVehicleId(), open.getContainerId(), open.getType(), goal,
            META_CONFIG.hallBounds.contains(pos), META_CONFIG.raidFarmBounds.contains(pos),
            com.zenith.Globals.CONFIG.client.extra.pathfinder.allowBreak,
            com.zenith.Globals.CONFIG.client.extra.pathfinder.allowPlace,
            inventorySummary());
    }

    private String inventorySummary() {
        var counts = new java.util.LinkedHashMap<String, Integer>();
        var inventory = CACHE.getPlayerCache().getPlayerInventory();
        for (int slot = 9; slot <= 45; slot++) {
            var stack = inventory.get(slot);
            if (stack == com.zenith.cache.data.inventory.Container.EMPTY_STACK) continue;
            var item = com.zenith.mc.item.ItemRegistry.REGISTRY.get(stack.getId());
            if (item != null) counts.merge(item.name(), stack.getAmount(), Integer::sum);
        }
        return counts.toString();
    }

    public void diagnostic(String event, Object... values) {
        if (!META_CONFIG.loggingEnabled) return;
        sessionLog("event=" + event + " values=" + java.util.Arrays.toString(values));
        info("META_EVENT {} {}", event, java.util.Arrays.toString(values));
    }

    private void sessionLog(String line) {
        if (!META_CONFIG.loggingEnabled || diagnosticSession == null) return;
        if (System.currentTimeMillis() - diagnosticLastTick < 250 && line.startsWith("tick")) return;
        diagnosticLastTick = System.currentTimeMillis();
        diagnosticSession.append(Instant.now()).append(' ').append(line).append('\n');
        if (diagnosticSession.length() > 2_000_000) diagnosticSession.delete(0, diagnosticSession.length() - 1_500_000);
    }

    private void beginDiagnosticSession(String mode) {
        diagnosticSession = new StringBuilder("MetaTrader diagnostic session\nmode=").append(mode).append('\n');
        diagnosticLastTick = 0;
    }

    private void publishDiagnosticSession(String result) {
        if (diagnosticSession == null) return;
        diagnosticSession.append(Instant.now()).append(" result=").append(result).append('\n');
        var data = diagnosticSession.toString().getBytes(StandardCharsets.UTF_8);
        discordNotification(com.zenith.discord.Embed.builder().title("MetaTrader diagnostic session")
            .description("Result: " + result).fileAttachment(new com.zenith.discord.Embed.FileAttachment("metatrader-" + META_CONFIG.lastTestStartedAt + ".txt", data)));
        diagnosticSession = null;
    }

    private void processTest() {
        var mode = META_CONFIG.testMode;
        if (mode.equals("stackableSortingCycle") || mode.equals("nonstackableSortingCycle")) {
            if (supplyWork || sorting.inventoryIdle()) {
                supplyWork=true;
                if (!supplies.tick()) return;
                supplyWork=false;
            }
            if (SupplyRestock.count("minecraft:shulker_shell") < 16 || SupplyRestock.count("minecraft:chest") < 16) return;
            if (sorting.tick(mode.equals("nonstackableSortingCycle"))) finishTest();
            else if (!META_CONFIG.sortingError.isEmpty()) failTest(META_CONFIG.sortingError);
            return;
        }
        if (mode.equals("metaTraderStandalone")) {
            if (allTradesComplete()) {
                PLUGIN_CONFIG.enabled = false;
                MODULE.get(VillagerTrader.class).syncEnabledFromConfig();
                finishTest();
            }
            return;
        }
        if (mode.equals("flightToRaid") || mode.equals("flightToHall")) {
            if (raid.tick()) finishTest();
            return;
        }
        if (mode.equals("theresNoPlaceLikeHome")) {
            var p = META_CONFIG.hallReturn;
            if (META_CONFIG.hallBounds.contains(CACHE.getPlayerCache().getThePlayer().blockPos())) finishTest();
            else BARITONE.pathTo(p.x(), p.y(), p.z());
            return;
        }
        if (mode.equals("raid") || mode.equals("raidStandalone")) {
            if (raid.tick()) finishTest();
            return;
        }
        if (mode.equals("inputRetrieval") || mode.equals("outputDeposit") || mode.equals("outputDepositProcessed")) {
            processTransferTest(mode.equals("inputRetrieval"));
            return;
        }
        if (mode.equals("ShulkerCrafting")) {
            if (!META_CONFIG.testRegion.contains(CACHE.getPlayerCache().getThePlayer().blockPos())) {
                var p = META_CONFIG.testRegion.first;
                BARITONE.pathTo(p.x(), p.y(), p.z());
            } else processCraftingTest();
            return;
        }
    }

    private void processStationInventoryTest() {
        if (!testAction.isCompleted()) return;
        var open = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        if (open.getContainerId() == 0) {
            var p = META_CONFIG.testRegion.first;
            if (stationPathingFuture == null || stationPathingFuture.isCompleted())
                stationPathingFuture = BARITONE.rightClickBlock(p.x(), p.y(), p.z());
            return;
        }
        int top = open.getSize() - 36;
        var actions = new java.util.ArrayList<com.zenith.feature.inventory.actions.InventoryAction>();
        for (int slot = 0; slot < top; slot++) {
            var stack = open.getItemStack(slot);
            if (stack != com.zenith.cache.data.inventory.Container.EMPTY_STACK)
                actions.add(new com.zenith.feature.inventory.actions.ShiftClick(open.getContainerId(), slot,
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction.LEFT_CLICK));
        }
        actions.add(new com.zenith.feature.inventory.actions.CloseContainer(open.getContainerId()));
        testAction = com.zenith.Globals.INVENTORY.submit(InventoryActionRequest.builder().owner(this)
            .priority(com.zenith.feature.pathfinder.Baritone.getPriority() + 100).actions(actions).build());
        if (actions.size() <= 1) finishTest();
        else {
            META_CONFIG.lastTestResult = "running:stackableShulking:items-withdrawn";
            saveConfigAsync();
        }
    }

    private void processCraftingTest() {
        int target = META_CONFIG.testCount;
        int current = testItemCount("minecraft:shulker_box");
        if (current >= target) { finishTest(); return; }
        var open = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        if (open.getContainerId() == 0) {
            var p = META_CONFIG.testRegion.first;
            if (stationPathingFuture == null || stationPathingFuture.isCompleted())
                stationPathingFuture = BARITONE.rightClickBlock(p.x(), p.y(), p.z());
            return;
        }
        if (open.getType() != org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType.CRAFTING) {
            failTest("configured crafting station did not open a crafting table");
            return;
        }
        if (!testAction.isCompleted()) return;
        int id = open.getContainerId();
        var actions = new java.util.ArrayList<com.zenith.feature.inventory.actions.InventoryAction>();
        actions.add(new com.zenith.feature.inventory.actions.PlaceRecipe(id, "minecraft:shulker_box", true));
        actions.add(new com.zenith.feature.inventory.actions.ShiftClick(id, 0,
            org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction.LEFT_CLICK));
        testAction = com.zenith.Globals.INVENTORY.submit(InventoryActionRequest.builder().owner(this)
            .priority(com.zenith.feature.pathfinder.Baritone.getPriority() + 100).actions(actions).build());
    }

    private void finishTest() {
        String finishedMode = META_CONFIG.testMode;
        raid.cancel();
        raidTrip=false;
        supplyWork=false;
        restoreTradeProgress();
        if (META_CONFIG.testMode.equals("metaTraderStandalone") || META_CONFIG.testMode.endsWith("SortingCycle")) META_CONFIG.enabled = false;
        sorting.resetRuntime();
        info("MetaTrader test completed: {}", META_CONFIG.testMode);
        META_CONFIG.lastTestResult = "completed:" + META_CONFIG.testMode;
        META_CONFIG.lastTestFinishedAt = System.currentTimeMillis();
        META_CONFIG.testMode = "";
        saveConfigAsync();
        publishDiagnosticSession("completed:" + finishedMode);
    }

    private void failTest(String reason) {
        String failedMode = META_CONFIG.testMode;
        raid.cancel();
        raidTrip=false;
        supplyWork=false;
        restoreTradeProgress();
        META_CONFIG.enabled = false;
        sorting.resetRuntime();
        error("MetaTrader test failed ({}): {}", META_CONFIG.testMode, reason);
        META_CONFIG.lastTestResult = "failed:" + META_CONFIG.testMode + ":" + reason;
        META_CONFIG.lastTestFinishedAt = System.currentTimeMillis();
        META_CONFIG.testMode = "";
        saveConfigAsync();
        publishDiagnosticSession("failed:" + failedMode + ":" + reason);
    }

    private boolean needsHeroRefresh() {
        return System.currentTimeMillis() >= META_CONFIG.raidRefreshAt;
    }

    private void processRaidRefresh() {
        if (!raidTrip) {
            PLUGIN_CONFIG.enabled=false;
            MODULE.get(VillagerTrader.class).syncEnabledFromConfig();
        }
        if (!raidTrip) raid.reset();
        raidTrip = true;
        if (raid.tick()) {
            raidTrip=false; startupRaid=false; supplies.returnedFromRaid();
            PLUGIN_CONFIG.enabled=true;
            MODULE.get(VillagerTrader.class).syncEnabledFromConfig();
        }
    }

    private void processBoatFallbackTravel(com.zenith.mc.block.BlockPos player) {
        // ZenithProxy currently exposes vehicle state but not reliable boat
        // boarding and steering. Walk to the configured station and pause for
        // the available vehicle-control layer to perform the water leg.
        if (!META_CONFIG.hallBoatPark.contains(player)) {
            var p = META_CONFIG.hallBoatPark.first;
            BARITONE.pathTo(p.x(), p.y(), p.z());
            return;
        }
        if (!CACHE.getPlayerCache().getThePlayer().isInVehicle()) {
            processGroundRoute(player, false);
            return;
        }
        if (!META_CONFIG.raidFarmBoatPark.contains(player)) {
            var p = META_CONFIG.raidFarmBoatPark.first;
            BARITONE.pathTo(p.x(), p.y(), p.z());
            return;
        }
        var p = META_CONFIG.raidFarmBounds.first;
        if (!META_CONFIG.raidFarmBounds.contains(player)) BARITONE.pathTo(p.x(), p.y(), p.z());
    }

    private void processGroundRoute(com.zenith.mc.block.BlockPos player, boolean returning) {
        GroundTravelMode mode = selectedGroundMode();
        if (mode == GroundTravelMode.DIRECT_WALK) {
            var destination = META_CONFIG.raidFarmBoatPark.first;
            BARITONE.pathTo(destination.x(), destination.y(), destination.z());
            return;
        }
        java.util.ArrayList<Region> route = returning
            ? (mode == GroundTravelMode.SOUL_SPEED ? META_CONFIG.raidToHallSoulRoute : META_CONFIG.raidToHallBlueIceRoute)
            : (mode == GroundTravelMode.SOUL_SPEED ? META_CONFIG.hallToRaidSoulRoute : META_CONFIG.hallToRaidBlueIceRoute);
        if (!route.isEmpty()) {
            for (Region segment : route) {
                if (!segment.contains(player)) {
                    var target = segment.first;
                    BARITONE.pathTo(target.x(), target.y(), target.z());
                    return;
                }
            }
        }
        var destination = returning ? META_CONFIG.hallBoatPark.first : META_CONFIG.raidFarmBoatPark.first;
        BARITONE.pathTo(destination.x(), destination.y(), destination.z());
    }

    private GroundTravelMode selectedGroundMode() {
        if (META_CONFIG.groundTravelMode != GroundTravelMode.AUTO) return META_CONFIG.groundTravelMode;
        var boots = CACHE.getPlayerCache().getEquipment(org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot.BOOTS);
        return boots != null && boots != com.zenith.cache.data.inventory.Container.EMPTY_STACK
            ? GroundTravelMode.SOUL_SPEED : GroundTravelMode.BLUE_ICE;
    }

    private boolean atHall() {
        return META_CONFIG.hallBounds.contains(CACHE.getPlayerCache().getThePlayer().blockPos());
    }

    private boolean allTradesComplete() {
        var active = VillagerTrader.activeTrades().entrySet().stream().filter(e -> e.getValue().enabled).toList();
        return !active.isEmpty() && active.stream().allMatch(e -> META_CONFIG.completedTrades.containsKey(e.getKey()));
    }


    public boolean isSorting() {
        if (META_CONFIG.testMode.equals("metaTraderStandalone")) return false;
        return phase != Phase.TRADING;
    }

    public boolean controlsTravel() {
        if (supplyWork) return true;
        if (!META_CONFIG.testMode.isEmpty() && !META_CONFIG.testMode.equals("metaTraderStandalone")) return true;
        if (META_CONFIG.testMode.equals("metaTraderStandalone")) return false;
        return isSorting() || (META_CONFIG.raidFarmEnabled && (startupRaid || raidTrip || needsHeroRefresh())) || !atHall();
    }

    public FlightProfile flightProfile(boolean returning) {
        var pad = returning ? META_CONFIG.hallHeliPad : META_CONFIG.raidFarmHeliPad;
        return new FlightProfile(pad, META_CONFIG.hallFlightAltitude,
            META_CONFIG.flightDescentStartFraction, META_CONFIG.flightArrivalRadius,
            returning, META_CONFIG.flightBridgeEnabled);
    }

    public record FlightProfile(com.zenith.mc.block.BlockPos pad, int altitude, double descentStartFraction,
                                int arrivalRadius, boolean returning, boolean bridgeEnabled) { }

    private boolean samePosition(com.zenith.mc.block.BlockPos a, com.zenith.mc.block.BlockPos b) {
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z();
    }

    public boolean itemAllowed(String itemName, String activeInput1, String activeInput2, String activeOutput) {
        return META_CONFIG.whitelistItems.contains(itemName)
            || itemName.endsWith("_axe") || itemName.endsWith("_pickaxe") || itemName.endsWith("_sword") || itemName.endsWith("shulker_box")
            || java.util.List.of("minecraft:shulker_shell", "minecraft:chest", "minecraft:firework_rocket", "minecraft:glass_pane", "minecraft:emerald", "minecraft:ominous_bottle", "minecraft:totem_of_undying").contains(itemName)
            || itemName.equals(activeInput1)
            || itemName.equals(activeInput2)
            || itemName.equals(activeOutput);
    }

    public boolean inventoryCleanupEnabled() {
        return META_CONFIG.inventoryCleanupEnabled;
    }

    public void startTest(String mode, String item, String container, int count, Region region) {
        if (mode.equals("metaTraderStandalone") && !META_CONFIG.testMode.isEmpty()) {
            cancelTest();
        }
        if (mode.equals("metaTraderStandalone") && PLUGIN_CONFIG.testTrades.values().stream().noneMatch(t -> t.enabled)) throw new IllegalArgumentException("Configure enabled test trades first");
        supplies.reset();
        supplyWork=false;
        if (!META_CONFIG.testMode.isEmpty()) throw new IllegalStateException("A test is already running");
        if (mode.isEmpty()) throw new IllegalArgumentException("Test mode cannot be empty");
        PLUGIN_CONFIG.enabled = false;
        MODULE.get(VillagerTrader.class).syncEnabledFromConfig();
        BARITONE.stop();
        META_CONFIG.testMode = mode;
        beginDiagnosticSession(mode);
        META_CONFIG.lastTestResult = "running:" + mode;
        META_CONFIG.lastTestStartedAt = System.currentTimeMillis();
        META_CONFIG.testItem = item;
        META_CONFIG.testContainer = container;
        META_CONFIG.testCount = count;
        var p = CACHE.getPlayerCache().getThePlayer().blockPos();
        META_CONFIG.testRegion = region != null ? region : new Region(p.add(-16, -8, -16), p.add(16, 8, 16));
        testBaseline = item.isEmpty() ? 0 : testItemCount(item);
        testCursor = 0;
        testWait = 0;
        transferNoProgress = 0;
        testOpened = false;
        stationPathingFuture = null;
        testAction = RequestFuture.rejected;
        enable();
        if(mode.equals("raid")){raid.reset();raidTrip=false;}
        if(mode.equals("raidStandalone")){raid.resetStandalone();raidTrip=false;}
        if(mode.equals("flightToRaid") || mode.equals("flightToHall")){raid.resetFlightTest(mode.equals("flightToHall"));raidTrip=false;}
        if (mode.equals("stackableSortingCycle") || mode.equals("nonstackableSortingCycle")) sorting.start(mode.equals("nonstackableSortingCycle"));
        if (mode.equals("metaTraderStandalone")) {
            standalonePreviousEnabled = META_CONFIG.enabled;
            savedCompleted.clear(); savedCompleted.putAll(META_CONFIG.completedTrades);
            savedPurchased.clear(); savedPurchased.putAll(META_CONFIG.purchasedOutputCounts);
            resetCycle();
            META_CONFIG.enabled = true;
             PLUGIN_CONFIG.enabled = true;
             MODULE.get(VillagerTrader.class).onTradeListChange();
            MODULE.get(VillagerTrader.class).syncEnabledFromConfig();
        }
        saveConfigAsync();
    }

    public void cancelTest() {
        raid.cancel();
        raidTrip=false;
        supplyWork=false;
        if (META_CONFIG.testMode.isEmpty()) return;
        String cancelledMode = META_CONFIG.testMode;
        restoreTradeProgress();
        META_CONFIG.enabled = false;
        BARITONE.stop();
        sorting.resetRuntime();
        PLUGIN_CONFIG.enabled = false;
        MODULE.get(VillagerTrader.class).syncEnabledFromConfig();
        testAction = RequestFuture.rejected;
        stationPathingFuture = null;
        META_CONFIG.lastTestResult = "cancelled:" + META_CONFIG.testMode;
        META_CONFIG.lastTestFinishedAt = System.currentTimeMillis();
        META_CONFIG.testMode = "";
        MODULE.get(VillagerTrader.class).onTradeListChange();
        saveConfigAsync();
        publishDiagnosticSession("cancelled:" + cancelledMode);
    }

    private int testItemCount(String name) {
        int result = 0;
        var item = com.zenith.mc.item.ItemRegistry.REGISTRY.get(name);
        if (item == null) return 0;
        for (var stack : CACHE.getPlayerCache().getPlayerInventory())
            if (stack != com.zenith.cache.data.inventory.Container.EMPTY_STACK && stack.getId() == item.id()) result += stack.getAmount();
        return result;
    }

    private void processTransferTest(boolean retrieval) {
        if (!testAction.isCompleted()) return;
        int progress = retrieval ? testItemCount(META_CONFIG.testItem) - testBaseline : testBaseline - testItemCount(META_CONFIG.testItem);
        if (progress >= META_CONFIG.testCount) { finishTest(); return; }
        var targets = ContainerLocator.find(META_CONFIG.testContainer, META_CONFIG.testRegion);
        if (targets.isEmpty()) { failTest("no matching loaded containers in test region"); return; }
        if (stationPathingFuture == null) {
            var p = targets.get(testCursor % targets.size());
            stationPathingFuture = BARITONE.rightClickBlock(p.x(), p.y(), p.z());
            testWait = 0;
            return;
        }
        if (!stationPathingFuture.isCompleted()) return;
        var open = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        if (open.getContainerId() == 0) {
            if (++testWait > 100) { stationPathingFuture = null; testCursor++; }
            return;
        }
        int top = open.getSize() - 36;
        int itemId = com.zenith.mc.item.ItemRegistry.REGISTRY.get(META_CONFIG.testItem).id();
        int source = -1;
        for (int slot = retrieval ? 0 : top; slot < (retrieval ? top : open.getSize()); slot++) {
            var stack = open.getItemStack(slot);
            if (stack != com.zenith.cache.data.inventory.Container.EMPTY_STACK && stack.getId() == itemId) { source = slot; break; }
        }
        int destination = -1;
        for (int slot = retrieval ? top : 0; slot < (retrieval ? open.getSize() : top); slot++) {
            var stack = open.getItemStack(slot);
            if (stack == com.zenith.cache.data.inventory.Container.EMPTY_STACK ||
                (stack.getId() == itemId && stack.getAmount() < com.zenith.mc.item.ItemRegistry.REGISTRY.get(itemId).stackSize())) { destination = slot; break; }
        }
        var actions = new java.util.ArrayList<com.zenith.feature.inventory.actions.InventoryAction>();
        int id = open.getContainerId();
        if (source >= 0 && destination >= 0) {
            // Move exactly one item, then verify the acknowledged inventory delta.
            actions.add(new com.zenith.feature.inventory.actions.ClickItem(id, source, org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction.LEFT_CLICK));
            actions.add(new com.zenith.feature.inventory.actions.ClickItem(id, destination, org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction.RIGHT_CLICK));
            actions.add(new com.zenith.feature.inventory.actions.ClickItem(id, source, org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction.LEFT_CLICK));
            transferNoProgress = 0;
        } else {
            actions.add(new com.zenith.feature.inventory.actions.CloseContainer(id));
            stationPathingFuture = null;
            testCursor++;
            if (++transferNoProgress >= targets.size()) {
                failTest(retrieval ? "insufficient items in configured chests" : "insufficient capacity in configured chests");
                return;
            }
        }
        testAction = com.zenith.Globals.INVENTORY.submit(InventoryActionRequest.builder().owner(this)
            .priority(com.zenith.feature.pathfinder.Baritone.getPriority() + 100).actions(actions).build());
    }

    public RequestFuture cleanupInventory(VillagerTraderConfig.Trade trade) {
        if (!inventoryCleanupEnabled()) return RequestFuture.rejected;
        if (!cleanupFuture.isCompleted()) return cleanupFuture;
        var actions = new java.util.ArrayList<com.zenith.feature.inventory.actions.InventoryAction>();
        var inventory = CACHE.getPlayerCache().getPlayerInventory();
        for (int slot = 9; slot <= 44; slot++) {
            var stack = inventory.get(slot);
            if (stack == com.zenith.cache.data.inventory.Container.EMPTY_STACK) continue;
            var item = com.zenith.mc.item.ItemRegistry.REGISTRY.get(stack.getId());
            if (item == null || !itemAllowed(item.name(), trade.inputItem1, trade.inputItem2, trade.outputItem))
                actions.add(new DropItem(slot, DropItemAction.DROP_SELECTED_STACK));
        }
        if (actions.isEmpty()) return RequestFuture.immediateFuture(true);
        cleanupFuture = com.zenith.Globals.INVENTORY.submit(InventoryActionRequest.builder()
            .owner(this).priority(com.zenith.feature.pathfinder.Baritone.getPriority() + 100).actions(actions).build());
        return cleanupFuture;
    }

    void cleanupAmbientInventory() {
        if (!inventoryCleanupEnabled() || CACHE.getPlayerCache().getInventoryCache().getOpenContainerId()!=0 || !cleanupFuture.isCompleted()) return;
        var allowed=new java.util.HashSet<String>();
        for(var trade: VillagerTrader.activeTrades().values()){allowed.add(trade.inputItem1);allowed.add(trade.inputItem2);allowed.add(trade.outputItem);}
        var actions=new java.util.ArrayList<com.zenith.feature.inventory.actions.InventoryAction>();
        var inv=CACHE.getPlayerCache().getPlayerInventory();
        for(int slot=9;slot<=44;slot++){var stack=inv.get(slot);if(stack==com.zenith.cache.data.inventory.Container.EMPTY_STACK)continue;var item=com.zenith.mc.item.ItemRegistry.REGISTRY.get(stack.getId());if(item!=null && !allowed.contains(item.name()) && !itemAllowed(item.name(),"","",""))actions.add(new DropItem(slot,DropItemAction.DROP_SELECTED_STACK));}
        if(!actions.isEmpty())cleanupFuture=com.zenith.Globals.INVENTORY.submit(InventoryActionRequest.builder().owner(this).priority(9000).actions(actions).build());
    }

    private void transition(Phase next, int nextIndex) {
        phase = next;
        stationIndex = nextIndex;
        META_CONFIG.cyclePhase = next.name();
        META_CONFIG.cycleStationIndex = nextIndex;
        saveConfigAsync();
    }

    private void beginNextCycle() {
        META_CONFIG.completedTrades.clear();
        META_CONFIG.purchasedOutputCounts.clear();
        PLUGIN_CONFIG.enabled = true;
        MODULE.get(VillagerTrader.class).syncEnabledFromConfig();
        transition(Phase.TRADING, 0);
    }

    public void markTradeComplete(String id, int purchasedOutput) {
        META_CONFIG.completedTrades.put(id, 1);
        META_CONFIG.purchasedOutputCounts.put(id, purchasedOutput);
        saveConfigAsync();
    }

    public void resetCycle() {
        sorting.resetRuntime();
        META_CONFIG.sortingStage = "LEFTOVERS";
        META_CONFIG.sortingCursor = 0;
        META_CONFIG.sortingRule = "";
        META_CONFIG.sortingError = "";
        META_CONFIG.sortingReplacement = null;
        META_CONFIG.completedTrades.clear();
        META_CONFIG.purchasedOutputCounts.clear();
        phase = Phase.TRADING;
        stationIndex = 0;
        META_CONFIG.cyclePhase = phase.name();
        META_CONFIG.cycleStationIndex = 0;
        saveConfigAsync();
    }

    void supplyFailure(String reason) {
        if (!META_CONFIG.testMode.isEmpty()) { failTest(reason); }
        error("MetaTrader supply refill paused: {}", reason);
        META_CONFIG.sortingError = reason;
        META_CONFIG.enabled = false;
        PLUGIN_CONFIG.enabled = false;
        MODULE.get(VillagerTrader.class).syncEnabledFromConfig();
        saveConfigAsync();
    }

    private void restoreTradeProgress() {
        if (!META_CONFIG.testMode.equals("metaTraderStandalone")) return;
        META_CONFIG.completedTrades.clear(); META_CONFIG.completedTrades.putAll(savedCompleted);
        META_CONFIG.purchasedOutputCounts.clear(); META_CONFIG.purchasedOutputCounts.putAll(savedPurchased);
    }
}
