package com.dierks.homecraft.arcade.candy;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.PrizeItemListener;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.arcade.candy.CandyRules.BagStep;
import com.dierks.homecraft.arcade.candy.CandyRules.Column;
import com.dierks.homecraft.arcade.candy.CandyRules.Floor;
import com.dierks.homecraft.arcade.candy.CandyRules.HandOver;
import com.dierks.homecraft.arcade.candy.CandyRules.Offset;
import com.dierks.homecraft.arcade.candy.CandyRules.State;
import com.dierks.homecraft.config.CandyConfig;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.storage.AchievementDao;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Fireworks;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The Candy Apple shower (HALLOWEEN-SPEC D10-D18, D11a): every 20 minutes through October (every 10 on the last
 * day), each player in an economy world hears "Trick or treat!" and six candy apples fall a few steps in front of
 * them. Only they see and pick up their own candy. Collecting {@code goal} earns the Trick-or-Treat Bag, and a rare
 * Golden Candy Apple pays a few tokens under a daily cap. The rules are {@link CandyRules}'; this reads the
 * world and the players, and acts.
 *
 * <p><b>One task, every 5 ticks.</b> It drops the apples that are due (at most {@value #PLACEMENTS_PER_RUN} a
 * run, each in loaded chunks only) and draws the falling ones; every 4th run (once a second) it checks the window
 * and the slot, showers everyone when a slot starts, and sweeps. The sweep walks only its own map, never a
 * world's entities: it removes candy that is past its 180 s, whose owner left or went into a game, or whose
 * entity is gone; counts a full bag's touches; retries owed bags; and draws the markers. {@link #start} arms
 * nothing outside the window, and once the last day is over the task removes everything and cancels itself.
 *
 * <p><b>Nothing to dupe.</b> Each candy is its own entity of amount 1, tagged {@link Keys#CANDY_DROP}, owned by
 * its player and hidden from everyone else, and never saved. Only a tracked, tagged candy picked up (or touched
 * with a full bag) by its owner counts, once: one dropped with Q is a new, untagged entity. Merges and hoppers are
 * refused, a portal or a teleport removes it, a chunk that loads with one removes it, and {@link #stop} (reload,
 * disable) and a quit remove them all.
 *
 * <p><b>For a 4-year-old on Bedrock.</b> The show is hers alone, in view and on Bedrock's terms (titles, boss
 * bars, particles and sounds Geyser maps). Candy lands 2-4 blocks away by block distance, never in reach of a
 * player who doesn't move, mostly in front of her and in sight; under a roof it pops up from the floor. A full
 * bag still counts. A full Bedrock player eats the red apple by tapping (Geyser sends no food component), and
 * the bag plays a giggle when tapped. The bag waits, never on the ground and never into a game, until there is
 * room.
 */
public final class CandyService implements Listener {

    /** The task's period: apples due go down this often, and every {@value #RUNS_PER_SECOND}th run is a second. */
    static final int PERIOD_TICKS = 5;
    static final int RUNS_PER_SECOND = 4;
    /** The main-thread budget: at most this many apples placed in one run; the rest wait 5 ticks. */
    static final int PLACEMENTS_PER_RUN = 8;
    /** One eat (the Bedrock fallback): holding the button repeats the use, and a stack would vanish. */
    static final long EAT_COOLDOWN_MS = 1_600L;
    static final long TOY_COOLDOWN_MS = 3_000L;
    /** A player's first join of a local day gets a shower of their own this long after it (SHOULD, kid-bedrock #12). */
    static final long WELCOME_DELAY_MS = 30_000L;
    /** The bag is tried this long after a join: a crash-rejoin's game restore runs 1 tick after it. */
    static final long JOIN_BAG_DELAY_TICKS = 40L;
    static final int PICKUP_BAR_SECONDS = 5;
    static final int SHOWER_BAR_SECONDS = 3;
    /** The red apple's treat (Speed I or Jump Boost I), and the golden one's Speed II. */
    static final int TREAT_TICKS = 20 * 20;
    static final int GOLDEN_SPEED_TICKS = 30 * 20;
    /** Others see an eater's treat particles from this far. */
    static final double VIEW_RANGE = 32;
    /** The giggle comes this long after the chime. */
    static final long GIGGLE_DELAY_TICKS = 6L;

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /** An apple still to drop: whose, for which slot, when, golden or not, the look at shower time, and its retries. */
    private record Pending(UUID owner, long slot, long dueTick, boolean golden, float yaw, int requeued) {
        Pending retry(long at) {
            return new Pending(owner, slot, at, golden, yaw, requeued + 1);
        }
    }

    /** A live candy. */
    private static final class Drop {
        final UUID owner;
        final Item item;
        final boolean golden;
        final long expiresAt;
        boolean landed;

        Drop(UUID owner, Item item, boolean golden, long expiresAt) {
            this.owner = owner;
            this.item = item;
            this.golden = golden;
            this.expiresAt = expiresAt;
        }
    }

    /** Where an apple starts: from the sky, or popped up from the floor. */
    private record Spot(Location at, boolean pop) {
    }

    /** How a bag attempt went, for {@code /hcm candy}. */
    public enum BagResult {
        /** Below the goal. */
        NOT_YET,
        /** This year's bag was given before. */
        HAS_IT,
        /** Given now. */
        GIVEN,
        /** Owed: in a game or another world. */
        WAITING,
        /** Owed: no free slot. */
        FULL,
        /** It couldn't be read or saved (logged). */
        FAILED
    }

    private final HomeCraftManagement plugin;
    private final AchievementDao counters;
    private final Random random;

    private final Map<UUID, Drop> live = new LinkedHashMap<>();
    private final List<Pending> queue = new ArrayList<>();
    /** Online players: a golden ever picked up this year (read once, D14) and the year's count. */
    private final Map<UUID, Boolean> goldenSeen = new HashMap<>();
    private final Map<UUID, Long> found = new HashMap<>();
    /** Bags owed (a full bag, or in a game): retried every second, and on join and {@code /hcm candy}. */
    private final Set<UUID> owed = new HashSet<>();
    private final Set<UUID> toldMakeRoom = new HashSet<>();
    /** The shower count when the admins were last told about this player's full bag (one line a shower). */
    private final Map<UUID, Long> adminsTold = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Long> barTokens = new HashMap<>();
    private final Map<UUID, Long> eatAt = new HashMap<>();
    private final Map<UUID, Long> toyAt = new HashMap<>();
    /** The local day each player was last welcomed, and the welcome showers due (memory only: a restart gives one more). */
    private final Map<UUID, Long> welcomedDay = new HashMap<>();
    private final Map<UUID, Long> welcomeDue = new HashMap<>();

    private BukkitTask task;
    private BukkitTask wake;
    private long ticks;
    private int runs;
    /** The last slot fired, kept across reloads (memory only). */
    private long lastSlot = CandyRules.NO_SLOT;
    private long showers;
    private long barSerial;
    private long lastErrorAt;

    public CandyService(HomeCraftManagement plugin, AchievementDao counters) {
        this(plugin, counters, new Random());
    }

    CandyService(HomeCraftManagement plugin, AchievementDao counters, Random random) {
        this.plugin = plugin;
        this.counters = counters;
        this.random = random;
    }

    // ---- lifecycle -------------------------------------------------------------------------

    /**
     * (Re)arm under the config now: sweep the loaded worlds once for leftover candy, then arm the 5-tick task
     * only while the shower is on and today is in the window. Before the window it looks again at local
     * midnight, so a server that runs through the first day starts by itself.
     */
    public void start() {
        stop();
        goldenSeen.clear();
        found.clear();
        sweepWorlds();
        CandyConfig.Candy c = settings();
        if (!c.enabled() || !plugin.config().arcade().enabled()) {
            return;
        }
        LocalDate today = plugin.clock().today();
        if (today.isAfter(c.to())) {
            return;
        }
        if (today.isBefore(c.from())) {
            long untilMidnight = Math.max(20L, plugin.clock().msUntilNextDay() / 50L + 20L);
            wake = plugin.getServer().getScheduler().runTaskLater(plugin, this::start, untilMidnight);
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
        plugin.getLogger().info("Candy Apple shower on through " + DAY_MONTH.format(c.to()) + ": every "
                + c.everyMinutes() + " minutes from :" + String.format("%02d", c.offsetMinutes())
                + (c.lastDayEveryMinutes() > 0 ? ", every " + c.lastDayEveryMinutes() + " on the last day" : "") + ".");
    }

    /** Cancel the task, remove every tracked candy, clear the queue and hide the bars (reload, disable). */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (wake != null) {
            wake.cancel();
            wake = null;
        }
        clear();
        for (UUID id : new ArrayList<>(bars.keySet())) {
            hideBar(id);
        }
    }

    private void tick() {
        try {
            ticks += PERIOD_TICKS;
            placeDue();
            falling();
            if (++runs % RUNS_PER_SECOND == 0) {
                second();
            }
        } catch (RuntimeException e) {
            long now = System.currentTimeMillis();
            if (now - lastErrorAt > 60_000L) {
                lastErrorAt = now;
                plugin.getLogger().log(Level.WARNING, "The Candy Apple shower hit an error (it keeps going).", e);
            }
        }
    }

    /** Once a second: the window (D18), the sweep, owed bags, welcomes, and the slot (D10). */
    private void second() {
        CandyConfig.Candy c = settings();
        LocalDate today = plugin.clock().today();
        if (!open(c, today)) {
            if (today.isAfter(c.to())) {
                plugin.getLogger().info("The Candy Apple shower is over for " + c.year() + ".");
            }
            stop();
            return;
        }
        long now = plugin.clock().nowMillis();
        sweep(now);
        retryOwed();
        welcomes(now, today);
        long slot = CandyRules.dueSlot(localNow(), c.everyMinutes(), c.offsetMinutes(), c.lastDayEveryMinutes(),
                c.lastDay(today));
        if (slot == CandyRules.NO_SLOT || slot == lastSlot) {
            return;
        }
        lastSlot = slot;
        if (CandyRules.heldByRestart(slot, plugin.clock().zone(), restartHold())) {
            // the candy isn't saved, and the restart would wipe it
            plugin.getLogger().info("Candy Apple shower at " + CandyRules.slotStart(slot).toLocalTime()
                    + " skipped: a restart is close.");
            return;
        }
        showers++;
        for (Player p : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            shower(p, slot);
        }
    }

    // ---- showers (D11a, D14, D17) ----------------------------------------------------------

    /**
     * A shower for one player: the D17 reason they get none, or null when the apples are queued (one every
     * {@value CandyRules#SPREAD_TICKS} ticks) and the show has started.
     */
    private String shower(Player p, long slot) {
        CandyConfig.Candy c = settings();
        State s = state(p);
        String why = CandyRules.skip(s);
        if (why != null) {
            return why;
        }
        int apples = CandyRules.applesFor(c.applesPerShower(), s.liveOwn(), s.liveServer());
        if (apples <= 0) {
            return CandyRules.LIVE_PER_PLAYER + " candies already out";
        }
        boolean seen = goldenSeen(p);
        int goldenAt = CandyRules.goldenIndex(apples);
        float yaw = p.getLocation().getYaw();
        for (int i = 0; i < apples; i++) {
            boolean golden = CandyRules.golden(random, c.goldenPercent(), i == goldenAt, seen);
            queue.add(new Pending(p.getUniqueId(), slot, ticks + (long) i * CandyRules.SPREAD_TICKS, golden, yaw, 0));
        }
        showStart(p);
        return null;
    }

    /** "Trick or treat!", the chime, then a high, quiet giggle (not a cackle), and the bar for 3 s. */
    private void showStart(Player p) {
        try {
            p.showTitle(Title.title(Text.of("&6Trick or treat!"), Text.of("&eCandy apples are falling!"),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.0f);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) {
                    p.playSound(p.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 0.5f, 1.6f);
                }
            }, GIGGLE_DELAY_TICKS);
        } catch (RuntimeException ignored) {
            // cosmetic
        }
        showBar(p, found(p.getUniqueId()), SHOWER_BAR_SECONDS);
    }

    /** What the skips need to know, read now. The player's own column is in a loaded chunk. */
    private State state(Player p) {
        Location at = p.getLocation();
        World w = p.getWorld();
        var games = plugin.games();
        int groundTop = w.getHighestBlockYAt(at.getBlockX(), at.getBlockZ(), HeightMap.MOTION_BLOCKING);
        return new State(p.isDead(), p.isSleeping(), p.getGameMode(), plugin.sandbox().allowed(w), gamesWorld(w),
                games != null && games.sessions().session(p) != null, games == null || games.sessions().home(p),
                idleMillis(p), p.isGliding(), p.isFlying(), at.getY(), groundTop, p.isInLava(), p.isInWater(),
                p.isInsideVehicle(), liveOf(p.getUniqueId()), live.size() + queue.size());
    }

    private static long idleMillis(Player p) {
        try {
            Duration idle = p.getIdleDuration();
            return idle == null ? 0 : idle.toMillis();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    // ---- dropping (D11, D12) ---------------------------------------------------------------

    /** Drop the apples that are due, at most {@value #PLACEMENTS_PER_RUN} this run. */
    private void placeDue() {
        if (queue.isEmpty()) {
            return;
        }
        List<Pending> later = new ArrayList<>();
        int placed = 0;
        for (Iterator<Pending> it = queue.iterator(); it.hasNext() && placed < PLACEMENTS_PER_RUN; ) {
            Pending pd = it.next();
            if (pd.dueTick() > ticks) {
                continue;
            }
            it.remove();
            placed++;
            Pending retry = place(pd);
            if (retry != null) {
                later.add(retry);
            }
        }
        queue.addAll(later);
    }

    /**
     * Drop one apple: from the sky, else popped up from the floor, else back in the queue for a second (up to
     * {@value CandyRules#MAX_REQUEUES} times; then it is dropped silently, and nothing spawns or counts). An
     * owner who left, died or went into a game since the shower loses the apple.
     *
     * @return the apple to try again, or null
     */
    private Pending place(Pending pd) {
        Player p = Bukkit.getPlayer(pd.owner());
        if (p == null || !p.isOnline() || p.isDead() || !candyHere(p)) {
            return null;
        }
        Spot spot = findSpot(p, pd.yaw());
        if (spot == null) {
            return CandyRules.requeue(pd.requeued()) ? pd.retry(ticks + CandyRules.REQUEUE_TICKS) : null;
        }
        spawn(p, pd, spot);
        return null;
    }

    /** Candy can be out where the player is now: an economy world, not a Games world, home from every game. */
    private boolean candyHere(Player p) {
        return plugin.sandbox().allowed(p.getWorld()) && !gamesWorld(p.getWorld()) && homeFromGames(p);
    }

    /**
     * Where an apple goes (D11): pass A from the sky, 2-4 blocks out by block distance, the front arc first,
     * in two rounds (into air, then into grass and the like); pass B, under a roof or a tree, popped up from the
     * player's own floor 2-3 blocks out. Every column is read in a loaded chunk only, the ray cast last.
     */
    private Spot findSpot(Player p, float yaw) {
        World w = p.getWorld();
        Location feet = p.getLocation();
        Location eye = p.getEyeLocation();
        int bx = feet.getBlockX();
        int bz = feet.getBlockZ();
        int feetY = feet.getBlockY();
        int min = w.getMinHeight();
        int max = w.getMaxHeight();

        List<Offset> sky = CandyRules.ringOffsets(yaw, CandyRules.RING_MIN, CandyRules.SKY_MAX, random);
        Column[] columns = new Column[sky.size()];
        for (int round = 0; round < 2; round++) {
            boolean replaceable = round == 1;
            for (int i = 0; i < sky.size(); i++) {
                int x = bx + sky.get(i).bx();
                int z = bz + sky.get(i).bz();
                if (round == 0) {
                    columns[i] = column(w, x, z);
                }
                Column c = columns[i];
                if (c == null || !CandyRules.columnOk(c, feetY, min, replaceable)) {
                    continue;
                }
                if (!inSight(w, eye, x + 0.5, c.top() + 1.5, z + 0.5)) {
                    columns[i] = new Column(c.top(), c.topSolid(), c.topLiquid(), c.topName(), c.landingAir(),
                            c.landingReplaceable(), c.landingLiquidOrFire(), c.lavaBeside(), false);
                    continue;
                }
                return new Spot(new Location(w, x + 0.5, CandyRules.spawnY(c.top(), max), z + 0.5), false);
            }
        }

        for (Offset o : CandyRules.ringOffsets(yaw, CandyRules.RING_MIN, CandyRules.POP_MAX, random)) {
            int x = bx + o.bx();
            int z = bz + o.bz();
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            for (int d : CandyRules.POP_STEPS) {
                int floorY = feetY - 1 + d;
                if (floorY < min || floorY + 2 >= max) {
                    continue;
                }
                Floor f = floor(w, x, floorY, z);
                if (CandyRules.popOk(f) && inSight(w, eye, x + 0.5, floorY + 1.5, z + 0.5)) {
                    return new Spot(new Location(w, x + 0.5, CandyRules.popY(floorY), z + 0.5), true);
                }
            }
        }
        return null;
    }

    /** A column from the sky, its sight not yet looked at (true); null when its chunk isn't loaded. */
    private static Column column(World w, int x, int z) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        int top = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
        if (top < w.getMinHeight() || top + 1 >= w.getMaxHeight()) {
            return new Column(top, false, false, null, false, false, false, false, false);
        }
        Block t = w.getBlockAt(x, top, z);
        Block landing = w.getBlockAt(x, top + 1, z);
        Material tm = t.getType();
        Material lm = landing.getType();
        return new Column(top, tm.isSolid(), t.isLiquid(), tm.name(), lm.isAir(), replaceable(lm),
                landing.isLiquid() || fire(lm), lavaBeside(w, x, top + 1, z), true);
    }

    /** A floor for pass B, its sight not yet looked at (true). The column's chunk is loaded. */
    private static Floor floor(World w, int x, int floorY, int z) {
        Block f = w.getBlockAt(x, floorY, z);
        Block lower = w.getBlockAt(x, floorY + 1, z);
        Block upper = w.getBlockAt(x, floorY + 2, z);
        return new Floor(f.getType().isSolid(), f.getType().name(), lower.isPassable(),
                lower.isLiquid() || fire(lower.getType()), upper.isPassable(), upper.isLiquid() || fire(upper.getType()),
                lavaBeside(w, x, floorY + 1, z), true);
    }

    /** Lava in any of the 4 blocks beside; a neighbour in a chunk that isn't loaded counts as lava (never read). */
    private static boolean lavaBeside(World w, int x, int y, int z) {
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] s : sides) {
            int nx = x + s[0];
            int nz = z + s[1];
            if (!w.isChunkLoaded(nx >> 4, nz >> 4) || w.getBlockAt(nx, y, nz).getType() == Material.LAVA) {
                return true;
            }
        }
        return false;
    }

    private static boolean replaceable(Material m) {
        try {
            return Tag.REPLACEABLE.isTagged(m);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean fire(Material m) {
        return m == Material.FIRE || m == Material.SOUL_FIRE;
    }

    /** One ray from the eye to the spot hits nothing first: no candy behind a wall, a fence or a hedge. */
    private static boolean inSight(World w, Location eye, double x, double y, double z) {
        Vector to = new Vector(x, y, z).subtract(eye.toVector());
        double dist = to.length();
        if (dist < 0.01) {
            return true;
        }
        try {
            RayTraceResult hit = w.rayTraceBlocks(eye, to.normalize(), dist, FluidCollisionMode.NEVER, true);
            return hit == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Spawn it with everything set before it exists (D12): owned, no mobs, a short pickup delay, never saved,
     * unhurt, hidden from everyone, its fall or hop, and the tag. Then shown to its owner (and, with
     * {@code admins_see_all}, to the admins online: they still can't pick it up).
     */
    private void spawn(Player p, Pending pd, Spot spot) {
        CandyConfig.Candy c = settings();
        UUID owner = p.getUniqueId();
        String tag = CandyRules.dropTag(owner, pd.slot());
        Vector velocity = new Vector(0, spot.pop() ? CandyRules.POP_SPEED : CandyRules.FALL_SPEED, 0);
        World w = spot.at().getWorld();
        Item item = w.dropItem(spot.at(), CandyItems.candy(c.year(), pd.golden()), e -> {
            e.setOwner(owner);
            e.setCanMobPickup(false);
            e.setPickupDelay(10);
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setVisibleByDefault(false);
            e.setVelocity(velocity);
            e.getPersistentDataContainer().set(Keys.CANDY_DROP, PersistentDataType.STRING, tag);
            if (pd.golden()) {
                e.customName(Text.of("&6Golden!"));
                e.setCustomNameVisible(true);
            }
        });
        if (!item.isValid()) {
            return; // another plugin cancelled the spawn
        }
        live.put(item.getUniqueId(), new Drop(owner, item, pd.golden(),
                plugin.clock().nowMillis() + CandyRules.LIFETIME_MS));
        p.showEntity(plugin, item);
        if (c.adminsSeeAll()) {
            for (Player admin : plugin.getServer().getOnlinePlayers()) {
                if (!admin.equals(p) && admin.hasPermission("hcm.admin")) {
                    admin.showEntity(plugin, item);
                }
            }
        }
        try {
            Location at = item.getLocation();
            if (spot.pop()) {
                p.spawnParticle(Particle.POOF, at, 4, 0.15, 0.1, 0.15, 0.01);
                p.playSound(at, Sound.ENTITY_CHICKEN_EGG, 0.8f, 1.2f);
            }
            if (pd.golden()) {
                p.spawnParticle(Particle.SMALL_FLAME, at, 2, 0.1, 0.1, 0.1, 0);
                p.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.6f, 1.2f);
            }
        } catch (RuntimeException ignored) {
            // cosmetic
        }
    }

    /**
     * Every 5 ticks: 2 witch sparkles at each falling apple (and a little flame at a golden one), and the landing
     * the moment it touches down: a positional "plop", so she hears where it is, and a puff of green sparkles.
     */
    private void falling() {
        for (Drop d : live.values()) {
            if (d.landed || !d.item.isValid()) {
                continue;
            }
            Player o = Bukkit.getPlayer(d.owner);
            if (o == null) {
                continue;
            }
            try {
                Location at = d.item.getLocation();
                if (d.item.isOnGround()) {
                    d.landed = true;
                    o.playSound(at, Sound.ENTITY_CHICKEN_EGG, 0.8f, 1.2f);
                    o.spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 0.3, 0), 6, 0.25, 0.2, 0.25, 0);
                } else {
                    o.spawnParticle(Particle.WITCH, at, 2, 0.1, 0.1, 0.1, 0);
                    if (d.golden) {
                        o.spawnParticle(Particle.SMALL_FLAME, at, 1, 0.05, 0.05, 0.05, 0);
                    }
                }
            } catch (RuntimeException ignored) {
                // cosmetic
            }
        }
    }

    // ---- the sweep (D15, D17) --------------------------------------------------------------

    /**
     * Remove candy past its lifetime, whose owner is offline, in another world or in a game, or whose entity is
     * gone (picked up, or its chunk unloaded); count a candy its owner is standing on with no room for it; and
     * draw one marker sparkle over each landed candy, for its owner.
     */
    private void sweep(long now) {
        List<Drop> touched = new ArrayList<>();
        for (Iterator<Drop> it = live.values().iterator(); it.hasNext(); ) {
            Drop d = it.next();
            Item item = d.item;
            if (!item.isValid()) {
                it.remove();
                continue;
            }
            Player o = Bukkit.getPlayer(d.owner);
            if (now >= d.expiresAt || o == null || !o.isOnline() || !o.getWorld().equals(item.getWorld())
                    || !homeFromGames(o)) {
                item.remove();
                it.remove();
                continue;
            }
            if (touches(o, item)) {
                item.remove();
                it.remove();
                touched.add(d);
                continue;
            }
            if (d.landed) {
                try {
                    o.spawnParticle(Particle.HAPPY_VILLAGER, item.getLocation().add(0, 0.4, 0), 1, 0, 0, 0, 0);
                } catch (RuntimeException ignored) {
                    // cosmetic
                }
            }
        }
        for (Drop d : touched) {
            Player o = Bukkit.getPlayer(d.owner);
            if (o != null) {
                // "Eaten right away": the count, the pop, a golden's moment and tokens, and the treat on the spot.
                count(o, 1, d.golden);
                treat(o, d.golden, true);
            }
        }
    }

    /** A full bag still counts (kid-bedrock #5): within 1 block of the owner's feet, and no room for it. */
    private static boolean touches(Player o, Item item) {
        if (o.isDead() || o.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        double distance = o.getLocation().distance(item.getLocation());
        return distance <= CandyRules.TOUCH_DISTANCE
                && CandyRules.touchCounts(true, distance, hasRoom(o.getInventory(), item.getItemStack()));
    }

    /** Room for the stack as vanilla would pick it up: a free slot, or an equal stack with space (the off hand too). */
    static boolean hasRoom(PlayerInventory inv, ItemStack stack) {
        if (inv.firstEmpty() != -1) {
            return true;
        }
        for (ItemStack s : inv.getStorageContents()) {
            if (fits(s, stack)) {
                return true;
            }
        }
        return fits(inv.getItemInOffHand(), stack);
    }

    private static boolean fits(ItemStack slot, ItemStack stack) {
        return slot != null && !slot.getType().isAir() && slot.isSimilar(stack) && slot.getAmount() < slot.getMaxStackSize();
    }

    /** Owed bags: tried again the moment the player is home with a free slot (no database read until then). */
    private void retryOwed() {
        if (owed.isEmpty()) {
            return;
        }
        for (UUID id : List.copyOf(owed)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || !bagHome(p)) {
                continue;
            }
            if (p.getInventory().firstEmpty() != -1) {
                tryBag(p);
            } else {
                tellAdmins(p); // once a shower
            }
        }
    }

    /** Welcome showers that are due: a shower of their own, if the skips pass now (SHOULD, D10). */
    private void welcomes(long now, LocalDate today) {
        if (welcomeDue.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<UUID, Long>> it = welcomeDue.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() > now) {
                continue;
            }
            it.remove();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.isOnline()) {
                shower(p, slotNow(today));
            }
        }
    }

    // ---- pickup, counting, tokens and the bag (D14-D16) --------------------------------------

    /**
     * Only shower candy picked up by its own owner counts (E1): the entity carries {@link Keys#CANDY_DROP}, it
     * is one we are tracking, and the picker is the owner its tag names. A candy dropped with Q is a new,
     * untagged entity, so it never counts.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Item item = event.getItem();
        String tag = dropTag(item);
        if (tag == null || !(event.getEntity() instanceof Player p)) {
            return;
        }
        int got = item.getItemStack().getAmount() - event.getRemaining();
        int n = CandyRules.counts(true, p.getUniqueId().equals(CandyRules.dropOwner(tag)), got);
        if (n <= 0) {
            return;
        }
        Drop d = event.getRemaining() <= 0 ? live.remove(item.getUniqueId()) : live.get(item.getUniqueId());
        if (d == null) {
            return; // tagged but not ours to count: a stray from before a restart
        }
        count(p, n, d.golden);
    }

    /**
     * Count {@code got} toward the bag (one synchronous write, the plugin's normal pattern), then the feedback a
     * child who can't read yet understands: the pop, the bar, a sound at 10, 20 and 30. A golden one also gets
     * its moment and its tokens, paid after the count is written. Crossing the goal tries the bag.
     */
    private void count(Player p, int got, boolean golden) {
        CandyConfig.Candy c = settings();
        UUID id = p.getUniqueId();
        long after;
        try {
            after = counters.addCounter(id, CandyRules.counterKey(c.year()), got);
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not count " + p.getName() + "'s candy apple: " + e.getMessage());
            return;
        }
        long before = after - got;
        found.put(id, after);
        try {
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.0f);
            p.sendActionBar(Text.of(CandyRules.actionBar(after, c.goal())));
            if (CandyRules.milestone(before, after, c.goal())) {
                p.playSound(p.getLocation(), Sound.ENTITY_ALLAY_ITEM_GIVEN, 0.8f, 1.0f);
            }
        } catch (RuntimeException ignored) {
            // cosmetic
        }
        if (golden) {
            goldenPickup(p, c);
        }
        if (!CandyRules.crossed(before, after, c.goal())) {
            showBar(p, after, PICKUP_BAR_SECONDS);
            return;
        }
        // The bag (and then the bar, which says whether it is waiting) next tick, once vanilla has put this apple
        // in the bag: given now, the bag could take the slot the apple was about to go into, leaving it on the ground.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player now = Bukkit.getPlayer(id);
            if (now != null && now.isOnline()) {
                tryBag(now);
                showBar(now, found(id), PICKUP_BAR_SECONDS);
            }
        });
    }

    /**
     * A Golden Candy Apple picked up: the mark that ends the guaranteed golden (written once, at the first
     * golden pickup, D14), the "GOLDEN!" moment, and the tokens under the day's cap, read from the ledger (D16).
     * Creative, an empty cap and a closed window pay nothing, and say nothing about tokens.
     */
    private void goldenPickup(Player p, CandyConfig.Candy c) {
        UUID id = p.getUniqueId();
        if (!goldenSeen(p) && plugin.achievements() != null) {
            plugin.achievements().firstTime(p, CandyRules.goldenSeenKey(c.year()));
            goldenSeen.put(id, true);
        }
        try {
            p.showTitle(Title.title(Text.of("&6GOLDEN!"), Text.of("&eA Golden Candy Apple!"),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
            Fireworks.burstInView(p);
        } catch (RuntimeException ignored) {
            // cosmetic
        }
        boolean open = open(c, plugin.clock().today());
        boolean pays = CandyRules.paysTokens(p.getGameMode());
        if (!open || !pays || c.goldenTokens() <= 0 || c.dailyTokenCap() <= 0) {
            return;
        }
        long since = plugin.clock().startOfDay(plugin.clock().dayKey());
        long paid = CandyRules.paidToday(plugin.tokens().totals(since, id));
        int pay = CandyRules.tokensToPay(paid, c.goldenTokens(), c.dailyTokenCap(), open, pays);
        if (pay > 0) {
            plugin.tokens().award(p, pay, TokenService.Source.EVENT_CANDY, "Golden Candy Apple");
        }
    }

    /**
     * Try the Trick-or-Treat Bag now: on crossing the goal, 40 ticks after a join, on {@code /hcm candy}, and every
     * second for a player owed one. Only home from every game and with a free slot; the mark first, then the
     * give, so a failed write never hands out two. With no room, or in a game, nothing is marked: the player is
     * owed one, told once to make room, and the admins hear once a shower.
     */
    public BagResult tryBag(Player p) {
        CandyConfig.Candy c = settings();
        UUID id = p.getUniqueId();
        long marked;
        long count;
        try {
            marked = counters.counter(id, CandyRules.bagKey(c.year()));
            count = counters.counter(id, CandyRules.counterKey(c.year()));
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not read " + p.getName() + "'s candy apples: " + e.getMessage());
            return BagResult.FAILED;
        }
        found.put(id, count);
        boolean room = p.getInventory().firstEmpty() != -1;
        BagStep step = CandyRules.bagStep(marked > 0, count, c.goal(), bagHome(p), room);
        switch (step) {
            case HAS_IT, NOT_YET -> {
                forgetOwed(id);
                return step == BagStep.HAS_IT ? BagResult.HAS_IT : BagResult.NOT_YET;
            }
            case WAIT_GAME -> {
                owed.add(id);
                return BagResult.WAITING;
            }
            case WAIT_ROOM -> {
                owed.add(id);
                if (toldMakeRoom.add(id)) {
                    p.sendMessage(Text.of("&6Make room in your bag for your Trick-or-Treat Bag!"));
                }
                tellAdmins(p);
                return BagResult.FULL;
            }
            default -> {
                return handOverBag(p, c, () -> counters.addCounter(id, CandyRules.bagKey(c.year()), 1) == 1);
            }
        }
    }

    /**
     * {@code /hcm candy bag <player>}, the restore: one bag, free, needing room and home from every game, and the
     * mark set whatever it was. Null when given, else why not (for the admin).
     */
    public String giveBag(Player target) {
        if (!bagHome(target)) {
            return target.getName() + " is in a game or another world. Try again when they're back.";
        }
        if (target.getInventory().firstEmpty() == -1) {
            return target.getName() + "'s bag is full.";
        }
        CandyConfig.Candy c = settings();
        UUID id = target.getUniqueId();
        BagResult r = handOverBag(target, c, () -> {
            counters.addCounter(id, CandyRules.bagKey(c.year()), 1);
            return true;
        });
        return switch (r) {
            case GIVEN -> null;
            case FAILED -> "That couldn't be saved. Try again.";
            default -> "That bag couldn't be given.";
        };
    }

    /** The mark, then the bag, then the moment: the toast, the title and a burst in view. */
    private BagResult handOverBag(Player p, CandyConfig.Candy c, CandyRules.Mark mark) {
        UUID id = p.getUniqueId();
        HandOver done = CandyRules.handOver(mark, () -> {
            Map<Integer, ItemStack> left = p.getInventory().addItem(CandyItems.bag(c.year(), c.goal()));
            return left.isEmpty();
        });
        switch (done) {
            case ALREADY -> {
                forgetOwed(id);
                return BagResult.HAS_IT;
            }
            case NOT_SAVED -> {
                forgetOwed(id); // not every second against a failing database: on join and /hcm candy
                plugin.getLogger().warning("Could not save " + p.getName() + "'s Trick-or-Treat Bag mark - no bag"
                        + " given; it is tried again on their next join or /hcm candy.");
                return BagResult.FAILED;
            }
            case LOST -> {
                forgetOwed(id);
                plugin.getLogger().severe(p.getName() + "'s Trick-or-Treat Bag " + c.year() + " was marked but"
                        + " didn't fit - give it with /hcm candy bag " + p.getName() + ".");
                return BagResult.FAILED;
            }
            default -> {
                forgetOwed(id);
                try {
                    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
                    p.showTitle(Title.title(Text.of("&6Trick-or-Treat Bag!"),
                            Text.of("&eYou found " + c.goal() + " candy apples!"),
                            Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2500), Duration.ofMillis(500))));
                    Fireworks.burstInView(p);
                } catch (RuntimeException ignored) {
                    // cosmetic
                }
                p.sendMessage(Text.of("&6✦ &eThe " + CandyItems.bagName(c.year()) + " &eis yours! &7It's in your"
                        + " bag. Put your candy apples in it!"));
                return BagResult.GIVEN;
            }
        }
    }

    private void forgetOwed(UUID id) {
        owed.remove(id);
        toldMakeRoom.remove(id);
        adminsTold.remove(id);
    }

    /** One line to the admins online, once a shower: a child who can't read has a parent who can. */
    private void tellAdmins(Player p) {
        Long told = adminsTold.get(p.getUniqueId());
        if (told != null && told == showers) {
            return;
        }
        adminsTold.put(p.getUniqueId(), showers);
        for (Player admin : plugin.getServer().getOnlinePlayers()) {
            if (admin.hasPermission("hcm.admin")) {
                admin.sendMessage(Text.of("&6" + p.getName() + "'s bag is full, so their Trick-or-Treat Bag is"
                        + " waiting."));
            }
        }
    }

    /**
     * Where a bag may be handed over (the same rules as a present, D8 check 4): home from every game, alive, in
     * an economy world and not a Games world. A world game restores the whole inventory, and another world may
     * keep its own.
     */
    private boolean bagHome(Player p) {
        return homeFromGames(p) && !p.isDead() && plugin.sandbox().allowed(p.getWorld()) && !gamesWorld(p.getWorld());
    }

    // ---- the treat (D13) -------------------------------------------------------------------

    /** Eating any candy, on either edition. Eating inside a game is already cancelled (KitGuard, LOWEST). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (CandyItems.isCandy(item)) {
            treat(event.getPlayer(), CandyItems.isGolden(item), false);
        }
    }

    /**
     * The treat: Speed I or Jump Boost I for 20 s for a red apple; Speed II for 30 s on top of vanilla's golden
     * apple for a golden one. Particles at {@code eye + 1.2 x look} for the eater (at their own head they clip a
     * first-person screen), and at their head for anyone within 32 blocks. Never levitation, blindness, nausea,
     * darkness, slowness or Glowing.
     *
     * @param touched eaten right away by a full bag's touch: a golden one then also gets vanilla's own effects,
     *                and no second title or burst (its pickup had them)
     */
    private void treat(Player p, boolean golden, boolean touched) {
        try {
            Location eye = p.getEyeLocation();
            Location inView = eye.clone().add(eye.getDirection().multiply(1.2));
            Location head = eye.clone().add(0, 0.5, 0);
            if (!golden) {
                boolean speedy = random.nextBoolean();
                p.addPotionEffect(new PotionEffect(speedy ? PotionEffectType.SPEED : PotionEffectType.JUMP_BOOST,
                        TREAT_TICKS, 0, false, true, true));
                particles(p, Particle.HEART, 6, inView, head, 0);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.7f, 1.2f);
                p.sendMessage(Text.of("&dYum! &7You feel " + (speedy ? "speedy" : "bouncy") + "!"));
                return;
            }
            if (touched) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 5 * 20, 1, false, true, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 120 * 20, 0, false, true, true));
            }
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, GOLDEN_SPEED_TICKS, 1, false, true, true));
            particles(p, Particle.TOTEM_OF_UNDYING, 20, inView, head, 0.1);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
            if (!touched) {
                Fireworks.burstInView(p);
                p.showTitle(Title.title(Text.of("&6GOLDEN!"), Text.of("&eYou feel super speedy!"),
                        Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
            }
            p.sendMessage(Text.of("&6Yum! &7You feel super speedy!"));
        } catch (RuntimeException ignored) {
            // cosmetic: the count never depends on the treat
        }
    }

    private static void particles(Player p, Particle particle, int count, Location inView, Location head, double speed) {
        p.spawnParticle(particle, inView, count, 0.3, 0.3, 0.3, speed);
        for (Player other : p.getWorld().getPlayers()) {
            if (!other.equals(p) && other.getLocation().distanceSquared(head) <= VIEW_RANGE * VIEW_RANGE) {
                other.spawnParticle(particle, head, count, 0.3, 0.3, 0.3, speed);
            }
        }
    }

    // ---- taps: Bedrock's eat fallback and the bag toy (D13, D15) ------------------------------

    /**
     * A tap with a Candy Apple (Bedrock, full) or the bag (both editions). HIGH and NOT ignoreCancelled: air
     * clicks arrive already cancelled, so with it this would never run. Main hand only (the client repeats a
     * click for the off hand). A tap on a chest, a door or a block of ours still does its own thing.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onTap(PlayerInteractEvent event) {
        Action a = event.getAction();
        boolean air = a == Action.RIGHT_CLICK_AIR;
        if (!air && a != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        boolean mainHand = event.getHand() != EquipmentSlot.OFF_HAND;
        if (!mainHand) {
            return;
        }
        Player p = event.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        Material type = held.getType();
        if (type != Material.APPLE && !type.name().endsWith("BUNDLE")) {
            return; // every right-click on the server comes here: the item's type first
        }
        boolean bag = CandyItems.isBag(held);
        boolean red = !bag && CandyItems.isCandy(held) && !CandyItems.isGolden(held);
        if (!bag && !red) {
            return;
        }
        boolean tap = air;
        if (!air) {
            Block clicked = event.getClickedBlock();
            tap = clicked != null && CandyRules.tap(false, true, clicked.getType().isInteractable(), p.isSneaking(),
                    PrizeItemListener.ours(plugin, clicked));
        }
        long now = System.currentTimeMillis();
        if (bag) {
            if (!CandyRules.toyOnTap(tap, true, true, homeFromGames(p))) {
                return;
            }
            cancel(event); // the bundle's own use would empty it
            if (CandyRules.cooledDown(toyAt.get(p.getUniqueId()), now, TOY_COOLDOWN_MS)) {
                toyAt.put(p.getUniqueId(), now);
                toy(p);
            }
            return;
        }
        if (!CandyRules.eatsOnTap(Bedrock.is(p), p.getFoodLevel(), tap, true, true, homeFromGames(p))
                || !CandyRules.cooledDown(eatAt.get(p.getUniqueId()), now, EAT_COOLDOWN_MS)) {
            return;
        }
        cancel(event);
        ItemStack inHand = p.getInventory().getItem(EquipmentSlot.HAND);
        if (inHand == null || inHand.getAmount() <= 0 || !CandyItems.isCandy(inHand) || CandyItems.isGolden(inHand)) {
            return;
        }
        eatAt.put(p.getUniqueId(), now);
        inHand.setAmount(inHand.getAmount() - 1);
        try {
            p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.8f, 1.0f);
        } catch (RuntimeException ignored) {
            // cosmetic
        }
        treat(p, false, false);
    }

    /** The bag's trick or treat: the chime, the giggle, and witch sparkles in view. */
    private void toy(Player p) {
        try {
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.0f);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) {
                    p.playSound(p.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 0.5f, 1.6f);
                }
            }, GIGGLE_DELAY_TICKS);
            Location eye = p.getEyeLocation();
            p.spawnParticle(Particle.WITCH, eye.clone().add(eye.getDirection().multiply(1.2)), 8, 0.3, 0.3, 0.3, 0);
        } catch (RuntimeException ignored) {
            // cosmetic
        }
    }

    private static void cancel(PlayerInteractEvent event) {
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
    }

    // ---- the guards (D12) ------------------------------------------------------------------

    /** Two candies never merge: each keeps its own pop, count and expiry (E2). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMerge(ItemMergeEvent event) {
        if (tagged(event.getEntity()) || tagged(event.getTarget())) {
            event.setCancelled(true);
        }
    }

    /** No hopper (or hopper minecart) takes shower candy (E2). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryPickupItemEvent event) {
        if (tagged(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /** Candy is never saved; one in a chunk that loads is a leftover. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (tagged(e)) {
                live.remove(e.getUniqueId());
                e.remove();
            }
        }
    }

    /** A portal would re-create it in another world, untracked but still tagged and owned (red-team m6). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(EntityPortalEvent event) {
        if (tagged(event.getEntity())) {
            event.setCancelled(true);
            live.remove(event.getEntity().getUniqueId());
            event.getEntity().remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(EntityTeleportEvent event) {
        if (tagged(event.getEntity())) {
            event.setCancelled(true);
            live.remove(event.getEntity().getUniqueId());
            event.getEntity().remove();
        }
    }

    /** Shower candy: an item entity carrying {@link Keys#CANDY_DROP}. Cheap: merges and hoppers ask every tick. */
    private static boolean tagged(Entity e) {
        if (!(e instanceof Item) || Keys.CANDY_DROP == null) {
            return false;
        }
        try {
            return e.getPersistentDataContainer().has(Keys.CANDY_DROP, PersistentDataType.STRING);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String dropTag(Entity e) {
        if (!(e instanceof Item) || Keys.CANDY_DROP == null) {
            return null;
        }
        try {
            return e.getPersistentDataContainer().get(Keys.CANDY_DROP, PersistentDataType.STRING);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ---- join and quit ---------------------------------------------------------------------

    /**
     * The live candy for an admin when {@code admins_see_all} is on; the bag 40 ticks later (after a game's
     * crash restore), with a line about the shower; and, on the first join of a local day while it is on, a
     * welcome shower 30 s later (SHOULD).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        CandyConfig.Candy c = settings();
        if (c.adminsSeeAll() && p.hasPermission("hcm.admin")) {
            for (Drop d : live.values()) {
                if (d.item.isValid()) {
                    p.showEntity(plugin, d.item);
                }
            }
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> afterJoin(id), JOIN_BAG_DELAY_TICKS);
        LocalDate today = plugin.clock().today();
        if (task != null && open(c, today)) {
            Long day = welcomedDay.get(id);
            if (day == null || day != today.toEpochDay()) {
                welcomedDay.put(id, today.toEpochDay());
                welcomeDue.put(id, plugin.clock().nowMillis() + WELCOME_DELAY_MS);
            }
        }
    }

    private void afterJoin(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p == null || !p.isOnline()) {
            return;
        }
        BagResult bag = tryBag(p); // an owed bag can still be claimed after the window
        CandyConfig.Candy c = settings();
        if (bag == BagResult.GIVEN || bag == BagResult.FAILED || !open(c, plugin.clock().today())) {
            return;
        }
        long n = found(id);
        int every = CandyRules.everyOn(c.everyMinutes(), c.lastDayEveryMinutes(), c.lastDay(plugin.clock().today()));
        p.sendMessage(Text.of("&6✦ Candy apples fall every " + every + " minutes until " + DAY_MONTH.format(c.to())
                + "! &7You've found &f" + n + (n < c.goal() ? " &7of " + c.goal() : "") + "&7."));
    }

    /** That player's candy, queued apples and bar go; their caches too. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (Iterator<Drop> it = live.values().iterator(); it.hasNext(); ) {
            Drop d = it.next();
            if (d.owner.equals(id)) {
                d.item.remove();
                it.remove();
            }
        }
        queue.removeIf(pd -> pd.owner().equals(id));
        hideBar(id);
        goldenSeen.remove(id);
        found.remove(id);
        forgetOwed(id);
        eatAt.remove(id);
        toyAt.remove(id);
        welcomeDue.remove(id);
    }

    // ---- the boss bar (D15) ----------------------------------------------------------------

    /**
     * One yellow bar a player, redrawn in place (Bedrock shows boss bars through Geyser): the count toward the
     * goal, full from the goal on. It goes {@code seconds} later unless it was shown again since.
     */
    private void showBar(Player p, long n, int seconds) {
        CandyConfig.Candy c = settings();
        UUID id = p.getUniqueId();
        try {
            String line = CandyRules.barLine(n, c.goal(), owed.contains(id));
            float progress = CandyRules.progress(n, c.goal());
            BossBar bar = bars.get(id);
            if (bar == null) {
                bar = BossBar.bossBar(Text.of(line), progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
                bars.put(id, bar);
                p.showBossBar(bar);
            } else {
                bar.name(Text.of(line));
                bar.progress(progress);
            }
        } catch (RuntimeException e) {
            return;
        }
        long token = ++barSerial;
        barTokens.put(id, token);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Long now = barTokens.get(id);
            if (now != null && now == token) {
                hideBar(id);
            }
        }, seconds * 20L);
    }

    private void hideBar(UUID id) {
        barTokens.remove(id);
        BossBar bar = bars.remove(id);
        Player p = bar == null ? null : Bukkit.getPlayer(id);
        if (p != null) {
            try {
                p.hideBossBar(bar);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
    }

    // ---- for /hcm candy (the commands) -----------------------------------------------------

    /** The settings now ({@code arcade.events.candy}). */
    public CandyConfig.Candy settings() {
        return plugin.config().candy();
    }

    /** Whether the shower is on today: enabled, the Arcade on, and today inside the window (D18). */
    public boolean open() {
        return open(settings(), plugin.clock().today());
    }

    /** Whether the shower's task is running. */
    public boolean running() {
        return task != null;
    }

    /**
     * Why {@code /hcm candy now} can't shower, or null: switched off, over for the year, before the window, or
     * not running.
     */
    public String nowRefusal() {
        CandyConfig.Candy c = settings();
        LocalDate today = plugin.clock().today();
        if (!c.enabled() || !plugin.config().arcade().enabled()) {
            return "The Candy Apple shower is off (arcade.events.candy.enabled).";
        }
        if (today.isAfter(c.to())) {
            return "The Candy Apple shower is over for this year.";
        }
        if (today.isBefore(c.from())) {
            return "The Candy Apple shower starts on " + DAY_MONTH.format(c.from()) + ".";
        }
        if (task == null) {
            return "The Candy Apple shower isn't running. Try /hcm reload.";
        }
        return null;
    }

    /**
     * A shower now for one player ({@code /hcm candy now [player]}): null when it is falling, else the D17 reason
     * they were skipped, or {@link #nowRefusal()}'s.
     */
    public String showerNow(Player p) {
        String refused = nowRefusal();
        if (refused != null) {
            return refused;
        }
        return shower(p, slotNow(plugin.clock().today()));
    }

    /** When the next shower starts (local), or null when the window is over. */
    public LocalDateTime nextShower() {
        CandyConfig.Candy c = settings();
        if (!c.enabled()) {
            return null;
        }
        return CandyRules.nextSlot(localNow(), c.from(), c.to(), c.everyMinutes(), c.offsetMinutes(),
                c.lastDayEveryMinutes());
    }

    /** Whether the restart hold skips a shower starting at {@code start} (local). */
    public boolean heldByRestart(LocalDateTime start) {
        if (start == null) {
            return false;
        }
        long slot = CandyRules.slotId(start.toLocalDate(), start.getHour() * 60 + start.getMinute());
        return CandyRules.heldByRestart(slot, plugin.clock().zone(), restartHold());
    }

    /** The candy out now (live and still to drop), per owner. */
    public Map<UUID, Integer> liveByPlayer() {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        for (Drop d : live.values()) {
            out.merge(d.owner, 1, Integer::sum);
        }
        for (Pending pd : queue) {
            out.merge(pd.owner(), 1, Integer::sum);
        }
        return out;
    }

    /** All the candy out now, live and still to drop. */
    public int liveTotal() {
        return live.size() + queue.size();
    }

    /** The players owed a bag right now. */
    public Set<UUID> owed() {
        return Set.copyOf(owed);
    }

    /** The candy apples the player has found this year (0 when it can't be read). */
    public long found(UUID player) {
        Long n = found.get(player);
        if (n != null) {
            return n;
        }
        try {
            long read = counters.counter(player, CandyRules.counterKey(settings().year()));
            if (Bukkit.getPlayer(player) != null) {
                found.put(player, read);
            }
            return read;
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Whether the player has had this year's bag (false when it can't be read). */
    public boolean hasBag(UUID player) {
        try {
            return counters.counter(player, CandyRules.bagKey(settings().year())) > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    /** {@code /hcm candy clear}: remove every live candy and the queue. Nothing is counted. How many went. */
    public int clear() {
        int n = live.size() + queue.size();
        for (Drop d : live.values()) {
            d.item.remove();
        }
        live.clear();
        queue.clear();
        welcomeDue.clear();
        return n;
    }

    /**
     * {@code /hcm candy add <player> <n>}: add {@code n} (the caller keeps it to 1-200 and logs it), then the goal
     * check, so the bag can be tested without collecting 31 by hand. The new total, or -1 when it couldn't be saved.
     */
    public long add(Player target, int n) {
        CandyConfig.Candy c = settings();
        UUID id = target.getUniqueId();
        long after;
        try {
            after = counters.addCounter(id, CandyRules.counterKey(c.year()), n);
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not add candy apples for " + target.getName() + ": " + e.getMessage());
            return -1;
        }
        found.put(id, after);
        if (after >= c.goal()) {
            tryBag(target);
        }
        showBar(target, after, PICKUP_BAR_SECONDS);
        return after;
    }

    // ---- helpers ---------------------------------------------------------------------------

    private boolean open(CandyConfig.Candy c, LocalDate today) {
        return c.open(today) && plugin.config().arcade().enabled();
    }

    private LocalDateTime localNow() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(plugin.clock().nowMillis()), plugin.clock().zone());
    }

    /** A slot id for a shower outside the schedule (a welcome, {@code /hcm candy now}): this minute's. */
    private long slotNow(LocalDate today) {
        LocalDateTime now = localNow();
        return CandyRules.slotId(today, now.getHour() * 60 + now.getMinute());
    }

    /** The golden-seen mark, read once per player (D14: {@code firstTime} would write on every shower). */
    private boolean goldenSeen(Player p) {
        Boolean seen = goldenSeen.get(p.getUniqueId());
        if (seen != null) {
            return seen;
        }
        boolean read;
        try {
            read = counters.counter(p.getUniqueId(), CandyRules.goldenSeenKey(settings().year())) > 0;
        } catch (SQLException e) {
            return true; // can't tell: no extra golden, and nothing cached
        }
        goldenSeen.put(p.getUniqueId(), read);
        return read;
    }

    private int liveOf(UUID owner) {
        int n = 0;
        for (Drop d : live.values()) {
            if (d.owner.equals(owner)) {
                n++;
            }
        }
        for (Pending pd : queue) {
            if (pd.owner().equals(owner)) {
                n++;
            }
        }
        return n;
    }

    private boolean homeFromGames(Player p) {
        var games = plugin.games();
        return games == null || (games.sessions().session(p) == null && games.sessions().home(p));
    }

    private boolean gamesWorld(World w) {
        var g = plugin.config().games();
        return w != null && g != null && CandyRules.gamesWorld(w.getName(), g.common().worlds());
    }

    /** The Games' restart hold, or one built from the games config when the Games aren't running. */
    private RestartHold restartHold() {
        var games = plugin.games();
        if (games != null) {
            return games.restartHold();
        }
        var g = plugin.config().games();
        return g == null ? null
                : new RestartHold(g.common().restartTimes(), plugin.clock().zone(), g.common().restartHoldMinutes());
    }

    /** Leftover candy in every loaded world (start only: the sweep walks its own map). */
    private void sweepWorlds() {
        int removed = 0;
        for (World w : plugin.getServer().getWorlds()) {
            for (Item item : w.getEntitiesByClass(Item.class)) {
                if (tagged(item)) {
                    item.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Candy Apple shower: removed " + removed + " leftover cand"
                    + (removed == 1 ? "y" : "ies") + ".");
        }
    }
}
