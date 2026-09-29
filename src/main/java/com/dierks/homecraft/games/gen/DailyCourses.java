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
import com.dierks.homecraft.games.gen.admin.GenAdmin;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatPlanner;
import com.dierks.homecraft.games.gen.engine.BukkitWorldPort;
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
 * Daily Courses (GEN-SPEC §0, §5.4): parkour in three tiers, Sky Rings, Daily Golf and Tiny Golf,
 * built by the plugin itself every morning — the same for everyone all day, each with its own
 * board, and 1 to 3 stars a day for the weekly Star Chart.
 *
 * <p><b>Why it is a game.</b> Being in {@link com.dierks.homecraft.games.GameCatalog} gives it
 * everything the framework gives a game for free: its {@code games.daily} block parsed like every
 * other (junk closes only it), its guard (anything it throws switches off only Daily Courses — and
 * with it the gate, so no generated course opens that nothing vouches for), its tasks and listeners
 * registered and removed with it, a tile, and a line in {@code /hcm games status}.
 *
 * <p><b>What it is not.</b> The courses themselves are ordinary {@code game_courses} rows that Time
 * Trials and Mini Golf run unchanged; this game only builds them and says, through
 * {@link GamesService#generated()}, which of them may be played ({@link GenService}). Its own screen
 * is Today's Courses; {@code /hcm play daily_parkour} opens the tier picker.
 *
 * <p>Ships off ({@code games.daily.enabled: false}): switching it on and {@code /hcm reload} is all
 * the owner does; the first set is up within a few minutes.
 */
public final class DailyCourses implements Game {

    /** Built: the game follows its config switch. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<DailySettings> SPEC = new GameSpec<>(Slots.DAILY, GameKind.TRIAL,
            DailySettings.KEYS, DailySettings.defaults(), DailySettings::parse, DailyCourses::new, null);

    /** The rules, on the tile and the screen. */
    static final List<String> RULES = List.of(
            "New courses every morning: parkour, Sky Rings and golf.",
            "The same courses for everyone, all day.",
            "Finish for a star, go faster for more!",
            "Fill your Star Chart every week.");

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
        return "Daily Courses";
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
        return RULES;
    }

    /** Never today's pick: its courses are picked on their own. */
    @Override
    public boolean featurable() {
        return false;
    }

    @Override
    public ItemStack tile(Player viewer) {
        List<String> lore = new ArrayList<>();
        for (String line : RULES) {
            lore.add("&7" + line);
        }
        GenService e = engine;
        if (e != null && e.nextChangeAt() > 0) {
            lore.add(GenCopy.newIn(e.nextChangeAt() - games().clock().nowMillis()));
        }
        lore.add("&eClick to see today's courses");
        return Menus.icon(Material.CLOCK, GenCopy.TILE, lore.toArray(new String[0]));
    }

    /** One tile on the Courses tab and one on the Golf tab, both opening Today's Courses. */
    @Override
    public List<GameTile> tiles(Player viewer) {
        ItemStack icon = tile(viewer);
        return List.of(new GameTile(Tab.COURSES, icon, id(), 0), new GameTile(Tab.GOLF, icon.clone(), id(), 1));
    }

    /** Today's Courses. */
    @Override
    public void open(Player player, Runnable back) {
        games().screens().today(player, back);
    }

    /** {@code daily_parkour}: the parkour tier picker. */
    @Override
    public Collection<Playable> playables() {
        return List.of(new Playable(Slots.DAILY_PARKOUR, "Daily Parkour", this, TokenService.Source.GAMES_DAILY, ""));
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

    /** The Star Chart: this week's best total (a name only when the feed may show names). */
    @Override
    public void feed(FeedWriter out) {
        Edition ed = edition();
        long week = ed.weekKey(ed.day(games().clock().nowMillis()));
        GamesDao.ScoreRow r = games().scores().record(GenBoards.GAME, GenBoards.week(week), false);
        out.starChart(LocalDate.ofEpochDay(week).toString(), r == null ? null : r.score(),
                r != null && out.showNames() ? holder(r.player()) : null);
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
                games().generated(null); // only Daily Courses installs one
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

    /** The running engine, or {@code null} while Daily Courses is off. */
    public GenService engine() {
        return engine;
    }

    /** The course day's rules: {@code clock.time_zone}, {@code rollover}, {@code quests.week_starts_on}. */
    public Edition edition() {
        return new Edition(games().clock().zone(), settings().rollover(), weekStart());
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
    }
}
