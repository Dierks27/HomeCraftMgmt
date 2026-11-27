package com.dierks.homecraft.games.chance.sportsbook;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.storage.SportsPicksDao;
import com.dierks.homecraft.storage.SportsPicksDao.Pick;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Sports Board in the Clubhouse's Game Parlor (0.55.0; the owner: "can we make a betting area in the parlor for
 * sports betting? I would love to be able to bet on all the games that are happening that night ... Kalshi is open
 * api that we could get the games, scores and lines from"). Players pick who wins tonight's real games, for money.
 *
 * <p><b>Lines.</b> Every {@code refresh_minutes} the board reads, off the main thread ({@link KalshiClient}), the
 * game-winner markets of its leagues that start within {@code hours_ahead}, and keeps the last good reading in memory
 * and in {@code hcm_meta} ({@link #KEY_BOARD}), so a restart shows the games again at once. A side's chance is its
 * market's price ({@link Lines}); sides under 5¢ or over 95¢ are left out. When Kalshi can't be read, or the last
 * reading is old, picks close and the board says "Lines are updating"; open picks are never touched for it, and the
 * console gets one WARN for the whole outage.
 *
 * <p><b>Picks.</b> Only in the Parlor, only with {@link Parlor#PERMISSION_MONEY} and money play on, inside the day's
 * money limit and the biggest stake, built from the Parlor's chips, and only until the game starts. The stake is taken
 * through the play gate and the bank, then the pick is written ({@code sports_picks}) with its price and payout
 * locked: fixed odds, whatever the market does after. A pick's payout is never more than the Parlor's biggest payout.
 *
 * <p><b>Settling.</b> Every refresh also reads the markets of the open picks: a decided market pays its winners
 * their locked payout (through the Parlor, which keeps what the bank refuses as owed and pays it when it can), a
 * called-off one gives every stake back, and a pick still undecided {@code settle_days} after its game is given back
 * with a console line. Each pick is moved out of OPEN before it is paid, so it is paid once. Its player is told in
 * chat now, or at their next join. Settling goes on while the board is switched off ({@code /hcm games sportsbook
 * off}), and while the game itself is closed it still runs from the games' one-minute sweep.
 *
 * <p><b>Kids.</b> It is behind {@code hcm.games.money}, the games of chance and the Parlor: it has no tile anywhere a
 * child could see it, publishes no odds line, and no NPC player ever picks (only a real player's click does).
 */
public final class Sportsbook implements Game {

    public static final GameSpec<SportsbookSettings> SPEC = new GameSpec<>(SportsbookSettings.ID, GameKind.CHANCE,
            SportsbookSettings.KEYS, SportsbookSettings.defaults(), SportsbookSettings::parse, Sportsbook::new, null);

    /** The player-facing name. */
    public static final String NAME = "Sports Board";
    /** The last good board, kept for a restart. */
    static final String KEY_BOARD = "gen.sportsbook.board";
    /** An admin switched picks off ({@code 1}). */
    static final String KEY_OFF = "gen.sportsbook.off";
    /** 0.60.0: the games followed for their live scores, and their last scores, kept for a restart. */
    static final String KEY_SCORES = "gen.sportsbook.scores";
    /** How many settled picks My picks shows. */
    public static final int SETTLED_SHOWN = 14;
    /** The stake the wall board prices at. */
    public static final int WALL_STAKE = 10;

    public static final Refusal UPDATING = Refusal.of("Lines are updating. Picks open again in a moment.");
    public static final Refusal OFF = Refusal.of("The Sports Board is closed right now.");
    public static final Refusal STARTED = Refusal.of("That game has started, so picks on it are closed.");
    public static final Refusal MOVED = Refusal.of("That price just changed. Look at the new one, then pick again.");
    public static final Refusal NOT_OFFERED = Refusal.of("That side isn't offered right now.");

    /** Work off the main thread, and back on it (the live server's scheduler; a test runs both at once). */
    public interface Runner {
        void async(Runnable work);

        void main(Runnable work);
    }

    /**
     * A price as a screen shows it, to be locked if the player confirms.
     *
     * @param ticker the market
     * @param side   {@code yes} or {@code no}
     * @param price  its chance in mills
     * @param stake  whole dollars
     * @param payout the cents a win gives back
     */
    public record Quote(String ticker, String side, int price, int stake, long payout) {
    }

    /** Why a side can or can't be picked right now, for its tile. */
    public enum State {
        OPEN, OFF, UPDATING, STARTED, NOT_OFFERED
    }

    private final GameContext ctx;
    private final Admin admin = new Admin();

    private volatile Board board = Board.EMPTY;
    private boolean loaded;
    /** When the last good reading of the lines came in, this run (0: none yet). */
    private long lastOk;
    /** When a refresh last started. */
    private long lastTry;
    /** What the last refresh couldn't read, or {@code null}. */
    private String lastError;
    /** The lines couldn't be read: picks are closed until they are. */
    private boolean outage;
    /** The outage's one WARN went out. */
    private boolean warned;
    private boolean busy;
    private int generation;
    private boolean running;
    private Boolean off;

    private KalshiClient.Fetcher fetcher;
    private Runner runner;
    private KalshiClient.Sleeper sleeper = Thread::sleep;

    /** 0.60.0: the games under way and just over, with their live scores ({@link LiveScores}). */
    private LiveScores scores = new LiveScores();
    /** A round of live scores is in flight. */
    private boolean scoresBusy;
    /** When the last round of live scores started (0: none this run). */
    private long scoresTry;
    /** What the last round couldn't read, or {@code null}. */
    private String scoresError;
    /** The live scores' outage had its one console line. */
    private boolean scoresWarned;
    /** Whether anyone is on the server to see the scores (a test sets its own). */
    private java.util.function.BooleanSupplier watched;

    public Sportsbook(GameContext ctx) {
        this.ctx = ctx;
    }

    /**
     * For tests: read Kalshi through {@code fetcher} (the saved replies) and run "off the main thread" with
     * {@code runner}; nothing waits between requests.
     */
    public void connect(KalshiClient.Fetcher fetcher, Runner runner) {
        this.fetcher = fetcher;
        this.runner = runner;
        this.sleeper = ms -> { };
    }

    /** For tests: whether anyone is on the server to see the live scores. */
    public void watched(java.util.function.BooleanSupplier anyone) {
        this.watched = anyone;
    }

    public GamesService games() {
        return ctx.games();
    }

    public SportsbookSettings settings() {
        return games().settings(SPEC);
    }

    // ---- the Game -----------------------------------------------------------------------------------------

    @Override
    public String id() {
        return SPEC.id();
    }

    @Override
    public GameKind kind() {
        return SPEC.kind();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.ARCADE_SPORTSBOOK;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled();
    }

    @Override
    public List<String> rules() {
        return List.of("Pick who wins a real game tonight.",
                "Prices come from Kalshi's markets and are locked when you pick.",
                "Results come when the real game is decided.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        List<String> lore = new ArrayList<>();
        for (String line : rules()) {
            lore.add("&7" + line);
        }
        lore.add("&eClick to see tonight's games");
        return Menus.icon(Material.CLOCK, "&aSports Board &7- tonight's games", lore.toArray(new String[0]));
    }

    /** Its tile is shown only to someone who may make picks now: money play on, the permission, in the Parlor. */
    @Override
    public List<GameTile> tiles(Player viewer) {
        if (!tileShown(viewer)) {
            return List.of();
        }
        return List.of(new GameTile(Tab.LUCK, tile(viewer), id(), 0));
    }

    /** Whether {@code viewer} sees its tile: they may make picks now (money play, the permission, in the Parlor). */
    public boolean tileShown(Player viewer) {
        return viewer != null && games().parlor().offered(viewer);
    }

    /** No odds line: the board is played for money only, and {@code /hcm arcade odds} is read by players of every age. */
    @Override
    public List<String> oddsLines() {
        return List.of();
    }

    @Override
    public void open(Player player, Runnable back) {
        Refusal why = games().parlor().allowed(player);
        if (why != null) {
            games().tell(player, why);
            return;
        }
        if (ctx.plugin() == null) {
            return; // the framework's own tests: no screens
        }
        new com.dierks.homecraft.gui.games.sportsbook.SportsBoardMenu(ctx.plugin(), this, player, back, null, 0)
                .open(player);
    }

    @Override
    public void start() {
        load();
        running = true;
        if (ctx.plugin() != null) {
            games().every(this, 100, 1200, this::tick); // once a minute: a refresh when one is due
            games().every(this, 200, 200, this::scoreTick); // 0.60.0: every 10 s, a round of live scores when due
        }
    }

    @Override
    public void stop() {
        running = false;
        generation++; // a reading in flight is dropped
        busy = false;
        scoresBusy = false;
    }

    /**
     * The games' one-minute sweep, whether this game is open or not: while it is closed (its own task is stopped),
     * the open picks are still checked once every {@code refresh_minutes}, so a pick is paid even after a switch-off.
     */
    @Override
    public void settleOwed(Player player, boolean joined) {
        if (!running) {
            tick();
        }
    }

    @Override
    public GameAdmin admin() {
        return admin;
    }

    @Override
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add(statusLine());
        return out;
    }

    // ---- the lines -----------------------------------------------------------------------------------------

    /** The board as last read (the kept one after a restart, until the first reading). */
    public Board board() {
        load();
        return board;
    }

    /** Whether picks are switched off by an admin. */
    public boolean off() {
        if (off == null) {
            String v = meta(KEY_OFF);
            off = "1".equals(v);
        }
        return off;
    }

    /** Switch picks off or on (settling goes on either way). */
    public void off(boolean on) {
        off = on;
        metaSet(KEY_OFF, on ? "1" : "0");
    }

    /**
     * Whether the lines may be picked from at {@code now}: a reading came in this run, it is no older than two
     * refreshes and a minute, and the last refresh could read Kalshi.
     */
    public boolean linesOpen(long now) {
        return lastOk > 0 && !outage && now - lastOk <= 2 * settings().refreshMs() + 60_000L;
    }

    /** What a side's tile says it is now. */
    public State state(Board.Game g, Board.Side s, long now) {
        if (off()) {
            return State.OFF;
        }
        if (!g.open(now)) {
            return State.STARTED;
        }
        if (!linesOpen(now)) {
            return State.UPDATING;
        }
        return Lines.offered(s.price(), settings().rtpTenths()) ? State.OPEN : State.NOT_OFFERED;
    }

    /** The biggest stake a side priced {@code mills} takes under the Parlor's biggest payout (whole dollars). */
    public int mostFor(int mills) {
        Parlor.Rules r = games().parlor().rules();
        return Math.min(r.maxStake(), Lines.maxStake(mills, settings().rtpTenths(), r.maxPayout()));
    }

    /** What {@code stake} dollars on {@code side} would lock in now, or {@code null} when that stake isn't offered. */
    public Quote quote(Board.Side side, int stake) {
        int rtp = settings().rtpTenths();
        if (stake <= 0 || !Lines.offered(side.price(), rtp) || stake > mostFor(side.price())) {
            return null;
        }
        long payout = Lines.payoutCents(stake, side.price(), rtp);
        return payout > stake * 100L ? new Quote(side.ticker(), side.side(), side.price(), stake, payout) : null;
    }

    /** One minute passed: refresh when one is due. */
    void tick() {
        long now = games().clock().nowMillis();
        if (!busy && !scoresBusy && now - lastTry >= settings().refreshMs()) {
            refresh(); // one reader at a time: a round of scores in flight puts it off to the next minute
        }
    }

    /**
     * Read Kalshi now (off the main thread): the lines while the board is running and switched on, and always the
     * open picks' markets, then settle what was decided. Nothing happens while a reading is in flight.
     *
     * @return false when one was already in flight (or there was nothing to read)
     */
    public boolean refresh() {
        if (busy) {
            return false;
        }
        load();
        SportsbookSettings s = settings();
        long now = games().clock().nowMillis();
        lastTry = now;
        List<String> tickers;
        try {
            tickers = games().dao().sports().openTickers();
        } catch (SQLException e) {
            log().log(Level.SEVERE, "[Sports Board] Could not read the open picks - they are checked next time", e);
            tickers = List.of();
        }
        boolean lines = running && !off();
        if (!lines && tickers.isEmpty()) {
            lapse(now);
            return false;
        }
        KalshiClient.Fetcher f;
        try {
            f = fetcher != null ? fetcher : KalshiClient.http(s.apiBase());
        } catch (IllegalArgumentException e) {
            failed(e.getMessage(), lines);
            return false;
        }
        Runner run = runner != null ? runner : bukkit(ctx.plugin());
        if (run == null) {
            return false;
        }
        busy = true;
        int gen = generation;
        long until = now + s.hoursAhead() * 3_600_000L;
        long since = now - LiveScores.KEEP_MS;
        List<SportsbookSettings.League> leagues = s.boardLeagues();
        List<String> asked = List.copyOf(tickers);
        KalshiClient.Sleeper sleep = sleeper;
        run.async(() -> {
            KalshiClient.Result r;
            try {
                r = KalshiClient.fetch(f, sleep, leagues, asked, now, until, lines, since);
            } catch (RuntimeException e) {
                r = new KalshiClient.Result(null, Map.of(), KalshiClient.describe(e));
            }
            KalshiClient.Result result = r;
            run.main(() -> {
                busy = false;
                if (gen != generation && running) {
                    return; // stopped and started again meanwhile: the next tick reads afresh
                }
                games().guard(this, () -> read(result, lines, now));
            });
        });
        return true;
    }

    /** A reading came back (main thread): the new board, the outage line, and the settling. */
    void read(KalshiClient.Result r, boolean lines, long askedAt) {
        if (lines && r.games() != null) {
            board = new Board(askedAt, r.games());
            lastOk = games().clock().nowMillis();
            outage = false;
            metaSet(KEY_BOARD, board.toJson());
            scores.seen(r.started(), askedAt); // 0.60.0: the games under way, for their live scores
            scores.seen(board.games(), askedAt);
        }
        if (r.error() != null) {
            failed(r.error(), lines && r.games() == null);
        } else {
            if (warned) {
                log().info("[Sports Board] Kalshi answers again" + (running && !off() ? " - picks are open." : "."));
            }
            warned = false;
            outage = false;
            lastError = null;
        }
        settle(r.markets());
    }

    /** Something couldn't be read: one WARN for the whole outage; the lines' failing closes picks. */
    private void failed(String why, boolean closesPicks) {
        lastError = why;
        if (closesPicks) {
            outage = true;
        }
        if (!warned) {
            warned = true;
            log().warning("[Sports Board] Kalshi could not be read (" + why + ")"
                    + (closesPicks ? " - picks are closed until it answers again;" : " -")
                    + " open picks are kept and checked again every " + settings().refreshMinutes() + " minutes.");
        }
    }

    /** The live server's scheduler, or {@code null} without one. */
    private static Runner bukkit(HomeCraftManagement plugin) {
        if (plugin == null) {
            return null;
        }
        return new Runner() {
            @Override
            public void async(Runnable work) {
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, work);
            }

            @Override
            public void main(Runnable work) {
                if (plugin.isEnabled()) {
                    plugin.getServer().getScheduler().runTask(plugin, work);
                }
            }
        };
    }

    // ---- picks ----------------------------------------------------------------------------------------------

    /**
     * Make a pick at the price the confirm screen showed: every check again (the board's switch and freshness, the
     * side still offered at that very price and payout, the game not started, the stake under the payout cap, then
     * the play gate's money steps: the Parlor, the permission, the day's limit, the chips, the balance), then the
     * stake taken and the pick written.
     *
     * @return why not (the player has NOT been told), or {@code null} when the pick is in
     */
    public Refusal place(Player player, Quote q) {
        if (player == null || q == null) {
            return Refusal.CLOSED;
        }
        GamesService g = games();
        long now = g.clock().nowMillis();
        if (off()) {
            return OFF;
        }
        Board b = board();
        Board.Found found = b.find(q.ticker(), q.side());
        if (found == null) {
            return NOT_OFFERED;
        }
        if (!found.game().open(now)) {
            return STARTED;
        }
        if (!linesOpen(now)) {
            return UPDATING;
        }
        Quote current = quote(found.side(), q.stake());
        if (current == null) {
            int most = mostFor(found.side().price());
            return Lines.offered(found.side().price(), settings().rtpTenths()) && most > 0 && q.stake() > most
                    ? Refusal.of("At that chance the most you can put in is " + Parlor.dollars(most) + ".")
                    : NOT_OFFERED;
        }
        if (!current.equals(q)) {
            return MOVED;
        }
        Refusal r = g.gate().check(player, this, q.stake(), true);
        if (r != null) {
            return r;
        }
        UUID id = player.getUniqueId();
        r = g.parlor().takeStake(id, q.stake());
        if (r != null) {
            return r;
        }
        Pick pick;
        try {
            pick = g.dao().sports().add(id, q.ticker(), q.side(), q.stake(), q.price(), q.payout(), now,
                    g.clock().dayKey(), found.game().startsAt(), found.game().league(), found.game().title(),
                    found.side().label());
        } catch (SQLException e) {
            log().log(Level.SEVERE, "[Sports Board] Could not keep a pick - its stake is given back", e);
            g.parlor().giveCents(id, q.stake() * 100L, "Sports Board stake put back");
            return Refusal.CLOSED;
        }
        g.gate().clicked(player);
        log().info("[Sports Board] " + player.getName() + " picked " + found.side().label() + " (" + q.ticker() + " "
                + q.side() + ") at " + Lines.inHundred(q.price()) + " in 100: " + Parlor.dollars(q.stake())
                + " in, " + Parlor.money(q.payout()) + " if it wins (pick " + pick.id() + ")");
        return null;
    }

    /** A player's picks: the open ones, then the latest settled ones (empty when they can't be read). */
    public List<Pick> picks(UUID player) {
        try {
            return games().dao().sports().mine(player, SETTLED_SHOWN);
        } catch (SQLException e) {
            log().log(Level.WARNING, "[Sports Board] Could not read a player's picks", e);
            return List.of();
        }
    }

    /**
     * 0.60.0 (the owner: "any place we can check our bets after the fact to show how we did?"): every pick a player
     * made, newest first, the open ones then every settled one ({@code null} when they can't be read).
     */
    public List<Pick> history(UUID player) {
        try {
            return games().dao().sports().history(player);
        } catch (SQLException e) {
            log().log(Level.WARNING, "[Sports Board] Could not read a player's picks", e);
            return null;
        }
    }

    /** 0.60.0: everyone's settled picks together, or {@code null} when they can't be read. */
    public SportsPicksDao.Totals everyone() {
        try {
            return games().dao().sports().everyone();
        } catch (SQLException e) {
            log().log(Level.WARNING, "[Sports Board] Could not read everyone's picks", e);
            return null;
        }
    }

    // ---- settling ------------------------------------------------------------------------------------------

    /** Settle every open pick whose market was decided, then give back the ones undecided too long. */
    void settle(Map<String, Kalshi.Market> markets) {
        long now = games().clock().nowMillis();
        List<Pick> open;
        try {
            open = games().dao().sports().open();
        } catch (SQLException e) {
            log().log(Level.SEVERE, "[Sports Board] Could not read the open picks - they are checked next time", e);
            return;
        }
        for (Pick p : open) {
            Kalshi.Market m = markets == null ? null : markets.get(p.ticker());
            Kalshi.Outcome o = m == null ? Kalshi.Outcome.OPEN : m.outcome();
            if (o != Kalshi.Outcome.OPEN) {
                decide(p, o, now, null);
            }
        }
        lapse(now);
    }

    /** Give back every open pick still undecided {@code settle_days} after its game (or after it was made). */
    void lapse(long now) {
        long days = settings().settleDays() * 86_400_000L;
        try {
            for (Pick p : games().dao().sports().open()) {
                if (now - Math.max(p.placedAt(), p.startsAt()) > days) {
                    if (finish(p, SportsPicksDao.LAPSED, p.stake() * 100L, now)) {
                        log().info("[Sports Board] Pick " + p.id() + " on " + p.ticker() + " (" + p.game()
                                + ") had no result after " + settings().settleDays() + " days - "
                                + Parlor.dollars(p.stake()) + " given back to " + p.player());
                    }
                }
            }
        } catch (SQLException e) {
            log().log(Level.SEVERE, "[Sports Board] Could not read the open picks", e);
        }
    }

    /**
     * {@code /hcm games sportsbook settle <ticker> yes|no|void}: settle every open pick on one market by hand.
     *
     * @return how many picks it settled
     */
    public int settleByHand(String ticker, Kalshi.Outcome outcome, String who) {
        if (outcome == Kalshi.Outcome.OPEN) {
            return 0;
        }
        long now = games().clock().nowMillis();
        int n = 0;
        try {
            for (Pick p : games().dao().sports().openOn(ticker)) {
                if (decide(p, outcome, now, who)) {
                    n++;
                }
            }
        } catch (SQLException e) {
            log().log(Level.SEVERE, "[Sports Board] Could not read the picks on " + ticker, e);
        }
        log().info("[Sports Board] " + who + " settled " + ticker + " by hand as " + outcome.name().toLowerCase(Locale.ROOT)
                + ": " + n + " pick" + (n == 1 ? "" : "s"));
        return n;
    }

    /** Settle one pick by its market's outcome. @return whether this call settled it */
    private boolean decide(Pick p, Kalshi.Outcome o, long now, String byHand) {
        if (o == Kalshi.Outcome.VOID) {
            return finish(p, SportsPicksDao.VOID, p.stake() * 100L, now);
        }
        boolean won = (o == Kalshi.Outcome.YES) == "yes".equals(p.side());
        return finish(p, won ? SportsPicksDao.WON : SportsPicksDao.LOST, won ? p.payout() : 0, now);
    }

    /**
     * Move the pick out of OPEN, then pay what it gives back: only the call that moved it pays, so it pays once. The
     * Parlor keeps what the bank refuses (or can't reach) as owed and pays it when it can.
     */
    private boolean finish(Pick p, String status, long cents, long now) {
        String fin = scores.finalFor(p.ticker()); // 0.60.0: the game's final score, when the live scores have it
        try {
            if (!games().dao().sports().settle(p.id(), status, cents, now, fin)) {
                return false; // already settled: it paid there
            }
        } catch (SQLException e) {
            log().log(Level.SEVERE, "[Sports Board] Could not settle pick " + p.id() + " - it stays open", e);
            return false;
        }
        if (cents > 0) {
            games().parlor().giveCents(p.player(), cents, "Sports Board " + status.toLowerCase(Locale.ROOT)
                    + " (pick " + p.id() + ")");
        }
        log().info("[Sports Board] Pick " + p.id() + " on " + p.pick() + " (" + p.ticker() + " " + p.side() + "): "
                + status + (cents > 0 ? ", " + Parlor.money(cents) + " to " + p.player() : ""));
        games().notice(p.player(), told(p, status, cents, settings().settleDays(), fin),
                games().online(p.player()) == null);
        return true;
    }

    /**
     * What the player reads when their pick is settled (pure), with the game's final score after it when it is known
     * (0.60.0: "Your pick on Calgary won: $12.41. Final: CGY 6  PIT 3.").
     */
    public static String told(Pick p, String status, long cents, int settleDays, String finalScore) {
        String said = told(p, status, cents, settleDays);
        return finalScore == null || finalScore.isBlank() || SportsPicksDao.LAPSED.equals(status) ? said
                : said + " &7" + finalScore + ".";
    }

    /** What the player reads when their pick is settled (pure). */
    public static String told(Pick p, String status, long cents, int settleDays) {
        return switch (status) {
            case SportsPicksDao.WON -> "&aYour pick on " + p.pick() + " won: &6" + Parlor.money(cents) + "&a.";
            case SportsPicksDao.LOST -> "&7Your pick on " + p.pick() + " (" + p.game() + ") didn't win: no win this"
                    + " time.";
            case SportsPicksDao.VOID -> "&7The " + p.game() + " game was called off, so your &6"
                    + Parlor.money(cents) + " &7came back.";
            default -> "&7Your pick on " + p.pick() + " had no result after " + settleDays + " days, so your &6"
                    + Parlor.money(cents) + " &7came back.";
        };
    }

    // ---- 0.60.0: the live scores (the sports corner's TVs and ticker draw them) -----------------------------------

    /** Every ten seconds: a round of live scores when one is due. */
    void scoreTick() {
        long now = games().clock().nowMillis();
        if (!scoresBusy && !busy && now - scoresTry >= settings().scoreMs() - 1_000L) {
            refreshScores();
        }
    }

    /**
     * Read the live scores now (off the main thread, one game after another): the games on the board that have
     * started and aren't known to be over, at most {@code score_games}, the ones with open picks first. Only while
     * someone is on the server to see them, or a pick waits on the game; never while the lines are being read.
     *
     * @return whether a round went out
     */
    public boolean refreshScores() {
        SportsbookSettings s = settings();
        if (!s.liveScores() || scoresBusy || busy) {
            return false;
        }
        load();
        long now = games().clock().nowMillis();
        scoresTry = now;
        scores.seen(board.games(), now);
        boolean pruned = scores.prune(now);
        java.util.Set<String> wanted = new java.util.HashSet<>();
        try {
            for (String t : games().dao().sports().openTickers()) {
                wanted.add(event(t));
            }
        } catch (SQLException e) {
            log().log(Level.WARNING, "[Sports Board] Could not read the open picks for the live scores", e);
        }
        boolean anyone = watched != null ? watched.getAsBoolean()
                : ctx.plugin() == null || !ctx.plugin().getServer().getOnlinePlayers().isEmpty();
        List<LiveScores.Game> due = new ArrayList<>();
        for (LiveScores.Game g : scores.due(now, anyone ? s.scoreGames() : Integer.MAX_VALUE, wanted)) {
            if ((anyone || wanted.contains(g.event())) && due.size() < s.scoreGames()) {
                due.add(g);
            }
        }
        if (due.isEmpty()) {
            if (pruned) {
                metaSet(KEY_SCORES, scores.toJson());
            }
            return false;
        }
        KalshiClient.Fetcher f;
        try {
            f = fetcher != null ? fetcher : KalshiClient.http(s.apiBase());
        } catch (IllegalArgumentException e) {
            scoresFailed(e.getMessage());
            return false;
        }
        Runner run = runner != null ? runner : bukkit(ctx.plugin());
        if (run == null) {
            return false;
        }
        Map<String, String[]> asked = new LinkedHashMap<>();
        for (LiveScores.Game g : due) {
            asked.put(g.event(), new String[]{g.kind(), g.milestone()});
        }
        scoresBusy = true;
        int gen = generation;
        KalshiClient.Sleeper sleep = sleeper;
        run.async(() -> {
            KalshiClient.ScoresRead r;
            try {
                r = KalshiClient.scores(f, sleep, asked);
            } catch (RuntimeException e) {
                r = new KalshiClient.ScoresRead(Map.of(), KalshiClient.describe(e));
            }
            KalshiClient.ScoresRead result = r;
            run.main(() -> {
                scoresBusy = false;
                if (gen != generation && running) {
                    return;
                }
                games().guard(this, () -> readScores(result));
            });
        });
        return true;
    }

    /** A round of live scores came back (main thread): each game's score, and a finished game's final kept. */
    void readScores(KalshiClient.ScoresRead r) {
        long now = games().clock().nowMillis();
        for (Map.Entry<String, Scores.Live> e : r.scores().entrySet()) {
            if (scores.read(e.getKey(), e.getValue(), now)) {
                LiveScores.Entry done = scores.get(e.getKey());
                try {
                    games().dao().sports().finalScore(e.getKey(), done.line());
                } catch (SQLException ex) {
                    log().log(Level.WARNING, "[Sports Board] Could not keep a final score on its picks", ex);
                }
            }
        }
        if (r.error() != null) {
            scoresFailed(r.error());
        } else {
            if (scoresWarned) {
                log().info("[Sports Board] The live scores read again.");
            }
            scoresWarned = false;
            scoresError = null;
        }
        metaSet(KEY_SCORES, scores.toJson());
    }

    /** One console line for a whole outage of the live scores; the scores shown stay as they were. */
    private void scoresFailed(String why) {
        scoresError = why;
        if (!scoresWarned) {
            scoresWarned = true;
            log().warning("[Sports Board] A live score could not be read (" + why + ") - the last scores stay up and"
                    + " are read again in " + settings().scoreSeconds() + " seconds.");
        }
    }

    /** A market's event: its ticker without the last part ({@code KXNBAGAME-26NOV27MIANYK-NYK}: the game's). */
    static String event(String ticker) {
        int dash = ticker == null ? -1 : ticker.lastIndexOf('-');
        return dash <= 0 ? (ticker == null ? "" : ticker) : ticker.substring(0, dash);
    }

    /** The live scores as kept (for the tests and the status). */
    public LiveScores scores() {
        load();
        return scores;
    }

    /**
     * What the TVs and the ticker show: the games being played, then those just over, each with its league's tab
     * and its last score. A plain read: nothing is fetched.
     */
    public List<ScoreScreens.Item> scoreItems() {
        load();
        List<ScoreScreens.Item> out = new ArrayList<>();
        if (!settings().liveScores()) {
            return out;
        }
        Map<String, String> tabs = tabs();
        for (LiveScores.Entry e : scores.shown()) {
            LiveScores.Game g = e.game();
            out.add(new ScoreScreens.Item(tabs.getOrDefault(g.league(), g.league().toUpperCase(Locale.ROOT)),
                    g.away(), g.home(), e.live()));
        }
        return out;
    }

    /** The next {@code max} games to start, for a TV with no game under way. A plain read. */
    public List<ScoreScreens.Upcoming> nextGames(int max) {
        long now = games().clock().nowMillis();
        Map<String, String> tabs = tabs();
        List<ScoreScreens.Upcoming> out = new ArrayList<>();
        for (Board.Game g : board().upcoming(now, null)) {
            if (out.size() >= max) {
                break;
            }
            String[] t = g.away().isEmpty() || g.home().isEmpty() ? Scores.teams("", g.title())
                    : new String[]{g.away(), g.home()};
            if (t[0].isEmpty()) {
                continue;
            }
            out.add(new ScoreScreens.Upcoming(tabs.getOrDefault(g.league(), g.league().toUpperCase(Locale.ROOT)),
                    t[0], t[1], when(g.startsAt(), now, games().clock().zone())));
        }
        return out;
    }

    private Map<String, String> tabs() {
        Map<String, String> tabs = new LinkedHashMap<>();
        for (SportsbookSettings.League l : settings().boardLeagues()) {
            tabs.put(l.key(), l.tab());
        }
        return tabs;
    }

    // ---- the wall board (the Clubhouse draws it) -------------------------------------------------------------

    /**
     * One game for the Parlor's wall board.
     *
     * @param league its tab ("NBA")
     * @param title  "San Antonio vs Sacramento"
     * @param starts its start in the server's zone ("7:30 PM", or "Tue 7:30 PM" when it isn't today)
     * @param sides  its sides
     */
    public record WallGame(String league, String title, String starts, List<WallSide> sides) {
    }

    /**
     * One side on the wall board.
     *
     * @param label     who it is
     * @param inHundred wins about this many in 100 (0 when it has no price)
     * @param backAtTen what $10 on it gives back if it wins ("$12.33"), or "" when it isn't offered
     */
    public record WallSide(String label, int inHundred, String backAtTen) {
    }

    /**
     * Tonight's games for the Parlor's wall board: the next {@code max} games to start, soonest first, with what
     * {@value #WALL_STAKE} dollars on each side gives back. A plain read: nothing is fetched or changed.
     */
    public List<WallGame> wall(int max) {
        long now = games().clock().nowMillis();
        Board b = board();
        List<WallGame> out = new ArrayList<>();
        Map<String, String> tabs = new LinkedHashMap<>();
        for (SportsbookSettings.League l : settings().boardLeagues()) {
            tabs.put(l.key(), l.tab());
        }
        for (Board.Game g : b.upcoming(now, null)) {
            if (out.size() >= max) {
                break;
            }
            List<WallSide> sides = new ArrayList<>();
            for (Board.Side s : g.sides()) {
                Quote q = quote(s, WALL_STAKE);
                sides.add(new WallSide(s.label(), s.price() > 0 ? Lines.inHundred(s.price()) : 0,
                        q == null ? "" : Parlor.money(q.payout())));
            }
            out.add(new WallGame(tabs.getOrDefault(g.league(), g.league().toUpperCase(Locale.ROOT)), g.title(),
                    when(g.startsAt(), now, games().clock().zone()), sides));
        }
        return out;
    }

    /**
     * {@link #wall} as lines of plain text for a sign or a text display: "NBA 9:00 PM San Antonio vs Sacramento",
     * then "  San Antonio $12.33 · Sacramento $31.03" ($10 in; "-" for a side not offered). "Lines are updating"
     * while the lines are closed, "No games tonight" for none.
     */
    public List<String> wallText(int max) {
        List<String> out = new ArrayList<>();
        long now = games().clock().nowMillis();
        if (off()) {
            out.add("The Sports Board is closed right now");
            return out;
        }
        List<WallGame> games = wall(max);
        if (games.isEmpty()) {
            out.add("No games in the next " + settings().hoursAhead() + " hours");
            return out;
        }
        out.add(linesOpen(now) ? "Tonight's games - $" + WALL_STAKE + " in pays" : "Lines are updating");
        for (WallGame g : games) {
            out.add(g.league() + " " + g.starts() + "  " + g.title());
            List<String> sides = new ArrayList<>();
            for (WallSide s : g.sides()) {
                sides.add(s.label() + " " + (s.backAtTen().isEmpty() ? "-" : s.backAtTen()));
            }
            out.add("  " + String.join(" · ", sides));
        }
        return out;
    }

    /** "7:30 PM" today, "Tue 7:30 PM" another day, in {@code zone} (pure). */
    public static String when(long at, long now, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochMilli(at).atZone(zone);
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        String time = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US));
        return t.toLocalDate().equals(today) ? time
                : t.format(DateTimeFormatter.ofPattern("EEE", Locale.US)) + " " + time;
    }

    // ---- status --------------------------------------------------------------------------------------------

    /** One line for {@code /hcm games status}. */
    String statusLine() {
        long now = games().clock().nowMillis();
        Board b = board();
        String read = b.fetchedAt() <= 0 ? "never read" : "read " + Menus.duration(now - b.fetchedAt()) + " ago";
        return "sportsbook: " + (off() ? "picks switched off" : linesOpen(now) ? "picks open" : "lines updating")
                + ", " + b.upcoming(now, null).size() + " games (" + read + ")"
                + (lastError != null ? ", last error: " + lastError : "");
    }

    /** {@code /hcm games sportsbook status}'s lines. */
    public List<String> adminStatus(long now) {
        List<String> out = new ArrayList<>();
        Board b = board();
        out.add("&6Sports Board &7- " + (off() ? "&cpicks switched off" : linesOpen(now) ? "&apicks open"
                : "&elines updating"));
        out.add("&7Last refresh: &f" + (lastTry <= 0 ? "not yet this run" : Menus.duration(now - lastTry) + " ago")
                + (lastError != null ? " &c(" + lastError + ")" : ""));
        out.add("&7Lines read: &f" + (b.fetchedAt() <= 0 ? "never" : Menus.duration(now - b.fetchedAt()) + " ago")
                + " &7- " + b.upcoming(now, null).size() + " games, " + b.sideCount() + " markets");
        try {
            long day = games().clock().dayKey();
            long[] t = games().dao().sports().today(day, games().clock().startOfDay(day));
            out.add("&7Open picks: &f" + t[2]);
            out.add("&7Today: &f" + Parlor.dollars((int) t[0]) + " &7in, &f" + Parlor.money(t[1])
                    + " &7back (wins and refunds)");
        } catch (SQLException e) {
            out.add("&cThe picks could not be read - see the console");
            log().log(Level.WARNING, "[Sports Board] Could not read today's picks", e);
        }
        SportsbookSettings s = settings();
        out.add("&7Live scores: &f" + (!s.liveScores() ? "off" : scores.size() + " games followed, "
                + scores.shown().size() + " shown; read " + (scoresTry <= 0 ? "not yet this run"
                : Menus.duration(now - scoresTry) + " ago")) + (scoresError != null ? " &c(" + scoresError + ")" : ""));
        out.add("&7Leagues: &f" + String.join(", ", s.leagues()) + " &7- every " + s.refreshMinutes() + " min, "
                + s.hoursAhead() + " h ahead, " + percent(s.rtp()) + " of every 100 back");
        return out;
    }

    /** "90" or "92.5". */
    public static String percent(double rtp) {
        return rtp == Math.rint(rtp) ? Long.toString((long) rtp) : Double.toString(rtp);
    }

    // ---- the admin commands --------------------------------------------------------------------------------

    /** {@code /hcm games sportsbook status|refresh|settle <ticker> yes|no|void|off|on}. */
    final class Admin implements GameAdmin {

        @Override
        public String name() {
            return SPEC.id();
        }

        @Override
        public void handle(org.bukkit.command.CommandSender sender, String[] args) {
            String verb = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
            switch (verb) {
                case "status" -> {
                    for (String line : adminStatus(games().clock().nowMillis())) {
                        sender.sendMessage(com.dierks.homecraft.util.Text.of(line));
                    }
                }
                case "refresh" -> {
                    boolean went = refresh();
                    sender.sendMessage(com.dierks.homecraft.util.Text.of(went
                            ? "&aReading Kalshi now. &7/hcm games sportsbook status shows how it went."
                            : busy ? "&7A reading is already in flight." : "&7Nothing to read right now."));
                }
                case "settle" -> {
                    Kalshi.Outcome o = args.length < 3 ? null : switch (args[2].toLowerCase(Locale.ROOT)) {
                        case "yes" -> Kalshi.Outcome.YES;
                        case "no" -> Kalshi.Outcome.NO;
                        case "void" -> Kalshi.Outcome.VOID;
                        default -> null;
                    };
                    if (o == null) {
                        sender.sendMessage(com.dierks.homecraft.util.Text.of(
                                "&cUsage: /hcm games sportsbook settle <ticker> yes|no|void"));
                        return;
                    }
                    String ticker = args[1].toUpperCase(Locale.ROOT);
                    int n = settleByHand(ticker, o, sender.getName());
                    sender.sendMessage(com.dierks.homecraft.util.Text.of("&a" + ticker + " settled as "
                            + o.name().toLowerCase(Locale.ROOT) + ": &f" + n + " &aopen pick" + (n == 1 ? "" : "s")
                            + " settled."));
                }
                case "off", "on" -> {
                    off(verb.equals("off"));
                    log().info("[Sports Board] " + sender.getName() + " switched picks " + verb);
                    sender.sendMessage(com.dierks.homecraft.util.Text.of(verb.equals("off")
                            ? "&ePicks are off. &7Open picks are still settled and paid."
                            : "&aPicks are on."));
                }
                default -> {
                    for (String line : help()) {
                        sender.sendMessage(com.dierks.homecraft.util.Text.of(line));
                    }
                }
            }
        }

        @Override
        public List<String> tab(org.bukkit.command.CommandSender sender, String[] args) {
            List<String> out = new ArrayList<>();
            String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            if (args.length <= 1) {
                for (String v : List.of("status", "refresh", "settle", "off", "on")) {
                    if (v.startsWith(last)) {
                        out.add(v);
                    }
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("settle")) {
                try {
                    for (String t : games().dao().sports().openTickers()) {
                        if (t.toLowerCase(Locale.ROOT).startsWith(last)) {
                            out.add(t);
                        }
                    }
                } catch (SQLException ignored) {
                    // completions are a courtesy
                }
            } else if (args.length == 3 && args[0].equalsIgnoreCase("settle")) {
                for (String v : List.of("yes", "no", "void")) {
                    if (v.startsWith(last)) {
                        out.add(v);
                    }
                }
            }
            return out;
        }

        @Override
        public List<String> help() {
            return HELP;
        }
    }

    /** The admin lines, as {@code /hcm games} help shows them. */
    public static final List<String> HELP = List.of(
            "&e/hcm games sportsbook status &7- the Sports Board: last refresh, games, open picks, money today",
            "&e/hcm games sportsbook refresh &7- read Kalshi now (the lines, and the open picks' games)",
            "&e/hcm games sportsbook settle <ticker> yes|no|void &7- settle a stuck market's picks by hand (logged)",
            "&e/hcm games sportsbook off|on &7- stop or start new picks (open picks are still settled)");

    // ---- kept values ---------------------------------------------------------------------------------------

    /** Read the kept board once (a restart shows the games at once; picks wait for a fresh reading). */
    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        String kept = meta(KEY_BOARD);
        if (kept != null && board.fetchedAt() == 0) {
            board = Board.fromJson(kept);
        }
        String followed = meta(KEY_SCORES); // 0.60.0
        if (followed != null && scores.size() == 0) {
            scores = LiveScores.fromJson(followed);
        }
    }

    private String meta(String key) {
        Parlor.Store store = games().metaStore();
        if (store == null) {
            return memory.get(key);
        }
        try {
            return store.get(key);
        } catch (SQLException | RuntimeException e) {
            log().log(Level.WARNING, "[Sports Board] Could not read " + key, e);
            return null;
        }
    }

    private void metaSet(String key, String value) {
        Parlor.Store store = games().metaStore();
        if (store == null) {
            memory.put(key, value);
            return;
        }
        try {
            store.set(key, value);
        } catch (SQLException | RuntimeException e) {
            log().log(Level.WARNING, "[Sports Board] Could not keep " + key, e);
        }
    }

    /** Kept values in a test with no store. */
    private final Map<String, String> memory = new LinkedHashMap<>();

    private Logger log() {
        return games().logger();
    }
}
