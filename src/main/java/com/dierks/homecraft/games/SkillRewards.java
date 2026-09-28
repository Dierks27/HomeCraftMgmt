package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import org.bukkit.entity.Player;

/**
 * The small token rewards of the skill games (spec §6.1, R1.22, R2.12, R2.16).
 *
 * <p>The games are for fun, not a token farm: the token economy's budget (DESIGN §3.9, about 28
 * tokens on an active day) comes first. So every reward except a first clear counts toward the
 * game's {@code daily_cap} and the server-wide {@code games.skill_daily_cap} (shipped 6); a reward
 * that would pass a cap pays what is left, maybe nothing ("scores still count!"); a personal best
 * pays nothing; and one-time rewards (a milestone, today's challenge, a first clear) carry a stable
 * ref the database refuses to pay twice. Checked BEFORE anything one-time is marked as used: a
 * player who can't earn here (wrong world, creative) keeps the reward for later. Nothing is ever
 * earned by playing a game of chance (R1.17).
 *
 * <p>The ref builders below are the one spelling of each ref, so two games can never disagree
 * about what "today's featured bonus" is called.
 */
public final class SkillRewards {

    private final GamesService games;

    public SkillRewards(GamesService games) {
        this.games = games;
    }

    /**
     * Pay a reward: capped, once-only by {@code ref} (an empty ref = repeatable), one transaction,
     * then the chat line and sound ("✦ +1 token (Snake: daily challenge). You have N.").
     *
     * @param source       the ledger source (a course's own source for the time trials)
     * @param gameDailyCap the game's {@code daily_cap}
     * @return the tokens actually paid, 0 to {@code tokens}
     */
    public int pay(Player player, Game game, TokenService.Source source, RewardKind kind, String ref, int tokens,
                   int gameDailyCap, String detail) {
        // F1b: never for a CHANCE game; canEarn (online, not creative/spectator, allowed world incl.
        // games.worlds/play_worlds) BEFORE anything; dao.payReward(... dayKey, kind, ref, tokens,
        // gameDailyCap, config skill_daily_cap, !ref.isEmpty(), detail, now) in guard; feedback.
        return 0;
    }

    /** Today's daily challenge: {@code daily:<day>}. */
    public static String dailyRef(long day) {
        return "daily:" + day;
    }

    /** A board milestone, 1 = bronze .. 3 = gold: {@code ms:<board>:<n>}. */
    public static String milestoneRef(String board, int n) {
        return "ms:" + board + ":" + n;
    }

    /** Today's featured bonus: {@code featured:<day>}. */
    public static String featuredRef(long day) {
        return "featured:" + day;
    }

    /** A course's first clear: {@code first_clear:<course>}. */
    public static String firstClearRef(String course) {
        return "first_clear:" + course;
    }

    /** The week's best on a course: {@code weekly:<course>:<weekKey>}. */
    public static String weeklyRef(String course, long weekKey) {
        return "weekly:" + course + ":" + weekKey;
    }

    /** Finishing the course of the week today: {@code cotw:<day>}. */
    public static String courseOfWeekRef(long day) {
        return "cotw:" + day;
    }

    /** Golf at par or better on a course today: {@code par:<course>:<day>}. */
    public static String parRef(String course, long day) {
        return "par:" + course + ":" + day;
    }

    /** A hole-in-one on a hole today: {@code hio:<course>:<hole>:<day>}. */
    public static String holeInOneRef(String course, int hole, long day) {
        return "hio:" + course + ":" + hole + ":" + day;
    }
}
