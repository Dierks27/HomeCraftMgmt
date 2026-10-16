package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.gen.api.Slots;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Config revision 21: the v4 areas (V4-DECISIONS "The merged layout", "Config revisions"). Golf v4 makes
 * Golf of the Week's and Classic Golf's halves 128 x 16 x 224 and moves them to a column of their own at
 * x 8768; the Mountain Run v2 makes the Ice Boat's halves 480 x 176 x 640 and moves them north, to
 * x 6080..7615, z 2880..3519 (GOLF-V4-SPEC §4.3, §5.2 step 1; MOUNTAIN-V2-SPEC §10.1, §11.1).
 *
 * <p><b>Only untouched shipped values move.</b> An origin still exactly at a value this plugin shipped
 * (0.36's, or 0.35's, which LayoutGuard may have written back on a server that built at 0.35's spots)
 * becomes the v4 one, and a {@code half_gap: 32} that came with 0.35's shape (beside 0.35's spot, or a missing
 * origin) goes with it (the default 576 applies; the shape changes anyway); a 32 beside 0.36's spot is the
 * owner's own and stays, with one WARN. An owner's own origin stays, with one WARN naming the shipped spot:
 * the slot is resized in place there, its old area emptied first. A missing origin is left to the
 * backfill, which writes the v4 one. Nothing else is touched: {@code enabled}, {@code tier}, {@code mix}.
 *
 * <p><b>What happens next</b> is the engine's: at the restart the old claim no longer matches (its size
 * differs), so it is recorded in {@code gen.<slot>.old}, Golf of the Week is built again at its new spot on
 * a fresh board, and RETIRE empties every old area (water first, only Fresh Courses' own blocks).
 *
 * <p>Pure on a {@link FileConfiguration}; runs before the database opens ({@code migrateConfig}), after
 * revision 20 (the token balance).
 */
public final class GamesAreaMigration {

    /** The config revision this step is ({@code HomeCraftManagement.CONFIG_REVISION} is at least this). */
    public static final int REVISION = 21;

    /** Fresh Courses' block: {@code games.fresh}. */
    static final String FRESH = GamesConfig.PATH + "." + GamesConfig.FRESH_BLOCK;

    /**
     * One area v4 moves.
     *
     * @param def     the slot (or Classics slot)
     * @param shipped every origin a version of this plugin shipped for it before v4 (0.36's first)
     */
    public record Move(Slots.Def def, List<List<Integer>> shipped) {

        public Move {
            shipped = List.copyOf(shipped);
        }

        /** Whether it is a Classics slot (its entry may be a bare {@code [x, y, z]} list). */
        public boolean classic() {
            return Slots.isClassic(def.id());
        }

        /** Its config entry: the slot's section, or the Classic's section or list. */
        public String entry() {
            return FRESH + (classic() ? ".classics.slots." : ".slots.") + def.id();
        }

        /** Its v4 origin {x, y, z}. */
        public List<Integer> to() {
            return List.of(def.originX(), def.originY(), def.originZ());
        }
    }

    /** The three areas v4 moves, with what 0.36 and 0.35 shipped for each. */
    public static final List<Move> MOVES = List.of(
            new Move(Slots.DAILY_GOLF, List.of(List.of(7488, 160, 4096), List.of(4864, 160, 4096))),
            new Move(Slots.ICE_BOAT, List.of(List.of(6080, 160, 5888), List.of(4480, 160, 4352))),
            new Move(Slots.CLASSIC_GOLF, List.of(List.of(7488, 160, 4800), List.of(4352, 160, 4736))));

    private GamesAreaMigration() {
    }

    // ---- a file the update couldn't save (F10) --------------------------------------------------------

    /**
     * Whether {@code c} (config.yml as read from disk, defaults-free) is still below {@link #REVISION}: the
     * update's config migration couldn't save it (a read-only file, a full disk), so the spots it holds for
     * the three areas v4 moves are an older version's. As {@link LayoutGuard#unmigrated} is for revision 19.
     */
    public static boolean unsaved(ConfigurationSection c) {
        return c != null && (!(c.get("config_revision", null) instanceof Number n) || n.intValue() < REVISION);
    }

    /** Why the three moved areas wait while config.yml is below {@link #REVISION} (the engine's SEVERE, the check). */
    public static String heldWhy(ConfigurationSection c) {
        Object r = c == null ? null : c.get("config_revision", null);
        return "config.yml couldn't be saved at this update (it is still at config revision "
                + (r instanceof Number n ? String.valueOf(n.intValue()) : "?") + ", and revision " + REVISION
                + " moves this area), so it stays where it was built, closed, and nothing is built or emptied there"
                + " until the file can be written: fix that (the SEVERE at the start says why it wasn't) and restart";
    }

    /**
     * F10: the Games' reading of a file below {@link #REVISION} with the three areas it would move held
     * ({@link DailySettings.SlotConfig#held}): rather than build Golf v4 at 0.36's spot (half B straddling
     * x 8192) and move it again once the file is saved, each stays where it was claimed, off, and nothing is
     * claimed, built, rerolled or emptied for it until revision 21 is written. Everything else reads as it is.
     */
    public static GamesConfig.Parsed hold(GamesConfig.Parsed p, String why) {
        if (p == null || !(p.settings().get(DailyCourses.SPEC.id()) instanceof DailySettings d)) {
            return p;
        }
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        for (DailySettings.SlotConfig c : d.slots()) {
            slots.add(moves(c.id()) ? c.held(why) : c);
        }
        List<DailySettings.SlotConfig> classics = new ArrayList<>();
        for (DailySettings.SlotConfig c : d.archive().classics()) {
            classics.add(moves(c.id()) ? c.held(why) : c);
        }
        java.util.Map<String, Object> settings = new java.util.LinkedHashMap<>(p.settings());
        settings.put(DailyCourses.SPEC.id(), d.withSlots(slots).withArchive(d.archive().withClassics(classics)));
        return new GamesConfig.Parsed(p.common(), settings, p.unreadable());
    }

    /** Whether slot {@code id} is one of the areas v4 moves. */
    static boolean moves(String id) {
        return MOVES.stream().anyMatch(m -> m.def().id().equals(id));
    }

    /**
     * Revision 21's step on the on-disk file (defaults-free). One INFO line per area moved, one
     * {@code WARN } line per owner's spot kept ({@link LayoutGuard#WARN}).
     *
     * @return whether {@code c} changed
     */
    public static boolean apply(FileConfiguration c, List<String> log) {
        boolean changed = false;
        List<String> moved = new ArrayList<>();
        for (Move m : MOVES) {
            if (blocked(c, m)) {
                continue; // a value that isn't a section stands where its keys go: it is off anyway
            }
            Object raw = origin(c, m);
            boolean already = raw != null && LayoutGuard.same(raw, m.to());
            // an origin the plugin can't read was never where anything stood (0.35 fell back to its own shipped
            // spot, 0.36 left the course off): it takes the new spot like an untouched one
            boolean ours = raw == null || already || !readable(raw)
                    || m.shipped().stream().anyMatch(v -> LayoutGuard.same(raw, v));
            if (raw != null && ours && !already) {
                writeOrigin(c, m, m.to());
                changed = true;
                moved.add(m.def().name() + " " + raw + " -> " + m.to());
            }
            if (ours && gapIs32(c, m)) {
                if (legacyShape(raw, m)) {
                    c.set(m.entry() + ".half_gap", null); // 0.35's shape went with the old spot
                    changed = true;
                } else {
                    // beside 0.36's spot no version wrote a 32: it is the owner's own, and stays (ECON03)
                    log.add(LayoutGuard.WARN + "Config migration: kept your own " + m.entry() + ".half_gap "
                            + Slots.LEGACY_HALF_GAP + " for " + m.def().name() + " at " + m.to() + ": its spare half"
                            + " is built " + Slots.LEGACY_HALF_GAP + " blocks from the one played, where players can"
                            + " see it. The shipped gap is " + Slots.HALF_GAP + ", out of sight: take half_gap out for it.");
                }
            }
            if (!ours) {
                log.add(LayoutGuard.WARN + "Config migration: kept your own " + m.entry() + ".origin " + raw + " for "
                        + m.def().name() + ". From this version its area is bigger (" + m.def().sizeX() + " x "
                        + m.def().sizeY() + " x " + m.def().sizeZ() + " a half), so it is checked again there before it"
                        + " is built, and its old area is emptied first; /hcm games check says if it doesn't fit. The"
                        + " shipped spot is " + m.to() + ".");
            }
        }
        if (!moved.isEmpty()) {
            log.add("Config migration: the bigger Golf v4 and Mountain Run v2 areas get their new spots ("
                    + String.join("; ", moved) + "). After the restart the old areas are emptied by themselves"
                    + " (ponds first, only Fresh Courses' own blocks); /hcm games check shows it.");
        }
        return changed;
    }

    /** The raw origin value: a Classic's may be a bare {@code [x, y, z]} list in place of its section. */
    static Object origin(FileConfiguration c, Move m) {
        Object entry = c.get(m.entry(), null);
        if (entry instanceof List<?>) {
            return m.classic() ? entry : null;
        }
        return entry instanceof ConfigurationSection s ? s.get("origin", null) : null;
    }

    /** Three whole numbers, as the plugin reads an origin. */
    static boolean readable(Object raw) {
        if (!(raw instanceof List<?> l) || l.size() != 3) {
            return false;
        }
        for (Object o : l) {
            if (!(o instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                    || Math.abs(n.doubleValue()) > Integer.MAX_VALUE / 2.0) {
                return false;
            }
        }
        return true;
    }

    private static void writeOrigin(FileConfiguration c, Move m, List<Integer> v) {
        if (m.classic() && c.get(m.entry(), null) instanceof List<?>) {
            c.set(m.entry(), new ArrayList<>(v));
        } else {
            c.set(m.entry() + ".origin", new ArrayList<>(v));
        }
    }

    /**
     * Whether a {@code half_gap: 32} beside {@code raw} came with 0.35's shape and goes with the old spot: the
     * origin is missing or unreadable (0.35 fell back to its own spot), or it is 0.35's shipped spot, beside which
     * LayoutGuard wrote the 32 back. Beside 0.36's spot (or the new one) no version wrote a 32: it is the owner's.
     */
    static boolean legacyShape(Object raw, Move m) {
        return raw == null || !readable(raw) || LayoutGuard.same(raw, m.shipped().get(m.shipped().size() - 1));
    }

    private static boolean gapIs32(FileConfiguration c, Move m) {
        return c.get(m.entry(), null) instanceof ConfigurationSection s && s.get("half_gap", null) instanceof Number n
                && n.doubleValue() == Slots.LEGACY_HALF_GAP;
    }

    /** Whether a value that isn't a section stands on the path to {@code m}'s entry (nothing is written then). */
    private static boolean blocked(FileConfiguration c, Move m) {
        String path = m.entry();
        for (int dot = path.lastIndexOf('.'); dot > 0; dot = path.lastIndexOf('.')) {
            path = path.substring(0, dot);
            Object v = c.get(path, null);
            if (v != null && !(v instanceof ConfigurationSection)) {
                return true;
            }
        }
        Object e = c.get(m.entry(), null);
        return e != null && !(e instanceof ConfigurationSection) && !(m.classic() && e instanceof List<?>);
    }
}
