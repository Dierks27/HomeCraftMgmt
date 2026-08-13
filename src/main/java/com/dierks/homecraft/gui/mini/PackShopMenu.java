package com.dierks.homecraft.gui.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.Pack;
import com.dierks.homecraft.mini.PackService;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The Card Pack shop (Phase 10, Part E): buy a booster pack → open N random Cards in
 * a reveal GUI. Packs are a repeatable money sink; the Cards they give respect card
 * caps and are printed into Minis later at a Printer (packs never contain Minis).
 */
public final class PackShopMenu extends Menu {

    private final Player player;
    private final Runnable onBack;

    public PackShopMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of("&6Card Packs"));
    }

    @Override
    protected void build() {
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        List<Pack.PackDef> packs = plugin.packs().packs();
        if (packs.isEmpty()) {
            set(22, Menus.icon(Material.BARRIER, "&7No packs available",
                    "&8An admin can build packs with &f/hcm packs&8."), null);
        }
        int slot = 0;
        for (Pack.PackDef p : packs) {
            if (slot >= 45) {
                break;
            }
            set(slot++, packIcon(p), e -> buy(p));
        }

        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
        set(50, Menus.balance(plugin, player), null);
    }

    private org.bukkit.inventory.ItemStack packIcon(Pack.PackDef p) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Contains &f" + p.cardCount() + " &7random Card" + (p.cardCount() == 1 ? "" : "s") + ".");
        lore.add("&7Price: &6" + plugin.economy().format(p.price()));
        lore.add("");
        if (!p.isValid()) {
            lore.add("&cThis pack has no cards yet.");
        } else {
            lore.add("&eClick to buy & open");
        }
        return Menus.icon(Material.PAPER, "&b" + p.displayName(), lore.toArray(new String[0]));
    }

    private void buy(Pack.PackDef p) {
        PackService.OpenResult r = plugin.packs().open(player, p.id());
        if (!r.ok()) {
            player.sendMessage(Text.of("&c" + r.error()));
            refresh();
            return;
        }
        new PackRevealMenu(plugin, player, p.displayName(), r.cardIds(), this::reopen).open(player);
    }

    private void reopen() {
        new PackShopMenu(plugin, player, onBack).open(player);
    }
}
