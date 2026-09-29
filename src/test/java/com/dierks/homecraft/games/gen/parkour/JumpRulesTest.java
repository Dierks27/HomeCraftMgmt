package com.dierks.homecraft.games.gen.parkour;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parkour rule table (GEN-SPEC §4.1) against the jump simulation: every jump a tier allows
 * keeps that tier's margin, and every margin is more than a jump pressed one tick early loses,
 * so no allowed jump needs a frame-perfect press. Easy never needs a sprint; Hard never has a
 * flat 4-gap; pads that aren't neighbours are out of reach.
 */
class JumpRulesTest {

    @Test
    void everyAllowedJumpKeepsItsTiersMargin() {
        for (JumpRules.Level level : JumpRules.Level.values()) {
            int checked = 0;
            for (JumpRules.Row row : level.rows()) {
                for (boolean diagonal : new boolean[]{false, true}) {
                    for (int[] s : JumpRules.shapes(level, row.dy(), diagonal)) {
                        double d = JumpRules.gap(s[0], s[1]);
                        double margin = JumpSim.reach(row.dy(), level.mode()) - d;
                        assertTrue(margin >= level.margin() - 1e-9, level + ": " + row.dy() + " over " + d
                                + " keeps " + margin + ", below the tier's " + level.margin());
                        assertEquals(margin, JumpRules.margin(level, row.dy(), d), 1e-12, "margin() says the same");
                        checked++;
                    }
                }
                // the row's widest gap, whatever shape reaches it
                double worst = JumpSim.reach(row.dy(), level.mode()) - row.maxD();
                assertTrue(worst >= level.margin() - 1e-9, level + " row " + row.dy() + ": its widest gap keeps "
                        + worst);
            }
            assertTrue(checked >= level.rows().size(), level + " has a shape for every row");
        }
    }

    @Test
    void everyMarginIsMoreThanOneTickOfTravel() {
        for (JumpRules.Level level : JumpRules.Level.values()) {
            assertTrue(level.margin() > JumpSim.tickLoss(level.mode()), level + ": margin " + level.margin()
                    + " must beat a tick early (" + JumpSim.tickLoss(level.mode()) + ")");
        }
    }

    @Test
    void theTightestRowOfEachTierIsTheOneTheSpecNames() {
        assertEquals(0.68, tightest(JumpRules.Level.EASY), 0.05, "easy: 0 over 2, walking");
        assertEquals(1.28, tightest(JumpRules.Level.MEDIUM), 0.05, "medium: 0 over 3, sprinting");
        assertEquals(0.41, tightest(JumpRules.Level.HARD), 0.05, "hard: +1 over 3, sprinting");
        assertEquals(JumpSim.reach(1, JumpSim.Mode.SPRINT) - 3, tightest(JumpRules.Level.HARD), 1e-9,
                "hard's tightest is exactly the +1 over 3 (the one the acceptance pass checks on Bedrock)");
    }

    private static double tightest(JumpRules.Level level) {
        double min = Double.MAX_VALUE;
        for (JumpRules.Row row : level.rows()) {
            min = Math.min(min, JumpSim.reach(row.dy(), level.mode()) - row.maxD());
        }
        return min;
    }

    @Test
    void easyWalksAndTheOthersSprint() {
        assertEquals(JumpSim.Mode.WALK, JumpRules.Level.EASY.mode(), "easy needs no sprint (touch screens)");
        assertEquals(JumpSim.Mode.SPRINT, JumpRules.Level.MEDIUM.mode(), "medium sprints");
        assertEquals(JumpSim.Mode.SPRINT, JumpRules.Level.HARD.mode(), "hard sprints");
        for (JumpRules.Row row : JumpRules.Level.EASY.rows()) {
            assertTrue(JumpSim.walkReach(row.dy()) - row.maxD() >= 0.6, "easy row " + row.dy() + " by walking");
        }
    }

    @Test
    void theRowsAreTheSpecsTable() {
        assertRow(JumpRules.Level.EASY, 1, 1, 1);
        assertRow(JumpRules.Level.EASY, 0, 1, 2);
        assertRow(JumpRules.Level.EASY, -1, 1, 2);
        assertNull(JumpRules.Level.EASY.row(-2), "easy never drops 2");
        assertRow(JumpRules.Level.MEDIUM, 1, 1, 2);
        assertRow(JumpRules.Level.MEDIUM, 0, 2, 3);
        assertRow(JumpRules.Level.MEDIUM, -2, 2, 3);
        assertRow(JumpRules.Level.HARD, 1, 1, 3);
        assertRow(JumpRules.Level.HARD, 0, 2, 3);
        assertRow(JumpRules.Level.HARD, -1, 1, 4);
        assertRow(JumpRules.Level.HARD, -2, 1, 4);
        for (JumpRules.Level level : JumpRules.Level.values()) {
            assertNull(level.row(2), level + " never climbs 2 (the jump peaks at 1.25)");
        }
        assertEquals(12, JumpRules.Level.EASY.jumps(), "easy: 12 jumps");
        assertEquals(3, JumpRules.Level.EASY.checkpoints(), "easy: a checkpoint every 3 jumps, 3 in all");
        assertEquals(4, JumpRules.Level.MEDIUM.checkpoints(), "medium: every 4 of 20");
        assertEquals(5, JumpRules.Level.HARD.checkpoints(), "hard: every 5 of 28");
        assertEquals(List.of(6, 14, 24), List.of(JumpRules.Level.EASY.band(), JumpRules.Level.MEDIUM.band(),
                JumpRules.Level.HARD.band()), "height bands");
    }

    private static void assertRow(JumpRules.Level level, int dy, double min, double max) {
        JumpRules.Row row = level.row(dy);
        assertNotNull(row, level + " allows dy " + dy);
        assertEquals(min, row.minD(), 1e-9, level + " dy " + dy + " smallest gap");
        assertEquals(max, row.maxD(), 1e-9, level + " dy " + dy + " widest gap");
    }

    @Test
    void hardHasNoFlatFourGapAndNobodyJumpsAcrossACorner() {
        assertFalse(JumpRules.allowed(JumpRules.Level.HARD, 0, 4, 0, 1), "no flat 4-gap, even on hard");
        assertTrue(JumpRules.allowed(JumpRules.Level.HARD, -1, 4, 0, 1), "a 4-gap down one is fine");
        assertFalse(JumpRules.allowed(JumpRules.Level.HARD, 0, 3, 2, 0), "a side gap of 2 isn't a straight jump");
        assertTrue(JumpRules.allowed(JumpRules.Level.HARD, 0, 2, 2, 0), "a clean diagonal (after a 45 turn) is");
        assertFalse(JumpRules.allowed(JumpRules.Level.EASY, 0, 1, 1, 0), "easy never jumps diagonally");
        assertFalse(JumpRules.allowed(JumpRules.Level.EASY, 0, 2, 1, 0), "nor with a side gap");
        assertFalse(JumpRules.allowed(JumpRules.Level.EASY, 0, 2, 0, 1), "and lands on a pad sharing 2 rows");
        assertTrue(JumpRules.allowed(JumpRules.Level.EASY, 0, 2, 0, 2), "which is fine");
        assertFalse(JumpRules.allowed(JumpRules.Level.MEDIUM, 0, 0, 0, 3), "pads never touch");
        assertNotNull(JumpRules.problem(JumpRules.Level.HARD, 0, 4, 0, 1), "and a refusal says why");
    }

    @Test
    void shapesHoldOnlyAllowedJumps() {
        for (JumpRules.Level level : JumpRules.Level.values()) {
            for (int dy = -2; dy <= 1; dy++) {
                for (boolean diagonal : new boolean[]{false, true}) {
                    for (int[] s : JumpRules.shapes(level, dy, diagonal)) {
                        assertTrue(JumpRules.allowed(level, dy, s[0], s[1], JumpRules.EASY_OVERLAP),
                                level + " shape " + s[0] + "," + s[1] + " for dy " + dy);
                        assertEquals(diagonal, s[0] == s[1], "a diagonal shape has equal gaps");
                    }
                }
            }
            assertEquals(level.diagonals(), !JumpRules.shapes(level, 0, true).isEmpty(),
                    level + " has diagonal shapes exactly when it may turn 45");
        }
        assertTrue(JumpRules.shapes(JumpRules.Level.EASY, 2, false).isEmpty(), "no shapes for +2");
    }

    @Test
    void padsThatArentNeighboursAreOutOfReach() {
        for (JumpRules.Level level : JumpRules.Level.values()) {
            for (int dy = -20; dy <= 3; dy++) {
                double need = JumpRules.minSkipGap(level, dy, false, 0);
                assertTrue(need >= JumpRules.SKIP_FLOOR, "never closer than 6");
                assertTrue(need >= 1 + JumpSim.sprintReach(dy), "and a block past sprinting reach at dy " + dy);
                double turn = JumpRules.minSkipGap(level, dy, true, 2.5);
                JumpSim.Mode mode = level == JumpRules.Level.EASY ? JumpSim.Mode.WALK : JumpSim.Mode.SPRINT;
                assertTrue(turn >= 1 + JumpSim.reach(dy, mode), level + ": around a turn still out of reach");
                assertTrue(turn > 2.5, level + ": and further than the jump onto the turn pad");
                assertTrue(turn <= need, level + ": the turn rule only ever relaxes the 6");
            }
        }
    }

    @Test
    void turnsFollowTheTiers() {
        assertTrue(JumpRules.turnAllowed(JumpRules.Level.EASY, 2, true, 3, 3), "easy turns 90 on a checkpoint");
        assertFalse(JumpRules.turnAllowed(JumpRules.Level.EASY, 2, false, 3, 3), "but nowhere else");
        assertFalse(JumpRules.turnAllowed(JumpRules.Level.EASY, 1, true, 3, 3), "and never 45");
        assertTrue(JumpRules.turnAllowed(JumpRules.Level.MEDIUM, -1, false, 2, 2), "medium turns 45 on a 2x2");
        assertTrue(JumpRules.turnAllowed(JumpRules.Level.MEDIUM, 2, false, 2, 2), "and 90");
        assertFalse(JumpRules.turnAllowed(JumpRules.Level.MEDIUM, 1, false, 1, 1), "not on a 1x1");
        assertTrue(JumpRules.turnAllowed(JumpRules.Level.HARD, 1, false, 1, 1), "hard turns 45 anywhere");
        assertFalse(JumpRules.turnAllowed(JumpRules.Level.HARD, 2, false, 1, 2), "90 only on checkpoints");
        assertTrue(JumpRules.turnAllowed(JumpRules.Level.HARD, -2, true, 3, 3), "where it may");
        for (JumpRules.Level level : JumpRules.Level.values()) {
            assertTrue(JumpRules.turnAllowed(level, 0, false, 1, 1), level + " always goes straight on");
            assertFalse(JumpRules.turnAllowed(level, 3, true, 3, 3), level + " never turns more than 90");
        }
    }

    @Test
    void theTiersAreReadFromTheirConfigWords() {
        assertEquals(JumpRules.Level.EASY, JumpRules.Level.of(" Easy "), "any case, trimmed");
        assertEquals(JumpRules.Level.HARD, JumpRules.Level.of("hard"), "hard");
        assertNull(JumpRules.Level.of("extreme"), "no extreme generated parkour");
        assertNull(JumpRules.Level.of(null), "nothing is no tier");
    }
}
