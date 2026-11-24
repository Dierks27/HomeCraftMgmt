package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.Regions;
import com.dierks.homecraft.games.lava.rules.LavaMode;
import com.dierks.homecraft.games.lava.rules.LavaRound;

import java.util.List;

/**
 * The Floor Is Lava's settings: {@code games.floor_is_lava}. Ships ON: the playroom builds itself in an empty box
 * of the Games world, and nothing happens while {@code games.enabled} is false.
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml block parses to exactly
 * it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each key over the defaults: an out-of-range
 * number is clamped with one WARN naming its full key, junk closes only this game; it never throws. The 0.57.0 keys
 * reach a shipped server through the config backfill (no revision): until then their defaults apply.
 *
 * @param enabled     the game's own switch (it also needs {@code games.enabled})
 * @param origin      the room box's min corner {x, y, z}; x and z on the 16-block grid
 * @param maxPlayers  the most players in the room at once
 * @param stars       how many stars there are to collect: all of them in Little, this many a round on the floor in
 *                    Group
 * @param safeSeconds the longest the floor is safe before the warning (Little's; the other modes' are shorter)
 * @param lavaSeconds the longest the lava stays before it drains (Little's; Group's is longer, Challenge's shorter)
 * @param rounds      Group's rounds
 * @param movingRoom  0.57.0: the furniture moves (every game, and every round of Group and Challenge)
 * @param surprises   0.57.0: rounds have surprises (a piece that sinks, lava on one side first, a quick rise, a shower
 *                    of stars, a bouncy cushion)
 * @param hearts      0.57.0: Group's hearts: a round that misses its star goal costs one
 */
public record LavaSettings(boolean enabled, List<Integer> origin, int maxPlayers, int stars, int safeSeconds,
                           int lavaSeconds, int rounds, boolean movingRoom, boolean surprises, int hearts) {

    /** The leaves under {@code games.floor_is_lava}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "origin", "max_players", "stars", "safe_seconds",
            "lava_seconds", "rounds", "moving_room", "surprises", "hearts");

    /**
     * The shipped corner: east of the Falling Floors arena on the Clubhouse's row, 576 blocks from it and at least
     * that from every other shipped place, so from the room nothing else can be seen ({@code LavaRoomApartTest} pins
     * it).
     */
    public static final List<Integer> ORIGIN = List.of(7312, 160, 8544);

    /** The ranges config takes. */
    public static final int MAX_PLAYERS = 12;
    public static final int MAX_STARS = 20;
    public static final int MAX_ROUNDS = 12;
    public static final int MAX_HEARTS = 9;

    /** 0.57.0: Group's stars during the lava, each round. */
    public static final int GROUP_LAVA_STARS = 4;
    /** 0.57.0: the furniture's glow and rumble before it moves: Group's, Challenge's (ticks). */
    public static final int GROUP_SHIFT_TICKS = 3 * LavaRound.TICKS_PER_SECOND;
    public static final int CHALLENGE_SHIFT_TICKS = 2 * LavaRound.TICKS_PER_SECOND;

    public LavaSettings {
        origin = List.copyOf(origin == null ? ORIGIN : origin);
    }

    /** The 0.50.0 seven, with the 0.57.0 keys at their defaults (the tests' and old callers' shape). */
    public LavaSettings(boolean enabled, List<Integer> origin, int maxPlayers, int stars, int safeSeconds,
                        int lavaSeconds, int rounds) {
        this(enabled, origin, maxPlayers, stars, safeSeconds, lavaSeconds, rounds, true, true, 3);
    }

    /**
     * The shipped settings: on, at {@link #ORIGIN}, 6 players, 10 stars, 25 s safe and 12 s of lava at most, 5
     * rounds; the room moves, surprises on, 3 hearts.
     */
    public static LavaSettings defaults() {
        return new LavaSettings(true, ORIGIN, 6, 10, 25, 12, 5, true, true, 3);
    }

    /** Read {@code games.floor_is_lava} over {@code d}; never throws. */
    public static LavaSettings parse(GamesConfig.Node n, LavaSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> origin = n.intList("origin", d.origin(), -Regions.MAX_XZ, Regions.MAX_XZ, 3);
        if (origin != d.origin()) {
            int x = Math.floorDiv(origin.get(0), 16) * 16;
            int z = Math.floorDiv(origin.get(2), 16) * 16;
            int y = (int) n.clamp("origin", origin.get(1), Regions.MIN_Y, Regions.MAX_Y - LavaPlanner.SIZE_Y + 1, false);
            if (x != origin.get(0) || z != origin.get(2)) {
                n.warn(n.key("origin") + " " + origin + " is not on the 16-block grid - using [" + x + ", " + y + ", "
                        + z + "]");
            }
            origin = List.of(x, y, z);
        }
        int maxPlayers = n.whole("max_players", d.maxPlayers(), 1, MAX_PLAYERS);
        int stars = n.whole("stars", d.stars(), 1, MAX_STARS);
        int safeSeconds = n.whole("safe_seconds", d.safeSeconds(), 8, 120);
        int lavaSeconds = n.whole("lava_seconds", d.lavaSeconds(), 4, 60);
        int rounds = n.whole("rounds", d.rounds(), 1, MAX_ROUNDS);
        boolean moving = n.bool("moving_room", d.movingRoom());
        boolean surprises = n.bool("surprises", d.surprises());
        int hearts = n.whole("hearts", d.hearts(), 1, MAX_HEARTS);
        return new LavaSettings(enabled, origin, maxPlayers, stars, safeSeconds, lavaSeconds, rounds, moving, surprises,
                hearts);
    }

    /** The room's box: {@value LavaPlanner#SIZE_X} x {@value LavaPlanner#SIZE_Y} x {@value LavaPlanner#SIZE_Z} from {@link #origin}. */
    public Box box() {
        return Box.sized(origin.get(0), origin.get(1), origin.get(2), LavaPlanner.SIZE_X, LavaPlanner.SIZE_Y,
                LavaPlanner.SIZE_Z);
    }

    /** 0.57.0: the box the room had before it grew (its claim is recognised and the new ground claimed). */
    public Box oldBox() {
        return Box.sized(origin.get(0), origin.get(1), origin.get(2), LavaPlanner.OLD_SIZE, LavaPlanner.SIZE_Y,
                LavaPlanner.OLD_SIZE);
    }

    /**
     * The knobs the pure rules take for a round of {@code mode}. 0.57.0: every phase is drawn from a range, so the
     * lava can't be counted, and the harder the mode the shorter its floor time and its warning (S is
     * {@code safe_seconds}, L {@code lava_seconds}; shipped 25 and 12):
     * <ul>
     *   <li>Little: safe 0.75-1 S (19-25 s), a 5 s warning, lava 0.75-1 L (9-12 s); one layout a game.</li>
     *   <li>Group: safe 0.45-0.7 S (11-17 s), warning 2.5-4 s, lava 1-1.4 L (12-17 s, with stars to hop for); the
     *       furniture moves every round (a 3 s warning, inside the safe time).</li>
     *   <li>Challenge: safe 0.28-0.45 S (7-11 s, a tenth shorter every round, never under 4 s), warning 1.5-2.5 s,
     *       lava 0.6-0.9 L (7-11 s); the furniture moves every round (a 2 s warning).</li>
     * </ul>
     * Little starts at once; the other two gather players for a moment first.
     */
    public LavaRound.Rules rules(LavaMode mode) {
        int s = safeSeconds * LavaRound.TICKS_PER_SECOND;
        int l = lavaSeconds * LavaRound.TICKS_PER_SECOND;
        int t = LavaRound.TICKS_PER_SECOND;
        return switch (mode) {
            case LITTLE -> new LavaRound.Rules(stars, 0, new LavaRound.Span(s * 3 / 4, s), LavaRound.Span.of(5 * t),
                    new LavaRound.Span(l * 3 / 4, l), LavaRound.DRAIN_TICKS, rounds, LavaPlanner.SAFE_TOP,
                    LavaPlanner.FLOOD, maxPlayers, 0, 0, hearts, surprises);
            case GROUP -> new LavaRound.Rules(stars, GROUP_LAVA_STARS, new LavaRound.Span(s * 45 / 100, s * 70 / 100),
                    new LavaRound.Span(5 * t / 2, 4 * t), new LavaRound.Span(l, l * 14 / 10), LavaRound.DRAIN_TICKS,
                    rounds, LavaPlanner.SAFE_TOP, LavaPlanner.FLOOD, maxPlayers, LavaRound.GATHER_TICKS,
                    movingRoom ? GROUP_SHIFT_TICKS : 0, hearts, surprises);
            case CHALLENGE -> new LavaRound.Rules(stars, 0, new LavaRound.Span(Math.max(LavaRound.MIN_CLIMB_TICKS,
                    s * 28 / 100), Math.max(LavaRound.MIN_CLIMB_TICKS, s * 45 / 100)),
                    new LavaRound.Span(3 * t / 2, 5 * t / 2), new LavaRound.Span(Math.max(3 * t, l * 6 / 10),
                    Math.max(3 * t, l * 9 / 10)), LavaRound.DRAIN_TICKS, rounds, LavaPlanner.SAFE_TOP,
                    LavaPlanner.FLOOD, maxPlayers, LavaRound.GATHER_TICKS, movingRoom ? CHALLENGE_SHIFT_TICKS : 0,
                    hearts, surprises);
        };
    }
}
