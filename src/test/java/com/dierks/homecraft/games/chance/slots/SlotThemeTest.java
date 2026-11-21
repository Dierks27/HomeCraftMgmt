package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.games.chance.slots.SlotsEngine.LineOdds;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.Spin;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slot machines' themes (the owner: "several different 'Types' of slot machine").
 *
 * <p>Pinned here: the ores' theme is Ore Slots word for word (its names and keys are the engine's own enums', its
 * pictures the ones its screen has always shown), so sharing the machine changed nothing it says; every theme names
 * all seven roles and all seven lines, with a picture each and no key twice; a theme's keys reach the engine under
 * the engine's own names; and a themed machine's lines, results and website rows read in its own words.
 */
class SlotThemeTest {

    @Test
    void theOresThemeIsOreSlotsWordForWord() {
        SlotTheme ore = SlotTheme.ORE;
        for (Symbol s : Symbol.values()) {
            assertEquals(s.key(), ore.key(s), s + "'s key is the engine's");
            assertEquals(s.label(), ore.label(s), s + "'s name is the engine's");
        }
        for (Line l : Line.values()) {
            assertEquals(l.key(), ore.key(l), l + "'s key");
            assertEquals(l.label(), ore.label(l), l + "'s name");
            assertEquals(l.combo(), ore.combo(l), l + " on the website");
        }
        assertEquals(Material.COAL, ore.icon(Symbol.COAL));
        assertEquals(Material.COPPER_INGOT, ore.icon(Symbol.COPPER));
        assertEquals(Material.IRON_INGOT, ore.icon(Symbol.IRON));
        assertEquals(Material.GOLD_INGOT, ore.icon(Symbol.GOLD));
        assertEquals(Material.DIAMOND, ore.icon(Symbol.DIAMOND));
        assertEquals(Material.NETHER_STAR, ore.icon(Symbol.WILD));
        assertEquals(Material.COBBLESTONE, ore.icon(Symbol.STONE));
        assertEquals(Material.IRON_NUGGET, ore.pairIcon(), "two the same");
        assertEquals(Material.DIAMOND_ORE, ore.tileIcon(), "its tile on the Games screen");
        assertEquals(OreSlotsSettings.KEYS, SlotSettings.keys(ore, false), "and its config keys, in order");
        OreSlotsSettings shipped = OreSlotsSettings.defaults();
        assertEquals(shipped.reels(), ore.engineReels(shipped.reels()), "its reels are the engine's already");
        assertEquals(shipped.pays(), ore.enginePays(shipped.pays()));
        assertEquals(List.of("Three reels of ores. Match them on the line.",
                        "Three the same pays the most. Two the same pays a little.",
                        "A Wild stands in for any ore. Stone never pays.",
                        "Every spin is decided before the reels move."), SlotsCopy.rules(shipped),
                "the rules it has always shown");
        assertEquals(SlotsCopy.feedRules(), SlotsCopy.feedRules(shipped), "and the website's one line");
        assertEquals(shipped.engine().feedRows(), shipped.engine().feedRows(ore));
        // ... and they are its own fixed words, as before: a themed machine's follow its paytable, Ore Slots' don't
        OreSlotsSettings changed = OreSlotsSettings.of(true, List.of(1), 50, 90, shipped.reels(),
                Map.of("two", 1, "coal", 4, "copper", 6, "iron", 10, "gold", 20, "diamond", 40, "wild", 50), 250);
        assertEquals(SlotsCopy.rules(shipped), SlotsCopy.rules(changed), "whatever its config says");
        assertEquals(SlotsCopy.feedRules(), SlotsCopy.feedRules(changed));
    }

    @Test
    void everyThemeNamesEveryRoleAndLineWithAPictureEach() {
        assertEquals(List.of(SlotTheme.ORE, SlotTheme.NETHER, SlotTheme.FARM, SlotTheme.MOTHERLODE, SlotTheme.DEEP,
                SlotTheme.MOB), SlotTheme.all());
        Set<String> ids = new HashSet<>();
        for (SlotTheme t : SlotTheme.all()) {
            assertTrue(ids.add(t.id()), t + ": its own id");
            Set<String> keys = new HashSet<>();
            Set<String> labels = new HashSet<>();
            Set<Material> icons = new HashSet<>();
            for (Symbol s : Symbol.values()) {
                assertTrue(t.key(s).matches("[a-z][a-z_]*"), t + " " + s + ": a config key, " + t.key(s));
                assertTrue(keys.add(t.key(s)), t + ": no key twice, " + t.key(s));
                assertFalse(t.label(s).isBlank(), t + " " + s + " has a name");
                assertTrue(labels.add(t.label(s)), t + ": no name twice, " + t.label(s));
                assertNotNull(t.icon(s), t + " " + s + " has a picture");
                assertTrue(icons.add(t.icon(s)), t + ": no picture twice, " + t.icon(s));
            }
            if (t == SlotTheme.MOB) {
                // Mob Slots' Wild is the Creeper: it stands in for any drop, and three of them give the free spins
                assertEquals("creeper", t.key(Symbol.WILD));
                assertEquals("Creeper", t.label(Symbol.WILD));
            } else {
                assertEquals("wild", t.key(Symbol.WILD), t + ": the Wild is `wild` on every other machine");
                assertEquals("Wild", t.label(Symbol.WILD), t + ": and is called the Wild");
            }
            assertEquals(t.label(Symbol.WILD), t.wild());
            assertFalse(keys.contains("two"), t + ": `two` is the pair line's key");
            assertEquals(t.label(Symbol.STONE), t.blank());
            Set<String> lines = new HashSet<>();
            for (Line l : Line.values()) {
                assertNotNull(t.label(l), t + " names " + l);
                assertTrue(lines.add(t.label(l)), t + ": no line name twice, " + t.label(l));
                assertNotNull(t.icon(l), t + " " + l + " has a picture");
                assertEquals(l.symbol() == null ? "two" : t.key(l.symbol()), t.key(l));
            }
            assertEquals("Two the same", t.label(Line.TWO_THE_SAME));
            assertTrue(t.label(Line.THREE_WILDS).startsWith("Three "), t.label(Line.THREE_WILDS));
            assertNotNull(t.tileIcon());
            assertNotNull(t.pairIcon());
            assertFalse(t.noun().isBlank());
            assertFalse(t.reelsOf().isBlank());
            assertEquals(6, t.reelKeys().size(), t + ": five paying symbols and the Wild (the blank one is solved)");
            assertEquals(7, t.payKeys().size(), t + ": two the same, and the six");
            assertEquals("two", t.payKeys().get(0));
            assertFalse(t.reelKeys().contains(t.key(Symbol.STONE)), t + ": the blank symbol has no weight to set");
        }
    }

    @Test
    void theOwnersThemesAreTheOnesAskedFor() {
        SlotTheme nether = SlotTheme.NETHER;
        assertEquals(List.of("quartz", "magma_cream", "blaze_rod", "ghast_tear", "nether_star", "wild"),
                nether.reelKeys());
        assertEquals(Material.WITHER_SKELETON_SKULL, nether.icon(Symbol.WILD), "the Wild is a wither skull");
        assertEquals("Netherrack", nether.blank());
        assertEquals("Three ghast tears", nether.label(Line.THREE_GOLD));
        assertEquals("3 ghast_tear", nether.combo(Line.THREE_GOLD));
        SlotTheme farm = SlotTheme.FARM;
        assertEquals(List.of("wheat", "carrot", "potato", "beetroot", "golden_carrot", "wild"), farm.reelKeys());
        assertEquals(Material.EGG, farm.icon(Symbol.WILD), "the Wild is an egg");
        assertEquals("Dirt", farm.blank());
        assertEquals("Three golden carrots", farm.label(Line.THREE_DIAMONDS));
        SlotTheme lode = SlotTheme.MOTHERLODE;
        assertEquals(List.of("redstone", "lapis", "amethyst", "emerald", "netherite", "wild"), lode.reelKeys());
        assertEquals("Deepslate", lode.blank());
    }

    @Test
    void deepMinesAndMobSlotsThemesAreTheOnesAskedFor() {
        SlotTheme deep = SlotTheme.DEEP;
        assertEquals(List.of("coal", "copper", "iron", "gold", "diamond", "wild"), deep.reelKeys(),
                "the ores' own names, as on Ore Slots");
        assertEquals(Material.DEEPSLATE_COAL_ORE, deep.icon(Symbol.COAL), "but they are the deepslate ores");
        assertEquals(Material.DEEPSLATE_COPPER_ORE, deep.icon(Symbol.COPPER));
        assertEquals(Material.DEEPSLATE_IRON_ORE, deep.icon(Symbol.IRON));
        assertEquals(Material.DEEPSLATE_GOLD_ORE, deep.icon(Symbol.GOLD));
        assertEquals(Material.DEEPSLATE_DIAMOND_ORE, deep.icon(Symbol.DIAMOND));
        assertEquals(Material.LANTERN, deep.icon(Symbol.WILD), "the Wild is a lantern");
        assertEquals(Material.TUFF, deep.icon(Symbol.STONE));
        assertEquals("Tuff", deep.blank());
        assertEquals("Wild", deep.wild());
        assertEquals("Three diamonds", deep.label(Line.THREE_DIAMONDS));
        assertEquals(Material.DEEPSLATE_DIAMOND_ORE, deep.tileIcon(), "its tile is its machine's picture");
        assertEquals(OreSlotsSettings.KEYS, SlotSettings.keys(deep, false), "the Ore Slots shape, key for key");

        SlotTheme mob = SlotTheme.MOB;
        assertEquals(List.of("rotten_flesh", "bone", "string", "ender_pearl", "blaze_rod", "creeper"),
                mob.reelKeys(), "what the mobs drop, the commonest first, then the Creeper");
        assertEquals(List.of("two", "rotten_flesh", "bone", "string", "ender_pearl", "blaze_rod", "creeper"),
                mob.payKeys());
        assertEquals(Material.ROTTEN_FLESH, mob.icon(Symbol.COAL));
        assertEquals(Material.BONE, mob.icon(Symbol.COPPER));
        assertEquals(Material.STRING, mob.icon(Symbol.IRON));
        assertEquals(Material.ENDER_PEARL, mob.icon(Symbol.GOLD));
        assertEquals(Material.BLAZE_ROD, mob.icon(Symbol.DIAMOND));
        assertEquals(Material.CREEPER_HEAD, mob.icon(Symbol.WILD), "the Creeper is a creeper's head");
        assertEquals(Material.COBWEB, mob.icon(Symbol.STONE));
        assertEquals("Cobweb", mob.blank());
        assertEquals("Creeper", mob.wild(), "the engine's Wild role is the Creeper here");
        assertEquals("Three creepers", mob.label(Line.THREE_WILDS));
        assertEquals("3 creeper", mob.combo(Line.THREE_WILDS));
        assertEquals("Three ender pearls", mob.label(Line.THREE_GOLD));
        assertEquals(Material.SPAWNER, mob.tileIcon());
        assertEquals(Map.of("coal", 1, "copper", 2, "iron", 3, "gold", 4, "diamond", 5, "wild", 6),
                mob.engineReels(Map.of("rotten_flesh", 1, "bone", 2, "string", 3, "ender_pearl", 4, "blaze_rod", 5,
                        "creeper", 6)), "the Creeper's weight reaches the engine as its Wild's");
        assertEquals(20, mob.enginePays(Map.of("creeper", 20)).get("wild"));
    }

    @Test
    void aThemesKeysReachTheEngineUnderTheEnginesOwnNames() {
        SlotSettings nether = SlotSettings.nether();
        assertEquals(Map.of("coal", 12, "copper", 9, "iron", 6, "gold", 3, "diamond", 2, "wild", 2),
                nether.engineReels(), "quartz is the most common role, the nether star the rarest");
        assertEquals(Map.of("two", 0, "coal", 6, "copper", 10, "iron", 20, "gold", 50, "diamond", 100, "wild", 125),
                nether.enginePays());
        assertEquals(List.of("coal", "copper", "iron", "gold", "diamond", "wild"),
                List.copyOf(nether.engineReels().keySet()), "in the engine's order");
        SlotsEngine same = SlotsEngine.solve(nether.engineReels(), nether.enginePays(), nether.stakes(), 250, 90);
        assertEquals(same, nether.engine(), "the settings' engine is solved from exactly that");
        assertEquals(0, SlotTheme.NETHER.engineReels(Map.of()).get("coal"), "a key left out is 0");
    }

    @Test
    void aThemedMachineReadsInItsOwnWords() {
        SlotSettings nether = SlotSettings.nether();
        SlotsEngine e = nether.engine();
        LineOdds tears = e.odds(2).line(Line.THREE_GOLD);
        assertEquals("&fThree ghast tears &6×50 &7· 1 in " + SlotsCopy.grouped(tears.oneIn()),
                SlotsCopy.lineName(SlotTheme.NETHER, tears));
        assertEquals(List.of("&7Ghast Tear, Ghast Tear, Ghast Tear &8(a Wild counts)", "&72 tokens in → &6100 &7back",
                        "&7Comes up about 1 in " + SlotsCopy.grouped(tears.oneIn()) + " spins."),
                SlotsCopy.lineLore(SlotTheme.NETHER, tears));
        assertNull(e.odds(2).line(Line.TWO_THE_SAME), "two the same pays nothing, so the paytable has no such line");
        Spin won = new Spin(2, List.of(Symbol.GOLD, Symbol.WILD, Symbol.GOLD), Line.THREE_GOLD, 100);
        assertEquals("&aThree ghast tears! &6+100 tokens", SlotsCopy.result(SlotTheme.NETHER, won));
        assertEquals("&eThree ghast tears!", SlotsCopy.title(SlotTheme.NETHER, won)[0]);
        Spin none = new Spin(2, List.of(Symbol.COAL, Symbol.COAL, Symbol.STONE), null, 0);
        assertEquals("&7No win this time.", SlotsCopy.result(SlotTheme.NETHER, none), "a pair there pays nothing");
        assertEquals(List.of("Three reels of Nether treasures. Match them on the line.",
                "Only three the same pays, and it pays big.",
                "A Wild stands in for any treasure. Netherrack never pays.",
                "Every spin is decided before the reels move."), SlotsCopy.rules(nether));
        assertTrue(SlotsCopy.feedRules(nether).contains("two the same pays nothing, and Netherrack never pays."),
                SlotsCopy.feedRules(nether));
        assertTrue(e.feedRows(SlotTheme.NETHER).stream().anyMatch(r -> r.combo().equals("3 nether_star")),
                "the website names the lines as the machine does: " + e.feedRows(SlotTheme.NETHER));
        assertTrue(e.feedRows(SlotTheme.NETHER).stream().noneMatch(r -> r.combo().contains("diamond")
                || r.combo().contains("coal")), "never as Ore Slots does");
        assertTrue(SlotsCopy.oddsDetail(SlotTheme.NETHER, e).get(0).contains("Netherrack " + e.odds(1).stone()),
                "the admin's line names the machine's own blank symbol: " + SlotsCopy.oddsDetail(SlotTheme.NETHER, e));
    }

    @Test
    void aPotMachinesRulesSayWhatThePotIs() {
        SlotSettings lode = SlotSettings.motherlode();
        assertEquals(List.of("Three reels of deep treasures. Match them on the line.",
                "Three the same pays the most. Two the same gives yours back.",
                "A Wild stands in for any treasure. Deepslate never pays.",
                "Every spin adds to the pot. Three Wilds takes it all.",
                "Every spin is decided before the reels move."), SlotsCopy.rules(lode));
        assertEquals("&6The pot: &e$212.25", SlotsCopy.potName(21225));
        assertEquals(List.of("&7Wild, Wild, Wild takes it all.", "&7Every spin adds $0.25, whoever spins.",
                "&7It starts again at $50."), SlotsCopy.potLore(SlotTheme.MOTHERLODE, 25, 5000));
        assertEquals("The Motherlode · $212", SlotsCopy.potLabel("The Motherlode", 21225),
                "the room's label is whole dollars, so it is not drawn again for every spin");
        assertEquals("$5 a spin · pot $212", SlotsCopy.potFact(5, 21299));
        assertEquals("&aThree wilds! You took the pot: &6+$212.25", SlotsCopy.potResult(SlotTheme.MOTHERLODE, 21225));
        for (String line : List.of(SlotsCopy.potName(1), SlotsCopy.potResult(SlotTheme.FARM, 1),
                String.join(" ", SlotsCopy.rules(lode)), SlotsCopy.feedRules(lode), SlotsCopy.potTitle(5)[0])) {
            assertFalse(line.toLowerCase().contains("jackpot"), "it is the pot, never anything else: " + line);
        }
    }
}
