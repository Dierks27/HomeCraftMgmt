package com.dierks.homecraft.arcade.looks;

import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.LookDao;
import com.dierks.homecraft.storage.LookDao.Look;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static com.dierks.homecraft.arcade.looks.LookFixtures.DAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A heads-up is never shown in a world game, on the way back from one, in a Games world, on the death screen or
 * behind a Bedrock chest screen, and its stage is written only when it is sent (SHOP-V2-SPEC §3.10): a held notice is
 * still due when it may be shown. The sending step on a real SQLite, as the service runs it: what
 * {@link LookRules#dueNow} gives is written, and nothing else.
 */
class NoticeHoldTest {

    private Connection conn;
    private LookDao looks;
    private final UUID her = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        looks = new LookDao(Database.open(conn, Logger.getAnonymousLogger()));
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    /** The service's sending step: write each due one's stage (it is then sent). */
    private int send(boolean allowed, long now) throws Exception {
        List<LookRules.Due> due = LookRules.dueNow(allowed, looks.looks(her), now);
        for (LookRules.Due d : due) {
            looks.notice(her, d.row().id(), LookRules.stage(d.notice()), d.row().expiresAt());
        }
        return due.size();
    }

    @Test
    void heldEverywhereAGameOrTheDeathScreenIs() {
        assertFalse(LookRules.noticesAllowed(true, false, false, false, true), "in a world game");
        assertFalse(LookRules.noticesAllowed(false, false, false, false, true), "on the way back from one");
        assertFalse(LookRules.noticesAllowed(false, true, true, false, true), "in a Games world");
        assertFalse(LookRules.noticesAllowed(false, true, false, true, true), "dead");
        assertFalse(LookRules.noticesAllowed(false, true, false, false, false), "behind a chest screen on Bedrock");
        assertTrue(LookRules.noticesAllowed(false, true, false, false, true));
    }

    /**
     * Red-team finding DJ-2: on Bedrock a chest screen covers the title, and an Arcade cabinet game (Snake, Creeper
     * Sweeper) is a chest screen with no game session. A heads-up that came due there went off unseen and its stage
     * was used up; it now waits for the screen to close. Java draws a title over any screen, so it is unchanged.
     */
    @Test
    void aHeadsUpWaitsForHerScreenToClose() throws Exception {
        assertFalse(LookRules.screenFree(true, false), "Bedrock, a chest screen open");
        assertTrue(LookRules.screenFree(true, true), "Bedrock, only her own bag view");
        assertTrue(LookRules.screenFree(false, false), "Java: a title shows over a screen");
        long end = 100 * DAY;
        looks.write(her, "cap_lime", LookDao.HAT, end, 10 * DAY, "");
        long now = end - 6 * DAY;
        assertEquals(0, send(LookRules.noticesAllowed(false, true, false, false, LookRules.screenFree(true, false)),
                now), "playing Snake: held");
        assertEquals(0, looks.look(her, "cap_lime").noticeStage(), "and its stage not used up");
        assertEquals(1, send(LookRules.noticesAllowed(false, true, false, false, LookRules.screenFree(true, true)),
                now + 60_000), "the next minute after the screen closed: sent");
        assertEquals(7, looks.look(her, "cap_lime").noticeStage());
    }

    /**
     * Red-team finding F1: the start that brings v37 marks every end 0.37 already had as said, so her first 0.38 join
     * doesn't put up "2 looks have ended" with a 100-token pitch for crate trails that ran out last week. A trail still
     * running then ends quietly; a row from 0.38 (a run start known) is never touched.
     */
    @Test
    void endsBeforeTheUpgradeAreNotSaidAgain() throws Exception {
        long upgrade = 400 * DAY;
        looks.write(her, "trail_hearts", LookDao.TRAIL, upgrade - 3 * DAY, 0, "");
        looks.write(her, "trail_sparkles", LookDao.TRAIL, upgrade - DAY, 0, "");
        looks.write(her, "trail_notes", LookDao.TRAIL, upgrade + 10 * 3_600_000L, 0, "");
        looks.write(her, "cap_lime", LookDao.HAT, upgrade - 1, upgrade - 1 - 90 * DAY, "");
        assertEquals(2, looks.endsBeforeUpgrade(upgrade), "the two 0.37 ends already past");
        assertEquals(-1, looks.look(her, "trail_hearts").noticeStage());
        assertEquals(upgrade - 3 * DAY, looks.look(her, "trail_hearts").noticeFor());
        assertEquals(0, looks.look(her, "trail_notes").noticeStage(), "still running: its end is still to come");
        assertEquals(0, looks.look(her, "cap_lime").noticeStage(), "a 0.38 row is never touched");

        List<LookRules.Due> due = LookRules.dueNow(true, looks.looks(her), upgrade + 5_000);
        assertEquals(1, due.size(), "only the 0.38 row's end: " + due);
        assertEquals("cap_lime", due.get(0).row().id());

        long notesEnd = upgrade + 10 * 3_600_000L;
        due = LookRules.dueNow(true, looks.looks(her), notesEnd + 60_000);
        LookRules.Due notes = due.stream().filter(d -> d.row().id().equals("trail_notes")).findFirst().orElseThrow();
        assertEquals(LookRules.Notice.ENDED, notes.notice(), "the live one's end is said...");
        assertNull(LookRules.titleFor(List.of(notes), notesEnd + 60_000, id -> id), "...with no title");
        assertTrue(LookRules.quietEnd(notes.row().since(), notes.row().expiresAt()), "...and no price");
    }

    @Test
    void theStageIsWrittenOnlyWhenSent() throws Exception {
        long end = 100 * DAY;
        looks.write(her, "cap_lime", LookDao.HAT, end, 10 * DAY, "");
        long now = end - 6 * DAY;
        assertEquals(0, send(LookRules.noticesAllowed(true, false, false, false, true), now), "in a game: nothing sent");
        Look held = looks.look(her, "cap_lime");
        assertEquals(0, held.noticeStage(), "and nothing written");
        assertEquals(0, held.noticeFor());
        assertEquals(0, send(LookRules.noticesAllowed(false, true, true, false, true), now + 60_000), "a Games world");
        assertEquals(0, looks.look(her, "cap_lime").noticeStage());

        assertEquals(1, send(true, now + 120_000), "home: the held one goes now");
        Look sent = looks.look(her, "cap_lime");
        assertEquals(7, sent.noticeStage());
        assertEquals(end, sent.noticeFor());
        assertEquals(0, send(true, now + 180_000), "and only once");
    }
}
