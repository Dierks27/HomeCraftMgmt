package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.Quest;
import com.dierks.homecraft.config.PluginConfig.QuestPeriod;
import com.dierks.homecraft.config.PluginConfig.Quests;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The Quests board (Phase 11, §3.9): the player's daily and weekly objectives, each
 * with live progress toward a token reward and a "resets in …" countdown. Read-only —
 * completion pays out automatically the moment progress hits the target.
 */
public final class QuestsMenu extends Menu {

    private final Player player;
    private final Runnable back;

    /**
     * @param back where slot 49 goes — the screen that opened this one. Null when there is
     *             nothing behind it (opened by command), and then 49 closes.
     */
    public QuestsMenu(HomeCraftManagement plugin, Player player, Runnable back) {
        super(plugin);
        this.player = player;
        this.back = back;
        init(54, Text.of("&5&lQuests"));
    }

    @Override
    protected void build() {
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        Quests quests = plugin.config().quests();
        int tokens = plugin.tokens().balance(player.getUniqueId());
        set(4, Menus.icon(Material.SUNFLOWER, "&eYou have &6" + tokens + " tokens",
                "&7Finish quests to earn tokens.",
                "&7New ones every day and every week!"), null);

        if (quests == null || !quests.enabled() || quests.all().isEmpty()) {
            set(22, Menus.icon(Material.BARRIER, "&cNo quests right now",
                    "&7Check back later!"), null);
            exit();
            return;
        }

        // Each player's own draw from the pools, not the whole pool.
        renderRow(plugin.quests().assigned(player.getUniqueId(), QuestPeriod.DAILY), QuestPeriod.DAILY, 9,
                "&eToday's Quests");
        renderRow(plugin.quests().assigned(player.getUniqueId(), QuestPeriod.WEEKLY), QuestPeriod.WEEKLY, 27,
                "&bThis Week's Quests");

        // Swap a daily you don't like (a Prize Counter row, so it has a price and a daily limit).
        for (var prize : plugin.config().arcade().prizes()) {
            if (prize.type() == com.dierks.homecraft.config.PluginConfig.PrizeType.QUEST_REROLL
                    && plugin.prizes().visible(prize)) {
                String left = plugin.prizes().limitText(player.getUniqueId(), prize);
                set(22, Menus.icon(Material.WRITABLE_BOOK, "&eSwap a quest &7- &6" + prize.costTokens() + " tokens",
                        "&7Don't like one of today's quests?",
                        "&7Swap it for a different one.",
                        left != null ? "&e" + left : "&8—"),
                        e -> new QuestRerollMenu(plugin, player, prize,
                                () -> new QuestsMenu(plugin, player, back).open(player)).open(player));
                break;
            }
        }

        exit();
    }

    /** Slot 49: Back to the screen that opened this one, or Close when there is none. */
    private void exit() {
        if (back != null) {
            set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> back.run());
        } else {
            set(49, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
        }
    }

    /** One period's label (at {@code base}) followed by its quest icons across the row. */
    private void renderRow(List<Quest> quests, QuestPeriod period, int base, String label) {
        String resets = Menus.duration(plugin.quests().msToReset(period));
        set(base, Menus.icon(period == QuestPeriod.WEEKLY ? Material.CLOCK : Material.BELL,
                label, "&7Resets in &f" + resets), null);
        int slot = base + 1;
        for (Quest q : quests) {
            if (slot >= base + 9) {
                break;
            }
            set(slot++, questIcon(q), null);
        }
    }

    private org.bukkit.inventory.ItemStack questIcon(Quest q) {
        int progress = plugin.quests().progress(player.getUniqueId(), q);
        boolean done = plugin.quests().done(player.getUniqueId(), q);
        Material mat = done ? Material.LIME_DYE : Material.WRITABLE_BOOK;
        String state = done
                ? "&a✔ Complete — &6+" + q.reward() + " tokens earned"
                : "&e" + Math.max(0, q.target() - progress) + " to go";
        return Menus.icon(mat, (done ? "&a" : "&f") + q.display(),
                "&7Progress: &f" + progress + "&7/&f" + q.target(),
                bar(progress, q.target()),
                "&7Reward: &6+" + q.reward() + " token" + (q.reward() == 1 ? "" : "s"),
                "&8—", state);
    }

    /** A 10-cell text progress bar, filled green up to the completion fraction. */
    private String bar(long progress, long target) {
        int cells = 10;
        int filled = target <= 0 ? cells : (int) Math.min(cells, Math.round((double) progress / target * cells));
        StringBuilder sb = new StringBuilder("&8[");
        for (int i = 0; i < cells; i++) {
            sb.append(i < filled ? "&a|" : "&7|");
        }
        return sb.append("&8]").toString();
    }
}
