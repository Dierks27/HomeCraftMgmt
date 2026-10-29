package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.cup.CupEntry;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.games.golf.GolfNow;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TrialsNow;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.gui.games.clubhouse.MyStatsMenu;
import com.dierks.homecraft.storage.CupDao;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.TokenDao;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * The Clubhouse's walls (0.38.3; the owner: "For the Clubhouse we should display the top runs of the week and such
 * ... Maybe some stats or something? Have to make it a reason to be there after all"): three boards besides the
 * results board, floating text only, so the room's blocks and its built and checked state never change.
 *
 * <ul>
 *   <li><b>This week's best</b> on the east wall ({@link FameBoard}): each course's fastest run this week and who,
 *       the best golf round, today's pick and its bonus, the Weekly Cup leaders, then the all-time records; a page
 *       every {@value FameBoard#PAGE_MS} ms.</li>
 *   <li><b>Now playing</b> on the south wall ({@link NowBoard}): who is in which game and how far, from the games'
 *       live state, read-only, once a second.</li>
 *   <li><b>My stats</b> on the west wall ({@link StatsCard}): each visitor sees their own board and nobody else's
 *       ({@link ViewerBoards}), and the kit's slot 7 opens the same numbers as a screen ({@link MyStatsMenu}).</li>
 *   <li><b>The trophy case</b> (0.38.4, {@link TrophyWall}): one item frame and line per all-time record, from
 *       the same cache, under My stats. The Fame board also pages the family week ({@link FamilyWeek}).</li>
 * </ul>
 *
 * <p>The database is read off the main thread and cached ({@link ClubReads}: every {@value ClubReads#EVERY_MS} ms
 * while anyone is in, and soon after an arrival); nothing draws per tick, and a board's text is sent only when it
 * changed. {@code games.clubhouse.boards: false} takes all three down and reads nothing (the results board stays).
 *
 * <p>Decoration: anything here that throws is one WARN a minute and the walls come down until the next second;
 * it never switches the Clubhouse off.
 */
final class ClubBoards {

    /** The kit's My stats action. */
    static final String STATS = "stats";

    private final Clubhouse club;
    private final BoardDisplay fameBoard;
    private final BoardDisplay nowBoard;
    private final ViewerBoards stats;
    private final TrophyWall trophies;
    private final ClubReads reads;
    private final ClubData.Db db;
    private final Map<UUID, String> names = new HashMap<>();
    private long warnedAt = Long.MIN_VALUE / 2;

    ClubBoards(Clubhouse club) {
        this.club = club;
        HomeCraftManagement plugin = club.plugin();
        this.fameBoard = new BoardDisplay(club, TextDisplay.TextAlignment.LEFT);
        this.nowBoard = new BoardDisplay(club, TextDisplay.TextAlignment.LEFT);
        this.stats = new ViewerBoards(club, plugin);
        this.trophies = new TrophyWall(club);
        GamesDao games = club.games().dao();
        this.db = new LiveDb(games, games.cup(), new TokenDao(plugin.database()));
        this.reads = new ClubReads(new LiveSource(), async(plugin), main(plugin), line -> club.log().warning(line));
    }

    /** The scheduler's async pool: where the database is read. */
    private static Executor async(HomeCraftManagement plugin) {
        return r -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, r);
    }

    /** The next tick, while the plugin is on: where what was read is applied. */
    private static Executor main(HomeCraftManagement plugin) {
        return r -> {
            try {
                if (plugin.isEnabled()) {
                    plugin.getServer().getScheduler().runTask(plugin, r);
                }
            } catch (RuntimeException ignored) {
                // stopping: the read is dropped, and the next start reads again
            }
        };
    }

    /** Kit slot 7: My stats (its key fact in the NAME, for Bedrock). */
    static ItemStack kitItem(Game g) {
        return KitItems.item(g, STATS, Material.PAPER, ClubBoardText.KIT_STATS, "&7Your best times, your runs",
                "&7and tokens this week.", "&7Only you see them.");
    }

    // ---- once a second ----------------------------------------------------------------------------

    /** Once a second while the room is open: read when due, and draw the walls while anyone is in. */
    void second(long now) {
        try {
            ClubhouseRoom room = club.room();
            if (!club.settings().boards() || room == null || !room.open()) {
                clear(); // switched off: nothing up, nothing read
                return;
            }
            List<UUID> inside = new ArrayList<>();
            for (ClubVisits.Visit v : club.visits().all()) {
                inside.add(v.id());
            }
            reads.second(now, inside);
            if (inside.isEmpty()) {
                return; // nobody to see them: left as they are
            }
            String world = room.world();
            draw(fameBoard, world, room.wall(WallBoards.FAME), FameBoard.text(reads.fame(), now));
            draw(nowBoard, world, room.wall(WallBoards.PLAYING), NowBoard.text(playing()));
            WallBoards.Wall mine = room.wall(WallBoards.STATS);
            for (UUID id : inside) {
                Player p = online(id);
                if (p == null || mine == null) {
                    stats.remove(id);
                } else {
                    stats.show(p, world, mine, WallBoards.pad(StatsCard.board(p.getName(), reads.mine(id))));
                }
            }
            trophies.redraw(room, reads.fame()); // 0.38.4
        } catch (RuntimeException | LinkageError e) {
            warn(now, "Clubhouse boards: " + e);
            clear();
        }
    }

    private static void draw(BoardDisplay d, String world, WallBoards.Wall at, String text) {
        if (at == null) {
            d.remove(); // an owner-built room without this board
        } else {
            d.show(world, at, WallBoards.pad(text));
        }
    }

    /** Now playing's rows: runs, rounds, other games' sessions, cabinets on screen, and the Clubhouse. */
    private List<NowBoard.Playing> playing() {
        GamesService g = club.games();
        List<NowBoard.Other> others = new ArrayList<>();
        for (Session s : g.sessions().sessions()) {
            if (s.phase() != Session.Phase.ACTIVE) {
                continue;
            }
            if (club.id().equals(s.gameId())) {
                boolean watching = club.watchLive() != null && club.watchLive().watching(s.player());
                others.add(new NowBoard.Other(s.player(), watching ? ClubBoardText.WATCHING
                        : ClubBoardText.IN_CLUBHOUSE, NowBoard.CLUBHOUSE));
                continue;
            }
            Game game = g.game(s.gameId());
            if (game != null && !TimeTrials.SPEC.id().equals(game.id()) && !MiniGolf.SPEC.id().equals(game.id())) {
                others.add(new NowBoard.Other(s.player(), game.name(), NowBoard.GAME)); // Falling Floors, ...
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            Game on = GameMenu.gameOnScreen(p);
            if (on != null && on.kind() == GameKind.CABINET) {
                others.add(new NowBoard.Other(p.getUniqueId(), on.name(), NowBoard.CABINET));
            }
        }
        return NowBoard.rows(TrialsNow.runs(g), GolfNow.rounds(g), others, this::name);
    }

    // ---- the kit, arrivals, departures -------------------------------------------------------------

    /** Someone arrived (or came back after a finish): their numbers, and a fresh read soon. */
    void arrived(Player p) {
        reads.want(p.getUniqueId());
    }

    /** Someone left the Clubhouse (any way at all): their board comes down, their numbers go. */
    void gone(UUID id) {
        stats.remove(id);
        reads.forget(id);
    }

    /** The kit's My stats: the screen with what was last read, and a fresh read soon. */
    void openStats(Player p) {
        StatsCard.Mine m = reads.mine(p.getUniqueId());
        reads.want(p.getUniqueId());
        new MyStatsMenu(club.plugin(), club, p, m).open(p);
    }

    /** Every wall board comes down and the cache is emptied (the room closed or moved, or the boards are off). */
    void clear() {
        fameBoard.remove();
        nowBoard.remove();
        stats.clear();
        trophies.remove();
        reads.clear();
    }

    /** This week's best as last read (the family week's ceremony reads last week's top three from it). */
    FameBoard.Fame fame() {
        return reads.fame();
    }

    /** A read soon (points were just added). */
    void soon() {
        reads.soon();
    }

    /** How many trophies stand (for the admin's status). */
    int trophies() {
        return trophies.count();
    }

    /** The Clubhouse stopped. */
    void stop() {
        clear();
        reads.stop();
        names.clear();
    }

    // ---- reading --------------------------------------------------------------------------------

    /** A player's name for the boards: online, else the server's last known one, else "someone" (cached). */
    private String name(UUID id) {
        Player p = online(id);
        if (p != null) {
            names.put(id, p.getName());
            return p.getName();
        }
        return names.computeIfAbsent(id, k -> {
            try {
                OfflinePlayer o = Bukkit.getOfflinePlayer(k);
                return o.getName() == null ? "someone" : o.getName();
            } catch (RuntimeException e) {
                return "someone";
            }
        });
    }

    /** The live server's side of a read: the snapshot (main), the database (async), the names (main). */
    private final class LiveSource implements ClubReads.Source {

        @Override
        public ClubData.Snapshot snapshot(Set<UUID> viewers) {
            GamesService g = club.games();
            // the weekly boards', the Weekly Cup's and the quests' week (quests.week_starts_on), whether or not
            // Time Trials is on (R383-B1: golf's week and the week's tokens read the same boundary)
            long weekKey = g.clock().weekKey(DailyLookup.weekStart(g.plugin()));
            long weekStart = g.clock().startOfDay(weekKey);
            Game cupGame = g.game(WeeklyCup.SPEC.id());
            long cupWeek = cupGame instanceof WeeklyCup w && g.enabled(w) ? w.desk().week() : -1;
            String pick = null;
            String id = g.featured().today();
            GamesService.Target t = id == null ? null : g.resolve(id);
            if (t != null && t.game().kind() != GameKind.CHANCE && g.enabled(t.game())) {
                pick = t.playable() != null ? t.playable().name() : t.game().name();
            }
            return new ClubData.Snapshot(TrialsNow.boards(g), GolfNow.boards(g), weekStart, cupWeek, pick,
                    g.config().common().featuredBonus(), viewers, weekKey, weekKey - 7); // 0.38.4: the family week
        }

        @Override
        public ClubData.Raw read(ClubData.Snapshot snapshot) throws SQLException {
            return ClubData.read(snapshot, db);
        }

        @Override
        public ClubReads.Result result(ClubData.Snapshot snapshot, ClubData.Raw raw) {
            Map<UUID, StatsCard.Mine> mine = new HashMap<>();
            raw.personal().forEach((id, p) -> mine.put(id, StatsCard.build(snapshot, p)));
            return new ClubReads.Result(FameBoard.fame(snapshot, raw, ClubBoards.this::name), mine);
        }
    }

    /** The DAOs' read methods, nothing that writes: safe on the async pool (each locks the one connection). */
    private record LiveDb(GamesDao games, CupDao cups, TokenDao tokens) implements ClubData.Db {

        @Override
        public List<GamesDao.ScoreRow> top(String game, String board, int limit) throws SQLException {
            return games.top(game, board, true, limit);
        }

        @Override
        public List<CupKey> openCups() throws SQLException {
            return cups.openKeys();
        }

        @Override
        public List<CupEntry> cupEntries(CupKey key) throws SQLException {
            return cups.entries(key);
        }

        @Override
        public List<GamesDao.PlayerBoard> boardsOf(UUID player, String game) throws SQLException {
            return games.boardsOf(player, game);
        }

        @Override
        public int rank(UUID player, String game, String board) throws SQLException {
            return games.rank(player, game, board, true);
        }

        @Override
        public List<TokenDao.SourceTotal> totals(long since, UUID player) throws SQLException {
            return tokens.totals(since, player);
        }

        @Override
        public List<GamesDao.ScoreRow> topPoints(String game, String board, int limit) throws SQLException {
            return games.top(game, board, false, limit); // a points board: higher is better
        }
    }

    private void warn(long now, String line) {
        if (now - warnedAt >= ClubReads.WARN_EVERY_MS) {
            warnedAt = now;
            club.log().warning(line);
        }
    }

    private static Player online(UUID id) {
        try {
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
