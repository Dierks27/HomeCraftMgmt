package com.dierks.homecraft.gui.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * The pack-opening reveal (Phase 10, Part E): the Cards you just pulled flip face-up
 * one at a time for the booster-pack thrill. The Cards are already in your inventory
 * (issued when the pack was bought) — this is the cosmetic reveal.
 */
public final class PackRevealMenu extends Menu {

    /** Centered reveal slots in the middle row (up to 9 shown; extras still go to inventory). */
    private static final int[] SLOTS = {9, 10, 11, 12, 13, 14, 15, 16, 17};

    private final Player player;
    private final String packName;
    private final List<String> cardIds;
    private final Runnable onBack;

    private int revealed = 0;
    private BukkitTask task;

    public PackRevealMenu(HomeCraftManagement plugin, Player player, String packName,
                          List<String> cardIds, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.packName = packName;
        this.cardIds = cardIds;
        this.onBack = onBack;
        init(27, Text.of("&5Opening: &f" + packName));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }
        int shown = Math.min(cardIds.size(), SLOTS.length);
        for (int i = 0; i < shown; i++) {
            set(SLOTS[i], Menus.icon(Material.GRAY_STAINED_GLASS_PANE, "&7? ? ?",
                    "&8A mystery Card…"), null);
        }
        if (cardIds.size() > SLOTS.length) {
            set(26, Menus.icon(Material.CHEST, "&7+" + (cardIds.size() - SLOTS.length) + " more",
                    "&8In your inventory."), null);
        }
        set(22, Menus.icon(Material.ARROW, "&aCollect"), e -> {
            if (task != null) {
                task.cancel();
            }
            revealAllNow();
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
        startReveal();
    }

    private void startReveal() {
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int shown = Math.min(cardIds.size(), SLOTS.length);
            if (revealed >= shown) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
                if (task != null) {
                    task.cancel();
                }
                return;
            }
            int i = revealed++;
            getInventory().setItem(SLOTS[i], cardIcon(cardIds.get(i)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING,
                    0.6f, 0.8f + 0.15f * i);
        }, 10L, 10L);
    }

    /** Flip everything face-up immediately (on Collect, so nothing is left hidden). */
    private void revealAllNow() {
        int shown = Math.min(cardIds.size(), SLOTS.length);
        for (int i = revealed; i < shown; i++) {
            getInventory().setItem(SLOTS[i], cardIcon(cardIds.get(i)));
        }
        revealed = shown;
    }

    private ItemStack cardIcon(String miniId) {
        ItemStack card = plugin.miniService().cardFor(miniId);
        return card != null ? card : Menus.icon(Material.PAPER, "&bCard");
    }
}
