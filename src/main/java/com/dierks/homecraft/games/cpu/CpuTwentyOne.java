package com.dierks.homecraft.games.cpu;

import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Card;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * How an NPC plays a hand at the Twenty-One table: basic play (no splits here, so the hard and soft totals alone), with
 * the level's share of off-the-book choices and the personality's small tastes. Pure: the generator is the caller's.
 *
 * <p><b>The level.</b> Hard always plays the book. Medium goes its own way on about one choice in twelve, easy on
 * about one in three (it stands when it should hit, or hits when it should stand, and never doubles out of turn): the
 * further from the book, the more often its hand loses, as a person's would. None of it touches a real seat: an NPC's
 * hand is pretend and its choices only move the shared shoe on, as another player's would.
 */
public final class CpuTwentyOne {

    /** A move. */
    public enum Move {
        HIT,
        STAND,
        DOUBLE
    }

    /** How often each level leaves the book, out of 1000. */
    static final int[] OFF_BOOK = {330, 85, 0};

    private CpuTwentyOne() {
    }

    /**
     * The book's move for {@code cards} against the Arcade's up card.
     *
     * @param canDouble whether a Double is open to this hand now
     * @param doublesMore the personality that doubles on a couple of extra hands
     */
    public static Move book(List<Card> cards, Card up, boolean canDouble, boolean doublesMore) {
        int total = TwentyOneHand.best(cards);
        boolean soft = TwentyOneHand.soft(cards) && total <= 21;
        int d = up.value() == 1 ? 11 : up.value(); // the Arcade's card: 2-10, or 11 for an Ace
        Move m;
        if (soft) {
            m = switch (total) {
                case 13, 14 -> d == 5 || d == 6 ? Move.DOUBLE : Move.HIT;
                case 15, 16 -> d >= 4 && d <= 6 ? Move.DOUBLE : Move.HIT;
                case 17 -> d >= 3 && d <= 6 ? Move.DOUBLE : Move.HIT;
                case 18 -> d >= 3 && d <= 6 ? Move.DOUBLE : d == 2 || d == 7 || d == 8 ? Move.STAND : Move.HIT;
                default -> total <= 12 ? Move.HIT : Move.STAND;
            };
            if (doublesMore && total == 18 && d == 2) {
                m = Move.DOUBLE;
            }
            if (m == Move.DOUBLE && !canDouble) {
                m = total >= 18 ? Move.STAND : Move.HIT;
            }
            return m;
        }
        if (total <= 8) {
            m = Move.HIT;
        } else if (total == 9) {
            m = d >= 3 && d <= 6 ? Move.DOUBLE : Move.HIT;
        } else if (total == 10) {
            m = d <= 9 ? Move.DOUBLE : Move.HIT;
        } else if (total == 11) {
            m = d <= 10 || doublesMore ? Move.DOUBLE : Move.HIT;
        } else if (total == 12) {
            m = d >= 4 && d <= 6 ? Move.STAND : Move.HIT;
        } else if (total <= 16) {
            m = d <= 6 ? Move.STAND : Move.HIT;
        } else {
            m = Move.STAND;
        }
        if (m == Move.DOUBLE && !canDouble) {
            m = Move.HIT;
        }
        return m;
    }

    /**
     * The NPC's move: the book's, unless its level leaves the book this time. A careful one never doubles.
     *
     * @return never {@link Move#DOUBLE} when {@code canDouble} is false
     */
    public static Move decide(List<Card> cards, Card up, boolean canDouble, CpuLevel level,
                              CpuPersonality personality, RandomGenerator rng) {
        Move m = book(cards, up, canDouble, personality == CpuPersonality.BOLD);
        if (personality == CpuPersonality.CAREFUL && m == Move.DOUBLE) {
            m = TwentyOneHand.best(cards) >= 18 ? Move.STAND : Move.HIT;
        }
        if (rng.nextInt(1000) < OFF_BOOK[level.ordinal()]) {
            m = offBook(m, TwentyOneHand.best(cards));
        }
        return m;
    }

    /** The other choice a less sure player makes: a stand where the book hits, a hit where it stands, never a double. */
    static Move offBook(Move book, int total) {
        return switch (book) {
            case HIT, DOUBLE -> total >= 12 ? Move.STAND : Move.HIT;
            case STAND -> total < 20 ? Move.HIT : Move.STAND;
        };
    }

    /**
     * The pretend stake an NPC puts in, from the stakes on offer: the smallest for a careful one, the biggest for a bold
     * one, else one in the middle.
     *
     * @return 0 when nothing is on offer
     */
    public static int stake(List<Integer> playable, CpuPersonality personality) {
        if (playable == null || playable.isEmpty()) {
            return 0;
        }
        return switch (personality.stakeLean) {
            case -1 -> playable.get(0);
            case 1 -> playable.get(playable.size() - 1);
            default -> playable.get(playable.size() / 2);
        };
    }
}
