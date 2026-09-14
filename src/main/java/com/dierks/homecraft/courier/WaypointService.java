package com.dierks.homecraft.courier;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Picks somewhere to deliver to.
 *
 * <p>A random bearing and distance from the player, resolved down to the real ground — through
 * any canopy standing on it — and then checked for the things that would make the trip
 * pointless or impossible: water, an ocean biome, land somebody else owns, ground too broken to
 * build on, or something already standing there.
 *
 * <p><b>This is asynchronous, and it has to be.</b> A long-haul waypoint is up to four
 * thousand blocks out, in terrain that very often has never been generated — and every way of
 * asking "what is the ground like there", {@code getHighestBlockAt} included, loads the chunk
 * to answer. Doing that on the main thread ran a world-generation pass per candidate, up to
 * {@code max_rerolls} times, while the server sat still. Chunks are pulled in through
 * {@link World#getChunkAtAsync} instead and every block read happens afterwards, back on the
 * main thread.
 */
public final class WaypointService {

    private final HomeCraftManagement plugin;
    private final BuildingService buildings;

    public WaypointService(HomeCraftManagement plugin, BuildingService buildings) {
        this.plugin = plugin;
        this.buildings = buildings;
    }

    /**
     * A deliverable spot between {@code min} and {@code max} blocks from {@code from}.
     *
     * <p>The future completes on the main thread with a usable location, or with null if the
     * area will not yield one inside {@code max_rerolls} tries. Handing out a job whose
     * destination is the middle of an ocean would be worse than a shorter board.
     */
    public CompletableFuture<Location> find(Player player, Location from, int min, int max) {
        CompletableFuture<Location> result = new CompletableFuture<>();
        World world = from.getWorld();
        if (world == null) {
            result.complete(null);
            return result;
        }
        attempt(player, world, from, min, max, 0,
                Math.max(1, plugin.config().courier().maxRerolls()), result);
        return result;
    }

    /**
     * One candidate, then the next.
     *
     * <p>Written as a chain rather than a loop because each try waits on a chunk: a
     * {@code for} loop would either block the thread it runs on or fire every candidate's
     * chunk load at once, which is a lot of world generation to ask for and then throw away.
     */
    private void attempt(Player player, World world, Location from, int min, int max,
                         int tried, int limit, CompletableFuture<Location> result) {
        if (tried >= limit) {
            result.complete(null);
            return;
        }
        double bearing = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
        double distance = min + ThreadLocalRandom.current().nextDouble() * (max - min);
        int x = (int) Math.round(from.getX() + Math.cos(bearing) * distance);
        int z = (int) Math.round(from.getZ() + Math.sin(bearing) * distance);

        // The neighbours as well as the candidate's own chunk: the terrain and safety checks
        // read a box around the spot, and a block read in an unloaded chunk loads it
        // synchronously — the one thing this whole async chain exists to avoid.
        java.util.List<CompletableFuture<org.bukkit.Chunk>> loads = new java.util.ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                loads.add(world.getChunkAtAsync((x >> 4) + dx, (z >> 4) + dz, true));
            }
        }
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0]))
                .whenComplete((ignored, error) -> plugin.getServer().getScheduler()
                        .runTask(plugin, () -> {
                            Location candidate = error == null ? resolve(world, x, z) : null;
                            if (candidate != null && acceptable(player, candidate)) {
                                result.complete(candidate);
                            } else {
                                attempt(player, world, from, min, max, tried + 1, limit, result);
                            }
                        }));
    }

    /**
     * The forest floor at these coordinates, as a standing position.
     *
     * <p>Deliberately not {@code getHighestBlockAt}. That returns the highest motion-blocking
     * block, which in a wood is a <b>leaf</b> — and leaves report {@code isSolid() == true}, so
     * the old check accepted a treetop as perfectly good ground. Every waypoint in a forest was
     * therefore recorded at canopy height, which put the building's own terrain test, its
     * region scans and its foundation all up in the air. See {@link Ground}.
     *
     * <p>Main thread only, and only once the chunk is in — see the class note.
     */
    private Location resolve(World world, int x, int z) {
        Ground.Column column = Ground.solid(world, x, z,
                plugin.config().courier().building().groundScanDepth());
        return column.isGround() ? new Location(world, x, column.y() + 1, z) : null;
    }

    /** True if a player could actually be sent here. */
    private boolean acceptable(Player player, Location loc) {
        // resolve() has already established real ground under this spot and refused water, so
        // what is left is whether the place is worth sending somebody to.
        if (loc.getBlock().isLiquid()) {
            return false;
        }
        if (isOcean(loc)) {
            return false;
        }
        if (claimed(player, loc)) {
            return false;
        }
        return !occupied(loc);
    }

    /**
     * True if something is already here that a delivery building must not be built over.
     *
     * <p>Checked at <b>waypoint</b> time, not just at placement time, and that difference is the
     * point: rejecting here rerolls onto a different field, whereas rejecting at placement leaves
     * a job the player still has to walk that quietly arrives at nothing. Terrain belongs here
     * for the same reason — a canopy will not grow during an hour-long delivery, so a field that
     * cannot be built on should cost a reroll now rather than a walk later. The container scan
     * stays at placement, because somebody really can put a chest down in the meantime.
     *
     * <p>A spot that passes here can still be refused later; an hour is long enough for somebody
     * to put a chest down.
     */
    private boolean occupied(Location loc) {
        try {
            int radius = plugin.config().courier().building().waypointScanRadius();
            String reason = buildings.groundUnsuitable(loc, radius);
            if (reason != null) {
                plugin.getLogger().fine(() -> "Courier waypoint rejected at x " + loc.getBlockX()
                        + ", z " + loc.getBlockZ() + " — " + reason + ".");
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isOcean(Location loc) {
        try {
            String biome = loc.getBlock().getBiome().toString().toLowerCase(Locale.ROOT);
            return biome.contains("ocean");
        } catch (RuntimeException e) {
            // A server that will not name a biome is not a reason to refuse every waypoint.
            return false;
        }
    }

    /**
     * True if this spot is inside land that belongs to somebody else.
     *
     * <p>Uses {@code claimedByOthers} rather than {@code canBuild}, and the difference is not
     * cosmetic: {@code canBuild} short-circuits to "yes" for ops and anyone holding
     * {@code hcm.protection.bypass}, so under the Phase 1 check an admin — which on this
     * server is the person most likely to be testing — could be sent to deliver into the
     * middle of somebody's town, and Phase 2 would then try to build there. The question worth
     * asking is whether the land is claimed, not whether this particular player could override
     * the claim.
     */
    private boolean claimed(Player player, Location loc) {
        try {
            return plugin.protection().claimedByOthers(player, loc);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
