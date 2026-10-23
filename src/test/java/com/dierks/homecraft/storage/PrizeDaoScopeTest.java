package com.dierks.homecraft.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Prize Counter's trail bookkeeping keeps to trail rows once {@code cosmetics_owned} holds hats' rights too
 * (SHOP-V2-SPEC §3.2, D28): {@code enableOnly} never touches a HAT row, a HAT row never counts as "a trail on",
 * and {@code grantCosmetic} writes kind TRAIL and the run's start, keeping it while the trail is live.
 */
class PrizeDaoScopeTest {

    private static final long DAY = 86_400_000L;

    private Connection conn;
    private PrizeDao dao;
    private LookDao looks;
    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        Database db = Database.open(conn, Logger.getAnonymousLogger());
        dao = new PrizeDao(db);
        looks = new LookDao(db);
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    private PrizeDao.Cosmetic cosmetic(String id) throws SQLException {
        for (PrizeDao.Cosmetic c : dao.cosmetics(player)) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    /** A HAT row switched on by hand: no code writes one, so this is the worst case the scoping must survive. */
    private void hatOn(String id, long expiresAt) throws SQLException {
        looks.write(player, id, LookDao.HAT, expiresAt, 1, "");
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE cosmetics_owned SET enabled = 1 WHERE player=? AND cosmetic_id=?")) {
            ps.setString(1, player.toString());
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    @Test
    void enableOnlyNeverTouchesAHatRow() throws Exception {
        long now = 1_000_000;
        dao.grantCosmetic(player, "trail_hearts", 30 * DAY, now, true);
        dao.grantCosmetic(player, "trail_notes", 30 * DAY, now, false);
        looks.write(player, "hat_creeper", LookDao.HAT, now + 90 * DAY, now, "");
        hatOn("cap_pink", now + 90 * DAY);

        dao.enableOnly(player, "trail_notes");
        assertTrue(cosmetic("trail_notes").enabled());
        assertFalse(cosmetic("trail_hearts").enabled(), "the other trail goes off");
        assertTrue(cosmetic("cap_pink").enabled(), "switching trails doesn't write a HAT row");

        dao.enableOnly(player, "hat_creeper");
        assertFalse(cosmetic("hat_creeper").enabled(), "a hat's right is never switched on");
        assertFalse(cosmetic("trail_notes").enabled(), "the trails all went off, as for any id");

        dao.enableOnly(player, null);
        assertTrue(cosmetic("cap_pink").enabled(), "off for every trail, still not a HAT row");
        assertEquals(LookDao.HAT, cosmetic("hat_creeper").kind());
    }

    @Test
    void aHatRowNeverCountsAsATrailOn() throws Exception {
        long now = 1_000_000;
        hatOn("hat_dragon", now + 90 * DAY);
        dao.grantCosmetic(player, "trail_snow", 30 * DAY, now, true);
        assertTrue(cosmetic("trail_snow").enabled(), "the first trail goes on: a hat isn't a trail that's on");
        dao.grantCosmetic(player, "trail_stars", 30 * DAY, now, true);
        assertFalse(cosmetic("trail_stars").enabled(), "the second waits: a real trail is on");
    }

    @Test
    void grantCosmeticWritesKindTrailAndTheRunsStart() throws Exception {
        long now = 1_000_000;
        long first = dao.grantCosmetic(player, "trail_hearts", 30 * DAY, now, true);
        LookDao.Look row = looks.look(player, "trail_hearts");
        assertNotNull(row);
        assertEquals(LookDao.TRAIL, row.kind());
        assertEquals(now, row.since(), "a new trail's run starts now");
        assertEquals(first, row.expiresAt());
        assertEquals("", row.givenBy());

        looks.notice(player, "trail_hearts", 7, first);
        long later = now + 5 * DAY;
        long second = dao.grantCosmetic(player, "trail_hearts", DAY, later, true);
        row = looks.look(player, "trail_hearts");
        assertEquals(first + DAY, second, "a live trail is extended from its end");
        assertEquals(now, row.since(), "and keeps its run's start");
        assertEquals(0, row.noticeStage(), "a new end forgets the heads-up sent for the old one");
        assertEquals(0, row.noticeFor());

        long restart = second + 10 * DAY;
        dao.grantCosmetic(player, "trail_hearts", DAY, restart, true);
        row = looks.look(player, "trail_hearts");
        assertEquals(restart, row.since(), "an ended trail starts a new run");
        assertEquals(restart + DAY, row.expiresAt());
    }

    @Test
    void cosmeticsReadsTheKind() throws Exception {
        long now = 1_000_000;
        dao.grantCosmetic(player, "trail_glow", 30 * DAY, now, true);
        looks.write(player, "cap_lime", LookDao.HAT, now + 90 * DAY, now, "");
        List<PrizeDao.Cosmetic> all = dao.cosmetics(player);
        assertEquals(List.of("cap_lime", "trail_glow"), all.stream().map(PrizeDao.Cosmetic::id).toList());
        assertEquals(LookDao.HAT, all.get(0).kind());
        assertEquals(LookDao.TRAIL, all.get(1).kind());
    }
}
