package com.dierks.homecraft.gui.mini;

import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.MiniDao;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A traded Mini keeps its value (SHOP-V2-SPEC §4.11, T9): a trade or a gift logs a price-0 sale, which neither the
 * type's last sale price ({@code MiniDao.lastSalePrice}, behind the Museum's value and {@code MiniValue}) nor the
 * info card's "Current value" reads as a price; a Vending sale after a trade is the value; and the card's trail says
 * "(traded)" or "(gift)" for such a row instead of "($0.00)".
 */
class MiniPriceHistoryTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static MiniDao.Sale sale(double price, String venue, long at) {
        return new MiniDao.Sale("u", "creeper", price, A, B, venue, at);
    }

    @Test
    void lastSalePriceIgnoresTradesAndGifts() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            MiniDao dao = new MiniDao(Database.open(conn, Logger.getAnonymousLogger()));
            assertNull(dao.lastSalePrice("creeper"));
            dao.recordSale("u1", "creeper", 120, A, B, "VENDING", 1);
            dao.recordSale("u1", "creeper", 0, B, A, MiniService.VENUE_TRADE, 2);
            dao.recordSale("u2", "creeper", 0, A, B, MiniService.VENUE_GIFT, 3);
            assertEquals(120.0, dao.lastSalePrice("creeper"), "the last sale with a price, not the $0 trade after it");
            dao.recordSale("u2", "creeper", 90, B, A, "AUCTION", 4);
            assertEquals(90.0, dao.lastSalePrice("creeper"));
            dao.recordSale("u3", "zombie", 0, A, B, MiniService.VENUE_TRADE, 5);
            assertNull(dao.lastSalePrice("zombie"), "only ever traded: no price yet");
        }
    }

    @Test
    void theInfoCardsValueIgnoresThemToo() {
        assertEquals(200.0, MiniInfoMenu.valueOf(List.of(sale(200, "VENDING", 1), sale(0, "TRADE", 2)), 150.0, 50),
                "this copy's last priced sale");
        assertEquals(150.0, MiniInfoMenu.valueOf(List.of(sale(0, "GIFT", 1)), 150.0, 50),
                "a copy only ever given: the type's last priced sale");
        assertEquals(50.0, MiniInfoMenu.valueOf(List.of(sale(0, "TRADE", 1)), null, 50), "else the mint price");
        assertEquals(75.0, MiniInfoMenu.valueOf(List.of(sale(0, "TRADE", 1), sale(75, "VENDING", 2)), null, 50),
                "a Vending sale after a trade is the value");
        assertEquals(50.0, MiniInfoMenu.valueOf(List.of(), null, 50));
    }

    @Test
    void theTrailSaysTradedOrGift() {
        assertEquals("(traded)", MiniInfoMenu.saleNote(sale(0, MiniService.VENUE_TRADE, 1), d -> "$" + d));
        assertEquals("(gift)", MiniInfoMenu.saleNote(sale(0, MiniService.VENUE_GIFT, 1), d -> "$" + d));
        assertEquals("($120.0)", MiniInfoMenu.saleNote(sale(120, "VENDING", 1), d -> "$" + d));
    }
}
