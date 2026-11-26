package com.dierks.homecraft.gui.games.lava;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.lava.FloorIsLava;
import com.dierks.homecraft.games.lava.LavaText;
import com.dierks.homecraft.games.lava.rules.LavaMode;
import com.dierks.homecraft.games.lava.rules.LavaResult;
import com.dierks.homecraft.gui.MenuStyle;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The Floor Is Lava's end-of-round screen (27): the kid screen, made for a player who can't read yet. Two rows are
 * one big green Play again; under it, a door: All done. Nothing else can be tapped, and for a moment after it opens
 * nothing can be tapped at all, so a thumb still pressing from the round doesn't start the next one.
 *
 * <pre>
 *  rows 0-1  0-17 Play again (one big picture)
 *  row 2     18 the result, for the grown-ups        22 All done (a door)
 * </pre>
 * Play again starts a new round of the same mode for everyone still in the room. All done takes the player out: back
 * to the Clubhouse's Arcade Room if they came from it, else home with their things. Closing the screen leaves them in
 * the room, where the kit has the same two pictures.
 */
public final class LavaEndMenu extends GameMenu {

    static final int RESULT = 18;

    private final FloorIsLava lava;
    private final LavaResult result;

    public LavaEndMenu(HomeCraftManagement plugin, FloorIsLava lava, Player viewer, LavaResult result) {
        super(plugin, lava, viewer, () -> {
            viewer.closeInventory();
            lava.sendBack(viewer);
        });
        this.lava = lava;
        this.result = result;
        init(27, Text.of(MenuStyle.title(LavaText.NAME)));
        hold(KID_SETTLE_MS);
    }

    @Override
    protected void build() {
        fill();
        bigAgain(slots(0, 17), () -> {
            viewer.closeInventory();
            lava.again(viewer);
        });
        if (result != null) {
            set(RESULT, Menus.icon(result.mode() == LavaMode.CHALLENGE ? Material.GOLDEN_HELMET : Material.NETHER_STAR,
                    "&6" + result.mode().label() + " &7- " + LavaText.endSmall(result, viewer.getUniqueId())), null);
        }
        kidExit();
    }
}
