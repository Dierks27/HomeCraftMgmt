package com.dierks.homecraft.gui.games.cabinet.match;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.match.MatchEngine;
import com.dierks.homecraft.games.cabinet.match.MiniMatch;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * One Mini Match board (54 slots): the 4 by 4 cards in rows 1-4, columns 2-5 (spec §10b).
 *
 * <p>The screen draws only the engine's public view: a face-down card is always the game's one
 * shared face-down item, a face-up card its face, a found pair its face with a tick and a shine.
 * A miss turns back by itself after about a second (a second and a half on Bedrock) — or at once
 * when the next card is tapped. The faces are built once, when the board is dealt.
 *
 * <p>Closing the screen ends the board with no score (the Daily's scored try is already used by
 * then — the first screen said so). When the last pair is found the game records and pays at
 * once, then offers Play again and the high scores.
 *
 * <pre>
 *   M  .  .  .  S  .  .  .  .      0 mode · 4 flips so far
 *   .  .  c  c  c  c  .  .  .
 *   .  .  c  c  c  c  .  .  .      the 16 cards
 *   .  .  c  c  c  c  .  .  .
 *   .  .  c  c  c  c  .  .  .
 *   .  B  .  ?  X  A  H  .  .      46 best · 48 how to play · 49 back · 50 play again · 51 high scores
 * </pre>
 */
public final class MiniMatchPlayMenu extends GameMenu {

    /** Card i's slot: rows 1-4, columns 2-5. */
    private static final int[] CARDS = {11, 12, 13, 14, 20, 21, 22, 23, 29, 30, 31, 32, 38, 39, 40, 41};

    private final MiniMatch match;
    private final CabinetGame.DailyStart daily;
    private final MatchEngine engine;
    private final List<ItemStack> faces;
    private final List<ItemStack> found = new ArrayList<>();
    private BukkitTask hide;
    private CabinetGame.Finish finish;

    /**
     * @param back  the first screen
     * @param daily the daily start, or {@code null} for Classic
     */
    public MiniMatchPlayMenu(HomeCraftManagement plugin, MiniMatch game, Player viewer, Runnable back,
                             CabinetGame.DailyStart daily, long seed) {
        super(plugin, game, viewer, back);
        this.match = game;
        this.daily = daily;
        this.engine = new MatchEngine(seed);
        this.faces = game.faces(viewer, seed);
        for (ItemStack face : faces) {
            ItemStack f = face.clone();
            var meta = f.getItemMeta();
            if (meta != null && meta.displayName() != null) {
                meta.displayName(Text.of("&a✔ ").append(meta.displayName()));
                f.setItemMeta(meta);
            }
            found.add(Menus.glint(f, true));
        }
        init(54, Text.of("&bMini Match"));
    }

    @Override
    protected void build() {
        fill();
        set(0, modeTile(), null);
        set(4, statusTile(), null);
        for (int i = 0; i < MatchEngine.CARDS; i++) {
            int card = i;
            int face = engine.face(i);
            ItemStack item = face < 0 ? match.faceDown() : (engine.matched(i) ? found.get(face) : faces.get(face));
            set(CARDS[i], item, engine.done() ? null : e -> flip(card));
        }
        Long best = match.best(viewer);
        set(46, Menus.icon(Material.GOLD_NUGGET, best == null ? "&7No best yet" : "&eYour best: &f" + best + " flips"),
                null);
        set(48, rulesTile(match.rules()), null);
        set(49, Menus.icon(Material.BARRIER, "&cBack",
                engine.done() ? "&7Back to Mini Match." : "&7Leaves this board (no score)."), e -> back.run());
        if (engine.done()) {
            boolean dailyAgain = daily != null;
            set(50, Menus.icon(Material.LIME_CONCRETE,
                    dailyAgain ? "&ePlay today's board again &7(practice)" : "&ePlay again"), e -> again());
            set(51, Menus.icon(Material.OAK_SIGN, "&eHigh scores"),
                    e -> match.scores(viewer, daily == null ? Scores.CLASSIC : match.todayBoard(), back));
        }
    }

    private ItemStack modeTile() {
        if (daily == null) {
            return Menus.icon(Material.PAINTING, "&bClassic", "&7A new board every time.");
        }
        if (daily.scored()) {
            return Menus.icon(Material.CLOCK, "&eDaily &7- your scored try",
                    "&7Goal: " + MiniMatch.DAILY_GOAL + " flips or fewer.", "&cClosing uses up your try.");
        }
        return Menus.icon(Material.CLOCK, "&7Daily &8- practice", "&7Practice isn't recorded.");
    }

    private ItemStack statusTile() {
        int flips = engine.flips();
        if (engine.done()) {
            List<String> lore = new ArrayList<>();
            if (finish != null && finish.tokens() > 0) {
                lore.add("&6+" + finish.tokens() + " token" + (finish.tokens() == 1 ? "" : "s"));
            }
            if (finish != null && finish.result().personalBest()) {
                lore.add("&eNew best!");
            }
            return Menus.count(Menus.glint(Menus.icon(Material.NETHER_STAR, "&aDone in " + flips + " flips!",
                    lore.toArray(new String[0])), true), flips);
        }
        return Menus.count(Menus.glint(Menus.icon(Material.PAPER,
                "&bFlips: &f" + flips + " &7· pairs " + engine.pairsFound() + " of " + MatchEngine.PAIRS,
                "&7Each go of two cards is one flip."), false), Math.max(1, flips));
    }

    private void flip(int card) {
        if (hide != null) {
            hide.cancel();
            hide = null;
        }
        MatchEngine.Flip result = engine.flip(card);
        switch (result) {
            case FIRST -> sound(Sound.ITEM_BOOK_PAGE_TURN, 1.2f);
            case MATCH -> sound(Sound.BLOCK_NOTE_BLOCK_CHIME, 1.4f);
            case MISS -> {
                sound(Sound.ITEM_BOOK_PAGE_TURN, 0.9f);
                hide = later(Bedrock.is(viewer) ? 30 : 20, () -> {
                    hide = null;
                    if (engine.hideMiss()) {
                        refresh();
                    }
                });
            }
            case DONE -> finish = match.finish(viewer, daily, engine.flips());
            case IGNORED -> {
            }
        }
        refresh();
    }

    private void again() {
        if (daily == null) {
            new MiniMatchPlayMenu(plugin, match, viewer, back, null, match.classicSeed()).open(viewer);
        } else {
            CabinetGame.DailyStart next = match.startDaily(viewer);
            new MiniMatchPlayMenu(plugin, match, viewer, back, next, next.seed()).open(viewer);
        }
    }

    /** Run {@code task} once, {@code delay} ticks from now, while this screen stays open. */
    private BukkitTask later(long delay, Runnable task) {
        BukkitTask[] self = new BukkitTask[1];
        self[0] = ticker(delay, () -> {
            self[0].cancel();
            task.run();
        });
        return self[0];
    }

    private void sound(Sound sound, float pitch) {
        viewer.playSound(viewer.getLocation(), sound, 0.6f, pitch);
    }
}
