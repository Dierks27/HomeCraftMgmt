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
import org.bukkit.Sound;
import org.bukkit.entity.Player;
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

    /**
     * How far around the waypoint's chunk to load before touching the world.
     *
     * <p>Two is enough for the widest region the module builds: a box of roughly twice the
     * largest template's span plus padding, centred anywhere inside a chunk, reaches at most two
     * chunks out.
     */
    private static final int CHUNK_RADIUS = 2;

    /** One ambient noise every this many presence ticks, so it is occasional, not constant. */
    private static final int AMBIENT_EVERY = 40;

    private int ambient;

    /** Structure keys that resolved at startup, grouped by the family they belong to. */
    private final Map<BuildingTemplate.BiomeGroup, List<BuildingTemplate>> usable =
            new LinkedHashMap<>();

    /** Sites with blocks currently in the world, by job id. The world's source of truth. */
    private final Map<Long, DeliverySite> standing = new HashMap<>();

    /** Chunk key → job ids waiting for that chunk to load so their restore can run. */
    private final Map<Long, Set<Long>> pending = new HashMap<>();

    /**
     * Jobs with a placement in flight.
     *
     * <p>{@code standing} cannot do this job: it is only populated after the chunk batch
     * resolves, which for ungenerated terrain is the normal case and takes far longer than the
     * one second between approach checks. Without a reservation a second placement starts while
     * the first is still loading, and both reach {@code build()} — the second then snapshots a
     * field that <b>already has a house on it</b> and {@code INSERT OR REPLACE} overwrites the
     * real undo record. The restore would then faithfully put the first house back, for good.
     * That is the one outcome in this class that permanently damages a player's world.
     */
    private final Set<Long> placing = new HashSet<>();

    /** Jobs whose site was refused, so a hopeless placement is attempted once and not 3,600 times. */
    private final Set<Long> refused = new HashSet<>();

    /**
     * Job id → when its delivery ended, for sites waiting to be taken away.
     *
     * <p>A site is not removed on a clock. It is removed when the player has <b>left</b> — see
     * {@link #readyToRestore}. A timer can always fire while somebody is standing in the
     * doorway, which is exactly what it did: the house evaporated the instant the payout
     * landed, mid-conversation with the villager.
     */
    private final Map<Long, Long> finishedAt = new HashMap<>();

    private final HomeCraftManagement plugin;
    private final CourierSiteDao dao;
    private final com.dierks.homecraft.storage.TrackedGroundDao ground;
    private boolean templatesChecked;

    public BuildingService(HomeCraftManagement plugin, CourierSiteDao dao,
                           com.dierks.homecraft.storage.TrackedGroundDao ground) {
        this.plugin = plugin;
        this.dao = dao;
        this.ground = ground;
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
        placing.clear();
        refused.clear();
        finishedAt.clear();
        if (!config().enabled()) {
            // Clear the template table too. Leaving it loaded meant turning buildings off with
            // /hcm reload left a ready-to-place set behind that nothing would ever consult
            // again but which reported itself as ready.
            usable.clear();
            templatesChecked = false;
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
        long now = System.currentTimeMillis();
        for (DeliverySite site : owing) {
            // A just-delivered job is already non-ACTIVE, so without this the 60-second sweep
            // beat the linger timer and pulled the house out from under a player still reading
            // their payout message.
            if (!readyToRestore(site, now)) {
                continue;
            }
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
        if (refused.contains(job.id())) {
            done.complete(false);
            return done;
        }
        if (!placing.add(job.id())) {
            done.complete(false); // already loading chunks for this job
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
        // Every chunk the region can touch, not just the waypoint's. The snapshot reads a box
        // wider than one chunk, and reading a block in an unloaded chunk loads it — on the main
        // thread, one at a time, which is the very thing the async placement exists to avoid.
        int radius = CHUNK_RADIUS;
        int cx = way.getBlockX() >> 4;
        int cz = way.getBlockZ() >> 4;
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                loads.add(world.getChunkAtAsync(cx + dx, cz + dz, true));
            }
        }
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0]))
                .whenComplete((ignored, error) -> plugin.getServer().getScheduler()
                        .runTask(plugin, () -> {
                            try {
                                if (error != null) {
                                    refuse(job, way, "the chunks there could not be loaded ("
                                            + error + ")");
                                    done.complete(false);
                                    return;
                                }
                                boolean built = build(job, way);
                                if (!built) {
                                    refused.add(job.id());
                                }
                                done.complete(built);
                            } catch (Exception e) {
                                refuse(job, way, "placement threw " + e);
                                done.complete(false);
                            } finally {
                                placing.remove(job.id());
                            }
                        }));
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

        // Real ground under the waypoint, found by looking through the canopy rather than
        // measuring it — see Ground.
        Ground.Column centre = Ground.solid(world, way.getBlockX(), way.getBlockZ(),
                cfg.groundScanDepth());
        if (!centre.isGround()) {
            refuse(job, way, centre.kind() == Ground.Kind.LIQUID
                    ? "the drop-off is on water" : "no solid ground under the drop-off");
            return false;
        }
        int baseY = centre.y() + 1;

        String terrain = groundIsBuildable(world, way.getBlockX(), way.getBlockZ(), span + 2,
                centre.y(), cfg);
        if (terrain != null) {
            refuse(job, way, terrain);
            return false;
        }

        // Where the structure will actually be put. Everything below is sized around THIS
        // point rather than the waypoint, and that distinction is load-bearing: Bukkit does not
        // say which corner a rotation pivots around, so the structure can occupy any of the
        // four quadrants around its placement origin, reaching at most `span` in each
        // direction. A box of `span + pad` around the placement origin therefore contains it
        // whichever way it turns. The previous version centred the box on the WAYPOINT, which
        // is offset from the origin by half the structure — so a template of 11 or wider
        // overflowed the captured region, and any block outside the snapshot is never restored.
        Location at = new Location(world, way.getBlockX() - sx / 2, baseY,
                way.getBlockZ() - sz / 2);

        int pad = cfg.regionPadding();

        // The structure's own reach: a rotation pivots about `at` and can send it into any of
        // the four quadrants around that point, up to `span` in each direction.
        int structureSide = DeliverySite.captureSide(sx, sz, pad);
        int reachMinX = at.getBlockX() - span;
        int reachMaxX = at.getBlockX() + span;
        int reachMinZ = at.getBlockZ() - span;
        int reachMaxZ = at.getBlockZ() + span;

        // Whole trees rooted in the building's footprint, found BEFORE anything is captured so
        // the box can be grown to bound them. Cutting a trunk at a fixed height was what left
        // canopy hanging in the sky — and worse, orphaned leaves outside the region decay,
        // which is a change no restore undoes.
        List<int[]> growth = growthToClear(world, at, span, baseY, cfg);
        int growMinX = reachMinX;
        int growMaxX = reachMaxX;
        int growMinZ = reachMinZ;
        int growMaxZ = reachMaxZ;
        int growMinY = baseY;
        int growMaxY = baseY + sy;
        for (int[] block : growth) {
            growMinX = Math.min(growMinX, block[0]);
            growMaxX = Math.max(growMaxX, block[0]);
            growMinY = Math.min(growMinY, block[1]);
            growMaxY = Math.max(growMaxY, block[1]);
            growMinZ = Math.min(growMinZ, block[2]);
            growMaxZ = Math.max(growMaxZ, block[2]);
        }

        int[] axisX = DeliverySite.unionAxis(reachMinX, reachMaxX, growMinX, growMaxX,
                pad, cfg.maxRegionSide());
        int[] axisZ = DeliverySite.unionAxis(reachMinZ, reachMaxZ, growMinZ, growMaxZ,
                pad, cfg.maxRegionSide());
        int originX = axisX[0];
        int sizeX = Math.max(structureSide, axisX[1]);
        int originZ = axisZ[0];
        int sizeZ = Math.max(structureSide, axisZ[1]);

        int originY = Math.max(world.getMinHeight(), Math.min(baseY, growMinY) - pad);
        int topY = Math.min(world.getMaxHeight() - 1, Math.max(baseY + sy, growMaxY) + pad);
        int height = Math.max(1, topY - originY + 1);

        // Anything the fill reached that the clamp then excluded is left standing. A few leaves
        // hanging for the length of a delivery is cosmetic; a block changed outside the
        // snapshot is permanent.
        int wanted = growth.size();
        growth.removeIf(b -> b[0] < originX || b[0] >= originX + sizeX
                || b[1] < originY || b[1] >= originY + height
                || b[2] < originZ || b[2] >= originZ + sizeZ);
        if (growth.size() < wanted) {
            plugin.getLogger().fine(() -> "Courier job " + job.id() + ": "
                    + (wanted - growth.size()) + " of " + wanted + " tree blocks fall outside "
                    + "the captured region and were left standing.");
        }

        if (overlapsStandingSite(world.getName(), originX, originZ,
                originX + sizeX, originZ + sizeZ)) {
            refuse(job, way, "another delivery is already standing there");
            return false;
        }
        String blocked = regionBlocked(world, originX, originY, originZ, sizeX, height, sizeZ);
        if (blocked != null) {
            refuse(job, way, blocked + " is in the way");
            return false;
        }

        byte[] snapshot;
        try {
            snapshot = BuildingSnapshot.capture(world, originX, originY, originZ,
                    sizeX, height, sizeZ);
        } catch (Exception e) {
            refuse(job, way, "the region could not be captured (" + e + ")");
            return false;
        }

        StructureRotation rotation = StructureRotation.values()[
                ThreadLocalRandom.current().nextInt(StructureRotation.values().length)];

        // The undo record goes in BEFORE the first block moves. If the server dies between
        // this line and the next, the sweep on the following enable puts the field back.
        DeliverySite site = new DeliverySite(job.id(), world.getName(),
                originX, originY, originZ, sizeX, height, sizeZ,
                template.key(), rotation,
                way.getBlockX(), baseY, way.getBlockZ(),
                null, DeliverySite.State.PLACED, snapshot, System.currentTimeMillis());
        dao.insert(site);
        standing.put(job.id(), site);

        try {
            // Snapshot first, then clear, then place. The trees have to be inside the captured
            // region or the restore cannot put them back — which is why this runs after the
            // capture above and is bounded by that same region.
            for (int[] block : growth) {
                world.getBlockAt(block[0], block[1], block[2]).setType(Material.AIR, false);
            }

            // includeEntities false: the template's own villagers are not ours and would not
            // be tagged, tracked, or cleaned up.
            structure.place(at, false, rotation, Mirror.NONE, -1, 1.0f, new Random());

            // Village templates are worldgen pieces: they carry JIGSAW blocks that the assembly
            // process normally consumes and replaces. Structure.place does no such processing,
            // so every one survives into the world — one was standing in a wall beside a front
            // door on the first real delivery.
            stripWorldgenMarkers(world, originX, originY, originZ, sizeX, height, sizeZ);

            foundation(world, originX, originY, originZ, sizeX, height, sizeZ, baseY,
                    cfg.foundationDepth());
            // Village templates ship chests carrying loot tables. A building that reappears at
            // the end of every run would turn that into a per-delivery item faucet, which §3.1
            // refuses, so the furniture stays and the contents do not.
            BuildingSnapshot.clearContainers(world, originX, originY, originZ,
                    sizeX, height, sizeZ);

            // The WAYPOINT, not `at`. `at` is the structure's minimum corner, so measuring
            // "which side of the door is outward" from it inverted the test for about half of
            // all door orientations and spawned the recipient indoors.
            Location door = findDoor(world, originX, originY, originZ, sizeX, height, sizeZ,
                    way.getBlockX(), way.getBlockZ());
            if (door == null) {
                // Outside the structure, not at the waypoint: the waypoint is the middle of
                // the footprint, so falling back to it spawned the recipient inside the house
                // they are supposed to be standing in front of.
                door = outsideSpot(world, at, sx, sz, baseY, way);
            }

            Villager villager = spawnRecipient(job, door, way, group);
            site = new DeliverySite(job.id(), world.getName(),
                    originX, originY, originZ, sizeX, height, sizeZ,
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
     * Everything in this region that a block snapshot could not put back, described.
     *
     * <p>Three kinds of thing, one rule: <b>a snapshot restores block data and nothing else.</b>
     * Anything whose value lives somewhere other than block data has to be refused rather than
     * built over, because the restore that makes the rest of this module safe simply does not
     * reach it.
     *
     * <ul>
     *   <li><b>Blocks with contents</b> — a chest is not recoverable from its block data.</li>
     *   <li><b>Entities that were placed</b> — item frames, armour stands, chest minecarts,
     *       boats, displays. These are not in the snapshot at all, so anything that destroys
     *       one during the job destroys it for good. Living mobs are ignored: they wander, and
     *       refusing a field because a cow walked through it would refuse most fields.</li>
     *   <li><b>Anything HomeCraft has recorded here</b> — a placed PC, Workbench, Pallet, or
     *       worst of all a placed <b>Mini</b>, which is a numbered and capped collectible that
     *       cannot be re-minted if the window goes wrong.</li>
     * </ul>
     */
    private String regionBlocked(World world, int ox, int oy, int oz,
                                 int sizeX, int sizeY, int sizeZ) {
        String feature = BuildingSnapshot.blockingFeature(world, ox, oy, oz, sizeX, sizeY, sizeZ,
                config().avoidBlocks());
        if (feature != null) {
            return feature;
        }
        String entity = placedEntityIn(world, ox, oy, oz, sizeX, sizeY, sizeZ);
        if (entity != null) {
            return entity;
        }
        return trackedGroundIn(world.getName(), ox, oy, oz, sizeX, sizeY, sizeZ);
    }

    /** A placed (non-wandering) entity in the region, described, or null. */
    private String placedEntityIn(World world, int ox, int oy, int oz,
                                  int sizeX, int sizeY, int sizeZ) {
        try {
            org.bukkit.util.BoundingBox box = org.bukkit.util.BoundingBox.of(
                    new Location(world, ox, oy, oz),
                    new Location(world, ox + sizeX, oy + sizeY, oz + sizeZ));
            for (Entity entity : world.getNearbyEntities(box)) {
                if (isPlacement(entity)) {
                    return entity.getType().name().toLowerCase(java.util.Locale.ROOT)
                            .replace('_', ' ');
                }
            }
        } catch (RuntimeException e) {
            // A world that will not answer is not a reason to build on top of something.
            return "an area that could not be checked";
        }
        return null;
    }

    /**
     * True for entities somebody put there, false for anything that walked there.
     *
     * <p>An armour stand is a {@code LivingEntity} in Bukkit despite being furniture, which is
     * why it is named before the mob exemption rather than after it. A horse is both a
     * {@code Vehicle} and an {@code InventoryHolder}, and is deliberately <i>not</i> caught:
     * it will have wandered off long before the delivery ends.
     */
    private boolean isPlacement(Entity entity) {
        if (entity instanceof org.bukkit.entity.ArmorStand
                || entity instanceof org.bukkit.entity.Hanging
                || entity instanceof org.bukkit.entity.Display
                || entity instanceof org.bukkit.entity.EnderCrystal) {
            return true;
        }
        if (entity instanceof org.bukkit.entity.LivingEntity) {
            return false; // mobs wander; they are not somebody's placement
        }
        return entity instanceof org.bukkit.entity.Vehicle
                || entity instanceof org.bukkit.inventory.InventoryHolder;
    }

    /** Anything the plugin has recorded at these coordinates, described, or null. */
    private String trackedGroundIn(String world, int ox, int oy, int oz,
                                   int sizeX, int sizeY, int sizeZ) {
        try {
            return ground.firstIn(world, ox, oy, oz,
                    ox + sizeX - 1, oy + sizeY - 1, oz + sizeZ - 1);
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not check tracked ground for a courier site: "
                    + e.getMessage());
            // Cannot tell means do not build. The thing this protects is a minted Mini.
            return "ground that could not be checked";
        }
    }

    /**
     * The cheap half of {@link #regionBlocked}, for choosing a waypoint.
     *
     * <p>Runs before a job exists, so a bad spot is <b>rerolled</b> rather than becoming a
     * delivery that quietly arrives at an empty field. Only the two checks that are cheap
     * enough to run per candidate — the database query and the entity sweep — and deliberately
     * not the block scan, which is thousands of reads and is done once, properly, at placement.
     */
    public String groundUnsuitable(Location at, int radius) {
        World world = at.getWorld();
        if (world == null) {
            return "no world";
        }
        PluginConfig.CourierBuilding cfg = config();

        // Terrain first, and it is checked HERE rather than only at placement because terrain
        // does not change during a delivery. A canopy will not grow in an hour, so a field that
        // cannot be built on should cost a reroll now, not a walk to an empty field later.
        Ground.Column centre = Ground.solid(world, at.getBlockX(), at.getBlockZ(),
                cfg.groundScanDepth());
        if (!centre.isGround()) {
            return centre.kind() == Ground.Kind.LIQUID ? "water" : "no solid ground";
        }
        String terrain = groundIsBuildable(world, at.getBlockX(), at.getBlockZ(),
                cfg.terrainCheckRadius() * 2 + 1, centre.y(), cfg);
        if (terrain != null) {
            return terrain;
        }

        // The box for the remaining checks hangs off the real ground, not off the location's
        // own Y. That Y used to be canopy height, which put this whole scan up in the air —
        // so the tracked-Mini and placed-entity checks, the two things this method exists for,
        // were searching empty sky in exactly the forests where they mattered.
        int side = radius * 2 + 1;
        int oy = Math.max(world.getMinHeight(), centre.y() - radius);
        int height = Math.min(world.getMaxHeight() - oy, side);
        String entity = placedEntityIn(world, at.getBlockX() - radius, oy, at.getBlockZ() - radius,
                side, height, side);
        if (entity != null) {
            return entity;
        }
        return trackedGroundIn(world.getName(), at.getBlockX() - radius, oy, at.getBlockZ() - radius,
                side, height, side);
    }

    /**
     * Why this footprint cannot be built on, or null if it can.
     *
     * <p>Returns a reason rather than a boolean so the refusal can say what it was — a silent
     * {@code return false} here is what made a delivery arrive at an empty field with nothing
     * in the log to explain it.
     *
     * <p>Rejecting is still cheaper than flattening: a delivery that lands on a cliff can be
     * rolled again, but a plugin that terraforms somebody's hillside cannot undo it by putting
     * blocks back. Trees are the exception and always were — they are cleared, not refused,
     * because vanilla puts villages in forests constantly and a wood is not a cliff.
     */
    private String groundIsBuildable(World world, int centreX, int centreZ, int side,
                                     int centreGroundY, PluginConfig.CourierBuilding cfg) {
        int half = side / 2;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int liquid = 0;
        int columns = 0;
        for (int x = centreX - half; x <= centreX + half; x++) {
            for (int z = centreZ - half; z <= centreZ + half; z++) {
                columns++;
                Ground.Column column = Ground.solid(world, x, z, cfg.groundScanDepth());
                if (column.kind() == Ground.Kind.LIQUID) {
                    liquid++;
                    continue;
                }
                if (!column.isGround()) {
                    return "no solid ground at x " + x + ", z " + z;
                }
                min = Math.min(min, column.y());
                max = Math.max(max, column.y());
            }
        }
        // A shoreline is a good place for a delivery; a lake is not. Counting rather than
        // refusing on the first wet column is the difference between the two.
        int allowed = Math.max(0, columns * cfg.maxLiquidPercent() / 100);
        if (liquid > allowed) {
            return liquid + " of " + columns + " columns are water (at most " + allowed
                    + " allowed)";
        }
        if (min > max) {
            return "the whole footprint is water";
        }
        if (max - min > cfg.maxSlope()) {
            return "ground varies by " + (max - min) + " blocks across the footprint (max "
                    + cfg.maxSlope() + ")";
        }
        if (Math.abs(centreGroundY - max) > cfg.maxSlope()) {
            return "the drop-off sits " + Math.abs(centreGroundY - max)
                    + " blocks off the surrounding ground";
        }
        return null;
    }

    /**
     * Every block of every tree rooted where the house is going.
     *
     * <p>A flood fill rather than a cut at a fixed height, because slicing a trunk leaves its
     * canopy hanging in the sky — which looked wrong walking up to the first real delivery, and
     * is worse than it looks: orphaned leaves <b>decay</b>, and decay happens whether or not the
     * block was inside the captured region. A restore cannot put back what Minecraft deleted on
     * its own, so half a tree is a slow permanent change to the map.
     *
     * <p>Seeded from the whole area the structure could occupy under any rotation, and spread
     * through connected logs and leaves. Diagonals count: vanilla canopies attach cornerwise to
     * their trunk, and a six-way fill leaves the corners behind.
     *
     * <p>Capped. A dark forest is one connected canopy for a very long way, and the cap is what
     * stops one delivery asking to snapshot a hundred-block region. Hitting it is not a failure —
     * the caller keeps whatever fits inside the box and leaves the rest standing.
     */
    private List<int[]> growthToClear(World world, Location at, int span, int baseY,
                                      PluginConfig.CourierBuilding cfg) {
        List<int[]> found = new ArrayList<>();
        if (!cfg.clearTrees()) {
            return found;
        }
        int max = cfg.maxClearBlocks();
        Set<Long> seen = new HashSet<>();
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();

        int top = Math.min(world.getMaxHeight() - 1, baseY + cfg.maxRegionSide());
        for (int x = at.getBlockX() - span; x <= at.getBlockX() + span; x++) {
            for (int z = at.getBlockZ() - span; z <= at.getBlockZ() + span; z++) {
                for (int y = baseY; y <= top; y++) {
                    if (isGrowth(world, x, y, z) && seen.add(pack(x, y, z))) {
                        queue.add(new int[] {x, y, z});
                    }
                }
            }
        }

        while (!queue.isEmpty() && found.size() < max) {
            int[] block = queue.poll();
            found.add(block);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        int x = block[0] + dx;
                        int y = block[1] + dy;
                        int z = block[2] + dz;
                        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                            continue;
                        }
                        if (isGrowth(world, x, y, z) && seen.add(pack(x, y, z))) {
                            queue.add(new int[] {x, y, z});
                        }
                    }
                }
            }
        }
        if (found.size() >= max) {
            plugin.getLogger().fine(() -> "Courier: tree clearing hit the "
                    + max + "-block cap; the rest is left standing.");
        }
        return found;
    }

    /** True for a tree part — a log or a leaf — as opposed to ground cover or terrain. */
    private boolean isGrowth(World world, int x, int y, int z) {
        Material type = world.getBlockAt(x, y, z).getType();
        if (type.isAir()) {
            return false;
        }
        return Ground.isClutter(type);
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    /**
     * Remove the worldgen scaffolding a raw template placement leaves behind.
     *
     * <p>Village pieces are assembled by the jigsaw generator, which consumes each
     * {@code JIGSAW} block and replaces it with the final state recorded inside it.
     * {@code Structure.place} runs none of that, so the markers survive as real, visible,
     * op-interactable blocks — one was standing beside a front door on the first delivery.
     *
     * <p>They become air. <b>Bukkit exposes no way to read the recorded final state</b> —
     * {@code org.bukkit.block.Jigsaw} is an empty marker interface with no accessor — so the
     * "proper" substitution is not reachable through the API. For the village connectors these
     * templates carry, air is what the generator would have left anyway.
     */
    private void stripWorldgenMarkers(World world, int ox, int oy, int oz,
                                      int sizeX, int sizeY, int sizeZ) {
        for (int y = oy; y < oy + sizeY; y++) {
            for (int x = ox; x < ox + sizeX; x++) {
                for (int z = oz; z < oz + sizeZ; z++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (type == Material.JIGSAW || type == Material.STRUCTURE_BLOCK
                            || type == Material.STRUCTURE_VOID) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    /**
     * Say why a delivery has no building, once, where somebody will see it.
     *
     * <p>At INFO while {@code building.debug} is on, because the alternative — which is what
     * shipped — is a refusal that leaves no trace anywhere at any log level, and a player who
     * walked 460 blocks to an empty field with nothing to explain it.
     */
    private void refuse(CourierJob job, Location way, String reason) {
        refused.add(job.id());
        String line = "Courier job " + job.id() + ": no building at x " + way.getBlockX()
                + ", z " + way.getBlockZ() + " in " + job.world() + " — " + reason
                + ". The run hands in at the PC.";
        if (config().debug()) {
            plugin.getLogger().info(line);
        } else {
            plugin.getLogger().fine(() -> line);
        }
    }

    /** True if this job has been refused a building, so nothing retries it. */
    public boolean wasRefused(long jobId) {
        return refused.contains(jobId);
    }

    /** Note that a delivery has ended, starting the clock the restore policy reads. */
    public void finished(long jobId) {
        finishedAt.putIfAbsent(jobId, System.currentTimeMillis());
    }

    /**
     * Whether this site can be taken away yet.
     *
     * <p>Three rules, in order of how much they matter:
     *
     * <ol>
     *   <li><b>Never while somebody is inside it.</b> Restoring spruce logs into the space a
     *       player is standing in suffocates them, and that is a far worse bug than a house
     *       that outstays its welcome. This one defers even past the backstop.</li>
     *   <li><b>Not before {@code linger_seconds}</b>, so it does not vanish in the same breath
     *       as the payout.</li>
     *   <li>Then: once nobody is within {@code restore_distance} — the house is out of sight,
     *       so it simply is not there when they next look — or once
     *       {@code max_linger_seconds} has passed, which covers somebody logging off on the
     *       doorstep.</li>
     * </ol>
     */
    private boolean readyToRestore(DeliverySite site, long now) {
        PluginConfig.CourierBuilding cfg = config();
        World world = site.bukkitWorld();
        if (world == null) {
            return true; // no world, no blocks, nothing to be standing in
        }
        if (playerInside(world, site)) {
            return false;
        }
        long since = now - finishedAt.getOrDefault(site.jobId(), 0L);
        if (since < 1000L * cfg.lingerSeconds()) {
            return false;
        }
        if (since >= 1000L * cfg.maxLingerSeconds()) {
            return true;
        }
        return !playerWithin(world, site, cfg.restoreDistance());
    }

    /** True if any player is standing in the site's footprint, with a block of margin. */
    private boolean playerInside(World world, DeliverySite site) {
        for (Player player : world.getPlayers()) {
            Location at = player.getLocation();
            if (at.getBlockX() >= site.originX() - 1
                    && at.getBlockX() <= site.originX() + site.sizeX()
                    && at.getBlockZ() >= site.originZ() - 1
                    && at.getBlockZ() <= site.originZ() + site.sizeZ()
                    && at.getBlockY() >= site.originY() - 2
                    && at.getBlockY() <= site.originY() + site.sizeY() + 2) {
                return true;
            }
        }
        return false;
    }

    private boolean playerWithin(World world, DeliverySite site, int blocks) {
        double cx = site.originX() + site.sizeX() / 2.0;
        double cz = site.originZ() + site.sizeZ() / 2.0;
        double limit = (double) blocks * blocks;
        for (Player player : world.getPlayers()) {
            double dx = player.getLocation().getX() - cx;
            double dz = player.getLocation().getZ() - cz;
            if (dx * dx + dz * dz <= limit) {
                return true;
            }
        }
        return false;
    }

    /**
     * Take away every site whose delivery has ended and whose player has moved on.
     *
     * <p>Runs on the same one-second tick as the approach check, because "they have left" is an
     * event rather than a deadline — waiting for the sixty-second sweep would leave the house
     * standing long after anybody could see it.
     */
    public void tickRestores() {
        if (finishedAt.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Long jobId : new ArrayList<>(finishedAt.keySet())) {
            DeliverySite site = standing.get(jobId);
            if (site == null) {
                finishedAt.remove(jobId);
                continue;
            }
            if (readyToRestore(site, now) && !restoreNow(site)) {
                park(site);
            }
        }
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
     * Somewhere to stand clear of the building, for when no door could be found.
     *
     * <p>Walks outwards from the structure until it finds open ground. The old fallback was the
     * waypoint itself, which is the centre of the footprint — so a template whose door the scan
     * could not identify put the recipient inside its own walls.
     */
    private Location outsideSpot(World world, Location at, int sx, int sz, int baseY,
                                 Location way) {
        int span = Math.max(sx, sz);
        int[][] bearings = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int step = span / 2 + 1; step <= span + 3; step++) {
            for (int[] bearing : bearings) {
                int x = at.getBlockX() + sx / 2 + bearing[0] * step;
                int z = at.getBlockZ() + sz / 2 + bearing[1] * step;
                Ground.Column column = Ground.solid(world, x, z, config().groundScanDepth());
                if (!column.isGround()) {
                    continue;
                }
                Block feet = world.getBlockAt(x, column.y() + 1, z);
                Block head = world.getBlockAt(x, column.y() + 2, z);
                if (feet.getType().isAir() && head.getType().isAir()) {
                    return new Location(world, x + 0.5, column.y() + 1, z + 0.5);
                }
            }
        }
        return new Location(world, way.getBlockX() + 0.5, baseY, way.getBlockZ() + 0.5);
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
     * The yaw that looks from {@code from} towards {@code to}, in Minecraft's convention
     * (0 = south / +Z, 90 = west / -X).
     */
    private static float outwardYaw(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return 0f;
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /**
     * The person expecting the crate.
     *
     * <p>No AI, invulnerable, silent and persistent: this is a fixture of the delivery, not a
     * mob. Without {@code setAI(false)} it would wander off the spot the player was sent to;
     * without {@code setPersistent(true)} it would despawn while they were still walking.
     */
    private Villager spawnRecipient(CourierJob job, Location at, Location centre,
                                    BuildingTemplate.BiomeGroup group) {
        try {
            World world = at.getWorld();
            if (world == null) {
                return null;
            }
            // Face away from the building, which is to say towards whoever walks up. Spawning
            // at the default yaw left the recipient staring into their own wall.
            Location spawn = at.clone();
            spawn.setYaw(outwardYaw(centre, at));
            spawn.setPitch(0f);
            Villager villager = world.spawn(spawn, Villager.class, v -> {
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
            villager.setRotation(spawn.getYaw(), 0f);
            dao.setVillager(job.id(), villager.getUniqueId());
            return villager;
        } catch (Exception e) {
            plugin.getLogger().warning("Could not spawn the courier recipient for job "
                    + job.id() + ": " + e);
            return null;
        }
    }

    /**
     * Turn the recipient to watch a nearby player, and let them make a noise now and then.
     *
     * <p>{@code setAI(false)} is what keeps them planted on the doorstep, but it also makes them
     * completely inert — they read as a prop rather than a person. Rotation still works with the
     * AI off, so this is the cheap half of being alive: look at whoever is close, and every so
     * often say something. It runs only for sites that have somebody standing near them, which
     * on this server is at most one.
     */
    public void tickPresence() {
        if (standing.isEmpty()) {
            return;
        }
        for (DeliverySite site : standing.values()) {
            if (site.villager() == null) {
                continue;
            }
            Entity entity = Bukkit.getEntity(site.villager());
            if (!(entity instanceof Villager villager) || !villager.isValid()) {
                continue;
            }
            Player nearest = null;
            double best = Double.MAX_VALUE;
            for (Player player : villager.getWorld().getPlayers()) {
                double distance = player.getLocation().distanceSquared(villager.getLocation());
                if (distance < best) {
                    best = distance;
                    nearest = player;
                }
            }
            if (nearest == null || best > 12 * 12) {
                continue;
            }
            double dx = nearest.getLocation().getX() - villager.getLocation().getX();
            double dz = nearest.getLocation().getZ() - villager.getLocation().getZ();
            if (dx * dx + dz * dz > 0.01) {
                villager.setRotation((float) Math.toDegrees(Math.atan2(-dx, dz)), 0f);
            }
            // setSilent(true) stops the villager making its own noise; the plugin can still
            // play one, which keeps it occasional and deliberate rather than constant.
            if (best < 6 * 6 && ambient++ % AMBIENT_EVERY == 0) {
                villager.getWorld().playSound(villager.getLocation(),
                        Sound.ENTITY_VILLAGER_AMBIENT, 0.6f, 1.0f);
            }
        }
    }

    /** Acknowledge a delivery, out loud, where the player is standing. */
    public void celebrate(long jobId) {
        DeliverySite site = standing.get(jobId);
        if (site == null || site.villager() == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(site.villager());
        if (entity == null) {
            return;
        }
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.0f);
        if (entity instanceof Villager villager) {
            villager.playEffect(org.bukkit.EntityEffect.VILLAGER_HAPPY);
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
        finished(jobId);
        if (readyToRestore(site, System.currentTimeMillis()) && !restoreNow(site)) {
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
        finishedAt.remove(jobId);
        refused.remove(jobId);
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
