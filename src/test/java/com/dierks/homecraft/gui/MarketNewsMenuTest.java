package com.dierks.homecraft.gui;

import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.OrderMath;
import com.dierks.homecraft.market.PriceMood;
import com.dierks.homecraft.market.PricingEngine;
import com.dierks.homecraft.market.sim.Badge;
import com.dierks.homecraft.market.sim.EventKind;
import com.dierks.homecraft.market.sim.MarketEvent;
import com.dierks.homecraft.market.sim.Source;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure pieces of the live market's shop and news surfaces in {@link MarketNewsMenu}.
 *
 * <p>Pinned:
 * <ul>
 *   <li>{@link MarketNewsMenu#shownCap}: a tile or order preview shows an event cap only while the
 *       mood says the event behind it is showing its badge ({@code PriceMood.eventCapShown}), so a
 *       HOT/DEAL in its silent ramp, or an UP/DOWN in its badge-less tail, is never given away by
 *       a "Limit N a day" — and nothing at all with no mood or the neutral one.</li>
 *   <li>{@link MarketNewsMenu#capLine}: a shown cap is said exactly once — by the badge's own lore
 *       (DEAL/DOWN on the Store, HOT/UP on the Sell screen) or else by a plain line that does not
 *       name the event (a tile shows one badge, so a DEAL's limit under an ▲ would go unsaid).</li>
 *   <li>{@link MarketNewsMenu#entryQuote}: a news entry quotes what the chat announcement quotes —
 *       the bid for UP/HOT, the ask for DOWN/DEAL, the stored mid for a real-world move — and no
 *       prices for a WANTED or a season, whose rows carry none that moved.</li>
 * </ul>
 * Registry-free: {@link Material} enum identity only, as {@code DepartmentIconsTest} explains.
 */
class MarketNewsMenuTest {

    private static final long CAP = 37L;
    private static final double SPREAD = 0.10;
    private static final PricingEngine ENGINE = new PricingEngine(1.0, 0.0, SPREAD);
    private static final MarketItem WHEAT = new MarketItem("wheat", Material.WHEAT, "Wheat", 0.5, 12.0, 1000, 5000, 0, 0);

    // ---- event caps on tiles and previews ----

    @Test
    void aCapIsShownOnlyWhileItsEventShowsItsBadge() {
        for (boolean buyShown : new boolean[] {false, true}) {
            for (boolean sellShown : new boolean[] {false, true}) {
                PriceMood mood = mood(40L, 41L, buyShown, sellShown);
                assertEquals(buyShown ? 40L : 0L, MarketNewsMenu.shownCap(mood, WHEAT, false));
                assertEquals(sellShown ? 41L : 0L, MarketNewsMenu.shownCap(mood, WHEAT, true));
            }
        }
        // A shown event with no cap (hot.sell_limit: false) shows none; nor does the neutral mood,
        // no mood, or no item.
        assertEquals(0L, MarketNewsMenu.shownCap(mood(0L, 0L, true, true), WHEAT, true));
        assertEquals(0L, MarketNewsMenu.shownCap(mood(-5L, -5L, true, true), WHEAT, false));
        assertEquals(0L, MarketNewsMenu.shownCap(PriceMood.NEUTRAL, WHEAT, false));
        assertEquals(0L, MarketNewsMenu.shownCap(PriceMood.NEUTRAL, WHEAT, true));
        assertEquals(0L, MarketNewsMenu.shownCap((PriceMood) null, WHEAT, true));
        assertEquals(0L, MarketNewsMenu.shownCap(mood(40L, 41L, true, true), null, false));
    }

    @Test
    void noCapNoLine() {
        for (Badge b : Badge.values()) {
            assertEquals(List.of(), MarketNewsMenu.capLine(b, false, 0L));
            assertEquals(List.of(), MarketNewsMenu.capLine(b, true, 0L));
            assertEquals(List.of(), MarketNewsMenu.capLine(b, true, -1L));
        }
    }

    @Test
    void thePlainLineAppearsOnlyWhereTheBadgeDoesNotSayIt() {
        for (Badge b : Badge.values()) {
            boolean storeSays = b == Badge.DEAL || b == Badge.DOWN;
            boolean sellSays = b == Badge.HOT || b == Badge.UP;
            assertEquals(storeSays ? List.of() : List.of("&7Limit &f37 &7a day."),
                    MarketNewsMenu.capLine(b, false, CAP), "store " + b);
            assertEquals(sellSays ? List.of() : List.of("&7Up to &f37 &7a day."),
                    MarketNewsMenu.capLine(b, true, CAP), "sell " + b);
        }
        // No status reads as no badge.
        assertEquals(List.of("&7Limit &f37 &7a day."), MarketNewsMenu.capLine(null, false, CAP));
    }

    @Test
    void theStoreTileAndOrderPreviewSayTheCapExactlyOnceWhateverTheBadge() {
        for (Badge b : Badge.values()) {
            for (boolean fading : new boolean[] {false, true}) {
                for (double pct : new double[] {0.0, -12.0, 11.0}) {
                    List<String> tile = new ArrayList<>(MarketLabels.storeLore(b, fading, pct, "$12.50", "about a day", CAP));
                    tile.addAll(MarketNewsMenu.capLine(b, false, CAP));
                    assertEquals(1, mentions(tile), "store tile " + b + " " + fading + " " + pct + ": " + tile);
                }
            }
            List<String> preview = new ArrayList<>(MarketLabels.previewLines(b, false, CAP));
            preview.addAll(MarketNewsMenu.capLine(b, false, CAP));
            assertEquals(1, mentions(preview), "order preview " + b + ": " + preview);
        }
    }

    @Test
    void theSellTileSaysTheCapExactlyOnceWhateverTheBadge() {
        for (Badge b : Badge.values()) {
            for (boolean fading : new boolean[] {false, true}) {
                List<String> tile = new ArrayList<>(MarketLabels.sellLore(b, fading, 11.0, "$12.50", "about a day", CAP));
                tile.addAll(MarketNewsMenu.capLine(b, true, CAP));
                assertEquals(1, mentions(tile), "sell tile " + b + " " + fading + ": " + tile);
            }
        }
    }

    @Test
    void thePlainLineNeverNamesTheEvent() {
        for (boolean sell : new boolean[] {false, true}) {
            for (String line : MarketNewsMenu.capLine(Badge.NONE, sell, CAP)) {
                String plain = MarketLabels.plain(line).toLowerCase(Locale.ROOT);
                for (String word : List.of("sale", "deal", "hot", "news", "price", "up!", "down", "★", "✦", "▲", "▼")) {
                    assertFalse(plain.contains(word), "'" + plain + "' gives the event away: " + word);
                }
            }
        }
    }

    // ---- news entry prices (findings: WANTED "$X → $X (0%)"; menu quoted mid, chat bid/ask) ----

    @Test
    void sellEntriesQuoteWhatCratePaysAndOrderEntriesWhatItCharges() {
        // The finding's wheat UP: mid $3.46 -> $4.22, chat "Crate pays $3.29 → $4.01 (+22%)".
        assertEquals(String.format(Locale.ROOT, "%.2f", 3.46 * (1 - SPREAD / 2)),
                String.format(Locale.ROOT, "%.2f", MarketNewsMenu.entryQuote(EventKind.UP, ENGINE, WHEAT, 3.46)));
        assertEquals("3.29", String.format(Locale.ROOT, "%.2f", MarketNewsMenu.entryQuote(EventKind.UP, ENGINE, WHEAT, 3.46)));
        assertEquals("4.01", String.format(Locale.ROOT, "%.2f", MarketNewsMenu.entryQuote(EventKind.UP, ENGINE, WHEAT, 4.22)));
        for (double mid : new double[] {0.5, 0.51, 3.46, 4.22, 11.9, 12.0}) {
            assertEquals(OrderMath.bid(ENGINE, WHEAT, mid), MarketNewsMenu.entryQuote(EventKind.UP, ENGINE, WHEAT, mid));
            assertEquals(OrderMath.bid(ENGINE, WHEAT, mid), MarketNewsMenu.entryQuote(EventKind.HOT, ENGINE, WHEAT, mid));
            assertEquals(OrderMath.ask(ENGINE, WHEAT, mid), MarketNewsMenu.entryQuote(EventKind.DOWN, ENGINE, WHEAT, mid));
            assertEquals(OrderMath.ask(ENGINE, WHEAT, mid), MarketNewsMenu.entryQuote(EventKind.DEAL, ENGINE, WHEAT, mid));
            assertEquals(mid, MarketNewsMenu.entryQuote(EventKind.REAL, ENGINE, WHEAT, mid));
        }
        // An item Crate no longer trades: the stored mid, as the chat line (MarketSimService.bid/ask).
        assertEquals(3.46, MarketNewsMenu.entryQuote(EventKind.UP, ENGINE, null, 3.46));
    }

    @Test
    void wantedAndSeasonEntriesHaveNoPriceLine() {
        long t0 = 1_800_000_000_000L;
        MarketEvent wanted = MarketEvent.info(EventKind.WANTED, Source.SIM, "diamond", null, t0, t0 + 7 * 86_400_000L)
                .withPrices(0.0, 180.0, 180.0);
        assertTrue(Double.isNaN(MarketNewsMenu.entryQuote(EventKind.WANTED, ENGINE, WHEAT, wanted.priceBefore())));
        assertTrue(Double.isNaN(MarketNewsMenu.entryQuote(EventKind.WANTED, ENGINE, WHEAT, wanted.priceAfter())));
        assertTrue(Double.isNaN(MarketNewsMenu.entryQuote(EventKind.SEASON, ENGINE, null, 3.0)));
        // A blank quote leaves the "{before} → {after}" line out of the entry.
        List<String> lore = MarketLabels.newsEntryLore(wanted, "3h ago", "", "", true);
        assertEquals(List.of("&73h ago", "&aStill going!"), lore);
        // Nothing to quote: no price, 0 or below, NaN.
        for (EventKind k : EventKind.values()) {
            assertTrue(Double.isNaN(MarketNewsMenu.entryQuote(k, ENGINE, WHEAT, 0.0)), k.name());
            assertTrue(Double.isNaN(MarketNewsMenu.entryQuote(k, ENGINE, WHEAT, -1.0)), k.name());
            assertTrue(Double.isNaN(MarketNewsMenu.entryQuote(k, ENGINE, WHEAT, Double.NaN)), k.name());
        }
        assertTrue(Double.isNaN(MarketNewsMenu.entryQuote(null, ENGINE, WHEAT, 3.0)));
    }

    /** A mood with fixed event caps that says whether each side's event is showing. */
    private static PriceMood mood(long buyCap, long sellCap, boolean buyShown, boolean sellShown) {
        return new PriceMood() {
            @Override
            public double multiplier(String id) {
                return 1.0;
            }

            @Override
            public long jumpSeq(String id) {
                return 0L;
            }

            @Override
            public long eventBuyCap(MarketItem item) {
                return buyCap;
            }

            @Override
            public long eventSellCap(MarketItem item) {
                return sellCap;
            }

            @Override
            public boolean eventCapShown(MarketItem item, boolean sell) {
                return sell ? sellShown : buyShown;
            }

            @Override
            public void onTrade(String id, boolean sell, int units, double total, double neutralTotal) {
            }

            @Override
            public void catalogChanged() {
            }
        };
    }

    /** How many lines say the cap's number. */
    private static int mentions(List<String> lines) {
        int n = 0;
        for (String line : lines) {
            if (MarketLabels.plain(line).contains(Long.toString(CAP))) {
                n++;
            }
        }
        return n;
    }
}
