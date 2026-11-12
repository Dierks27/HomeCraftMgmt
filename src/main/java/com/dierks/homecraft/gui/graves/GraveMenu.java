package com.dierks.homecraft.gui.graves;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.graves.Grave;
import com.dierks.homecraft.graves.GraveItems;
import com.dierks.homecraft.graves.GraveService;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;

/**
 * The grave screen (HCM-GRAVES §4): what is in a grave, to take one stack at a time, everything at once, or the
 * experience. Sneak + right-click on your own grave, any right-click by an admin on someone else's, and
 * {@code /hcm graves open <id>}.
 *
 * <p>The stacks shown are copies, built from the database row each time the screen is drawn; every click is
 * cancelled ({@code MenuListener}) and each take is its own guarded write ({@link GraveService#takeOne}), so a click
 * on a stack someone else already took, or that a quit, a stop or an admin restore emptied, gives nothing and the
 * screen redraws with what is really left. One viewer at a time.
 */
public final class GraveMenu extends Menu {

    /** Stacks shown: the top five rows. */
    static final int SHOWN = 45;

    private final GraveService graves;
    private final Player viewer;
    private final long id;

    public GraveMenu(HomeCraftManagement plugin, GraveService graves, Player viewer, long id) {
        super(plugin);
        this.graves = graves;
        this.viewer = viewer;
        this.id = id;
        Grave g = graves.openGrave(id);
        init(54, Text.of("&8" + (g == null ? "Grave" : g.ownerName() + "'s grave") + " &7#" + id));
    }

    /** The grave this screen shows. */
    public long graveId() {
        return id;
    }

    @Override
    protected void build() {
        Grave g;
        try {
            g = graves.fresh(id);
        } catch (SQLException e) {
            g = null;
        }
        for (int i = SHOWN; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        if (g == null || !g.open()) {
            set(22, Menus.icon(Material.BARRIER, "&7This grave is empty now"), null);
            set(49, Menus.icon(Material.BARRIER, "&cClose"), e -> viewer.closeInventory());
            return;
        }
        List<GraveItems.Entry> entries = g.items();
        for (int i = 0; i < entries.size() && i < SHOWN; i++) {
            GraveItems.Entry entry = entries.get(i);
            ItemStack shown = Items.fromBase64(entry.data());
            if (shown == null) {
                shown = Menus.icon(Material.BARRIER, "&cA stack this server can't read", "&7An admin can see it in the log.");
            }
            int n = entry.n();
            set(i, shown, e -> {
                if (!graves.takeOne(viewer, id, n)) {
                    Sounds.refused(viewer);
                }
                refreshOrClose();
            });
        }
        set(45, Menus.icon(Material.PAPER, "&f" + g.ownerName() + "'s grave",
                "&7" + GraveService.where(g),
                "&7" + graves.ageOf(g) + " ago",
                "&7" + g.stacks() + " stack" + (g.stacks() == 1 ? "" : "s")
                        + (entries.size() > SHOWN ? " &8(take some to see the rest)" : ""),
                "", "&8Click a stack to take it."), null);
        boolean own = g.owner().equals(viewer.getUniqueId());
        set(49, Menus.icon(Material.CHEST, "&aTake everything",
                own ? "&7Back into the slots it came from." : "&7Into your bag.",
                "&7What won't fit drops at your feet."), e -> {
            if (!graves.takeAllFromScreen(viewer, id)) {
                Sounds.refused(viewer);
            }
            viewer.closeInventory();
        });
        if (g.xp() > 0) {
            set(53, Menus.icon(Material.EXPERIENCE_BOTTLE, "&aTake the experience", "&7" + g.xp() + " xp"), e -> {
                if (!graves.takeXp(viewer, id)) {
                    Sounds.refused(viewer);
                }
                refreshOrClose();
            });
        }
    }

    private void refreshOrClose() {
        if (graves.openGrave(id) == null) {
            viewer.closeInventory();
            return;
        }
        refresh();
    }

    @Override
    protected void onClose(Player player) {
        graves.screenClosed(id, player);
    }
}
