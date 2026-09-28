package com.dierks.homecraft.games.golf;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scorecard snapshot and the small words around a course (spec §12): the chat line colours
 * each hole against par and totals the round, the card knows which hole is being played, the
 * admin tool's names are clean, and each block kind means what the physics expects.
 */
class GolfCardTest {

    private static final List<GolfRun.HoleScore> THREE = List.of(new GolfRun.HoleScore(3, 2, false),
            new GolfRun.HoleScore(4, 4, false), new GolfRun.HoleScore(2, 5, true));

    @Test
    void theChatLineColoursEachHoleAgainstParAndTotalsIt() {
        GolfCard card = new GolfCard("meadow", "Meadow Links", List.of(3, 4, 2), THREE, 0, true);
        assertEquals("&7Card: &a2 &f4 &c5 &8| &fTotal 11 &7(+2)", card.line(),
                "green under par, white at par, red picked up; the total against par");
        assertEquals(11, card.total(), "2 + 4 + 5");
        assertEquals(2, card.vsPar(), "two over");
        assertEquals(9, card.par(), "par for the course");
        assertTrue(card.finished(), "every hole done");
        assertEquals(-1, card.playing(), "nothing being played");
    }

    @Test
    void aCardMidRoundKnowsTheHoleBeingPlayed() {
        GolfCard card = new GolfCard("m", "M", List.of(3, 4, 2), THREE.subList(0, 1), 2, false);
        assertFalse(card.finished(), "one of three");
        assertEquals(1, card.playing(), "the second hole");
        assertEquals(2, card.strokes(), "two strokes on it so far");
        GolfCard between = new GolfCard("m", "M", List.of(3, 4, 2), THREE.subList(0, 1), 0, true);
        assertEquals(-1, between.playing(), "between holes, none is being played");
        assertEquals("&7Card: &a2 &8| &fTotal 2 &7(-1)", between.line(), "the holes so far only");
    }

    @Test
    void aCardIsBuiltFromTheRound() {
        GolfCourse c = new GolfCourse("m", "M", "games", true, 1, List.of(
                new GolfCourse.Hole(null, null, 3, null, null), new GolfCourse.Hole(null, null, 2, null, null)));
        GolfRun run = new GolfRun(c.pars(), 3);
        run.stroke();
        run.stroke();
        run.inCup();
        run.stroke();
        GolfCard playing = GolfCard.of(c, run, false);
        assertEquals(List.of(3, 2), playing.pars(), "the course's pars");
        assertEquals(1, playing.scores().size(), "one hole done");
        assertEquals(1, playing.strokes(), "one stroke on the second");
        assertEquals(0, GolfCard.of(c, run, true).strokes(), "between holes there is no hole in play");
    }

    @Test
    void courseNamesAreCleanAndIdsReadAsNames() {
        assertEquals("Meadow Links", GolfAdmin.cleanName("  &aMeadow   Links "), "colour codes and extra spaces go");
        assertEquals(32, GolfAdmin.cleanName("x".repeat(40)).length(), "at most 32");
        assertEquals("Meadow Links", GolfAdmin.pretty("meadow_links"), "an id as a name");
        assertEquals("Hole 9", GolfAdmin.pretty("hole_9"), "digits stay");
    }

    @Test
    void eachBlockKindMeansWhatThePhysicsExpects() {
        assertEquals(BallPhysics.Surface.ICE, LiveBlocks.surface(Material.BLUE_ICE), "ice slides");
        assertEquals(BallPhysics.Surface.ICE, LiveBlocks.surface(Material.PACKED_ICE), "packed ice too");
        assertEquals(BallPhysics.Surface.SLOW, LiveBlocks.surface(Material.SOUL_SAND), "soul sand slows");
        assertEquals(BallPhysics.Surface.SLOW, LiveBlocks.surface(Material.SOUL_SOIL), "soul soil too");
        assertEquals(BallPhysics.Surface.SLOW, LiveBlocks.surface(Material.HONEY_BLOCK), "and honey");
        assertEquals(BallPhysics.Surface.SLIME, LiveBlocks.surface(Material.SLIME_BLOCK), "slime bounces");
        assertEquals(BallPhysics.Surface.WATER, LiveBlocks.surface(Material.WATER), "water sends it back");
        assertEquals(BallPhysics.Surface.LAVA, LiveBlocks.surface(Material.LAVA), "so does lava");
        assertEquals(BallPhysics.Surface.NORMAL, LiveBlocks.surface(Material.GRASS_BLOCK), "grass is normal");
    }
}
