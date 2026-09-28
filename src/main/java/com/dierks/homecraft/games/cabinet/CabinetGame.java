package com.dierks.homecraft.games.cabinet;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.SkillRewards;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What every arcade cabinet shares (spec §10b, R1.22, R2.13): the daily board, the one scored try
 * at it, milestones, the daily challenge reward and today's featured bonus.
 *
 * <p><b>Classic</b> play is free and unlimited: a new personal best is recorded and announced but
 * pays nothing (paying for bests invites holding back on purpose). A board's three
 * <b>milestones</b> — bronze, silver, gold — each pay once ever. The <b>daily board</b> is the same
 * puzzle for everyone today; each player's FIRST try at it is the scored one (a row written when
 * the board is dealt), later tries are practice. Meeting the daily goal on the scored try pays the
 * daily reward, once a day. Everything goes through {@link SkillRewards}, so the game's and the
 * server's daily caps always apply.
 *
 * <p><b>Why the daily seed is secret.</b> A board worked out from a formula in the public source
 * could be solved before it is dealt. The seed mixes the server's own random secret (made once,
 * kept in the database, never logged) with the local day and the game id, so it is stable all day,
 * different for every game, and unguessable outside the server.
 */
public abstract class CabinetGame implements Game {

    protected final GameContext ctx;
    /** Read once from the database; a per-run random stand-in if that ever fails. */
    private Long secret;

    protected CabinetGame(GameContext ctx) {
        this.ctx = ctx;
    }

    /** This cabinet's live settings (read every call, never cached across a reload). */
    protected abstract CabinetSettings cabinetSettings();

    protected GamesService games() {
        return ctx.games();
    }

    /** Today's local day key. */
    protected long today() {
        return ctx.plugin().clock().dayKey();
    }

    // ---- the daily board ----------------------------------------------------------------------

    /**
     * How a daily play starts.
     *
     * @param day    the local day the board belongs to
     * @param seed   the board's seed (the same for every player today)
     * @param scored true for the player's first try today, false for practice
     */
    public record DailyStart(long day, long seed, boolean scored) {
    }

    /** The seed of this game's daily board for {@code day}. */
    public long dailySeed(long day) {
        return mix(secret(), day, id());
    }

    /**
     * Deal today's board to {@code player}: the first deal of the day is the scored try (recorded
     * now, so closing the screen can't earn a second one); every later one is practice.
     */
    public DailyStart startDaily(Player player) {
        long day = today();
        long seed = dailySeed(day);
        boolean scored = false;
        try {
            scored = games().dao().markDailyAttempt(player.getUniqueId(), id(), day, seed, "",
                    ctx.plugin().clock().nowMillis());
        } catch (SQLException e) {
            ctx.plugin().getLogger().warning("Could not record " + id() + "'s daily try for "
                    + player.getName() + " - it is practice: " + e.getMessage());
        }
        return new DailyStart(day, seed, scored);
    }

    // ---- finishing ----------------------------------------------------------------------------

    /**
     * What a finished game earned, for the screen and the chat line.
     *
     * @param result     the score's standing (NONE for practice)
     * @param milestones the milestones this run reached for the first time (1 = bronze .. 3 = gold)
     * @param tokens     tokens actually paid (after the caps)
     * @param practice   a daily board played again: nothing was recorded or paid
     */
    public record Finish(ScoreResult result, List<Integer> milestones, int tokens, boolean practice) {
        public Finish {
            milestones = List.copyOf(milestones);
        }
    }

    /**
     * A Classic run ended: record the score on {@code board}, pay any milestone reached for the
     * first time, and today's featured bonus if this is today's pick.
     */
    public Finish finishClassic(Player player, String board, long score, boolean lowerIsBetter) {
        return finishClassic(player, board, score, score, lowerIsBetter);
    }

    /**
     * As {@link #finishClassic(Player, String, long, boolean)}, for a board whose milestones read
     * another number than its score: Ore Merge scores the sum of its merges, but its milestones
     * are the biggest tile made. {@code milestoneValue} is compared in the same direction.
     */
    public Finish finishClassic(Player player, String board, long score, long milestoneValue, boolean lowerIsBetter) {
        ScoreResult result = games().scores().submit(player.getUniqueId(), id(), board, score, lowerIsBetter);
        CabinetSettings s = cabinetSettings();
        int paid = 0;
        List<Integer> reached = new ArrayList<>();
        int reward = s.milestoneReward();
        if (reward > 0) {
            for (int n : milestonesReached(s.milestonesFor(board), milestoneValue, lowerIsBetter)) {
                int got = games().rewards().pay(player, this, source(), RewardKind.MILESTONE,
                        SkillRewards.milestoneRef(board, n), reward, s.dailyCap(),
                        name() + ": " + medal(n) + " milestone");
                if (got > 0) {
                    reached.add(n);
                    paid += got;
                }
            }
        }
        paid += featuredBonus(player);
        return new Finish(result, reached, paid, false);
    }

    /**
     * A daily-board run ended. The scored try records its score on today's daily board and, if the
     * goal was met, pays the daily reward once; practice records and pays nothing.
     */
    public Finish finishDaily(Player player, DailyStart start, long score, boolean lowerIsBetter, boolean goalMet) {
        if (start == null || !start.scored()) {
            return new Finish(ScoreResult.NONE, List.of(), 0, true);
        }
        ScoreResult result = games().scores().submit(player.getUniqueId(), id(), Scores.daily(start.day()), score,
                lowerIsBetter);
        int paid = 0;
        CabinetSettings s = cabinetSettings();
        if (goalMet && s.dailyReward() > 0) {
            paid += games().rewards().pay(player, this, source(), RewardKind.DAILY_CHALLENGE,
                    SkillRewards.dailyRef(start.day()), s.dailyReward(), s.dailyCap(),
                    name() + ": daily challenge");
        }
        paid += featuredBonus(player);
        return new Finish(result, List.of(), paid, false);
    }

    /** Today's featured bonus, if this game is today's pick (once a day across every game). */
    private int featuredBonus(Player player) {
        if (!games().featured().isFeatured(id())) {
            return 0;
        }
        int bonus = games().config().common().featuredBonus();
        if (bonus <= 0) {
            return 0;
        }
        return games().rewards().pay(player, this, source(), RewardKind.FEATURED,
                SkillRewards.featuredRef(today()), bonus, cabinetSettings().dailyCap(),
                name() + ": today's pick");
    }

    /** Open one of this game's high-score boards. */
    protected void openScores(Player player, String board, boolean lowerIsBetter, Runnable back) {
        games().screens().scores(player, this, board, lowerIsBetter, back);
    }

    // ---- pure helpers (tested) ----------------------------------------------------------------

    /**
     * Which milestones {@code score} reaches, 1-based (1 = bronze). A lower-is-better board (a time,
     * flips) reaches a milestone at or under its threshold; a higher-is-better one at or over it.
     * Thresholds of 0 or less never count.
     */
    public static List<Integer> milestonesReached(List<Integer> thresholds, long score, boolean lowerIsBetter) {
        List<Integer> out = new ArrayList<>();
        if (thresholds == null) {
            return out;
        }
        for (int i = 0; i < thresholds.size(); i++) {
            Integer t = thresholds.get(i);
            if (t == null || t <= 0) {
                continue;
            }
            boolean reached = lowerIsBetter ? score <= t : score >= t;
            if (reached) {
                out.add(i + 1);
            }
        }
        return out;
    }

    /** "bronze", "silver", "gold" (and "milestone N" past three). */
    public static String medal(int n) {
        return switch (n) {
            case 1 -> "bronze";
            case 2 -> "silver";
            case 3 -> "gold";
            default -> "milestone " + n;
        };
    }

    /**
     * Mix the secret, the day and the game id into one seed (SplitMix64 finaliser over each part):
     * the same inputs always give the same seed, and changing any one of them changes it
     * completely.
     */
    public static long mix(long secret, long day, String gameId) {
        long h = splitMix(secret);
        h = splitMix(h ^ day);
        h = splitMix(h ^ (gameId == null ? 0 : gameId.hashCode()));
        return h;
    }

    private static long splitMix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private long secret() {
        if (secret == null) {
            try {
                secret = games().dao().secret();
            } catch (SQLException e) {
                // Never fall back to anything guessable: a per-run random secret keeps today's
                // board private (it just changes if the server restarts before the DB recovers).
                secret = ThreadLocalRandom.current().nextLong();
                ctx.plugin().getLogger().warning("Could not read the games secret - daily boards use a "
                        + "temporary one until the next restart: " + e.getMessage());
            }
        }
        return secret;
    }
}
