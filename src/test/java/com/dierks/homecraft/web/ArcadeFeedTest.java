package com.dierks.homecraft.web;

import com.dierks.homecraft.arcade.ArcadeService;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.AchievementDef;
import com.dierks.homecraft.config.PluginConfig.AchievementType;
import com.dierks.homecraft.config.PluginConfig.Jackpot;
import com.dierks.homecraft.config.PluginConfig.Lotto;
import com.dierks.homecraft.config.PluginConfig.LottoPayout;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.FeedWriter.PayRow;
import com.dierks.homecraft.mini.Pack;
import com.dierks.homecraft.mini.Rarity;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code /api/arcade} feed the website's Arcade page reads (games spec §10), filled by hand
 * the way the games fill it through {@link FeedWriter}.
 *
 * <p>Pinned: the exact JSON of a full feed and of the four table games; a strict parse with the
 * exact key set of every kind of object (chance with {@code rtpByStake}, {@code oneIn} and
 * {@code stake} rows, Twenty-One's {@code payouts}, Higher or Lower's {@code maxMultiplier} /
 * {@code maxGuesses}, the cabinet's {@code board} / {@code unit} / {@code lowerIsBetter}, courses,
 * golf, the Arcade's own sections); the headline {@code rtp} is the lowest stake's, floored to one
 * decimal; a one-in-a-million line never reads {@code 0}; the Scratch Ticket's entry comes with
 * its pot, and its RTP is {@link ArcadeService#rtp}'s; {@code jackpot} is never advertised; what is
 * omitted and when; colour codes go.
 *
 * <p>And privacy: no UUID, balance, winner, command, permission or texture ever reaches the JSON,
 * and a record's holder only while {@code web.dashboard.arcade_show_names} is on.
 */
class ArcadeFeedTest {

    private static final long T = 1_790_000_000_000L;
    private static final String STEVE = "Steve";
    private static final String STEVE_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";

    // ---- fixtures -----------------------------------------------------------------------

    /** The Scratch Ticket exactly as config.yml ships it (10 tokens; 1 in 100 is the jackpot). */
    private static Lotto shippedLotto() {
        return new Lotto(10, List.of(new LottoPayout(0, false, 25), new LottoPayout(3, false, 40),
                new LottoPayout(8, false, 22), new LottoPayout(20, false, 9), new LottoPayout(50, false, 3),
                new LottoPayout(0, true, 1)), new Jackpot(50, 1, 1000));
    }

    private static Prize prize(String id, PrizeTab tab, String display, List<String> description, int cost,
                               PrizeType type, boolean enabled, List<Integer> costs) {
        return new Prize(id, tab, display, description, cost, new PluginConfig.Icon("", Material.SUGAR), type, enabled,
                null, 0, null, null, null, null, 0, 0, null, 0, "", List.of(), List.of(), null, null, costs);
    }

    /** A +1 Home priced 400 then 600, as shipped. */
    private static Prize homeSlot() {
        return prize("home_slot", PrizeTab.PERKS, "&a+1 Home",
                List.of("One more /home, on top of", "the homes you already have."), 0, PrizeType.HOME_SLOT, true,
                List.of(400, 600));
    }

    private static Prize nightVision() {
        return prize("night_vision", PrizeTab.BOOSTS, "&9Night Vision", List.of("See in the dark for 20 minutes."), 6,
                PrizeType.BOOST, true, List.of());
    }

    /** The shipped /hat perk: a console command and a permission, neither of which may leak. */
    private static Prize hatCommand() {
        return new Prize("hat_command", PrizeTab.PERKS, "&e/hat",
                List.of("Wear the block in your hand", "on your head with /hat."),
                300, new PluginConfig.Icon("eyJ0ZXh0dXJlcyI6e30=", Material.CARVED_PUMPKIN), PrizeType.COMMAND, true,
                null, 0, null, null, null, null, 0, 0, null, 0, "eyJ0ZXh0dXJlcyI6e30=",
                List.of("lp user %player% permission set essentials.hat true"), List.of("LuckPerms", "Essentials"),
                "essentials.hat", null, List.of());
    }

    private static Pack.PackDef starter() {
        return new Pack.PackDef("starter", "&aStarter Pack", 100, 50, 1, "",
                Map.of(Rarity.COMMON, 62.0, Rarity.UNCOMMON, 28.0, Rarity.RARE, 9.0, Rarity.EPIC, 1.0,
                        Rarity.LEGENDARY, 0.0), List.of());
    }

    private static Pack.PackDef premium() {
        return new Pack.PackDef("premium", "&dPremium Pack", 300, 0, 1, "",
                Map.of(Rarity.UNCOMMON, 45.0, Rarity.RARE, 38.0, Rarity.EPIC, 13.0, Rarity.LEGENDARY, 4.0), List.of());
    }

    private static AchievementDef achievement(String id, AchievementType type, String key, boolean enabled, int reward,
                                              String display) {
        return new AchievementDef(id, "Group", type, key, 1, enabled, reward, display);
    }

    private static AchievementDef firstSale() {
        return new AchievementDef("first_sale", "Getting Started", AchievementType.EVENT, null, 1, true, 10,
                "Sell something to Crate");
    }

    /** The shipped jackpot achievement: won by a game of chance, so never on the website. */
    private static AchievementDef jackpot() {
        return new AchievementDef("jackpot", "Arcade", AchievementType.EVENT, null, 1, true, 25,
                "Win the Scratch Ticket jackpot");
    }

    /** Ore Slots, a cabinet with a record, a boat course and a golf course. */
    private static ArcadeFeed filled(boolean showNames) {
        ArcadeFeed feed = new ArcadeFeed(showNames);
        Map<Integer, Double> rtp = new LinkedHashMap<>();
        rtp.put(1, 0.8976);
        rtp.put(2, 0.8976);
        rtp.put(5, 0.9);
        feed.chance("ore_slots", "&6Ore Slots", List.of(1, 2, 5), rtp, 50,
                List.of(new PayRow(null, "3 diamond", 40, 0.00242149, null, null),
                        new PayRow(null, "two the same", 2, 0.25, null, null)), null, null);
        feed.cabinet("creeper_sweeper", "Creeper Sweeper", "normal", "ms", true, 18_400L, STEVE);
        feed.course("river_run", "River Run", "boat", "medium", 61_234L, 1_789_990_000_000L, STEVE);
        feed.golf("golf_meadow", "Meadow Links", 9, 27, 24, 1_789_980_000_000L, STEVE);
        return feed;
    }

    private static String full(ArcadeFeed feed) {
        return feed.json(T, new ArcadeFeed.Featured("river_run", 1_790_035_200_000L),
                ArcadeFeed.scratch(shippedLotto(), 137),
                ArcadeFeed.prizes(List.of(nightVision(), homeSlot(), hatCommand(),
                        prize("trade_in", PrizeTab.MINIS, "&dTrade In Cards", List.of(), 0, PrizeType.TRADE_IN, true,
                                List.of()))),
                ArcadeFeed.packs(List.of(starter(), premium()), id -> null),
                ArcadeFeed.achievements(List.of(firstSale(), jackpot())));
    }

    /** The four table games as their owners write them. */
    private static ArcadeFeed tables() {
        ArcadeFeed feed = new ArcadeFeed(false);
        Map<Integer, Double> wheel = new LinkedHashMap<>();
        wheel.put(5, 0.9);
        wheel.put(10, 0.8958);
        feed.chance("wheel", "The Wheel", List.of(5, 10), wheel, 30,
                List.of(new PayRow(5, "17 tokens", 17, null, 3, 24), new PayRow(10, "your 10 back", 10, null, 21, 24)),
                null, Map.of());

        Map<String, Integer> five = new LinkedHashMap<>();
        five.put("win", 9);
        five.put("twentyOne", 11);
        five.put("doubleWin", 19);
        Map<String, Object> payouts = new LinkedHashMap<>();
        payouts.put("5", five);
        feed.chance("twenty_one", "Twenty-One", List.of(5), Map.of(5, 0.9012), 30, List.of(),
                "Beat the Arcade's hand without going over 21.", Map.of("payouts", payouts));

        Map<String, Object> hilo = new LinkedHashMap<>();
        hilo.put("maxMultiplier", 8);
        hilo.put("maxGuesses", 6);
        feed.chance("higher_lower", "Higher or Lower", List.of(10), Map.of(10, 0.905), 30, List.of(),
                "Guess higher or lower. Cash out any time.", hilo);

        feed.chance("coin_flip", "Coin Flip", List.of(5), Map.of(5, 0.9), 5,
                List.of(new PayRow(5, "win the flip", 9, 0.5, null, null)), null, null);
        return feed;
    }

    private static JsonObject root(String json) {
        return MarketFeedTest.strict(json).getAsJsonObject();
    }

    private static Map<String, JsonObject> games(String json) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        JsonArray games = root(json).getAsJsonArray("games");
        if (games != null) {
            for (JsonElement e : games) {
                out.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
            }
        }
        return out;
    }

    private static Set<String> keys(JsonElement e) {
        return e.getAsJsonObject().keySet();
    }

    // ---- the exact shape ----------------------------------------------------------------

    @Test
    void aFullFeedMatchesItsGoldenString() {
        assertEquals("{\"generatedAt\":1790000000000,\"games\":["
                + "{\"id\":\"scratch_ticket\",\"name\":\"Scratch Ticket\",\"kind\":\"chance\",\"stakes\":[10],"
                + "\"rtp\":77.6,\"rtpByStake\":{\"10\":77.6},\"paytable\":["
                + "{\"stake\":10,\"combo\":\"3 tokens\",\"pays\":3,\"chance\":0.4,\"oneIn\":3},"
                + "{\"stake\":10,\"combo\":\"8 tokens\",\"pays\":8,\"chance\":0.22,\"oneIn\":5},"
                + "{\"stake\":10,\"combo\":\"20 tokens\",\"pays\":20,\"chance\":0.09,\"oneIn\":11},"
                + "{\"stake\":10,\"combo\":\"50 tokens\",\"pays\":50,\"chance\":0.03,\"oneIn\":33},"
                + "{\"stake\":10,\"combo\":\"the jackpot\",\"pays\":137,\"chance\":0.01,\"oneIn\":100}]},"
                + "{\"id\":\"ore_slots\",\"name\":\"Ore Slots\",\"kind\":\"chance\",\"stakes\":[1,2,5],\"rtp\":89.7,"
                + "\"rtpByStake\":{\"1\":89.7,\"2\":89.7,\"5\":90.0},\"dailyLimit\":50,\"paytable\":["
                + "{\"combo\":\"3 diamond\",\"pays\":40,\"chance\":0.002421,\"oneIn\":413},"
                + "{\"combo\":\"two the same\",\"pays\":2,\"chance\":0.25,\"oneIn\":4}]},"
                + "{\"id\":\"creeper_sweeper\",\"name\":\"Creeper Sweeper\",\"kind\":\"cabinet\",\"board\":\"normal\","
                + "\"unit\":\"ms\",\"lowerIsBetter\":true,\"best\":18400},"
                + "{\"id\":\"river_run\",\"name\":\"River Run\",\"kind\":\"boat\",\"tier\":\"medium\","
                + "\"record\":{\"ms\":61234,\"at\":1789990000000}},"
                + "{\"id\":\"golf_meadow\",\"name\":\"Meadow Links\",\"kind\":\"golf\",\"holes\":9,\"par\":27,"
                + "\"record\":{\"strokes\":24,\"at\":1789980000000}}],"
                + "\"featured\":{\"game\":\"river_run\",\"until\":1790035200000},"
                + "\"jackpots\":[{\"game\":\"scratch_ticket\",\"tokens\":137}],"
                + "\"prizes\":["
                + "{\"id\":\"night_vision\",\"name\":\"Night Vision\",\"category\":\"boosts\",\"cost\":6,"
                + "\"description\":\"See in the dark for 20 minutes.\"},"
                + "{\"id\":\"home_slot\",\"name\":\"+1 Home\",\"category\":\"perks\",\"cost\":400,"
                + "\"description\":\"One more /home, on top of the homes you already have.\"},"
                + "{\"id\":\"hat_command\",\"name\":\"/hat\",\"category\":\"perks\",\"cost\":300,"
                + "\"description\":\"Wear the block in your hand on your head with /hat.\"}],"
                + "\"packs\":[{\"id\":\"starter\",\"name\":\"Starter Pack\",\"cost\":50,"
                + "\"odds\":{\"COMMON\":62,\"UNCOMMON\":28,\"RARE\":9,\"EPIC\":1}}],"
                + "\"achievements\":[{\"id\":\"first_sale\",\"name\":\"Sell something to Crate\","
                + "\"description\":\"Sell something to Crate\",\"tokens\":10}]}", full(filled(false)));
    }

    @Test
    void theTableGamesMatchTheirGoldenString() {
        assertEquals("{\"generatedAt\":1790000000000,\"games\":["
                + "{\"id\":\"wheel\",\"name\":\"The Wheel\",\"kind\":\"chance\",\"stakes\":[5,10],\"rtp\":89.5,"
                + "\"rtpByStake\":{\"5\":90.0,\"10\":89.5},\"dailyLimit\":30,\"paytable\":["
                + "{\"stake\":5,\"combo\":\"17 tokens\",\"pays\":17,\"spaces\":3,\"of\":24},"
                + "{\"stake\":10,\"combo\":\"your 10 back\",\"pays\":10,\"spaces\":21,\"of\":24}]},"
                + "{\"id\":\"twenty_one\",\"name\":\"Twenty-One\",\"kind\":\"chance\",\"stakes\":[5],\"rtp\":90.1,"
                + "\"rtpByStake\":{\"5\":90.1},\"dailyLimit\":30,"
                + "\"rules\":\"Beat the Arcade's hand without going over 21.\","
                + "\"payouts\":{\"5\":{\"win\":9,\"twentyOne\":11,\"doubleWin\":19}}},"
                + "{\"id\":\"higher_lower\",\"name\":\"Higher or Lower\",\"kind\":\"chance\",\"stakes\":[10],"
                + "\"rtp\":90.5,\"rtpByStake\":{\"10\":90.5},\"dailyLimit\":30,"
                + "\"rules\":\"Guess higher or lower. Cash out any time.\",\"maxMultiplier\":8,\"maxGuesses\":6},"
                + "{\"id\":\"coin_flip\",\"name\":\"Coin Flip\",\"kind\":\"chance\",\"stakes\":[5],\"rtp\":90.0,"
                + "\"rtpByStake\":{\"5\":90.0},\"dailyLimit\":5,\"paytable\":["
                + "{\"stake\":5,\"combo\":\"win the flip\",\"pays\":9,\"chance\":0.5,\"oneIn\":2}]}]}",
                tables().json(T, null, null, null, null, null),
                "no Arcade side passed: only games[] after generatedAt");
    }

    @Test
    void everyObjectCarriesExactlyItsDocumentedKeys() {
        String json = full(filled(false));
        JsonObject root = root(json);
        assertEquals(Set.of("generatedAt", "games", "featured", "jackpots", "prizes", "packs", "achievements"),
                root.keySet(), "the top level (no events yet)");

        Map<String, JsonObject> games = games(json);
        Set<String> chance = Set.of("id", "name", "kind", "stakes", "rtp", "rtpByStake", "dailyLimit", "paytable");
        assertEquals(chance, keys(games.get("ore_slots")), "a slots-style chance entry");
        assertEquals(Set.of("combo", "pays", "chance", "oneIn"),
                keys(games.get("ore_slots").getAsJsonArray("paytable").get(0)),
                "a row that pays the same multiple at every stake has no stake");
        assertEquals(Set.of("id", "name", "kind", "stakes", "rtp", "rtpByStake", "paytable"),
                keys(games.get("scratch_ticket")), "the Scratch Ticket has no daily limit");
        assertEquals(Set.of("stake", "combo", "pays", "chance", "oneIn"),
                keys(games.get("scratch_ticket").getAsJsonArray("paytable").get(0)), "its rows pay tokens, per stake");
        assertEquals(Set.of("1", "2", "5"), keys(games.get("ore_slots").get("rtpByStake")),
                "one RTP per published stake");
        assertEquals(Set.of("id", "name", "kind", "board", "unit", "lowerIsBetter", "best"),
                keys(games.get("creeper_sweeper")), "a cabinet with a record");
        assertEquals(Set.of("id", "name", "kind", "tier", "record"), keys(games.get("river_run")), "a course");
        assertEquals(Set.of("ms", "at"), keys(games.get("river_run").get("record")), "a course record");
        assertEquals(Set.of("id", "name", "kind", "holes", "par", "record"), keys(games.get("golf_meadow")), "golf");
        assertEquals(Set.of("strokes", "at"), keys(games.get("golf_meadow").get("record")), "a golf record");
        assertEquals(Set.of("game", "until"), keys(root.get("featured")), "featured");
        assertEquals(Set.of("game", "tokens"), keys(root.getAsJsonArray("jackpots").get(0)), "a jackpot");
        assertEquals(Set.of("id", "name", "category", "cost", "description"),
                keys(root.getAsJsonArray("prizes").get(0)),
                "a prize");
        assertEquals(Set.of("id", "name", "cost", "odds"), keys(root.getAsJsonArray("packs").get(0)), "a pack");
        assertEquals(Set.of("id", "name", "description", "tokens"), keys(root.getAsJsonArray("achievements").get(0)),
                "an achievement");

        Map<String, JsonObject> tables = games(tables().json(T, null, null, null, null, null));
        assertEquals(chance, keys(tables.get("wheel")), "the Wheel");
        assertEquals(Set.of("stake", "combo", "pays", "spaces", "of"),
                keys(tables.get("wheel").getAsJsonArray("paytable").get(0)), "a Wheel row counts spaces, no chance");
        assertEquals(Set.of("id", "name", "kind", "stakes", "rtp", "rtpByStake", "dailyLimit", "rules", "payouts"),
                keys(tables.get("twenty_one")), "Twenty-One: rules and payouts, no paytable");
        assertEquals(Set.of("win", "twentyOne", "doubleWin"),
                keys(tables.get("twenty_one").getAsJsonObject("payouts").get("5")), "Twenty-One's payouts per stake");
        assertEquals(Set.of("id", "name", "kind", "stakes", "rtp", "rtpByStake", "dailyLimit", "rules", "maxMultiplier",
                "maxGuesses"), keys(tables.get("higher_lower")), "Higher or Lower");
        assertEquals(Set.of("stake", "combo", "pays", "chance", "oneIn"),
                keys(tables.get("coin_flip").getAsJsonArray("paytable").get(0)), "a Coin Flip row");

        ArcadeFeed noBest = new ArcadeFeed(false);
        noBest.cabinet("snake", "Snake", "classic", "apples", false, null, null);
        noBest.course("cliffs", "Cliffs", "parkour", "", null, null, null);
        noBest.golf("golf_dunes", "Dunes", 9, 30, null, null, null);
        Map<String, JsonObject> bare = games(noBest.json(T, null, null, null, null, null));
        assertEquals(Set.of("id", "name", "kind", "board", "unit", "lowerIsBetter"), keys(bare.get("snake")),
                "no record yet: no best");
        assertFalse(bare.get("snake").get("lowerIsBetter").getAsBoolean(), "higher is better for apples");
        assertEquals(Set.of("id", "name", "kind"), keys(bare.get("cliffs")), "no tier, no record");
        assertEquals(Set.of("id", "name", "kind", "holes", "par"), keys(bare.get("golf_dunes")), "no golf record yet");
    }

    // ---- numbers ------------------------------------------------------------------------

    @Test
    void theHeadlineRtpIsTheLowestStakeFlooredToOneDecimal() {
        ArcadeFeed feed = new ArcadeFeed(false);
        Map<Integer, Double> rtp = new LinkedHashMap<>();
        rtp.put(5, 0.8976);
        rtp.put(10, 0.89699);
        rtp.put(20, 0.9049);
        feed.chance("g", "G", List.of(5, 10, 20), rtp, null, List.of(), null, null);
        JsonObject g = games(feed.json(T, null, null, null, null, null)).get("g");
        assertEquals("89.6", g.get("rtp").getAsString(), "the lowest stake (89.699%) floored, never rounded up");
        assertEquals(89.7, g.getAsJsonObject("rtpByStake").get("5").getAsDouble(), 1e-9, "89.76% floors to 89.7");
        assertEquals(90.4, g.getAsJsonObject("rtpByStake").get("20").getAsDouble(), 1e-9, "90.49% floors to 90.4");
        assertTrue(feed.json(T, null, null, null, null, null).contains("\"rtp\":89.6,"),
                "the headline is written with its one decimal");
    }

    @Test
    void anExactRtpIsNotFlooredBelowItself() {
        ArcadeFeed feed = new ArcadeFeed(false);
        feed.chance("g", "G", List.of(5, 10, 25), Map.of(5, 0.9, 10, 0.87, 25, 0.95), null, List.of(), null, null);
        JsonObject by = games(feed.json(T, null, null, null, null, null)).get("g").getAsJsonObject("rtpByStake");
        assertEquals("90.0", by.get("5").getAsString(), "0.9 is 90.0, not 89.9 from its binary fraction");
        assertEquals("87.0", by.get("10").getAsString(), "0.87 is 87.0");
        assertEquals("95.0", by.get("25").getAsString(), "0.95 is 95.0");
    }

    @Test
    void aOneInAMillionLineNeverReadsZero() {
        ArcadeFeed feed = new ArcadeFeed(false);
        feed.chance("g", "G", List.of(1), Map.of(1, 0.9), null,
                List.of(new PayRow(null, "3 wild", 250, 1e-6, null, null),
                        new PayRow(null, "3 gold", 20, 0.0000123456, null, null)), null, null);
        String json = feed.json(T, null, null, null, null, null);
        JsonArray rows = games(json).get("g").getAsJsonArray("paytable");
        assertEquals("0.000001", rows.get(0).getAsJsonObject().get("chance").getAsString(),
                "four decimals would publish this line as chance 0");
        assertEquals(1_000_000, rows.get(0).getAsJsonObject().get("oneIn").getAsLong(), "the screen's 1 in N");
        assertEquals("0.00001235", rows.get(1).getAsJsonObject().get("chance").getAsString(), "4 significant digits");
        assertEquals(FeedWriter.oneIn(0.0000123456), rows.get(1).getAsJsonObject().get("oneIn").getAsLong(),
                "oneIn is the one FeedWriter.oneIn the screens use");
        assertFalse(json.contains("E-") || json.contains("e-"), "plain notation, never an exponent: " + json);
    }

    // ---- what is left out ---------------------------------------------------------------

    @Test
    void anEmptyFeedIsJustItsTimestamp() {
        String expected = "{\"generatedAt\":1790000000000}";
        assertEquals(expected, new ArcadeFeed(false).json(T, null, null, null, null, null));
        assertEquals(expected, new ArcadeFeed(false).json(T, new ArcadeFeed.Featured("snake", T + 1), null,
                List.of(), List.of(), List.of()), "empty sections are absent, never []");
        List<ArcadeFeed.PrizeRow> nullRow = new ArrayList<>();
        nullRow.add(null);
        assertEquals(expected, new ArcadeFeed(false).json(T, null, null, nullRow, null, null), "null rows are skipped");
    }

    @Test
    void featuredIsPublishedOnlyForAGameTheFeedShows() {
        ArcadeFeed feed = filled(false);
        assertTrue(root(feed.json(T, new ArcadeFeed.Featured("RIVER_RUN", T + 5), null, null, null, null))
                .has("featured"), "a course that is in games[] (any case)");
        assertFalse(root(feed.json(T, new ArcadeFeed.Featured("snake", T + 5), null, null, null, null))
                .has("featured"), "a game with no entry would point the site at nothing");
        assertFalse(root(feed.json(T, new ArcadeFeed.Featured("river_run", 0), null, null, null, null))
                .has("featured"), "no end time, no featured game");
        assertFalse(root(feed.json(T, new ArcadeFeed.Featured(" ", T + 5), null, null, null, null))
                .has("featured"), "a blank pick");
    }

    @Test
    void aStakeWithoutItsRtpIsNotPublishedNorAreItsRows() {
        ArcadeFeed feed = new ArcadeFeed(false);
        Map<Integer, Double> rtp = new HashMap<>();
        rtp.put(5, 0.9);
        rtp.put(10, Double.NaN);
        rtp.put(99, 0.9); // a stake the solve dropped: not in stakes, so never published
        List<Integer> stakes = new ArrayList<>(List.of(5, 10, 20, 5, -1));
        stakes.add(null);
        feed.chance("wheel", "The Wheel", stakes, rtp, 30,
                List.of(new PayRow(5, "17 tokens", 17, null, 3, 24), new PayRow(10, "20 tokens", 20, null, 2, 24),
                        new PayRow(20, "40 tokens", 40, null, 1, 24)), null, null);
        JsonObject wheel = games(feed.json(T, null, null, null, null, null)).get("wheel");
        assertEquals("[5]", wheel.get("stakes").toString(), "only a stake with a real RTP, once");
        assertEquals(Set.of("5"), keys(wheel.get("rtpByStake")), "the stakes and rtpByStake agree");
        assertEquals(1, wheel.getAsJsonArray("paytable").size(), "rows of unpublished stakes go with them");
    }

    @Test
    void aGameOfChanceWithNoStakeLeftIsLeftOut() {
        ArcadeFeed feed = new ArcadeFeed(false);
        feed.chance("a", "A", List.of(), Map.of(), 10, List.of(new PayRow(null, "x", 2, 0.5, null, null)), "r", null);
        feed.chance("b", "B", List.of(5), Map.of(5, 0.0), 10, List.of(), null, null);
        feed.chance("c", "C", null, null, null, null, null, null);
        assertEquals("{\"generatedAt\":1790000000000}", feed.json(T, null, null, null, null, null),
                "the site never shows a game of chance without its odds");
    }

    @Test
    void aPaytableRowWithoutOddsIsLeftOut() {
        ArcadeFeed feed = new ArcadeFeed(false);
        List<PayRow> rows = new ArrayList<>(List.of(
                new PayRow(null, "no odds", 5, null, null, null),
                new PayRow(null, "zero", 5, 0.0, null, null),
                new PayRow(null, "impossible", 5, 1.5, null, null),
                new PayRow(null, "nan", 5, Double.NaN, null, null),
                new PayRow(5, "no ring", 5, null, 0, 24),
                new PayRow(5, "too many", 5, null, 25, 24),
                new PayRow(null, "negative", -1, 0.5, null, null),
                new PayRow(null, "kept", 3, 0.1, null, null)));
        rows.add(null);
        feed.chance("g", "G", List.of(5), Map.of(5, 0.9), null, rows, null, null);
        JsonArray paytable = games(feed.json(T, null, null, null, null, null)).get("g").getAsJsonArray("paytable");
        assertEquals(1, paytable.size(), "a payout is only ever shown with its odds: " + paytable);
        assertEquals("kept", paytable.get(0).getAsJsonObject().get("combo").getAsString());
    }

    @Test
    void optionalChanceKeysAreAbsentWhenEmpty() {
        ArcadeFeed feed = new ArcadeFeed(false);
        feed.chance("g", "G", List.of(5), Map.of(5, 0.9), null, List.of(), "  ", Map.of());
        feed.chance("h", "H", List.of(5), Map.of(5, 0.9), 0, null, "&r", null);
        Map<String, JsonObject> games = games(feed.json(T, null, null, null, null, null));
        Set<String> bare = Set.of("id", "name", "kind", "stakes", "rtp", "rtpByStake");
        assertEquals(bare, keys(games.get("g")), "no daily limit, no paytable, blank rules");
        assertEquals(bare, keys(games.get("h")), "a 0 limit is no limit; rules that are only a colour code are blank");
    }

    @Test
    void aSecondEntryWithAPublishedIdIsDropped() {
        ArcadeFeed feed = new ArcadeFeed(false);
        feed.course("scratch_ticket", "A course named like the ticket", "parkour", "easy", 1L, 1L, null);
        feed.cabinet("snake", "Snake", "classic", "apples", false, 12L, null);
        feed.cabinet("SNAKE", "Snake again", "classic", "apples", false, 99L, null);
        feed.cabinet(" ", "No id", "classic", "apples", false, 99L, null);
        Map<String, JsonObject> games = games(feed.json(T, null, ArcadeFeed.scratch(shippedLotto(), 60), null, null,
                null));
        assertEquals(List.of("scratch_ticket", "snake"), new ArrayList<>(games.keySet()),
                "the site keys on id: the first one wins, and the ticket always comes first");
        assertEquals("chance", games.get("scratch_ticket").get("kind").getAsString(), "the real Scratch Ticket");
        assertEquals(12, games.get("snake").get("best").getAsLong());
    }

    @Test
    void aGameThatThrowsHalfwayLeavesNothingBehind() {
        ArcadeFeed feed = new ArcadeFeed(false);
        feed.cabinet("snake", "Snake", "classic", "apples", false, 12L, null);
        int mark = feed.size();
        feed.course("a", "A", "parkour", "easy", 1L, 1L, null);
        feed.course("b", "B", "parkour", "easy", 1L, 1L, null);
        feed.truncate(mark);
        assertEquals(1, feed.rows().size(), "the half-written game's entries are gone");
        assertEquals(Set.of("snake"), games(feed.json(T, null, null, null, null, null)).keySet());
        feed.truncate(-3);
        assertEquals(0, feed.size(), "a negative mark empties it");
    }

    // ---- the Scratch Ticket -------------------------------------------------------------

    @Test
    void theScratchTicketEntryAppearsWheneverJackpotsDoes() {
        ArcadeFeed.Scratch withPot = ArcadeFeed.scratch(shippedLotto(), 137);
        JsonObject root = root(new ArcadeFeed(false).json(T, null, withPot, null, null, null));
        assertTrue(root.has("jackpots"), "the pot is published");
        assertTrue(games(root.toString()).containsKey("scratch_ticket"), "...and so are its odds");

        Lotto noJackpot = new Lotto(10, List.of(new LottoPayout(0, false, 50), new LottoPayout(15, false, 50)),
                new Jackpot(50, 1, 1000));
        JsonObject plain = root(new ArcadeFeed(false).json(T, null, ArcadeFeed.scratch(noJackpot, 137), null, null,
                null));
        assertFalse(plain.has("jackpots"), "no jackpot line, no pot");
        assertTrue(games(plain.toString()).containsKey("scratch_ticket"), "the ticket's odds are still published");

        // Every line pays nothing: no RTP to publish, so neither the entry nor the pot.
        Lotto broken = new Lotto(10, List.of(new LottoPayout(0, false, 50), new LottoPayout(0, true, 0)),
                new Jackpot(50, 1, 1000));
        ArcadeFeed.Scratch empty = ArcadeFeed.scratch(broken, 137);
        assertNotNull(empty, "a ticket with weights is set up");
        assertEquals("{\"generatedAt\":1790000000000}", new ArcadeFeed(false).json(T, null, empty, null, null, null),
                "never a pot without its odds");

        assertNull(ArcadeFeed.scratch(new Lotto(0, shippedLotto().payouts(), new Jackpot(50, 1, 1000)), 10),
                "free tickets are not a game");
        assertNull(ArcadeFeed.scratch(new Lotto(10, List.of(), new Jackpot(50, 1, 1000)), 10), "no payouts");
        assertNull(ArcadeFeed.scratch(null, 10), "no ticket");
    }

    @Test
    void theScratchTicketPublishesArcadeServicesSteadyStateNumber() {
        Lotto shipped = shippedLotto();
        ArcadeFeed.Scratch s = ArcadeFeed.scratch(shipped, 137);
        assertEquals(ArcadeService.rtp(shipped), s.rtp(), 0.0, "the same math /hcm arcade odds prints");
        assertEquals(10, s.ticketTokens());
        assertEquals(137, s.pot(), "the pot now, not its steady state");
        assertEquals("77.6", games(new ArcadeFeed(false).json(T, null, s, null, null, null)).get("scratch_ticket")
                .get("rtp").getAsString(), "(3·40 + 8·22 + 20·9 + 50·3 + 150·1) / 100 / 10 = 77.6%");
        double sum = 0;
        for (PayRow row : s.paytable()) {
            sum += row.chance();
            assertEquals(10, row.stake(), "rows pay tokens, so they carry the stake");
        }
        assertEquals(0.75, sum, 1e-12, "every line but the 25% no-win row");
    }

    @Test
    void scratchLinesPayingTheSameAreOneLine() {
        Lotto split = new Lotto(5, List.of(new LottoPayout(8, false, 10), new LottoPayout(1, false, 30),
                new LottoPayout(8, false, 10), new LottoPayout(0, false, 50)), new Jackpot(50, 1, 1000));
        ArcadeFeed.Scratch s = ArcadeFeed.scratch(split, 0);
        assertEquals(2, s.paytable().size(), "two 8-token rows are one line: " + s.paytable());
        assertEquals("8 tokens", s.paytable().get(0).combo());
        assertEquals(0.2, s.paytable().get(0).chance(), 1e-12, "their weights add up");
        assertEquals("1 token", s.paytable().get(1).combo(), "one token is singular");
        assertNull(s.pot(), "no jackpot line");
    }

    // ---- the Arcade's own sections ------------------------------------------------------

    @Test
    void prizesLeaveOutTheCountersActionsAndPublishTheHomesFirstPrice() {
        List<ArcadeFeed.PrizeRow> rows = ArcadeFeed.prizes(List.of(nightVision(), homeSlot(),
                prize("trade_in", PrizeTab.MINIS, "Trade In", List.of(), 0, PrizeType.TRADE_IN, true, List.of()),
                prize("quest_reroll", PrizeTab.BOOSTS, "Quest Reroll", List.of(), 10, PrizeType.QUEST_REROLL, true,
                        List.of()),
                prize("pity", PrizeTab.MINIS, "Rare Card", List.of(), 150, PrizeType.PITY, true, List.of()),
                prize("off", PrizeTab.BOOSTS, "Off", List.of(), 5, PrizeType.BOOST, false, List.of())));
        assertEquals(List.of("night_vision", "home_slot"), rows.stream().map(ArcadeFeed.PrizeRow::id).toList(),
                "Trade In, Quest Reroll and the Rare Card are actions; a disabled row never shows");
        assertEquals(400, rows.get(1).cost(), "the +1 Home's first price, not its 0 cost_tokens");
        assertEquals("perks", rows.get(1).category(), "the counter tab, lower case");
        assertEquals(List.of(), ArcadeFeed.prizes(null), "no counter, no prizes");
    }

    @Test
    void packsAreTheTokenPacksWithTheirConfiguredOddsAsPercents() {
        Pack.PackDef handoff = new Pack.PackDef("handoff", "Handoff", 0, 25, 1, "",
                Map.of(Rarity.COMMON, 70.0, Rarity.UNCOMMON, 20.0, Rarity.RARE, 8.0, Rarity.EPIC, 1.8,
                        Rarity.LEGENDARY, 0.2), List.of());
        Pack.PackDef pool = new Pack.PackDef("pool", "Pool", 0, 30, 2, "", Map.of(Rarity.COMMON, 100.0),
                List.of(new Pack.PackEntry("fox", 3), new Pack.PackEntry("cat", 1), new Pack.PackEntry("gone", 5),
                        new Pack.PackEntry("owl", 0)));
        Pack.PackDef empty = new Pack.PackDef("empty", "Empty", 0, 30, 1, "", Map.of(), List.of());
        Pack.PackDef noCards = new Pack.PackDef("none", "None", 0, 30, 0, "", Map.of(Rarity.RARE, 1.0), List.of());
        Map<String, Rarity> catalog = Map.of("fox", Rarity.RARE, "cat", Rarity.COMMON, "owl", Rarity.LEGENDARY);

        List<ArcadeFeed.PackRow> rows = ArcadeFeed.packs(List.of(starter(), premium(), handoff, pool, empty, noCards),
                catalog::get);
        assertEquals(List.of("starter", "handoff", "pool"), rows.stream().map(ArcadeFeed.PackRow::id).toList(),
                "the dollars-only pack, a pack with nothing to roll and a pack of no Cards are not published");

        JsonObject root = root(new ArcadeFeed(false).json(T, null, null, null, rows, null));
        JsonArray packs = root.getAsJsonArray("packs");
        assertEquals("{\"COMMON\":70,\"UNCOMMON\":20,\"RARE\":8,\"EPIC\":1.8,\"LEGENDARY\":0.2}",
                packs.get(1).getAsJsonObject().get("odds").toString(), "percents, as the handoff's example");
        assertEquals("{\"COMMON\":25,\"RARE\":75}", packs.get(2).getAsJsonObject().get("odds").toString(),
                "a pool pack by its Minis' rarities; an unknown or zero-weight Mini doesn't count");
        assertEquals(50, packs.get(0).getAsJsonObject().get("cost").getAsInt(), "the price in tokens");
        assertFalse(packs.get(0).getAsJsonObject().getAsJsonObject("odds").has("LEGENDARY"),
                "a rarity at 0 can't come out, so it isn't listed");
    }

    @Test
    void achievementsNeverAdvertiseAChanceWin() {
        List<ArcadeFeed.AchievementRow> rows = ArcadeFeed.achievements(List.of(firstSale(), jackpot(),
                achievement("jackpots_3", AchievementType.COUNTER, "jackpots", true, 30, "Win 3 jackpots"),
                achievement("wild_5", AchievementType.COUNTER, "wild_finds", true, 50, "&aCatch 5 wild Minis"),
                achievement("off", AchievementType.EVENT, null, false, 5, "Disabled"),
                achievement("first_sale", AchievementType.EVENT, null, true, 99, "A duplicate id")));
        assertEquals(List.of("first_sale", "wild_5"), rows.stream().map(ArcadeFeed.AchievementRow::id).toList(),
                "no jackpot, no jackpot counter, no disabled row, one row per id");
        assertTrue(ArcadeFeed.chanceWin(jackpot()), "the shipped jackpot achievement is a chance win");
        assertFalse(ArcadeFeed.chanceWin(firstSale()), "selling to Crate is not");

        String json = new ArcadeFeed(false).json(T, null, null, null, null, rows);
        assertFalse(json.toLowerCase().contains("jackpot"), "not a word of it: " + json);
        JsonObject wild = root(json).getAsJsonArray("achievements").get(1).getAsJsonObject();
        assertEquals("Catch 5 wild Minis", wild.get("name").getAsString(), "colour codes go");
        assertEquals(50, wild.get("tokens").getAsInt());
    }

    // ---- extra keys ---------------------------------------------------------------------

    @Test
    void extraKeysNeverReplaceAStandardKeyAndKeepAStableOrder() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("zeta", 1);
        ordered.put("rtp", 99.9);
        ordered.put("name", "Hijacked");
        ordered.put("alpha", 0.5);
        ordered.put("tiny", 0.0000123);
        ordered.put("flag", true);
        ordered.put("broken", Double.NaN);
        ordered.put("list", List.of(3, "&6gold", Rarity.RARE));
        ordered.put("byStake", Map.of("20", 3, "5", 1, "10", 2, "x", 0));
        ordered.put("skipped", null);
        ArcadeFeed feed = new ArcadeFeed(false);
        feed.chance("g", "G", List.of(5), Map.of(5, 0.9), null, List.of(), null, ordered);
        String json = feed.json(T, null, null, null, null, null);
        assertTrue(json.endsWith("\"rtpByStake\":{\"5\":90.0},\"zeta\":1,\"alpha\":0.5,\"tiny\":0.0000123,"
                        + "\"flag\":true,\"list\":[3,\"gold\",\"RARE\"],"
                        + "\"byStake\":{\"5\":1,\"10\":2,\"20\":3,\"x\":0}}]}"),
                "a LinkedHashMap keeps its order, an unordered map is sorted (numbers first, numerically); "
                        + "rtp and name stay the writer's; NaN and null are left out: " + json);
        JsonObject g = games(json).get("g");
        assertEquals("G", g.get("name").getAsString(), "an extra key never replaces a standard one");
        assertEquals("90.0", g.get("rtp").getAsString());
    }

    // ---- privacy ------------------------------------------------------------------------

    /** A writer given every kind of player data a careless game could pass. */
    private static ArcadeFeed careless(boolean showNames) {
        ArcadeFeed feed = filled(showNames);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("uuid", STEVE_UUID);
        extra.put("Player", STEVE);
        extra.put("winner", STEVE);
        extra.put("balance", 987_654);
        extra.put("owner", STEVE);
        extra.put("holders", List.of(STEVE));
        extra.put("someone", UUID.fromString(STEVE_UUID));
        extra.put("sneaky", "held by " + STEVE_UUID);
        extra.put("nested", Map.of("player", STEVE, "ok", 1));
        feed.chance("coin_flip", "Coin Flip", List.of(5), Map.of(5, 0.9), 5,
                List.of(new PayRow(5, "win the flip", 9, 0.5, null, null)), null, extra);
        feed.cabinet("simon_says", "Simon Says", "classic", "points", false, 14L, STEVE_UUID);
        return feed;
    }

    @Test
    void noPlayerDataEverReachesTheFeed() {
        String json = full(careless(false));
        MarketFeedTest.strict(json);
        for (String forbidden : List.of("uuid", "player", "winner", "balance", "owner", "holder", "commands",
                "command", "permission", "texture", "requires", "limit")) {
            assertFalse(json.toLowerCase().contains("\"" + forbidden),
                    "the feed must never carry a \"" + forbidden + "\" key: " + json);
        }
        for (String value : List.of(STEVE, STEVE_UUID, "987654", "lp user", "essentials.hat", "eyJ0ZXh0dXJlcyI6e30=",
                "%player%")) {
            assertFalse(json.contains(value), "the feed must never carry \"" + value + "\": " + json);
        }
        JsonObject coinFlip = games(json).get("coin_flip");
        assertEquals("{\"ok\":1}", coinFlip.get("nested").toString(), "plain data in the same map still goes out");
        assertFalse(coinFlip.has("someone"), "an object that isn't plain data is never written");
    }

    @Test
    void recordsCarryTheirHolderOnlyWhileNamesAreSwitchedOn() {
        assertFalse(new ArcadeFeed(false).showNames(), "the games are told names are off");
        assertTrue(new ArcadeFeed(true).showNames(), "...or on");

        String off = full(filled(false));
        assertFalse(off.contains("holder") || off.contains(STEVE), "shipped off: scores and times only");

        String on = full(careless(true));
        Map<String, JsonObject> games = games(on);
        assertEquals(STEVE, games.get("creeper_sweeper").get("holder").getAsString(), "a cabinet's best");
        assertEquals(STEVE, games.get("river_run").getAsJsonObject("record").get("holder").getAsString(), "a course");
        assertEquals(STEVE, games.get("golf_meadow").getAsJsonObject("record").get("holder").getAsString(), "golf");
        assertFalse(games.get("simon_says").has("holder"), "a UUID is never a name");
        assertFalse(games.get("coin_flip").has("winner") || on.contains(STEVE_UUID),
                "names on still means no winners and no UUIDs");

        ArcadeFeed noRecord = new ArcadeFeed(true);
        noRecord.cabinet("snake", "Snake", "classic", "apples", false, null, STEVE);
        assertFalse(noRecord.json(T, null, null, null, null, null).contains(STEVE), "no record, nobody to name");
    }

    @Test
    void colourCodesAreStripped() {
        ArcadeFeed feed = new ArcadeFeed(true);
        feed.chance("g", "&6&lOre §bSlots", List.of(1), Map.of(1, 0.9), null,
                List.of(new PayRow(null, "&b3 diamond", 40, 0.01, null, null)), "&7Match &lthree&r.",
                Map.of("note", "&eShiny"));
        feed.cabinet("c", "&aSnake", "&7classic", "&7apples", false, 3L, "&cSteve");
        feed.course("r", "&9River", "&7BOAT", "&7Medium", 5L, 6L, null);
        String json = feed.json(T, null, null,
                List.of(new ArcadeFeed.PrizeRow("p", "&9Night", "boosts", 6, "&7Dark")),
                List.of(new ArcadeFeed.PackRow("k", "&aStarter", 5, Map.of(Rarity.COMMON, 1.0))),
                List.of(new ArcadeFeed.AchievementRow("a", "&6Win", "&7Do it", 1)));
        assertFalse(json.contains("&") || json.contains("§"), "no colour code survives: " + json);
        Map<String, JsonObject> games = games(json);
        assertEquals("Ore Slots", games.get("g").get("name").getAsString());
        assertEquals("Match three.", games.get("g").get("rules").getAsString());
        assertEquals("boat", games.get("r").get("kind").getAsString(), "a course kind reads lower case");
        assertEquals("medium", games.get("r").get("tier").getAsString());
        assertEquals("Steve", games.get("c").get("holder").getAsString());
    }
}
