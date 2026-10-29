package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.Regions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Clubhouse's settings: {@code games.clubhouse} (CLUBHOUSE-SPEC §5). Ships ON: the room builds
 * itself in an empty box, and nothing happens while {@code games.enabled} is false.
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml block
 * parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each key over
 * the defaults and never throws. Unlike a game's usual block, a key that can't be read is NOT junk
 * that closes the Clubhouse: it logs one WARN and uses its shipped value (§5: "an unreadable block
 * logs a WARN and uses the defaults"). Only {@code enabled} itself fails closed, as every games
 * switch does (the framework's rule), and so does {@code origin}, where the room stands (the shipped
 * corner in its place would be a second room); then every flow behaves exactly as it did before.
 *
 * @param enabled        the Clubhouse's own switch (it also needs {@code games.enabled})
 * @param origin         the box's min corner {x, y, z}; x and z on the 16-block grid
 * @param maxMinutes     a visitor with no race or party going is sent home after this long
 * @param partyAfter     party racers come back to the Clubhouse after the race, instead of home
 * @param raceNightAfter everyone at the track goes to the Clubhouse at the end of Race Night
 * @param golfAfter      a golf-together group goes to the Clubhouse when its round ends
 * @param soloFirst      0.38.3: a course's or a round of golf's Start goes through the Clubhouse first, and on
 *                       to it after {@code firstSeconds} (or "Go now")
 * @param soloAfter      0.38.3: a solo run's finish, or a round alone's end, comes back to the Clubhouse with the
 *                       player's own results, instead of home
 * @param firstSeconds   0.38.3: how long the Clubhouse waits before taking a player on (1-30 s)
 * @param boards         0.38.3: the walls' boards (this week's best, now playing, my stats); the results board
 *                       shows either way
 */
public record ClubhouseSettings(boolean enabled, List<Integer> origin, int maxMinutes, boolean partyAfter,
                                boolean raceNightAfter, boolean golfAfter, boolean soloFirst, boolean soloAfter,
                                int firstSeconds, boolean boards) {

    /** The leaves under {@code games.clubhouse}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "origin", "max_minutes", "party_after",
            "race_night_after", "golf_after", "solo_first", "solo_after", "first_seconds", "boards");

    /** The shipped wait before a player is taken on from the Clubhouse (s). */
    public static final int FIRST_SECONDS = 5;

    /** The box's size (§1): 32 x 16 x 32, fixed by the plan. */
    public static final int SIZE_X = 32;
    public static final int SIZE_Y = 16;
    public static final int SIZE_Z = 32;

    /**
     * The shipped corner (LAYOUT-SPEC §1.5): south of the courses' west column, 576 blocks from every
     * shipped Fresh Courses area, the Classics, the kept courses' area and the Falling Floors arena,
     * so from the room none of them can be seen ({@code ClubhouseRegionsTest} and
     * {@code ShippedLayoutTest} pin it).
     */
    public static final List<Integer> ORIGIN = List.of(6080, 160, 8544);

    public ClubhouseSettings {
        origin = List.copyOf(origin == null ? ORIGIN : origin);
    }

    /** 0.38.2's six settings, with 0.38.3's at their shipped values (the tests that build a room use it). */
    public ClubhouseSettings(boolean enabled, List<Integer> origin, int maxMinutes, boolean partyAfter,
                             boolean raceNightAfter, boolean golfAfter) {
        this(enabled, origin, maxMinutes, partyAfter, raceNightAfter, golfAfter, true, true, FIRST_SECONDS, true);
    }

    /**
     * The shipped settings: on, at {@link #ORIGIN}, 30 minutes, back to the Clubhouse after everything, the
     * courses and golf through it ({@value #FIRST_SECONDS} s), and its boards.
     */
    public static ClubhouseSettings defaults() {
        return new ClubhouseSettings(true, ORIGIN, 30, true, true, true, true, true, FIRST_SECONDS, true);
    }

    /**
     * Read {@code games.clubhouse} over {@code d}; never throws, and a bad value is its shipped one, except
     * {@code origin}: one that can't be read closes the Clubhouse until it is fixed (one WARN,
     * {@link GamesConfig.Node#invalid}). The shipped corner isn't where a server that kept 0.35's spots,
     * or an owner who moved the room, built it: the room would read it as a move and build a second
     * Clubhouse there, leaving the first standing.
     */
    public static ClubhouseSettings parse(GamesConfig.Node n, ClubhouseSettings d) {
        boolean enabled = n.enabled(d.enabled());
        GamesConfig.Node k = lenient(n);
        List<Integer> origin = n.intList("origin", d.origin(), -Regions.MAX_XZ, Regions.MAX_XZ, 3);
        if (origin != d.origin()) {
            int x = Math.floorDiv(origin.get(0), 16) * 16;
            int z = Math.floorDiv(origin.get(2), 16) * 16;
            int y = (int) k.clamp("origin", origin.get(1), Regions.MIN_Y, Regions.MAX_Y - SIZE_Y + 1, false);
            if (x != origin.get(0) || z != origin.get(2)) {
                k.warn(k.key("origin") + " " + origin + " is not on the 16-block grid - using [" + x + ", " + y
                        + ", " + z + "]");
            }
            origin = List.of(x, y, z);
        }
        int maxMinutes = k.whole("max_minutes", d.maxMinutes(), 2, 24 * 60);
        boolean partyAfter = k.bool("party_after", d.partyAfter());
        boolean raceNightAfter = k.bool("race_night_after", d.raceNightAfter());
        boolean golfAfter = k.bool("golf_after", d.golfAfter());
        boolean soloFirst = k.bool("solo_first", d.soloFirst());
        boolean soloAfter = k.bool("solo_after", d.soloAfter());
        int firstSeconds = k.whole("first_seconds", d.firstSeconds(), 1, 30);
        boolean boards = k.bool("boards", d.boards());
        return new ClubhouseSettings(enabled, origin, maxMinutes, partyAfter, raceNightAfter, golfAfter, soloFirst,
                soloAfter, firstSeconds, boards);
    }

    /**
     * A node over the same values whose junk only WARNs: the value that can't be read falls back to
     * its shipped one, and the Clubhouse stays open.
     */
    private static GamesConfig.Node lenient(GamesConfig.Node n) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (String key : n.keys()) {
            raw.put(key, n.raw(key));
        }
        String closes = " - " + n.path() + " is off until it is fixed";
        return new GamesConfig.Node(n.path(), raw, line -> n.warn(line.endsWith(closes)
                ? line.substring(0, line.length() - closes.length()) + " - using its shipped value" : line));
    }

    /** The box (§1): 32 x 16 x 32 from {@link #origin}. */
    public Box box() {
        return Box.sized(origin.get(0), origin.get(1), origin.get(2), SIZE_X, SIZE_Y, SIZE_Z);
    }

    /** Whether the Clubhouse is on after anything at all (for the check's summary). */
    public boolean anyAfter() {
        return partyAfter || raceNightAfter || golfAfter;
    }
}
