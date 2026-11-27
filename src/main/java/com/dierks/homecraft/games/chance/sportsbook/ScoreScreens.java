package com.dierks.homecraft.games.chance.sportsbook;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.60.0, what the sports corner's TVs and score ticker say (pure, tested; the Clubhouse draws it). The owner: "For the
 * bar, can you make several TVs? ... Also can you make a ticker someplace around the bar with a ticker that shows the
 * scores of the games."
 *
 * <ul>
 *   <li><b>A TV</b> shows one game at a time, big: its league and clock, then each side's short name and points. It
 *   turns to the next game every {@value #TV_SECONDS} seconds; each TV starts at a different game ({@code offset}), so
 *   the screens side by side show different games. With no game being played or just over, it lists the next games
 *   to start, or says there are none.</li>
 *   <li><b>The ticker</b> is one line of every score ("NBA MIL 98-101 TOR Q4 2:31 · NHL Final CGY 6-3 PIT"), moved
 *   along {@value #TICKER_STEP} letters every {@value #TICKER_TICKS} ticks ({@link #window}).</li>
 * </ul>
 */
public final class ScoreScreens {

    /** Seconds a TV shows one game. */
    public static final int TV_SECONDS = 8;
    /** Upcoming games a TV lists at a time. */
    public static final int TV_NEXT = 2;
    /** The ticker moves {@value} letters ... */
    public static final int TICKER_STEP = 2;
    /** ... every this many ticks. */
    public static final int TICKER_TICKS = 4;
    /** What goes between the ticker's end and its start again. */
    public static final String TICKER_GAP = "      ";
    /** The text shown with no games at all. */
    public static final String NONE = "No games on right now";

    private ScoreScreens() {
    }

    /** What a line on a TV is: its heading, a side, the winning side of a finished game, or a small note. */
    public enum Kind {
        HEAD, TEAM, LEAD, NOTE
    }

    /** One line on a TV. */
    public record Line(String text, Kind kind) {
    }

    /**
     * A game with a score to show.
     *
     * @param league its league's tab ("NBA")
     * @param away   the visitors' short name
     * @param home   the home side's short name
     * @param live   its last score
     */
    public record Item(String league, String away, String home, Scores.Live live) {
    }

    /**
     * A game still to start.
     *
     * @param league its league's tab
     * @param away   the visitors
     * @param home   the home side
     * @param starts when ("7:30 PM", "Sat 1:00 PM")
     */
    public record Upcoming(String league, String away, String home, String starts) {
    }

    /**
     * One TV's page at {@code second} (seconds counted up by the caller), the TV being number {@code offset}.
     */
    public static List<Line> tv(List<Item> items, List<Upcoming> next, long second, int offset) {
        List<Line> out = new ArrayList<>();
        if (items != null && !items.isEmpty()) {
            int n = items.size();
            int i = Math.floorMod(second / TV_SECONDS + offset, n);
            Item it = items.get(i);
            Scores.Live l = it.live();
            String when = l == null ? "" : l.when();
            out.add(new Line(it.league() + (when.isEmpty() ? "" : " · " + when), Kind.HEAD));
            boolean done = l != null && l.phase() == Scores.Phase.FINAL;
            int a = l == null ? 0 : l.away();
            int h = l == null ? 0 : l.home();
            out.add(new Line(it.away() + "  " + a, done && a > h ? Kind.LEAD : Kind.TEAM));
            out.add(new Line(it.home() + "  " + h, done && h > a ? Kind.LEAD : Kind.TEAM));
            if (n > 1) {
                out.add(new Line((i + 1) + " of " + n, Kind.NOTE));
            }
            return out;
        }
        if (next != null && !next.isEmpty()) {
            int pages = (next.size() + TV_NEXT - 1) / TV_NEXT;
            int p = Math.floorMod(second / TV_SECONDS + offset, pages);
            out.add(new Line("Next up", Kind.HEAD));
            for (int k = p * TV_NEXT; k < next.size() && k < (p + 1) * TV_NEXT; k++) {
                Upcoming u = next.get(k);
                out.add(new Line(u.league() + " " + u.away() + " at " + u.home(), Kind.TEAM));
                out.add(new Line(u.starts(), Kind.NOTE));
            }
            return out;
        }
        out.add(new Line(NONE, Kind.HEAD));
        return out;
    }

    /** The ticker's whole line: every score, or the next games, or {@link #NONE}. */
    public static String tickerText(List<Item> items, List<Upcoming> next) {
        List<String> parts = new ArrayList<>();
        if (items != null) {
            for (Item it : items) {
                parts.add(it.league() + " " + Scores.ticker(it.away(), it.home(), it.live()));
            }
        }
        if (!parts.isEmpty()) {
            return String.join("  ·  ", parts);
        }
        if (next != null && !next.isEmpty()) {
            for (Upcoming u : next) {
                parts.add(u.league() + " " + u.away() + " at " + u.home() + " " + u.starts());
            }
            return "Next up: " + String.join("  ·  ", parts);
        }
        return NONE;
    }

    /** Whether {@code text} is longer than the ticker shows at once (so it moves). */
    public static boolean scrolls(String text, int width) {
        return text != null && text.length() > width;
    }

    /**
     * The {@code width} letters of the ticker showing at {@code offset} (letters moved so far): the line runs round
     * and round, {@link #TICKER_GAP} between its end and its start. A line that fits is shown as it is, still.
     */
    public static String window(String text, int offset, int width) {
        if (text == null) {
            return "";
        }
        if (!scrolls(text, width)) {
            return text;
        }
        String loop = text + TICKER_GAP;
        int start = Math.floorMod(offset, loop.length());
        String twice = loop + loop;
        return twice.substring(start, start + width);
    }
}
