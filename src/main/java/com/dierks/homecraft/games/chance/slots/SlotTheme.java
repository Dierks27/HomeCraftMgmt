package com.dierks.homecraft.games.chance.slots;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a slot machine looks like and what it calls things (the owner: "it would be fun to have several
 * different 'Types' of slot machine").
 *
 * <p>Every machine runs the one {@link SlotsEngine}, and the engine's seven {@link Symbol}s keep their roles on all of
 * them: five paying symbols from the most common to the rarest, the Wild that stands in for any of them, and the blank
 * one that never pays and whose reel weight is what the engine solves. A theme only dresses those roles: each gets a
 * player-facing name, a picture (an item) and the key it has in the machine's own config section, and each line gets
 * its name ("Three ghast tears"). The odds, the draw and the stored round never read a theme.
 *
 * <p>{@link #ORE} is Ore Slots exactly as it has always read: its names are {@link Symbol#label()} and
 * {@link Line#label()}, its keys {@link Symbol#key()}.
 */
public final class SlotTheme {

    /** Ore Slots: the reference machine. */
    public static final SlotTheme ORE = new SlotTheme("ore", "ore", "ores", Material.DIAMOND_ORE, Material.IRON_NUGGET)
            .symbol(Symbol.COAL, "coal", "Coal", "Three coal", Material.COAL)
            .symbol(Symbol.COPPER, "copper", "Copper", "Three copper", Material.COPPER_INGOT)
            .symbol(Symbol.IRON, "iron", "Iron", "Three iron", Material.IRON_INGOT)
            .symbol(Symbol.GOLD, "gold", "Gold", "Three gold", Material.GOLD_INGOT)
            .symbol(Symbol.DIAMOND, "diamond", "Diamond", "Three diamonds", Material.DIAMOND)
            .symbol(Symbol.WILD, "wild", "Wild", "Three wilds", Material.NETHER_STAR)
            .symbol(Symbol.STONE, "stone", "Stone", null, Material.COBBLESTONE);

    /** Nether Slots: it pays rarely and big. */
    public static final SlotTheme NETHER = new SlotTheme("nether", "treasure", "Nether treasures",
            Material.NETHER_GOLD_ORE, Material.GOLD_NUGGET)
            .symbol(Symbol.COAL, "quartz", "Quartz", "Three quartz", Material.QUARTZ)
            .symbol(Symbol.COPPER, "magma_cream", "Magma Cream", "Three magma creams", Material.MAGMA_CREAM)
            .symbol(Symbol.IRON, "blaze_rod", "Blaze Rod", "Three blaze rods", Material.BLAZE_ROD)
            .symbol(Symbol.GOLD, "ghast_tear", "Ghast Tear", "Three ghast tears", Material.GHAST_TEAR)
            .symbol(Symbol.DIAMOND, "nether_star", "Nether Star", "Three nether stars", Material.NETHER_STAR)
            .symbol(Symbol.WILD, "wild", "Wild", "Three wilds", Material.WITHER_SKELETON_SKULL)
            .symbol(Symbol.STONE, "netherrack", "Netherrack", null, Material.NETHERRACK);

    /** Farm Slots: the small-pot machine. */
    public static final SlotTheme FARM = new SlotTheme("farm", "crop", "crops", Material.HAY_BLOCK,
            Material.WHEAT_SEEDS)
            .symbol(Symbol.COAL, "wheat", "Wheat", "Three wheat", Material.WHEAT)
            .symbol(Symbol.COPPER, "carrot", "Carrot", "Three carrots", Material.CARROT)
            .symbol(Symbol.IRON, "potato", "Potato", "Three potatoes", Material.POTATO)
            .symbol(Symbol.GOLD, "beetroot", "Beetroot", "Three beetroots", Material.BEETROOT)
            .symbol(Symbol.DIAMOND, "golden_carrot", "Golden Carrot", "Three golden carrots", Material.GOLDEN_CARROT)
            .symbol(Symbol.WILD, "wild", "Wild", "Three wilds", Material.EGG)
            .symbol(Symbol.STONE, "dirt", "Dirt", null, Material.DIRT);

    /** The Motherlode: the big building pot, deep underground. */
    public static final SlotTheme MOTHERLODE = new SlotTheme("motherlode", "treasure", "deep treasures",
            Material.RAW_GOLD_BLOCK, Material.RAW_GOLD)
            .symbol(Symbol.COAL, "redstone", "Redstone", "Three redstone", Material.REDSTONE)
            .symbol(Symbol.COPPER, "lapis", "Lapis", "Three lapis", Material.LAPIS_LAZULI)
            .symbol(Symbol.IRON, "amethyst", "Amethyst", "Three amethysts", Material.AMETHYST_SHARD)
            .symbol(Symbol.GOLD, "emerald", "Emerald", "Three emeralds", Material.EMERALD)
            .symbol(Symbol.DIAMOND, "netherite", "Netherite", "Three netherite", Material.NETHERITE_INGOT)
            .symbol(Symbol.WILD, "wild", "Wild", "Three wilds", Material.GOLDEN_PICKAXE)
            .symbol(Symbol.STONE, "deepslate", "Deepslate", null, Material.COBBLED_DEEPSLATE);

    /** Deep Mine: three rows that all count, down among the deepslate ores. */
    public static final SlotTheme DEEP = new SlotTheme("deep", "ore", "deepslate ores",
            Material.DEEPSLATE_DIAMOND_ORE, Material.RAW_COPPER)
            .symbol(Symbol.COAL, "coal", "Coal", "Three coal", Material.DEEPSLATE_COAL_ORE)
            .symbol(Symbol.COPPER, "copper", "Copper", "Three copper", Material.DEEPSLATE_COPPER_ORE)
            .symbol(Symbol.IRON, "iron", "Iron", "Three iron", Material.DEEPSLATE_IRON_ORE)
            .symbol(Symbol.GOLD, "gold", "Gold", "Three gold", Material.DEEPSLATE_GOLD_ORE)
            .symbol(Symbol.DIAMOND, "diamond", "Diamond", "Three diamonds", Material.DEEPSLATE_DIAMOND_ORE)
            .symbol(Symbol.WILD, "wild", "Wild", "Three wilds", Material.LANTERN)
            .symbol(Symbol.STONE, "tuff", "Tuff", null, Material.TUFF);

    /**
     * Mob Slots: the machine with free spins. Its reels hold what the mobs drop, and the Creeper is its Wild: it
     * stands in for any drop, and three of them on the line also give the free spins. So the engine's Wild role is
     * called the Creeper here, in the config ({@code reels.creeper}, {@code pays.creeper}) and on the screen.
     */
    public static final SlotTheme MOB = new SlotTheme("mob", "drop", "mob drops", Material.SPAWNER, Material.FEATHER)
            .symbol(Symbol.COAL, "rotten_flesh", "Rotten Flesh", "Three rotten flesh", Material.ROTTEN_FLESH)
            .symbol(Symbol.COPPER, "bone", "Bone", "Three bones", Material.BONE)
            .symbol(Symbol.IRON, "string", "String", "Three strings", Material.STRING)
            .symbol(Symbol.GOLD, "ender_pearl", "Ender Pearl", "Three ender pearls", Material.ENDER_PEARL)
            .symbol(Symbol.DIAMOND, "blaze_rod", "Blaze Rod", "Three blaze rods", Material.BLAZE_ROD)
            .symbol(Symbol.WILD, "creeper", "Creeper", "Three creepers", Material.CREEPER_HEAD)
            .symbol(Symbol.STONE, "cobweb", "Cobweb", null, Material.COBWEB);

    private final String id;
    private final String noun;
    private final String reelsOf;
    private final Material tile;
    private final Material pair;
    private final Map<Symbol, String> keys = new EnumMap<>(Symbol.class);
    private final Map<Symbol, String> labels = new EnumMap<>(Symbol.class);
    private final Map<Symbol, String> threes = new EnumMap<>(Symbol.class);
    private final Map<Symbol, Material> icons = new EnumMap<>(Symbol.class);

    private SlotTheme(String id, String noun, String reelsOf, Material tile, Material pair) {
        this.id = id;
        this.noun = noun;
        this.reelsOf = reelsOf;
        this.tile = tile;
        this.pair = pair;
    }

    private SlotTheme symbol(Symbol symbol, String key, String label, String three, Material icon) {
        keys.put(symbol, key);
        labels.put(symbol, label);
        if (three != null) {
            threes.put(symbol, three);
        }
        icons.put(symbol, icon);
        return this;
    }

    /** Every theme, Ore Slots' first. */
    public static List<SlotTheme> all() {
        return List.of(ORE, NETHER, FARM, MOTHERLODE, DEEP, MOB);
    }

    /** Its short name ("nether"), for logs and tests. */
    public String id() {
        return id;
    }

    /** What one paying symbol is called in a sentence: "A Wild stands in for any <i>ore</i>". */
    public String noun() {
        return noun;
    }

    /** What the reels are full of: "Three reels of <i>ores</i>". */
    public String reelsOf() {
        return reelsOf;
    }

    /** The symbol's key under the machine's {@code reels} (the blank one has none in config: it is solved). */
    public String key(Symbol symbol) {
        return keys.get(symbol);
    }

    /** The symbol's player-facing name ("Ghast Tear"). */
    public String label(Symbol symbol) {
        return labels.get(symbol);
    }

    /** The symbol's picture on a reel and on the paytable. */
    public Material icon(Symbol symbol) {
        return icons.get(symbol);
    }

    /** The picture for "two the same" on the paytable. */
    public Material pairIcon() {
        return pair;
    }

    /** The machine's tile on the Games screen. */
    public Material tileIcon() {
        return tile;
    }

    /**
     * What the symbol that stands in for any other is called: "Wild" on every machine but Mob Slots, where it is
     * the Creeper.
     */
    public String wild() {
        return label(Symbol.WILD);
    }

    /** The blank symbol's name ("Netherrack"): it never pays. */
    public String blank() {
        return label(Symbol.STONE);
    }

    /** The line's key under the machine's {@code pays}: its symbol's key, or {@code two}. */
    public String key(Line line) {
        return line.symbol() == null ? line.key() : key(line.symbol());
    }

    /** The line's player-facing name ("Three ghast tears", "Two the same"). */
    public String label(Line line) {
        return line.symbol() == null ? line.label() : threes.get(line.symbol());
    }

    /** How the website names the line ("3 ghast_tear", "two the same"). */
    public String combo(Line line) {
        return line.symbol() == null ? line.combo() : "3 " + key(line.symbol());
    }

    /** The picture of a line on the paytable. */
    public Material icon(Line line) {
        return line.symbol() == null ? pair : icon(line.symbol());
    }

    /** The leaves under the machine's {@code reels}, in config order: the five paying symbols, then the Wild. */
    public List<String> reelKeys() {
        List<String> out = new ArrayList<>();
        for (Symbol s : Symbol.values()) {
            if (s != Symbol.STONE) {
                out.add(key(s));
            }
        }
        return out;
    }

    /** The leaves under the machine's {@code pays}, in config order: two the same, the five symbols, the Wild. */
    public List<String> payKeys() {
        List<String> out = new ArrayList<>();
        out.add(Line.TWO_THE_SAME.key());
        out.addAll(reelKeys());
        return out;
    }

    /** Reel weights written with this theme's keys, as the engine reads them (by {@link Symbol#key()}). */
    public Map<String, Integer> engineReels(Map<String, Integer> themed) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Symbol s : Symbol.values()) {
            if (s != Symbol.STONE) {
                out.put(s.key(), themed.getOrDefault(key(s), 0));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Line multiples written with this theme's keys, as the engine reads them (by {@link Line#key()}). */
    public Map<String, Integer> enginePays(Map<String, Integer> themed) {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put(Line.TWO_THE_SAME.key(), themed.getOrDefault(Line.TWO_THE_SAME.key(), 0));
        for (Symbol s : Symbol.values()) {
            if (s != Symbol.STONE) {
                out.put(Line.of(s).key(), themed.getOrDefault(key(s), 0));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    @Override
    public String toString() {
        return "SlotTheme(" + id + ")";
    }
}
