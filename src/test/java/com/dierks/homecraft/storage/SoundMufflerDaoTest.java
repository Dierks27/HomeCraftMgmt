package com.dierks.homecraft.storage;

import com.dierks.homecraft.muffler.MuffleLevel;
import com.dierks.homecraft.muffler.Muffler;
import com.dierks.homecraft.muffler.MufflerPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code sound_mufflers} table (schema v33) against a real in-memory SQLite. */
class SoundMufflerDaoTest {

    private Connection conn;
    private SoundMufflerDao dao;
    private final UUID owner = UUID.randomUUID();
    private final MufflerPos pos = new MufflerPos("world", 12, 70, -40);

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        dao = new SoundMufflerDao(Database.open(conn, Logger.getAnonymousLogger()));
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    @Test
    void aMufflerComesBackExactlyAsSaved() throws Exception {
        Muffler m = Muffler.placed(pos, owner, 8, 25)
                .withEnabled(false).withRadius(12).withQuietPercent(10)
                .withGroup("chickens", MuffleLevel.SILENT)
                .withSound("minecraft:entity.chicken.egg", MuffleLevel.ALLOW);
        dao.save(m, 1000L);
        assertEquals(List.of(m), dao.all());
    }

    @Test
    void savingAgainReplacesTheRow() throws Exception {
        Muffler m = Muffler.placed(pos, owner, 8, 25);
        dao.save(m, 1L);
        Muffler changed = m.withGroup("pistons", MuffleLevel.QUIETER);
        dao.save(changed, 2L);
        assertEquals(List.of(changed), dao.all());
    }

    @Test
    void deleteForgetsOnlyThatPosition() throws Exception {
        Muffler a = Muffler.placed(pos, owner, 8, 25);
        Muffler b = Muffler.placed(new MufflerPos("world", 0, 64, 0), owner, 4, 50);
        dao.save(a, 1L);
        dao.save(b, 1L);
        assertTrue(dao.delete(pos));
        assertFalse(dao.delete(pos), "already gone");
        assertEquals(List.of(b), dao.all());
    }

    @Test
    void aRowWithABrokenOwnerIsSkippedNotFatal() throws Exception {
        dao.save(Muffler.placed(pos, owner, 8, 25), 1L);
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO sound_mufflers(world, x, y, z, owner, enabled, radius, quiet_percent, rules, "
                    + "updated_at) VALUES ('world', 1, 2, 3, 'not-a-uuid', 1, 8, 25, '', 0)");
        }
        assertEquals(1, dao.all().size());
    }
}
