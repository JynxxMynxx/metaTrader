package dev.zenith.trader.flight;

/**
 * Geometry of one flight leg and the altitude the bot should hold along it.
 *
 * <p>{@link Mode#GLIDE}: an unpowered descent along the straight 3D line from
 * the launch point to the landing point (the hall roof to the raid farm). The
 * slope of that line is the glide angle.
 *
 * <p>{@link Mode#CLIMB}: a powered leg (raid farm back to the hall roof). Hold at
 * least {@code cruiseY} until {@code approachRadius} blocks from the landing
 * point, then descend along a straight line to it.
 *
 * <p>Positions are feet positions; block coordinates are converted to block
 * centres by the caller.
 */
public final class FlightRoute {
    public enum Mode { GLIDE, CLIMB }

    public final Mode mode;
    public final double originX, originY, originZ;
    public final double targetX, targetY, targetZ;
    public final double cruiseY;
    public final double approachRadius;
    /**
     * Before the approach radius the climb leg's reference descends towards the
     * cruise height at this slope (just steeper than the elytra's best glide), so
     * rocket energy is stored as height rather than speed that drag would waste.
     */
    public static final double CRUISE_SLOPE = 0.11;
    /** Highest the climb leg's reference rises above the cruise height. */
    public static final double CRUISE_HEADROOM = 30;
    private final double dirX, dirZ, length;
    private final double glideSlope;
    private final double approachSlope;

    private FlightRoute(Mode mode, double ox, double oy, double oz, double tx, double ty, double tz,
                        double cruiseY, double approachRadius) {
        this.mode = mode;
        this.originX = ox; this.originY = oy; this.originZ = oz;
        this.targetX = tx; this.targetY = ty; this.targetZ = tz;
        this.cruiseY = cruiseY;
        this.approachRadius = approachRadius;
        double dx = tx - ox, dz = tz - oz;
        this.length = Math.sqrt(dx * dx + dz * dz);
        this.dirX = length > 1.0E-6 ? dx / length : 0;
        this.dirZ = length > 1.0E-6 ? dz / length : 1;
        this.glideSlope = length > 1.0E-6 ? (oy - ty) / length : 0;
        this.approachSlope = approachRadius > 1.0E-6 ? (cruiseY - ty) / approachRadius : 0;
    }

    public static FlightRoute glide(double ox, double oy, double oz, double tx, double ty, double tz) {
        return new FlightRoute(Mode.GLIDE, ox, oy, oz, tx, ty, tz, oy, 0);
    }

    public static FlightRoute climb(double ox, double oy, double oz, double tx, double ty, double tz,
                                    double cruiseY, double approachRadius) {
        return new FlightRoute(Mode.CLIMB, ox, oy, oz, tx, ty, tz, Math.max(cruiseY, ty + 1), approachRadius);
    }

    public double length() { return length; }

    /** Descent slope of the glide line (drop per horizontal block). */
    public double glideSlope() { return glideSlope; }

    /** Descent slope of the final approach line. */
    public double approachSlope() { return mode == Mode.GLIDE ? glideSlope : approachSlope; }

    public double glideAngleDegrees() { return Math.toDegrees(Math.atan(approachSlope())); }

    /** Horizontal distance to the landing point. */
    public double remaining(double x, double z) {
        double dx = targetX - x, dz = targetZ - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Distance travelled along the route line (may be negative or exceed length). */
    public double progress(double x, double z) {
        return (x - originX) * dirX + (z - originZ) * dirZ;
    }

    /** Signed sideways distance from the route line. */
    public double crossTrack(double x, double z) {
        return (x - originX) * dirZ - (z - originZ) * dirX;
    }

    /** Reference feet altitude at the given horizontal distance from the landing point. */
    public double referenceY(double remaining) {
        if (mode == Mode.GLIDE) return targetY + glideSlope * remaining;
        if (remaining <= approachRadius) return targetY + approachSlope * remaining;
        return cruiseY + Math.min(CRUISE_HEADROOM, CRUISE_SLOPE * (remaining - approachRadius));
    }

    /**
     * Highest horizontal speed (blocks/tick) the planner aims to hold. The glide
     * leg stays in the slow, nose-up regime; the climb leg may cruise fast but
     * must slow down before the approach so it can descend steeply.
     */
    public double speedLimit(double remaining) {
        if (mode == Mode.GLIDE) return remaining < 16 ? 0.42 : 0.55;
        if (remaining < approachRadius) return remaining < 16 ? 0.42 : 0.5;
        double fast = 2.2, bleed = 70;
        if (remaining > approachRadius + bleed) return fast;
        return 0.5 + (fast - 0.5) * (remaining - approachRadius) / bleed;
    }

    /** Yaw towards a look-ahead point on the route line, or the landing point when close. */
    public float guidanceYaw(double x, double z, double horizontalSpeed) {
        double r = remaining(x, z);
        double lookAhead = Math.max(6.0, Math.min(24.0, horizontalSpeed * 14.0));
        if (r <= lookAhead) return ElytraModel.yawTowards(x, z, targetX, targetZ);
        double along = Math.min(length, progress(x, z) + lookAhead);
        double px = originX + dirX * along, pz = originZ + dirZ * along;
        return ElytraModel.yawTowards(x, z, px, pz);
    }

    /** Yaw from the landing point back along the route, used on the ground before takeoff. */
    public float launchYaw() {
        return ElytraModel.yawTowards(originX, originZ, targetX, targetZ);
    }

    @Override
    public String toString() {
        return String.format("%s (%.1f, %.1f, %.1f) -> (%.1f, %.1f, %.1f) length=%.1f slope=%.3f (%.1f deg)%s",
            mode, originX, originY, originZ, targetX, targetY, targetZ, length, approachSlope(), glideAngleDegrees(),
            mode == Mode.CLIMB ? String.format(" cruiseY=%.0f approachRadius=%.0f", cruiseY, approachRadius) : "");
    }
}
