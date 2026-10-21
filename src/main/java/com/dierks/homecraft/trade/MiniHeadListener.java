package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.mini.MiniInfoMenu;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes a Mini head placed as a block a first-class placed Mini: on placement the
 * block records the Mini's full identity (id, uid, mint, grade, finish, owner, exact
 * item) and joins the placed-Mini registry so its effects run; right-clicking opens
 * the public info card; breaking it (by hand, explosion, piston or water) always
 * returns the exact copy — never a plain head, never a fresh mint, and by hand only
 * once the break is final (0.37.3). Towny/WorldGuard are respected on break. Wild
 * spawns are claimed by touching them instead.
 */
public final class MiniHeadListener implements Listener {

    private final HomeCraftManagement plugin;

    public MiniHeadListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            return;
        }
        // A PC is also a PLAYER_HEAD custom block — leave it to the block system.
        if (plugin.blockService().itemType(item) != null) {
            return;
        }
        if (plugin.miniService().identify(item) == null) {
            return;
        }
        if (!(event.getBlockPlaced().getState() instanceof Skull)) {
            return;
        }
        plugin.placedMinis().onPlaced(event.getBlockPlaced(), item, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || !(clicked.getState() instanceof Skull skull)) {
            return;
        }
        MiniService.MiniRef ref = plugin.miniService().refFrom(skull.getPersistentDataContainer());
        if (ref == null) {
            return;
        }
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        Player player = event.getPlayer();
        // A wild Mini is caught by touching it, not inspected.
        if (plugin.hunt() != null && plugin.hunt().isWild(clicked)) {
            plugin.hunt().claim(player, clicked, true);
            return;
        }
        ItemStack display = plugin.placedMinis().itemAt(clicked);
        new MiniInfoMenu(plugin, player, ref, display, null).open(player);
    }

    /**
     * A break only decides here (HIGH): never a plain head, a wild Mini is caught, and a player who may not take a
     * placed one is refused ({@link PlacedMiniRules#decide}). The exact copy drops in {@link #onBreakFinal}, once no
     * other plugin can cancel the break (0.37.3, red team D-M6).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isMiniHead(event.getBlock())) {
            return;
        }
        event.setDropItems(false); // never a plain head
        boolean wild = huntWild(event.getBlock());
        boolean mayBreak = !wild && plugin.placedMinis().mayBreak(event.getPlayer(), event.getBlock());
        switch (PlacedMiniRules.decide(wild, mayBreak)) {
            case CATCH -> plugin.hunt().claim(event.getPlayer(), event.getBlock(), false);
            case REFUSE -> {
                event.setCancelled(true);
                event.getPlayer().sendMessage(Text.of("&cYou can't take that Mini here."));
            }
            case WAIT -> {
            }
        }
    }

    /**
     * The break is final (MONITOR, {@code ignoreCancelled}: a break any plugin cancelled never gets here, so the
     * head keeps its copy and its row). Return the exact minted copy and forget the head; the block is removed by
     * the event itself. A wild head was caught at HIGH and carries no copy.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakFinal(BlockBreakEvent event) {
        if (!isMiniHead(event.getBlock()) || !PlacedMiniRules.dropsCopy(huntWild(event.getBlock()))) {
            return;
        }
        plugin.placedMinis().dropAndForget(event.getBlock(), false);
    }

    /** A skull carrying a Mini, placed or wild. */
    private boolean isMiniHead(Block block) {
        BlockState state = block.getState();
        return state instanceof Skull skull && plugin.miniService().refFrom(skull.getPersistentDataContainer()) != null;
    }

    /** A wild head while the hunt is on; with the hunt off, a wild-tagged head breaks like a placed one, as before. */
    private boolean huntWild(Block block) {
        return plugin.hunt() != null && plugin.hunt().isWild(block);
    }

    // ---- destruction that isn't a player break: the copy still comes back --------

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        dropAll(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        dropAll(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        List<Block> heads = new ArrayList<>();
        for (Block b : event.getBlocks()) {
            if (plugin.placedMinis().isPlacedMini(b)) {
                heads.add(b);
            }
        }
        for (Block b : heads) {
            plugin.placedMinis().dropAndForget(b, true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        List<Block> heads = new ArrayList<>();
        for (Block b : event.getBlocks()) {
            if (plugin.placedMinis().isPlacedMini(b)) {
                heads.add(b);
            }
        }
        for (Block b : heads) {
            plugin.placedMinis().dropAndForget(b, true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block to = event.getToBlock();
        if (plugin.placedMinis().isPlacedMini(to)) {
            plugin.placedMinis().dropAndForget(to, true);
        }
    }

    private void dropAll(List<Block> blocks) {
        List<Block> heads = new ArrayList<>();
        for (Block b : blocks) {
            if (plugin.placedMinis().isPlacedMini(b) && !isWild(b)) {
                heads.add(b);
            }
        }
        for (Block b : heads) {
            blocks.remove(b); // we clear it ourselves so the exact copy drops, not a plain head
            plugin.placedMinis().dropAndForget(b, true);
        }
    }

    private boolean isWild(Block b) {
        return b.getState() instanceof Skull s
                && s.getPersistentDataContainer().has(Keys.WILD_SPAWN, PersistentDataType.BYTE);
    }
}
