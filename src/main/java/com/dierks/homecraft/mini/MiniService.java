package com.dierks.homecraft.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.integration.EconomyService;
import com.dierks.homecraft.storage.MiniDao;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Minis engine: owns the config-driven catalog and is the single source of
 * truth for minting. Every copy — bought, admin-given, or (later) crafted/dropped
 * — flows through {@link #mint}/{@link #giveAdmin} so mint counts and per-copy
 * provenance stay honest and caps are enforced.
 */
public final class MiniService {

    public record MintResult(boolean ok, String error, long mintNumber) {
        static MintResult fail(String error) {
            return new MintResult(false, error, 0);
        }
    }

    private final HomeCraftManagement plugin;
    private final MiniDao dao;
    private final EconomyService economy;
    private final MiniItems items = new MiniItems();

    private Map<String, MiniDef> catalog = new LinkedHashMap<>();

    public MiniService(HomeCraftManagement plugin, MiniDao dao, EconomyService economy) {
        this.plugin = plugin;
        this.dao = dao;
        this.economy = economy;
    }

    /** (Re)build the catalog from config. Mint tallies live in the DB and are untouched. */
    public void reload() {
        Map<String, MiniDef> next = new LinkedHashMap<>();
        for (MiniDef def : plugin.config().minis().catalog()) {
            next.put(def.id(), def);
        }
        this.catalog = next;
        plugin.getLogger().info("Minis catalog loaded " + catalog.size() + " entry(ies).");
    }

    public Collection<MiniDef> catalog() {
        return catalog.values();
    }

    public MiniDef def(String id) {
        return catalog.get(id);
    }

    public RarityStyle style(Rarity rarity) {
        return plugin.config().minis().style(rarity);
    }

    public MiniDao.Counts counts(String id) {
        try {
            return dao.counts(id);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read Mini counts for " + id + ": " + e.getMessage());
            return new MiniDao.Counts(0, 0);
        }
    }

    /** Museum display icon (cosmetic; shows live minted/cap + circulation). */
    public ItemStack icon(MiniDef def) {
        MiniDao.Counts c = counts(def.id());
        return items.preview(def, style(def.rarity()), c.minted(), c.circulation());
    }

    /** Buy + mint a Mini for a player (charges the price via Vault, enforces the cap). */
    public MintResult mint(Player player, String id) {
        MiniDef def = catalog.get(id);
        if (def == null) {
            return MintResult.fail("No such Mini '" + id + "'.");
        }
        MiniDao.Counts c = counts(id);
        if (!def.uncapped() && c.minted() >= def.cap()) {
            return MintResult.fail(def.name() + " is minted out (" + def.cap() + "/" + def.cap() + ").");
        }
        if (!economy.isEnabled()) {
            return MintResult.fail("The economy is offline (no Vault).");
        }
        if (def.price() > 0) {
            if (!economy.has(player, def.price())) {
                return MintResult.fail("You can't afford " + economy.format(def.price()) + ".");
            }
            if (!economy.withdraw(player, def.price())) {
                return MintResult.fail("Payment failed.");
            }
        }
        return mintInternal(player, def);
    }

    /** Admin: mint + give a Mini with no charge and no cap check. */
    public MintResult giveAdmin(Player target, String id) {
        MiniDef def = catalog.get(id);
        if (def == null) {
            return MintResult.fail("No such Mini '" + id + "'.");
        }
        return mintInternal(target, def);
    }

    private MintResult mintInternal(Player target, MiniDef def) {
        try {
            long mintNumber = dao.mintNext(def.id());
            UUID uid = UUID.randomUUID();
            ItemStack item = items.minted(def, style(def.rarity()), mintNumber, uid);
            target.getInventory().addItem(item).values()
                    .forEach(drop -> target.getWorld().dropItemNaturally(target.getLocation(), drop));
            dao.recordIndividual(uid, def.id(), mintNumber, target.getUniqueId(), System.currentTimeMillis());
            return new MintResult(true, null, mintNumber);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to mint Mini " + def.id() + ": " + e.getMessage());
            return MintResult.fail("Minting failed — try again.");
        }
    }
}
