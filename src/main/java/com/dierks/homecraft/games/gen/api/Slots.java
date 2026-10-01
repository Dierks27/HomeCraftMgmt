package com.dierks.homecraft.games.gen.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The generated courses, fixed in code (GEN-SPEC §1.1, §2.2; the weekly addendum §2 for the ids).
 *
 * <p>A slot is one generated course with a fixed id, which is also its {@code /hcm play} id, its
 * {@code game_courses} row id and its board name. Config can tune a slot or switch it off
 * ({@code games.fresh.slots.<id>}), but never add one: every id here is reserved from the day it
 * ships ({@link #RESERVED}), so no hand-built course can ever take one, and the half sizes are the
 * generators' own, so a config typo can't make a course that doesn't fit its area.
 *
 * <p>The constants keep their first names ({@code DAILY_GOLF}, {@code DAILY}...) so every package
 * written against them still compiles; their ids are the Fresh Courses ones ({@code fresh_golf},
 * {@code fresh_courses}...), and so are the names players read. Golf's name follows the cadence
 * ("Golf of the Week" / "Golf of the Day"): {@link GenCopy#slotName}.
 */
public final class Slots {

    /** The game's id and the play id of its screen (Fresh Courses). */
    public static final String DAILY = "fresh_courses";
    /**
     * The play id of the parkour level picker. Not {@code fresh_parkour}: that is the middle
     * parkour course itself.
     */
    public static final String DAILY_PARKOUR = "fresh_parkour_tiers";

    /** Generator ids ({@link Planner#id()}). */
    public static final String PARKOUR = "parkour";
    public static final String RINGS = "rings";
    public static final String GOLF = "golf";
    public static final String BOAT = "boat";
    /** The Dropper (EVENTS-DROPPER-SPEC §B.1.2): a {@code trials} row of kind {@code dropper}, set by a mix. */
    public static final String DROPPER = "dropper";

    /** The course games a slot's row belongs to. */
    public static final String GAME_TRIALS = "trials";
    public static final String GAME_GOLF = "golf";

    /**
     * The blocks between a slot's half A and half B, along +X, in 0.35.0: 2 chunks, so the spare half
     * (where the next course is built) is in plain sight of the live one. A claim written without a
     * gap was made with it ({@code Regions.claimGap}).
     */
    public static final int LEGACY_HALF_GAP = 32;
    /**
     * The gap that keeps a slot's spare half out of its live half's sight at every view distance
     * (LAYOUT-SPEC §1.2): {@link Sight#GAP}, 576 blocks (36 chunk columns).
     */
    public static final int SIGHT_HALF_GAP = Sight.GAP;
    /**
     * The gap a slot or Classics slot has when its config names none
     * ({@code games.fresh.slots.<id>.half_gap}): {@link #SIGHT_HALF_GAP}, so from the course being
     * played the spare half, where the next course is built, is out of sight. Every main-code caller
     * passes the slot's own configured gap; this is only its default. An install that built at 0.35's
     * spots has {@code half_gap: 32} written for each course, so nothing it built changes shape.
     */
    public static final int HALF_GAP = SIGHT_HALF_GAP;
    /** The smallest and largest {@code half_gap} config takes; it is a multiple of {@link #GAP_GRID}. */
    public static final int MIN_HALF_GAP = LEGACY_HALF_GAP;
    public static final int MAX_HALF_GAP = 4096;
    /** Origins and gaps stand on the 16-block (chunk) grid, so every box is chunk-aligned. */
    public static final int GAP_GRID = 16;
    /** The trial tiers a parkour, rings or boat slot may be set to. */
    public static final List<String> TIERS = List.of("easy", "medium", "hard");

    /**
     * One slot.
     *
     * @param id          its id: play id, row id, board name
     * @param generator   which planner makes it ({@link #PARKOUR}, {@link #RINGS}, {@link #GOLF}, {@link #BOAT},
     *                    {@link #DROPPER})
     * @param game        the game its row belongs to ({@link #GAME_TRIALS} or {@link #GAME_GOLF})
     * @param kind        the row's kind: {@code parkour}, {@code elytra}, {@code boat}, {@code dropper} or
     *                    {@code golf}
     * @param name        the player-facing name, no colour codes
     * @param colour      the colour its name is shown in ({@code &a} easy ... {@code &d} golf)
     * @param sizeX       one half's size, fixed per generator
     * @param sizeY       ...
     * @param sizeZ       ...
     * @param plots       golf: how many holes fit (the longest mix); a dropper: how many levels; 0 for the others
     * @param enabled     shipped on
     * @param tierOrMix   the shipped tier ({@code easy}), or golf or dropper mix ({@code EEEMMMMHH}, {@code EEMMH})
     * @param originX     the shipped origin: half A's min corner (multiples of 16)
     * @param originY     ...
     * @param originZ     ...
     * @param dailyClear  the shipped first-finish tokens of an edition when the cadence is daily
     *                    ({@code games.fresh.rewards.clear_daily.<id>})
     * @param weeklyClear the same when the cadence is weekly or longer
     *                    ({@code games.fresh.rewards.clear_weekly.<id>})
     */
    public record Def(String id, String generator, String game, String kind, String name, String colour, int sizeX,
                      int sizeY, int sizeZ, int plots, boolean enabled, String tierOrMix, int originX, int originY,
                      int originZ, int dailyClear, int weeklyClear) {

        /** Whether it is a golf course (a {@code golf} row) rather than a time trial. */
        public boolean golf() {
            return GOLF.equals(generator);
        }

        /** Whether it is a Dropper (a {@code trials} row of kind {@code dropper}, EVENTS-DROPPER-SPEC §B.1.2). */
        public boolean dropper() {
            return DROPPER.equals(generator);
        }

        /**
         * Whether its plans may place water, and so its halves (or a plot a course of it is kept in)
         * may hold some (Course Variety §1.2): a Dropper's sealed pools, and golf's ponds (the Classic
         * Golf slot too, whose generator is golf). THE one predicate every water gate asks: the
         * palette lint, a moved plan's proof, the drain-first warnings, the old wet regions, the
         * staged builds and the keep plots' flow guard. The ice boat stays dry: no boat plan ever
         * places water.
         */
        public boolean mayHoldWater() {
            return dropper() || golf();
        }

        /**
         * Whether its difficulty is a mix of E, M and H (golf's holes, a Dropper's levels) rather
         * than one tier: the config key is {@code mix}, admins use {@code /hcm games gen mix}.
         */
        public boolean mixed() {
            return golf() || dropper();
        }

        /** The shipped origin, {x, y, z}. */
        public int[] origin() {
            return new int[]{originX, originY, originZ};
        }

        /**
         * Half {@code which} ('A' or 'B') for a region whose origin (half A's min corner) is
         * (x, y, z) and whose halves stand {@code gap} blocks apart: A starts at the origin, B
         * {@code sizeX + gap} blocks further along +X, same y and z.
         */
        public Box half(int x, int y, int z, char which, int gap) {
            if (gap < 0) {
                throw new IllegalArgumentException("a gap is 0 or more blocks: " + gap);
            }
            int dx = switch (Character.toUpperCase(which)) {
                case 'A' -> 0;
                case 'B' -> sizeX + gap;
                default -> throw new IllegalArgumentException("a half is A or B: " + which);
            };
            return Box.sized(x + dx, y, z, sizeX, sizeY, sizeZ);
        }

        /** {@link #half(int, int, int, char, int)} at the default gap, {@link #HALF_GAP}. */
        public Box half(int x, int y, int z, char which) {
            return half(x, y, z, which, HALF_GAP);
        }

        /** Half {@code which} at the shipped origin and the default gap. */
        public Box half(char which) {
            return half(originX, originY, originZ, which);
        }

        /** Both halves and the {@code gap} between them, for a region whose origin is (x, y, z). */
        public Box region(int x, int y, int z, int gap) {
            if (gap < 0) {
                throw new IllegalArgumentException("a gap is 0 or more blocks: " + gap);
            }
            return Box.sized(x, y, z, 2 * sizeX + gap, sizeY, sizeZ);
        }

        /** {@link #region(int, int, int, int)} at the default gap, {@link #HALF_GAP}. */
        public Box region(int x, int y, int z) {
            return region(x, y, z, HALF_GAP);
        }

        /**
         * Why {@code tierOrMix} can't be this slot's difficulty, or {@code null} when it can: a
         * tier is one of {@link #TIERS}; a golf mix is 1 to {@link #plots} of E, M and H, and so is
         * a Dropper's (one letter a level, the Dropper's own {@code DropRules.mixProblem}).
         */
        public String tierProblem(String tierOrMix) {
            String t = normalise(tierOrMix);
            if (mixed()) {
                return t.isEmpty() || t.length() > plots || !MIX.matcher(t).matches()
                        ? "a " + (golf() ? "golf" : "dropper") + " mix is 1-" + plots + " of E, M and H (like "
                        + this.tierOrMix + ")" : null;
            }
            return TIERS.contains(t) ? null : "a tier is easy, medium or hard";
        }

        /** A tier in lower case, a mix in upper case, trimmed; "" for none. */
        public String normalise(String tierOrMix) {
            if (tierOrMix == null) {
                return "";
            }
            String t = tierOrMix.trim();
            return mixed() ? t.toUpperCase(Locale.ROOT) : t.toLowerCase(Locale.ROOT);
        }
    }

    private static final Pattern MIX = Pattern.compile("[EMH]+");

    /*
     * Where the courses stand (LAYOUT-SPEC §1.5): two columns far out in the sky, Col W at x 6080 and
     * Col E at x 7488, each course (and each Classic) in a row of its own from z 4096 to z 7967. Every
     * box is 576 blocks (36 chunk columns) from every other, a course's own spare half included
     * (HALF_GAP), so from any course a player sees only that course at every view distance a server
     * can set (Sight). Every half stays inside x, z 4096..8191, the number range 0.35's halves stood
     * in, so the golf ball, the Dropper's pilots and the elytra sim give bit-identical answers there.
     * The kept courses' plots (KeepArea), the Clubhouse and the arena stand further out on the same
     * rule. An install that built at 0.35's spots keeps them (LayoutGuard).
     *
     *   Col W, x 6080 (half B at x + size + 576): Sky Rings z 4096, Classic Sky Rings z 4992, Ice Boat
     *   z 5888, Easy Parkour z 6592, Parkour z 7232, Hard Parkour z 7872.
     *   Col E, x 7488: Golf of the Week z 4096, Classic Golf z 4800, Tiny Golf z 5504, Classic Parkour
     *   z 6128, Easy Dropper z 6768, Dropper z 7360, Classic Dropper z 7952.
     */

    public static final Def DAILY_PARKOUR_EASY = new Def("fresh_parkour_easy", PARKOUR, GAME_TRIALS, "parkour",
            "Easy Parkour", "&a", 64, 48, 64, 0, true, "easy", 6080, 160, 6592, 1, 2);
    public static final Def DAILY_PARKOUR_MEDIUM = new Def("fresh_parkour", PARKOUR, GAME_TRIALS, "parkour",
            "Parkour", "&e", 64, 48, 64, 0, true, "medium", 6080, 160, 7232, 2, 3);
    public static final Def DAILY_PARKOUR_HARD = new Def("fresh_parkour_hard", PARKOUR, GAME_TRIALS, "parkour",
            "Hard Parkour", "&c", 64, 48, 64, 0, true, "hard", 6080, 160, 7872, 3, 4);
    public static final Def SKY_RINGS = new Def("fresh_rings", RINGS, GAME_TRIALS, "elytra", "Sky Rings", "&b",
            128, 176, 320, 0, true, "easy", 6080, 128, 4096, 2, 3);
    public static final Def DAILY_GOLF = new Def("fresh_golf", GOLF, GAME_GOLF, "golf", "Golf of the Week", "&d",
            64, 16, 128, 9, true, "EEEMMMMHH", 7488, 160, 4096, 2, 3);
    public static final Def TINY_GOLF = new Def("fresh_tiny_golf", GOLF, GAME_GOLF, "golf", "Tiny Golf", "&d", 64,
            16, 48, 3, true, "EEE", 7488, 160, 5504, 1, 2);
    public static final Def ICE_BOAT = new Def("fresh_boat", BOAT, GAME_TRIALS, "boat", "Ice Boat", "&b", 128, 16,
            128, 0, false, "medium", 6080, 160, 5888, 2, 3);

    /*
     * The Dropper (EVENTS-DROPPER-SPEC §B.1.2): a row of glass shafts, one per level of its mix (E, M,
     * H), each 11 x 11 inside, so a half is 64 x 64 x 16 (4 x 1 chunks). Both stand in the east column,
     * after Classic Parkour, 576 apart in z like every row. Both ship ON (the owner's decision: they run
     * only while games.fresh.enabled is on, which the owner switches on).
     */

    /** Easy Dropper: 3 easy levels, every hole ringed with light. */
    public static final Def EASY_DROPPER = new Def("fresh_dropper_easy", DROPPER, GAME_TRIALS, "dropper",
            "Easy Dropper", "&a", 64, 64, 16, 5, true, "EEE", 7488, 160, 6768, 1, 2);
    /** Dropper: 5 levels, easy to hard. */
    public static final Def FRESH_DROPPER = new Def("fresh_dropper", DROPPER, GAME_TRIALS, "dropper", "Dropper",
            "&9", 64, 64, 16, 5, true, "EEMMH", 7488, 160, 7360, 2, 3);

    /** Every slot, in display and config order. */
    public static final List<Def> ALL = List.of(DAILY_PARKOUR_EASY, DAILY_PARKOUR_MEDIUM, DAILY_PARKOUR_HARD,
            SKY_RINGS, DAILY_GOLF, TINY_GOLF, ICE_BOAT, EASY_DROPPER, FRESH_DROPPER);

    // ---- the Classics slots (GEN-SPEC-KEEP §3) -------------------------------------------------------

    /*
     * A Classics slot is empty and closed until an admin recalls an archived course into it. It is
     * NOT in ALL: the scheduler never builds one on its own, the Star Chart doesn't count it, and a
     * screen listing "this week's courses" doesn't show it. Its regions stand in the same two
     * columns, each in a row of its own next to its kind (Classic Sky Rings under Sky Rings, Classic
     * Golf under Golf of the Week, Classic Dropper after the droppers), each half the size of the
     * largest course its kind can hold (a Classic Golf half is the big golf course's, so Tiny Golf
     * fits too). Its tier or mix is the recalled course's own; the shipped one here only satisfies
     * the region checks.
     */

    /** Classic Parkour: holds any parkour tier. */
    public static final Def CLASSIC_PARKOUR = new Def("fresh_classic_parkour", PARKOUR, GAME_TRIALS, "parkour",
            "Classic Parkour", "&6", 64, 48, 64, 0, true, "easy", 7488, 160, 6128, 0, 0);
    /** Classic Sky Rings. */
    public static final Def CLASSIC_RINGS = new Def("fresh_classic_rings", RINGS, GAME_TRIALS, "elytra",
            "Classic Sky Rings", "&6", 128, 176, 320, 0, true, "easy", 6080, 128, 4992, 0, 0);
    /** Classic Golf: holds the big golf course or Tiny Golf. */
    public static final Def CLASSIC_GOLF = new Def("fresh_classic_golf", GOLF, GAME_GOLF, "golf", "Classic Golf",
            "&6", 64, 16, 128, 9, true, "EEEMMMMHH", 7488, 160, 4800, 0, 0);

    /** Classic Dropper: holds a recalled dropper of any mix. */
    public static final Def CLASSIC_DROPPER = new Def("fresh_classic_dropper", DROPPER, GAME_TRIALS, "dropper",
            "Classic Dropper", "&6", 64, 64, 16, 5, true, "EEMMH", 7488, 160, 7952, 0, 0);

    /** The Classics slots, in display and config order. */
    public static final List<Def> CLASSICS = List.of(CLASSIC_PARKOUR, CLASSIC_RINGS, CLASSIC_GOLF, CLASSIC_DROPPER);

    /** Every play id Fresh Courses keeps: the slots, the Classics slots, {@link #DAILY} and {@link #DAILY_PARKOUR}. */
    public static final Set<String> RESERVED;

    static {
        Set<String> ids = new LinkedHashSet<>();
        for (Def d : ALL) {
            ids.add(d.id());
        }
        for (Def d : CLASSICS) {
            ids.add(d.id());
        }
        ids.add(DAILY);
        ids.add(DAILY_PARKOUR);
        RESERVED = java.util.Collections.unmodifiableSet(ids);
    }

    private Slots() {
    }

    /** The slot with this id (any case, trimmed), or {@code null}. */
    public static Def of(String id) {
        if (id == null) {
            return null;
        }
        String k = id.trim().toLowerCase(Locale.ROOT);
        for (Def d : ALL) {
            if (d.id().equals(k)) {
                return d;
            }
        }
        return null;
    }

    /** Whether {@code id} is a slot's id. */
    public static boolean isSlot(String id) {
        return of(id) != null;
    }

    /** Whether Fresh Courses keeps {@code id} (a slot, {@code fresh_courses} or {@code fresh_parkour_tiers}). */
    public static boolean reserved(String id) {
        return id != null && RESERVED.contains(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Every slot id, in order. */
    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (Def d : ALL) {
            out.add(d.id());
        }
        return out;
    }

    /** The Classics slot with this id (any case, trimmed), or {@code null}. */
    public static Def classic(String id) {
        if (id == null) {
            return null;
        }
        String k = id.trim().toLowerCase(Locale.ROOT);
        for (Def d : CLASSICS) {
            if (d.id().equals(k)) {
                return d;
            }
        }
        return null;
    }

    /** Whether {@code id} is a Classics slot's id. */
    public static boolean isClassic(String id) {
        return classic(id) != null;
    }

    /** A slot or a Classics slot with this id, or {@code null}. */
    public static Def any(String id) {
        Def d = of(id);
        return d != null ? d : classic(id);
    }

    /** Every Classics slot id, in order. */
    public static List<String> classicIds() {
        List<String> out = new ArrayList<>();
        for (Def d : CLASSICS) {
            out.add(d.id());
        }
        return out;
    }

    /**
     * The Classics slot a course of {@code def}'s generator is recalled into: parkour (any tier)
     * into Classic Parkour, Sky Rings into Classic Sky Rings, both golf courses into Classic Golf,
     * both droppers into Classic Dropper. {@code null} for the ice boat, which has no Classics slot.
     */
    public static Def classicFor(Def def) {
        if (def == null) {
            return null;
        }
        for (Def c : CLASSICS) {
            if (c.generator().equals(def.generator())) {
                return c;
            }
        }
        return null;
    }

    /**
     * The Classics slot a word names: its id, or its kind ({@code parkour}; {@code rings},
     * {@code elytra} or {@code sky_rings}; {@code golf}; {@code dropper}). {@code null} for anything else.
     */
    public static Def classicByWord(String word) {
        Def d = classic(word);
        if (d != null || word == null) {
            return d;
        }
        return switch (word.trim().toLowerCase(Locale.ROOT)) {
            case "parkour" -> CLASSIC_PARKOUR;
            case "rings", "elytra", "sky_rings", "skyrings" -> CLASSIC_RINGS;
            case "golf" -> CLASSIC_GOLF;
            case "dropper", "droppers" -> CLASSIC_DROPPER;
            default -> null;
        };
    }
}
