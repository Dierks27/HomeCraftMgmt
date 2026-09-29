package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.gen.admin.GenAdmin;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatPlanner;
import com.dierks.homecraft.games.gen.engine.BukkitWorldPort;
import com.dierks.homecraft.games.gen.engine.FreshFeed;
import com.dierks.homecraft.games.gen.engine.GenHost;
import com.dierks.homecraft.games.gen.engine.GenRegionGuard;
import com.dierks.homecraft.games.gen.engine.GenService;
import com.dierks.homecraft.games.gen.engine.GenStore;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.PlannerThread;
import com.dierks.homecraft.games.gen.engine.WorldPort;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import com.dierks.homecraft.games.gen.parkour.ParkourPlanner;
import com.dierks.homecraft.games.gen.rings.RingsPlanner;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Fresh Courses (GEN-SPEC §0, §5.4 and the weekly addendum): parkour in three tiers, Sky Rings, the
 * big golf course and Tiny Golf, built by the plugin itself — a new set every week by default
 * ({@code games.fresh.cadence}: weekly, daily, or every few days), the same for everyone, each with
 * its own board, and 1 to 3 stars per course per set for the weekly Star Chart.
 *
 * <p><b>Why it is a game.</b> Being in {@link com.dierks.homecraft.games.GameCatalog} gives it
 * everything the framework gives a game for free: its {@code games.fresh} block parsed like every
 * other (junk closes only it), its guard (anything it throws switches off only Fresh Courses — and
 * with it the gate, so no generated course opens that nothing vouches for), its tasks and listeners
 * registered and removed with it, a tile, and a line in {@code /hcm games status}. Its id is
 * {@code fresh_courses}; its settings block is {@code games.fresh}.
 *
 * <p><b>What it is not.</b> The courses themselves are ordinary {@code game_courses} rows that Time
 * Trials and Mini Golf run unchanged; this game only builds them and says, through
 * {@link GamesService#generated()}, which of them may be played ({@link GenService}). Its own screen
 * lists the current courses; {@code /hcm play fresh_parkour_tiers} opens the parkour level picker.
 *
 * <p><b>The words follow the cadence.</b> Its tile and rules say "every Monday", "every day" or
 * "every 3 days" as configured ({@link GenCopy}); nothing here says "daily" or "today" on its own.
 *
 * <p>Ships off ({@code games.fresh.enabled: false}): switching it on and {@code /hcm reload} is all
 * the owner does; the first set is up within a few minutes.
 */
public final class DailyCourses implements Game {

    /** Built: the game follows its config switch. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<DailySettings> SPEC = new GameSpec<>(Slots.DAILY, GameKind.TRIAL,
            DailySettings.KEYS, DailySettings.defaults(), DailySettings::parse, DailyCourses::new, null);

    /** The rules at the shipped (weekly) cadence, on the tile and the screen ({@link #rules(int, DayOfWeek)}). */
    static final List<String> RULES = rules(Edition.DEFAULT_CADENCE, DayOfWeek.MONDAY);

    private final GameContext ctx;
    private final GenAdmin admin;
    private GenService engine;
    private PlannerThread planner;

    public DailyCourses(GameContext ctx) {
        this.ctx = ctx;
        this.admin = new GenAdmin(() -> engine, log());
    }

    // ---- the Game ---------------------------------------------------------------------------------

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
        return GenCopy.NAME;
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_DAILY;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        Edition ed = edition();
        return rules(ed.cadenceDays(), ed.rebuildDay());
    }

    /** The rules for an N-day cadence starting on {@code rebuildDay}: when they change comes first. */
    static List<String> rules(int cadence, DayOfWeek rebuildDay) {
        return List.of(
                GenCopy.schedule(cadence, rebuildDay, null) + ": parkour, Sky Rings and golf.",
                "The same courses for everyone.",
                "Finish for a star, go faster for more!",
                "Fill your Star Chart every week.");
    }

    /** Never today's pick: its courses are picked on their own. */
    @Override
    public boolean featurable() {
        return false;
    }

    @Override
    public ItemStack tile(Player viewer) {
        Edition ed = edition();
        List<String> lore = new ArrayList<>();
        for (String line : rules(ed.cadenceDays(), ed.rebuildDay())) {
            lore.add("&7" + line);
        }
        GenService e = engine;
        if (e != null && e.nextChangeAt() > 0) {
            lore.add(GenCopy.newIn(e.nextChangeAt() - games().clock().nowMillis()));
        }
        lore.add("&eClick to see " + GenCopy.current(ed.cadenceDays()).toLowerCase(java.util.Locale.ROOT));
        return Menus.icon(Material.CLOCK, GenCopy.tile(ed.cadenceDays(), ed.rebuildDay()),
                lore.toArray(new String[0]));
    }

    /** One tile on the Courses tab and one on the Golf tab, both opening the Fresh Courses screen. */
    @Override
    public List<GameTile> tiles(Player viewer) {
        ItemStack icon = tile(viewer);
        return List.of(new GameTile(Tab.COURSES, icon, id(), 0), new GameTile(Tab.GOLF, icon.clone(), id(), 1));
    }

    /** The Fresh Courses screen (the current set). */
    @Override
    public void open(Player player, Runnable back) {
        games().screens().today(player, back);
    }

    /** {@code fresh_parkour_tiers}: the parkour level picker. */
    @Override
    public Collection<Playable> playables() {
        return List.of(new Playable(Slots.DAILY_PARKOUR, "Parkour Levels", this, TokenService.Source.GAMES_DAILY, ""));
    }

    @Override
    public boolean play(Player player, String playableId, Runnable back) {
        if (!Slots.DAILY_PARKOUR.equalsIgnoreCase(playableId == null ? "" : playableId.trim())) {
            return false;
        }
        games().screens().parkourTiers(player, back);
        return true;
    }

    @Override
    public GameAdmin admin() {
        return admin;
    }

    /**
     * The Star Chart (this week's best total, a name only when the feed may show names), and what
     * the archive says (GEN-SPEC-KEEP §8): each live course's {@code fresh} object (its course code
     * and short seed), each Classics slot's {@code classic} window, and {@code freshHistory} for a
     * writer that publishes it ({@link FeedWriter#wantsHistory}: the website's, not a screen's). The
     * courses' own entries are written by Time Trials and Mini Golf; the feed puts these with them.
     */
    @Override
    public void feed(FeedWriter out) {
        Edition ed = edition();
        long week = ed.weekKey(ed.day(games().clock().nowMillis()));
        GamesDao.ScoreRow r = games().scores().record(GenBoards.GAME, GenBoards.week(week), false);
        out.starChart(LocalDate.ofEpochDay(week).toString(), r == null ? null : r.score(),
                r != null && out.showNames() ? holder(r.player()) : null);
        GenService e = engine;
        if (e == null) {
            return;
        }
        for (Slots.Def d : Slots.ALL) {
            FreshFeed.Fresh f = e.fresh(d.id());
            if (f != null) {
                out.fresh(d.id(), f);
            }
        }
        for (Slots.Def d : Slots.CLASSICS) {
            FreshFeed.Classic c = e.classic(d.id());
            if (c != null) {
                out.classic(d.id(), c);
            }
        }
        if (out.wantsHistory()) { // only the website's feed: the archive costs two queries a course
            out.freshHistory(e.freshHistory(out.showNames()));
        }
    }

    @Override
    public List<String> statusLines() {
        GenService e = engine;
        return e == null ? List.of("not running") : e.summary();
    }

    /**
     * Start the engine: it reads every live course (all closed until checked), installs itself as
     * the gate, keeps the areas, and checks every live half a tick from now, when the worlds are up.
     */
    @Override
    public void start() {
        stopEngine();
        planner = new PlannerThread();
        engine = new GenService(new LiveHost(planner), planners());
        engine.start();
        GamesService g = games();
        g.generated(engine);
        GenService running = engine;
        GenRegionGuard.register(g, this, () -> running::inArea, log());
        g.every(this, 1, 1, running::tick);
        g.every(this, 20, 20, running::check);
        g.later(this, 1, running::worldsReady);
        // "New courses this week!" once per player per set (EXTRAS E2), on the same one-second beat.
        NewCoursesNudge nudge = NewCoursesNudge.live(g, () -> running, this::settings);
        g.every(this, 20, 20, nudge::tickSafely);
    }

    /** Stop the engine; the gate goes with it (the framework already hands out NONE once closed). */
    @Override
    public void stop() {
        stopEngine();
    }

    private void stopEngine() {
        if (engine != null) {
            GenService e = engine;
            engine = null;
            try {
                e.stop();
            } finally {
                games().generated(null); // only Fresh Courses installs one
            }
        }
        if (planner != null) {
            planner.shutdown();
            planner = null;
        }
    }

    // ---- for the course engines and the screens (WP4) ---------------------------------------------

    /** The live settings (read on every use). */
    public DailySettings settings() {
        return ctx.games().settings(SPEC);
    }

    /** The running engine, or {@code null} while Fresh Courses is off. */
    public GenService engine() {
        return engine;
    }

    /**
     * The schedule: {@code clock.time_zone}, {@code games.fresh.cadence}, {@code rebuild_at},
     * {@code rebuild_day} (the quests' week start when empty), and the quests' week.
     */
    public Edition edition() {
        return settings().edition(games().clock().zone(), weekStart());
    }

    /**
     * Tokens for the first counted finish of {@code slotId} in an edition, at the configured cadence
     * ({@code games.fresh.rewards}: between the daily and weekly tables).
     */
    public int dailyClear(String slotId) {
        GenService e = engine;
        return e != null ? e.dailyClear(slotId) : settings().dailyClear(slotId);
    }

    /**
     * Tokens for the first counted finish of {@code slotId} in an edition of {@code cadence} days:
     * what a finish pays, with the run's own {@code tag.cadence()}, so a layout kept over a cadence
     * change pays by the edition it is.
     */
    public int dailyClear(String slotId, int cadence) {
        GenService e = engine;
        return e != null ? e.dailyClear(slotId, cadence) : settings().dailyClear(slotId, cadence);
    }

    /**
     * The Star Chart goals of the week starting {@code weekKey}: the cadence's goals and tokens
     * ({@code star_goals}), never above 80% of what the week can give, and fixed for the week once
     * the engine has handed them out ({@link GenService#goals}): pay and show only these. With the
     * engine off they are worked out from the settings (nothing generated can be finished then).
     */
    public List<DailyStars.Goal> goals(long weekKey) {
        GenService e = engine;
        if (e != null) {
            return e.goals(weekKey);
        }
        DailySettings st = settings();
        int on = 0;
        for (DailySettings.SlotConfig c : st.slots()) {
            on += c.enabled() ? 1 : 0;
        }
        return st.starGoals(DailyStars.weekMax(on, Math.max(1, edition().startsInWeek(weekKey))));
    }

    /**
     * Pay the weekly Star Chart goals a counted run has reached (each goal's own tokens, once a week
     * each, all or nothing under {@code daily_cap}): the course engines call this with what
     * {@code GamesDao.addStars} returned. Every goal the week's total has reached is offered
     * ({@link DailyStars#reached}), so a goal the day's caps held back is paid by a later run that
     * week; its once-a-week ref keeps it from being paid twice.
     *
     * @param weekKey the Star Chart week the run counts in
     * @return the goals paid by this run, smallest first
     */
    public List<Integer> starGoals(Player player, GamesDao.StarsAdded added, long weekKey) {
        if (added == null) {
            return List.of();
        }
        DailySettings st = settings();
        List<DailyStars.Goal> goals = goals(weekKey);
        List<Integer> paid = new ArrayList<>();
        for (int goal : DailyStars.reached(added.weekTotal(), DailyStars.stars(goals))) {
            int tokens = DailyStars.tokens(goals, goal);
            if (tokens > 0 && games().rewards().payWhole(player, this, TokenService.Source.GAMES_DAILY,
                    RewardKind.MILESTONE, SkillRewards.milestoneRef(GenBoards.week(weekKey), goal), tokens,
                    st.dailyCap(), "Star Chart: " + goal + " stars this week", GenCopy.GOAL_LIMIT) > 0) {
                paid.add(goal);
            }
        }
        return paid;
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private GamesService games() {
        return ctx.games();
    }

    private HomeCraftManagement plugin() {
        return ctx.plugin();
    }

    private Logger log() {
        return plugin() == null ? Logger.getLogger("HomeCraftMgmt") : plugin().getLogger();
    }

    private DayOfWeek weekStart() {
        try {
            var quests = plugin() == null ? null : plugin().config().quests();
            if (quests != null && quests.weekStartsOn() != null) {
                return quests.weekStartsOn();
            }
        } catch (RuntimeException e) {
            // the default week
        }
        return DayOfWeek.MONDAY;
    }

    private static String holder(UUID player) {
        try {
            OfflinePlayer p = Bukkit.getOfflinePlayer(player);
            return p.getName() == null ? "someone" : p.getName();
        } catch (RuntimeException e) {
            return "someone";
        }
    }

    /** Every generator's planner, by its id. */
    static Map<String, Planner> planners() {
        Map<String, Planner> out = new LinkedHashMap<>();
        for (Planner p : List.<Planner>of(new ParkourPlanner(), new RingsPlanner(), new GolfPlanner(),
                new BoatPlanner())) {
            out.put(p.id(), p);
        }
        return out;
    }

    /** The engine's view of the running server. */
    private final class LiveHost implements GenHost {
        private final PlannerThread thread;
        private final Map<String, BukkitWorldPort> ports = new HashMap<>();
        private final Map<String, World> portWorlds = new HashMap<>();
        private GenStore store;

        LiveHost(PlannerThread thread) {
            this.thread = thread;
        }

        @Override
        public long now() {
            return games().clock().nowMillis();
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public Logger logger() {
            return log();
        }

        @Override
        public DailySettings settings() {
            return DailyCourses.this.settings();
        }

        @Override
        public RestartHold restartHold() {
            return games().restartHold();
        }

        @Override
        public ZoneId zone() {
            return games().clock().zone();
        }

        @Override
        public DayOfWeek weekStart() {
            return DailyCourses.this.weekStart();
        }

        @Override
        public List<String> gamesWorlds() {
            return games().config().common().worlds();
        }

        @Override
        public int fallDepth() {
            return games().settings(TimeTrials.SPEC).fallDepth();
        }

        @Override
        public WorldPort world(String name) {
            World w = name == null || name.isBlank() ? null : Bukkit.getWorld(name);
            if (w == null) {
                return null;
            }
            String key = w.getName();
            if (portWorlds.get(key) != w) {
                portWorlds.put(key, w);
                ports.put(key, new BukkitWorldPort(plugin(), w));
            }
            return ports.get(key);
        }

        @Override
        public GenStore store() {
            if (store == null) {
                store = GenStore.of(plugin().database());
            }
            return store;
        }

        @Override
        public Executor planner() {
            return thread;
        }

        @Override
        public void coursesChanged(String gameId) {
            games().coursesChanged(gameId);
        }

        @Override
        public List<Person> people() {
            List<Person> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                Location l = p.getLocation();
                Session s = games().sessions().session(p);
                out.add(new Person(p.getUniqueId(), p.getName(), l.getWorld() == null ? "" : l.getWorld().getName(),
                        l.getX(), l.getY(), l.getZ(), s == null ? null : s.gameId(), s == null ? null : s.ref()));
            }
            return out;
        }

        @Override
        public boolean anyoneOnline() {
            return !Bukkit.getOnlinePlayers().isEmpty();
        }

        @Override
        public double mspt() {
            return Bukkit.getAverageTickTime();
        }

        @Override
        public void tell(UUID player, String line) {
            Player p = Bukkit.getPlayer(player);
            if (p != null) {
                p.sendMessage(Text.of(line));
            }
        }

        @Override
        public void actionBar(UUID player, String line) {
            Player p = Bukkit.getPlayer(player);
            if (p != null) {
                p.sendActionBar(Text.of(line));
            }
        }

        @Override
        public void endRun(UUID player) {
            Player p = Bukkit.getPlayer(player);
            if (p != null && games().sessions().session(p) != null) {
                games().sessions().leave(p, EndReason.ADMIN);
            }
        }

        @Override
        public void move(UUID player, String world, double x, double y, double z) {
            Player p = Bukkit.getPlayer(player);
            World w = Bukkit.getWorld(world);
            if (p != null && w != null) {
                Location at = p.getLocation();
                p.teleport(new Location(w, x, y, z, at.getYaw(), at.getPitch()));
            }
        }

        @Override
        public boolean playIdTaken(String id) {
            return games().game(id) != null || games().resolve(id) != null;
        }

        @Override
        public String playerName(UUID player) {
            return holder(player);
        }
    }
}
