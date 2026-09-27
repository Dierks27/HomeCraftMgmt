package com.dierks.homecraft.gui;

import com.dierks.homecraft.display.Trend;
import com.dierks.homecraft.market.sim.Badge;
import com.dierks.homecraft.market.sim.EventKind;
import com.dierks.homecraft.market.sim.Headlines;
import com.dierks.homecraft.market.sim.MarketEvent;
import com.dierks.homecraft.market.sim.SimMath;
import com.dierks.homecraft.market.sim.Source;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live market's exact player-facing strings ({@link MarketLabels}).
 *
 * <p>Pinned here: every label and chat line stays below U+FFFF and uses only the market's
 * glyphs (★ ✦ » « ▲ ▼ · • → …), so Bedrock players through Geyser see no empty boxes; a sign's
 * badge prefix plus the worst trend label still fits the sign's 15 characters, and a line that
 * would not falls back to the trend alone; the spec §6.2 announcements, the §6.3 catch-up, the
 * §7.1 tile / sign / hologram / TV / preview table and the §7.4 PlaceholderAPI forms, string for
 * string; the stored lines strip to exactly what {@code /api/news} shows; a template's
 * {@code {pct}} is a whole number that never contradicts its badge, whichever sign the row was
 * stored with; a REAL never claims Crate's price moved when it did not (a sold-out item pinned
 * at its ceiling); a kind a renderer does not handle gives nothing rather than a wrong message; and
 * the cut / wrap limits (34, 40, 32 × 3, 60).
 */
class MarketLabelsTest {

    private static final long H = SimMath.HOUR_MS;
    private static final long T0 = 1_790_000_000_000L;

    /** Every non-ASCII character the market's strings may use. */
    private static final String ALLOWED = "★✦»«▲▼·•→…";

    private static MarketEvent up() {
        return MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.22, T0, 6 * H, 30 * H)
                .withPrices(22.0, 3.46, 4.22);
    }

    private static MarketEvent down() {
        return MarketEvent.shock(EventKind.DOWN, Source.SIM, "iron_ingot", 0.18, T0, 6 * H, 30 * H)
                .withPrices(-18.0, 22.49, 18.44);
    }

    private static MarketEvent hot() {
        return MarketEvent.story(EventKind.HOT, Source.SIM, "oak_log", 0.12, T0, 4 * H, 30 * H, 10 * H);
    }

    private static MarketEvent deal() {
        return MarketEvent.story(EventKind.DEAL, Source.ADMIN, "iron_ingot", 0.14, T0, 4 * H, 30 * H, 10 * H);
    }

    private static MarketEvent real(double strength) {
        return MarketEvent.shock(EventKind.REAL, Source.REAL, "gold_ingot", strength, T0, 24 * H, 96 * H);
    }

    private static MarketEvent wanted() {
        return MarketEvent.info(EventKind.WANTED, Source.SIM, "gold_ingot", null, T0, T0);
    }

    private static MarketEvent season() {
        return MarketEvent.info(EventKind.SEASON, Source.CALENDAR, null, "harvest_time:2026", T0, T0 + 30 * 24 * H);
    }

    private static MarketEvent of(EventKind kind) {
        return switch (kind) {
            case UP -> up();
            case DOWN -> down();
            case HOT -> hot();
            case DEAL -> deal();
            case WANTED -> wanted();
            case SEASON -> season();
            case REAL -> real(0.03);
        };
    }

    // ---- glyphs and lengths -------------------------------------------------------------

    @Test
    void everyLabelStaysBelowU10000AndUsesOnlyTheMarketsGlyphs() {
        List<String> all = everything();
        assertTrue(all.size() > 150, "the sweep reached every renderer: " + all.size());
        List<String> offences = new ArrayList<>();
        for (String s : all) {
            s.codePoints().forEach(cp -> {
                if (cp > 0xFFFF) {
                    offences.add(String.format("U+%04X above the BMP in: %s", cp, s));
                } else if (cp > 0x7E && ALLOWED.indexOf(cp) < 0) {
                    offences.add(String.format("U+%04X is not one of the market's glyphs in: %s", cp, s));
                }
            });
        }
        assertTrue(offences.isEmpty(), String.join("\n", offences));
    }

    @Test
    void aSignPrefixPlusTheWorstTrendFitsFifteenCharacters() {
        assertEquals(14, MarketLabels.visibleLength("&a✦DEAL " + Trend.color(-14.2) + Trend.label(-14.2)),
                "the spec's longest case, ✦DEAL ▼ 14.20%");
        // A price can at most lose everything (-100%); a gain of 1000% or more in 24 h is the
        // only thing longer than the ones below, and signLine drops the badge for it.
        double[] trends = {-100.0, -99.99, -14.2, -0.01, 0.0, 0.04, 14.2, 99.99, 100.0, 999.99};
        for (Badge badge : Badge.values()) {
            for (double ch : trends) {
                String line = MarketLabels.signPrefix(badge) + Trend.color(ch) + Trend.label(ch);
                assertTrue(MarketLabels.visibleLength(line) <= MarketLabels.SIGN_LINE_MAX, line);
                assertEquals(line, MarketLabels.signLine(badge, Trend.color(ch), Trend.label(ch)));
            }
        }
        assertEquals("&a✦DEAL &c▼ 14.20%", MarketLabels.signLine(Badge.DEAL, Trend.color(-14.2), Trend.label(-14.2)));
        assertEquals("&a▲ 1234.50%", MarketLabels.signLine(Badge.DEAL, Trend.color(1234.5), Trend.label(1234.5)),
                "too long with the badge: the trend alone, never a cut number");
        assertEquals("&7▬ 0.00%", MarketLabels.signLine(Badge.NONE, Trend.color(0), Trend.label(0)));
    }

    @Test
    void cutsAndWrapsKeepTheirLimits() {
        assertEquals("Short", MarketLabels.cut("Short", 34));
        String cut = MarketLabels.cut("Hot, hot, hot! Crate wants your Oak Logs today!", 34);
        assertEquals(34, cut.length());
        assertTrue(cut.endsWith("…"), cut);
        assertEquals("abc…", MarketLabels.cut("abc defgh", 5), "a trailing space before … is dropped");

        String head = "A giant castle is being built! Everyone wants Heart of the Sea Shards!";
        List<String> sell = MarketLabels.sellButtonLore(List.of(), "&f" + head);
        assertEquals(1, sell.size());
        assertEquals(2 + MarketLabels.SLOT_HEADLINE_MAX, MarketLabels.visibleLength(sell.get(0)), "» + space + 34");
        assertTrue(sell.get(0).startsWith("&e» A giant castle"), sell.get(0));

        assertEquals(MarketLabels.NEWS_ENTRY_NAME_MAX, MarketLabels.visibleLength(MarketLabels.newsEntryName(head)));
        assertEquals(MarketLabels.PAPI_NEWS_MAX, MarketLabels.papiNews("x".repeat(80)).length());
        assertEquals("A giant castle is being built! Everyone wants Heart of the…",
                MarketLabels.papiNews("&f" + head + " " + head), "a space at the cut is dropped before the …");
        assertEquals(MarketLabels.CALM, MarketLabels.papiNews(null));
        assertEquals(MarketLabels.CALM, MarketLabels.papiNews("&7 "));

        List<String> wrapped = MarketLabels.wrap(head, 32, 3);
        assertEquals(List.of("A giant castle is being built!", "Everyone wants Heart of the Sea", "Shards!"), wrapped);
        List<String> tooLong = MarketLabels.wrap(head + " " + head, 32, 3);
        assertEquals(3, tooLong.size());
        assertTrue(tooLong.get(2).endsWith("…"), "the last line says there is more: " + tooLong.get(2));
        for (String line : tooLong) {
            assertTrue(line.length() <= 32, line);
        }
        assertEquals(List.of("abcdefghij", "klmno"), MarketLabels.wrap("abcdefghijklmno", 10, 3),
                "a word longer than a line is split");
        assertEquals(List.of(), MarketLabels.wrap("  ", 32, 3));
    }

    @Test
    void theNewsBoardFitsEveryShippedHeadlineOnThirtyTwoCharacterLines() {
        List<String> tpls = new ArrayList<>();
        tpls.addAll(Headlines.UP);
        tpls.addAll(Headlines.DOWN);
        tpls.addAll(Headlines.HOT);
        tpls.addAll(Headlines.DEAL);
        tpls.addAll(Headlines.WANTED);
        for (String tpl : tpls) {
            String h = Headlines.render(tpl,
                    Map.of("item", "Heart of the Sea Shards", "Name", "Heart of the Sea Shard"));
            List<String> board = MarketLabels.newsBoard(h, "12m ago",
                    List.of("Oak Log", "Iron Ingot", "Cobblestone", "Heart of the Sea"), List.of("Wheat"));
            assertEquals(MarketLabels.BOARD_HEADER, board.get(0));
            for (String line : board.subList(1, board.size())) {
                assertTrue(MarketLabels.visibleLength(line) <= MarketLabels.BOARD_WIDTH, line);
            }
            List<String> headline = board.stream().filter(l -> l.startsWith("&f")).toList();
            assertTrue(!headline.isEmpty() && headline.size() <= MarketLabels.BOARD_HEADLINE_LINES, h);
            assertEquals(h, String.join(" ", headline.stream().map(MarketLabels::plain).toList()),
                    "a shipped headline is wrapped whole, never cut: " + board);
        }
        assertEquals(List.of("&e&l» CRATE NEWS «", "&fCrate is looking for Gold", "&fIngots!", "&812m ago",
                        "&6★ Hot: Oak Log, Iron Ingot, Cob…", "&a✦ Deal: Wheat"),
                MarketLabels.newsBoard("Crate is looking for Gold Ingots!", "12m ago",
                        List.of("Oak Log", "Iron Ingot", "Cobblestone"), List.of("&aWheat")));
        assertEquals(List.of("&e&l» CRATE NEWS «", "&7The Crate Market is calm today."),
                MarketLabels.newsBoard(null, null, List.of(), null));
    }

    // ---- §7.1 tiles, signs, holograms, TVs, previews ------------------------------------

    @Test
    void tileLabelsAreTheSpecTable() {
        assertEquals(" &6&l★ HOT", MarketLabels.nameSuffix(Badge.HOT, false));
        assertEquals(" &a&l✦ DEAL", MarketLabels.nameSuffix(Badge.DEAL, true));
        assertEquals(" &a▲", MarketLabels.nameSuffix(Badge.UP, false));
        assertEquals(" &c▼", MarketLabels.nameSuffix(Badge.DOWN, true));
        assertEquals(" &e&l» WANTED", MarketLabels.nameSuffix(Badge.WANTED, true));
        assertEquals("", MarketLabels.nameSuffix(Badge.WANTED, false), "the store keeps OUT OF STOCK");
        assertEquals("", MarketLabels.nameSuffix(Badge.NONE, true));
        assertEquals("", MarketLabels.nameSuffix(null, true));
        assertTrue(MarketLabels.glint(Badge.HOT));
        assertTrue(MarketLabels.glint(Badge.DEAL));
        assertFalse(MarketLabels.glint(Badge.UP));
        assertFalse(MarketLabels.glint(Badge.WANTED));

        String usual = "$22.49";
        assertEquals(List.of("&6★ HOT! &7Price is up &6+12%&7. Usually &f$22.49&7.",
                        "&7Better for selling than buying. &8(about a day left)"),
                MarketLabels.storeLore(Badge.HOT, false, 11.7, usual, "about a day", 0));
        assertEquals(List.of("&6★ HOT! &7Price is up &6+12%&7. Usually &f$22.49&7.",
                        "&7Better for selling than buying. &8(a few hours left)", "&7Cooling off…"),
                MarketLabels.storeLore(Badge.HOT, true, 11.7, usual, "a few hours", 0));
        assertEquals(List.of("&a✦ DEAL! &f14% off&7. Usually &f$22.49&7.",
                        "&7Limit &f40 &7a day while it's on sale. &8(about 2 days left)"),
                MarketLabels.storeLore(Badge.DEAL, false, -14.2, usual, "about 2 days", 40));
        assertEquals(List.of("&a✦ DEAL! &f14% off&7. Usually &f$22.49&7.",
                        "&7Limit &f40 &7a day while it's on sale. &8(a few hours left)", "&7(ending soon)"),
                MarketLabels.storeLore(Badge.DEAL, true, -14.2, usual, "a few hours", 40));
        assertEquals(List.of("&a▲ Price going up fast! &7Usually &f$22.49&7."),
                MarketLabels.storeLore(Badge.UP, false, 21.0, usual, "less than a day", 0));
        assertEquals(List.of("&c▼ Price went down! &a&lGood time to buy!", "&7Usually &f$22.49&7. Limit &f40 &7a day."),
                MarketLabels.storeLore(Badge.DOWN, false, -18.0, usual, "less than a day", 40));
        assertEquals(List.of(), MarketLabels.storeLore(Badge.WANTED, false, 0, usual, "", 0));
        assertEquals(List.of("&7Usually &f$22.49"), MarketLabels.storeLore(Badge.NONE, false, -3.0, usual, "", 0));
        assertEquals(List.of(), MarketLabels.storeLore(Badge.NONE, false, 2.99, usual, "", 0), "under 3% says nothing");
        assertEquals(List.of(), MarketLabels.storeLore(null, false, Double.NaN, usual, "", 0));

        assertEquals(List.of("&6★ HOT! &eCrate pays &6+12% &eextra!", "&e&lSell now! &8(about a day left)",
                        "&7Up to &f40 &7a day at this price."),
                MarketLabels.sellLore(Badge.HOT, false, 12.4, usual, "about a day", 40));
        assertEquals(List.of("&6★ HOT! &eCrate pays &6+12% &eextra!", "&e&lSell now! &8(a few hours left)",
                        "&7Up to &f40 &7a day at this price.", "&eCooling off soon - sell now!"),
                MarketLabels.sellLore(Badge.HOT, true, 12.4, usual, "a few hours", 40));
        assertEquals(List.of("&a✦ On sale in the store, so Crate pays less right now."),
                MarketLabels.sellLore(Badge.DEAL, false, -14, usual, "about 2 days", 40));
        assertEquals(MarketLabels.sellLore(Badge.DEAL, false, -14, usual, "x", 40),
                MarketLabels.sellLore(Badge.DEAL, true, -14, usual, "y", 40), "a fading DEAL reads the same");
        assertEquals(List.of("&a▲ Price going up fast! &e&lSell now!", "&7Up to &f40 &7a day at this price."),
                MarketLabels.sellLore(Badge.UP, false, 22, usual, "", 40));
        assertEquals(List.of("&c▼ Price went down. &7Maybe wait to sell."),
                MarketLabels.sellLore(Badge.DOWN, false, -18, usual, "", 40));
        assertEquals(List.of("&e» WANTED! &7Crate has none. &eSell some for its top price!"),
                MarketLabels.sellLore(Badge.WANTED, false, 0, "$150.00", "", 0));
        assertEquals(List.of("&7Usually &f$22.49"), MarketLabels.sellLore(Badge.NONE, false, 4.1, usual, "", 0));

        assertEquals("&6★HOT ", MarketLabels.signPrefix(Badge.HOT));
        assertEquals("&a✦DEAL ", MarketLabels.signPrefix(Badge.DEAL));
        assertEquals("&a▲NEWS ", MarketLabels.signPrefix(Badge.UP));
        assertEquals("&c▼NEWS ", MarketLabels.signPrefix(Badge.DOWN));
        assertEquals("", MarketLabels.signPrefix(Badge.WANTED));
        assertEquals(" &6★ HOT", MarketLabels.holoSuffix(Badge.HOT));
        assertEquals(" &a✦ DEAL", MarketLabels.holoSuffix(Badge.DEAL));
        assertEquals(" &a▲ NEWS", MarketLabels.holoSuffix(Badge.UP));
        assertEquals(" &c▼ NEWS", MarketLabels.holoSuffix(Badge.DOWN));
        assertEquals("", MarketLabels.holoSuffix(Badge.WANTED));
        assertEquals("&6&l★ HOT ★", MarketLabels.tvLine(Badge.HOT, false));
        assertEquals("&6★ HOT &7(cooling off)", MarketLabels.tvLine(Badge.HOT, true));
        assertEquals("&a&l✦ DEAL ✦", MarketLabels.tvLine(Badge.DEAL, false));
        assertEquals("&a✦ DEAL &7(ending soon)", MarketLabels.tvLine(Badge.DEAL, true));
        assertEquals("&a&l▲ PRICE UP!", MarketLabels.tvLine(Badge.UP, false));
        assertEquals("&c&l▼ PRICE DOWN!", MarketLabels.tvLine(Badge.DOWN, false));
        assertEquals("&e&l» WANTED", MarketLabels.tvLine(Badge.WANTED, false));
        assertEquals("", MarketLabels.tvLine(Badge.NONE, false));
        assertEquals("&7Usually &f$4.47", MarketLabels.usually("$4.47"));
    }

    @Test
    void quantityPreviewExtrasFollowTheBadgeAndTheSide() {
        for (Badge b : new Badge[] {Badge.HOT, Badge.UP}) {
            assertEquals(List.of("&6★ Crate pays extra right now!"), MarketLabels.previewLines(b, true, 40));
            assertEquals(List.of("&7The price is up right now."), MarketLabels.previewLines(b, false, 40));
        }
        for (Badge b : new Badge[] {Badge.DEAL, Badge.DOWN}) {
            assertEquals(List.of("&7Crate pays less right now (on sale)."), MarketLabels.previewLines(b, true, 40));
            assertEquals(List.of("&a✦ On sale right now! &7Limit 40 a day."), MarketLabels.previewLines(b, false, 40));
        }
        assertEquals(List.of("&a✦ On sale right now!"), MarketLabels.previewLines(Badge.DEAL, false, 0));
        assertEquals(List.of(), MarketLabels.previewLines(Badge.WANTED, true, 0));
        assertEquals(List.of(), MarketLabels.previewLines(Badge.NONE, false, 0));
        assertEquals(List.of(), MarketLabels.previewLines(null, false, 0));
    }

    // ---- §6.2 announcements -------------------------------------------------------------

    @Test
    void newsFlashesAreTheSpecLines() {
        assertEquals(List.of("&e&l» NEWS FLASH «",
                        "&fThe villagers are having a party and need Wheat!",
                        "&a▲ Wheat is going UP! &fCrate pays $3.29 → $4.01 &7(+22%) &e&lSell now!"),
                MarketLabels.newsLines(up(), "Wheat", "The villagers are having a party and need Wheat!",
                        "$3.29", "$4.01", 0));
        assertEquals(List.of("&e&l» NEWS FLASH «",
                        "&fMiners found a huge pile of Iron Ingots!",
                        "&c▼ Iron Ingot is going DOWN! &f$23.62 → $19.37 &7(-18%) &b&lGood time to buy! "
                                + "&7Limit 40 a day."),
                MarketLabels.newsLines(down(), "Iron Ingot", "Miners found a huge pile of Iron Ingots!",
                        "$23.62", "$19.37", 40));

        assertEquals(Optional.of(new MarketLabels.Title("&e&lNEWS FLASH", "&a▲ Wheat going UP! ▲")),
                MarketLabels.titleFor(EventKind.UP, "Wheat"));
        assertEquals(Optional.of(new MarketLabels.Title("&e&lNEWS FLASH", "&c▼ Wheat going DOWN! ▼")),
                MarketLabels.titleFor(EventKind.DOWN, "Wheat"));
        assertEquals("&e» NEWS: &fWheat &agoing UP!", MarketLabels.actionBar(EventKind.UP, "Wheat", "Wheat"));
        assertEquals("&e» NEWS: &fWheat &cgoing DOWN!", MarketLabels.actionBar(EventKind.DOWN, "Wheat", "Wheat"));
        assertEquals("[Market] NEWS FLASH wheat UP +22% ($3.46 → $4.22) sim",
                MarketLabels.consoleLine(up(), "wheat", "$3.46", "$4.22"));
        assertEquals("[Market] HOT oak_log +12% sim", MarketLabels.consoleLine(hot(), "oak_log", null, ""));
        assertEquals("&7Click to see the price", MarketLabels.PRICE_HOVER);
        assertEquals("/hcm market price wheat", MarketLabels.priceCommand("wheat"));
    }

    @Test
    void storiesAreAnnouncedAtFullStrengthWithTheSpecLines() {
        assertEquals(List.of("&6&l★ HOT ITEM ★", "&fEverybody wants Oak Logs this week!",
                        "&6Oak Log: Crate pays about +12% for about 2 days. &e&lSell now!"),
                MarketLabels.storyLines(hot(), "Oak Log", "Everybody wants Oak Logs this week!", "about 2 days", 0));
        assertEquals(List.of("&a&l✦ DEAL ✦", "&fSale time! Get Iron Ingots for less at Crate!",
                        "&aIron Ingot is about 14% off for about 2 days. &7Limit 40 a day. &e&lOrder at a PC!"),
                MarketLabels.storyLines(deal(), "Iron Ingot", "Sale time! Get Iron Ingots for less at Crate!",
                        "about 2 days", 40));

        assertEquals(Optional.of(new MarketLabels.Title("&6&l★ HOT ITEM ★", "&6Oak Log")),
                MarketLabels.titleFor(EventKind.HOT, "Oak Log"));
        assertEquals(Optional.of(new MarketLabels.Title("&a&l✦ DEAL ✦", "&aIron Ingot on sale!")),
                MarketLabels.titleFor(EventKind.DEAL, "Iron Ingot"));
        assertEquals("&e» NEWS: &fOak Log &6is HOT!", MarketLabels.actionBar(EventKind.HOT, "Oak Log", "Oak Logs"));
        assertEquals("&e» NEWS: &fIron Ingot &aon SALE!", MarketLabels.actionBar(EventKind.DEAL, "Iron Ingot", "x"));

        assertEquals("&6» Last call! &fOak Log &7is only HOT for about 3 more hours.",
                MarketLabels.lastCall(EventKind.HOT, "Oak Log", 3 * H - 60_000));
        assertEquals("&a» Last call! &fIron Ingot &7is only on sale for about 3 more hours.",
                MarketLabels.lastCall(EventKind.DEAL, "Iron Ingot", 3 * H));
        assertEquals("&6» Last call! &fOak Log &7is only HOT for about 1 more hour.",
                MarketLabels.lastCall(EventKind.HOT, "Oak Log", 40 * 60_000L),
                "never '0 more hours' or '1 more hours'");
        assertEquals("&7★ Oak Log cooled off. Prices are back to normal.",
                MarketLabels.ending(EventKind.HOT, "Oak Log"));
        assertEquals("&7✦ The Iron Ingot sale is over.", MarketLabels.ending(EventKind.DEAL, "Iron Ingot"));
        assertEquals("&7✦ The Iron Ingot sale sold out!", MarketLabels.soldOut("Iron Ingot"));
    }

    @Test
    void wantedSeasonRealAndIntroAreTheSpecLines() {
        assertEquals(List.of("&e&l» MARKET NEWS «", "&fCrate is looking for Gold Ingots! &eBe the first to sell some!"),
                MarketLabels.wantedLines("Crate is looking for Gold Ingots!"));
        assertEquals("&e» Crate wants Gold Ingots!",
                MarketLabels.actionBar(EventKind.WANTED, "Gold Ingot", "Gold Ingots"));

        Map<String, Double> harvest = new LinkedHashMap<>();
        harvest.put("wheat", -4.0);
        String effects = MarketLabels.seasonEffects(harvest, id -> "wheat".equals(id) ? "Wheat" : null);
        assertEquals("Wheat -4%", effects);
        assertEquals("Oct 31", MarketLabels.untilDate(LocalDate.of(2026, 10, 31)));
        assertEquals("Sep 5", MarketLabels.untilDate(LocalDate.of(2026, 9, 5)));
        assertEquals(List.of("&2&l» SEASON NEWS «",
                        "&aHarvest time! The farms are full of wheat. &7(Wheat -4% until Oct 31)"),
                MarketLabels.seasonLines("Harvest time! The farms are full of wheat.", effects, "Oct 31"));

        Map<String, Double> many = new LinkedHashMap<>();
        many.put("*", -3.0);
        many.put("gold_ingot", 4.5);
        many.put("zero", 0.0);
        many.put("diamond", 3.0);
        many.put("oak_log", 4.0);
        assertEquals("Everything -3%, Gold Ingot +4.5%, diamond +3%, …",
                MarketLabels.seasonEffects(many, id -> "gold_ingot".equals(id) ? "&6Gold Ingot" : null));

        assertEquals(List.of("&d&l» REAL-WORLD NEWS «", "&fIn the real world, gold prices went up today!",
                        "&a▲ So Crate's Gold Ingot went up a little too. &7(+3%)"),
                MarketLabels.realLines(real(0.03), "Gold Ingot", "In the real world, gold prices went up today!"));
        assertEquals(List.of("&d&l» REAL-WORLD NEWS «", "&fIn the real world, gold prices went down today!",
                        "&c▼ So Crate's Gold Ingot went down a little too. &7(-4%)"),
                MarketLabels.realLines(real(-0.04), "Gold Ingot", "In the real world, gold prices went down today!"));

        assertEquals(List.of("&6&l★ The Crate Market is LIVE! ★",
                        "&ePrices now move a little on their own. Watch for &6★ HOT&e, &a✦ DEAL &eand "
                                + "&e» NEWS FLASH&e! &7(/hcm market news)"),
                MarketLabels.intro());
        for (EventKind k : new EventKind[] {EventKind.WANTED, EventKind.SEASON, EventKind.REAL}) {
            assertEquals(Optional.empty(), MarketLabels.titleFor(k, "x"), k + " has no title");
        }
    }

    @Test
    void storedLinesStripToWhatTheNewsFeedShows() {
        assertEquals("Wheat is going UP! Crate pays $3.29 → $4.01 (+22%) Sell now!",
                MarketLabels.plain(MarketLabels.newsLine(up(), "Wheat", "$3.29", "$4.01", 0)), "the §14.2 example");
        assertEquals("&aWheat is going UP! &fCrate pays $3.29 → $4.01 &7(+22%) &e&lSell now!",
                MarketLabels.newsLine(up(), "Wheat", "$3.29", "$4.01", 0));
        assertEquals("&cIron Ingot is going DOWN! &f$23.62 → $19.37 &7(-18%) &b&lGood time to buy! &7Limit 40 a day.",
                MarketLabels.newsLine(down(), "Iron Ingot", "$23.62", "$19.37", 40));
        assertEquals("Wheat -4% until Oct 31", MarketLabels.seasonLine("Wheat -4%", "Oct 31"), "the §14.2 example");
        assertEquals("&6Oak Log: Crate pays about +12% for about a day. &e&lSell now!",
                MarketLabels.storyLine(hot(), "Oak Log", "about a day", 0));
        assertEquals("&aSo Crate's Gold Ingot went up a little too. &7(+3%)",
                MarketLabels.realLine(real(0.03), "Gold Ingot"));
        assertEquals("&eBe the first to sell some!", MarketLabels.wantedLine());
    }

    @Test
    void aRendererGivesNothingForAKindItDoesNotHandle() {
        assertEquals(List.of(), MarketLabels.newsLines(hot(), "x", "h", "a", "b", 1));
        assertEquals(List.of(), MarketLabels.newsLines(null, "x", "h", "a", "b", 1));
        assertEquals("", MarketLabels.newsLine(real(0.03), "x", "a", "b", 1));
        assertEquals(List.of(), MarketLabels.storyLines(up(), "x", "h", "l", 1));
        assertEquals("", MarketLabels.storyLine(wanted(), "x", "l", 1));
        assertEquals(List.of(), MarketLabels.realLines(down(), "x", "h"));
        assertEquals("", MarketLabels.realLine(season(), "x"));
        assertEquals("", MarketLabels.lastCall(EventKind.UP, "x", H));
        assertEquals("", MarketLabels.lastCall(null, "x", H));
        assertEquals("", MarketLabels.ending(EventKind.DOWN, "x"));
        assertEquals("", MarketLabels.actionBar(EventKind.SEASON, "x", "y"));
        assertEquals("", MarketLabels.actionBar(null, "x", "y"));
        assertEquals(Optional.empty(), MarketLabels.titleFor(null, "x"));
        assertEquals("", MarketLabels.summary(null, "x"));
        assertEquals("", MarketLabels.consoleLine(null, "x", "a", "b"));
    }

    // ---- §6.3 catch-up, §7.2 menus ------------------------------------------------------

    @Test
    void theCatchUpIsTheSpecBlock() {
        List<String> bullets = List.of(
                MarketLabels.catchUpLine(MarketLabels.summary(up(), "Wheat"), "3h ago"),
                MarketLabels.catchUpLine(MarketLabels.summary(hot(), "Oak Log"), "yesterday"));
        String now = MarketLabels.rightNow(List.of(
                MarketLabels.rightNowPart(Badge.HOT, "Oak Log", "about a day"),
                MarketLabels.rightNowPart(Badge.DEAL, "Iron Ingot", "about 2 days")));
        assertEquals(List.of("&6&l» While you were away:",
                        "&7 • &a▲ &fWheat went up 22% &8(3h ago)",
                        "&7 • &6★ &fOak Log got HOT &8(yesterday)",
                        "&8   …and 2 more: /hcm market news",
                        "&7 Right now: &6★ Oak Log &7(about a day left) &8· &a✦ Iron Ingot &7(about 2 days left)"),
                MarketLabels.catchUp(bullets, 2, now));

        assertEquals(List.of(now), MarketLabels.catchUp(List.of(), 0, now), "no news: only the Right now line");
        assertEquals(List.of("&6&l» While you were away:", bullets.get(0)),
                MarketLabels.catchUp(bullets.subList(0, 1), 0, ""));
        assertEquals(List.of(), MarketLabels.catchUp(null, 5, null));
        assertEquals("", MarketLabels.rightNow(List.of("", MarketLabels.rightNowPart(Badge.WANTED, "x", "y"))));
        assertEquals("&7 Right now: &2» Harvest Time &7(about 34 days left)",
                MarketLabels.rightNow(List.of(MarketLabels.rightNowSeason("Harvest Time", "about 34 days"))));
    }

    @Test
    void summariesAreTheSpecForms() {
        assertEquals("&a▲ &fWheat went up 22%", MarketLabels.summary(up(), "Wheat"));
        assertEquals("&c▼ &fIron Ingot went down 18%", MarketLabels.summary(down(), "Iron Ingot"));
        assertEquals("&6★ &fOak Log got HOT", MarketLabels.summary(hot(), "Oak Log"));
        assertEquals("&a✦ &fIron Ingot went on sale", MarketLabels.summary(deal(), "Iron Ingot"));
        assertEquals("&e» &fCrate is looking for Gold Ingots", MarketLabels.summary(wanted(), "Gold Ingots"));
        assertEquals("&2» &fHarvest Time started", MarketLabels.summary(season(), "Harvest Time"));
        assertEquals("&d» &fReal-world gold went up", MarketLabels.summary(real(0.03), "gold"));
        assertEquals("&d» &fReal-world gold went down", MarketLabels.summary(real(-0.03), "gold"));
    }

    @Test
    void aRealMoveNeverClaimsACratePriceChangeThatDidNotHappen() {
        // gold_ingot ships with stock 0: pinned at its $150 ceiling, a REAL nudge cannot move it,
        // so the simulator stores pct 0 with before == after. That 0 is the truth, not "unset".
        MarketEvent stuckUp = real(0.04).withPrices(SimMath.pct(150.0, 150.0), 150.0, 150.0);
        MarketEvent stuckDown = real(-0.04).withPrices(0.0, 150.0, 150.0);
        assertEquals(0.0, Math.abs(MarketLabels.pct(stuckUp)), 1e-12);
        assertEquals(0.0, Math.abs(MarketLabels.pct(stuckDown)), 1e-12);
        assertEquals(List.of("&d&l» REAL-WORLD NEWS «", "&fIn the real world, gold prices went up today!",
                        "&7» But Crate's Gold Ingot price stayed about the same."),
                MarketLabels.realLines(stuckUp, "Gold Ingot", "In the real world, gold prices went up today!"));
        assertEquals("&7» But Crate's Gold Ingot price stayed about the same.",
                MarketLabels.realLines(stuckDown, "Gold Ingot", "h").get(2));
        assertEquals("But Crate's Gold Ingot price stayed about the same.",
                MarketLabels.plain(MarketLabels.realLine(stuckUp, "Gold Ingot")), "the stored line /api/news shows");
        // The real world did move, and the summary still says which way.
        assertEquals("&d» &fReal-world gold went up", MarketLabels.summary(stuckUp, "gold"));
        assertEquals("&d» &fReal-world gold went down", MarketLabels.summary(stuckDown, "gold"));
        assertEquals("[Market] REAL gold_ingot 0% ($150.00 → $150.00) real",
                MarketLabels.consoleLine(stuckUp, "gold_ingot", "$150.00", "$150.00"));

        // Under half a percent rounds to 0: no "went up a little too (+0%)".
        MarketEvent tiny = real(0.04).withPrices(0.4, 100.0, 100.4);
        assertEquals("&7But Crate's Gold Ingot price stayed about the same.", MarketLabels.realLine(tiny, "Gold Ingot"));

        // A measured move quotes what the price did, not the strength.
        MarketEvent partly = real(0.04).withPrices(2.6, 100.0, 102.6);
        assertEquals("&a▲ So Crate's Gold Ingot went up a little too. &7(+3%)",
                MarketLabels.realLines(partly, "Gold Ingot", "h").get(2));
        MarketEvent partlyDown = real(-0.04).withPrices(-1.2, 100.0, 98.8);
        assertEquals("&cSo Crate's Gold Ingot went down a little too. &7(-1%)",
                MarketLabels.realLine(partlyDown, "Gold Ingot"));

        // A row with no prices recorded still falls back to its strength.
        assertEquals("&aSo Crate's Gold Ingot went up a little too. &7(+3%)", MarketLabels.realLine(real(0.03), "Gold Ingot"));
    }

    @Test
    void theNewsButtonTheSellButtonAndTheNewsMenu() {
        assertEquals("&e» Market News", MarketLabels.NEWS_BUTTON);
        assertEquals(List.of("&a▲ &fWheat went up 22% &8(3h ago)", "&6★ &fOak Log got HOT &8(yesterday)",
                        "&a✦ &fIron Ingot went on sale &8(2 days ago)", "&6★ Hot: Oak Log", "&a✦ Deals: Iron Ingot",
                        "&eClick for all the news"),
                MarketLabels.newsButtonLore(List.of(
                                MarketLabels.withAge(MarketLabels.summary(up(), "Wheat"), "3h ago"),
                                MarketLabels.withAge(MarketLabels.summary(hot(), "Oak Log"), "yesterday"),
                                MarketLabels.withAge(MarketLabels.summary(deal(), "Iron Ingot"), "2 days ago"),
                                "a fourth is not shown"),
                        List.of("Oak Log"), List.of("Iron Ingot")));
        assertEquals(List.of("&7The Crate Market is calm today.", "&eClick for all the news"),
                MarketLabels.newsButtonLore(null, List.of(), null));

        assertEquals(List.of("&6★ Hot right now: &fOak Log, Iron Ingot, Wheat, …",
                        "&e» Everybody wants Oak Logs this wee…"),
                MarketLabels.sellButtonLore(List.of("Oak Log", "Iron Ingot", "Wheat", "Cobblestone"),
                        "Everybody wants Oak Logs this week!"));
        assertEquals(List.of(), MarketLabels.sellButtonLore(null, null));

        assertEquals("&e&l» Crate Market News", MarketLabels.NEWS_MENU_HEADER);
        assertEquals("&7News in chat: &aON", MarketLabels.muteToggle(true));
        assertEquals("&7News in chat: &cOFF", MarketLabels.muteToggle(false));
        assertEquals(List.of("&73h ago", "&7$3.29 → $4.01 (+22%)", "&aStill going!", "&eClick to sell some"),
                MarketLabels.newsEntryLore(up(), "3h ago", "$3.29", "$4.01", true));
        assertEquals(List.of("&72 days ago", "&7$22.49 → $18.44 (-18%)", "&7All over.", "&eClick to order some"),
                MarketLabels.newsEntryLore(down(), "2 days ago", "$22.49", "$18.44", false));
        assertEquals(List.of("&7yesterday", "&aStill going!"),
                MarketLabels.newsEntryLore(season(), "yesterday", null, null, true), "a season opens nothing");
        assertEquals("&ePrices just moved! Take another look.", MarketLabels.PRICES_MOVED);
    }

    // ---- numbers, PAPI, admin -----------------------------------------------------------

    @Test
    void percentsNeverContradictTheBadge() {
        assertEquals(List.of("&6★ HOT! &7Price is up &6+0%&7. Usually &f$1.00&7.", "&7Better for selling than buying."),
                MarketLabels.storeLore(Badge.HOT, false, -1.2, "$1.00", "", 0), "drift pulled a HOT below usual");
        assertEquals("&a✦ DEAL! &f0% off&7. Usually &f$1.00&7.",
                MarketLabels.storeLore(Badge.DEAL, false, 0.4, "$1.00", "", 0).get(0));
        assertEquals(0, MarketLabels.wholeUp(-7));
        assertEquals(7, MarketLabels.wholeDown(-7.4));
        assertEquals(0, MarketLabels.wholeUp(Double.NaN));

        // A row stored with an unsigned (or oddly signed) pct still reads the way its kind goes.
        MarketEvent unsignedDown = down().withPrices(18.0, 22.49, 18.44);
        assertEquals(-18.0, MarketLabels.pct(unsignedDown));
        assertEquals("&c▼ &fIron Ingot went down 18%", MarketLabels.summary(unsignedDown, "Iron Ingot"));
        MarketEvent negativeUp = up().withPrices(-22.0, 3.46, 4.22);
        assertEquals(22.0, MarketLabels.pct(negativeUp));
        assertEquals(12.0, MarketLabels.pct(hot()), 1e-9, "no stored pct: the strength");
        assertEquals(-14.0, MarketLabels.pct(deal()), 1e-9);
        assertEquals(-3.0, MarketLabels.pct(real(-0.03).withPrices(3.0, 0, 0)), 1e-9, "a REAL follows its strength");
        assertEquals(0.0, MarketLabels.pct(null));

        assertEquals("+11.7%", MarketLabels.moodPct(11.7));
        assertEquals("-3.2%", MarketLabels.moodPct(-3.2));
        assertEquals("0%", MarketLabels.moodPct(0));
        assertEquals("0%", MarketLabels.moodPct(-0.04));
        assertEquals("0%", MarketLabels.moodPct(Double.NaN));
        assertEquals("+12.0%", MarketLabels.moodPct(11.96));
        assertEquals("+22%", MarketLabels.signedWhole(21.6));
        assertEquals("-18%", MarketLabels.signedWhole(-18.4));
        assertEquals("0%", MarketLabels.signedWhole(0.4));
    }

    @Test
    void placeholdersAreTheSpecForms() {
        assertEquals("★ HOT +12%", MarketLabels.papiBadge(Badge.HOT, 11.7));
        assertEquals("✦ DEAL -14%", MarketLabels.papiBadge(Badge.DEAL, -14.2));
        assertEquals("▲ UP +22%", MarketLabels.papiBadge(Badge.UP, 22));
        assertEquals("▼ DOWN -18%", MarketLabels.papiBadge(Badge.DOWN, -18));
        assertEquals("» WANTED", MarketLabels.papiBadge(Badge.WANTED, 0));
        assertEquals("", MarketLabels.papiBadge(Badge.NONE, 5));
        assertEquals("HOT", MarketLabels.papiStatus(Badge.HOT));
        assertEquals("WANTED", MarketLabels.papiStatus(Badge.WANTED));
        assertEquals("", MarketLabels.papiStatus(Badge.NONE));
        assertEquals("", MarketLabels.papiStatus(null));

        assertEquals("20h", MarketLabels.endsIn(20 * H));
        assertEquals("20h", MarketLabels.endsIn(19 * H + 1), "hours round up");
        assertEquals("1h", MarketLabels.endsIn(30 * 60_000L));
        assertEquals("47h", MarketLabels.endsIn(47 * H));
        assertEquals("2d", MarketLabels.endsIn(48 * H));
        assertEquals("3d", MarketLabels.endsIn(70 * H));
        assertEquals("", MarketLabels.endsIn(0));
        assertEquals("", MarketLabels.endsIn(-5));

        assertEquals("&7Usual: &6$22.49 &8· &7Mood: &f+11.7% &8(HOT)",
                MarketLabels.adminMood("$22.49", 11.7, Badge.HOT));
        assertEquals("&7Usual: &6$4.47 &8· &7Mood: &f0% &8(quiet)", MarketLabels.adminMood("$4.47", 0, Badge.NONE));
        assertEquals("&7  usual: &f$22.49 &7 mood: &f+11.7%", MarketLabels.priceMood("$22.49", 11.7));
        assertEquals("&7  status: &6★ HOT +12% &8(about a day left)",
                MarketLabels.priceStatus(Badge.HOT, 11.7, "about a day"));
        assertEquals("", MarketLabels.priceStatus(Badge.NONE, 11.7, "about a day"));
    }

    // ---- the sweep ----------------------------------------------------------------------

    /** Every string every renderer can produce, for a representative spread of inputs. */
    private static List<String> everything() {
        List<String> out = new ArrayList<>();
        String name = "Heart of the Sea Shard";
        for (Badge b : Badge.values()) {
            out.add(MarketLabels.nameSuffix(b, true));
            out.add(MarketLabels.nameSuffix(b, false));
            out.add(MarketLabels.signPrefix(b));
            out.add(MarketLabels.signLine(b, Trend.color(-14.2), Trend.label(-14.2)));
            out.add(MarketLabels.holoSuffix(b));
            out.add(MarketLabels.badgeTag(b, 12));
            out.add(MarketLabels.papiBadge(b, -12));
            out.add(MarketLabels.papiStatus(b));
            out.add(MarketLabels.rightNowPart(b, name, "about a day"));
            out.add(MarketLabels.adminMood("$1.00", 3.3, b));
            out.add(MarketLabels.priceStatus(b, 3.3, "a few hours"));
            for (boolean flag : new boolean[] {true, false}) {
                out.add(MarketLabels.tvLine(b, flag));
                out.addAll(MarketLabels.storeLore(b, flag, 12.3, "$1.00", "about a day", 40));
                out.addAll(MarketLabels.storeLore(b, flag, -12.3, "$1.00", "", 0));
                out.addAll(MarketLabels.sellLore(b, flag, 12.3, "$1.00", "about a day", 40));
                out.addAll(MarketLabels.sellLore(b, flag, -12.3, "$1.00", "", 0));
                out.addAll(MarketLabels.previewLines(b, flag, 40));
            }
        }
        for (EventKind k : EventKind.values()) {
            MarketEvent e = of(k);
            out.add(MarketLabels.summary(e, name));
            out.add(MarketLabels.lastCall(k, name, 2 * H));
            out.add(MarketLabels.ending(k, name));
            out.add(MarketLabels.actionBar(k, name, name + "s"));
            out.add(MarketLabels.consoleLine(e, "id", "$1.00", "$2.00"));
            MarketLabels.titleFor(k, name).ifPresent(t -> {
                out.add(t.title());
                out.add(t.subtitle());
            });
            out.addAll(MarketLabels.newsLines(e, name, "Head", "$1.00", "$2.00", 40));
            out.add(MarketLabels.newsLine(e, name, "$1.00", "$2.00", 40));
            out.addAll(MarketLabels.storyLines(e, name, "Head", "about a day", 40));
            out.add(MarketLabels.storyLine(e, name, "about a day", 40));
            out.addAll(MarketLabels.realLines(e, name, "Head"));
            out.addAll(MarketLabels.realLines(real(-0.04), name, "Head"));
            out.addAll(MarketLabels.realLines(real(0.04).withPrices(0.0, 150.0, 150.0), name, "Head"));
            out.add(MarketLabels.realLine(e, name));
            out.addAll(MarketLabels.newsEntryLore(e, "3h ago", "$1.00", "$2.00", true));
            out.addAll(MarketLabels.newsEntryLore(e, "3h ago", "$1.00", "$2.00", false));
            out.add(MarketLabels.newsEntryName("Head " + k));
            out.add(MarketLabels.catchUpLine(MarketLabels.summary(e, name), "yesterday"));
        }
        out.addAll(MarketLabels.wantedLines("Crate is looking for Gold Ingots!"));
        out.addAll(MarketLabels.seasonLines("Harvest time!", "Wheat -4%, Everything +3%, …", "Oct 31"));
        out.add(MarketLabels.seasonEffects(Map.of("*", -3.0), null));
        out.addAll(MarketLabels.intro());
        out.add(MarketLabels.soldOut(name));
        out.addAll(MarketLabels.catchUp(List.of("a"), 3, MarketLabels.rightNow(List.of("&6★ x", "&a✦ y"))));
        out.add(MarketLabels.rightNowSeason("Harvest Time", "about a day"));
        out.addAll(MarketLabels.newsBoard("A long headline about many things that wraps over lines for sure",
                "3h ago", List.of("Oak Log", "Iron Ingot", "Cobblestone", "Wheat"), List.of("Diamond")));
        out.addAll(MarketLabels.newsBoard(null, null, null, null));
        out.addAll(MarketLabels.newsButtonLore(List.of("a"), List.of("b"), List.of("c")));
        out.addAll(MarketLabels.newsButtonLore(null, null, null));
        out.addAll(MarketLabels.sellButtonLore(List.of("a", "b", "c", "d"),
                "A headline long enough to be cut somewhere"));
        out.add(MarketLabels.muteToggle(true));
        out.add(MarketLabels.muteToggle(false));
        out.add(MarketLabels.priceMood("$1.00", -2.5));
        out.add(MarketLabels.papiNews(null));
        out.add(MarketLabels.papiNews("x".repeat(80)));
        out.add(MarketLabels.catchUpMore(2));
        out.add(MarketLabels.CALM);
        out.add(MarketLabels.PRICES_MOVED);
        out.add(MarketLabels.NEWS_BUTTON);
        out.add(MarketLabels.NEWS_BUTTON_CLICK);
        out.add(MarketLabels.NEWS_MENU_HEADER);
        out.add(MarketLabels.BOARD_HEADER);
        out.add(MarketLabels.PRICE_HOVER);
        out.add(MarketLabels.NEWS_NO_SIGN);
        out.add(MarketLabels.CATCH_UP_HEADER);
        return out;
    }
}
