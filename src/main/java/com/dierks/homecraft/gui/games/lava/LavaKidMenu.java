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
 * 0.57.0: the Floor Is Lava cabinet's screen in the Clubhouse's Arcade Room (the owner: "when you get in through the
 * arcade machine you should be able to pick any level you want not just easy"). Made for a four-year-old with a
 * controller who can't read: three pictures side by side in the middle, so one press of the stick goes from one to
 * the next (0.54.1's Whack-a-Zombie lesson: spaced out, every move was two presses), and under each a row of magma
 * that says how hard it is: one for Little, two for Group, three for Challenge. Each column is one button.
 *
 * <pre>
 *  row 0   3 Little (a star)   4 Group (a lava bucket)   5 Challenge (a crown)
 *  row 1  12 one magma        13 two magma              14 three magma
 *  row 2  22 All done (a door)
 * </pre>
 * A tap goes in by the cabinet's own way (the player's Clubhouse visit is handed over and comes back to the Arcade
 * Room at the end), with Little joining the Group round going, as the cabinet always did. A mode that can't be
 * played right now is a grey dye; the door closes the screen, leaving the player at the cabinet.
 */
public final class LavaKidMenu extends GameMenu {

    /** The three columns, side by side: {picture, difficulty} for Little, Group, Challenge. */
    static final int[][] COLUMNS = {{3, 12}, {4, 13}, {5, 14}};

    private final FloorIsLava lava;

    public LavaKidMenu(HomeCraftManagement plugin, FloorIsLava lava, Player viewer) {
        super(plugin, lava, viewer, null);
        this.lava = lava;
        init(27, Text.of(MenuStyle.title(LavaText.NAME)));
        hold(KID_SETTLE_MS); // the tap on the cabinet never lands on a picture
    }

    @Override
    protected void build() {
        fill();
        LavaMode[] modes = LavaMode.values();
        for (int n = 0; n < modes.length; n++) {
            LavaMode mode = modes[n];
            String why = lava.modeRefusal(viewer, mode, true);
            ItemStack picture = why == null ? picture(mode) : Menus.icon(Material.GRAY_DYE, "&7" + mode.label()
                    + " &8- not right now", why);
            ItemStack hard = why == null ? difficulty(mode) : picture;
            set(COLUMNS[n][0], picture, e -> go(mode));
            set(COLUMNS[n][1], hard, e -> go(mode));
        }
        kidExit();
    }

    private void go(LavaMode mode) {
        viewer.closeInventory();
        lava.kidPlay(viewer, mode);
    }

    /** A mode's picture: a star for Little, a lava bucket for Group, a crown for Challenge. */
    static ItemStack picture(LavaMode mode) {
        return switch (mode) {
            case LITTLE -> Menus.glint(Menus.icon(Material.NETHER_STAR, "&a&lLittle &7- nobody can lose",
                    "&7Collect every star.", "&7Touch the lava? A puff, and you're", "&7back on the furniture."), true);
            case GROUP -> Menus.icon(Material.LAVA_BUCKET, "&e&lGroup &7- play together",
                    "&7A star goal every round, three hearts.", "&7The furniture moves; stars come out",
                    "&7during the lava too.");
            case CHALLENGE -> Menus.icon(Material.GOLDEN_HELMET, "&c&lChallenge &7- last one standing",
                    "&7The lava keeps rising. Touch it, or", "&7end a round without a star, and",
                    "&7you're out.");
        };
    }

    /** How hard a mode is, without words: one, two or three blocks of magma. */
    static ItemStack difficulty(LavaMode mode) {
        int n = mode.ordinal() + 1;
        ItemStack magma = Menus.icon(Material.MAGMA_BLOCK, (n == 1 ? "&a" : n == 2 ? "&e" : "&c") + mode.label()
                + " &7- " + "■".repeat(n) + "□".repeat(3 - n));
        magma.setAmount(n);
        return magma;
    }
}
