package com.dierks.homecraft.block;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.storage.PlacedBlock;
import com.dierks.homecraft.storage.PlacedBlockDao;
import com.dierks.homecraft.util.Keys;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.util.List;
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

    // ---- Mailbox colour variants -------------------------------------------

    /** Stamp a placed Mailbox tile with its colour variant (tile-entity bases only). */
    public void tagMailboxVariant(Block block, MailboxVariant variant) {
        BlockState state = block.getState();
        if (state instanceof TileState tile) {
            tile.getPersistentDataContainer().set(Keys.MAILBOX_VARIANT, PersistentDataType.STRING, variant.name());
            tile.update(true, false);
        }
    }

    /** The variant a placed Mailbox wears; untagged (pre-variant) placements read as wood. */
    public MailboxVariant mailboxVariantAt(Block block) {
        BlockState state = block.getState();
        if (state instanceof TileState tile) {
            return MailboxVariant.parseOrWood(
                    tile.getPersistentDataContainer().get(Keys.MAILBOX_VARIANT, PersistentDataType.STRING));
        }
        return MailboxVariant.WOOD;
    }

    // ---- Two-tall Vending Machine --------------------------------------------

    /** True if this block is the auto-placed upper head of a Vending Machine. */
    public boolean isVendingUpper(Block block) {
        BlockState state = block.getState();
        return state instanceof TileState tile
                && tile.getPersistentDataContainer().has(Keys.VENDING_UPPER, PersistentDataType.BYTE);
    }

    /**
     * Place (or refresh) the upper head of a Vending Machine above {@code lower}: a
     * player head wearing {@code skins.vending_upper}, tagged as a companion so
     * interact/break on it resolve to the real block below. Requires the block
     * above to be air (or an existing companion head). Returns false if blocked.
     */
    public boolean placeVendingUpper(Block lower) {
        Block upper = lower.getRelative(BlockFace.UP);
        if (upper.getY() >= lower.getWorld().getMaxHeight()) {
            return false;
        }
        if (!upper.getType().isAir() && !isVendingUpper(upper)) {
            return false;
        }
        upper.setType(Material.PLAYER_HEAD, false);
        // Face the same way as the lower head when both are floor-mounted heads.
        BlockData lowerData = lower.getBlockData();
        if (lowerData instanceof Rotatable lowRot && upper.getBlockData() instanceof Rotatable upRot) {
            upRot.setRotation(lowRot.getRotation());
            upper.setBlockData(upRot, false);
        }
        BlockState state = upper.getState();
        if (state instanceof TileState tile) {
            tile.getPersistentDataContainer().set(Keys.VENDING_UPPER, PersistentDataType.BYTE, (byte) 1);
            tile.update(true, false);
        }
        BlockSkins.apply(upper, plugin.config().skinNamed("vending_upper"));
        return true;
    }

    /** Remove the companion head above a Vending Machine, if present. */
    public void removeVendingUpper(Block lower) {
        Block upper = lower.getRelative(BlockFace.UP);
        if (isVendingUpper(upper)) {
            upper.setType(Material.AIR, false);
        }
    }

    /**
     * Resolve a clicked/broken block to the Vending Machine it belongs to: the block
     * itself if it is a recorded Vending Machine, or the block below if this is a
     * companion upper head. Empty for anything else.
     */
    public Optional<Block> vendingLowerOf(Block block) {
        if (isVendingUpper(block)) {
            Block below = block.getRelative(BlockFace.DOWN);
            Optional<PlacedBlock> placed = at(below.getLocation());
            if (placed.isPresent() && placed.get().type() == CustomBlockType.MINI_VENDING_MACHINE) {
                return Optional.of(below);
            }
        }
        return Optional.empty();
    }

    /**
     * Load-time migration: every recorded Vending Machine gets its upper head if the
     * block above is air (already-migrated machines are left alone). Anything else in
     * the way is logged with coordinates so an admin can clear it.
     */
    public void migrateVendingUppers() {
        List<PlacedBlock> machines;
        try {
            machines = dao.findByType(CustomBlockType.MINI_VENDING_MACHINE);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to list vending machines for migration: " + e.getMessage());
            return;
        }
        int placed = 0;
        for (PlacedBlock pb : machines) {
            World world = plugin.getServer().getWorld(pb.world());
            if (world == null) {
                plugin.getLogger().warning("Vending Machine at " + pb.world() + " " + pb.x() + "," + pb.y() + ","
                        + pb.z() + ": world not loaded — upper head not placed.");
                continue;
            }
            Block lower = world.getBlockAt(pb.x(), pb.y(), pb.z());
            Block upper = lower.getRelative(BlockFace.UP);
            if (isVendingUpper(upper)) {
                continue; // already two-tall
            }
            if (!upper.getType().isAir()) {
                plugin.getLogger().warning("Vending Machine at " + pb.world() + " " + pb.x() + "," + pb.y() + ","
                        + pb.z() + ": block above is " + upper.getType() + ", not air — upper head NOT placed. "
                        + "Clear it and break/re-place the machine.");
                continue;
            }
            if (placeVendingUpper(lower)) {
                placed++;
            }
        }
        if (placed > 0) {
            plugin.getLogger().info("Vending Machine migration: placed " + placed + " upper head(s).");
        }
    }
}
