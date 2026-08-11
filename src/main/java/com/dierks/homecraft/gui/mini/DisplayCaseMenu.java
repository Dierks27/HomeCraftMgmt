package com.dierks.homecraft.gui.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.storage.MiniListingDao;
import com.dierks.homecraft.trade.VendingService;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Mini Display Case GUI: the owner loads a Mini to show off as a trophy (no
 * sale); anyone can view its provenance. Owner-only load/reclaim.
 */
public final class DisplayCaseMenu extends Menu {

    private final Player player;
    private final Location loc;
    private final boolean blockOwner;

    public DisplayCaseMenu(HomeCraftManagement plugin, Player player, Location loc, boolean blockOwner) {
        super(plugin);
        this.player = player;
        this.loc = loc;
        this.blockOwner = blockOwner;
        init(27, Text.of("&bMini Display Case"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }
        Optional<MiniListingDao.Listing> opt = plugin.vending().at(loc);

        if (opt.isEmpty()) {
            if (blockOwner) {
                set(13, Menus.icon(Material.NAME_TAG, "&eDisplay a Mini",
                        "&7Hold the Mini in your main hand,",
                        "&7then click to show it off."), e -> {
                    report(plugin.vending().load(player, loc, VendingService.DISPLAY, 0));
                    com.dierks.homecraft.trade.DisplayRender.apply(plugin, loc);
                    reopen();
                });
            } else {
                set(13, Menus.icon(Material.BARRIER, "&7Empty display case"), null);
            }
        } else {
            MiniListingDao.Listing listing = opt.get();
            set(13, decorate(Items.fromBase64(listing.itemB64()), listing), null);
            set(11, Menus.icon(Material.BOOK, "&5Info card", "&7View this Mini's details"),
                    e -> new MiniInfoMenu(plugin, player,
                            new com.dierks.homecraft.mini.MiniService.MiniRef(
                                    listing.uid(), listing.miniId(), listing.mintNumber()),
                            Items.fromBase64(listing.itemB64()),
                            () -> new DisplayCaseMenu(plugin, player, loc, blockOwner).open(player)).open(player));
            if (listing.owner().equals(player.getUniqueId()) || player.hasPermission("hcm.admin")) {
                set(15, Menus.icon(Material.CHEST, "&aTake it back"), e -> {
                    report(plugin.vending().reclaim(player, loc));
                    com.dierks.homecraft.trade.DisplayRender.apply(plugin, loc);
                    reopen();
                });
            }
        }
        set(22, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
    }

    private ItemStack decorate(ItemStack mini, MiniListingDao.Listing listing) {
        if (mini == null) {
            return Menus.icon(Material.BARRIER, "&cUnreadable Mini");
        }
        ItemStack icon = mini.clone();
        var meta = icon.getItemMeta();
        if (meta != null) {
            List<net.kyori.adventure.text.Component> lore = meta.hasLore()
                    ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            MiniDef def = plugin.miniService().def(listing.miniId());
            lore.add(Text.of("&8—"));
            if (def != null) {
                lore.add(Text.of("&7Series: &f" + def.series()));
                lore.add(Text.of("&7Rarity: &f" + def.rarity().name()));
                lore.add(Text.of("&7Mint #&f" + listing.mintNumber()
                        + (def.uncapped() ? "" : " &7of &f" + def.cap())));
            }
            lore.add(Text.of("&8On display — not for sale"));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private void report(VendingService.Result r) {
        player.sendMessage(r.ok() ? Text.of("&aDone.") : Text.of("&c" + r.error()));
    }

    private void reopen() {
        new DisplayCaseMenu(plugin, player, loc, blockOwner).open(player);
    }
}
