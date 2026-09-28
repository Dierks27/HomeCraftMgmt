package com.dierks.homecraft.gui.games.cabinet.snake;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.snake.Snake;
import com.dierks.homecraft.games.cabinet.snake.SnakeEngine;
import com.dierks.homecraft.games.cabinet.snake.SnakeSettings;
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
 * The Snake screen (54, spec §10b, R3.14): first Classic or today's board, then the field.
 *
 * <p><b>Choosing.</b> Classic shows the player's best and the three apple milestones; today's
 * board says BEFORE it is dealt whether this is the scored try or practice, and that closing the
 * game uses the try up. Each has its high scores underneath.
 *
 * <p><b>Playing.</b> The 7 by 5 field is columns 1-7 of rows 0-4. The whole of column 0 is "◀ Turn
 * left" and the whole of column 8 "Turn right ▶": five-tall targets a thumb can't miss, turning
 * the snake the way IT faces. Row 5: 46 apples, 47 best, 48 speed, 49 Back, 50 start / pause /
 * resume / play again; 45 and 53 stay filler. A run waits for Start, then moves on a one-tick
 * {@link #ticker} countdown (so the speed can change between moves), which stops by itself when
 * the screen closes. During a run the first tap on Back pauses and asks "click again to quit",
 * because leaving ends the run with no score.
 */
public final class SnakeMenu extends GameMenu {

    private static final ItemStack EMPTY = Menus.icon(Material.BLACK_STAINED_GLASS_PANE, " ");
    private static final ItemStack BODY = Menus.icon(Material.LIME_CONCRETE, "&aSnake");
    private static final ItemStack APPLE = Menus.icon(Material.APPLE, "&cApple");
    private static final ItemStack CRASH = Menus.icon(Material.RED_CONCRETE, "&cCrash!");
    private static final ItemStack TURN_LEFT = Menus.icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&e◀ Turn left",
            "&7Turns the snake to its own left.");
    private static final ItemStack TURN_RIGHT = Menus.icon(Material.ORANGE_STAINED_GLASS_PANE, "&eTurn right ▶",
            "&7Turns the snake to its own right.");

    private final Snake snake;
    private Snake.Run run;
    private SnakeEngine field;
    private Long bestBefore;
    private boolean started;
    private boolean paused;
    private boolean quitArmed;
    private boolean ticking;
    private int wait;

    public SnakeMenu(HomeCraftManagement plugin, Snake snake, Player viewer, Runnable back) {
        super(plugin, snake, viewer, back);
        this.snake = snake;
        init(54, Text.of("&b" + snake.name()));
    }

    @Override
    protected void build() {
        fill();
        if (field == null) {
            buildChoice();
        } else {
            buildField();
        }
    }

    // ---- choosing a board ---------------------------------------------------------------------

    private void buildChoice() {
        SnakeSettings s = snake.settings();
        set(SnakeLayout.TITLE, Menus.icon(Material.LIME_CONCRETE, "&b" + snake.name(),
                snake.rules().stream().map(line -> "&7" + line).toArray(String[]::new)), null);
        set(SnakeLayout.CLASSIC, classicTile(s), e -> start(() -> snake.classic(viewer)));
        set(SnakeLayout.CLASSIC + SnakeLayout.SCORES_BELOW, Menus.icon(Material.OAK_SIGN, "&eHigh scores &7- Classic"),
                e -> snake.showScores(viewer, Scores.CLASSIC, this::reopen));
        set(SnakeLayout.DAILY, dailyTile(s), e -> start(() -> snake.daily(viewer)));
        String today = Scores.daily(snake.day());
        set(SnakeLayout.DAILY + SnakeLayout.SCORES_BELOW, Menus.icon(Material.OAK_SIGN,
                "&eHigh scores &7- today's board"), e -> snake.showScores(viewer, today, this::reopen));
        set(SnakeLayout.RULES, rulesTile(snake.rules()), null);
        exitTile();
    }

    private ItemStack classicTile(SnakeSettings s) {
        Long best = snake.best(viewer, Scores.CLASSIC);
        List<String> lore = new ArrayList<>();
        if (s.milestoneReward() > 0) {
            lore.add("&7Milestones - apples in one run:");
            List<Integer> apples = s.milestones();
            for (int n = 1; n <= apples.size(); n++) {
                lore.add("&7" + medalName(n) + ": &f" + Snake.apples(apples.get(n - 1))
                        + (snake.milestonePaid(viewer, n) ? " &a✔" : ""));
            }
            lore.add("&7Each pays &6" + tokens(s.milestoneReward()) + "&7, once.");
        }
        lore.add("&7Closing a game ends it: no score.");
        lore.add("&eClick to play");
        return Menus.icon(Material.LIME_WOOL, "&aClassic" + (best != null ? " &7- your best " + Snake.apples(best) : ""),
                lore.toArray(new String[0]));
    }

    private ItemStack dailyTile(SnakeSettings s) {
        boolean tried = snake.dailyTried(viewer);
        List<String> lore = new ArrayList<>();
        lore.add("&7The apples come in the same order");
        lore.add("&7for everyone today.");
        if (snake.dailyDone(viewer)) {
            lore.add("&a✔ Today's challenge done");
        } else {
            lore.add("&7Goal: " + Snake.apples(Snake.DAILY_GOAL)
                    + (s.dailyReward() > 0 ? ", for &6" + tokens(s.dailyReward()) : ""));
        }
        Long today = snake.best(viewer, Scores.daily(snake.day()));
        if (today != null) {
            lore.add("&7Your score today: &f" + Snake.apples(today));
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

    /** Deal a field (after the gate) and switch this screen to it; the snake waits for Start. */
    private void start(Supplier<Snake.Run> how) {
        if (!snake.mayPlay(viewer)) {
            return;
        }
        run = how.get();
        field = snake.deal(run);
        bestBefore = snake.best(viewer, run.board());
        started = false;
        paused = false;
        quitArmed = false;
        if (!ticking) {
            ticking = true;
            ticker(1, this::tick);
        }
        refresh();
    }

    // ---- the field ----------------------------------------------------------------------------

    private void buildField() {
        boolean over = field.state().over();
        boolean moving = started && !paused && !over;
        for (int y = 0; y < SnakeEngine.ROWS; y++) {
            for (int x = 0; x < SnakeEngine.COLS; x++) {
                set(SnakeLayout.slot(x, y), square(x, y), null);
            }
            set(SnakeLayout.left(y), TURN_LEFT, moving ? e -> turn(true) : null);
            set(SnakeLayout.right(y), TURN_RIGHT, moving ? e -> turn(false) : null);
        }
        int apples = field.apples();
        set(SnakeLayout.APPLES, Menus.count(Menus.icon(Material.APPLE, "&eApples: &f" + apples,
                run.isDaily() ? "&7Today's goal: " + Snake.apples(Snake.DAILY_GOAL) : "&7One point each."), apples), null);
        set(SnakeLayout.BEST, Menus.icon(Material.GOLDEN_APPLE, (run.isDaily() ? "&eYour best today: &f" : "&eYour best: &f")
                + (bestBefore != null ? Snake.apples(bestBefore) : "none yet")), null);
        set(SnakeLayout.SPEED, Menus.icon(Material.SUGAR, "&bSpeed &f" + SnakeEngine.speedLevel(run.tick(), apples),
                "&7A little faster every " + SnakeEngine.APPLES_PER_SPEEDUP + " apples."), null);
        set(49, Menus.icon(Material.BARRIER, quitArmed ? "&cBack - click again to quit" : "&cBack",
                over ? "&7Back to the boards." : "&7Leaving ends this run: no score."), e -> {
            if (!field.state().over() && !quitArmed) {
                paused = true;
                quitArmed = true;
                refresh();
                return;
            }
            reopen();
        });
        set(SnakeLayout.PLAY, playButton(over), e -> play());
        set(SnakeLayout.RUN, runTile(), null);
    }

    private ItemStack playButton(boolean over) {
        if (over) {
            return Menus.icon(Material.LIME_DYE, "&aPlay again" + (run.isDaily() ? " &7- practice" : ""));
        }
        if (!started) {
            return Menus.icon(Material.LIME_DYE, "&a▶ Start", "&7The snake starts moving right.");
        }
        return paused ? Menus.icon(Material.LIME_DYE, "&a▶ Resume") : Menus.icon(Material.YELLOW_DYE, "&ePause");
    }

    private void play() {
        if (field.state().over()) {
            start(() -> run.isDaily() ? snake.daily(viewer) : snake.classic(viewer));
            return;
        }
        if (started && !paused) {
            paused = true;
        } else {
            started = true;
            paused = false;
            quitArmed = false;
            wait = SnakeEngine.interval(run.tick(), field.apples());
        }
        refresh();
    }

    private void turn(boolean left) {
        quitArmed = false;
        if (left ? field.turnLeft() : field.turnRight()) {
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, left ? 1.2f : 1.5f);
            refresh();
        }
    }

    /** Every tick: count down to the next move, make it, and end the run when it crashes. */
    private void tick() {
        if (field == null || !started || paused || field.state().over()) {
            return;
        }
        if (--wait > 0) {
            return;
        }
        int before = field.apples();
        SnakeEngine.State state = field.step();
        wait = SnakeEngine.interval(run.tick(), field.apples());
        if (field.apples() > before) {
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.5f, 1.2f);
        }
        if (state.over()) {
            quitArmed = false;
            snake.finish(viewer, run, field);
        }
        refresh();
    }

    /** One square of the field, drawn from the engine's public view only. */
    private ItemStack square(int x, int y) {
        return switch (field.look(x, y)) {
            case EMPTY -> EMPTY;
            case BODY -> BODY;
            case APPLE -> APPLE;
            case CRASH -> CRASH;
            case HEAD -> Menus.icon(Material.GREEN_CONCRETE, "&aHead &7- going " + glyph(field.nextHeading()));
        };
    }

    private ItemStack runTile() {
        if (run.isDaily()) {
            return Menus.icon(Material.LIGHT_BLUE_CONCRETE, "&bToday's board &7- "
                    + (run.practice() ? "practice" : "your scored try"),
                    "&7Goal: " + Snake.apples(Snake.DAILY_GOAL) + ".");
        }
        return Menus.icon(Material.LIME_WOOL, "&aClassic", "&7Eat as many apples as you can.");
    }

    private static String glyph(SnakeEngine.Heading heading) {
        return switch (heading) {
            case UP -> "▲";
            case RIGHT -> "▶";
            case DOWN -> "▼";
            case LEFT -> "◀";
        };
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Back to Classic / today's board: a fresh screen (this run, if live, ends with no score). */
    private void reopen() {
        new SnakeMenu(plugin, snake, viewer, back).open(viewer);
    }

    private static String medalName(int n) {
        String medal = CabinetGame.medal(n);
        return Character.toUpperCase(medal.charAt(0)) + medal.substring(1);
    }

    private static String tokens(int n) {
        return n + " token" + (n == 1 ? "" : "s");
    }
}
