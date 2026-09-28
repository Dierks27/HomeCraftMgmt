package com.dierks.homecraft.gui.games.cabinet.sweeper;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.sweeper.CreeperSweeper;
import com.dierks.homecraft.games.cabinet.sweeper.CreeperSweeperSettings;
import com.dierks.homecraft.games.cabinet.sweeper.SweeperEngine;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.ClickHold;
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
 * The Creeper Sweeper screen (54, spec §10b): first the choice of board, then the board itself.
 *
 * <p><b>Choosing.</b> Easy, normal and hard (each its own Classic board with its milestones and
 * the player's best) and today's daily board, whose tile says BEFORE it is dealt whether this is
 * the scored try or practice, and that closing the game uses the try up. Each has its high scores
 * underneath.
 *
 * <p><b>Playing.</b> The 9 by 5 board fills rows 0-4, square {@code i} on slot {@code i}. A tap
 * can't right-click on Bedrock, so row 5 has a Dig / Flag switch (46) that decides what a tap on
 * the board does. Every undug square is the SAME item, whatever is under it; a dug square is a
 * coloured pane carrying its number both as its stack size and in its name. Only the engine's
 * public view is drawn. 49 goes back to the choice; during a live board the first tap on it only
 * asks "click again to quit", because leaving ends the board with no score.
 *
 * <p>The choice tiles share slots with board squares, so a deal holds clicks for a moment
 * ({@link ClickHold}): the second half of a double click on "Today's board" must not dig the
 * square that has just appeared under it. Arming the quit holds them too.
 */
public final class CreeperSweeperMenu extends GameMenu {

    /** Every undug square: one shared item, so nothing about a square shows before it is dug. */
    private static final ItemStack HIDDEN = Menus.icon(Material.GRASS_BLOCK, "&aUndug square",
            "&7Tap to dig it, or to flag it", "&7when the switch says Flag.");
    private static final ItemStack FLAG = Menus.icon(Material.RED_BANNER, "&cFlag",
            "&7Tap it in Flag mode to take it off.");
    /** Pane colours for 0-8 creepers around, after the classic Minesweeper colours. */
    private static final Material[] PANES = {Material.WHITE_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE,
            Material.LIME_STAINED_GLASS_PANE, Material.RED_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE,
            Material.BROWN_STAINED_GLASS_PANE, Material.CYAN_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE,
            Material.MAGENTA_STAINED_GLASS_PANE};
    private static final String[] INK = {"&f", "&b", "&a", "&c", "&9", "&4", "&3", "&f", "&d"};
    private static final Material[] LEVEL_ICONS = {Material.LIME_CONCRETE, Material.YELLOW_CONCRETE,
            Material.RED_CONCRETE};

    private final CreeperSweeper sweeper;
    private CreeperSweeper.Run run;
    private SweeperEngine board;
    private boolean flagMode;
    private boolean quitArmed;
    private boolean clockRunning;

    public CreeperSweeperMenu(HomeCraftManagement plugin, CreeperSweeper sweeper, Player viewer, Runnable back) {
        super(plugin, sweeper, viewer, back);
        this.sweeper = sweeper;
        init(54, Text.of("&b" + sweeper.name()));
    }

    @Override
    protected void build() {
        fill();
        if (board == null) {
            buildChoice();
        } else {
            buildBoard();
        }
    }

    // ---- choosing a board ---------------------------------------------------------------------

    private void buildChoice() {
        CreeperSweeperSettings s = sweeper.settings();
        set(SweeperLayout.TITLE, Menus.icon(Material.CREEPER_HEAD, "&b" + sweeper.name(), lore(sweeper.rules())), null);
        for (int i = 0; i < CreeperSweeperSettings.LEVELS.size(); i++) {
            String level = CreeperSweeperSettings.LEVELS.get(i);
            int slot = SweeperLayout.LEVELS[i];
            set(slot, levelTile(s, level, LEVEL_ICONS[i]), e -> start(() -> sweeper.classic(level)));
            set(slot + SweeperLayout.SCORES_BELOW, Menus.icon(Material.OAK_SIGN, "&eHigh scores &7- " + title(level)),
                    e -> sweeper.showScores(viewer, level, this::reopen));
        }
        set(SweeperLayout.DAILY, dailyTile(s), e -> start(() -> sweeper.daily(viewer)));
        String today = Scores.daily(sweeper.day());
        set(SweeperLayout.DAILY + SweeperLayout.SCORES_BELOW, Menus.icon(Material.OAK_SIGN,
                "&eHigh scores &7- today's board"), e -> sweeper.showScores(viewer, today, this::reopen));
        set(SweeperLayout.RULES, rulesTile(sweeper.rules()), null);
        exitTile();
    }

    private ItemStack levelTile(CreeperSweeperSettings s, String level, Material icon) {
        Long best = sweeper.best(viewer, level);
        List<String> lore = new ArrayList<>();
        List<Integer> times = s.milestones().get(level);
        if (times != null && s.milestoneReward() > 0) {
            lore.add("&7Milestones - clear it within:");
            for (int n = 1; n <= times.size(); n++) {
                lore.add("&7" + medalName(n) + ": &f" + minutes(times.get(n - 1))
                        + (sweeper.milestonePaid(viewer, level, n) ? " &a✔" : ""));
            }
            lore.add("&7Each pays &6" + tokens(s.milestoneReward()) + "&7, once.");
        }
        lore.add("&7Closing a game ends it: no score.");
        lore.add("&eClick to play");
        return Menus.icon(icon, "&a" + title(level) + " &7- " + s.minesFor(level) + " creepers"
                + (best != null ? ", best " + SweeperEngine.clock(best) : ""), lore.toArray(new String[0]));
    }

    private ItemStack dailyTile(CreeperSweeperSettings s) {
        boolean tried = sweeper.dailyTried(viewer);
        List<String> lore = new ArrayList<>();
        lore.add("&7The same board for everyone today,");
        lore.add("&7with a safe start already dug.");
        lore.add("&7Its clock starts when it's dealt.");
        if (sweeper.dailyDone(viewer)) {
            lore.add("&a✔ Today's challenge done");
        } else if (s.dailyReward() > 0) {
            lore.add("&7Goal: clear it, for &6" + tokens(s.dailyReward()));
        }
        Long today = sweeper.best(viewer, Scores.daily(sweeper.day()));
        if (today != null) {
            lore.add("&7Your time today: &f" + SweeperEngine.clock(today));
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

    /** Deal a board (after the gate) and switch this screen to it. */
    private void start(Supplier<CreeperSweeper.Run> how) {
        if (!sweeper.mayPlay(viewer)) {
            return;
        }
        run = how.get();
        board = sweeper.deal(run);
        flagMode = false;
        quitArmed = false;
        if (!clockRunning) {
            clockRunning = true;
            ticker(20, this::tickClock);
        }
        hold(ClickHold.SETTLE_MS);
        refresh();
    }

    // ---- the board ----------------------------------------------------------------------------

    private void buildBoard() {
        boolean over = board.state().over();
        for (int c = 0; c < SweeperEngine.CELLS; c++) {
            int cell = c;
            set(SweeperLayout.slot(cell), square(cell), over ? null : e -> tap(cell));
        }
        if (!over) {
            set(SweeperLayout.TOGGLE, flagMode
                    ? Menus.icon(Material.RED_BANNER, "&eMode: &cFlag &7- tap for Dig", "&7A tap on the board puts a flag",
                    "&7on a square, or takes it off.")
                    : Menus.icon(Material.IRON_SHOVEL, "&eMode: &fDig &7- tap for Flag", "&7A tap on the board digs",
                    "&7that square."), e -> {
                flagMode = !flagMode;
                quitArmed = false;
                click(1.2f);
                refresh();
            });
        }
        set(SweeperLayout.LEFT, Menus.count(Menus.icon(Material.CREEPER_HEAD, "&aCreepers left: &f" + board.creepersLeft(),
                "&7Creepers on the board, minus your flags."), board.creepersLeft()), null);
        set(SweeperLayout.TIME, timeTile(), null);
        boolean live = board.state() == SweeperEngine.State.LIVE;
        set(49, Menus.icon(Material.BARRIER, quitArmed ? "&cBack - click again to quit" : "&cBack",
                live ? "&7Leaving ends this board: no score." : "&7Back to the boards."), e -> {
            if (board.state() == SweeperEngine.State.LIVE && !quitArmed) {
                quitArmed = true;
                hold(ClickHold.SETTLE_MS);
                refresh();
                return;
            }
            reopen();
        });
        if (over) {
            set(SweeperLayout.AGAIN, Menus.icon(Material.LIME_DYE, run.isDaily()
                    ? "&a" + CabinetGame.dailyAgain("board", sweeper.dailyTried(viewer)) : "&aPlay again"),
                    e -> start(() -> run.isDaily() ? sweeper.daily(viewer) : sweeper.classic(run.level())));
        }
        set(SweeperLayout.RUN, runTile(), null);
    }

    /** One square, drawn from the engine's public view only. */
    private ItemStack square(int cell) {
        return switch (board.look(cell)) {
            case HIDDEN -> HIDDEN;
            case FLAG -> FLAG;
            case OPEN -> number(board.number(cell));
            case CREEPER -> Menus.icon(Material.CREEPER_HEAD, "&aCreeper");
            case BOOM -> Menus.icon(Material.TNT, "&c&lBoom!");
            case WRONG_FLAG -> Menus.icon(Material.WHITE_BANNER, "&7Flag &8- no creeper here");
        };
    }

    /** A dug square: its colour, and its number as the stack size and in the name. */
    private static ItemStack number(int n) {
        int k = Math.max(0, Math.min(8, n));
        String name = k == 0 ? "&fClear &7- no creepers next to it"
                : INK[k] + k + " &7creeper" + (k == 1 ? "" : "s") + " next to it";
        return Menus.count(Menus.icon(PANES[k], name), Math.max(1, k));
    }

    private void tap(int cell) {
        quitArmed = false;
        if (flagMode) {
            if (board.toggleFlag(cell)) {
                click(1.6f);
            }
        } else if (board.dig(cell, plugin.clock().nowMillis())) {
            viewer.playSound(viewer.getLocation(), Sound.BLOCK_GRASS_BREAK, 0.6f, 1.0f);
            if (board.state().over()) {
                sweeper.finish(viewer, run, board);
            }
        }
        refresh();
    }

    private ItemStack timeTile() {
        long ms = board.timeMs(plugin.clock().nowMillis());
        if (board.state() == SweeperEngine.State.READY) {
            return Menus.icon(Material.CLOCK, "&eTime: &f" + SweeperEngine.clock(0) + " &7- starts on your first dig");
        }
        return Menus.icon(Material.CLOCK, "&eTime: &f" + SweeperEngine.clock(ms));
    }

    /** Once a second while a board is live: move the clock on (just that slot). */
    private void tickClock() {
        if (board != null && board.state() == SweeperEngine.State.LIVE) {
            getInventory().setItem(SweeperLayout.TIME, timeTile());
        }
    }

    private ItemStack runTile() {
        if (run.isDaily()) {
            return Menus.icon(Material.LIGHT_BLUE_CONCRETE, "&bToday's board &7- "
                    + (run.practice() ? "practice" : "your scored try"), "&7Goal: clear it.");
        }
        int level = CreeperSweeperSettings.LEVELS.indexOf(run.level());
        return Menus.icon(LEVEL_ICONS[Math.max(0, level)], "&a" + title(run.level()) + " &7- "
                + board.mines() + " creepers");
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Back to the choice of board: a fresh screen (this board, if live, ends with no score). */
    private void reopen() {
        new CreeperSweeperMenu(plugin, sweeper, viewer, back).open(viewer);
    }

    private void click(float pitch) {
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, pitch);
    }

    private static String title(String level) {
        return level.isEmpty() ? level : Character.toUpperCase(level.charAt(0)) + level.substring(1);
    }

    private static String medalName(int n) {
        return title(CabinetGame.medal(n));
    }

    /** Whole seconds as "1:30". */
    private static String minutes(int seconds) {
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    private static String tokens(int n) {
        return n + " token" + (n == 1 ? "" : "s");
    }

    private static String[] lore(List<String> lines) {
        return lines.stream().map(line -> "&7" + line).toArray(String[]::new);
    }
}
