package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure rules of RETIRE ({@link OldAreas}) and of a claim's recorded sizes ({@link Regions#claimHalves}):
 * what is Fresh Courses' own, what is in the way, what crowds a region waiting to be built, the record of
 * an old area emptied, and where an old claim's halves are (at the sizes it recorded, never today's).
 */
class OldAreasTest {

    private static final String GOLF_036 = "games,7488,160,4096,64,16,128,576";
    private static final String GOLF_035 = "games,4864,160,4096,64,16,128";

    @Test
    void everyBlockAPlanMayUseAndWaterAreOursAndNothingElseIs() {
        for (String b : Palette.ALLOWED) {
            assertTrue(OldAreas.ours(b), b + " is on the allowlist: a plan may have set it");
        }
        assertTrue(OldAreas.ours("minecraft:oak_leaves[distance=3,persistent=true,waterlogged=false]"),
                "states don't matter: the block is the plan's");
        assertTrue(OldAreas.ours("minecraft:water[level=0]"), "a pond's still water is ours");
        assertTrue(OldAreas.ours("minecraft:water[level=5]"), "and so is water that flowed out of one: drained too");
        for (String b : List.of("minecraft:bedrock", "minecraft:diamond_block", "minecraft:oak_planks",
                "minecraft:chest[facing=north]", "minecraft:lava[level=0]", "minecraft:spruce_planks")) {
            assertFalse(OldAreas.ours(b), b + " is no plan's: it is someone else's and stays");
            assertTrue(OldAreas.foreign(b), b + " is foreign");
        }
        assertFalse(OldAreas.ours(null), "nothing is nobody's");
    }

    @Test
    void aClaimsHalvesAreAtTheSizesAndGapItRecorded() {
        assertEquals(List.of(Box.sized(7488, 160, 4096, 64, 16, 128), Box.sized(8128, 160, 4096, 64, 16, 128)),
                Regions.claimHalves(GOLF_036), "0.36's golf: 64 x 16 x 128, half B 576 along x");
        assertEquals(List.of(Box.sized(4864, 160, 4096, 64, 16, 128), Box.sized(4960, 160, 4096, 64, 16, 128)),
                Regions.claimHalves(GOLF_035), "a 0.35 claim (7 fields) at 0.35's gap of 32");
        assertTrue(Regions.claimHalves(GOLF_036).get(0).sizeX() != Slots.DAILY_GOLF.sizeX(),
                "(golf's halves are bigger now: the claim's own size, never today's, finds what was built)");
        assertEquals(List.of(64, 16, 128), List.of(Regions.claimSize(GOLF_036)[0], Regions.claimSize(GOLF_036)[1],
                Regions.claimSize(GOLF_036)[2]), "fields 5-7 are one half's size");
        assertNull(Regions.claimHalves("games,1,2,3"), "an unreadable claim has no halves");
        assertNull(Regions.claimHalves("games,0,160,0,0,16,128,576"), "nor one with a size of 0");
        assertEquals("half A x 7488..7551, y 160..175, z 4096..4223; half B x 8128..8191, y 160..175, z 4096..4223",
                Regions.describeClaim(GOLF_036), "admins read the recorded halves");
    }

    @Test
    void aClaimIsResizedOnlyWhenItsHalfSizeDiffersFromTheSlotsNow() {
        assertTrue(Regions.resized(Slots.DAILY_GOLF, GOLF_036), "golf grew to 128 x 16 x 224");
        assertTrue(Regions.resized(Slots.ICE_BOAT, "games,6080,160,5888,128,16,128,576"), "the boat grew");
        assertFalse(Regions.resized(Slots.TINY_GOLF, "games,1,160,1,64,16,48,576"),
                "Tiny Golf kept its size: an origin change alone is an owner's move, not this");
        assertFalse(Regions.resized(Slots.DAILY_GOLF, Regions.claim(Slots.DAILY_GOLF, "games", new int[]{0, 160, 0},
                576)), "a claim at today's size isn't resized, wherever it is");
        assertFalse(Regions.resized(Slots.DAILY_GOLF, "junk"), "an unreadable claim is never resized");
    }

    @Test
    void somethingWithinSixteenBlocksOfAnOldHalfHoldsItAndSaysWhat() {
        List<Box> halves = Regions.claimHalves(GOLF_036);
        // half A ends at z 4223: z 4239 leaves 15 blocks between, z 4240 leaves 16
        OldAreas.Obstacle near = new OldAreas.Obstacle("games", Box.sized(7488, 160, 4239, 16, 16, 16), "the Clubhouse");
        OldAreas.Obstacle far = new OldAreas.Obstacle("games", Box.sized(7488, 160, 4240, 16, 16, 16), "the arena");
        OldAreas.Obstacle elsewhere = new OldAreas.Obstacle("other", Box.sized(7488, 160, 4096, 16, 16, 16), "a plot");
        assertNull(OldAreas.inTheWay(halves, "games", List.of(far, elsewhere), null, null),
                "16 blocks off, or in another world, is out of the way");
        String why = OldAreas.inTheWay(halves, "games", List.of(far, near), null, null);
        assertNotNull(why, "16 blocks is the clearance; 15 is in the way");
        assertTrue(why.startsWith("the Clubhouse is only 15 blocks from its old half A"), why);
        assertTrue(OldAreas.inTheWay(halves, "games", List.of(), new int[]{8150, 165, 4100}, null)
                .contains("spawn is in or next to its old half B"), "the spawn in half B");
        assertTrue(OldAreas.inTheWay(halves, "games", List.of(), null, new double[]{7472.5, 165, 4100})
                .contains("safe_spot"), "the safe spot 15 blocks off half A is in the way");
        assertNull(OldAreas.inTheWay(halves, "games", List.of(), null, new double[]{7471.9, 165, 4100}),
                "x 7471 has 16 blocks between it and x 7488: out of the way");
        assertEquals("its halves can't be read", OldAreas.inTheWay(null, "games", List.of(), null, null),
                "no halves: never emptied");
    }

    @Test
    void anOldHalfWithinTheBuildRuleOfAWaitingRegionCrowdsIt() {
        List<Box> old = Regions.claimHalves(GOLF_036);
        assertTrue(OldAreas.crowds(old, List.of(Box.sized(7488, 160, 4096, 128, 16, 224))),
                "resized in place: the new half covers the old one");
        assertTrue(OldAreas.crowds(old, List.of(Box.sized(7488, 160, 4224 + 31, 16, 16, 16))),
                "31 blocks off is inside the build rule's 32");
        assertFalse(OldAreas.crowds(old, List.of(Box.sized(7488, 160, 4224 + 32, 16, 16, 16))), "32 is not");
        assertFalse(OldAreas.crowds(old, List.of(Box.sized(8768, 160, 4096, 128, 16, 224))),
                "golf's new column is 576 off");
    }

    @Test
    void theRecordOfAnEmptiedAreaRoundTrips() {
        OldAreas.Retired r = new OldAreas.Retired(1_759_400_000_000L, GOLF_036, 1234, 2,
                List.of("7490,161,4100 minecraft:bedrock", "7491,161,4100 minecraft:chest[facing=north,type=single]"));
        assertEquals(r, OldAreas.Retired.parse(r.text()), "what is stored reads back");
        OldAreas.Retired none = new OldAreas.Retired(5, GOLF_036, 0, 0, List.of());
        assertEquals(none, OldAreas.Retired.parse(none.text()), "with nothing left");
        assertEquals(List.of("a/b"), OldAreas.Retired.parse(new OldAreas.Retired(5, GOLF_036, 1, 1,
                List.of("a|b")).text()).firstLeft(), "a separator in a block's text can't break the record");
        assertNull(OldAreas.Retired.parse("junk"), "unreadable: nothing to say");
        assertNull(OldAreas.Retired.parse(null), "unset: nothing to say");
    }

    @Test
    void eachStateHasItsOwnStatusLine() {
        String where = Regions.describeClaim(GOLF_036);
        assertEquals("emptying its old area (" + where + "), 63%", OldAreas.line(new OldAreas.Area("fresh_golf",
                "Golf of the Week", false, GOLF_036, where, OldAreas.State.RUNNING, null, 63)), "running");
        assertTrue(OldAreas.line(new OldAreas.Area("fresh_golf", "Golf of the Week", false, GOLF_036, where,
                OldAreas.State.WAITING, null, 0)).endsWith("is emptied by itself once nothing else is being built"),
                "waiting");
        assertTrue(OldAreas.line(new OldAreas.Area("fresh_golf", "Golf of the Week", false, GOLF_036, where,
                OldAreas.State.HELD, "the Clubhouse is inside its old half A", 0))
                .contains("can't be emptied: the Clubhouse is inside its old half A - it stays guarded"), "held");
        assertTrue(OldAreas.line(new OldAreas.Area("fresh_dropper", "Dropper", false, GOLF_036, where,
                OldAreas.State.MANUAL, null, 0)).endsWith("/hcm games gen tidy fresh_dropper confirm"),
                "an owner's move: the one command");
        assertTrue(OldAreas.line(new OldAreas.Area("fresh_golf", "Golf of the Week", false, GOLF_036, where,
                OldAreas.State.ELSEWHERE, "old_games", 0)).contains("waits for its world old_games"), "elsewhere");
    }

    @Test
    void anEmptiedClaimKeepsItsPlaceInTheRecordWithWhenItWasEmptied() {
        String other = "games,6080,160,5888,128,16,128,576";
        String text = Regions.oldText(List.of(GOLF_036, other), java.util.Map.of(GOLF_036, 1_790_000_000_000L));
        assertEquals(GOLF_036 + "|emptied@1790000000000;" + other, text, "the mark follows its claim");
        assertEquals(List.of(GOLF_036, other), Regions.oldClaims(text), "the claims read as they always did");
        assertEquals(java.util.Map.of(GOLF_036, 1_790_000_000_000L), Regions.oldEmptied(text), "and when it was emptied");
        assertEquals(java.util.Map.of(), Regions.oldEmptied(GOLF_036 + "|emptied@soon"),
                "an unreadable mark reads as not emptied: it is emptied again, writing nothing");
        assertEquals(List.of(GOLF_036), Regions.oldClaims(GOLF_036 + "|emptied@soon"), "the claim itself still reads");
        assertNull(Regions.oldText(List.of(), java.util.Map.of()), "none: the key unset");
    }

    @Test
    void cuttingAnOldHalfLeavesExactlyThePartsOutsideTheCourseStandingThere() {
        Box old = Box.sized(7488, 160, 4096, 64, 16, 128);
        assertEquals(List.of(), OldAreas.minus(old, List.of(Box.sized(7488, 160, 4096, 128, 16, 224))),
                "inside the grown half A: nothing left to look at");
        assertEquals(List.of(old), OldAreas.minus(old, List.of(Box.sized(8192, 160, 4096, 128, 16, 224))),
                "a half it doesn't touch: all of it");
        Box cut = Box.sized(7500, 162, 4100, 10, 4, 10);
        List<Box> parts = OldAreas.minus(old, List.of(cut));
        long volume = parts.stream().mapToLong(Box::volume).sum();
        assertEquals(old.volume() - cut.volume(), volume, "every block but the cut one, once: " + parts);
        for (Box p : parts) {
            assertFalse(p.intersects(cut), "no part overlaps the cut: " + p);
            assertTrue(old.contains(p), "every part is inside the old half: " + p);
            for (Box q : parts) {
                assertTrue(p == q || !p.intersects(q), "no two parts overlap: " + p + " / " + q);
            }
        }
    }

    @Test
    void anEmptiedAreaSaysItWaitsForTheSave() {
        String where = Regions.describeClaim(GOLF_036);
        assertTrue(OldAreas.line(new OldAreas.Area("fresh_golf", "Golf of the Week", false, GOLF_036, where,
                OldAreas.State.EMPTIED, null, 0)).endsWith("is empty; it stays guarded until the world has been saved"
                + " (or the next start finds it still empty), then it is let go"), "emptied, waiting for the save");
    }
}
