package com.dierks.homecraft.display;

import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The games' leaderboards on the hub's displays (EXTRAS E3): a hologram, a TV panel or a sign
 * bound to the pseudo-target {@code @board:<id>[:<board>]}, the way {@code @news} is the Market
 * News board ({@link DisplayService#NEWS_ID}).
 *
 * <p>{@code id} is a game id (an arcade cabinet), a course id (a hand-built time trial or golf
 * course) or a Fresh Courses slot id. The board it shows by default: a cabinet's own published
 * board (Classic, or the one it puts on the website), a course's all-time board, a golf course's
 * all-time board, and a Fresh course's <b>current</b> set's board, which follows each new set; a
 * Classics slot shows the board of the course it holds. A cabinet may name another of its boards
 * ({@code @board:creeper_sweeper:hard}), and only one it has; nothing else has a second board to pick.
 *
 * <p>What it shows: a title ("Hard Parkour - this week"), the best five as "1. Sam 0:42.1" (a
 * sign: the title in whole words, "Hard Parkour", and the best three), and "/hcm play &lt;id&gt;"
 * under a hologram or TV. Names are
 * shown: these are players in game, and {@code web.dashboard.arcade_show_names} is the website's
 * rule, not the server's. An empty board says so: "No times yet - be the first!".
 *
 * <p>This class is pure (no Bukkit), so the parsing, the choice of board and every line are tested
 * without a server; {@link DisplayService} reads the rows and draws them.
 */
public final class BoardDisplay {

    /** The pseudo-target's prefix. */
    public static final String PREFIX = "@board:";
    /** Rows under a hologram's or a TV's title. */
    public static final int SCREEN_ROWS = 5;
    /** Rows on a sign, under its title. */
    public static final int SIGN_ROWS = 3;
    /** A sign line's most characters. */
    public static final int SIGN_CHARS = 15;
    /** An empty time board. */
    public static final String EMPTY = "No times yet - be the first!";
    /** An empty board of anything else. */
    public static final String EMPTY_SCORES = "No scores yet - be the first!";

    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern BOARD = Pattern.compile("[a-z0-9_]{1,32}");

    private BoardDisplay() {
    }

    /**
     * A parsed target.
     *
     * @param id    the game, course or slot id (lower case)
     * @param board the board a cabinet should show instead of its own, or {@code null}
     */
    public record Target(String id, String board) {

        /** What a display row stores: {@code @board:snake} or {@code @board:creeper_sweeper:hard}. */
        public String itemId() {
            return PREFIX + id + (board == null ? "" : ":" + board);
        }
    }

    /**
     * What a target resolved to.
     *
     * @param game    whose scores ({@code trials}, {@code golf}, a cabinet's id)
     * @param board   the board, or {@code null} while a Fresh course has no set up (nothing to show yet)
     * @param lower   lower is better
     * @param unit    {@code ms}, {@code strokes}, {@code points}, {@code apples}...
     * @param title   the heading
     * @param playId  what {@code /hcm play} takes to play it
     */
    public record Resolved(String game, String board, boolean lower, String unit, String title, String playId) {
    }

    /** A target resolved, or why it can't be shown ({@code error}, for the admin binding it). */
    public record Result(Resolved resolved, String error) {

        static Result ok(Resolved r) {
            return new Result(r, null);
        }

        static Result fail(String why) {
            return new Result(null, why);
        }

        public boolean ok() {
            return resolved != null;
        }
    }

    /**
     * A cabinet's board, as it publishes it (its name, board, unit and which way is better), and
     * the other boards it keeps all-time scores on ({@code boards}: Creeper Sweeper's levels), the
     * only ones a display may name instead.
     */
    public record Cabinet(String name, String board, String unit, boolean lower, List<String> boards) {

        public Cabinet {
            boards = boards == null ? List.of() : List.copyOf(boards);
        }

        /** A cabinet with one board. */
        public Cabinet(String name, String board, String unit, boolean lower) {
            this(name, board, unit, lower, List.of());
        }

        /** Every board a display may show: the published one first, then the others. */
        public List<String> all() {
            List<String> out = new ArrayList<>();
            out.add(board);
            for (String b : boards) {
                if (b != null && !out.contains(b)) {
                    out.add(b);
                }
            }
            return out;
        }
    }

    /** One row: its rank (ties share one), who, and the score. */
    public record Row(int rank, String name, long value) {
    }

    /** What a target needs from the games (the server's, or a test's). */
    public interface Lookup {

        /** A Fresh Courses slot's or a Classics slot's live layout, or {@code null}. */
        GenTag liveTag(String slotId);

        /** The live cadence in days (for a Fresh course that isn't up yet). */
        int cadence();

        /** An open skill cabinet's published board, or {@code null} when {@code id} isn't one. */
        Cabinet cabinet(String id);

        /** Whether {@code id} is a game of chance (they have no leaderboard). */
        boolean chance(String id);

        /** A hand-built time trial's name, or {@code null}. */
        String trialCourse(String id);

        /** A hand-built golf course's name, or {@code null}. */
        String golfCourse(String id);

        /**
         * Race Night's boards ({@code @board:race_night} the season, {@code :last} the last night;
         * EVENTS-DROPPER-SPEC §A.8), or {@code null} when {@code t} isn't Race Night's.
         */
        default Result event(Target t) {
            return null;
        }
    }

    // ---- parsing ----------------------------------------------------------------------------------

    /** Whether a display's item is a leaderboard ({@code @board:...}, any case). */
    public static boolean is(String itemId) {
        return itemId != null && itemId.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    /**
     * The target in {@code text} ({@code @board:<id>[:<board>]}, any case, trimmed), or {@code null}
     * when it isn't a well-formed one.
     */
    public static Target parse(String text) {
        if (!is(text == null ? null : text.trim())) {
            return null;
        }
        String rest = text.trim().substring(PREFIX.length()).toLowerCase(Locale.ROOT);
        int colon = rest.indexOf(':');
        String id = colon < 0 ? rest : rest.substring(0, colon);
        String board = colon < 0 ? null : rest.substring(colon + 1);
        if (!ID.matcher(id).matches() || (board != null && !BOARD.matcher(board).matches())) {
            return null;
        }
        return new Target(id, board);
    }

    /**
     * What {@code text} shows, or why it can't: a malformed target, a game of chance, an id nothing
     * has, or a second board asked of something with one.
     */
    public static Result resolve(String text, Lookup l) {
        Target t = parse(text);
        if (t == null) {
            return Result.fail("A leaderboard is @board:<id>, like @board:snake or @board:fresh_parkour_hard.");
        }
        return resolve(t, l);
    }

    /** {@link #resolve(String, Lookup)} for a parsed target. */
    public static Result resolve(Target t, Lookup l) {
        Slots.Def slot = Slots.of(t.id());
        Slots.Def classic = Slots.classic(t.id());
        if (slot != null || classic != null) {
            if (t.board() != null) {
                return Result.fail("A Fresh course always shows its current set's board: use @board:" + t.id() + ".");
            }
            GenTag tag = l.liveTag(t.id());
            Slots.Def d = slot != null ? slot : classic;
            String unit = d.golf() ? "strokes" : "ms";
            String game = d.golf() ? Slots.GAME_GOLF : Slots.GAME_TRIALS;
            if (classic != null) {
                Slots.Def original = tag == null ? null : Slots.of(tag.slot());
                String title = tag == null || original == null ? classic.name()
                        : GenCopy.classicName(GenCopy.slotName(original, tag.cadence()), tag.cadence(), tag.day(),
                        false);
                return Result.ok(new Resolved(game, tag == null ? null : GenBoards.day(tag), true, unit, title,
                        classic.id()));
            }
            int cadence = tag == null ? l.cadence() : tag.cadence();
            String title = GenCopy.boardTitle(GenCopy.slotName(slot, cadence), cadence);
            return Result.ok(new Resolved(game, tag == null ? null : GenBoards.day(tag), true, unit, title, slot.id()));
        }
        Result event = l.event(t); // Race Night's season and last night (EVENTS-DROPPER-SPEC §A.8)
        if (event != null) {
            return event;
        }
        if (l.chance(t.id())) {
            return Result.fail("Games of chance have no leaderboard.");
        }
        Cabinet c = l.cabinet(t.id());
        if (c != null) {
            if (t.board() != null && !c.all().contains(t.board())) {
                return Result.fail(c.all().size() < 2 ? c.name() + " has one board: use @board:" + t.id() + "."
                        : c.name() + " has no board called '" + t.board() + "': use @board:" + t.id() + ", or one of "
                        + String.join(", ", c.all()) + ".");
            }
            String board = t.board() == null ? c.board() : t.board();
            String title = t.board() == null || t.board().equals(c.board()) ? c.name()
                    : c.name() + " - " + Character.toUpperCase(board.charAt(0)) + board.substring(1).replace('_', ' ');
            return Result.ok(new Resolved(t.id(), board, c.lower(), c.unit(), title, t.id()));
        }
        String trial = l.trialCourse(t.id());
        String golf = trial == null ? l.golfCourse(t.id()) : null;
        if (trial != null || golf != null) {
            if (t.board() != null) {
                return Result.fail((trial != null ? trial : golf) + " has one board: use @board:" + t.id() + ".");
            }
            return trial != null
                    ? Result.ok(new Resolved(Slots.GAME_TRIALS, "course:" + t.id(), true, "ms", trial, t.id()))
                    : Result.ok(new Resolved(Slots.GAME_GOLF, "golf:" + t.id(), true, "strokes", golf, t.id()));
        }
        return Result.fail("No game or course is called '" + t.id() + "'.");
    }

    // ---- the lines --------------------------------------------------------------------------------

    /** A score as a display reads it: a time "0:42.1" (tenths, floored), "27 strokes", "30 points", "1 apple". */
    public static String value(String unit, long v) {
        String u = unit == null || unit.isBlank() ? "points" : unit.toLowerCase(Locale.ROOT);
        if (u.equals("ms")) {
            long t = Math.max(0, v);
            long seconds = (t / 1000) % 60;
            return t / 60_000 + ":" + (seconds < 10 ? "0" : "") + seconds + "." + (t / 100) % 10;
        }
        String word = v == 1 && u.endsWith("s") ? u.substring(0, u.length() - 1) : u;
        return v + " " + word;
    }

    /** A score as short as it goes, for a sign: "0:42.1", or the bare number. */
    static String shortValue(String unit, long v) {
        return "ms".equalsIgnoreCase(unit) ? value(unit, v) : Long.toString(v);
    }

    /** What an empty board says: {@link #EMPTY} for times, {@link #EMPTY_SCORES} for anything else. */
    public static String empty(String unit) {
        return "ms".equalsIgnoreCase(unit) ? EMPTY : EMPTY_SCORES;
    }

    /**
     * A hologram's or a TV's lines: the title, the best {@value #SCREEN_ROWS} ("&amp;e1. &amp;fSam
     * &amp;70:42.1"), or the empty line, and "/hcm play &lt;id&gt;".
     */
    public static List<String> screen(String title, List<Row> rows, String unit, String playId) {
        List<String> out = new ArrayList<>();
        out.add("&6&l" + title);
        if (rows == null || rows.isEmpty()) {
            out.add("&7" + empty(unit));
        } else {
            for (int i = 0; i < rows.size() && i < SCREEN_ROWS; i++) {
                Row r = rows.get(i);
                out.add((r.rank() == 1 ? "&6" : "&e") + r.rank() + ". &f" + name(r.name()) + " &7" + value(unit, r.value()));
            }
        }
        out.add("&8/hcm play " + playId);
        return out;
    }

    /**
     * A title that fits a sign's {@value #SIGN_CHARS} characters, never cut inside a word: the
     * whole title if it fits; else without its " - this week" or "(week of 5 Oct)" part ("Hard
     * Parkour"); else without a "Classic: " in front; else as many whole words as fit, never ending
     * on a dash, a colon or a little word ("Golf of the Week" is "Golf"). A single word too long for
     * the line is the only thing ever cut.
     */
    public static String signTitle(String title) {
        String t = plain(title).trim();
        if (t.length() <= SIGN_CHARS) {
            return t;
        }
        for (String cut : List.of(" - ", " (")) {
            int at = t.indexOf(cut);
            if (at > 0) {
                t = t.substring(0, at).trim();
            }
        }
        if (t.length() > SIGN_CHARS && t.indexOf(": ") > 0) {
            t = t.substring(t.indexOf(": ") + 2).trim();
        }
        if (t.length() <= SIGN_CHARS) {
            return t;
        }
        List<String> kept = new ArrayList<>();
        int length = 0;
        for (String word : t.split(" +")) {
            int next = length + (kept.isEmpty() ? 0 : 1) + word.length();
            if (next > SIGN_CHARS) {
                break;
            }
            kept.add(word);
            length = next;
        }
        while (!kept.isEmpty() && LOOSE.contains(kept.get(kept.size() - 1).toLowerCase(Locale.ROOT)
                .replaceAll("[-:]+$", ""))) {
            kept.remove(kept.size() - 1);
        }
        return kept.isEmpty() ? t.substring(0, SIGN_CHARS) : String.join(" ", kept).replaceAll("[-:,]+$", "");
    }

    /** Words a sign's title never ends on ("Golf of the", "Hard Parkour -"). */
    private static final List<String> LOOSE = List.of("", "of", "the", "a", "an", "and", "on", "in", "to", "at");

    /**
     * A sign's four lines: the title, then the best {@value #SIGN_ROWS} ("1 Sam 0:42.1"), each
     * at most {@value #SIGN_CHARS} characters (a long name is cut, never the score), or the empty
     * board in two lines.
     */
    public static List<String> sign(String title, List<Row> rows, String unit) {
        List<String> out = new ArrayList<>();
        out.add(signTitle(title));
        if (rows == null || rows.isEmpty()) {
            String[] words = empty(unit).split(" - ", 2);
            out.add(fit(words[0]));
            out.add(fit(words.length > 1 ? words[1] : ""));
            out.add("");
            return out;
        }
        for (int i = 0; i < SIGN_ROWS; i++) {
            if (i >= rows.size()) {
                out.add("");
                continue;
            }
            Row r = rows.get(i);
            String score = shortValue(unit, r.value());
            String head = r.rank() + " ";
            int room = Math.max(1, SIGN_CHARS - head.length() - 1 - score.length());
            String who = name(r.name());
            out.add(head + (who.length() > room ? who.substring(0, room) : who) + " " + score);
        }
        return out;
    }

    /** Each row's rank, best first, ties sharing one (1, 1, 3). */
    public static List<Row> ranked(List<String> names, List<Long> values) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            int rank = i > 0 && values.get(i).equals(values.get(i - 1)) ? out.get(i - 1).rank() : i + 1;
            out.add(new Row(rank, i < names.size() ? names.get(i) : null, values.get(i)));
        }
        return out;
    }

    private static String name(String n) {
        return n == null || n.isBlank() ? "a player" : n;
    }

    private static String fit(String s) {
        String plain = plain(s);
        return plain.length() <= SIGN_CHARS ? plain : plain.substring(0, SIGN_CHARS).trim();
    }

    private static String plain(String s) {
        return s == null ? "" : s.replaceAll("(?i)[&§][0-9a-fk-or]", "");
    }
}
