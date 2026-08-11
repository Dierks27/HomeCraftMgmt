package com.dierks.homecraft.gui.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.storage.MiniDao;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A read-only Mini info card (§3.4): the rendered head, name, series, rarity (in
 * its colour), Mint #N / cap, circulation, provenance (mint date + owner trail),
 * and current value (last sale, else market/mint price). Public — anyone can
 * inspect a held or placed Mini.
 */
public final class MiniInfoMenu extends Menu {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private final Player player;
    private final MiniService.MiniRef ref;
    private final ItemStack display;
    private final Runnable onBack;

    public MiniInfoMenu(HomeCraftManagement plugin, Player player, MiniService.MiniRef ref,
                        ItemStack display, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.ref = ref;
        this.display = display;
        this.onBack = onBack;
        init(27, Text.of("&5Mini Info Card"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }
        set(13, card(), null);
        set(22, Menus.icon(Material.BARRIER, onBack != null ? "&cBack" : "&cClose"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    private ItemStack card() {
        MiniService minis = plugin.miniService();
        MiniDef def = minis.def(ref.miniId());
        MiniDao.Counts counts = minis.counts(ref.miniId());
        Optional<MiniDao.Individual> ind = minis.individual(ref.uid());
        List<MiniDao.Sale> sales = minis.salesForUid(ref.uid());

        ItemStack icon = display != null ? display.clone() : new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) {
            return icon;
        }

        List<Component> lore = new ArrayList<>();
        if (def != null) {
            meta.displayName(Component.text(def.name(), minis.style(def.rarity()).nameColor())
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(grey("Type: ", def.category()));
            lore.add(grey("Series: ", def.series()));
            lore.add(Component.text("Rarity: ", NamedTextColor.GRAY)
                    .append(Component.text(def.rarity().name(), minis.style(def.rarity()).nameColor()))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(grey("Mint #", ref.mintNumber() + (def.uncapped() ? "" : " of " + def.cap())));
            lore.add(grey("In circulation: ", Long.toString(counts.circulation())));
        } else {
            lore.add(grey("Mini id: ", ref.miniId()));
            lore.add(grey("Mint #", Long.toString(ref.mintNumber())));
        }

        lore.add(Component.empty());
        lore.add(Component.text("Provenance", NamedTextColor.DARK_PURPLE).decoration(TextDecoration.ITALIC, false));
        ind.ifPresent(i -> {
            lore.add(grey("Minted: ", DATE.format(Instant.ofEpochMilli(i.mintedAt()))));
            lore.add(grey("Owner: ", name(i.owner())));
        });
        if (sales.isEmpty()) {
            lore.add(grey("Sales: ", "none yet"));
        } else {
            int shown = Math.min(sales.size(), 4);
            for (int k = sales.size() - shown; k < sales.size(); k++) {
                MiniDao.Sale s = sales.get(k);
                lore.add(Component.text("  " + DATE.format(Instant.ofEpochMilli(s.soldAt())) + ": "
                        + name(s.seller()) + " -> " + name(s.buyer()) + " ("
                        + plugin.economy().format(s.price()) + ")", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }

        lore.add(Component.empty());
        lore.add(Component.text("Current value: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.economy().format(value(def, sales)), NamedTextColor.GOLD))
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    /** Value = last sale of this copy, else last sale of the type, else mint price. */
    private double value(MiniDef def, List<MiniDao.Sale> sales) {
        if (!sales.isEmpty()) {
            return sales.get(sales.size() - 1).price();
        }
        Double last = plugin.miniService().lastSalePrice(ref.miniId());
        if (last != null) {
            return last;
        }
        return def != null ? def.price() : 0;
    }

    private Component grey(String label, String value) {
        return Component.text(label, NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false);
    }

    private String name(java.util.UUID id) {
        if (id == null) {
            return "—";
        }
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n != null ? n : id.toString().substring(0, 8);
    }
}
