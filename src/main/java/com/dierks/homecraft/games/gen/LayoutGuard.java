package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenAdminKeys;
import com.dierks.homecraft.games.gen.engine.KeepArea;
import com.dierks.homecraft.games.gen.engine.KeptPlot;
import com.dierks.homecraft.games.gen.engine.Regions;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenArchiveDao;
import com.dierks.homecraft.storage.GenMetaDao;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The legacy guard (LAYOUT-DECISIONS item 5): the one-time answer to "does this server keep 0.35's
 * Games spots, or take the new layout?", and config.yml written to say so.
 *
 * <p><b>Why it is needed.</b> This version moves every shipped Games spot far apart (LAYOUT-SPEC
 * §1.5) and widens the gaps a course's spare half and the kept courses stand at ({@code half_gap}
 * and {@code keep.plot_gap}, 576 now, 32 and 0 in 0.35). The engine reads a changed origin or gap
 * as a moved area: a Fresh course built at the old spot is rerolled into the new one with a new board
 * and its old blocks are left standing (a Dropper's or golf course's water is guarded there until
 * someone drains it by hand), and the Clubhouse and the arena build a second room. So a server that
 * built anything in 0.35 must keep every spot and shape exactly as it was, and one that never built
 * anything takes the new layout. That is {@link Decision#LEGACY} and {@link Decision#NEW}.
 *
 * <p><b>Why two steps.</b> Whether anything was built is in the database, and config.yml is migrated
 * before the database opens. So config revision 19 only marks a file that came from an older version
 * ({@value #PENDING_KEY}, with the places whose origin was missing then: {@link #markPending}); this
 * guard runs right after the
 * database opens and before any Games code reads the settings ({@link #run}), decides, rewrites
 * config.yml, and stamps the decision in {@code hcm_meta} ({@value #STAMP_KEY}).
 *
 * <p><b>Order, and why it is crash-safe.</b> Decide (from the database, which nothing changes before
 * the stamp, so the same answer every time), then write the file (its marker goes in the same write),
 * then stamp. Applying a decision to a file that already has it changes nothing, so a stop anywhere
 * before the stamp just decides again with the same inputs and writes the same file; a stop after it
 * finds nothing to do. The Games start only once the guard has finished, so nothing is ever built
 * before the stamp exists: "no stamp" means "anything built was built by 0.35", unless the stamp was
 * lost since. A claim of this version's shape says so ({@link #builtNew}: 0.35 never wrote one), and
 * then the server keeps the new layout: a file already through revision 19 is left as it is, rather
 * than given 0.35's spots over the courses built at the new ones. A file still below revision 19 (the
 * migration couldn't save it, so it was never marked) counts as marked, so nothing is stamped or
 * started until the decision is in the file.
 *
 * <p><b>What each decision writes</b> ({@link #apply}), for the 16 places ({@link #AREAS}):
 * <ul>
 *   <li>{@link Decision#LEGACY}: every place's 0.35 origin (one whose key was missing before the
 *       backfill wrote the new value gets its 0.35 value back; an owner's own stays), and
 *       {@code half_gap: 32} for each course and Classic and {@code keep.plot_gap: 0} where none is set.
 *       Every claim then still matches, word for word, and nothing moves.</li>
 *   <li>{@link Decision#NEW}, for a file that came from an older version: each origin still exactly at
 *       its 0.35 value moves to the new one. An owner's own origin stays, with the legacy gap, so its
 *       shape stays too (LAYOUT-SPEC §4.1). A place whose new spot would crowd an owner's spot keeps its
 *       0.35 spot and shape instead. A new install's file (already the new layout) is left alone.</li>
 * </ul>
 *
 * <p>A stamped server whose file comes back from an older version (a restored config.yml, which
 * revision 19 marks again) gets the STAMPED decision again, never a new one: things may have been
 * built at the decided spots since.
 *
 * <p><b>v4: the resized places</b> ({@link #RESIZED}). Golf of the Week, Classic Golf and the Ice Boat are
 * bigger from v4 on, so no decision keeps their 0.35 spot or shape: both write their shipped spot (an
 * owner's own stays) and drop a {@code half_gap: 32}. Config revision 21 ({@code GamesAreaMigration}) moves
 * a file that is already past revision 19 the same way; the engine empties their old areas (RETIRE).
 */
public final class LayoutGuard {

    /** The {@code hcm_meta} key the decision is stamped in ({@link Stamp#text()}). */
    public static final String STAMP_KEY = "gen.layout.guard";
    /**
     * The top-level config.yml section revision 19 marks a file with, until the guard has run. Top level,
     * so a file from before the Games still gets the backfill's whole commented {@code games} block.
     */
    public static final String PENDING_KEY = "games_layout_check";
    /** Under it: the ids of the places whose origin was missing before the backfill wrote the new one. */
    public static final String MISSING = PENDING_KEY + ".missing";
    /** Fresh Courses' block: {@code games.fresh}. */
    static final String FRESH = GamesConfig.PATH + "." + GamesConfig.FRESH_BLOCK;

    /** What the server keeps. */
    public enum Decision {
        /** Something was built in 0.35: every place keeps its 0.35 spot and shape. */
        LEGACY,
        /** Nothing was built: the new layout. */
        NEW;

        String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Decision of(String word) {
            for (Decision d : values()) {
                if (d.word().equals(word)) {
                    return d;
                }
            }
            return null;
        }
    }

    /** What kind of place an {@link Area} is, which decides its keys and its shape. */
    public enum Kind {
        SLOT, CLASSIC, KEEP, CLUBHOUSE, ARENA
    }

    /**
     * One of the 16 places whose spot this version moves.
     *
     * @param id      the slot id, or {@code keep}, {@code clubhouse}, {@code falling_floors}
     * @param name    what an owner reads
     * @param legacy  its 0.35.0 origin (the frozen table; {@code LayoutGuardTest} pins it to 0.35's file)
     * @param shipped its origin in this version
     * @param def     the slot, for a course or Classic; {@code null} otherwise
     */
    public record Area(String id, String name, Kind kind, List<Integer> legacy, List<Integer> shipped,
                       Slots.Def def) {

        public Area {
            legacy = List.copyOf(legacy);
            shipped = List.copyOf(shipped);
        }

        /** The config path of its entry: the slot's section, a Classic's section or list, or the origin. */
        public String entry() {
            return switch (kind) {
                case SLOT -> FRESH + ".slots." + id;
                case CLASSIC -> FRESH + ".classics.slots." + id;
                case KEEP -> FRESH + ".keep.area";
                case CLUBHOUSE -> GamesConfig.PATH + ".clubhouse.origin";
                case ARENA -> GamesConfig.PATH + ".falling_floors.origin";
            };
        }

        /** The config path of its origin. */
        public String originPath() {
            return kind == Kind.SLOT || kind == Kind.CLASSIC ? entry() + ".origin" : entry();
        }

        /** The config path of its gap, or {@code null} for the Clubhouse and the arena (one box each). */
        public String gapPath() {
            return switch (kind) {
                case SLOT, CLASSIC -> entry() + ".half_gap";
                case KEEP -> FRESH + ".keep.plot_gap";
                default -> null;
            };
        }

        /** 0.35's gap: 32 between a course's halves, 0 between kept plots; -1 without one. */
        public int legacyGap() {
            return switch (kind) {
                case SLOT, CLASSIC -> Slots.LEGACY_HALF_GAP;
                case KEEP -> KeepArea.LEGACY_GAP;
                default -> -1;
            };
        }

        /** This version's default gap; -1 without one. */
        int shippedGap() {
            return switch (kind) {
                case SLOT, CLASSIC -> Slots.HALF_GAP;
                case KEEP -> KeepArea.DEFAULT_GAP;
                default -> -1;
            };
        }

        /**
         * Whether a later version changed this place's size, so its 0.35 shape can't be kept
         * ({@link #RESIZED}): it takes its shipped spot and gap in both decisions.
         */
        public boolean resized() {
            return RESIZED.contains(id);
        }

        /** Its blocks at {@code origin} and {@code gap} ({@code plots} for the keep area), for the crowding rule. */
        List<Box> boxes(int[] origin, int gap, int plots) {
            int x = Math.floorDiv(origin[0], Slots.GAP_GRID) * Slots.GAP_GRID;
            int z = Math.floorDiv(origin[2], Slots.GAP_GRID) * Slots.GAP_GRID;
            int[] at = {x, origin[1], z};
            return switch (kind) {
                case SLOT, CLASSIC -> Regions.halves(def, at, Math.max(0, gap));
                case KEEP -> List.of(new KeepArea(x, origin[1], z, Math.max(1, plots), Math.max(0, gap)).area());
                case CLUBHOUSE -> List.of(Box.sized(x, origin[1], z, 32, 16, 32));
                case ARENA -> List.of(Box.sized(x, origin[1], z, 48, 40, 48));
            };
        }
    }

    /**
     * The 16 places, in config order: the nine courses, the four Classics, the keep area, then the
     * arena and the Clubhouse. The 0.35 origins are written out, not read from anywhere: they are
     * what 0.35.0 shipped, whatever this version ships.
     */
    public static final List<Area> AREAS = List.of(
            slot(Slots.DAILY_PARKOUR_EASY, 4096, 160, 4096),
            slot(Slots.DAILY_PARKOUR_MEDIUM, 4352, 160, 4096),
            slot(Slots.DAILY_PARKOUR_HARD, 4608, 160, 4096),
            slot(Slots.SKY_RINGS, 4096, 128, 4352),
            slot(Slots.DAILY_GOLF, 4864, 160, 4096),
            slot(Slots.TINY_GOLF, 5120, 160, 4096),
            slot(Slots.ICE_BOAT, 4480, 160, 4352),
            slot(Slots.EASY_DROPPER, 5376, 160, 4096),
            slot(Slots.FRESH_DROPPER, 5376, 160, 4160),
            slot(Slots.CLASSIC_PARKOUR, 4096, 160, 4736),
            slot(Slots.CLASSIC_RINGS, 4608, 128, 4736),
            slot(Slots.CLASSIC_GOLF, 4352, 160, 4736),
            slot(Slots.CLASSIC_DROPPER, 5376, 160, 4224),
            new Area("keep", "the kept courses' area", Kind.KEEP, List.of(4096, 128, 5376),
                    DailySettings.Archive.KEEP_AREA, null),
            new Area("falling_floors", "Falling Floors", Kind.ARENA, List.of(5376, 176, 4352),
                    com.dierks.homecraft.games.arena.FallingFloorsSettings.ORIGIN, null),
            new Area("clubhouse", "the Clubhouse", Kind.CLUBHOUSE, List.of(5376, 160, 4448),
                    com.dierks.homecraft.games.clubhouse.ClubhouseSettings.ORIGIN, null));

    /**
     * The places whose size v4 changed (GOLF-V4-SPEC §5.2 step 1, MOUNTAIN-V2-SPEC §10.3 item 7): Golf of the
     * Week and Classic Golf (128 x 16 x 224 now; a 128-wide half at a 0.35 spot would overlap Tiny Golf's
     * 0.35 box) and the Ice Boat (the Mountain Run v2's 480 x 176 x 640). None is ever "legacy-held": each
     * takes its shipped spot and the default gap in BOTH decisions, unless the owner set their own spot,
     * which stays (the slot then resizes in place). Whatever stood at its old spot is emptied by itself
     * (RETIRE, {@code gen.<slot>.old}), so moving it never strands blocks.
     */
    public static final Set<String> RESIZED = Set.of(Slots.DAILY_GOLF.id(), Slots.CLASSIC_GOLF.id(),
            Slots.ICE_BOAT.id());

    private static Area slot(Slots.Def def, int x, int y, int z) {
        return new Area(def.id(), def.name(), Slots.isClassic(def.id()) ? Kind.CLASSIC : Kind.SLOT,
                List.of(x, y, z), List.of(def.originX(), def.originY(), def.originZ()), def);
    }

    private LayoutGuard() {
    }

    // ---- what a config reset leaves alone -------------------------------------------------------

    /**
     * The config paths that say where the Games places stand and how their halves and plots are spaced
     * ({@code /hcm config reset} leaves them as they are: {@code ConfigReset.kept}),
     * as {@code c} holds them: each course's and Classic's {@code origin} and {@code half_gap} (a Classic
     * written as a bare {@code [x, y, z]} list: its whole entry), {@code keep.area} and
     * {@code keep.plot_gap}, and the Clubhouse's and the arena's {@code origin}.
     */
    public static List<String> spotPaths(FileConfiguration c) {
        List<String> out = new ArrayList<>();
        for (Area a : AREAS) {
            if (a.kind() == Kind.CLASSIC && c.get(a.entry(), null) instanceof List<?>) {
                out.add(a.entry());
                continue;
            }
            out.add(a.originPath());
            if (a.gapPath() != null) {
                out.add(a.gapPath());
            }
        }
        return out;
    }

    // ---- revision 19: the mark --------------------------------------------------------------

    /**
     * Config revision 19's step, before the database is open and before the backfill: mark a file that
     * came from an older version, so the guard decides for it once the database can say what was built,
     * and note which places have no origin in it yet. The backfill then writes this version's value for
     * those; on a server that keeps 0.35's layout, that value is put back to 0.35's (it was 0.35's
     * default the server built with), while a new value an owner typed in stays theirs. Nothing else is
     * written: the guard runs before any Games code reads the settings.
     */
    public static void markPending(FileConfiguration c) {
        List<String> missing = new ArrayList<>();
        for (Area a : AREAS) {
            if (held(c, a) == Held.ABSENT) {
                missing.add(a.id());
            }
        }
        c.set(MISSING, missing);
        try {
            c.setComments(PENDING_KEY, List.of("Written by the plugin at the update to the new Games layout, and gone"
                    + " again once it has checked", "what your server built (/hcm games check shows the result)."
                    + " Don't edit this by hand."));
        } catch (Throwable ignored) {
            // Comment API unavailable on this server: the mark still stands.
        }
    }

    /** Whether {@code c} carries the mark. */
    public static boolean pending(FileConfiguration c) {
        return c.get(PENDING_KEY, null) != null;
    }

    /**
     * The config revision that brings this layout (and the mark): step 19 of the config migration. A file
     * may be at a later revision ({@code HomeCraftManagement.CONFIG_REVISION}); this one stays 19.
     */
    public static final int REVISION = 19;

    /**
     * Whether {@code c} is still below {@link #REVISION}: the config migration couldn't save it (a
     * read-only file, say), so revision 19 never marked it, though it came from an older version. It is
     * pending all the same ({@link #run}): the decision is written into it before anything is stamped, so
     * while it can't be written the run stops and the Games stay off, rather than start on 0.35's origins
     * at this version's gaps and be moved from there once the file is marked at a later start.
     */
    public static boolean unmigrated(FileConfiguration c) {
        return !(c.get("config_revision", null) instanceof Number n) || n.intValue() < REVISION;
    }

    /** The places the mark notes as having had no origin before the backfill. */
    static List<String> missing(FileConfiguration c) {
        return c.getStringList(MISSING);
    }

    // ---- what the database says was built --------------------------------------------------

    /**
     * What the database holds about the Games' places.
     *
     * @param meta     every {@code hcm_meta} key under {@code gen.} and its value
     * @param editions how many sets the archive holds ({@code gen_editions})
     * @param rows     the Fresh courses and Classics that have a course row
     */
    public record Facts(Map<String, String> meta, int editions, List<String> rows) {

        public Facts {
            meta = Map.copyOf(meta == null ? Map.of() : meta);
            rows = List.copyOf(rows == null ? List.of() : rows);
        }
    }

    /**
     * One rule for "something was built": its name (for the mutation test) and what it finds, as
     * lines an owner reads (none: it found nothing).
     */
    record Evidence(String name, Function<Facts, List<String>> find) {
    }

    /** The Clubhouse's claim ({@code ClubhouseRoom.CLAIM_KEY}; spelled out so this stays free of Bukkit). */
    static final String CLUBHOUSE_CLAIM = "gen.clubhouse.claim";
    /** The arena's claim ({@code ArenaService.CLAIM_KEY}). */
    static final String ARENA_CLAIM = "gen.floors.claim";

    /**
     * Every rule. Built means any live claim or Fresh edition, a Classic holding a recall, a kept
     * course (or one being kept), the Clubhouse claimed or built, or the arena claimed
     * (LAYOUT-DECISIONS item 5), and, to be safe, a slot's old wet halves and any course row of a
     * Fresh course or Classic. A server wrongly counted as built only keeps the old layout (a WARN in
     * the check); one wrongly counted as empty would have its courses rerolled away from their blocks,
     * so every rule errs towards "built".
     */
    static final List<Evidence> RULES = List.of(
            new Evidence("claims", f -> each(f, id -> GenAdminKeys.claim(id), "'s area is claimed")),
            new Evidence("wet", f -> each(f, id -> GenAdminKeys.wet(id), "'s old area may still hold water")),
            new Evidence("old", f -> each(f, id -> GenAdminKeys.old(id), "'s old area still stands")),
            new Evidence("recalls", f -> {
                List<String> out = new ArrayList<>();
                for (Slots.Def d : Slots.CLASSICS) {
                    if (set(f.meta().get(GenAdminKeys.recall(d.id())))) {
                        out.add(d.name() + " holds a recalled course");
                    }
                }
                return out;
            }),
            new Evidence("kept", f -> {
                long n = f.meta().keySet().stream().filter(k -> GenAdminKeys.plotOf(k) > 0).count();
                return n == 0 ? List.of() : List.of(n + " kept course" + (n == 1 ? "" : "s"));
            }),
            new Evidence("keeping", f -> set(f.meta().get(GenAdminKeys.KEEP_PENDING))
                    ? List.of("a course being kept") : List.of()),
            new Evidence("clubhouse", f -> set(f.meta().get(CLUBHOUSE_CLAIM))
                    ? List.of("the Clubhouse is claimed") : List.of()),
            new Evidence("arena", f -> set(f.meta().get(ARENA_CLAIM))
                    ? List.of("the Falling Floors arena is claimed") : List.of()),
            new Evidence("archive", f -> f.editions() > 0 ? List.of(f.editions() + " Fresh Courses set"
                    + (f.editions() == 1 ? "" : "s") + " in the archive") : List.of()),
            new Evidence("rows", f -> f.rows().isEmpty() ? List.of()
                    : List.of("course rows of " + String.join(", ", f.rows()))));

    /** Every Fresh course and Classic whose key {@code key(id)} is set, as "<name><what>". */
    private static List<String> each(Facts f, Function<String, String> key, String what) {
        List<String> out = new ArrayList<>();
        for (Slots.Def d : all()) {
            if (set(f.meta().get(key.apply(d.id())))) {
                out.add(d.name() + what);
            }
        }
        return out;
    }

    private static boolean set(String v) {
        return v != null && !v.isBlank();
    }

    private static List<Slots.Def> all() {
        List<Slots.Def> out = new ArrayList<>(Slots.ALL);
        out.addAll(Slots.CLASSICS);
        return out;
    }

    /**
     * What was built at this version's own spots and shapes, which 0.35 never wrote: a course's or
     * Classic's claim (or old wet halves) with its gap as an 8th field (0.35's claims have 7), the
     * Clubhouse or the arena claimed at its new box, a course kept in a plot of the new keep area. With
     * no stamp these can only mean one was lost after this version built at the new spots: the server
     * took the new layout ({@link #run}), and 0.35's spots would move what stands there.
     */
    static List<String> builtNew(Facts f) {
        return located(f, true);
    }

    /** What was built at 0.35's own spots and shapes: the same places, as {@link #builtNew}. */
    static List<String> builtOld(Facts f) {
        return located(f, false);
    }

    private static List<String> located(Facts f, boolean shipped) {
        List<String> out = new ArrayList<>();
        for (Slots.Def d : all()) {
            List<String> claims = new ArrayList<>();
            String claim = f.meta().get(GenAdminKeys.claim(d.id()));
            if (Regions.claimOrigin(claim) != null) {
                claims.add(claim);
            }
            claims.addAll(Regions.wetClaims(f.meta().get(GenAdminKeys.wet(d.id()))));
            claims.addAll(Regions.oldClaims(f.meta().get(GenAdminKeys.old(d.id()))));
            for (String c : claims) {
                if ((c.split(",").length == 8) == shipped) {
                    out.add(d.name() + " was claimed at " + (shipped ? "this version's" : "0.35's") + " shape (" + c
                            + ")");
                    break;
                }
            }
        }
        for (Area a : AREAS) {
            if (a.kind() != Kind.CLUBHOUSE && a.kind() != Kind.ARENA) {
                continue;
            }
            String claim = f.meta().get(a.kind() == Kind.CLUBHOUSE ? CLUBHOUSE_CLAIM : ARENA_CLAIM);
            Box box = a.boxes(ints(shipped ? a.shipped() : a.legacy()), 0, 1).get(0);
            String at = box.minX() + "," + box.minY() + "," + box.minZ() + "," + box.sizeX() + "," + box.sizeY() + ","
                    + box.sizeZ();
            if (claim != null && claim.indexOf(',') >= 0 && claim.substring(claim.indexOf(',') + 1).trim().equals(at)) {
                out.add(a.name() + " was claimed at its " + (shipped ? "new" : "0.35") + " spot");
            }
        }
        List<Integer> keep = AREAS.stream().filter(a -> a.kind() == Kind.KEEP).findFirst().orElseThrow()
                .legacy();
        keep = shipped ? DailySettings.Archive.KEEP_AREA : keep;
        for (Map.Entry<String, String> e : f.meta().entrySet()) {
            int n = GenAdminKeys.plotOf(e.getKey());
            KeptPlot p = n < 1 ? null : KeptPlot.parse(n, e.getValue());
            if (p != null && p.box().equals(new KeepArea(keep.get(0), keep.get(1), keep.get(2), n,
                    shipped ? KeepArea.DEFAULT_GAP : KeepArea.LEGACY_GAP).plot(n))) {
                out.add("a course was kept in plot " + n + " of the " + (shipped ? "new" : "0.35") + " keep area");
            }
        }
        return out;
    }

    /** Why the server counts as built, by every rule: empty when nothing was. */
    public static List<String> built(Facts f) {
        return built(f, RULES);
    }

    static List<String> built(Facts f, List<Evidence> rules) {
        List<String> out = new ArrayList<>();
        for (Evidence e : rules) {
            out.addAll(e.find().apply(f));
        }
        return out;
    }

    // ---- the stamp ----------------------------------------------------------------------------

    /**
     * The decision as stamped: {@code legacy|<epoch ms>|<why>} or {@code new|<epoch ms>|}.
     *
     * @param why what counted as built, "; "-joined ("" for {@link Decision#NEW})
     */
    public record Stamp(Decision decision, long at, String why) {

        public Stamp {
            why = why == null ? "" : why.replace('|', '/');
        }

        public String text() {
            return decision.word() + "|" + at + "|" + why;
        }

        /** A stored stamp, or {@code null} when {@code text} isn't one. */
        public static Stamp parse(String text) {
            if (text == null) {
                return null;
            }
            String[] p = text.split("\\|", 3);
            if (p.length < 2 || Decision.of(p[0]) == null) {
                return null;
            }
            try {
                return new Stamp(Decision.of(p[0]), Long.parseLong(p[1]), p.length > 2 ? p[2] : "");
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** The day it was decided, for admins ("30 Sep 2026", UTC). */
        public String day() {
            return DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(
                    Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC));
        }
    }

    // ---- applying a decision -------------------------------------------------------------------

    /** What an area's origin holds in the file. */
    enum Held {
        /** Nothing there (or under a value that isn't a section). */
        ABSENT,
        /** Exactly its 0.35 value. */
        LEGACY,
        /** Exactly this version's value. */
        SHIPPED,
        /** Anything else: the owner's. */
        OWN
    }

    /**
     * Write {@code d} into {@code c} (the on-disk config.yml, defaults-free) and take the mark away.
     * Changing nothing when the file already says it: running it twice is running it once.
     *
     * @param pending the file came from an older version (it carries the mark)
     * @param why     what counted as built (for the log)
     * @param stamp   the stamp this decision came from, or {@code null} when it was just made
     * @param log     gets one line per group (a line starting {@code "WARN "} is a warning)
     * @return whether {@code c} changed
     */
    public static boolean apply(FileConfiguration c, Decision d, boolean pending, List<String> why, Stamp stamp,
                                List<String> log) {
        return apply(c, d, pending, why, stamp, false, log);
    }

    /**
     * {@link #apply(FileConfiguration, Decision, boolean, List, Stamp, List)}; {@code lost}: the decision is
     * {@link Decision#NEW} because the server built at the new spots though no stamp was found ({@link #run}
     * has said so).
     */
    static boolean apply(FileConfiguration c, Decision d, boolean pending, List<String> why, Stamp stamp,
                         boolean lost, List<String> log) {
        boolean changed = false;
        List<String> missing = pending ? missing(c) : null;
        if (pending) {
            c.set(PENDING_KEY, null);
            changed = true;
        }
        if (d == Decision.LEGACY) {
            changed |= keepLegacy(c, missing, why, stamp, log);
        } else if (pending) {
            changed |= takeNew(c, stamp, lost, log);
        } else if (stamp == null && !lost) {
            log.add("Games layout: a new install, so the Games places use the new spots, far apart, and from any"
                    + " course you can't see another.");
        }
        return changed;
    }

    /**
     * What an owner reads when the stamp holds something else: which word to write back, and never to
     * delete the row. Without one, the guard decides again from what was built, and a server that took
     * the new spots, where nothing says where it built, would be put back on 0.35's, moving every course
     * built since. A file already through revision 19 says which it has: 0.35's spots and gaps written
     * out ({@link #keepLegacy}), or not.
     */
    static String unreadableStamp(String text, FileConfiguration c, long now) {
        String advice;
        if (pending(c) || unmigrated(c)) {
            advice = "set it back to what this server chose: " + new Stamp(Decision.LEGACY, now, "").text()
                    + " if it kept its 0.35 Games spots (half_gap: 32 under every course in the config.yml it ran"
                    + " with), " + new Stamp(Decision.NEW, now, "").text() + " if it took the new ones";
        } else {
            Decision d = legacyShaped(c) ? Decision.LEGACY : Decision.NEW;
            advice = "config.yml has " + (d == Decision.LEGACY ? "0.35's Games spots and gaps written out" : "the new"
                    + " Games spots") + ", so set it back to " + new Stamp(d, now, "").text();
        }
        return STAMP_KEY + " (in hcm_meta) holds \"" + text + "\", which isn't a layout decision (legacy or new): "
                + advice + ". Don't delete it: the check would decide again from what was built, and that can move a"
                + " server that took the new spots back to 0.35's";
    }

    /**
     * Whether every course and Classic in {@code c} that has a gap holds 0.35's, as {@link #keepLegacy} writes
     * (the {@link #RESIZED} places aside: no decision keeps their 0.35 shape).
     */
    static boolean legacyShaped(FileConfiguration c) {
        boolean any = false;
        for (Area a : AREAS) {
            if ((a.kind() == Kind.SLOT || a.kind() == Kind.CLASSIC) && !blocked(c, a) && !a.resized()) {
                any = true;
                if (!(c.get(a.gapPath(), null) instanceof Number n) || n.intValue() != a.legacyGap()) {
                    return false;
                }
            }
        }
        return any;
    }

    /**
     * {@link Decision#LEGACY}: every place's 0.35 spot and shape, written out. {@code missing} (the mark's
     * list, or {@code null} for a file that came without one) says which of this version's values the
     * backfill wrote: those go back to 0.35's. Without a mark every such value came from the bundled
     * file, since an owner can only have chosen one after a stamp, when this never runs again.
     */
    private static boolean keepLegacy(FileConfiguration c, List<String> missing, List<String> why, Stamp stamp,
                                      List<String> log) {
        boolean changed = false;
        List<String> own = new ArrayList<>();
        for (Area a : AREAS) {
            if (blocked(c, a)) {
                continue;
            }
            Held h = held(c, a);
            if (a.resized()) {
                changed |= takeShipped(c, a, h);
                continue;
            }
            boolean gapSet = gapSet(c, a);
            boolean backfilled = h == Held.SHIPPED && (missing == null || missing.contains(a.id()));
            // One the plugin can't read: 0.35 fell back to its own shipped spot there, so that is where it built.
            boolean unreadable = h == Held.OWN && !readable(a, origin(c, a));
            if ((h == Held.ABSENT || backfilled || unreadable) && !gapSet) {
                writeOrigin(c, a, a.legacy());
                changed = true;
            } else if (h == Held.OWN || h == Held.SHIPPED) {
                own.add(a.originPath());
            }
            if (a.gapPath() != null && !gapSet) {
                writeGap(c, a);
                changed = true;
            }
        }
        if (stamp != null) {
            log.add("Games layout: config.yml came from an older version again; it keeps the choice made on "
                    + stamp.day() + ": every Games place at its 0.35 spot and shape.");
            return changed;
        }
        log.add(WARN + "Games layout: this server had already built Games places ("
                + String.join(", ", why == null ? List.of() : why) + "), so every one keeps its 0.35 spot and shape:"
                + " config.yml now says so (the 0.35 origins, half_gap: 32 for each course and Classic, and"
                + " keep.plot_gap: 0). From some courses players can see others; /hcm games check lists them and"
                + " how to move one by hand (README \"Moving an area by hand\"). Golf of the Week, Classic Golf and"
                + " the Ice Boat are bigger now, so they take their new spots; what stands at their old ones is"
                + " emptied by itself."
                + (own.isEmpty() ? "" : " Your own spots stay too: " + String.join(", ", own) + "."));
        return changed;
    }

    /** {@link Decision#NEW} for a file from an older version: the untouched 0.35 spots move. */
    private static boolean takeNew(FileConfiguration c, Stamp stamp, boolean lost, List<String> log) {
        boolean changed = false;
        int plots = plots(c);
        List<Area> candidates = new ArrayList<>();
        List<Area> own = new ArrayList<>();
        List<Box> fixed = new ArrayList<>();
        List<String> resized = new ArrayList<>();
        for (Area a : AREAS) {
            if (blocked(c, a)) {
                continue;
            }
            Held h = held(c, a);
            if (a.resized()) {
                if (takeShipped(c, a, h)) {
                    changed = true;
                    resized.add(a.name());
                }
                continue;
            }
            if (h != Held.OWN && !gapSet(c, a)) {
                candidates.add(a);
                continue;
            }
            own.add(a);
            int[] at = readOrigin(origin(c, a));
            if (at != null) {
                fixed.addAll(a.boxes(at, gapOf(c, a), plots));
            }
        }
        // A place whose new spot would crowd an owner's spot keeps its 0.35 one (which may in turn crowd
        // another's new spot, so round again until nothing changes).
        Set<Area> stay = new LinkedHashSet<>();
        boolean again = true;
        while (again) {
            again = false;
            for (Area a : candidates) {
                if (!stay.contains(a) && crowds(a.boxes(ints(a.shipped()), a.shippedGap(), plots), fixed)) {
                    stay.add(a);
                    fixed.addAll(a.boxes(ints(a.legacy()), a.legacyGap(), plots));
                    again = true;
                }
            }
        }
        List<String> moved = new ArrayList<>(resized);
        for (Area a : candidates) {
            Held h = held(c, a);
            if (stay.contains(a)) {
                if (h != Held.LEGACY) {
                    writeOrigin(c, a, a.legacy());
                }
                if (a.gapPath() != null) {
                    writeGap(c, a);
                }
                changed = true;
                log.add(WARN + "Games layout: " + a.name() + " keeps its 0.35 spot (" + a.originPath() + " = "
                        + a.legacy() + "), because your own spot for " + crowder(c, a, own, plots)
                        + " is where it would go. /hcm games check says what players can see.");
            } else if (h == Held.LEGACY) {
                writeOrigin(c, a, a.shipped());
                changed = true;
                moved.add(a.name());
            }
        }
        for (Area a : own) {
            if (a.gapPath() != null && !gapSet(c, a)) {
                writeGap(c, a);
                changed = true;
                log.add(WARN + "Games layout: kept " + a.originPath() + " = " + origin(c, a) + " because you set it."
                        + " Its " + (a.kind() == Kind.KEEP ? "plots stay touching (keep.plot_gap: 0 was added)"
                        : "spare half stays 32 blocks away (half_gap: 32 was added)") + ", so players can see it;"
                        + " /hcm games check says more.");
            } else if (a.gapPath() == null) {
                log.add("Games layout: kept " + a.originPath() + " = " + origin(c, a) + " because you set it;"
                        + " /hcm games check says what can be seen from it.");
            }
        }
        if (stamp != null || lost) {
            log.add("Games layout: config.yml came from an older version again; it keeps "
                    + (stamp != null ? "the choice made on " + stamp.day() : "the layout it built with") + ": the new"
                    + " spots" + (moved.isEmpty() ? "." : " (moved there again: " + String.join(", ", moved) + ")."));
        } else if (!moved.isEmpty()) {
            log.add("Games layout: nothing was built yet, so the Games places take their new spots, far apart, and"
                    + " from any course you can't see another. Moved the spots you hadn't changed: "
                    + String.join(", ", moved) + ".");
        } else {
            log.add("Games layout: nothing was built yet, and every Games place already has its new spot or your"
                    + " own.");
        }
        return changed;
    }

    /**
     * A {@link Area#resized} place, in either decision: an origin still at a value this plugin shipped (0.35's,
     * or one that can't be read, which 0.35 fell back from to its own) becomes this version's, and a
     * {@code half_gap} of 0.35's 32 goes with it, so the default 576 applies; an owner's own readable spot
     * stays as it is (the slot is resized in place there). Nothing is written for one already at its shipped
     * spot, or missing (the backfill writes the shipped one).
     *
     * @return whether {@code c} changed
     */
    private static boolean takeShipped(FileConfiguration c, Area a, Held h) {
        boolean ours = h == Held.LEGACY || (h == Held.OWN && !readable(a, origin(c, a)));
        boolean changed = false;
        if (ours) {
            writeOrigin(c, a, a.shipped());
            changed = true;
        }
        if ((ours || h == Held.SHIPPED || h == Held.ABSENT) && a.gapPath() != null
                && c.get(a.gapPath(), null) instanceof Number n && n.intValue() == a.legacyGap()) {
            c.set(a.gapPath(), null);
            changed = true;
        }
        return changed;
    }

    /** The first owner spot that {@code a}'s new spot would crowd, for the WARN. */
    private static String crowder(FileConfiguration c, Area a, List<Area> own, int plots) {
        List<Box> mine = a.boxes(ints(a.shipped()), a.shippedGap(), plots);
        for (Area o : own) {
            int[] at = readOrigin(origin(c, o));
            if (at != null && crowds(mine, o.boxes(at, gapOf(c, o), plots))) {
                return o.name();
            }
        }
        return "another place";
    }

    /** Whether any box of {@code a} is closer than {@link Regions#APART} to any of {@code b} (or on it). */
    static boolean crowds(List<Box> a, List<Box> b) {
        for (Box x : a) {
            for (Box y : b) {
                if (x.gap(y) < Regions.APART) {
                    return true;
                }
            }
        }
        return false;
    }

    /** What the file holds at {@code a}'s origin: nothing, 0.35's value, this version's, or the owner's. */
    static Held held(FileConfiguration c, Area a) {
        Object raw = origin(c, a);
        if (raw == null) {
            return Held.ABSENT;
        }
        if (same(raw, a.legacy())) {
            return Held.LEGACY;
        }
        return same(raw, a.shipped()) ? Held.SHIPPED : Held.OWN;
    }

    /** The raw origin value: a Classic's may be a bare {@code [x, y, z]} list in place of its section. */
    static Object origin(FileConfiguration c, Area a) {
        if (a.kind() == Kind.CLASSIC) {
            Object entry = c.get(a.entry(), null);
            if (entry instanceof List<?>) {
                return entry;
            }
            return entry instanceof ConfigurationSection s ? s.get("origin", null) : null;
        }
        return c.get(a.originPath(), null);
    }

    /**
     * Whether {@code raw} is exactly {@code v}: a list of three numbers, each equal to it (a whole-number
     * type compared as a long, any other by its exact double: {@code 4096.0} is 4096, {@code 4096.7} and
     * {@code "4096"} are not).
     */
    static boolean same(Object raw, List<Integer> v) {
        if (!(raw instanceof List<?> l) || l.size() != v.size()) {
            return false;
        }
        for (int i = 0; i < v.size(); i++) {
            Object o = l.get(i);
            if (o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) {
                if (((Number) o).longValue() != v.get(i)) {
                    return false;
                }
            } else if (!(o instanceof Number n) || n.doubleValue() != v.get(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the plugin reads {@code raw} as {@code a}'s origin, as 0.35 did: three whole numbers for a
     * course, a Classic and the keep area ({@link #readOrigin}); for the Clubhouse and the arena also
     * numbers written as text ({@code GamesConfig}'s lists), each whole.
     */
    static boolean readable(Area a, Object raw) {
        if (a.kind() == Kind.SLOT || a.kind() == Kind.CLASSIC || a.kind() == Kind.KEEP) {
            return readOrigin(raw) != null;
        }
        if (!(raw instanceof List<?> l) || l.size() != 3) {
            return false;
        }
        for (Object o : l) {
            double d;
            if (o instanceof Number n) {
                d = n.doubleValue();
            } else if (o instanceof String t) {
                try {
                    d = Double.parseDouble(t.trim().replace("%", ""));
                } catch (NumberFormatException e) {
                    return false;
                }
            } else {
                return false;
            }
            if (!Double.isFinite(d) || d != Math.rint(d)) {
                return false;
            }
        }
        return true;
    }

    /** Three whole numbers, as DailySettings reads an origin, or {@code null}. */
    private static int[] readOrigin(Object raw) {
        if (!(raw instanceof List<?> l) || l.size() != 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!(l.get(i) instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                    || Math.abs(n.doubleValue()) > Integer.MAX_VALUE / 2.0) {
                return null;
            }
            out[i] = n.intValue();
        }
        return out;
    }

    private static int[] ints(List<Integer> v) {
        return new int[]{v.get(0), v.get(1), v.get(2)};
    }

    /** Whether the owner's file names {@code a}'s gap. */
    private static boolean gapSet(FileConfiguration c, Area a) {
        return a.gapPath() != null && c.get(a.gapPath(), null) != null;
    }

    /** The gap {@code a} will have once written: its own when it is a whole number, else the legacy one. */
    private static int gapOf(FileConfiguration c, Area a) {
        if (a.gapPath() == null) {
            return 0;
        }
        Object g = c.get(a.gapPath(), null);
        return g instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) ? n.intValue() : a.legacyGap();
    }

    /** {@code keep.max_plots} as written (24 when it can't be read). */
    private static int plots(FileConfiguration c) {
        Object p = c.get(FRESH + ".keep.max_plots", null);
        return p instanceof Number n ? Math.max(1, Math.min(100, n.intValue())) : 24;
    }

    /**
     * Whether a value that isn't a section stands where {@code a}'s keys go ({@code games: false} that
     * the switch rewrite couldn't turn into a section, a Classic that is neither a list nor a section):
     * then nothing is written for it (that course or block is off anyway).
     */
    private static boolean blocked(FileConfiguration c, Area a) {
        String path = a.kind() == Kind.CLASSIC && c.get(a.entry(), null) instanceof List<?> ? parent(a.entry())
                : parent(a.originPath());
        for (String p = path; p != null; p = parent(p)) {
            Object v = c.get(p, null);
            if (v != null && !(v instanceof ConfigurationSection)) {
                return true;
            }
        }
        return false;
    }

    private static String parent(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? null : path.substring(0, dot);
    }

    /** Write {@code v} as {@code a}'s origin; a Classic's bare list stays a bare list. */
    private static void writeOrigin(FileConfiguration c, Area a, List<Integer> v) {
        if (a.kind() == Kind.CLASSIC && c.get(a.entry(), null) instanceof List<?>) {
            c.set(a.entry(), new ArrayList<>(v));
        } else {
            c.set(a.originPath(), new ArrayList<>(v));
        }
    }

    /** Write {@code a}'s legacy gap; a Classic's bare list becomes {@code {origin: [...], half_gap: 32}}. */
    private static void writeGap(FileConfiguration c, Area a) {
        if (a.kind() == Kind.CLASSIC && c.get(a.entry(), null) instanceof List<?> list) {
            List<String> above = comments(c, a.entry());
            ConfigurationSection s = c.createSection(a.entry()); // same map slot, so the same spot in the file
            s.set("origin", new ArrayList<>(list));
            s.set("half_gap", a.legacyGap());
            try {
                c.setComments(a.entry(), above);
            } catch (Throwable ignored) {
                // Comment API unavailable on this server: the values still stand.
            }
            return;
        }
        c.set(a.gapPath(), a.legacyGap());
    }

    private static List<String> comments(FileConfiguration c, String path) {
        try {
            return c.getComments(path);
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /** A log line to log as a warning ({@code HomeCraftManagement.WARN}). */
    public static final String WARN = "WARN ";

    // ---- running it -------------------------------------------------------------------------

    /** The database side: the stamp and what was built. */
    public interface Store {
        /** The stamp's text, or {@code null} when there is none. */
        String stamp() throws SQLException;

        void stamp(String text) throws SQLException;

        Facts facts() throws SQLException;

        /** The plugin's database: {@code hcm_meta}, {@code gen_editions} and {@code game_courses}. */
        static Store of(Database db) {
            GenMetaDao meta = new GenMetaDao(db);
            GenArchiveDao archive = new GenArchiveDao(db);
            GamesDao games = new GamesDao(db);
            return new Store() {
                @Override
                public String stamp() throws SQLException {
                    return meta.get(STAMP_KEY);
                }

                @Override
                public void stamp(String text) throws SQLException {
                    meta.set(STAMP_KEY, text);
                }

                @Override
                public Facts facts() throws SQLException {
                    List<String> rows = new ArrayList<>();
                    for (Slots.Def d : all()) {
                        if (games.course(d.id()) != null) {
                            rows.add(d.id());
                        }
                    }
                    return new Facts(meta.like(GenMetaDao.PREFIX), archive.count(null), rows);
                }
            };
        }
    }

    /** The file side: config.yml on disk, defaults-free. */
    public interface ConfigFile {
        /** The file as it is on disk, or {@code null} when it can't be read. */
        FileConfiguration load();

        /** Write it; false when it couldn't be written. */
        boolean save(FileConfiguration c);
    }

    /**
     * What a run did.
     *
     * @param decision what the server keeps ({@code null}: nothing to do, it was decided before)
     * @param changed  config.yml was rewritten (read it again)
     * @param log      lines for the console ({@link #WARN} lines are warnings)
     */
    public record Outcome(Decision decision, boolean changed, List<String> log) {

        public Outcome {
            log = List.copyOf(log);
        }
    }

    /**
     * Decide (once), write config.yml, stamp. Right after the database opens and before any Games code
     * reads the settings: at a start, and at {@code /hcm reload} (when a file from an older version
     * came back). Throws {@link IllegalStateException}, saying why, when it can't finish: the caller
     * keeps the Games from using the file until it can.
     *
     * @param now the time to stamp
     */
    public static Outcome run(Store store, ConfigFile file, long now) {
        return run(store, file, now, RULES);
    }

    static Outcome run(Store store, ConfigFile file, long now, List<Evidence> rules) {
        FileConfiguration c = file.load();
        if (c == null) {
            throw new IllegalStateException("config.yml could not be read");
        }
        boolean unmigrated = unmigrated(c);
        boolean pending = pending(c) || unmigrated;
        Stamp stamp;
        try {
            String text = store.stamp();
            stamp = Stamp.parse(text);
            if (text != null && stamp == null) {
                throw new IllegalStateException(unreadableStamp(text, c, now));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the database could not be read (" + e.getMessage() + ")", e);
        }
        if (stamp != null && !pending) {
            return new Outcome(null, false, List.of());
        }
        Decision d;
        List<String> why;
        List<String> log = new ArrayList<>();
        if (stamp != null) {
            d = stamp.decision();
            why = List.of();
        } else {
            Facts facts;
            try {
                facts = store.facts();
            } catch (SQLException e) {
                throw new IllegalStateException("the database could not be read (" + e.getMessage() + ")", e);
            }
            why = built(facts, rules);
            // Built at the new spots with no stamp: the stamp was lost after this version built there. A file
            // already through revision 19 is left exactly as it is then; a marked one moves only if nothing
            // stands at an old spot too (never both ways at once).
            List<String> newer = why.isEmpty() ? List.of() : builtNew(facts);
            d = why.isEmpty() || !newer.isEmpty() && (!pending || builtOld(facts).isEmpty()) ? Decision.NEW
                    : Decision.LEGACY;
            if (d == Decision.NEW && !why.isEmpty()) {
                log.add(WARN + "Games layout: no decision was stored (" + STAMP_KEY + "), but this server has built at"
                        + " the new spots (" + String.join("; ", newer) + "), so it keeps the new layout"
                        + (pending ? "" : "; config.yml is left as it is") + ".");
                why = List.of();
            }
        }
        boolean changed = apply(c, d, pending, why, stamp, d == Decision.NEW && !log.isEmpty(), log) || unmigrated;
        if (changed && !file.save(c)) {
            throw new IllegalStateException("config.yml could not be written" + (unmigrated ? " (it is still at config"
                    + " revision " + c.getInt("config_revision", 0) + ": the update's config migration couldn't save it"
                    + " either, so the Games wait until it can be)" : ""));
        }
        if (stamp == null) {
            try {
                store.stamp(new Stamp(d, now, String.join("; ", why)).text());
            } catch (SQLException e) {
                throw new IllegalStateException("the decision could not be stored in the database ("
                        + e.getMessage() + ")", e);
            }
        }
        return new Outcome(d, changed, log);
    }
}
