package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.NoPush;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where Time Trials meets the Dropper (EVENTS-DROPPER-SPEC §B.1.7; WIRING §4), read from the source
 * the way {@code GameProgressHooksTest} reads the cabinets: Time Trials keeps its dropper diff to hook
 * calls, and the runner's collisions are given back on every way a run ends.
 *
 * <p>Pinned here: every place Time Trials lets go of a run (the tick's sweep of runs whose session
 * is gone, the end of a session or a quit, the game stopping) calls {@code drops.end} next to it; a
 * run whose player has gone is ended without one (collidability isn't saved with a player, so there
 * is nothing to give back); the fall-damage bonk is heard at MONITOR with the cancelled events (the
 * kit guard has already cancelled it), and a runner's fall damage is a bonk while nothing else is;
 * a run whose player has gone still comes off the no-push team; the pool guard is Time Trials' own;
 * and a dropper run takes its moves, ticks, kit clicks, void, send-back and finish through the hooks,
 * and nothing else in Time Trials changed for other kinds.
 */
class DropperHooksTest {

    private static String source() throws IOException {
        return Files.readString(Path.of("src/main/java/com/dierks/homecraft/games/trial/TimeTrials.java"));
    }

    @Test
    void everyWayARunEndsGivesBackTheRunnersCollisions() throws IOException {
        List<String> lines = source().lines().toList();
        List<Integer> lets = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.contains("runs.remove(") || l.contains("runs.clear()")) {
                lets.add(i);
            }
        }
        assertEquals(3, lets.size(), "the tick's sweep, end(player) and stop() let go of runs: " + lets);
        for (int i : lets) {
            boolean ended = false;
            for (int j = Math.max(0, i - 4); j <= Math.min(lines.size() - 1, i + 4); j++) {
                ended |= lines.get(j).contains("drops.end(");
            }
            assertTrue(ended, "line " + (i + 1) + " lets go of a run and gives back its collisions: " + lines.get(i));
        }
    }

    @Test
    void aRunWhosePlayerHasGoneEndsWithoutThem() {
        Course c = DropperCourses.hand();
        TrialRun run = new TrialRun(UUID.randomUUID(), c, false, 61);
        DropperRunTest.FakePort port = new DropperRunTest.FakePort(run);
        run.drop = DropperRun.start(run, port, true);
        new DropperHooks(null).end(run, null);
        assertTrue(run.drop.ended(), "a quit or a crash-restore ends the run with no player to touch");
        assertFalse(port.collidable, "the player object isn't touched (it is gone; nothing is saved with it)");
        new DropperHooks(null).end(run, null);
        new DropperHooks(null).end(null, null);
        TrialRun plain = new TrialRun(UUID.randomUUID(), DropperCourses.asParkour(), false, 61);
        new DropperHooks(null).end(plain, null);
        assertEquals(null, plain.drop, "a run of any other kind has nothing of the Dropper's");
    }

    @Test
    void aRunWhosePlayerHasGoneStillComesOffTheNoPushTeam() {
        Map<String, String> board = new HashMap<>();
        NoPush team = new NoPush(() -> new NoPush.Board() {
            @Override
            public String teamOf(String entry) {
                return board.get(entry);
            }

            @Override
            public void ensureNoCollision(String t) {
            }

            @Override
            public boolean exists(String t) {
                return true;
            }

            @Override
            public void add(String t, String entry) {
                board.put(entry, t);
            }

            @Override
            public void remove(String t, String entry) {
                board.remove(entry, t);
            }

            @Override
            public Set<String> entries(String t) {
                return Set.of();
            }
        });
        board.put("Sam", "nametags");
        TrialRun run = new TrialRun(UUID.randomUUID(), DropperCourses.hand(), false, 61);
        DropperRunTest.FakePort port = new DropperRunTest.FakePort(run);
        run.drop = DropperRun.start(run, port, true);
        assertTrue(team.on(run.player, "Sam"), "(what the live port does at the start)");
        assertEquals(NoPush.TEAM, board.get("Sam"), "the runner is on the no-push team");
        new DropperHooks(null, () -> team, null).end(run, null);
        assertFalse(team.isOn(run.player), "a quit ends the run and takes them off the team (saved with the world)");
        assertEquals("nametags", board.get("Sam"), "back on the team they came from");
        new DropperHooks(null, () -> null, null).end(new TrialRun(UUID.randomUUID(), DropperCourses.hand(), false, 61), null);
    }

    @Test
    void aRunnersFallDamageIsABonkAndNothingElseIs() {
        TimeTrials trials = new TimeTrials(null);
        UUID id = UUID.randomUUID();
        TrialRun run = new TrialRun(id, DropperCourses.hand(), false, 61);
        DropperRunTest.FakePort port = new DropperRunTest.FakePort(run);
        run.drop = DropperRun.start(run, port, false);
        run.progress = new Progress(run.course, run.course.start().point(), port.nanos);
        run.phase = TrialRun.Phase.RUNNING;
        trials.replaceRun(run);
        DropperHooks hooks = new DropperHooks(trials, () -> null, (p, r) -> port);
        Player sam = TrialFakes.player(id, "Sam", new ArrayList<>());

        hooks.hurt(EntityDamageEvent.DamageCause.CONTACT, sam);
        assertEquals(0, run.drop.bonks(), "a cactus isn't a landing");
        hooks.hurt(EntityDamageEvent.DamageCause.FALL, sam);
        assertEquals(1, run.drop.bonks(), "a runner's fall damage (cancelled by the kit guard, heard anyway) is a bonk");
        assertArrayEquals(DropperLayout.stand(run.course, 0), port.lastTeleport(), 1e-9, "back to the top of level 1");
        port.tick += DropperRules.BONK_GAP;
        hooks.hurt(EntityDamageEvent.DamageCause.FALL, TrialFakes.player(UUID.randomUUID(), "Ava", new ArrayList<>()));
        assertEquals(1, run.drop.bonks(), "someone with no run: nothing");
        hooks.hurt(EntityDamageEvent.DamageCause.FALL, null);
        assertEquals(1, run.drop.bonks(), "nobody: nothing");
        TrialRun parkour = new TrialRun(id, DropperCourses.asParkour(), false, 61);
        trials.replaceRun(parkour);
        hooks.hurt(EntityDamageEvent.DamageCause.FALL, sam);
        assertEquals(1, run.drop.bonks(), "a run of another kind has no bonks to count");
    }

    @Test
    void theFallDamageBonkIsHeardAtMonitorWithTheCancelledEvents() throws IOException {
        // what the hook does is aRunnersFallDamageIsABonkAndNothingElseIs; its registration's flags
        // can't run without a server, so they are read from the source
        Matcher m = Pattern.compile("g\\.on\\(this, EntityDamageEvent\\.class, EventPriority\\.(\\w+), (\\w+),"
                + " drops::hurt\\)").matcher(source());
        assertTrue(m.find(), "the hook is registered through the game (so it lives and dies with it)");
        assertEquals("MONITOR", m.group(1), "at MONITOR: it only looks");
        assertEquals("false", m.group(2), "with ignoreCancelled false: the kit guard cancelled the damage already");
    }

    @Test
    void thePoolGuardIsTimeTrialsOwnSoItWorksWithFreshCoursesOff() throws IOException {
        // the registration's flags can't run without a server: read them from the source
        assertTrue(source().contains("g.on(this, BlockFromToEvent.class, EventPriority.LOW, true, drops::flow);"),
                "every fluid is judged by the Dropper's pool guard, registered through Time Trials (not Fresh Courses)");
    }

    @Test
    void aDropperRunGoesThroughTheHooks() throws IOException {
        String src = source();
        for (String hook : List.of("drops.start(p, run)", "drops.countdown(p, run)", "drops.running(p, run)",
                "drops.moved(p, run, e.getTo())", "drops.kit(player, run, action)",
                "drops.bonk(player, run, DropperRules.Why.VOID)", "drops.back(p, run)", "drops.finished(p, run,",
                "drops.giveKit(p, DropperRun.Kit.DROP)")) {
            assertTrue(src.contains(hook), "Time Trials calls " + hook);
        }
        assertFalse(src.contains("new DropperRules.Landing"), "the landing watch is DropperRun's, not Time Trials'");
        assertFalse(src.contains("DropperRules.splash("), "and so is the splash");
    }
}
