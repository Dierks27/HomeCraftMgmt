package com.dierks.homecraft.games.chance.sportsbook;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.60.0, a real game's live score as Kalshi has it (pure: text in, records and words out, never the network; tested on
 * real replies kept under {@code src/test/resources/kalshi/}). The owner: "can you make a ticker someplace around the
 * bar with a ticker that shows the scores of the games".
 *
 * <p><b>What is asked</b>: {@code GET /live_data/{milestone type}/milestone/{milestone id}} for one game (the batch
 * form answers {@code null}, so it isn't used). The milestone's id and type come with the board's own {@code /events}
 * reading ({@link Kalshi#events}).
 *
 * <p><b>Fields read</b> from {@code live_data.details}: {@code away_points}, {@code home_points}, {@code period} (a
 * football game's is {@code quarter}), {@code period_type} ({@code quarter}, {@code half}; none for hockey),
 * {@code period_remaining_time} (a football game's is {@code clock}), {@code status} ({@code scheduled},
 * {@code inprogress}/{@code in_progress}/{@code live}, {@code closed}, ...) and {@code widget_status}
 * ({@code finished} once it is over). Who won is read from the points ({@code winner} is a team id for some games).
 */
public final class Scores {

    private Scores() {
    }

    /** Where a game is. */
    public enum Phase {
        /** Not started (or nothing known yet). */
        UPCOMING,
        /** Being played. */
        LIVE,
        /** Over: its score is final. */
        FINAL
    }

    private static final Set<String> LIVE_STATES = Set.of("inprogress", "in_progress", "live", "halftime",
            "intermission", "delayed", "suspended");
    private static final Set<String> DONE_STATES = Set.of("closed", "final", "finished", "complete", "completed",
            "ended", "post", "postgame", "full_time");

    /**
     * One reading of a game's score.
     *
     * @param away       the visitors' points
     * @param home       the home side's points
     * @param period     the quarter, half, period or inning (0 before the start)
     * @param periodType {@code quarter}, {@code half}, {@code period}, {@code inning} or "" (unknown)
     * @param clock      the time left in it as Kalshi has it ("05:55"), "" for none
     * @param status     Kalshi's status of the game
     * @param widget     Kalshi's {@code widget_status} ({@code finished} once it is over)
     * @param kind       the milestone's type ({@code basketball_game}, ...)
     * @param top        an inning's half: {@code top}, {@code bottom} or "" (baseball only)
     */
    public record Live(int away, int home, int period, String periodType, String clock, String status, String widget,
                       String kind, String top) {

        public Live {
            periodType = low(periodType);
            clock = clock == null ? "" : clock.trim();
            status = low(status);
            widget = low(widget);
            kind = low(kind);
            top = low(top);
        }

        /** Where the game is. */
        public Phase phase() {
            if (widget.equals("finished") || DONE_STATES.contains(status)) {
                return Phase.FINAL;
            }
            if (widget.equals("live") || LIVE_STATES.contains(status)) {
                return Phase.LIVE;
            }
            if (status.equals("scheduled") || status.equals("pre") || status.isEmpty()) {
                return period > 0 || away > 0 || home > 0 ? Phase.LIVE : Phase.UPCOMING;
            }
            return period > 0 || away > 0 || home > 0 ? Phase.LIVE : Phase.UPCOMING;
        }

        /** Whether it went past its regular periods (overtime). */
        public boolean overtime() {
            int regular = regular();
            return regular > 0 && period > regular;
        }

        /** The regular periods of its sport: 4 quarters, 2 halves, 3 periods, 9 innings (0 for unknown). */
        int regular() {
            return switch (unit()) {
                case "quarter" -> 4;
                case "half" -> 2;
                case "period" -> 3;
                case "inning" -> 9;
                default -> 0;
            };
        }

        /** What its periods are: from {@code period_type}, else from its sport. */
        String unit() {
            if (!periodType.isEmpty()) {
                return periodType;
            }
            if (kind.startsWith("football")) {
                return "quarter";
            }
            if (kind.startsWith("hockey")) {
                return "period";
            }
            if (kind.startsWith("baseball")) {
                return "inning";
            }
            if (kind.startsWith("basketball")) {
                return "quarter";
            }
            return "";
        }

        /** Whether the clock reads no time left (or there is no clock). */
        boolean clockOut() {
            String c = clock.replace(":", "").replace(".", "").replace("0", "");
            return clock.isEmpty() || c.isEmpty();
        }

        /**
         * Where the game is, in a few words: "Q4 2:31", "2nd half 5:55", "P2 12:01", "OT 3:10", "Halftime", "End of
         * Q3", "Top 7", "Final", "Final/OT", or "" before it starts.
         */
        public String when() {
            Phase p = phase();
            if (p == Phase.FINAL) {
                return overtime() ? "Final/OT" : "Final";
            }
            if (p == Phase.UPCOMING) {
                return "";
            }
            String unit = unit();
            String name = periodName(unit, period, regular());
            if (unit.equals("inning")) {
                return name;
            }
            if (period <= 0) {
                return "Starting";
            }
            if (clockOut()) {
                boolean half = (unit.equals("quarter") && period == 2) || (unit.equals("half") && period == 1);
                return half ? "Halftime" : "End of " + name;
            }
            return name + " " + clockText(clock);
        }

        private String periodName(String unit, int n, int regular) {
            if (unit.equals("inning")) {
                String side = top.startsWith("t") ? "Top " : top.startsWith("b") ? "Bottom " : "Inning ";
                return side + Math.max(1, n);
            }
            if (regular > 0 && n > regular) {
                int ot = n - regular;
                return ot == 1 ? "OT" : ot + "OT";
            }
            return switch (unit) {
                case "quarter" -> "Q" + n;
                case "half" -> n == 1 ? "1st half" : "2nd half";
                case "period" -> "P" + n;
                default -> "Period " + n;
            };
        }
    }

    /** "05:55" as "5:55", "00:42" as "0:42", anything else as it is. */
    static String clockText(String clock) {
        Matcher m = Pattern.compile("^0*(\\d+):(\\d\\d)(?:\\.\\d+)?$").matcher(clock.trim());
        if (m.matches()) {
            return Integer.parseInt(m.group(1)) + ":" + m.group(2);
        }
        return clock.trim();
    }

    private static String low(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    // ---- what to ask ---------------------------------------------------------------------------------------

    /** The path for one game's live score. */
    public static String path(String type, String milestone) {
        return "/live_data/" + URLEncoder.encode(type, StandardCharsets.UTF_8) + "/milestone/"
                + URLEncoder.encode(milestone, StandardCharsets.UTF_8);
    }

    // ---- reading ------------------------------------------------------------------------------------------

    /**
     * One {@code /live_data} reply.
     *
     * @throws IllegalArgumentException when the reply is Kalshi's error, or holds no live data
     */
    public static Live parse(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json == null ? "" : json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the reply isn't JSON");
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("the reply isn't a JSON object");
        }
        JsonObject o = root.getAsJsonObject();
        if (o.has("error")) {
            JsonElement err = o.get("error");
            String code = err.isJsonObject() && err.getAsJsonObject().has("code")
                    ? err.getAsJsonObject().get("code").getAsString() : "error";
            throw new IllegalArgumentException("Kalshi said: " + code);
        }
        JsonElement ld = o.get("live_data");
        if (ld == null || !ld.isJsonObject()) {
            throw new IllegalArgumentException("no live data in the reply");
        }
        JsonObject live = ld.getAsJsonObject();
        JsonElement de = live.get("details");
        JsonObject d = de != null && de.isJsonObject() ? de.getAsJsonObject() : new JsonObject();
        int period = whole(d, "period");
        if (period == 0) {
            period = whole(d, "quarter");
        }
        if (period == 0) {
            period = whole(d, "inning");
        }
        String clock = text(d, "period_remaining_time");
        if (clock.isEmpty()) {
            clock = text(d, "clock");
        }
        String type = text(d, "period_type");
        if (type.isEmpty() && d.has("inning")) {
            type = "inning";
        }
        String top = text(d, "inning_half");
        if (top.isEmpty()) {
            top = text(d, "half");
        }
        return new Live(whole(d, "away_points"), whole(d, "home_points"), period, type, clock, text(d, "status"),
                text(d, "widget_status"), text(live, "type"), top);
    }

    private static int whole(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) {
            return 0;
        }
        try {
            return (int) Math.round(e.getAsDouble());
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static String text(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString().trim();
    }

    // ---- who is playing ------------------------------------------------------------------------------------

    private static final Pattern VERSUS = Pattern.compile("^(.+?)\\s+(?:vs\\.?|at|@)\\s+(.+?)(?:\\s*\\([^)]*\\))?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * The two sides' short names, visitors first: from an event's {@code sub_title} ("MIA vs NYK (Nov 27)"), else
     * from its title ("Miami vs New York"; "Calgary at Pittsburgh"), else {"", ""}. Kalshi names the visitors first.
     */
    public static String[] teams(String subTitle, String title) {
        for (String s : new String[]{subTitle, title}) {
            if (s == null || s.isBlank()) {
                continue;
            }
            Matcher m = VERSUS.matcher(s.trim());
            if (m.matches()) {
                return new String[]{m.group(1).trim(), m.group(2).trim()};
            }
        }
        return new String[]{"", ""};
    }

    // ---- words -------------------------------------------------------------------------------------------

    /**
     * A score as one short line: "MIL 98  TOR 101 · Q4 2:31", "Final: CGY 6  PIT 3", "Final/OT: ...", or "MIL at
     * TOR" before the start.
     */
    public static String line(String away, String home, Live l) {
        if (l == null || l.phase() == Phase.UPCOMING) {
            return away + " at " + home;
        }
        String score = away + " " + l.away() + "  " + home + " " + l.home();
        if (l.phase() == Phase.FINAL) {
            return l.when() + ": " + score;
        }
        String when = l.when();
        return when.isEmpty() ? score : score + " · " + when;
    }

    /** A score for the ticker: "MIL 98-101 TOR Q4 2:31", "Final CGY 6-3 PIT". */
    public static String ticker(String away, String home, Live l) {
        if (l == null || l.phase() == Phase.UPCOMING) {
            return away + " at " + home;
        }
        String score = away + " " + l.away() + "-" + l.home() + " " + home;
        if (l.phase() == Phase.FINAL) {
            return l.when() + " " + score;
        }
        String when = l.when();
        return when.isEmpty() ? score : score + " " + when;
    }
}
