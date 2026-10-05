package dev.zenith.trader.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.*;
import com.zenith.feature.inventory.util.InventoryActionMacros;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import java.util.*;
import static com.zenith.Globals.*;
import static dev.zenith.trader.VillagerTraderPlugin.META_CONFIG;

/** Refills persistent supplies to exactly 64, checking acknowledged inventory. */
final class SupplyRestock {
    private final MetaTrader owner;
    private String item = "";
    private List<BlockPos> sources = List.of();
    private int cursor, wait, before = -1, stalled;
    private int confirmWait;
    private PathingRequestFuture path;
    private RequestFuture action = RequestFuture.immediateFuture(true);
    private boolean startup = true, raidReturn;
    SupplyRestock(MetaTrader owner) { this.owner = owner; }
    void reset() { item=""; path=null; action=RequestFuture.immediateFuture(true); startup=true; raidReturn=false; cursor=wait=stalled=confirmWait=0; before=-1; }
    void returnedFromRaid() { raidReturn=true; }
    boolean busy() { return !item.isEmpty() || startup || raidReturn; }
    static int count(String name) {
        var data=ItemRegistry.REGISTRY.get(name); if(data==null)return 0;
        int n=0; var inv=CACHE.getPlayerCache().getPlayerInventory();
        for(int i=9;i<=45;i++){var s=inv.get(i);if(s!=Container.EMPTY_STACK && s.getId()==data.id())n+=s.getAmount();}return n;
    }
    boolean tick() {
        if(!action.isCompleted())return false;
        if(!action.getNow()){owner.supplyFailure("Inventory refill action was rejected for "+item);return false;}
        if(confirmWait > 0) { confirmWait--; return false; }
        if(item.isEmpty()) {
            for(String name:List.of("minecraft:firework_rocket","minecraft:shulker_shell","minecraft:chest")) {
                boolean rocket=name.endsWith("rocket");
                boolean refill=rocket?(startup || raidReturn) && count(name)<(startup?64:16):count(name)<(startup?64:16);
                if(!refill)continue;
                item=name; cursor=wait=stalled=0;before=-1;path=null;
                sources=new ArrayList<>(rocket?META_CONFIG.rocketChests:META_CONFIG.shulkerIngredientsChests);
                sources.sort(Comparator.comparingDouble(p -> { var here=CACHE.getPlayerCache().getThePlayer().blockPos(); return Math.pow(p.x()-here.x(),2)+Math.pow(p.y()-here.y(),2)+Math.pow(p.z()-here.z(),2); }));
                if(sources.isEmpty() || sources.contains(BlockPos.ZERO)) { owner.supplyFailure("Configure supply chest for "+item); return false; }
                break;
            }
            if(item.isEmpty()){startup=false;raidReturn=false;return true;}
        }
        int now=count(item);
        var open=CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        if(now>=64) {
            if(open.getContainerId()!=0){close(open.getContainerId());return false;}
            item="";path=null;return false;
        }
        if(before>=0){if(now<=before && ++stalled>=3){owner.supplyFailure("No acknowledged refill progress for "+item);return false;}if(now>before)stalled=0;before=-1;}
        if(cursor>=sources.size()){owner.supplyFailure("Configured supply chests exhausted for "+item);return false;}
        if(path==null){if(open.getContainerId()!=0){close(open.getContainerId());return false;}var p=sources.get(cursor);path=BARITONE.rightClickBlock(p.x(),p.y(),p.z());wait=0;confirmWait=6;return false;}
        if(!path.isCompleted()){if(++wait>1200){cursor++;path=null;}return false;}
        if(open.getContainerId()==0){if(++wait>200){cursor++;path=null;}return false;}
        int id=ItemRegistry.REGISTRY.get(item).id();
        var actions = InventoryActionMacros.withdraw(open.getContainerId(),
            stack -> stack != Container.EMPTY_STACK && stack.getId() == id, 1);
        if(actions.isEmpty()){close(open.getContainerId());cursor++;path=null;return false;}
        var sequence=new ArrayList<>(actions);
        sequence.add(new CloseContainer(open.getContainerId()));
        before=now;confirmWait=5;submit(sequence);return false;
    }
    private void close(int id){submit(List.of(new CloseContainer(id)));}
    private void submit(List<InventoryAction> actions){action=INVENTORY.submit(InventoryActionRequest.builder().owner(owner).priority(com.zenith.feature.pathfinder.Baritone.getPriority()+100).actions(actions).build());}
}
