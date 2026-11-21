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
 * number is clamped with one WARN naming its full key, junk closes only this game; it never throws.
 *
 * @param enabled     the game's own switch (it also needs {@code games.enabled})
 * @param origin      the room box's min corner {x, y, z}; x and z on the 16-block grid
 * @param maxPlayers  the most players in the room at once
 * @param stars       how many stars there are to collect: all of them in Little, this many a round in Group
 * @param safeSeconds how long the floor is safe before the warning
 * @param lavaSeconds how long the lava stays before it drains
 * @param rounds      Group's rounds
 */
public record LavaSettings(boolean enabled, List<Integer> origin, int maxPlayers, int stars, int safeSeconds,
                           int lavaSeconds, int rounds) {

    /** The leaves under {@code games.floor_is_lava}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "origin", "max_players", "stars", "safe_seconds",
            "lava_seconds", "rounds");

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

    public LavaSettings {
        origin = List.copyOf(origin == null ? ORIGIN : origin);
    }

    /** The shipped settings: on, at {@link #ORIGIN}, 6 players, 10 stars, 25 s safe and 12 s of lava, 5 rounds. */
    public static LavaSettings defaults() {
        return new LavaSettings(true, ORIGIN, 6, 10, 25, 12, 5);
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
        return new LavaSettings(enabled, origin, maxPlayers, stars, safeSeconds, lavaSeconds, rounds);
    }

    /** The room's box: 32 x 16 x 32 from {@link #origin}. */
    public Box box() {
        return Box.sized(origin.get(0), origin.get(1), origin.get(2), LavaPlanner.SIZE_X, LavaPlanner.SIZE_Y,
                LavaPlanner.SIZE_Z);
    }

    /**
     * The knobs the pure rules take for a round of {@code mode}. Little and Group take the phases as set; Challenge
     * is brisker (half of each, and its climbing time shrinks round by round: {@link LavaRound.Rules#safeTicks}).
     * Little starts at once; the other two gather players for a moment first.
     */
    public LavaRound.Rules rules(LavaMode mode) {
        int safe = safeSeconds * LavaRound.TICKS_PER_SECOND;
        int lava = lavaSeconds * LavaRound.TICKS_PER_SECOND;
        if (mode == LavaMode.CHALLENGE) {
            safe = Math.max(LavaRound.MIN_CLIMB_TICKS, safe / 2);
            lava = Math.max(3 * LavaRound.TICKS_PER_SECOND, lava / 2);
        }
        return new LavaRound.Rules(stars, safe, LavaRound.WARN_TICKS, lava, LavaRound.DRAIN_TICKS, rounds,
                LavaPlanner.SAFE_TOP, LavaPlanner.FLOOD, maxPlayers,
                mode == LavaMode.LITTLE ? 0 : LavaRound.GATHER_TICKS);
    }
}
