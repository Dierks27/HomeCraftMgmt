package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.gen.api.GenTag;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Race mode inside Time Trials (EVENTS-DROPPER-SPEC §A.4.11), its decisions pinned with fakes (a
 * {@link RaceLink} the test drives, a clock the test turns): every racer released on the same tick
 * with the same start nanos; a late seat on the shared clock; staleness judged on the base course;
 * no course board or course reward on a race finish; park, re-grid and a second race; a link no
 * longer alive ends the run; a held course refuses solo runs; and a normal run is exactly as it
 * always was.
 */
class RaceModeTest {

    private static final Point STAND = new Point(0.5, 70, 0.5);

    /** The course raced: Laps' two-lap loop. */
    private static final Course BASE = LapsTest.loop(6, 2);

    private static RaceRun racer(TrialFakes.Link link, Course.Spot grid, Point stand) {
        RaceRun rr = new RaceRun(link, BASE, grid, stand, false);
        assertEquals(RaceRun.State.GRID, rr.state, "no warm-up: straight onto the grid");
        return rr;
    }

    /** A {@code System.nanoTime()} that moves on every time it is read, as the real one does between racers. */
    private static LongSupplier ticking(AtomicLong now) {
        return () -> now.addAndGet(1_000);
    }

    @Test
    void everyRacerIsReleasedOnTheSameTickWithTheSameStartNanos() {
        TrialFakes.Link link = new TrialFakes.Link();
        link.goTick = 200;
        RaceRun.Clock clock = new RaceRun.Clock();
        AtomicLong nanos = new AtomicLong(5_000_000);
        List<RaceRun> racers = List.of(racer(link, new Course.Spot(1, 65, -4, 0, 0), STAND),
                racer(link, new Course.Spot(-2, 65, -4, 0, 0), STAND), racer(link, new Course.Spot(1, 65, -8, 0, 0), STAND));
        for (long tick = 100; tick < 200; tick++) {
            for (RaceRun rr : racers) {
                RaceRun.Release r = rr.release(tick, true, clock, ticking(nanos));
                assertTrue(r.hold() && !r.go(), "held on the grid before the go tick (tick " + tick + ")");
                int want = tick == 140 ? 3 : tick == 160 ? 2 : tick == 180 ? 1 : 0;
                assertEquals(want, r.count(), "3, 2, 1 on the last three whole seconds (tick " + tick + ")");
            }
        }
        long[] starts = new long[racers.size()];
        for (int i = 0; i < racers.size(); i++) {
            RaceRun.Release r = racers.get(i).release(200, true, clock, ticking(nanos));
            assertFalse(r.hold(), "released at the go tick");
            assertTrue(r.go(), "every one of them, on that same tick");
            starts[i] = r.nanos();
        }
        assertEquals(starts[0], starts[1], "one start instant for everyone, though the clock moved between racers");
        assertEquals(starts[0], starts[2], "the third too");
        assertEquals(1, clock.size(), "one race, one instant");
        assertEquals(5_001_000, starts[0], "taken the first time any racer reached Go");
    }

    @Test
    void aLateSeatStartsAtOnceOnTheSharedClock() {
        TrialFakes.Link link = new TrialFakes.Link();
        link.goTick = 200;
        RaceRun.Clock clock = new RaceRun.Clock();
        AtomicLong nanos = new AtomicLong(0);
        RaceRun early = racer(link, new Course.Spot(1, 65, -4, 0, 0), STAND);
        long go = early.release(200, true, clock, ticking(nanos)).nanos();

        RaceRun slowChunk = racer(link, new Course.Spot(-2, 65, -4, 0, 0), STAND);
        RaceRun.Release notYet = slowChunk.release(200, false, clock, ticking(nanos));
        assertFalse(notYet.hold(), "a racer still arriving at Go isn't held back");
        assertFalse(notYet.go(), "and doesn't start before it is in place");
        RaceRun.Release in = slowChunk.release(215, true, clock, ticking(nanos));
        assertTrue(in.go(), "it starts the moment it is in");
        assertEquals(go, in.nanos(), "on the shared clock: its time already counts from Go");

        RaceRun seatedLate = racer(link, new Course.Spot(1, 65, -8, 0, 0), STAND);
        assertEquals(go, seatedLate.release(260, true, clock, ticking(nanos)).nanos(),
                "a racer seated after Go starts on the same instant too");

        link.goTick = 900; // the next race
        long next = early.release(900, true, clock, ticking(nanos)).nanos();
        assertNotEquals(go, next, "the next race has its own start");
        assertEquals(next, slowChunk.release(901, true, clock, ticking(nanos)).nanos(), "shared by all of its racers");
    }

    @Test
    void staleIsJudgedOnTheBaseCourseNeverTheDerivedOne() {
        TrialFakes.Link link = new TrialFakes.Link();
        Course.Spot grid = new Course.Spot(29, 65, -8, 0, 0);
        Course raced = Laps.raced(BASE, grid, 3).course();
        RaceRun rr = new RaceRun(link, BASE, grid, null, false);
        assertNotEquals(BASE.layoutHash(), raced.layoutHash(), "the raced course differs from the base by design");
        assertTrue(FairPlay.stale(raced, raced.layoutHash(), BASE, null),
                "judged on the derived course, every race would be void");
        assertFalse(rr.stale(BASE, null), "judged on the base course: the course as it is, so it counts");
        Course edited = BASE.withFinish(new Course.Mark(31, 65, 1, 5)).withRev(2);
        assertTrue(rr.stale(edited, null), "the base course changed while racing: stale");
        assertTrue(rr.stale(null, null), "the course was deleted: stale");

        GenTag tag = new GenTag("fresh_boat", "boat", 2, 20_725, 0, 1L, 'A', "abcabcabcabc", 1, 2, 3, List.of(),
                List.of(), 1L);
        Course fresh = BASE.withGen(tag);
        RaceRun onFresh = new RaceRun(link, fresh, grid, null, false);
        Course next = fresh.withFinish(new Course.Mark(31, 65, 1, 5)).withRev(2);
        assertFalse(onFresh.stale(next, t -> true), "the next layout is live but this one still stands: counts");
        assertTrue(onFresh.stale(next, t -> false), "this layout's blocks are being cleared: stale");
    }

    @Test
    void aRaceNightFinishNeverTouchesTheCoursesBoardsOrRewards() {
        TrialFakes.Link night = new TrialFakes.Link(); // normalRun() false: Race Night
        TrialRun run = new TrialRun(UUID.randomUUID(), BASE, false, 0);
        run.race = racer(night, BASE.start(), STAND);
        assertEquals(RaceRun.Route.RACE, RaceRun.route(run), "a race's line goes to the race, never the normal finish");
        run.race.started();
        RaceRun.Line line = run.race.line(true);
        assertTrue(line.report(), "the link hears it");
        assertFalse(line.normal(), "no course board, weekly best, first clear, stars or course reward");
        assertTrue(line.e4(), "a counted race finish tells the quests once (E4 FINISH_COURSE)");
        assertEquals(RaceRun.Due.PARK, line.next(), "then onto the stand");

        RaceRun voided = racer(night, BASE.start(), null);
        voided.started();
        RaceRun.Line v = voided.line(false);
        assertTrue(v.report(), "a void is reported too (0 points)");
        assertFalse(v.normal() || v.e4(), "and counts for nothing anywhere");
        assertEquals(RaceRun.Due.HOME, v.next(), "no stand: home at the line");
    }

    @Test
    void aPartyRacesFinishIsAlsoTheNormalRunExactlyOnce() {
        TrialFakes.Link party = new TrialFakes.Link();
        party.normal = true;
        RaceRun rr = racer(party, BASE.start(), STAND);
        rr.started();
        RaceRun.Line first = rr.line(true);
        assertTrue(first.normal(), "a counted party finish is the course's normal run");
        assertFalse(first.e4(), "whose own finish tells the quests, so not twice");
        assertFalse(rr.line(true).report(), "the line crossed again in the same race: nothing at all");
        assertFalse(rr.normalFinish(true), "and never a second normal run");
        RaceRun notCounted = racer(party, BASE.start(), STAND);
        assertFalse(notCounted.line(false).normal(), "a race that didn't count is no normal run either");
    }

    @Test
    void parkThenRegridThenASecondRace() {
        TrialFakes.Link link = new TrialFakes.Link();
        link.goTick = 100;
        RaceRun rr = racer(link, new Course.Spot(1, 65, -4, 0, 0), STAND);
        assertEquals(RaceRun.Act.GRID, rr.next(true), "on the grid");
        rr.started();
        assertEquals(RaceRun.Act.RACE, rr.next(true), "racing");
        RaceRun.Line line = rr.line(true);
        rr.due = line.next();
        assertEquals(RaceRun.Act.PARK, rr.next(true), "over the line: park on the next tick, never inside the move");
        rr.due = RaceRun.Due.NONE;
        rr.parked();
        assertEquals(RaceRun.Act.STAND, rr.next(true), "waiting on the stand");
        assertTrue(rr.offStand(new Point(0.5, 70, 6)), "wandered 5.5 blocks off: put back");
        assertFalse(rr.offStand(new Point(2.5, 70, 2.5)), "on the stand: left alone");

        Course.Spot second = new Course.Spot(-2, 65, -4, 0, 0);
        rr.regrid(second);
        assertEquals(2, rr.race, "the second race");
        assertEquals(second, rr.grid, "on the new spot");
        assertEquals(RaceRun.Act.GRID, rr.next(true), "held again until the next go tick");
        rr.started();
        assertTrue(rr.line(true).report(), "the second race's line is its own");
        assertFalse(rr.line(true).report(), "crossed once");

        RaceRun warm = new RaceRun(link, BASE, second, STAND, true);
        assertEquals(RaceRun.Act.WARMUP, warm.next(true), "the shared warm-up runs as free laps");
        warm.regrid(second);
        assertEquals(1, warm.race, "the grid after the warm-up is still the first race");
    }

    @Test
    void aLinkNoLongerAliveEndsTheRun() {
        TrialFakes.Link link = new TrialFakes.Link();
        RaceRun rr = racer(link, BASE.start(), STAND);
        for (RaceRun.State s : RaceRun.State.values()) {
            rr.state = s;
            assertEquals(RaceRun.Act.CALLED_OFF, rr.next(false), s + ": the race is over, home with its line");
        }
        rr.home(EndReason.FINISH, "&7Race over - great racing!");
        assertEquals(RaceRun.Act.HOME, rr.next(false), "a trip home the race asked for keeps its own line");
        rr.ended = true;
        assertEquals(RaceRun.Act.ENDED, rr.next(false), "and once on the way, nothing more");

        TimeTrials trials = new TimeTrials(null);
        RaceLink broken = new TrialFakes.Link() {
            @Override
            public boolean alive() {
                throw new IllegalStateException("the coordinator broke");
            }
        };
        assertFalse(trials.raceMode().alive(broken), "a coordinator that throws is over, never a throw into Time Trials");
        assertEquals("fallback", trials.raceMode().call(broken, () -> "asked", "fallback"),
                "and nothing more is asked of it");
    }

    @Test
    void aHeldCourseRefusesSoloRuns() {
        TimeTrials trials = new TimeTrials(null);
        Object night = new Object();
        List<String> said = new ArrayList<>();
        Player p = TrialFakes.player(UUID.randomUUID(), "Sam", said);
        assertFalse(trials.raceMode().refuseSolo(p, "loop"), "a course nobody holds: solo runs as ever");
        assertTrue(trials.reserve("loop", night, "&7Race Night is on this track - back at 8:00."), "held for the night");
        assertTrue(trials.raceMode().refuseSolo(p, "LOOP"), "a solo run on it is refused");
        assertEquals(List.of("Race Night is on this track - back at 8:00."), said, "with the holder's own line");
        assertFalse(trials.raceMode().refuseSolo(p, "another"), "other courses are free");
        trials.release("loop", night);
        assertFalse(trials.raceMode().refuseSolo(p, "loop"), "let go: solo runs again");
        RaceRun.Holds holds = new RaceRun.Holds();
        assertTrue(holds.reserve("loop", night, " "), "a blank line");
        assertEquals("&7A race is on this course.", holds.refusal("loop"), "gets a plain one");
    }

    @Test
    void aNormalRunIsExactlyAsItAlwaysWas() {
        TimeTrials trials = new TimeTrials(null);
        RaceMode mode = trials.raceMode();
        TrialRun run = new TrialRun(UUID.randomUUID(), BASE, false, TimeTrials.COUNTDOWN_TICKS + 1);
        assertNull(run.race, "a run has no race unless race mode seats it");
        assertEquals(RaceRun.Route.SOLO, RaceRun.route(run), "its finish is the normal finish");
        Location at = new Location(null, 1, 65, 2);
        assertSame(at, mode.reseat(run, at), "sent back exactly where it always was");
        List<String> said = new ArrayList<>();
        Player p = TrialFakes.player(run.player, "Sam", said);
        assertFalse(mode.onVoid(p, run), "the void sends it back as ever");
        mode.left(p, EndReason.COMMAND); // nothing to tell anyone
        mode.gone(run, p);
        mode.endRace(run.player, EndReason.ADMIN, "&7Bye."); // a race's end never touches a solo run
        assertEquals(TrialRun.Phase.COUNTDOWN, run.phase, "still counting down");
        assertEquals(List.of(), said, "and nobody said anything to the player");
        assertTrue(run.timed(), "timed as ever");
    }
}
