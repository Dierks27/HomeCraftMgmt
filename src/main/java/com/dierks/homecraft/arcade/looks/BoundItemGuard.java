package com.dierks.homecraft.arcade.looks;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.PrizeService;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.TokenPrizes;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

/**
 * The guard (SHOP-V2-SPEC §3.7, D9): a bound key ({@link PrizeService#bound}: a rented hat or cap, a Keeper) stays
 * with its owner. It is never placed, dropped, put in a chest, a hopper, a dispenser, a furnace, a workstation or
 * another player's things, given to a frame, a stand or an allay, put in a pot, on a shelf or in a bundle. The
 * player's own ender chest is fine: the database is the truth, every sweep reads the ender chest, and a child can
 * put her hats away. The decisions are {@link GuardRules}'; this reads the facts off each event.
 *
 * <p>What it never does: refuse a Creative player's own inventory (the client drives it, and a refusal desyncs it; a
 * Creative copy of your own key is harmless, and the sweeps take the extra one), or delete anything from a player's
 * own inventory or ender chest that someone else is looking at (an admin's {@code /invsee} or {@code /ec} of the
 * daughter changes nothing, red team D-M2). Only a block's or a non-player entity's container is swept on close.
 *
 * <p>Death needs nothing here: a key is a prize, and prizes are kept on death ({@code PrizeSafetyListener}). A key
 * that reaches the ground anyway never lands (it is cancelled as it spawns) and is made again free in My Looks.
 *
 * <p>A bundle or a shulker box holding a key ({@link #carriesKey}, red-team finding GUARD-1) is guarded as the key
 * is: never placed, dropped, put in a container or given to an entity. Only Creative puts a key inside one, and the
 * player's own sweeps keep it there; one that reaches a container's close or the ground anyway has its keys taken
 * out, and the rest of what it holds stays.
 */
public final class BoundItemGuard implements Listener {

    private final HomeCraftManagement plugin;

    public BoundItemGuard(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    // ---- placing and dropping ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (GuardRules.placeRefused(carriesKey(event.getItemInHand()))) {
            event.setCancelled(true); // a key, or a shulker box with one inside
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack stack = event.getItemDrop().getItemStack();
        boolean bound = PrizeService.bound(stack);
        List<LookRules.Found> inside = bound ? List.of() : keysInside(stack);
        if (!bound && inside.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        player.sendMessage(Text.of(bound ? "&7Your " + name(stack) + " stays with you. Take it off in My Looks."
                : "&7Your " + name(inside.get(0).key().id()) + " is inside that, and it stays with you. Take it out"
                + " first."));
        Sounds.refused(player);
        resync(player);
    }

    /**
     * A bound key never lands, and nor does a preview stand's copy. HIGH: before prize safety's MONITOR protect, so
     * nothing protects it first. One that gets here came by a path the guard closes elsewhere; the right is in the
     * database, so "Wear it" makes it again.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        if (GuardRules.spawnRefused(PrizeService.bound(stack), preview(stack))) {
            event.setCancelled(true);
            plugin.getLogger().fine("A bound key (" + TokenPrizes.prizeId(stack) + ") was about to land - removed.");
            return;
        }
        // a bundle or a shulker box with a key inside lands without it: the rest of what it holds stays (GUARD-1)
        ItemStack cleaned = withoutKeys(stack);
        if (cleaned != null) {
            event.getEntity().setItemStack(cleaned);
            plugin.getLogger().fine("A bound key inside a " + stack.getType() + " was about to land - taken out.");
        }
    }

    // ---- inventories -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event instanceof InventoryCreativeEvent || GuardRules.creativeRefused()) {
            return; // a Creative player's own inventory is the client's (see the class comment)
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryView view = event.getView();
        GuardRules.Top top = top(view, player);
        if (top == GuardRules.Top.MENU) {
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        boolean cursorBound = carriesKey(cursor);
        boolean currentBound = carriesKey(current);
        PlayerInventory inv = player.getInventory();
        boolean hotbarBound = event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0
                && carriesKey(inv.getItem(event.getHotbarButton()));
        boolean offhandBound = event.getClick() == ClickType.SWAP_OFFHAND && carriesKey(inv.getItemInOffHand());
        if (!cursorBound && !currentBound && !hotbarBound && !offhandBound) {
            return; // the common case: nothing bound in play
        }
        int raw = event.getRawSlot();
        boolean clickedInTop = raw >= 0 && raw < view.getTopInventory().getSize();
        if (GuardRules.clickRefused(top, clickedInTop, event.getAction(), event.getClick(), cursorBound, currentBound,
                hotbarBound, offhandBound, bundle(cursor), bundle(current))) {
            event.setCancelled(true);
            player.sendMessage(Text.of("&7That stays with you."));
            Sounds.refused(player);
            resync(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !carriesKey(event.getOldCursor())) {
            return;
        }
        InventoryView view = event.getView();
        int topSize = view.getTopInventory().getSize();
        boolean anyTop = false;
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) {
                anyTop = true;
                break;
            }
        }
        if (GuardRules.dragRefused(top(view, player), true, anyTop)) {
            event.setCancelled(true);
            player.sendMessage(Text.of("&7That stays with you."));
            Sounds.refused(player);
            resync(player);
        }
    }

    /**
     * A key left in a block's or an entity's container on close is a path the guard missed: it is deleted, with one
     * WARN naming the inventory and the item. Never a player's own inventory or ender chest (see the class comment).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryType type = top.getType();
        InventoryHolder holder = top.getHolder(false);
        GuardRules.Holder kind = holder instanceof Menu ? GuardRules.Holder.MENU
                : holder instanceof HumanEntity ? GuardRules.Holder.PLAYER
                : holder instanceof BlockInventoryHolder || holder instanceof DoubleChest ? GuardRules.Holder.BLOCK
                : holder instanceof Entity ? GuardRules.Holder.ENTITY
                : GuardRules.Holder.OTHER;
        if (!GuardRules.closeSweeps(kind, type == InventoryType.ENDER_CHEST, type == InventoryType.PLAYER,
                type == InventoryType.CRAFTING)) {
            return;
        }
        ItemStack[] contents = top.getContents();
        List<Integer> swept = GuardRules.sweptSlots(Arrays.asList(contents), PrizeService::bound);
        for (int slot : swept) {
            String id = TokenPrizes.prizeId(contents[slot]);
            top.setItem(slot, null);
            plugin.getLogger().warning(GuardRules.sweptWarning(type.name(), id == null ? "?" : id,
                    event.getPlayer().getName()));
        }
        // and the keys inside a bundle or a shulker box left there (GUARD-1): taken out, the rest stays
        for (int slot = 0; slot < contents.length; slot++) {
            if (swept.contains(slot)) {
                continue;
            }
            List<LookRules.Found> inside = keysInside(contents[slot]);
            ItemStack cleaned = inside.isEmpty() ? null : without(contents[slot], inside);
            if (cleaned != null) {
                top.setItem(slot, cleaned);
                for (LookRules.Found f : inside) {
                    plugin.getLogger().warning(GuardRules.sweptWarning(type.name(), f.key().id(),
                            event.getPlayer().getName()));
                }
            }
        }
    }

    // ---- entities and blocks -------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntity(PlayerInteractEntityEvent event) {
        guardEntity(event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityAt(PlayerInteractAtEntityEvent event) {
        guardEntity(event);
    }

    private void guardEntity(PlayerInteractEntityEvent event) {
        Entity e = event.getRightClicked();
        GuardRules.EntityKind kind = e instanceof ItemFrame ? GuardRules.EntityKind.ITEM_FRAME
                : e instanceof ArmorStand ? GuardRules.EntityKind.ARMOR_STAND
                : e instanceof Allay ? GuardRules.EntityKind.ALLAY
                : GuardRules.EntityKind.OTHER;
        if (kind == GuardRules.EntityKind.OTHER) {
            return;
        }
        ItemStack hand = event.getPlayer().getInventory().getItem(event.getHand() == null ? EquipmentSlot.HAND
                : event.getHand());
        if (GuardRules.entityRefused(kind, carriesKey(hand))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Text.of("&7That stays with you."));
            Sounds.refused(event.getPlayer());
            resync(event.getPlayer());
        }
    }

    /** No bound key onto a stand, and nothing at all off a preview stand (§3.16). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onStand(PlayerArmorStandManipulateEvent event) {
        if (carriesKey(event.getPlayerItem()) || previewStand(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    /** A pot, a chiseled bookshelf or a shelf: the material first, so every other block costs one compare. */
    @EventHandler(priority = EventPriority.LOW)
    public void onBlockUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Material m = block.getType();
        String name = m.name();
        if (m != Material.DECORATED_POT && m != Material.CHISELED_BOOKSHELF && !name.endsWith("_SHELF")) {
            return;
        }
        Player player = event.getPlayer();
        PlayerInventory inv = player.getInventory();
        boolean handBound = carriesKey(event.getItem());
        boolean anyHotbar = false;
        for (int i = 0; i < 9 && !anyHotbar; i++) {
            anyHotbar = carriesKey(inv.getItem(i));
        }
        if (GuardRules.blockUseRefused(name, handBound, anyHotbar || carriesKey(inv.getItemInOffHand()))) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            player.sendMessage(Text.of("&7That stays with you."));
            Sounds.refused(player);
        }
    }

    /** No furnace smelts a prize. (FurnaceStartSmeltEvent can't be cancelled; the smelt itself can.) */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSmelt(FurnaceSmeltEvent event) {
        if (GuardRules.smeltRefused(TokenPrizes.is(event.getSource()))) {
            event.setCancelled(true);
        }
    }

    // ---- facts ----------------------------------------------------------------------------------

    /** What the open screen's top is to {@code player}. */
    private static GuardRules.Top top(InventoryView view, Player player) {
        Inventory top = view.getTopInventory();
        if (top.getHolder(false) instanceof Menu) {
            return GuardRules.Top.MENU;
        }
        if (top.getType() == InventoryType.CRAFTING) {
            return GuardRules.Top.OWN_CRAFTING;
        }
        if (top.equals(player.getEnderChest())) {
            return GuardRules.Top.OWN_ENDER_CHEST;
        }
        return GuardRules.Top.OTHER;
    }

    private static boolean bundle(ItemStack item) {
        return item != null && item.getType().name().endsWith("BUNDLE");
    }

    /** A key, or a bundle or a shulker box with one inside (GUARD-1): what the guard keeps with its owner. */
    static boolean carriesKey(ItemStack item) {
        return PrizeService.bound(item) || !keysInside(item).isEmpty();
    }

    /** The keys inside a bundle or a shulker box, found by the sweep's own walk; empty for anything else. */
    static List<LookRules.Found> keysInside(ItemStack item) {
        return GuardRules.keysInside(item, i -> !i.getType().isAir() && LookService.container(i.getType()),
                LookService::keyFacts, LookService::contents);
    }

    /** {@code item} with the keys inside it taken out, or null when it holds none or can't be rewritten. */
    static ItemStack withoutKeys(ItemStack item) {
        List<LookRules.Found> inside = keysInside(item);
        return inside.isEmpty() ? null : without(item, inside);
    }

    private static ItemStack without(ItemStack item, List<LookRules.Found> inside) {
        List<List<Integer>> paths = new ArrayList<>();
        for (LookRules.Found f : inside) {
            paths.add(f.path());
        }
        return LookService.without(item, paths, 0);
    }

    /** A preview stand's copy ({@link Keys#LOOK_PREVIEW}), read from the data view. */
    static boolean preview(ItemStack item) {
        if (item == null || Keys.LOOK_PREVIEW == null || item.getType().isAir()) {
            return false;
        }
        try {
            PersistentDataContainerView pdc = item.getPersistentDataContainer();
            return pdc.has(Keys.LOOK_PREVIEW, PersistentDataType.BYTE);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean previewStand(ArmorStand stand) {
        try {
            return Keys.LOOK_PREVIEW != null
                    && stand.getPersistentDataContainer().has(Keys.LOOK_PREVIEW, PersistentDataType.BYTE);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The look's name for a line: its row's, without colours, or "look". */
    private String name(ItemStack item) {
        return name(TokenPrizes.prizeId(item));
    }

    private String name(String id) {
        var row = id == null ? null : plugin.config().arcade().prize(id);
        return row != null ? Text.plain(row.display()) : "look";
    }

    /** The client drew the move it asked for: send the bag again next tick, as prize safety does. */
    private void resync(Player player) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.updateInventory();
                }
            });
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "Could not resync an inventory", e);
        }
    }
}
