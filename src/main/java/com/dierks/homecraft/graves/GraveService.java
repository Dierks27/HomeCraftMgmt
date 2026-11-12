package com.dierks.homecraft.graves;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.courier.CourierPackage;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.storage.GraveDao;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.TokenPrizes;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Graves (0.39.0, HCM-GRAVES): HCM's own death storage, in place of GravesX.
 *
 * <p><b>Made at the end of the death</b> ({@link GraveListener#onDeath}, MONITOR): every HCM filter has run by
 * then (the Games' revive at LOWEST, prizes kept at LOW, courier packages kept at NORMAL, the prize guard at
 * HIGHEST), so the grave takes only what really is about to fall on the ground, and takes it out of the drops in the
 * same step: nothing is ever both on the ground and in a grave. Three kinds of item are never taken: prizes and game
 * kit items ({@link TokenPrizes#is}, which covers both) and courier packages; their own modules decide what happens to
 * them, as before graves.
 *
 * <p><b>The row is the grave.</b> Everything it holds is in {@code graves} ({@link GraveDao}); the head block and its
 * two name tags are only markers, put back from the row whenever its chunk is loaded. Every way out of a grave empties
 * the row first and gives second ({@link GraveDao#claimAll}, {@link GraveDao#takeOne}).
 */
public final class GraveService {

    /** Get a grave, loot your own, {@code /hcm graves}. */
    public static final String USE = "hcm.graves.use";
    /** Every admin subcommand, and opening anyone's grave. */
    public static final String ADMIN = "hcm.graves.admin";

    /** How often the markers, the name tags' age and expiry are seen to (ticks). */
    private static final long REFRESH_TICKS = 20L * 20;
    /** How often each player's last safe spot is noted (ticks). */
    private static final long SAFE_SPOT_TICKS = 40L;
    /** Expired graves in unloaded chunks handled a refresh, at most (each loads its chunk). */
    private static final int EXPIRE_LOADS_PER_REFRESH = 2;

    private final HomeCraftManagement plugin;
    private final GraveDao dao;

    /** Open graves by id. */
    private final Map<Long, Grave> open = new LinkedHashMap<>();
    /** Open graves by block. */
    private final Map<Spot, Long> bySpot = new HashMap<>();
    /** Each grave's name-tag stands (top line, age line). */
    private final Map<Long, UUID[]> tags = new HashMap<>();
    /** Graves being emptied right now: a second click in the same moment does nothing. */
    private final Set<Long> busy = new HashSet<>();
    /** Who has each grave's screen open: one at a time. */
    private final Map<Long, UUID> viewers = new HashMap<>();
    /** The last spot each player stood safely on, for a death in the void. */
    private final Map<UUID, Location> safe = new HashMap<>();
    /** Graves whose block was taken by something else: warned once. */
    private final Set<Long> warnedBlocked = new HashSet<>();

    private BukkitTask refreshTask;
    private BukkitTask safeTask;

    /** A block, by world name. */
    record Spot(String world, int x, int y, int z) {
        static Spot of(Grave g) {
            return new Spot(g.world(), g.x(), g.y(), g.z());
        }

        static Spot of(Block b) {
            return new Spot(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        }
    }

    public GraveService(HomeCraftManagement plugin, GraveDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    public GraveSettings settings() {
        return plugin.config().graves();
    }

    public GraveDao dao() {
        return dao;
    }

    private long now() {
        return plugin.config().clock().nowMillis();
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    /** Load the open graves and start the refresh. The markers go back as their chunks are seen. */
    public void start() {
        stopTasks();
        open.clear();
        bySpot.clear();
        try {
            for (Grave g : dao.open()) {
                remember(g);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Graves: could not read the open graves.", e);
        }
        GraveSettings s = settings();
        plugin.getLogger().info("Graves: " + (s.enabled() ? "on" : "off (graves.enabled: false)") + ", "
                + open.size() + " open grave(s).");
        refreshTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, 20L, REFRESH_TICKS);
        safeTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::noteSafeSpots, SAFE_SPOT_TICKS,
                SAFE_SPOT_TICKS);
    }

    /** Settings changed ({@code /hcm reload}): the next refresh uses them; nothing else to do. */
    public void reload() {
        refresh();
    }

    /** Tasks off and name tags down. Graves stay: their rows hold them, and the head blocks stay in the world. */
    public void stop() {
        stopTasks();
        for (UUID[] ids : tags.values()) {
            removeTags(ids);
        }
        tags.clear();
        viewers.clear();
        busy.clear();
    }

    private void stopTasks() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        if (safeTask != null) {
            safeTask.cancel();
            safeTask = null;
        }
    }

    private void remember(Grave g) {
        open.put(g.id(), g);
        bySpot.put(Spot.of(g), g.id());
    }

    private void forget(long id) {
        Grave g = open.remove(id);
        if (g != null) {
            bySpot.remove(Spot.of(g));
        }
        UUID[] ids = tags.remove(id);
        if (ids != null) {
            removeTags(ids);
        }
        warnedBlocked.remove(id);
    }

    // ---- queries ------------------------------------------------------------------------------------

    /** The open grave whose head is this block, or null. O(1): the protection checks call this a lot. */
    public Grave at(Block block) {
        if (block == null || open.isEmpty()) {
            return null;
        }
        Long id = bySpot.get(Spot.of(block));
        return id == null ? null : open.get(id);
    }

    /** Whether the block is an open grave's head. */
    public boolean isGrave(Block block) {
        return at(block) != null;
    }

    /** An open grave by id, or null. */
    public Grave openGrave(long id) {
        return open.get(id);
    }

    /** Every open grave, oldest first. */
    public List<Grave> openGraves() {
        return new ArrayList<>(open.values());
    }

    /** A grave as the database has it now (open or not), or null. */
    public Grave fresh(long id) throws SQLException {
        return dao.get(id);
    }

    // ---- the death ------------------------------------------------------------------------------------

    /**
     * The death, at MONITOR: decide, take what is left in the drops, write the row, then take it out of the drops.
     * A grave that can't be written or placed leaves the drops as they are: the death then goes as it did before
     * graves, and nothing is lost.
     */
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        GraveSettings s = settings();
        GraveRules.Decision d = GraveRules.decide(s.enabled(), s.worldDisabled(p.getWorld().getName()),
                event.isCancelled(), event.getKeepInventory(), inGameSession(p));
        if (d != GraveRules.Decision.MAKE || !p.hasPermission(USE)) {
            return;
        }
        List<ItemStack> drops = event.getDrops();
        List<ItemStack> take = GraveItems.take(drops, i -> i.getType().isAir() || i.getAmount() <= 0,
                GraveService::ownHandling);
        int xp = GraveRules.xpToStore(GraveRules.totalXp(p.getLevel(), p.getExp()), s.xpPercent(),
                event.getKeepLevel());
        if (GraveRules.empty(take.size(), xp)) {
            return;
        }
        World world = p.getWorld();
        GravePlacement.Spot spot = place(p, world);
        if (spot == null) {
            plugin.getLogger().warning("Graves: nowhere to put " + p.getName() + "'s grave near "
                    + where(world.getName(), p.getLocation().getBlockX(), p.getLocation().getBlockY(),
                    p.getLocation().getBlockZ()) + " - their things drop on the ground as they would without graves.");
            return;
        }
        List<ItemStack> inventory = Arrays.asList(p.getInventory().getContents());
        List<GraveItems.Slotted<ItemStack>> slotted = GraveItems.assignSlots(inventory, take, ItemStack::equals);
        List<GraveItems.Entry> entries = new ArrayList<>(slotted.size());
        List<ItemStack> stored = new ArrayList<>(slotted.size());
        int n = 0;
        for (GraveItems.Slotted<ItemStack> si : slotted) {
            String data;
            try {
                data = Items.toBase64(si.item());
            } catch (RuntimeException e) {
                continue; // can't be saved: it stays in the drops and falls on the ground as before
            }
            entries.add(new GraveItems.Entry(n++, si.slot(), data));
            stored.add(si.item());
        }
        if (GraveRules.empty(entries.size(), xp)) {
            return;
        }
        long created = now();
        long id;
        try {
            id = dao.create(p.getUniqueId(), p.getName(), world.getName(), spot.x(), spot.y(), spot.z(), created, xp,
                    entries);
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Graves: could not save " + p.getName() + "'s grave - their things "
                    + "drop on the ground as they would without graves.", e);
            return;
        }
        // Written: now, and only now, out of the drops.
        GraveItems.removeTaken(drops, GraveItems.identitySet(stored));
        if (!event.getKeepLevel()) {
            event.setDroppedExp(0); // the grave holds xp_percent of it; no orbs as well
        }
        Grave g = new Grave(id, p.getUniqueId(), p.getName(), world.getName(), spot.x(), spot.y(), spot.z(), created,
                xp, entries, Grave.OPEN, 0L, "", false);
        remember(g);
        try {
            placeMarker(g, p);
            showTags(g);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Graves: grave #" + id + " is saved but its block couldn't be put "
                    + "down yet; it comes back with the next refresh.", e);
        }
        plugin.getLogger().info("Graves: #" + id + " for " + p.getName() + " at " + where(g) + " (" + entries.size()
                + " stack(s), " + xp + " xp).");
        p.sendMessage(Text.of("&7Your things are in a grave at &f" + where(g) + "&7. Right-click it to get them back."));
        p.sendMessage(Text.of("&8/hcm graves &7lists your graves."));
    }

    /** Prizes, game kit items and courier packages: their own modules decide, never the grave. */
    static boolean ownHandling(ItemStack item) {
        return TokenPrizes.is(item) || KitItems.isKit(item) || CourierPackage.is(item);
    }

    /** A death inside a world game: the Games revive it or bank it themselves. */
    private boolean inGameSession(Player p) {
        try {
            var games = plugin.games();
            return games != null && games.sessions() != null && games.sessions().session(p) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The grave's cell for this death (HCM-GRAVES §2), or null. */
    private GravePlacement.Spot place(Player p, World world) {
        Location at = p.getLocation();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        EntityDamageEvent last = p.getLastDamageCause();
        boolean voidDeath = at.getY() < minY
                || (last != null && last.getCause() == EntityDamageEvent.DamageCause.VOID);
        if (voidDeath) {
            Location s = safe.get(p.getUniqueId());
            at = s != null && s.getWorld() != null && s.getWorld().equals(world) ? s : world.getSpawnLocation();
        }
        GravePlacement.Grid grid = grid(world);
        GravePlacement.Spot spot = GravePlacement.find(grid, at.getBlockX(), at.getBlockY(), at.getBlockZ(), minY, maxY);
        if (spot == null && !voidDeath) {
            Location sp = world.getSpawnLocation();
            spot = GravePlacement.find(grid, sp.getBlockX(), sp.getBlockY(), sp.getBlockZ(), minY, maxY);
        }
        return spot;
    }

    /** The live world as {@link GravePlacement} reads it. */
    private GravePlacement.Grid grid(World world) {
        String name = world.getName();
        var buildings = plugin.courier() == null ? null : plugin.courier().buildings();
        return (x, y, z) -> {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return GravePlacement.Cell.NEVER;
            }
            if (bySpot.containsKey(new Spot(name, x, y, z))) {
                return GravePlacement.Cell.NEVER;
            }
            GravePlacement.Cell c = cell(world.getBlockAt(x, y, z));
            if (c != GravePlacement.Cell.SOLID && c != GravePlacement.Cell.NEVER && buildings != null) {
                try {
                    if (buildings.siteContaining(name, x, y, z) != null) {
                        return GravePlacement.Cell.NEVER; // a Courier delivery building: its restore would bury it
                    }
                } catch (RuntimeException ignored) {
                    // no courier answer: the cell as the world has it
                }
            }
            return c;
        };
    }

    /** One block, as a grave cares. Containers, signs, beds and every HCM block are solid: never replaced. */
    static GravePlacement.Cell cell(Block b) {
        Material m = b.getType();
        if (m.isAir()) {
            return GravePlacement.Cell.AIR;
        }
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN) {
            return GravePlacement.Cell.WATER;
        }
        if (m == Material.LAVA) {
            return GravePlacement.Cell.LAVA;
        }
        if (m == Material.NETHER_PORTAL || m == Material.END_PORTAL || m == Material.END_GATEWAY) {
            return GravePlacement.Cell.NEVER;
        }
        if (soft(m)) {
            return GravePlacement.Cell.SOFT;
        }
        return GravePlacement.Cell.SOLID;
    }

    /** Grass, ferns, flowers, a snow layer, vines and the like: a grave replaces them. */
    static boolean soft(Material m) {
        return Tag.REPLACEABLE.isTagged(m) || Tag.SMALL_FLOWERS.isTagged(m) || m == Material.SNOW;
    }

    /** Note where each player stands safely, for a death in the void. */
    private void noteSafeSpots() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            try {
                if (p.isOnGround() && !p.isInLava() && !p.isDead()
                        && p.getLocation().getY() >= p.getWorld().getMinHeight()) {
                    safe.put(p.getUniqueId(), p.getLocation());
                }
            } catch (RuntimeException ignored) {
                // a player mid-teleport: next time
            }
        }
    }

    /** A player left: forget their safe spot and any grave screen they had. */
    public void quit(Player p) {
        safe.remove(p.getUniqueId());
        viewers.values().removeIf(v -> v.equals(p.getUniqueId()));
    }

    // ---- markers ------------------------------------------------------------------------------------

    /**
     * Put the grave's head down (if its chunk is loaded), facing nowhere in particular, with the owner's skin and the
     * grave's id on it. A cell something else has taken since (WorldEdit, an admin) is left alone, with one warning:
     * the grave is still whole in its row.
     */
    private boolean placeMarker(Grave g, Player owner) {
        World w = Bukkit.getWorld(g.world());
        if (w == null || !w.isChunkLoaded(g.x() >> 4, g.z() >> 4)) {
            return false;
        }
        Block b = w.getBlockAt(g.x(), g.y(), g.z());
        if (b.getType() != Material.PLAYER_HEAD) {
            if (!GravePlacement.takes(cell(b))) {
                if (warnedBlocked.add(g.id())) {
                    plugin.getLogger().warning("Graves: #" + g.id() + " (" + g.ownerName() + ") at " + where(g)
                            + " has " + b.getType() + " where its head belongs. Its things are safe in the database: "
                            + "/hcm graves restore " + g.id() + " gives them to its owner.");
                }
                return false;
            }
            clearOtherHalf(b);
            b.setType(Material.PLAYER_HEAD, false);
        }
        if (b.getState() instanceof Skull skull) {
            Long tagged = skull.getPersistentDataContainer().get(Keys.GRAVE, PersistentDataType.LONG);
            if (tagged != null && tagged == g.id() && owner == null) {
                return true;
            }
            if (owner != null) {
                skull.setPlayerProfile(owner.getPlayerProfile());
            } else {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(g.owner()));
            }
            skull.getPersistentDataContainer().set(Keys.GRAVE, PersistentDataType.LONG, g.id());
            skull.update(true, false);
        }
        warnedBlocked.remove(g.id());
        return true;
    }

    /** A tall plant's other half goes too, so no half-plant is left floating. */
    private static void clearOtherHalf(Block b) {
        BlockData data = b.getBlockData();
        if (data instanceof Bisected bi) {
            Block other = bi.getHalf() == Bisected.Half.BOTTOM ? b.getRelative(0, 1, 0) : b.getRelative(0, -1, 0);
            if (other.getType() == b.getType()) {
                other.setType(Material.AIR, false);
            }
        }
    }

    /** Take the head away (only if it is still a head). */
    private void removeMarker(Grave g) {
        World w = Bukkit.getWorld(g.world());
        if (w == null) {
            return;
        }
        Block b = w.getBlockAt(g.x(), g.y(), g.z());
        if (b.getType() == Material.PLAYER_HEAD || b.getType() == Material.PLAYER_WALL_HEAD) {
            b.setType(Material.AIR, false);
        }
    }

    /** The two name tags: "Name's grave" and how long ago. Invisible marker stands: Bedrock shows them too. */
    private void showTags(Grave g) {
        World w = Bukkit.getWorld(g.world());
        if (w == null || !w.isChunkLoaded(g.x() >> 4, g.z() >> 4)) {
            return;
        }
        UUID[] ids = tags.get(g.id());
        ArmorStand top = ids == null ? null : stand(ids[0]);
        ArmorStand age = ids == null ? null : stand(ids[1]);
        if (top == null || age == null) {
            if (ids != null) {
                removeTags(ids);
            }
            top = spawnTag(w, g, 1.15, "&f" + g.ownerName() + "'s grave");
            age = spawnTag(w, g, 0.88, ageLine(g));
            tags.put(g.id(), new UUID[]{top.getUniqueId(), age.getUniqueId()});
        } else {
            age.customName(Text.of(ageLine(g)));
        }
    }

    private String ageLine(Grave g) {
        String age = GraveRules.age(now() - g.createdAt());
        return "&7" + (age.equals("just now") ? "just now" : age + " ago");
    }

    private ArmorStand spawnTag(World w, Grave g, double dy, String text) {
        Location at = new Location(w, g.x() + 0.5, g.y() + dy, g.z() + 0.5);
        return w.spawn(at, ArmorStand.class, as -> {
            as.setPersistent(false); // never saved with the chunk: rebuilt from the row
            as.setMarker(true);
            as.setInvisible(true);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setSilent(true);
            as.setCanPickupItems(false);
            as.setBasePlate(false);
            as.customName(Text.of(text));
            as.setCustomNameVisible(true);
            as.getPersistentDataContainer().set(Keys.GRAVE, PersistentDataType.LONG, g.id());
        });
    }

    private static ArmorStand stand(UUID id) {
        Entity e = id == null ? null : Bukkit.getEntity(id);
        return e instanceof ArmorStand as && as.isValid() ? as : null;
    }

    private static void removeTags(UUID[] ids) {
        for (UUID id : ids) {
            Entity e = id == null ? null : Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
    }

    /** A chunk came in: its graves' heads and name tags, now rather than at the next refresh. */
    public void chunkLoaded(Chunk chunk) {
        if (open.isEmpty()) {
            return;
        }
        String world = chunk.getWorld().getName();
        List<Long> here = new ArrayList<>();
        for (Grave g : open.values()) {
            if (g.world().equals(world) && g.x() >> 4 == chunk.getX() && g.z() >> 4 == chunk.getZ()) {
                here.add(g.id());
            }
        }
        // A stray tag from before a crash (they are never saved, but a chunk saved mid-way could hold one).
        for (Entity e : chunk.getEntities()) {
            if (e instanceof ArmorStand && e.getPersistentDataContainer().has(Keys.GRAVE, PersistentDataType.LONG)) {
                Long id = e.getPersistentDataContainer().get(Keys.GRAVE, PersistentDataType.LONG);
                UUID[] ours = id == null ? null : tags.get(id);
                if (ours == null || !(e.getUniqueId().equals(ours[0]) || e.getUniqueId().equals(ours[1]))) {
                    e.remove();
                }
            }
        }
        if (here.isEmpty()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (long id : here) {
                Grave g = open.get(id);
                if (g != null) {
                    seeTo(g);
                }
            }
        });
    }

    /** Head and name tags for one grave whose chunk is loaded. */
    private void seeTo(Grave g) {
        try {
            placeMarker(g, null);
            showTags(g);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Graves: could not put grave #" + g.id() + "'s marker back.", e);
        }
    }

    /** Every refresh: expiry, then the heads and name tags of graves in loaded chunks. */
    private void refresh() {
        GraveSettings s = settings();
        long now = now();
        int loads = 0;
        for (Grave g : new ArrayList<>(open.values())) {
            World w = Bukkit.getWorld(g.world());
            if (w == null) {
                continue; // a world that isn't loaded (yet): the grave waits
            }
            boolean loaded = w.isChunkLoaded(g.x() >> 4, g.z() >> 4);
            if (GraveRules.expired(g.createdAt(), now, s.expireAfterHours()) && !busy.contains(g.id())
                    && !viewers.containsKey(g.id())) {
                if (loaded || loads++ < EXPIRE_LOADS_PER_REFRESH) {
                    w.getChunkAt(g.x() >> 4, g.z() >> 4); // the items need somewhere to fall
                    expire(g);
                }
                continue;
            }
            if (loaded) {
                seeTo(g);
            } else {
                UUID[] ids = tags.remove(g.id());
                if (ids != null) {
                    removeTags(ids);
                }
            }
        }
    }

    // ---- getting things back ------------------------------------------------------------------------

    /** Why a player can't open a grave, or null when they can. */
    public String refusal(Player p, Grave g) {
        boolean admin = p.hasPermission(ADMIN);
        boolean own = g.owner().equals(p.getUniqueId());
        if (own && !admin && !p.hasPermission(USE)) {
            return "&cYou can't open graves.";
        }
        if (!GraveRules.canOpen(g.owner(), p.getUniqueId(), admin, g.createdAt(), now(),
                settings().unlockAfterMinutes())) {
            long in = GraveRules.unlocksIn(g.createdAt(), now(), settings().unlockAfterMinutes());
            return "&7This is &f" + g.ownerName() + "&7's grave." + (in > 0
                    ? " &8Anyone can open it in " + GraveRules.age(in) + "." : "");
        }
        UUID viewer = viewers.get(g.id());
        if (viewer != null && !viewer.equals(p.getUniqueId())) {
            return "&7Someone is looking in this grave right now.";
        }
        if (busy.contains(g.id())) {
            return "";
        }
        return null;
    }

    /**
     * A right-click on a grave's head. The owner gets everything back in its slots; sneaking (when
     * {@code sneak_opens_menu}) opens the grave screen instead. An admin at someone else's grave always gets the
     * screen, never a whole grave in their own bag; anyone else, once it has unlocked, gets the whole grave.
     */
    public void use(Player p, Grave g, boolean sneaking) {
        String no = refusal(p, g);
        if (no != null) {
            if (!no.isEmpty()) {
                p.sendMessage(Text.of(no));
                Sounds.refused(p);
            }
            return;
        }
        boolean own = g.owner().equals(p.getUniqueId());
        if ((sneaking && settings().sneakOpensMenu()) || (!own && p.hasPermission(ADMIN))) {
            openScreen(p, g);
            return;
        }
        lootAll(p, g, own, Grave.LOOTED);
    }

    /** The grave screen (take stacks one by one). One viewer at a time. */
    public void openScreen(Player p, Grave g) {
        UUID viewer = viewers.get(g.id());
        if (viewer != null && !viewer.equals(p.getUniqueId())) {
            Player other = Bukkit.getPlayer(viewer);
            if (other != null && other.isOnline()) {
                p.sendMessage(Text.of("&7Someone is looking in this grave right now."));
                return;
            }
        }
        viewers.put(g.id(), p.getUniqueId());
        new com.dierks.homecraft.gui.graves.GraveMenu(plugin, this, p, g.id()).open(p);
    }

    /** The grave screen closed. */
    public void screenClosed(long id, Player p) {
        viewers.remove(id, p.getUniqueId());
    }

    /**
     * Everything out of the grave and into {@code to}'s bag: emptied in the database first ({@link GraveDao#claimAll}),
     * then given. {@code toSlots}: back into the slots it came from (the owner). Returns whether anything was given.
     */
    public boolean lootAll(Player to, Grave g, boolean toSlots, String how) {
        if (!busy.add(g.id())) {
            return false;
        }
        try {
            Grave before;
            try {
                before = dao.claimAll(g.id(), how, to.getUniqueId().toString(), now(), false);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Graves: could not open grave #" + g.id() + ".", e);
                to.sendMessage(Text.of("&cThe grave wouldn't open - try again in a moment."));
                return false;
            }
            closeGrave(g);
            if (before == null) {
                to.sendMessage(Text.of("&7This grave is empty now."));
                return false;
            }
            give(to, before, toSlots);
            plugin.getLogger().info("Graves: #" + before.id() + " (" + before.ownerName() + ") " + how.toLowerCase()
                    + " by " + to.getName() + " (" + before.stacks() + " stack(s), " + before.xp() + " xp).");
            Sounds.collected(to);
            to.sendMessage(Text.of(before.owner().equals(to.getUniqueId())
                    ? "&aYou got your things back." : "&aYou took everything from " + before.ownerName() + "'s grave."));
            return true;
        } finally {
            busy.remove(g.id());
        }
    }

    /** The grave is closed: forget it, head and tags away, any open screen shut. */
    private void closeGrave(Grave g) {
        forget(g.id());
        removeMarker(g);
        UUID viewer = viewers.remove(g.id());
        Player v = viewer == null ? null : Bukkit.getPlayer(viewer);
        if (v != null && v.getOpenInventory().getTopInventory().getHolder(false)
                instanceof com.dierks.homecraft.gui.graves.GraveMenu menu && menu.graveId() == g.id()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> v.closeInventory());
        }
    }

    /** Hand a claimed grave's things to a player: into its slots when {@code toSlots}, the rest anywhere, then feet. */
    private void give(Player to, Grave g, boolean toSlots) {
        List<GraveItems.Slotted<ItemStack>> items = new ArrayList<>();
        for (GraveItems.Entry e : g.items()) {
            ItemStack item = Items.fromBase64(e.data());
            if (item == null) {
                unreadable(g, e);
                continue;
            }
            items.add(new GraveItems.Slotted<>(toSlots ? e.slot() : -1, item));
        }
        PlayerInventory inv = to.getInventory();
        GraveItems.Plan<ItemStack> plan = GraveItems.plan(items, slot -> {
            ItemStack in = inv.getItem(slot);
            return in == null || in.getType().isAir();
        });
        for (Map.Entry<Integer, ItemStack> e : plan.intoSlots().entrySet()) {
            try {
                inv.setItem(e.getKey(), e.getValue());
            } catch (RuntimeException ex) {
                atFeet(to, List.of(e.getValue()));
            }
        }
        giveAnywhere(to, plan.anywhere());
        if (g.xp() > 0) {
            to.giveExp(g.xp(), false);
        }
    }

    /** Into the bag; whatever won't fit drops at the player's feet. */
    void giveAnywhere(Player to, Collection<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack item : items) {
            try {
                left.addAll(to.getInventory().addItem(item).values());
            } catch (RuntimeException e) {
                left.add(item);
            }
        }
        if (!left.isEmpty()) {
            atFeet(to, left);
            to.sendMessage(Text.of("&7Your bag was full - the rest is at your feet."));
        }
    }

    private static void atFeet(Player p, List<ItemStack> items) {
        for (ItemStack item : items) {
            Location l = p.getLocation();
            p.getWorld().dropItem(l, item, drop -> drop.setOwner(p.getUniqueId()));
        }
    }

    private void unreadable(Grave g, GraveItems.Entry e) {
        plugin.getLogger().severe("Graves: a stack in grave #" + g.id() + " (" + g.ownerName() + ") couldn't be read "
                + "on this server and was not given. Its saved form, to hand back by hand: " + e.data());
    }

    /**
     * One stack out of the grave screen, into {@code to}'s bag (written first, {@link GraveDao#takeOne}). False when
     * it was already gone: the screen then shows what is really left.
     */
    public boolean takeOne(Player to, long id, int n) {
        Grave g = open.get(id);
        if (g == null || refusalForScreen(to, g) != null || !busy.add(id)) {
            return false;
        }
        try {
            GraveDao.Taken t = dao.takeOne(id, n, to.getUniqueId().toString(), now());
            if (t == null) {
                return false;
            }
            afterTake(g, t);
            ItemStack item = Items.fromBase64(t.entry().data());
            if (item == null) {
                unreadable(g, t.entry());
                return true;
            }
            giveAnywhere(to, List.of(item));
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Graves: could not take a stack from grave #" + id + ".", e);
            return false;
        } finally {
            busy.remove(id);
        }
    }

    /** "Take everything" on the grave screen: the screen's own check again, then {@link #lootAll}. */
    public boolean takeAllFromScreen(Player to, long id) {
        Grave g = open.get(id);
        if (g == null || refusalForScreen(to, g) != null) {
            return false;
        }
        boolean own = g.owner().equals(to.getUniqueId());
        return lootAll(to, g, own, Grave.LOOTED);
    }

    /** The experience out of the grave screen. */
    public boolean takeXp(Player to, long id) {
        Grave g = open.get(id);
        if (g == null || refusalForScreen(to, g) != null || !busy.add(id)) {
            return false;
        }
        try {
            GraveDao.Taken t = dao.takeXp(id, to.getUniqueId().toString(), now());
            if (t == null) {
                return false;
            }
            afterTake(g, t);
            to.giveExp(t.xp(), false);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Graves: could not take the experience from grave #" + id + ".", e);
            return false;
        } finally {
            busy.remove(id);
        }
    }

    /** The screen's own check: the viewer may still open it (an unlock can't run backwards, an admin can lose it). */
    private String refusalForScreen(Player p, Grave g) {
        if (!GraveRules.canOpen(g.owner(), p.getUniqueId(), p.hasPermission(ADMIN), g.createdAt(), now(),
                settings().unlockAfterMinutes())) {
            return "locked";
        }
        UUID viewer = viewers.get(g.id());
        return viewer != null && !viewer.equals(p.getUniqueId()) ? "another viewer" : null;
    }

    private void afterTake(Grave g, GraveDao.Taken t) {
        if (t.emptied()) {
            closeGrave(g);
            return;
        }
        try {
            Grave now = dao.get(g.id());
            if (now != null && now.open()) {
                open.put(now.id(), now);
            }
        } catch (SQLException ignored) {
            // the cache keeps the old count; the row is right
        }
    }

    // ---- expiry and admin ---------------------------------------------------------------------------

    /** Expired: emptied in the database, then its things dropped where it stands, and the owner told. */
    private void expire(Grave g) {
        if (!busy.add(g.id())) {
            return;
        }
        try {
            Grave before;
            try {
                before = dao.claimAll(g.id(), Grave.EXPIRED, "expiry", now(), true);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Graves: could not expire grave #" + g.id() + ".", e);
                return;
            }
            closeGrave(g);
            if (before == null) {
                return;
            }
            dropAt(before);
            plugin.getLogger().info("Graves: #" + before.id() + " (" + before.ownerName() + ") expired at "
                    + where(before) + "; " + before.stacks() + " stack(s) dropped there.");
            Player owner = Bukkit.getPlayer(before.owner());
            if (owner != null && owner.isOnline()) {
                tellNotices(owner);
            }
        } finally {
            busy.remove(g.id());
        }
    }

    /** A claimed grave's things and experience on the ground where it stood. */
    private void dropAt(Grave g) {
        World w = Bukkit.getWorld(g.world());
        if (w == null) {
            for (GraveItems.Entry e : g.items()) {
                unreadable(g, e);
            }
            return;
        }
        Location at = new Location(w, g.x() + 0.5, g.y() + 0.5, g.z() + 0.5);
        for (GraveItems.Entry e : g.items()) {
            ItemStack item = Items.fromBase64(e.data());
            if (item == null) {
                unreadable(g, e);
                continue;
            }
            w.dropItemNaturally(at, item);
        }
        if (g.xp() > 0) {
            w.spawn(at, ExperienceOrb.class, orb -> orb.setExperience(g.xp()));
        }
    }

    /** {@code /hcm graves restore}: everything straight to {@code to}, wherever they are. */
    public boolean restore(long id, Player to) {
        Grave g = open.get(id);
        if (g == null) {
            return false;
        }
        return lootAll(to, g, g.owner().equals(to.getUniqueId()), Grave.RESTORED);
    }

    /** {@code /hcm graves remove}: closed, and its things dropped where it stood (or deleted). */
    public boolean remove(long id, String by, boolean delete) {
        Grave g = open.get(id);
        if (g == null || !busy.add(id)) {
            return false;
        }
        try {
            Grave before = dao.claimAll(id, Grave.REMOVED, by, now(), false);
            if (before == null) {
                forget(id);
                return false;
            }
            World w = Bukkit.getWorld(g.world());
            if (w != null) {
                w.getChunkAt(g.x() >> 4, g.z() >> 4);
            }
            closeGrave(g);
            if (!delete) {
                dropAt(before);
            }
            plugin.getLogger().info("Graves: #" + id + " (" + before.ownerName() + ") removed by " + by
                    + (delete ? " and its " + before.stacks() + " stack(s) deleted." : "; its things dropped at " + where(before) + "."));
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Graves: could not remove grave #" + id + ".", e);
            return false;
        } finally {
            busy.remove(id);
        }
    }

    /** Messages about graves that expired while the player was away, then cleared. */
    public void tellNotices(Player p) {
        try {
            List<Grave> notes = dao.noticesFor(p.getUniqueId());
            if (notes.isEmpty()) {
                return;
            }
            for (Grave g : notes) {
                p.sendMessage(Text.of("&7Your grave at &f" + where(g) + " &7was there too long: your things were "
                        + "dropped on the ground where it stood."));
            }
            dao.clearNotices(p.getUniqueId());
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Graves: could not read " + p.getName() + "'s grave messages.", e);
        }
    }

    // ---- words ----------------------------------------------------------------------------------------

    public static String where(Grave g) {
        return where(g.world(), g.x(), g.y(), g.z());
    }

    static String where(String world, int x, int y, int z) {
        return world + " " + x + ", " + y + ", " + z;
    }

    /** How long ago the death was: "5m", "2h 3m". */
    public String ageOf(Grave g) {
        return GraveRules.age(now() - g.createdAt());
    }

    /** One line for the lists: id, place, age, stacks. */
    public String line(Grave g, boolean withOwner) {
        return "&e#" + g.id() + " &f" + where(g) + " &7" + GraveRules.age(now() - g.createdAt()) + " ago, "
                + g.stacks() + " stack" + (g.stacks() == 1 ? "" : "s") + (g.xp() > 0 ? ", " + g.xp() + " xp" : "")
                + (withOwner ? " &8(" + g.ownerName() + ")" : "");
    }
}
