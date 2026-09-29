package com.dierks.homecraft.games.trial;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * The live blocks of a course's world, as a race grid reads them ({@link RaceGrid.Surface}): water
 * is water, anything a player walks through (air, signs, grass) is air, and everything else,
 * ice included, is solid. Main thread only; it reads the loaded chunks racers are standing in.
 */
public final class WorldSurface implements RaceGrid.Surface {

    private final World world;

    public WorldSurface(World world) {
        this.world = world;
    }

    @Override
    public RaceGrid.Cell at(int x, int y, int z) {
        if (world == null || y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return RaceGrid.Cell.AIR;
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
}
