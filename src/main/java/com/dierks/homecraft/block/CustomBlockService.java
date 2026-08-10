package com.dierks.homecraft.block;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.storage.PlacedBlock;
import com.dierks.homecraft.storage.PlacedBlockDao;
import com.dierks.homecraft.util.Keys;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * The bridge between "a block in the world" and "our persisted custom block".
 *
 * <p>Identity is established two ways:
 * <ul>
 *   <li><b>Items</b> carry a PDC marker — read on placement to know it's ours.</li>
 *   <li><b>Placed blocks</b> are recorded in SQLite (source of truth for
 *       break/interact lookups) and, when the base block is a tile entity,
 *       additionally PDC-tagged as defence-in-depth.</li>
 * </ul>
 */
public final class CustomBlockService {

    private final HomeCraftManagement plugin;
    private final PlacedBlockDao dao;

    public CustomBlockService(HomeCraftManagement plugin, PlacedBlockDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    /** @return the custom type an item represents, or {@code null} if it isn't one of ours. */
    public CustomBlockType itemType(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String raw = meta.getPersistentDataContainer().get(Keys.CUSTOM_BLOCK_TYPE, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return CustomBlockType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Record a placement (owner + type) and tag the block if it supports PDC. */
    public void recordPlacement(Block block, CustomBlockType type, UUID owner) {
        try {
            dao.save(PlacedBlock.at(block.getLocation(), type, owner, System.currentTimeMillis()));
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to persist placed block: " + e.getMessage());
        }
        tagBlock(block, type);
    }

    /** @return the persisted placement at this location, if any. */
    public Optional<PlacedBlock> at(Location location) {
        try {
            return dao.findAt(location);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to query placed block: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Remove the placement record at this location. */
    public void removeAt(Location location) {
        try {
            dao.deleteAt(location);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to delete placed block: " + e.getMessage());
        }
    }

    private void tagBlock(Block block, CustomBlockType type) {
        BlockState state = block.getState();
        if (state instanceof TileState tile) {
            tile.getPersistentDataContainer().set(Keys.CUSTOM_BLOCK_TYPE, PersistentDataType.STRING, type.name());
            tile.update(true, false);
        }
    }
}
