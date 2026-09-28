package com.dierks.homecraft.games.world;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.util.Text;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Games worlds are read-only (spec §7.1, R2.14): while games are on, a player without
 * {@code hcm.games.admin} can't place or break blocks, use buckets, hang or break paintings and
 * frames, place boats, carts or stands, or write signs in any world of {@code games.worlds}.
 *
 * <p>Fair play covers the course, not only the runner: without this, a friend outside a session
 * (or an alt) could bridge a shortcut across a parkour or elytra course, or stash things, and the
 * straight-line checks between checkpoints would never notice. Admins are exempt, which is also
 * what lets them build courses and write {@code [Arcade]} join signs. Each player's attempts are
 * logged at most once an hour.
 */
public final class GamesWorldGuard implements Listener {

    static final long LOG_EVERY_MS = 3_600_000L;
    static final long TELL_EVERY_MS = 2_000L;
    static final String ADMIN = "hcm.games.admin";

    private final HomeCraftManagement plugin;
    private final Map<UUID, Long> logged = new HashMap<>();
    private final Map<UUID, Long> told = new HashMap<>();

    public GamesWorldGuard(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** Whether a change in {@code world} is refused: games on, a Games world, not an admin. */
    static boolean readOnly(boolean gamesOn, List<String> gamesWorlds, String world, boolean admin) {
        if (!gamesOn || admin || world == null || gamesWorlds == null) {
            return false;
        }
        for (String w : gamesWorlds) {
            if (w.equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }

    /** Refuse (and tell, and log once an hour) if {@code player} may not change {@code world}. */
    private boolean refuse(Player player, World world, String what) {
        if (player == null || world == null) {
            return false;
        }
        try {
            GamesConfig.Parsed cfg = plugin.config().games();
            if (!readOnly(cfg.enabled(), cfg.common().worlds(), world.getName(), player.hasPermission(ADMIN))) {
                return false;
            }
            long now = System.currentTimeMillis();
            UUID id = player.getUniqueId();
            Long last = logged.get(id);
            if (last == null || now - last >= LOG_EVERY_MS) {
                logged.put(id, now);
                plugin.getLogger().info("Games: " + player.getName() + " tried to " + what + " in the Games world "
                        + world.getName() + " (read-only; logged once an hour).");
            }
            Long lastTold = told.get(id);
            if (lastTold == null || now - lastTold >= TELL_EVERY_MS) {
                told.put(id, now);
                player.sendActionBar(Text.of("&cThe Games world can't be changed."));
            }
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Games: the read-only check failed: " + e.getMessage());
            return false;
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (refuse(e.getPlayer(), e.getBlock().getWorld(), "place a block")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (refuse(e.getPlayer(), e.getBlock().getWorld(), "break a block")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (refuse(e.getPlayer(), e.getBlock().getWorld(), "empty a bucket")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (refuse(e.getPlayer(), e.getBlock().getWorld(), "fill a bucket")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHang(HangingPlaceEvent e) {
        if (refuse(e.getPlayer(), e.getEntity().getWorld(), "hang something")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUnhang(HangingBreakByEntityEvent e) {
        if (e.getRemover() instanceof Player p && refuse(p, e.getEntity().getWorld(), "take something down")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        if (refuse(e.getPlayer(), e.getEntity().getWorld(), "place a " + e.getEntityType().name().toLowerCase(java.util.Locale.ROOT))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSign(SignChangeEvent e) {
        if (refuse(e.getPlayer(), e.getBlock().getWorld(), "write a sign")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        told.remove(e.getPlayer().getUniqueId());
    }
}
