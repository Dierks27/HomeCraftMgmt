package com.dierks.homecraft.graves;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The {@code graves:} section of config.yml (HCM-GRAVES §9). An absent section (a start whose backfill didn't run)
 * reads as the shipped defaults.
 *
 * @param enabled            false: HCM does nothing on a death beyond what it did before graves
 * @param xpPercent          how much of a player's experience goes in the grave (0-100)
 * @param unlockAfterMinutes after this long anyone may open a grave (0 = never: only its owner and admins)
 * @param expireAfterHours   after this long a grave's things drop on the ground where it stands (0 = never)
 * @param disabledWorlds     worlds where a death makes no grave, lower-cased
 * @param sneakOpensMenu     sneak + right-click opens the grave screen (take stacks one by one)
 * @param keepInventoryMakesGrave a keep-inventory death (the gamerule, Essentials) still makes a grave, from the
 *                               inventory, for everyone without {@code hcm.graves.keep}
 * @param compass                a grave compass on respawn, pointing at the grave
 */
public record GraveSettings(boolean enabled, int xpPercent, int unlockAfterMinutes, int expireAfterHours,
                            Set<String> disabledWorlds, boolean sneakOpensMenu, boolean keepInventoryMakesGrave,
                            boolean compass) {

    /** The shipped values: fairyhub, whose keep_inventory gamerule keeps everything there, makes no graves. */
    public static final GraveSettings DEFAULTS = new GraveSettings(true, 100, 0, 0, Set.of("fairyhub"), true, true,
            true);

    public GraveSettings {
        disabledWorlds = Set.copyOf(disabledWorlds);
    }

    /** Whether a death in {@code world} makes no grave. */
    public boolean worldDisabled(String world) {
        return world != null && disabledWorlds.contains(world.toLowerCase(Locale.ROOT));
    }

    /** Read {@code graves:}; a value out of range is brought into range with a warning. */
    public static GraveSettings read(ConfigurationSection s, Consumer<String> warn) {
        if (s == null) {
            return DEFAULTS;
        }
        boolean enabled = s.getBoolean("enabled", true);
        int xp = s.getInt("xp_percent", 100);
        if (xp < 0 || xp > 100) {
            warn.accept("graves.xp_percent " + xp + " is outside 0-100; using " + Math.max(0, Math.min(100, xp)) + ".");
            xp = Math.max(0, Math.min(100, xp));
        }
        int unlock = s.getInt("unlock_after_minutes", 0);
        if (unlock < 0) {
            warn.accept("graves.unlock_after_minutes " + unlock + " is below 0; using 0 (never).");
            unlock = 0;
        }
        int expire = s.getInt("expire_after_hours", 0);
        if (expire < 0) {
            warn.accept("graves.expire_after_hours " + expire + " is below 0; using 0 (never).");
            expire = 0;
        }
        Set<String> worlds = new LinkedHashSet<>();
        List<String> names = s.getStringList("disabled_worlds");
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                worlds.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        boolean menu = s.getBoolean("sneak_opens_menu", true);
        boolean override = s.getBoolean("keep_inventory_makes_grave", true);
        boolean compass = s.getBoolean("compass", true);
        return new GraveSettings(enabled, xp, unlock, expire, worlds, menu, override, compass);
    }
}
