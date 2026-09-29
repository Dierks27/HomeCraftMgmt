package com.dierks.homecraft.command;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.arena.ArenaRegions;
import com.dierks.homecraft.games.arena.ArenaService;
import com.dierks.homecraft.games.arena.FallingFloors;
import com.dierks.homecraft.games.arena.FallingFloorsSettings;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.BukkitWorldPort;
import com.dierks.homecraft.games.gen.engine.GenService;
import com.dierks.homecraft.games.gen.engine.Regions;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenMetaDao;
import com.dierks.homecraft.web.ArcadeFeed;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The live server's facts for {@link GamesCheck}: the config, the worlds, Multiverse, the Fresh
 * Courses engine, the course rows and the website feed, each read and nothing written.
 *
 * <p>Multiverse is a soft dependency and is only ever read: a world's game mode through its API by
 * reflection (Multiverse-Core 5, then 4), and Multiverse-Inventories' groups and game-mode setting from
 * its own files. Whatever can't be read comes back as "can't tell", and the check says what to look
 * at by hand. The website's {@code /api/arcade} is built in memory the way the dashboard builds it
 * (every open game's entries, the Scratch Ticket, prizes, packs, achievements), with each game's
 * {@code feed} called directly, not through the games' guard, so a feed that throws is reported
 * instead of switching its game off.
 */
final class GamesCheckLive implements GamesCheck.Facts {

    private final HomeCraftManagement plugin;

    GamesCheckLive(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    private GamesService games() {
        return plugin.games();
    }

    private GamesConfig.Parsed config() {
        GamesService g = games();
        return g != null ? g.config() : plugin.config().games();
    }

    @Override
    public boolean serviceUp() {
        return games() != null;
    }

    @Override
    public boolean gamesEnabled() {
        GamesConfig.Parsed c = config();
        return c != null && c.enabled();
    }

    @Override
    public List<String> economyWorlds() {
        PluginConfig.Worlds w = plugin.config().worlds();
        return w == null ? List.of() : w.economyEnabled();
    }

    @Override
    public List<String> gamesWorlds() {
        GamesConfig.Parsed c = config();
        return c == null ? List.of() : c.common().worlds();
    }

    @Override
    public boolean worldLoaded(String world) {
        return world != null && Bukkit.getWorld(world) != null;
    }

    @Override
    public String gameMode(String world) {
        Plugin mv = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        if (mv == null || !mv.isEnabled()) {
            return null;
        }
        // Multiverse-Core 5: MultiverseCoreApi.get().getWorldManager().getWorld(name) -> Option<MultiverseWorld>.
        try {
            Class<?> api = Class.forName("org.mvplugins.multiverse.core.MultiverseCoreApi", true,
                    mv.getClass().getClassLoader());
            Object core = api.getMethod("get").invoke(null);
            Object worlds = call(core, "getWorldManager");
            Object mode = call(unwrap(call(worlds, "getWorld", world)), "getGameMode");
            if (mode != null) {
                return String.valueOf(mode);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // not Multiverse-Core 5
        }
        // Multiverse-Core 4: getMVWorldManager().getMVWorld(name).getGameMode().
        try {
            Object mode = call(call(call(mv, "getMVWorldManager"), "getMVWorld", world), "getGameMode");
            return mode == null ? null : String.valueOf(mode);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    @Override
    public GamesCheck.MvInv mvInventories() {
        Plugin inv = Bukkit.getPluginManager().getPlugin("Multiverse-Inventories");
        if (inv == null) {
            return null;
        }
        File folder = inv.getDataFolder();
        return GamesCheck.readMvInv(yaml(new File(folder, "groups.yml")), yaml(new File(folder, "config.yml")));
    }

    @Override
    public List<Object> restartTimes() {
        return GamesCheck.restartTimes(plugin.getConfig()); // the owner's value, never the jar's in its place
    }

    @Override
    public String restartStatus() {
        long now = plugin.clock().nowMillis();
        GamesService g = games();
        if (g != null) {
            return g.restartHold().status(now);
        }
        GamesConfig.Common c = config().common();
        return new RestartHold(c.restartTimes(), plugin.clock().zone(), c.restartHoldMinutes()).status(now);
    }

    @Override
    public GamesCheck.Fresh fresh() {
        GamesConfig.Parsed cfg = config();
        DailySettings st = cfg.settings(DailyCourses.SPEC);
        ZoneId zone = plugin.clock().zone();
        Edition ed = st.edition(zone, weekStart());
        String world = st.world().isBlank() ? (cfg.common().worlds().isEmpty() ? "" : cfg.common().worlds().get(0))
                : st.world();
        boolean listed = cfg.common().worlds().stream().anyMatch(w -> w.equalsIgnoreCase(world));
        World w = world.isBlank() ? null : Bukkit.getWorld(world);
        GenService engine = engine();
        long now = plugin.clock().nowMillis();

        List<DailySettings.SlotConfig> all = new ArrayList<>(st.slots());
        all.addAll(st.archive().classics());
        List<DailySettings.SlotConfig> used = new ArrayList<>();
        for (DailySettings.SlotConfig c : all) {
            if (c.enabled() || Slots.isClassic(c.id())) {
                used.add(c);
            }
        }
        List<Regions.Area> built = Regions.handBuilt(rows());
        Regions.WorldFacts facts = w == null ? null : new Regions.WorldFacts(w.getName(), true,
                w.getMinHeight(), w.getMaxHeight(), new BukkitWorldPort(plugin, w).border(),
                new BukkitWorldPort(plugin, w).spawn(), st.safeSpot());
        List<GamesCheck.Region> regions = new ArrayList<>();
        List<Regions.Extra> arenaBox = DailyCourses.arenaExtras(cfg.settings(FallingFloors.SPEC)); // WP-D fix
        for (DailySettings.SlotConfig c : all) {
            Slots.Def def = Slots.any(c.id());
            if (def == null) {
                continue;
            }
            List<String> problems = new ArrayList<>();
            if (facts != null) {
                problems.addAll(Regions.worldProblems(def, c.origin(), facts));
            }
            String apart = Regions.apartProblem(c, used);
            if (apart != null) {
                problems.add(apart);
            }
            String near = Regions.handBuiltProblem(def, c.origin(), world, built);
            if (near != null) {
                problems.add(near);
            }
            String arena = Regions.extrasProblem(def, c.origin(), arenaBox); // WP-D fix: the arena's extra box
            if (arena != null) {
                problems.add(arena);
            }
            regions.add(new GamesCheck.Region(def.id(), GenCopy.slotName(def, st.cadenceDays()), Slots.isClassic(def.id()),
                    c.enabled(), problems, Regions.describe(def, c.origin())));
        }

        List<GamesCheck.SlotFact> slots = null;
        String next = null;
        if (engine != null) {
            slots = new ArrayList<>();
            for (GenService.SlotReport r : engine.report()) {
                Slots.Def def = Slots.any(r.id());
                String holds = null;
                if (r.classic() && r.current() && r.live() != null) {
                    GenTag t = r.live();
                    Slots.Def orig = Slots.of(t.slot());
                    String code = engine.code(t);
                    holds = (code == null ? "" : code + " ") + "(" + (orig == null ? t.slot() : orig.name()) + ", "
                            + GenCopy.editionDates(t.cadence(), t.day()) + ")";
                }
                slots.add(new GamesCheck.SlotFact(r.id(), def == null ? r.id() : GenCopy.slotName(def, st.cadenceDays()),
                        r.classic(), r.wanted(), r.problem(), r.claimed(), r.live() != null, r.current(), r.building(),
                        r.lastError(), r.healFailed(), holds));
            }
            long at = engine.nextChangeAt();
            if (at > 0) {
                next = GenCopy.whenDated(at, zone) + " (in " + GenCopy.span(at - now) + ")";
            }
        } else if (st.enabled()) {
            long at = ed.nextChangeAt(now);
            next = GenCopy.whenDated(at, zone) + " (in " + GenCopy.span(at - now) + ")";
        }
        return new GamesCheck.Fresh(st.enabled() && cfg.enabled(), st.cadenceName(),
                GenCopy.schedule(ed.cadenceDays(), ed.rebuildDay(), null), world, w != null, listed, regions, slots,
                next, st.archive().keepProblem() != null ? st.archive().keepProblem()
                : Regions.keepExtrasProblem(st.archive().keep(), arenaBox), // WP-D fix: or it crowds the arena
                st.archive().keep().describe() + ", " + st.archive().keep().maxPlots() + " plots");
    }

    // ---- Falling Floors (EVENTS-DROPPER-SPEC §C.2 WP-F) ----

    /** Falling Floors from its config, the world, the claim and the running arena; read-only. */
    @Override
    public ArenaCheck.Facts arena() {
        GamesConfig.Parsed cfg = config();
        FallingFloorsSettings ff = cfg.settings(FallingFloors.SPEC);
        DailySettings st = cfg.settings(DailyCourses.SPEC);
        String world = st.world().isBlank() ? (cfg.common().worlds().isEmpty() ? "" : cfg.common().worlds().get(0))
                : st.world();
        World w = world.isBlank() ? null : Bukkit.getWorld(world);
        Box box = ff.box();
        List<String> problems = List.of();
        if (w != null) {
            BukkitWorldPort port = new BukkitWorldPort(plugin, w);
            boolean listed = cfg.common().worlds().stream().anyMatch(x -> x.equalsIgnoreCase(w.getName()));
            problems = ArenaRegions.problems(box, st, Regions.handBuilt(rows()), new Regions.WorldFacts(w.getName(),
                    listed, w.getMinHeight(), w.getMaxHeight(), port.border(), port.spawn(), st.safeSpot()));
        }
        ArenaCheck.Claim claim;
        try {
            String c = new GenMetaDao(plugin.database()).get(ArenaService.CLAIM_KEY);
            claim = c == null ? ArenaCheck.Claim.UNCLAIMED
                    : c.equals(ArenaService.claimText(world, box)) ? ArenaCheck.Claim.CLAIMED : ArenaCheck.Claim.MOVED;
        } catch (SQLException | RuntimeException e) {
            claim = ArenaCheck.Claim.UNKNOWN;
        }
        GamesService g = games();
        Game game = g == null ? null : g.game(FallingFloors.SPEC.id());
        FallingFloors running = game instanceof FallingFloors f && f.running() ? f : null;
        return new ArenaCheck.Facts(cfg.enabled() && ff.enabled(), world, w != null, box.describe(), problems, claim,
                running == null ? null : running.closedWhy(), running != null && running.ready());
    }

    // ---- end Falling Floors ----

    @Override
    public GamesCheck.RaceNight raceNight() {
        GamesService g = games();
        if (g == null || !(g.game(com.dierks.homecraft.games.event.RaceNight.SPEC.id())
                instanceof com.dierks.homecraft.games.event.RaceNight r)) {
            return null;
        }
        List<GamesCheck.RaceNightLine> lines = new ArrayList<>();
        for (com.dierks.homecraft.games.event.RaceNight.Check c : g.guard(r, r::check,
                List.<com.dierks.homecraft.games.event.RaceNight.Check>of())) {
            lines.add(new GamesCheck.RaceNightLine(c.what(), c.fix()));
        }
        return new GamesCheck.RaceNight(r.switchedOn(), lines);
    }

    @Override
    public List<GamesCheck.Course> courses() {
        List<String> worlds = gamesWorlds();
        List<GamesCheck.Course> out = new ArrayList<>();
        for (GamesDao.CourseRow row : rows()) {
            if (Regions.hasGen(row.data())) {
                continue; // Fresh Courses' own: checked above
            }
            if (Slots.GAME_GOLF.equals(row.game())) {
                com.dierks.homecraft.games.golf.GolfCourse g;
                try {
                    g = com.dierks.homecraft.games.golf.CourseCodec.fromRow(row);
                } catch (RuntimeException e) {
                    out.add(new GamesCheck.Course(row.id(), true, row.world(), row.enabled(), worldLoaded(row.world()),
                            List.of("it can't be read (" + e.getMessage() + ")")));
                    continue;
                }
                out.add(new GamesCheck.Course(g.id(), true, g.world(), row.enabled(), worldLoaded(g.world()),
                        g.problems(worlds)));
            } else if (Slots.GAME_TRIALS.equals(row.game())) {
                com.dierks.homecraft.games.trial.Course c = null;
                try {
                    c = com.dierks.homecraft.games.trial.CourseCodec.decode(row.id(), row.data()).course();
                } catch (RuntimeException ignored) {
                    // reported below
                }
                if (c == null) {
                    out.add(new GamesCheck.Course(row.id(), false, row.world(), row.enabled(), worldLoaded(row.world()),
                            List.of("it can't be read")));
                    continue;
                }
                out.add(new GamesCheck.Course(c.id(), false, c.world(), row.enabled(), worldLoaded(c.world()),
                        c.problems(worlds)));
            }
        }
        return out;
    }

    @Override
    public GamesCheck.Web web() {
        PluginConfig.WebDashboard dash = plugin.config().webDashboard();
        if (dash == null || !dash.enabled()) {
            return new GamesCheck.Web(false, false, null, 0, 0);
        }
        boolean token = dash.feedToken() != null && !dash.feedToken().isBlank();
        long now = plugin.clock().nowMillis();
        GamesService g = games();
        // as /api/arcade builds it, top lists included (MarketDashboardServer.arcadeJson)
        ArcadeFeed feed = new ArcadeFeed(plugin.getConfig().getBoolean("web.dashboard.arcade_show_names", false),
                g == null ? 0 : g.config().common().feedTop(),
                g == null ? null : com.dierks.homecraft.web.MarketDashboardServer.topBoards(g));
        String error = null;
        if (g != null && g.config().enabled()) {
            for (Game game : g.games()) {
                if (!g.enabled(game)) {
                    continue;
                }
                try {
                    game.feed(feed); // directly, not through the guard: a failure is reported, the game stays open
                } catch (RuntimeException | LinkageError e) {
                    error = game.id() + "'s entry failed (" + e + ")";
                    break;
                }
            }
        }
        if (error != null) {
            return new GamesCheck.Web(true, token, error, feed.size(), 0);
        }
        try {
            PluginConfig.Arcade arcade = plugin.config().arcade();
            String json;
            if (arcade == null || !arcade.enabled()) {
                json = feed.json(now, null, null, null, null, null);
            } else {
                ArcadeFeed.Scratch scratch = plugin.arcade() == null ? null
                        : ArcadeFeed.scratch(arcade.lotto(), plugin.arcade().pot());
                List<ArcadeFeed.PrizeRow> prizes = List.of();
                if (plugin.prizes() != null) {
                    List<PluginConfig.Prize> visible = new ArrayList<>();
                    for (PluginConfig.PrizeTab tab : PluginConfig.PrizeTab.values()) {
                        for (PluginConfig.Prize p : plugin.prizes().visible(tab)) {
                            // the +1 Home is only ever offered through the homes service, as on /api/arcade
                            if (p.type() != PluginConfig.PrizeType.HOME_SLOT || plugin.homes() != null) {
                                visible.add(p);
                            }
                        }
                    }
                    prizes = ArcadeFeed.prizes(visible);
                }
                List<ArcadeFeed.PackRow> packs = List.of();
                MiniService minis = plugin.miniService();
                if (plugin.packs() != null) {
                    packs = ArcadeFeed.packs(plugin.packs().packs(), id -> {
                        MiniDef def = minis == null ? null : minis.def(id);
                        return def == null ? null : def.rarity();
                    });
                }
                List<ArcadeFeed.AchievementRow> achievements = plugin.achievements() == null ? List.of()
                        : ArcadeFeed.achievements(plugin.achievements().all());
                json = feed.json(now, null, scratch, prizes, packs, achievements);
            }
            return new GamesCheck.Web(true, token, null, feed.size(), json.getBytes(StandardCharsets.UTF_8).length);
        } catch (RuntimeException | LinkageError e) {
            return new GamesCheck.Web(true, token, e.toString(), feed.size(), 0);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private GenService engine() {
        GamesService g = games();
        if (g == null) {
            return null;
        }
        Game game = g.game(Slots.DAILY);
        return game instanceof DailyCourses dc && g.enabled(game) ? dc.engine() : null;
    }

    private DayOfWeek weekStart() {
        PluginConfig.Quests q = plugin.config().quests();
        return q == null || q.weekStartsOn() == null ? DayOfWeek.MONDAY : q.weekStartsOn();
    }

    /** Every course row (time trials and golf), read-only; empty when they can't be read. */
    private List<GamesDao.CourseRow> rows() {
        GamesService g = games();
        GamesDao dao = g != null ? g.dao() : new GamesDao(plugin.database());
        List<GamesDao.CourseRow> out = new ArrayList<>();
        try {
            out.addAll(dao.courses(Slots.GAME_TRIALS));
            out.addAll(dao.courses(Slots.GAME_GOLF));
        } catch (SQLException e) {
            throw new IllegalStateException("the course rows can't be read: " + e.getMessage(), e);
        }
        return out;
    }

    /** A YAML file, or {@code null} when it is missing or can't be read. */
    private static YamlConfiguration yaml(File file) {
        if (!file.isFile()) {
            return null;
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(file);
            return y;
        } catch (Exception e) {
            return null;
        }
    }

    /** A public no-arg or one-arg method by name, called; {@code null} target gives {@code null}. */
    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length && accepts(m, args)) {
                m.setAccessible(true);
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    /** Whether {@code m} takes these arguments ({@code getWorld(String)}, not {@code getWorld(World)}). */
    private static boolean accepts(Method m, Object[] args) {
        Class<?>[] types = m.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            if (args[i] != null && !types[i].isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    /** An {@link Optional}, a vavr {@code Option}, or a plain value, unwrapped. */
    private static Object unwrap(Object o) throws ReflectiveOperationException {
        if (o instanceof Optional<?> opt) {
            return opt.orElse(null);
        }
        if (o != null && o.getClass().getName().startsWith("io.vavr.")) {
            return call(o, "getOrNull");
        }
        return o;
    }
}
