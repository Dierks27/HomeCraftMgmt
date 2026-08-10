package com.dierks.homecraft.integration;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Array;
import java.lang.reflect.Method;

/**
 * Respects Towny and WorldGuard build permissions before we let a player place,
 * use, or break a HomeCraft custom block.
 *
 * <p>Both integrations are optional soft-depends, so this class talks to them
 * purely via <b>reflection</b> — the plugin compiles and runs with neither of
 * their jars on the classpath, and every check <b>degrades to "allow"</b> if the
 * plugin is absent or an API call fails (logged once). The reflective call
 * chains mirror the stable public entry points; verify them against the live
 * server during the integration "formalities" pass.
 */
public final class ProtectionService {

    private final HomeCraftManagement plugin;
    private final boolean townyPresent;
    private final boolean worldGuardPresent;

    private boolean warnedTowny = false;
    private boolean warnedWorldGuard = false;

    public ProtectionService(HomeCraftManagement plugin) {
        this.plugin = plugin;
        PluginManager pm = plugin.getServer().getPluginManager();
        this.townyPresent = pm.getPlugin("Towny") != null;
        this.worldGuardPresent = pm.getPlugin("WorldGuard") != null;
        plugin.getLogger().info("Protection integrations — Towny: " + townyPresent
                + ", WorldGuard: " + worldGuardPresent);
    }

    /**
     * @return true if {@code player} may build at {@code location} (or if there's
     * nothing to check). Ops and holders of {@code hcm.protection.bypass} skip checks.
     */
    public boolean canBuild(Player player, Location location) {
        if (player.isOp() || player.hasPermission("hcm.protection.bypass")) {
            return true;
        }
        if (townyPresent && !townyAllows(player, location)) {
            return false;
        }
        if (worldGuardPresent && !worldGuardAllows(player, location)) {
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean townyAllows(Player player, Location location) {
        try {
            Class<?> playerCacheUtil = Class.forName("com.palmergames.bukkit.towny.utils.PlayerCacheUtil");
            Class<?> actionTypeClass = Class.forName("com.palmergames.bukkit.towny.object.TownyPermission$ActionType");
            Object build = Enum.valueOf(actionTypeClass.asSubclass(Enum.class), "BUILD");
            Material material = location.getBlock().getType();
            Method getCachePermission = playerCacheUtil.getMethod(
                    "getCachePermission", Player.class, Location.class, Material.class, actionTypeClass);
            Object result = getCachePermission.invoke(null, player, location, material, build);
            return (Boolean) result;
        } catch (Throwable t) {
            if (!warnedTowny) {
                warnedTowny = true;
                plugin.getLogger().warning("Towny build check unavailable (" + t + "); allowing by default.");
            }
            return true;
        }
    }

    private boolean worldGuardAllows(Player player, Location location) {
        try {
            Class<?> wgPluginClass = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin");
            Object wgPlugin = wgPluginClass.getMethod("inst").invoke(null);
            Object localPlayer = wgPluginClass.getMethod("wrapPlayer", Player.class).invoke(wgPlugin, player);

            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object worldGuard = wgClass.getMethod("getInstance").invoke(null);
            Object platform = wgClass.getMethod("getPlatform").invoke(worldGuard);
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Object query = container.getClass().getMethod("createQuery").invoke(container);

            Object weLocation = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter")
                    .getMethod("adapt", Location.class).invoke(null, location);

            Object buildFlag = Class.forName("com.sk89q.worldguard.protection.flags.Flags")
                    .getField("BUILD").get(null);

            Class<?> weLocationClass = Class.forName("com.sk89q.worldedit.util.Location");
            Class<?> associableClass = Class.forName("com.sk89q.worldguard.protection.association.RegionAssociable");
            Class<?> stateFlagClass = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag");

            Object flagArray = Array.newInstance(stateFlagClass, 1);
            Array.set(flagArray, 0, buildFlag);

            Method testState = query.getClass().getMethod(
                    "testState", weLocationClass, associableClass, flagArray.getClass());
            Object result = testState.invoke(query, weLocation, localPlayer, flagArray);
            return (Boolean) result;
        } catch (Throwable t) {
            if (!warnedWorldGuard) {
                warnedWorldGuard = true;
                plugin.getLogger().warning("WorldGuard build check unavailable (" + t + "); allowing by default.");
            }
            return true;
        }
    }
}
