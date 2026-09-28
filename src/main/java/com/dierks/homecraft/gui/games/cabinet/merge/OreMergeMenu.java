package com.dierks.homecraft.gui.games.cabinet.merge;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.merge.MergeEngine;
import com.dierks.homecraft.games.cabinet.merge.OreMerge;
import com.dierks.homecraft.games.cabinet.merge.OreMergeSettings;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The Ore Merge screen (54, spec §10b): first Classic or today's board, then the grid.
 *
 * <p><b>Choosing.</b> Classic shows the player's best and the three milestones (the biggest ore
 * made); today's board says BEFORE it is dealt whether this is the scored try or practice, and
 * that closing the game uses the try up. Each has its high scores underneath.
 *
 * <p><b>Playing.</b> The 4 by 4 grid sits in rows 1-4, columns 2-5; row 0 shows the score, the
 * board and the biggest ore. The four slide buttons are in row 5 either side of Back (47 ◀, 48 ▲,
 * 50 ▼, 51 ▶), never on 45/49/53. Ores made by the last move shimmer. "End game" (46) keeps the
 * score now; Back during a live game first asks "click again to quit", because leaving ends it
 * with no score. When no move is left the game records itself and 4 offers "Play again".
 */
public final class OreMergeMenu extends GameMenu {

    private static final ItemStack EMPTY = Menus.icon(Material.BLACK_STAINED_GLASS_PANE, " ");
    /** Each level's ore, coal (1) to dragon egg (11). */
    private static final Material[] ORES = {Material.BLACK_STAINED_GLASS_PANE, Material.COAL, Material.COPPER_INGOT,
            Material.IRON_INGOT, Material.REDSTONE, Material.LAPIS_LAZULI, Material.GOLD_INGOT, Material.EMERALD,
            Material.DIAMOND, Material.NETHERITE_INGOT, Material.NETHER_STAR, Material.DRAGON_EGG};

    private final OreMerge merge;
    private OreMerge.Run run;
    private MergeEngine grid;
    private boolean ended;
    private boolean endArmed;
    private boolean quitArmed;

    public OreMergeMenu(HomeCraftManagement plugin, OreMerge merge, Player viewer, Runnable back) {
        super(plugin, merge, viewer, back);
        this.merge = merge;
        init(54, Text.of("&b" + merge.name()));
    }

    @Override
    protected void build() {
        fill();
        if (grid == null) {
            buildChoice();
        } else {
            buildGrid();
        }
    }

    // ---- choosing a board ---------------------------------------------------------------------

    private void buildChoice() {
        OreMergeSettings s = merge.settings();
        List<String> title = new ArrayList<>();
        for (String line : merge.rules()) {
            title.add("&7" + line);
        }
        title.add("&7Coal, copper, iron, redstone, lapis, gold,");
        title.add("&7emerald, diamond, netherite, nether star, dragon egg.");
        set(MergeLayout.TITLE, Menus.icon(Material.DIAMOND, "&b" + merge.name(), title.toArray(new String[0])), null);
        set(MergeLayout.CLASSIC, classicTile(s), e -> start(merge::classic));
        set(MergeLayout.CLASSIC + MergeLayout.SCORES_BELOW, Menus.icon(Material.OAK_SIGN, "&eHigh scores &7- Classic"),
                e -> merge.showScores(viewer, Scores.CLASSIC, this::reopen));
        set(MergeLayout.DAILY, dailyTile(s), e -> start(() -> merge.daily(viewer)));
        String today = Scores.daily(merge.day());
        set(MergeLayout.DAILY + MergeLayout.SCORES_BELOW, Menus.icon(Material.OAK_SIGN,
                "&eHigh scores &7- today's board"), e -> merge.showScores(viewer, today, this::reopen));
        set(MergeLayout.RULES, rulesTile(merge.rules()), null);
        exitTile();
    }

    private ItemStack classicTile(OreMergeSettings s) {
        Long best = merge.best(viewer, Scores.CLASSIC);
        List<String> lore = new ArrayList<>();
        if (s.milestoneReward() > 0) {
            lore.add("&7Milestones - make:");
            List<Integer> tiles = s.milestones();
            for (int n = 1; n <= tiles.size(); n++) {
                lore.add("&7" + medalName(n) + ": &f" + tileWords(tiles.get(n - 1))
                        + (merge.milestonePaid(viewer, n) ? " &a✔" : ""));
            }
            lore.add("&7Each pays &6" + tokens(s.milestoneReward()) + "&7, once.");
        }
        lore.add("&7End game keeps your score;");
        lore.add("&7closing the game ends it with none.");
        lore.add("&eClick to play");
        return Menus.icon(Material.IRON_INGOT, "&aClassic" + (best != null ? " &7- your best " + best + " points" : ""),
                lore.toArray(new String[0]));
    }

    private ItemStack dailyTile(OreMergeSettings s) {
        boolean tried = merge.dailyTried(viewer);
        List<String> lore = new ArrayList<>();
        lore.add("&7The same ores for everyone today.");
        if (merge.dailyDone(viewer)) {
            lore.add("&a✔ Today's challenge done");
        } else {
            lore.add("&7Goal: make a diamond" + (s.dailyReward() > 0 ? ", for &6" + tokens(s.dailyReward()) : ""));
        }
        Long today = merge.best(viewer, Scores.daily(merge.day()));
        if (today != null) {
            lore.add("&7Your score today: &f" + today);
        }
        if (tried) {
            lore.add("&7You've had today's scored try.");
            lore.add("&7Practice: nothing is recorded.");
        } else {
            lore.add("&7Your first try today is the scored one.");
            lore.add("&cClosing the game uses it up.");
        }
        lore.add("&eClick to play");
        return Menus.icon(Material.LIGHT_BLUE_CONCRETE, "&bToday's board &7- " + (tried ? "practice" : "your scored try"),
                lore.toArray(new String[0]));
    }

    /** Deal a grid (after the gate) and switch this screen to it. */
    private void start(Supplier<OreMerge.Run> how) {
        if (!merge.mayPlay(viewer)) {
            return;
        }
        run = how.get();
        grid = merge.deal(run);
        ended = false;
        endArmed = false;
        quitArmed = false;
        refresh();
    }

    // ---- the grid -----------------------------------------------------------------------------

    private void buildGrid() {
        boolean live = !ended;
        for (int cell = 0; cell < MergeEngine.CELLS; cell++) {
            set(MergeLayout.slot(cell), ore(grid.level(cell), grid.merged(cell)), null);
        }
        set(MergeLayout.SCORE, Menus.icon(Material.EXPERIENCE_BOTTLE, "&eScore: &f" + grid.score(),
                "&7Every merge adds the new ore's value."), null);
        int top = grid.biggest();
        set(MergeLayout.BIGGEST, Menus.glint(Menus.icon(ORES[top], "&eBiggest: &f" + MergeEngine.name(top)
                + " &7(" + MergeEngine.value(top) + ")"), false), null);
        if (live) {
            set(MergeLayout.RUN, runTile(), null);
            set(MergeLayout.END, Menus.icon(Material.RED_DYE, endArmed ? "&cEnd game - click again" : "&eEnd game &7- keep your score",
                    "&7Stops here and records your score."), e -> {
                quitArmed = false;
                if (!endArmed) {
                    endArmed = true;
                    refresh();
                    return;
                }
                end();
            });
            arrow("&e◀ Left", MergeEngine.Dir.LEFT);
            arrow("&e▲ Up", MergeEngine.Dir.UP);
            arrow("&e▼ Down", MergeEngine.Dir.DOWN);
            arrow("&eRight ▶", MergeEngine.Dir.RIGHT);
        } else {
            set(MergeLayout.RUN, Menus.icon(Material.LIME_DYE, "&aPlay again &7- " + (run.isDaily() ? "practice" : "Classic")),
                    e -> start(() -> run.isDaily() ? merge.daily(viewer) : merge.classic()));
        }
        set(49, Menus.icon(Material.BARRIER, quitArmed ? "&cBack - click again to quit" : "&cBack",
                live ? "&7Leaving ends this game: no score." : "&7Back to the boards."), e -> {
            if (!ended && !quitArmed) {
                quitArmed = true;
                endArmed = false;
                refresh();
                return;
            }
            reopen();
        });
    }

    private void arrow(String name, MergeEngine.Dir dir) {
        set(MergeLayout.arrow(dir), Menus.icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, name, "&7Slides every ore that way."), e -> {
            quitArmed = false;
            endArmed = false;
            if (!grid.move(dir)) {
                return;
            }
            int made = 0;
            for (int cell = 0; cell < MergeEngine.CELLS; cell++) {
                if (grid.merged(cell)) {
                    made = Math.max(made, grid.level(cell));
                }
            }
            if (made > 0) {
                viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f,
                        Math.min(2.0f, 0.6f + made * 0.12f));
            } else {
                viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.4f);
            }
            if (grid.over()) {
                end();
                return;
            }
            refresh();
        });
    }

    /** The game is over (no move left, or End game): record it once and show the final grid. */
    private void end() {
        if (ended) {
            return;
        }
        ended = true;
        endArmed = false;
        quitArmed = false;
        merge.finish(viewer, run, grid);
        refresh();
    }

    private ItemStack runTile() {
        if (run.isDaily()) {
            return Menus.icon(Material.LIGHT_BLUE_CONCRETE, "&bToday's board &7- "
                    + (run.practice() ? "practice" : "your scored try"), "&7Goal: make a diamond.");
        }
        return Menus.icon(Material.IRON_INGOT, "&aClassic", "&7Make the biggest ore you can.");
    }

    /** An ore tile, drawn from its level; the ones the last move made shimmer. */
    private static ItemStack ore(int level, boolean made) {
        if (level <= 0) {
            return EMPTY;
        }
        int l = Math.min(level, MergeEngine.TOP);
        return Menus.glint(Menus.icon(ORES[l], "&f" + MergeEngine.name(l) + " &7(" + MergeEngine.value(l) + ")"), made);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Back to Classic / today's board: a fresh screen (this game, if live, ends with no score). */
    private void reopen() {
        new OreMergeMenu(plugin, merge, viewer, back).open(viewer);
    }

    /** "Diamond (256)" for a tile's value, or "an ore worth 300 or more" for a value no single ore has. */
    private static String tileWords(int value) {
        int level = MergeEngine.levelOf(value);
        return level > 0 ? MergeEngine.name(level) + " (" + value + ")" : "an ore worth " + value + " or more";
    }

    private static String medalName(int n) {
        String medal = CabinetGame.medal(n);
        return Character.toUpperCase(medal.charAt(0)) + medal.substring(1);
    }

    private static String tokens(int n) {
        return n + " token" + (n == 1 ? "" : "s");
    }
}
