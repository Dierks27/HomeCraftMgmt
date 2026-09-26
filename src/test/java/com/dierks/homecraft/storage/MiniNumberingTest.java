package com.dierks.homecraft.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mint numbers after the wild-hunt repair: escaped copies give their numbers back, the next
 * mints re-use them lowest first, and no number is ever issued twice or past the cap.
 */
class MiniNumberingTest {

    private static final String MINI = "golden_idol";

    private Connection conn;
    private MiniDao dao;
    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        dao = new MiniDao(Database.open(conn, Logger.getAnonymousLogger()));
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    /** Mint as the old wild hunt did: no owner, then retire it — an escape under the old rule. */
    private String oldEscape(long expectNumber) throws SQLException {
        UUID uid = UUID.randomUUID();
        assertEquals(expectNumber, dao.mint(MINI, uid, null, null, 1));
        assertTrue(dao.retire(uid.toString(), MINI));
        return uid.toString();
    }

    private long mint() throws SQLException {
        return dao.mint(MINI, UUID.randomUUID(), player, "PRINTER", 2);
    }

    @Test
    void numbersRunInOrder() throws Exception {
        assertEquals(1, mint());
        assertEquals(2, mint());
        assertEquals(3, mint());
        assertEquals(3, dao.counts(MINI).minted());
    }

    @Test
    void escapedCopiesAreFoundAndOnlyThose() throws Exception {
        mint();                                  // #1 printed — owned
        UUID caught = UUID.randomUUID();
        dao.mint(MINI, caught, null, null, 1);   // #2 spawned, then caught (owner set on pickup)
        dao.updateOwner(caught.toString(), player);
        dao.retire(caught.toString(), MINI);     // …and later destroyed — not an escape
        oldEscape(3);                            // #3 escaped
        UUID sold = UUID.randomUUID();
        dao.mint(MINI, sold, null, null, 1);     // #4 no owner but it changed hands — excluded
        dao.recordSale(sold.toString(), MINI, 10, null, player, "VENDING", 3);
        dao.retire(sold.toString(), MINI);
        UUID printedGone = UUID.randomUUID();
        dao.mint(MINI, printedGone, null, "PRINTER", 1); // #5 claims another origin — excluded
        dao.retire(printedGone.toString(), MINI);
        oldEscape(6);                            // #6 escaped

        List<Long> found = new ArrayList<>();
        for (MiniDao.EscapedCopy c : dao.escapedCopies()) {
            found.add(c.mintNumber());
        }
        assertEquals(List.of(3L, 6L), found);
    }

    @Test
    void aRepairFreesTheNumbersAndTheNextMintsReuseThemLowestFirst() throws Exception {
        mint();          // 1
        oldEscape(2);
        mint();          // 3
        oldEscape(4);
        mint();          // 5
        assertEquals(5, dao.counts(MINI).minted());
        assertEquals(2, dao.counts(MINI).destroyed());

        assertEquals(2, dao.repairEscaped(dao.escapedCopies(), 9));

        MiniDao.Counts after = dao.counts(MINI);
        assertEquals(3, after.minted(), "escaped copies leave Minted");
        assertEquals(0, after.destroyed(), "…and destroyed, where retiring had put them");
        assertEquals(2, after.escaped(), "…and are counted as got away");
        assertEquals(List.of(2L, 4L), dao.freeNumbers(MINI));
        assertTrue(dao.escapedCopies().isEmpty(), "a second run finds nothing");

        assertEquals(2, mint(), "lowest freed number first");
        assertEquals(4, mint());
        assertEquals(6, mint(), "then one past the highest ever issued");
        assertEquals(6, dao.counts(MINI).minted());
    }

    /**
     * "#N of cap" can never pass the cap, however repairs and new mints interleave — minting
     * stops at minted == cap and the free pool only ever holds numbers already below the top.
     */
    @Test
    void noNumberIsIssuedTwiceOrPastTheCap() throws Exception {
        int cap = 12;
        for (int i = 1; i <= 8; i++) {
            if (i % 3 == 0) {
                oldEscape(i);
            } else {
                assertEquals(i, mint());
            }
        }
        dao.repairEscaped(dao.escapedCopies(), 9);
        while (dao.counts(MINI).minted() < cap) {
            mint();
        }
        Set<Long> seen = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT mint_number FROM mini_individuals WHERE mini_id = '" + MINI + "'")) {
            while (rs.next()) {
                long n = rs.getLong(1);
                assertTrue(seen.add(n), "#" + n + " issued twice");
                assertTrue(n >= 1 && n <= cap, "#" + n + " is past the cap of " + cap);
            }
        }
        assertEquals(cap, seen.size());
        assertTrue(dao.freeNumbers(MINI).isEmpty());
    }

    @Test
    void theUniqueIndexGoesOnAHealthyTableAndHoldsFromThenOn() throws Exception {
        mint();
        assertTrue(dao.ensureUniqueNumbers().isEmpty());
        assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO mini_individuals(uid, mini_id, mint_number, owner, minted_at) VALUES(?,?,1,NULL,0)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, MINI);
                ps.executeUpdate();
            }
        }, "a second #1 must be refused by the index");
    }

    @Test
    void existingDuplicatesAreReportedAndTheIndexIsSkipped() throws Exception {
        mint();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO mini_individuals(uid, mini_id, mint_number, owner, minted_at) VALUES(?,?,1,NULL,0)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, MINI);
            ps.executeUpdate();
        }
        List<MiniDao.Duplicate> dups = dao.ensureUniqueNumbers();
        assertEquals(1, dups.size());
        assertEquals(1, dups.get(0).mintNumber());
        assertEquals(2, dups.get(0).copies());
    }

    @Test
    void anEscapeUnderTheNewRuleMovesOnlyTheEscapedCount() throws Exception {
        mint();
        dao.addEscaped(MINI);
        dao.addEscaped("never_minted");
        assertEquals(new MiniDao.Counts(1, 0, 1), dao.counts(MINI));
        assertEquals(new MiniDao.Counts(0, 0, 1), dao.counts("never_minted"));
    }
}
