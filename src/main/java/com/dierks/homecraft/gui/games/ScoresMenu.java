package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Breaks;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenService;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.gui.games.daily.DailyText;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * One board's high scores (spec §6.2): your best at the top, then the top ten with their names.
 *
 * <pre>
 *  row 0    4 your best
 *  rows 2-3 20-24, 29-33 the top ten (a player head each on Java; a plain numbered tile on Bedrock)
 *  row 5    49 Back/Close
 * </pre>
 *
 * <p>Names are fine in game — only the website feed leaves them out. Every board is one table
 * ({@code game_scores}), so this one screen serves every game: a board is just a string
 * ({@code classic}, {@code easy}, {@code daily:<day>}, {@code course:<id>}, {@code golf:<id>}),
 * and how a score reads (a time, flips, apples...) comes from what the game publishes about its
 * boards, never from a guess about its internals. {@link Boards} is the list of every board,
 * reached from the Games screen.
 *
 * <p>Fresh Courses' boards read the same way (GEN-SPEC §5.2, the weekly addendum §3): a set's own
 * board is named by its course and its set, "Easy Parkour · this week" (the set up now), "Easy
 * Parkour · week of Mon 28 Sep", "Easy Parkour · Tue 29 Sep" (a daily one), "Easy Parkour · Mon 28
 * Sep-Wed 30 Sep" (every 3 days); a player's stars "Easy Parkour stars · this week"; a set
 * recalled into a Classics slot "Classic: Hard Parkour · week of 5 Oct"; and the weekly Star Chart
 * "Star Chart · this week", counted in stars, higher is better.
 */
public final class ScoresMenu extends GameMenu {

    private static final int BEST = 4;
    private static final int[] TOP = {20, 21, 22, 23, 24, 29, 30, 31, 32, 33};

    private final String board;
    private final boolean lowerIsBetter;

    public ScoresMenu(HomeCraftManagement plugin, Game game, Player viewer, String board, boolean lowerIsBetter,
                      Runnable back) {
        super(plugin, game, viewer, back);
        this.board = board;
        this.lowerIsBetter = lowerIsBetter;
        init(54, Text.of("&b" + game.name() + " &8· &3Scores"));
    }

    // ---- the words (pure, tested) ---------------------------------------------------------

    /** A time in milliseconds as "1:23.4" (tenths, floored). */
    static String time(long ms) {
        long t = Math.max(0, ms);
        long minutes = t / 60_000;
        long seconds = (t / 1000) % 60;
        long tenths = (t / 100) % 10;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds + "." + tenths;
    }

    /** A score in its unit: "1:23.4", "12 apples", "1 flip", "30 points". */
    static String score(String unit, long value) {
        String u = unit == null ? "points" : unit.toLowerCase(Locale.ROOT);
        if (u.equals("ms")) {
            return time(value);
        }
        String word = value == 1 && u.endsWith("s") ? u.substring(0, u.length() - 1) : u;
        return value + " " + word;
    }

    /**
     * The unit a board of a game of {@code kind} is kept in: a Fresh set's board is a time, or
     * strokes for golf; the Star Chart and a set's stars are stars; anything else as
     * {@link #unitFor(String, String)}.
     */
    static String unitFor(String board, String cabinetUnit, GameKind kind) {
        GenBoards.Board b = GenBoards.parse(board);
        if (b != null) {
            return b.kind() == GenBoards.Kind.DAY ? (kind == GameKind.GOLF ? "strokes" : "ms") : "stars";
        }
        return unitFor(board, cabinetUnit);
    }

    /** The unit a board is kept in: courses are times, golf is strokes, a cabinet's is its own. */
    static String unitFor(String board, String cabinetUnit) {
        if (board.startsWith("course:") || board.startsWith("week:")) {
            return "ms";
        }
        if (board.startsWith("golf:")) {
            return "strokes";
        }
        return cabinetUnit == null ? "points" : cabinetUnit;
    }

    /**
     * A board as a player reads it: "Classic", "Hard", "Today's challenge", "River Run",
     * "River Run this week".
     *
     * @param courseName a course id's name (the id itself when unknown)
     */
    static String boardLabel(String board, long today, Function<String, String> courseName) {
        if (board.startsWith("daily:")) {
            long day = parse(board.substring(6), -1);
            return day == today ? "Today's challenge" : "Challenge of " + (day < 0 ? "a past day" : Breaks.dateText(day));
        }
        if (board.startsWith("course:")) {
            return courseName.apply(board.substring(7));
        }
        if (board.startsWith("golf:")) {
            return courseName.apply(board.substring(5));
        }
        if (board.startsWith("week:")) {
            String rest = board.substring(5);
            int colon = rest.lastIndexOf(':');
            return courseName.apply(colon < 0 ? rest : rest.substring(0, colon)) + " this week";
        }
        if (board.isEmpty()) {
            return "Classic";
        }
        return Character.toUpperCase(board.charAt(0)) + board.substring(1).replace('_', ' ');
    }

    /**
     * {@link #boardLabel(String, long, Function)}, and Fresh Courses' boards: "Easy Parkour · this
     * week", "Easy Parkour · week of Mon 28 Sep", "Easy Parkour · Tue 29 Sep", "Easy Parkour · Mon
     * 28 Sep-Wed 30 Sep", "Easy Parkour stars · this week", "Classic: Hard Parkour · week of 5 Oct",
     * "Star Chart · this week". A set's real dates come from {@code ed} (the live schedule:
     * {@link Edition#startDayOf}); it reads "this week" or "today" while it is the set up now.
     *
     * @param ed        the live schedule
     * @param now       now (epoch ms): which set is up
     * @param thisWeek  the Star Chart week now
     * @param recalled  whether a board is the original board of a set recalled into a Classics slot now
     */
    static String boardLabel(String board, long today, Edition ed, long now, long thisWeek,
                             Function<String, String> courseName, Predicate<String> recalled) {
        GenBoards.Board b = GenBoards.parse(board);
        if (b == null) {
            return boardLabel(board, today, courseName);
        }
        return switch (b.kind()) {
            case WEEK -> DailyText.chartLabel(b.day(), thisWeek);
            case DAY -> {
                long start = startDay(b, ed);
                String name = dailyName(b.courseId(), courseName);
                if (recalled != null && recalled.test(board)) {
                    yield "Classic: " + name + " · " + GenCopy.editionDates(b.cadence(), start);
                }
                yield name + " · " + setLabel(b.cadence(), start, ed, now)
                        + (b.reroll() > 0 ? " (layout " + (b.reroll() + 1) + ")" : "");
            }
            case STARS -> dailyName(b.courseId(), courseName) + " stars · " + (b.edition().isEmpty()
                    ? DailyText.dayText(b.day(), ed.day(now)) : setLabel(b.cadence(), startDay(b, ed), ed, now));
        };
    }

    /** A set board's real first day under the live schedule (its key's earliest day when unknown). */
    private static long startDay(GenBoards.Board b, Edition ed) {
        Edition.Key k = Edition.Key.parse(b.edition());
        try {
            return k == null || ed == null ? b.day() : ed.startDayOf(k);
        } catch (RuntimeException e) {
            return b.day();
        }
    }

    /**
     * A set as a board label reads it: "this week" / "today" while it is the set up now, else
     * "week of Mon 28 Sep", "Tue 29 Sep", or "Mon 28 Sep-Wed 30 Sep".
     */
    static String setLabel(int cadence, long start, Edition ed, long now) {
        boolean current = ed != null && ed.cadenceDays() == cadence && ed.editionStart(now) == start;
        if (current && (cadence == Edition.DAILY || cadence == Edition.WEEKLY)) {
            return GenCopy.when(cadence);
        }
        if (cadence == Edition.WEEKLY) {
            return "week of " + DailyText.date(start);
        }
        return DailyText.setDates(cadence, start);
    }

    /** A Fresh course's name: the live course's, else its slot's, else the id. */
    private static String dailyName(String id, Function<String, String> courseName) {
        String name = courseName.apply(id);
        Slots.Def slot = Slots.any(id);
        return (name == null || name.equals(id)) && slot != null ? slot.name() : name == null ? id : name;
    }

    private static long parse(String s, long fallback) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A board's label with the live schedule and the Classics as they are now. */
    static String label(GamesService games, Game game, String board, long today) {
        if (games == null) {
            return boardLabel(board, today, id -> id);
        }
        Edition ed = DailyLookup.edition(games);
        long now = games.clock().nowMillis();
        return boardLabel(board, today, ed, now, ed.weekKey(ed.day(now)), id -> courseName(games, game, id),
                b -> recalledBoard(games, b));
    }

    /** Whether {@code board} is the original board of a set that a Classics slot holds now. */
    private static boolean recalledBoard(GamesService games, String board) {
        GenService e = DailyLookup.engine(games);
        if (e == null || board == null || !board.startsWith(GenBoards.DAY_PREFIX)) {
            return false;
        }
        for (String id : Slots.classicIds()) {
            GenTag t = e.liveTag(id);
            if (t != null && GenBoards.day(t).equals(board)) {
                return true;
            }
        }
        return false;
    }

    /** A course id's name among the game's playables, else the id. */
    static String courseName(GamesService games, Game game, String id) {
        Collection<Game.Playable> ps = games == null ? List.of() : games.guard(game, game::playables, List.of());
        for (Game.Playable p : ps == null ? List.<Game.Playable>of() : ps) {
            if (p.id().equalsIgnoreCase(id)) {
                return p.name();
            }
        }
        return id;
    }

    // ---- the screen ----------------------------------------------------------------------

    @Override
    protected void build() {
        fill();
        exitTile();
        GamesService games = plugin.games();
        if (games == null) {
            set(22, Menus.icon(Material.GRAY_DYE, "&7The games are closed right now"), null);
            return;
        }
        String unit = unitFor(board, Screens.published(games, game).cabinetUnit(), game.kind());
        String label = label(games, game, board, plugin.clock().dayKey());

        Long best = games.scores().best(viewer.getUniqueId(), game.id(), board);
        set(BEST, Menus.glint(Menus.icon(Material.GOLD_INGOT,
                best == null ? "&7No score of yours yet" : "&eYour best: &f" + score(unit, best),
                "&7" + game.name() + " &8· &7" + label,
                lowerIsBetter ? "&7Lower is better." : "&7Higher is better."), best != null), null);

        List<GamesDao.ScoreRow> top = games.scores().top(game.id(), board, lowerIsBetter, TOP.length);
        if (top.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7No scores yet", "&7Be the first on this board!"), null);
            return;
        }
        long now = plugin.clock().nowMillis();
        boolean bedrock = Bedrock.is(viewer);
        for (int i = 0; i < top.size() && i < TOP.length; i++) {
            GamesDao.ScoreRow row = top.get(i);
            set(TOP[i], rowTile(i + 1, row, unit, now, bedrock), null);
        }
    }

    private ItemStack rowTile(int rank, GamesDao.ScoreRow row, String unit, long now, boolean bedrock) {
        OfflinePlayer who = Bukkit.getOfflinePlayer(row.player());
        String name = who.getName() == null ? "a player" : who.getName();
        boolean you = row.player().equals(viewer.getUniqueId());
        String title = (rank == 1 ? "&6" : "&f") + "#" + rank + " " + (you ? "&a" : "&f") + name
                + (you ? " &7(you)" : "") + " &7- &f" + score(unit, row.score());
        String when = "&7Set " + Menus.duration(Math.max(0, now - row.at())) + " ago";
        ItemStack tile = bedrock ? Menus.icon(Material.PAPER, title, when) : head(who, title, when);
        return Menus.count(tile, rank);
    }

    /** A player's head, named: the profile goes on first, then the name on a fresh meta (Heads' two-pass rule). */
    private static ItemStack head(OfflinePlayer who, String name, String... lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(who);
            head.setItemMeta(skull);
        }
        ItemMeta meta = head.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.of(name));
            List<Component> lines = new ArrayList<>();
            for (String l : lore) {
                lines.add(Text.of(l));
            }
            meta.lore(lines);
            head.setItemMeta(meta);
        }
        return head;
    }

    /** Heads for the picker too. */
    static ItemStack playerHead(OfflinePlayer who, String name, String... lore) {
        return head(who, name, lore);
    }

    /**
     * Every high-score board, one tile each, from what the open skill games publish (and a
     * cabinet's other boards, such as Creeper Sweeper's easy and hard). Daily and weekly boards
     * are left to each game's own screen.
     */
    public static final class Boards extends GameMenu {

        /** One board to list. */
        record Entry(Game game, String board, String unit, boolean lowerIsBetter) {
        }

        private final int page;

        public Boards(HomeCraftManagement plugin, Player viewer, int page, Runnable back) {
            super(plugin, null, viewer, back);
            this.page = Math.max(0, page);
            init(54, Text.of("&bHigh scores"));
        }

        @Override
        protected void build() {
            fill();
            exitTile();
            set(4, Menus.icon(Material.OAK_SIGN, "&bHigh scores", "&7Pick a board to see the top 10."), null);
            GamesService games = plugin.games();
            List<Entry> entries = games == null || !games.config().enabled() ? List.of() : entries(games);
            int p = GamesMenu.clampPage(page, entries.size());
            List<Entry> onPage = GamesMenu.slice(entries, p);
            if (onPage.isEmpty()) {
                set(22, Menus.icon(Material.PAPER, "&7No boards yet", "&7Play a game to start one!"), null);
            }
            long today = plugin.clock().dayKey();
            for (int i = 0; i < onPage.size(); i++) {
                Entry en = onPage.get(i);
                set(9 + i, tile(games, en, today), e -> new ScoresMenu(plugin, en.game(), viewer,
                        en.board(), en.lowerIsBetter(), this::reopen).open(viewer));
            }
            if (p > 0) {
                set(45, Menus.icon(Material.ARROW, "&fPrevious page"),
                        e -> new Boards(plugin, viewer, p - 1, back).open(viewer));
            }
            if (p < GamesMenu.pages(entries.size()) - 1) {
                set(53, Menus.icon(Material.ARROW, "&fNext page"),
                        e -> new Boards(plugin, viewer, p + 1, back).open(viewer));
            }
        }

        private List<Entry> entries(GamesService games) {
            List<Entry> out = new ArrayList<>();
            for (Game g : games.games()) {
                if (g.kind() == GameKind.CHANCE || !games.enabled(g)) {
                    continue;
                }
                Screens.Published pub = Screens.published(games, g);
                Screens.Published.Board first = null;
                List<String> seen = new ArrayList<>();
                for (Screens.Published.Board b : pub.boards()) {
                    if (seen.contains(b.board())) {
                        continue;
                    }
                    seen.add(b.board());
                    out.add(new Entry(g, b.board(), b.unit(), b.lowerIsBetter()));
                    if (first == null && g.kind() == GameKind.CABINET) {
                        first = b;
                    }
                }
                if (first != null) {
                    for (String other : others(games, g)) {
                        if (!seen.contains(other)) {
                            seen.add(other);
                            out.add(new Entry(g, other, first.unit(), first.lowerIsBetter()));
                        }
                    }
                }
            }
            return out;
        }

        /** A cabinet's other all-time boards (not daily or weekly ones). */
        private List<String> others(GamesService games, Game g) {
            List<String> out = new ArrayList<>();
            try {
                for (String b : games.dao().boards(g.id())) {
                    if (b != null && !b.isBlank() && !b.contains(":")) {
                        out.add(b);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not list the " + g.id() + " boards", e);
            }
            return out;
        }

        private ItemStack tile(GamesService games, Entry en, long today) {
            String label = label(games, en.game(), en.board(), today);
            boolean course = en.board().contains(":");
            String name = course ? "&e" + label + " &7(" + en.game().name() + ")"
                    : "&b" + en.game().name() + " &7- " + label;
            GamesDao.ScoreRow record = games.scores().record(en.game().id(), en.board(), en.lowerIsBetter());
            List<String> lore = new ArrayList<>();
            if (record == null) {
                lore.add("&7No scores yet.");
            } else {
                String who = Bukkit.getOfflinePlayer(record.player()).getName();
                lore.add("&7Best: &f" + score(unitFor(en.board(), en.unit(), en.game().kind()), record.score())
                        + (who == null ? "" : " &7by &f" + who));
            }
            lore.add("&eClick to see the top 10");
            Material m = en.board().startsWith(GenBoards.WEEK_PREFIX) ? Material.NETHER_STAR
                    : switch (en.game().kind()) {
                        case TRIAL -> Material.FEATHER;
                        case GOLF -> Material.SNOWBALL;
                        default -> Material.JUKEBOX;
                    };
            return Menus.icon(m, name, lore.toArray(new String[0]));
        }

        private void reopen() {
            new Boards(plugin, viewer, page, back).open(viewer);
        }
    }
}
