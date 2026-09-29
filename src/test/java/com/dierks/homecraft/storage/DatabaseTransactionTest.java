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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Database#transaction}: every statement lands or none does, whatever is thrown. The bug this
 * pins (found by WP-C): an {@link Error} (an OutOfMemoryError, a StackOverflowError, a LinkageError)
 * used to skip the rollback, and the {@code finally}'s {@code setAutoCommit(true)} then made
 * sqlite-jdbc COMMIT the half-done work: a Cup settlement row with half its prizes, a debit with no
 * entry. Now any Throwable rolls back and is rethrown as it was.
 */
class DatabaseTransactionTest {

    private Connection conn;
    private Database db;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)");
        }
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    private int rows() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static void insert(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO t (id, v) VALUES (?, 'x')")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    @Test
    void anErrorInsideATransactionRollsBackEverythingAndIsRethrown() throws Exception {
        StackOverflowError boom = new StackOverflowError("deep");
        StackOverflowError thrown = assertThrows(StackOverflowError.class, () -> db.transaction(c -> {
            insert(c, 1);
            insert(c, 2);
            throw boom;
        }), "the Error comes out as it went in");
        assertSame(boom, thrown, "the very same one, not wrapped");
        assertEquals(0, rows(), "nothing the transaction wrote was kept (it used to be committed)");
        assertTrue(conn.getAutoCommit(), "and the connection is back to auto-commit for the next caller");

        db.transaction(c -> {
            insert(c, 3);
            return null;
        });
        assertEquals(1, rows(), "the next transaction works and commits as ever");
    }

    @Test
    void aLinkageErrorOrAnyOtherThrowableRollsBackToo() throws Exception {
        assertThrows(NoClassDefFoundError.class, () -> db.transaction(c -> {
            insert(c, 1);
            throw new NoClassDefFoundError("a class that went away");
        }));
        assertEquals(0, rows(), "a LinkageError rolls back");
        assertThrows(IllegalStateException.class, () -> db.transaction(c -> {
            insert(c, 1);
            throw new IllegalStateException("a bug");
        }));
        assertEquals(0, rows(), "a RuntimeException still rolls back");
        assertThrows(SQLException.class, () -> db.transaction(c -> {
            insert(c, 1);
            insert(c, 1); // the same key twice
            return null;
        }));
        assertEquals(0, rows(), "an SQLException still rolls back");
    }

    @Test
    void anErrorInAJoinedTransactionRollsBackTheOuterOneWhole() throws Exception {
        assertThrows(OutOfMemoryError.class, () -> db.transaction(c -> {
            insert(c, 1);
            return db.transaction(inner -> {
                insert(inner, 2);
                throw new OutOfMemoryError("simulated");
            });
        }));
        assertEquals(0, rows(), "the inner work joins the outer transaction, so both go");
    }
}
