package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.arena.rules.ArenaScoring;
import com.dierks.homecraft.games.arena.rules.RoundSettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.Regions;

import java.util.List;

/**
 * Falling Floors' settings: {@code games.falling_floors} (EVENTS-DROPPER-SPEC §B.3.5). Ships OFF.
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml block
 * parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each key over
 * the defaults: an out-of-range number is clamped with one WARN naming its full key, junk closes
 * only {@code falling_floors} (one WARN, {@link GamesConfig.Node#invalid}); it never throws. The
 * round knobs clamp to exactly {@link RoundSettings}' ranges, so {@link #round()} never moves them
 * again.
 *
 * @param enabled             the game's own switch (it also needs {@code games.enabled})
 * @param origin              the arena box's min corner {x, y, z}; x and z on the 16-block grid
 * @param fadeTicks           how long a stepped-on cell stays red before it falls (6-20)
 * @param minPlayers          ready players that start a round (2 up to {@code maxPlayers})
 * @param maxPlayers          the most players in the arena (2-16)
 * @param solo                whether a lone player may play a solo round
 * @param roundSeconds        sudden death (the edges fall in) after this long (30-900)
 * @param resetBlocksPerTick  how many blocks the reset between rounds writes a tick
 * @param dailyReward         tokens for the first full round of the day
 * @param milestones          solo survival seconds for the three milestones, rising
 * @param milestoneRewards    tokens for each milestone, once ever
 * @param dailyCap            the most tokens Falling Floors pays a player a day
 */
public record FallingFloorsSettings(boolean enabled, List<Integer> origin, int fadeTicks, int minPlayers,
                                    int maxPlayers, boolean solo, int roundSeconds, int resetBlocksPerTick,
                                    int dailyReward, List<Integer> milestones, List<Integer> milestoneRewards,
                                    int dailyCap) {

    /** The leaves under {@code games.falling_floors}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "origin", "fade_ticks", "min_players", "max_players",
            "solo", "round_seconds", "reset_blocks_per_tick", "daily_reward", "milestones", "milestone_rewards",
            "daily_cap");

    /** The arena box's size (§B.3.2): 48 x 40 x 48. */
    public static final int SIZE_X = 48;
    public static final int SIZE_Y = 40;
    public static final int SIZE_Z = 48;

    public FallingFloorsSettings {
        origin = List.copyOf(origin == null ? List.of(5376, 176, 4352) : origin);
        milestones = List.copyOf(milestones == null ? List.of() : milestones);
        milestoneRewards = List.copyOf(milestoneRewards == null ? List.of() : milestoneRewards);
    }

    /** The shipped settings (§B.3.5): off, at [5376, 176, 4352], fade 10, 2-12 players, solo on. */
    public static FallingFloorsSettings defaults() {
        return new FallingFloorsSettings(
                false,
                List.of(5376, 176, 4352),
                RoundSettings.DEFAULT_FADE_TICKS,
                RoundSettings.DEFAULT_MIN_PLAYERS,
                RoundSettings.DEFAULT_MAX_PLAYERS,
                true,
                RoundSettings.DEFAULT_ROUND_SECONDS,
                400,
                1,
                List.of(30, 60, 120),
                List.of(1, 2, 3),
                3);
    }

    /** Read {@code games.falling_floors} over {@code d}; never throws. */
    public static FallingFloorsSettings parse(GamesConfig.Node n, FallingFloorsSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> origin = n.intList("origin", d.origin(), -Regions.MAX_XZ, Regions.MAX_XZ, 3);
        if (origin != d.origin()) {
            int x = Math.floorDiv(origin.get(0), 16) * 16;
            int z = Math.floorDiv(origin.get(2), 16) * 16;
            int y = (int) n.clamp("origin", origin.get(1), Regions.MIN_Y, Regions.MAX_Y - SIZE_Y + 1, false);
            if (x != origin.get(0) || z != origin.get(2)) {
                n.warn(n.key("origin") + " " + origin + " is not on the 16-block grid - using [" + x + ", " + y + ", "
                        + z + "]");
            }
            origin = List.of(x, y, z);
        }
        int fadeTicks = n.whole("fade_ticks", d.fadeTicks(), RoundSettings.MIN_FADE_TICKS,
                RoundSettings.MAX_FADE_TICKS);
        int maxPlayers = n.whole("max_players", d.maxPlayers(), 2, RoundSettings.MAX_PLAYERS_LIMIT);
        int minPlayers = n.whole("min_players", d.minPlayers(), 2, maxPlayers);
        boolean solo = n.bool("solo", d.solo());
        int roundSeconds = n.whole("round_seconds", d.roundSeconds(), RoundSettings.MIN_ROUND_SECONDS,
                RoundSettings.MAX_ROUND_SECONDS);
        int resetBlocksPerTick = n.whole("reset_blocks_per_tick", d.resetBlocksPerTick(), 50, 5000);
        int dailyReward = n.whole("daily_reward", d.dailyReward(), 0, 10);
        List<Integer> milestones = n.ladder("milestones", d.milestones(), 3600, false);
        List<Integer> milestoneRewards = n.intList("milestone_rewards", d.milestoneRewards(), 0, 10, 3);
        int dailyCap = n.whole("daily_cap", d.dailyCap(), 0, 100);
        return new FallingFloorsSettings(enabled, origin, fadeTicks, minPlayers, maxPlayers, solo, roundSeconds,
                resetBlocksPerTick, dailyReward, milestones, milestoneRewards, dailyCap);
    }

    /** The round knobs the pure rules take. */
    public RoundSettings round() {
        return new RoundSettings(fadeTicks, minPlayers, maxPlayers, solo, roundSeconds);
    }

    /** The rewards the pure scoring takes. */
    public ArenaScoring.Rewards rewards() {
        return new ArenaScoring.Rewards(dailyReward, milestones, milestoneRewards);
    }

    /** The arena box (§B.3.2): 48 x 40 x 48 from {@link #origin}. */
    public Box box() {
        return Box.sized(origin.get(0), origin.get(1), origin.get(2), SIZE_X, SIZE_Y, SIZE_Z);
    }
}
