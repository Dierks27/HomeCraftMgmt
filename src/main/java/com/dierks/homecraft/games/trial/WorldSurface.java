package com.dierks.homecraft.games.trial;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The live blocks of a course's world, as a race grid reads them ({@link RaceGrid.Surface}): water
 * is water, anything a player walks through (air, signs, grass) is air, and everything else,
 * ice included, is solid. Main thread only.
 *
 * <p><b>It never loads a chunk</b> (round 2, G2 #4; fx2-C #4's bug class): {@code World.getBlockAt} in
 * a chunk that isn't loaded loads it synchronously, 5-50 ms on the main thread, and a party race's
 * Start from the Clubhouse reads a boat grid's path (up to {@link RaceGrid#REACH} blocks behind the
 * start) and the stand in chunks nobody has loaded. A block there reads as air and its chunk is noted
 * ({@link #missing}): the caller loads those asynchronously ({@link TrackChunks}) and reads again,
 * never using a read that wasn't {@link #complete}. Only {@link #mayLoad} (an admin's command, the
 * boot) reads through a load, as before.
 */
public final class WorldSurface implements RaceGrid.Surface {

    private final World world;
    private final boolean mayLoad;
    private final Set<Long> missing = new LinkedHashSet<>();

    /** The blocks of the loaded chunks only: anything else reads as air and is {@link #missing}. */
    public WorldSurface(World world) {
        this(world, false);
    }

    private WorldSurface(World world, boolean mayLoad) {
        this.world = world;
        this.mayLoad = mayLoad;
    }

    /**
     * Reads that load a chunk they need on the main thread, for a one-off an admin asked for (a grid
     * or stand set by command, the doctor) or the boot's recovery of a night, never a player's click
     * or the schedule.
     */
    public static WorldSurface mayLoad(World world) {
        return new WorldSurface(world, true);
    }

    @Override
    public RaceGrid.Cell at(int x, int y, int z) {
        if (world == null || y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return RaceGrid.Cell.AIR;
        }
        if (!mayLoad && !world.isChunkLoaded(x >> 4, z >> 4)) {
            missing.add(TrackChunks.key(x >> 4, z >> 4));
            return RaceGrid.Cell.AIR; // never a synchronous load: the caller loads it and reads again
        }
        Block b = world.getBlockAt(x, y, z);
        Material m = b.getType();
        if (m == Material.WATER) {
            return RaceGrid.Cell.WATER;
        }
        if (b.isLiquid()) {
            return RaceGrid.Cell.SOLID; // lava is never somewhere to race
        }
        return b.isPassable() ? RaceGrid.Cell.AIR : RaceGrid.Cell.SOLID;
    }

    /** Whether every block read so far was in a loaded chunk, so what was worked out from them holds. */
    public boolean complete() {
        return missing.isEmpty();
    }

    /** The chunks ({x, z}) a read needed that weren't loaded, in the order first needed. */
    public List<int[]> missing() {
        List<int[]> out = new ArrayList<>(missing.size());
        for (long k : missing) {
            out.add(TrackChunks.chunk(k));
        }
        return out;
    }
}
