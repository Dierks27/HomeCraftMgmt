package com.dierks.homecraft.courier;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Picks somewhere to deliver to.
 *
 * <p>A random bearing and distance from the player, resolved down to the highest solid block
 * and then checked for the things that would make the trip pointless or impossible: standing
 * on liquid, an ocean biome, or land somebody else owns. Phase 1 has no building at the far
 * end, so the only thing a waypoint has to be is <b>somewhere a player can stand when they
 * get there</b>.
 *
 * <p>After {@code max_rerolls} the job is simply not offered. Handing out a job whose
 * destination is the middle of an ocean would be worse than a shorter board.
 */
public final class WaypointService {

    private final HomeCraftManagement plugin;

    public WaypointService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /**
     * A deliverable spot between {@code min} and {@code max} blocks from {@code from}, or null
     * if the area will not yield one.
     */
    public Location find(Player player, Location from, int min, int max) {
        World world = from.getWorld();
        if (world == null) {
            return null;
        }
        int rerolls = plugin.config().courier().maxRerolls();
        for (int attempt = 0; attempt < rerolls; attempt++) {
            double bearing = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            double distance = min + ThreadLocalRandom.current().nextDouble() * (max - min);
            int x = (int) Math.round(from.getX() + Math.cos(bearing) * distance);
            int z = (int) Math.round(from.getZ() + Math.sin(bearing) * distance);
            Location candidate = resolve(world, x, z);
            if (candidate != null && acceptable(player, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The highest solid block at these coordinates, as a standing position.
     *
     * <p>Reads the world's height map rather than loading the chunk: a waypoint thousands of
     * blocks out would otherwise generate terrain on the main thread just to be rejected.
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
        // Someone else's town or region. A delivery that needs build rights to reach is a
        // delivery that fails, so this is checked at generation rather than at turn-in.
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
     * True if this spot is inside land the player has no claim to.
     *
     * <p>{@code ProtectionService} degrades to "allow" when neither Towny nor WorldGuard is
     * installed, which is right for building but wrong here: with no protection plugin at all
     * every spot is unclaimed, and rejecting them would leave the board permanently empty.
     * So this asks whether the player may build, which answers the question that matters —
     * can they get there and hand the crate over.
     */
    private boolean claimed(Player player, Location loc) {
        try {
            return !plugin.protection().canBuild(player, loc);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
