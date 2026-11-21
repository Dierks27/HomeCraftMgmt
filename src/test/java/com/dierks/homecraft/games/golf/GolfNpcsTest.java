package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.cpu.CpuLevel;
import com.dierks.homecraft.games.cpu.CpuRoster;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NPC golfers in golf together (stage 2): an NPC's row is on the shared card and in the ranking, its strokes drawn
 * by level (easy about par + 2, medium par + 1, hard about par), and it never holds the group up, never starts the
 * hole clock and never changes a real player's row; a real player's round is exactly the same with or without it.
 */
class GolfNpcsTest {

    private static final UUID LILAH = new UUID(0, 1);
    private static final UUID DAD = new UUID(0, 2);
    private static final UUID PIP = CpuRoster.byKey("pip").id();

    private static GolfGroup group(UUID... who) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (UUID u : who) {
            names.put(u, u.equals(LILAH) ? "Lilah" : "Dad");
        }
        return new GolfGroup(7, "meadow", "Meadow", List.of(3, 4, 3), names);
    }

    @Test
    void anNpcsStrokesFollowItsLevel() {
        SplittableRandom rng = new SplittableRandom(5);
        for (CpuLevel level : CpuLevel.values()) {
            double sum = 0;
            int n = 6_000;
            for (int i = 0; i < n; i++) {
                int s = GolfNpcs.strokes(3, level, 3 + 5, rng);
                assertTrue(s >= 1 && s <= 8, "between one and the pick-up score");
                sum += s;
            }
            double over = sum / n - 3;
            double want = switch (level) {
                case EASY -> 2;
                case MEDIUM -> 1;
                case HARD -> 0;
            };
            assertEquals(want, over, 0.15, level + ": on average par + " + want);
        }
        for (int i = 0; i < 200; i++) {
            assertTrue(GolfNpcs.strokes(2, CpuLevel.EASY, 3, rng) <= 3, "never over the pick-up score");
        }
        double last = 0;
        for (int k = 1; k <= 5; k++) {
            double a = GolfNpcs.along(k, 5);
            assertTrue(a > last, "each shot closer to the cup");
            last = a;
        }
        assertEquals(1, GolfNpcs.along(5, 5), "and in it with the last");
    }

    @Test
    void theGroupNeverWaitsForAnNpcAndItNeverStartsTheHoleClock() {
        GolfGroup g = group(LILAH);
        assertTrue(g.addNpc(PIP, CpuRoster.tagged("Pip")));
        assertEquals(List.of(LILAH), g.active(), "the group's players are the real ones");
        assertEquals(List.of(LILAH), g.out(), "only real balls are waited for");
        assertEquals(List.of("Lilah"), g.waitingFor());
        g.npcPlan(PIP, 4);
        g.npcStrokes(PIP, 2);
        g.npcHoleDone(PIP);
        assertTrue(g.npcDone(PIP));
        assertEquals(-1, g.clock(), "an NPC in first never starts the clock on Lilah");
        assertFalse(g.allDone(), "the hole waits for Lilah only");
        assertTrue(g.holeDone(LILAH, new GolfRun.HoleScore(3, 6, false)));
        assertTrue(g.advance(), "on to hole 2");
        g.npcPlan(PIP, 5);
        assertTrue(g.holeDone(LILAH, new GolfRun.HoleScore(4, 5, false)), "Lilah in, Pip still putting: done");
        assertTrue(g.advance(), "it never holds her up: on to hole 3");
        GolfGroup.Row pip = g.card().rows().get(1);
        assertEquals(List.of(new GolfRun.HoleScore(3, 4, false), new GolfRun.HoleScore(4, 5, false)), pip.scores(),
                "Pip finished hole 2 on its planned strokes");
        assertTrue(pip.name().contains("⚙ NPC"), "its row is marked");
        assertFalse(g.addNpc(CpuRoster.byKey("wren").id(), "late"), "an NPC joins from the first tee only");
    }

    @Test
    void aRealPlayersRowAndRoundAreTheSameWithOrWithoutAnNpc() {
        GolfGroup with = group(LILAH, DAD);
        GolfGroup without = group(LILAH, DAD);
        with.addNpc(PIP, CpuRoster.tagged("Pip"));
        int[][] lilah = {{3, 4}, {4, 6}, {3, 3}};
        int[][] dad = {{3, 3}, {4, 4}, {3, 2}};
        for (int h = 0; h < 3; h++) {
            with.npcPlan(PIP, 1); // Pip does very well
            with.npcHoleDone(PIP);
            for (GolfGroup g : List.of(with, without)) {
                g.holeDone(LILAH, new GolfRun.HoleScore(lilah[h][0], lilah[h][1], false));
            }
            assertEquals(without.clock(), with.clock(), "the same hole clock for Dad");
            assertEquals(without.allDone(), with.allDone(), "and the same wait");
            for (GolfGroup g : List.of(with, without)) {
                g.holeDone(DAD, new GolfRun.HoleScore(dad[h][0], dad[h][1], false));
                g.advance();
            }
        }
        assertTrue(with.over() && without.over());
        assertEquals(without.card().rows().get(0), with.card().rows().get(0), "Lilah's row: the same");
        assertEquals(without.card().rows().get(1), with.card().rows().get(1), "Dad's row: the same");
        List<GolfGroup.Standing> rank = with.ranking();
        assertEquals(PIP, rank.get(0).player(), "the group ranking shows Pip's 3 first");
        assertEquals(3, rank.size(), "with everyone");
    }
}
