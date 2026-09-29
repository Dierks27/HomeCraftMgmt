package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.arena.rules.ArenaScoring;
import com.dierks.homecraft.games.arena.rules.ArenaText;
import com.dierks.homecraft.games.arena.rules.RoundResult;
import com.dierks.homecraft.games.arena.rules.RoundSettings;

import java.util.List;
import java.util.UUID;

/**
 * What a played-out Falling Floors round records and pays (EVENTS-DROPPER-SPEC §B.3.4): the pure
 * {@link ArenaScoring} decides, and this hands each decision to a {@link Ledger} in one fixed
 * order, so the live game and the tests run exactly the same steps.
 *
 * <ul>
 *   <li><b>Boards.</b> Solo survival goes on {@code ffsolo:<week>} (the best kept, higher is
 *       better); a multiplayer win adds 1 to {@code ffwins:<week>}.</li>
 *   <li><b>Rewards</b> go through {@code SkillRewards} (source {@code GAMES_FLOORS}, the game's
 *       {@code daily_cap}): the first full round of the day, the three solo milestones once ever,
 *       and today's pick. The database refuses a one-time ref paid before, which is what makes the
 *       daily once a day and the milestones once ever. A milestone is paid whole or not at all
 *       ({@code payWhole}, as Fresh Courses pays its one-time rewards): one the caps can only pay
 *       part of waits for another day instead of being recorded short for ever.</li>
 *   <li><b>A win pays nothing extra.</b> The winner's claims are exactly everyone else's, so
 *       siblings can't farm tokens by taking turns to lose.</li>
 *   <li><b>E4.</b> Lasting a whole minute counts toward the {@code game_floors_minute}
 *       achievement.</li>
 * </ul>
 *
 * <p>Pure apart from the ledger. A round called off records and pays nothing.
 */
public final class FloorsRewards {

    /** The game id every board and reward is kept under. */
    public static final String GAME = "falling_floors";

    /** Where a round's results go: the live boards and token rewards, or a test's fake. */
    public interface Ledger {

        /**
         * Keep the player's best on a board where higher is better.
         *
         * @return their previous best, {@code -1} when this is their first, or {@code null} when it
         *         didn't beat it (nothing to say)
         */
        Long best(UUID player, String board, long value);

        /** Add to the player's running total on a board. */
        void addPoints(UUID player, String board, long delta);

        /** Pay one reward (capped, once by its ref). @return the tokens paid, 0 when none */
        int pay(UUID player, ArenaScoring.Claim claim);

        /**
         * Pay one reward all or nothing ({@code SkillRewards.payWhole}): when today's caps can't pay
         * it whole, nothing is paid and nothing recorded, so it waits for another day.
         *
         * @return the tokens paid, 0 when none
         */
        int payWhole(UUID player, ArenaScoring.Claim claim);

        /** The player lasted a whole minute (the E4 achievement). */
        void lastedMinute(UUID player);

        /** Tell the player a line ({@code &}-coded). */
        void tell(UUID player, String line);
    }

    private FloorsRewards() {
    }

    /**
     * Record and pay a round.
     *
     * @param day           today's day number (the daily and featured refs)
     * @param week          the week the round's floors were made for (the boards)
     * @param featuredBonus today's featured bonus, 0 when Falling Floors isn't today's pick
     * @return what each player's round was worth (empty for a round called off)
     */
    public static List<ArenaScoring.PlayerScore> apply(RoundResult result, long day, long week,
                                                       ArenaScoring.Rewards rewards, int featuredBonus, Ledger ledger) {
        List<ArenaScoring.PlayerScore> scores = ArenaScoring.score(result, day, week, rewards, featuredBonus);
        for (ArenaScoring.PlayerScore ps : scores) {
            for (ArenaScoring.BoardWrite b : ps.boards()) {
                switch (b.mode()) {
                    case BEST_HIGHER -> {
                        Long previous = ledger.best(ps.player(), b.board(), b.value());
                        String line = bestLine(b.value(), previous);
                        if (line != null) {
                            ledger.tell(ps.player(), line);
                        }
                    }
                    case ADD_POINTS -> ledger.addPoints(ps.player(), b.board(), b.value());
                }
            }
            for (ArenaScoring.Claim c : ps.claims()) {
                if (c.kind() == RewardKind.MILESTONE) {
                    ledger.payWhole(ps.player(), c); // once ever: never short-paid near the cap (F review #5)
                } else {
                    ledger.pay(ps.player(), c);
                }
            }
            if (ps.lastedMinute()) {
                ledger.lastedMinute(ps.player());
            }
        }
        return scores;
    }

    /**
     * The line for a solo time that went on this week's board: a first time, or a new best with
     * the old one; {@code null} when it didn't beat their best.
     *
     * @param previous their previous best (ms), {@code -1} for none, {@code null} when not beaten
     */
    static String bestLine(long ms, Long previous) {
        if (previous == null) {
            return null;
        }
        String now = ArenaText.clock(ms / RoundSettings.MS_PER_TICK);
        return previous < 0 ? "&aYour first solo time this week: " + now + "!"
                : "&aNew best this week: " + now + " &7(was " + ArenaText.clock(previous / RoundSettings.MS_PER_TICK)
                + ")&a!";
    }
}
