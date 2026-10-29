package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.event.NightRunner;
import com.dierks.homecraft.games.event.RaceNight;
import com.dierks.homecraft.games.gen.engine.GenRegionGuard;
import com.dierks.homecraft.games.trial.PartyLobby;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The Clubhouse (CLUBHOUSE-SPEC; the owner: "a waiting room ... and the same room after the race so
 * folks can hang out and joke around and talk about what happened"): one room in the Games world
 * where racers wait before a race and hang out after it.
 *
 * <p><b>A place, not a new engine.</b> Everyone in the Clubhouse is in a world session: the
 * Clubhouse's own ({@code /hcm play clubhouse}, "Go to the Clubhouse" on the party screen, "Wait in
 * the Clubhouse" for Race Night, a Watch button), or a race's or a round of golf's handed to it in
 * place when it ends ({@link ClubDoor#takeIn}). Being seated for a race from here hands the session
 * the other way ({@link ClubDoor#handOut}): one session all along, so a player's things are saved
 * once and come back once, on every way out (Leave game, a quit, a kick, the restart hold, the
 * Clubhouse or the games switched off, a reload, a failure; a crash too, at the next join).
 *
 * <p><b>Off means off.</b> The other games only ever reach the Clubhouse through {@link #door}, which
 * is there only while {@code games.clubhouse.enabled} is on and the room is built and checked (or an
 * owner-built room is set): otherwise every flow does exactly what it did before.
 *
 * <p><b>In the room</b>: nobody is hurt or pushed (the world session and {@link NoPush}), nobody
 * changes a block (the box is guarded, like the Falling Floors arena's), chat is the server's. The
 * kit: Party (while in a party race's lobby), Results (the last results screen), Go now and Stay here
 * (while on the way to a course or a round), Watch live (while a race is going), Games and Leave game.
 * Anyone here longer than {@code max_minutes} with no race or party going is sent home, with a
 * warning a minute before; the restart hold sends everyone home a minute after it starts or after
 * they arrive from a race or golf already going (and always before the restart's own minute), and
 * from a little over a minute before the restart, when an arrival could not get that minute, nobody
 * is taken in at all ({@link #closingForRestart}).
 *
 * <p><b>The board</b> shows the last event's result (a party race's times and gaps, Race Night's
 * points, golf's strokes), drawn only on a new result. At the end of Race Night its top three stand
 * on the podium for "Photo time!", with a firework over 1st.
 *
 * <p><b>Through the Clubhouse</b> (0.38.3, the owner: "Can you make it so we go to the clubhouse when
 * click into a game? I just feel like the clubhouse isnt really used"). A Start on a course or a mini
 * golf course comes here first ({@code solo_first}, {@link #lobby}): "Off to Sky Rings!", a countdown of
 * {@code first_seconds} on the action bar, then the session is handed out to the start ({@link
 * #goNext}); "Go now" skips the wait and "Stay here" calls it off. A solo run's finish or a round
 * alone's end comes back here ({@code solo_after}) with that player's own results ({@link
 * #soloResult}: never the board or the shared Results), and Play again hands them straight out again.
 * The Games item opens the Games screen here: courses and golf start by hand-out, cabinets play on
 * their screens right here, and games of luck, Falling Floors and Race Night stay outside (the play
 * gate's step 0). {@link ClubRoute} holds every fallback: with the Clubhouse off, a course in another
 * world, the restart hold, a rider or a test run, everything is exactly as before.
 *
 * <p>Nothing here moves a token, and nothing counts toward a board, quest, achievement or the Cup.
 * It runs inside the framework's guard: a bug switches the Clubhouse off, never a race.
 */
public final class Clubhouse implements Game, ClubDoor {

    /** Built: it follows its config switch (shipped on). */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<ClubhouseSettings> SPEC = new GameSpec<>("clubhouse", GameKind.TRIAL,
            ClubhouseSettings.KEYS, ClubhouseSettings.defaults(), ClubhouseSettings::parse, Clubhouse::new, null);

    /** The Clubhouse's own sessions' ref. */
    public static final String REF = "clubhouse";
    /** The kit's actions ({@code leave} is the kit guard's own: two clicks end the session). */
    static final String PARTY = "party";
    static final String RESULTS = "results";
    static final String WATCH = "watch";
    /** 0.38.3: "Go now: Sky Rings", "Stay here - hang out" and "Games - pick a game". */
    static final String GO_NOW = "go_now";
    static final String STAY = "stay";
    static final String GAMES = "games";
    static final String LEAVE = "leave";
    /**
     * A solo player's own results open once they are seen in the room: looked for this often (ticks), this many
     * times (15 s, past {@link ClubVisits#ARRIVAL_MS}: by then an arrival never seen has been sent home).
     */
    static final long RESULT_TICKS = 10;
    static final int RESULT_TRIES = 30;
    /** "Photo time!" stays up this long (ticks), and waits at most this long for the podium (ms). */
    static final int PHOTO_TICKS = 200;
    static final long PHOTO_WAIT_MS = 5_000L;

    private final GameContext ctx;
    private final ClubhouseAdmin admin;
    private ClubhouseRoom room;
    private LiveRoomHost host;
    private BoardDisplay display;
    /** The walls' boards (0.38.3): this week's best, now playing, my stats; null with no server. */
    private ClubBoards boards;
    private final ClubVisits visits = new ClubVisits();
    private final ClubBoard board = new ClubBoard();
    private final Map<UUID, Arrival> arriving = new HashMap<>();
    private final Map<UUID, String> kits = new HashMap<>();
    private final Map<UUID, Long> toldGuarded = new HashMap<>();
    private int nextSpot;
    private Results last;
    private Photo photo;
    private WatchLive watch;
    /** 0.38.4: the jukebox corner and the family week; null with no server. */
    private JukeboxCorner jukebox;
    private FamilyWeek familyWeek;
    private final Cheers cheers = new Cheers();
    private final Map<UUID, Boolean> cheersOn = new HashMap<>();
    /** 0.38.3: who is on their way to a course or a round from here, and their countdowns. */
    private final ClubLobby lobby = new ClubLobby();
    /** Each solo player's own last results here (0.38.3): what their Results opens while newer than {@link #last}. */
    private final Map<UUID, Mine> mine = new HashMap<>();
    /** When {@link #last} was set (the clock's ms), so the newer of a player's own and the shared one opens. */
    private long lastAt;
    /** Whether the room was open at the last second (0.38.4: the jukebox is swept when it opens). */
    private boolean wasOpen;

    /**
     * Someone on their way into the Clubhouse's own session: why they come, whether to watch live at once,
     * and (0.38.3) the course or round they go on to after the countdown, or {@code null}.
     */
    private record Arrival(ClubVisits.Kind kind, boolean spectator, boolean watch, ClubNext next) {

        Arrival(ClubVisits.Kind kind, boolean spectator) {
            this(kind, spectator, false);
        }

        Arrival(ClubVisits.Kind kind, boolean spectator, boolean watch) {
            this(kind, spectator, watch, null);
        }
    }

    /** The last event's result: the board's sheet, and what "Results" opens. */
    private record Results(ClubBoard.Sheet sheet, Consumer<Player> opener) {
    }

    /** A solo player's own last results (0.38.3): their results screen, and when it came. */
    private record Mine(Consumer<Player> opener, long at) {
    }

    /** Race Night's podium, waiting for its three to arrive. */
    private static final class Photo {
        final List<UUID> top;
        final long until;
        final Set<UUID> placed = new LinkedHashSet<>();

        Photo(List<UUID> top, long until) {
            this.top = List.copyOf(top);
            this.until = until;
        }
    }

    public Clubhouse(GameContext ctx) {
        this.ctx = ctx;
        this.admin = new ClubhouseAdmin(this);
    }

    // ---- the Game -------------------------------------------------------------------------------

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
        return ClubhouseText.NAME;
    }

    /** Never used: the Clubhouse moves no tokens. A source is only the ledger's label. */
    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_RACE_NIGHT;
    }

    @Override
    public boolean configEnabled() {
        return IMPLEMENTED && settings().enabled();
    }

    /** A place to hang out is never "today's pick". */
    @Override
    public boolean featurable() {
        return false;
    }

    @Override
    public List<String> rules() {
        return List.of("A room to hang out in before and after your games.", "Chat, cheer, see the boards"
                + " and pick a game.", "Leave game takes you home, with your things.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        boolean open = room != null && room.open();
        return Menus.icon(Material.CAKE, open ? "&6The Clubhouse &7- " + visits.size() + " here · visit!"
                : "&7The Clubhouse &8- closed right now", "&7Hang out with friends,", "&7before and after your games.",
                "&7/hcm play clubhouse");
    }

    /**
     * Its tile on the Games screen's Together tab while it is open (0.38.3: "The Clubhouse - 2 here · visit!");
     * a click comes in ({@link #open}). Closed, no tile at all. It is also reached from the party and Race
     * Night screens, the Arcade's door and {@code /hcm play clubhouse}.
     */
    @Override
    public List<GameTile> tiles(Player viewer) {
        ClubhouseRoom r = room;
        if (r == null || !r.open()) {
            return List.of();
        }
        List<GameTile> out = new ArrayList<>();
        out.add(new GameTile(Tab.TOGETHER, tile(viewer), id(), 0));
        if (viewer != null && visits.in(viewer.getUniqueId()) && jukebox != null && jukebox.block() != null) {
            out.add(new GameTile(Tab.TOGETHER, songsTile(), SONGS, 1)); // 0.38.4: Songs, while inside
        }
        return out;
    }

    /** 0.38.4: the Songs playable ({@code /hcm play club_songs}, the Together tab's tile while inside). */
    static final String SONGS = "club_songs";

    private static ItemStack songsTile() {
        return Menus.icon(Material.JUKEBOX, ClubBoardText.SONGS_TILE, "&7Tap a record: it plays", "&7for everyone in"
                + " the room.");
    }

    @Override
    public Collection<Playable> playables() {
        return List.of(new Playable(SONGS, "Songs", this, source(), ""));
    }

    /** Songs: the jukebox corner's screen, for a visitor; anyone else is told to come in first. */
    @Override
    public boolean play(Player player, String playableId, Runnable back) {
        if (!SONGS.equals(playableId)) {
            return false;
        }
        if (jukebox == null || !visits.in(player.getUniqueId())) {
            refuse(player, ClubBoardText.SONGS_ELSEWHERE);
        } else {
            jukebox.openSongs(player);
        }
        return true;
    }

    /** {@code /hcm play clubhouse}: come in (or, from Watch live, come back). */
    @Override
    public void open(Player player, Runnable back) {
        if (watch != null && watch.watching(player.getUniqueId())) {
            watch.stop(player, WatchLive.BACK);
            return;
        }
        visit(player);
    }

    @Override
    public GameAdmin admin() {
        return admin;
    }

    @Override
    public List<String> statusLines() {
        ClubhouseRoom r = room;
        if (r == null) {
            return List.of("not running");
        }
        List<String> out = new ArrayList<>(r.statusLines());
        out.add(visits.size() + " in the Clubhouse (" + (watch == null ? 0 : watch.count()) + " watching live), "
                + arriving.size() + " on the way");
        return out;
    }

    /**
     * Start: the room (its boot build), the box guard, and the tick and once-a-second tasks. Every
     * board and firework a crash or a reload left is swept first.
     */
    @Override
    public void start() {
        stopRoom();
        if (ctx.plugin() == null) {
            return; // the framework's own tests: no server
        }
        GamesService g = games();
        WorldEntities.sweep(this);
        host = new LiveRoomHost(this);
        display = new BoardDisplay(this);
        boards = new ClubBoards(this);
        jukebox = new JukeboxCorner(this);
        familyWeek = new FamilyWeek(this);
        watch = new WatchLive(this, new WatchVisibility(WatchLive.viewers(plugin())));
        room = newRoom();
        GenRegionGuard.register(g, this, () -> (world, x, y, z) -> guardedAt(world, x, y, z), log());
        guardEdits(g);
        g.on(this, EntityDamageByEntityEvent.class, EventPriority.LOWEST, false, e -> {
            if (BoardDisplay.ours(e.getDamager(), this)) {
                e.setCancelled(true); // the podium's firework never hurts anyone
            }
        });
        g.on(this, org.bukkit.event.player.PlayerMoveEvent.class, EventPriority.HIGH, true, e -> watch.moved(e));
        g.on(this, org.bukkit.event.player.PlayerTeleportEvent.class, EventPriority.LOW, true,
                e -> watch.teleported(e));
        g.on(this, com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent.class, EventPriority.NORMAL,
                true, e -> watch.spectating(e));
        g.on(this, org.bukkit.event.player.PlayerChangedWorldEvent.class, EventPriority.MONITOR, false,
                e -> watch.arrived(e.getPlayer()));
        g.on(this, org.bukkit.event.player.PlayerInteractEvent.class, EventPriority.LOWEST, false,
                e -> jukebox.tapped(e)); // 0.38.4: a tap on the jukebox opens Songs, and never pops the disc
        g.every(this, 1, 1, this::tick);
        g.every(this, 20, 20, this::second);
        room.start();
    }

    private ClubhouseRoom newRoom() {
        ClubhouseRoom r = new ClubhouseRoom(host, settings());
        r.visitors(visits::in);
        return r;
    }

    /** Stop: no build left running, the board gone, everyone off the no-push team. Sessions are the framework's. */
    @Override
    public void stop() {
        stopRoom();
    }

    private void stopRoom() {
        if (room != null) {
            room.stop();
            room = null;
        }
        if (display != null) {
            display.remove();
        }
        if (boards != null) {
            boards.stop();
        }
        if (jukebox != null) {
            jukebox.stop(); // the music off and our disc cleared while the world is up
        }
        if (watch != null) {
            watch.clear(); // every watcher seen again
        }
        for (ClubVisits.Visit v : visits.all()) {
            noPushOff(v.id(), online(v.id()));
        }
        visits.clear();
        arriving.clear();
        kits.clear();
        lobby.clear();
        mine.clear();
        photo = null;
        board.forget();
    }

    @Override
    public void onQuit(Player player) {
        gone(player.getUniqueId(), player);
        cheers.forget(player.getUniqueId());
        cheersOn.remove(player.getUniqueId());
    }

    /** Someone joined: everyone sees them again (a crash boot, a quit while watching), and they don't see watchers. */
    @Override
    public void onJoin(Player player) {
        if (watch != null) {
            watch.joined(player);
        }
    }

    /** Their Clubhouse session ended (any way at all): out of the Clubhouse, off the no-push team. */
    @Override
    public void onSessionEnd(Player player, EndReason reason) {
        gone(player.getUniqueId(), player);
    }

    /**
     * Fell out of the world (or was nudged): back to an arrival spot. A watcher is never sent to the
     * Clubhouse from here (they are still watching, in spectator mode): they are put back inside the
     * area they watch, by the session's own teleport (review #1).
     */
    @Override
    public void onVoid(Player player) {
        if (watch != null && watch.watching(player.getUniqueId())) {
            watch.putBack(player);
            return;
        }
        if (visits.in(player.getUniqueId())) {
            toSpawn(player);
        }
    }

    @Override
    public void onKitUse(Player player, String action, boolean leftClick) {
        if (action == null || !visits.in(player.getUniqueId())) {
            return;
        }
        switch (action) {
            case ClubBoards.STATS -> {
                if (boards != null) {
                    boards.openStats(player);
                }
            }
            case PARTY -> openParty(player);
            case RESULTS -> openResults(player);
            case WATCH -> watchNow(player, null);
            case GO_NOW -> goNext(player);
            case STAY -> stay(player);
            case GAMES -> games().openGamesScreen(player, null);
            default -> {
                // "leave" is the kit guard's
            }
        }
    }

    // ---- coming in ------------------------------------------------------------------------------

    /**
     * {@code /hcm play clubhouse}, "Go to the Clubhouse" and "Wait in the Clubhouse": into the
     * Clubhouse as a party racer (in a party race's lobby), a Race Night racer (on tonight's list) or
     * a visitor. Refused, with a plain reason, while it is closed or not built, during the restart
     * hold, or while they are in another game.
     */
    public void visit(Player p) {
        UUID id = p.getUniqueId();
        if (visits.in(id)) {
            ClubVisits.Visit v = visits.get(id);
            if (v.spectator() && kindFor(id) != ClubVisits.Kind.VISIT) {
                visits.spectator(id, false);
                p.sendMessage(Text.of(ClubhouseText.NOT_SPECTATOR));
            } else {
                p.sendMessage(Text.of(ClubhouseText.ALREADY));
            }
            return;
        }
        enter(p, kindFor(id), false);
    }

    /** A Watch button: into the Clubhouse as a spectator (never seated), or one already here stops racing. */
    public void spectate(Player p) {
        UUID id = p.getUniqueId();
        if (visits.in(id)) {
            visits.spectator(id, true);
            p.sendMessage(Text.of(ClubhouseText.SPECTATING));
            if (raceFor(id) != null) {
                watchNow(p, null); // a race is going: Watch live starts at once
            }
            return;
        }
        enter(p, kindFor(id), true, raceFor(id) != null);
    }

    private boolean enter(Player p, ClubVisits.Kind kind, boolean spectator) {
        return enter(p, kind, spectator, false);
    }

    private boolean enter(Player p, ClubVisits.Kind kind, boolean spectator, boolean watchLive) {
        return enter(p, kind, spectator, watchLive, null);
    }

    /**
     * Into the Clubhouse's own session. {@code next} (0.38.3): the course or round they go on to after the
     * countdown, or {@code null} for a stay.
     *
     * @return whether the entry started (false: refused, and they were told why; nothing taken or moved)
     */
    private boolean enter(Player p, ClubVisits.Kind kind, boolean spectator, boolean watchLive, ClubNext next) {
        ClubhouseRoom r = room;
        if (r == null || !r.open()) {
            refuse(p, ClubhouseText.CLOSED);
            return false;
        }
        // Final gate, group B (#2): every way in from outside (/hcm play watch, Watch, Go to / Wait in the
        // Clubhouse) runs the play gate, as /hcm play clubhouse does: hcm.games.play, a world games are
        // played in. Before anything is taken or moved.
        com.dierks.homecraft.games.Refusal gate = games().canOpen(p, this);
        if (gate != null) {
            games().tell(p, gate);
            return false;
        }
        World w = Bukkit.getWorld(r.world());
        ClubhouseSite.Spot s = r.spawn(nextSpot++);
        if (w == null || s == null) {
            refuse(p, ClubhouseText.CLOSED);
            return false;
        }
        UUID id = p.getUniqueId();
        arriving.put(id, new Arrival(kind, spectator, watchLive, next));
        Location at = new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0f);
        if (!games().sessions().enter(p, this, REF, at, this::arrived)) {
            arriving.remove(id); // refused (they were told why): nothing was taken or moved
            return false;
        }
        return true;
    }

    /** In, saved and cleared: into the Clubhouse, or home again if it closed meanwhile. */
    private void arrived(Player p) {
        Arrival a = arriving.remove(p.getUniqueId());
        ClubhouseRoom r = room;
        if (a == null || r == null || !r.open()) {
            p.sendMessage(Text.of(ClubhouseText.CLOSED_NOW));
            games().sessions().leave(p, EndReason.FINISH);
            return;
        }
        String line = a.spectator() ? ClubhouseText.SPECTATING : switch (a.kind()) {
            case PARTY -> ClubhouseText.WAIT_PARTY;
            case NIGHT -> ClubhouseText.WAIT_NIGHT;
            default -> ClubhouseText.WELCOME;
        };
        if (a.next() != null) {
            // 0.38.3: on their way to a course or a round: the countdown, Go now and Stay here in the kit
            lobby.start(p.getUniqueId(), a.next(), now(), settings().firstSeconds());
            line = ClubhouseText.offToLine(a.next().name());
        }
        welcome(p, a.kind(), a.spectator(), line);
        if (a.next() != null) {
            title(p, ClubhouseText.offTo(a.next().name()), ClubhouseText.OFF_TO_SMALL);
        }
        if (a.watch()) {
            watchNow(p, null); // a Watch button while the race is going: straight on to Watch live
        }
    }

    /** They are in the Clubhouse now: a visitor, on the no-push team, with the kit and a line. */
    private void welcome(Player p, ClubVisits.Kind kind, boolean spectator, String line) {
        UUID id = p.getUniqueId();
        admit(id, p.getName(), kind, spectator);
        if (room != null && room.contains(p.getWorld().getName(), p.getX(), p.getY(), p.getZ())) {
            visits.seen(id);
        }
        kits.remove(id);
        giveKit(p);
        if (boards != null) {
            boards.arrived(p);
        }
        if (line != null && !line.isBlank()) {
            p.sendMessage(Text.of(line));
        }
        Photo ph = photo;
        if (ph != null && ph.top.contains(id)) {
            placeOnPodium(p, ph);
        }
    }

    /** In the Clubhouse: a visitor, and on the no-push team (nobody pushes anybody in here). */
    void admit(UUID id, String name, ClubVisits.Kind kind, boolean spectator) {
        visits.enter(id, name, kind, now());
        visits.spectator(id, spectator);
        NoPush np = noPush();
        if (np != null) {
            np.on(id, name);
        }
    }

    /**
     * Anyone on the way into a Clubhouse session who is offline or no longer entering one is
     * forgotten, so nothing ever waits on a ghost (race mode's arriving sweep): an entry dropped on
     * the way (hands not free, hurt, the start couldn't be reached) never arrives, and nothing else
     * tells the Clubhouse.
     */
    private void sweepArrivals() {
        if (!arriving.isEmpty()) {
            sweepArrivals(this::stillComing);
        }
    }

    /** {@link #sweepArrivals()} with who is still on the way decided by {@code stillComing}. */
    void sweepArrivals(java.util.function.Predicate<UUID> stillComing) {
        arriving.keySet().removeIf(id -> !stillComing.test(id));
    }

    private boolean stillComing(UUID id) {
        Player p = online(id);
        Session s = p == null ? null : games().sessions().session(p);
        return p != null && s != null && id().equals(s.gameId()) && s.phase() == Session.Phase.ENTERING;
    }

    /** Someone on the way in (a test's own, as {@link #enter} records one). */
    void expect(UUID id, ClubVisits.Kind kind) {
        arriving.put(id, new Arrival(kind, false));
    }

    /** Whether someone is on the way in. */
    boolean arriving(UUID id) {
        return arriving.containsKey(id);
    }

    /** Why someone coming in comes: a party racer, a Race Night racer, or a visitor. */
    ClubVisits.Kind kindFor(UUID id) {
        PartyLobby l = games().parties().of(id);
        if (l != null && l.kind() == PartyLobby.Kind.RACE) {
            return ClubVisits.Kind.PARTY;
        }
        NightRunner n = night();
        return n != null && !n.phase().over() && n.in(id) ? ClubVisits.Kind.NIGHT : ClubVisits.Kind.VISIT;
    }

    // ---- going out ------------------------------------------------------------------------------

    /** Out of the Clubhouse, however it happened: forgotten, off the no-push team. Never throws. */
    private void gone(UUID id, Player p) {
        arriving.remove(id);
        kits.remove(id);
        toldGuarded.remove(id);
        lobby.forget(id); // a trip waiting: no more
        mine.remove(id);
        if (watch != null) {
            watch.gone(id); // seen by everyone again; the session's end puts their game mode back
        }
        if (boards != null) {
            boards.gone(id);
        }
        if (visits.leave(id) != null) {
            noPushOff(id, p);
        }
    }

    /** Home with their things, reading {@code line}. */
    private void sendHome(Player p, String line) {
        if (line != null) {
            p.sendMessage(Text.of(line));
        }
        games().sessions().leave(p, EndReason.FINISH);
    }

    // ---- the tick and the second ----------------------------------------------------------------

    private void tick() {
        ClubhouseRoom r = room;
        if (r != null) {
            r.tick();
        }
        sweepArrivals();
    }

    /**
     * Once a second: the room (a build waiting for its world), a moved box, the Clubhouse closing,
     * the arrival checks, the timeouts and the restart hold, the podium, the kits and the board.
     */
    private void second() {
        ClubhouseRoom r = room;
        if (r == null) {
            return;
        }
        if (moved(r)) {
            return;
        }
        r.second();
        long now = now();
        if (!r.open()) {
            for (ClubVisits.Visit v : visits.all()) {
                Player p = online(v.id());
                if (p == null) {
                    gone(v.id(), null);
                } else {
                    sendHome(p, ClubhouseText.CLOSED_NOW);
                }
            }
            display.remove();
            board.forget();
            if (boards != null) {
                boards.clear();
            }
            if (jukebox != null) {
                jukebox.stop();
            }
            wasOpen = false;
            return;
        }
        if (!wasOpen && jukebox != null) {
            jukebox.sweep(); // the room just opened: a disc left by a crash comes out
        }
        wasOpen = true;
        watch.refresh(liveRaces());
        for (ClubVisits.Visit v : visits.all()) {
            Player p = online(v.id());
            if (p == null || !inOurSession(p)) {
                gone(v.id(), p);
                continue;
            }
            if (!watch.watching(v.id())) {
                arrivalCheck(p, v, now); // a watcher is on the course: the keeper holds them in its area
            }
        }
        watch.second();
        RestartHold hold = games().restartHold();
        boolean holding = hold.holding(now);
        // home a minute after the warning, and always before the restart's own minute (its start: next())
        for (ClubVisits.Act a : visits.second(now, settings().maxMinutes(), this::busy, holding, hold.next(now))) {
            Player p = online(a.player());
            if (p == null) {
                gone(a.player(), null);
                continue;
            }
            switch (a.what()) {
                case WARN_IDLE -> p.sendMessage(Text.of(ClubhouseText.IDLE_WARN));
                case HOME_IDLE -> sendHome(p, ClubhouseText.IDLE_HOME);
                case WARN_HOLD -> p.sendMessage(Text.of(ClubhouseText.holdWarn(games().restartHeld())));
                case HOME_HOLD -> sendHome(p, ClubhouseText.HOLD_HOME);
            }
        }
        countdowns(now);
        podium(now);
        for (ClubVisits.Visit v : visits.all()) {
            Player p = online(v.id());
            if (p != null) {
                giveKit(p);
            }
        }
        drawBoard(now);
        if (boards != null) {
            boards.second(now);
        }
        if (jukebox != null) {
            jukebox.second(now);
        }
        if (familyWeek != null) {
            familyWeek.second(now);
        }
    }

    /**
     * Whether the settings moved the box or the Games world ({@code /hcm reload}): everyone in the
     * old Clubhouse goes home with their things, and a new one starts with its own build.
     */
    private boolean moved(ClubhouseRoom r) {
        if (r.builtWith(settings(), host.worldName())) {
            return false;
        }
        log().info("Clubhouse: its box or world changed - starting it again");
        for (ClubVisits.Visit v : visits.all()) {
            Player p = online(v.id());
            if (p != null) {
                sendHome(p, ClubhouseText.CLOSED_NOW);
            }
            gone(v.id(), p);
        }
        r.stop();
        display.remove();
        board.forget();
        if (boards != null) {
            boards.clear();
        }
        if (jukebox != null) {
            jukebox.stop();
        }
        room = newRoom();
        room.start();
        return true;
    }

    /**
     * A visitor put in the room must be seen there within {@value ClubVisits#ARRIVAL_MS} ms (a
     * teleport that never landed sends them home, never leaves them a ghost); one seen who is out of
     * the room some other way is put back.
     */
    private void arrivalCheck(Player p, ClubVisits.Visit v, long now) {
        boolean inRoom = room.contains(p.getWorld().getName(), p.getX(), p.getY(), p.getZ());
        if (inRoom) {
            visits.seen(v.id());
            return;
        }
        if (!v.seen()) {
            if (visits.lost(v.id(), now)) {
                sendHome(p, ClubhouseText.LOST);
            }
            return;
        }
        toSpawn(p); // out of the room some other way: back in it
    }

    /**
     * Whether a race or a party is going for the visitor (their idle clock stands still); 0.38.3: also while
     * a trip to a course or a round is pending, or a cabinet's screen is open for them (a game of Snake in
     * the Clubhouse never times them out).
     */
    private boolean busy(UUID id) {
        if (lobby.waiting(id)) {
            return true; // a pending Go
        }
        Game on = com.dierks.homecraft.gui.games.GameMenu.gameOnScreen(online(id));
        if (on != null && on.kind() == GameKind.CABINET) {
            return true;
        }
        if (games().parties().of(id) != null || (watch != null && watch.watching(id))) {
            return true;
        }
        NightRunner n = night();
        return n != null && !n.phase().over() && n.in(id);
    }

    // ---- the kit --------------------------------------------------------------------------------

    /**
     * The kit, key facts in the NAMES: 0 Party (while in a party race's lobby), 2 Results, 3 Go now (0.38.3,
     * while on the way to a course or a round), 4 Watch live (while a race is going), 5 Stay here (with Go
     * now), 6 Games (0.38.3: pick a game), 7 My stats (0.38.3, the boards'), 8 Leave game (two clicks, the
     * kit guard's). Given again only when it changes.
     */
    private void giveKit(Player p) {
        UUID id = p.getUniqueId();
        if (watch != null && watch.watching(id)) {
            return; // spectator mode can't use items: the action bar says how to come back
        }
        boolean party = raceLobby(id) != null;
        LiveRace live = followed();
        ClubNext next = lobby.next(id);
        boolean own = ownNewer(id);
        String sig = (party ? "P" : "") + (own ? "M" : "R") + (live != null ? "W" + live.key() : "")
                + (next != null ? "N" + next.gameId() + ":" + next.ref() : "") + "G"
                + (settings().boards() ? "S" : "");
        if (sig.equals(kits.get(id)) || !inOurSession(p)) {
            return;
        }
        kits.put(id, sig);
        games().sessions().stripKit(p); // the kit only: anything delivered mid-session stays, banked at the end (#3)
        PlayerInventory inv = p.getInventory();
        if (party) {
            KitItems.put(inv, 0, KitItems.item(this, PARTY, Material.CAKE, ClubhouseText.KIT_PARTY,
                    "&7Who's in, who's ready,", "&7and the host's Start."));
        }
        KitItems.put(inv, 2, own
                ? KitItems.item(this, RESULTS, Material.BOOK, ClubhouseText.KIT_RESULTS_MINE, "&7Your time, and Play again.")
                : KitItems.item(this, RESULTS, Material.BOOK, ClubhouseText.KIT_RESULTS, "&7The last race's results."));
        if (settings().boards()) {
            KitItems.put(inv, 7, ClubBoards.kitItem(this)); // My stats (0.38.3)
        }
        if (live != null) {
            KitItems.put(inv, 4, KitItems.item(this, WATCH, Material.SPYGLASS, ClubhouseText.KIT_WATCH,
                    "&7" + Text.plain(live.title()), "&7Fly round and see who leads.",
                    "&7/hcm play clubhouse brings you back."));
        }
        if (next != null) {
            KitItems.put(inv, 3, KitItems.item(this, GO_NOW, Material.LIME_CONCRETE, ClubhouseText.goNow(next.name()),
                    "&7Skip the wait.", "&7Off you go!"));
            KitItems.put(inv, 5, KitItems.item(this, STAY, Material.ORANGE_DYE, ClubhouseText.KIT_STAY,
                    "&7Stay in the Clubhouse.", "&7Pick a game when you're ready."));
        }
        KitItems.put(inv, 6, KitItems.item(this, GAMES, Material.BOOKSHELF, ClubhouseText.KIT_GAMES,
                "&7Courses, mini golf and cabinets.", "&7Luck games are outside."));
        KitItems.put(inv, 8, KitItems.item(this, LEAVE, Material.OAK_DOOR, ClubhouseText.KIT_LEAVE,
                "&7Click twice to go home.", "&7Your things come back."));
        inv.setHeldItemSlot(next != null ? 3 : 2);
    }

    /** The kit's Party: the party race's lobby screen. */
    private void openParty(Player p) {
        TimeTrials t = trials();
        if (t == null || raceLobby(p.getUniqueId()) == null) {
            p.sendMessage(Text.of("&7You're not in a party."));
            return;
        }
        games().guard(t, () -> t.party().openLobby(p, null));
    }

    /**
     * The kit's Results: the last results screen, or the board's lines in chat; 0.38.3: the player's own
     * results from a solo run or a round alone here, while they are newer than the shared ones.
     */
    private void openResults(Player p) {
        Mine m = mine.get(p.getUniqueId());
        Results r = last;
        if (ownNewer(p.getUniqueId())) {
            m.opener().accept(p);
            return;
        }
        if (r == null) {
            p.sendMessage(Text.of(ClubhouseText.NO_RESULTS));
            return;
        }
        if (r.opener() != null) {
            r.opener().accept(p);
            return;
        }
        p.sendMessage(Text.of(r.sheet().title()));
        for (String row : r.sheet().rows()) {
            p.sendMessage(Text.of("  " + row));
        }
    }

    private PartyLobby raceLobby(UUID id) {
        PartyLobby l = games().parties().of(id);
        return l != null && l.kind() == PartyLobby.Kind.RACE ? l : null;
    }

    // ---- the board and the podium ----------------------------------------------------------------

    /** The board's sheet now: a race going on (live, at most once a second), else the last result, or the idle board. */
    ClubBoard.Sheet sheet() {
        LiveRace live = followed();
        if (live != null) {
            return live.sheet();
        }
        Results r = last;
        return r == null ? ClubBoard.idle() : r.sheet();
    }

    // ---- races going on: the live board, Watch live and cheers ------------------------------------

    /** Every race and golf group going on now, read-only. */
    private List<LiveRace> liveRaces() {
        List<LiveRace> out = new ArrayList<>();
        out.addAll(com.dierks.homecraft.games.event.ClubNight.live(games()));
        out.addAll(com.dierks.homecraft.games.trial.ClubRaces.live(games()));
        out.addAll(com.dierks.homecraft.games.golf.ClubGolf.live(games()));
        return out;
    }

    /**
     * The race the Clubhouse follows (the board, the kit's Watch live): Race Night, else a party race
     * or golf group whose players come back here, else any in the Clubhouse's world; {@code null} for none.
     */
    LiveRace followed() {
        return follow(watch == null ? List.of() : watch.live(), world());
    }

    /** {@link #followed()} over {@code live} in {@code world}. */
    static LiveRace follow(List<LiveRace> live, String world) {
        LiveRace linked = null;
        LiveRace any = null;
        for (LiveRace r : live) {
            if (world == null || !world.equalsIgnoreCase(r.world())) {
                continue;
            }
            if (r.key().startsWith("night:")) {
                return r;
            }
            if (r.linked() && linked == null) {
                linked = r;
            }
            if (any == null) {
                any = r;
            }
        }
        return linked != null ? linked : any;
    }

    /** The race for someone who taps Watch: their party's, Race Night, or the one the Clubhouse follows. */
    private LiveRace raceFor(UUID id) {
        if (watch == null) {
            return null;
        }
        PartyLobby l = raceLobby(id);
        if (l != null && watch.race("party:" + l.id()) != null) {
            return watch.race("party:" + l.id());
        }
        return followed();
    }

    /** Watch live: the race {@code target} is in, or the one followed. */
    void watchNow(Player p, UUID target) {
        LiveRace r = target != null ? watch.raceOf(target) : raceFor(p.getUniqueId());
        String why = watch.start(p, r);
        if (why != null) {
            p.sendMessage(Text.of(why));
        } else {
            kits.remove(p.getUniqueId());
        }
    }

    /**
     * Whether the player's own results (a solo run or a round alone here) are what their Results opens: kept,
     * and newer than the shared ones (the last race's). The kit's Results is named for it (R383-J3).
     */
    private boolean ownNewer(UUID id) {
        Mine m = mine.get(id);
        return m != null && (last == null || m.at() >= lastAt);
    }

    /** Give the kit again (back from Watch live, or the Results item's name changed). */
    void kitAgain(Player p) {
        kits.remove(p.getUniqueId());
        giveKit(p);
    }

    /**
     * {@code /hcm play watch [<player>]}: in the Clubhouse, watch the race that player is in (or the one
     * it follows), or come back from watching; from outside, in as a spectator who watches at once.
     */
    public static void watchCommand(GamesService games, Player p, String targetName) {
        Clubhouse c = running(games);
        if (c == null) {
            refuse(p, ClubhouseText.CLOSED);
            return;
        }
        games.guard(c, () -> c.watchCommand(p, targetName));
    }

    private void watchCommand(Player p, String targetName) {
        UUID id = p.getUniqueId();
        UUID target = null;
        if (targetName != null && !targetName.isBlank()) {
            Player t = Bukkit.getPlayerExact(targetName);
            if (t == null) {
                refuse(p, "&c" + targetName + " isn't online.");
                return;
            }
            target = t.getUniqueId();
            if (watch.raceOf(target) == null) {
                refuse(p, "&7" + t.getName() + " isn't racing right now.");
                return;
            }
        }
        if (watch.watching(id) && target == null) {
            watch.stop(p, WatchLive.BACK);
            return;
        }
        if (!visits.in(id)) {
            if (games().sessions().session(p) != null) {
                refuse(p, "&cFinish or leave your game first - /hcm leave");
                return;
            }
            enter(p, kindFor(id), true, true);
            return;
        }
        if (watch.watching(id)) {
            watch.stop(p, null);
        }
        watchNow(p, target);
    }

    /** {@code /hcm play cheer}: a cheer for the racers of the race being watched (once every 10 s). */
    public static void cheerCommand(GamesService games, Player p) {
        Clubhouse c = running(games);
        if (c == null) {
            refuse(p, ClubhouseText.CLOSED);
            return;
        }
        games.guard(c, () -> c.cheer(p));
    }

    private void cheer(Player p) {
        UUID id = p.getUniqueId();
        if (!visits.in(id)) {
            refuse(p, "&7Cheer from the Clubhouse, or while you watch live - /hcm play clubhouse");
            return;
        }
        LiveRace r = watch.watching(id) ? watch.watched(id) : followed();
        // 0.38.3 (R383-J1): no race going, the cheer reaches everyone on a solo run or a round alone, as Now
        // playing shows them ("Cheer them on")
        Collection<UUID> racers = r != null ? r.racers() : Cheers.solo(
                com.dierks.homecraft.games.trial.TrialsNow.runs(games()),
                com.dierks.homecraft.games.golf.GolfNow.rounds(games()), id);
        if (racers.isEmpty()) {
            p.sendMessage(Text.of(Cheers.NOBODY));
            return;
        }
        long now = now();
        if (!cheers.allow(id, now)) {
            p.sendActionBar(Text.of("&7You can cheer again in " + cheers.waitSeconds(id, now) + " s."));
            return;
        }
        int heard = 0;
        for (UUID racer : racers) {
            Player q = online(racer);
            if (q != null && !racer.equals(id) && cheersOn(racer)) {
                q.sendActionBar(Text.of(Cheers.line(p.getName())));
                heard++;
            }
        }
        if (heard > 0 && familyWeek != null) {
            familyWeek.scored(id, FamilyWeekRules.CHEER); // 0.38.4: the family week
        }
        p.sendMessage(Text.of(heard > 0 ? "&dYou cheered for the racers!" : "&7Nobody racing has cheers on."));
    }

    /** Whether a racer sees cheers ({@code /hcm play cheers off} turns them off). */
    boolean cheersOn(UUID racer) {
        return cheersOn.computeIfAbsent(racer, r -> {
            try {
                return !"off".equalsIgnoreCase(games().dao().pref(r, Cheers.PREF));
            } catch (java.sql.SQLException | RuntimeException e) {
                return true;
            }
        });
    }

    /** {@code /hcm play cheers [on|off]}: show, or switch the cheers a racer sees. */
    public static void cheersCommand(GamesService games, Player p, String[] args) {
        UUID id = p.getUniqueId();
        try {
            if (args != null && args.length >= 3 && (args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) {
                boolean on = args[2].equalsIgnoreCase("on");
                games.dao().setPref(id, Cheers.PREF, on ? null : "off");
                Clubhouse c = games.game(SPEC.id()) instanceof Clubhouse cc ? cc : null;
                if (c != null) {
                    c.cheersOn.put(id, on);
                }
                p.sendMessage(Text.of(on ? "&aYou'll see cheers while you race." : "&7No more cheers while you race."
                        + " &8(/hcm play cheers on)"));
                return;
            }
            boolean on = !"off".equalsIgnoreCase(games.dao().pref(id, Cheers.PREF));
            p.sendMessage(Text.of("&eCheers while you race: " + (on ? "&aon" : "&7off") + " &7- /hcm play cheers on|off"));
        } catch (java.sql.SQLException | RuntimeException e) {
            p.sendMessage(Text.of("&cThat can't be done right now."));
        }
    }

    /** Draw the board when its text changed (a new result), or when it had to be made again. */
    private void drawBoard(long now) {
        WallBoards.Wall at = room.wall(WallBoards.RESULTS); // 0.38.3: locked in place on its wall
        if (at == null) {
            return;
        }
        ClubBoard.Sheet s = sheet();
        if (!display.standing(room.world(), at)) {
            board.forget();
        }
        if (board.offer(s, now) && !display.show(room.world(), at, WallBoards.pad(s.text()))) {
            board.forget(); // its chunk isn't loaded: drawn once it is
        }
    }

    /** Place a top-three racer on their pedestal. */
    private void placeOnPodium(Player p, Photo ph) {
        int n = ph.top.indexOf(p.getUniqueId()) + 1;
        ClubhouseSite.Spot s = room == null ? null : room.podium(n);
        World w = room == null ? null : Bukkit.getWorld(room.world());
        if (s == null || w == null || ph.placed.contains(p.getUniqueId())) {
            return;
        }
        if (games().sessions().teleport(p, new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0f))) {
            ph.placed.add(p.getUniqueId());
            visits.recheck(p.getUniqueId(), now());
        }
    }

    /** The podium: place whoever of the three is here, then "Photo time!" once they all are (or in 5 s). */
    private void podium(long now) {
        Photo ph = photo;
        if (ph == null) {
            return;
        }
        List<UUID> coming = new ArrayList<>();
        for (UUID id : ph.top) {
            Player p = online(id);
            if (p != null && visits.in(id)) {
                placeOnPodium(p, ph);
            }
            if (p != null && !ph.placed.contains(id) && (visits.in(id) || arrivingFrom(p))) {
                coming.add(id);
            }
        }
        if (!coming.isEmpty() && now < ph.until) {
            return;
        }
        photo = null;
        for (ClubVisits.Visit v : visits.all()) {
            Player p = online(v.id());
            if (p != null) {
                try {
                    p.showTitle(Title.title(Text.of(ClubhouseText.PHOTO), Text.of(familyWeek != null
                                    && familyWeek.showing() ? ClubBoardText.PHOTO_FAMILY : ClubhouseText.PHOTO_SMALL),
                            Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(PHOTO_TICKS * 50L),
                                    Duration.ofMillis(400))));
                } catch (RuntimeException | LinkageError ignored) {
                    // a title is decoration
                }
            }
        }
        if (!ph.placed.isEmpty() && ph.placed.contains(ph.top.get(0))) {
            display.firework(room.world(), room.podium(1), 3.0);
        }
    }

    /** Whether the player is still in another game's session that is on its way here (a race's end). */
    private boolean arrivingFrom(Player p) {
        Session s = games().sessions().session(p);
        return s != null && s.phase() == Session.Phase.ACTIVE && !id().equals(s.gameId());
    }

    // ---- the door (the other games' only way in) --------------------------------------------------

    /**
     * The Clubhouse's door for the races and golf, or {@code null} while it is off, closed or not
     * built: then every flow does exactly what it did before. Every call through it runs inside the
     * Clubhouse's own guard, so a bug here switches the Clubhouse off and never stops a race.
     */
    public static ClubDoor door(GamesService games) {
        Clubhouse c = running(games);
        return c == null ? null : c.guarded();
    }

    /** The Clubhouse when it is on and its room open, or {@code null}. */
    static Clubhouse running(GamesService games) {
        try {
            if (games != null && games.game(SPEC.id()) instanceof Clubhouse c && games.enabled(c) && c.room != null
                    && c.room.open()) {
                return c;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private ClubDoor guarded() {
        GamesService g = games();
        Clubhouse self = this;
        return new ClubDoor() {
            private <T> T call(Supplier<T> s, T fallback) {
                return g.guard(self, s, fallback);
            }

            @Override
            public String world() {
                return call(self::world, "");
            }

            @Override
            public boolean seatable(UUID player) {
                return call(() -> self.seatable(player), false);
            }

            @Override
            public boolean spectator(UUID player) {
                return call(() -> self.spectator(player), false);
            }

            @Override
            public boolean handOut(Player p, Game to, String ref) {
                return call(() -> self.handOut(p, to, ref), false);
            }

            @Override
            public void handBack(Player p, ClubVisits.Kind kind) {
                g.guard(self, () -> self.handBack(p, kind));
            }

            @Override
            public boolean takeIn(Player p, ClubVisits.Kind kind, String line) {
                return call(() -> self.takeIn(p, kind, line), false);
            }

            @Override
            public boolean closingForRestart() {
                return call(self::closingForRestart, true); // unsure: home, as with no Clubhouse
            }

            @Override
            public boolean partyAfter() {
                return call(self::partyAfter, false);
            }

            @Override
            public boolean nightAfter() {
                return call(self::nightAfter, false);
            }

            @Override
            public boolean golfAfter() {
                return call(self::golfAfter, false);
            }

            @Override
            public boolean soloFirst() {
                return call(self::soloFirst, false);
            }

            @Override
            public boolean soloAfter() {
                return call(self::soloAfter, false);
            }

            @Override
            public void soloResult(Player p, Consumer<Player> opener) {
                g.guard(self, () -> self.soloResult(p, opener));
            }

            @Override
            public void result(ClubBoard.Sheet sheet, Consumer<Player> opener) {
                g.guard(self, () -> self.result(sheet, opener));
            }

            @Override
            public void podium(List<UUID> topThree) {
                g.guard(self, () -> self.podium(topThree));
            }
        };
    }

    @Override
    public String world() {
        ClubhouseRoom r = room;
        return r == null ? "" : r.world();
    }

    @Override
    public boolean seatable(UUID player) {
        Player p = online(player);
        return p != null && visits.in(player) && inOurSession(p);
    }

    @Override
    public boolean spectator(UUID player) {
        ClubVisits.Visit v = visits.get(player);
        return v != null && v.spectator();
    }

    @Override
    public boolean handOut(Player p, Game to, String ref) {
        UUID id = p.getUniqueId();
        if (!visits.in(id) || !inOurSession(p) || to == null) {
            return false;
        }
        if (watch != null && watch.watching(id)) {
            watch.stop(p, null); // off the course and back in adventure mode, then to the grid
        }
        return handOut(() -> games().sessions().bankExtras(p), () -> games().sessions().passTo(p, to, ref),
                () -> kitAgain(p), () -> gone(id, p));
    }

    /**
     * The hand-out's order. The Clubhouse's kit goes and the next game gives its own, in its own slots:
     * anything else (an auction win, a Mini that arrived here) is banked in the session's carry FIRST
     * and comes home (#3). If that can't be done (the database is failing) nobody is handed out, since
     * the next kit would go over it (final gate #16): they stay, with their things, and the race or
     * round says it couldn't take them. A hand-over that fails after the bank gives the Clubhouse's
     * kit back (the bank took it with the rest).
     *
     * @return whether they were handed out
     */
    static boolean handOut(BooleanSupplier bank, BooleanSupplier pass, Runnable kitBack, Runnable gone) {
        if (!bank.getAsBoolean()) {
            return false;
        }
        if (!pass.getAsBoolean()) {
            kitBack.run();
            return false;
        }
        gone.run();
        return true;
    }

    @Override
    public void handBack(Player p, ClubVisits.Kind kind) {
        if (games().sessions().passTo(p, this, REF)) {
            games().sessions().stripKit(p); // the race's kit only (#3)
            toSpawn(p);
            welcome(p, kind, false, ClubhouseText.NO_GRID);
        } else {
            games().sessions().leave(p, EndReason.FINISH);
        }
    }

    @Override
    public boolean takeIn(Player p, ClubVisits.Kind kind, String line) {
        ClubhouseRoom r = room;
        if (r == null || !r.open() || p == null || !p.isOnline()) {
            return false;
        }
        if (closingForRestart()) {
            // Nobody comes in from a little over a minute before the restart, not even from a race or a round
            // that ends then: one taken in during its last minute was still here when it came, and ClubVisits
            // promises nobody ever is. The caller sends them home, as with no Clubhouse. Earlier in the hold a
            // race's end still comes in (CLUBHOUSE-SPEC §7), warned and home a minute later with everyone.
            return false;
        }
        World w = Bukkit.getWorld(r.world());
        ClubhouseSite.Spot s = r.spawn(nextSpot++);
        Session sess = games().sessions().session(p);
        if (w == null || s == null || sess == null || sess.phase() != Session.Phase.ACTIVE
                || !w.getName().equals(sess.world())) {
            return false; // not in a session in the Clubhouse's world: the caller sends them home
        }
        if (!games().sessions().teleport(p, new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0f))) {
            return false;
        }
        if (!games().sessions().passTo(p, this, REF)) {
            return false;
        }
        games().sessions().stripKit(p); // the race's kit only (#3)
        p.setFallDistance(0f);
        welcome(p, kind, false, line);
        visits.recheck(p.getUniqueId(), now());
        return true;
    }

    @Override
    public boolean closingForRestart() {
        RestartHold hold = games().restartHold();
        long now = now();
        return ClubVisits.closedForRestart(now, hold.holding(now), hold.next(now));
    }

    @Override
    public boolean partyAfter() {
        return settings().partyAfter();
    }

    @Override
    public boolean nightAfter() {
        return settings().raceNightAfter();
    }

    @Override
    public boolean golfAfter() {
        return settings().golfAfter();
    }

    @Override
    public boolean soloFirst() {
        return settings().soloFirst();
    }

    @Override
    public boolean soloAfter() {
        return settings().soloAfter();
    }

    /**
     * A solo run's or a round alone's results, for that player only (0.38.3): kept as what their Results
     * opens (the kit's Results is named for it), and opened for them once they have landed in the room (the
     * move back is usually async, into an unloaded chunk, and a landing closes any screen open: R383-S2),
     * unless they are looking at another screen or watching live by then. The board and the shared Results
     * are never touched.
     */
    @Override
    public void soloResult(Player p, Consumer<Player> opener) {
        if (p == null || opener == null) {
            return;
        }
        UUID id = p.getUniqueId();
        Mine m = new Mine(opener, now());
        mine.put(id, m);
        if (ctx.plugin() == null) {
            return; // the framework's own tests: no server to open a screen on
        }
        if (visits.in(id)) {
            kitAgain(p); // takeIn's kit came before these results existed: Results reads "your last run" now
        }
        games().later(this, RESULT_TICKS, () -> soloOpen(id, m, RESULT_TRIES));
    }

    /** Open {@code m} once its player is seen in the room, looking at no other screen; {@code tries} more looks. */
    private void soloOpen(UUID id, Mine m, int tries) {
        Player q = online(id);
        if (q == null || !visits.in(id) || mine.get(id) != m || (watch != null && watch.watching(id))) {
            return; // gone, or a newer result of theirs took over
        }
        ClubhouseRoom r = room;
        if (r == null || !r.contains(q.getWorld().getName(), q.getX(), q.getY(), q.getZ())) {
            if (tries > 0) {
                games().later(this, RESULT_TICKS, () -> soloOpen(id, m, tries - 1));
            }
            return; // not landed yet
        }
        visits.seen(id); // the arrival check's, done
        InventoryType open = q.getOpenInventory().getType();
        if (open == InventoryType.CRAFTING || open == InventoryType.CREATIVE) {
            m.opener().accept(q);
        }
    }

    @Override
    public void result(ClubBoard.Sheet sheet, Consumer<Player> opener) {
        if (sheet != null) {
            last = new Results(sheet, opener);
            lastAt = now();
            for (UUID id : new ArrayList<>(mine.keySet())) {
                Player q = online(id);
                if (q != null && visits.in(id)) {
                    kitAgain(q); // their Results opens the race now: named for it again
                }
            }
        }
    }

    @Override
    public void podium(List<UUID> topThree) {
        List<UUID> top = topThree == null ? List.of() : topThree.subList(0, Math.min(3, topThree.size()));
        if (!top.isEmpty() && room != null && room.podium(1) != null) {
            photo = new Photo(top, now() + PHOTO_WAIT_MS);
        }
    }

    // ---- the box guard --------------------------------------------------------------------------

    /** Whether (x, y, z) of {@code world} is in the generated Clubhouse's box while it is guarded. */
    private boolean guardedAt(String world, int x, int y, int z) {
        ClubhouseRoom r = room;
        return r != null && r.guarded() && world != null && world.equalsIgnoreCase(r.world()) && r.box().contains(x, y, z);
    }

    /**
     * A player's edit inside the box (admins too) is refused here, before Fresh Courses' own area
     * guard, so the line names the Clubhouse. Everything else changing the box (flowing, pistons,
     * explosions) is the area guard's ({@link GenRegionGuard}).
     */
    private void guardEdits(GamesService g) {
        EventPriority p = EventPriority.LOWEST;
        g.on(this, BlockPlaceEvent.class, p, true, e -> refuseEdit(e, e.getPlayer(), e.getBlock()));
        g.on(this, BlockBreakEvent.class, p, true, e -> refuseEdit(e, e.getPlayer(), e.getBlock()));
        g.on(this, PlayerBucketEmptyEvent.class, p, true, e -> refuseEdit(e, e.getPlayer(), e.getBlock()));
        g.on(this, PlayerBucketFillEvent.class, p, true, e -> refuseEdit(e, e.getPlayer(), e.getBlock()));
        g.on(this, SignChangeEvent.class, p, true, e -> refuseEdit(e, e.getPlayer(), e.getBlock()));
        g.on(this, HangingPlaceEvent.class, p, true,
                e -> refuseEdit(e, e.getPlayer(), e.getEntity().getLocation().getBlock()));
        g.on(this, EntityPlaceEvent.class, p, true,
                e -> refuseEdit(e, e.getPlayer(), e.getEntity().getLocation().getBlock()));
    }

    /** What an admin reads when they try to change the Clubhouse. */
    static final String GUARDED = "&cThat's the Clubhouse - it can't be changed. &7Use /hcm games clubhouse to"
            + " look after it.";

    private void refuseEdit(Cancellable e, Player player, Block block) {
        if (block == null || !guardedAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())) {
            return;
        }
        e.setCancelled(true);
        if (player != null) {
            long now = System.currentTimeMillis();
            Long last = toldGuarded.get(player.getUniqueId());
            if (last == null || now - last >= 2_000L) {
                toldGuarded.put(player.getUniqueId(), now);
                player.sendActionBar(Text.of(GUARDED));
            }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Back to an arrival spot (a session teleport). */
    void toSpawn(Player p) {
        ClubhouseRoom r = room;
        World w = r == null ? null : Bukkit.getWorld(r.world());
        ClubhouseSite.Spot s = r == null ? null : r.spawn(nextSpot++);
        if (w != null && s != null && games().sessions().teleport(p, new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0f))) {
            p.setFallDistance(0f);
            visits.recheck(p.getUniqueId(), now());
        }
    }

    /** Whether the player is in an ACTIVE Clubhouse session. */
    private boolean inOurSession(Player p) {
        Session s = games().sessions().session(p);
        return s != null && id().equals(s.gameId()) && s.phase() == Session.Phase.ACTIVE;
    }

    private void noPushOff(UUID id, Player p) {
        NoPush np = noPush();
        if (np != null) {
            np.off(id, p == null ? null : p.getName());
        }
    }

    /** The no-push team a test passes; {@code null}: the games' own. */
    private NoPush pushes;

    /** The games' no-push team (or the one a test passed). */
    private NoPush noPush() {
        return pushes != null ? pushes : games().noPush();
    }

    /** A test's own no-push team (over a fake scoreboard). */
    void pushes(NoPush team) {
        this.pushes = team;
    }

    /** A test's own Watch live (over fake viewers). */
    void watch(WatchLive w) {
        this.watch = w;
    }

    /** Watch live (for the tests). */
    WatchLive watchLive() {
        return watch;
    }

    /** A test's own room (over a fake world), so the ways in can be walked with no server. Final gate, group B. */
    void room(ClubhouseRoom r) {
        this.room = r;
    }

    private static void refuse(Player p, String line) {
        p.sendMessage(Text.of(line));
        try {
            Sounds.refused(p);
        } catch (RuntimeException | LinkageError ignored) {
            // a sound is decoration
        }
    }

    private static Player online(UUID id) {
        try {
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private NightRunner night() {
        Game g = games().game(RaceNight.SPEC.id());
        return g instanceof RaceNight r && games().enabled(r) ? r.night() : null;
    }

    private TimeTrials trials() {
        Game g = games().game(TimeTrials.SPEC.id());
        return g instanceof TimeTrials t && games().enabled(t) ? t : null;
    }

    long now() {
        return games().clock().nowMillis();
    }

    GamesService games() {
        return ctx.games();
    }

    HomeCraftManagement plugin() {
        return ctx.plugin();
    }

    Logger log() {
        return plugin() == null ? Logger.getLogger("HomeCraftMgmt") : plugin().getLogger();
    }

    /** The live settings (read on every use, never cached across a reload). */
    ClubhouseSettings settings() {
        return ctx.games().settings(SPEC);
    }

    /** The running room, or {@code null} while the Clubhouse is off. */
    ClubhouseRoom room() {
        return room;
    }

    /** The visitors (for the admin tool and the tests). */
    ClubVisits visits() {
        return visits;
    }

    /** Whether a podium photo is waiting (the family week's ceremony waits for it). */
    boolean photoPending() {
        return photo != null;
    }

    /** 0.38.4: the family week, or {@code null} with no server. */
    FamilyWeek familyWeek() {
        return familyWeek;
    }

    /** The walls' boards, or {@code null} with no server. */
    ClubBoards boards() {
        return boards;
    }

    // ---- for the party and Race Night screens (their buttons show only while this is open) --------

    /** Whether the Clubhouse's buttons show: it is on, and its room built and checked. */
    public static boolean offered(GamesService games) {
        return running(games) != null;
    }

    /** "Go to the Clubhouse" / "Wait in the Clubhouse": in as a racer who waits (inside the Clubhouse's guard). */
    public static void go(GamesService games, Player p) {
        Clubhouse c = running(games);
        if (c == null) {
            refuse(p, ClubhouseText.CLOSED);
            return;
        }
        games.guard(c, () -> c.visit(p));
    }

    /** "Watch": in as a spectator, never seated (inside the Clubhouse's guard). */
    public static void watch(GamesService games, Player p) {
        Clubhouse c = running(games);
        if (c == null) {
            refuse(p, ClubhouseText.CLOSED);
            return;
        }
        games.guard(c, () -> c.spectate(p));
    }

    /** Whether the player is in the Clubhouse now (any reason). */
    public static boolean inside(GamesService games, UUID player) {
        Clubhouse c = running(games);
        return c != null && c.visits.in(player);
    }

    // ---- 0.38.3: through the Clubhouse (courses and mini golf) --------------------------------------

    /**
     * 0.38.3 (design C1, the owner: "go to the clubhouse when click into a game"): a course's or a round's
     * Start goes through the Clubhouse first: in as a visitor, "Off to Sky Rings!" and a countdown, then on to
     * it ({@link #goNext}). {@code null} when the Clubhouse isn't open (the caller starts it straight away, as
     * before); false when the entry was refused (they were told why, as a direct start would tell them).
     */
    public static Boolean lobby(GamesService games, Player p, ClubNext next) {
        Clubhouse c = running(games);
        if (c == null || p == null || next == null) {
            return null;
        }
        return games.guard(c, () -> c.enter(p, c.kindFor(p.getUniqueId()), false, false, next), null);
    }

    /**
     * Whether the player is hanging out in the Clubhouse (in it, not watching live): a friend game's invite
     * reaches them there, and its screen opens where they stand.
     */
    public static boolean hangingOut(GamesService games, UUID player) {
        Clubhouse c = running(games);
        return c != null && c.visits.in(player) && (c.watch == null || !c.watch.watching(player));
    }

    /** Once a second: the countdowns on the action bar ("Sky Rings in 3"), and the trips that are due. */
    private void countdowns(long now) {
        for (ClubLobby.Tick t : lobby.second(now)) {
            Player p = online(t.player());
            if (p == null || !visits.in(t.player())) {
                lobby.forget(t.player());
            } else if (t.left() <= 0) {
                goNext(p);
            } else {
                p.sendActionBar(Text.of(ClubhouseText.countdown(t.next().name(), t.left())));
            }
        }
    }

    /**
     * "Go now", or the countdown ran out: the trip is handed to its game, which hands the player out from
     * here to the start (its gate, the warm-up choice carried across, its own teleport). Refused (the
     * restart hold began, the course closed meanwhile), they stay here, told why, and the kit loses Go now.
     */
    private void goNext(Player p) {
        ClubNext n = lobby.take(p.getUniqueId());
        if (n == null) {
            return;
        }
        kits.remove(p.getUniqueId());
        Game g = games().game(n.gameId());
        if (g instanceof TimeTrials t && games().enabled(t)) {
            games().guard(t, () -> t.fromLobby(p, n.ref(), n.warmUp()));
        } else if (g instanceof com.dierks.homecraft.games.golf.MiniGolf m && games().enabled(m)) {
            games().guard(m, () -> m.fromLobby(p, n.ref()));
        } else {
            p.sendMessage(Text.of(ClubhouseText.NEXT_CLOSED));
        }
        if (visits.in(p.getUniqueId())) {
            giveKit(p); // still here (refused, and told why): the kit without Go now
        }
    }

    /** "Stay here": the trip is called off and they hang out (the Games item starts one again). */
    private void stay(Player p) {
        if (lobby.stay(p.getUniqueId()) == null) {
            return;
        }
        p.sendMessage(Text.of(ClubhouseText.STAYING));
        p.sendActionBar(Text.of(""));
        kitAgain(p);
    }

    /** A title for one player ("Off to Sky Rings!"); a title is decoration. */
    private static void title(Player p, String big, String small) {
        try {
            p.showTitle(Title.title(Text.of(big), Text.of(small), Title.Times.times(Duration.ofMillis(200),
                    Duration.ofMillis(2_500), Duration.ofMillis(400))));
        } catch (RuntimeException | LinkageError ignored) {
            // a title is decoration
        }
    }

    /** Someone on the way in for a trip on to {@code next} (a test's own, as {@link #lobby} records one). */
    void expect(UUID id, ClubVisits.Kind kind, ClubNext next) {
        arriving.put(id, new Arrival(kind, false, false, next));
    }

    /** The trip the next arrival of {@code id} carries on to, or {@code null} (none, or not on the way). */
    ClubNext arrivingFor(UUID id) {
        Arrival a = arriving.get(id);
        return a == null ? null : a.next();
    }

    /** The lobby's countdowns (for the tests). */
    ClubLobby lobby() {
        return lobby;
    }

    // ---- for /hcm games check --------------------------------------------------------------------

    /** Whether the Clubhouse is running (on and started). */
    public boolean running() {
        return room != null;
    }

    /** Whether visitors may come in now. */
    public boolean ready() {
        ClubhouseRoom r = room;
        return r != null && r.open();
    }

    /** Why its room closed itself, or {@code null}. */
    public String closedWhy() {
        ClubhouseRoom r = room;
        return r == null ? null : r.closedWhy();
    }

    /** Whether its generated room has been built and checked in this run. */
    public boolean verified() {
        ClubhouseRoom r = room;
        return r != null && r.verified();
    }

    /** Whether the owner-built room is on, and its spots. */
    public boolean handBuilt() {
        ClubhouseRoom r = room;
        return r != null && r.hand();
    }

    /** The owner-built room's world, or {@code null} while the generated room is the Clubhouse. */
    public String handWorld() {
        ClubhouseRoom r = room;
        return r == null || !r.hand() ? null : r.world();
    }

    /** The owner-built room's spots ("arrival not set", ...), for the check. */
    public List<String> handSpots() {
        ClubhouseRoom r = room;
        return r == null ? List.of() : r.handSpots();
    }

    /** Whether every owner-built spot is set. */
    public boolean handComplete() {
        ClubhouseRoom r = room;
        return r != null && r.handComplete();
    }
}
