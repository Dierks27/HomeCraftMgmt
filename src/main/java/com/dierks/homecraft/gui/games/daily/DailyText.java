package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.Stars;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The words the daily courses show a player (GEN-SPEC §5.4, §7), in one place and tested: a
 * course's tile name with the key fact in it (Bedrock shows lore only on tap-and-hold), the star
 * lines, the finish line, the Star Chart's lines and how a course day reads.
 *
 * <p>Pure: no Bukkit types, so the whole of it is checked for kid-safe words and for glyphs
 * Bedrock can draw (nothing above U+FFFF). Lines carry {@code &}-colour codes like the rest of the
 * plugin. The stars are the kid-facing score: one for finishing, so every line leads with what a
 * player has, then the next thing to go for, never with what they missed.
 */
public final class DailyText {

    /** "Mon 28 Sep": a course day as players read it. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US);

    private DailyText() {
    }

    // ---- days --------------------------------------------------------------------------------

    /**
     * The course day at {@code now}: the day before the next change when the engine has scheduled
     * one ({@code nextChangeAt}, epoch ms), else the shipped 04:00 rollover in {@code zone}. So a
     * screen agrees with the engine about "today" without knowing its rollover.
     */
    public static long courseDay(long now, long nextChangeAt, ZoneId zone) {
        ZoneId z = zone == null ? ZoneOffset.UTC : zone;
        if (nextChangeAt > now) {
            return Instant.ofEpochMilli(nextChangeAt).atZone(z).toLocalDate().toEpochDay() - 1;
        }
        return new Edition(z, null, null).day(now);
    }

    /** "today", or the day's date: "Mon 28 Sep". */
    public static String dayText(long day, long today) {
        return day == today ? "today" : date(day);
    }

    /** "Mon 28 Sep". */
    public static String date(long day) {
        return DAY.format(LocalDate.ofEpochDay(day));
    }

    /** A time in whole seconds, as a star time reads: "1:10", "0:45". */
    public static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        long m = s / 60;
        long r = s % 60;
        return m + ":" + (r < 10 ? "0" : "") + r;
    }

    // ---- tiles -----------------------------------------------------------------------------------

    /** A slot's colour ({@code &a} easy ... {@code &d} golf), {@code &e} for anything else. */
    public static String colour(Slots.Def slot) {
        return slot == null || slot.colour() == null || slot.colour().isBlank() ? "&e" : slot.colour();
    }

    /**
     * A daily course's tile name on Today's Courses and the tier picker, its key fact in the name:
     * "&amp;aEasy Parkour &amp;7- ★★☆" once the player has stars today, "&amp;cHard Parkour" before;
     * a golf course always says its holes and par ("&amp;dDaily Golf &amp;7- 9 holes, par 29"), then
     * its stars.
     *
     * @param stars the player's best stars on it today (0 = none yet)
     * @param holes golf: how many holes (ignored for a time trial)
     * @param par   golf: the course's par
     */
    public static String slotName(Slots.Def slot, int stars, int holes, int par) {
        String head = colour(slot) + (slot == null ? "Daily course" : slot.name());
        String starText = stars > 0 ? Stars.text(stars) : null;
        if (slot != null && slot.golf()) {
            return head + " &7- " + holes(holes) + ", par " + par + (starText == null ? "" : " " + starText);
        }
        return starText == null ? head : head + " &7- " + starText;
    }

    /** A daily course that can't be played right now: its name, being built (grey, GEN-SPEC §5.4). */
    public static String closedName(Slots.Def slot) {
        return "&7" + (slot == null ? "Daily course" : slot.name()) + " &8- being built, back soon";
    }

    /**
     * A generated course's tile on the Courses or Golf tab: the name in its colour, the fact, and
     * "(new today)" while it is today's layout ("(yesterday's)" until today's is up).
     */
    public static String tabName(Slots.Def slot, String name, String fact, long layoutDay, long today) {
        String n = colour(slot) + (name == null || name.isBlank() ? slot == null ? "Daily course" : slot.name() : name);
        return n + (fact == null || fact.isBlank() ? "" : " &7- " + fact) + " " + mark(layoutDay, today);
    }

    /** "&amp;a(new today)", or "&amp;8(yesterday's)" for a layout from an earlier day. */
    public static String mark(long layoutDay, long today) {
        return layoutDay >= today ? "&a(new today)" : "&8(yesterday's)";
    }

    /** A trial's fact on its tab tile: its stars today, or that there is no time yet today. */
    public static String trialFact(int stars) {
        return stars > 0 ? Stars.text(stars) : "no time today";
    }

    /** "1 hole", "9 holes". */
    public static String holes(int n) {
        return n + (n == 1 ? " hole" : " holes");
    }

    // ---- stars ---------------------------------------------------------------------------------

    /** A time trial's star times: "&amp;7★★ under 1:10 · ★★★ under 0:45" (none set: nothing). */
    public static String starTimes(long goldMs, long silverMs) {
        if (goldMs <= 0 && silverMs <= 0) {
            return "&7★ for finishing";
        }
        List<String> parts = new ArrayList<>();
        if (silverMs > 0) {
            parts.add("★★ under " + clock(silverMs));
        }
        if (goldMs > 0) {
            parts.add("★★★ under " + clock(goldMs));
        }
        return "&7" + String.join(" · ", parts);
    }

    /** A golf course's star lines: "&amp;7★★ in 32 or less · ★★★ in 29 (par) or less". */
    public static String starStrokes(int par, int holes) {
        return "&7★★ in " + Stars.golfSilver(par, holes) + " or less · ★★★ in " + par + " (par) or less";
    }

    /** "★★☆ 2 stars!" */
    public static String starsWon(int stars) {
        int n = Math.max(1, Math.min(Stars.MAX, stars));
        return Stars.text(n) + " " + n + (n == 1 ? " star!" : " stars!");
    }

    /**
     * A time trial's finish line (GEN-SPEC §5.4): "&amp;e★★☆ 2 stars! &amp;7Next star: under 0:45.
     * This week: 9★". Three stars say so instead of a next star; an unknown week total (-1) is left
     * out.
     */
    public static String trialFinish(int stars, long goldMs, long silverMs, long weekTotal) {
        return finish(stars, trialNext(stars, goldMs, silverMs), weekTotal);
    }

    /** A golf round's finish line: "&amp;e★☆☆ 1 star! &amp;7Next star: 32 strokes or less. This week: 3★". */
    public static String golfFinish(int stars, int par, int holes, long weekTotal) {
        return finish(stars, golfNext(stars, par, holes), weekTotal);
    }

    /** What a time trial's next star needs: "Next star: under 0:45.", or "Top marks!" at three. */
    public static String trialNext(int stars, long goldMs, long silverMs) {
        long next = Stars.nextTrialMs(stars, goldMs, silverMs);
        return stars >= Stars.MAX ? "Top marks!" : next > 0 ? "Next star: under " + clock(next) + "." : "";
    }

    /** What a golf round's next star needs: "Next star: 32 strokes or less.", or "Top marks!". */
    public static String golfNext(int stars, int par, int holes) {
        int next = Stars.nextGolfStrokes(stars, par, holes);
        return stars >= Stars.MAX || next < 0 ? "Top marks!" : "Next star: " + next + " strokes or less.";
    }

    /** "&amp;7This week: &amp;69★". */
    public static String weekLine(long weekTotal) {
        return "&7This week: &6" + Math.max(0, weekTotal) + "★";
    }

    private static String finish(int stars, String next, long weekTotal) {
        StringBuilder out = new StringBuilder("&e").append(starsWon(stars));
        if (!next.isEmpty()) {
            out.append(" &7").append(next);
        }
        if (weekTotal >= 0) {
            out.append(' ').append(weekLine(weekTotal));
        }
        return out.toString();
    }

    /** "&amp;eYour stars today: ★★☆", or that there are none yet. */
    public static String starsToday(int stars) {
        return stars > 0 ? "&eYour stars today: &6" + Stars.text(stars) : "&7No stars today yet - finish it for ★";
    }

    // ---- boards and rewards --------------------------------------------------------------------

    /** The finish line for a new best today: "&amp;e★ Your best today! &amp;7(was 1:02.3)"; the first time today. */
    public static String bestToday(String previous) {
        return previous == null ? "&e★ Your first finish today!" : "&e★ Your best today! &7(was " + previous + ")";
    }

    /** "&amp;7Today's best: &amp;f0:58.1 &amp;7by &amp;fAlex" ("(yours)" for the viewer), or that there is none yet. */
    public static String todaysBest(String score, String holder, boolean yours) {
        if (score == null) {
            return "&7No one has finished it today - be the first!";
        }
        return "&7Today's best: &f" + score + (yours ? " &7(yours)" : " &7by &f" + (holder == null ? "someone" : holder));
    }

    /** "&amp;7Your best today: &amp;f1:02.3", or none yet. */
    public static String yourBestToday(String score) {
        return score == null ? "&7You haven't finished it today." : "&7Your best today: &f" + score;
    }

    /** "&amp;7First finish today: &amp;6+1 token", or "&amp;a✔ First finish today done"; nothing for 0. */
    public static String firstToday(int tokens, boolean done) {
        if (tokens <= 0) {
            return null;
        }
        return done ? "&a✔ First finish today done" : "&7First finish today: &6+" + tokens + " token" + (tokens == 1 ? "" : "s");
    }

    // ---- the Star Chart ------------------------------------------------------------------------

    /** The Star Chart tile's name: "&amp;6Star Chart &amp;7- you: 14★ this week". */
    public static String chartName(long total) {
        return "&6Star Chart &7- you: " + Math.max(0, total) + "★ this week";
    }

    /** "&amp;7Next goal: 25★ (+1 token)", or every goal reached; nothing when there are no goals. */
    public static String nextGoal(long total, List<Integer> goals, int reward) {
        int next = DailyStars.nextGoal(total, goals);
        if (next < 0) {
            return goals == null || goals.isEmpty() ? null : "&aEvery goal this week reached!";
        }
        return "&7Next goal: &f" + next + "★" + (reward > 0 ? " &7(+" + reward + " token" + (reward == 1 ? "" : "s")
                + ")" : "");
    }

    /** How stars work, for its tile's lore. */
    public static List<String> howStars(List<Integer> goals, int reward) {
        List<String> out = new ArrayList<>(List.of(
                "&7Finish a course: &6★",
                "&7A good time: &6★★",
                "&7A great time: &6★★★",
                "&7Your best on each course each",
                "&7day goes on the Star Chart.",
                "&7A new chart every week!"));
        List<Integer> shown = new ArrayList<>();
        for (Integer g : goals == null ? List.<Integer>of() : goals) {
            if (g != null && g > 0 && !shown.contains(g)) {
                shown.add(g);
            }
        }
        shown.sort(Integer::compare);
        if (!shown.isEmpty() && reward > 0) {
            List<String> each = new ArrayList<>();
            for (int g : shown) {
                each.add(g + "★");
            }
            out.add("&7Reach " + String.join(" and ", each) + " in a week:");
            out.add("&6+" + reward + " token" + (reward == 1 ? "" : "s") + " &7each.");
        }
        return out;
    }

    /** The Star Chart board's label: "Star Chart · this week", or the week it started. */
    public static String chartLabel(long week, long thisWeek) {
        return week == thisWeek ? "Star Chart · this week" : "Star Chart · week of " + date(week);
    }

    // ---- checks (tested) -------------------------------------------------------------------------

    /** Every fixed line above with sample values, for the copy test. */
    static List<String> everyLine() {
        List<String> out = new ArrayList<>();
        for (Slots.Def s : Slots.ALL) {
            for (int stars = 0; stars <= Stars.MAX; stars++) {
                out.add(slotName(s, stars, s.plots(), 29));
            }
            out.add(closedName(s));
            out.add(tabName(s, s.name(), trialFact(2), 10, 10));
            out.add(tabName(s, s.name(), trialFact(0), 9, 10));
        }
        out.add(starTimes(45_000, 70_000));
        out.add(starTimes(0, 0));
        out.add(starStrokes(29, 9));
        for (int stars = 1; stars <= Stars.MAX; stars++) {
            out.add(trialFinish(stars, 45_000, 70_000, 9));
            out.add(golfFinish(stars, 29, 9, 9));
            out.add(starsToday(stars));
        }
        out.add(starsToday(0));
        out.add(bestToday(null));
        out.add(bestToday("1:02.3"));
        out.add(todaysBest(null, null, false));
        out.add(todaysBest("0:58.1", "Alex", false));
        out.add(todaysBest("0:58.1", "Alex", true));
        out.add(yourBestToday(null));
        out.add(yourBestToday("1:02.3"));
        out.add(firstToday(1, false));
        out.add(firstToday(2, true));
        out.add(chartName(14));
        out.add(nextGoal(3, List.of(10, 25), 1));
        out.add(nextGoal(30, List.of(10, 25), 1));
        out.addAll(howStars(List.of(10, 25), 1));
        out.add(chartLabel(20_724, 20_724));
        out.add(chartLabel(20_717, 20_724));
        return out;
    }
}
