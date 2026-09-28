package com.dierks.homecraft.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.world.WorldSessions;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

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
 * false, because the games themselves are only one of the things it looks after: Take a break
 * guards the Scratch Ticket and Crates too, and a player's saved things must come back whatever
 * the switches say.
 *
 * <p><b>Failure isolation.</b> Everything a game does runs inside {@link #guard}: an exception is
 * logged once (SEVERE, with the game id), that one game is switched off until {@code /hcm reload},
 * and the player sees "That game is taking a break. Try another one!". Nothing a game throws can
 * reach Bukkit, another game or the rest of the plugin. The same is why games never register
 * their own listeners or tasks: {@link #on}, {@link #every} and {@link #later} register them for
 * the game, skip them while it is closed, run them inside its guard, and remove them when it
 * stops.
 */
public final class GamesService {

    /** What {@code /hcm play <id>} names: a game, or a course inside one ({@code playable} non-null). */
    public record Target(Game game, Game.Playable playable) {
    }

    private final HomeCraftManagement plugin;
    private final Breaks breaks;
    private final GamesDao dao;
    private final GameContext context;
    private final PlayGate gate;
    private final ChanceRounds rounds;
    private final SkillRewards rewards;
    private final Scores scores;
    private final WorldSessions sessions;
    private final Invites invites;
    private final Featured featured;
    private final List<Game> games;
    /** The shared screens (gui/games); "Coming soon!" until installed. */
    private GamesScreens screens = GamesScreens.NONE;
    /** Ids and aliases, lower-case. */
    private final Map<String, Game> byName = new HashMap<>();
    /** Games that threw: off until {@code /hcm reload}. */
    private final Set<String> failed = new HashSet<>();
    /** Each game's registered handlers hang off one listener object, so they unregister together. */
    private final Map<String, Listener> listeners = new HashMap<>();
    private final Map<String, List<BukkitTask>> tasks = new HashMap<>();

    /**
     * @param breaks Take a break, built earlier and on its own (it also guards the Scratch Ticket
     *               and Crates, R1.15)
     */
    public GamesService(HomeCraftManagement plugin, Breaks breaks) {
        this.plugin = plugin;
        this.breaks = breaks;
        this.dao = new GamesDao(plugin.database());
        this.context = new GameContext(plugin, this);
        this.gate = new PlayGate(this);
        this.rounds = new ChanceRounds(this);
        this.rewards = new SkillRewards(this);
        this.scores = new Scores(this);
        this.sessions = new WorldSessions(this);
        this.invites = new Invites(this);
        this.featured = new Featured(this);

        List<Game> built = new ArrayList<>();
        for (GameSpec<?> spec : GameCatalog.SPECS) {
            try {
                Game g = spec.create().apply(context);
                built.add(g);
                byName.putIfAbsent(g.id().toLowerCase(Locale.ROOT), g);
                for (String alias : g.aliases()) {
                    byName.putIfAbsent(alias.toLowerCase(Locale.ROOT), g);
                }
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().log(Level.SEVERE, "The game " + spec.id() + " could not be built - it is off", e);
            }
        }
        this.games = Collections.unmodifiableList(built);
    }

    // ---- lifecycle --------------------------------------------------------------------------

    /** Enable: settle what a crash left, start the enabled games. */
    public void start() {
        // F1b: prune DONE saved states; settle OPEN rounds of offline players and stale ones
        // (R3.5); start each enabled game inside guard; the one-minute round sweep (R3.5).
    }

    /** {@code /hcm reload}: re-read config; games that stay open keep running, others stop (R3.12). */
    public void reload() {
        failed.clear();
        // F1b: stop games that became closed (sessions left with GAME_OFF, OPEN rounds settled),
        // start games that became open.
    }

    /** Disable (or games switched off): stop every game and remove what it registered. */
    public void stop() {
        // F1b: close every open GameMenu first; leave every session (in place + RETURN while the
        // server is stopping, R3.12); OPEN rounds stay OPEN during shutdown.
        for (Game g : games) {
            stopGame(g);
        }
    }

    /** A player joined: sessions first (restore/return), then each open game's {@link Game#onJoin}. */
    public void onJoin(Player player) {
        // F1b
    }

    /** A player quit: settle their OPEN rounds, end their session, each open game's {@link Game#onQuit}. */
    public void onQuit(Player player) {
        // F1b
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

    /** The live {@code games:} config (re-read on every reload; never cache it). */
    public GamesConfig.Parsed config() {
        return plugin.config().games();
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
        // F1b: resolve(id) -> unknown/closed: tell CLOSED; canOpen; game.open / game.play in guard.
        tell(player, Refusal.CLOSED);
        return false;
    }

    /** The Games screen. */
    public void openGamesScreen(Player player, Runnable back) {
        // F1b/F2: new GamesMenu(...).open(player).
        tell(player, Refusal.CLOSED);
    }

    /** Gate steps 0-4 (R3.9): may the player open this game? {@code null} = yes. */
    public Refusal canOpen(Player player, Game game) {
        return gate.open(player, game);
    }

    /** The full gate for putting {@code stake} in. {@code null} = go ahead. */
    public Refusal canStake(Player player, Game game, int stake) {
        return gate.check(player, game, stake);
    }

    /** Tell the player why not: a red chat line and the refused sound; nothing at all for the cooldown. */
    public void tell(Player player, Refusal refusal) {
        if (player == null || refusal == null || refusal.silent() || refusal.message().isEmpty()) {
            return;
        }
        player.sendMessage(Text.of("&c" + refusal.message()));
        Sounds.refused(player);
    }

    // ---- listeners, tasks and failure isolation ---------------------------------------------

    /**
     * Register an event handler for {@code game}. It is skipped while the game is closed, runs
     * inside the game's guard, and is removed when the game stops.
     */
    public <E extends Event> void on(Game game, Class<E> type, EventPriority priority, boolean ignoreCancelled,
                                     Consumer<E> handler) {
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

    /** Switch {@code game} off until {@code /hcm reload}, logging why once. */
    public void fail(Game game, Throwable cause) {
        if (game == null) {
            plugin.getLogger().log(Level.SEVERE, "A game failed", cause);
            return;
        }
        if (failed.add(game.id())) {
            plugin.getLogger().log(Level.SEVERE, "The game " + game.id()
                    + " failed and is switched off until /hcm reload", cause);
            // F1b: stop it, leave its live sessions (GAME_OFF), settle its OPEN rounds by the exit
            // rule and close its open screens with "That game is taking a break" (R2.6).
        }
    }

    // ---- the shared helpers -----------------------------------------------------------------

    public HomeCraftManagement plugin() {
        return plugin;
    }

    public GameContext context() {
        return context;
    }

    public GamesDao dao() {
        return dao;
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

    public Featured featured() {
        return featured;
    }

    /** The shared games screens (Games screen, high scores, Take a break, player picker). */
    public GamesScreens screens() {
        return screens;
    }

    /** Install the real screens (done once at enable, after the service is built). */
    public void screens(GamesScreens screens) {
        this.screens = screens == null ? GamesScreens.NONE : screens;
    }

    // ---- internals --------------------------------------------------------------------------

    /** Remove everything {@code game} registered through {@link #on}/{@link #every}/{@link #later}, then stop it. */
    private void stopGame(Game game) {
        Listener owner = listeners.remove(game.id());
        if (owner != null) {
            HandlerList.unregisterAll(owner);
        }
        List<BukkitTask> list = tasks.remove(game.id());
        if (list != null) {
            for (BukkitTask t : list) {
                t.cancel();
            }
        }
        guard(game, game::stop);
    }

    private void track(Game game, BukkitTask task) {
        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        List<BukkitTask> list = tasks.computeIfAbsent(game.id(), k -> new ArrayList<>());
        list.removeIf(t -> t.isCancelled() || !(scheduler.isQueued(t.getTaskId())
                || scheduler.isCurrentlyRunning(t.getTaskId())));
        list.add(task);
    }

    /** Every game's id, for {@code /hcm games status} and tab completion. */
    public Map<String, Game> byId() {
        Map<String, Game> out = new LinkedHashMap<>();
        for (Game g : games) {
            out.put(g.id(), g);
        }
        return out;
    }
}
