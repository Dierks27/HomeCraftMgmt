package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.CrateReward;
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
 * A single crate: its reward table with <b>published odds</b> (§3.9) and a token-priced open.
 * Opening pulls a weighted reward (cap-aware Card prizes) and shows the reveal. There is no
 * dollar "better odds" tier any more — the Arcade never takes dollars.
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
        set(46, Menus.icon(Material.SUNFLOWER, "&eYou have &6" + tokens + " tokens"), null);

        // Always a chest, glinting only when you can afford it — never faded into the filler row,
        // so the one control on the screen stays readable for exactly the player who needs it.
        boolean afford = tokens >= crate.costTokens();
        int need = crate.costTokens() - tokens;
        set(48, Menus.glint(Menus.icon(Material.CHEST,
                "&aOpen it &7- &6" + crate.costTokens() + " tokens",
                "&7The chances are shown above.", "&8—", afford
                        ? "&eClick to open"
                        : "&cNeed " + need + " more tokens"), afford),
                e -> pull());

        set(49, back(), e -> onBack.run());
    }

    private void pull() {
        var r = plugin.arcade().openCrate(player, crateId);
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
            case PRIZE, TRAIL -> {
                var ps = plugin.arcade().prizesFor(r);
                if (ps.size() == 1) {
                    var p = ps.get(0);
                    return Menus.icon(p.icon().material(), p.display()
                            + (r.type() == PluginConfig.RewardType.TRAIL ? " &7(" + r.days() + (r.days() == 1 ? " day)" : " days)") : ""),
                            odds);
                }
                List<String> lore = new ArrayList<>();
                lore.add("&7One of:");
                for (var p : ps) {
                    lore.add("&f " + p.display());
                }
                lore.add(odds);
                return Menus.icon(ps.isEmpty() ? Material.CHEST : ps.get(0).icon().material(),
                        r.type() == PluginConfig.RewardType.TRAIL
                                ? "&dA trail &7(" + r.days() + (r.days() == 1 ? " day)" : " days)") : "&dA prize",
                        lore.toArray(new String[0]));
            }
            case CARD, MINI -> {
                if (r.usesTag()) {
                    int pool = plugin.arcade().tagPool(r.tag()).size();
                    if ("*".equals(r.tag())) {
                        return Menus.glint(Menus.icon(Material.NETHER_STAR, "&d&lAny Mini's Card!",
                                "&7The big prize: a Card for any Mini.",
                                "&7Rarer Minis are harder to get.",
                                "&7Minis you can still get: &f" + pool, odds), true);
                    }
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
        return plugin.arcade().droppable(r);
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
