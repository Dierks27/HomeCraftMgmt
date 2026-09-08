package com.dierks.homecraft.integration;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * The per-world economy sandbox (§11 #1): every money- or token-moving action is
 * allowed only in {@code worlds.economy_enabled}. Outside those worlds the market,
 * Marketplace, packs, Printer, Vending sales, auctions, wild drops / natural
 * spawns and token earning are all refused, and HomeCraft blocks cannot be placed.
 * Blocked attempts are logged with player + world when
 * {@code worlds.log_blocked_attempts} is on.
 */
public final class EconomySandbox {

    public static final String MESSAGE = "&cThe economy is disabled in this world.";

    private final HomeCraftManagement plugin;

    public EconomySandbox(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** True if the economy runs in this world. An empty list means everywhere (never lock yourself out). */
    public boolean allowed(World world) {
        if (world == null) {
            return false;
        }
        PluginConfig.Worlds w = plugin.config().worlds();
        if (w == null || w.economyEnabled().isEmpty()) {
            return true;
        }
        for (String name : w.economyEnabled()) {
            if (name.equalsIgnoreCase(world.getName())) {
                return true;
            }
        }
        return false;
    }

    public boolean allowed(Location loc) {
        return loc != null && allowed(loc.getWorld());
    }

    /**
     * Gate a player action: true if allowed. When refused the player is told once and
     * the attempt is logged (player, world, action) if logging is on.
     */
    public boolean check(Player player, String action) {
        if (player == null || allowed(player.getWorld())) {
            return true;
        }
        player.sendMessage(Text.of(MESSAGE));
        log(player, action);
        return false;
    }

    /** Log-only variant for silent paths (drops, timers) where a chat line would be spam. */
    public void log(Player player, String action) {
        PluginConfig.Worlds w = plugin.config().worlds();
        if (w != null && w.logBlockedAttempts()) {
            plugin.getLogger().info("Economy sandbox: blocked " + action + " by " + player.getName()
                    + " in world '" + player.getWorld().getName() + "'.");
        }
    }

    /** The message shown for a refused action (for services returning a Result). */
    public static String reason() {
        return "The economy is disabled in this world.";
    }

    /** "market buy" → for log lines. */
    public static String action(String s) {
        return s == null ? "action" : s.toLowerCase(Locale.ROOT);
    }
}
