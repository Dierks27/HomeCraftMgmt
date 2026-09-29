package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.Stars;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The words the Fresh Courses show a player (GEN-SPEC §5.4, §7, the weekly addendum §2), in one
 * place and tested: a course's tile name with the key fact in it (Bedrock shows lore only on
 * tap-and-hold), the star lines, the finish line, the Star Chart's lines and how a set reads.
 *
 * <p><b>The words follow the cadence.</b> How often the courses change is a setting, so every line
 * about a set takes its cadence in days and says "this week" (weekly, shipped), "today" (daily)
 * or "on this course" (any other: each set is a new course), through {@link GenCopy}; nothing here
 * says "today" on its own. The Star Chart is weekly whatever the cadence.
 *
 * <p>Pure: no Bukkit types, so the whole of it is checked for kid-safe words and for glyphs
 * Bedrock can draw (nothing above U+FFFF). Lines carry {@code &}-colour codes like the rest of the
 * plugin. The stars are the kid-facing score: one for finishing, so every line leads with what a
 * player has, then the next thing to go for, never with what they missed.
 */
public final class DailyText {

    /** "Mon 28 Sep": a day as players read it. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US);

    private DailyText() {
    }

    // ---- days --------------------------------------------------------------------------------

    /** "today", or the day's date: "Mon 28 Sep". */
    public static String dayText(long day, long today) {
        return day == today ? "today" : date(day);
    }

    /** "Mon 28 Sep". */
    public static String date(long day) {
        return DAY.format(LocalDate.ofEpochDay(day));
    }

    /**
     * A set's dates as a heading reads them: "Tue 29 Sep" for a day, "Mon 28 Sep-Sun 4 Oct" for a
     * longer set (its first and last day).
     */
    public static String setDates(int cadence, long firstDay) {
        return cadence <= Edition.DAILY ? date(firstDay) : date(firstDay) + "-" + date(firstDay + cadence - 1);
    }

    /** A set as a line names it: "the week of Mon 28 Sep" (weekly), "Tue 29 Sep" (daily), "Mon 28 Sep-Wed 30 Sep". */
    public static String setName(int cadence, long firstDay) {
        return cadence == Edition.WEEKLY ? "the week of " + date(firstDay) : setDates(cadence, firstDay);
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
     * A Fresh course's tile name on the Fresh Courses screen and the tier picker, its key fact in
     * the name: "&amp;aEasy Parkour &amp;7- ★★☆" once the player has stars in this set,
     * "&amp;cHard Parkour" before; a golf course always says its holes and par ("&amp;dGolf of the
     * Week &amp;7- 9 holes, par 29"), then its stars; a dropper its levels ("&amp;aEasy Dropper &amp;7-
     * 3 levels · ★★☆", EVENTS-DROPPER-SPEC §B.1.8). Golf's big course is named for the cadence
     * ({@link GenCopy#slotName}).
     *
     * @param stars the player's best stars on it in this set (0 = none yet)
     * @param holes golf: how many holes; a dropper: how many levels (ignored for any other trial)
     * @param par   golf: the course's par
     */
    public static String slotName(Slots.Def slot, int cadence, int stars, int holes, int par) {
        String head = colour(slot) + (slot == null ? GenCopy.NAME : GenCopy.slotName(slot, cadence));
        String starText = stars > 0 ? Stars.text(stars) : null;
        if (slot != null && slot.golf()) {
            return head + " &7- " + holes(holes) + ", par " + par + (starText == null ? "" : " " + starText);
        }
        if (slot != null && slot.dropper() && holes > 0) {
            return head + " &7- " + levels(holes) + (starText == null ? "" : " · " + starText);
        }
        return starText == null ? head : head + " &7- " + starText;
    }

    /** A Fresh course that can't be played right now: its name, being built (grey, GEN-SPEC §5.4). */
    public static String closedName(Slots.Def slot, int cadence) {
        return GenCopy.building(slot == null ? GenCopy.NAME : GenCopy.slotName(slot, cadence));
    }

    /**
     * A Fresh course's tile on the Courses or Golf tab: the name in its colour, the fact, and
     * "(new this week)" while it is its set's current course ("(last week's)" until the new one is
     * up), in the cadence's words ({@link GenCopy#newMark}, {@link GenCopy#oldMark}).
     */
    public static String tabName(Slots.Def slot, String name, String fact, boolean current, int cadence) {
        String n = colour(slot) + (name == null || name.isBlank() ? slot == null ? GenCopy.NAME
                : GenCopy.slotName(slot, cadence) : name);
        return n + (fact == null || fact.isBlank() ? "" : " &7- " + fact) + " " + mark(current, cadence);
    }

    /** "&amp;a(new this week)", or "&amp;8(last week's)" for the last set's course. */
    public static String mark(boolean current, int cadence) {
        return current ? "&a" + GenCopy.newMark(cadence) : "&8" + GenCopy.oldMark(cadence);
    }

    /** A trial's fact on its tab tile: its stars in this set, or that there is no time yet ("no time this week"). */
    public static String trialFact(int cadence, int stars) {
        return stars > 0 ? Stars.text(stars) : "no time " + GenCopy.when(cadence);
    }

    /** "1 hole", "9 holes". */
    public static String holes(int n) {
        return n + (n == 1 ? " hole" : " holes");
    }

    /** A dropper's "1 level", "5 levels". */
    public static String levels(int n) {
        return n + (n == 1 ? " level" : " levels");
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

    /** "&amp;eYour stars this week: ★★☆", or that there are none yet ("today", "on this course" by the cadence). */
    public static String starsNow(int cadence, int stars) {
        return stars > 0 ? "&eYour stars " + GenCopy.when(cadence) + ": &6" + Stars.text(stars)
                : "&7No stars " + GenCopy.when(cadence) + " yet - finish it for ★";
    }

    // ---- boards and rewards --------------------------------------------------------------------

    /**
     * The finish line for a new best in the set: "&amp;e★ Your best this week! &amp;7(was 1:02.3)";
     * "&amp;e★ Your first finish this week!" the first time.
     */
    public static String newBest(int cadence, String previous) {
        return previous == null ? "&e★ Your first finish " + GenCopy.when(cadence) + "!"
                : "&e★ " + GenCopy.yourBest(cadence) + "! &7(was " + previous + ")";
    }

    /**
     * "&amp;7This week's best: &amp;f0:58.1 &amp;7by &amp;fAlex" ("(yours)" for the viewer), or that
     * nobody has finished it yet in this set.
     */
    public static String setBest(int cadence, String score, String holder, boolean yours) {
        if (score == null) {
            return "&7No one has finished it " + (cadence == Edition.DAILY || cadence == Edition.WEEKLY
                    ? GenCopy.when(cadence) : "yet") + " - be the first!";
        }
        return "&7" + GenCopy.bestOf(cadence) + ": &f" + score + (yours ? " &7(yours)"
                : " &7by &f" + (holder == null ? "someone" : holder));
    }

    /** "&amp;7Your best this week: &amp;f1:02.3", or none yet. */
    public static String yourBest(int cadence, String score) {
        if (score == null) {
            return "&7You haven't finished it " + (cadence == Edition.DAILY || cadence == Edition.WEEKLY
                    ? GenCopy.when(cadence) : "yet") + ".";
        }
        return "&7" + GenCopy.yourBest(cadence) + ": &f" + score;
    }

    /**
     * "&amp;7First finish this week: &amp;6+1 token", or "&amp;a✔ First finish this week done";
     * nothing for 0.
     */
    public static String firstFinish(int cadence, int tokens, boolean done) {
        if (tokens <= 0) {
            return null;
        }
        String first = GenCopy.firstFinish(cadence);
        return done ? "&a✔ " + first + " done" : "&7" + first + ": &6+" + tokens + " token" + (tokens == 1 ? "" : "s");
    }

    // ---- the Star Chart ------------------------------------------------------------------------

    /** The Star Chart tile's name: "&amp;6Star Chart &amp;7- you: 14★ this week". */
    public static String chartName(long total) {
        return "&6Star Chart &7- you: " + Math.max(0, total) + "★ this week";
    }

    /** "&amp;7Next goal: 12★ (+2 tokens)" (each goal its own tokens), or every goal reached; nothing without goals. */
    public static String nextGoal(long total, List<DailyStars.Goal> goals) {
        List<Integer> stars = DailyStars.stars(goals == null ? List.of() : goals);
        int next = DailyStars.nextGoal(total, stars);
        if (next < 0) {
            return stars.isEmpty() ? null : "&aEvery goal this week reached!";
        }
        int reward = DailyStars.tokens(goals, next);
        return "&7Next goal: &f" + next + "★" + (reward > 0 ? " &7(+" + reward + " token" + (reward == 1 ? "" : "s")
                + ")" : "");
    }

    /** How stars work, for its tile's lore: each goal of the week with its own tokens. */
    public static List<String> howStars(List<DailyStars.Goal> goals) {
        List<String> out = new ArrayList<>(List.of(
                "&7Finish a course: &6★",
                "&7A good time: &6★★",
                "&7A great time: &6★★★",
                "&7Your best stars on each course",
                "&7add up on the Star Chart.",
                "&7A new chart every week!"));
        List<DailyStars.Goal> shown = new ArrayList<>();
        List<Integer> seen = new ArrayList<>();
        for (DailyStars.Goal g : goals == null ? List.<DailyStars.Goal>of() : goals) {
            if (g != null && g.stars() > 0 && g.tokens() > 0 && !seen.contains(g.stars())) {
                seen.add(g.stars());
                shown.add(g);
            }
        }
        shown.sort((a, b) -> Integer.compare(a.stars(), b.stars()));
        for (DailyStars.Goal g : shown) {
            out.add("&7Reach " + g.stars() + "★ in a week: &6+" + g.tokens() + " token" + (g.tokens() == 1 ? "" : "s"));
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
        List<DailyStars.Goal> goals = List.of(new DailyStars.Goal(6, 1), new DailyStars.Goal(12, 2));
        for (int cadence : new int[]{GenCopy.CLASSIC, 1, 3, 7, 14}) {
            for (Slots.Def s : cadence == GenCopy.CLASSIC ? List.<Slots.Def>of() : Slots.ALL) {
                for (int stars = 0; stars <= Stars.MAX; stars++) {
                    out.add(slotName(s, cadence, stars, s.plots(), 29));
                }
                out.add(closedName(s, cadence));
                out.add(tabName(s, s.name(), trialFact(cadence, 2), true, cadence));
                out.add(tabName(s, s.name(), trialFact(cadence, 0), false, cadence));
            }
            for (int stars = 0; stars <= Stars.MAX; stars++) {
                out.add(starsNow(cadence, stars));
            }
            out.add(newBest(cadence, null));
            out.add(newBest(cadence, "1:02.3"));
            out.add(setBest(cadence, null, null, false));
            out.add(setBest(cadence, "0:58.1", "Alex", false));
            out.add(setBest(cadence, "0:58.1", "Alex", true));
            out.add(yourBest(cadence, null));
            out.add(yourBest(cadence, "1:02.3"));
            out.add(firstFinish(cadence, 1, false));
            out.add(firstFinish(cadence, 2, true));
            if (cadence != GenCopy.CLASSIC) {
                out.add(setDates(cadence, 20_724));
                out.add(setName(cadence, 20_724));
            } else {
                out.add(trialFact(cadence, 0));
                out.add(trialFact(cadence, 2));
            }
        }
        out.add(starTimes(45_000, 70_000));
        out.add(starTimes(0, 0));
        out.add(starStrokes(29, 9));
        for (int stars = 1; stars <= Stars.MAX; stars++) {
            out.add(trialFinish(stars, 45_000, 70_000, 9));
            out.add(golfFinish(stars, 29, 9, 9));
        }
        out.add(chartName(14));
        out.add(nextGoal(3, goals));
        out.add(nextGoal(30, goals));
        out.addAll(howStars(goals));
        out.add(chartLabel(20_724, 20_724));
        out.add(chartLabel(20_717, 20_724));
        return out;
    }
}
