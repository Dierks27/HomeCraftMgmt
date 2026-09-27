package com.dierks.homecraft.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Minis feed's "printed" numbers against a real SQLite: {@code mintedCounts} reads every
 * {@code mini_counts} row's {@code minted} tally in one go — not circulation, so destroyed copies
 * still count — and an empty table gives an empty map.
 */
class MiniCountsTest {

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

    private UUID mint(String id) throws Exception {
        UUID uid = UUID.randomUUID();
        dao.mint(id, uid, player, "PRINTER", 1);
        return uid;
    }

    @Test
    void anEmptyTableGivesAnEmptyMap() throws Exception {
        assertTrue(dao.mintedCounts().isEmpty());
    }

    @Test
    void everyTallyRowReportsItsMintedCount() throws Exception {
        mint("blue_amethyst");
        UUID broken = mint("blue_amethyst");
        mint("blue_amethyst");
        dao.retire(broken.toString(), "blue_amethyst"); // destroyed, but it was still printed
        mint("rubber_duck");
        dao.addEscaped("golden_idol");                   // a row, but nothing minted

        assertEquals(Map.of("blue_amethyst", 3L, "rubber_duck", 1L, "golden_idol", 0L), dao.mintedCounts());
        assertEquals(dao.counts("blue_amethyst").minted(), dao.mintedCounts().get("blue_amethyst"),
                "the same tally the caps are checked against");
    }

    @Test
    void rowsWrittenDirectlyAreReadToo() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO mini_counts(mini_id, minted, destroyed) VALUES('old_lantern', 7, 2)");
        }
        Map<String, Long> counts = dao.mintedCounts();
        assertEquals(1, counts.size());
        assertEquals(7L, counts.get("old_lantern"), "minted, not minted - destroyed");
        assertNull(counts.get("never_minted"), "a type with no row is absent; the feed reads it as 0");
    }
}
