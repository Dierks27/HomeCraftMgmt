package com.dierks.homecraft;

import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.TokenDao;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Hats bought before 0.38" (SHOP-V2-SPEC §3.15, D34; red team O18): the start that brings schema v37 says, from a
 * read-only query of the token ledger, whether any of the six animal hats was ever sold, so nobody runs SQL on the
 * live database to find out. Such a hat stays a plain prize, kept for good.
 */
class LegacyHatsLineTest {

    @Test
    void noHatsSaysNone() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Database db = Database.open(conn, Logger.getAnonymousLogger());
            TokenDao tokens = new TokenDao(db);
            UUID someone = UUID.randomUUID();
            tokens.change(someone, 500, "ADMIN", "seed", 0);
            tokens.change(someone, -8, "PRIZE", "Speed Boost", 1);
            assertEquals(List.of(), ShopMigration.legacyHats(conn), "a boost is not a hat");
            assertEquals("Hats bought before 0.38: none", ShopMigration.legacyHatsLine(List.of(), id -> null));
        }
    }

    @Test
    void twoOldHatsAreListedWithTheirBuyer() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Database db = Database.open(conn, Logger.getAnonymousLogger());
            TokenDao tokens = new TokenDao(db);
            UUID buyer = UUID.fromString("00000000-0000-0000-0000-000000000001");
            tokens.change(buyer, 1000, "ADMIN", "seed", 0);
            tokens.change(buyer, -120, "PRIZE", "Bunny Hat", 1);
            tokens.change(buyer, -120, "PRIZE", "Fox Hat", 2);
            tokens.change(buyer, 120, "REFUND", "Fox Hat", 3); // a refund line is not a sale
            List<ShopMigration.LegacyHat> hats = ShopMigration.legacyHats(conn);
            assertEquals(List.of(new ShopMigration.LegacyHat(buyer.toString(), "Bunny Hat", 1),
                    new ShopMigration.LegacyHat(buyer.toString(), "Fox Hat", 1)), hats);
            assertEquals("Hats bought before 0.38 (kept for good): a player Bunny Hat x1, a player Fox Hat x1",
                    ShopMigration.legacyHatsLine(hats, id -> buyer.equals(id) ? "a player" : null));
            assertEquals("Hats bought before 0.38 (kept for good): " + buyer + " Bunny Hat x1, " + buyer + " Fox Hat x1",
                    ShopMigration.legacyHatsLine(hats, id -> null), "a name nobody knows shows the UUID");
        }
    }

    @Test
    void theLineComesOnTheStartThatBringsV37() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Database fresh = Database.open(conn, Logger.getAnonymousLogger());
            assertEquals(0, fresh.openedAt(), "a new file opens at 0, below 37: the line shows (\"none\")");
            Database again = Database.open(conn, Logger.getAnonymousLogger());
            assertTrue(again.openedAt() >= 37, "the next start opens at " + again.openedAt() + ": no line");
        }
    }
}
