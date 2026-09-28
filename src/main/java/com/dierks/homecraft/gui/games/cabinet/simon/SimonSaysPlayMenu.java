package com.dierks.homecraft.gui.games.cabinet.simon;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.simon.SimonEngine;
import com.dierks.homecraft.games.cabinet.simon.SimonSays;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * One game of Simon Says (54 slots): four big 2 by 2 pads — green, red, yellow, blue — that light
 * up with a note each (spec §10b).
 *
 * <p>The show runs on the screen's own ticker (one screen tick = two server ticks), paced by the
 * engine's {@link SimonEngine.Tempo}: a dark wool pad lights up as a shining block with its note,
 * then goes dark again before the next. Then it's the player's turn; a tapped pad flashes with its
 * note. Taps during the show are ignored. The ticker stops when the screen closes, and closing
 * ends the game with no score.
 *
 * <pre>
 *   M  .  .  .  S  .  .  .  .      0 mode · 4 watch / your turn
 *   .  .  G  G  .  R  R  .  .
 *   .  .  G  G  .  R  R  .  .      the four pads
 *   .  .  Y  Y  .  B  B  .  .
 *   .  .  Y  Y  .  B  B  .  .
 *   .  B  .  ?  X  A  H  .  .      46 best · 48 how to play · 49 back · 50 play again · 51 high scores
 * </pre>
 */
public final class SimonSaysPlayMenu extends GameMenu {

    private static final int[][] PADS = {{11, 12, 20, 21}, {14, 15, 23, 24}, {29, 30, 38, 39}, {32, 33, 41, 42}};
    private static final Material[] DARK = {Material.GREEN_WOOL, Material.RED_WOOL, Material.YELLOW_WOOL,
            Material.BLUE_WOOL};
    private static final Material[] LIT = {Material.EMERALD_BLOCK, Material.REDSTONE_BLOCK, Material.GOLD_BLOCK,
            Material.LAPIS_BLOCK};
    private static final String[] COLOUR = {"&a", "&c", "&e", "&9"};
    private static final String[] NAME = {"Green", "Red", "Yellow", "Blue"};
    /** Four note-block pitches, low to high. */
    private static final float[] PITCH = {0.707f, 0.891f, 1.059f, 1.414f};
    /** Server ticks per screen tick. */
    private static final long PERIOD = 2;
    /** Screen ticks a tapped pad stays lit. */
    private static final int FLASH = 3;
    /** Screen ticks of quiet between a round played back and the next show. */
    private static final int BREATHER = 4;

    private final SimonSays simon;
    private final CabinetGame.DailyStart daily;
    private final SimonEngine engine;
    private final SimonEngine.Tempo tempo;
    private final ItemStack[] dark = new ItemStack[SimonEngine.PADS];
    private final ItemStack[] lit = new ItemStack[SimonEngine.PADS];
    private boolean started;
    private int showTick;
    private int shownStep = -1;
    private int litPad = -1;
    private int flashPad = -1;
    private int flashLeft;
    private CabinetGame.Finish finish;

    /**
     * @param back  the first screen
     * @param daily the daily start, or {@code null} for Classic
     */
    public SimonSaysPlayMenu(HomeCraftManagement plugin, SimonSays game, Player viewer, Runnable back,
                             CabinetGame.DailyStart daily, long seed) {
        super(plugin, game, viewer, back);
        this.simon = game;
        this.daily = daily;
        this.engine = new SimonEngine(seed);
        this.tempo = Bedrock.is(viewer) ? SimonEngine.Tempo.BEDROCK : SimonEngine.Tempo.JAVA;
        for (int p = 0; p < SimonEngine.PADS; p++) {
            dark[p] = Menus.icon(DARK[p], COLOUR[p] + NAME[p]);
            lit[p] = Menus.glint(Menus.icon(LIT[p], COLOUR[p] + "&l★ " + NAME[p] + " ★"), true);
        }
        init(54, Text.of("&bSimon Says"));
    }

    @Override
    public void open(Player player) {
        super.open(player);
        if (!started && isOpenFor(viewer)) {
            started = true;
            ticker(PERIOD, this::tick);
        }
    }

    @Override
    protected void build() {
        fill();
        set(0, modeTile(), null);
        set(4, statusTile(), null);
        for (int p = 0; p < SimonEngine.PADS; p++) {
            int pad = p;
            ItemStack item = p == litPad || p == flashPad ? lit[p] : dark[p];
            for (int slot : PADS[p]) {
                set(slot, item, engine.over() ? null : e -> press(pad));
            }
        }
        Long best = simon.best(viewer);
        set(46, Menus.icon(Material.GOLD_NUGGET, best == null || best == 0 ? "&7No best yet" : "&eYour best: &f" + best),
                null);
        set(48, rulesTile(simon.rules()), null);
        set(49, Menus.icon(Material.BARRIER, "&cBack",
                engine.over() ? "&7Back to Simon Says." : "&7Leaves this game (no score)."), e -> back.run());
        if (engine.over()) {
            set(50, Menus.icon(Material.LIME_CONCRETE, daily != null
                    ? "&e" + CabinetGame.dailyAgain("pattern", simon.triedToday(viewer)) : "&ePlay again"),
                    e -> again());
            set(51, Menus.icon(Material.OAK_SIGN, "&eHigh scores"),
                    e -> simon.scores(viewer, daily == null ? Scores.CLASSIC : simon.todayBoard(), back));
        }
    }

    /** One screen tick: play the show, and let a tapped pad go dark again. */
    private void tick() {
        boolean changed = false;
        if (engine.phase() == SimonEngine.Phase.SHOWING) {
            int length = engine.length();
            int step = tempo.litAt(showTick, length);
            if (step >= 0 && step != shownStep) {
                note(engine.step(step));
            }
            int pad = step >= 0 ? engine.step(step) : -1;
            changed = pad != litPad || step != shownStep;
            litPad = pad;
            shownStep = step;
            showTick++;
            if (showTick >= tempo.total(length)) {
                engine.shown();
                litPad = -1;
                shownStep = -1;
                changed = true;
            }
        }
        if (flashLeft > 0 && --flashLeft == 0) {
            flashPad = -1;
            changed = true;
        }
        if (changed) {
            refresh();
        }
    }

    private void press(int pad) {
        SimonEngine.Press result = engine.press(pad);
        if (result == SimonEngine.Press.IGNORED) {
            return;
        }
        flashPad = pad;
        flashLeft = FLASH;
        switch (result) {
            case RIGHT -> note(pad);
            case ROUND_DONE -> {
                note(pad);
                showTick = -BREATHER;
            }
            case COMPLETE -> {
                note(pad);
                finish = simon.finish(viewer, daily, engine.score());
            }
            case WRONG -> finish = simon.finish(viewer, daily, engine.score());
            default -> {
            }
        }
        refresh();
    }

    private ItemStack modeTile() {
        if (daily == null) {
            return Menus.icon(Material.NOTE_BLOCK, "&bClassic", "&7A new pattern every time.");
        }
        if (daily.scored()) {
            return Menus.icon(Material.CLOCK, "&eDaily &7- your scored try",
                    "&7Goal: play back " + SimonSays.DAILY_GOAL + ".", "&cClosing uses up your try.");
        }
        return Menus.icon(Material.CLOCK, "&7Daily &8- practice", "&7Practice isn't recorded.");
    }

    private ItemStack statusTile() {
        int length = engine.length();
        return switch (engine.phase()) {
            case SHOWING -> Menus.count(Menus.icon(Material.ENDER_EYE, "&eWatch! &7Pattern of " + length,
                    "&7Taps don't count until it's your turn."), length);
            case INPUT -> Menus.count(Menus.glint(Menus.icon(Material.LIME_DYE,
                    "&aYour turn! &7" + engine.progress() + " of " + length), true), length);
            case OVER -> {
                List<String> lore = new ArrayList<>();
                if (finish != null && finish.tokens() > 0) {
                    lore.add("&6+" + finish.tokens() + " token" + (finish.tokens() == 1 ? "" : "s"));
                }
                if (finish != null && finish.result().personalBest() && engine.score() > 0 && daily == null) {
                    lore.add("&eNew best!");
                }
                yield Menus.count(Menus.icon(Material.NETHER_STAR, "&7Game over &8- &fyou played back "
                        + engine.score(), lore.toArray(new String[0])), Math.max(1, engine.score()));
            }
        };
    }

    private void again() {
        if (!simon.mayPlay(viewer)) {
            return;
        }
        if (daily == null) {
            new SimonSaysPlayMenu(plugin, simon, viewer, back, null, simon.classicSeed()).open(viewer);
        } else {
            CabinetGame.DailyStart next = simon.startDaily(viewer);
            new SimonSaysPlayMenu(plugin, simon, viewer, back, next, next.seed()).open(viewer);
        }
    }

    private void note(int pad) {
        viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, PITCH[pad]);
    }
}
