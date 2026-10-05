package dev.zenith.trader.flight;

import dev.zenith.trader.flight.FlightTerrain.Cell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A simplified copy of the Piratebay base (hall roof, raid farm platform in the
 * ocean with its pillar and glass roof) plus a small emulation of ZenithProxy's
 * bot: walking with sneak edge clipping, jumping, elytra deploy, gliding with
 * collisions, rockets that start after server latency and last a server-side
 * lifetime, and the server ending the glide some ticks after touchdown.
 */
final class FlightTestWorld implements FlightTerrain {
    static final int LAUNCH_X = -2012057, LAUNCH_Y = 141, LAUNCH_Z = -1657332;
    static final int RAID_X = -2011851, RAID_Y = 63, RAID_Z = -1657483;
    static final int HALL_X = -2012079, HALL_Y = 141, HALL_Z = -1657322;

    private final Map<Long, Cell> cells = new HashMap<>();

    FlightTestWorld() {
        // Ocean surface.
        for (int x = -2012125; x <= -2011800; x++)
            for (int z = -1657530; z <= -1657280; z++) put(x, 62, z, Cell.LIQUID_CELL);
        // Hall: roof at y140, walls down to y117 on the north and east faces.
        for (int x = -2012119; x <= -2012057; x++)
            for (int z = -1657332; z <= -1657288; z++) {
                solid(x, 140, z);
                put(x, 62, z, null);
            }
        for (int y = 117; y < 140; y++) {
            for (int x = -2012119; x <= -2012057; x++) solid(x, y, -1657332);
            for (int z = -1657332; z <= -1657288; z++) solid(-2012057, y, z);
        }
        // Roof furniture: hopper line with a glass strip, test chests, rocket shulker.
        for (int z = -1657331; z <= -1657300; z++)
            for (int x = -2012090; x <= -2012088; x++) { solid(x, 141, z); solid(x, 142, z); }
        int[][] chests = {{-2012081, -1657317}, {-2012081, -1657315}, {-2012081, -1657312}, {-2012081, -1657310},
            {-2012078, -1657317}, {-2012078, -1657315}, {-2012078, -1657312}, {-2012078, -1657310},
            {-2012075, -1657312}, {-2012075, -1657310}, {-2012087, -1657317}};
        for (int[] c : chests) box(c[0], 141, c[1], 0.0625, 0.9375, 0, 0.875, 0.0625, 0.9375);
        // Raid farm platform: glass floor, chest, slab, hopper, shulkers, stone pillar, glass roof.
        for (int[] g : new int[][]{{-2011851, -1657485}, {-2011850, -1657485}, {-2011849, -1657485},
            {-2011851, -1657484}, {-2011850, -1657484}, {-2011851, -1657483}, {-2011850, -1657483}})
            solid(g[0], 62, g[1]);
        solid(-2011849, 62, -1657484);
        solid(-2011848, 62, -1657484);
        solid(-2011849, 62, -1657483);
        solid(-2011848, 62, -1657483);
        box(-2011851, 63, -1657485, 0.0625, 0.9375, 0, 0.875, 0.0625, 0.9375);
        solid(-2011848, 63, -1657485);
        box(-2011849, 63, -1657484, 0, 1, 0, 0.5, 0, 1);
        solid(-2011848, 63, -1657484);
        solid(-2011848, 63, -1657483);
        solid(-2011847, 63, -1657483);
        for (int y = 63; y <= 90; y++) solid(-2011847, y, -1657484);
        for (int y = 64; y <= 90; y++) { solid(-2011848, y, -1657485); solid(-2011848, y, -1657483); }
        for (int x = -2011848; x <= -2011846; x++)
            for (int z = -1657484; z <= -1657477; z++) solid(x, 90, z);
        for (int x = -2011852; x <= -2011845; x++)
            for (int z = -1657491; z <= -1657483; z++) solid(x, 100, z);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private void put(int x, int y, int z, Cell c) {
        if (c == null) cells.remove(key(x, y, z));
        else cells.put(key(x, y, z), c);
    }

    private void solid(int x, int y, int z) {
        put(x, y, z, Cell.fullBlock(x, y, z));
    }

    private void box(int x, int y, int z, double x0, double x1, double y0, double y1, double z0, double z1) {
        put(x, y, z, Cell.solid(new double[]{x + x0, x + x1, y + y0, y + y1, z + z0, z + z1}));
    }

    @Override
    public Cell cell(int x, int y, int z) {
        if (y < 62) return Cell.fullBlock(x, y, z);
        Cell c = cells.get(key(x, y, z));
        return c == null ? Cell.AIR_CELL : c;
    }

    static FlightRoute outbound() {
        return FlightRoute.glide(LAUNCH_X + 0.5, LAUNCH_Y, LAUNCH_Z + 0.5, RAID_X + 0.5, RAID_Y, RAID_Z + 0.5);
    }

    static FlightRoute home() {
        return FlightRoute.climb(RAID_X + 0.5, RAID_Y, RAID_Z + 0.5, HALL_X + 0.5, HALL_Y, HALL_Z + 0.5, 160, 50);
    }

    /** Server and rocket behaviour for one simulated flight. */
    record Server(int rocketLatency, int rocketLifeMin, int rocketLifeSpread, double tpsSlowdown, int glideStopDelay,
                  int refusedDeploys, int refuseDelay, int setbackTick) {
        static final Server NORMAL = new Server(2, 20, 11, 1.0, 3, 0, 0, -1);
        static final Server LAGGY = new Server(6, 20, 11, 4.0, 14, 0, 0, -1);

        Server refusing(int deploys, int delay) {
            return new Server(rocketLatency, rocketLifeMin, rocketLifeSpread, tpsSlowdown, glideStopDelay, deploys, delay, setbackTick);
        }

        Server setbackAt(int tick) {
            return new Server(rocketLatency, rocketLifeMin, rocketLifeSpread, tpsSlowdown, glideStopDelay, refusedDeploys, refuseDelay, tick);
        }
    }

    /** Outcome of one simulated flight. */
    record Flight(FlightController controller, double finalX, double finalY, double finalZ, double wallDamage,
                  double maxLineError, List<String> log) {}

    Flight fly(FlightRoute route, int launchX, int launchY, int launchZ, double startX, double startZ, Server server,
               long seed) {
        var bot = new SimBot(this, server, new Random(seed));
        bot.x = startX;
        bot.y = launchY;
        bot.z = startZ;
        List<String> log = new ArrayList<>();
        var controller = new FlightController(route, new FlightPlanner(), launchX, launchY, launchZ, 20 * 180, log::add);
        double maxLineError = 0;
        while (!controller.finished() && bot.t < 20 * 200) {
            var command = controller.tick(bot.observe(), this);
            bot.step(command);
            double r = route.remaining(bot.x, bot.z);
            if (route.mode == FlightRoute.Mode.GLIDE && bot.fallFlying && !bot.onGround
                && route.progress(bot.x, bot.z) > 20 && r > 3)
                maxLineError = Math.max(maxLineError, Math.abs(bot.y - route.referenceY(r)));
        }
        return new Flight(controller, bot.x, bot.y, bot.z, bot.damage, maxLineError, log);
    }

    private static final class SimBot {
        final FlightTestWorld world;
        final Server server;
        final Random random;
        double x, y, z, vx, vy, vz, damage;
        float yaw, pitch;
        boolean onGround = true, fallFlying, wasJump, inWater, horizontalCollision;
        int t, groundSince = -1, deployedAt = -1, refusalsLeft;
        boolean setback;
        final List<int[]> rockets = new ArrayList<>();

        SimBot(FlightTestWorld world, Server server, Random random) {
            this.world = world;
            this.server = server;
            this.random = random;
            this.refusalsLeft = server.refusedDeploys();
        }

        boolean collides(double nx, double ny, double nz, double h) {
            return world.probe(nx - 0.3, ny, nz - 0.3, nx + 0.3, ny + h, nz + 0.3) == Contact.SOLID;
        }

        boolean supported(double nx, double nz) {
            return collides(nx, y - 0.6, nz, 0.6);
        }

        boolean water() {
            for (int ix = (int) Math.floor(x - 0.3); ix <= (int) Math.floor(x + 0.3); ix++)
                for (int iz = (int) Math.floor(z - 0.3); iz <= (int) Math.floor(z + 0.3); iz++)
                    for (int iy = (int) Math.floor(y) - 1; iy <= (int) Math.floor(y); iy++)
                        if (world.cell(ix, iy, iz).kind == Cell.LIQUID && y < iy + 0.889) return true;
            return false;
        }

        void move(double h, boolean sneak) {
            double ny = y + vy;
            if (vy != 0 && collides(x, ny, z, h)) {
                double free = y, hit = ny;
                for (int i = 0; i < 30; i++) {
                    double mid = (free + hit) / 2;
                    if (collides(x, mid, z, h)) hit = mid;
                    else free = mid;
                }
                ny = free;
                onGround = vy < 0;
                vy = 0;
            } else onGround = collides(x, ny - 0.001, z, h);
            y = ny;
            double before = Math.hypot(vx, vz);
            horizontalCollision = false;
            double nx = x + vx;
            if (sneak && onGround && !supported(nx, z)) { nx = x; vx = 0; }
            if (collides(nx, y, z, h)) {
                if (onGround && !collides(nx, y + 0.6, z, h)) { while (collides(nx, y, z, h)) y += 0.0625; }
                else { horizontalCollision = true; nx = x; vx = 0; }
            }
            x = nx;
            double nz = z + vz;
            if (sneak && onGround && !supported(x, nz)) { nz = z; vz = 0; }
            if (collides(x, y, nz, h)) {
                if (onGround && !collides(x, y + 0.6, nz, h)) { while (collides(x, y, nz, h)) y += 0.0625; }
                else { horizontalCollision = true; nz = z; vz = 0; }
            }
            z = nz;
            if (horizontalCollision && fallFlying) damage += Math.max(0, (before - Math.hypot(vx, vz)) * 10 - 3);
            inWater = water();
        }

        boolean rocketAttached() {
            for (int[] r : rockets) if (t >= r[0] && t < r[0] + r[1]) return true;
            return false;
        }

        int rocketAge() {
            for (int[] r : rockets) if (t >= r[0] && t < r[0] + r[1]) return t - r[0];
            return 0;
        }

        void step(FlightController.Command c) {
            t++;
            yaw = c.yaw();
            pitch = c.pitch();
            if (c.useRocket() && fallFlying) {
                int life = (int) Math.round((server.rocketLifeMin() + random.nextInt(server.rocketLifeSpread() + 1)) * server.tpsSlowdown());
                rockets.add(new int[]{t + server.rocketLatency(), life});
            }
            if (c.jump() && !wasJump && !onGround && !fallFlying && !inWater) { fallFlying = true; deployedAt = t; }
            if (fallFlying && refusalsLeft > 0 && t - deployedAt >= server.refuseDelay()) { fallFlying = false; refusalsLeft--; }
            if (c.jump() && onGround) { vy = 0.42; onGround = false; }
            wasJump = c.jump();
            if (fallFlying) {
                var s = new ElytraModel.State(x, y, z, vx, vy, vz);
                ElytraModel.glide(s, yaw, pitch);
                vx = s.vx; vy = s.vy; vz = s.vz;
                move(ElytraModel.BOX, false);
            } else if (inWater) {
                vx *= 0.8; vz *= 0.8; vy = vy * 0.8 - 0.005;
                move(1.8, false);
            } else {
                if (c.forward()) {
                    double speed = onGround ? 0.1 * (c.sneak() ? 0.3 : 1.0) * 0.98 : 0.02;
                    vx += -Math.sin(Math.toRadians(yaw)) * speed;
                    vz += Math.cos(Math.toRadians(yaw)) * speed;
                }
                move(1.8, c.sneak());
                double f = onGround ? 0.546 : 0.91;
                vx *= f; vz *= f;
                vy = (vy - 0.08) * 0.98;
                if (onGround && vy < 0) vy = 0;
            }
            if (fallFlying && rocketAttached()) {
                var s = new ElytraModel.State(x, y, z, vx, vy, vz);
                ElytraModel.boost(s, yaw, pitch);
                vx = s.vx; vy = s.vy; vz = s.vz;
            }
            if (fallFlying && (onGround || inWater)) {
                if (groundSince < 0) groundSince = t;
                if (t - groundSince >= server.glideStopDelay()) { fallFlying = false; groundSince = -1; }
            } else groundSince = -1;
        }

        FlightController.Observation observe() {
            if (t == server.setbackTick()) {
                // Server position correction: Zenith's syncFromCache zeroes velocity and claims onGround.
                vx = vy = vz = 0;
                return new FlightController.Observation(x, y, z, 0, 0, 0, yaw, pitch, true, fallFlying, inWater,
                    rocketAttached(), rocketAge(), 64, false);
            }
            return new FlightController.Observation(x, y, z, vx, vy, vz, yaw, pitch, onGround, fallFlying, inWater,
                rocketAttached(), rocketAge(), 64, horizontalCollision);
        }
    }
}
