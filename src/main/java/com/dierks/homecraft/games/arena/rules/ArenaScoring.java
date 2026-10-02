package com.dierks.homecraft.games.arena.rules;

import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.TokenBalance;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a finished round is worth (EVENTS-DROPPER-SPEC §B.3.4): the boards it goes on and the
 * rewards it claims. It decides; {@code FallingFloors} records and pays, through
 * {@code GamesDao}/{@code Scores} and {@code SkillRewards.pay} (source {@code GAMES_FLOORS},
 * {@code daily_cap} 3), and the database refuses any one-time ref paid before.
 *
 * <ul>
 *   <li><b>Boards.</b> {@code ffsolo:<week>}: solo survival in ms, higher is better (fair, since
 *       the week's shapes are the same for everyone). {@code ffwins:<week>}: multiplayer wins, a
 *       count through {@code addPoints}.</li>
 *   <li><b>Rewards.</b> {@code DAILY_CHALLENGE} for the first full round of the day (a played-out
 *       multiplayer round, or solo for at least 20 s); {@code MILESTONE} for solo 30 / 60 / 120 s,
 *       once ever; {@code FEATURED} when it is today's pick.</li>
 *   <li><b>Wins pay nothing extra</b>, so siblings can't farm tokens by taking turns to lose: the
 *       winner's claims are exactly the loser's.</li>
 * </ul>
 * A round called off earns nothing, and neither does leaving: a player who left didn't play the
 * round out, so they get no board entry, no milestone and no daily.
 */
public final class ArenaScoring {

    /** The solo board's stem: {@code ffsolo:<week>}. */
    public static final String SOLO_BOARD = "ffsolo";
    /** The wins board's stem: {@code ffwins:<week>}. */
    public static final String WINS_BOARD = "ffwins";
    /** A solo round counts as a full round for the daily reward from 20 s. */
    public static final long SOLO_DAILY_TICKS = 20L * RoundSettings.TICKS_PER_SECOND;
    /** The E4 quest {@code game_floors_minute}: last a whole minute. */
    public static final long MINUTE_TICKS = 60L * RoundSettings.TICKS_PER_SECOND;

    /**
     * The reward knobs ({@code games.falling_floors}): {@code daily_reward}, {@code milestones}
     * (seconds) and {@code milestone_rewards} (tokens, one per milestone).
     */
    public record Rewards(int daily, List<Integer> milestoneSeconds, List<Integer> milestoneTokens) {

        public Rewards {
            daily = Math.max(0, daily);
            List<Integer> secs = new ArrayList<>();
            List<Integer> toks = new ArrayList<>();
            int n = Math.min(milestoneSeconds == null ? 0 : milestoneSeconds.size(),
                    milestoneTokens == null ? 0 : milestoneTokens.size());
            for (int i = 0; i < n; i++) {
                Integer s = milestoneSeconds.get(i);
                Integer t = milestoneTokens.get(i);
                secs.add(s == null ? 0 : Math.max(0, s));
                toks.add(t == null ? 0 : Math.max(0, t));
            }
            milestoneSeconds = List.copyOf(secs);
            milestoneTokens = List.copyOf(toks);
        }

        /** The shipped values: 30 / 60 / 120 s, and {@code TokenBalance}'s daily and milestone tokens. */
        public static Rewards defaults() {
            return new Rewards(TokenBalance.FLOORS_DAILY, List.of(30, 60, 120),
                    TokenBalance.FLOORS_MILESTONES);
        }
    }

    /** How a board takes a value. */
    public enum BoardMode {
        /** Keep the player's best; higher is better ({@code Scores.submit(..., lowerIsBetter false)}). */
        BEST_HIGHER,
        /** Add to the player's total ({@code GamesDao.addPoints}). */
        ADD_POINTS
    }

    /** One board write. */
    public record BoardWrite(String board, long value, BoardMode mode) {
    }

    /**
     * One reward to pay through {@code SkillRewards.pay(player, game, GAMES_FLOORS, kind, ref,
     * tokens, daily_cap, detail)}.
     */
    public record Claim(RewardKind kind, String ref, int tokens, String detail) {
    }

    /**
     * What one player's round is worth.
     *
     * @param fullRound    they played it out: the daily reward's test
     * @param lastedMinute they lasted a whole minute (the E4 quest {@code game_floors_minute})
     */
    public record PlayerScore(UUID player, Standing standing, boolean fullRound, boolean lastedMinute,
                              List<BoardWrite> boards, List<Claim> claims) {
        public PlayerScore {
            boards = List.copyOf(boards);
            claims = List.copyOf(claims);
        }
    }

    private ArenaScoring() {
    }

    /**
     * Everyone's boards and claims for a round.
     *
     * @param day           today's day number (the daily and featured refs)
     * @param week          the week key the arena's shape was made for (the boards)
     * @param featuredBonus today's featured bonus, 0 when Falling Floors isn't today's pick
     */
    public static List<PlayerScore> score(RoundResult r, long day, long week, Rewards rewards, int featuredBonus) {
        List<PlayerScore> out = new ArrayList<>();
        if (r == null || r.calledOff()) {
            return out;
        }
        Rewards rw = rewards == null ? Rewards.defaults() : rewards;
        for (Standing s : r.standings()) {
            boolean full = fullRound(r, s);
            boolean minute = !s.left() && s.survivedTicks() >= MINUTE_TICKS;
            List<BoardWrite> boards = new ArrayList<>();
            List<Claim> claims = new ArrayList<>();
            if (r.solo() && !s.left()) {
                boards.add(new BoardWrite(soloBoard(week), s.survivalMs(), BoardMode.BEST_HIGHER));
                for (int i = 0; i < rw.milestoneSeconds().size(); i++) {
                    int secs = rw.milestoneSeconds().get(i);
                    int tokens = rw.milestoneTokens().get(i);
                    if (secs > 0 && tokens > 0 && s.survivalMs() >= secs * 1000L) {
                        claims.add(new Claim(RewardKind.MILESTONE, milestoneRef(i + 1), tokens,
                                ArenaText.NAME + ": lasted " + ArenaText.clock(secs * (long) RoundSettings.TICKS_PER_SECOND)));
                    }
                }
            }
            if (s.winner()) {
                boards.add(new BoardWrite(winsBoard(week), 1, BoardMode.ADD_POINTS));
            }
            if (full && rw.daily() > 0) {
                claims.add(new Claim(RewardKind.DAILY_CHALLENGE, dailyRef(day), rw.daily(),
                        ArenaText.NAME + ": daily challenge"));
            }
            if (full && featuredBonus > 0) {
                claims.add(new Claim(RewardKind.FEATURED, featuredRef(day), featuredBonus,
                        ArenaText.NAME + ": today's pick"));
            }
            out.add(new PlayerScore(s.player(), s, full, minute, boards, claims));
        }
        return out;
    }

    /**
     * Whether a player's round is a "full round" (the daily reward): not left, and either a
     * contested multiplayer round or a solo round of at least 20 s.
     */
    public static boolean fullRound(RoundResult r, Standing s) {
        if (r == null || s == null || r.calledOff() || s.left()) {
            return false;
        }
        return r.solo() ? s.survivedTicks() >= SOLO_DAILY_TICKS : r.contested();
    }

    /** {@code ffsolo:<week>}. */
    public static String soloBoard(long week) {
        return SOLO_BOARD + ":" + week;
    }

    /** {@code ffwins:<week>}. */
    public static String winsBoard(long week) {
        return WINS_BOARD + ":" + week;
    }

    /** The daily ref, the same as {@code SkillRewards.dailyRef}: {@code daily:<day>}. */
    public static String dailyRef(long day) {
        return "daily:" + day;
    }

    /**
     * A solo milestone, once ever: {@code SkillRewards.milestoneRef("ffsolo", n)}, on the stem and
     * not the weekly board, so a new week never pays a milestone twice.
     */
    public static String milestoneRef(int n) {
        return "ms:" + SOLO_BOARD + ":" + n;
    }

    /** Today's featured bonus ref, the same as {@code SkillRewards.featuredRef}: {@code featured:<day>}. */
    public static String featuredRef(long day) {
        return "featured:" + day;
    }
}
