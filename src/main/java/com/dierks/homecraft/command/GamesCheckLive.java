package com.dierks.homecraft.command;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.VoidWorld;
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
import com.dierks.homecraft.games.gen.engine.GenAdminKeys;
import com.dierks.homecraft.games.gen.engine.GenService;
import com.dierks.homecraft.games.gen.engine.KeptPlot;
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
        // WP-D fix, and WP-CH: the arena's box and the Clubhouse's, as Fresh Courses' vet and keep see them
        List<Regions.Extra> extraBoxes = DailyCourses.extraBoxes(cfg.settings(FallingFloors.SPEC),
                cfg.settings(com.dierks.homecraft.games.clubhouse.Clubhouse.SPEC));
        for (DailySettings.SlotConfig c : all) {
            Slots.Def def = Slots.any(c.id());
            if (def == null) {
                continue;
            }
            List<String> problems = new ArrayList<>();
            if (facts != null) {
                problems.addAll(Regions.worldProblems(def, c.origin(), c.halfGap(), facts));
            }
            String apart = Regions.apartProblem(c, used);
            if (apart != null) {
                problems.add(apart);
            }
            String near = Regions.handBuiltProblem(def, c.origin(), c.halfGap(), world, built);
            if (near != null) {
                problems.add(near);
            }
            // the arena's or the Clubhouse's box
            String extra = Regions.extrasProblem(def, c.origin(), c.halfGap(), extraBoxes);
            if (extra != null) {
                problems.add(extra);
            }
            regions.add(new GamesCheck.Region(def.id(), GenCopy.slotName(def, st.cadenceDays()), Slots.isClassic(def.id()),
                    c.enabled(), problems, Regions.describe(def, c.origin(), c.halfGap())));
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
                : Regions.keepExtrasProblem(st.archive().keep(), extraBoxes), // or it crowds the arena or the Clubhouse
                st.archive().keep().describe() + ", " + st.archive().keep().maxPlots() + " plots",
                facts == null ? null : Regions.keepWorldProblems(st.archive().keep(), facts)); // each plot fits
    }

    // ---- what players can see (LAYOUT-SPEC §5.1) ----

    /**
     * Every Games world's ground, the view distance the server really uses in the world the games build
     * in (the largest of its view and send distances and every online player's send distance there;
     * the server's view distance when it isn't loaded), and every place the games build or put players
     * there: each Fresh course's and Classic's halves where config puts them (a course that is on or
     * claimed), each kept course's stored plot, the Clubhouse's and Falling Floors' boxes (on or claimed,
     * the Clubhouse not in hand mode), the world's spawn and the safe spot. Read-only.
     */
    @Override
    public SightCheck.Facts sight() {
        GamesConfig.Parsed cfg = config();
        DailySettings st = cfg.settings(DailyCourses.SPEC);
        String world = st.world().isBlank() ? (cfg.common().worlds().isEmpty() ? "" : cfg.common().worlds().get(0))
                : st.world();
        List<SightCheck.Ground> grounds = new ArrayList<>();
        for (String name : cfg.common().worlds()) {
            World gw = Bukkit.getWorld(name);
            grounds.add(new SightCheck.Ground(name, gw != null, gw != null && VoidWorld.ours(gw.getGenerator()),
                    gw == null ? null : footed(gw)));
        }
        World w = world.isBlank() ? null : Bukkit.getWorld(world);
        int view;
        String why;
        if (w == null) {
            view = Bukkit.getViewDistance();
            why = "the server's view distance; " + (world.isBlank() ? "there is no Games world"
                    : world + " isn't loaded");
        } else {
            int players = 0;
            int theirs = 0;
            for (org.bukkit.entity.Player p : w.getPlayers()) {
                players++;
                theirs = Math.max(theirs, p.getSendViewDistance());
            }
            view = Math.max(Math.max(w.getViewDistance(), w.getSendViewDistance()), theirs);
            why = w.getName() + "'s view distance " + w.getViewDistance() + ", its send distance "
                    + w.getSendViewDistance() + (players == 0 ? ", nobody there now"
                    : ", and the " + players + " player" + (players == 1 ? "" : "s") + " there (up to " + theirs + ")");
        }
        return new SightCheck.Facts(world, view, why, world.isBlank() ? List.of() : places(cfg, st, world, w), grounds);
    }

    /** The places of {@link #sight()} in {@code world} ({@code w} when it is loaded). */
    private List<SightCheck.Place> places(GamesConfig.Parsed cfg, DailySettings st, String world, World w) {
        List<SightCheck.Place> out = new ArrayList<>();
        java.util.Map<String, GenService.SlotReport> engine = new java.util.HashMap<>();
        GenService running = engine();
        if (running != null) {
            for (GenService.SlotReport r : running.report()) {
                engine.put(r.id(), r);
            }
        }
        List<DailySettings.SlotConfig> all = new ArrayList<>(st.slots());
        all.addAll(st.archive().classics());
        for (DailySettings.SlotConfig c : all) {
            Slots.Def def = c.def();
            GenService.SlotReport r = engine.get(c.id());
            boolean stands = r != null ? r.wanted() || r.claimed() : c.enabled() && st.enabled();
            if (def == null || !stands) {
                continue;
            }
            SightCheck.Kind kind = Slots.isClassic(def.id()) ? SightCheck.Kind.CLASSIC : SightCheck.Kind.SLOT;
            for (Box half : Regions.halves(c)) {
                out.add(new SightCheck.Place(kind, def.id(), GenCopy.slotName(def, st.cadenceDays()), half));
            }
        }
        java.util.Map<String, String> meta;
        try {
            meta = new GenMetaDao(plugin.database()).like("gen.");
        } catch (SQLException | RuntimeException e) {
            meta = java.util.Map.of();
        }
        for (java.util.Map.Entry<String, String> e : meta.entrySet()) {
            int n = GenAdminKeys.plotOf(e.getKey());
            KeptPlot p = n < 1 ? null : KeptPlot.parse(n, e.getValue());
            if (p != null && p.world().equalsIgnoreCase(world)) {
                out.add(new SightCheck.Place(SightCheck.Kind.KEPT, Integer.toString(n), "the kept course \""
                        + p.courseId() + "\" (plot " + n + ")", p.box()));
            }
        }
        com.dierks.homecraft.games.clubhouse.ClubhouseSettings cs =
                cfg.settings(com.dierks.homecraft.games.clubhouse.Clubhouse.SPEC);
        GamesService g = games();
        Game club = g == null ? null : g.game(com.dierks.homecraft.games.clubhouse.Clubhouse.SPEC.id());
        boolean hand = club instanceof com.dierks.homecraft.games.clubhouse.Clubhouse c && c.running() && c.handBuilt();
        if (!hand && (cfg.enabled() && cs.enabled() || meta.containsKey(
                com.dierks.homecraft.games.clubhouse.ClubhouseRoom.CLAIM_KEY))) {
            out.add(new SightCheck.Place(SightCheck.Kind.CLUBHOUSE,
                    com.dierks.homecraft.games.clubhouse.ClubhouseRegions.NAME, "the Clubhouse", cs.box()));
        }
        FallingFloorsSettings ff = cfg.settings(FallingFloors.SPEC);
        if (cfg.enabled() && ff.enabled() || meta.containsKey(ArenaService.CLAIM_KEY)) {
            out.add(new SightCheck.Place(SightCheck.Kind.ARENA, ArenaRegions.NAME, "Falling Floors", ff.box()));
        }
        if (w != null) {
            org.bukkit.Location s = w.getSpawnLocation();
            out.add(new SightCheck.Place(SightCheck.Kind.SPAWN, "spawn", "the spawn of " + w.getName(),
                    new Box(s.getBlockX(), s.getBlockY(), s.getBlockZ(), s.getBlockX(), s.getBlockY(), s.getBlockZ())));
        }
        double[] safe = st.safeSpot();
        if (safe != null) {
            int x = (int) Math.floor(safe[0]);
            int y = (int) Math.floor(safe[1]);
            int z = (int) Math.floor(safe[2]);
            out.add(new SightCheck.Place(SightCheck.Kind.SAFE_SPOT, "safe_spot", "the safe spot (games.fresh.safe_spot)",
                    new Box(x, y, z, x, y, z)));
        }
        return out;
    }

    /**
     * Whether anything stands under {@code w}'s spawn, all the way down: a void world with no platform
     * lets someone arriving there fall. {@code null} when it can't be read.
     */
    private static Boolean footed(World w) {
        try {
            org.bukkit.Location s = w.getSpawnLocation();
            for (int y = s.getBlockY() - 1; y >= w.getMinHeight(); y--) {
                if (!w.getBlockAt(s.getBlockX(), y, s.getBlockZ()).getType().isAir()) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- end what players can see ----

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
            problems = ArenaRegions.problems(box, st, com.dierks.homecraft.games.clubhouse.ClubhouseRegions.extras(
                    cfg.settings(com.dierks.homecraft.games.clubhouse.Clubhouse.SPEC)), // WP-CH: apart from the Clubhouse
                    Regions.handBuilt(rows()), new Regions.WorldFacts(w.getName(), listed, w.getMinHeight(),
                            w.getMaxHeight(), port.border(), port.spawn(), st.safeSpot()));
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
                running == null ? null : running.closedWhy(), running != null && running.ready(), ff.roundSeconds(),
                cfg.common().restartTimes().isEmpty() ? 0 : cfg.common().restartHoldMinutes());
    }

    // ---- end Falling Floors ----

    // ---- the Clubhouse (WP-CH) ----

    /** The Clubhouse from its config, the world, the claim and the running room; read-only. */
    @Override
    public ClubhouseCheck.Facts clubhouse() {
        GamesConfig.Parsed cfg = config();
        com.dierks.homecraft.games.clubhouse.ClubhouseSettings cs =
                cfg.settings(com.dierks.homecraft.games.clubhouse.Clubhouse.SPEC);
        DailySettings st = cfg.settings(DailyCourses.SPEC);
        String world = st.world().isBlank() ? (cfg.common().worlds().isEmpty() ? "" : cfg.common().worlds().get(0))
                : st.world();
        World w = world.isBlank() ? null : Bukkit.getWorld(world);
        Box box = cs.box();
        List<String> problems = List.of();
        if (w != null) {
            BukkitWorldPort port = new BukkitWorldPort(plugin, w);
            boolean listed = cfg.common().worlds().stream().anyMatch(x -> x.equalsIgnoreCase(w.getName()));
            List<Regions.Extra> arena = DailyCourses.arenaExtras(cfg.settings(FallingFloors.SPEC));
            problems = com.dierks.homecraft.games.clubhouse.ClubhouseRegions.problems(box, st, arena,
                    Regions.handBuilt(rows()), new Regions.WorldFacts(w.getName(), listed, w.getMinHeight(),
                            w.getMaxHeight(), port.border(), port.spawn(), st.safeSpot()));
        }
        ArenaCheck.Claim claim;
        try {
            String c = new GenMetaDao(plugin.database()).get(com.dierks.homecraft.games.clubhouse.ClubhouseRoom.CLAIM_KEY);
            claim = c == null ? ArenaCheck.Claim.UNCLAIMED
                    : c.equals(com.dierks.homecraft.games.clubhouse.ClubhouseRoom.claimText(world, box))
                    ? ArenaCheck.Claim.CLAIMED : ArenaCheck.Claim.MOVED;
        } catch (SQLException | RuntimeException e) {
            claim = ArenaCheck.Claim.UNKNOWN;
        }
        GamesService g = games();
        Game game = g == null ? null : g.game(com.dierks.homecraft.games.clubhouse.Clubhouse.SPEC.id());
        com.dierks.homecraft.games.clubhouse.Clubhouse running =
                game instanceof com.dierks.homecraft.games.clubhouse.Clubhouse c && c.running() ? c : null;
        boolean hand = running != null && running.handBuilt();
        String handWorld = hand ? running.handWorld() : null;
        boolean handListed = handWorld != null && cfg.common().worlds().stream().anyMatch(x -> x.equalsIgnoreCase(handWorld));
        return new ClubhouseCheck.Facts(cfg.enabled() && cs.enabled(), hand, world, w != null, box.describe(),
                hand ? List.of() : problems, claim, running == null ? null : running.closedWhy(),
                running != null && running.ready(), running == null ? List.of() : running.handSpots(),
                running != null && running.handComplete(), handWorld, !hand || handListed);
    }

    // ---- end the Clubhouse ----

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
