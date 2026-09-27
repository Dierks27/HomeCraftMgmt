package com.dierks.homecraft.market.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live market's headlines (spec §6.5).
 *
 * <p>Pinned here: the shipped lists hold 12/12/6/6/3 templates plus one line each for real
 * moves; every template carries its placeholder, fits 60 characters with the shipped items'
 * names and 72 with a 24-character one, uses no banned word, no shelf word (except
 * {@code wanted}, which is only sent while Crate really has none) and nothing above U+FFFF.
 * Plurals follow the same-plural list and plain English (Berry → Berries, Potato → Potatoes,
 * Raw Iron and Cooked Beef unchanged), and the banned/shelf checks catch the usual inflections
 * and hyphen forms (crashed, fighting, restocked, sold-out). The time words
 * fall in the documented buckets. A template is not reused until four others from its list
 * have been, and a broken template is reported and dropped, falling back to the shipped list
 * when nothing is left.
 */
class HeadlinesTest {

    private static final Set<String> SAME = Headlines.SAME_PLURAL;
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;

    private static Map<String, List<String>> shippedByList() {
        return Headlines.shippedLists();
    }

    private static Map<String, String> vars(String item, String name, String real) {
        Map<String, String> v = new HashMap<>();
        v.put("item", item);
        v.put("Name", name);
        v.put("real", real);
        return v;
    }

    // ---- the shipped lists --------------------------------------------------------------

    @Test
    void theShippedListsHaveTheirCounts() {
        assertEquals(12, Headlines.UP.size());
        assertEquals(12, Headlines.DOWN.size());
        assertEquals(6, Headlines.HOT.size());
        assertEquals(6, Headlines.DEAL.size());
        assertEquals(3, Headlines.WANTED.size());
        assertEquals(List.of(Headlines.REAL_UP), Headlines.shipped("real_up"));
        assertEquals(List.of(Headlines.REAL_DOWN), Headlines.shipped("real_down"));
        assertEquals(Headlines.LISTS, List.copyOf(shippedByList().keySet()));
        assertEquals(List.of(), Headlines.shipped("nope"));
    }

    @Test
    void everyShippedTemplatePassesValidationAndHasItsPlaceholder() {
        shippedByList().forEach((list, tpls) -> {
            assertEquals(List.of(), Headlines.problems(list, tpls), list);
            for (String tpl : tpls) {
                if (list.startsWith("real")) {
                    assertTrue(tpl.contains("{real}"), tpl);
                } else {
                    assertTrue(tpl.contains("{item}") || tpl.contains("{Name}"), tpl);
                }
            }
        });
    }

    @Test
    void shippedTemplatesFitSixtyWithTheShippedNamesAndSeventyTwoWithALongOne() {
        List<Map<String, String>> names = List.of(
                vars("Iron Ingots", "Iron Ingot", "metal"),
                vars("Cobblestone", "Cobblestone", "gold"),
                vars("Gold Ingots", "Gold Ingot", "wheat"));
        String long24 = "Enchanted Golden Apples!".substring(0, 24);
        assertEquals(24, long24.length());
        shippedByList().forEach((list, tpls) -> {
            for (String tpl : tpls) {
                for (Map<String, String> v : names) {
                    String out = Headlines.render(tpl, v);
                    assertTrue(out.length() <= 60, out.length() + ": " + out);
                    assertTrue(!out.contains("{"), out);
                }
                String worst = Headlines.render(tpl, vars(long24, long24, long24));
                assertTrue(worst.length() <= Headlines.MAX_RENDERED, worst.length() + ": " + worst);
            }
        });
    }

    @Test
    void noShippedTemplateUsesABannedWordAShelfWordOrAWideCharacter() {
        shippedByList().forEach((list, tpls) -> {
            for (String tpl : tpls) {
                assertNull(Headlines.textProblem(tpl, !list.equals("wanted")), list + ": " + tpl);
                assertTrue(tpl.codePoints().allMatch(cp -> cp <= 0xFFFF), tpl);
                String lower = tpl.toLowerCase();
                for (String w : Headlines.BANNED) {
                    assertTrue(!lower.matches(".*\\b" + w + "\\b.*"), w + " in " + tpl);
                }
                if (!list.equals("wanted")) {
                    for (String w : Headlines.SHELF_WORDS) {
                        assertTrue(!lower.matches(".*\\b" + w + "\\b.*"), w + " in " + tpl);
                    }
                }
            }
        });
        for (Season s : SeasonCalendar.SHIPPED) {
            assertNull(Headlines.textProblem(s.headline(), true), s.headline());
            assertNull(Headlines.textProblem(s.name(), true), s.name());
        }
    }

    // ---- validation ---------------------------------------------------------------------

    @Test
    void invalidTemplatesAreReportedAndDropped() {
        String wide = new String(Character.toChars(0x1F4B0));
        List<String> tpls = new ArrayList<>();
        tpls.add("Everybody wants {item}!");                                   // fine
        tpls.add("Everybody wants this!");                                     // no placeholder
        tpls.add("The mayor of the biggest town in the whole land wants {item} today!"); // 84 with 24
        tpls.add("A war broke out over {item}!");                              // banned
        tpls.add("Oh no, a STORMS of {item}!");                                // banned, plural, any case
        tpls.add("Crate's shelf is full of {item}!");                         // shelf word
        tpls.add("{item} are out  of stock!");                                 // shelf phrase
        tpls.add("Crate has stocked up on {item}!");                           // stock + ed
        tpls.add("Money " + wide + " for {item}!");                            // above U+FFFF
        tpls.add("{item} and {thing}!");                                       // unknown placeholder
        tpls.add("   ");                                                       // blank
        tpls.add(null);
        tpls.add("{Name} is on sale at Crate!");                               // fine

        List<String> problems = Headlines.problems("up", tpls);
        assertEquals(11, problems.size(), String.join("\n", problems));
        assertTrue(problems.get(0).startsWith("market.sim.headlines.up #2 "), problems.get(0));
        assertTrue(problems.get(1).contains("with a 24-character name"), problems.get(1));
        assertTrue(problems.get(2).contains("\"war\""), problems.get(2));
        assertTrue(problems.get(7).contains("U+1F4B0"), problems.get(7));
        assertTrue(problems.get(8).contains("{thing}"), problems.get(8));
        assertEquals(List.of("Everybody wants {item}!", "{Name} is on sale at Crate!"), Headlines.valid("up", tpls));

        // "wanted" may talk about the shelf: it is only sent while Crate really has none.
        assertNull(Headlines.problem("wanted", "Crate is out of {item}! Sell some!"));
        assertNotNull(Headlines.problem("wanted", "A war needs {item}!"));

        // Whole words only: these contain a banned word inside a longer one.
        assertNull(Headlines.problem("up", "Fireworks and wares need {item}!"));
        assertNull(Headlines.problem("up", "Everyone is stockpiling {item}!"));

        // The ways people really write these words are caught too, not just the dictionary form.
        for (String bad : List.of("Prices crashed! Grab {item}!", "Miners are fighting over {item}!",
                "The {item} market is burning hot!", "Traders killed it with {item}!",
                "Oh no, {item} is dying out!", "Everyone is scared to sell {item}!", "Someone stole {item}!",
                "Two thieves want {item}!", "The warring towns need {item}!", "{item} fighters are here!",
                "A stormy day for {item}!", "Deadly good {item}!", "{item}-killer deals!")) {
            String why = Headlines.problem("up", bad);
            assertNotNull(why, bad);
            assertTrue(why.startsWith("uses the word"), bad + ": " + why);
        }
        // ...and so are shelf claims written as one word or with a hyphen.
        for (String shelf : List.of("Crate just restocked {item}!", "Crate has overstocked {item}!",
                "Crate is restocking {item}!", "Everyone wants the sold-out {item}!",
                "{item} are selling out fast!", "Crate is out-of {item}!", "Crate's shelves are full of {item}")) {
            String why = Headlines.problem("up", shelf);
            assertNotNull(why, shelf);
            assertTrue(why.startsWith("talks about Crate's shelf"), shelf + ": " + why);
        }
        // Harmless words that start the same way stay allowed.
        for (String fine : List.of("Wares and gunpowder need {item}!", "A scarecrow wants {item}!",
                "The warden is warm and wants {item}!", "Farmers with livestock want {item}!",
                "Bookshelves need {item}!", "Warped forests need {item}!")) {
            assertNull(Headlines.problem("up", fine), fine);
        }

        // Real lines need {real}, and may name the item too.
        assertNotNull(Headlines.problem("real_up", "In the real world, {item} went up!"));
        assertNull(Headlines.problem("real_up", "{real} went up, so {Name} did too!"));
        assertNotNull(Headlines.problem("up", "In the real world, {real} and {item} went up!"));
    }

    @Test
    void anEmptyOrAllBadListFallsBackToTheShippedOne() {
        List<String> warnings = new ArrayList<>();
        assertEquals(Headlines.HOT, Headlines.validOrShipped("hot", List.of("No placeholder!", "Fire {item}!"),
                warnings::add));
        assertEquals(3, warnings.size(), warnings.toString());

        warnings.clear();
        assertEquals(Headlines.DEAL, Headlines.validOrShipped("deal", List.of(), warnings::add));
        assertEquals(List.of(), warnings);
        assertEquals(List.of(Headlines.REAL_DOWN), Headlines.validOrShipped("real_down", null, null));

        List<String> custom = List.of("Grab {item} now!", "No placeholder");
        assertEquals(List.of("Grab {item} now!"), Headlines.validOrShipped("deal", custom, s -> { }));
    }

    // ---- names --------------------------------------------------------------------------

    @Test
    void namesAndPlurals() {
        assertEquals("Iron Ingot", Headlines.name("IRON_INGOT"));
        assertEquals("Gold Ingot", Headlines.name("&6Gold §lIngot "));
        assertEquals("Oak Log", Headlines.name("Oak Log"));
        assertEquals("", Headlines.name(null));

        assertEquals("Diamonds", Headlines.plural("diamond", "Diamond", null, SAME));
        assertEquals("Wheat", Headlines.plural("wheat", "Wheat", null, SAME));
        assertEquals("Glass", Headlines.plural("glass", "Glass", null, SAME));
        assertEquals("Berries", Headlines.plural("berry", "Berry", null, SAME));
        assertEquals("Iron Ingots", Headlines.plural("iron_ingot", "IRON_INGOT", null, SAME));
        assertEquals("Cobblestone", Headlines.plural("cobblestone", "COBBLESTONE", null, SAME));
        assertEquals("Oak Logs", Headlines.plural("oak_log", "Oak Log", "", SAME));

        assertEquals("Shiny Rocks", Headlines.plural("diamond", "Diamond", " Shiny Rocks ", SAME), "news_name wins");
        assertEquals("Red Sand", Headlines.plural("custom_sand", "Red Sand", null, SAME), "matched by name too");
        assertEquals("Oak Planks", Headlines.plural("oak_planks", "Oak Planks", null, Set.of()));
        assertEquals("Torches", Headlines.plural("torch", "Torch", null, null));
        assertEquals("Boxes", Headlines.plural("box", "Box", null, null));
        assertEquals("Keys", Headlines.plural("key", "Key", null, null), "a vowel before y just adds s");
        assertEquals("Hearts of the Sea", Headlines.plural("heart_of_the_sea", "HEART_OF_THE_SEA", null, SAME));
        assertEquals("Bottles o' Enchanting", Headlines.plural("xp", "Bottle o' Enchanting", null, SAME));
        assertTrue(SAME.contains("cobbled_deepslate"));
        assertEquals(41, SAME.size());
    }

    @Test
    void commonFarmAndOreItemsGetTheirRealPlurals() {
        // Every shipped catalog item, then the common farm, food and ore commodities. These hold
        // with no same_plural at all (an older config's list, or an admin's own, may lack them).
        Map<String, String> want = new LinkedHashMap<>();
        want.put("COBBLESTONE", "Cobblestone");
        want.put("OAK_LOG", "Oak Logs");
        want.put("WHEAT", "Wheat");
        want.put("IRON_INGOT", "Iron Ingots");
        want.put("GOLD_INGOT", "Gold Ingots");
        want.put("DIAMOND", "Diamonds");
        want.put("CARROT", "Carrots");
        want.put("POTATO", "Potatoes");
        want.put("BAKED_POTATO", "Baked Potatoes");
        want.put("BEETROOT", "Beetroots");
        want.put("PUMPKIN", "Pumpkins");
        want.put("MELON_SLICE", "Melon Slices");
        want.put("APPLE", "Apples");
        want.put("EGG", "Eggs");
        want.put("BREAD", "Bread");
        want.put("BEEF", "Beef");
        want.put("COOKED_BEEF", "Cooked Beef");
        want.put("MUTTON", "Mutton");
        want.put("CHICKEN", "Chicken");
        want.put("PORKCHOP", "Porkchops");
        want.put("COD", "Cod");
        want.put("SALMON", "Salmon");
        want.put("TROPICAL_FISH", "Tropical Fish");
        want.put("ROTTEN_FLESH", "Rotten Flesh");
        want.put("SUGAR_CANE", "Sugar Cane");
        want.put("DRIED_KELP", "Dried Kelp");
        want.put("MUSHROOM_STEW", "Mushroom Stew");
        want.put("RAW_IRON", "Raw Iron");
        want.put("RAW_GOLD", "Raw Gold");
        want.put("RAW_COPPER", "Raw Copper");
        want.put("IRON_ORE", "Iron Ore");
        want.put("COAL", "Coal");
        want.put("COPPER_INGOT", "Copper Ingots");
        want.put("IRON_NUGGET", "Iron Nuggets");
        want.put("EMERALD", "Emeralds");
        want.put("LAPIS_LAZULI", "Lapis Lazuli");
        want.put("QUARTZ", "Quartz");
        want.put("REDSTONE", "Redstone");
        want.put("GLOWSTONE_DUST", "Glowstone Dust");
        want.put("GUNPOWDER", "Gunpowder");
        want.put("BONE_MEAL", "Bone Meal");
        want.put("STRING", "String");
        want.put("LEATHER", "Leather");
        want.put("WHITE_WOOL", "White Wool");
        want.put("PACKED_ICE", "Packed Ice");
        want.put("SAND", "Sand");
        want.put("FLINT", "Flint");
        want.put("PAPER", "Paper");
        want.put("SWEET_BERRIES", "Sweet Berries");
        want.put("RABBIT_FOOT", "Rabbit Feet");
        want.put("TORCH", "Torches");
        want.put("TNT", "TNT");
        want.put("TNT_MINECART", "TNT Minecarts");
        List<String> wrong = new ArrayList<>();
        want.forEach((raw, plural) -> {
            String id = raw.toLowerCase();
            List<Set<String>> lists = Arrays.asList(SAME, Set.of(), null);
            List<String> labels = List.of("shipped", "empty", "none");
            for (int i = 0; i < lists.size(); i++) {
                String got = Headlines.plural(id, raw, null, lists.get(i));
                if (!plural.equals(got)) {
                    wrong.add(raw + " -> " + got + " (want " + plural + ", same_plural " + labels.get(i) + ")");
                }
            }
        });
        assertEquals(List.of(), wrong);

        assertEquals("TNT", Headlines.name("TNT"));
        assertEquals("TNT Minecart", Headlines.name("TNT_MINECART"));
        assertEquals("Potatoes", Headlines.plural("x", "Potato", null, null));
        assertEquals("potatoes", Headlines.plural("x", "potato", null, null), "the case is kept");
        assertEquals("Big POTATOES", Headlines.plural("x", "Big POTATO", null, null));
        assertEquals("Spuds", Headlines.plural("potato", "Potato", "Spuds", SAME), "news_name still wins");
    }

    // ---- picking and rendering ----------------------------------------------------------

    @Test
    void aTemplateIsNotReusedWithinFourPicks() {
        Random rnd = new Random(42);
        for (int size : new int[]{12, 6}) {
            List<Integer> memory = List.of();
            List<Integer> history = new ArrayList<>();
            int[] counts = new int[size];
            for (int i = 0; i < 6000; i++) {
                int p = Headlines.pick(size, memory, rnd.nextDouble());
                assertTrue(p >= 0 && p < size);
                for (int back = 1; back <= 4 && back <= history.size(); back++) {
                    assertNotEquals(history.get(history.size() - back), p, "repeat within 4 picks");
                }
                history.add(p);
                counts[p]++;
                memory = Headlines.remember(memory, p);
                memory = Headlines.parseRecent(Headlines.formatRecent(memory)); // survives meta
            }
            for (int c : counts) {
                assertEquals(6000.0 / size, c, 6000.0 / size * 0.15, "every template gets its turn");
            }
        }
    }

    @Test
    void aShortListKeepsAChoice() {
        // wanted has 3: the last 2 are excluded, so the one left is picked every time.
        assertEquals(1, Headlines.pick(3, List.of(0, 2), 0.99));
        assertEquals(1, Headlines.pick(3, List.of(1, 0, 2), 0.0), "only the newest 2 count");
        assertEquals(0, Headlines.pick(1, List.of(0), 0.5));
        assertEquals(-1, Headlines.pick(0, List.of(), 0.5));
        assertEquals(11, Headlines.pick(12, null, 0.9999999));
        assertEquals(0, Headlines.pick(12, List.of(99, -1), 0.0), "junk memory is ignored");

        assertEquals(List.of(3, 4, 5, 6), Headlines.remember(List.of(2, 3, 4, 5), 6));
        assertEquals(List.of(2, 4, 5, 3), Headlines.remember(List.of(2, 3, 4, 5), 3));
        assertEquals("2,4,5,3", Headlines.formatRecent(List.of(2, 4, 5, 3)));
        assertEquals(List.of(4, 5, 3, 7), Headlines.parseRecent("1, 2,x,4,5,3,7"));
        assertEquals(List.of(), Headlines.parseRecent(null));
    }

    @Test
    void renderFillsKnownPlaceholdersOnce() {
        Map<String, String> v = vars("{Name}s", "Wheat", "gold");
        assertEquals("Everyone wants {Name}s! (Wheat)", Headlines.render("Everyone wants {item}! ({Name})", v),
                "a value is never expanded again");
        assertEquals("Hi {nobody} gold", Headlines.render("Hi {nobody} {real}", v));
        assertEquals("", Headlines.render(null, v));
        assertEquals("plain", Headlines.render("plain", null));
        Map<String, String> nulls = new HashMap<>();
        nulls.put("item", null);
        assertEquals("Buy  now", Headlines.render("Buy {item} now", nulls));
    }

    // ---- words for time -----------------------------------------------------------------

    @Test
    void timeLeftInWords() {
        assertEquals("about 2 days", Headlines.left(50 * HOUR));
        assertEquals("about 2 days", Headlines.left(36 * HOUR), "1.5 days rounds up");
        assertEquals("about 3 days", Headlines.left(70 * HOUR));
        assertEquals("about a day", Headlines.left(30 * HOUR));
        assertEquals("about a day", Headlines.left(18 * HOUR));
        assertEquals("less than a day", Headlines.left(10 * HOUR));
        assertEquals("less than a day", Headlines.left(6 * HOUR));
        assertEquals("a few hours", Headlines.left(2 * HOUR));
        assertEquals("a few hours", Headlines.left(-5));
    }

    @Test
    void howLongAgoInWords() {
        assertEquals("just now", Headlines.ago(0));
        assertEquals("just now", Headlines.ago(119_999));
        assertEquals("2m ago", Headlines.ago(2 * MIN));
        assertEquals("59m ago", Headlines.ago(59 * MIN + 59_000));
        assertEquals("3h ago", Headlines.ago(3 * HOUR + 20 * MIN));
        assertEquals("23h ago", Headlines.ago(24 * HOUR - 1));
        assertEquals("yesterday", Headlines.ago(30 * HOUR));
        assertEquals("2 days ago", Headlines.ago(48 * HOUR));
        assertEquals("5 days ago", Headlines.ago(5 * 24 * HOUR + HOUR));
        assertEquals("just now", Headlines.ago(-HOUR));
    }
}
