package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.QuestType;
import com.dierks.homecraft.storage.PlacedNaturalDao;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The pushed quest types that come straight from the world: planting and harvesting crops,
 * mining stone and ore, and taking cooked food or smelted ore out of a furnace.
 *
 * <p>MINE_BLOCKS must not count a block the player put there themselves, or place-and-break is a
 * quest. It shares the {@code player_placed} ledger the wild drops already keep: a mined block
 * that the ledger knows was placed counts for nothing. The wild-drop listener consumes ledger rows
 * at MONITOR, so this one reads the ledger at HIGH and counts at MONITOR, and tidies up rows for
 * the stone the wild drops do not track.
 */
public final class QuestListener implements Listener {

    /** Ore products a furnace makes. Anything else it smelts is not "smelting ore". */
    private static final Set<Material> ORE_PRODUCTS = Set.of(
            Material.IRON_INGOT, Material.GOLD_INGOT, Material.COPPER_INGOT, Material.NETHERITE_SCRAP,
            Material.COAL, Material.DIAMOND, Material.EMERALD, Material.LAPIS_LAZULI, Material.REDSTONE,
            Material.QUARTZ);

    private final HomeCraftManagement plugin;
    private final PlacedNaturalDao placed;
    /** Break decisions made at HIGH, waiting for the MONITOR pass: block → was it natural. */
    private final Map<Block, Boolean> pending = new HashMap<>();

    public QuestListener(HomeCraftManagement plugin, PlacedNaturalDao placed) {
        this.plugin = plugin;
        this.placed = placed;
    }

    private boolean active() {
        return plugin.quests() != null && plugin.config().quests() != null && plugin.config().quests().enabled();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Material type = block.getType();
        if (isMineable(type)) {
            try {
                placed.mark(block.getLocation());
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not note a placed block for quests: " + e.getMessage());
            }
        }
        if (!active() || event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (isCrop(type)) {
            plugin.quests().record(event.getPlayer(), QuestType.PLANT_CROPS, 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakCheck(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isMineable(block.getType())) {
            return;
        }
        try {
            pending.put(block, !placed.contains(block.getLocation()));
        } catch (SQLException e) {
            pending.put(block, false); // unknown is not "natural"
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Boolean natural = pending.remove(block);
        if (event.isCancelled()) {
            return;
        }
        if (natural != null && !natural && !plugin.wildDrops().isTrackedBlock(block.getType())) {
            try {
                placed.consume(block.getLocation()); // the wild drops will not clear this one
            } catch (SQLException ignored) {
                // a stale ledger row only means a later mine there does not count
            }
        }
        if (!active() || event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (Boolean.TRUE.equals(natural)) {
            plugin.quests().record(event.getPlayer(), QuestType.MINE_BLOCKS, 1);
        }
        BlockData data = block.getBlockData();
        if (isCrop(block.getType()) && data instanceof Ageable age && age.getAge() >= age.getMaximumAge()) {
            plugin.quests().record(event.getPlayer(), QuestType.HARVEST_CROPS, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnace(FurnaceExtractEvent event) {
        if (!active() || event.getItemAmount() <= 0) {
            return;
        }
        Material type = event.getItemType();
        if (type.isEdible()) {
            plugin.quests().record(event.getPlayer(), QuestType.COOK_FOOD, event.getItemAmount());
        } else if (ORE_PRODUCTS.contains(type)) {
            plugin.quests().record(event.getPlayer(), QuestType.SMELT_ORE, event.getItemAmount());
        }
    }

    /** Planted and harvested crops: the vanilla crops tag, plus the ones it leaves out. */
    static boolean isCrop(Material type) {
        try {
            if (Tag.CROPS.isTagged(type)) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // fall through to the names
        }
        return type == Material.NETHER_WART || type == Material.COCOA || type == Material.SWEET_BERRY_BUSH;
    }

    /** Natural stone, deepslate and ores — what "Mine 200 stone or ore" means. */
    static boolean isMineable(Material type) {
        try {
            if (Tag.BASE_STONE_OVERWORLD.isTagged(type) || Tag.BASE_STONE_NETHER.isTagged(type)) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // fall through to the names
        }
        String n = type.name();
        return n.endsWith("_ORE") || type == Material.ANCIENT_DEBRIS || type == Material.STONE
                || type == Material.DEEPSLATE;
    }
}
