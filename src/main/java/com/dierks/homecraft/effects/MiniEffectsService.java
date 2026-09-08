package com.dierks.homecraft.effects;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.block.CustomBlockType;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.mini.AnnounceService;
import com.dierks.homecraft.mini.Grade;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.storage.MiniListingDao;
import com.dierks.homecraft.storage.PlacedBlock;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * World effects for placed Minis (Display Case trophies, posed armor-stand Minis,
 * and natural wild spawns), driven per rarity by {@code minis.effects}: ambient
 * particles, a floating name hologram ({@link TextDisplay}), a hidden LIGHT block in
 * the nearest air neighbour, a slowly rotating / full-bright {@link ItemDisplay}
 * for the top tiers, a placement sound + burst, the Mint chime, and a ring of
 * Shiny particles on top of everything.
 *
 * <p>One repeating task serves every placed Mini, skipping any with no player within
 * {@code minis.effects.radius} and any in an unloaded chunk. Effect entities are
 * non-persistent and tagged, so they never save to disk: they are torn down on
 * removal, chunk unload and plugin disable, and swept + rebuilt from the datastore on
 * load — nothing leaks after a crash.
 */
public final class MiniEffectsService implements Listener {

    /** What kind of placed Mini an entry is (decides geometry and which extras apply). */
    public enum Kind { DISPLAY_CASE, STAND, WILD_SPAWN }

    private static final BlockFace[] LIGHT_CANDIDATES = {
            BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN};

    /** One placed Mini under effect management. */
    private static final class Placed {
        final String key;
        final Kind kind;
        final World world;
        final int bx;
        final int by;
        final int bz;
        final MiniDef def;
        final Grade grade;
        final boolean shiny;
        final ItemStack item;
        final String hologramText; // null = the Mini's own name
        final UUID standId;        // STAND only
        UUID hologramId;
        UUID displayId;
        Location lightLoc;
        long ticks;
        float yaw;

        Placed(String key, Kind kind, Location loc, MiniDef def, Grade grade, boolean shiny, ItemStack item,
               String hologramText, UUID standId) {
            this.key = key;
            this.kind = kind;
            this.world = loc.getWorld();
            this.bx = loc.getBlockX();
            this.by = loc.getBlockY();
            this.bz = loc.getBlockZ();
            this.def = def;
            this.grade = grade;
            this.shiny = shiny;
            this.item = item;
            this.hologramText = hologramText;
            this.standId = standId;
        }
    }

    private final HomeCraftManagement plugin;
    private final Map<String, Placed> placed = new LinkedHashMap<>();
    private final Map<String, Particle> particleCache = new HashMap<>();
    private final Set<String> warnedParticles = new HashSet<>();
    private BukkitTask task;

    public MiniEffectsService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    // ---- lifecycle ---------------------------------------------------------------

    /** (Re)arm the effect task at the configured cadence; keeps the registry. */
    public void start() {
        stopTask();
        int interval = Math.max(1, cfg().tickInterval());
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    /** Stop the task and tear down every entity + light we own. */
    public void stop() {
        stopTask();
        for (Placed p : new ArrayList<>(placed.values())) {
            teardown(p);
        }
        placed.clear();
    }

    /**
     * Rebuild from persistent sources: sweep stale effect entities, then register every
     * Display Case with a loaded Mini (from the placed-block + listing tables) and every
     * armor-stand Mini in a loaded chunk. Natural spawns re-register themselves.
     */
    public void rebuild() {
        stop();
        start();
        sweepStaleEntities();
        for (PlacedBlock pb : plugin.blockService().findByType(CustomBlockType.DISPLAY_CASE)) {
            World w = Bukkit.getWorld(pb.world());
            if (w == null) {
                continue;
            }
            refreshBlock(new Location(w, pb.x(), pb.y(), pb.z()), false);
        }
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e instanceof ArmorStand stand && plugin.stands() != null && plugin.stands().isMiniStand(stand)) {
                    registerStand(stand, false);
                }
            }
        }
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ---- registration ------------------------------------------------------------

    /**
     * Sync a Display Case with its listing: a loaded Mini gets (or keeps) its effects,
     * an empty case loses them. {@code justPlaced} plays the placement sound/burst.
     */
    public void refreshBlock(Location loc, boolean justPlaced) {
        if (loc == null || loc.getWorld() == null || plugin.vending() == null) {
            return;
        }
        Optional<MiniListingDao.Listing> listing = plugin.vending().at(loc);
        if (listing.isEmpty()) {
            unregisterBlock(loc);
            return;
        }
        ItemStack item = Items.fromBase64(listing.get().itemB64());
        if (item == null) {
            unregisterBlock(loc);
            return;
        }
        String key = blockKey(loc);
        if (placed.containsKey(key)) {
            return; // already managed
        }
        register(key, Kind.DISPLAY_CASE, loc, item, null, null, justPlaced);
    }

    /** A naturally spawned wild-Mini head: its rarity effects plus the "A wild Mini!" hologram. */
    public void registerWild(Location loc, ItemStack item, boolean justPlaced) {
        register(blockKey(loc), Kind.WILD_SPAWN, loc, item, cfg().wildHologramText(), null, justPlaced);
    }

    /** A posed armor-stand Mini. */
    public void registerStand(ArmorStand stand, boolean justPlaced) {
        if (stand == null || plugin.stands() == null) {
            return;
        }
        ItemStack item = plugin.stands().storedItem(stand);
        if (item == null) {
            return;
        }
        String key = "stand:" + stand.getUniqueId();
        if (placed.containsKey(key)) {
            return;
        }
        register(key, Kind.STAND, stand.getLocation(), item, null, stand.getUniqueId(), justPlaced);
    }

    public void unregisterBlock(Location loc) {
        if (loc != null && loc.getWorld() != null) {
            unregisterKey(blockKey(loc));
        }
    }

    public void unregisterStand(UUID standId) {
        if (standId != null) {
            unregisterKey("stand:" + standId);
        }
    }

    private void register(String key, Kind kind, Location loc, ItemStack item, String hologramText, UUID standId,
                          boolean justPlaced) {
        MiniService minis = plugin.miniService();
        MiniService.MiniRef ref = minis.identify(item);
        MiniDef def = ref == null ? null : minis.def(ref.miniId());
        if (def == null || loc.getWorld() == null) {
            return;
        }
        Placed old = placed.remove(key);
        if (old != null) {
            teardown(old);
        }
        Placed p = new Placed(key, kind, loc, def, minis.gradeOf(item), minis.isShiny(item), item.clone(),
                hologramText, standId);
        placed.put(key, p);
        PluginConfig.MiniEffect fx = cfg().of(def.rarity());
        if (loc.getWorld().isChunkLoaded(p.bx >> 4, p.bz >> 4)) {
            ensureLight(p, fx);
            if (justPlaced) {
                placeEffects(p, fx);
            }
        }
    }

    private void unregisterKey(String key) {
        Placed p = placed.remove(key);
        if (p != null) {
            teardown(p);
        }
    }

    /** Placement sound + particle burst for the rarity, and the Mint chime for a Mint copy. */
    private void placeEffects(Placed p, PluginConfig.MiniEffect fx) {
        Location base = baseLoc(p).add(0, 0.9, 0);
        if (fx.placeSound() != null) {
            try {
                p.world.playSound(base, AnnounceService.sound(fx.placeSound()), 1.0f, 1.0f);
            } catch (Throwable ignored) {
                // unknown sound name — skip
            }
        }
        if (fx.placeParticle() != null) {
            spawnParticle(fx.placeParticle(), base, 40, 0.5);
        }
        if (p.grade == Grade.MINT) {
            playMintChime(base);
        }
    }

    /** The amethyst chime a Mint-grade copy earns on print and on placement. */
    public void playMintChime(Location at) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        try {
            Sound s = AnnounceService.sound(cfg().mintSound());
            at.getWorld().playSound(at, s == Sound.ENTITY_EXPERIENCE_ORB_PICKUP ? Sound.BLOCK_AMETHYST_BLOCK_CHIME : s,
                    1.0f, 1.0f);
        } catch (Throwable ignored) {
            // cosmetic
        }
    }

    // ---- the tick ----------------------------------------------------------------

    private void tick() {
        PluginConfig.MiniEffects cfg = cfg();
        int interval = Math.max(1, cfg.tickInterval());
        double r2 = cfg.radius() * cfg.radius();
        for (Placed p : new ArrayList<>(placed.values())) {
            if (!p.world.isChunkLoaded(p.bx >> 4, p.bz >> 4)) {
                p.hologramId = null; // non-persistent entities went with the chunk
                p.displayId = null;
                continue;
            }
            ArmorStand stand = null;
            if (p.kind == Kind.STAND) {
                Entity e = p.standId == null ? null : Bukkit.getEntity(p.standId);
                if (!(e instanceof ArmorStand s) || !e.isValid()) {
                    unregisterKey(p.key);
                    continue;
                }
                stand = s;
            }
            Location base = baseLoc(p);
            if (!playerNear(p, base, r2)) {
                continue;
            }
            PluginConfig.MiniEffect fx = cfg.of(p.def.rarity());
            p.ticks += interval;
            boolean wantsDisplay = p.kind == Kind.DISPLAY_CASE && (fx.rotate() || fx.fullBright());
            if (fx.hologram() || p.hologramText != null) {
                ensureHologram(p, fx, base, wantsDisplay);
            }
            if (wantsDisplay) {
                ensureDisplay(p, fx, base, interval);
            }
            if (stand != null && fx.rotate() && fx.degreesPerSecond() > 0) {
                float step = (float) (fx.degreesPerSecond() * interval / 20.0);
                stand.setRotation(stand.getLocation().getYaw() + step, 0f);
            }
            if (fx.particle() != null && fx.particleInterval() > 0 && p.ticks % fx.particleInterval() < interval) {
                spawnParticle(fx.particle(), base.clone().add(0, 0.8, 0), fx.particleCount(), fx.particleOffset());
            }
            if (p.shiny && p.ticks % 20 < interval) {
                shinyRing(base.clone().add(0, 1.0, 0));
            }
            ensureLight(p, fx);
        }
    }

    private boolean playerNear(Placed p, Location base, double r2) {
        for (Player pl : p.world.getPlayers()) {
            if (pl.getLocation().distanceSquared(base) <= r2) {
                return true;
            }
        }
        return false;
    }

    /** The block centre (or the stand's feet) that everything hangs off. */
    private Location baseLoc(Placed p) {
        if (p.kind == Kind.STAND && p.standId != null) {
            Entity e = Bukkit.getEntity(p.standId);
            if (e != null) {
                return e.getLocation();
            }
        }
        return new Location(p.world, p.bx + 0.5, p.by, p.bz + 0.5);
    }

    private void ensureHologram(Placed p, PluginConfig.MiniEffect fx, Location base, boolean aboveDisplay) {
        if (p.hologramId != null) {
            Entity e = Bukkit.getEntity(p.hologramId);
            if (e != null && e.isValid()) {
                return;
            }
            p.hologramId = null;
        }
        double dy = p.kind == Kind.STAND ? 2.3 : (aboveDisplay ? 2.0 : 1.45);
        Component text = p.hologramText != null
                ? Text.of(p.hologramText)
                : Component.text(p.def.name() + " " + p.grade.symbol(),
                        color(fx.hologramColor(), plugin.miniService().style(p.def.rarity()).nameColor()))
                .decoration(TextDecoration.ITALIC, false);
        try {
            TextDisplay td = p.world.spawn(base.clone().add(0, dy, 0), TextDisplay.class, d -> {
                d.text(text);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setSeeThrough(false);
                d.setBackgroundColor(Color.fromARGB(0x40000000));
                d.setPersistent(false);
                d.getPersistentDataContainer().set(Keys.EFFECT_ENTITY, PersistentDataType.BYTE, (byte) 1);
            });
            p.hologramId = td.getUniqueId();
        } catch (Throwable t) {
            p.hologramId = null; // display entities unavailable — skip quietly
        }
    }

    private void ensureDisplay(Placed p, PluginConfig.MiniEffect fx, Location base, int interval) {
        ItemDisplay display = null;
        if (p.displayId != null) {
            Entity e = Bukkit.getEntity(p.displayId);
            if (e instanceof ItemDisplay d && e.isValid()) {
                display = d;
            } else {
                p.displayId = null;
            }
        }
        if (display == null) {
            try {
                display = p.world.spawn(base.clone().add(0, 1.15, 0), ItemDisplay.class, d -> {
                    d.setItemStack(p.item.clone());
                    d.setBillboard(Display.Billboard.FIXED);
                    if (fx.fullBright()) {
                        d.setBrightness(new Display.Brightness(15, 15));
                    }
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(Math.max(1, interval));
                    d.setTransformation(new Transformation(new Vector3f(0f, 0f, 0f), new Quaternionf(),
                            new Vector3f(0.6f, 0.6f, 0.6f), new Quaternionf()));
                    d.setPersistent(false);
                    d.getPersistentDataContainer().set(Keys.EFFECT_ENTITY, PersistentDataType.BYTE, (byte) 1);
                });
                p.displayId = display.getUniqueId();
            } catch (Throwable t) {
                p.displayId = null;
                return;
            }
        }
        if (fx.rotate() && fx.degreesPerSecond() > 0) {
            p.yaw = (float) ((p.yaw + fx.degreesPerSecond() * interval / 20.0) % 360.0);
            Transformation tr = display.getTransformation();
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(Math.max(1, interval));
            display.setTransformation(new Transformation(tr.getTranslation(),
                    new Quaternionf().rotationY((float) Math.toRadians(p.yaw)), tr.getScale(), tr.getRightRotation()));
        }
    }

    private void ensureLight(Placed p, PluginConfig.MiniEffect fx) {
        if (!fx.light() || fx.lightLevel() <= 0) {
            clearLight(p);
            return;
        }
        if (p.lightLoc != null) {
            Block b = p.lightLoc.getBlock();
            if (b.getType() == Material.LIGHT) {
                return;
            }
            if (!b.getType().isAir()) {
                p.lightLoc = null; // someone built there — find another spot
            }
        }
        if (p.lightLoc == null) {
            Block origin = p.world.getBlockAt(p.bx, p.by, p.bz);
            Block target = null;
            if (p.kind == Kind.STAND && (origin.getType().isAir() || origin.getType() == Material.LIGHT)) {
                target = origin; // a stand's own block is air — light it from inside
            }
            for (int i = 0; target == null && i < LIGHT_CANDIDATES.length; i++) {
                Block c = origin.getRelative(LIGHT_CANDIDATES[i]);
                if (c.getType() == Material.LIGHT || c.getType().isAir()) {
                    target = c;
                }
            }
            if (target == null) {
                return;
            }
            p.lightLoc = target.getLocation();
        }
        try {
            BlockData data = Material.LIGHT.createBlockData();
            if (data instanceof Levelled lv) {
                lv.setLevel(Math.max(0, Math.min(lv.getMaximumLevel(), fx.lightLevel())));
            }
            p.lightLoc.getBlock().setBlockData(data, false);
        } catch (Throwable t) {
            p.lightLoc = null;
        }
    }

    private void clearLight(Placed p) {
        if (p.lightLoc == null) {
            return;
        }
        Block b = p.lightLoc.getBlock();
        if (b.getType() == Material.LIGHT) {
            b.setType(Material.AIR, false);
        }
        p.lightLoc = null;
    }

    private void shinyRing(Location centre) {
        Particle particle = particle(cfg().shinyParticle());
        if (particle == null) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8.0;
            Location at = centre.clone().add(Math.cos(a) * 0.55, 0, Math.sin(a) * 0.55);
            try {
                centre.getWorld().spawnParticle(particle, at, 1, 0, 0, 0, 0.0);
            } catch (Throwable ignored) {
                return;
            }
        }
    }

    private void spawnParticle(String name, Location at, int count, double offset) {
        Particle particle = particle(name);
        if (particle == null || count <= 0) {
            return;
        }
        try {
            at.getWorld().spawnParticle(particle, at, count, offset, offset, offset, 0.0);
        } catch (Throwable t) {
            if (warnedParticles.add(name)) {
                plugin.getLogger().warning("Particle '" + name + "' can't be spawned without data — disabled: " + t.getMessage());
            }
            particleCache.put(name.toUpperCase(Locale.ROOT), null);
        }
    }

    private Particle particle(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim().toUpperCase(Locale.ROOT);
        if (particleCache.containsKey(key)) {
            return particleCache.get(key);
        }
        Particle p = null;
        try {
            p = Particle.valueOf(key);
        } catch (Throwable t) {
            if (warnedParticles.add(key)) {
                plugin.getLogger().warning("Unknown particle '" + name + "' in minis.effects — ignored.");
            }
        }
        particleCache.put(key, p);
        return p;
    }

    private static TextColor color(String name, TextColor fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("#") && n.length() == 7) {
            TextColor c = TextColor.fromHexString(n);
            return c != null ? c : fallback;
        }
        NamedTextColor named = NamedTextColor.NAMES.value(n);
        return named != null ? named : fallback;
    }

    private void teardown(Placed p) {
        removeEntity(p.hologramId);
        removeEntity(p.displayId);
        p.hologramId = null;
        p.displayId = null;
        if (p.world.isChunkLoaded(p.bx >> 4, p.bz >> 4) || p.lightLoc != null) {
            clearLight(p);
        }
    }

    private void removeEntity(UUID id) {
        if (id == null) {
            return;
        }
        Entity e = Bukkit.getEntity(id);
        if (e != null) {
            e.remove();
        }
    }

    /** Remove any tagged effect entity still in a loaded chunk (leftovers from a crash). */
    private void sweepStaleEntities() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getPersistentDataContainer().has(Keys.EFFECT_ENTITY, PersistentDataType.BYTE)) {
                    e.remove();
                }
            }
        }
    }

    private static String blockKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private PluginConfig.MiniEffects cfg() {
        return plugin.config().miniEffects();
    }

    // ---- chunk lifecycle ---------------------------------------------------------

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk c = event.getChunk();
        for (Placed p : placed.values()) {
            if (p.world.equals(c.getWorld()) && (p.bx >> 4) == c.getX() && (p.bz >> 4) == c.getZ()) {
                removeEntity(p.hologramId);
                removeEntity(p.displayId);
                p.hologramId = null;
                p.displayId = null;
            }
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (e.getPersistentDataContainer().has(Keys.EFFECT_ENTITY, PersistentDataType.BYTE)) {
                e.remove(); // a stale effect entity that somehow got saved
            } else if (e instanceof ArmorStand stand && plugin.stands() != null && plugin.stands().isMiniStand(stand)) {
                registerStand(stand, false);
            }
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity e : event.getEntities()) {
            if (e instanceof ArmorStand) {
                Placed p = placed.remove("stand:" + e.getUniqueId());
                if (p != null) {
                    removeEntity(p.hologramId);
                    removeEntity(p.displayId); // the light block stays with the chunk and is reused on reload
                }
            }
        }
    }
}
