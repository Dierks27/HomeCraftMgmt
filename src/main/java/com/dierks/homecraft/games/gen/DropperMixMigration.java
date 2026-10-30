package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.games.gen.api.Slots;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;

/**
 * Config revision 25: the Long Drop. The Dropper's shipped mix becomes {@code EEMHX} (its level 5 is the
 * Long Drop, the letter X: 128 blocks down through 8 unlit plates), and its halves grow from 64 to 144
 * blocks tall at the same spot, Classic Dropper's with them.
 *
 * <p><b>Only the untouched shipped value moves.</b> A {@code games.fresh.slots.fresh_dropper.mix} still
 * exactly {@code EEMMH} (trimmed, any case: what every version before this one shipped) becomes
 * {@code EEMHX}, with one INFO line. Any other value is the owner's own and stays, with one WARN naming
 * {@code /hcm games gen mix fresh_dropper EEMHX}; a value already {@code EEMHX} needs no word. A missing
 * key is left to the backfill, which writes the shipped {@code EEMHX}. {@code fresh_dropper_easy} is never
 * read: the Easy Dropper does not change by one block, and an X can't be typed onto its 64-tall half.
 * Nothing else of the entry is touched: {@code enabled}, {@code origin}, {@code half_gap}. An admin's own
 * mix set by command ({@code GenAdminKeys.tier}) wins over config as it always has and is not touched
 * either: the README says to run the command again if they ever set one.
 *
 * <p><b>The size change is the engine's</b>, as for the v4 areas ({@link GamesAreaMigration}): the Defs
 * are taller, so at the restart the old 64-tall claim no longer matches, it is recorded in
 * {@code gen.<slot>.old}, the old area is emptied by itself (the pools drained first, before the new box,
 * which contains it, is claimed and built), and the Dropper is built again in its 144 box on a fresh board.
 * No origin moves, so no {@code LayoutGuard} entry: an install that built at 0.35's spots keeps them.
 *
 * <p>Pure on a {@link FileConfiguration}; runs before the database opens ({@code migrateConfig}), after
 * revision 24 (the Firework Remote).
 */
public final class DropperMixMigration {

    /** The config revision this step is ({@code HomeCraftManagement.CONFIG_REVISION} is at least this). */
    public static final int REVISION = 25;

    /** What every version before this one shipped as the Dropper's mix. */
    public static final String OLD_MIX = "EEMMH";

    /** The Dropper's config entry: {@code games.fresh.slots.fresh_dropper}. */
    static final String ENTRY = GamesAreaMigration.FRESH + ".slots." + Slots.FRESH_DROPPER.id();

    /** Its mix key. */
    static final String KEY = ENTRY + ".mix";

    private DropperMixMigration() {
    }

    /**
     * Revision 25's step on the on-disk file (defaults-free): the shipped mix moves, the owner's own stays.
     * One INFO line for a move, one {@code WARN } line ({@link LayoutGuard#WARN}) for a kept value.
     *
     * @return whether {@code c} changed
     */
    public static boolean apply(FileConfiguration c, List<String> log) {
        if (!(c.get(ENTRY, null) instanceof ConfigurationSection s)) {
            return false; // missing (the backfill writes the shipped entry), or a bare switch: off anyway
        }
        Object raw = s.get("mix", null);
        if (raw == null) {
            return false; // left to the backfill, which writes the shipped EEMHX
        }
        String shipped = Slots.FRESH_DROPPER.tierOrMix();
        String mix = raw instanceof String t ? t.trim().toUpperCase(Locale.ROOT) : "";
        if (mix.equals(shipped)) {
            return false;
        }
        if (mix.equals(OLD_MIX)) {
            s.set("mix", shipped);
            log.add("Config migration: the Dropper's shipped mix " + OLD_MIX + " becomes " + shipped
                    + " (level 5 is the Long Drop). Its halves are 64 x " + Slots.FRESH_DROPPER.sizeY()
                    + " x 16 now; after the restart its old area is emptied by itself (pools first) and the"
                    + " Dropper is built again, taller, at the same spot.");
            return true;
        }
        log.add(LayoutGuard.WARN + "Config migration: kept your own " + KEY + " " + raw + " for "
                + Slots.FRESH_DROPPER.name() + ". The shipped mix is " + shipped + " now (level 5 is the Long Drop):"
                + " /hcm games gen mix " + Slots.FRESH_DROPPER.id() + " " + shipped + " sets it, from the next build.");
        return false;
    }
}
