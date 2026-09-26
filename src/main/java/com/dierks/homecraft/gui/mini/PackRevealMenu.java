package com.dierks.homecraft.gui.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.Rarity;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * The pack-opening reveal. The Cards are already in the player's inventory (issued when the pack
 * was opened); this screen is only the show, so closing it early loses nothing.
 *
 * <p>One Card builds up: a gray "?" shimmers, the frame around it lights in the Card's rarity
 * colour, then the Card itself appears — the colour is the "it's a Rare!" moment before you see
 * which one. Several Cards flip left to right the same way. Bedrock players get the short
 * version (colour, then Card), because rapid inventory updates stutter through Geyser.
 */
public final class PackRevealMenu extends Menu {

    /** Where one Card sits, and the frame that lights up around it. */
    private static final int CENTRE = 13;
    private static final int[] RING = {3, 4, 5, 12, 14, 21, 23};
    /** Up to nine Cards in the middle row; extras still went to the inventory. */
    private static final int[] ROW = {9, 10, 11, 12, 13, 14, 15, 16, 17};
    private static final int COLLECT = 22;

    private final Player player;
    private final List<String> cardIds;
    private final Runnable onBack;
    private final boolean bedrock;

    private BukkitTask task;
    private int step;
    private boolean done;

    public PackRevealMenu(HomeCraftManagement plugin, Player player, String packName,
                          List<String> cardIds, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.cardIds = cardIds;
        this.onBack = onBack;
        this.bedrock = Bedrock.is(player);
        init(27, Text.of("&5Opening: &f" + Text.plain(packName)));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }
        if (single()) {
            set(CENTRE, mystery(Material.GRAY_STAINED_GLASS_PANE), null);
        } else {
            for (int i = 0; i < shown(); i++) {
                set(ROW[i], mystery(Material.GRAY_STAINED_GLASS_PANE), null);
            }
            if (cardIds.size() > ROW.length) {
                set(26, Menus.icon(Material.CHEST, "&7+" + (cardIds.size() - ROW.length) + " more",
                        "&8In your inventory."), null);
            }
        }
        set(COLLECT, Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&a&lCOLLECT",
                "&7Your Cards are already in your bag."), true), e -> {
            finish();
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
        if (task == null && !done) {
            start();
        }
    }

    private boolean single() {
        return cardIds.size() == 1;
    }

    private int shown() {
        return Math.min(cardIds.size(), ROW.length);
    }

    private void start() {
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f);
        long period = bedrock ? 12L : 7L;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    /**
     * One frame. Java, one Card: shimmer ×3, colour, Card. Bedrock, one Card: colour, Card.
     * Several Cards: colour then Card for each in turn (Bedrock: all colours, then all Cards).
     */
    private void tick() {
        int s = step++;
        if (single()) {
            String id = cardIds.get(0);
            int shimmer = bedrock ? 0 : 3;
            if (s < shimmer) {
                getInventory().setItem(CENTRE, mystery(s % 2 == 0 ? Material.WHITE_STAINED_GLASS_PANE
                        : Material.LIGHT_GRAY_STAINED_GLASS_PANE));
                ping(0.7f + 0.1f * s);
            } else if (s == shimmer) {
                colour(CENTRE, id, true);
            } else {
                card(CENTRE, id);
                fanfare(rarity(id));
                finishTask();
            }
            return;
        }
        int n = shown();
        if (bedrock) {
            if (s == 0) {
                for (int i = 0; i < n; i++) {
                    colour(ROW[i], cardIds.get(i), false);
                }
                ping(1.0f);
            } else {
                for (int i = 0; i < n; i++) {
                    card(ROW[i], cardIds.get(i));
                }
                fanfare(best(n));
                finishTask();
            }
            return;
        }
        int card = s / 2;
        if (card >= n) {
            fanfare(best(n));
            finishTask();
            return;
        }
        if (s % 2 == 0) {
            colour(ROW[card], cardIds.get(card), false);
            ping(0.8f + 0.12f * card);
        } else {
            card(ROW[card], cardIds.get(card));
        }
    }

    /** Show everything now (Collect, or closing early), so nothing is left face-down. */
    private void finish() {
        finishTask();
        if (single()) {
            card(CENTRE, cardIds.get(0));
            return;
        }
        for (int i = 0; i < shown(); i++) {
            card(ROW[i], cardIds.get(i));
        }
    }

    private void finishTask() {
        done = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @Override
    protected void onClose(Player viewer) {
        finishTask();
    }

    private ItemStack mystery(Material pane) {
        return Menus.icon(pane, "&7? ? ?", "&8A mystery Card…");
    }

    /** The rarity-colour step: the slot (and, for one Card, the frame) lights up. */
    private void colour(int slot, String id, boolean withRing) {
        Rarity r = rarity(id);
        Material pane = r == null ? Material.WHITE_STAINED_GLASS_PANE : plugin.miniService().style(r).pane();
        String name = r == null ? "&f!" : plugin.miniService().rarityText(r) + "&f!";
        getInventory().setItem(slot, Menus.glint(Menus.icon(pane, name), r != null && r.ordinal() >= Rarity.RARE.ordinal()));
        if (withRing) {
            for (int ring : RING) {
                getInventory().setItem(ring, Menus.icon(pane, " "));
            }
        }
        if (r != null && r.ordinal() >= Rarity.RARE.ordinal()) {
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.0f);
        } else {
            ping(1.2f);
        }
    }

    private void card(int slot, String id) {
        ItemStack card = plugin.miniService().cardFor(id);
        getInventory().setItem(slot, card != null ? card : Menus.icon(Material.PAPER, "&bCard"));
    }

    private Rarity rarity(String id) {
        MiniDef def = plugin.miniService().def(id);
        return def == null ? null : def.rarity();
    }

    private Rarity best(int n) {
        Rarity best = null;
        for (int i = 0; i < n; i++) {
            Rarity r = rarity(cardIds.get(i));
            if (r != null && (best == null || r.ordinal() > best.ordinal())) {
                best = r;
            }
        }
        return best;
    }

    private void ping(float pitch) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, pitch);
    }

    private void fanfare(Rarity best) {
        if (best != null && best.ordinal() >= Rarity.EPIC.ordinal()) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
        }
    }
}
