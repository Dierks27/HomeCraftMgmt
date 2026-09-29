package com.dierks.homecraft.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Parties;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.games.world.WorldSessions;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.GameClock;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * The Games module's framework (spec §3.2, R3.2): the registry of every game, the switch that
 * decides whether each is open, failure isolation, the entry points, and the shared helpers the
 * games are built on (the gate, rounds, rewards, scores, world sessions, invites, the featured
 * game).
 *
 * <p>It is always constructed — like the token service — even while {@code games.enabled} is
 * false, because the games themselves are only one of the things it looks after: rounds a crash
 * left open still have to be finished and paid, and invites still have to be cancelled.
 *
 * <p><b>Failure isolation.</b> Everything a game does runs inside {@link #guard}: an exception is
 * logged once (SEVERE, with the game id), that one game is switched off until {@code /hcm reload},
 * and the player sees "That game is taking a break. Try another one!". Nothing a game throws can
 * reach Bukkit, another game or the rest of the plugin. The same is why games never register
 * their own listeners or tasks: {@link #on}, {@link #every} and {@link #later} register them for
 * the game, skip them while it is closed, run them inside its guard, and remove them when it
 * stops. A game that is switched off — it threw, or a reload closed it — has its screens closed,
 * its world sessions ended (GAME_OFF) and its OPEN rounds finished by its exit rule, by the
 * framework, whatever state the game itself is in.
 *
 * <p><b>Lifecycle.</b> {@link #start} finishes what a crash or a shutdown left (OPEN rounds of
 * players who are offline, old saved states) and starts the open games; {@link #reload} leaves the
 * games that stay open running (a live screen or session keeps the settings it started with),
 * stops the ones that closed and starts the ones that opened; {@link #stop} closes every games
 * screen and stops everything — during a server shutdown without teleporting anyone, leaving OPEN
 * rounds for the next start.
 */
public final class GamesService {

    /** What {@code /hcm play <id>} names: a game, or a course inside one ({@code playable} non-null). */
    public record Target(Game game, Game.Playable playable) {
    }

    /** How often unfinished rounds nobody touched are swept up: once a minute. */
    static final long SWEEP_TICKS = 20L * 60;
    /** Finished saved states are kept this long for support, then pruned at start. */
    static final long DONE_KEPT_MS = 7L * 86_400_000L;
    /** Queued notices wait this long after a join, so they aren't lost among the join lines. */
    static final long NOTICE_DELAY_TICKS = 40L;

    private final GamesHost host;
    private final Breaks breaks;
    private final GameContext context;
    private final PlayGate gate;
    private final ChanceRounds rounds;
    private final SkillRewards rewards;
    private final Scores scores;
    private final WorldSessions sessions;
    private final Invites invites;
    /** Every play-together party (EVENTS-OWNER-DECISIONS D4): one per player across the games. */
    private final Parties parties = new Parties();
    private final Featured featured;
    /** Who hears about skill-game finishes (quests, achievements); {@link GameProgress#NONE} until registered. */
    private volatile GameProgress progress = GameProgress.NONE;
    private final List<GameSpec<?>> specs;
    private volatile List<Game> games = List.of();
    /** The shared screens (gui/games); "Coming soon!" until installed. */
    private GamesScreens screens = GamesScreens.NONE;
    /** Fresh Courses' gate over generated courses; nothing generated is live until it is installed. */
    private volatile GeneratedCourses generated = GeneratedCourses.NONE;
    /** Ids and aliases, lower-case. */
    private final Map<String, Game> byName = new HashMap<>();
    /** Games that threw: off until {@code /hcm reload}. */
    private final Set<String> failed = new HashSet<>();
    /** Games whose constructor threw, with why: never built this run (a reload tries again). */
    private final Map<String, String> unbuilt = new LinkedHashMap<>();
    /** Games whose {@link Game#start()} ran and whose {@link Game#stop()} hasn't. */
    private final Set<String> running = new HashSet<>();
    /** Each game's registered handlers hang off one listener object, so they unregister together. */
    private final Map<String, Listener> listeners = new HashMap<>();
    private final Map<String, List<BukkitTask>> tasks = new HashMap<>();
    /** Cancels the one-minute round sweep. */
    private Runnable sweep;

    /**
     * @param breaks Take a break, built earlier and on its own (it also guards the Scratch Ticket
     *               and Crates, R1.15)
     */
    public GamesService(HomeCraftManagement plugin, Breaks breaks) {
        this(GamesHost.live(plugin, new GamesDao(plugin.database())), breaks, GameCatalog.SPECS);
    }

    /** The framework over {@code host}, with these games (the catalog, or a test's own). */
    GamesService(GamesHost host, Breaks breaks, List<GameSpec<?>> specs) {
        this.host = host;
        this.breaks = breaks;
        this.specs = List.copyOf(specs);
        this.context = new GameContext(host.plugin(), this);
        this.gate = new PlayGate(this);
        this.rounds = new ChanceRounds(this);
        this.rewards = new SkillRewards(this);
        this.scores = new Scores(this);
        this.sessions = new WorldSessions(this);
        this.invites = new Invites(this);
        this.featured = new Featured(this);
        build();
    }

    // ---- lifecycle --------------------------------------------------------------------------

    /**
     * Enable: prune old saved states, finish the OPEN rounds of players who are offline (and any
     * nobody touched for ten minutes), start every open game, and arm the one-minute sweep.
     */
    public void start() {
        cancelSweep();
        try {
            int pruned = dao().pruneDone(host.clock().nowMillis() - DONE_KEPT_MS);
            if (pruned > 0) {
                host.logger().info("Games: pruned " + pruned + " finished saved state(s) older than a week.");
            }
        } catch (SQLException e) {
            host.logger().log(Level.WARNING, "Could not prune old saved states", e);
        }
        rounds.settleAtStart();
        // The session and Games-world guards (they check games.enabled themselves).
        quietly(sessions::start);
        for (Game g : games) {
            if (enabled(g)) {
                startGame(g);
            }
        }
        sweep = host.every(SWEEP_TICKS, SWEEP_TICKS, this::sweep);
    }

    /**
     * {@code /hcm reload}: re-read config. Games that stay open keep running (their start is not
     * run again — that would register their handlers twice); games that closed or failed are
     * stopped, their sessions left with GAME_OFF and their OPEN rounds finished; games that opened
     * are started. A game that failed, or could not be built, gets another chance.
     */
    public void reload() {
        failed.clear();
        featured.forget();
        build();
        if (!config().enabled()) {
            closeScreens(); // the shared screens (the Games screen, Take a break...) too
        }
        for (Game g : games) {
            boolean open = enabled(g);
            if (running.contains(g.id()) && !open) {
                stopGame(g);
                switchOff(g, Refusal.CLOSED);
            } else if (!running.contains(g.id()) && open) {
                startGame(g);
            } else if (open && g.kind() == GameKind.CHANCE) {
                // A game of chance plays with the settings its screen opened with (its odds and
                // payouts were shown from them): close it so the next open shows the new ones.
                // Nothing is lost: a result is decided and paid before it's shown, and an open
                // round stays open to resume.
                closeScreensOf(g, ChanceRounds.CHANGED);
            }
        }
    }

    /**
     * Disable: close every games screen, end every world session, stop every game and remove what
     * it registered. While the server is stopping nobody is teleported (the sessions restore in
     * place, R2.6) and OPEN rounds stay OPEN for the next start; otherwise they are finished now.
     */
    public void stop() {
        cancelSweep();
        boolean stopping = host.stopping();
        closeScreens();
        for (Player p : online()) {
            quietly(() -> {
                if (sessions.session(p) != null) {
                    sessions.leave(p, stopping ? EndReason.STOP : EndReason.GAME_OFF);
                }
            });
        }
        if (!stopping) {
            for (Game g : games) {
                quietly(() -> rounds.settleGame(g.id()));
            }
        }
        for (Game g : games) {
            stopGame(g);
        }
        invites.clear();
        parties.clear();
        featured.forget();
        // Anyone still in a session ends it the right way for a stop, and the guards come off.
        quietly(sessions::stop);
    }

    /**
     * The worlds are up (the plugin's one-tick pass after enable): players online whose last
     * session is still sending them back go home now.
     */
    public void worldsReady() {
        quietly(sessions::worldsReady);
    }

    /**
     * A player joined: finish any round a crash left open (the line waits a moment, so it isn't
     * lost among the join messages), then each open game's {@link Game#onJoin}. Their saved things
     * are the recovery listener's, which runs even while the games are off.
     */
    public void onJoin(Player player) {
        UUID id = player.getUniqueId();
        rounds.settleOpen(id, true);
        Runnable deliver = () -> {
            Player p = host.online(id);
            if (p != null) {
                deliverNotices(p);
            }
        };
        if (host.later(NOTICE_DELAY_TICKS, deliver) == null) {
            deliver.run();
        }
        for (Game g : games) {
            if (running.contains(g.id()) && enabled(g)) {
                guard(g, () -> g.onJoin(player));
            }
        }
    }

    /**
     * A player quit: each open game's {@link Game#onQuit} (it ends anything live that isn't a
     * round or a session), then their OPEN rounds finished by the exit rule (the line waits for
     * their next join), their invites cancelled and their cooldown forgotten.
     */
    public void onQuit(Player player) {
        UUID id = player.getUniqueId();
        for (Game g : games) {
            if (running.contains(g.id()) && enabled(g)) {
                guard(g, () -> g.onQuit(player));
            }
        }
        rounds.settleOpen(id, true);
        invites.cancel(id);
        gate.forget(id);
        rewards.forget(id);
    }

    // ---- the registry -----------------------------------------------------------------------

    /** Every game, in catalog order (open or not). */
    public Collection<Game> games() {
        return games;
    }

    /** A game by id or alias, any case; {@code null} if there is none. */
    public Game game(String idOrAlias) {
        return idOrAlias == null ? null : byName.get(idOrAlias.trim().toLowerCase(Locale.ROOT));
    }

    /** What {@code /hcm play <id>} names — a game, or a course of an open game — or {@code null}. */
    public Target resolve(String id) {
        Game g = game(id);
        if (g != null) {
            return new Target(g, null);
        }
        if (id == null) {
            return null;
        }
        for (Game game : games) {
            if (!enabled(game)) {
                continue;
            }
            Collection<Game.Playable> playables = guard(game, game::playables, List.of());
            for (Game.Playable p : playables) {
                if (p.id().equalsIgnoreCase(id.trim())) {
                    return new Target(game, p);
                }
            }
        }
        return null;
    }

    /**
     * Whether the game is open: {@code games.enabled}, its settings readable, its own
     * {@link Game#configEnabled()}, and it has not failed.
     */
    public boolean enabled(Game game) {
        if (game == null || failed.contains(game.id())) {
            return false;
        }
        GamesConfig.Parsed cfg = config();
        if (!cfg.enabled() || !cfg.readable(game.id())) {
            return false;
        }
        return guard(game, game::configEnabled, false);
    }

    /** Whether the game threw and is off until {@code /hcm reload}. */
    public boolean failed(Game game) {
        return game != null && failed.contains(game.id());
    }

    /**
     * Why a game is closed, in plain words for {@code /hcm games status}, or {@code null} when it
     * is open.
     */
    public String closedReason(Game game) {
        if (game == null) {
            return "no such game";
        }
        if (failed.contains(game.id())) {
            return "it failed - see the console; /hcm reload tries it again";
        }
        GamesConfig.Parsed cfg = config();
        if (!cfg.enabled()) {
            return "games.enabled is false";
        }
        if (!cfg.readable(game.id())) {
            return "its config block could not be read - see the console";
        }
        if (!guard(game, game::configEnabled, false)) {
            GameSpec<?> spec = spec(game.id());
            Object on = spec == null ? null : PlayGate.component(cfg.settings(spec), "enabled");
            return Boolean.FALSE.equals(on)
                    ? GamesConfig.PATH + "." + GamesConfig.block(game.id()) + ".enabled is false"
                    : "not ready (not built yet, or no stake fits 85-95 - see the console)";
        }
        return null;
    }

    /** Games whose constructor threw, with why (they are not in {@link #games()} this run). */
    public Map<String, String> unbuilt() {
        return Collections.unmodifiableMap(unbuilt);
    }

    /** The live {@code games:} config (re-read on every reload; never cache it). */
    public GamesConfig.Parsed config() {
        return host.config();
    }

    /** A game's live settings (its shipped defaults if its section couldn't be read). */
    public <S> S settings(GameSpec<S> spec) {
        return config().settings(spec);
    }

    // ---- entry points -----------------------------------------------------------------------

    /**
     * {@code /hcm play <id>}: resolve, run the gate, then open the game or start the course.
     *
     * @return whether something opened (the player has been told why not)
     */
    public boolean open(Player player, String id, Runnable back) {
        Target t = resolve(id);
        if (t == null) {
            tell(player, Refusal.of("There's no game called \"" + (id == null ? "" : id.trim())
                    + "\". /hcm play shows them all."));
            return false;
        }
        Game game = t.game();
        // Going back to a round already paid for is not a new play: no gate (spec §5.2), or a
        // pause or a world change would leave it to be finished by the exit rule.
        boolean resume = t.playable() == null && enabled(game) && rounds.openRound(player.getUniqueId(), game.id()) != null;
        Refusal refusal = resume ? null : canOpen(player, game);
        if (refusal != null) {
            tell(player, refusal);
            return false;
        }
        if (t.playable() == null) {
            guard(game, () -> game.open(player, back));
            return !failed(game);
        }
        boolean started = guard(game, () -> game.play(player, t.playable().id(), back), false);
        return started && !failed(game);
    }

    /**
     * The Games screen ({@code /hcm play}): open while the games are on, to a player who may play
     * them, in a world they are played in.
     */
    public void openGamesScreen(Player player, Runnable back) {
        if (!config().enabled()) {
            tell(player, Refusal.of("The games are closed right now."));
            return;
        }
        if (!player.hasPermission(PlayGate.PERMISSION_PLAY)) {
            tell(player, Refusal.NO_GAMES);
            return;
        }
        if (!gate.worldAllowed(player.getWorld())) {
            tell(player, Refusal.WORLD);
            return;
        }
        try {
            screens.games(player, back);
        } catch (RuntimeException | LinkageError e) {
            host.logger().log(Level.SEVERE, "The Games screen failed", e);
            tell(player, Refusal.BROKEN);
        }
    }

    /** Gate steps 0-4 (R3.9): may the player open this game? {@code null} = yes. */
    public Refusal canOpen(Player player, Game game) {
        return gate.open(player, game);
    }

    /** The full gate for putting {@code stake} in. {@code null} = go ahead. */
    public Refusal canStake(Player player, Game game, int stake) {
        return gate.check(player, game, stake);
    }

    // ---- the restart hold -------------------------------------------------------------------

    /**
     * The scheduled-restart hold as configured now ({@code games.restart_times},
     * {@code games.restart_hold_minutes}, read in {@code clock.time_zone}). Built on every call,
     * so a reload takes effect at once.
     */
    public RestartHold restartHold() {
        GamesConfig.Common c = config().common();
        return new RestartHold(c.restartTimes(), host.clock().zone(), c.restartHoldMinutes());
    }

    /**
     * While a scheduled restart is minutes away, its time for players ("4:00 PM"); otherwise
     * {@code null}. "Now" is the plugin's clock.
     */
    public String restartHeld() {
        return restartHold().heldFor(host.clock().nowMillis());
    }

    /**
     * The refusal for starting something a restart would cut off (a world game, a new round of
     * chance), or {@code null} to go ahead. Nothing already going is ever stopped by it.
     */
    public Refusal restartRefusal() {
        String at = restartHeld();
        return at == null ? null : Refusal.restart(at);
    }

    /** Tell the player why not: a red chat line and the refused sound; nothing at all for the cooldown. */
    public void tell(Player player, Refusal refusal) {
        if (player == null || refusal == null || refusal.silent() || refusal.message().isEmpty()) {
            return;
        }
        player.sendMessage(Text.of("&c" + refusal.message()));
        try {
            Sounds.refused(player);
        } catch (RuntimeException | LinkageError ignored) {
            // a sound never stops the line reaching the player
        }
    }

    // ---- listeners, tasks and failure isolation ---------------------------------------------

    /**
     * Register an event handler for {@code game}. It is skipped while the game is closed, runs
     * inside the game's guard, and is removed when the game stops.
     */
    public <E extends Event> void on(Game game, Class<E> type, EventPriority priority, boolean ignoreCancelled,
                                     Consumer<E> handler) {
        HomeCraftManagement plugin = host.plugin();
        Listener owner = listeners.computeIfAbsent(game.id(), k -> new Listener() {
        });
        plugin.getServer().getPluginManager().registerEvent(type, owner, priority, (l, event) -> {
            if (!type.isInstance(event) || !enabled(game)) {
                return;
            }
            guard(game, () -> handler.accept(type.cast(event)));
        }, plugin, ignoreCancelled);
    }

    /** A repeating task for {@code game}: skipped while closed, guarded, cancelled when it stops. */
    public BukkitTask every(Game game, long delay, long period, Runnable task) {
        HomeCraftManagement plugin = host.plugin();
        BukkitTask t = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (enabled(game)) {
                guard(game, task);
            }
        }, Math.max(0, delay), Math.max(1, period));
        track(game, t);
        return t;
    }

    /** A one-off task for {@code game}: skipped if it closed meanwhile, guarded, cancelled when it stops. */
    public BukkitTask later(Game game, long delay, Runnable task) {
        HomeCraftManagement plugin = host.plugin();
        BukkitTask t = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (enabled(game)) {
                guard(game, task);
            }
        }, Math.max(0, delay));
        track(game, t);
        return t;
    }

    /** Run {@code task} for {@code game}; if it throws, the game fails (see {@link #fail}). */
    public void guard(Game game, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException | LinkageError e) {
            fail(game, e);
        }
    }

    /** Run {@code task} for {@code game} and return its result, or {@code fallback} if it threw. */
    public <T> T guard(Game game, Supplier<T> task, T fallback) {
        try {
            return task.get();
        } catch (RuntimeException | LinkageError e) {
            fail(game, e);
            return fallback;
        }
    }

    /**
     * Switch {@code game} off until {@code /hcm reload}, logging why once. Its handlers and tasks
     * go at once; a tick later (so the screen whose click failed has closed itself first) its
     * screens close with "That game is taking a break. Try another one!", its world sessions end
     * (GAME_OFF) and its OPEN rounds are finished by its exit rule (R2.6) — unless a reload came
     * in between and brought it back.
     */
    public void fail(Game game, Throwable cause) {
        if (game == null) {
            host.logger().log(Level.SEVERE, "A game failed", cause);
            return;
        }
        if (!failed.add(game.id())) {
            return;
        }
        host.logger().log(Level.SEVERE, "The game " + game.id() + " failed and is switched off until /hcm reload",
                cause);
        featured.forget();
        stopGame(game);
        Runnable cleanup = () -> {
            // A reload in the tick between gave it another chance: leave it running.
            if (failed.contains(game.id())) {
                switchOff(game, Refusal.BROKEN);
            }
        };
        if (host.later(1, cleanup) == null) {
            cleanup.run();
        }
    }

    // ---- the shared helpers -----------------------------------------------------------------

    public HomeCraftManagement plugin() {
        return host.plugin();
    }

    /** The server's calendar where the players live (the plugin's clock; a test's movable one). */
    public GameClock clock() {
        return host.clock();
    }

    public GameContext context() {
        return context;
    }

    public GamesDao dao() {
        return host.dao();
    }

    public PlayGate gate() {
        return gate;
    }

    public Breaks breaks() {
        return breaks;
    }

    public ChanceRounds rounds() {
        return rounds;
    }

    public SkillRewards rewards() {
        return rewards;
    }

    public Scores scores() {
        return scores;
    }

    public WorldSessions sessions() {
        return sessions;
    }

    public Invites invites() {
        return invites;
    }

    /**
     * The play-together parties (EVENTS-OWNER-DECISIONS D4): party races and golf together share
     * this one registry, so a player is in at most one party at a time. The games that run them
     * add and remove players themselves (a quit is theirs to see first: it is a DNF); every party
     * is dropped when the games stop.
     */
    public Parties parties() {
        return parties;
    }

    public Featured featured() {
        return featured;
    }

    /** Who hears about skill-game finishes; never {@code null}. */
    public GameProgress progress() {
        return progress;
    }

    /**
     * Register who hears about skill-game finishes ({@code null} = nobody). A game calls it through
     * {@link #tellProgress}, which guards the call.
     */
    public void progress(GameProgress listener) {
        this.progress = listener == null ? GameProgress.NONE : listener;
    }

    /** Tell the progress listener something, guarded: a listener that throws can't break a game. */
    public void tellProgress(java.util.function.Consumer<GameProgress> call) {
        if (call == null) {
            return;
        }
        try {
            call.accept(progress);
        } catch (RuntimeException | LinkageError e) {
            host.logger().log(java.util.logging.Level.WARNING, "Games: a quest/achievement listener failed", e);
        }
    }

    /** The shared games screens (Games screen, high scores, Take a break, player picker). */
    public GamesScreens screens() {
        return screens;
    }

    /** Install the real screens (done once at enable, after the service is built). */
    public void screens(GamesScreens screens) {
        this.screens = screens == null ? GamesScreens.NONE : screens;
    }

    /**
     * What the course engines ask about generated courses (GEN-SPEC §0.2 R5): {@link
     * GeneratedCourses#NONE} — nothing generated is live — until Fresh Courses installs its engine,
     * and whenever the {@code fresh_courses} game is closed (switched off, reloaded off, or failed), whatever
     * its own stop managed to do. So the gate can't outlive the game that vouches for it.
     */
    public GeneratedCourses generated() {
        GeneratedCourses g = generated;
        if (g == GeneratedCourses.NONE) {
            return g;
        }
        Game daily = byName.get(Slots.DAILY);
        return daily == null || enabled(daily) ? g : GeneratedCourses.NONE;
    }

    /** Install (or, with {@code null}, remove) Fresh Courses' engine. */
    public void generated(GeneratedCourses generated) {
        this.generated = generated == null ? GeneratedCourses.NONE : generated;
    }

    /**
     * Tell the game {@code gameId} ({@code trials} or {@code golf}) that its courses changed
     * outside its own commands, so it reads them again. Inside its guard; unknown ids are ignored.
     */
    public void coursesChanged(String gameId) {
        Game g = gameId == null ? null : byId().get(gameId.trim().toLowerCase(Locale.ROOT));
        if (g != null) {
            guard(g, g::coursesChanged);
        }
    }

    /** Every game's id, for {@code /hcm games status} and tab completion. */
    public Map<String, Game> byId() {
        Map<String, Game> out = new LinkedHashMap<>();
        for (Game g : games) {
            out.put(g.id(), g);
        }
        return out;
    }

    // ---- framework internals ------------------------------------------------------------------

    GamesHost host() {
        return host;
    }

    /** The spec a game was built from, by id (any case), or {@code null}. */
    GameSpec<?> spec(String id) {
        if (id == null) {
            return null;
        }
        String k = id.trim().toLowerCase(Locale.ROOT);
        for (GameSpec<?> s : specs) {
            if (s.id().equals(k)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Tell a player something that happened while they weren't looking (a round finished for
     * them, a Race Night prize waiting, a Weekly Cup paid out or called off): now if they are online
     * and {@code queue} is false, else kept for their next join. {@code line} may carry colour codes
     * ({@code &e...}). Public since EVENTS-DROPPER-SPEC C1, for the games outside this package.
     */
    public void notice(UUID player, String line, boolean queue) {
        Player p = queue ? null : host.online(player);
        if (p != null) {
            p.sendMessage(Text.of(line));
            return;
        }
        try {
            dao().setPref(player, ChanceRounds.NOTICE + host.clock().nowMillis() + "."
                    + Integer.toHexString(line.hashCode()), line);
        } catch (SQLException e) {
            host.logger().log(Level.WARNING, "Could not keep a message for a player's next join", e);
        }
    }

    /** Send, then forget, every line kept for the player. */
    void deliverNotices(Player player) {
        try {
            Map<String, String> waiting = dao().prefsLike(player.getUniqueId(), ChanceRounds.NOTICE);
            for (Map.Entry<String, String> e : waiting.entrySet()) {
                if (e.getValue() != null && !e.getValue().isBlank()) {
                    player.sendMessage(Text.of(e.getValue()));
                }
                dao().deletePref(player.getUniqueId(), e.getKey());
            }
        } catch (SQLException e) {
            host.logger().log(Level.WARNING, "Could not deliver a player's waiting messages", e);
        }
    }

    /** The one-minute sweep: stale rounds, lapsed invites. Never throws. */
    void sweep() {
        quietly(rounds::sweep);
        quietly(invites::expireLapsed);
    }

    /** Build every game not built yet, in catalog order; a constructor that throws leaves that game out. */
    private void build() {
        Map<String, Game> have = new HashMap<>();
        for (Game g : games) {
            have.put(g.id(), g);
        }
        List<Game> built = new ArrayList<>();
        for (GameSpec<?> spec : specs) {
            Game g = have.get(spec.id());
            if (g == null) {
                try {
                    g = spec.create().apply(context);
                    if (g == null || !spec.id().equals(g.id())) {
                        throw new IllegalStateException("its constructor made " + (g == null ? "nothing" : g.id()));
                    }
                    unbuilt.remove(spec.id());
                } catch (RuntimeException | LinkageError e) {
                    unbuilt.put(spec.id(), String.valueOf(e));
                    host.logger().log(Level.SEVERE, "The game " + spec.id() + " could not be built - it is off", e);
                    continue;
                }
            }
            built.add(g);
        }
        byName.clear();
        for (Game g : built) {
            byName.putIfAbsent(g.id().toLowerCase(Locale.ROOT), g);
        }
        for (Game g : built) {
            for (String alias : guard(g, g::aliases, List.<String>of())) {
                if (alias != null) {
                    byName.putIfAbsent(alias.trim().toLowerCase(Locale.ROOT), g);
                }
            }
        }
        this.games = Collections.unmodifiableList(built);
    }

    private void startGame(Game game) {
        if (running.add(game.id())) {
            guard(game, game::start);
        }
    }

    /** Remove everything {@code game} registered through {@link #on}/{@link #every}/{@link #later}, then stop it. */
    private void stopGame(Game game) {
        Listener owner = listeners.remove(game.id());
        if (owner != null) {
            quietly(() -> HandlerList.unregisterAll(owner));
        }
        List<BukkitTask> list = tasks.remove(game.id());
        if (list != null) {
            for (BukkitTask t : list) {
                quietly(t::cancel);
            }
        }
        if (running.remove(game.id())) {
            guard(game, game::stop);
        }
    }

    /**
     * A game closed (it failed, or a reload switched it off): close its screens, end its world
     * sessions (GAME_OFF), finish its OPEN rounds by its exit rule and cancel its invites. Each
     * step on its own, so one that fails can't keep the others from running.
     */
    private void switchOff(Game game, Refusal why) {
        String id = game.id();
        closeScreensOf(game, why);
        for (Player p : online()) {
            quietly(() -> {
                Session s = sessions.session(p);
                if (s != null && id.equals(s.gameId())) {
                    sessions.leave(p, EndReason.GAME_OFF);
                    tell(p, why);
                }
            });
        }
        quietly(() -> rounds.settleGame(id));
        quietly(() -> invites.cancelGame(id));
    }

    /** Close every screen of {@code game}, telling each player {@code why}. */
    private void closeScreensOf(Game game, Refusal why) {
        for (Player p : online()) {
            GameMenu menu = menu(p);
            if (menu != null && menu.game() != null && game.id().equals(menu.game().id())) {
                quietly(() -> menu.closeNow(p));
                tell(p, why);
            }
        }
    }

    /** Close every games screen, the shared ones (which belong to no game) included. */
    private void closeScreens() {
        for (Player p : online()) {
            GameMenu menu = menu(p);
            if (menu != null) {
                quietly(() -> menu.closeNow(p));
            }
        }
    }

    private Collection<? extends Player> online() {
        try {
            return new ArrayList<>(host.online());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** The games screen the player has open, or {@code null}. */
    private static GameMenu menu(Player player) {
        try {
            return player.getOpenInventory().getTopInventory().getHolder(false) instanceof GameMenu m ? m : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void cancelSweep() {
        if (sweep != null) {
            quietly(sweep);
            sweep = null;
        }
    }

    private void track(Game game, BukkitTask task) {
        BukkitScheduler scheduler = host.plugin().getServer().getScheduler();
        List<BukkitTask> list = tasks.computeIfAbsent(game.id(), k -> new ArrayList<>());
        list.removeIf(t -> t.isCancelled() || !(scheduler.isQueued(t.getTaskId())
                || scheduler.isCurrentlyRunning(t.getTaskId())));
        list.add(task);
    }

    /** Run framework housekeeping that must never throw out of a listener, a task or a shutdown. */
    private void quietly(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException | LinkageError e) {
            host.logger().log(Level.WARNING, "Games housekeeping failed", e);
        }
    }
}
