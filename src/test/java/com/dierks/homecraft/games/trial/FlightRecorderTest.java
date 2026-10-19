package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.boat.BoatEnvelope;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * 1-block drop a 1-block flight; a reset mid-flight drops it, and so does a teleport nobody announced (a move
 * starting more than {@code GAP} from where the last ended, along x, along z or down, or longer than
 * {@code MAX_STEP}), where the same drop without the jump is a flight; stairs are two flights; a flat run
 * with ±0.002 of jitter in its height has none, and neither does a fall off the track; a flight whose take-off
 * or landing came in a merged move (two ticks of riding or more: a late packet, an early one, a stall's queue)
 * counts but is kept out of the longest, the p90 and the all-time record, its take-off speed standing in the
 * fastest; the take-off speed is over the ticks of riding the moves covered, whatever the server's ticks did,
 * and a stall on the run-up lags nothing; the landing hook hears each kept flight once, where it settled, with
 * its d; the summary's line is the spec's ("Ice Boat flights (Java, road medium): ..."), with no player's name;
 * the recorder keeps the last 50 runs and shows the last 10 with the longest jump next to the proof's L(d); and
 * the all-time longest goes to the store only when it is beaten.
 *
 * <p>The tracks are fed as Paper feeds them ({@link Ride}): one move a server tick at most, covering the ticks of
 * riding whose packets that tick handled.
 */
class FlightRecorderTest {

    /** The air's drag a tick, the thrust a tick and the fall's speed-up a tick (Java's boat). */
    private static final double DRAG = 0.9;
    private static final double THRUST = 0.04;
    private static final double GRAVITY = 0.04;

    /**
     * A boat on a straight run east, feeding one record a move a server tick, as Paper does: each tick's move covers
     * the ticks of riding whose packets it handled, one unless {@link #bins} says otherwise (TimeTrialsFlightsTest
     * rides it too).
     */
    static final class Ride {

        final FlightRecorder.Run run;
        double x = 6200.5;
        double y = 170;
        double z = 2900.5;
        /** The server tick. */
        long tick = 1000;
        /** The server ticks to come: how many ticks of riding each hands over (0: none, no move); then one each. */
        private final ArrayDeque<Integer> bins = new ArrayDeque<>();
        /** Ticks ridden since the last move reached the recorder, and where it ended. */
        private int owed;
        private double hx;
        private double hy;

        Ride(FlightRecorder.Run run) {
            this.run = run;
        }

        /** One tick of riding to (nx, ny, z): a move at the next server tick, or with the next as the bins say. */
        void to(double nx, double ny) {
            if (owed == 0) {
                hx = x;
                hy = y;
            }
            x = nx;
            y = ny;
            owed++;
            while (!bins.isEmpty() && bins.peekFirst() == 0) {
                bins.pollFirst();
                tick++; // a server tick that got no packet: no move
            }
            if (owed < (bins.isEmpty() ? 1 : bins.peekFirst())) {
                return; // this server tick's move waits for the next tick's riding too
            }
            bins.pollFirst();
            tick++;
            run.move(hx, hy, z, x, y, z, tick);
            owed = 0;
        }

        /**
         * From {@code after} ticks of riding from now, the server ticks hand over {@code each} ticks of riding
         * each, one by one (0: a tick with no packet), and then one each again.
         */
        Ride bins(int after, int... each) {
            for (int i = 0; i < after; i++) {
                bins.addLast(1);
            }
            for (int n : each) {
                bins.addLast(n);
            }
            return this;
        }

        /**
         * The tick {@code after} ticks from now and the one after it reach the recorder as one merged move, at the
         * second (a late packet: Paper's move with a tick that got no packet before it).
         */
        Ride merge(int after) {
            return bins(after, 0, 2);
        }

        /**
         * The tick {@code after} ticks from now and the one after it reach the recorder as one merged move, at the
         * first (an early packet: the next server tick gets none).
         */
        Ride early(int after) {
            return bins(after, 2, 0);
        }

        /**
         * The tick {@code after} ticks from now and the {@code n - 1} after it reach the recorder as one move at
         * one server tick (a stall's late tick handing over the packets queued up, the server's tick count on by
         * one only), the server then catching up ({@code caughtUp}: {@code n - 1} ticks with no packet) or not.
         */
        Ride stall(int after, int n, boolean caughtUp) {
            bins(after, n);
            for (int i = 1; caughtUp && i < n; i++) {
                bins.addLast(0);
            }
            return this;
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

    /** A ride off a lip at y 170, {@code ticks} ticks into the air (0.04 x n (n + 1) / 2 under it, 0.6 after 5). */
    private static Ride inTheAir(FlightRecorder r, int ticks) {
        Ride ride = ride(r).flat(20, 2.0);
        double vx = 2.0;
        double vy = 0;
        for (int i = 0; i < ticks; i++) {
            vx *= DRAG;
            vy -= GRAVITY;
            ride.to(ride.x + vx, ride.y + vy);
        }
        assertTrue(ride.run.flying(), "fixture: in the air");
        return ride;
    }

    @Test
    void aResetMidFlightDropsIt() {
        FlightRecorder r = new FlightRecorder();
        Ride ride = inTheAir(r, 4);
        ride.run.drop(); // the run's own reset: drop() (TimeTrials.move)
        assertFalse(ride.run.flying(), "the reset forgets it");
        // back at a checkpoint 2 lower and on again: no flight from the lip to there
        ride.x = 6000.5;
        ride.y = 168;
        ride.flat(30, 2.0);
        FlightRecorder.Summary s = r.close(ride.run, false);
        assertEquals(0, s.drops(), "nothing recorded across the reset: " + s);
    }

    // A teleport nobody told the recorder about, 5 ticks into the air (y 169.4, y0 170), put down 0.6 lower
    // (settling at 168.8: d 1) or 1.6 lower (167.8: d 2), so only the teleport guard can keep it out: the same
    // drop with no jump is a flight (theSameDropWithNoJumpIsAFlight).

    @Test
    void anUnannouncedTeleportAcrossDropsTheFlight() {
        FlightRecorder r = new FlightRecorder();
        Ride moved = inTheAir(r, 5);
        moved.x += 300; // put 300 east and 0.6 lower: the next move starts there (GAP across)
        moved.y -= 0.6;
        moved.to(moved.x + 2, moved.y);
        assertFalse(moved.run.flying(), "the move from somewhere else forgets the flight at once");
        moved.flat(30, 2.0);
        FlightRecorder.Summary s = r.close(moved.run, false);
        assertEquals(0, s.drops(), "a move from somewhere else is no landing, though it settles 1 lower: " + s);
    }

    @Test
    void anUnannouncedTeleportAlongZDropsTheFlight() {
        // a course running north or south: put 300 south and 0.6 lower, the next move starting there (GAP along z)
        FlightRecorder r = new FlightRecorder();
        Ride moved = inTheAir(r, 5);
        moved.z += 300;
        moved.y -= 0.6;
        moved.to(moved.x + 2, moved.y);
        assertFalse(moved.run.flying(), "the move from 300 along z forgets the flight at once");
        moved.flat(30, 2.0);
        FlightRecorder.Summary s = r.close(moved.run, false);
        assertEquals(0, s.drops(), "no 1-block flight from a move that started elsewhere along z: " + s);
    }

    @Test
    void anUnannouncedTeleportDownDropsTheFlight() {
        FlightRecorder r = new FlightRecorder();
        Ride moved = inTheAir(r, 5);
        moved.y -= 1.6; // put 1.6 lower, right there: the next move starts there (GAP down)
        moved.to(moved.x + 2, moved.y);
        assertFalse(moved.run.flying(), "the move from 1.6 under where the last ended forgets the flight");
        moved.flat(30, 2.0);
        FlightRecorder.Summary s = r.close(moved.run, false);
        assertEquals(0, s.drops(), "no 2-block flight from a move that started elsewhere: " + s);
    }

    @Test
    void aMoveLongerThanMaxStepDropsTheFlight() {
        for (double down : new double[]{0.6, 1.6}) {
            FlightRecorder r = new FlightRecorder();
            Ride moved = inTheAir(r, 5);
            // from exactly where the last move ended, but farther across the ground than a boat goes in a tick
            moved.to(moved.x + FlightRecorder.MAX_STEP + 0.5, moved.y - down);
            assertFalse(moved.run.flying(), down + " lower: a move longer than MAX_STEP forgets the flight");
            moved.flat(30, 2.0);
            FlightRecorder.Summary s = r.close(moved.run, false);
            assertEquals(0, s.drops(), down + " lower, " + (FlightRecorder.MAX_STEP + 0.5) + " across: " + s);
        }
    }

    @Test
    void theSameDropWithNoJumpIsAFlight() {
        // the teleport cases' geometry with nothing jumped: each would be a flight but for the guard
        double[][] cases = {{2, 0.6, 1}, {2, 1.6, 2}, {FlightRecorder.MAX_STEP - 0.5, 0.6, 1}};
        for (double[] c : cases) {
            FlightRecorder r = new FlightRecorder();
            Ride ride = inTheAir(r, 5);
            ride.to(ride.x + c[0], ride.y - c[1]);
            assertTrue(ride.run.flying(), "still in the air: " + c[0] + " across, " + c[1] + " down");
            ride.flat(30, 2.0);
            FlightRecorder.Summary s = r.close(ride.run, false);
            assertEquals(1, s.drops(), c[0] + " across, " + c[1] + " down: one flight: " + s);
            assertEquals(1, (c[2] == 1 ? s.one() : s.two()).count(), "a " + (int) c[2] + "-block flight: " + s);
        }
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
    void theTakeOffSpeedIsOverTheTicksOfRidingInTheLastThree() {
        // a late packet in Paper's shape: no move at one server tick, then one move covering both (4 blocks) in the
        // last 3 ticks before the lip: 6 blocks over the 3 ticks of riding, not over the 2 moves (60 b/s)
        FlightRecorder r = new FlightRecorder();
        Ride late = ride(r).flat(10, 2.0).merge(0).flat(3, 2.0);
        late.drop(2, 2.0, true);
        late.flat(5, 1.0);
        assertEquals(40.0, r.close(late.run, true).two().longestSpeed(), 1e-9, "6 blocks over 3 ticks of riding");

        // a stall two ticks before the lip: three ticks of riding (6 blocks) in one move at one server tick, so the
        // 3 server ticks back from the lip hold 5 ticks of riding (10 blocks), not 3 (66.7 b/s)
        for (boolean caughtUp : new boolean[]{false, true}) {
            FlightRecorder st = new FlightRecorder();
            Ride stalled = ride(st).flat(37, 2.0).stall(0, 3, caughtUp).flat(3, 2.0);
            double len = stalled.drop(2, 2.0, true);
            stalled.flat(20, 1.2);
            FlightRecorder.Stat two = st.close(stalled.run, true).two();
            assertEquals(40.0, two.longestSpeed(), 1e-9,
                    "caught up " + caughtUp + ": 10 blocks over 5 ticks of riding");
            assertEquals(0, two.lagged(), "caught up " + caughtUp + ": the stall ended on the lip, at no end: " + two);
            assertEquals(len, two.longest(), 1e-9, "measured: the ticks with no move after it made up for it");
        }

        // that stall a tick earlier, caught up: the lip's move comes after the two ticks with no move, 3 server
        // ticks after the stall's but a tick of riding (2 blocks): 40 b/s, not 13.3 over the server's ticks
        FlightRecorder cu = new FlightRecorder();
        Ride caught = ride(cu).flat(36, 2.0).stall(0, 3, true).flat(4, 2.0);
        caught.drop(2, 2.0, true);
        caught.flat(20, 1.2);
        assertEquals(40.0, cu.close(caught.run, true).two().longestSpeed(), 1e-9, "a tick of riding, 2 blocks");

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

    /** A store over {@code kept}. */
    private static FlightRecorder.Store store(Map<String, String> kept) {
        return new FlightRecorder.Store() {
            @Override
            public String get(String key) {
                return kept.get(key);
            }

            @Override
            public void set(String key, String value) {
                kept.put(key, value);
            }
        };
    }

    @Test
    void aFlightAcrossAMergedMoveCountsButIsKeptOutOfTheLengths() {
        Map<String, String> kept = new HashMap<>();
        FlightRecorder r = new FlightRecorder(store(kept));
        double held = 4 + 14.4 * (1 - Math.pow(0.9, 10)); // 13.4, holding forward
        double coast = 18 * (1 - Math.pow(0.9, 10)); // 11.7, not
        // the take-off: the last tick on the ground and the first in the air come as one move
        Ride lip = ride(r).flat(39, 2.0).merge(0).flat(1, 2.0);
        assertEquals(held, lip.drop(2, 2.0, true), 1e-9, "fixture: the ride itself is the same");
        lip.flat(20, 1.2);
        // the landing: the tick it lands and the first on the ice after it come as one move (10 in the air), off a
        // faster run-up (42 b/s) than any other flight here
        lip.flat(30, 2.1).merge(9).drop(2, 2.1, true);
        lip.flat(20, 1.2);
        // a merged move in the air, and one on the run-up: no end of a flight, so it is measured
        lip.flat(10, 2.0).merge(3).flat(20, 2.0).merge(4).drop(2, 2.0, false);
        lip.flat(20, 1.2);
        FlightRecorder.Summary s = r.close(lip.run, true);
        assertEquals(3, s.two().count(), "all three count: " + s);
        assertEquals(2, s.two().lagged(), "two of them across a merged move: " + s);
        assertEquals(coast, s.two().longest(), 1e-9, "the longest is the one measured cleanly, not 13.4 and a tick");
        assertEquals(coast, s.two().p90(), 1e-9, "and so is the p90");
        assertEquals(42.0, s.two().fastest(), 1e-9,
                "a lagged flight's take-off speed stands in the fastest: it is measured on the ground before it");
        assertEquals(coast, s.best(), 1e-9);
        assertTrue(s.line().contains("2-block: 3 (2 lagged), longest " + FlightRecorder.tenth(coast) + " at 40.0 b/s"),
                s.line());
        assertEquals(String.format(java.util.Locale.ROOT, "%.2f 40.00", coast), kept.get("gen.boat.flights.java.2"),
                "the all-time record is the clean one");
        assertTrue(r.lines().contains("&7Longest 2-block jump (Java): &f" + FlightRecorder.tenth(coast) + " blocks &7at"
                + " 40.0 b/s, fastest take-off 42.0 b/s &8(the safety rule allows 49)"), "" + r.lines());

        // a run whose only flight is lagged (7 ticks in the air, the 7th merged): counted, no longest, no record
        Map<String, String> none = new HashMap<>();
        FlightRecorder l = new FlightRecorder(store(none));
        Ride late = ride(l).flat(30, 2.0).merge(6);
        late.drop(1, 2.0, true);
        late.flat(10, 1.0);
        FlightRecorder.Summary one = l.close(late.run, true);
        assertEquals(1, one.drops());
        assertTrue(Double.isNaN(one.one().longest()) && Double.isNaN(one.one().p90()), "no length: " + one.one());
        assertTrue(Double.isNaN(one.best()), "no best jump");
        assertTrue(one.line().endsWith("; 1-block: 1 (1 lagged)"), one.line());
        assertEquals(Map.of(), none, "nothing for the record");
        assertTrue(l.lines().contains("&7No 1- or 2-block jumps measured in those runs yet."), "" + l.lines());
    }

    @Test
    void anEndInOneMoveWithTheNextTickIsLaggedHoweverTheServerTicksFell() {
        // the lip's tick and the first in the air, or the landing's and the first on the ice after it, in one move
        // with no tick missing before it: a packet ahead of its tick (2, then a tick with none), and a stall's late
        // tick handing over 3 ticks of riding at once, caught up after or not. Each would add a tick or two of
        // ground travel to a 13.4-block flight (15.4 off the lip, 14.6 and 15.8 at the landing)
        double held = 4 + 14.4 * (1 - Math.pow(0.9, 10));
        for (boolean atLip : new boolean[]{true, false}) {
            for (int shape = 0; shape < 3; shape++) {
                FlightRecorder r = new FlightRecorder();
                int[] heard = {0};
                Ride ride = new Ride(r.start(false, "road", "medium", (f, x, y, z, d) -> heard[0]++));
                ride.flat(atLip ? 39 : 40, 2.0);
                int after = atLip ? 0 : 9; // the lip's tick (the 40th on the flat), or the 10th in the air
                if (shape == 0) {
                    ride.early(after);
                } else {
                    ride.stall(after, 3, shape == 2);
                }
                if (atLip) {
                    ride.flat(1, 2.0);
                }
                assertEquals(held, ride.drop(2, 2.0, true), 1e-9, "fixture: the ride itself is the same");
                ride.flat(20, 1.2);
                String what = (atLip ? "the lip" : "the landing") + ", " + (shape == 0 ? "an early packet"
                        : shape == 1 ? "a stall" : "a stall caught up");
                FlightRecorder.Summary s = r.close(ride.run, true);
                assertEquals(1, s.two().count(), what + ": the flight counts: " + s);
                assertEquals(1, s.two().lagged(), what + ": lagged: " + s);
                assertTrue(Double.isNaN(s.two().longest()) && Double.isNaN(s.best()), what + ": no length: " + s);
                assertEquals(40.0, s.two().fastest(), 1e-9, what + ": taking off at 40 b/s, measured on the ground");
                assertEquals(1, heard[0], what + ": its landing was real");
            }
        }

        // a boat too slow for its moves' lengths to tell (under MIN_PACE a tick in the air): a late packet at the
        // landing is still told by the server tick with no move before it
        FlightRecorder r = new FlightRecorder();
        Ride slow = ride(r).flat(20, 0.3).merge(6);
        slow.drop(1, 0.3, false); // 7 ticks in the air, the 7th merged with the first on the ice
        slow.flat(10, 0.3);
        FlightRecorder.Stat one = r.close(slow.run, true).one();
        assertEquals(1, one.count(), "the flight counts: " + one);
        assertEquals(1, one.lagged(), "lagged: " + one);
    }

    @Test
    void theLandingIsHeardOncePerFlightWithItsD() {
        List<double[]> heard = new ArrayList<>();
        FlightRecorder r = new FlightRecorder();
        FlightRecorder.Run run = r.start(false, "road", "medium",
                (f, x, y, z, d) -> heard.add(new double[]{d, x, y, z}));
        Ride ride = new Ride(run).flat(30, 2.0);
        double lip = ride.x;
        double first = ride.drop(1, 2.0, true);
        assertEquals(List.of(), heard, "not in the air, nor the tick it touches down: the tick it settles");
        ride.flat(1, 1.6);
        assertEquals(1, heard.size(), "the tick it settles");
        ride.flat(7, 1.6);
        double second = ride.x;
        second += ride.drop(2, 1.6, true);
        ride.flat(10, 1.2);
        ride.drop(4, 1.2, true); // a fall off the track: no flight, nothing heard
        ride.flat(10, 1.0);
        Random noise = new Random(7);
        double flat = ride.y;
        for (int i = 0; i < 400; i++) {
            ride.to(ride.x + 1.8, flat + (noise.nextDouble() * 2 - 1) * 0.002); // jitter: nothing heard
        }
        ride.flat(10, 2.0).merge(6).drop(1, 2.0, true); // a lagged flight: its landing was real
        ride.flat(10, 1.0);
        assertEquals(3, heard.size(), "once a kept flight: " + heard.size());
        assertEquals(List.of(1.0, 2.0, 1.0), heard.stream().map(h -> h[0]).toList(), "each with its d");
        assertEquals(lip + first, heard.get(0)[1], 1e-9, "where it came down, the first settled point");
        assertEquals(169.0, heard.get(0)[2], 1e-9, "on the ice it settled on");
        assertEquals(ride.z, heard.get(0)[3], 1e-9);
        assertEquals(second, heard.get(1)[1], 1e-9);
        assertEquals(167.0, heard.get(1)[2], 1e-9);

        FlightRecorder t = new FlightRecorder();
        List<double[]> none = new ArrayList<>();
        Ride moved = new Ride(t.start(false, "road", "medium", (f, x, y, z, d) -> none.add(new double[]{d})));
        moved.flat(20, 2.0);
        moved.drop(2, 2.0, true);
        moved.run.drop(); // reset on the tick it touches down: no settle, nothing heard
        moved.flat(20, 2.0);
        assertEquals(List.of(), none, "a flight dropped by a reset lands nowhere");
        assertEquals(0, t.close(moved.run, false).drops());
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
