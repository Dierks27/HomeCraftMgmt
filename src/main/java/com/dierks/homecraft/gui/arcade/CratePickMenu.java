package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The Crate Machine's front screen: pick which loot crate to open. Opened by
 * right-clicking a placed Crate Machine (Phase 9). If only one crate is configured
 * the machine jumps straight into it.
 */
public final class CratePickMenu extends Menu {

    private final Player player;

    public CratePickMenu(HomeCraftManagement plugin, Player player) {
        super(plugin);
        this.player = player;
        init(27, Text.of("&6Crate Machine"));
    }

    /** Open the machine — straight into the sole crate, or the picker when there are several. */
    public static void open(HomeCraftManagement plugin, Player player) {
        List<String> ids = new ArrayList<>(plugin.config().arcade().crates().keySet());
        if (ids.size() == 1) {
            new CrateMenu(plugin, player, ids.get(0),
                    () -> new CratePickMenu(plugin, player).open(player)).open(player);
        } else {
            new CratePickMenu(plugin, player).open(player);
        }
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, i >= 18 ? Menus.FILLER : null, null);
        }
        int tokens = plugin.arcade().balance(player.getUniqueId());
        set(4, Menus.icon(Material.SUNFLOWER, "&eYour Tokens: &6" + tokens), null);

        int slot = 9;
        List<String> ids = new ArrayList<>(plugin.config().arcade().crates().keySet());
        if (ids.isEmpty()) {
            set(13, Menus.icon(Material.BARRIER, "&cNo crates configured"), null);
        }
        for (String id : ids) {
            if (slot > 17) {
                break;
            }
            PluginConfig.Crate crate = plugin.config().arcade().crates().get(id);
            boolean afford = tokens >= crate.costTokens();
            int need = crate.costTokens() - tokens;
            // Crates always render (never hidden). When unaffordable they're greyed with
            // a clear "need N more tokens" note, but stay clickable so odds are viewable.
            set(slot++, Menus.icon(afford ? Material.CHEST : Material.GRAY_STAINED_GLASS_PANE,
                    crate.display(),
                    "&7Cost: &6" + crate.costTokens() + " token" + (crate.costTokens() == 1 ? "" : "s"),
                    "&8—", afford
                            ? "&eClick to view odds & open"
                            : "&c✖ Need " + need + " more token" + (need == 1 ? "" : "s")),
                    e -> new CrateMenu(plugin, player, id,
                            () -> new CratePickMenu(plugin, player).open(player)).open(player));
        }
        set(22, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
    }
}
