package com.dierks.homecraft.games.chance.sportsbook;

import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.storage.SportsPicksDao;
import com.dierks.homecraft.storage.SportsPicksDao.Pick;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.60.0, "how did we do" (the owner: "any place we can check our bets after the fact to show how we did?"): a
 * player's picks a page at a time, and the sums of them (pure, tested; My picks draws them).
 *
 * <p>Money in and money back count settled picks only: an open pick isn't won or lost yet, so it is counted apart (how
 * many wait, and what is in them). "Up" is more came back than went in; "down" the other way round.
 */
public final class PickHistory {

    /** Picks a page of My picks shows (rows 1-4). */
    public static final int PER_PAGE = 36;

    private PickHistory() {
    }

    /**
     * How a run of picks went.
     *
     * @param made      every pick counted
     * @param open      of them, those waiting for their game
     * @param won       those that won
     * @param lost      those that didn't win
     * @param back      those given back (called off, or no result)
     * @param inCents   what went into the settled ones
     * @param backCents what came back from the settled ones (wins and refunds)
     * @param openCents what is in the open ones
     */
    public record Summary(int made, int open, int won, int lost, int back, long inCents, long backCents,
                          long openCents) {

        /** Money back less money in, over the settled picks (cents; under 0 is down). */
        public long net() {
            return backCents - inCents;
        }

        /** Settled picks. */
        public int settled() {
            return made - open;
        }
    }

    /**
     * The sums of {@code picks}: every one when {@code since} is 0; otherwise only those settled at or after
     * {@code since} (an open one then doesn't count at all).
     */
    public static Summary of(List<Pick> picks, long since) {
        int made = 0;
        int open = 0;
        int won = 0;
        int lost = 0;
        int back = 0;
        long in = 0;
        long out = 0;
        long waiting = 0;
        if (picks != null) {
            for (Pick p : picks) {
                if (p.open()) {
                    if (since > 0) {
                        continue;
                    }
                    made++;
                    open++;
                    waiting += p.stake() * 100L;
                    continue;
                }
                if (since > 0 && p.settledAt() < since) {
                    continue;
                }
                made++;
                in += p.stake() * 100L;
                out += p.paid();
                switch (p.status()) {
                    case SportsPicksDao.WON -> won++;
                    case SportsPicksDao.LOST -> lost++;
                    default -> back++;
                }
            }
        }
        return new Summary(made, open, won, lost, back, in, out, waiting);
    }

    /** "up $4.20", "down $3.00", or "even" (pure). */
    public static String upDown(long cents) {
        if (cents == 0) {
            return "even";
        }
        return (cents > 0 ? "up " : "down ") + Parlor.money(Math.abs(cents));
    }

    /** How many pages {@code n} things take at {@code per} a page (at least one). */
    public static int pages(int n, int per) {
        return Math.max(1, (Math.max(0, n) + per - 1) / per);
    }

    /** Page {@code page} (from 0; kept inside the pages there are) of {@code all}. */
    public static <T> List<T> page(List<T> all, int page, int per) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int p = Math.max(0, Math.min(page, pages(all.size(), per) - 1));
        return new ArrayList<>(all.subList(p * per, Math.min(all.size(), (p + 1) * per)));
    }

    /** When this week started (Monday, midnight) in {@code zone}, as epoch ms. */
    public static long weekStart(long now, ZoneId zone) {
        LocalDate monday = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /**
     * The summary tile's lines (pure): what went in and came back, overall and this week, and how many picks won.
     */
    public static List<String> lines(Summary all, Summary week) {
        List<String> out = new ArrayList<>();
        out.add("&7Picks made: &f" + all.made() + (all.open() > 0 ? " &7(" + all.open() + " waiting, &6"
                + Parlor.money(all.openCents()) + " &7in)" : ""));
        out.add("&7Won: &a" + all.won() + " &7- didn't win: &f" + all.lost()
                + (all.back() > 0 ? " &7- given back: &f" + all.back() : ""));
        out.add("&7Money in: &6" + Parlor.money(all.inCents()) + " &7- money back: &6" + Parlor.money(all.backCents()));
        out.add("&7Overall: " + colour(all.net()) + upDown(all.net()));
        out.add("&7This week: " + colour(week.net()) + upDown(week.net()) + " &7(" + week.settled() + " pick"
                + (week.settled() == 1 ? "" : "s") + ")");
        return out;
    }

    /** The colour of an amount up (green), down (grey) or even (white). */
    static String colour(long cents) {
        return cents > 0 ? "&a" : cents < 0 ? "&7" : "&f";
    }
}
