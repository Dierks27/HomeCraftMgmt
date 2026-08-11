package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.Loot;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Build a Wild-Drop source entirely by clicking: pick the trigger, pick the block
 * or mob from a visual picker, cycle to a loot list, and set the chance with
 * click steppers (down to tiny values). No syntax typing.
 */
public final class SourceCreateMenu extends Menu {

    private static final double MIN_CHANCE = 0.00000001; // 0.00000001%
    private static final double MAX_CHANCE = 100.0;

    private final Player player;
    private final Runnable onBack;

    private Loot.Trigger trigger = Loot.Trigger.BLOCK_BREAK;
    private String match = "";
    private String listId;
    private double chance = 1.0; // percent

    public SourceCreateMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        // Default the list to the first available.
        List<Loot.LootList> lists = plugin.miniService().loot().lists();
        if (!lists.isEmpty()) {
            this.listId = lists.get(0).id();
        }
        init(45, Text.of("&5New Drop Source"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 45; i++) {
            set(i, Menus.FILLER, null);
        }

        set(10, triggerIcon(Loot.Trigger.BLOCK_BREAK, Material.IRON_PICKAXE, "Block-break"),
                e -> setTrigger(Loot.Trigger.BLOCK_BREAK));
        set(11, triggerIcon(Loot.Trigger.MOB_KILL, Material.IRON_SWORD, "Mob-kill"),
                e -> setTrigger(Loot.Trigger.MOB_KILL));
        set(12, triggerIcon(Loot.Trigger.FISHING, Material.FISHING_ROD, "Fishing"),
                e -> setTrigger(Loot.Trigger.FISHING));

        // Target picker (n/a for fishing).
        if (trigger == Loot.Trigger.FISHING) {
            set(14, Menus.icon(Material.WATER_BUCKET, "&eTarget: any catch", "&8Fishing has no specific target"), null);
        } else {
            boolean isBlock = trigger == Loot.Trigger.BLOCK_BREAK;
            set(14, Menus.icon(isBlock ? Material.GRASS_BLOCK : Material.ZOMBIE_HEAD,
                    "&eTarget: &f" + (match.isBlank() ? "click to pick" : match),
                    "&7Click to choose a " + (isBlock ? "block" : "mob")),
                    e -> new TargetPickerMenu(plugin, player, isBlock, picked -> {
                        match = picked;
                        open(player);
                    }, () -> open(player)).open(player));
        }

        // Loot list (cycle).
        List<Loot.LootList> lists = plugin.miniService().loot().lists();
        set(16, Menus.icon(Material.CHEST, "&eLoot list: &f" + (listId == null ? "none" : listId),
                lists.isEmpty() ? "&cCreate a list first" : "&7Click to cycle"),
                e -> cycleList());

        // Chance steppers.
        set(19, Menus.icon(Material.RED_STAINED_GLASS_PANE, "&c÷10"), e -> setChance(chance / 10));
        set(20, Menus.icon(Material.RED_STAINED_GLASS_PANE, "&c-0.01"), e -> setChance(chance - 0.01));
        set(21, Menus.icon(Material.RED_STAINED_GLASS_PANE, "&c-0.0001"), e -> setChance(chance - 0.0001));
        set(22, Menus.icon(Material.PAPER, "&eChance: &f" + plain(chance) + "%",
                "&8= " + plain(chance / 100.0) + " probability"), null);
        set(23, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a+0.0001"), e -> setChance(chance + 0.0001));
        set(24, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a+0.01"), e -> setChance(chance + 0.01));
        set(25, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a×10"), e -> setChance(chance * 10));

        set(39, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a&l✓ Create source",
                "&7trigger + target → list @ chance"), e -> create());
        set(36, Menus.icon(Material.ARROW, "&cBack"), e -> back());
    }

    private void setTrigger(Loot.Trigger t) {
        trigger = t;
        if (t == Loot.Trigger.FISHING) {
            match = "*";
        } else if (match.equals("*")) {
            match = "";
        }
        refresh();
    }

    private void cycleList() {
        List<Loot.LootList> lists = plugin.miniService().loot().lists();
        if (lists.isEmpty()) {
            return;
        }
        int idx = -1;
        for (int i = 0; i < lists.size(); i++) {
            if (lists.get(i).id().equals(listId)) {
                idx = i;
                break;
            }
        }
        listId = lists.get((idx + 1) % lists.size()).id();
        refresh();
    }

    private void setChance(double value) {
        chance = Math.max(MIN_CHANCE, Math.min(MAX_CHANCE, value));
        refresh();
    }

    private void create() {
        if (listId == null) {
            player.sendMessage(Text.of("&cCreate and pick a loot list first."));
            return;
        }
        if (trigger != Loot.Trigger.FISHING && match.isBlank()) {
            player.sendMessage(Text.of("&cPick a target block/mob first."));
            return;
        }
        Loot.MiniLoot loot = plugin.miniService().loot();
        List<Loot.LootSource> sources = new ArrayList<>(loot.sources());
        sources.add(new Loot.LootSource(trigger, trigger == Loot.Trigger.FISHING ? "*" : match, listId, chance));
        plugin.miniService().saveLoot(new Loot.MiniLoot(loot.lists(), sources));
        player.sendMessage(Text.of("&aSource created."));
        back();
    }

    private org.bukkit.inventory.ItemStack triggerIcon(Loot.Trigger t, Material mat, String label) {
        boolean on = trigger == t;
        return Menus.icon(mat, (on ? "&a» " : "&7") + label, on ? "&8selected" : "&7Click to select");
    }

    private String plain(double v) {
        return new BigDecimal(Double.toString(v)).stripTrailingZeros().toPlainString();
    }

    private void back() {
        if (onBack != null) {
            onBack.run();
        } else {
            player.closeInventory();
        }
    }
}
