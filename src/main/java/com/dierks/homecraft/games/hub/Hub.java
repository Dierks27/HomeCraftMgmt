package com.dierks.homecraft.games.hub;

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
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.hub.BagMenu;
import com.dierks.homecraft.storage.GenMetaDao;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The Hub (0.44.0; the owner: "We have this huge Spawn that players join into the first time ... we dont use it at
 * all ... do the event in the First spawn/hub so it simply gets used ... we need a way for all players to simply get
 * to and back from the world we play in", and "a 'Bag' that you take home with you and that is the only thing you
 * keep from that world").
 *
 * <p>A visit is a world session, as the Clubhouse's is: one tap ({@code /hcm play hub}, {@code /hcm hub}, the Hub
 * button in {@code /hcm arcade} or the Games screen's Together tab) saves the player's things and takes them to the
 * Hub's arrival spot in Adventure mode with a small kit: <b>Your Bag</b> and <b>Leave</b> (click twice). Nothing comes
 * in with them, so nobody flies or builds (the Hub's world is a Games world: read-only for everyone but admins).
 *
 * <p><b>The Bag</b> is everything that isn't the kit: what an event hands out in the Hub (a turkey's feather, a Turkey
 * Mini) lands in their inventory, the Bag's screen shows it, and when they leave the session banks it in its carry
 * before their things come back, and hands it over at home (written first, never dropped; what doesn't fit waits for
 * {@code /hcm leave}). That is the framework's own carry: nothing new holds anyone's things.
 *
 * <p>The Hub's world keeps its own Multiverse-Inventories group (never the economy worlds'), and is listed in
 * {@code games.worlds}; without either, nobody is let in (the admin's status says which). Nothing here moves tokens.
 */
public final class Hub implements Game {

    public static final GameSpec<HubSettings> SPEC = new GameSpec<>("hub", GameKind.TRIAL, HubSettings.KEYS,
            HubSettings.defaults(), HubSettings::parse, Hub::new, null);

    /** The Hub's own sessions' ref. */
    public static final String REF = "hub";
    /** The kit's actions. */
    static final String BAG = "bag";
    static final String LEAVE = "leave";
    /**
     * Where an admin's arrival spot is kept ({@code world,x,y,z,yaw}): under {@code gen.}, the only keys the store
     * keeps (0.44.0 used {@code hub.arrival}, which the store refused, switching the Hub off at the first {@code here}).
     */
    static final String SPOT_KEY = "gen.hub.arrival";

    static final String NAME = "The Hub";
    static final String CLOSED = "&cThe Hub isn't open right now.";
    static final String ALREADY = "&7You're in the Hub. &eLeave game &7(click twice) takes you home.";
    static final String WELCOME = "&6Welcome to the Hub! &7What you win here goes in &eYour Bag&7, and comes home"
            + " with you.";
    static final String KIT_BAG = "&6Your Bag &7- what goes home";
    static final String KIT_LEAVE = "&cLeave game &7- click twice";

    private final GameContext ctx;
    private final HubAdmin admin = new HubAdmin(this);
    /** The admin's arrival spot, or {@code null} for the world's spawn. */
    private Spot spot;
    private final Set<UUID> kitted = new HashSet<>();
    /** Each WARN once a run (the world missing, not a Games world). */
    private final Set<String> warned = new HashSet<>();

    /** An arrival spot as kept. */
    record Spot(String world, double x, double y, double z, float yaw) {

        String text() {
            return world + "," + x + "," + y + "," + z + "," + yaw;
        }

        static Spot of(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            String[] p = text.split(",");
            if (p.length != 5) {
                return null;
            }
            try {
                return new Spot(p[0].trim(), Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim()),
                        Double.parseDouble(p[3].trim()), Float.parseFloat(p[4].trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    public Hub(GameContext ctx) {
        this.ctx = ctx;
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
        return NAME;
    }

    /** Never used: the Hub moves no tokens. */
    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_RACE_NIGHT;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled();
    }

    @Override
    public boolean featurable() {
        return false;
    }

    @Override
    public List<String> rules() {
        return List.of("The big spawn, where the events are.", "What you win goes in Your Bag.",
                "Leave game takes you home, with your things and your Bag.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.BEACON, "&bThe Hub &7- " + here() + " here", "&7The big spawn, where the"
                + " events are.", "&7What you win comes home", "&7in Your Bag.", "&eClick to go");
    }

    /** Its tile on the Games screen's Together tab while it can be visited. */
    @Override
    public List<GameTile> tiles(Player viewer) {
        if (whyClosed() != null) {
            return List.of();
        }
        return List.of(new GameTile(Tab.TOGETHER, tile(viewer), id(), 0));
    }

    @Override
    public List<String> aliases() {
        return List.of("spawn");
    }

    @Override
    public void open(Player player, Runnable back) {
        visit(player);
    }

    @Override
    public GameAdmin admin() {
        return admin;
    }

    @Override
    public void start() {
        try {
            spot = Spot.of(new GenMetaDao(plugin().database()).get(SPOT_KEY));
        } catch (SQLException | RuntimeException e) {
            spot = null;
            plugin().getLogger().log(Level.WARNING, "The Hub could not read its arrival spot", e);
        }
        warned.clear();
    }

    @Override
    public void stop() {
        kitted.clear();
    }

    @Override
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        String why = whyClosed();
        out.add("hub: " + (why == null ? "open, " + here() + " here" : "closed - " + why));
        out.add("arrival: " + (spot == null ? "the world's spawn" : spot.text()));
        return out;
    }

    // ---- coming in and going home ------------------------------------------------------------------

    /** Why nobody can go now (an admin's reason), or {@code null}. */
    String whyClosed() {
        HubSettings s = settings();
        if (!s.enabled()) {
            return "games.hub.enabled is false";
        }
        World w = Bukkit.getWorld(s.world());
        if (w == null) {
            return "its world '" + s.world() + "' isn't loaded";
        }
        if (!gamesWorld(w.getName())) {
            return "'" + s.world() + "' isn't listed in games.worlds (give it its own Multiverse-Inventories group"
                    + " too)";
        }
        return firstGamesWorld(games().config().common().worlds(), w.getName());
    }

    /**
     * Why not, when the Hub's world is first in {@code games.worlds}: the first is where the Clubhouse, Fresh Courses
     * and Falling Floors look when their own world isn't set. {@code null} when it isn't first.
     */
    static String firstGamesWorld(List<String> worlds, String hub) {
        if (worlds != null && !worlds.isEmpty() && worlds.get(0) != null && worlds.get(0).equalsIgnoreCase(hub)) {
            return "'" + hub + "' is first in games.worlds - put your Games world first";
        }
        return null;
    }

    private boolean gamesWorld(String name) {
        for (String w : games().config().common().worlds()) {
            if (w != null && w.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** Where a visitor arrives: the admin's spot in the Hub's world, else its spawn. */
    Location arrival() {
        World w = Bukkit.getWorld(settings().world());
        if (w == null) {
            return null;
        }
        Spot s = spot;
        if (s != null && s.world().equalsIgnoreCase(w.getName())) {
            return new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0f);
        }
        return w.getSpawnLocation().add(0.5, 0, 0.5);
    }

    /** One tap: into the Hub's own session, or a plain reason why not. */
    public void visit(Player p) {
        if (inside(p)) {
            p.sendMessage(Text.of(ALREADY));
            return;
        }
        String why = whyClosed();
        if (why != null) {
            if (warned.add(why)) {
                plugin().getLogger().warning("The Hub is closed: " + why + ".");
            }
            refuse(p, CLOSED);
            return;
        }
        Refusal gate = games().canOpen(p, this);
        if (gate != null) {
            games().tell(p, gate);
            return;
        }
        Location at = arrival();
        if (at == null) {
            refuse(p, CLOSED);
            return;
        }
        games().sessions().enter(p, this, REF, at, this::arrived);
    }

    /** In, saved and cleared: the kit, a welcome and a sparkle. */
    private void arrived(Player p) {
        kitted.remove(p.getUniqueId());
        giveKit(p);
        p.sendMessage(Text.of(WELCOME));
        try {
            p.showTitle(Title.title(Text.of("&bThe Hub"), Text.of("&7Your Bag comes home with you"),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.3f);
            p.spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 20, 0.4, 0.7, 0.4, 0.04);
        } catch (RuntimeException ignored) {
            // decoration
        }
    }

    /** The kit: Your Bag (slot 4) and Leave (slot 8); anything else in the inventory is the Bag's. */
    private void giveKit(Player p) {
        if (!inside(p)) {
            return;
        }
        kitted.add(p.getUniqueId());
        games().sessions().stripKit(p); // the kit only: what the Hub gave stays, banked at the end
        PlayerInventory inv = p.getInventory();
        KitItems.put(inv, 4, KitItems.item(this, BAG, Material.BUNDLE, KIT_BAG, "&7What you win here.",
                "&7It all comes home with you."));
        KitItems.put(inv, 8, KitItems.item(this, LEAVE, Material.OAK_DOOR, KIT_LEAVE, "&7Click twice to go home.",
                "&7Your things and your Bag come too."));
        inv.setHeldItemSlot(0); // an empty hand: a tap on a turkey is a catch, not the Bag
    }

    @Override
    public void onKitUse(Player player, String action, boolean leftClick) {
        if (BAG.equals(action) && inside(player)) {
            new BagMenu(plugin(), this, player).open(player);
        }
    }

    /** Fell out of the world: back to the arrival spot. */
    @Override
    public void onVoid(Player player) {
        Location at = arrival();
        if (at != null && games().sessions().teleport(player, at)) {
            // the session's own teleport (0.45.0): a plain one read as someone else's, which asked for this again
            // every tick and froze the visitor on the spot
            player.setFallDistance(0f);
        }
    }

    @Override
    public void onSessionEnd(Player player, EndReason reason) {
        kitted.remove(player.getUniqueId());
    }

    @Override
    public void onQuit(Player player) {
        kitted.remove(player.getUniqueId());
    }

    // ---- what others ask ----------------------------------------------------------------------------

    /** Whether {@code p} is visiting the Hub now (its session, in its world). */
    public boolean inside(Player p) {
        if (p == null) {
            return false;
        }
        Session s = games().sessions().session(p);
        return s != null && id().equals(s.gameId()) && p.getWorld().getName().equalsIgnoreCase(settings().world());
    }

    /** The Hub if it is running, else {@code null}. */
    public static Hub running(GamesService games) {
        if (games == null) {
            return null;
        }
        Game g = games.game(SPEC.id());
        return g instanceof Hub h && games.enabled(h) ? h : null;
    }

    /** Whether {@code p} is visiting the Hub (false with the games or the Hub off). */
    public static boolean visiting(HomeCraftManagement plugin, Player p) {
        Hub h = plugin == null ? null : running(plugin.games());
        return h != null && h.inside(p);
    }

    /** Where the Hub's visitors arrive, while it can be visited (the turkey stampede's middle), else {@code null}. */
    public static Location arrivalOf(GamesService games) {
        Hub h = running(games);
        return h == null || h.whyClosed() != null ? null : h.arrival();
    }

    /** Everyone visiting the Hub now. */
    public static List<Player> visitors(GamesService games) {
        Hub h = running(games);
        List<Player> out = new ArrayList<>();
        if (h != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (h.inside(p)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** Whether the Hub can be visited now (the Arcade's button and the event shout). */
    public static boolean offered(GamesService games) {
        Hub h = running(games);
        return h != null && h.whyClosed() == null;
    }

    /** How many are in the Hub now. */
    int here() {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (inside(p)) {
                n++;
            }
        }
        return n;
    }

    /** The admin's {@code here}: the arrival spot where they stand (in the Hub's world). */
    String setSpot(Player p) {
        if (!p.getWorld().getName().equalsIgnoreCase(settings().world())) {
            return "&cStand in the Hub's world (" + settings().world() + ") first.";
        }
        Location l = p.getLocation();
        Spot s = new Spot(p.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw());
        try {
            new GenMetaDao(plugin().database()).set(SPOT_KEY, s.text());
        } catch (SQLException e) {
            return "&cThat can't be saved to the database right now.";
        }
        spot = s;
        return "&aVisitors arrive where you stand, facing the way you face.";
    }

    /** The admin's {@code here clear}: back to the world's spawn. */
    String clearSpot() {
        try {
            new GenMetaDao(plugin().database()).set(SPOT_KEY, null);
        } catch (SQLException e) {
            return "&cThat can't be saved to the database right now.";
        }
        spot = null;
        return "&aVisitors arrive at the Hub world's spawn.";
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    HubSettings settings() {
        return ctx.games().settings(SPEC);
    }

    GamesService games() {
        return ctx.games();
    }

    HomeCraftManagement plugin() {
        return ctx.plugin();
    }

    private static void refuse(Player p, String line) {
        p.sendMessage(Text.of(line));
        try {
            Sounds.refused(p);
        } catch (RuntimeException | LinkageError ignored) {
            // a sound is decoration
        }
    }
}
