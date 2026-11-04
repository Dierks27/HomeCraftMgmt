package com.dierks.homecraft.arcade.turkey;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.arcade.candy.CandyRules;
import com.dierks.homecraft.arcade.candy.CandyRules.Offset;
import com.dierks.homecraft.arcade.turkey.TurkeyRules.CatchStep;
import com.dierks.homecraft.arcade.turkey.TurkeyRules.Ground;
import com.dierks.homecraft.arcade.turkey.TurkeyRules.Kind;
import com.dierks.homecraft.arcade.turkey.TurkeyRuns.Offer;
import com.dierks.homecraft.arcade.turkey.TurkeyRuns.Run;
import com.dierks.homecraft.config.TurkeyConfig;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.storage.AchievementDao;
import com.dierks.homecraft.util.Fireworks;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.Whereabouts;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityEnterLoveModeEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Catch the Turkey (0.38.6): six times a day, and once after each player's first join of the day, every player in
 * the Overworld of an economy world gets their own big turkey a few steps in front of them. A tap catches it, on
 * Java or Bedrock, a hit or a use, with an empty hand or anything in it. Everyone who catches a turkey in a run gets
 * the same prize, once a run: a firework, a title, a Turkey Feather and a few tokens under a daily cap. The turkey
 * never runs away. The rules are {@link TurkeyRules}', the runs {@link TurkeyRuns}'; this reads the world and acts.
 *
 * <p><b>One task, every second</b>, only while the window is open or a run is still going: it keeps the turkeys
 * near where they appeared, ends runs whose time is up, gives welcome turkeys and turkeys to players who arrive
 * mid-run, and starts a run when a slot does (not when a restart is close). Before the window it looks again at
 * local midnight; after it, it logs that it is over and stops.
 *
 * <p><b>Nothing left behind.</b> A turkey is a Chicken tagged {@link Keys#TURKEY}, never saved: a reload, a stop
 * or a quit removes it, a chunk that loads with one removes it, a portal or a teleport ends it. It is never
 * hurt, bred, leashed, ridden or renamed, lays no eggs and drops nothing. Not invulnerable: Bedrock's tap on a mob
 * is an attack, and the hit has to reach the catch listener, which cancels it after counting.
 *
 * <p><b>Fair to a 4-year-old.</b> Her turkey is in front of her and in sight, stays three minutes, and stays even
 * after her uncles catch it, so she always gets the same prize. A repeat tap clucks, never silence. Her claim is
 * written before anything is given, so a restart never gives her a turkey she can't catch.
 */
public final class TurkeyService implements Listener, TurkeyHost {

    /** The task runs once a second. */
    static final long PERIOD_TICKS = 20L;
    /** A spot that didn't fit is looked for again only this long after (a player in a cave). */
    static final long NO_SPOT_RETRY_MS = 5_000L;
    /** The turkey's walking speed (a chicken's is 0.25): it wanders, it never runs. */
    static final double WALK_SPEED = 0.15;
    /** How fast it walks back when it wanders past the leash. */
    static final double COME_BACK_SPEED = 1.2;
    /** One hop in this many seconds, on average. */
    static final int HOP_ONE_IN = 6;
    /** The three gobbles, this many ticks apart, at this pitch (a chicken's call, low). */
    static final long GOBBLE_GAP_TICKS = 8L;
    static final float GOBBLE_PITCH = 0.5f;

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /** A live turkey: the entity, the player it appeared for, and where it appeared (its leash). */
    private record Live(Chicken entity, UUID owner, Location home) {
    }

    /** A player's last main-hand tap: which turkey, and when. */
    private record LastTap(UUID turkey, long at) {
    }

    private final HomeCraftManagement plugin;
    private final AchievementDao counters;
    private final Random random;
    private final TurkeyRuns runs;

    private final Map<UUID, Live> live = new HashMap<>();
    private final Map<UUID, LastTap> lastTap = new HashMap<>();
    /** Welcome turkeys due (memory only; the persisted welcome mark stops a second one after a restart). */
    private final Map<UUID, Long> welcomeDue = new HashMap<>();
    /** The local day each player had their welcome turkey: this service's own first-join map. */
    private final Map<UUID, Long> welcomedDay = new HashMap<>();
    private final Map<UUID, Long> noSpotUntil = new HashMap<>();

    private BukkitTask task;
    private BukkitTask wake;
    /** The last slot started, kept across reloads (memory only). */
    private long lastSlot = CandyRules.NO_SLOT;
    private boolean overLogged;
    private long lastErrorAt;

    public TurkeyService(HomeCraftManagement plugin, AchievementDao counters) {
        this(plugin, counters, new Random());
    }

    TurkeyService(HomeCraftManagement plugin, AchievementDao counters, Random random) {
        this.plugin = plugin;
        this.counters = counters;
        this.random = random;
        this.runs = new TurkeyRuns(this);
    }

    // ---- lifecycle -------------------------------------------------------------------------

    /**
     * (Re)arm under the config now: remove any leftover turkey in the loaded worlds, then run the task only while
     * the window is open. Before the window it looks again at local midnight.
     */
    public void start() {
        stop();
        sweepWorlds();
        overLogged = false;
        TurkeyConfig.Turkey c = settings();
        if (!c.enabled() || !arcadeOn()) {
            return;
        }
        LocalDate today = plugin.clock().today();
        if (today.isAfter(c.to())) {
            return;
        }
        if (today.isBefore(c.from())) {
            armWake();
            return;
        }
        arm();
        plugin.getLogger().info("Catch the Turkey on through " + DAY_MONTH.format(c.to()) + ": a run every "
                + c.everyMinutes() + " minutes from " + minuteText(CandyRules.offsetOn(c.offsetMinutes(), c.everyMinutes()))
                + ", " + c.runMinutes() + " minute" + (c.runMinutes() == 1 ? "" : "s") + " each.");
    }

    /** Cancel the task, end every run and remove every turkey (reload, disable). */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (wake != null) {
            wake.cancel();
            wake = null;
        }
        runs.clear();
        for (UUID id : new ArrayList<>(live.keySet())) {
            remove(id);
        }
        welcomeDue.clear();
        lastTap.clear();
        noSpotUntil.clear();
    }

    private void arm() {
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
        }
    }

    private void armWake() {
        if (wake == null) {
            long untilMidnight = Math.max(20L, plugin.clock().msUntilNextDay() / 50L + 20L);
            wake = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                wake = null;
                start();
            }, untilMidnight);
        }
    }

    private void tick() {
        try {
            second();
        } catch (RuntimeException e) {
            long now = System.currentTimeMillis();
            if (now - lastErrorAt > 60_000L) {
                lastErrorAt = now;
                plugin.getLogger().log(Level.WARNING, "Catch the Turkey hit an error (it keeps going).", e);
            }
        }
    }

    /** Once a second: the turkeys' leash and hops, runs that end, welcomes, mid-run arrivals and the slot. */
    private void second() {
        TurkeyConfig.Turkey c = settings();
        LocalDateTime local = localNow();
        LocalDate today = local.toLocalDate();
        long now = plugin.clock().nowMillis();
        care();
        runs.expire(now);
        if (!open(c, today)) {
            // a test run before the window, or the last minutes of a run at midnight: it finishes first
            if (runs.isEmpty()) {
                if (task != null) {
                    task.cancel();
                    task = null;
                }
                if (c.enabled() && today.isAfter(c.to()) && !overLogged) {
                    overLogged = true;
                    plugin.getLogger().info("Catch the Turkey is over for " + c.year() + ".");
                } else if (c.enabled() && arcadeOn() && today.isBefore(c.from())) {
                    armWake();
                }
            }
            return;
        }
        welcomes(now, today, c);
        arrivals(now);
        long slot = CandyRules.dueSlot(local, c.everyMinutes(), c.offsetMinutes(), 0, false);
        if (slot == CandyRules.NO_SLOT || slot == lastSlot) {
            return;
        }
        lastSlot = slot;
        if (CandyRules.heldByRestart(slot, plugin.clock().zone(), restartHold())) {
            // the turkeys aren't saved, and the restart would take them mid-run
            plugin.getLogger().info("Turkey run at " + CandyRules.slotStart(slot).toLocalTime()
                    + " skipped: a restart is close.");
            return;
        }
        Run r = runs.start(TurkeyRules.runId(slot), Kind.SCHEDULED, null, now, c.runMillis(), false);
        if (r != null) {
            for (Player p : List.copyOf(plugin.getServer().getOnlinePlayers())) {
                offerScheduled(r, p, now);
            }
        }
    }

    // ---- who gets a turkey -----------------------------------------------------------------

    /**
     * A turkey in a scheduled run for one player: null when one appeared (or they have theirs out), else why not, in
     * the words {@code /hcm turkey now} prints.
     */
    private String offerScheduled(Run r, Player p, long now) {
        String why = skip(p);
        if (why != null) {
            return why;
        }
        UUID id = p.getUniqueId();
        if (r.turkeys().containsKey(id)) {
            return null;
        }
        // A welcome turkey out doesn't stop this one: the run's prize is everyone's, and hers too.
        Long until = noSpotUntil.get(id);
        if (until != null && until > now) {
            return "no room for a turkey in front of them";
        }
        Offer o = runs.offer(r.id(), id);
        if (o == Offer.NO_SPOT) {
            noSpotUntil.put(id, now + NO_SPOT_RETRY_MS);
        }
        return offerText(o);
    }

    /** What an offer's outcome reads as for an admin; null when a turkey appeared. */
    static String offerText(Offer o) {
        return switch (o) {
            case SPAWNED, HAS_ONE -> null;
            case CAUGHT -> "caught this run already";
            case NOT_YOURS -> "someone else's welcome turkey";
            case WELCOME_GIVEN -> "had their welcome turkey today";
            case CAP -> "cap: " + TurkeyRules.MAX_TURKEYS_PER_RUN + " turkeys out this run";
            case NO_SPOT -> "no room for a turkey in front of them";
            case NOT_LIVE -> "the run is over";
        };
    }

    /** Players who arrive (or come back from a game) mid-run get a turkey while a minute or more is left. */
    private void arrivals(long now) {
        List<Run> going = new ArrayList<>();
        for (Run r : runs.live()) {
            if (r.kind() == Kind.SCHEDULED && r.msLeft(now) > TurkeyRules.JOIN_MIN_LEFT_MS) {
                going.add(r);
            }
        }
        if (going.isEmpty()) {
            return;
        }
        for (Player p : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            for (Run r : going) {
                offerScheduled(r, p, now);
            }
        }
    }

    /**
     * Welcome turkeys that are due: one of their own, once a local day, when the skips pass. A player who can't
     * have one yet (in a game, idle, a turkey out already, no room, a restart close) is tried again each second.
     */
    private void welcomes(long now, LocalDate today, TurkeyConfig.Turkey c) {
        if (welcomeDue.isEmpty()) {
            return;
        }
        if (!c.welcome()) {
            welcomeDue.clear();
            return;
        }
        long day = today.toEpochDay();
        for (Iterator<Map.Entry<UUID, Long>> it = welcomeDue.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            UUID id = e.getKey();
            if (e.getValue() > now) {
                continue;
            }
            Player p = Bukkit.getPlayer(id);
            Long had = welcomedDay.get(id);
            if (p == null || !p.isOnline() || (had != null && had == day)) {
                it.remove();
                continue;
            }
            Long until = noSpotUntil.get(id);
            if (runs.hasTurkey(id) || skip(p) != null || (until != null && until > now)) {
                continue;
            }
            if (TurkeyRules.welcomeHeld(now, c.runMillis(), restartHold())) {
                continue; // nothing new before a restart: the welcome stays owed and comes after it
            }
            String rid = TurkeyRules.welcomeRunId(day, id);
            Run r = runs.start(rid, Kind.WELCOME, id, now, c.runMillis(), false);
            if (r == null) {
                welcomedDay.put(id, day);
                it.remove();
                continue;
            }
            Offer o = runs.offer(rid, id);
            if (o == Offer.SPAWNED) {
                welcomedDay.put(id, day);
                it.remove();
            } else if (o == Offer.NO_SPOT) {
                runs.abandon(rid);
                noSpotUntil.put(id, now + NO_SPOT_RETRY_MS);
            } else {
                runs.abandon(rid);
                welcomedDay.put(id, day);
                it.remove();
            }
        }
    }

    /** Why the player gets no turkey now, or null ({@link TurkeyRules#skip}). */
    private String skip(Player p) {
        Location at = p.getLocation();
        World w = p.getWorld();
        var games = plugin.games();
        int groundTop = w.getHighestBlockYAt(at.getBlockX(), at.getBlockZ(), HeightMap.MOTION_BLOCKING);
        CandyRules.State s = new CandyRules.State(p.isDead(), p.isSleeping(), p.getGameMode(),
                plugin.sandbox().allowed(w), Whereabouts.gamesWorld(plugin, w),
                games != null && games.sessions().session(p) != null, games == null || games.sessions().home(p),
                idleMillis(p), p.isGliding(), p.isFlying(), at.getY(), groundTop, p.isInLava(), p.isInWater(),
                p.isInsideVehicle(), 0, 0);
        return TurkeyRules.skip(s, w.getEnvironment() == World.Environment.NORMAL);
    }

    private static long idleMillis(Player p) {
        try {
            Duration idle = p.getIdleDuration();
            return idle == null ? 0 : idle.toMillis();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    // ---- the turkeys (TurkeyHost) ----------------------------------------------------------

    /**
     * A turkey for the player, 4-6 blocks in front of them and facing them: a Chicken scaled up, slow, named, never
     * saved, tagged with its run and owner. Three gobbles, the title and sparkles. Null when no spot fits.
     */
    @Override
    public UUID spawn(UUID player, String runId) {
        Player p = Bukkit.getPlayer(player);
        if (p == null || !p.isOnline() || Keys.TURKEY == null) {
            return null;
        }
        TurkeyConfig.Turkey c = settings();
        Location at = findSpot(p, c.scale());
        if (at == null) {
            return null;
        }
        Vector toPlayer = p.getLocation().toVector().subtract(at.toVector()).setY(0);
        if (toPlayer.lengthSquared() > 0.01) {
            at.setDirection(toPlayer);
        }
        String tag = TurkeyRules.tag(runId, player);
        Chicken chicken;
        try {
            chicken = at.getWorld().spawn(at, Chicken.class, CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
                e.setPersistent(false);
                e.setAdult();
                e.setAgeLock(true);
                e.setBreed(false);
                e.setIsChickenJockey(false);
                e.setEggLayTime(Integer.MAX_VALUE);
                e.setCanPickupItems(false);
                setAttribute(e.getAttribute(Attribute.SCALE), c.scale());
                setAttribute(e.getAttribute(Attribute.MOVEMENT_SPEED), WALK_SPEED);
                e.customName(Text.of("&6Turkey"));
                e.setCustomNameVisible(true);
                e.getPersistentDataContainer().set(Keys.TURKEY, PersistentDataType.STRING, tag);
                if (Keys.MOB_ARTIFICIAL != null) {
                    e.getPersistentDataContainer().set(Keys.MOB_ARTIFICIAL, PersistentDataType.BYTE, (byte) 1);
                }
            });
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not bring a turkey for " + p.getName() + ": " + e.getMessage());
            return null;
        }
        if (chicken == null || !chicken.isValid()) {
            return null; // another plugin refused the spawn there
        }
        live.put(chicken.getUniqueId(), new Live(chicken, player, at.clone()));
        announce(p, chicken);
        return chicken.getUniqueId();
    }

    private static void setAttribute(AttributeInstance a, double value) {
        if (a != null) {
            a.setBaseValue(value);
        }
    }

    /** "Gobble gobble! / Catch the turkey!" for its player, three low calls everyone near hears, and sparkles. */
    private void announce(Player p, Chicken chicken) {
        try {
            p.showTitle(Title.title(Text.of("&6Gobble gobble!"), Text.of("&eCatch the turkey!"),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
            for (int i = 0; i < 3; i++) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (chicken.isValid()) {
                        chicken.getWorld().playSound(chicken.getLocation(), Sound.ENTITY_CHICKEN_AMBIENT, 1.0f,
                                GOBBLE_PITCH);
                    }
                }, i * GOBBLE_GAP_TICKS);
            }
            sparkle(chicken, 8);
        } catch (RuntimeException ignored) {
            // cosmetic
        }
    }

    /** The turkey goes with a poof ("waddles home"). */
    @Override
    public void remove(UUID turkey) {
        Live l = live.remove(turkey);
        if (l == null) {
            return;
        }
        Chicken e = l.entity();
        try {
            if (e.isValid()) {
                e.getWorld().spawnParticle(Particle.POOF, e.getLocation().add(0, 0.5, 0), 12, 0.3, 0.3, 0.3, 0.02);
            }
        } catch (RuntimeException ignored) {
            // cosmetic
        }
        e.remove();
    }

    @Override
    public boolean claimed(UUID player, String runId) {
        try {
            return counters.counter(player, TurkeyRules.claimKey(settings().year(), runId)) > 0;
        } catch (SQLException e) {
            return true; // can't tell: no turkey that might not be catchable
        }
    }

    @Override
    public boolean claim(UUID player, String runId) {
        try {
            return counters.addCounter(player, TurkeyRules.claimKey(settings().year(), runId), 1) == 1;
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not save a turkey catch: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean welcomeGiven(UUID player) {
        try {
            return counters.counter(player, welcomeKeyToday()) > 0;
        } catch (SQLException e) {
            return true;
        }
    }

    @Override
    public boolean markWelcome(UUID player) {
        try {
            return counters.addCounter(player, welcomeKeyToday(), 1) == 1;
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not save a welcome turkey: " + e.getMessage());
            return false;
        }
    }

    private String welcomeKeyToday() {
        return TurkeyRules.welcomeKey(settings().year(), plugin.clock().today().toEpochDay());
    }

    /**
     * The catch, after its claim: a firework in view, the title and a level-up chime; then, in a real run, the
     * feather into a free slot (none: skipped quietly), the tokens under the day's cap (Survival and Adventure
     * only), and the small chance of a Turkey Mini. The turkey hops for joy.
     */
    @Override
    public void caught(UUID player, UUID turkey, String runId, boolean test) {
        Player p = Bukkit.getPlayer(player);
        if (p == null || !p.isOnline()) {
            return;
        }
        TurkeyConfig.Turkey c = settings();
        try {
            Fireworks.burstInView(p);
            p.showTitle(Title.title(Text.of("&6You caught the turkey!"), Text.of(test ? "&7(a test run)" : ""),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        } catch (RuntimeException ignored) {
            // cosmetic
        }
        if (!test) {
            if (c.feather()) {
                p.getInventory().addItem(TurkeyItems.feather(c.year())); // what doesn't fit stays out: never dropped
            }
            payTokens(p, c);
            maybeMini(p, c);
        }
        Live l = live.get(turkey);
        if (l != null) {
            hop(l.entity());
        }
    }

    @Override
    public void cluck(UUID player, UUID turkey) {
        Player p = Bukkit.getPlayer(player);
        Live l = live.get(turkey);
        if (p == null || l == null) {
            return;
        }
        try {
            p.playSound(l.entity().getLocation(), Sound.ENTITY_CHICKEN_AMBIENT, 0.6f, 1.5f);
        } catch (RuntimeException ignored) {
            // cosmetic
        }
    }

    /** The catch's tokens, under the day's cap read back from the ledger since local midnight. */
    private void payTokens(Player p, TurkeyConfig.Turkey c) {
        boolean open = open(c, plugin.clock().today());
        boolean pays = CandyRules.paysTokens(p.getGameMode());
        if (!open || !pays || c.tokens() <= 0 || c.dailyTokenCap() <= 0 || plugin.tokens() == null) {
            return;
        }
        long since = plugin.clock().startOfDay(plugin.clock().dayKey());
        long paid = TurkeyRules.paidToday(plugin.tokens().totals(since, p.getUniqueId()));
        int pay = TurkeyRules.tokensToPay(paid, c.tokens(), c.dailyTokenCap(), open, pays, false);
        if (pay > 0) {
            plugin.tokens().award(p, pay, TokenService.Source.EVENT_TURKEY, "Caught the turkey");
        }
    }

    /**
     * The small chance of one extra Turkey Mini a season: rolled first (most catches read nothing), then the mark,
     * then the mint. A full bag doesn't roll, so the chance waits for a later catch and nothing is dropped. The Mini's
     * own window and cap still apply: past either, nothing is minted and nothing is said.
     */
    private void maybeMini(Player p, TurkeyConfig.Turkey c) {
        if (c.miniChancePercent() <= 0) {
            return;
        }
        double roll = random.nextDouble();
        if (!TurkeyRules.extraMini(roll, c.miniChancePercent(), false, false, MiniService.hasRoom(p))) {
            return;
        }
        UUID id = p.getUniqueId();
        String key = TurkeyRules.miniKey(c.year());
        try {
            if (counters.counter(id, key) > 0 || counters.addCounter(id, key, 1) != 1) {
                return; // had the one extra this season
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not read " + p.getName() + "'s Turkey Mini chance: " + e.getMessage());
            return;
        }
        var minis = plugin.miniService();
        String miniId = TurkeyRules.miniId(c.year());
        boolean minted = minis != null && minis.mintEvent(p, miniId, "Catch the Turkey");
        plugin.getLogger().info("Catch the Turkey: " + p.getName() + " won the extra Turkey Mini"
                + (minted ? "." : ", but none could be minted (closed, at its cap, or the mint failed). If it should "
                + "have come, /hcm mini give " + miniId + " " + p.getName() + " gives it back inside its window."));
    }

    // ---- care: the leash, hops and sparkles ------------------------------------------------

    /** Each second: a turkey past its leash walks back, a lost one ends, the rest hop now and then and sparkle. */
    private void care() {
        for (Map.Entry<UUID, Live> e : new ArrayList<>(live.entrySet())) {
            Chicken c = e.getValue().entity();
            Location home = e.getValue().home();
            boolean valid = c.isValid() && !c.isDead();
            double distance = valid && c.getWorld().equals(home.getWorld()) ? c.getLocation().distance(home) : -1;
            switch (TurkeyRules.leash(valid, valid && c.isInWater(), valid && c.isInLava(), distance)) {
                case LOST -> {
                    if (runs.runOf(e.getKey()) != null) {
                        runs.lost(e.getKey()); // its run lets it go, and it goes
                    } else {
                        remove(e.getKey());
                    }
                }
                case BRING_BACK -> {
                    try {
                        c.getPathfinder().moveTo(home, COME_BACK_SPEED);
                    } catch (RuntimeException ignored) {
                        // it tries again next second
                    }
                }
                case STAY -> {
                    if (random.nextInt(HOP_ONE_IN) == 0) {
                        hop(c);
                    }
                    sparkle(c, 2);
                }
            }
        }
    }

    /** A little hop with a puff of feathers. */
    private static void hop(Chicken c) {
        try {
            if (c.isValid() && c.isOnGround()) {
                c.setVelocity(c.getVelocity().setY(0.42));
                c.getWorld().spawnParticle(Particle.ITEM, c.getLocation().add(0, 0.6, 0), 6, 0.3, 0.2, 0.3, 0.05,
                        new ItemStack(Material.FEATHER));
            }
        } catch (RuntimeException ignored) {
            // cosmetic
        }
    }

    private static void sparkle(Chicken c, int count) {
        try {
            double top = c.getHeight() + 0.4;
            c.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, c.getLocation().add(0, top, 0), count, 0.3, 0.15, 0.3, 0);
        } catch (RuntimeException ignored) {
            // cosmetic
        }
    }

    // ---- where -----------------------------------------------------------------------------

    /**
     * Where the turkey appears: the first of {@link TurkeyRules#spotOffsets} whose floor near the player's own
     * height {@link TurkeyRules#spotOk}. Every block is read in a loaded chunk only, the sight ray last.
     */
    private static Location findSpot(Player p, double scale) {
        World w = p.getWorld();
        Location feet = p.getLocation();
        Location eye = p.getEyeLocation();
        int bx = feet.getBlockX();
        int bz = feet.getBlockZ();
        int feetY = feet.getBlockY();
        int min = w.getMinHeight();
        int max = w.getMaxHeight();
        int need = TurkeyRules.headroom(scale);
        for (Offset o : TurkeyRules.spotOffsets(feet.getYaw())) {
            int x = bx + o.bx();
            int z = bz + o.bz();
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            for (int d : TurkeyRules.FLOOR_STEPS) {
                int floorY = feetY - 1 + d;
                if (floorY < min || floorY + need >= max) {
                    continue;
                }
                Ground g = ground(w, x, floorY, z, need);
                if (TurkeyRules.spotOk(g, scale) && inSight(w, eye, x + 0.5, floorY + 1.5, z + 0.5)) {
                    return new Location(w, x + 0.5, floorY + 1, z + 0.5);
                }
            }
        }
        return null;
    }

    /** One floor and the blocks above it, its sight not yet looked at (true). The column's chunk is loaded. */
    private static Ground ground(World w, int x, int floorY, int z, int need) {
        Block floor = w.getBlockAt(x, floorY, z);
        boolean wetOrFire = floor.isLiquid() || fire(floor.getType());
        int clear = 0;
        for (int i = 1; i <= need; i++) {
            Block b = w.getBlockAt(x, floorY + i, z);
            if (b.isLiquid() || fire(b.getType())) {
                wetOrFire = true;
                break;
            }
            if (!b.isPassable() || CandyRules.inTheWay(b.getType().name())) {
                break;
            }
            clear++;
        }
        boolean lava = lavaBeside(w, x, floorY, z) || lavaBeside(w, x, floorY + 1, z);
        return new Ground(true, floor.getType().isSolid(), floor.getType().name(), clear, wetOrFire, lava, true);
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

    private static boolean fire(Material m) {
        return m == Material.FIRE || m == Material.SOUL_FIRE;
    }

    /** One ray from the eye to the spot hits nothing first: no turkey behind a wall, a fence or a hedge. */
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

    // ---- the catch: a tap on either edition ------------------------------------------------

    /**
     * A hit is how Bedrock's touch screen taps a mob (and a Java left-click): counted first, then cancelled, so the
     * turkey is never hurt. Runs whatever another plugin did to the hit, so a claimed town can't hide it.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!isTurkey(event.getEntity())) {
            return;
        }
        event.setCancelled(true);
        if (event.getDamager() instanceof Player p) {
            tapped(p, event.getEntity(), true);
        }
    }

    /**
     * A use (a Java right-click, or Bedrock's interact button): counted for the main hand, then cancelled for both
     * hands, so a name tag, a lead, seeds or breeding never reach it.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onUse(PlayerInteractEntityEvent event) {
        if (!isTurkey(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
        tapped(event.getPlayer(), event.getRightClicked(), event.getHand() == EquipmentSlot.HAND);
    }

    /** Its companion fires for the same click: cancelled only, never counted twice. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onUseAt(PlayerInteractAtEntityEvent event) {
        if (isTurkey(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    /** One tap: {@link TurkeyRules#catchStep}, then the run's catch or cluck. */
    private void tapped(Player p, Entity turkey, boolean mainHand) {
        UUID tid = turkey.getUniqueId();
        Live l = live.get(tid);
        Run r = runs.runOf(tid);
        if (l == null || r == null) {
            turkey.remove(); // a leftover from before a reload: not ours any more
            return;
        }
        if (!mainHand) {
            return;
        }
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        LastTap before = lastTap.put(id, new LastTap(tid, now));
        Long lastAt = before != null && before.turkey().equals(tid) ? before.at() : null;
        World w = p.getWorld();
        TurkeyRules.Tap t = new TurkeyRules.Tap(true, lastAt, now, plugin.sandbox().allowed(w),
                Whereabouts.gamesWorld(plugin, w), Whereabouts.homeFromGames(plugin, p),
                Whereabouts.inGameSession(plugin, p), r.kind() == Kind.WELCOME, id.equals(l.owner()));
        CatchStep step = TurkeyRules.catchStep(t);
        if (step == CatchStep.NOT_YOURS) {
            cluck(id, tid);
        } else if (step == CatchStep.CATCH) {
            runs.tap(id, tid);
        }
    }

    // ---- guards: never hurt, bred, leashed, ridden or carried off --------------------------

    /** Nothing hurts a turkey: a catch's hit is counted and cancelled first. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (isTurkey(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (isTurkey(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLove(EntityEnterLoveModeEvent event) {
        if (isTurkey(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** No eggs, and nothing else dropped. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(EntityDropItemEvent event) {
        if (isTurkey(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** No chicken jockey on it, and it rides nothing. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (isTurkey(event.getEntity()) || isTurkey(event.getMount())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicle(VehicleEnterEvent event) {
        if (isTurkey(event.getEntered())) {
            event.setCancelled(true);
        }
    }

    /** No fox, cat or wolf goes after it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (isTurkey(event.getTarget()) || isTurkey(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** It can't die, but if it does (a command): nothing drops, and its run lets it go. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        if (isTurkey(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            runs.lost(event.getEntity().getUniqueId());
        }
    }

    /** A portal or a teleport would take it away from its player: that turkey ends. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(EntityPortalEvent event) {
        if (isTurkey(event.getEntity())) {
            event.setCancelled(true);
            gone(event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(EntityTeleportEvent event) {
        if (isTurkey(event.getEntity())) {
            event.setCancelled(true);
            gone(event.getEntity());
        }
    }

    /** Turkeys are never saved; one in a chunk that loads is a leftover. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (isTurkey(e) && !live.containsKey(e.getUniqueId())) {
                e.remove();
            }
        }
    }

    private void gone(Entity e) {
        if (live.containsKey(e.getUniqueId())) {
            runs.lost(e.getUniqueId());
        } else {
            e.remove();
        }
    }

    // ---- join and quit ---------------------------------------------------------------------

    /** The first join of a local day, while it is open: a welcome turkey of their own 90 seconds later. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        TurkeyConfig.Turkey c = settings();
        LocalDate today = plugin.clock().today();
        if (task == null || !c.welcome() || !open(c, today)) {
            return;
        }
        Long had = welcomedDay.get(id);
        if (had == null || had != today.toEpochDay()) {
            welcomeDue.put(id, plugin.clock().nowMillis() + TurkeyRules.WELCOME_DELAY_MS);
        }
    }

    /** Their turkeys go; their memory too. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        runs.quit(id);
        welcomeDue.remove(id);
        lastTap.remove(id);
        noSpotUntil.remove(id);
    }

    // ---- for /hcm turkey (the commands) ----------------------------------------------------

    /** The settings now ({@code arcade.events.turkey}). */
    public TurkeyConfig.Turkey settings() {
        return plugin.config().turkey();
    }

    /** Whether it is on today: enabled, the Arcade on, and today inside the window. */
    public boolean open() {
        return open(settings(), plugin.clock().today());
    }

    /** Whether the task is running (the window is open, or a test run is going on). */
    public boolean running() {
        return task != null;
    }

    /** What {@code /hcm turkey now} did: whether it was a test run, and one line for each player. */
    public record NowResult(boolean test, String runId, Map<String, String> skipped) {
    }

    /**
     * {@code /hcm turkey now [player]}: a run now for {@code who}, or those players added to the run going on.
     * Inside the window it is a real run (claims, feathers, tokens); outside it, or with the switch off, a test
     * run: the moment only, nothing saved and nothing paid. Each player maps to why they got no turkey, or null.
     */
    public NowResult runNow(List<Player> who) {
        TurkeyConfig.Turkey c = settings();
        long now = plugin.clock().nowMillis();
        boolean test = !open(c, plugin.clock().today());
        Run r = null;
        for (Run going : runs.live()) {
            if (going.kind() == Kind.SCHEDULED && going.test() == test) {
                r = going;
            }
        }
        if (r == null) {
            String id = test ? TurkeyRules.testRunId(now) : TurkeyRules.runId(slotNow());
            r = runs.start(id, Kind.SCHEDULED, null, now, c.runMillis(), test);
            if (r == null) {
                r = runs.start(TurkeyRules.testRunId(now), Kind.SCHEDULED, null, now, c.runMillis(), true);
                test = true;
            }
        }
        arm();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Player p : who) {
            noSpotUntil.remove(p.getUniqueId());
            out.put(p.getName(), r == null ? "the run is over" : offerScheduled(r, p, now));
        }
        return new NowResult(test, r == null ? null : r.id(), out);
    }

    /** {@code /hcm turkey clear}: every run ends and every turkey goes. How many turkeys went. */
    public int clear() {
        int n = runs.clear();
        for (UUID id : new ArrayList<>(live.keySet())) {
            remove(id);
        }
        return n;
    }

    /** One run as {@code /hcm turkey status} shows it. */
    public record RunView(String id, boolean welcome, boolean test, long secondsLeft, int turkeys, List<String> catchers) {
    }

    /** The runs going on now, for {@code /hcm turkey status}. */
    public List<RunView> runsNow() {
        long now = plugin.clock().nowMillis();
        List<RunView> out = new ArrayList<>();
        for (Run r : runs.live()) {
            List<String> names = new ArrayList<>();
            for (UUID id : r.catchers()) {
                String name = Bukkit.getOfflinePlayer(id).getName();
                names.add(name == null ? id.toString().substring(0, 8) : name);
            }
            out.add(new RunView(r.id(), r.kind() == Kind.WELCOME, r.test(), r.msLeft(now) / 1000L, r.turkeys().size(),
                    names));
        }
        return out;
    }

    /** When the next run starts (local) that a restart won't skip, or null when none is left this year. */
    public LocalDateTime nextRun() {
        TurkeyConfig.Turkey c = settings();
        if (!c.enabled()) {
            return null;
        }
        LocalDateTime at = localNow();
        for (int i = 0; i < 100 && at != null; i++) {
            at = CandyRules.nextSlot(at, c.from(), c.to(), c.everyMinutes(), c.offsetMinutes(), 0);
            if (at == null || !heldByRestart(at)) {
                return at;
            }
        }
        return null;
    }

    private boolean heldByRestart(LocalDateTime start) {
        long slot = CandyRules.slotId(start.toLocalDate(), start.getHour() * 60 + start.getMinute());
        return CandyRules.heldByRestart(slot, plugin.clock().zone(), restartHold());
    }

    /** How many turkeys the player caught today (each run of today's, and today's welcome turkey). */
    public int caughtToday(UUID player) {
        TurkeyConfig.Turkey c = settings();
        LocalDate today = plugin.clock().today();
        int n = 0;
        try {
            for (long slot : TurkeyRules.slotsOn(today, c.everyMinutes(), c.offsetMinutes())) {
                if (counters.counter(player, TurkeyRules.claimKey(c.year(), TurkeyRules.runId(slot))) > 0) {
                    n++;
                }
            }
            String welcome = TurkeyRules.welcomeRunId(today.toEpochDay(), player);
            if (counters.counter(player, TurkeyRules.claimKey(c.year(), welcome)) > 0) {
                n++;
            }
        } catch (SQLException e) {
            return n;
        }
        return n;
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** A turkey of ours: a Chicken carrying {@link Keys#TURKEY}. */
    static boolean isTurkey(Entity e) {
        if (!(e instanceof Chicken) || Keys.TURKEY == null) {
            return false;
        }
        try {
            return e.getPersistentDataContainer().has(Keys.TURKEY, PersistentDataType.STRING);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean open(TurkeyConfig.Turkey c, LocalDate today) {
        return c.open(today) && arcadeOn();
    }

    private boolean arcadeOn() {
        return plugin.config().arcade().enabled();
    }

    private LocalDateTime localNow() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(plugin.clock().nowMillis()), plugin.clock().zone());
    }

    /** This minute's slot id, for {@code /hcm turkey now} inside the window. */
    private long slotNow() {
        LocalDateTime now = localNow();
        return CandyRules.slotId(now.toLocalDate(), now.getHour() * 60 + now.getMinute());
    }

    private static String minuteText(int minuteOfDay) {
        return String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
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

    /** Leftover turkeys in every loaded world (start only). */
    private void sweepWorlds() {
        int removed = 0;
        for (World w : plugin.getServer().getWorlds()) {
            for (Chicken c : w.getEntitiesByClass(Chicken.class)) {
                if (isTurkey(c) && !live.containsKey(c.getUniqueId())) {
                    c.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Catch the Turkey: removed " + removed + " leftover turkey"
                    + (removed == 1 ? "" : "s") + ".");
        }
    }
}
