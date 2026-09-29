package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * kit guard has already cancelled it); and a dropper run takes its moves, ticks, kit clicks, void,
 * send-back and finish through the hooks, and nothing else in Time Trials changed for other kinds.
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
    void theFallDamageBonkIsHeardAtMonitorWithTheCancelledEvents() throws IOException {
        Matcher m = Pattern.compile("g\\.on\\(this, EntityDamageEvent\\.class, EventPriority\\.(\\w+), (\\w+),"
                + " drops::hurt\\)").matcher(source());
        assertTrue(m.find(), "the hook is registered through the game (so it lives and dies with it)");
        assertEquals("MONITOR", m.group(1), "at MONITOR: it only looks");
        assertEquals("false", m.group(2), "with ignoreCancelled false: the kit guard cancelled the damage already");
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
