package com.dierks.homecraft.trade;

import com.dierks.homecraft.trade.TradeSession.Cause;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * The trade window's events (SHOP-V2-SPEC §4.2, §4.5): the crouch gesture that asks (or says yes), and everything
 * that cancels a trade or a request. A cancel moves nothing: the real stacks never left their bags.
 *
 * <ul>
 *   <li>The gesture: crouch and right-click a player with an empty main hand ({@code PlayerInteractEntityEvent},
 *       HIGH, {@code ignoreCancelled}, so the Games' LOWEST guard wins; main hand only; not an NPC). Cancelled when
 *       it asks.</li>
 *   <li>A quit or a kick (LOWEST backstops: the server closes the view too), a death (LOWEST, with the close
 *       reason DEATH), a world change (LOWEST, before Multiverse-Inventories' LOW swap), a game-mode change
 *       (LOWEST, before Multiverse-Inventories' MONITOR profile swap; both modes logged), and a teleport over one
 *       block or to another world (MONITOR).</li>
 * </ul>
 *
 * <p>Nothing here acts on a trade the server didn't see finish: the next start reports it and an admin settles it
 * (red-team finding TCR4-WORTH-1).
 */
public final class TradeListener implements Listener {

    /** What the listener asks of the trade window (the service, or a test's stand-in). */
    public interface Trades {
        /** {@code trade.enabled}. */
        boolean on();

        /** The gesture: ask {@code target}, or say yes if they asked first. */
        void gesture(Player asker, Player target);

        /** Cancel the player's trade, if any; {@code detail} (or null) goes in the console line. */
        void ended(Player player, Cause cause, String detail);

        /** Drop every request from or to the player. */
        void dropRequests(Player player, String why);
    }

    private final Trades trades;

    public TradeListener(Trades trades) {
        this.trades = trades;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGesture(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        Entity target = e.getRightClicked();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!TradeRules.gestureAsks(p.isSneaking(), e.getHand() == EquipmentSlot.HAND,
                hand == null || hand.getType().isAir(), target instanceof Player, target.hasMetadata("NPC"))) {
            return;
        }
        if (!trades.on() || !p.hasPermission(TradeService.PERMISSION)) {
            return;
        }
        e.setCancelled(true);
        trades.gesture(p, (Player) target);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        trades.ended(e.getPlayer(), Cause.QUIT, null);
        trades.dropRequests(e.getPlayer(), "they left");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onKick(PlayerKickEvent e) {
        trades.ended(e.getPlayer(), Cause.KICKED, null);
        trades.dropRequests(e.getPlayer(), "they left");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        trades.ended(e.getEntity(), Cause.DEATH, null);
        trades.dropRequests(e.getEntity(), null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        trades.ended(e.getPlayer(), Cause.WORLD, null);
        trades.dropRequests(e.getPlayer(), null);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        trades.ended(e.getPlayer(), Cause.GAME_MODE, e.getPlayer().getGameMode() + " -> " + e.getNewGameMode());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (movedAway(e.getFrom(), e.getTo())) {
            trades.ended(e.getPlayer(), Cause.TELEPORT, null);
        }
    }

    /** A teleport that ends a trade (§4.5, T7): to another world, or more than one block; small corrections don't. */
    static boolean movedAway(Location from, Location to) {
        if (from == null || to == null) {
            return false;
        }
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return true;
        }
        return from.distanceSquared(to) > 1.0;
    }
}
