package com.dierks.homecraft.games.cabinet;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.util.Text;
import org.bukkit.entity.Player;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
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
 * could be solved before it is dealt. The seed is an HMAC-SHA256 of the local day and the game id
 * keyed with the server's own random secret (made once, kept in the database, never logged), so it
 * is stable all day, different for every game, unguessable outside the server — and a board a
 * player has seen (so its seed, worked back from the layout) can't be walked back to the secret to
 * predict tomorrow's, which a plain mixing function could be.
 *
 * <p><b>Where it can't pay, it doesn't use the try.</b> A player who can't earn where they are
 * (creative, a world without games) is dealt today's board as practice and told why: the scored try
 * is kept for when it can count.
 *
 * <p><b>The restart hold.</b> In the last few minutes before a scheduled restart
 * ({@code games.restart_times}) a scored try the restart cut off would be gone, so a player whose
 * try is unused is dealt nothing and told when the server restarts, like any new run. Not today's
 * board as practice: that would show them the layout before their scored try. Practice after a
 * used try, and Classic play, have no try to lose and are never held.
 */
public abstract class CabinetGame implements Game {

    /**
     * Why a daily board is practice for a player who can't earn where they are (SkillRewards'
     * NOT_HERE, which says scores still count — practice records none, so it says what is true).
     */
    public static final String NOT_HERE_DAILY =
            "&7No tokens can be earned here, so today's board is practice. Your scored try waits for later.";

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
        return games().clock().dayKey();
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
        return seed(secret(), day, id());
    }

    /**
     * Deal today's board to {@code player}: the first deal of the day is the scored try (recorded
     * now, so closing the screen can't earn a second one); every later one is practice. A player
     * who can't earn here ({@link SkillRewards#canEarnHere}) gets practice without the try being
     * used, and is told once why.
     *
     * @return {@code null} when refused (told): the player's scored try is unused and a scheduled
     *         restart is minutes away ({@link GamesService#restartRefusal}). Nothing is dealt, so
     *         today's board isn't seen before the try, and the try is kept.
     */
    public DailyStart startDaily(Player player) {
        long day = today();
        boolean canEarn = games().rewards().canEarnHere(player);
        Refusal held = canEarn ? games().restartRefusal() : null;
        if (held != null && !usedTry(player, day)) {
            games().tell(player, held);
            return null;
        }
        long seed = dailySeed(day);
        if (!canEarn) {
            player.sendMessage(Text.of(NOT_HERE_DAILY));
        }
        try {
            return deal(day, seed, canEarn, () -> games().dao().markDailyAttempt(player.getUniqueId(), id(), day,
                    seed, "", games().clock().nowMillis()));
        } catch (SQLException e) {
            ctx.plugin().getLogger().warning("Could not record " + id() + "'s daily try for "
                    + player.getName() + " - it is practice: " + e.getMessage());
            return new DailyStart(day, seed, false);
        }
    }

    /**
     * Whether the player's scored try at {@code day}'s board is used (false if it can't be read,
     * so a hold keeps it rather than showing the board).
     */
    private boolean usedTry(Player player, long day) {
        try {
            return games().dao().dailyAttempt(player.getUniqueId(), id(), day);
        } catch (SQLException e) {
            return false;
        }
    }

    /** Writes a player's daily attempt row: true when it is their first today (the scored try). */
    @FunctionalInterface
    interface Attempt {
        boolean mark() throws SQLException;
    }

    /**
     * A daily deal: the scored try only where the player can earn, and only then is the attempt
     * row written — practice somewhere it can't count never uses the try up.
     */
    static DailyStart deal(long day, long seed, boolean canEarn, Attempt attempt) throws SQLException {
        return new DailyStart(day, seed, canEarn && attempt.mark());
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
        boolean gold = milestonesReached(s.milestonesFor(board), milestoneValue, lowerIsBetter).contains(3);
        games().tellProgress(g -> g.cabinetFinished(player, id(), false, gold)); // quests and achievements (E4)
        return new Finish(result, reached, paid, false);
    }

    /**
     * A daily-board run ended. The scored try records its score on today's daily board and, if the
     * goal was met, pays the daily reward once; practice records and pays nothing.
     */
    public Finish finishDaily(Player player, DailyStart start, long score, boolean lowerIsBetter, boolean goalMet) {
        if (start == null || !start.scored()) {
            games().tellProgress(g -> g.cabinetFinished(player, id(), true, false)); // practice counts (E4)
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
        games().tellProgress(g -> g.cabinetFinished(player, id(), false, false)); // quests and achievements (E4)
        return new Finish(result, List.of(), paid, false);
    }

    /**
     * A run that went to its end but records nothing (a loss or a draw against the Arcade, a
     * Connect Four win below hard, a creeper dug up): still a finish for the quests and
     * achievements (EXTRAS E4), with no medal. Never for a friend game, or a run closed early.
     *
     * @param practice a daily board played again after its scored try
     */
    protected void finishedUnscored(Player player, boolean practice) {
        games().tellProgress(g -> g.cabinetFinished(player, id(), practice, false)); // quests and achievements (E4)
    }

    /**
     * The boards this cabinet keeps all-time scores on, besides the one it publishes: none, unless
     * it has more than one (Creeper Sweeper's levels). A leaderboard display may name any of them.
     */
    public List<String> boards() {
        return List.of();
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

    /**
     * Whether the player may start a new game, board or match right now (gate steps 0-4, re-run
     * before every deal: a pause, a world change or the game closing since the screen opened all
     * count). They're told why not.
     */
    public boolean mayPlay(Player player) {
        Refusal refusal = games().canOpen(player, this);
        if (refusal != null) {
            games().tell(player, refusal);
            return false;
        }
        return true;
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
     * What a finished daily board's "Play again" says, decided when the screen is drawn:
     * "Play today's board again (practice)" while today's scored try is used, and "Play today's
     * board - your scored try" once it isn't (after midnight the next deal counts again).
     *
     * @param thing what the game calls its daily ("board", "pattern", "round")
     */
    public static String dailyAgain(String thing, boolean triedToday) {
        return triedToday ? "Play today's " + thing + " again &7(practice)"
                : "Play today's " + thing + " &7- your scored try";
    }

    /**
     * A daily board's seed: HMAC-SHA256 keyed with the secret (its 8 bytes, big-endian) over
     * "{@code day}|{@code gameId}", the first 8 bytes of the MAC as a long. The same inputs always
     * give the same seed; a changed day, game or secret gives an unrelated one; and knowing seeds
     * (boards) tells nothing about the key.
     */
    public static long seed(long secret, long day, String gameId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(ByteBuffer.allocate(Long.BYTES).putLong(secret).array(), "HmacSHA256"));
            byte[] out = mac.doFinal((day + "|" + (gameId == null ? "" : gameId)).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(out, 0, Long.BYTES).getLong();
        } catch (GeneralSecurityException e) {
            // Every Java runtime ships HmacSHA256; this can't happen on one that runs the server.
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
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
