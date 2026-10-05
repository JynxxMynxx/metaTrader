package dev.zenith.trader.flight;

import dev.zenith.trader.flight.ElytraModel.State;
import dev.zenith.trader.flight.FlightTerrain.Contact;

/**
 * Receding-horizon pitch planner. Every tick it simulates a set of pitch
 * schedules with the exact elytra model, rejects any that touch terrain or water
 * away from the landing point, and picks the one that best follows the route's
 * reference altitude, respects the speed limit and touches down slowly on the
 * landing block. Yaw follows the route line.
 *
 * <p>Rockets are fired only when no unpowered glide can still reach the next
 * altitude goal (the cruise height at the approach radius on the climb leg, the
 * landing block otherwise), and only once the previous rocket is spent.
 */
public final class FlightPlanner {
    /** Tunables; defaults are what the simulations in FlightPlannerTest were run with. */
    public static final class Settings {
        public int horizon = 60;
        public int splitTick = 12;
        public float maxPitchStep = 12f;
        public float maxYawStep = 25f;
        public float[] firstPitches = {-70, -60, -50, -45, -40, -35, -32, -29, -26, -22, -18, -14, -10, -5, 0, 5, 12, 20, 30};
        public float[] secondPitches = {-70, -55, -45, -38, -32, -27, -22, -15, -8, 0, 10, 25};
        /** Horizontal distance from the landing point accepted as a touchdown. */
        public double landingRadius = 1.2;
        /** Within this distance of the landing point the planner may also flare by turning to face backwards. */
        public double brakeRadius = 8.0;
        /** Ticks after which a backwards-facing flare may start. */
        public int[] brakeStarts = {1, 3, 6, 10};
        /** Pitch held while a rocket is boosting and the bot is well below cruise height. */
        public float climbPitch = -40f;
        /** Rocket boost length assumed when the attached rocket's remaining life is unknown. */
        public int expectedRocketTicks = 22;
        /** The cruise height may be missed by this much at the approach radius. */
        public double cruiseTolerance = 3.0;
        /** Never fire within this distance of the landing point; the bot is landing. */
        public double noRocketRadius = 8.0;
        /** Hard cap on rockets for one leg. */
        public int maxRockets = 6;
        /** Inside the approach radius, fire if this far below the approach line (missed cruise height). */
        public double approachRecoveryMargin = 6.0;
        /**
         * A needed rocket waits until the bot stops rising (the top of the zoom
         * climb), so each rocket starts from low speed and adds the most energy.
         */
        public double fireMaxVerticalSpeed = 0.12;
        public double weightBelow = 4.0;
        public double weightAbove = 1.0;
        public double weightSpeed = 120.0;
        public double weightLandDistance = 40.0;
        public double weightLandSpeed = 60.0;
    }

    /** Planner output for one tick. */
    public record Decision(float yaw, float pitch, boolean fireRocket, double cost, int touchdownTick,
                           double touchdownSpeed, boolean crashPredicted, String mode) {}

    private static final double CRASH = 1.0E6;
    private static final float[] ZOOM_PITCHES = {Float.NaN, -45, -35, -25, -15};
    private static final float[] GLIDE_PITCHES = {-20, -12, -6, 0, 6};

    private final Settings settings;
    private final State scratch = new State(0, 0, 0, 0, 0, 0);
    private int lastTouchdownTick;
    private double lastTouchdownSpeed;
    private boolean lastCrash;

    public FlightPlanner(Settings settings) {
        this.settings = settings;
    }

    public FlightPlanner() {
        this(new Settings());
    }

    public Settings settings() { return settings; }

    /**
     * @param boostTicks   ticks of rocket boost still expected (0 when none is attached or pending)
     * @param mayFire      a rocket is held, none is attached or pending and the cooldown has passed
     */
    public Decision decide(State now, float yaw, float pitch, int boostTicks, boolean mayFire,
                           FlightRoute route, FlightTerrain world) {
        FlightTerrain terrain = FlightTerrain.cached(world);
        double r = route.remaining(now.x, now.z);
        float yawCommand = r > 1.0
            ? stepYaw(yaw, route.guidanceYaw(now.x, now.z, now.horizontalSpeed()), settings.maxYawStep)
            : yaw;

        boolean fire = false;
        int boost = boostTicks;
        boolean need = mayFire && boost == 0 && needsRocket(now, route, terrain);
        if (need && (now.vy < settings.fireMaxVerticalSpeed || unpoweredCrash(now, yaw, pitch, route, terrain))) {
            fire = true;
            boost = settings.expectedRocketTicks;
        }

        if (boost > 0 && route.mode == FlightRoute.Mode.CLIMB
            && now.y < route.referenceY(r) - 12 && r > route.approachRadius) {
            double cost = rollout(now, yaw, pitch, settings.climbPitch, settings.climbPitch, settings.splitTick, false,
                boost, route, terrain);
            if (!lastCrash) {
                float p = stepToward(pitch, settings.climbPitch, settings.maxPitchStep);
                return new Decision(yawCommand, p, fire, cost, -1, 0, false, "climb");
            }
        }

        double best = Double.POSITIVE_INFINITY;
        float bestP1 = pitch, bestP2 = pitch;
        int bestBrake = 0;
        int bestTouch = -1;
        double bestTouchSpeed = 0;
        boolean bestCrash = true;
        for (float p1 : settings.firstPitches) {
            for (float p2 : settings.secondPitches) {
                double cost = rollout(now, yaw, pitch, p1, p2, settings.splitTick, false, boost, route, terrain);
                if (cost < best) {
                    best = cost;
                    bestP1 = p1;
                    bestP2 = p2;
                    bestBrake = 0;
                    bestTouch = lastTouchdownTick;
                    bestTouchSpeed = lastTouchdownSpeed;
                    bestCrash = lastCrash;
                }
            }
        }
        if (r < settings.brakeRadius && boost == 0) {
            for (int start : settings.brakeStarts) {
                for (float p1 : start > 1 ? settings.firstPitches : new float[]{0}) {
                    double cost = rollout(now, yaw, pitch, p1, 0, start - 1, true, 0, route, terrain);
                    if (cost < best) {
                        best = cost;
                        bestP1 = p1;
                        bestP2 = 0;
                        bestBrake = start;
                        bestTouch = lastTouchdownTick;
                        bestTouchSpeed = lastTouchdownSpeed;
                        bestCrash = lastCrash;
                    }
                }
            }
        }
        if (bestBrake == 1) {
            // Flare: face away from the direction of travel, which drags horizontal speed down ~20% a tick.
            float brakeYaw = ElytraModel.wrapDegrees(ElytraModel.yawTowards(0, 0, now.vx, now.vz) + 180f);
            return new Decision(brakeYaw, stepToward(pitch, 0, settings.maxPitchStep * 2), fire, best, bestTouch,
                bestTouchSpeed, bestCrash, "flare");
        }
        float p = stepToward(pitch, bestP1, settings.maxPitchStep);
        return new Decision(yawCommand, p, fire, best, bestTouch, bestTouchSpeed, bestCrash,
            bestBrake > 0 ? String.format("mpc %.0f then flare@%d", bestP1, bestBrake) : String.format("mpc %.0f/%.0f", bestP1, bestP2));
    }

    private double rollout(State start, float yaw0, float pitch0, float p1, float p2, int split, boolean brake,
                           int boostTicks, FlightRoute route, FlightTerrain terrain) {
        Settings c = settings;
        State s = scratch;
        s.set(start);
        float yaw = yaw0, pitch = pitch0;
        float brakeYaw = Float.NaN;
        double cost = 0.01 * (p1 - pitch0) * (p1 - pitch0) + 0.002 * (p2 - p1) * (p2 - p1);
        lastTouchdownTick = -1;
        lastTouchdownSpeed = 0;
        lastCrash = false;
        int n = c.horizon;
        for (int k = 1; k <= n; k++) {
            boolean second = k > split;
            if (brake && second) {
                if (Float.isNaN(brakeYaw)) brakeYaw = ElytraModel.wrapDegrees(ElytraModel.yawTowards(0, 0, s.vx, s.vz) + 180f);
                yaw = brakeYaw;
                pitch = stepToward(pitch, p2, c.maxPitchStep * 2);
            } else {
                pitch = stepToward(pitch, second ? p2 : p1, c.maxPitchStep);
                double rBefore = route.remaining(s.x, s.z);
                if (rBefore > 1.0) yaw = stepYaw(yaw, route.guidanceYaw(s.x, s.z, s.horizontalSpeed()), c.maxYawStep);
            }
            double oldY = s.y;
            ElytraModel.tick(s, yaw, pitch, k <= boostTicks);
            double r = route.remaining(s.x, s.z);
            double h = s.horizontalSpeed();
            Contact contact = terrain.probePlayer(s.x, s.y, s.z, 0);
            if (contact == Contact.SOLID) {
                boolean fromAbove = s.vy < 0 && terrain.probePlayer(s.x, oldY, s.z, 0) != Contact.SOLID;
                if (fromAbove && r <= c.landingRadius && Math.abs(s.y - route.targetY) < 1.0) {
                    lastTouchdownTick = k;
                    lastTouchdownSpeed = h;
                    return cost + landingCost(r, h, s.vy);
                }
                lastCrash = true;
                return cost + CRASH + (n - k) * 1.0E4;
            }
            if (contact == Contact.LIQUID) {
                if (r <= 4.0) {
                    lastTouchdownTick = k;
                    lastTouchdownSpeed = h;
                    return cost + landingCost(r, h, s.vy) + 400;
                }
                lastCrash = true;
                return cost + CRASH / 2 + (n - k) * 5.0E3;
            }
            double tw = (double) k / n;
            double e = s.y - route.referenceY(r);
            cost += (e < 0 ? c.weightBelow : c.weightAbove) * e * e * tw;
            double limit = route.speedLimit(r);
            if (h > limit) cost += c.weightSpeed * (h - limit) * (h - limit) * tw;
        }
        return cost;
    }

    private double landingCost(double distance, double horizontalSpeed, double vy) {
        double cost = settings.weightLandDistance * distance * distance
            + settings.weightLandSpeed * horizontalSpeed * horizontalSpeed;
        if (vy < -0.5) cost += 400 * (-0.5 - vy);
        return cost;
    }

    /** True when every unpowered pitch schedule over the horizon hits terrain or water. */
    private boolean unpoweredCrash(State now, float yaw, float pitch, FlightRoute route, FlightTerrain terrain) {
        for (float p1 : settings.firstPitches) {
            rollout(now, yaw, pitch, p1, p1, settings.splitTick, false, 0, route, terrain);
            if (!lastCrash) return false;
        }
        return true;
    }

    /**
     * True when no unpowered continuation reaches the next goal: the cruise height
     * at the approach radius on the climb leg, otherwise the landing point. Each
     * candidate first zooms (pitch up while still rising, turning speed into
     * height) and then glides at a fixed pitch, so a rocket is only used once the
     * previous one's energy is spent.
     */
    public boolean needsRocket(State now, FlightRoute route, FlightTerrain terrain) {
        double r = route.remaining(now.x, now.z);
        if (r < settings.noRocketRadius) return false;
        boolean cruiseGoal = route.mode == FlightRoute.Mode.CLIMB && r > route.approachRadius + 1;
        // Safety: well below the approach line inside the approach radius means the cruise height was missed.
        if (route.mode == FlightRoute.Mode.CLIMB && !cruiseGoal && r > 2 * settings.noRocketRadius
            && now.y < route.referenceY(r) - settings.approachRecoveryMargin) return true;
        double goalY = cruiseGoal ? route.cruiseY - settings.cruiseTolerance : route.targetY + 0.3;
        State s = new State(0, 0, 0, 0, 0, 0);
        for (float zoom : ZOOM_PITCHES) {
            for (float glide : GLIDE_PITCHES) {
                s.set(now);
                boolean zooming = !Float.isNaN(zoom);
                for (int k = 0; k < 2400; k++) {
                    if (zooming && k >= 3 && s.vy <= 0) zooming = false;
                    float pitch = zooming ? zoom : glide;
                    ElytraModel.glide(s, route.guidanceYaw(s.x, s.z, s.horizontalSpeed()), pitch);
                    double rr = route.remaining(s.x, s.z);
                    boolean arrived = cruiseGoal
                        ? rr <= route.approachRadius
                        : rr <= 1.0 || route.progress(s.x, s.z) >= route.length() - 0.5;
                    if (arrived) {
                        if (s.y >= goalY) return false;
                        break;
                    }
                    if (s.y < goalY - 60) break;
                    if ((k & 3) == 0) {
                        Contact c = terrain.probePlayer(s.x, s.y, s.z, 0);
                        if (c == Contact.SOLID || c == Contact.LIQUID) break;
                    }
                }
            }
        }
        return true;
    }

    static float stepToward(float current, float target, float maxStep) {
        float d = target - current;
        if (d > maxStep) return current + maxStep;
        if (d < -maxStep) return current - maxStep;
        return target;
    }

    static float stepYaw(float current, float target, float maxStep) {
        float d = ElytraModel.wrapDegrees(target - current);
        if (d > maxStep) d = maxStep;
        if (d < -maxStep) d = -maxStep;
        return ElytraModel.wrapDegrees(current + d);
    }
}
