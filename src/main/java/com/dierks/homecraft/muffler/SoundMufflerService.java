package com.dierks.homecraft.muffler;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.block.CustomBlockType;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.storage.PlacedBlock;
import com.dierks.homecraft.storage.SoundMufflerDao;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Sound Mufflers: the registry of placed mufflers, what the packet listener reads, and the few
 * things the listener hands back to the main thread.
 *
 * <p><b>How a sound is hushed.</b> Bukkit has no event for a sound being played, so the muffler
 * reaches sounds on their way to each player, through ProtocolLib (a soft dependency, already on
 * the live server). {@link MufflerPacketListener} looks at three packets: a sound at a position,
 * a sound attached to an entity, and a "level event" (the dispenser click and friends — see
 * {@link LevelEvents}). Silent drops the packet; Quieter drops it and plays a quieter copy to the
 * same player on the next tick (see {@link Replay} for why it is never edited in place).
 *
 * <p><b>Threads.</b> Packets leave on network threads. Everything the listener touches is either
 * immutable ({@link MufflerZones}, swapped in whole after every change) or concurrent (the
 * "Heard nearby" lists, the replay queue). Everything else here is main-thread only.
 *
 * <p><b>What it can't reach.</b> Sounds the player's own game makes without being told: rain,
 * thunder, music and jukeboxes, furnaces and campfires, portals, lava, minecarts rolling, bees in
 * flight, and your own footsteps, clicks and pickups. The menus say so rather than offering
 * buttons that do nothing.
 */
public final class SoundMufflerService {

    /** How long a sound stays on a muffler's "Heard nearby" list. */
    static final long HEARD_WINDOW_MS = 10 * 60_000L;
    /** Distinct sounds one muffler remembers hearing. */
    static final int HEARD_LIMIT = 150;
    /** Quieter copies waiting for the next tick; past this they are simply dropped (which is quieter still). */
    private static final int REPLAY_QUEUE_LIMIT = 4096;
    /** How long "Show the area" draws the box. */
    private static final int PREVIEW_SECONDS = 10;

    /** One sound on a muffler's "Heard nearby" list. */
    public record Heard(String key, long at) {
    }

    /** A quieter copy of a sound, waiting to be played. */
    private record Pending(Player player, String world, double x, double y, double z, String key,
                           SoundCategory category, float volume, float pitch) {
    }

    private final HomeCraftManagement plugin;
    private final SoundMufflerDao dao;
    /** Main thread only. */
    private final Map<MufflerPos, Muffler> mufflers = new HashMap<>();
    /** What the packet threads read; replaced whole on every change. */
    private volatile MufflerZones zones = MufflerZones.EMPTY;
    private final Map<MufflerPos, Map<String, Long>> heard = new ConcurrentHashMap<>();
    private final BiConsumer<Muffler, String> heardSink = this::heard;
    private final ConcurrentLinkedQueue<Pending> replays = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final Map<UUID, BukkitTask> previews = new HashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private volatile boolean configEnabled = true;
    private volatile boolean hooked;
    private Runnable unhook;
    private String problem = "";
    private BukkitTask replayTask;
    private BukkitTask sweepTask;
    private List<String> allKeys;

    public SoundMufflerService(HomeCraftManagement plugin, SoundMufflerDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    /** Load every muffler, hook ProtocolLib and start the two small timers. Call once, on enable. */
    public void start() {
        reload();
        load();
        hook();
        replayTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::drainReplays, 1L, 1L);
        sweepTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweep, 600L, 600L);
    }

    /** Re-read {@code sound_muffler} from config (on/off, range limits) and re-clamp every muffler. */
    public void reload() {
        PluginConfig.SoundMuffler cfg = plugin.config().soundMuffler();
        configEnabled = cfg.enabled();
        List<Muffler> tooFar = new ArrayList<>();
        for (Muffler m : mufflers.values()) {
            if (m.radius() > cfg.maxRadius()) {
                tooFar.add(m.withRadius(cfg.maxRadius()));
            }
        }
        for (Muffler m : tooFar) {
            store(m);
        }
        publish();
    }

    /** Unhook ProtocolLib and stop everything. Safe to call twice. */
    public void stop() {
        if (unhook != null) {
            try {
                unhook.run();
            } catch (Throwable t) {
                plugin.getLogger().warning("Sound Muffler: could not unhook ProtocolLib cleanly: " + t.getMessage());
            }
            unhook = null;
        }
        hooked = false;
        if (replayTask != null) {
            replayTask.cancel();
            replayTask = null;
        }
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        for (BukkitTask t : previews.values()) {
            t.cancel();
        }
        previews.clear();
        replays.clear();
        queued.set(0);
        zones = MufflerZones.EMPTY;
    }

    /**
     * Load the table and square it with {@code placed_blocks}: settings whose block is gone are
     * dropped, and a muffler block with no settings (a crash between the two writes) gets fresh ones.
     */
    private void load() {
        mufflers.clear();
        Map<MufflerPos, PlacedBlock> placed = new HashMap<>();
        for (PlacedBlock pb : plugin.blockService().findByType(CustomBlockType.SOUND_MUFFLER)) {
            placed.put(new MufflerPos(pb.world(), pb.x(), pb.y(), pb.z()), pb);
        }
        List<Muffler> rows;
        try {
            rows = dao.all();
        } catch (SQLException e) {
            plugin.getLogger().severe("Sound Muffler: could not load mufflers: " + e.getMessage());
            rows = List.of();
        }
        for (Muffler m : rows) {
            if (!placed.containsKey(m.pos())) {
                plugin.getLogger().info("Sound Muffler: forgetting settings at " + m.pos() + " — the block is gone.");
                delete(m.pos());
                continue;
            }
            mufflers.put(m.pos(), m);
        }
        PluginConfig.SoundMuffler cfg = plugin.config().soundMuffler();
        for (Map.Entry<MufflerPos, PlacedBlock> e : placed.entrySet()) {
            if (!mufflers.containsKey(e.getKey())) {
                store(Muffler.placed(e.getKey(), e.getValue().owner(), cfg.defaultRadius(), cfg.defaultQuietPercent()));
            }
        }
        publish();
        if (!mufflers.isEmpty()) {
            plugin.getLogger().info("Sound Muffler: " + mufflers.size() + " muffler(s) loaded.");
        }
    }

    /**
     * Hook the packet listener when ProtocolLib is running. The listener class extends ProtocolLib's
     * and is only ever loaded from here, behind the check, so the plugin starts without it.
     */
    private void hook() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            problem = "ProtocolLib isn't installed, so mufflers can't reach any sounds.";
            plugin.getLogger().info("Sound Muffler: ProtocolLib is not installed — mufflers can be placed and "
                    + "set up, but they won't hush anything until it is.");
            return;
        }
        try {
            unhook = MufflerPacketListener.install(plugin, this);
            hooked = true;
            problem = "";
            plugin.getLogger().info("Sound Muffler: hooked into ProtocolLib.");
        } catch (Throwable t) {
            problem = "ProtocolLib is installed but couldn't be hooked (" + t.getClass().getSimpleName() + ").";
            plugin.getLogger().log(Level.WARNING, "Sound Muffler: could not hook ProtocolLib — mufflers won't hush "
                    + "anything.", t);
        }
    }

    // ---- what the packet listener reads (any thread) ---------------------------------------------

    /** Whether mufflers are hushing anything right now. */
    public boolean working() {
        return hooked && configEnabled;
    }

    /** Why {@link #working()} is false, in words for a player; "" when it is true. */
    public String problem() {
        if (!configEnabled) {
            return "Sound Mufflers are switched off on this server.";
        }
        return hooked ? "" : problem;
    }

    /** The frozen set of mufflers the listener decides against. */
    MufflerZones zones() {
        return zones;
    }

    /** Whether the listener should look at sounds at all. */
    boolean muffling() {
        return configEnabled && !zones.isEmpty();
    }

    BiConsumer<Muffler, String> heardSink() {
        return heardSink;
    }

    /** Note that a muffler heard a sound (for "Heard nearby"). Any thread. */
    void heard(Muffler m, String key) {
        Map<String, Long> list = heard.computeIfAbsent(m.pos(), p -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();
        list.put(key, now);
        if (list.size() > HEARD_LIMIT) {
            trim(list, now);
        }
    }

    /** Queue a quieter copy of a sound for one player, played on the next tick. Any thread. */
    void replay(Player player, double x, double y, double z, String key, SoundCategory category,
                float volume, float pitch) {
        if (queued.incrementAndGet() > REPLAY_QUEUE_LIMIT) {
            queued.decrementAndGet();
            return; // swamped: dropping it is only quieter than asked
        }
        replays.add(new Pending(player, player.getWorld().getName(), x, y, z, key, category, volume, pitch));
    }

    /** A packet the listener couldn't read: it went through untouched. Logged once per kind. */
    void packetFailed(String packet, Throwable t) {
        String kind = packet + " " + t.getClass().getName();
        if (warned.size() < 32 && warned.add(kind)) {
            plugin.getLogger().log(Level.WARNING, "Sound Muffler: could not read a " + packet
                    + " packet — it was let through untouched. (Logged once per kind of problem.)", t);
        }
    }

    // ---- main-thread timers ---------------------------------------------------------------------

    private void drainReplays() {
        Pending p;
        while ((p = replays.poll()) != null) {
            queued.decrementAndGet();
            Player player = p.player();
            if (!player.isOnline() || !player.getWorld().getName().equals(p.world())) {
                continue;
            }
            try {
                player.playSound(new Location(player.getWorld(), p.x(), p.y(), p.z()), p.key(), p.category(),
                        p.volume(), p.pitch(), Replay.seed());
            } catch (Throwable t) {
                if (warned.add("replay " + t.getClass().getName())) {
                    plugin.getLogger().warning("Sound Muffler: could not play a quieter sound: " + t.getMessage());
                }
            }
        }
    }

    /**
     * Every 30 seconds: forget mufflers whose block has vanished without a break event (WorldEdit,
     * /setblock), so nobody is left with an invisible muffler hushing a farm; and let old
     * "Heard nearby" entries go.
     */
    private void sweep() {
        List<Muffler> gone = new ArrayList<>();
        for (Muffler m : mufflers.values()) {
            World w = plugin.getServer().getWorld(m.pos().world());
            if (w == null || !w.isChunkLoaded(m.pos().x() >> 4, m.pos().z() >> 4)) {
                continue;
            }
            if (w.getBlockAt(m.pos().x(), m.pos().y(), m.pos().z()).getType().isAir()) {
                gone.add(m);
            }
        }
        for (Muffler m : gone) {
            plugin.getLogger().info("Sound Muffler at " + m.pos() + " is gone from the world — forgetting it.");
            World w = plugin.getServer().getWorld(m.pos().world());
            if (w != null) {
                plugin.blockService().removeAt(new Location(w, m.pos().x(), m.pos().y(), m.pos().z()));
            }
            forget(m.pos());
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<MufflerPos, Map<String, Long>> e : heard.entrySet()) {
            if (!mufflers.containsKey(e.getKey())) {
                heard.remove(e.getKey());
            } else {
                e.getValue().values().removeIf(at -> now - at > HEARD_WINDOW_MS);
            }
        }
    }

    // ---- the registry (main thread) -------------------------------------------------------------

    public static MufflerPos posOf(Location loc) {
        return new MufflerPos(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public Muffler at(MufflerPos pos) {
        return mufflers.get(pos);
    }

    public Muffler at(Location loc) {
        return loc == null || loc.getWorld() == null ? null : mufflers.get(posOf(loc));
    }

    public boolean isMuffler(Block block) {
        return !mufflers.isEmpty() && mufflers.containsKey(posOf(block.getLocation()));
    }

    /**
     * A muffler was just placed. It starts from the settings its item remembers (one that was
     * picked up and put down again), or fresh.
     */
    public Muffler placed(Block block, UUID owner, ItemStack item) {
        PluginConfig.SoundMuffler cfg = plugin.config().soundMuffler();
        MufflerPos pos = posOf(block.getLocation());
        Muffler m = Muffler.fromMemory(memoryOn(item), pos, owner);
        if (m == null) {
            m = Muffler.placed(pos, owner, cfg.defaultRadius(), cfg.defaultQuietPercent());
        } else if (m.radius() > cfg.maxRadius()) {
            m = m.withRadius(cfg.maxRadius());
        }
        store(m);
        publish();
        return m;
    }

    /** Save a changed muffler and let the listener see it. Returns what was stored. */
    public Muffler update(Muffler next) {
        if (!mufflers.containsKey(next.pos())) {
            return next; // broken while a menu was open
        }
        store(next);
        publish();
        return next;
    }

    /** The muffler at this block was broken (or vanished): forget it everywhere. */
    public void removed(Location loc) {
        if (loc != null && loc.getWorld() != null) {
            forget(posOf(loc));
        }
    }

    /** The owner, or an admin, may change a muffler. Anyone else may look. */
    public boolean canEdit(Player player, Muffler m) {
        return m.owner().equals(player.getUniqueId()) || player.hasPermission("hcm.admin");
    }

    /** The biggest range a muffler may be set to on this server. */
    public int maxRadius() {
        return plugin.config().soundMuffler().maxRadius();
    }

    private void forget(MufflerPos pos) {
        if (mufflers.remove(pos) != null) {
            delete(pos);
        }
        heard.remove(pos);
        publish();
    }

    private void store(Muffler m) {
        mufflers.put(m.pos(), m);
        try {
            dao.save(m, System.currentTimeMillis());
        } catch (SQLException e) {
            plugin.getLogger().severe("Sound Muffler: could not save the muffler at " + m.pos() + ": " + e.getMessage());
        }
    }

    private void delete(MufflerPos pos) {
        try {
            dao.delete(pos);
        } catch (SQLException e) {
            plugin.getLogger().severe("Sound Muffler: could not delete the muffler at " + pos + ": " + e.getMessage());
        }
    }

    private void publish() {
        zones = MufflerZones.of(mufflers.values());
    }

    // ---- the item ------------------------------------------------------------------------------

    /**
     * The item a broken muffler drops. A muffler that was set up remembers it — its picks, range,
     * Quieter volume and power — so moving one is pick up, put down, done.
     */
    public ItemStack itemFor(Muffler m) {
        ItemStack item = plugin.items().soundMuffler();
        PluginConfig.SoundMuffler cfg = plugin.config().soundMuffler();
        if (m == null || m.isDefault(cfg.defaultRadius(), cfg.defaultQuietPercent())) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.getPersistentDataContainer().set(Keys.MUFFLER_MEMORY, PersistentDataType.STRING, m.encodeMemory());
        List<net.kyori.adventure.text.Component> lore = meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Text.of("&8—"));
        lore.add(Text.of("&bRemembers its settings:"));
        lore.add(Text.of("&7" + m.ruleCount() + " choice" + (m.ruleCount() == 1 ? "" : "s")
                + ", range &f" + m.radius() + "&7" + (m.enabled() ? "" : ", &7switched off")));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String memoryOn(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(Keys.MUFFLER_MEMORY, PersistentDataType.STRING);
    }

    // ---- menus ---------------------------------------------------------------------------------

    /** What this muffler has heard lately, newest first. */
    public List<Heard> heardBy(Muffler m) {
        Map<String, Long> list = heard.get(m.pos());
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<Heard> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : list.entrySet()) {
            if (now - e.getValue() <= HEARD_WINDOW_MS) {
                out.add(new Heard(e.getKey(), e.getValue()));
            }
        }
        out.sort(Comparator.comparingLong(Heard::at).reversed());
        return out;
    }

    /** Every sound in the game (and any resource pack registered on the server), sorted by key. */
    public List<String> allSoundKeys() {
        if (allKeys == null) {
            List<String> keys = new ArrayList<>();
            try {
                for (Sound s : Registry.SOUNDS) {
                    org.bukkit.NamespacedKey key = Registry.SOUNDS.getKey(s);
                    if (key != null) {
                        keys.add(key.asString());
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Sound Muffler: could not list the game's sounds: " + t.getMessage());
            }
            Collections.sort(keys);
            allKeys = Collections.unmodifiableList(keys);
        }
        return allKeys;
    }

    /** Sounds whose key holds every word of {@code query}. */
    public List<String> search(String query) {
        List<String> out = new ArrayList<>();
        for (String key : allSoundKeys()) {
            if (SoundNames.matches(key, query)) {
                out.add(key);
            }
        }
        return out;
    }

    /**
     * Draw the muffler's box in particles, for this player only, for a few seconds — so "8 blocks"
     * can be seen instead of imagined.
     */
    public void showArea(Player player, Muffler m) {
        BukkitTask old = previews.remove(player.getUniqueId());
        if (old != null) {
            old.cancel();
        }
        World world = plugin.getServer().getWorld(m.pos().world());
        if (world == null || !world.equals(player.getWorld())) {
            return;
        }
        double minX = m.pos().x() - m.radius();
        double minY = m.pos().y() - m.radius();
        double minZ = m.pos().z() - m.radius();
        double maxX = m.pos().x() + m.radius() + 1;
        double maxY = m.pos().y() + m.radius() + 1;
        double maxZ = m.pos().z() + m.radius() + 1;
        double step = m.boxSize() > 17 ? 2.0 : 1.0;
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(0x55, 0xCC, 0xFF), 1.3f);
        int[] runs = {0};
        UUID id = player.getUniqueId();
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !player.getWorld().equals(world) || runs[0]++ >= PREVIEW_SECONDS * 2) {
                BukkitTask self = previews.remove(id);
                if (self != null) {
                    self.cancel();
                }
                return;
            }
            try {
                edges(player, dust, step, minX, minY, minZ, maxX, maxY, maxZ);
            } catch (Throwable t) {
                BukkitTask self = previews.remove(id);
                if (self != null) {
                    self.cancel();
                }
            }
        }, 0L, 10L);
        previews.put(id, task);
    }

    private static void edges(Player p, Particle.DustOptions dust, double step,
                              double x0, double y0, double z0, double x1, double y1, double z1) {
        for (double x = x0; x <= x1; x += step) {
            dot(p, dust, x, y0, z0);
            dot(p, dust, x, y0, z1);
            dot(p, dust, x, y1, z0);
            dot(p, dust, x, y1, z1);
        }
        for (double y = y0; y <= y1; y += step) {
            dot(p, dust, x0, y, z0);
            dot(p, dust, x0, y, z1);
            dot(p, dust, x1, y, z0);
            dot(p, dust, x1, y, z1);
        }
        for (double z = z0; z <= z1; z += step) {
            dot(p, dust, x0, y0, z);
            dot(p, dust, x0, y1, z);
            dot(p, dust, x1, y0, z);
            dot(p, dust, x1, y1, z);
        }
    }

    private static void dot(Player p, Particle.DustOptions dust, double x, double y, double z) {
        p.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** A SoundCategory by name, MASTER when it isn't one (a newer category, a ProtocolLib gap). */
    static SoundCategory category(String name) {
        if (name != null) {
            try {
                return SoundCategory.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return SoundCategory.MASTER;
    }

    /** Drop entries past the window, then the oldest, until the list fits. */
    private static void trim(Map<String, Long> list, long now) {
        list.values().removeIf(at -> now - at > HEARD_WINDOW_MS);
        while (list.size() > HEARD_LIMIT) {
            String oldest = null;
            long when = Long.MAX_VALUE;
            for (Map.Entry<String, Long> e : list.entrySet()) {
                if (e.getValue() < when) {
                    when = e.getValue();
                    oldest = e.getKey();
                }
            }
            if (oldest == null) {
                return;
            }
            list.remove(oldest);
        }
    }
}
