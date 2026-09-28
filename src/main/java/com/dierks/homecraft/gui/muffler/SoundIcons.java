package com.dierks.homecraft.gui.muffler;

import com.dierks.homecraft.muffler.SoundGroups;
import com.dierks.homecraft.muffler.SoundNames;
import org.bukkit.Material;

import java.util.Locale;

/**
 * A picture for a sound, so a list of them reads at a glance: a mob's sound shows its spawn egg,
 * a block's shows the block ({@code block.stone.step} is stone), and anything else borrows its
 * group's icon or falls back to a note block.
 */
final class SoundIcons {

    private SoundIcons() {
    }

    static Material of(String key) {
        String path = SoundNames.vanillaPath(key);
        if (path != null) {
            String[] parts = path.split("\\.");
            if (parts.length >= 2) {
                String subject = parts[1].toUpperCase(Locale.ROOT);
                if (parts[0].equals("entity")) {
                    Material egg = item(subject + "_SPAWN_EGG");
                    if (egg == null && subject.indexOf('_') > 0) {
                        egg = item(subject.substring(0, subject.indexOf('_')) + "_SPAWN_EGG"); // wolf_big → wolf
                    }
                    if (egg != null) {
                        return egg;
                    }
                    if (subject.equals("PLAYER")) {
                        return Material.PLAYER_HEAD;
                    }
                } else if (parts[0].equals("block") || parts[0].equals("item")) {
                    Material m = item(subject);
                    if (m != null) {
                        return m;
                    }
                }
            }
        }
        for (SoundGroups.SoundGroup g : SoundGroups.of(key)) {
            Material m = item(g.icon());
            if (m != null) {
                return m;
            }
        }
        return Material.NOTE_BLOCK;
    }

    /** A group's icon, or a note block if the configured name isn't an item on this version. */
    static Material group(SoundGroups.SoundGroup g) {
        Material m = item(g.icon());
        return m != null ? m : Material.NOTE_BLOCK;
    }

    private static Material item(String name) {
        Material m = Material.matchMaterial(name);
        return m != null && m.isItem() && !m.isAir() ? m : null;
    }
}
