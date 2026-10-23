package com.dierks.homecraft.arcade;

import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A worn Keeper's mark (SHOP-V2-SPEC §5.3a, red team D-B1): one puff of its reserved particle above the head, only
 * while the key is worn and the wearer walks, at most once in 2,000 ms, never where a trail can't show, hidden by
 * hide-others like a trail; the Crown's is its Royal Trail's own step, and not drawn while the Royal Trail is on.
 */
class KeeperMarkTest {

    private static final long T = 1_800_000_000_000L;

    @Test
    void aMarkPuffsWhileWornAndWalking() {
        assertTrue(TrailService.markPuffs(true, true, true, true, false, null, T));
        assertFalse(TrailService.markPuffs(true, false, true, true, false, null, T), "not worn: in the bag, nothing");
        assertFalse(TrailService.markPuffs(true, true, false, true, false, null, T), "standing still");
        assertFalse(TrailService.markPuffs(false, true, true, true, false, null, T), "marks switched off");
    }

    @Test
    void atMostOnceInTwoSeconds() {
        assertEquals(2_000, Keepers.MARK_MS);
        assertFalse(TrailService.markPuffs(true, true, true, true, false, T, T + 1_999));
        assertTrue(TrailService.markPuffs(true, true, true, true, false, T, T + 2_000));
        assertTrue(Keepers.MARK_MS / TrailService.PUFF_MS >= 13, "about 13 times sparser than a trail");
    }

    @Test
    void neverWhereATrailCantShow() {
        boolean inGame = TrailService.showsHere(true, false, false, false);
        boolean gamesWorld = TrailService.showsHere(false, true, false, false);
        boolean spectator = TrailService.showsHere(false, false, true, false);
        boolean invisible = TrailService.showsHere(false, false, false, true);
        for (boolean shows : new boolean[] {inGame, gamesWorld, spectator, invisible}) {
            assertFalse(TrailService.markPuffs(true, true, true, shows, false, null, T));
        }
    }

    @Test
    void hideOthersHidesMarksToo() {
        assertFalse(TrailService.sees(false, true, true, 1), "a viewer who hides others sees no mark");
        assertTrue(TrailService.sees(true, true, true, 0), "the wearer sees their own");
    }

    @Test
    void theCrownsMarkIsOffWhileItsRoyalTrailIsOn() {
        assertFalse(TrailService.markPuffs(true, true, true, true, true, null, T),
                "the Royal Trail is the mark then");
        assertEquals(Keepers.pattern(Keepers.ROYAL, Keepers.Variant.DEFAULT).steps().get(0),
                Keepers.mark(Keepers.CROWN, Keepers.Variant.DEFAULT), "the Crown's mark is the Royal step");
    }

    @Test
    void theMarksAreAsTheSpecDrawsThem() {
        TrailService.Step wizard = Keepers.mark(Keepers.WIZARD, Keepers.Variant.DEFAULT);
        assertEquals(Particle.WITCH, wizard.particle());
        assertEquals(new TrailService.Puff(2.1, 1, 0.15, 0.02, 0.15), wizard.puff());
        TrailService.Step knight = Keepers.mark(Keepers.KNIGHT, Keepers.Variant.DEFAULT);
        assertEquals(Particle.SMALL_FLAME, knight.particle());
        assertEquals(new TrailService.Puff(2.05, 1, 0.05, 0.01, 0.05), knight.puff());
        assertEquals(null, Keepers.mark(Keepers.AURORA, Keepers.Variant.DEFAULT), "a trail has no mark");
        assertEquals(null, Keepers.mark("hat_creeper", Keepers.Variant.DEFAULT), "nor a rental: no sparkle");
    }
}
