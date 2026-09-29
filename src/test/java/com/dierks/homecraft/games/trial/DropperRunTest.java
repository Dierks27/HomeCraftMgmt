package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dierks.homecraft.games.trial.DropperCourses.at;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One Dropper run, played through a fake player (EVENTS-DROPPER-SPEC §B.1.7 and the practice drop of
 * EVENTS-OWNER-DECISIONS D3): the Time Trials dropper cases, with no server.
 *
 * <p>Pinned here, the timed run: a splash reaches its pool where the feet crossed into the water; the
 * hop to the next ledge is the run's own teleport {@value DropperRules#HOP_TICKS} ticks later, and the
 * next move from there reaches that ledge; nothing counts and no bonk happens during the hop; a bonk
 * (a landing, a cancelled fall-damage event, a fall past the floor, the kit) goes back to the top of
 * the level the run is on, counts one and keeps the clock running, with the steering tip once; the
 * last splash is the finish, and the game's own hops never trip the speed check; the clock line.
 *
 * <p>Collisions: a runner is set not collidable at the start and given back exactly what it had, once,
 * however the run ends.
 *
 * <p>The practice drop: it is offered only while warm-ups are on, at most once per run; it lets go
 * of the ledge but is never timed, submitted or paid, and its bonks aren't the run's; a splash, a bonk
 * or "Start timed run" ends it, back on level 1's ledge for the 3-2-1; "Go straight" skips it; and the
 * timed run after it counts normally, its clock starting at Go.
 */
class DropperRunTest {

    private static final long TICK = 50_000_000L;

    /** The player, as the run sees it: what it was told, where it was sent, what it holds. */
    static final class FakePort implements DropperRun.Port {
        final TrialRun run;
        long tick = 1_000;
        long nanos = 5_000_000_000L;
        boolean collidable = true;
        /** The run's own teleports land inside the call, as Paper's do (TimeTrials#teleported). */
        boolean landAtOnce = true;
        boolean failTeleports;
        final List<double[]> teleports = new ArrayList<>();
        final List<DropperRun.Kit> kits = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        final List<String> bars = new ArrayList<>();
        final List<String> chats = new ArrayList<>();
        final List<DropperRun.Cue> sounds = new ArrayList<>();
        final List<Long> finishes = new ArrayList<>();
        final List<Boolean> collidableCalls = new ArrayList<>();
        /** On the no-push team now, and every call made. */
        boolean onTeam;
        final List<Boolean> noPushCalls = new ArrayList<>();

        FakePort(TrialRun run) {
            this.run = run;
        }

        @Override
        public long tick() {
            return tick;
        }

        @Override
        public boolean teleport(double[] stand) {
            if (failTeleports || stand == null) {
                return false;
            }
            teleports.add(stand);
            run.suspended = true; // TimeTrials#move: moves are held until it lands
            if (landAtOnce) {
                land();
            }
            return true;
        }

        /** The run's own teleport arrived (TimeTrials#teleported): moves count again from there. */
        void land() {
            double[] s = teleports.get(teleports.size() - 1);
            run.suspended = false;
            if (run.progress != null) {
                run.progress.jump(at(s[0], s[1], s[2]), nanos);
            }
        }

        @Override
        public void kit(DropperRun.Kit kit) {
            kits.add(kit);
        }

        @Override
        public void title(String big, String small, int stayTicks) {
            titles.add(big + "|" + small);
        }

        @Override
        public void actionBar(String line) {
            bars.add(line);
        }

        @Override
        public void chat(String line) {
            chats.add(line);
        }

        @Override
        public void sound(DropperRun.Cue cue) {
            sounds.add(cue);
        }

        @Override
        public boolean collidable() {
            return collidable;
        }

        @Override
        public void collidable(boolean on) {
            collidable = on;
            collidableCalls.add(on);
        }

        @Override
        public void noPush(boolean on) {
            onTeam = on;
            noPushCalls.add(on);
        }

        @Override
        public void finish(long at) {
            finishes.add(at);
            run.phase = TrialRun.Phase.DONE;
        }

        double[] lastTeleport() {
            return teleports.get(teleports.size() - 1);
        }
    }

    private final Course course = DropperCourses.hand();

    private TrialRun newRun() {
        return new TrialRun(UUID.randomUUID(), course, false, TimeTrials.COUNTDOWN_TICKS + 1);
    }

    /** Go! at the start, as Time Trials' countdown does. */
    private static void go(TrialRun run, FakePort port) {
        run.progress = new Progress(run.course, run.course.start().point(), port.nanos);
        run.phase = TrialRun.Phase.RUNNING;
    }

    /** One tick of the timed run: the clock moves, the player moves to {@code to}, then the run's tick. */
    private static void step(DropperRun d, FakePort port, Point to, boolean inWater) {
        port.tick++;
        port.nanos += TICK;
        if (to != null) {
            d.moved(port, to, port.nanos);
        }
        d.running(port, to, inWater);
    }

    /** Walk from where the run is to (x, y, z) in one move, then fall straight down to {@code bottom}. */
    private static void dropTo(DropperRun d, FakePort port, double x, double z, double top, double bottom) {
        step(d, port, at(x, top, z), false);
        double y = top;
        double vy = 0;
        while (y > bottom && port.run.running() && !d.hopping()) {
            vy = (vy - 0.08) * 0.98;
            y = Math.max(bottom, y + vy);
            step(d, port, at(x, y, z), y < bottom + 3.5);
        }
    }

    /** Ticks until the hop has been made and has landed. */
    private static void hop(DropperRun d, FakePort port) {
        for (int i = 0; i < 20 && d.hopping(); i++) {
            step(d, port, null, true);
        }
    }

    // ---- the timed run -----------------------------------------------------------------------------

    @Test
    void aTimedDropSplashesHopsToTheNextLedgeAndFinishesAtTheLastPool() {
        TrialRun run = newRun();
        FakePort port = new FakePort(run);
        DropperRun d = DropperRun.start(run, port, false);
        run.drop = d;
        assertEquals(DropperRun.Stage.TIMED, d.stage(), "no practice offered: straight to the timed run");
        assertEquals(List.of(DropperRun.Kit.DROP), port.kits, "with the drop kit");
        go(run, port);
        long goNanos = port.nanos;

        dropTo(d, port, 12.5, 12.5, 100, 66);
        assertEquals(1, run.progress.reachedTargets(), "the splash reached pool 1");
        assertTrue(d.hopping(), "and the hop is on its way");
        assertEquals("&aLevel 2!|&7of 3 - keep going!", port.titles.get(port.titles.size() - 1), "the title plays");
        assertTrue(port.teleports.isEmpty(), "no teleport yet: the title plays first");
        long splashTick = port.tick;

        assertFalse(d.bonk(port, DropperRules.Why.FALL_DAMAGE), "a bonk during the hop is ignored");
        step(d, port, at(12.5, 64, 12.5), true); // sinking in the pool: nothing counts
        assertEquals(1, run.progress.reachedTargets(), "moves during the hop count nothing");
        hop(d, port);
        assertEquals(1, port.teleports.size(), "one hop");
        assertEquals(splashTick + DropperRules.HOP_TICKS, port.tick - 1, "made " + DropperRules.HOP_TICKS
                + " ticks after the splash, and done the tick after it landed");
        assertArrayEquals(DropperLayout.stand(course, 1), port.lastTeleport(), 1e-9, "onto level 2's ledge");
        assertEquals(0, d.bonks(), "no bonk from the hop");

        step(d, port, at(22.6, 100, 10.5), false);
        assertEquals(2, run.progress.reachedTargets(), "the first move from the ledge reaches the ledge mark");

        dropTo(d, port, 24.5, 12.5, 100, 58);
        assertEquals(3, run.progress.reachedTargets(), "pool 2");
        hop(d, port);
        assertArrayEquals(DropperLayout.stand(course, 2), port.lastTeleport(), 1e-9, "onto level 3's ledge");
        step(d, port, at(34.6, 100, 10.5), false);
        assertEquals(4, run.progress.reachedTargets(), "ledge 3");

        dropTo(d, port, 36.5, 12.5, 100, 50);
        assertEquals(1, port.finishes.size(), "the last splash is the finish");
        assertTrue(run.progress.finished(), "every target reached");
        long ms = (port.finishes.get(0) - goNanos) / 1_000_000L;
        assertTrue(ms > 3 * 1_600 && ms < 10_000, "three falls and two hops: " + ms + " ms");
        assertTrue(d.clean(), "no bonks: a clean drop");
        assertEquals(-1, FairPlay.tooFast(course, run.progress.startNanos(), run.progress.times(),
                run.progress.reachedTargets(), List.of()), "the game's own hops never trip the speed check");
    }

    @Test
    void aBonkGoesBackToTheTopOfThisLevelCountsOneAndKeepsTheClockRunning() {
        TrialRun run = newRun();
        FakePort port = new FakePort(run);
        DropperRun d = DropperRun.start(run, port, false);
        run.drop = d;
        go(run, port);
        dropTo(d, port, 12.5, 12.5, 100, 66);
        hop(d, port);
        step(d, port, at(22.6, 100, 10.5), false);

        for (int i = 0; i < 3; i++) {
            step(d, port, at(23.5, 80, 11.5), false); // landed on a layer, 20 under the ledge
        }
        assertEquals(1, d.bonks(), "two still ticks under the ledge: a bonk");
        assertArrayEquals(DropperLayout.stand(course, 1), port.lastTeleport(), 1e-9, "back to level 2's ledge");
        assertEquals("&eBonk!|&7Back to the top of level 2.", port.titles.get(port.titles.size() - 1), "it says so");
        assertEquals(List.of(DropperText.TIP), port.chats, "the first bonk adds the steering tip");
        assertEquals(2, run.progress.reachedTargets(), "nothing reached is taken back");
        assertTrue(run.running(), "the clock keeps running");

        port.tick += DropperRules.BONK_GAP;
        assertTrue(d.bonk(port, DropperRules.Why.FALL_DAMAGE), "a cancelled fall-damage event is a bonk too");
        assertFalse(d.bonk(port, DropperRules.Why.LANDING), "the same fall seen again is not a second one");
        port.tick += DropperRules.BONK_GAP;
        assertTrue(d.bonk(port, DropperRules.Why.KIT), "the kit's Back to the top is one");
        port.tick += DropperRules.BONK_GAP;
        step(d, port, at(31.5, 90, 10.5), false); // off to the side of the pool's column...
        step(d, port, at(31.5, 40, 10.5), false); // ...and past the course's fall height (44)
        assertEquals(4, d.bonks(), "and so is a fall past the floor");
        assertEquals(1, port.chats.size(), "the tip is shown once a run");
        String clock = d.clockLine(port.nanos);
        assertTrue(clock.startsWith("&e") && clock.endsWith(" &7· level 2 of 3 · 4 bonks"),
                "the clock line: the time, the level and the bonks: " + clock);
        assertFalse(d.clean(), "not a clean drop");
    }

    @Test
    void aBonkWhoseTeleportFailsIsNotCountedAndTheNextOneIs() {
        TrialRun run = newRun();
        FakePort port = new FakePort(run);
        DropperRun d = DropperRun.start(run, port, false);
        run.drop = d;
        go(run, port);
        port.failTeleports = true;
        int titles = port.titles.size();
        for (int i = 0; i < 100; i++) {
            port.tick++;
            d.bonk(port, DropperRules.Why.FALL_DAMAGE);
        }
        assertEquals(0, d.bonks(), "100 ticks of bonks that couldn't send the run back count none (was 9)");
        assertEquals(titles, port.titles.size(), "and show no Bonk! title");
        assertTrue(port.sounds.isEmpty(), "nor play its sound");
        assertTrue(port.chats.isEmpty(), "nor the tip");
        assertFalse(d.bonk(port, DropperRules.Why.KIT), "the bonk reports it did nothing, so Time Trials sends back");
        port.failTeleports = false;
        port.tick += DropperRules.BONK_GAP;
        assertTrue(d.bonk(port, DropperRules.Why.FALL_DAMAGE), "once teleports work again, a bonk is one");
        assertEquals(1, d.bonks(), "counted once");
        assertEquals("&eBonk!|&7Back to the top of level 1.", port.titles.get(port.titles.size() - 1), "and shown");
        assertEquals(List.of(DropperText.TIP), port.chats, "with the tip");
    }

    @Test
    void theHopWaitsForItsTeleportToLandAndTriesAgainIfItFailed() {
        TrialRun run = newRun();
        FakePort port = new FakePort(run);
        DropperRun d = DropperRun.start(run, port, false);
        run.drop = d;
        go(run, port);
        dropTo(d, port, 12.5, 12.5, 100, 66);
        port.failTeleports = true;
        for (int i = 0; i <= DropperRules.HOP_TICKS + 2; i++) {
            step(d, port, null, true);
        }
        assertTrue(d.hopping(), "a hop that couldn't be made is still on");
        port.failTeleports = false;
        port.landAtOnce = false;
        step(d, port, null, true);
        assertEquals(1, port.teleports.size(), "it is tried again the next tick");
        step(d, port, null, true);
        assertTrue(d.hopping(), "and it waits until the teleport lands");
        assertFalse(d.bonk(port, DropperRules.Why.LANDING), "no bonk meanwhile");
        port.land();
        step(d, port, null, false);
        assertFalse(d.hopping(), "landed: the run goes on");
    }

    // ---- collisions ----------------------------------------------------------------------------------

    @Test
    void theRunnerIsNotCollidableAndGetsBackWhatItHadOnceHoweverTheRunEnds() {
        for (boolean before : new boolean[]{true, false}) {
            TrialRun run = newRun();
            FakePort port = new FakePort(run);
            port.collidable = before;
            DropperRun d = DropperRun.start(run, port, true);
            assertTrue(port.onTeam, "two fallers in one shaft can't push each other: the no-push team");
            assertFalse(port.collidable, "and not collidable, so mobs can't either");
            assertFalse(d.ended(), "the run is on");
            d.end(port);
            assertFalse(port.onTeam, "the end takes the runner off the no-push team");
            assertEquals(List.of(true, false), port.noPushCalls, "on once at the start, off once at the end");
            assertEquals(before, port.collidable, "the end gives back exactly what it had (" + before + ")");
            assertTrue(d.ended(), "and remembers it did");
            port.collidable = false;
            d.end(port);
            assertFalse(port.collidable, "a second end changes nothing");
            assertEquals(List.of(false, before), port.collidableCalls, "one change at the start, one at the end");
        }
    }

    // ---- the practice drop ---------------------------------------------------------------------------

    @Test
    void thePracticeDropIsOfferedOnlyWhileWarmUpsAreOn() {
        assertTrue(DropperRun.offers(TimeTrialsSettings.defaults()), "warm-ups ship on (180 s): offered");
        TimeTrialsSettings d = TimeTrialsSettings.defaults();
        TimeTrialsSettings off = new TimeTrialsSettings(d.enabled(), d.firstClear(), d.weeklyBestBonus(),
                d.courseOfWeekBonus(), d.dailyCap(), d.fallDepth(), d.minSeconds(), 0, d.partyMax());
        assertFalse(DropperRun.offers(off), "warmup_seconds: 0 turns it off");
        assertFalse(DropperRun.offers(null), "no settings: not offered");

        TrialRun run = newRun();
        FakePort port = new FakePort(run);
        DropperRun offered = DropperRun.start(run, port, true);
        assertEquals(DropperRun.Stage.OFFER, offered.stage(), "offered");
        assertEquals(List.of(DropperRun.Kit.OFFER), port.kits, "the two choices in the hotbar");
        assertTrue(offered.holdsCountdown(), "the 3-2-1 waits for the choice");
        assertFalse(offered.letsGo(), "and the player is held on the ledge");
        assertEquals(List.of(DropperText.OFFER), port.chats, "it is said once in chat");
    }

    @Test
    void thePracticeDropIsNeverTimedSubmittedOrPaidAndEndsOnItsSplash() {
        TrialRun run = newRun();
        FakePort port = new FakePort(run);
        DropperRun d = DropperRun.start(run, port, true);
        run.drop = d;
        d.choose(port, true);
        assertEquals(DropperRun.Stage.PRACTICE, d.stage(), "the practice drop");
        assertTrue(run.warmup && run.warmupUsed, "it is the run's one warm-up");
        assertFalse(run.timed(), "nothing in it may be recorded");
        assertTrue(d.letsGo(), "the hold lets go");
        assertTrue(d.holdsCountdown(), "no 3-2-1 during it");
        assertEquals(DropperRun.Kit.PRACTICE, port.kits.get(port.kits.size() - 1), "Start timed run in the hotbar");
        assertTrue(port.bars.contains(DropperText.PRACTICE_BAR), "Practice drop - not counted");

        for (double y = 100; y > 66; y -= 1.5) {
            port.nanos += TICK;
            d.practiceMove(port, at(12.5, y, 12.5));
            d.waiting(port, at(12.5, y, 12.5), false);
            if (d.stage() != DropperRun.Stage.PRACTICE) {
                break;
            }
        }
        assertEquals(DropperRun.Stage.TIMED, d.stage(), "the splash into level 1's pool ends it");
        assertNull(run.progress, "no clock ran");
        assertTrue(port.finishes.isEmpty(), "nothing was finished, so nothing submitted or paid");
        assertFalse(run.warmup, "the warm-up is over");
        assertTrue(run.warmupUsed, "and used");
        assertArrayEquals(DropperLayout.stand(course, 0), port.lastTeleport(), 1e-9, "back on level 1's ledge");
        assertEquals(DropperRun.Kit.DROP, port.kits.get(port.kits.size() - 1), "with the drop kit");
        assertEquals(TimeTrials.COUNTDOWN_TICKS + 1, run.countdown, "for a whole 3-2-1");
        assertFalse(d.holdsCountdown(), "which now runs");
        assertTrue(port.chats.contains(DropperText.PRACTICE_SPLASH), "it says the timed run is next");
    }

    @Test
    void aPracticeBonkEndsItAndIsNotTheRunsBonk() {
        TrialRun run = newRun();
        FakePort port = new FakePort(run);
        DropperRun d = DropperRun.start(run, port, true);
        d.choose(port, true);
        for (int i = 0; i < 4 && d.stage() == DropperRun.Stage.PRACTICE; i++) {
            d.waiting(port, at(12.5, 80, 12.5), false); // landed on a layer of level 1
        }
        assertEquals(DropperRun.Stage.TIMED, d.stage(), "a landing ends the practice drop");
        assertEquals(0, d.bonks(), "and it isn't one of the run's bonks");
        assertTrue(port.chats.contains(DropperText.PRACTICE_BONK), "it says so, kindly");

        TrialRun run2 = newRun();
        FakePort port2 = new FakePort(run2);
        DropperRun d2 = DropperRun.start(run2, port2, true);
        d2.choose(port2, true);
        assertTrue(d2.bonk(port2, DropperRules.Why.FALL_DAMAGE), "a fall-damage event in the practice drop ends it");
        assertEquals(0, d2.bonks(), "without a bonk counted");
        assertTrue(d2.clean(), "so a clean timed run after it is still a clean drop");
    }

    @Test
    void theTimedRunAfterAPracticeDropCountsNormallyWithItsClockStartingAtGo() {
        TrialRun run = newRun();
        FakePort port = new FakePort(run);
        DropperRun d = DropperRun.start(run, port, true);
        run.drop = d;
        d.choose(port, true);
        port.nanos += 30 * TICK;
        d.skipPractice(port); // "Start timed run"
        assertEquals(DropperRun.Stage.TIMED, d.stage(), "Start timed run ends it early");
        assertTrue(port.chats.contains(DropperText.PRACTICE_SKIPPED), "then the timed run");
        assertTrue(run.timed(), "the timed run may be recorded");

        port.nanos += 60 * TICK; // the 3-2-1
        go(run, port);
        long goNanos = port.nanos;
        dropTo(d, port, 12.5, 12.5, 100, 66);
        hop(d, port);
        step(d, port, at(22.6, 100, 10.5), false);
        dropTo(d, port, 24.5, 12.5, 100, 58);
        hop(d, port);
        step(d, port, at(34.6, 100, 10.5), false);
        dropTo(d, port, 36.5, 12.5, 100, 50);
        assertEquals(1, port.finishes.size(), "the timed run finishes like any other");
        assertEquals(port.finishes.get(0) - goNanos, run.progress.times()[4] - run.progress.startNanos(),
                "its time is counted from Go, never from the practice drop");
        assertEquals(run.elapsedMs(port.finishes.get(0)), (port.finishes.get(0) - goNanos) / 1_000_000L,
                "the clock the result reads");
        assertTrue(d.clean(), "and a clean one is a clean drop");
    }

    @Test
    void goStraightSkipsThePracticeDrop() {
        TrialRun run = newRun();
        FakePort port = new FakePort(run);
        DropperRun d = DropperRun.start(run, port, true);
        run.countdown = 7;
        d.choose(port, false);
        assertEquals(DropperRun.Stage.TIMED, d.stage(), "straight to the timed run");
        assertFalse(run.warmupUsed, "no warm-up was used");
        assertTrue(port.teleports.isEmpty(), "the player is already on the ledge");
        assertEquals(TimeTrials.COUNTDOWN_TICKS + 1, run.countdown, "the 3-2-1 starts at once, from the top");
        assertEquals(DropperRun.Kit.DROP, port.kits.get(port.kits.size() - 1), "with the drop kit");
        d.choose(port, true);
        assertEquals(DropperRun.Stage.TIMED, d.stage(), "and the offer is gone");
    }

    @Test
    void aRunIsOfferedAtMostOnePracticeDrop() {
        TrialRun run = newRun();
        FakePort port = new FakePort(run);
        DropperRun d = DropperRun.start(run, port, true);
        d.choose(port, true);
        d.choose(port, true);
        assertEquals(1, port.kits.stream().filter(k -> k == DropperRun.Kit.PRACTICE).count(), "chosen once");
        d.skipPractice(port);
        d.choose(port, true);
        assertEquals(DropperRun.Stage.TIMED, d.stage(), "after it, no second practice drop");
        assertFalse(run.beginWarmup(0), "the run's one warm-up is used");

        TrialRun used = newRun();
        used.warmupUsed = true;
        DropperRun again = DropperRun.start(used, new FakePort(used), true);
        assertEquals(DropperRun.Stage.TIMED, again.stage(), "a run that had its warm-up isn't offered one");
    }

    @Test
    void theKitsActionsAreTheDroppersOwn() {
        assertEquals("checkpoint", DropperRun.BACK_ACTION, "Back to the top is the course kit's slot-0 action");
        assertEquals(4, DropperRun.ACTIONS.size(), "practice, straight, start timed run and back to the top");
        assertEquals(4, Set.copyOf(DropperRun.ACTIONS).size(), "all different");
    }
}
