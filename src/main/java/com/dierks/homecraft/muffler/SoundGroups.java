package com.dierks.homecraft.muffler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The friendly buttons on a Sound Muffler: "Chickens", "Pistons", "Doors, Gates &amp; Trapdoors".
 *
 * <p>Minecraft has well over a thousand sounds, and a list of them is no way to find the chicken.
 * Each group is a handful of patterns over a sound's key without its namespace
 * ({@code entity.chicken.ambient}), where {@code *} matches anything, dots included. Only vanilla
 * ({@code minecraft:}) sounds belong to groups; a resource pack's own sounds can still be picked
 * one at a time.
 *
 * <p>A sound may sit in more than one group — {@code entity.chicken.step} is a chicken and a
 * footstep — and a muffler applies the strongest of them. The ids are stored in the database, so
 * an id never changes once shipped; the name, icon and patterns may.
 *
 * <p>Only sounds the SERVER sends can be hushed. The groups leave out what the player's own game
 * makes by itself — rain and thunder, music and jukeboxes, furnaces and campfires crackling,
 * portals humming, lava popping, minecarts rolling, bees buzzing in flight, and a player's own
 * footsteps, clicks and pickups — because a button that does nothing is worse than no button.
 */
public final class SoundGroups {

    /** One button. {@code slot} is where it sits in the muffler's 54-slot menu (rows 2–5). */
    public record SoundGroup(String id, String name, String icon, int slot, List<String> about,
                             List<String> patterns, List<Pattern> compiled) {

        /** Whether this group covers a sound path (a key with no namespace). */
        public boolean covers(String path) {
            for (Pattern p : compiled) {
                if (p.matcher(path).matches()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final List<SoundGroup> ALL;
    private static final Map<String, SoundGroup> BY_ID;
    /** Sound key → the groups covering it. Sound keys are a small, closed set, so this stays small. */
    private static final Map<String, List<SoundGroup>> CACHE = new ConcurrentHashMap<>();
    /** A server playing endless made-up sound names must not grow the cache without end. */
    private static final int CACHE_LIMIT = 8192;

    static {
        List<SoundGroup> g = new ArrayList<>();
        // Row 2 — animals.
        g.add(group("chickens", "Chickens", "CHICKEN_SPAWN_EGG", 9,
                List.of("Clucks, laying eggs and footsteps."),
                "entity.chicken.*"));
        g.add(group("cows", "Cows", "COW_SPAWN_EGG", 10,
                List.of("Moos and milking — mooshrooms too."),
                "entity.cow.*", "entity.mooshroom.*"));
        g.add(group("pigs", "Pigs", "PIG_SPAWN_EGG", 11,
                List.of("Oinks and footsteps."),
                "entity.pig.*"));
        g.add(group("sheep", "Sheep", "SHEEP_SPAWN_EGG", 12,
                List.of("Baas and shearing."),
                "entity.sheep.*"));
        g.add(group("goats", "Goats", "GOAT_SPAWN_EGG", 13,
                List.of("Bleats, screams and ramming."),
                "entity.goat.*"));
        g.add(group("bees", "Bees", "BEE_SPAWN_EGG", 14,
                List.of("Hives, pollinating and stings.",
                        "&8Their buzz while flying comes from your",
                        "&8own game, so a muffler can't reach it."),
                "entity.bee.*", "block.beehive.*"));
        g.add(group("horses", "Horses & Llamas", "HORSE_SPAWN_EGG", 15,
                List.of("Horses, donkeys, mules, llamas and camels."),
                "entity.horse.*", "entity.donkey.*", "entity.mule.*", "entity.llama.*",
                "entity.camel.*", "entity.skeleton_horse.*", "entity.zombie_horse.*"));
        g.add(group("pets", "Cats, Dogs & Parrots", "WOLF_SPAWN_EGG", 16,
                List.of("Meows, barks, growls and parrot chatter."),
                "entity.cat.*", "entity.ocelot.*", "entity.wolf*", "entity.parrot.*"));
        g.add(group("wildlife", "Other Animals", "FOX_SPAWN_EGG", 17,
                List.of("Rabbits, foxes, frogs, turtles, axolotls,",
                        "pandas, bears, fish, squid, bats and more."),
                "entity.rabbit.*", "entity.fox.*", "entity.frog.*", "entity.tadpole.*",
                "entity.turtle.*", "entity.axolotl.*", "entity.panda.*", "entity.polar_bear.*",
                "entity.dolphin.*", "entity.cod.*", "entity.salmon.*", "entity.pufferfish.*",
                "entity.tropical_fish.*", "entity.fish.*", "entity.squid.*", "entity.glow_squid.*",
                "entity.bat.*", "entity.armadillo.*", "entity.sniffer.*", "entity.strider.*",
                "entity.allay.*", "entity.happy_ghast.*", "entity.nautilus.*"));

        // Row 3 — villages, then the monsters.
        g.add(group("villagers", "Villagers & Traders", "VILLAGER_SPAWN_EGG", 18,
                List.of("Chatter, trading, and the sounds they", "make working at their job blocks."),
                "entity.villager.*", "entity.wandering_trader.*"));
        g.add(group("golems", "Golems", "IRON_GOLEM_SPAWN_EGG", 19,
                List.of("Iron, snow and copper golems."),
                "entity.iron_golem.*", "entity.snow_golem.*", "entity.copper_golem.*"));
        g.add(group("zombies", "Zombies", "ZOMBIE_SPAWN_EGG", 20,
                List.of("Zombies, husks, drowned and zombie", "villagers — door banging too."),
                "entity.zombie.*", "entity.zombie_villager.*", "entity.husk.*", "entity.drowned.*"));
        g.add(group("skeletons", "Skeletons", "SKELETON_SPAWN_EGG", 21,
                List.of("Skeletons, strays, bogged and", "wither skeletons."),
                "entity.skeleton.*", "entity.stray.*", "entity.wither_skeleton.*", "entity.bogged.*",
                "entity.parched.*"));
        g.add(group("creepers", "Creepers", "CREEPER_SPAWN_EGG", 22,
                List.of("Hissing and deaths.", "&6Careful: a hushed creeper sneaks up quietly!"),
                "entity.creeper.*"));
        g.add(group("spiders", "Spiders", "SPIDER_SPAWN_EGG", 23,
                List.of("Hissing, climbing and footsteps."),
                "entity.spider.*"));
        g.add(group("endermen", "Endermen", "ENDERMAN_SPAWN_EGG", 24,
                List.of("Endermen and endermites."),
                "entity.enderman.*", "entity.endermite.*"));
        g.add(group("slimes", "Slimes & Magma Cubes", "SLIME_SPAWN_EGG", 25,
                List.of("Squishing and jumping."),
                "entity.slime.*", "entity.magma_cube.*"));
        g.add(group("raiders", "Witches & Raiders", "WITCH_SPAWN_EGG", 26,
                List.of("Witches, pillagers, vindicators, evokers,", "ravagers, vexes and the raid horn."),
                "entity.witch.*", "entity.pillager.*", "entity.vindicator.*", "entity.evoker.*",
                "entity.illusioner.*", "entity.ravager.*", "entity.vex.*", "event.raid.*"));

        // Row 4 — the rest of the monsters, then people.
        g.add(group("nether", "Nether Mobs", "GHAST_SPAWN_EGG", 27,
                List.of("Ghasts, blazes, piglins, hoglins", "and zombified piglins."),
                "entity.ghast.*", "entity.ghastling.*", "entity.blaze.*", "entity.piglin.*",
                "entity.piglin_brute.*", "entity.hoglin.*", "entity.zoglin.*",
                "entity.zombified_piglin.*"));
        g.add(group("monsters", "Other Monsters", "PHANTOM_MEMBRANE", 28,
                List.of("Guardians, phantoms, silverfish, shulkers,",
                        "breezes, creakings, the warden, the wither", "and the dragon."),
                "entity.guardian.*", "entity.elder_guardian.*", "entity.phantom.*",
                "entity.silverfish.*", "entity.shulker.*", "entity.shulker_bullet.*",
                "entity.breeze.*", "entity.creaking.*", "entity.warden.*", "entity.wither.*",
                "entity.ender_dragon.*"));
        g.add(group("players", "Other Players", "PLAYER_HEAD", 30,
                List.of("Other players getting hurt, attacking,", "eating, drinking and burping.",
                        "&8Your own come from your own game."),
                "entity.player.*", "entity.generic.eat", "entity.generic.drink"));
        g.add(group("footsteps", "Footsteps", "LEATHER_BOOTS", 31,
                List.of("Every walking sound: mobs, villagers and", "other players, on any block."),
                "block.*.step", "entity.*.step"));
        g.add(group("fishing", "Fishing", "FISHING_ROD", 32,
                List.of("Casting, splashing and reeling in", "— handy next to an AFK fish farm."),
                "entity.fishing_bobber.*"));

        // Row 5 — machines and blocks.
        g.add(group("pistons", "Pistons", "PISTON", 36,
                List.of("Pushing and pulling."),
                "block.piston.*"));
        g.add(group("dispensers", "Dispensers & Droppers", "DISPENSER", 37,
                List.of("The click every time they fire."),
                "block.dispenser.*"));
        g.add(group("doors", "Doors, Gates & Trapdoors", "OAK_DOOR", 38,
                List.of("Opening and closing, every wood and metal."),
                "block.*door*", "block.*fence_gate*"));
        g.add(group("switches", "Buttons, Levers & Plates", "LEVER", 39,
                List.of("Buttons, levers, pressure plates, tripwires,", "comparators and copper bulbs."),
                "block.*button*", "block.lever.*", "block.*pressure_plate*", "block.tripwire.*",
                "block.comparator.*", "block.copper_bulb.*"));
        g.add(group("note_blocks", "Note Blocks", "NOTE_BLOCK", 40,
                List.of("Every instrument."),
                "block.note_block.*"));
        g.add(group("bells", "Bells", "BELL", 41,
                List.of("Ringing and resonating."),
                "block.bell.*"));
        g.add(group("storage", "Chests & Barrels", "CHEST", 42,
                List.of("Chests, barrels, ender chests and", "shulker boxes opening and closing."),
                "block.*chest*", "block.barrel.*", "block.shulker_box.*"));
        g.add(group("workstations", "Anvils & Workstations", "ANVIL", 43,
                List.of("Anvils, grindstones, smithing tables, brewing",
                        "stands, crafters, composters, enchanting,", "looms and stonecutters."),
                "block.anvil.*", "block.grindstone.*", "block.smithing_table.*", "block.brewing_stand.*",
                "block.crafter.*", "block.composter.*", "block.enchantment_table.*", "ui.loom.*",
                "ui.stonecutter.*", "ui.cartography_table.*"));
        g.add(group("beacons", "Beacons & Conduits", "BEACON", 44,
                List.of("The hum and the power-up."),
                "block.beacon.*", "block.conduit.*"));

        ALL = Collections.unmodifiableList(g);
        Map<String, SoundGroup> byId = new LinkedHashMap<>();
        for (SoundGroup group : g) {
            byId.put(group.id(), group);
        }
        BY_ID = Collections.unmodifiableMap(byId);
    }

    private SoundGroups() {
    }

    /** Every group, in menu order. */
    public static List<SoundGroup> all() {
        return ALL;
    }

    /** The group with this id, or null. */
    public static SoundGroup byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /**
     * The groups a sound belongs to ({@code minecraft:entity.chicken.step} → Chickens, Footsteps).
     * Empty for a sound outside the {@code minecraft} namespace or one no group mentions.
     */
    public static List<SoundGroup> of(String key) {
        if (key == null) {
            return List.of();
        }
        List<SoundGroup> cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        List<SoundGroup> found = compute(key);
        if (CACHE.size() < CACHE_LIMIT) {
            CACHE.put(key, found);
        }
        return found;
    }

    private static List<SoundGroup> compute(String key) {
        String path = SoundNames.vanillaPath(key);
        if (path == null) {
            return List.of();
        }
        List<SoundGroup> out = new ArrayList<>(2);
        for (SoundGroup group : ALL) {
            if (group.covers(path)) {
                out.add(group);
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static SoundGroup group(String id, String name, String icon, int slot, List<String> about,
                                    String... patterns) {
        List<Pattern> compiled = new ArrayList<>(patterns.length);
        for (String p : patterns) {
            compiled.add(glob(p));
        }
        return new SoundGroup(id, name, icon, slot, List.copyOf(about), List.of(patterns), List.copyOf(compiled));
    }

    /** {@code *} is any run of characters, dots included; everything else is literal. */
    static Pattern glob(String glob) {
        StringBuilder re = new StringBuilder();
        int from = 0;
        for (int i = 0; i < glob.length(); i++) {
            if (glob.charAt(i) == '*') {
                if (i > from) {
                    re.append(Pattern.quote(glob.substring(from, i)));
                }
                re.append(".*");
                from = i + 1;
            }
        }
        if (from < glob.length()) {
            re.append(Pattern.quote(glob.substring(from)));
        }
        return Pattern.compile(re.toString());
    }
}
