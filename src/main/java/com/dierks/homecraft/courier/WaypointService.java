package com.dierks.homecraft.courier;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Picks somewhere to deliver to.
 *
 * <p>A random bearing and distance from the player, resolved down to the highest solid block
 * and then checked for the things that would make the trip pointless or impossible: standing
 * on liquid, an ocean biome, or land somebody else owns.
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

    public WaypointService(HomeCraftManagement plugin) {
        this.plugin = plugin;
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

        world.getChunkAtAsync(x >> 4, z >> 4, true)
                .thenAccept(chunk -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Location candidate = resolve(world, x, z);
                    if (candidate != null && acceptable(player, candidate)) {
                        result.complete(candidate);
                    } else {
                        attempt(player, world, from, min, max, tried + 1, limit, result);
                    }
                }))
                .exceptionally(t -> {
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> attempt(player, world, from, min, max, tried + 1, limit, result));
                    return null;
                });
    }

    /**
     * The highest solid block at these coordinates, as a standing position.
     *
     * <p>Main thread only, and only once the chunk is in — see the class note.
     */
    private Location resolve(World world, int x, int z) {
        try {
            Block highest = world.getHighestBlockAt(x, z);
            if (highest == null || highest.getType().isAir()) {
                return null;
            }
            return new Location(world, x, highest.getY() + 1, z);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True if a player could actually be sent here. */
    private boolean acceptable(Player player, Location loc) {
        Block ground = loc.getBlock().getRelative(0, -1, 0);
        // Water and lava both read as "highest block" but neither is somewhere to arrive.
        if (ground.isLiquid() || loc.getBlock().isLiquid()) {
            return false;
        }
        if (!ground.getType().isSolid()) {
            return false;
        }
        if (isOcean(loc)) {
            return false;
        }
        return !claimed(player, loc);
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
