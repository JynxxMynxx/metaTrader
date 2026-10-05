package dev.zenith.trader.module;

import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import dev.zenith.trader.MetaTraderConfig;
import java.util.Comparator;
import java.util.List;
import static com.zenith.Globals.CACHE;
import static dev.zenith.trader.VillagerTraderPlugin.META_CONFIG;

/** Finds up to 28 nearest matching loaded containers inside the hall. */
public final class ContainerLocator {
    private ContainerLocator() {}

    public static List<BlockPos> find(String type) {
        return find(type, META_CONFIG.hallBounds);
    }

    public static List<BlockPos> find(String type, MetaTraderConfig.Region region) {
        var player = CACHE.getPlayerCache().getThePlayer().blockPos();
        return region.positions().stream()
            .filter(p -> World.isChunkLoadedBlockPos(p.x(), p.z()))
            .filter(p -> World.getBlock(p).name().equals(type))
            .sorted(Comparator.comparingDouble(p -> distance(p, player)))
            .limit(28).toList();
    }

    public static List<BlockPos> positions(MetaTraderConfig.Region region) {
        return region.positions();
    }

    /** Discover the nearest loaded table locally, including the lower sorting floors. */
    public static List<BlockPos> craftingTables() {
        var p = CACHE.getPlayerCache().getThePlayer().blockPos();
        return find("minecraft:crafting_table", new MetaTraderConfig.Region(p.add(-32, -16, -32), p.add(32, 16, 32)));
    }

    public static BlockPos nearest(List<BlockPos> positions) {
        var player = CACHE.getPlayerCache().getThePlayer().blockPos();
        return positions.stream().min(Comparator.comparingDouble(p -> distance(p, player))).orElse(null);
    }

    private static double distance(BlockPos a, BlockPos b) {
        double x = (double) a.x() - b.x();
        double y = (double) a.y() - b.y();
        double z = (double) a.z() - b.z();
        return x * x + y * y + z * z;
    }
}
