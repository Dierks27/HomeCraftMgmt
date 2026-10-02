package com.dierks.homecraft.games;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole arcade's token balance in one table (0.37; the owner's live test of 2 Oct, BALANCE-SPEC):
 * every shipped skill-game reward, cap, prize and entry, the code limits that must move with them, and
 * the Scratch Ticket's prizes.
 *
 * <p><b>Why one table.</b> Each number lives three times: here, in the bundled config.yml, and in a
 * settings record's {@code defaults()}. Those records read their token numbers from these constants,
 * so a retune is this file plus config.yml (and a config revision, below), and the tests hold the three together:
 * {@code GamesConfigTest} (config.yml parses to exactly the defaults) and {@code EconomyMigrationTest}
 * (every {@link #ROWS} "now" is what config.yml ships, every "old" what 0.35 and 0.36 shipped). The
 * other tests read their expectations from here, or pin explicit settings when they test a mechanism.
 *
 * <p><b>The rule behind the numbers</b> (BALANCE-SPEC §3.1): about a token a minute of first-time
 * play, the quests' rate; a course's first finish of a set 10-25; the small repeatable rewards 5; caps
 * that stop farming without biting on a normal evening; and every reward paid whole fitting each cap
 * it counts toward ({@link RewardCeilings} warns at load when one doesn't). Games of chance, quests,
 * achievements and prices are not here: they are unchanged.
 *
 * <p><b>Config revision 20</b> ({@code EconomyMigration}) is driven by {@link #ROWS}: each row's
 * {@code old} (what 0.35.0 and 0.36.0 shipped) moves to its {@code now} (what 0.37.0 ships) wherever the
 * owner hasn't changed it. Both are frozen as literal numbers, never these constants, so a later retune
 * can't change what revision 20 did: it edits the constants and config.yml, and adds a new revision whose
 * "old" is what 0.37.0 shipped, so a server already past 20 is moved again and one that isn't is moved
 * by both. {@code EconomyMigrationTest} pins every {@code now} to this release's config.yml, so a retune
 * fails it: the answer is a new revision (and pinning these rows to a verbatim copy of 0.37.0's file),
 * never an edit to these rows.
 */
public final class TokenBalance {

    private TokenBalance() {
    }

    // ---- common (games.*) --------------------------------------------------------------------------

    /** {@code games.skill_daily_cap}: all skill games together, a day. */
    public static final int SKILL_DAILY_CAP = 60;
    /** {@code games.featured_bonus}: today's pick, first finish, once a day across games. */
    public static final int FEATURED_BONUS = 5;

    // ---- the six solo cabinets (games.<cabinet>.*) --------------------------------------------------

    /** {@code milestone_reward}: each bronze, silver and gold, once ever, paid whole. */
    public static final int CABINET_MILESTONE = 5;
    /** {@code daily_reward}: the daily goal on the scored try. */
    public static final int CABINET_DAILY = 5;
    /** {@code daily_cap}: the goal and two medals. */
    public static final int CABINET_DAILY_CAP = 15;

    // ---- Connect Four and Tic-Tac-Toe ------------------------------------------------------------------

    /** {@code daily_reward}: the day's first result against the Arcade. */
    public static final int DUEL_DAILY = 5;
    /** {@code daily_cap}. */
    public static final int DUEL_DAILY_CAP = 5;

    // ---- time trials (games.trials.*) ---------------------------------------------------------------

    /** {@code first_clear.easy}, once ever per course, not capped. */
    public static final int TRIALS_FIRST_CLEAR_EASY = 10;
    /** {@code first_clear.medium}. */
    public static final int TRIALS_FIRST_CLEAR_MEDIUM = 15;
    /** {@code first_clear.hard}. */
    public static final int TRIALS_FIRST_CLEAR_HARD = 25;
    /** {@code first_clear.extreme}. */
    public static final int TRIALS_FIRST_CLEAR_EXTREME = 50;
    /** {@code weekly_best_bonus}. */
    public static final int TRIALS_WEEKLY_BEST = 10;
    /** {@code course_of_week_bonus}. */
    public static final int TRIALS_COURSE_OF_WEEK = 5;
    /** {@code daily_cap}: every trial course together (Fresh first finishes count, so keep it above them). */
    public static final int TRIALS_DAILY_CAP = 40;

    // ---- mini golf (games.golf.*) ---------------------------------------------------------------------

    /** {@code par_reward}. */
    public static final int GOLF_PAR = 5;
    /** {@code hole_in_one_reward}. */
    public static final int GOLF_HOLE_IN_ONE = 3;
    /** {@code first_clear}, once ever per course, not capped. */
    public static final int GOLF_FIRST_CLEAR = 15;
    /**
     * {@code daily_cap}: shared by every golf course, so it holds both Fresh golf first finishes of a set in one
     * day, in either order (25 + 10), after the first one's par and three holes-in-one (5 + 9): a cap-held
     * first finish is never Golf of the Week's 9-hole round (BALANCE-SPEC §3.1, §4.1).
     */
    public static final int GOLF_DAILY_CAP = 50;

    // ---- the Weekly Cup (games.cup.*) -------------------------------------------------------------------

    /** {@code entry}. */
    public static final int CUP_ENTRY = 10;
    /** {@code server_topup}: at least twice the entry, so nobody timed in a Cup of 2 or 3 loses. */
    public static final int CUP_TOPUP = 20;

    // ---- Fresh Courses (games.fresh.*; the last two numbers of each Slots.Def row) ---------------------

    /** {@code daily_cap}: the Star Chart's tokens a day (both goals in one day). */
    public static final int FRESH_DAILY_CAP = 15;
    /** {@code rewards.clear_weekly.fresh_parkour_easy} / {@code clear_daily}. */
    public static final int FRESH_PARKOUR_EASY_WEEKLY = 10;
    public static final int FRESH_PARKOUR_EASY_DAILY = 5;
    /** {@code fresh_parkour}. */
    public static final int FRESH_PARKOUR_WEEKLY = 15;
    public static final int FRESH_PARKOUR_DAILY = 8;
    /** {@code fresh_parkour_hard}. */
    public static final int FRESH_PARKOUR_HARD_WEEKLY = 20;
    public static final int FRESH_PARKOUR_HARD_DAILY = 10;
    /** {@code fresh_rings}. */
    public static final int FRESH_RINGS_WEEKLY = 15;
    public static final int FRESH_RINGS_DAILY = 8;
    /** {@code fresh_golf}: Golf of the Week (Golf v4 makes its rounds 20-25 minutes). */
    public static final int FRESH_GOLF_WEEKLY = 25;
    public static final int FRESH_GOLF_DAILY = 12;
    /** {@code fresh_tiny_golf}. */
    public static final int FRESH_TINY_GOLF_WEEKLY = 10;
    public static final int FRESH_TINY_GOLF_DAILY = 5;
    /** {@code fresh_boat}: the Mountain Run (a 2-3 minute run). */
    public static final int FRESH_BOAT_WEEKLY = 15;
    public static final int FRESH_BOAT_DAILY = 8;
    /** {@code fresh_dropper_easy}. */
    public static final int FRESH_DROPPER_EASY_WEEKLY = 10;
    public static final int FRESH_DROPPER_EASY_DAILY = 5;
    /** {@code fresh_dropper}. */
    public static final int FRESH_DROPPER_WEEKLY = 15;
    public static final int FRESH_DROPPER_DAILY = 8;
    /** {@code star_goals.weekly_tokens} (the goals stay 6 and 12 stars). */
    public static final List<Integer> STAR_WEEKLY_TOKENS = List.of(5, 10);
    /** {@code star_goals.daily_tokens} (the goals stay 10 and 25 stars). */
    public static final List<Integer> STAR_DAILY_TOKENS = List.of(5, 10);

    // ---- Race Night (games.race_night.*) ----------------------------------------------------------------

    /** {@code prizes}: the night's 1st, 2nd and 3rd. */
    public static final List<Integer> RACE_PRIZES = List.of(20, 12, 8);
    /** {@code finisher_prize}: everyone else who finished a race. */
    public static final int RACE_FINISHER_PRIZE = 5;
    /** The code's bound: the most one racer wins a night, and the most one {@code prizes} entry may be. */
    public static final int RACE_MAX_PRIZE_PER_NIGHT = 30;
    /** The code's bound on {@code finisher_prize}. */
    public static final int RACE_MAX_FINISHER_PRIZE = 10;

    // ---- Falling Floors (games.falling_floors.*) ---------------------------------------------------------

    /** {@code daily_reward}: the first full round of the day. */
    public static final int FLOORS_DAILY = 5;
    /** {@code milestone_rewards}: 30, 60 and 120 seconds solo, once ever, paid whole. */
    public static final List<Integer> FLOORS_MILESTONES = List.of(5, 10, 15);
    /**
     * {@code daily_cap}: every milestone and the daily (5 + 10 + 15 + 5): a solo round that first lasts 2:00 passes
     * 0:30 and 1:00 on the way, and each medal is paid whole, so the cap holds them all on that day (the v4 audit,
     * ECON-R3-00; BALANCE-SPEC's 20 held only the biggest and the daily).
     */
    public static final int FLOORS_DAILY_CAP = 35;
    /** The code's bound on {@code daily_reward} and each {@code milestone_rewards} entry. */
    public static final int FLOORS_MAX_REWARD = 50;

    // ---- the Scratch Ticket (arcade.lotto.payouts; config only) ------------------------------------------

    /** Its prizes: 0×25, 3×40, 10×22, 25×9, 60×3 and the jackpot ×1, 89.5% with the pot at 150. */
    public static final List<Map<String, Object>> TICKET_PAYOUTS = ticket(10, 25, 60);
    /**
     * The ticket's other keys as 0.35.0 and 0.36.0 shipped them (frozen; this release ships them unchanged):
     * its return is the prizes, the price and the jackpot together, so revision 20 moves the prizes only while
     * each of these is absent or still this.
     */
    public static final Map<String, Object> TICKET_SHIPPED = shipped("arcade.lotto.ticket_tokens", 10,
            "arcade.lotto.jackpot.seed", 50, "arcade.lotto.jackpot.per_ticket", 1, "arcade.lotto.jackpot.cap", 1000);

    // ---- revision 20's table --------------------------------------------------------------------------------

    /**
     * One value config revision 20 moves.
     *
     * @param path its full config key
     * @param old  what 0.35.0 and 0.36.0 shipped (frozen)
     * @param now  what 0.37.0 ships (frozen; the constant above for this release)
     * @param with the keys that make one unit with it, at what 0.35.0 and 0.36.0 shipped: it moves only while
     *             each is absent or still that (empty for all but the Scratch Ticket's prizes)
     */
    public record Row(String path, Object old, Object now, Map<String, Object> with) {

        public Row {
            with = with == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(with));
        }

        /** A value that moves on its own. */
        public Row(String path, Object old, Object now) {
            this(path, old, now, Map.of());
        }
    }

    /** The six solo cabinets, in config order. */
    public static final List<String> SOLO_CABINETS = List.of("creeper_sweeper", "ore_merge", "snake", "mini_match",
            "simon_says", "whack_a_zombie");
    /** Connect Four and Tic-Tac-Toe. */
    public static final List<String> DUEL_CABINETS = List.of("connect_four", "tic_tac_toe");

    /**
     * Every value revision 20 moves, in config order: 64 rows, each "now" a literal (0.37.0's value), never a
     * constant. Don't edit them to retune: add a revision.
     */
    public static final List<Row> ROWS = rows();

    private static final Map<String, Row> BY_PATH = byPath();

    /** The row for {@code path}, or {@code null}. */
    public static Row row(String path) {
        return BY_PATH.get(path);
    }

    private static List<Row> rows() {
        List<Row> r = new ArrayList<>();
        r.add(new Row("games.skill_daily_cap", 6, 60));
        r.add(new Row("games.featured_bonus", 1, 5));
        for (String cab : SOLO_CABINETS) {
            r.add(new Row("games." + cab + ".milestone_reward", 1, 5));
            r.add(new Row("games." + cab + ".daily_reward", 1, 5));
            r.add(new Row("games." + cab + ".daily_cap", 2, 15));
        }
        for (String cab : DUEL_CABINETS) {
            r.add(new Row("games." + cab + ".daily_reward", 1, 5));
            r.add(new Row("games." + cab + ".daily_cap", 1, 5));
        }
        r.add(new Row("games.trials.first_clear.easy", 5, 10));
        r.add(new Row("games.trials.first_clear.medium", 10, 15));
        r.add(new Row("games.trials.first_clear.hard", 20, 25));
        r.add(new Row("games.trials.first_clear.extreme", 40, 50));
        r.add(new Row("games.trials.weekly_best_bonus", 5, 10));
        r.add(new Row("games.trials.course_of_week_bonus", 2, 5));
        r.add(new Row("games.trials.daily_cap", 4, 40));
        r.add(new Row("games.golf.par_reward", 2, 5));
        r.add(new Row("games.golf.hole_in_one_reward", 1, 3));
        r.add(new Row("games.golf.first_clear", 5, 15));
        r.add(new Row("games.golf.daily_cap", 4, 50));
        r.add(new Row("games.cup.entry", 5, 10));
        r.add(new Row("games.cup.server_topup", 10, 20));
        r.add(new Row("games.fresh.daily_cap", 2, 15));
        String w = "games.fresh.rewards.clear_weekly.";
        r.add(new Row(w + "fresh_parkour_easy", 2, 10));
        r.add(new Row(w + "fresh_parkour", 3, 15));
        r.add(new Row(w + "fresh_parkour_hard", 4, 20));
        r.add(new Row(w + "fresh_rings", 3, 15));
        r.add(new Row(w + "fresh_golf", 3, 25));
        r.add(new Row(w + "fresh_tiny_golf", 2, 10));
        r.add(new Row(w + "fresh_boat", 3, 15));
        r.add(new Row(w + "fresh_dropper_easy", 2, 10));
        r.add(new Row(w + "fresh_dropper", 3, 15));
        String d = "games.fresh.rewards.clear_daily.";
        r.add(new Row(d + "fresh_parkour_easy", 1, 5));
        r.add(new Row(d + "fresh_parkour", 2, 8));
        r.add(new Row(d + "fresh_parkour_hard", 3, 10));
        r.add(new Row(d + "fresh_rings", 2, 8));
        r.add(new Row(d + "fresh_golf", 2, 12));
        r.add(new Row(d + "fresh_tiny_golf", 1, 5));
        r.add(new Row(d + "fresh_boat", 2, 8));
        r.add(new Row(d + "fresh_dropper_easy", 1, 5));
        r.add(new Row(d + "fresh_dropper", 2, 8));
        r.add(new Row("games.fresh.star_goals.weekly_tokens", List.of(1, 2), List.of(5, 10)));
        r.add(new Row("games.fresh.star_goals.daily_tokens", List.of(1, 1), List.of(5, 10)));
        r.add(new Row("games.race_night.prizes", List.of(5, 3, 2), List.of(20, 12, 8)));
        r.add(new Row("games.race_night.finisher_prize", 1, 5));
        r.add(new Row("games.falling_floors.daily_reward", 1, 5));
        r.add(new Row("games.falling_floors.milestone_rewards", List.of(1, 2, 3), List.of(5, 10, 15)));
        r.add(new Row("games.falling_floors.daily_cap", 3, 35));
        r.add(new Row("arcade.lotto.payouts", ticket(8, 20, 50), ticket(10, 25, 60), TICKET_SHIPPED));
        return Collections.unmodifiableList(r);
    }

    private static Map<String, Row> byPath() {
        Map<String, Row> m = new LinkedHashMap<>();
        for (Row r : ROWS) {
            if (m.put(r.path(), r) != null) {
                throw new IllegalStateException("two rows for " + r.path());
            }
        }
        return Collections.unmodifiableMap(m);
    }

    /** {@code key, value, key, value, ...} as an ordered, unmodifiable map. */
    private static Map<String, Object> shipped(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }

    /** The Scratch Ticket's six rows with these three middle prizes (the rest never changed). */
    private static List<Map<String, Object>> ticket(int third, int fourth, int fifth) {
        return List.of(entry("tokens", 0, 25), entry("tokens", 3, 40), entry("tokens", third, 22),
                entry("tokens", fourth, 9), entry("tokens", fifth, 3), entry("jackpot", true, 1));
    }

    private static Map<String, Object> entry(String key, Object value, int weight) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        m.put("weight", weight);
        return Collections.unmodifiableMap(m);
    }
}
