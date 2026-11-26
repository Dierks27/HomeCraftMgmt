package com.dierks.homecraft.gui.games.lava;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.lava.FloorIsLava;
import com.dierks.homecraft.games.lava.LavaText;
import com.dierks.homecraft.games.lava.rules.LavaMode;
import com.dierks.homecraft.gui.MenuStyle;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The Floor Is Lava's first screen (27), from the Games screen's tile or {@code /hcm play floor_is_lava}: the three
 * ways to play, each a big picture (six slots, so a thumb on a controller can't miss it). The words are for the
 * grown-ups; a star, a lava bucket and a crown say the rest.
 *
 * <pre>
 *  rows 0-1  Little (0-2, 9-11) | Group (3-5, 12-14) | Challenge (6-8, 15-17)
 *  row 2     18 How to play                22 Back / Close
 * </pre>
 * A mode that can't be played right now (the room is in use for another, a Challenge has started, the room is being
 * tidied) is grey and says why; it is checked again when it is tapped.
 */
public final class LavaModeMenu extends GameMenu {

    private static final int[][] SLOTS = {{0, 1, 2, 9, 10, 11}, {3, 4, 5, 12, 13, 14}, {6, 7, 8, 15, 16, 17}};
    static final int RULES = 18;

    private final FloorIsLava lava;

    public LavaModeMenu(HomeCraftManagement plugin, FloorIsLava lava, Player viewer, Runnable back) {
        super(plugin, lava, viewer, back);
        this.lava = lava;
        init(27, Text.of(MenuStyle.title(LavaText.NAME)));
    }

    @Override
    protected void build() {
        fill();
        LavaMode[] modes = LavaMode.values();
        for (int n = 0; n < modes.length; n++) {
            LavaMode mode = modes[n];
            String why = lava.modeRefusal(viewer, mode);
            ItemStack icon = why == null ? icon(mode) : Menus.icon(Material.GRAY_DYE, "&7" + mode.label()
                    + " &8- not right now", why);
            for (int slot : SLOTS[n]) {
                set(slot, icon, e -> {
                    viewer.closeInventory();
                    lava.play(viewer, mode);
                });
            }
        }
        set(RULES, rulesTile(lava.rules()), null);
        exitTile();
    }

    /** A mode's picture: a star for Little, a lava bucket for Group, a golden helmet (a crown) for Challenge. */
    static ItemStack icon(LavaMode mode) {
        return switch (mode) {
            case LITTLE -> Menus.glint(Menus.icon(Material.NETHER_STAR, "&a&lLittle &7- nobody can lose",
                    "&7Collect every star.", "&7Touch the lava? A sizzle, a puff of", "&7smoke, and you're back on"
                            + " the furniture.", "&eClick to play"), true);
            case GROUP -> Menus.icon(Material.LAVA_BUCKET, "&e&lGroup &7- play together",
                    "&7One star count, a star goal every", "&7round and three hearts. The lava",
                    "&7comes higher, the furniture moves,", "&7stars come out during the lava.",
                    "&eClick to play");
            case CHALLENGE -> Menus.icon(Material.GOLDEN_HELMET, "&c&lChallenge &7- last one standing",
                    "&7The lava keeps rising and never", "&7goes down. Touch it, or end a round",
                    "&7without a star, and you're out.", "&7Alone: how many rounds can you last?",
                    "&eClick to play");
        };
    }
}
