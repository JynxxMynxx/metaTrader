package dev.zenith.trader.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end flights of both raid legs through {@link FlightController} against
 * a model of the base. These pin the behaviour asked for: the outbound leg
 * glides the straight launch-to-farm line (about 17 degrees) without rockets and
 * lands slowly on the platform; the return leg is at about y160 by 50 blocks
 * out, uses few rockets and lands on the roof block.
 */
class FlightPlannerTest {
    private static final FlightTestWorld WORLD = new FlightTestWorld();

    @Test
    void noseUpGlideMatchesTheRaidLineAngle() {
        var s = new ElytraModel.State(0, 0, 0, 0, 0, 0.3);
        for (int i = 0; i < 2000; i++) ElytraModel.glide(s, 0, -30);
        double slope = -s.vy / s.horizontalSpeed();
        assertEquals(0.315, slope, 0.01, "steady glide slope at -30 pitch");
        assertEquals(0.344, s.horizontalSpeed(), 0.01, "steady horizontal speed at -30 pitch");
        assertEquals(17.0, FlightTestWorld.outbound().glideAngleDegrees(), 0.1);
    }

    @Test
    void climbReferenceIsCruiseHeightAtTheApproachRadius() {
        var route = FlightTestWorld.home();
        assertEquals(160.0, route.referenceY(50), 1.0E-9);
        assertEquals(141.0, route.referenceY(0), 1.0E-9);
        assertTrue(route.referenceY(100) > 160);
    }

    @Test
    void outboundGlideLandsOnThePlatformWithoutRockets() {
        for (var server : new FlightTestWorld.Server[]{FlightTestWorld.Server.NORMAL, FlightTestWorld.Server.LAGGY}) {
            var flight = WORLD.fly(FlightTestWorld.outbound(), FlightTestWorld.LAUNCH_X, FlightTestWorld.LAUNCH_Y,
                FlightTestWorld.LAUNCH_Z, FlightTestWorld.LAUNCH_X + 0.31, FlightTestWorld.LAUNCH_Z + 0.69, server, 7);
            var c = flight.controller();
            assertEquals(FlightController.Phase.DONE, c.phase(), () -> c.failure() + "\n" + String.join("\n", flight.log()));
            assertEquals(0, c.rocketsFired(), "outbound glide must not use rockets");
            double touchdown = c.route().remaining(c.touchdownX(), c.touchdownZ());
            assertTrue(touchdown < 1.0, "touchdown " + touchdown + " blocks from the landing block");
            assertTrue(c.touchdownSpeed() < 0.4, "touchdown speed " + c.touchdownSpeed());
            assertTrue(flight.maxLineError() < 1.5, "left the glide line by " + flight.maxLineError());
            assertEquals(0.0, flight.wallDamage(), 1.0E-9);
            assertTrue(c.route().remaining(flight.finalX(), flight.finalZ()) < 5, "slid too far");
        }
    }

    @Test
    void returnFlightIsHighBeforeTheApproachAndLandsOnTheRoof() {
        for (var server : new FlightTestWorld.Server[]{FlightTestWorld.Server.NORMAL, FlightTestWorld.Server.LAGGY}) {
            var flight = WORLD.fly(FlightTestWorld.home(), FlightTestWorld.RAID_X, FlightTestWorld.RAID_Y,
                FlightTestWorld.RAID_Z, FlightTestWorld.RAID_X + 0.65, FlightTestWorld.RAID_Z + 0.33, server, 11);
            var c = flight.controller();
            assertEquals(FlightController.Phase.DONE, c.phase(), () -> c.failure() + "\n" + String.join("\n", flight.log()));
            assertTrue(c.rocketsFired() >= 1 && c.rocketsFired() <= 3, "rockets used: " + c.rocketsFired());
            assertTrue(c.altitudeAtApproach() >= 156, "altitude at 50 blocks: " + c.altitudeAtApproach());
            double touchdown = c.route().remaining(c.touchdownX(), c.touchdownZ());
            assertTrue(touchdown < 1.0, "touchdown " + touchdown + " blocks from the landing block");
            assertTrue(c.touchdownSpeed() < 0.4, "touchdown speed " + c.touchdownSpeed());
            assertEquals(0.0, flight.wallDamage(), 1.0E-9);
        }
    }

    @Test
    void refusedElytraDropsBackOnTheRoofAndRetries() {
        var server = FlightTestWorld.Server.NORMAL.refusing(2, 8);
        var flight = WORLD.fly(FlightTestWorld.outbound(), FlightTestWorld.LAUNCH_X, FlightTestWorld.LAUNCH_Y,
            FlightTestWorld.LAUNCH_Z, FlightTestWorld.LAUNCH_X + 0.5, FlightTestWorld.LAUNCH_Z + 0.5, server, 3);
        var c = flight.controller();
        assertEquals(FlightController.Phase.DONE, c.phase(), () -> c.failure() + "\n" + String.join("\n", flight.log()));
        var attempts = flight.log().stream().filter(l -> l.startsWith("takeoff attempt")).toList();
        assertTrue(attempts.size() >= 2, "expected a retry after the refused elytra");
        // The retry starts from the roof: the refused elytra dropped the bot back onto the launch block.
        assertTrue(attempts.get(1).contains(" 141.00 "), attempts.get(1));
        assertTrue(c.route().remaining(c.touchdownX(), c.touchdownZ()) < 1.0, "did not land on the platform");
    }

    @Test
    void serverSetbackMidAirDoesNotEndTheFlight() {
        var server = FlightTestWorld.Server.NORMAL.setbackAt(300);
        var flight = WORLD.fly(FlightTestWorld.outbound(), FlightTestWorld.LAUNCH_X, FlightTestWorld.LAUNCH_Y,
            FlightTestWorld.LAUNCH_Z, FlightTestWorld.LAUNCH_X + 0.5, FlightTestWorld.LAUNCH_Z + 0.5, server, 5);
        var c = flight.controller();
        assertEquals(FlightController.Phase.DONE, c.phase(), () -> c.failure() + "\n" + String.join("\n", flight.log()));
        assertTrue(c.route().remaining(c.touchdownX(), c.touchdownZ()) < 1.0, "did not land on the platform");
        assertEquals(0, c.rocketsFired());
    }
}
