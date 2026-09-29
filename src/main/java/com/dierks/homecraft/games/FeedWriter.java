package com.dierks.homecraft.games;

import com.dierks.homecraft.games.gen.engine.FreshFeed;

import java.util.ArrayList;
import java.util.Collections;
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
     * What a Fresh Courses entry adds (GEN-SPEC §5.6, the weekly addendum), written as
     * {@code daily:{day,nextAt?,goldMs?,silverMs?,cadence?,lastDay?}}. Never a full seed, a rev, a
     * half or a UUID.
     *
     * @param day      its set's first day ({@code 2026-09-28})
     * @param nextAt   when the next set is due (epoch ms), or 0 when there is none (a Classic)
     * @param goldMs   the 3-star time, or {@code null} (golf)
     * @param silverMs the 2-star time, or {@code null} (golf)
     * @param cadence  the set's length in days (7 weekly, 1 daily), or 0 when unknown
     * @param lastDay  its set's last day ({@code 2026-10-04}), or {@code null}
     */
    record Daily(String day, long nextAt, Long goldMs, Long silverMs, int cadence, String lastDay) {

        /** The shape before cadences: no cadence or last day. */
        public Daily(String day, long nextAt, Long goldMs, Long silverMs) {
            this(day, nextAt, goldMs, silverMs, 0, null);
        }
    }

    /**
     * A generated time-trial course: {@link #course} plus its {@link Daily} part, its record from
     * its set's board. Until the feed knows the daily part, it is written as a plain course.
     */
    default void course(String id, String name, String kind, String tier, Long recordMs, Long recordAt,
                        String holder, Daily daily) {
        course(id, name, kind, tier, recordMs, recordAt, holder);
    }

    /** A generated golf course: {@link #golf} plus its {@link Daily} part. */
    default void golf(String id, String name, int holes, int par, Integer recordStrokes, Long recordAt,
                      String holder, Daily daily) {
        golf(id, name, holes, par, recordStrokes, recordAt, holder);
    }

    /**
     * The board behind entry {@code id} (a course or a golf course), for its {@code top} list
     * (EXTRAS E3): which game's scores, which board, which way is better and its unit ({@code ms},
     * {@code strokes}). A cabinet's board is already in {@link #cabinet}. The writer reads the top
     * rows itself, so a holder's name is only ever looked up while names may be shown.
     */
    default void board(String id, String game, String board, boolean lowerIsBetter, String unit) {
    }

    /**
     * Fresh Courses' {@code fresh} object for its slot's entry (GEN-SPEC-KEEP §8): the live course's
     * code, short seed, dates and cadence. Holds no player.
     */
    default void fresh(String id, FreshFeed.Fresh fresh) {
    }

    /** Fresh Courses' {@code classic} object for a Classics slot's entry: the recalled course's code and window. */
    default void classic(String id, FreshFeed.Classic classic) {
    }

    /**
     * The top-level {@code freshHistory} array: every past (and current) Fresh course, newest first.
     * A record's and a top row's holder are written by the writer only while {@link #showNames()}.
     */
    default void freshHistory(List<FreshFeed.Entry> entries) {
    }

    /**
     * Whether this writer publishes {@link #freshHistory} (the website's feed does). Reading it costs
     * two queries per archived course, so a writer that would throw it away (a screen asking which
     * boards the games publish) is never handed one.
     */
    default boolean wantsHistory() {
        return false;
    }

    /**
     * The Star Chart: this week's best total and who holds it ({@code null} unless
     * {@link #showNames()}). Nothing until the feed knows it.
     *
     * @param weekIso the week's first day ({@code 2026-09-28})
     */
    default void starChart(String weekIso, Long best, String holder) {
    }

    // ---- Race Night and Falling Floors (EVENTS-DROPPER-SPEC §A.7, §B.3.4) ------------------------

    /**
     * Race Night's top-level {@code events} section (§A.7): the next night, the next start times, the
     * night on now, the last few nights and the season. Race Night writes it once from its own
     * engine; a second call replaces the first. The writer publishes only the parts that have
     * something in them, and nothing at all when none has; a holder's name only while
     * {@link #showNames()}. Never a UUID, a balance or a player's prize.
     */
    default void events(Events events) {
    }

    /**
     * An arena game's {@code games[]} entry (§B.3.4, Falling Floors):
     * {@code {id,name,kind:"arena",shape?,top?}}. Its {@code top} comes from the board named for the
     * same id with {@link #board}.
     *
     * @param shape this week's floor shape ({@code ring}, {@code disc}, ...), or {@code null}
     */
    default void arena(String id, String name, String shape) {
    }

    // ---- the Weekly Cup (EVENTS-OWNER-DECISIONS §D2; WP-C) ---------------------------------------

    /**
     * The {@code cup} object on course {@code id}'s entry: this week's Weekly Cup on it, live. The Cup
     * writes it from its own game; the writer joins it to the course's entry by id and drops it when
     * no course of that id was written. Never a player: only the entry, the pool and a head count.
     */
    default void cup(String id, Cup cup) {
    }

    /**
     * This week's Weekly Cup on one course.
     *
     * @param entry    tokens to enter
     * @param pool     the pool now: every entry, plus the server's top-up once 2 or more are in
     * @param entrants how many are in
     * @param endsAt   when it is paid out (epoch ms): the quests' week start at 04:00
     */
    record Cup(int entry, int pool, int entrants, long endsAt) {
    }

    /**
     * What Race Night publishes (§A.7). Every part may be {@code null} or empty: it is then left out.
     *
     * @param next     the next night (open or upcoming), or {@code null}
     * @param upcoming the start times (epoch ms) of the nights after it, soonest first
     * @param live     the night on now, or {@code null}
     * @param recent   the last nights, newest first (the writer keeps at most {@value #RECENT} of them)
     * @param season   this month's season board, or {@code null}
     */
    record Events(Next next, List<Long> upcoming, Live live, List<Recent> recent, Season season) {

        /** The most past nights published. */
        public static final int RECENT = 5;
        /** The most rows of one past night published. */
        public static final int ROWS = 8;

        public Events {
            upcoming = upcoming == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(upcoming));
            recent = recent == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(recent));
        }

        /**
         * The next night.
         *
         * @param courseId      the track's course id ({@code fresh_boat}), or {@code null} while
         *                      {@code course: auto} hasn't picked one
         * @param prizes        the tokens for the night's 1st, 2nd and 3rd
         * @param finisherPrize the tokens for every other racer who finished a race
         * @param prizeNight    whether it has one of the week's prize-night slots (false: just for fun)
         * @param racers        how many have joined (a count, never who)
         */
        public record Next(String id, String name, long joinAt, long startsAt, String courseId, String courseName,
                           int races, int laps, List<Integer> prizes, int finisherPrize, boolean prizeNight,
                           int racers, int maxRacers) {

            public Next {
                prizes = prizes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(prizes));
            }
        }

        /**
         * The night on now.
         *
         * @param state     {@code open}, {@code racing}, {@code break} or {@code results}
         * @param race      the race on now (1-based)
         * @param of        races tonight
         * @param standings the night's standings so far, best first
         */
        public record Live(String id, String state, int race, int of, int racers, List<Standing> standings) {

            public Live {
                standings = standings == null ? List.of()
                        : Collections.unmodifiableList(new ArrayList<>(standings));
            }
        }

        /**
         * One racer's line in {@link Live}.
         *
         * @param lap    the lap they are on now (0 before the start)
         * @param holder their name, or {@code null} (published only while names are shown)
         */
        public record Standing(int rank, int points, int lap, int laps, String holder) {
        }

        /**
         * A past night.
         *
         * @param at    when it ended (epoch ms)
         * @param state {@code done} or {@code called_off}
         * @param top   its result, best first (the writer keeps at most {@value Events#ROWS})
         */
        public record Recent(String id, long at, String courseId, String courseName, int racers, String state,
                             List<Top> top) {

            public Recent {
                top = top == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(top));
            }
        }

        /** One row of a past night's result: its points, and who, while names are shown. */
        public record Top(int rank, long value, String holder) {
        }

        /**
         * This month's season. Its {@code top} is read by the writer itself from the board (in points,
         * higher is better), so a name is only looked up while names may be shown.
         *
         * @param key   {@code 2026-10}
         * @param name  {@code October}
         * @param until when the season ends (epoch ms)
         * @param game  the board's game ({@code race_night})
         * @param board the board ({@code rnseason:2026-10})
         */
        public record Season(String key, String name, long until, String game, String board) {
        }
    }

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
