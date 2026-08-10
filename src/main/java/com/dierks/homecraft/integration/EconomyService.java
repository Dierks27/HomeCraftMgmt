package com.dierks.homecraft.integration;

import com.dierks.homecraft.HomeCraftManagement;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Thin wrapper over the Vault {@link Economy} service so all money in the
 * plugin flows through the one server economy (EssentialsX, backing Vault).
 *
 * <p>Soft-depend: if Vault or an economy provider is missing, {@link #isEnabled()}
 * returns false and every money operation is a safe no-op/false — callers refuse
 * the transaction with a clear message rather than crashing. Resolution is lazy
 * so it still works if the economy provider registers after we enable.
 */
public final class EconomyService {

    private final HomeCraftManagement plugin;
    private Economy economy;
    private boolean announced = false;

    public EconomyService(HomeCraftManagement plugin) {
        this.plugin = plugin;
        resolve();
        if (economy == null) {
            plugin.getLogger().warning("No Vault economy yet — market money features are disabled until one registers.");
        }
    }

    /** Attempt to (re)acquire the economy provider; caches on success. */
    private Economy resolve() {
        if (economy != null) {
            return economy;
        }
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        RegisteredServiceProvider<Economy> rsp =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return null;
        }
        economy = rsp.getProvider();
        if (!announced) {
            announced = true;
            plugin.getLogger().info("Hooked Vault economy: " + economy.getName());
        }
        return economy;
    }

    public boolean isEnabled() {
        return resolve() != null;
    }

    public boolean has(OfflinePlayer player, double amount) {
        Economy eco = resolve();
        return eco != null && eco.has(player, amount);
    }

    /** @return true only if the withdrawal actually succeeded. */
    public boolean withdraw(OfflinePlayer player, double amount) {
        Economy eco = resolve();
        return eco != null && eco.withdrawPlayer(player, amount).transactionSuccess();
    }

    /** @return true only if the deposit actually succeeded. */
    public boolean deposit(OfflinePlayer player, double amount) {
        Economy eco = resolve();
        return eco != null && eco.depositPlayer(player, amount).transactionSuccess();
    }

    /** Format money using the economy's own formatter (falls back to 2dp). */
    public String format(double amount) {
        Economy eco = resolve();
        return eco != null ? eco.format(amount) : String.format("%.2f", amount);
    }
}
