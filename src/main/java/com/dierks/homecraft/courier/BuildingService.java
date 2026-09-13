package com.dierks.homecraft.courier;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.storage.CourierSiteDao;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Puts a building at the far end of a delivery, and takes it away again.
 *
 * <p>The whole module is written around one rule: <b>the field goes back exactly as it was.</b>
 * Everything else follows from it. The undo record is written before the first block changes,
 * it is deleted only after the restore actually succeeds, and a restore that cannot run right
 * now (because the player gave up and went home, so the chunk is unloaded) is parked rather
 * than dropped — picked up by the next {@code ChunkLoadEvent} for the region, or failing that
 * by the sweep on the next plugin enable. A house left standing is the failure this is built to
 * make impossible.
 *
 * <p>Placement is asynchronous up to the point of touching the world. A delivery waypoint can
 * be four thousand blocks out in terrain that has never been generated, and pulling those
 * chunks in on the main thread would freeze the server for the length of a world-gen pass —
 * so chunks are requested through {@link World#getChunkAtAsync} and every block read and write
 * happens afterwards, back on the main thread, in one go.
 */
public final class BuildingService {

    /** Structure keys that resolved at startup, grouped by the family they belong to. */
    private final Map<BuildingTemplate.BiomeGroup, List<BuildingTemplate>> usable =
            new LinkedHashMap<>();

    /** Sites with blocks currently in the world, by job id. The world's source of truth. */
    private final Map<Long, DeliverySite> standing = new HashMap<>();

    /** Chunk key → job ids waiting for that chunk to load so their restore can run. */
    private final Map<Long, Set<Long>> pending = new HashMap<>();

    private final HomeCraftManagement plugin;
    private final CourierSiteDao dao;
    private boolean templatesChecked;

    public BuildingService(HomeCraftManagement plugin, CourierSiteDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    // ---- lifecycle ------------------------------------------------------------

    /**
     * Load what is standing, then put back everything that owes a restore.
     *
     * <p>This runs before the Courier will accept a single new job, because the alternative is
     * a server that accumulates one abandoned house per crash forever.
     */
    public void start() {
        standing.clear();
        pending.clear();
        if (!config().enabled()) {
            return;
        }
        validateTemplates();
        try {
            for (DeliverySite site : dao.standing()) {
                standing.put(site.jobId(), site);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not read standing courier sites: " + e.getMessage());
            return;
        }
        sweep();
    }

    /**
     * Restore every site whose delivery is over.
     *
     * <p>Called on enable and whenever a job closes. Sites whose chunks are not loaded are
     * parked, not skipped — {@link #onChunkLoad} finishes them.
     */
    public void sweep() {
        List<DeliverySite> owing;
        try {
            owing = dao.owingRestore();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not list courier sites owing a restore: "
                    + e.getMessage());
            return;
        }
        if (owing.isEmpty()) {
            return;
        }
        int done = 0;
        for (DeliverySite site : owing) {
            if (restoreNow(site)) {
                done++;
            } else {
                park(site);
            }
        }
        plugin.getLogger().info("Courier sites: " + done + " restored, "
                + (owing.size() - done) + " waiting on their chunks.");
    }

    /**
     * Take everything down. Called on disable, so a reload never leaves a house behind.
     *
     * <p>A site whose chunk is unloaded at shutdown stays {@code RESTORE_PENDING} in the
     * database and is dealt with on the next enable — which is exactly what that state is for.
     */
    public void stop() {
        for (DeliverySite site : new ArrayList<>(standing.values())) {
            if (!restoreNow(site)) {
                park(site);
            }
        }
        standing.clear();
        pending.clear();
    }

    // ---- templates ------------------------------------------------------------

    /**
     * Resolve every configured structure key once and keep the ones that exist.
     *
     * <p>Mojang renames village pieces between versions, and a key that no longer resolves
     * would otherwise fail a delivery at the far end — after the player has walked eighteen
     * hundred blocks, which is the worst possible moment to find out. Checking at startup turns
     * that into one warning line an admin can act on.
     */
    public void validateTemplates() {
        usable.clear();
        templatesChecked = true;
        List<BuildingTemplate> configured = config().buildings();
        List<String> missing = new ArrayList<>();

        for (BuildingTemplate template : configured) {
            if (load(template) == null) {
                missing.add(template.key());
                continue;
            }
            for (BuildingTemplate.BiomeGroup group : BuildingTemplate.BiomeGroup.values()) {
                if (template.fits(group)) {
                    usable.computeIfAbsent(group, g -> new ArrayList<>()).add(template);
                }
            }
        }

        if (!missing.isEmpty()) {
            plugin.getLogger().warning("Courier buildings: " + missing.size()
                    + " template(s) did not resolve and were dropped — " + String.join(", ", missing)
                    + ". Check them with /place structure and correct courier.buildings in config.yml.");
        }
        int total = configured.size() - missing.size();
        if (total == 0) {
            plugin.getLogger().warning("Courier buildings: no template resolved. Deliveries will "
                    + "still work, but they arrive at an empty field and hand in at the PC.");
        } else {
            plugin.getLogger().info("Courier buildings: " + total + " template(s) ready across "
                    + usable.size() + " biome group(s).");
        }
    }

    /** Load a template's structure, or null if this server does not have it. */
    private Structure load(BuildingTemplate template) {
        try {
            if (template.source() == BuildingTemplate.Source.CUSTOM) {
                File file = new File(plugin.getDataFolder(), "buildings/" + template.key());
                return file.isFile() ? Bukkit.getStructureManager().loadStructure(file) : null;
            }
            NamespacedKey key = NamespacedKey.fromString(template.key().contains(":")
                    ? template.key() : "minecraft:" + template.key());
            return key == null ? null : Bukkit.getStructureManager().loadStructure(key, false);
        } catch (Exception e) {
            return null;
        }
    }

    /** A weighted pick from the templates that suit this biome, falling back to plains. */
    private BuildingTemplate pick(BuildingTemplate.BiomeGroup group) {
        List<BuildingTemplate> options = usable.get(group);
        if (options == null || options.isEmpty()) {
            options = usable.get(BuildingTemplate.BiomeGroup.PLAINS);
        }
        if (options == null || options.isEmpty()) {
            return null;
        }
        int total = 0;
        for (BuildingTemplate t : options) {
            total += Math.max(1, t.weight());
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (BuildingTemplate t : options) {
            roll -= Math.max(1, t.weight());
            if (roll < 0) {
                return t;
            }
        }
        return options.get(0);
    }

    // ---- placement ------------------------------------------------------------

    /** True if a building is already standing for this job. */
    public boolean hasSite(long jobId) {
        return standing.containsKey(jobId);
    }

    /** The site standing for this job, or null. */
    public DeliverySite siteFor(long jobId) {
        return standing.get(jobId);
    }

    /**
     * Build the delivery site for a job.
     *
     * <p>Returns a future rather than a boolean because the chunks at the far end may not
     * exist yet. The future completes on the main thread with whether a building actually went
     * up; {@code false} is a normal outcome, not an error — the delivery simply stays a
     * hand-in-at-the-PC job, which is why that fallback is never removed.
     */
    public CompletableFuture<Boolean> placeFor(CourierJob job) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        if (!config().enabled() || !templatesChecked || usable.isEmpty()) {
            done.complete(false);
            return done;
        }
        if (standing.containsKey(job.id())) {
            done.complete(true);
            return done;
        }
        Location way = job.waypoint();
        if (way == null || way.getWorld() == null) {
            done.complete(false);
            return done;
        }
        // §11 #1: a world the economy is switched off in gets no deliveries, so it gets no
        // delivery buildings either.
        if (!plugin.sandbox().allowed(way.getWorld())) {
            done.complete(false);
            return done;
        }

        World world = way.getWorld();
        world.getChunkAtAsync(way.getBlockX() >> 4, way.getBlockZ() >> 4, true)
                .thenAccept(chunk -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        done.complete(build(job, way));
                    } catch (Exception e) {
                        plugin.getLogger().warning("Courier site placement failed for job "
                                + job.id() + ": " + e);
                        done.complete(false);
                    }
                }))
                .exceptionally(t -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> done.complete(false));
                    return null;
                });
        return done;
    }

    /** The main-thread half of placement: everything that reads or writes blocks. */
    private boolean build(CourierJob job, Location way) throws SQLException {
        World world = way.getWorld();
        PluginConfig.CourierBuilding cfg = config();

        BuildingTemplate.BiomeGroup group =
                BuildingTemplate.BiomeGroup.of(way.getBlock().getBiome().toString());
        BuildingTemplate template = pick(group);
        if (template == null) {
            return false;
        }
        Structure structure = load(template);
        if (structure == null) {
            return false;
        }

        BlockVector size = structure.getSize();
        int sx = Math.max(1, size.getBlockX());
        int sy = Math.max(1, size.getBlockY());
        int sz = Math.max(1, size.getBlockZ());
        int span = Math.max(sx, sz);

        // Ground level under the waypoint, re-read now the chunk is really here.
        int baseY = world.getHighestBlockYAt(way.getBlockX(), way.getBlockZ()) + 1;

        if (!groundIsBuildable(world, way.getBlockX(), way.getBlockZ(), span + 2,
                baseY, cfg.maxSlope())) {
            return false;
        }

        // The snapshot box is deliberately larger than the house. Bukkit does not specify
        // which corner a rotation pivots around, so a box of twice the span centred on the
        // waypoint is the only size that is certainly big enough whichever way it turns out —
        // and a box of air gzips down to almost nothing, so the generosity is close to free.
        int pad = cfg.regionPadding();
        int side = span * 2 + pad * 2;
        int originX = way.getBlockX() - side / 2;
        int originZ = way.getBlockZ() - side / 2;
        int originY = Math.max(world.getMinHeight(), baseY - pad);
        int height = Math.min(world.getMaxHeight() - originY, sy + pad * 2);

        if (overlapsStandingSite(world.getName(), originX, originZ, originX + side, originZ + side)) {
            return false;
        }
        // Block data alone cannot carry a chest's contents, so a region holding one is a
        // region we must not touch — somebody's unclaimed storage is still somebody's.
        if (BuildingSnapshot.hasContainers(world, originX, originY, originZ, side, height, side)) {
            return false;
        }

        byte[] snapshot;
        try {
            snapshot = BuildingSnapshot.capture(world, originX, originY, originZ,
                    side, height, side);
        } catch (Exception e) {
            plugin.getLogger().warning("Courier site snapshot failed for job " + job.id()
                    + ": " + e);
            return false;
        }

        StructureRotation rotation = StructureRotation.values()[
                ThreadLocalRandom.current().nextInt(StructureRotation.values().length)];

        // The undo record goes in BEFORE the first block moves. If the server dies between
        // this line and the next, the sweep on the following enable puts the field back.
        DeliverySite site = new DeliverySite(job.id(), world.getName(),
                originX, originY, originZ, side, height, side,
                template.key(), rotation,
                way.getBlockX(), baseY, way.getBlockZ(),
                null, DeliverySite.State.PLACED, snapshot, System.currentTimeMillis());
        dao.insert(site);
        standing.put(job.id(), site);

        try {
            Location at = new Location(world, way.getBlockX() - sx / 2, baseY,
                    way.getBlockZ() - sz / 2);
            // includeEntities false: the template's own villagers are not ours and would not
            // be tagged, tracked, or cleaned up.
            structure.place(at, false, rotation, Mirror.NONE, -1, 1.0f, new Random());

            foundation(world, originX, originY, originZ, side, height, side, baseY,
                    cfg.foundationDepth());
            // Village templates ship chests carrying loot tables. A building that reappears at
            // the end of every run would turn that into a per-delivery item faucet, which §3.1
            // refuses, so the furniture stays and the contents do not.
            BuildingSnapshot.clearContainers(world, originX, originY, originZ, side, height, side);

            Location door = findDoor(world, originX, originY, originZ, side, height, side,
                    way.getBlockX(), way.getBlockZ());
            if (door == null) {
                door = new Location(world, way.getBlockX() + 0.5, baseY, way.getBlockZ() + 0.5);
            }

            Villager villager = spawnRecipient(job, door, group);
            site = new DeliverySite(job.id(), world.getName(),
                    originX, originY, originZ, side, height, side,
                    template.key(), rotation,
                    door.getBlockX(), door.getBlockY(), door.getBlockZ(),
                    villager == null ? null : villager.getUniqueId(),
                    DeliverySite.State.PLACED, snapshot, site.placedAt());
            dao.insert(site);
            standing.put(job.id(), site);
            return true;
        } catch (Exception e) {
            // Half-built is the one state we refuse to leave behind: put it straight back.
            plugin.getLogger().warning("Courier site build failed for job " + job.id()
                    + ", rolling back: " + e);
            restoreNow(site);
            return false;
        }
    }

    /**
     * True if the ground across the footprint is flat enough to build on.
     *
     * <p>Rejecting is cheaper than flattening. A delivery that lands on a cliff can simply be
     * rolled again; a plugin that terraforms somebody's hillside to make room cannot be undone
     * by putting blocks back.
     */
    private boolean groundIsBuildable(World world, int centreX, int centreZ, int side,
                                      int baseY, int maxSlope) {
        int half = side / 2;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int x = centreX - half; x <= centreX + half; x++) {
            for (int z = centreZ - half; z <= centreZ + half; z++) {
                int y = world.getHighestBlockYAt(x, z);
                Block ground = world.getBlockAt(x, y, z);
                if (ground.isLiquid()) {
                    return false;
                }
                min = Math.min(min, y);
                max = Math.max(max, y);
                if (max - min > maxSlope) {
                    return false;
                }
            }
        }
        return Math.abs(baseY - 1 - max) <= maxSlope;
    }

    /**
     * Fill the gap under the house so it never floats on a slope.
     *
     * <p>Only ever fills <i>downwards under blocks the structure placed</i>, and only within
     * the snapshotted region, so every block it adds is one the restore will take away again.
     */
    private void foundation(World world, int ox, int oy, int oz, int sizeX, int sizeY, int sizeZ,
                            int baseY, int depth) {
        for (int x = ox; x < ox + sizeX; x++) {
            for (int z = oz; z < oz + sizeZ; z++) {
                Block above = world.getBlockAt(x, baseY, z);
                if (above.getType().isAir()) {
                    continue; // nothing of the house here
                }
                Material fill = surfaceUnder(world, x, baseY, z);
                for (int d = 1; d <= depth; d++) {
                    int y = baseY - d;
                    if (y < oy || y < world.getMinHeight()) {
                        break;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir() || block.isLiquid()) {
                        block.setType(fill, false);
                    } else {
                        break; // hit real ground — nothing more to underpin
                    }
                }
            }
        }
    }

    /** What the ground is made of nearby, so the foundation matches the field. */
    private Material surfaceUnder(World world, int x, int baseY, int z) {
        for (int d = 1; d <= 4; d++) {
            Block block = world.getBlockAt(x, baseY - d, z);
            if (!block.getType().isAir() && !block.isLiquid() && block.getType().isSolid()) {
                return block.getType();
            }
        }
        return Material.DIRT;
    }

    /**
     * Find the front door of whatever was just placed, and the standing spot outside it.
     *
     * <p>Scanned rather than configured per template. A door offset table would need a row per
     * structure and would silently rot the first time Mojang moved one — and the commissioned
     * buildings coming later would each need their own. Scanning costs one pass over a small
     * region and works for every building the module will ever place.
     */
    private Location findDoor(World world, int ox, int oy, int oz, int sizeX, int sizeY, int sizeZ,
                              int centreX, int centreZ) {
        for (int y = oy; y < oy + sizeY; y++) {
            for (int x = ox; x < ox + sizeX; x++) {
                for (int z = oz; z < oz + sizeZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    BlockData data = block.getBlockData();
                    if (!(data instanceof Door door)) {
                        continue;
                    }
                    if (door.getHalf() != org.bukkit.block.data.Bisected.Half.BOTTOM) {
                        continue;
                    }
                    BlockFace facing = door.getFacing();
                    Location outward = standingSpot(world, block, facing, centreX, centreZ);
                    if (outward != null) {
                        return outward;
                    }
                }
            }
        }
        return null;
    }

    /**
     * The open air block beside a door, on the outside.
     *
     * <p>Both neighbours are tested and the one further from the building's centre wins, which
     * settles "which way is out" without depending on how a given template happened to orient
     * its door.
     */
    private Location standingSpot(World world, Block door, BlockFace facing,
                                  int centreX, int centreZ) {
        BlockFace[] candidates = {facing, facing.getOppositeFace()};
        Location best = null;
        double bestDistance = -1;
        for (BlockFace face : candidates) {
            Block spot = door.getRelative(face);
            Block head = spot.getRelative(BlockFace.UP);
            Block floor = spot.getRelative(BlockFace.DOWN);
            if (!spot.getType().isAir() || !head.getType().isAir()) {
                continue;
            }
            if (!floor.getType().isSolid()) {
                continue;
            }
            double dx = spot.getX() - centreX;
            double dz = spot.getZ() - centreZ;
            double distance = dx * dx + dz * dz;
            if (distance > bestDistance) {
                bestDistance = distance;
                best = new Location(world, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            }
        }
        return best;
    }

    /**
     * The person expecting the crate.
     *
     * <p>No AI, invulnerable, silent and persistent: this is a fixture of the delivery, not a
     * mob. Without {@code setAI(false)} it would wander off the spot the player was sent to;
     * without {@code setPersistent(true)} it would despawn while they were still walking.
     */
    private Villager spawnRecipient(CourierJob job, Location at,
                                    BuildingTemplate.BiomeGroup group) {
        try {
            World world = at.getWorld();
            if (world == null) {
                return null;
            }
            Villager villager = world.spawn(at, Villager.class, v -> {
                v.setAI(false);
                v.setInvulnerable(true);
                v.setSilent(true);
                v.setPersistent(true);
                v.setRemoveWhenFarAway(false);
                v.setCollidable(false);
                try {
                    v.setVillagerType(group.villagerType());
                    v.setProfession(group.profession());
                    v.setVillagerLevel(2);
                } catch (RuntimeException ignored) {
                    // a profession this server does not know is cosmetic — carry on
                }
                v.customName(Text.of("&e" + config().recipientName()));
                v.setCustomNameVisible(true);
                v.getPersistentDataContainer().set(Keys.COURIER_JOB, PersistentDataType.LONG,
                        job.id());
                v.getPersistentDataContainer().set(Keys.MOB_ARTIFICIAL, PersistentDataType.BYTE,
                        (byte) 1);
            });
            dao.setVillager(job.id(), villager.getUniqueId());
            return villager;
        } catch (Exception e) {
            plugin.getLogger().warning("Could not spawn the courier recipient for job "
                    + job.id() + ": " + e);
            return null;
        }
    }

    // ---- teardown -------------------------------------------------------------

    /**
     * The delivery is over — put the field back, or park the restore until the chunk returns.
     *
     * <p>Never throws and never leaves the row deleted without the blocks replaced. The record
     * surviving a failed attempt is the entire reason a crashed server can still tidy up.
     */
    public void release(long jobId) {
        DeliverySite site = standing.get(jobId);
        if (site == null) {
            return;
        }
        if (!restoreNow(site)) {
            park(site);
        }
    }

    /**
     * Restore immediately if the region is loaded.
     *
     * @return true if the blocks are actually back and the row is gone
     */
    private boolean restoreNow(DeliverySite site) {
        World world = site.bukkitWorld();
        if (world == null) {
            // The world is gone entirely. There are no blocks left to put back, so the record
            // has done its job and holding it forever would only re-run this every enable.
            forget(site.jobId());
            return true;
        }
        for (long chunk : site.chunks()) {
            int cx = (int) (chunk >> 32);
            int cz = (int) chunk;
            if (!world.isChunkLoaded(cx, cz)) {
                return false;
            }
        }
        removeRecipient(site);
        if (!BuildingSnapshot.restore(world, site.originX(), site.originY(), site.originZ(),
                site.snapshot())) {
            return false;
        }
        forget(site.jobId());
        return true;
    }

    /** Note that this site still owes a restore, and wake on the chunk that would allow it. */
    private void park(DeliverySite site) {
        DeliverySite parked = site.withState(DeliverySite.State.RESTORE_PENDING);
        standing.put(parked.jobId(), parked);
        try {
            dao.setState(parked.jobId(), DeliverySite.State.RESTORE_PENDING);
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not park a courier site restore: " + e.getMessage());
        }
        for (long chunk : parked.chunks()) {
            pending.computeIfAbsent(chunk, k -> new HashSet<>()).add(parked.jobId());
        }
    }

    /** Drop the record — only ever called once the blocks are genuinely back. */
    private void forget(long jobId) {
        DeliverySite site = standing.remove(jobId);
        if (site != null) {
            for (long chunk : site.chunks()) {
                Set<Long> waiting = pending.get(chunk);
                if (waiting != null && waiting.remove(jobId) && waiting.isEmpty()) {
                    pending.remove(chunk);
                }
            }
        }
        try {
            dao.delete(jobId);
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not delete a restored courier site: "
                    + e.getMessage());
        }
    }

    /** A parked restore gets its chance the moment its chunk comes back. */
    public void onChunkLoad(Chunk chunk) {
        if (pending.isEmpty()) {
            return;
        }
        Set<Long> waiting = pending.get(DeliverySite.chunkKey(chunk.getX(), chunk.getZ()));
        if (waiting == null || waiting.isEmpty()) {
            return;
        }
        for (long jobId : new ArrayList<>(waiting)) {
            DeliverySite site = standing.get(jobId);
            if (site != null) {
                restoreNow(site);
            }
        }
    }

    private void removeRecipient(DeliverySite site) {
        if (site.villager() != null) {
            Entity entity = Bukkit.getEntity(site.villager());
            if (entity != null) {
                entity.remove();
                return;
            }
        }
        // No stored id, or the entity moved worlds: sweep the region for anything of ours
        // carrying this job's tag, so a failed spawn record can never strand a villager.
        World world = site.bukkitWorld();
        if (world == null) {
            return;
        }
        Location centre = new Location(world,
                site.originX() + site.sizeX() / 2.0,
                site.originY() + site.sizeY() / 2.0,
                site.originZ() + site.sizeZ() / 2.0);
        double radius = Math.max(site.sizeX(), site.sizeZ());
        for (Entity entity : world.getNearbyEntities(centre, radius, site.sizeY(), radius)) {
            if (entity.getType() != EntityType.VILLAGER) {
                continue;
            }
            Long tagged = entity.getPersistentDataContainer()
                    .get(Keys.COURIER_JOB, PersistentDataType.LONG);
            if (tagged != null && tagged == site.jobId()) {
                entity.remove();
            }
        }
    }

    // ---- queries used by the listener -----------------------------------------

    /**
     * The standing site a block belongs to, or null.
     *
     * <p>Used to refuse edits inside a delivery building. Two reasons, and both matter: a
     * player mining the house would walk away with free blocks the restore then deletes, and
     * every block they changed is one the snapshot no longer describes.
     */
    public DeliverySite siteContaining(String world, int x, int y, int z) {
        for (DeliverySite site : standing.values()) {
            if (site.world().equals(world) && site.contains(x, y, z)) {
                return site;
            }
        }
        return null;
    }

    private boolean overlapsStandingSite(String world, int minX, int minZ, int maxX, int maxZ) {
        for (DeliverySite site : standing.values()) {
            if (site.overlapsColumn(world, minX, minZ, maxX, maxZ)) {
                return true;
            }
        }
        return false;
    }

    private PluginConfig.CourierBuilding config() {
        return plugin.config().courier().building();
    }
}
