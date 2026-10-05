package dev.zenith.trader.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.*;
import com.zenith.feature.player.*;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.mc.item.ToolType;
import com.zenith.module.impl.KillAura;
import com.zenith.util.RequestFuture;
import dev.zenith.trader.flight.ElytraFlight;
import dev.zenith.trader.flight.FlightRoute;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.*;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static com.zenith.Globals.*;
import static dev.zenith.trader.VillagerTraderPlugin.META_CONFIG;

/**
 * One non-blocking raid trip shared by normal operation and the isolated tests.
 *
 * <p>HALL walks to the roof launch block, OUTBOUND glides down the straight line
 * to the raid farm landing block (no rockets), ARRIVE walks onto that block,
 * then the bottle/drink/combat/deposit stages run, LAUNCH walks back onto the
 * landing block and RETURN climbs (rockets only when needed), holds the cruise
 * height until the approach radius and lands on the hall roof landing block.
 */
final class RaidCycle {
    enum Stage { HALL, OUTBOUND, ARRIVE, BOTTLE, DRINK, SWORD, COMBAT, DEPOSIT, LAUNCH, RETURN, DONE }
    private final MetaTrader owner;
    private Stage stage = Stage.HALL;
    /** First and last stage of this run; the flight tests run only part of the cycle. */
    private Stage firstStage = Stage.HALL, lastStage = Stage.RETURN;
    private RequestFuture action = RequestFuture.immediateFuture(true);
    private com.zenith.feature.pathfinder.PathingRequestFuture path;
    private CompletableFuture<ElytraFlight.Result> flight;
    private long stageStarted = System.currentTimeMillis(), combatUntil;
    private boolean combatOwned;
    private boolean hostile, players, neutral, custom, aggressive, neutralAggressive, named, switchWeapon;
    private final List<EntityType> previousCustomTargets = new ArrayList<>();
    private int drinkTicks;
    private int bottlesBeforeDrink;
    private int bottlesBeforeRetrieval = -1;
    private boolean bottleRetrieved;
    private boolean totemOwned, previousAutoTotem;
    RaidCycle(MetaTrader owner) { this.owner = owner; }
    boolean active() { return stage != Stage.DONE; }
    void reset() { reset(Stage.HALL, Stage.RETURN); }
    /** Outbound only (walk to the roof, glide, land) or return only (take off at the farm, land on the roof). */
    void resetFlightTest(boolean home) { reset(home ? Stage.LAUNCH : Stage.HALL, home ? Stage.RETURN : Stage.ARRIVE); }
    void resetStandalone() { reset(Stage.BOTTLE, Stage.DEPOSIT); }
    private void reset(Stage first, Stage last) {
        MODULE.get(ElytraFlight.class).abort("raid cycle reset");
        restoreCombat();
        flight=null; path=null; action=RequestFuture.immediateFuture(true); drinkTicks=0;
        bottlesBeforeRetrieval=-1; bottleRetrieved=false;
        firstStage = first;
        lastStage = last;
        next(first);
    }
    void cancel() { MODULE.get(ElytraFlight.class).abort("raid cycle cancelled"); flight=null; restoreCombat(); stage=Stage.DONE; }
    private void next(Stage value) { stage=value; path=null; stageStarted=System.currentTimeMillis(); owner.diagnostic("raid",value); }
    /** Move on from a finished stage; true when that was the last stage of this run. */
    private boolean advance(Stage finished, Stage following) {
        if (finished == lastStage) { next(Stage.DONE); return true; }
        next(following);
        return false;
    }
    private void fail(String reason) { cancel(); owner.supplyFailure("Raid: "+reason); }
    boolean tick() {
        if (stage == Stage.DONE) return true;
        if (System.currentTimeMillis()-stageStarted > 20*60_000L) { fail("stage timed out: "+stage); return false; }
        if (!action.isCompleted()) return false;
        if (!action.getNow()) { fail("inventory action rejected"); return false; }
        switch(stage) {
            case HALL -> { if(walk(META_CONFIG.flightLaunch)) return advance(Stage.HALL, Stage.OUTBOUND); }
            case OUTBOUND -> { if(fly(true)) return advance(Stage.OUTBOUND, Stage.ARRIVE); }
            case ARRIVE -> {
                // Only a confirmed landing and arrival on the landing block unlocks bottle retrieval.
                if(walk(META_CONFIG.raidLanding)) return advance(Stage.ARRIVE, Stage.BOTTLE);
            }
            case BOTTLE -> {
                if(bottleRetrieved) {
                    // The bottle may merge into an existing stack or land in any
                    // player-inventory slot.  Retrieval is successful once the
                    // acknowledged count has increased, not only when it is
                    // exactly one larger in the hotbar.
                    int bottlesNow = SupplyRestock.count("minecraft:ominous_bottle");
                    if(bottlesNow < bottlesBeforeRetrieval+1){
                        owner.diagnostic("bottle-retrieval-wait", "before="+bottlesBeforeRetrieval, "now="+bottlesNow);
                        return false;
                    }
                    int bottle=findBottle();
                    if(bottle<0){fail("retrieved bottle is not level V");return false;}
                    var open=CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if(open.getContainerId()!=0) { submit(List.of(new CloseContainer(open.getContainerId()))); return false; }
                    bottlesBeforeDrink=SupplyRestock.count("minecraft:ominous_bottle");
                    equip(bottle); next(Stage.DRINK); return false;
                }
                if(bottlesBeforeRetrieval<0)bottlesBeforeRetrieval=SupplyRestock.count("minecraft:ominous_bottle");
                var screen=CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                if(path==null && screen.getContainerId()!=0){submit(List.of(new CloseContainer(screen.getContainerId())));return false;}
                var open=open(META_CONFIG.ominousBottleChest); if(open==null)return false;
                int top=open.getSize()-36, source=-1, dest=-1;
                for(int i=0;i<top;i++)if(levelFive(open.getItemStack(i))){source=i;break;}
                for(int i=top;i<open.getSize();i++)if(open.getItemStack(i)==Container.EMPTY_STACK){dest=i;break;}
                if(source<0 || dest<0){fail("no level V ominous bottle or no free inventory slot");return false;}
                int id=open.getContainerId();
                 submit(List.of(new ClickItem(id,source,ClickItemAction.LEFT_CLICK),new ClickItem(id,dest,ClickItemAction.RIGHT_CLICK),new ClickItem(id,source,ClickItemAction.LEFT_CLICK),new CloseContainer(id)));
                 bottleRetrieved=true;
                path=null;
            }
            case DRINK -> {
                if (drinkTicks >= 60) {
                    if(SupplyRestock.count("minecraft:ominous_bottle")>=bottlesBeforeDrink){fail("bottle consumption was not acknowledged");return false;}
                    combat(); next(Stage.SWORD); return false;
                }
                INPUTS.submit(InputRequest.builder().owner(owner).priority(12000).input(Input.builder()
                    .rightClick(true).hand(Hand.MAIN_HAND).clickTarget(ClickTarget.None.INSTANCE).clickRequiresRotation(false).build()).build())
                    .addInputExecutedListener(f -> { if(f.getNow())drinkTicks++; });
            }
            case SWORD -> {
                int sword=findSword();
                if(sword<0){fail("nosword");return false;}
                equip(sword); next(Stage.COMBAT);
                combatUntil=0;
            }
            case COMBAT -> {
                if(combatUntil==0){BARITONE.stop();combatUntil=System.currentTimeMillis()+330_000L;}
                owner.cleanupAmbientInventory();
                if(System.currentTimeMillis()>=combatUntil){
                    previousAutoTotem=CONFIG.client.extra.autoTotem.enabled;totemOwned=true;
                    CONFIG.client.extra.autoTotem.enabled=false;
                    MODULE.get(com.zenith.module.impl.AutoTotem.class).syncEnabledFromConfig();
                    next(Stage.DEPOSIT);
                }
            }
            case DEPOSIT -> {
                var offhand=CACHE.getPlayerCache().getPlayerInventory().get(45);
                if(!isTotem(offhand)) {
                    var inv=CACHE.getPlayerCache().getPlayerInventory();int free=-1;
                    for(int i=9;i<=44;i++)if(isTotem(inv.get(i))){free=i;break;}
                    if(free<0){fail("no totem available for offhand reserve");return false;}
                    var screen=CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if(screen.getContainerId()!=0){submit(List.of(new CloseContainer(screen.getContainerId())));path=null;return false;}
                    var moves=new ArrayList<InventoryAction>();
                    moves.add(new ClickItem(0,free,ClickItemAction.LEFT_CLICK));moves.add(new ClickItem(0,45,ClickItemAction.LEFT_CLICK));
                    if(offhand!=Container.EMPTY_STACK)moves.add(new ClickItem(0,free,ClickItemAction.LEFT_CLICK));
                    submit(moves);return false;
                }
                var open=CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                if(SupplyRestock.count("minecraft:totem_of_undying")==1) {
                    if(open.getContainerId()!=0){submit(List.of(new CloseContainer(open.getContainerId())));return false;}
                    restoreCombat();
                    return advance(Stage.DEPOSIT, Stage.LAUNCH);
                }
                open=open(META_CONFIG.raidFarmTotemOutputChest);if(open==null)return false;
                int top=open.getSize()-36, source=-1, dest=-1;
                for(int i=top;i<open.getSize();i++)if(isTotem(open.getItemStack(i))){source=i;break;}
                for(int i=0;i<top;i++)if(open.getItemStack(i)==Container.EMPTY_STACK){dest=i;break;}
                if(source<0 || dest<0){fail("totem output full or inaccessible inventory totem");return false;}
                submit(List.of(new ClickItem(open.getContainerId(),source,ClickItemAction.LEFT_CLICK),new ClickItem(open.getContainerId(),dest,ClickItemAction.LEFT_CLICK)));
            }
            case LAUNCH -> { if(walk(META_CONFIG.raidLanding)) return advance(Stage.LAUNCH, Stage.RETURN); }
            case RETURN -> {
                if(fly(false)){
                    // Only a full raid (not the return-flight test) earns the 40-minute Hero refresh.
                    if(firstStage==Stage.HALL){META_CONFIG.raidRefreshAt=System.currentTimeMillis()+2_400_000L; saveConfigAsync();}
                    next(Stage.DONE); return true;
                }
            }
            default -> { }
        }
        return false;
    }
    private boolean isTotem(ItemStack s){return s!=Container.EMPTY_STACK && s.getId()==ItemRegistry.TOTEM_OF_UNDYING.id();}
    static boolean levelFive(ItemStack s) {
        if(s==null || s==Container.EMPTY_STACK || s.getId()!=ItemRegistry.OMINOUS_BOTTLE.id())return false;
        Integer amplifier=s.getDataComponentsOrEmpty().get(DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER);
        return amplifier!=null && amplifier==4;
    }
    private int findBottle(){var inv=CACHE.getPlayerCache().getPlayerInventory();for(int i=9;i<=44;i++)if(levelFive(inv.get(i)))return i;return -1;}
    private int findSword(){
        var inv=CACHE.getPlayerCache().getPlayerInventory();
        for(int i=9;i<=44;i++){
            var stack=inv.get(i);if(stack==Container.EMPTY_STACK)continue;
            var item=ItemRegistry.REGISTRY.get(stack.getId());
            if(item!=null && item.toolTag()!=null && item.toolTag().type()==ToolType.SWORD)return i;
        }
        return -1;
    }
    private void equip(int slot){
        submit(InventoryHotbar.selectSlot(slot));
    }
    private void submit(List<InventoryAction> actions){action=INVENTORY.submit(InventoryActionRequest.builder().owner(owner).priority(12000).actions(actions).build());}
    private boolean walk(BlockPos p){
        if(p.equals(BlockPos.ZERO)){fail("flight position not configured");return false;}
        if(CACHE.getPlayerCache().getThePlayer().blockPos().equals(p)){BARITONE.stop();return true;}
        if(path==null || path.isCompleted())path=BARITONE.pathTo(p.x(),p.y(),p.z());return false;
    }

    /** Equip rockets, then start the outbound glide or the return climb and wait for a confirmed landing. */
    private boolean fly(boolean outbound) {
        if(!action.isCompleted())return false;
        if(!action.getNow()){fail("flight inventory preparation rejected");return false;}
        BlockPos launch = outbound ? META_CONFIG.flightLaunch : META_CONFIG.raidLanding;
        BlockPos landing = outbound ? META_CONFIG.raidLanding : META_CONFIG.hallLanding;
        if(launch.equals(BlockPos.ZERO) || landing.equals(BlockPos.ZERO)){fail("flight position not configured");return false;}
        if(flight==null) {
            var open=CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
            if(open.getContainerId()!=0){submit(List.of(new CloseContainer(open.getContainerId())));return false;}
            if(!rocketsInHand()){
                var inv=CACHE.getPlayerCache().getPlayerInventory();int slot=-1;
                for(int i=9;i<=44;i++)if(inv.get(i)!=Container.EMPTY_STACK && inv.get(i).getId()==ItemRegistry.FIREWORK_ROCKET.id()){slot=i;break;}
                if(slot>=0){equip(slot);return false;}
                // The glide needs no rockets; they are only an emergency reserve there.
                if(!outbound){fail("no rockets");return false;}
                owner.diagnostic("flight","no rockets in inventory; gliding without an emergency reserve");
            }
            BARITONE.stop();
            FlightRoute route = route(outbound);
            owner.diagnostic("flight", (outbound ? "outbound glide " : "return climb ") + launch + " -> " + landing + " | " + route);
            flight=MODULE.get(ElytraFlight.class).start(new ElytraFlight.Request(route, launch.x(), launch.y(), launch.z(),
                META_CONFIG.flightTimeoutSeconds,
                outbound ? META_CONFIG.flightMinimumElytraDurability : META_CONFIG.flightReturnMinimumElytraDurability,
                msg -> owner.diagnostic("flight", msg)));
            return false;
        }
        if(!flight.isDone())return false;
        ElytraFlight.Result result;
        try{result=flight.join();}
        catch(CompletionException e){flight=null;fail("flight failed: "+(e.getCause()!=null?e.getCause().getMessage():e.getMessage()));return false;}
        catch(Exception e){flight=null;fail("flight failed: "+e.getMessage());return false;}
        flight=null;
        owner.diagnostic("flight-result", String.format("landed %.2f %.2f %.2f, %.2f blocks from %s, rockets=%d, ticks=%d, touchdownSpeed=%.3f, approachY=%.1f, elytra=%d",
            result.x(), result.y(), result.z(), result.distanceToTarget(), landing, result.rockets(), result.ticks(),
            result.touchdownSpeed(), result.approachAltitude(), result.elytraDurability()));
        if(result.distanceToTarget() > META_CONFIG.flightLandingTolerance){
            fail(String.format("landed %.1f blocks from the landing point %s", result.distanceToTarget(), landing));
            return false;
        }
        return true;
    }

    /** Route for the outbound glide (launch block to raid landing) or the return climb (raid landing to hall landing). */
    static FlightRoute route(boolean outbound) {
        if (outbound) {
            BlockPos a = META_CONFIG.flightLaunch, b = META_CONFIG.raidLanding;
            return FlightRoute.glide(a.x() + 0.5, a.y(), a.z() + 0.5, b.x() + 0.5, b.y(), b.z() + 0.5);
        }
        BlockPos a = META_CONFIG.raidLanding, b = META_CONFIG.hallLanding;
        return FlightRoute.climb(a.x() + 0.5, a.y(), a.z() + 0.5, b.x() + 0.5, b.y(), b.z() + 0.5,
            META_CONFIG.flightCruiseY, META_CONFIG.flightApproachRadius);
    }

    private static boolean rocketsInHand() {
        for (var slot : new org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot[]{
            org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot.MAIN_HAND,
            org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot.OFF_HAND}) {
            var stack = CACHE.getPlayerCache().getEquipment(slot);
            if (stack != null && stack != Container.EMPTY_STACK && stack.getId() == ItemRegistry.FIREWORK_ROCKET.id()) return true;
        }
        return false;
    }
    private Container open(BlockPos p) {
        if(p.equals(BlockPos.ZERO)){fail("chest not configured");return null;}
        var open=CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        if(open.getContainerId()!=0)return open;
        if(path==null)path=BARITONE.rightClickBlock(p.x(),p.y(),p.z());
        return null;
    }
    private void combat(){
        var c=CONFIG.client.extra.killAura;
        hostile=c.targetHostileMobs;players=c.targetPlayers;neutral=c.targetNeutralMobs;custom=c.targetCustom;aggressive=c.onlyHostileAggressive;named=c.ignoreNamedMobs;
        neutralAggressive=c.onlyNeutralAggressive;switchWeapon=c.switchWeapon;
        previousCustomTargets.clear();previousCustomTargets.addAll(c.customTargets);
        c.customTargets.clear();
        for(var type:EntityType.values())if(type!=EntityType.PLAYER && type!=EntityType.ARMOR_STAND)c.customTargets.add(type);
        combatOwned=true;c.enabled=true;c.targetHostileMobs=true;c.targetPlayers=false;c.targetNeutralMobs=true;c.targetCustom=true;c.onlyHostileAggressive=false;c.onlyNeutralAggressive=false;c.ignoreNamedMobs=false;c.switchWeapon=false;
        MODULE.get(KillAura.class).syncEnabledFromConfig();
    }
    private void restoreCombat(){
        if(totemOwned){CONFIG.client.extra.autoTotem.enabled=previousAutoTotem;MODULE.get(com.zenith.module.impl.AutoTotem.class).syncEnabledFromConfig();totemOwned=false;}
        if(!combatOwned)return;var c=CONFIG.client.extra.killAura;c.enabled=false;c.targetHostileMobs=hostile;c.targetPlayers=players;c.targetNeutralMobs=neutral;c.targetCustom=custom;c.onlyHostileAggressive=aggressive;c.onlyNeutralAggressive=neutralAggressive;c.ignoreNamedMobs=named;c.switchWeapon=switchWeapon;
        c.customTargets.clear();c.customTargets.addAll(previousCustomTargets);
        combatOwned=false;MODULE.get(KillAura.class).syncEnabledFromConfig();
    }
}
