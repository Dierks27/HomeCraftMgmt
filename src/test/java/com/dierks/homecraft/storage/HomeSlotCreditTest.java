package com.dierks.homecraft.storage;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Schema v31: whoever bought Second/Third Home keeps what they paid for, as +1 Home slots. */
class HomeSlotCreditTest {

    @Test
    void oldHomePurchasesBecomeSlots() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Database.open(conn, Logger.getAnonymousLogger());
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM prize_purchases");
                st.executeUpdate("INSERT INTO prize_purchases VALUES "
                        + "('a','home_2','life',1),('a','home_3','life',1),"   // both: 2 slots
                        + "('b','home_2','life',1),"                            // one: 1 slot
                        + "('c','speed_boost','d1',3),"                         // unrelated
                        + "('d','home_slot','life',1),('d','home_2','life',1)"); // already has slots: left alone
                st.executeUpdate(Database.CREDIT_HOME_SLOTS);
                Map<String, Integer> slots = new LinkedHashMap<>();
                try (ResultSet rs = st.executeQuery(
                        "SELECT player, count FROM prize_purchases WHERE prize_id='home_slot' ORDER BY player")) {
                    while (rs.next()) {
                        slots.put(rs.getString(1), rs.getInt(2));
                    }
                }
                assertEquals(Map.of("a", 2, "b", 1, "d", 1), slots);
            }
        }
    }
}
