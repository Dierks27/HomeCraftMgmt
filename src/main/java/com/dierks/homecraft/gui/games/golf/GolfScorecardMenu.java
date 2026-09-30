package com.dierks.homecraft.gui.games.golf;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.golf.GolfCard;
import com.dierks.homecraft.games.golf.GolfRun;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A round's scorecard (54, spec §12): shown between holes, from the kit's "Scorecard", and at the
 * end.
 *
 * <pre>
 *  row 0     4 the course and its par
 *  rows 1-2  9-17 holes 1-9, 18-26 holes 10-18 (the stack count is the hole number):
 *            green under par, white par, yellow over, red picked up, a star for a hole-in-one,
 *            light blue the hole being played, grey not played yet
 *  row 3     31 the total against par
 *  row 4     40 "Next hole" between holes; "Play again" at the end, once you're home
 *  row 5     49 Close
 * </pre>
 *
 * It shows a snapshot ({@link GolfCard}), so it never reaches into a round that has moved on.
 */
public final class GolfScorecardMenu extends GameMenu {

    private static final int HEADER = 4;
    private static final int FIRST_HOLE = 9;
    private static final int TOTAL = 31;
    private static final int ACTION = 40;

    private final MiniGolf golf;
    private final GolfCard card;

    public GolfScorecardMenu(HomeCraftManagement plugin, MiniGolf golf, Player viewer, GolfCard card, Runnable back) {
        super(plugin, golf, viewer, back);
        this.golf = golf;
        this.card = card;
        init(54, Text.of("&d" + card.courseName() + " &8· &5Scorecard"));
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        set(HEADER, Menus.icon(Material.PAPER, "&d" + card.courseName() + " &7- " + MiniGolf.holes(card.pars().size())
                + ", par " + card.par(), "&7Strokes per hole, and the total", "&7against par."), null);
        for (int i = 0; i < card.pars().size() && i < 18; i++) {
            set(FIRST_HOLE + i, Menus.count(hole(i), i + 1), null);
        }
        if (card.scores().isEmpty()) {
            set(TOTAL, Menus.icon(Material.CLOCK, "&7No holes done yet"), null);
        } else {
            int diff = card.vsPar();
            set(TOTAL, Menus.icon(Material.CLOCK, (card.finished() ? "&fFinal: " : "&fSo far: ")
                    + GolfRun.strokesText(card.total()) + " &7(" + GolfRun.vsParText(diff) + ")",
                    "&7" + card.scores().size() + " of " + card.pars().size() + " holes done."), null);
        }
        if (card.finished()) {
            if (golf.games().sessions().home(viewer) && golf.playableCourse(card.courseId()) != null) {
                set(ACTION, Menus.icon(Material.LIME_CONCRETE, "&aPlay again", "&7Another round of " + card.courseName() + ".",
                        "&eClick to play"), e -> {
                    if (!golf.start(viewer, card.courseId())) {
                        Sounds.refused(viewer);
                    }
                });
            }
        } else if (card.between()) {
            set(ACTION, Menus.icon(Material.LIME_CONCRETE, "&aNext hole ▶", "&7Or wait a moment:",
                    "&7it starts by itself."), e -> golf.rounds().nextHoleNow(viewer));
        }
    }

    private ItemStack hole(int i) {
        int n = i + 1;
        int par = card.pars().get(i);
        if (i < card.scores().size()) {
            GolfRun.HoleScore s = card.scores().get(i);
            Material m = s.pickedUp() ? Material.RED_CONCRETE : s.holeInOne() ? Material.NETHER_STAR
                    : s.vsPar() < 0 ? Material.LIME_CONCRETE : s.vsPar() == 0 ? Material.WHITE_CONCRETE
                    : Material.YELLOW_CONCRETE;
            ItemStack tile = Menus.icon(m, "&fHole " + n + " &7(par " + par + "): " + GolfCard.colour(s)
                    + GolfRun.strokesText(s.strokes()), "&7" + GolfRun.holeWord(s));
            return Menus.glint(tile, s.holeInOne());
        }
        if (i == card.playing()) {
            return Menus.icon(Material.LIGHT_BLUE_CONCRETE, "&bHole " + n + " &7(par " + par + "): playing",
                    "&7Strokes so far: " + card.strokes());
        }
        return Menus.icon(Material.GRAY_CONCRETE, "&7Hole " + n + " (par " + par + ")", "&7Not played yet.");
    }
}
