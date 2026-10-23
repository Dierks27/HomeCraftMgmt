package com.dierks.homecraft.gui.games.golf;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.Prize;
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
 * "Pick your ball" (54, spec §12; SHOP-V2-SPEC §5.10): which ball rolls round the course.
 *
 * <pre>
 *  row 0     4 your ball now
 *  rows 1-3  10-16, 19-25, 28-34 the golf balls you bought, then each Mini you own that has a head, then the
 *            plain white ball, 21 a page
 *  row 5     45/53 page arrows when needed, 49 Back
 * </pre>
 *
 * A golf ball from the Prize Counter is a coloured block, so it plays as that block on both editions and is
 * offered on both. A Mini's look is used on a fresh plain head: the Mini itself stays in the collection and
 * nothing about it changes. The choice is remembered for next time. On Bedrock a head texture doesn't show
 * reliably, so a Bedrock player is offered no Minis (their white ball is a white block), and one with no golf
 * balls is told that instead of being offered a choice that wouldn't show.
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
     * The "Pick your ball" button other screens show: the ball the player has now — a golf ball they bought,
     * or the chosen Mini only while they still own it and it can be a ball (as {@link #build} checks), else the
     * plain white ball (a white block on Bedrock).
     */
    static ItemStack currentTile(MiniGolf golf, Player viewer) {
        String chosen = golf.chosenBall(viewer.getUniqueId());
        Prize shop = shopChoice(golf.shopBalls(viewer.getUniqueId()), chosen);
        if (shop != null) {
            return Menus.icon(shop.extra().ballLook(), "&ePick your ball &7- " + Text.plain(shop.display()),
                    "&7Your golf ball!", "&eClick to choose");
        }
        if (Bedrock.is(viewer)) {
            return Menus.icon(Material.WHITE_CONCRETE, "&fYour ball: &7a white block",
                    "&7On Bedrock the ball is a white", "&7block, so it's easy to see.");
        }
        MiniDef def = chosen == null ? null : golf.ballChoices(viewer.getUniqueId()).stream()
                .filter(d -> d.id().equals(chosen)).findFirst().orElse(null);
        String name = def == null ? "the plain white ball" : def.name();
        ItemStack head = def == null ? MiniGolf.whiteBall() : Heads.base(def.texture());
        return named(head, "&ePick your ball &7- " + name, "&7Your Mini can be the ball!", "&eClick to choose");
    }

    /** The bought golf ball {@code chosen} picks, while the player still owns it; else null. */
    private static Prize shopChoice(List<Prize> owned, String chosen) {
        String id = MiniGolf.shopBallId(chosen);
        if (id == null) {
            return null;
        }
        for (Prize p : owned) {
            if (p.id().equalsIgnoreCase(id)) {
                return p;
            }
        }
        return null;
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        boolean bedrock = Bedrock.is(viewer);
        List<Prize> balls = golf.shopBalls(viewer.getUniqueId());
        if (bedrock && balls.isEmpty()) {
            set(22, currentTile(golf, viewer), null);
            return;
        }
        String chosen = golf.chosenBall(viewer.getUniqueId());
        List<MiniDef> minis = bedrock ? List.of() : golf.ballChoices(viewer.getUniqueId());
        Prize shopNow = shopChoice(balls, chosen);
        MiniDef miniNow = shopNow != null || chosen == null ? null
                : minis.stream().filter(d -> d.id().equals(chosen)).findFirst().orElse(null);
        String now = shopNow != null ? MiniGolf.shopBallPref(shopNow.id()) : miniNow != null ? miniNow.id() : null;
        String nowName = shopNow != null ? Text.plain(shopNow.display())
                : miniNow != null ? miniNow.name() : "the plain white ball";
        ItemStack nowLook = shopNow != null ? new ItemStack(shopNow.extra().ballLook())
                : miniNow != null ? Heads.base(miniNow.texture()) : white(bedrock);
        String[] lore = bedrock
                ? new String[] {"&7Pick a golf ball you bought.", "&7It looks the same on every screen."}
                : new String[] {"&7Pick a golf ball or any Mini", "&7you own. A Mini stays in your",
                        "&7collection: only its look rolls."};
        set(HEADER, named(nowLook, "&eYour ball: &f" + nowName, lore), null);

        List<String> order = MiniGolf.ballOrder(balls.stream().map(Prize::id).toList(),
                minis.stream().map(MiniDef::id).toList(), bedrock);
        int pages = Math.max(1, (order.size() + SLOTS.length - 1) / SLOTS.length);
        int p = Math.min(page, pages - 1);
        for (int i = 0; i < SLOTS.length; i++) {
            int index = p * SLOTS.length + i;
            if (index >= order.size()) {
                break;
            }
            String pref = order.get(index);
            if (pref == null) {
                set(SLOTS[i], choice(white(bedrock), "&fPlain white ball", now == null), e -> choose(null));
                continue;
            }
            Prize ball = shopChoice(balls, pref);
            if (ball != null) {
                set(SLOTS[i], choice(new ItemStack(ball.extra().ballLook()), ball.display(), pref.equals(now)),
                        e -> choose(pref));
                continue;
            }
            MiniDef d = minis.stream().filter(m -> m.id().equals(pref)).findFirst().orElse(null);
            if (d != null) {
                set(SLOTS[i], choice(Heads.base(d.texture()), "&f" + d.name(), d.id().equals(now)), e -> choose(d.id()));
            }
        }
        if (!bedrock && minis.isEmpty() && balls.isEmpty()) {
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

    /** The plain white ball's look: the snowball head on Java, a white block on Bedrock. */
    private static ItemStack white(boolean bedrock) {
        return bedrock ? new ItemStack(Material.WHITE_CONCRETE) : MiniGolf.whiteBall();
    }

    private void choose(String miniId) {
        if (golf.chooseBall(viewer.getUniqueId(), miniId)) {
            Sounds.received(viewer);
        } else {
            Sounds.refused(viewer);
        }
        refresh();
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
