package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Quest Reroll: pick one of today's unfinished dailies to swap for a quest you were not dealt.
 * Charged only when the swap happens; if there is nothing to swap to, nothing is taken.
 */
public final class QuestRerollMenu extends Menu {

    private final Player player;
    private final PluginConfig.Prize prize;
    private final Runnable back;

    public QuestRerollMenu(HomeCraftManagement plugin, Player player, PluginConfig.Prize prize, Runnable back) {
        super(plugin);
        this.player = player;
        this.prize = prize;
        this.back = back;
        init(54, Text.of("&5Swap a Quest"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        int tokens = plugin.tokens().balance(player.getUniqueId());
        String left = plugin.prizes().limitText(player.getUniqueId(), prize);
        set(4, Menus.icon(Material.WRITABLE_BOOK, "&eSwap a Quest &7· &6" + prize.costTokens() + " tokens",
                "&7Pick a daily quest you don't like.",
                "&7You'll get a different one.",
                "&7You have &6" + tokens + " tokens&7.",
                left != null ? "&e" + left : "&8—"), null);
        List<PluginConfig.Quest> quests = plugin.quests().rerollable(player);
        if (quests.isEmpty()) {
            set(22, Menus.icon(Material.LIME_DYE, "&aNothing to swap",
                    "&7Your dailies are all done!"), null);
        }
        int slot = 20;
        for (PluginConfig.Quest q : quests) {
            if (slot > 24) {
                break;
            }
            set(slot, Menus.icon(Material.PAPER, "&f" + q.display(),
                    "&7Progress: &f" + plugin.quests().progress(player.getUniqueId(), q) + "&7/&f" + q.target(),
                    "&8—", "&eClick to swap this one"), e -> swap(q));
            slot += 2;
        }
        set(49, Menus.icon(Material.BARRIER, back != null ? "&cBack" : "&cClose"), e -> {
            if (back != null) {
                back.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    private void swap(PluginConfig.Quest q) {
        var r = plugin.prizes().rerollQuest(player, prize, q.id());
        if (!r.ok()) {
            player.sendMessage(Text.of("&c" + r.error()));
            Sounds.refused(player);
            refresh();
            return;
        }
        player.sendMessage(Text.of("&a✔ New quest: " + r.label()));
        Sounds.paid(player);
        if (back != null) {
            back.run();
        } else {
            refresh();
        }
    }
}
