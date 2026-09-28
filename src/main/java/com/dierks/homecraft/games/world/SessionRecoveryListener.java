package com.dierks.homecraft.games.world;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;

import java.util.ArrayList;
import java.util.logging.Level;

/**
 * Players' things come back whatever the switches say (spec §7.6, R2.6).
 *
 * <p>Registered once at enable and never unregistered — with games on, off, or the games service
 * failed to start (it is built from the database alone). A player whose game was cut short by a
 * crash, a stop, a death or a quit gets everything back on their next join, respawn or the
 * worlds-up pass; turning games off after a crash (the natural reaction) must never strand them
 * with a kit, or leave an old snapshot to overwrite what they gained later.
 *
 * <ul>
 *   <li><b>Join</b> (one tick after, once Multiverse-Inventories has loaded their profile): every
 *       player's inventory and ender chest are swept of stray kit items; a RETURN row sends them
 *       home and hands over the carry; an ACTIVE row (a crash) is restored overwrite-only in its
 *       session world, then they go home.</li>
 *   <li><b>Respawn</b>: a player who died with an ACTIVE row gets it back.</li>
 *   <li><b>Quit</b> (LOWEST, before Multiverse-Inventories saves): a live session is restored in
 *       place and marked RETURN; the next join sends them home.</li>
 *   <li><b>Enable</b>: the same as a join for players already online (a /reload).</li>
 *   <li><b>Disable</b> (our own PluginDisableEvent, which comes just before onDisable): every live
 *       session is restored in place and marked RETURN, with no teleport — Multiverse-Inventories
 *       may be going too, and no task can run to finish a trip. The next start or join sends
 *       them home. The games service's own stop then finds nothing left to do.</li>
 * </ul>
 *
 * <p>It is O(1) for everyone without a row: an in-memory set, loaded at enable, of the players
 * who have one.
 */
public final class SessionRecoveryListener implements Listener {

    private final HomeCraftManagement plugin;
    private final BukkitPort port;

    public SessionRecoveryListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
        BukkitPort built = null;
        try {
            built = BukkitPort.of(plugin);
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.SEVERE, "Games: saved-state recovery could not start", e);
        }
        this.port = built;
        if (port != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> port.safely("the recovery sweep at enable", () -> {
                for (Player p : new ArrayList<>(plugin.getServer().getOnlinePlayers())) {
                    port.core().joined(p);
                }
            }));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (port == null) {
            return;
        }
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> port.safely("the join recovery", () -> {
            if (player.isOnline()) {
                port.core().joined(player);
            }
        }), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerPostRespawnEvent event) {
        if (port != null) {
            port.safely("the respawn recovery", () -> port.core().respawned(event.getPlayer()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDisable(PluginDisableEvent event) {
        if (port != null && event.getPlugin() == plugin) {
            port.disabling();
            port.safely("ending the world sessions at disable", () -> port.core().stop());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        if (port != null) {
            port.safely("the quit restore", () -> {
                port.core().quit(event.getPlayer());
                port.forget(event.getPlayer().getUniqueId());
            });
        }
    }
}
