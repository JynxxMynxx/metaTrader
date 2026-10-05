package dev.zenith.trader.flight;

import java.util.function.Consumer;

/**
 * Tick-by-tick state machine for one flight leg, independent of ZenithProxy so
 * the same logic runs in-game and in the offline simulations.
 *
 * <ol>
 *   <li>POSITION - sneak-walk to the edge of the launch block facing the route
 *       (sneaking cannot walk off the roof).</li>
 *   <li>JUMP / DEPLOY - a standing jump, release, then jump again in the air to
 *       open the elytra. A standing jump lands back on the launch block if the
 *       server refuses the elytra, so a failed deploy never falls off the roof.</li>
 *   <li>FLY - {@link FlightPlanner} steers along the {@link FlightRoute}; rockets
 *       only when the planner finds the goal unreachable without one.</li>
 *   <li>LANDING - touched down; wait for the server to end the glide.</li>
 * </ol>
 */
public final class FlightController {
    public enum Phase { POSITION, JUMP, DEPLOY, FLY, LANDING, DONE, FAILED }

    /** What the bot observed this tick. */
    public record Observation(double x, double y, double z, double vx, double vy, double vz,
                              float yaw, float pitch, boolean onGround, boolean fallFlying,
                              boolean inWater, boolean rocketAttached, int rocketAge, int rocketsHeld,
                              boolean horizontalCollision) {}

    /** Inputs to apply this tick. */
    public record Command(float yaw, float pitch, boolean forward, boolean sneak, boolean jump, boolean useRocket) {}

    private static final int TAKEOFF_ATTEMPTS = 4;
    /** Ticks to wait for a fired rocket's entity before assuming it never spawned. */
    private static final int ROCKET_PENDING_TICKS = 30;
    /** Horizontal distance from the launch block within which takeoff positioning may sneak-walk back. */
    private static final double LAUNCH_SLACK = 3.0;

    private final FlightRoute route;
    private final FlightPlanner planner;
    private final Consumer<String> log;
    private final int launchBlockX, launchBlockY, launchBlockZ;
    private final double standX, standZ;
    private final int timeoutTicks;

    private Phase phase = Phase.POSITION;
    private String failure = "";
    private int tick, phaseTick, takeoffAttempts, rocketsFired;
    private int lastFireTick = -1000;
    private boolean rocketSeen;
    private int groundTicks;
    private boolean retryTakeoff;
    private int openTicks;
    private float lastPitch;
    private FlightPlanner.Decision lastDecision;
    private double maxAltitude = Double.NEGATIVE_INFINITY;
    private double altitudeAtApproach = Double.NaN;
    private double touchdownSpeed = Double.NaN;
    private double touchdownX, touchdownY, touchdownZ;

    /**
     * @param launchBlock block the bot stands on top of at takeoff (feet block)
     */
    public FlightController(FlightRoute route, FlightPlanner planner, int launchBlockX, int launchBlockY,
                            int launchBlockZ, int timeoutTicks, Consumer<String> log) {
        this.route = route;
        this.planner = planner;
        this.log = log;
        this.launchBlockX = launchBlockX;
        this.launchBlockY = launchBlockY;
        this.launchBlockZ = launchBlockZ;
        this.timeoutTicks = timeoutTicks;
        // Stand flush with the edge of the launch block that faces the route.
        double dx = route.targetX - (launchBlockX + 0.5), dz = route.targetZ - (launchBlockZ + 0.5);
        double m = Math.max(Math.abs(dx), Math.abs(dz));
        this.standX = launchBlockX + 0.5 + (m > 1.0E-6 ? 0.19 * dx / m : 0);
        this.standZ = launchBlockZ + 0.5 + (m > 1.0E-6 ? 0.19 * dz / m : 0);
        log.accept("route " + route);
    }

    public Phase phase() { return phase; }
    public String failure() { return failure; }
    public int rocketsFired() { return rocketsFired; }
    public int ticks() { return tick; }
    public double maxAltitude() { return maxAltitude; }
    /** Altitude when first within the approach radius (climb leg), NaN before. */
    public double altitudeAtApproach() { return altitudeAtApproach; }
    public double touchdownSpeed() { return touchdownSpeed; }
    public double touchdownX() { return touchdownX; }
    public double touchdownY() { return touchdownY; }
    public double touchdownZ() { return touchdownZ; }
    public FlightRoute route() { return route; }
    public boolean finished() { return phase == Phase.DONE || phase == Phase.FAILED; }

    public void fail(String reason) {
        if (finished()) return;
        failure = reason;
        setPhase(Phase.FAILED);
    }

    private void setPhase(Phase next) {
        if (phase == next) return;
        log.accept("phase " + phase + " -> " + next + " tick=" + tick);
        phase = next;
        phaseTick = 0;
    }

    public Command tick(Observation o, FlightTerrain terrain) {
        tick++;
        phaseTick++;
        if (tick > timeoutTicks) fail("flight timed out after " + tick + " ticks");
        if (finished()) return hold(o);
        maxAltitude = Math.max(maxAltitude, o.y());
        return switch (phase) {
            case POSITION -> position(o);
            case JUMP -> jump(o);
            case DEPLOY -> deploy(o);
            case FLY -> fly(o, terrain);
            case LANDING -> landing(o, terrain);
            default -> hold(o);
        };
    }

    private Command hold(Observation o) {
        return new Command(o.yaw(), o.pitch(), false, false, false, false);
    }

    private Command position(Observation o) {
        float yaw = route.launchYaw();
        if (o.fallFlying() || !o.onGround()) {
            if (phaseTick > 60) fail("not standing on the launch block");
            return new Command(yaw, 0, false, false, false, false);
        }
        double dx = standX - o.x(), dz = standZ - o.z();
        double d = Math.sqrt(dx * dx + dz * dz);
        double speed = Math.sqrt(o.vx() * o.vx() + o.vz() * o.vz());
        int by = (int) Math.floor(o.y() + 1.0E-3);
        if (d > LAUNCH_SLACK || Math.abs(by - launchBlockY) > 1) {
            if (speed > 0.01 && phaseTick < 20) return new Command(yaw, 0, false, true, false, false);
            fail(String.format("bot is at %.1f %.1f %.1f, not on the launch block %d %d %d", o.x(), o.y(), o.z(),
                launchBlockX, launchBlockY, launchBlockZ));
            return hold(o);
        }
        if (d < 0.06 || (phaseTick > 40 && d < 0.35)) {
            if (speed < 0.01) {
                takeoffAttempts++;
                log.accept(String.format("takeoff attempt %d from %.2f %.2f %.2f yaw=%.1f", takeoffAttempts,
                    o.x(), o.y(), o.z(), yaw));
                setPhase(Phase.JUMP);
            }
            return new Command(yaw, 0, false, true, false, false);
        }
        if (phaseTick > 200) {
            fail("could not reach the takeoff point on the launch block");
            return hold(o);
        }
        // Sneak-walk towards the edge; Zenith's sneak edge clipping keeps the bot on the roof.
        float walkYaw = ElytraModel.yawTowards(o.x(), o.z(), standX, standZ);
        boolean step = d > speed * 2.5 + 0.02;
        return new Command(walkYaw, 0, step, true, false, false);
    }

    /** Ticks to wait for the server to confirm the elytra before leaving the launch block's footprint. */
    private int deployConfirmTicks() {
        return route.mode == FlightRoute.Mode.GLIDE ? 10 : 0;
    }

    private Command jump(Observation o) {
        float yaw = route.launchYaw();
        openTicks = 0;
        if (phaseTick == 1) return new Command(yaw, 0, false, false, true, false);
        setPhase(Phase.DEPLOY);
        return new Command(yaw, 0, false, false, false, false);
    }

    private Command deploy(Observation o) {
        float yaw = route.launchYaw();
        if (o.fallFlying()) {
            if (++openTicks == 1) log.accept(String.format("elytra open at %.2f %.2f %.2f v=(%.3f %.3f %.3f)",
                o.x(), o.y(), o.z(), o.vx(), o.vy(), o.vz()));
            // Zenith opens the elytra locally at once; the server can still refuse it a few ticks later.
            // On the glide leg hover in the slow glide over the roof until a refusal would have arrived,
            // so a refused elytra drops the bot back onto the roof instead of off its edge.
            if (openTicks > deployConfirmTicks()) {
                lastPitch = o.pitch();
                setPhase(Phase.FLY);
                return new Command(yaw, 0, false, false, false, false);
            }
            return new Command(yaw, -30, false, false, false, false);
        }
        if (openTicks > 0) {
            log.accept("the server closed the elytra during takeoff");
            openTicks = 0;
        }
        if (o.onGround() && phaseTick > 2) {
            if (takeoffAttempts >= TAKEOFF_ATTEMPTS) {
                fail("elytra did not open after " + takeoffAttempts + " jumps");
                return hold(o);
            }
            log.accept("elytra did not open; retrying takeoff");
            setPhase(Phase.POSITION);
            return hold(o);
        }
        // Zenith opens the elytra on a fresh jump press while airborne, so alternate.
        boolean press = phaseTick % 2 == 1;
        return new Command(yaw, 0, false, false, press, false);
    }

    private Command fly(Observation o, FlightTerrain terrain) {
        double r = route.remaining(o.x(), o.z());
        if (route.mode == FlightRoute.Mode.CLIMB && Double.isNaN(altitudeAtApproach) && r <= route.approachRadius) {
            altitudeAtApproach = o.y();
            log.accept(String.format("entered %.0f-block approach radius at y=%.1f (target cruise %.0f)",
                route.approachRadius, o.y(), route.cruiseY));
        }
        if (o.inWater()) {
            recordTouchdown(o);
            log.accept(String.format("touched water at %.2f %.2f %.2f, %.1f blocks from landing point",
                o.x(), o.y(), o.z(), r));
            setPhase(Phase.LANDING);
            return hold(o);
        }
        if (o.onGround() && groundBelow(o, terrain)) {
            recordTouchdown(o);
            log.accept(String.format("touchdown at %.2f %.2f %.2f h=%.3f vy=%.3f, %.2f blocks from landing point",
                o.x(), o.y(), o.z(), touchdownSpeed, o.vy(), r));
            if (route.progress(o.x(), o.z()) < 4 && r > 10) {
                // Came back down at the launch point (the server refused the elytra): take off again.
                retryTakeoff = true;
                log.accept("touched down at the launch point; will retry the takeoff");
            }
            setPhase(Phase.LANDING);
            return new Command(o.yaw(), lastPitch, false, false, false, false);
        }
        if (!o.fallFlying()) {
            // The server closed the elytra mid-air; reopen it with a fresh jump press.
            boolean press = phaseTick % 2 == 1;
            if (phaseTick % 20 == 1) log.accept(String.format("elytra closed in the air at y=%.1f; reopening", o.y()));
            return new Command(o.yaw(), lastPitch, false, false, press, false);
        }
        if (o.rocketAttached()) rocketSeen = true;
        boolean pending = !o.rocketAttached() && !rocketSeen && tick - lastFireTick < ROCKET_PENDING_TICKS;
        int boost;
        if (o.rocketAttached()) boost = Math.max(2, planner.settings().expectedRocketTicks - o.rocketAge());
        else if (pending) boost = planner.settings().expectedRocketTicks;
        else boost = 0;
        boolean mayFire = !o.rocketAttached() && !pending && o.rocketsHeld() > 0 && tick - lastFireTick > ROCKET_PENDING_TICKS
            && rocketsFired < planner.settings().maxRockets;
        var state = new ElytraModel.State(o.x(), o.y(), o.z(), o.vx(), o.vy(), o.vz());
        FlightPlanner.Decision decision = planner.decide(state, o.yaw(), o.pitch(), boost, mayFire, route, terrain);
        lastDecision = decision;
        if (decision.fireRocket()) {
            rocketsFired++;
            lastFireTick = tick;
            rocketSeen = false;
            log.accept(String.format("rocket #%d at %.1f %.1f %.1f h=%.3f vy=%.3f, %.1f blocks to go",
                rocketsFired, o.x(), o.y(), o.z(), Math.hypot(o.vx(), o.vz()), o.vy(), r));
        }
        if (phaseTick % 10 == 0 || decision.fireRocket()) {
            log.accept(String.format("t=%d pos=%.1f %.1f %.1f h=%.3f vy=%.3f yaw=%.1f pitch=%.1f ref=%.1f err=%+.1f "
                    + "togo=%.1f rocket=%s rockets=%d plan=%s%s",
                tick, o.x(), o.y(), o.z(), Math.hypot(o.vx(), o.vz()), o.vy(), decision.yaw(), decision.pitch(),
                route.referenceY(r), o.y() - route.referenceY(r), r,
                o.rocketAttached() ? "on" : pending ? "pending" : "off", rocketsFired, decision.mode(),
                decision.crashPredicted() ? " WARNING:no-clear-path" : ""));
        }
        lastPitch = decision.pitch();
        return new Command(decision.yaw(), decision.pitch(), false, false, false, decision.fireRocket());
    }

    private void recordTouchdown(Observation o) {
        touchdownSpeed = Math.hypot(o.vx(), o.vz());
        touchdownX = o.x();
        touchdownY = o.y();
        touchdownZ = o.z();
    }

    private Command landing(Observation o, FlightTerrain terrain) {
        double r = route.remaining(o.x(), o.z());
        boolean supported = o.onGround() && groundBelow(o, terrain);
        if ((supported && !o.fallFlying()) || o.inWater()) groundTicks++;
        else groundTicks = 0;
        if (groundTicks >= 3) {
            if (retryTakeoff) {
                retryTakeoff = false;
                if (takeoffAttempts >= TAKEOFF_ATTEMPTS) {
                    fail("elytra did not stay open after " + takeoffAttempts + " takeoffs");
                    return hold(o);
                }
                setPhase(Phase.POSITION);
                return hold(o);
            }
            log.accept(String.format("landed at %.2f %.2f %.2f, %.2f blocks from landing point, rockets used=%d",
                o.x(), o.y(), o.z(), r, rocketsFired));
            setPhase(Phase.DONE);
            return hold(o);
        }
        if (o.fallFlying() && !o.inWater() && !supported && !groundBelow(o, terrain, 0.6) && phaseTick > 1
            && (r > 3 || o.y() > route.targetY + 1.5)) {
            // Not actually on the ground (a server position reset or a clipped edge): keep flying.
            log.accept(String.format("airborne again at %.2f %.2f %.2f; continuing the flight", o.x(), o.y(), o.z()));
            retryTakeoff = false;
            setPhase(Phase.FLY);
            return new Command(o.yaw(), lastPitch, false, false, false, false);
        }
        if (phaseTick > 200) {
            fail("the server did not end the glide after touchdown");
            return hold(o);
        }
        double h = Math.hypot(o.vx(), o.vz());
        if (o.fallFlying() && !o.inWater() && h > 0.01) {
            // Until the server ends the glide the elytra keeps sliding at almost full speed. Facing
            // away from the slide makes the glide physics drag it down ~20% a tick instead of 1%.
            float brakeYaw = ElytraModel.wrapDegrees(ElytraModel.yawTowards(0, 0, o.vx(), o.vz()) + 180f);
            return new Command(brakeYaw, 0, false, false, false, false);
        }
        return new Command(o.yaw(), lastPitch, false, false, false, false);
    }

    /** Solid collision directly under the feet (within {@code depth} blocks). */
    private static boolean groundBelow(Observation o, FlightTerrain terrain, double depth) {
        return terrain.probe(o.x() - 0.3, o.y() - depth, o.z() - 0.3, o.x() + 0.3, o.y() + 0.01, o.z() + 0.3)
            == FlightTerrain.Contact.SOLID;
    }

    private static boolean groundBelow(Observation o, FlightTerrain terrain) {
        return groundBelow(o, terrain, 0.08);
    }

    /** The last rocket use was not executed by the bot (input rejected or click skipped); undo its bookkeeping. */
    public void rocketNotFired() {
        if (rocketsFired > 0) rocketsFired--;
        lastFireTick = -1000;
        log.accept("rocket use was not executed; it will be retried");
    }

    public FlightPlanner.Decision lastDecision() { return lastDecision; }
}
