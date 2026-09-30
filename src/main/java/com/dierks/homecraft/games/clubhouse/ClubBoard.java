package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.trial.TrialText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Clubhouse's results board (CLUBHOUSE-SPEC §4, §11): what it says, and when it may be drawn.
 *
 * <p><b>What it says.</b> The event's title, then up to {@value #MAX_ROWS} rows: "1. Sam 1:02.4
 * (+0.0)" for a party race (the gap to the winner), "1. Sam 28 pts" for Race Night, "1. Sam 24
 * strokes" for golf together. While a race or a golf group is going, the rows are its live
 * positions ("1. Sam lap 2/3 (+1.4)"); once it ends, its final result.
 *
 * <p><b>When.</b> A new result is drawn at once; live positions at most once a second, and only
 * when they changed. Nothing redraws per tick ({@link #offer}).
 *
 * <p>Pure: the text is plain values in, lines out.
 */
public final class ClubBoard {

    /** The most rows the board shows. */
    public static final int MAX_ROWS = 8;
    /** Live positions are drawn at most this often (ms). */
    public static final long LIVE_EVERY_MS = 1_000L;

    /**
     * One board: its title and rows ({@code &}-coded), and whether it shows a race still going.
     *
     * @param version what the board is about (an event's result, or a live race), for the redraw rule
     */
    public record Sheet(String title, List<String> rows, boolean live, String version) {

        public Sheet {
            rows = List.copyOf(rows == null ? List.of() : rows.subList(0, Math.min(MAX_ROWS, rows.size())));
            title = title == null ? "" : title;
        }

        /** The board's text, one line each. */
        public String text() {
            StringBuilder b = new StringBuilder(title);
            for (String r : rows) {
                b.append('\n').append(r);
            }
            return b.toString();
        }
    }

    /** The board with nothing to show yet. */
    public static Sheet idle() {
        return new Sheet("&6&lThe Clubhouse", List.of("&7Race results show here.", "&7Hang out, have fun!"), false,
                "idle");
    }

    private String shown;
    private long drawnAt = Long.MIN_VALUE / 2;
    private int draws;

    /**
     * Whether {@code sheet} should be drawn now: its text differs from what is shown, and it is a
     * result (drawn at once) or live positions whose last draw was at least {@value #LIVE_EVERY_MS} ms
     * ago. When true, it counts as drawn.
     */
    public boolean offer(Sheet sheet, long now) {
        if (sheet == null) {
            return false;
        }
        String text = sheet.text();
        if (text.equals(shown)) {
            return false;
        }
        if (sheet.live() && now - drawnAt < LIVE_EVERY_MS) {
            return false;
        }
        shown = text;
        drawnAt = now;
        draws++;
        return true;
    }

    /** Forget what was shown (the display was made again): the next offer draws. */
    public void forget() {
        shown = null;
        drawnAt = Long.MIN_VALUE / 2;
    }

    /** The text shown now, or {@code null}. */
    public String shown() {
        return shown;
    }

    /** How many times it was drawn. */
    public int draws() {
        return draws;
    }

    // ---- rows ----------------------------------------------------------------------------------------

    /** "&amp;e1. &amp;fSam &amp;71:02.4 (+0.0)": a finisher's time and the gap to the winner. */
    public static String timeRow(int place, String name, long ms, long winnerMs) {
        return "&e" + place + ". &f" + safe(name) + " &7" + TrialText.time(ms) + " (+" + tenths(ms - winnerMs) + ")";
    }

    /** "&amp;fLee &amp;7- still racing": someone without a time. */
    public static String noTimeRow(String name, String why) {
        return "&f" + safe(name) + " &7- " + why;
    }

    /** "&amp;e1. &amp;fSam &amp;728 pts". */
    public static String pointsRow(int place, String name, int points) {
        return "&e" + place + ". &f" + safe(name) + " &7" + points + (points == 1 ? " pt" : " pts");
    }

    /** "&amp;e1. &amp;fSam &amp;724 strokes (-2)". */
    public static String strokesRow(int place, String name, int strokes, int vsPar) {
        return "&e" + place + ". &f" + safe(name) + " &7" + strokes + (strokes == 1 ? " stroke" : " strokes") + " ("
                + (vsPar == 0 ? "par" : (vsPar > 0 ? "+" : "") + vsPar) + ")";
    }

    /** A live row: "&amp;e1. &amp;fSam &amp;7lap 2/3 (+1.4)", or "... finished 1:02.4". */
    public static String liveRow(int place, String name, String where, Long gapMs) {
        return "&e" + place + ". &f" + safe(name) + " &7" + where + (gapMs == null ? "" : " (+" + tenths(gapMs) + ")");
    }

    /** Seconds with one decimal, never negative: "1.4". */
    public static String tenths(long ms) {
        return String.format(Locale.ROOT, "%.1f", Math.max(0, ms) / 1000.0);
    }

    /** A name as the board shows it (never blank). */
    static String safe(String name) {
        return name == null || name.isBlank() ? "a player" : name;
    }

    /** Rows, cut to {@value #MAX_ROWS}. */
    static List<String> cut(List<String> rows) {
        List<String> out = new ArrayList<>(rows);
        return out.size() > MAX_ROWS ? out.subList(0, MAX_ROWS) : out;
    }
}
