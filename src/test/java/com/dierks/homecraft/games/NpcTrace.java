package com.dierks.homecraft.games;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The NPC players' "no trace" scan ({@code CpuPlayersTest}), shared with the world games' tests (stage 2): every
 * value in every table of the database that names a UUID. An NPC must never be named anywhere.
 */
public final class NpcTrace {

    private NpcTrace() {
    }

    /** Every {@code table.column} holding a value that contains {@code id}, once per row found. */
    public static List<String> rowsNaming(Connection connection, UUID id) throws SQLException {
        List<String> out = new ArrayList<>();
        List<String> tables = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        for (String t : tables) {
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM \"" + t
                    + "\"")) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    for (int c = 1; c <= md.getColumnCount(); c++) {
                        Object v = rs.getObject(c);
                        if (v != null && v.toString().contains(id.toString())) {
                            out.add(t + "." + md.getColumnName(c));
                        }
                    }
                }
            }
        }
        return out;
    }
}
