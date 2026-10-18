package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.PrizeService.Steps;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.PrizeDao;
import com.dierks.homecraft.storage.TokenDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A collector's claim → charge → hand-over (HALLOWEEN-SPEC D8; E7, E9, E23), run against a real
 * SQLite with the real claim and the real token ledger: nothing charged twice, nothing charged for
 * nothing, and no claim left behind for a present that never arrived.
 */
class CollectorHandOverTest {

    private static final String CAP = "cap_pumpkin_2026";
    private static final int COST = 250;
    private static final String ALREADY = "already";
    private static final String BROKE = "broke";
    private static final String NO_ROOM = "no room";

    private Connection conn;
    private PrizeDao prizes;
    private TokenDao tokens;
    private final UUID giver = UUID.randomUUID();
    private final UUID receiver = UUID.randomUUID();
    private final List<String> calls = new ArrayList<>();
    private int handedOver;

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        Database db = Database.open(conn, Logger.getAnonymousLogger());
        prizes = new PrizeDao(db);
        tokens = new TokenDao(db);
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    private int balance(UUID id) throws SQLException {
        return tokens.get(id).tokens();
    }

    /** One confirm of a present, as {@code PrizeService.gift} runs it; {@code room} is whether it fits. */
    private String confirm(boolean room) {
        return PrizeService.claimChargeHandOver(new Steps(
                        () -> {
                            calls.add("claim");
                            return prizes.claimOnce(receiver, CAP);
                        },
                        () -> {
                            calls.add("charge");
                            try {
                                return tokens.change(giver, -COST, "PRIZE", "Pumpkin Cap 2026 for a friend", 1)
                                        != TokenDao.REFUSED;
                            } catch (SQLException e) {
                                return false;
                            }
                        },
                        () -> {
                            calls.add("hand over");
                            if (room) {
                                handedOver++;
                            }
                            return room;
                        },
                        () -> {
                            calls.add("refund");
                            try {
                                tokens.change(giver, COST, "REFUND", "Pumpkin Cap 2026", 2);
                            } catch (SQLException e) {
                                throw new IllegalStateException(e);
                            }
                        },
                        () -> {
                            calls.add("unclaim");
                            try {
                                prizes.unclaim(receiver, CAP);
                            } catch (SQLException e) {
                                throw new IllegalStateException(e);
                            }
                        }),
                ALREADY, BROKE, NO_ROOM, null);
    }

    @Test
    void aDoubleClickChargesOnce() throws Exception {
        tokens.change(giver, 600, "ADMIN", "seed", 0);
        assertNull(confirm(true));
        assertEquals(List.of("claim", "charge", "hand over"), calls);
        calls.clear();
        assertEquals(ALREADY, confirm(true), "the second confirm finds the claim");
        assertEquals(List.of("claim"), calls, "and stops before any charge");
        assertEquals(600 - COST, balance(giver));
        assertEquals(1, handedOver);
        assertEquals(1, prizes.purchases(receiver, CAP, "life"));
    }

    @Test
    void aFailedChargeLeavesNoClaim() throws Exception {
        tokens.change(giver, COST - 1, "ADMIN", "seed", 0);
        assertEquals(BROKE, confirm(true));
        assertEquals(List.of("claim", "charge", "unclaim"), calls);
        assertEquals(COST - 1, balance(giver), "nothing charged");
        assertEquals(0, handedOver);
        assertEquals(0, prizes.purchases(receiver, CAP, "life"), "the receiver can still get one");

        tokens.change(giver, 1, "ADMIN", "seed", 0);
        calls.clear();
        assertNull(confirm(true), "with enough tokens the next try goes through");
        assertEquals(0, balance(giver));
    }

    @Test
    void aFailedHandOverRefundsAndUnclaims() throws Exception {
        tokens.change(giver, 300, "ADMIN", "seed", 0);
        assertEquals(NO_ROOM, confirm(false));
        assertEquals(List.of("claim", "charge", "hand over", "refund", "unclaim"), calls);
        assertEquals(300, balance(giver), "charged and paid back");
        assertEquals(0, prizes.purchases(receiver, CAP, "life"));
    }

    @Test
    void anUnsavedClaimChargesNothing() throws Exception {
        tokens.change(giver, 300, "ADMIN", "seed", 0);
        List<SQLException> logged = new ArrayList<>();
        String r = PrizeService.claimChargeHandOver(new Steps(
                        () -> {
                            throw new SQLException("disk full");
                        },
                        () -> {
                            calls.add("charge");
                            return true;
                        },
                        () -> {
                            calls.add("hand over");
                            return true;
                        },
                        () -> calls.add("refund"),
                        () -> calls.add("unclaim")),
                ALREADY, BROKE, NO_ROOM, logged::add);
        assertEquals("That couldn't be saved — nothing charged. Tell an admin.", r);
        assertTrue(calls.isEmpty(), "no token moved and nothing was handed over");
        assertEquals(1, logged.size());
        assertEquals(300, balance(giver));
    }
}
