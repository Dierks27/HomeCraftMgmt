package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.clubhouse.ClubDoor;
import com.dierks.homecraft.games.clubhouse.ClubRoute;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.gen.engine.GenRegionGuard;
import com.dierks.homecraft.games.lava.rules.LavaMode;
import com.dierks.homecraft.games.lava.rules.LavaRound;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.lava.LavaKidMenu;
import com.dierks.homecraft.gui.games.lava.LavaModeMenu;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
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
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The Floor Is Lava (the owner: "is there any way we can add a game for Lilah 'The Floor Is Lava'"). Lilah is four,
 * can't read yet and plays with a controller: this is her game first, and the grown-ups' too.
 *
 * <p><b>The game.</b> A giant playroom where the players are toy-sized. Stars float over the floor: walk into one
 * to collect it. Then the floor rumbles and glows, and it turns to lava: real lava, made harmless. Hop onto the
 * furniture to stay dry. The lava drains, and there are stars again. Three ways to play ({@link LavaMode}):
 * <b>Little</b> (nobody can lose; collect every star), <b>Group</b> (one shared count, the lava higher every round)
 * and <b>Challenge</b> (the lava keeps rising; the last one standing wins).
 *
 * <p><b>0.57.0: harder, and never the same twice</b> (the owner: "the floor is lava game is WAY too easy ... it would
 * be a lot more fun if I didn't have to [pretend I can't keep up]"). The room is bigger and its furniture moves: a new
 * layout every game, and every round of Group and Challenge ({@link LavaLayout}, {@link LavaShift}); the lava's timing
 * is drawn from ranges; stars come out on the furniture during the lava; Group has a star goal a round and hearts,
 * Challenge needs stars every round; most rounds bring a surprise. Little stays unlosable. See {@link LavaService}.
 *
 * <p>Id {@code floor_is_lava}, alias {@code lava}; kind {@link GameKind#TRIAL} (a free skill game, as Falling
 * Floors is); never a game of chance, never behind {@code hcm.games.chance}, and <b>it moves no tokens at all</b>:
 * its only rewards are the bests it records ({@link LavaScores}), which the secret trophy room shows as trophies.
 * It is never the featured game (there would be nothing to pay). Ships ON.
 *
 * <p><b>How it is put together</b> (as Falling Floors): the round rules are pure ({@code games.lava.rules}); the
 * running room is {@link LavaService} (the build and its check, the lava, what every round event means), talking to
 * the server only through {@link LavaHost} ({@link LiveLavaHost} here). This class is the Game: the tile, the ways
 * in, the listeners and tasks (all through the framework, so they run in the game's guard and go when it stops),
 * and the admin commands ({@link LavaAdmin}).
 *
 * <p><b>Getting in.</b>
 * <ul>
 *   <li>The Games screen's tile, or {@code /hcm play floor_is_lava}: a screen with the three modes as pictures.
 *       From home, that is a world session of its own (the player's things are kept safe and come back at the
 *       end); from the Clubhouse's Games item, the visitor's session is handed over in place.</li>
 *   <li>The Clubhouse's Arcade Room has a cabinet for it ({@link #kidTap}). 0.57.0 (the owner: "when you get in
 *       through the arcade machine you should be able to pick any level you want not just easy"): a tap opens the
 *       cabinet's own screen ({@code LavaKidMenu}), three pictures side by side with one, two or three blocks of
 *       magma under them, and a tap on one goes in by the same hand-out a course uses. Whoever came from the
 *       Clubhouse goes back to its Arcade Room when they leave or say All done, never home.</li>
 * </ul>
 *
 * <p><b>Harmless, for real.</b> Everyone in the room is in a world session: no damage from anything, no hunger, no
 * fire, no death, adventure mode, nothing to break or place (the session guard). On top of that this game clears
 * fire, fall and breath from every player every tick ({@link LavaHost#calm}); the box is guarded like the arena's
 * (no flow, no fire, no burning, no edits, admins too); the room is built of blocks that can't burn; and the lava
 * is source blocks placed and taken away by {@link LavaWriter}, never flowing.
 */
public final class FloorIsLava implements Game, com.dierks.homecraft.games.cpu.WorldNpcs.Host {

    public static final GameSpec<LavaSettings> SPEC = new GameSpec<>("floor_is_lava", GameKind.TRIAL,
            LavaSettings.KEYS, LavaSettings.defaults(), LavaSettings::parse, FloorIsLava::new, null);

    /** The kit's actions ({@code leave} is the kit guard's own: two clicks). */
    static final String START = "start";
    static final String AGAIN = "again";
    static final String LEAVE = "leave";

    private final GameContext ctx;
    private final LavaAdmin admin;
    private LavaService service;
    private LiveLavaHost host;
    /** Who was handed out from the Clubhouse: they go back there, not home. */
    private final Set<UUID> fromClub = new HashSet<>();
    private final Map<UUID, Long> toldGuarded = new HashMap<>();

    public FloorIsLava(GameContext ctx) {
        this.ctx = ctx;
        this.admin = new LavaAdmin(this);
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
        return LavaText.NAME;
    }

    /** Never used: The Floor Is Lava moves no tokens. */
    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_FLOORS;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled();
    }

    /** Never today's featured game: the featured bonus is tokens, and this game pays none. */
    @Override
    public boolean featurable() {
        return false;
    }

    @Override
    public List<String> aliases() {
        return List.of("lava");
    }

    @Override
    public List<String> rules() {
        return List.of("Collect the stars. When the floor rumbles, climb!",
                "Stars come out on the furniture during the lava too,",
                "and the furniture moves. The lava can't hurt you.",
                "Little: nobody can lose. Group: a star goal each round.",
                "Challenge: keep collecting; the last one standing wins.");
    }

    /** "&amp;6The Floor Is Lava &amp;7- 2 playing Little": the key fact in the NAME, for Bedrock. */
    @Override
    public ItemStack tile(Player viewer) {
        LavaService s = service;
        boolean open = s != null && s.open();
        List<String> lore = new ArrayList<>();
        for (String line : rules()) {
            lore.add("&7" + line);
        }
        if (viewer != null) {
            List<String> bests = new ArrayList<>();
            for (LavaMode m : LavaMode.values()) {
                Long best = games().scores().best(viewer.getUniqueId(), id(), m.board());
                if (best != null) {
                    bests.add(m.label() + " " + (m == LavaMode.CHALLENGE ? LavaText.rounds(best.intValue())
                            : LavaText.stars(best.intValue())));
                }
            }
            if (!bests.isEmpty()) {
                lore.add("&7Your best: &f" + String.join("&7, &f", bests));
            }
        }
        lore.add(open ? "&eClick to play" : "&7The playroom is being tidied up - back soon!");
        LavaMode busy = s == null ? null : s.busyWith();
        return Menus.icon(Material.LAVA_BUCKET, LavaText.tile(busy == null || s == null ? 0 : s.playing(),
                busy == null ? LavaMode.LITTLE : busy, open), lore.toArray(new String[0]));
    }

    @Override
    public List<GameTile> tiles(Player viewer) {
        return List.of(new GameTile(Tab.TOGETHER, tile(viewer), id(), 0));
    }

    /** {@code /hcm play floor_is_lava} and the tile: the three modes (after the framework's gate). */
    @Override
    public void open(Player player, Runnable back) {
        new LavaModeMenu(plugin(), this, player, back).open(player);
    }

    @Override
    public List<String> statusLines() {
        LavaService s = service;
        return s == null ? List.of("not running") : s.statusLines();
    }

    @Override
    public GameAdmin admin() {
        return admin;
    }

    /**
     * Start the room: its plan, the area guard over its box, the tick and the once-a-second check. Each reads the
     * room running now, so a settings change can start a new one without registering anything twice.
     */
    @Override
    public void start() {
        stopRoom();
        if (ctx.plugin() == null) {
            return; // the framework's own tests: no server
        }
        GamesService g = games();
        WorldEntities.sweep(this); // stars a crash or a reload left
        host = new LiveLavaHost(this);
        service = new LavaService(host);
        GenRegionGuard.register(g, this, () -> (world, x, y, z) -> inBox(service, world, x, y, z), log());
        guardEdits(g);
        g.every(this, 1, 1, () -> {
            LavaService s = service;
            if (s != null) {
                s.tick();
            }
        });
        g.every(this, 20, 20, () -> {
            LavaService s = service;
            if (s != null && !settingsChanged(s)) {
                s.check();
            }
        });
        service.start();
    }

    /**
     * {@code /hcm reload} changed where the room is ({@code origin}, {@code games.fresh.world}): once no round is
     * going, everyone still in the old room goes back with their things and a new room starts, with its own build
     * and check. The round knobs are read at each round's start and need nothing.
     *
     * @return whether a new room was started
     */
    private boolean settingsChanged(LavaService s) {
        LiveLavaHost h = host;
        if (h == null || !startAgain(s, settings(), h.worldName())) {
            return false;
        }
        log().info("The Floor Is Lava: its settings changed - starting its playroom again");
        List<UUID> members = s.round() == null ? List.of() : s.round().members();
        s.stop();
        h.hideAll();
        service = new LavaService(h);
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                sendBack(p);
            }
        }
        service.start();
        return true;
    }

    /** Whether a running room must make way for a new one: it was made for another box or world, and no round is on. */
    static boolean startAgain(LavaService s, LavaSettings now, String worldName) {
        if (s == null || s.builtWith(now, worldName)) {
            return false;
        }
        LavaRound r = s.round();
        return r == null || r.over();
    }

    /** Stop: the lava away, the stars and bars gone, no build left running. Sessions are the framework's to end. */
    @Override
    public void stop() {
        stopRoom();
    }

    private void stopRoom() {
        LavaService s = service;
        service = null;
        if (s != null) {
            s.stop();
        }
        if (host != null) {
            host.hideAll();
            host = null;
        }
        fromClub.clear();
    }

    @Override
    public void onQuit(Player player) {
        left(player);
    }

    @Override
    public void onSessionEnd(Player player, EndReason reason) {
        left(player);
    }

    private void left(Player player) {
        fromClub.remove(player.getUniqueId());
        toldGuarded.remove(player.getUniqueId());
        LavaService s = service;
        if (s != null) {
            s.left(player.getUniqueId());
        }
    }

    @Override
    public void onVoid(Player player) {
        LavaService s = service;
        if (s != null) {
            s.voided(player.getUniqueId());
        }
    }

    @Override
    public void onKitUse(Player player, String action, boolean leftClick) {
        LavaService s = service;
        if (s == null || action == null) {
            return;
        }
        switch (action) {
            case START -> s.startNow(player.getUniqueId());
            case AGAIN -> again(player);
            default -> {
                // "leave" is the kit guard's
            }
        }
    }

    /** Leave game, clicked twice: whoever came from the Clubhouse goes back to it, and keeps their session. */
    @Override
    public boolean onLeaveItem(Player player) {
        return backToClub(player);
    }

    // ---- getting in ---------------------------------------------------------------------------------

    /** The mode screen's choice: into the room for {@code mode}; refused (told why) when it can't be. */
    public void play(Player p, LavaMode mode) {
        join(p, mode, false);
    }

    /**
     * A tap on the Clubhouse's Floor Is Lava cabinet: {@code /hcm play}'s own gate, then (0.57.0) the cabinet's
     * screen, three pictures to pick from with nothing to read ({@link LavaKidMenu}).
     *
     * @return whether the screen opened
     */
    public static boolean kidTap(GamesService games, Player p) {
        Game g = games == null || p == null ? null : games.game(SPEC.id());
        if (!(g instanceof FloorIsLava lava)) {
            return false;
        }
        Refusal refusal = games.canOpen(p, lava);
        if (refusal != null) {
            games.tell(p, refusal);
            return false;
        }
        return games.guard(lava, () -> {
            if (lava.plugin() == null) {
                return lava.join(p, LavaMode.LITTLE, true); // no screens without a server (the framework's tests)
            }
            new LavaKidMenu(lava.plugin(), lava, p).open(p);
            return true;
        }, false);
    }

    /**
     * 0.57.0: the cabinet screen's choice: the gate again (a moment has passed), then into the room for {@code mode}
     * by the cabinet's way (Little joins the Group round going, and they come back to the Arcade Room at the end).
     *
     * @return whether the player is on their way
     */
    public boolean kidPlay(Player p, LavaMode mode) {
        Refusal refusal = games().canOpen(p, this);
        if (refusal != null) {
            games().tell(p, refusal);
            return false;
        }
        return join(p, mode, true);
    }

    /**
     * Into the room. A Clubhouse visitor's session is handed over in place and moved here; anyone else gets a
     * world session of their own.
     *
     * @return whether they are on their way (told why when not)
     */
    boolean join(Player p, LavaMode mode, boolean kid) {
        LavaService s = service;
        UUID id = p.getUniqueId();
        if (s == null) {
            return refuse(p, LavaText.CLOSED);
        }
        String why = s.joinRefusal(id, mode, kid);
        if (why != null) {
            return refuse(p, why);
        }
        World w = s.world().isEmpty() ? null : Bukkit.getWorld(s.world());
        LavaSite.Spot spot = s.entrySpot();
        if (w == null || spot == null) {
            return refuse(p, LavaText.FIXING);
        }
        Location at = new Location(w, spot.x(), spot.y(), spot.z(), spot.yaw(), 0);
        ClubDoor club = Clubhouse.door(games());
        if (club != null && club.seatable(id)) {
            return fromClubhouse(club, p, at, mode, kid);
        }
        return games().sessions().enter(p, this, LavaService.REF, at, q -> arrived(q, mode, kid));
    }

    /**
     * The hand-out from the Clubhouse, as a course's ({@code ClubSolo.seat}): nothing is taken or moved before the
     * entry check passes; the visitor's session becomes this game's in place, then the session's own teleport takes
     * them to the room. A move that can't be made hands them straight back: they are still in the Clubhouse.
     */
    private boolean fromClubhouse(ClubDoor club, Player p, Location at, LavaMode mode, boolean kid) {
        UUID id = p.getUniqueId();
        if (!ClubRoute.sameWorld(club, at.getWorld().getName())) {
            return refuse(p, LavaText.NO_ROOM);
        }
        Refusal closed = games().sessions().entryRefusal(this);
        if (closed != null) {
            games().tell(p, closed);
            return false;
        }
        if (!club.handOut(p, this, LavaService.REF)) {
            return refuse(p, LavaText.NO_ROOM);
        }
        fromClub.add(id);
        boolean moving = games().sessions().teleport(p, at, ok -> {
            if (ok) {
                arrived(p, mode, kid);
            } else {
                fromClub.remove(id);
                club.handBack(p, ClubVisits.Kind.VISIT);
                refuse(p, LavaText.NO_ROOM);
            }
        });
        if (!moving) {
            fromClub.remove(id);
            club.handBack(p, ClubVisits.Kind.VISIT);
            return refuse(p, LavaText.NO_ROOM);
        }
        return true;
    }

    /** In the room, with a session: into the round (the kit comes with it), or back again if it closed meanwhile. */
    private void arrived(Player p, LavaMode mode, boolean kid) {
        p.setFallDistance(0f);
        LavaService s = service;
        String why = s == null ? LavaText.CLOSED : s.joined(p.getUniqueId(), mode, kid);
        if (why != null) {
            p.sendMessage(Text.of(why));
            sendBack(p);
        }
    }

    /** Play again (the end screen, the kit): a new round of the same mode for everyone still in the room. */
    public void again(Player p) {
        LavaService s = service;
        if (s != null) {
            s.again(p.getUniqueId());
        }
    }

    /**
     * A player's time in the room is over (All done, the round's long wait, the room closing): back to the
     * Clubhouse's Arcade Room if they came from it, else home with their things.
     */
    public void sendBack(Player p) {
        Session sess = games().sessions().session(p);
        if (sess == null || !id().equals(sess.gameId())) {
            return;
        }
        if (!backToClub(p)) {
            games().sessions().leave(p, EndReason.FINISH);
        }
    }

    /**
     * Back to the Clubhouse, for a player who was handed out from it: the round forgets them, the session is handed
     * back in place, and they stand in the Arcade Room again. False (nothing changed for them) when they didn't come
     * from it, it is closed or closing for a restart, or it couldn't take them: the caller sends them home.
     */
    private boolean backToClub(Player p) {
        UUID id = p.getUniqueId();
        if (!fromClub.contains(id)) {
            return false;
        }
        ClubDoor club = Clubhouse.door(games());
        if (club == null || club.closingForRestart() || !ClubRoute.sameWorld(club, p.getWorld().getName())) {
            return false;
        }
        left(p); // the round first: the session isn't ending, so nothing else tells it
        LiveLavaHost h = host;
        if (h != null) {
            h.forget(p);
        }
        if (!club.takeIn(p, ClubVisits.Kind.VISIT, LavaText.BACK_IN_CLUB)) {
            return false;
        }
        club.toArcade(p);
        return true;
    }

    private static boolean refuse(Player p, String line) {
        p.sendMessage(Text.of(line));
        try {
            Sounds.refused(p);
        } catch (RuntimeException | LinkageError ignored) {
            // a sound is decoration
        }
        return false;
    }

    // ---- the box's own words for an edit ---------------------------------------------------------------

    /** Whether (x, y, z) in {@code world} is inside the running room's box. */
    static boolean inBox(LavaService s, String world, int x, int y, int z) {
        return s != null && world != null && world.equalsIgnoreCase(s.world()) && s.box().contains(x, y, z);
    }

    /**
     * A player's edit inside the box (admins too) is refused here, before the area guard ({@link GenRegionGuard},
     * which skips an edit already refused), so the line is the playroom's own. Everything else changing the box
     * (flowing, forming, fire, burning, pistons, explosions) is the area guard's.
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

    private void refuseEdit(Cancellable e, Player player, Block block) {
        if (block == null || !inBox(service, block.getWorld().getName(), block.getX(), block.getY(), block.getZ())) {
            return;
        }
        e.setCancelled(true);
        if (player != null) {
            long now = System.currentTimeMillis();
            Long last = toldGuarded.get(player.getUniqueId());
            if (last == null || now - last >= 2_000L) {
                toldGuarded.put(player.getUniqueId(), now);
                player.sendActionBar(Text.of(LavaText.GUARDED));
            }
        }
    }

    // ---- for the host, the screens and the admin tool ------------------------------------------------------

    public GamesService games() {
        return ctx.games();
    }

    HomeCraftManagement plugin() {
        return ctx.plugin();
    }

    Logger log() {
        return plugin() == null ? Logger.getLogger("HomeCraftMgmt") : plugin().getLogger();
    }

    /** The running room, or {@code null} while the game is off. */
    LavaService service() {
        return service;
    }

    /** Stage 2: NPC players in the playroom, never in the round ({@link LavaNpcs}). */
    private final LavaNpcs npcs = new LavaNpcs(this);

    @Override
    public com.dierks.homecraft.games.cpu.WorldNpcs npcs() {
        return npcs;
    }

    /** The live settings (read on every use, never cached across a reload). */
    LavaSettings settings() {
        return ctx.games().settings(SPEC);
    }

    /** Whether a player was handed out from the Clubhouse (and goes back there). */
    boolean cameFromClub(UUID player) {
        return fromClub.contains(player);
    }

    /**
     * What the mode screen shows for {@code mode}: {@code null} when it can be played now, else why not in a few
     * words (the room is in use for another mode, a Challenge has started, it is being tidied).
     */
    public String modeRefusal(Player viewer, LavaMode mode) {
        return modeRefusal(viewer, mode, false);
    }

    /** As {@link #modeRefusal(Player, LavaMode)}, for the cabinet's screen when {@code kid}. */
    public String modeRefusal(Player viewer, LavaMode mode, boolean kid) {
        LavaService s = service;
        return s == null ? LavaText.CLOSED : s.joinRefusal(viewer.getUniqueId(), mode, kid);
    }

    /** How many are in the room, and the mode being played ({@code null}: none). */
    public int playing() {
        LavaService s = service;
        return s == null ? 0 : s.playing();
    }

    public LavaMode busyWith() {
        LavaService s = service;
        return s == null ? null : s.busyWith();
    }

    // ---- for /hcm games check --------------------------------------------------------------------------

    /** Whether its room is running (the game is open and started). */
    public boolean running() {
        return service != null;
    }

    /** Whether players may come in now: the room has been built and checked and it isn't closed. */
    public boolean ready() {
        LavaService s = service;
        return s != null && s.open();
    }

    /** Why its room closed itself, or {@code null}. */
    public String closedWhy() {
        LavaService s = service;
        return s == null ? null : s.closedWhy();
    }
}
