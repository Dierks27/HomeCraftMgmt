package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenService;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A Fresh course's admin tools (27, WP-ADM; {@link FreshAdmin} holds what each is), opened from the
 * course screen's "Admin tools" item, for {@code hcm.games.admin} only.
 *
 * <pre>
 *  4  the course: its preview and next set's pick
 *  10 Make a new course now (regenerate)   11 Build one to try (preview)   12 Build next week's to try (preview next)
 *  14 Try the preview (test run; golf: walk it)   15 Use it now (promote)   16 Use it next week (choose)
 *  22 Back    24 Cancel next week's pick (unchoose; during the chosen set: let this week's pick go)
 * </pre>
 *
 * The preview's three appear once a preview stands (Use it now only for this set's); the cancel once
 * a course is picked. Like the confirm screen, it drops clicks for {@link FreshAdmin#OPEN_HOLD_MS}
 * as it opens (the rest of the double click that opened it), and it opens, and paints its tools,
 * only for someone who still has {@code hcm.games.admin} (fix2-D, D4 and D7). A tool
 * runs its {@code /hcm games gen} command through the command's own path, as the admin who clicked
 * (so every refusal, answer and log line is the command's); one whose command needs {@code confirm}
 * asks first ({@link FreshAdminConfirm}). The screen closes as the command runs, so its answer reads
 * in chat. It belongs to Fresh Courses: a click that throws closes only Fresh Courses.
 */
public final class FreshAdminMenu extends GameMenu {

    /** The course screen's item, with what a click does. */
    public record Button(ItemStack icon, Runnable click) {
    }

    private final String slotId;

    public FreshAdminMenu(HomeCraftManagement plugin, Game fresh, Player viewer, String slotId, Runnable back) {
        super(plugin, fresh, viewer, back);
        this.slotId = slotId;
        Slots.Def def = Slots.of(slotId);
        init(27, Text.of(def == null ? "&dAdmin tools: " + slotId : FreshAdmin.title(def, state(plugin, slotId))));
    }

    /** The engine's view of the slot, or {@code null} when Fresh Courses isn't running (or throws). */
    private static GenOps.Tools state(HomeCraftManagement plugin, String slotId) {
        GamesService games = plugin.games();
        DailyCourses fresh = games == null ? null : DailyLookup.fresh(games);
        GenService engine = games == null ? null : DailyLookup.engine(games);
        return fresh == null || engine == null ? null : games.guard(fresh, () -> engine.tools(slotId), null);
    }

    /**
     * The course screen's "Admin tools" item for {@code courseId} ({@link FreshAdmin#SLOT}), or
     * {@code null} when {@code viewer} doesn't see it: not an admin, not a Fresh Course, or Fresh
     * Courses isn't running.
     */
    public static Button button(HomeCraftManagement plugin, Player viewer, String courseId, Runnable back) {
        GamesService games = plugin.games();
        if (viewer == null || games == null || !FreshAdmin.shown(viewer.hasPermission(FreshAdmin.PERMISSION), courseId)) {
            return null;
        }
        DailyCourses fresh = DailyLookup.fresh(games);
        GenService engine = DailyLookup.engine(games);
        if (fresh == null || engine == null) {
            return null;
        }
        // Fresh Courses' own guard: this runs on another game's screen, which a Fresh Courses bug mustn't close
        GenOps.Tools state = games.guard(fresh, () -> engine.tools(courseId), null);
        ItemStack icon = Menus.icon(Material.COMMAND_BLOCK, FreshAdmin.itemName(state),
                FreshAdmin.state(state).toArray(new String[0]));
        return new Button(icon, () -> {
            if (admin(viewer)) { // D7: the course screen may have been painted before the permission went
                new FreshAdminMenu(plugin, fresh, viewer, courseId, back).open(viewer);
            }
        });
    }

    @Override
    protected void build() {
        hold(FreshAdmin.OPEN_HOLD_MS); // D4: the rest of the double click that opened it
        fill();
        exitTile();
        if (!viewer.hasPermission(FreshAdmin.PERMISSION)) {
            return; // D7: no tools, no preview or pick seeds, for someone who isn't an admin any more
        }
        GamesService games = plugin.games();
        GenService engine = games == null ? null : DailyLookup.engine(games);
        GenOps.Tools state = engine == null ? null : engine.tools(slotId);
        Slots.Def def = Slots.of(slotId);
        set(4, Menus.icon(Material.COMMAND_BLOCK, def == null ? "&dAdmin tools &7- " + slotId
                : FreshAdmin.headerName(def, state), FreshAdmin.state(state).toArray(new String[0])), null);
        if (state == null || def == null) {
            return;
        }
        for (FreshAdmin.Tool t : FreshAdmin.tools(def, state)) {
            set(t.slot(), icon(t), e -> FreshAdmin.click(t, this::ask, words -> run(plugin, viewer, words)));
        }
    }

    static ItemStack icon(FreshAdmin.Tool t) {
        Material m = switch (t.kind()) {
            case REGENERATE -> Material.TNT;
            case PREVIEW, PREVIEW_NEXT -> Material.SPYGLASS;
            case TEST -> Material.LIME_CONCRETE;
            case WALK -> Material.ENDER_PEARL;
            case PROMOTE -> Material.EMERALD;
            case CHOOSE -> Material.CLOCK;
            case UNCHOOSE -> Material.BARRIER;
        };
        return Menus.icon(m, t.name(), t.lore().toArray(new String[0]));
    }

    private void ask(FreshAdmin.Tool t) {
        if (admin(viewer)) {
            new FreshAdminConfirm(plugin, game, viewer, t, this::reopen).open(viewer);
        }
    }

    /**
     * Whether {@code viewer} may have a tools or confirm screen now (fix2-D, D7): the permission is
     * checked when the course screen paints the item, but a screen stays open while it is taken
     * away. Someone who lost it is told, and the screen they have open closes.
     */
    static boolean admin(Player viewer) {
        if (viewer.hasPermission(FreshAdmin.PERMISSION)) {
            return true;
        }
        viewer.sendMessage(Text.of("&cThat's for admins."));
        viewer.closeInventory();
        return false;
    }

    /**
     * Run {@code words} as {@code /hcm games gen <words>} for {@code viewer}, through Fresh Courses'
     * own command (its refusals, answers and log line), inside its guard. The screen closes first so
     * the answer reads in chat (and a test run can take the player to its start).
     */
    static void run(HomeCraftManagement plugin, Player viewer, String[] words) {
        if (!viewer.hasPermission(FreshAdmin.PERMISSION)) {
            viewer.sendMessage(Text.of("&cThat's for admins."));
            return;
        }
        GamesService games = plugin.games();
        Game fresh = games == null ? null : DailyLookup.fresh(games);
        GameAdmin tool = fresh == null ? null : games.guard(fresh, fresh::admin, null);
        viewer.closeInventory();
        if (tool == null) {
            viewer.sendMessage(Text.of("&cFresh Courses isn't running."));
            return;
        }
        games.guard(fresh, () -> tool.handle(viewer, words));
    }

    private void reopen() {
        if (admin(viewer)) {
            new FreshAdminMenu(plugin, game, viewer, slotId, back).open(viewer);
        }
    }
}
