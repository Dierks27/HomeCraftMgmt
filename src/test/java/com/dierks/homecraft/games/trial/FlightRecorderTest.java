package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.boat.BoatEnvelope;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Ice Boat flight recorder (MOUNTAIN-V21-SPEC D17) on synthetic tracks fed as {@code VehicleMoveEvent}s
 * would feed it: a boat at 40 b/s (2 blocks a tick) on flat ice, off a lip with Java's boat physics in the air
 * (the speed across the ground times 0.9 a tick, the thrust 0.04 after it while the rider holds forward, and
 * 0.04 more downward speed a tick), settling on the ice below.
 *
 * <p>Pinned here: a 2-block drop at 40 b/s is a 2-block flight of about 12-13 blocks taking off at 40 b/s, a
 * 1-block drop a 1-block flight; a teleport or a reset mid-flight drops it; stairs are two flights; a flat run
 * with ±0.002 of jitter in its height has none, and neither does a fall off the track; the summary's line is
 * the spec's ("Ice Boat flights (Java, road medium): ..."), with no player's name; the recorder keeps the last
 * 50 runs and shows the last 10 with the longest jump next to the proof's L(d); and the all-time longest goes
 * to the store only when it is beaten.
 */
class FlightRecorderTest {

    /** The air's drag a tick, the thrust a tick and the fall's speed-up a tick (Java's boat). */
    private static final double DRAG = 0.9;
    private static final double THRUST = 0.04;
    private static final double GRAVITY = 0.04;

    /** A boat on a straight run east, feeding one record a move a tick. */
    private static final class Ride {

        final FlightRecorder.Run run;
        double x = 6200.5;
        double y = 170;
        double z = 2900.5;
        long tick = 1000;

        Ride(FlightRecorder.Run run) {
            this.run = run;
        }

        /** One move to (nx, ny, z), a tick later. */
        void to(double nx, double ny) {
            tick++;
            run.move(x, y, z, nx, ny, z, tick);
            x = nx;
            y = ny;
        }

        /** {@code ticks} ticks on flat ice at {@code perTick} blocks a tick. */
        Ride flat(int ticks, double perTick) {
            for (int i = 0; i < ticks; i++) {
                to(x + perTick, y);
            }
            return this;
        }

        /**
         * Off a lip of {@code d} blocks at {@code perTick}: in the air each tick the speed across the ground is
         * times {@value #DRAG} (plus {@value #THRUST} with {@code thrust}), the fall's speed {@value #GRAVITY}
         * more, until the boat is on the ice d below (the landing tick clipped to it).
         *
         * @return the distance across the ground from the lip to the landing
         */
        double drop(int d, double perTick, boolean thrust) {
            double lipX = x;
            double ground = y - d;
            double vx = perTick;
            double vy = 0;
            while (y > ground) {
                vx = vx * DRAG + (thrust ? THRUST : 0);
                vy -= GRAVITY;
                to(x + vx, Math.max(ground, y + vy));
            }
            return x - lipX;
        }
    }

    private static Ride ride(FlightRecorder r) {
        return new Ride(r.start(false, "road", "medium"));
    }

    @Test
    void aTwoBlockDropAtFortyIsAFlightOfAboutTwelveToThirteen() {
        FlightRecorder r = new FlightRecorder();
        Ride ride = ride(r).flat(40, 2.0);
        double held = ride.drop(2, 2.0, true);
        ride.flat(20, 1.2);
        FlightRecorder.Summary s = r.close(ride.run, true);
        assertEquals(1, s.two().count(), "one 2-block flight: " + s);
        assertEquals(0, s.one().count(), "and no 1-block one");
        // the closed form: 10 ticks in the air (0.04 n (n + 1) / 2 >= 2), each 0.4 + 1.6 x 0.9^k across
        double expected = 4 + 14.4 * (1 - Math.pow(0.9, 10));
        assertEquals(expected, held, 1e-9, "the ride itself");
        assertEquals(expected, s.two().longest(), 1e-9, "the flight is lip to landing: " + s);
        assertTrue(s.two().longest() >= 12 && s.two().longest() <= 13.5, "about 12-13 blocks: " + s.two());
        assertEquals(40.0, s.two().longestSpeed(), 1e-9, "taking off at 40 b/s");
        assertEquals(40.0, s.two().fastest(), 1e-9);
        assertEquals(s.two().longest(), s.two().p90(), 1e-9, "one flight is its own p90");

        FlightRecorder coast = new FlightRecorder();
        Ride free = ride(coast).flat(10, 2.0);
        free.drop(2, 2.0, false);
        free.flat(5, 1.0);
        double off = coast.close(free.run, true).two().longest();
        assertEquals(18 * (1 - Math.pow(0.9, 10)), off, 1e-9, "with no thrust: 11.7, the air's drag alone");
        assertTrue(off < BoatEnvelope.flight(2), "well inside the proof's L(2) = 49");
    }

    @Test
    void aOneBlockDropIsAOneBlockFlight() {
        FlightRecorder r = new FlightRecorder();
        Ride ride = ride(r).flat(20, 1.9);
        double len = ride.drop(1, 1.9, true);
        ride.flat(10, 1.5);
        FlightRecorder.Summary s = r.close(ride.run, true);
        assertEquals(1, s.one().count(), "one 1-block flight: " + s);
        assertEquals(0, s.two().count());
        assertEquals(len, s.one().longest(), 1e-9, "7 ticks in the air (0.02 n (n + 1) >= 1)");
        assertEquals(38.0, s.one().longestSpeed(), 1e-9, "1.9 blocks a tick is 38 b/s");
        assertTrue(len > 9 && len < 11, "about 10 blocks: " + len);
    }

    @Test
    void aTeleportOrAResetMidFlightDropsIt() {
        FlightRecorder r = new FlightRecorder();
        Ride ride = ride(r).flat(20, 2.0);
        // four ticks into the air, then the run's own reset: drop() (TimeTrials.move)
        double vx = 2.0;
        double vy = 0;
        for (int i = 0; i < 4; i++) {
            vx *= DRAG;
            vy -= GRAVITY;
            ride.to(ride.x + vx, ride.y + vy);
        }
        assertTrue(ride.run.flying(), "in the air");
        ride.run.drop();
        assertFalse(ride.run.flying(), "the reset forgets it");
        // back at a checkpoint 2 lower and on again: no flight from the lip to there
        ride.x = 6000.5;
        ride.y = 168;
        ride.flat(30, 2.0);
        FlightRecorder.Summary s = r.close(ride.run, false);
        assertEquals(0, s.drops(), "nothing recorded across the reset: " + s);

        // a teleport nobody told the recorder about: the next move doesn't start where the last one ended
        FlightRecorder t = new FlightRecorder();
        Ride moved = ride(t).flat(20, 2.0);
        vx = 2.0;
        vy = 0;
        for (int i = 0; i < 5; i++) {
            vx *= DRAG;
            vy -= GRAVITY;
            moved.to(moved.x + vx, moved.y + vy);
        }
        assertTrue(moved.run.flying());
        moved.x += 300; // put somewhere else, 2 lower: the next move starts there
        moved.y -= 2;
        moved.flat(30, 2.0);
        assertEquals(0, t.close(moved.run, false).drops(), "a move from somewhere else is no landing");
    }

    @Test
    void stairsAreTwoFlights() {
        FlightRecorder r = new FlightRecorder();
        Ride ride = ride(r).flat(30, 2.0);
        double first = ride.drop(1, 2.0, true);
        ride.flat(8, 1.6);
        double second = ride.drop(2, 1.6, true);
        ride.flat(10, 1.2);
        FlightRecorder.Summary s = r.close(ride.run, true);
        assertEquals(2, s.drops(), "two flights: " + s);
        assertEquals(1, s.one().count());
        assertEquals(1, s.two().count());
        assertEquals(first, s.one().longest(), 1e-9);
        assertEquals(second, s.two().longest(), 1e-9);
        assertEquals(32.0, s.two().longestSpeed(), 1e-9, "the second took off at the run-up's 1.6 a tick");
    }

    @Test
    void aFlatRunWithJitterHasNoFlights() {
        FlightRecorder r = new FlightRecorder();
        Ride ride = ride(r);
        Random noise = new Random(7);
        for (int i = 0; i < 2000; i++) {
            ride.to(ride.x + 1.8, 170 + (noise.nextDouble() * 2 - 1) * 0.002);
        }
        assertEquals(0, r.close(ride.run, true).drops(), "no false flights on a flat run");
    }

    @Test
    void aFallOffTheTrackIsNoDrop() {
        FlightRecorder r = new FlightRecorder();
        Ride ride = ride(r).flat(20, 2.0);
        ride.drop(4, 2.0, true); // 4 blocks down: round(y0 - y1) is neither 1 nor 2
        ride.flat(10, 1.0);
        ride.drop(2, 1.0, false);
        ride.flat(10, 1.0);
        FlightRecorder.Summary s = r.close(ride.run, true);
        assertEquals(0, s.one().count());
        assertEquals(1, s.two().count(), "only the 2-block one: " + s);
    }

    @Test
    void theTakeOffSpeedIsOverTheLastThreeTicksEvenWithTwoMovesInOne() {
        // a late packet: no move at one server tick, two (each a full tick of riding) at the next
        FlightRecorder r = new FlightRecorder();
        Ride late = ride(r).flat(10, 2.0);
        late.tick++; // nothing arrived this tick
        late.tick++;
        late.run.move(late.x, 170, late.z, late.x + 2, 170, late.z, late.tick);
        late.run.move(late.x + 2, 170, late.z, late.x + 4, 170, late.z, late.tick);
        late.x += 4;
        late.flat(1, 2.0);
        late.drop(2, 2.0, true);
        late.flat(5, 1.0);
        assertEquals(40.0, r.close(late.run, true).two().longestSpeed(), 1e-9, "6 blocks over 3 ticks");

        // a catch-up: a move every tick and a spare one in the same tick: 4 moves in 3 ticks is 4 ticks of riding
        FlightRecorder c = new FlightRecorder();
        Ride burst = ride(c).flat(10, 2.0);
        burst.tick++;
        burst.run.move(burst.x, 170, burst.z, burst.x + 2, 170, burst.z, burst.tick);
        burst.run.move(burst.x + 2, 170, burst.z, burst.x + 4, 170, burst.z, burst.tick);
        burst.x += 4;
        burst.flat(1, 2.0);
        burst.drop(2, 2.0, true);
        burst.flat(5, 1.0);
        assertEquals(40.0, c.close(burst.run, true).two().longestSpeed(), 1e-9, "8 blocks over 4 moves, not 3 ticks");
    }

    @Test
    void theConsoleLineIsTheSpecsAndNamesNoPlayer() {
        FlightRecorder r = new FlightRecorder();
        Ride ride = ride(r).flat(30, 2.0);
        for (int i = 0; i < 3; i++) {
            ride.drop(2, 2.0, true);
            ride.flat(30, 2.0);
        }
        ride.drop(1, 2.0, true);
        ride.flat(10, 2.0);
        FlightRecorder.Summary s = r.close(ride.run, true);
        assertEquals(4, s.drops());
        String two = FlightRecorder.tenth(4 + 14.4 * (1 - Math.pow(0.9, 10)));
        assertTrue(s.line().startsWith("Ice Boat flights (Java, road medium): 4 drops; 2-block: 3, longest " + two
                + " at 40.0 b/s, p90 " + two + "; 1-block: 1, longest "), s.line());
        assertTrue(s.line().endsWith(" at 40.0 b/s, p90 " + FlightRecorder.tenth(s.one().longest())), s.line());

        FlightRecorder b = new FlightRecorder();
        FlightRecorder.Run run = b.start(true, "slalom", "hard");
        assertEquals("Ice Boat flights (Bedrock, slalom hard, not finished): 0 drops; 2-block: 0; 1-block: 0",
                b.close(run, false).line(), "the edition, the course, whether it finished; nothing else");
    }

    @Test
    void theP90IsTheNearestRank() {
        FlightRecorder.Flights f = new FlightRecorder.Flights();
        for (int i = 1; i <= 20; i++) {
            f.add(i, 30 + i);
        }
        FlightRecorder.Stat s = f.stat();
        assertEquals(20, s.count());
        assertEquals(20, s.longest(), 0);
        assertEquals(50, s.longestSpeed(), 0);
        assertEquals(50, s.fastest(), 0);
        assertEquals(18, s.p90(), 0, "the 18th of 20 sorted (ceil(0.9 x 20))");
    }

    @Test
    void theCommandSaysSoWhenNothingWasRecorded() {
        List<String> lines = new FlightRecorder().lines();
        assertEquals(2, lines.size(), "a heading and one plain line: " + lines);
        assertTrue(lines.get(1).contains("Nothing recorded since the restart yet"), lines.get(1));
    }

    @Test
    void theCommandShowsTheLastTenRunsAndTheLongestNextToTheSafetyRule() {
        FlightRecorder r = new FlightRecorder();
        for (int i = 0; i < 60; i++) {
            Ride ride = ride(r).flat(30, 2.0);
            ride.drop(2, 2.0, i == 37); // run 37 holds forward in the air: the longest
            ride.flat(10, 1.0);
            r.close(ride.run, true);
        }
        assertEquals(FlightRecorder.KEEP, r.recent().size(), "the last 50 runs are kept");
        List<String> lines = r.lines();
        long runLines = lines.stream().filter(l -> l.startsWith("&fIce Boat flights (")).count();
        assertEquals(FlightRecorder.SHOW, runLines, "the last 10 shown: " + lines);
        String longest = FlightRecorder.tenth(4 + 14.4 * (1 - Math.pow(0.9, 10)));
        assertTrue(lines.contains("&7Longest 2-block jump (Java): &f" + longest + " blocks &7at 40.0 b/s, fastest"
                + " take-off 40.0 b/s &8(the safety rule allows 49)"), "this restart's is the all-time one: " + lines);
        assertTrue(lines.stream().noneMatch(l -> l.contains("1-block jump")), "no 1-block flights, no line for them");
    }

    @Test
    void theAllTimeLongestIsStoredOnlyWhenBeaten() {
        Map<String, String> kept = new HashMap<>();
        kept.put("gen.boat.flights.java.2", "12.00 39.50");
        int[] writes = {0};
        FlightRecorder.Store store = new FlightRecorder.Store() {
            @Override
            public String get(String key) {
                return kept.get(key);
            }

            @Override
            public void set(String key, String value) {
                writes[0]++;
                kept.put(key, value);
            }
        };
        FlightRecorder r = new FlightRecorder(store);
        Ride slow = ride(r).flat(20, 2.0);
        slow.drop(2, 2.0, false); // 11.7: not beaten
        slow.flat(5, 1.0);
        r.close(slow.run, true);
        assertEquals(0, writes[0], "12.0 stands");
        assertTrue(r.lines().stream().anyMatch(l -> l.contains("; all time 12.0 blocks")), "" + r.lines());

        Ride fast = ride(r).flat(20, 2.0);
        fast.drop(2, 2.0, true); // 13.4: beaten
        fast.flat(5, 1.5);
        fast.drop(1, 1.5, false);
        fast.flat(5, 1.0);
        r.close(fast.run, true);
        assertEquals(2, writes[0], "the 2-block record and the first 1-block one");
        assertEquals(String.format(java.util.Locale.ROOT, "%.2f 40.00", 4 + 14.4 * (1 - Math.pow(0.9, 10))),
                kept.get("gen.boat.flights.java.2"));
        assertTrue(kept.containsKey("gen.boat.flights.java.1"));
        assertNull(kept.get("gen.boat.flights.bedrock.2"), "per edition");
        assertTrue(new FlightRecorder(store).lines().stream().anyMatch(l -> l.startsWith(
                "&7Longest 2-block jump (Java): none since the restart; all time 13.4 blocks")),
                "after a restart the all-time longest is still there");
    }

    @Test
    void aStoredValueIsReadBackOrIgnored() {
        assertEquals(new FlightRecorder.Best(12.43, 39.81), FlightRecorder.Best.parse("12.43 39.81"));
        assertEquals("12.43 39.81", new FlightRecorder.Best(12.43, 39.81).text());
        assertTrue(Double.isNaN(FlightRecorder.Best.parse(new FlightRecorder.Best(9.5, Double.NaN).text()).speed()),
                "an unknown take-off speed comes back unknown");
        for (String junk : new String[]{null, "", "12.4", "a b", "-1 30", "NaN 30", "1 2 3"}) {
            assertNull(FlightRecorder.Best.parse(junk), "junk is no record: " + junk);
        }
    }

    @Test
    void onlyAMountainRunOfAlgoFourOrLaterIsRecorded() {
        assertTrue(FlightRecorder.records(MountainRunsV2.road()), "a Mountain Run v2 (algo 4)");
        assertTrue(FlightRecorder.records(MountainRunsV2.of(MountainRunsV2.tag(MountainRunsV2.SLALOM_SEED,
                MountainRunsV2.MODEL_MS, 5, 7))), "and 0.37.1's algo 5");
        assertFalse(FlightRecorder.records(MountainRunsV2.of(MountainRunsV2.tag(MountainRunsV2.ROAD_SEED,
                MountainRunsV2.MODEL_MS, 3, 7))), "not the algo-3 spiral");
        assertFalse(FlightRecorder.records(MountainRunsV2.of(null)), "not a hand-built boat track");
        assertFalse(FlightRecorder.records(MountainRuns.medium()), "not 0.36's Mountain Run");
        assertFalse(FlightRecorder.records(null));

        FlightRecorder r = new FlightRecorder();
        assertTrue(r.close(r.start(false, MountainRunsV2.road()), true).line()
                .startsWith("Ice Boat flights (Java, road medium): "), "the style off the seed, the course's tier");
        assertTrue(r.close(r.start(true, MountainRunsV2.slalom()), true).line()
                .startsWith("Ice Boat flights (Bedrock, slalom medium): "));
    }
}
