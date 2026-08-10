package com.dierks.homecraft.storage;

import com.dierks.homecraft.block.CustomBlockType;
import org.bukkit.Location;

import java.util.UUID;

/**
 * A persisted custom-block placement: which type, where, owned by whom, when.
 */
public record PlacedBlock(String world, int x, int y, int z, CustomBlockType type, UUID owner, long createdAt) {

    public static PlacedBlock at(Location loc, CustomBlockType type, UUID owner, long createdAt) {
        return new PlacedBlock(
                loc.getWorld().getName(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ(),
                type,
                owner,
                createdAt);
    }
}
