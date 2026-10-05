package dev.zenith.trader.flight;

import com.zenith.feature.player.World;

/** Flight terrain backed by ZenithProxy's chunk cache, using real collision shapes. */
public final class ZenithFlightTerrain implements FlightTerrain {
    public static final ZenithFlightTerrain INSTANCE = new ZenithFlightTerrain();

    private ZenithFlightTerrain() {}

    @Override
    public Cell cell(int x, int y, int z) {
        if (!World.isChunkLoadedBlockPos(x, z)) return Cell.UNKNOWN_CELL;
        var dimension = World.getCurrentDimension();
        if (y < dimension.minY()) return Cell.fullBlock(x, y, z);
        if (y >= dimension.buildHeight()) return Cell.AIR_CELL;
        var state = World.getBlockState(x, y, z);
        var boxes = state.getLocalizedCollisionBoxes();
        if (!boxes.isEmpty()) {
            double[] out = new double[boxes.size() * 6];
            int i = 0;
            for (var b : boxes) {
                out[i++] = b.minX();
                out[i++] = b.maxX();
                out[i++] = b.minY();
                out[i++] = b.maxY();
                out[i++] = b.minZ();
                out[i++] = b.maxZ();
            }
            return Cell.solid(out);
        }
        if (World.getFluidState(x, y, z) != null) return Cell.LIQUID_CELL;
        return Cell.AIR_CELL;
    }
}
