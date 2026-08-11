package com.dierks.homecraft.marketplace;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import org.bukkit.Material;

import java.util.List;

/**
 * Sorts a listed item into a Marketplace department using property flags
 * ({@code isEdible}/{@code isBlock}) + material-name heuristics, with an admin
 * override map that always wins. Department names come from config; an unknown
 * result falls back to the last department (Misc).
 */
public final class Categorizer {

    private final HomeCraftManagement plugin;

    public Categorizer(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    public String department(Material m) {
        PluginConfig.Marketplace mp = plugin.config().marketplace();
        String override = mp.override(m);
        if (override != null && !override.isBlank()) {
            return canonical(override, mp.departments());
        }
        return canonical(guess(m), mp.departments());
    }

    private String guess(Material m) {
        String n = m.name();
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS")
                || n.endsWith("_BOOTS") || n.endsWith("_HORSE_ARMOR") || n.equals("SHIELD")
                || n.equals("ELYTRA") || n.equals("TURTLE_HELMET")) {
            return "Armor";
        }
        if (n.endsWith("_SWORD") || n.endsWith("_AXE") || n.equals("BOW") || n.equals("CROSSBOW")
                || n.equals("TRIDENT") || n.equals("ARROW") || n.endsWith("_ARROW") || n.equals("TNT")) {
            return "Weapons";
        }
        if (n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.equals("SHEARS")
                || n.equals("FLINT_AND_STEEL") || n.equals("FISHING_ROD") || n.endsWith("_BUCKET")
                || n.equals("BUCKET") || n.equals("COMPASS") || n.equals("CLOCK") || n.equals("SPYGLASS")
                || n.equals("BRUSH") || n.equals("LEAD") || n.equals("NAME_TAG")) {
            return "Tools";
        }
        if (m.isEdible() || n.equals("CAKE") || n.endsWith("_STEW") || n.equals("MILK_BUCKET")
                || n.equals("HONEY_BOTTLE")) {
            return "Food";
        }
        if (isRedstone(n)) {
            return "Redstone";
        }
        if (n.endsWith("_HEAD") || n.endsWith("_SKULL") || n.endsWith("_BANNER")
                || n.equals("PAINTING") || n.equals("ITEM_FRAME") || n.equals("GLOW_ITEM_FRAME")
                || n.equals("MUSIC_DISC") || n.startsWith("MUSIC_DISC_") || n.equals("TOTEM_OF_UNDYING")) {
            return "Collectibles";
        }
        if (m.isBlock()) {
            return "Blocks";
        }
        return "Misc";
    }

    private boolean isRedstone(String n) {
        return n.equals("REDSTONE") || n.equals("REDSTONE_TORCH") || n.equals("REDSTONE_BLOCK")
                || n.equals("REPEATER") || n.equals("COMPARATOR") || n.equals("OBSERVER")
                || n.equals("PISTON") || n.equals("STICKY_PISTON") || n.equals("HOPPER")
                || n.equals("DISPENSER") || n.equals("DROPPER") || n.equals("LEVER")
                || n.endsWith("_BUTTON") || n.endsWith("_PRESSURE_PLATE") || n.equals("TRIPWIRE_HOOK")
                || n.equals("DAYLIGHT_DETECTOR") || n.equals("TARGET") || n.equals("SLIME_BLOCK")
                || n.equals("HONEY_BLOCK") || n.equals("REDSTONE_LAMP") || n.equals("NOTE_BLOCK")
                || n.equals("POWERED_RAIL") || n.equals("DETECTOR_RAIL") || n.equals("ACTIVATOR_RAIL")
                || n.equals("SCULK_SENSOR") || n.equals("CALIBRATED_SCULK_SENSOR");
    }

    /** Map a guessed/override name to the exact configured department (case-insensitive). */
    private String canonical(String dept, List<String> departments) {
        for (String d : departments) {
            if (d.equalsIgnoreCase(dept)) {
                return d;
            }
        }
        return departments.isEmpty() ? "Misc" : departments.get(departments.size() - 1);
    }
}
