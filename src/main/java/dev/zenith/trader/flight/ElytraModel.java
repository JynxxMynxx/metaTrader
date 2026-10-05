package dev.zenith.trader.flight;

/**
 * Elytra flight physics copied from ZenithProxy's client-side player simulation
 * ({@code Bot.travelFallFlying} and the attached-firework branch of
 * {@code Bot.tickEntities}). The planner predicts with exactly the model the bot
 * moves with, so planned glides match the flown ones up to server corrections.
 *
 * <p>Order per client tick in Zenith: rotation is applied, tiny velocity
 * components are zeroed, the glide update moves the player, then an attached
 * rocket adds its boost to the velocity used on the next tick.
 */
public final class ElytraModel {
    public static final double GRAVITY = 0.08;
    /** Width and height of the fall-flying bounding box. */
    public static final double BOX = 0.6;

    private ElytraModel() {}

    /** Mutable kinematic state: feet position and velocity in blocks/tick. */
    public static final class State {
        public double x, y, z, vx, vy, vz;

        public State(double x, double y, double z, double vx, double vy, double vz) {
            this.x = x; this.y = y; this.z = z; this.vx = vx; this.vy = vy; this.vz = vz;
        }

        public State copy() { return new State(x, y, z, vx, vy, vz); }

        public void set(State o) { x = o.x; y = o.y; z = o.z; vx = o.vx; vy = o.vy; vz = o.vz; }

        public double horizontalSpeed() { return Math.sqrt(vx * vx + vz * vz); }
    }

    /** Look vector exactly as MathHelper.calculateViewVector(yaw, pitch). */
    static double lookX(float yaw, float pitch) {
        return Math.sin(-yaw * (Math.PI / 180.0)) * Math.cos(pitch * (Math.PI / 180.0));
    }

    static double lookY(float pitch) {
        return -Math.sin(pitch * (Math.PI / 180.0));
    }

    static double lookZ(float yaw, float pitch) {
        return Math.cos(-yaw * (Math.PI / 180.0)) * Math.cos(pitch * (Math.PI / 180.0));
    }

    /** One fall-flying tick without collision (positions advance by the new velocity). */
    public static void glide(State s, float yaw, float pitch) {
        if (Math.abs(s.vx) < 0.003) s.vx = 0;
        if (Math.abs(s.vy) < 0.003) s.vy = 0;
        if (Math.abs(s.vz) < 0.003) s.vz = 0;
        double lx = lookX(yaw, pitch), ly = lookY(pitch), lz = lookZ(yaw, pitch);
        float pitchRad = pitch * (float) (Math.PI / 180.0);
        double hLook = Math.sqrt(lx * lx + lz * lz);
        double hVel = Math.sqrt(s.vx * s.vx + s.vz * s.vz);
        double lookLen = Math.sqrt(lx * lx + ly * ly + lz * lz);
        double cosPitch = Math.cos(pitchRad);
        cosPitch = cosPitch * cosPitch * Math.min(1.0, lookLen / 0.4);
        s.vy += GRAVITY * (-1.0 + cosPitch * 0.75);
        if (s.vy < 0 && hLook > 0) {
            double m = s.vy * -0.1 * cosPitch;
            s.vx += lx * m / hLook;
            s.vy += m;
            s.vz += lz * m / hLook;
        }
        if (pitchRad < 0 && hLook > 0) {
            double m = hVel * -Math.sin(pitchRad) * 0.04;
            s.vx += -lx * m / hLook;
            s.vy += m * 3.2;
            s.vz += -lz * m / hLook;
        }
        if (hLook > 0) {
            s.vx += (lx / hLook * hVel - s.vx) * 0.1;
            s.vz += (lz / hLook * hVel - s.vz) * 0.1;
        }
        s.vx *= 0.99;
        s.vy *= 0.98;
        s.vz *= 0.99;
        s.x += s.vx;
        s.y += s.vy;
        s.z += s.vz;
    }

    /** Velocity added by an attached firework rocket after the move. */
    public static void boost(State s, float yaw, float pitch) {
        double lx = lookX(yaw, pitch), ly = lookY(pitch), lz = lookZ(yaw, pitch);
        s.vx += lx * 0.1 + (lx * 1.5 - s.vx) * 0.5;
        s.vy += ly * 0.1 + (ly * 1.5 - s.vy) * 0.5;
        s.vz += lz * 0.1 + (lz * 1.5 - s.vz) * 0.5;
    }

    /** A full client tick: glide, then the rocket boost when one is attached. */
    public static void tick(State s, float yaw, float pitch, boolean rocket) {
        glide(s, yaw, pitch);
        if (rocket) boost(s, yaw, pitch);
    }

    /** Minecraft yaw that looks from (x, z) towards (tx, tz). */
    public static float yawTowards(double x, double z, double tx, double tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - x), tz - z));
    }

    public static float wrapDegrees(float degrees) {
        float d = degrees % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }
}
