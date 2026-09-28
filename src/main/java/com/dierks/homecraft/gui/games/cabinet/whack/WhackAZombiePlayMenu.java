package com.dierks.homecraft.gui.games.cabinet.whack;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.whack.WhackAZombie;
import com.dierks.homecraft.games.cabinet.whack.WhackEngine;
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
 * One round of Whack-a-Zombie (54 slots): nine holes in a spaced 3 by 3 grid, so a tap on a phone
 * lands where it's meant to (spec §10b).
 *
 * <p>A three-second "Get ready" first, then the screen's ticker moves the engine on a tenth of a
 * second at a time and redraws only the holes that changed. Bedrock ticks half as often (two
 * engine steps per tick, so the round is just as long) and the engine gives it longer pops. A
 * whack is a click on the hole: a zombie is +1, a villager -1 with a friendly "don't bonk the
 * villager!", an empty hole nothing. Closing ends the round with no score.
 *
 * <pre>
 *   M  .  .  P  C  .  .  .  .      0 mode · 3 points · 4 clock
 *   .  .  h  .  h  .  h  .  .
 *   .  .  h  .  h  .  h  .  .      the nine holes
 *   .  .  h  .  h  .  h  .  .
 *   .  .  .  .  .  .  .  .  .
 *   .  B  .  ?  X  A  H  .  .      46 best · 48 how to play · 49 back · 50 play again · 51 high scores
 * </pre>
 */
public final class WhackAZombiePlayMenu extends GameMenu {

    private static final int[] HOLES = {11, 13, 15, 20, 22, 24, 29, 31, 33};
    /** Server ticks of "Get ready" before the round starts. */
    private static final int READY = 60;

    private final WhackAZombie whack;
    private final CabinetGame.DailyStart daily;
    private final WhackEngine engine;
    private final boolean bedrock;
    private final ItemStack hole;
    private final ItemStack zombie;
    private final ItemStack villager;
    /** What each hole last showed, so a tick only redraws the holes that changed. */
    private final WhackEngine.Kind[] drawn = new WhackEngine.Kind[WhackEngine.HOLES];
    private boolean started;
    private int ready = READY;
    private int drawnSeconds = -1;
    private CabinetGame.Finish finish;

    /**
     * @param back  the first screen
     * @param daily the daily start, or {@code null} for Classic
     */
    public WhackAZombiePlayMenu(HomeCraftManagement plugin, WhackAZombie game, Player viewer, Runnable back,
                                CabinetGame.DailyStart daily, long seed) {
        super(plugin, game, viewer, back);
        this.whack = game;
        this.daily = daily;
        this.bedrock = Bedrock.is(viewer);
        this.engine = new WhackEngine(seed, game.settings().seconds(), bedrock);
        this.hole = Menus.icon(Material.COARSE_DIRT, "&8Empty hole");
        this.zombie = Menus.icon(Material.ZOMBIE_HEAD, "&a&lWhack it! &7+1");
        this.villager = Menus.icon(Material.VILLAGER_SPAWN_EGG, "&c&lVillager! &7Don't bonk!");
        init(54, Text.of("&bWhack-a-Zombie"));
    }

    @Override
    public void open(Player player) {
        super.open(player);
        if (!started && isOpenFor(viewer)) {
            started = true;
            ticker(bedrock ? 4 : 2, this::tick);
        }
    }

    @Override
    protected void build() {
        fill();
        set(0, modeTile(), null);
        set(3, pointsTile(), null);
        set(4, clockTile(), null);
        for (int h = 0; h < WhackEngine.HOLES; h++) {
            int at = h;
            drawn[h] = engine.at(h);
            set(HOLES[h], itemFor(drawn[h]), engine.over() ? null : e -> whack(at));
        }
        Long best = whack.best(viewer);
        set(46, Menus.icon(Material.GOLD_NUGGET,
                best == null || best == 0 ? "&7No best yet" : "&eYour best: &f" + best + " points"), null);
        set(48, rulesTile(whack.rules()), null);
        set(49, Menus.icon(Material.BARRIER, "&cBack",
                engine.over() ? "&7Back to Whack-a-Zombie." : "&7Leaves this round (no score)."), e -> back.run());
        if (engine.over()) {
            set(50, Menus.icon(Material.LIME_CONCRETE,
                    daily != null ? "&ePlay today's round again &7(practice)" : "&ePlay again"), e -> again());
            set(51, Menus.icon(Material.OAK_SIGN, "&eHigh scores"),
                    e -> whack.scores(viewer, daily == null ? Scores.CLASSIC : whack.todayBoard(), back));
        }
    }

    /** One screen tick: the countdown, then a tenth of a second of the round (two on Bedrock). */
    private void tick() {
        if (engine.over()) {
            return;
        }
        int period = bedrock ? 4 : 2;
        if (ready > 0) {
            ready -= period;
            if (ready <= 0) {
                viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.2f);
            }
            paintClock();
            return;
        }
        for (int i = 0; i < period / 2; i++) {
            engine.tick();
        }
        if (engine.over()) {
            finish = whack.finish(viewer, daily, engine.score());
            refresh();
            return;
        }
        for (int h = 0; h < WhackEngine.HOLES; h++) {
            WhackEngine.Kind now = engine.at(h);
            if (now != drawn[h]) {
                drawn[h] = now;
                getInventory().setItem(HOLES[h], itemFor(now));
            }
        }
        paintClock();
    }

    private void whack(int h) {
        if (ready > 0 || engine.over()) {
            return;
        }
        WhackEngine.Hit hit = engine.whack(h);
        switch (hit) {
            case ZOMBIE -> {
                viewer.playSound(viewer.getLocation(), Sound.ENTITY_ZOMBIE_HURT, 0.5f, 1.3f);
                viewer.sendActionBar(Text.of("&a+1 &7zombie bonked"));
            }
            case VILLAGER -> {
                viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_HURT, 0.5f, 1.0f);
                viewer.sendActionBar(Text.of("&cDon't bonk the villager! &7-1"));
            }
            case EMPTY -> {
                return;
            }
        }
        drawn[h] = engine.at(h);
        getInventory().setItem(HOLES[h], itemFor(drawn[h]));
        getInventory().setItem(3, pointsTile());
    }

    private void paintClock() {
        int seconds = ready > 0 ? (ready + 19) / 20 : engine.secondsLeft();
        if (seconds != drawnSeconds) {
            drawnSeconds = seconds;
            getInventory().setItem(4, clockTile());
        }
    }

    private ItemStack itemFor(WhackEngine.Kind kind) {
        if (kind == null) {
            return hole;
        }
        return kind == WhackEngine.Kind.ZOMBIE ? zombie : villager;
    }

    private ItemStack modeTile() {
        if (daily == null) {
            return Menus.icon(Material.ZOMBIE_HEAD, "&bClassic", "&7A new round every time.");
        }
        if (daily.scored()) {
            return Menus.icon(Material.CLOCK, "&eDaily &7- your scored try",
                    "&7Goal: " + WhackAZombie.DAILY_GOAL + " points.", "&cClosing uses up your try.");
        }
        return Menus.icon(Material.CLOCK, "&7Daily &8- practice", "&7Practice isn't recorded.");
    }

    private ItemStack pointsTile() {
        int points = engine.score();
        List<String> lore = new ArrayList<>();
        lore.add("&7Zombies: " + engine.zombies() + " &8· &7villagers bonked: " + engine.villagers());
        if (finish != null && finish.tokens() > 0) {
            lore.add("&6+" + finish.tokens() + " token" + (finish.tokens() == 1 ? "" : "s"));
        }
        if (finish != null && daily == null && points > 0 && finish.result().personalBest()) {
            lore.add("&eNew best!");
        }
        return Menus.count(Menus.icon(Material.EMERALD, "&aPoints: &f" + points, lore.toArray(new String[0])),
                Math.max(1, points));
    }

    private ItemStack clockTile() {
        if (engine.over()) {
            return Menus.icon(Material.CLOCK, "&7Time's up!");
        }
        if (ready > 0) {
            int s = (ready + 19) / 20;
            return Menus.count(Menus.glint(Menus.icon(Material.CLOCK, "&eGet ready… " + s), true), s);
        }
        int left = engine.secondsLeft();
        return Menus.count(Menus.icon(Material.CLOCK, "&e" + left + "s left"), Math.max(1, left));
    }

    private void again() {
        if (daily == null) {
            new WhackAZombiePlayMenu(plugin, whack, viewer, back, null, whack.classicSeed()).open(viewer);
        } else {
            CabinetGame.DailyStart next = whack.startDaily(viewer);
            new WhackAZombiePlayMenu(plugin, whack, viewer, back, next, next.seed()).open(viewer);
        }
    }
}
