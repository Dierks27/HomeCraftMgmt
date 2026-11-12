package com.dierks.homecraft.graves;

import com.dierks.homecraft.graves.GraveRules.Decision;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which deaths make a grave, how much experience goes in, who may open one, and when it expires. */
class GraveRulesTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;

    /** decide(enabled, worldDisabled, cancelled, keepInventory, inGameSession, keeps, keepInventoryMakesGrave). */
    @Test
    void aNormalDeathMakesAGrave() {
        assertEquals(Decision.MAKE, GraveRules.decide(true, false, false, false, false, false, true));
    }

    @Test
    void aKeepInventoryDeathStillMakesAGraveFromTheInventory() {
        // fairyhub's gamerule or Essentials' keepinv: everyone still walks back for their things.
        assertEquals(Decision.MAKE_FROM_INVENTORY, GraveRules.decide(true, false, false, true, false, false, true));
    }

    @Test
    void keepInventoryMakesNoGraveWhenTheOwnerSaysSo() {
        assertEquals(Decision.KEEP_INVENTORY, GraveRules.decide(true, false, false, true, false, false, false));
        assertEquals(Decision.WORLD_OFF, GraveRules.decide(true, true, false, true, false, false, true),
                "a disabled world (fairyhub listed there) keeps the gamerule's keep");
    }

    @Test
    void aPlayerWhoKeepsEverythingNeverGetsAGrave() {
        assertEquals(Decision.KEPT, GraveRules.decide(true, false, false, false, false, true, true));
        assertEquals(Decision.KEPT, GraveRules.decide(true, false, false, true, false, true, true),
                "whatever the gamerule");
        assertTrue(GraveRules.keepsEverything(true, false, true));
        assertFalse(GraveRules.keepsEverything(true, false, false), "everyone else");
        assertFalse(GraveRules.keepsEverything(false, false, true), "graves off: HCM does nothing on a death");
        assertFalse(GraveRules.keepsEverything(true, true, true), "a game session's death is the Games'");
    }

    @Test
    void aDeathInAGameSessionMakesNoGrave() {
        assertEquals(Decision.GAME_SESSION, GraveRules.decide(true, false, false, false, true, false, true));
        assertEquals(Decision.GAME_SESSION, GraveRules.decide(true, false, false, true, true, false, true),
                "a keep-inventory one too");
        assertEquals(Decision.CANCELLED, GraveRules.decide(true, false, true, false, true, false, true),
                "the usual case: the Games revived them at LOWEST, so the death was cancelled");
    }

    @Test
    void switchedOffOrADisabledWorldMakesNoGrave() {
        assertEquals(Decision.OFF, GraveRules.decide(false, false, false, false, false, false, true));
        assertEquals(Decision.OFF, GraveRules.decide(false, false, false, false, false, true, true));
        assertEquals(Decision.WORLD_OFF, GraveRules.decide(true, true, false, false, false, false, true));
    }

    @Test
    void nothingToKeepIsNoGrave() {
        assertTrue(GraveRules.empty(0, 0), "no drops and no experience: no grave, no message");
        assertFalse(GraveRules.empty(1, 0));
        assertFalse(GraveRules.empty(0, 5), "experience alone is worth a grave");
    }

    @Test
    void totalExperienceFollowsTheGamesCurve() {
        assertEquals(0, GraveRules.totalXp(0, 0f));
        assertEquals(7, GraveRules.totalXp(1, 0f));
        assertEquals(352, GraveRules.totalXp(16, 0f));
        assertEquals(394, GraveRules.totalXp(17, 0f));
        assertEquals(1395, GraveRules.totalXp(30, 0f));
        assertEquals(1507, GraveRules.totalXp(31, 0f));
        assertEquals(1628, GraveRules.totalXp(32, 0f));
        assertEquals(352 + 21, GraveRules.totalXp(16, 0.5f), "half of the 42 points from 16 to 17");
        assertEquals(GraveRules.totalXp(17, 0f), GraveRules.totalXp(16, 1f), "a full bar is the next level");
    }

    @Test
    void experiencePercent() {
        assertEquals(1395, GraveRules.xpToStore(1395, 100, false));
        assertEquals(697, GraveRules.xpToStore(1395, 50, false));
        assertEquals(0, GraveRules.xpToStore(1395, 0, false));
        assertEquals(0, GraveRules.xpToStore(1395, 100, true), "the level is kept: nothing goes in the grave");
    }

    @Test
    void onlyTheOwnerAndAdminsOpenIt() {
        assertTrue(GraveRules.canOpen(OWNER, OWNER, false, 0, 10 * HOUR, 0));
        assertTrue(GraveRules.canOpen(OWNER, OTHER, true, 0, 0, 0), "an admin");
        assertFalse(GraveRules.canOpen(OWNER, OTHER, false, 0, 1000 * HOUR, 0), "unlock 0: never anyone else");
    }

    @Test
    void theUnlockTimerLetsAnyoneLootAfterIt() {
        assertFalse(GraveRules.canOpen(OWNER, OTHER, false, 0, 29 * MIN, 30));
        assertTrue(GraveRules.canOpen(OWNER, OTHER, false, 0, 30 * MIN, 30));
        assertEquals(MIN, GraveRules.unlocksIn(0, 29 * MIN, 30));
        assertEquals(-1, GraveRules.unlocksIn(0, 31 * MIN, 30), "already unlocked");
        assertEquals(-1, GraveRules.unlocksIn(0, 0, 0), "never unlocks");
    }

    @Test
    void expiry() {
        assertFalse(GraveRules.expired(0, 1000 * HOUR, 0), "0 = never");
        assertFalse(GraveRules.expired(0, 47 * HOUR, 48));
        assertTrue(GraveRules.expired(0, 48 * HOUR, 48));
    }

    @Test
    void ages() {
        assertEquals("just now", GraveRules.age(30_000));
        assertEquals("5m", GraveRules.age(5 * MIN));
        assertEquals("2h 3m", GraveRules.age(2 * HOUR + 3 * MIN));
        assertEquals("3d 4h", GraveRules.age(76 * HOUR));
    }
}
