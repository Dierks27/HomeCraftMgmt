package com.dierks.homecraft.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Daily Courses' {@code hcm_meta} keys (GEN-SPEC §5.5) against a real SQLite: the {@code gen.}
 * prefix is enforced on every method before the database is touched, so the schema version and the
 * games secret can never be overwritten from here; values round-trip; a write inside a transaction
 * rolls back with it.
 */
class GenMetaDaoTest {

    private Connection conn;
    private Database db;
    private GenMetaDao meta;

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        meta = new GenMetaDao(db);
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    private String raw(String key) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM hcm_meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void theGenPrefixIsEnforcedOnEveryMethod() throws Exception {
        String version = raw("schema_version");
        for (String bad : new String[]{"schema_version", "games_secret", "gen.", "gen", "general.x", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> meta.set(bad, "1"), "set refuses " + bad);
            assertThrows(IllegalArgumentException.class, () -> meta.get(bad), "get refuses " + bad);
            assertThrows(IllegalArgumentException.class, () -> meta.delete(bad), "delete refuses " + bad);
            if (!"gen.".equals(bad)) {
                assertThrows(IllegalArgumentException.class, () -> meta.like(bad), "like refuses " + bad);
                assertThrows(IllegalArgumentException.class, () -> meta.deleteLike(bad), "deleteLike refuses " + bad);
            }
        }
        assertEquals(Map.of(), meta.like("gen."), "listing every gen. key is allowed (there are none yet)");
        assertEquals(version, raw("schema_version"), "the schema version is untouched by every refused call");
        assertFalse(GenMetaDao.allowed("GEN.x"), "the prefix is case-sensitive, like the keys it guards");
        assertTrue(GenMetaDao.allowed("gen.tiny_golf.enabled"), "a slot's key is allowed");
    }

    @Test
    void valuesRoundTripAndAreListedByPrefix() throws Exception {
        meta.set("gen.tiny_golf.enabled", "false");
        meta.set("gen.tiny_golf.tier", "EEM");
        meta.set("gen.sky_rings.pin", "3f2a:1:0");
        assertEquals("false", meta.get("gen.tiny_golf.enabled"), "a value reads back");
        meta.set("gen.tiny_golf.enabled", "true");
        assertEquals("true", meta.get("gen.tiny_golf.enabled"), "a second write replaces the first");
        assertEquals(Map.of("gen.tiny_golf.enabled", "true", "gen.tiny_golf.tier", "EEM"),
                meta.like("gen.tiny_golf."), "like lists one slot's keys only");
        meta.set("gen.tiny_golf.tier", null);
        assertNull(meta.get("gen.tiny_golf.tier"), "a null value removes the key");
        assertEquals(1, meta.deleteLike("gen.tiny_golf."), "deleteLike removes what is left of the slot");
        assertEquals(Map.of("gen.sky_rings.pin", "3f2a:1:0"), meta.like("gen."), "and nothing of another slot");
    }

    @Test
    void aWriteInsideATransactionRollsBackWithIt() throws Exception {
        assertThrows(IllegalStateException.class, () -> db.transaction(c -> {
            meta.set("gen.daily_golf.mix", "abc:EEE");
            throw new IllegalStateException("the course write failed");
        }), "the failure reaches the caller");
        assertNull(meta.get("gen.daily_golf.mix"), "the meta write went back with the rest of the unit");
    }
}
