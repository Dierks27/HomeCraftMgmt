package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * "Are you sure?" for an admin tool whose command needs {@code confirm} (27, WP-ADM): regenerate,
 * promote and choose. The same place for No and Yes on every one, and what Yes does in its NAME
 * (Bedrock).
 *
 * <pre>
 *  4 the tool, with what it does     11 No, go back     15 Yes: what it does     22 Back
 * </pre>
 *
 * Yes runs the tool's command with {@code confirm}, once ({@link FreshAdmin#yes}), and the screen
 * closes so the command's answer reads in chat.
 */
final class FreshAdminConfirm extends GameMenu {

    private final FreshAdmin.Tool tool;

    FreshAdminConfirm(HomeCraftManagement plugin, Game fresh, Player viewer, FreshAdmin.Tool tool, Runnable back) {
        super(plugin, fresh, viewer, back);
        this.tool = tool;
        init(27, Text.of("&dSure? " + tool.name().replaceFirst("^&.", "").replace("Admin: ", "")));
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        set(4, FreshAdminMenu.icon(tool), null);
        set(11, Menus.icon(Material.RED_STAINED_GLASS_PANE, "&cNo, go back", "&7Nothing changes."), e -> {
            if (back != null) {
                back.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
        set(15, Menus.icon(Material.LIME_STAINED_GLASS_PANE, tool.yes() == null ? "&aYes" : tool.yes(),
                tool.lore().toArray(new String[0])), e -> {
            hold(com.dierks.homecraft.gui.games.ClickHold.SETTLE_MS); // one Yes, one command
            FreshAdmin.yes(tool, words -> FreshAdminMenu.run(plugin, viewer, words));
        });
    }
}
