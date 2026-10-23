package com.dierks.homecraft.arcade.looks;

import com.dierks.homecraft.arcade.looks.LookService.Committed;
import com.dierks.homecraft.arcade.looks.LookService.Status;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.LookDao;
import com.dierks.homecraft.storage.LookDao.Look;
import com.dierks.homecraft.storage.TokenDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static com.dierks.homecraft.arcade.looks.LookFixtures.DAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A rented look's buy as ONE transaction (SHOP-V2-SPEC §3.5, D30) on a real SQLite, with the real token store on the
 * same connection: a double confirm is charged once, a refused charge rolls the right back, a failed write charges
 * nothing, a moved price charges nothing, a present to someone who has it is refused before any charge, a present
 * writes the receiver's row and the giver's ledger line, and a renewal keeps the run's start.
 */
class LookPurchaseTxTest {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final long NOW = ZonedDateTime.of(2026, 10, 20, 15, 0, 0, 0, ZONE).toInstant().toEpochMilli();

    private Connection conn;
    private Database db;
    private LookDao looks;
    private TokenDao tokens;
    private final UUID buyer = UUID.randomUUID();
    private final UUID kid = UUID.randomUUID();
    private final Prize bunny = LookFixtures.bunny();

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        looks = new LookDao(db);
        tokens = new TokenDao(db);
        tokens.change(buyer, 500, "ADMIN", "start", 1);
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    /** The real token store, joining the purchase's transaction. */
    private LookService.Spend spend() {
        return (payer, cost, detail) -> tokens.change(payer, -cost, "PRIZE", detail, NOW) != TokenDao.REFUSED;
    }

    private Committed buy(UUID receiver, int shown, long now) throws SQLException {
        return LookService.commit(db, looks, spend(), buyer, receiver, bunny, shown, now, ZONE, "Kid");
    }

    private int ledgerLines(UUID player) throws SQLException {
        return tokens.history(player, 100).size();
    }

    @Test
    void aDoubleConfirmIsChargedOnce() throws Exception {
        Committed first = buy(buyer, 120, NOW);
        assertEquals(Status.DONE, first.status());
        assertEquals(LocalDate.of(2027, 1, 18), first.lastDay());
        assertEquals(380, tokens.get(buyer).tokens());
        int lines = ledgerLines(buyer);
        Committed second = buy(buyer, 120, NOW + 1_000);
        assertEquals(Status.ALREADY, second.status(), "the second confirm finds the right already written");
        assertEquals(380, tokens.get(buyer).tokens(), "charged once");
        assertEquals(lines, ledgerLines(buyer), "no second ledger line");
    }

    @Test
    void aRefusedChargeRollsTheRightBack() throws Exception {
        UUID poor = UUID.randomUUID();
        Committed r = LookService.commit(db, looks, spend(), poor, poor, bunny, 120, NOW, ZONE, null);
        assertEquals(Status.NOT_PAID, r.status());
        assertNull(looks.look(poor, "hat_bunny"), "no right without the tokens");
        assertEquals(0, ledgerLines(poor));
    }

    @Test
    void aFailedWriteChargesNothing() throws Exception {
        LookService.Spend breaks = (payer, cost, detail) -> {
            tokens.change(payer, -cost, "PRIZE", detail, NOW);
            throw new SQLException("disk full");
        };
        assertThrows(SQLException.class,
                () -> LookService.commit(db, looks, breaks, buyer, buyer, bunny, 120, NOW, ZONE, null));
        assertEquals(500, tokens.get(buyer).tokens(), "the charge rolled back with the right");
        assertNull(looks.look(buyer, "hat_bunny"));
        assertEquals(1, ledgerLines(buyer), "only the starting line");
    }

    @Test
    void aMovedPriceChargesNothing() throws Exception {
        Committed r = buy(buyer, 100, NOW);
        assertEquals(Status.PRICE_CHANGED, r.status(), "the tile said 100, it costs 120");
        assertEquals(500, tokens.get(buyer).tokens());
        assertNull(looks.look(buyer, "hat_bunny"));
    }

    @Test
    void aPresentToSomeoneWhoHasItIsRefusedBeforeAnyCharge() throws Exception {
        looks.write(kid, "hat_bunny", LookDao.HAT, NOW + 60 * DAY, NOW - 30 * DAY, "");
        Committed r = buy(kid, 120, NOW);
        assertEquals(Status.ALREADY, r.status(), "her window is LIVE");
        assertEquals(500, tokens.get(buyer).tokens());
    }

    @Test
    void aPresentWritesHerRowAndTheGiversLine() throws Exception {
        Committed r = buy(kid, 120, NOW);
        assertEquals(Status.DONE, r.status());
        Look row = looks.look(kid, "hat_bunny");
        assertEquals(buyer.toString(), row.givenBy(), "given_by: whoever paid, for the giver's line");
        assertEquals(LookDao.HAT, row.kind());
        assertNull(looks.look(buyer, "hat_bunny"), "the giver gets nothing but the line");
        List<TokenDao.LedgerRow> mine = tokens.history(buyer, 1);
        assertEquals("Bunny Hat for Kid", mine.get(0).detail());
        assertEquals(-120, mine.get(0).delta());
    }

    @Test
    void aRenewalKeepsItsStartAndSaysAgain() throws Exception {
        long since = NOW - 85 * DAY;
        long end = LookRules.endOf(LocalDate.of(2026, 10, 25), ZONE);
        looks.write(buyer, "hat_bunny", LookDao.HAT, end, since, "");
        Committed r = buy(buyer, 120, NOW);
        assertEquals(Status.DONE, r.status());
        assertEquals(LocalDate.of(2027, 1, 23), r.lastDay(), "25 Oct + 90");
        assertEquals(since, looks.look(buyer, "hat_bunny").since(), "the run's start is kept");
        assertEquals("Bunny Hat (again)", tokens.history(buyer, 1).get(0).detail());
    }

    @Test
    void anOpenWindowCostsTheBuyAgainPrice() throws Exception {
        Prize cap = LookFixtures.cap("cap_lime", "&aLime Cap", 60, 40);
        looks.write(buyer, "cap_lime", LookDao.HAT, NOW + 2 * DAY, NOW - 88 * DAY, "");
        Committed wrong = LookService.commit(db, looks, spend(), buyer, buyer, cap, 60, NOW, ZONE, null);
        assertEquals(Status.PRICE_CHANGED, wrong.status(), "the full price is not what the window costs");
        Committed r = LookService.commit(db, looks, spend(), buyer, buyer, cap, 40, NOW, ZONE, null);
        assertEquals(Status.DONE, r.status());
        assertEquals(460, tokens.get(buyer).tokens());
    }
}
