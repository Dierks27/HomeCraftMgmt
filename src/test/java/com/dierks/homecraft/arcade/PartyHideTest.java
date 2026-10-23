package com.dierks.homecraft.arcade;

import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.PrizeDao;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hide-others switch stops parties too (SHOP-V2-SPEC §5.6, red team O14): no fireworks are spawned around a player
 * who hides other people's trails and parties, except the buyer's own; the switch is the player's pref
 * {@code arcade.trails.hide_others = "1"} in {@code game_prefs} (absent = shown), read and written by PrizeDao.
 */
class PartyHideTest {

    @Test
    void noFireworksAroundSomeoneWhoHidesOthersExceptTheBuyer() {
        assertTrue(PartyService.guest(true, false, false, false, false), "a guest who shows others' parties");
        assertFalse(PartyService.guest(true, false, false, true, false), "hides others: no fireworks around them");
        assertTrue(PartyService.guest(true, false, false, true, true), "the buyer always gets theirs");
        assertFalse(PartyService.guest(true, true, false, false, true), "never in a game, the buyer neither");
        assertFalse(PartyService.guest(false, false, false, false, false), "an economy world only");
    }

    @Test
    void theSwitchIsAPrefInGamePrefs() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Database db = Database.open(conn, Logger.getAnonymousLogger());
            PrizeDao dao = new PrizeDao(db);
            UUID her = UUID.randomUUID();
            assertEquals("arcade.trails.hide_others", TrailService.HIDE_PREF);
            assertNull(dao.pref(her, TrailService.HIDE_PREF), "default: shown");
            dao.setPref(her, TrailService.HIDE_PREF, "1");
            assertEquals("1", dao.pref(her, TrailService.HIDE_PREF));
            dao.setPref(her, TrailService.HIDE_PREF, "1");
            assertEquals("1", dao.pref(her, TrailService.HIDE_PREF), "set twice: one row");
            dao.setPref(her, TrailService.HIDE_PREF, null);
            assertNull(dao.pref(her, TrailService.HIDE_PREF), "shown again: the row goes");
            assertNull(dao.pref(UUID.randomUUID(), TrailService.HIDE_PREF), "per player");
        }
    }
}
