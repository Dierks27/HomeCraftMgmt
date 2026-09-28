package com.dierks.homecraft.gui.games.golf;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Heads;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * "Pick your ball" (54, spec §12): which of the player's own Minis rolls round the course.
 *
 * <pre>
 *  row 0     4 your ball now
 *  rows 1-3  10-16, 19-25, 28-34 the plain white ball, then each Mini you own that has a head, 21 a page
 *  row 5     45/53 page arrows when needed, 49 Back
 * </pre>
 *
 * Only the Mini's look is used, on a fresh plain head: the Mini itself stays in the collection and
 * nothing about it changes. The choice is remembered for next time. A Bedrock player's ball is
 * always a white block (Bedrock doesn't show head textures reliably), so they are told that
 * instead of being offered a choice that wouldn't show.
 */
public final class GolfBallMenu extends GameMenu {

    private static final int HEADER = 4;
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    private final MiniGolf golf;
    private final int page;

    public GolfBallMenu(HomeCraftManagement plugin, MiniGolf golf, Player viewer, int page, Runnable back) {
        super(plugin, golf, viewer, back);
        this.golf = golf;
        this.page = Math.max(0, page);
        init(54, Text.of("&dPick your ball"));
    }

    /**
     * The "Pick your ball" button other screens show: the ball the player has now — the chosen
     * Mini only while they still own it and it can be a ball (as {@link #build} checks), else the
     * plain white ball.
     */
    static ItemStack currentTile(MiniGolf golf, Player viewer) {
        if (Bedrock.is(viewer)) {
            return Menus.icon(Material.WHITE_CONCRETE, "&fYour ball: &7a white block",
                    "&7On Bedrock the ball is a white", "&7block, so it's easy to see.");
        }
        String chosen = golf.chosenBall(viewer.getUniqueId());
        MiniDef def = chosen == null ? null : golf.ballChoices(viewer.getUniqueId()).stream()
                .filter(d -> d.id().equals(chosen)).findFirst().orElse(null);
        String name = def == null ? "the plain white ball" : def.name();
        ItemStack head = def == null ? MiniGolf.whiteBall() : Heads.base(def.texture());
        return named(head, "&ePick your ball &7- " + name, "&7Your Mini can be the ball!", "&eClick to choose");
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        if (Bedrock.is(viewer)) {
            set(22, currentTile(golf, viewer), null);
            return;
        }
        String chosen = golf.chosenBall(viewer.getUniqueId());
        List<MiniDef> minis = golf.ballChoices(viewer.getUniqueId());
        boolean stillOwned = chosen != null && minis.stream().anyMatch(d -> d.id().equals(chosen));
        String now = chosen == null || !stillOwned ? null : chosen;
        String nowName = now == null ? "the plain white ball"
                : minis.stream().filter(d -> d.id().equals(now)).findFirst().map(MiniDef::name).orElse(now);
        set(HEADER, named(now == null ? MiniGolf.whiteBall() : head(minis, now), "&eYour ball: &f" + nowName,
                "&7Pick any Mini you own.", "&7It stays in your collection:", "&7only its look rolls."), null);

        int count = minis.size() + 1; // the plain ball first
        int pages = Math.max(1, (count + SLOTS.length - 1) / SLOTS.length);
        int p = Math.min(page, pages - 1);
        for (int i = 0; i < SLOTS.length; i++) {
            int index = p * SLOTS.length + i;
            if (index >= count) {
                break;
            }
            if (index == 0) {
                set(SLOTS[i], choice(MiniGolf.whiteBall(), "&fPlain white ball", now == null), e -> choose(null));
            } else {
                MiniDef d = minis.get(index - 1);
                set(SLOTS[i], choice(Heads.base(d.texture()), "&f" + d.name(), d.id().equals(now)), e -> choose(d.id()));
            }
        }
        if (minis.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7No Minis yet", "&7Collect Minis and any of them",
                    "&7can be your ball."), null);
        }
        if (p > 0) {
            set(45, Menus.icon(Material.ARROW, "&fPrevious page"),
                    e -> new GolfBallMenu(plugin, golf, viewer, p - 1, back).open(viewer));
        }
        if (p < pages - 1) {
            set(53, Menus.icon(Material.ARROW, "&fNext page"),
                    e -> new GolfBallMenu(plugin, golf, viewer, p + 1, back).open(viewer));
        }
    }

    private void choose(String miniId) {
        if (golf.chooseBall(viewer.getUniqueId(), miniId)) {
            Sounds.received(viewer);
        } else {
            Sounds.refused(viewer);
        }
        refresh();
    }

    private static ItemStack head(List<MiniDef> minis, String id) {
        for (MiniDef d : minis) {
            if (d.id().equals(id)) {
                return Heads.base(d.texture());
            }
        }
        return MiniGolf.whiteBall();
    }

    private static ItemStack choice(ItemStack head, String name, boolean picked) {
        ItemStack out = named(head, picked ? name + " &a✔" : name, picked ? "&aYour ball now." : "&eClick to pick it");
        return Menus.glint(out, picked);
    }

    /** Name a head on a fresh meta, after its profile was committed (Heads' two-pass rule). */
    private static ItemStack named(ItemStack head, String name, String... lore) {
        ItemMeta meta = head.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.of(name));
            List<Component> lines = new ArrayList<>();
            for (String l : lore) {
                lines.add(Text.of(l));
            }
            meta.lore(lines);
            head.setItemMeta(meta);
        }
        return head;
    }
}
