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

        int tokens = plugin.tokens().balance(player.getUniqueId());
        set(46, Menus.icon(Material.SUNFLOWER, "&eYour Tokens: &6" + tokens), null);

        // Free (token) open — no glint and a "need N more" note when unaffordable, but still
        // clickable (the click surfaces the exact shortfall message).
        boolean afford = tokens >= crate.costTokens();
        int need = crate.costTokens() - tokens;
        // Always a chest, glinting only when you can afford it. It used to degrade to the filler
        // pane — inside the row this menu fills with that same pane — so the one control on the
        // screen vanished for exactly the player who needed to read why.
        set(48, Menus.glint(Menus.icon(Material.CHEST,
                "&aOpen — &6" + crate.costTokens() + " token" + (crate.costTokens() == 1 ? "" : "s"),
                "&7Standard odds shown above.", "&8—", afford
                        ? "&eClick to open"
                        : "&c✖ Need " + need + " more token" + (need == 1 ? "" : "s")), afford),
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
                    "&7Always gives " + tier.floor().article() + " "
                            + plugin.miniService().rarityFloorText(tier.floor()) + " &7Card.",
                    "&8—", "&eClick to open"),
                    e -> pull(tier));
        }

        set(49, back(), e -> onBack.run());
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
            case PACK -> {
                var def = plugin.packs().pack(r.packId());
                return Menus.icon(Material.PAPER, "&d" + (def != null ? def.displayName() : r.packId()),
                        "&7A sealed Card Pack", odds);
            }
            case FILAMENT -> {
                // A random-colour filament gets a neutral icon, not a white dye — on a published
                // odds list, "Random Filament" must not look like the white one.
                return Menus.icon(r.color() != null
                                ? plugin.miniService().filamentItems().baseMaterial(r.color()) : Material.NETHER_STAR,
                        "&f" + r.amount() + "x " + (r.color() != null ? niceName(r.color()) + " " : "Random ") + "Filament",
                        "&7Printer filament", odds);
            }
            case TOKENS -> {
                return Menus.icon(Material.SUNFLOWER, "&e+" + r.amount() + " token" + (r.amount() == 1 ? "" : "s"),
                        "&7Arcade tokens", odds);
            }
            case CARD, MINI -> {
                if (r.usesTag()) {
                    int pool = plugin.cards().issuable(plugin.miniService().poolFromTag(r.tag())).size();
                    return Menus.icon(Material.PLAYER_HEAD, "&bRandom Card",
                            "&7A Card for one of the", "&f" + niceName(r.tag()) + " &7Minis.",
                            "&7Rarer Minis are harder to get.",
                            "&7Minis you can still get: &f" + pool, odds);
                }
                MiniDef def = plugin.miniService().def(r.miniId());
                boolean out = def != null && !plugin.cards().canIssue(def);
                if (def == null) {
                    return Menus.icon(Material.PLAYER_HEAD, "&bCard", odds);
                }
                ItemStack ic = plugin.miniService().cardFor(def.id());
                if (ic == null) {
                    ic = plugin.miniService().icon(def);
                }
                var meta = ic.getItemMeta();
                if (meta != null) {
                    java.util.List<net.kyori.adventure.text.Component> lore = meta.hasLore()
                            ? new java.util.ArrayList<>(meta.lore()) : new java.util.ArrayList<>();
                    lore.add(Text.of("&7Rarity: " + plugin.miniService().rarityText(def.rarity())));
                    lore.add(Text.of(odds));
                    if (out) {
                        lore.add(Text.of("&cAll gone — can't drop"));
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
        if (r.type() != PluginConfig.RewardType.MINI && r.type() != PluginConfig.RewardType.CARD) {
            return true;
        }
        if (r.usesTag()) {
            return !plugin.cards().issuable(plugin.miniService().poolFromTag(r.tag())).isEmpty();
        }
        return plugin.cards().canIssue(plugin.miniService().def(r.miniId()));
    }

    private ItemStack back() {
        return Menus.icon(Material.BARRIER, "&cBack to Arcade");
    }

    private String niceName(org.bukkit.DyeColor color) {
        String n = color.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** "starter" → "Starter", "wild_west" → "Wild west". */
    private String niceName(String key) {
        String n = key.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return n.isEmpty() ? n : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
