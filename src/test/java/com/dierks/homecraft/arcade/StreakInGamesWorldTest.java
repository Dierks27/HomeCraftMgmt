package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.TokenService.Earns;
import com.dierks.homecraft.arcade.TokenService.StreakDecision;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.TokenDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daughter's streak and playtime count in the Games world (SHOP-V2-SPEC §5.12, S6a; ECONOMY-V2 §5.1 A6): a
 * world the Games are played in pays the login streak and the playtime tokens like an economy world, still once
 * per local day, and the achievement sweep stays in economy worlds. A world that is neither pays nothing.
 */
class StreakInGamesWorldTest {

    private Connection conn;
    private TokenDao dao;
    private final UUID kid = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        dao = new TokenDao(Database.open(conn, Logger.getAnonymousLogger()));
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    @Test
    void anAllowedGamesWorldPaysTheStreakAndPlaytimeButRunsNoSweep() {
        Earns games = TokenService.earnsHere(false, true);
        assertEquals(Earns.STREAK_AND_PLAYTIME, games);
        assertTrue(games.pays(), "the streak and playtime are claimed in the Games world");
        assertFalse(games.sweeps(), "the achievement sweep stays economy-only");

        Earns economy = TokenService.earnsHere(true, false);
        assertTrue(economy.pays());
        assertTrue(economy.sweeps(), "an economy world is unchanged: everything, the sweep too");
        assertEquals(Earns.EVERYTHING, TokenService.earnsHere(true, true), "an economy world the Games use too");

        Earns elsewhere = TokenService.earnsHere(false, false);
        assertFalse(elsewhere.pays(), "the creative world (neither) pays nothing, as before");
        assertFalse(elsewhere.sweeps());
    }

    /** One day's claim, the way {@code TokenService.claimStreak} makes it: decide, then write. */
    private int claim(long today) throws Exception {
        TokenDao.TokenState s = dao.get(kid);
        StreakDecision d = TokenService.decideStreak(s.streak(), s.lastStreakDay(), today);
        if (d.rewind() >= 0) {
            dao.rewindStreakDay(kid, d.rewind());
        }
        if (!d.pay()) {
            return 0;
        }
        int after = dao.claimStreak(kid, d.streak(), today, 2, "LOGIN_STREAK", "Day " + d.streak(), 1L);
        return after == TokenDao.REFUSED ? 0 : 2;
    }

    @Test
    void stillOneClaimPerLocalDayWhereverItIsMade() throws Exception {
        long monday = LocalDate.of(2026, 11, 23).toEpochDay();
        assertTrue(TokenService.earnsHere(false, true).pays());
        assertEquals(2, claim(monday), "joining straight into the Games world claims Monday");
        assertEquals(0, claim(monday), "the five-minute tick in the Games world pays nothing more");
        assertEquals(0, claim(monday), "nor does walking home to an economy world the same day");
        assertEquals(1, dao.get(kid).streak());
        assertEquals(2, claim(monday + 1), "Tuesday in the Games world carries the streak on");
        assertEquals(2, dao.get(kid).streak());
        assertEquals(4, dao.get(kid).tokens());
        // the write itself refuses a second claim for one day, whatever decided it
        assertEquals(TokenDao.REFUSED, dao.claimStreak(kid, 3, monday + 1, 2, "LOGIN_STREAK", "again", 2L));
    }
}
