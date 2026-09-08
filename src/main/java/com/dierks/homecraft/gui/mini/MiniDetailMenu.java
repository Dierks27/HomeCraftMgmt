package com.dierks.homecraft.gui.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.storage.MiniDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The Museum's browse-only detail card for one Mini type: the appraisal preview
 * (minted/cap, circulation, Standard→Mint value range), who currently holds live
 * copies, and where a copy can come from. No minting or buying here — the Printer
 * (from a Card), wild drops, natural spawns and crates are the only mint paths.
 */
public final class MiniDetailMenu extends Menu {

    private final Player player;
    private final MiniDef def;
    private final Runnable onBack;

    public MiniDetailMenu(HomeCraftManagement plugin, Player player, MiniDef def, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.def = def;
        this.onBack = onBack;
        init(27, Text.of("&5" + def.name()));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }
        set(13, plugin.miniService().icon(def), null);

        List<String> owners = new ArrayList<>();
        owners.add("&7Live copies and who holds them:");
        List<MiniDao.OwnerCount> holders = plugin.miniService().owners(def.id(), 10);
        if (holders.isEmpty()) {
            owners.add("&8Nobody yet");
        } else {
            for (MiniDao.OwnerCount oc : holders) {
                String name = Bukkit.getOfflinePlayer(oc.owner()).getName();
                owners.add("&f" + (name != null ? name : oc.owner().toString().substring(0, 8))
                        + " &7× " + oc.count());
            }
        }
        set(11, Menus.icon(Material.BOOK, "&eWho owns it", owners.toArray(new String[0])), null);

        List<String> how = new ArrayList<>();
        how.add("&7Print its Card at a &bPrinter&7.");
        if (!def.tags().isEmpty()) {
            how.add("&7Drops in the wild from: &f" + String.join(", ", def.tags()));
        }
        how.add("&7Arcade crates and natural spawns");
        how.add("&7can also turn one up.");
        how.add("&8The Museum is for browsing only.");
        set(15, Menus.icon(Material.PAPER, "&eHow to get one", how.toArray(new String[0])), null);

        set(22, Menus.icon(Material.BARRIER, onBack != null ? "&cBack" : "&cClose"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }
}
