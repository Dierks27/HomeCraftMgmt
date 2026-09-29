package com.dierks.homecraft.games.arena;

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
import com.dierks.homecraft.games.arena.rules.ArenaScoring;
import com.dierks.homecraft.games.arena.rules.ArenaText;
import com.dierks.homecraft.games.arena.rules.RoundSettings;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.engine.GenRegionGuard;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Falling Floors (EVENTS-DROPPER-SPEC §B.3): TNT Run without any TNT. Three glass floors hang in
 * the sky, and every block you step on turns red and falls away half a second later. The last one
 * standing wins, and it works alone too: "how long can you last?". Nothing explodes; a block just
 * turns red, then it's gone.
 *
 * <p>Id {@code falling_floors}, alias {@code tnt_run}; kind {@link GameKind#TRIAL} (a free skill
 * game); it may be the featured game; ledger source {@code GAMES_FLOORS}; its tile goes on the
 * {@link Game.Tab#TOGETHER Together} tab. Ships OFF ({@code games.falling_floors.enabled: false}).
 *
 * <p><b>How it is put together.</b> The round rules are pure ({@code games.arena.rules}); the
 * running arena is {@link ArenaService} (the week's floors, the reset between rounds through Fresh
 * Courses' {@code BuildJob}, what every round event means), talking to the server only through
 * {@link ArenaHost} ({@link LiveArenaHost} here). This class is the Game: the tile, joining, the
 * listeners and tasks (all through the framework, so they run in the game's guard and go when it
 * stops), the kit, the feed and the admin commands ({@link ArenaAdmin}).
 *
 * <p><b>Joining</b> is one tap: {@code /hcm play falling_floors} (or {@code tnt_run}), or the tile,
 * takes the player into a world session (their things are kept safe and come back at the end)
 * straight into the gallery round the arena's edge. The gallery is the lobby, the stand and where
 * you go when you're out; the kit's "Leave game" (or {@code /hcm leave}) is the only way out.
 *
 * <p><b>Rewards</b> are the normal skill-game ones through {@code SkillRewards}: the first full round
 * of the day, three solo milestones once ever, and today's pick. A win pays nothing extra; it is a
 * point on this week's wins board ({@link FloorsRewards}).
 */
public final class FallingFloors implements Game {

    /** Built: the game follows its config switch (shipped off). */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<FallingFloorsSettings> SPEC = new GameSpec<>("falling_floors", GameKind.TRIAL,
            FallingFloorsSettings.KEYS, FallingFloorsSettings.defaults(), FallingFloorsSettings::parse,
            FallingFloors::new, null);

    /** The kit's actions ({@code leave} is the kit guard's own: two clicks end the session). */
    static final String READY = "ready";
    static final String SOLO = "solo";
    static final String LEAVE = "leave";

    private final GameContext ctx;
    private final ArenaAdmin admin;
    private ArenaService service;
    private LiveArenaHost host;

    public FallingFloors(GameContext ctx) {
        this.ctx = ctx;
        this.admin = new ArenaAdmin(this);
    }

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
        return ArenaText.NAME;
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_FLOORS;
    }

    @Override
    public boolean configEnabled() {
        return IMPLEMENTED && settings().enabled();
    }

    @Override
    public List<String> aliases() {
        return List.of("tnt_run");
    }

    @Override
    public List<String> rules() {
        return List.of("Every block you step on falls away.",
                "Keep moving! Three floors, three chances.",
                "Last one standing wins - or play solo.");
    }

    /** "&amp;eFalling Floors &amp;7- 2 playing · join!": the key fact in the NAME, for Bedrock. */
    @Override
    public ItemStack tile(Player viewer) {
        ArenaService s = service;
        boolean open = s != null && s.open();
        List<String> lore = new ArrayList<>();
        for (String line : rules()) {
            lore.add("&7" + line);
        }
        ArenaSite site = s == null ? null : s.site();
        if (site != null) {
            String week = FloorsText.thisWeek(ArenaPlanner.shapesText(site.shapes()));
            if (week != null) {
                lore.add(week);
            }
            Long best = viewer == null ? null
                    : games().scores().best(viewer.getUniqueId(), id(), ArenaScoring.soloBoard(site.week()));
            if (best != null) {
                lore.add("&7Your best solo this week: &f" + ArenaText.clock(best / RoundSettings.MS_PER_TICK));
            }
        }
        lore.add(open ? "&eClick to play" : "&7The floors are being made - back soon!");
        return Menus.icon(Material.YELLOW_STAINED_GLASS, FloorsText.tile(s == null ? 0 : s.playing(), open),
                lore.toArray(new String[0]));
    }

    @Override
    public List<GameTile> tiles(Player viewer) {
        return List.of(new GameTile(Tab.TOGETHER, tile(viewer), id(), 0));
    }

    /** {@code /hcm play falling_floors} and the tile: straight into the gallery (after the framework's gate). */
    @Override
    public void open(Player player, Runnable back) {
        join(player);
    }

    /**
     * This week's arena on the website ({@code kind: arena}, its shape) and its solo board behind
     * the {@code top} list (higher is better, in ms).
     */
    @Override
    public void feed(FeedWriter out) {
        ArenaService s = service;
        ArenaSite site = s == null ? null : s.site();
        out.arena(id(), name(), site == null ? null : site.shape());
        out.board(id(), id(), ArenaScoring.soloBoard(site != null ? site.week() : weekNow()), false, "ms");
    }

    @Override
    public List<String> statusLines() {
        ArenaService s = service;
        return s == null ? List.of("not running") : s.statusLines();
    }

    @Override
    public GameAdmin admin() {
        return admin;
    }

    /**
     * Start the arena: the week's floors (the rules start with the boot verify, so the box is put
     * back before anyone comes in), the area guard over the box, the tick and the once-a-second
     * check, and the move listeners. Each reads the arena running now, so a settings change can
     * start a new one without registering anything twice ({@link #settingsChanged}).
     */
    @Override
    public void start() {
        stopArena();
        if (ctx.plugin() == null) {
            return; // the framework's own tests: no server
        }
        GamesService g = games();
        host = new LiveArenaHost(this);
        service = new ArenaService(host);
        GenRegionGuard.register(g, this, () -> (world, x, y, z) -> {
            ArenaService s = service;
            return s != null && world != null && world.equalsIgnoreCase(s.world()) && s.box().contains(x, y, z);
        }, log());
        g.on(this, PlayerMoveEvent.class, EventPriority.HIGH, true, this::hold);
        g.on(this, PlayerMoveEvent.class, EventPriority.MONITOR, true, this::moved);
        g.every(this, 1, 1, () -> {
            ArenaService s = service;
            if (s != null) {
                s.tick();
            }
        });
        g.every(this, 20, 20, () -> {
            ArenaService s = service;
            if (s != null && !settingsChanged(s)) {
                s.check();
            }
        });
        service.start();
    }

    /**
     * {@code /hcm reload} changed where the arena is or how a round plays ({@code origin},
     * {@code games.fresh.world}, the round knobs): once no round is going, everyone in the old arena
     * goes home with their things and a new arena starts, with its own boot verify. The rewards and
     * the reset's speed are read live and need nothing.
     *
     * @return whether a new arena was started
     */
    private boolean settingsChanged(ArenaService s) {
        LiveArenaHost h = host;
        if (h == null || s.builtWith(settings(), h.worldName())) {
            return false;
        }
        if (s.round() != null && s.round().phase().inRound()) {
            return false; // the round going finishes on the arena it started on
        }
        log().info("Falling Floors: its settings changed - starting its arena again");
        List<UUID> members = s.round() == null ? List.of() : s.round().members();
        s.stop();
        service = new ArenaService(h);
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && games().sessions().session(p) != null) {
                p.sendMessage(Text.of(FloorsText.MOVING));
                games().sessions().leave(p, EndReason.FINISH);
            }
        }
        service.start();
        return true;
    }

    /** Stop: collisions back, bars away, no reset left running. Sessions are the framework's to end. */
    @Override
    public void stop() {
        stopArena();
    }

    private void stopArena() {
        ArenaService s = service;
        service = null;
        if (s != null) {
            s.stop();
        }
        if (host != null) {
            host.hideAllBars();
            host = null;
        }
    }

    @Override
    public void onQuit(Player player) {
        ArenaService s = service;
        if (s != null) {
            s.left(player.getUniqueId());
        }
    }

    @Override
    public void onSessionEnd(Player player, EndReason reason) {
        ArenaService s = service;
        if (s != null) {
            s.left(player.getUniqueId());
        }
        if (player.isOnline() && !player.isCollidable()) {
            player.setCollidable(true); // however the session ended, collisions come back
        }
    }

    @Override
    public void onVoid(Player player) {
        ArenaService s = service;
        if (s != null) {
            s.voided(player.getUniqueId());
        }
    }

    @Override
    public void onKitUse(Player player, String action, boolean leftClick) {
        ArenaService s = service;
        if (s == null || action == null) {
            return;
        }
        switch (action) {
            case READY -> s.ready(player.getUniqueId());
            case SOLO -> s.solo(player.getUniqueId());
            default -> {
                // "leave" is the kit guard's
            }
        }
    }

    // ---- joining ----------------------------------------------------------------------------------

    /** Into a world session, arriving at the next gallery spot; refused (told why) when it can't be. */
    void join(Player p) {
        ArenaService s = service;
        if (s == null) {
            refuse(p, ArenaText.closed());
            return;
        }
        String why = s.joinRefusal(p.getUniqueId());
        if (why != null) {
            refuse(p, why);
            return;
        }
        World w = s.world().isEmpty() ? null : Bukkit.getWorld(s.world());
        ArenaSite.Spot spot = s.entrySpot();
        if (w == null || spot == null) {
            refuse(p, FloorsText.FIXING);
            return;
        }
        Location at = new Location(w, spot.x(), spot.y(), spot.z(), spot.yaw(), 0);
        games().sessions().enter(p, this, ArenaService.REF, at, this::arrived);
    }

    /** In, saved and cleared: into the arena (the kit comes with it), or home again if it closed meanwhile. */
    private void arrived(Player p) {
        ArenaService s = service;
        String why = s == null ? ArenaText.closed() : s.joined(p.getUniqueId());
        if (why != null) {
            p.sendMessage(Text.of(why));
            games().sessions().leave(p, EndReason.FINISH);
        }
    }

    private static void refuse(Player p, String line) {
        p.sendMessage(Text.of(line));
        Sounds.refused(p);
    }

    // ---- moves ------------------------------------------------------------------------------------

    /** On a spawn waiting for Go: held in place, free to look around (the trials hold). */
    private void hold(PlayerMoveEvent e) {
        ArenaService s = service;
        if (s == null || !e.hasExplicitlyChangedPosition() || !s.held(e.getPlayer().getUniqueId())) {
            return;
        }
        Location held = e.getFrom().clone();
        held.setYaw(e.getTo().getYaw());
        held.setPitch(e.getTo().getPitch());
        e.setTo(held);
    }

    /** Every accepted move of a round player marks the floor it passes over (not one read a tick). */
    private void moved(PlayerMoveEvent e) {
        ArenaService s = service;
        if (s == null || !s.inPlay()) {
            return;
        }
        Location to = e.getTo();
        Location from = e.getFrom();
        s.moved(e.getPlayer().getUniqueId(), to.getX(), to.getY(), to.getZ(), to.getY() - from.getY());
    }

    // ---- for the host and the admin tool ----------------------------------------------------------

    GamesService games() {
        return ctx.games();
    }

    HomeCraftManagement plugin() {
        return ctx.plugin();
    }

    Logger log() {
        return plugin() == null ? Logger.getLogger("HomeCraftMgmt") : plugin().getLogger();
    }

    /** The running arena, or {@code null} while the game is off. */
    ArenaService service() {
        return service;
    }

    /** The week the arena's shape belongs to now: Fresh Courses' week, turning at its rollover. */
    long weekNow() {
        Edition ed = DailyLookup.edition(games());
        return ed.weekKey(ed.day(games().clock().nowMillis()));
    }

    /** The live settings (read on every use, never cached across a reload). */
    FallingFloorsSettings settings() {
        return ctx.games().settings(SPEC);
    }

    // ---- for /hcm games check (EXTRAS E1) ----------------------------------------------------------

    /** Whether its arena is running (the game is open and started). */
    public boolean running() {
        return service != null;
    }

    /** Whether players may come in now: the floors have been verified and it isn't closed. */
    public boolean ready() {
        ArenaService s = service;
        return s != null && s.open();
    }

    /** Why its arena closed itself, or {@code null}. */
    public String closedWhy() {
        ArenaService s = service;
        return s == null ? null : s.closedWhy();
    }
}
