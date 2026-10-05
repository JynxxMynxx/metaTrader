package dev.zenith.trader.flight;

/**
 * Block-level view of the world used for flight clearance checks. Implementations
 * return world-space collision boxes so slabs, hoppers and other partial blocks
 * are checked with their real shapes.
 */
public interface FlightTerrain {
    Cell cell(int x, int y, int z);

    /** A block cell: air, liquid, not loaded, or solid with its collision boxes. */
    final class Cell {
        public static final int AIR = 0, SOLID = 1, LIQUID = 2, UNKNOWN = 3;
        public static final Cell AIR_CELL = new Cell(AIR, null);
        public static final Cell LIQUID_CELL = new Cell(LIQUID, null);
        public static final Cell UNKNOWN_CELL = new Cell(UNKNOWN, null);

        public final int kind;
        /** World-space boxes, six values each: minX, maxX, minY, maxY, minZ, maxZ. */
        public final double[] boxes;

        public Cell(int kind, double[] boxes) {
            this.kind = kind;
            this.boxes = boxes;
        }

        public static Cell solid(double[] boxes) {
            return new Cell(SOLID, boxes);
        }

        /** Full cube at the given block position. */
        public static Cell fullBlock(int x, int y, int z) {
            return new Cell(SOLID, new double[]{x, x + 1, y, y + 1, z, z + 1});
        }

        public boolean intersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            if (kind != SOLID || boxes == null) return false;
            for (int i = 0; i + 5 < boxes.length; i += 6) {
                if (boxes[i] < maxX && boxes[i + 1] > minX
                    && boxes[i + 2] < maxY && boxes[i + 3] > minY
                    && boxes[i + 4] < maxZ && boxes[i + 5] > minZ) return true;
            }
            return false;
        }
    }

    /** Result of testing a box against the terrain. */
    enum Contact { CLEAR, SOLID, LIQUID, UNKNOWN }

    /**
     * Tests an axis-aligned box. Solid collision wins over liquid; liquid wins
     * over unloaded. Unloaded cells alone report UNKNOWN.
     */
    default Contact probe(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        int x0 = (int) Math.floor(minX), x1 = (int) Math.floor(maxX - 1.0E-7);
        int y0 = (int) Math.floor(minY), y1 = (int) Math.floor(maxY - 1.0E-7);
        int z0 = (int) Math.floor(minZ), z1 = (int) Math.floor(maxZ - 1.0E-7);
        boolean liquid = false, unknown = false;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    Cell c = cell(x, y, z);
                    if (c.kind == Cell.SOLID) {
                        if (c.intersects(minX, minY, minZ, maxX, maxY, maxZ)) return Contact.SOLID;
                    } else if (c.kind == Cell.LIQUID) liquid = true;
                    else if (c.kind == Cell.UNKNOWN) unknown = true;
                }
            }
        }
        if (liquid) return Contact.LIQUID;
        return unknown ? Contact.UNKNOWN : Contact.CLEAR;
    }

    /** Probe the fall-flying box (0.6 x 0.6 x 0.6) at feet position x, y, z, optionally inflated. */
    default Contact probePlayer(double x, double y, double z, double inflate) {
        double half = ElytraModel.BOX / 2 + inflate;
        return probe(x - half, y - inflate, z - half, x + half, y + ElytraModel.BOX + inflate, z + half);
    }

    /** Wraps a terrain with a cache that is valid for one planning tick. */
    static FlightTerrain cached(FlightTerrain delegate) {
        return new CachedTerrain(delegate);
    }

    /** Open-addressing long-keyed cache; avoids boxing in the planner's hot loop. */
    final class CachedTerrain implements FlightTerrain {
        private final FlightTerrain delegate;
        private long[] keys = new long[4096];
        private Cell[] values = new Cell[4096];
        private int size;

        CachedTerrain(FlightTerrain delegate) {
            this.delegate = delegate;
        }

        @Override
        public Cell cell(int x, int y, int z) {
            long key = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
            int mask = keys.length - 1;
            int i = (int) (mix(key) & mask);
            while (values[i] != null) {
                if (keys[i] == key) return values[i];
                i = (i + 1) & mask;
            }
            Cell c = delegate.cell(x, y, z);
            keys[i] = key;
            values[i] = c;
            if (++size * 2 > keys.length) grow();
            return c;
        }

        private static long mix(long k) {
            k ^= k >>> 33;
            k *= 0xff51afd7ed558ccdL;
            k ^= k >>> 33;
            return k;
        }

        private void grow() {
            long[] oldKeys = keys;
            Cell[] oldValues = values;
            keys = new long[oldKeys.length * 2];
            values = new Cell[oldKeys.length * 2];
            int mask = keys.length - 1;
            for (int j = 0; j < oldKeys.length; j++) {
                if (oldValues[j] == null) continue;
                int i = (int) (mix(oldKeys[j]) & mask);
                while (values[i] != null) i = (i + 1) & mask;
                keys[i] = oldKeys[j];
                values[i] = oldValues[j];
            }
        }
    }
}
