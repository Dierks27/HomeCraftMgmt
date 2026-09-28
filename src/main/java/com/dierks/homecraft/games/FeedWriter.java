package com.dierks.homecraft.games;

import java.util.List;
import java.util.Map;

/**
 * Where a game writes its entries of the website's Arcade feed, {@code GET /api/arcade}'s
 * {@code games[]} (spec §10).
 *
 * <p>One method per kind of entry, each taking plain values and no Bukkit types, so a game
 * describes itself from the SAME engine object it plays with — the numbers on the website are the
 * numbers in the game — and the feed builder owns the JSON, the number formats and the omission
 * rules. A game with nothing to publish (closed, no courses) writes nothing and its entry is
 * simply absent. Never a player's UUID or balance; a record holder's name only when
 * {@link #showNames()} says so.
 */
public interface FeedWriter {

    /**
     * One row of a game of chance's paytable.
     *
     * @param stake  the stake the row is for, or {@code null} when every stake pays the same
     *               (then {@code pays} is a multiple of the tokens put in, not tokens)
     * @param combo  what lands ("3 diamond", "two the same", "17 tokens", "win the flip")
     * @param pays   the multiple (no stake) or the tokens (with a stake)
     * @param chance its exact probability, or {@code null} for a row counted in {@code spaces}
     * @param spaces the Wheel: how many of the {@code of} spaces show it; else {@code null}
     * @param of     the Wheel: 24; else {@code null}
     */
    record PayRow(Integer stake, String combo, long pays, Double chance, Integer spaces, Integer of) {
    }

    /**
     * A game of chance.
     *
     * @param stakes     the stakes it takes (after the solve dropped any)
     * @param rtpByStake each stake's COMPUTED return as a fraction (0.8976); the feed publishes it
     *                   floored to one decimal percent, and the lowest as the headline {@code rtp}
     * @param dailyLimit plays a day, or {@code null} for none
     * @param paytable   its rows, or an empty list (Twenty-One, Higher or Lower)
     * @param rules      a one-line rules string, or {@code null}
     * @param extra      further keys in order ({@code payouts}, {@code maxMultiplier}, ...): values are
     *                   strings, numbers, booleans, or lists and string-keyed maps of those
     */
    void chance(String id, String name, List<Integer> stakes, Map<Integer, Double> rtpByStake, Integer dailyLimit,
                List<PayRow> paytable, String rules, Map<String, ?> extra);

    /**
     * A menu cabinet's published board.
     *
     * @param unit   {@code ms}, {@code points}, {@code flips}, {@code apples} or {@code wins}
     * @param best   the server record on that board, or {@code null} for none yet
     * @param holder who holds it, or {@code null} (published only when {@link #showNames()})
     */
    void cabinet(String id, String name, String board, String unit, boolean lowerIsBetter, Long best, String holder);

    /**
     * A time-trial course.
     *
     * @param kind     {@code parkour}, {@code elytra} or {@code boat}
     * @param tier     {@code easy}, {@code medium}, {@code hard} or {@code extreme}
     * @param recordMs the course record, or {@code null}
     * @param recordAt when it was set (epoch ms), or {@code null}
     * @param holder   who holds it, or {@code null}
     */
    void course(String id, String name, String kind, String tier, Long recordMs, Long recordAt, String holder);

    /**
     * A mini golf course.
     *
     * @param recordStrokes the course record, or {@code null}
     * @param recordAt      when it was set (epoch ms), or {@code null}
     * @param holder        who holds it, or {@code null}
     */
    void golf(String id, String name, int holes, int par, Integer recordStrokes, Long recordAt, String holder);

    /**
     * Whether record holders' names may be published ({@code web.dashboard.arcade_show_names},
     * shipped false). A game need not look names up when this is false.
     */
    default boolean showNames() {
        return false;
    }

    /** "1 in N" for a probability, the same N on the screen and in the feed: {@code Math.round(1 / p)}. */
    static long oneIn(double p) {
        return p <= 0 ? 0 : Math.round(1.0 / p);
    }
}
