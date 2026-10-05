package dev.zenith.trader.module;

import com.zenith.feature.pathfinder.process.InteractWithProcess;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemData;
import static com.zenith.Globals.*;

/** Runs interaction inputs only: returned pathing commands are never submitted. */
final class StationaryWorldEdit {
    private InteractWithProcess.InteractTarget target;
    private PathingRequestFuture future;
    private BlockPos standing;
    private int ticks;

    PathingRequestFuture start(BlockPos pos, ItemData placeItem) {
        BARITONE.stop();
        standing = CACHE.getPlayerCache().getThePlayer().blockPos();
        target = placeItem == null ? new InteractWithProcess.BreakBlock(pos.x(),pos.y(),pos.z(),false)
            : new InteractWithProcess.PlaceBlock(pos.x(),pos.y(),pos.z(),placeItem);
        future = new PathingRequestFuture(); ticks = 0;
        return future;
    }

    void tick() {
        if (target == null) return;
        if (!standing.equals(CACHE.getPlayerCache().getThePlayer().blockPos()) || ++ticks > 600) {
            future.complete(false); target = null; return;
        }
        // Executes rotation/click inputs, but never passes any movement goal to Baritone.
        target.pathingCommand();
        if (target.succeeded()) { future.complete(true); target = null; }
    }
    void cancel() { if(future!=null&&!future.isCompleted())future.complete(false);target=null; }
}
