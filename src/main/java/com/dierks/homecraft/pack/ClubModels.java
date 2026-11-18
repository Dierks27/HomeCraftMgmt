package com.dierks.homecraft.pack;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Clubhouse side rooms' models (0.41.0): HCM's first use of the {@code item_model} component. Every model is an
 * item display whose item carries {@code item_model = hcm:<name>}; the pack has an item definition for each name.
 *
 * <p><b>Two sources for each name.</b> HCM ships a simple placeholder for every name in its own CraftBridge source
 * ({@link #placeholderFiles}), built from vanilla textures only, so the rooms look right before the owner's models
 * exist. The owner's Blockbench models go in as a drop-in source; for every name the drop-in has
 * ({@code assets/hcm/items/<name>.json}) HCM leaves its own placeholder out, so the two never conflict
 * ({@link PackModels}).
 *
 * <p><b>Without the pack</b> (no CraftBridge, or a player who declined it) nobody sees a model: each has a vanilla
 * stand-in ({@link Model#fallback}) shown instead, never the missing-texture cube.
 *
 * <p><b>How a model is drawn</b> (for the owner's Blockbench work): the item display's transform is {@code NONE}, so
 * the model is drawn exactly as built, 16 units to a block, its point (8, 8, 8) on the display's position, and its
 * <b>north</b> side (Blockbench's "North", -Z) facing the way the display faces. Pure: no server.
 */
public final class ClubModels {

    /** The namespace every model lives in. */
    public static final String NS = "hcm";

    /** What a model is for: it decides where it stands and how big it is drawn. */
    public enum Kind {
        /** An arcade cabinet: 1 x 2 x 1 blocks, standing on the floor, its screen on the north side. */
        CABINET,
        /** A trophy cup: 1 x 1 x 1 blocks, standing on its pedestal. */
        TROPHY,
        /** The sign over a doorway: a 1 x 1 x 1 model drawn at 1.5 times, hung in front of the wall. */
        DOOR
    }

    /**
     * One model.
     *
     * @param name     the id's path ({@code hcm:<name>})
     * @param kind     what it is for
     * @param fallback the vanilla stand-in without the pack: a cabinet's floating icon (its game's Games-screen
     *                 icon), a trophy's metal, a door's picture
     * @param screen   the placeholder's picture (a vanilla block-atlas texture, {@code minecraft:block/...}: a cabinet's
     *                 screen, a trophy's metal)
     * @param size     what to build, in words, for the owner
     */
    public record Model(String name, Kind kind, Material fallback, String screen, String size) {

        /** {@code hcm:<name>}. */
        public String id() {
            return NS + ":" + name;
        }

        /** How much bigger than built the display draws it: 1.5 for a door sign, else 1. */
        public float scale() {
            return kind == Kind.DOOR ? 1.5f : 1f;
        }

        /** How far over the floor (or pedestal) the display stands: the model's middle. */
        public double up() {
            return switch (kind) {
                case CABINET -> 1.0;
                case TROPHY -> 0.5;
                case DOOR -> 0.0; // a door sign's place is its middle already
            };
        }
    }

    /** The game each cabinet model is for (any other cabinet game uses {@code hcm:cabinet}). */
    private static final Map<String, Model> CABINETS = new LinkedHashMap<>();
    private static final List<Model> ALL = new ArrayList<>();

    private static final String CABINET_SIZE = "1 wide, 2 tall, 1 deep (16 x 32 x 16 units, y from -8 to 24), screen"
            + " on the north side";
    private static final String TROPHY_SIZE = "1 x 1 x 1 (16 units each way, y from 0 to 16), front on the north side";
    private static final String DOOR_SIZE = "1 x 1 x 1 (16 units each way), drawn 1.5 times as big, front on the north"
            + " side";

    static {
        // 0.41.1: block-atlas textures only. On 26.x an item model's boxes read the block atlas; an item/ texture
        // there is the missing texture (0.41.0's Whack-a-Zombie, Mini Match and Tic-Tac-Toe cabinets were purple-black)
        cabinet("whack_a_zombie", "cabinet_whack", Material.ZOMBIE_HEAD, "minecraft:block/target_side");
        cabinet("simon_says", "cabinet_simon", Material.NOTE_BLOCK, "minecraft:block/note_block");
        cabinet("mini_match", "cabinet_match", Material.PAINTING, "minecraft:block/chiseled_bookshelf_occupied");
        cabinet("tic_tac_toe", "cabinet_tictactoe", Material.ITEM_FRAME, "minecraft:block/crafting_table_top");
        cabinet("snake", "cabinet_snake", Material.LIME_CONCRETE, "minecraft:block/melon_side");
        ALL.add(new Model("cabinet", Kind.CABINET, Material.JUKEBOX, "minecraft:block/emerald_block", CABINET_SIZE));
        ALL.add(new Model("trophy_gold", Kind.TROPHY, Material.GOLD_INGOT, "minecraft:block/gold_block", TROPHY_SIZE));
        ALL.add(new Model("trophy_silver", Kind.TROPHY, Material.IRON_INGOT, "minecraft:block/iron_block", TROPHY_SIZE));
        ALL.add(new Model("trophy_bronze", Kind.TROPHY, Material.COPPER_INGOT, "minecraft:block/copper_block",
                TROPHY_SIZE));
        ALL.add(new Model("door_arcade", Kind.DOOR, Material.LEVER, "minecraft:block/red_concrete", DOOR_SIZE));
        ALL.add(new Model("door_trophy", Kind.DOOR, Material.TOTEM_OF_UNDYING, "minecraft:block/gold_block", DOOR_SIZE));
    }

    private static void cabinet(String game, String name, Material icon, String screen) {
        Model m = new Model(name, Kind.CABINET, icon, screen, CABINET_SIZE);
        CABINETS.put(game, m);
        ALL.add(m);
    }

    private ClubModels() {
    }

    /** Every model, in the order the owner's list has them. */
    public static List<Model> all() {
        return List.copyOf(ALL);
    }

    /** A model by name ({@code cabinet_whack}), or {@code null}. */
    public static Model named(String name) {
        for (Model m : ALL) {
            if (m.name().equals(name)) {
                return m;
            }
        }
        return null;
    }

    /** The cabinet for a game: its own, or the plain {@code hcm:cabinet}. */
    public static Model cabinetFor(String gameId) {
        Model m = gameId == null ? null : CABINETS.get(gameId.toLowerCase(Locale.ROOT));
        return m != null ? m : named("cabinet");
    }

    /** The cup for a place: 1 gold, 2 silver, 3 bronze (a record is a 1st: gold). */
    public static Model trophy(int place) {
        return named(place == 2 ? "trophy_silver" : place == 3 ? "trophy_bronze" : "trophy_gold");
    }

    /** The sign over the Arcade Room's door ({@code arcade}) or the Trophy Room's. */
    public static Model door(boolean arcade) {
        return named(arcade ? "door_arcade" : "door_trophy");
    }

    // ---- the placeholders ---------------------------------------------------------------------------

    /** Where a model's item definition lives in a pack: {@code assets/hcm/items/<name>.json}. */
    public static String itemPath(String name) {
        return "assets/" + NS + "/items/" + name + ".json";
    }

    /** Where HCM's placeholder model for a name lives (never a path the owner's models use). */
    static String placeholderModelPath(String name) {
        return "assets/" + NS + "/models/item/placeholder/" + name + ".json";
    }

    /**
     * HCM's placeholder files for every model but {@code skip} (the names the owner's drop-in has): an item definition
     * at {@link #itemPath} and its model at {@code models/item/placeholder/}, by path.
     */
    public static Map<String, String> placeholderFiles(java.util.Set<String> skip) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Model m : ALL) {
            if (skip != null && skip.contains(m.name())) {
                continue;
            }
            out.put(itemPath(m.name()), "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + NS
                    + ":item/placeholder/" + m.name() + "\"}}\n");
            out.put(placeholderModelPath(m.name()), model(m));
        }
        return out;
    }

    /** A placeholder model: boxes in vanilla textures, in the {@link Kind}'s size, its front to the north. */
    static String model(Model m) {
        List<String> parts = new ArrayList<>();
        String tex;
        switch (m.kind()) {
            case CABINET -> {
                tex = "\"body\":\"minecraft:block/black_concrete\",\"panel\":\"minecraft:block/red_concrete\","
                        + "\"top\":\"minecraft:block/glowstone\",\"screen\":\"" + m.screen() + "\"";
                parts.add(box(1, -8, 2, 15, 22, 15, "body"));
                parts.add(box(1, 22, 1, 15, 24, 15, "top"));      // the lit marquee
                parts.add(box(3, 8, 1, 13, 19, 2, "screen"));     // the screen, on the north side
                parts.add(box(2, 2, 0, 14, 5, 2, "panel"));       // the control panel
            }
            case TROPHY -> {
                tex = "\"metal\":\"" + m.screen() + "\",\"base\":\"minecraft:block/polished_blackstone\"";
                parts.add(box(4, 0, 4, 12, 2, 12, "base"));
                parts.add(box(7, 2, 7, 9, 7, 9, "metal"));
                parts.add(box(4, 7, 4, 12, 14, 12, "metal"));
                parts.add(box(2, 9, 7, 4, 13, 9, "metal"));
                parts.add(box(12, 9, 7, 14, 13, 9, "metal"));
            }
            default -> {
                if (m.name().equals("door_arcade")) { // a joystick
                    tex = "\"base\":\"minecraft:block/black_concrete\",\"stick\":\"minecraft:block/iron_block\","
                            + "\"ball\":\"" + m.screen() + "\"";
                    parts.add(box(2, 0, 2, 14, 4, 14, "base"));
                    parts.add(box(7, 4, 7, 9, 11, 9, "stick"));
                    parts.add(box(5, 11, 5, 11, 16, 11, "ball"));
                } else { // a big cup
                    tex = "\"metal\":\"" + m.screen() + "\",\"base\":\"minecraft:block/polished_blackstone\"";
                    parts.add(box(3, 0, 3, 13, 3, 13, "base"));
                    parts.add(box(7, 3, 7, 9, 7, 9, "metal"));
                    parts.add(box(3, 7, 3, 13, 16, 13, "metal"));
                    parts.add(box(0, 9, 7, 3, 14, 9, "metal"));
                    parts.add(box(13, 9, 7, 16, 14, 9, "metal"));
                }
            }
        }
        String first = tex.substring(tex.indexOf(":\"") + 2, tex.indexOf("\"", tex.indexOf(":\"") + 2));
        return "{\"textures\":{\"particle\":\"" + first + "\"," + tex + "},\"elements\":[" + String.join(",", parts)
                + "]}\n";
    }

    /** One box, every face in {@code #tex}. */
    private static String box(int x0, int y0, int z0, int x1, int y1, int z1, String tex) {
        StringBuilder faces = new StringBuilder();
        for (String f : new String[]{"north", "east", "south", "west", "up", "down"}) {
            faces.append(faces.length() == 0 ? "" : ",").append('"').append(f).append("\":{\"texture\":\"#")
                    .append(tex).append("\"}");
        }
        return "{\"from\":[" + x0 + "," + y0 + "," + z0 + "],\"to\":[" + x1 + "," + y1 + "," + z1 + "],\"faces\":{"
                + faces + "}}";
    }
}
