package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.CrateReward;
import com.dierks.homecraft.config.PluginConfig.PaidTier;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A single crate: its reward table with <b>published odds</b> (§3.9), plus a free
 * token-priced open and any paid-odds fee tiers that guarantee a rarity floor.
 * Opening pulls a weighted reward (cap-aware Mini prizes) and shows the reveal.
 */
public final class CrateMenu extends Menu {

    private final Player player;
    private final String crateId;
    private final Runnable onBack;

    public CrateMenu(HomeCraftManagement plugin, Player player, String crateId, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.crateId = crateId;
        this.onBack = onBack;
        init(54, Text.of("&5Crate"));
    }

    @Override
    protected void build() {
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        PluginConfig.Crate crate = plugin.arcade().crate(crateId);
        if (crate == null) {
            set(22, Menus.icon(Material.BARRIER, "&cCrate not found"), null);
            set(49, back(), e -> onBack.run());
            return;
        }

        // Only show rewards that resolve; an unresolved Mini id is skipped (logged
        // by the service) so a bad reference never breaks the machine. Published
        // odds are computed over the rewards that can actually drop right now.
        List<CrateReward> shown = new ArrayList<>();
        double dropTotal = 0;
        for (CrateReward r : crate.rewards()) {
            if (r.type() == PluginConfig.RewardType.MINI && plugin.miniService().def(r.miniId()) == null) {
                continue; // unresolved reference — skip
            }
            shown.add(r);
            if (isDroppable(r)) {
                dropTotal += r.weight();
            }
        }

        int slot = 0;
        for (CrateReward r : shown) {
            if (slot > 26) {
                break;
            }
            double pct = (isDroppable(r) && dropTotal > 0) ? r.weight() / dropTotal * 100.0 : 0;
            set(slot++, rewardIcon(r, pct), null);
        }

        int tokens = plugin.arcade().balance(player.getUniqueId());
        set(45, Menus.icon(Material.SUNFLOWER, "&eYour Tokens: &6" + tokens), null);

        // Free (token) open.
        set(48, Menus.icon(Material.CHEST,
                "&aOpen — &6" + crate.costTokens() + " token" + (crate.costTokens() == 1 ? "" : "s"),
                "&7Standard odds shown above.", "&8—", "&eClick to open"),
                e -> pull(null));

        // Paid-odds tiers (buy a guaranteed rarity floor with Vault money).
        int tierSlot = 50;
        List<PaidTier> tiers = crate.paidTiers();
        for (PaidTier tier : tiers) {
            if (tierSlot > 52) {
                break;
            }
            set(tierSlot++, Menus.icon(Material.GOLD_INGOT,
                    "&6Better odds — " + plugin.economy().format(tier.costMoney()),
                    "&7Guarantees a &d" + tier.floor() + "+ &7Mini.",
                    "&8Money sink — burned.", "&8—", "&eClick to open"),
                    e -> pull(tier));
        }

        set(53, back(), e -> onBack.run());
    }

    private void pull(PaidTier tier) {
        var r = plugin.arcade().openCrate(player, crateId, tier);
        if (r.ok()) {
            new RevealMenu(plugin, player, r, () -> new CrateMenu(plugin, player, crateId, onBack).open(player))
                    .open(player);
        } else {
            player.sendMessage(Text.of("&c" + r.error()));
            refresh();
        }
    }

    private ItemStack rewardIcon(CrateReward r, double pct) {
        String odds = "&7Chance: &f" + String.format(java.util.Locale.ROOT, "%.1f%%", pct);
        switch (r.type()) {
            case MONEY -> {
                return Menus.icon(Material.GOLD_INGOT, "&6" + plugin.economy().format(r.amount()),
                        "&7Cash reward", odds);
            }
            case ITEM -> {
                return Menus.icon(r.material(), "&f" + r.itemAmount() + "x " + niceName(r.material()),
                        "&7Item reward", odds);
            }
            case MINI -> {
                MiniDef def = plugin.miniService().def(r.miniId());
                boolean out = def != null && !def.uncapped()
                        && plugin.miniService().counts(def.id()).minted() >= def.cap();
                if (def == null) {
                    return Menus.icon(Material.PLAYER_HEAD, "&dMini &7(" + r.miniId() + ")", odds);
                }
                ItemStack ic = plugin.miniService().icon(def);
                var meta = ic.getItemMeta();
                if (meta != null) {
                    java.util.List<net.kyori.adventure.text.Component> lore = meta.hasLore()
                            ? new java.util.ArrayList<>(meta.lore()) : new java.util.ArrayList<>();
                    lore.add(Text.of("&7Rarity: &f" + def.rarity()));
                    lore.add(Text.of(odds));
                    if (out) {
                        lore.add(Text.of("&cMinted out — won't drop"));
                    }
                    meta.lore(lore);
                    ic.setItemMeta(meta);
                }
                return ic;
            }
            default -> {
                return Menus.icon(Material.BARRIER, "&cUnknown reward");
            }
        }
    }

    private boolean isDroppable(CrateReward r) {
        if (r.type() != PluginConfig.RewardType.MINI) {
            return true;
        }
        MiniDef def = plugin.miniService().def(r.miniId());
        if (def == null) {
            return false;
        }
        return def.uncapped() || plugin.miniService().counts(def.id()).minted() < def.cap();
    }

    private ItemStack back() {
        return Menus.icon(Material.ARROW, "&cBack to Arcade");
    }

    private String niceName(Material material) {
        String n = material.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
