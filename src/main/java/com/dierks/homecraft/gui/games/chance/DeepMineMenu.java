package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.chance.slots.DeepMine;
import com.dierks.homecraft.games.chance.slots.DeepMineEngine;
import com.dierks.homecraft.games.chance.slots.DeepMineEngine.Dig;
import com.dierks.homecraft.games.chance.slots.DeepMineSettings;
import com.dierks.homecraft.games.chance.slots.Line;
import com.dierks.homecraft.games.chance.slots.ReelShow;
import com.dierks.homecraft.games.chance.slots.SlotTheme;
import com.dierks.homecraft.games.chance.slots.SlotsCopy;
import com.dierks.homecraft.games.chance.slots.SlotsEngine;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.LineOdds;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.Spin;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;
import com.dierks.homecraft.games.chance.slots.Symbol;
import com.dierks.homecraft.gui.MenuStyle;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Deep Mine's screen: three rows of three reels, and every row is a line. It has a layout of its own (the shared
 * slot screen's Spin button sits where the bottom row is here) and borrows that screen's pieces: the reel and
 * paytable tiles ({@link SlotsMenu}), the tokens-or-money switch and chips ({@link MoneyPlay}), and the reels' show
 * ({@link ReelShow}).
 *
 * <pre>
 *  row 0   How to play | the paytable for ONE row, top line first (7 lines, "×N · 1 in N")
 *  row 1            ▶  reel  reel  reel  ◀        (12 13 14: the top row)
 *  row 2   result ▶  reel  reel  reel  ◀  Spin   (21 22 23: the middle row; result 19, Spin 25)
 *  row 3            ▶  reel  reel  reel  ◀        (30 31 32: the bottom row)
 *  row 4          what goes on each row: stake buttons centred on 40, or the chips (39 40 41) and their amount (42)
 *  row 5   46 gives back about N · 47 plays left · 48 today · 49 Back · 51 balance (0.59.0: it was 50) ·
 *          53 tokens or money
 * </pre>
 *
 * <p>The player picks what goes on EACH row; a spin always plays all three, and the Spin button says so
 * ("3 lines × 2 = 6 tokens in"). The whole spin is decided and paid before the reels move
 * ({@code ChanceRounds.play}, one round for the three rows); this screen is the show. When the reels stop, the marker
 * beside each row says what that row gave back, and the result gives the spin's one total: a win only when more came
 * back than the three rows cost. For money the chips build what goes on a row, and a chip is unlit when three rows
 * of it would be more than the Parlor allows.
 */
public final class DeepMineMenu extends GameMenu {

    private static final int RULES = 0;
    /** The paytable: one slot per {@link Line}, in its order. */
    private static final int PAYTABLE = 1;
    /** The three rows' reels, top row first. */
    private static final int[][] REELS = {{12, 13, 14}, {21, 22, 23}, {30, 31, 32}};
    private static final int[] POINT_RIGHT = {11, 20, 29};
    private static final int[] POINT_LEFT = {15, 24, 33};
    private static final int RESULT = 19;
    private static final int SPIN = 25;
    private static final int STAKES_CENTRE = 40;
    private static final int GIVE_BACK = 46;
    private static final int PLAYS = 47;
    private static final int TODAY = 48;
    /** 0.59.0: 51, where every other game of chance shows the balance (it was 50). */
    private static final int BALANCE = 51;
    private static final int SWITCH = 53;
    private static final int[] CHIPS = {39, 40, 41};
    private static final int CHIPS_BACK = 42;

    private final DeepMine deep;
    private final DeepMineSettings settings;
    private final SlotTheme theme;
    private final boolean bedrock;
    /** Tokens on each row for the next spin. */
    private int perLine;
    /** What each row shows now ({@code null} = a blank reel). */
    private final List<List<Symbol>> shown = new ArrayList<>();
    /** The spin on screen: the one spinning, or the last one. */
    private Dig dig;
    private boolean spinning;
    private boolean told = true;
    private int tick;
    private int stopped;
    private SplittableRandom cosmetic;
    private BukkitTask task;
    /** Tokens or money for the next spin; the chips build what goes on a row, so a spin is three times them. */
    private final MoneyPlay money;
    /** Whether the spin on screen is (or was) for dollars, and the line its rows were drawn by. */
    private boolean spinMoney;
    private SlotsEngine spinLine;

    public DeepMineMenu(HomeCraftManagement plugin, DeepMine game, Player viewer, Runnable back,
                        DeepMineSettings settings) {
        super(plugin, game, viewer, back);
        this.deep = game;
        this.settings = settings;
        this.theme = settings.theme();
        this.bedrock = Bedrock.is(viewer);
        this.perLine = settings.offered().isEmpty() ? 0 : settings.offered().get(0);
        this.money = new MoneyPlay(plugin, plugin.games(), viewer, DeepMineEngine.LINES);
        for (int r = 0; r < DeepMineEngine.LINES; r++) {
            shown.add(blank());
        }
        init(54, Text.of(MenuStyle.title(game.name())));
    }

    /** What goes on each row for the next spin: the tokens picked, or the chips' dollars. */
    private int row() {
        return money.on() ? money.stake() : perLine;
    }

    /** The line the next spin's rows are drawn by, or {@code null} when that amount on a row isn't played. */
    private SlotsEngine live() {
        return money.on() ? deep.moneyEngine(settings, money.stake()) : settings.engine(perLine);
    }

    /** A line written for tokens, as this screen says it now. */
    private String say(String line) {
        return MoneyPlay.say(money.on(), line);
    }

    @Override
    protected void build() {
        fill();
        boolean dollars = money.on();
        SlotsEngine live = live();
        int row = row();
        set(RULES, rulesTile(game.rules()), null);
        StakeOdds odds = live == null ? null : live.odds(row);
        if (odds != null) {
            for (Line line : Line.values()) {
                LineOdds l = odds.line(line);
                if (l != null) {
                    set(PAYTABLE + line.ordinal(),
                            SlotsMenu.lineTile(theme, l, dollars, SlotsCopy.deepLineLore(theme, l)), null);
                }
            }
            DeepMineEngine spin = new DeepMineEngine(live, row);
            set(GIVE_BACK, Menus.glint(Menus.icon(Material.KNOWLEDGE_BOOK, say(SlotsCopy.giveBack(settings, odds)),
                    "&7For " + SlotsCopy.deepStake(row, dollars) + ",", "&7worked out exactly from the paytable.",
                    SlotsCopy.deepHitLine(spin.anyRowPays())), false), null);
        }
        set(RESULT, resultTile(), null);
        for (int r = 0; r < REELS.length; r++) {
            ItemStack marker = markerTile(r);
            set(POINT_RIGHT[r], marker, null);
            set(POINT_LEFT[r], marker, null);
            for (int i = 0; i < REELS[r].length; i++) {
                set(REELS[r][i], SlotsMenu.reelTile(theme, shown.get(r).get(i)), null);
            }
        }
        int left = deep.playsLeft(viewer.getUniqueId(), settings);
        set(SPIN, spinTile(left), e -> spin());
        set(PLAYS, Menus.icon(Material.CLOCK, "&ePlays left today: &f" + (left < 0 ? "?" : left),
                "&7" + settings.dailyLimit() + " spins a day.", "&7They come back at midnight."), null);
        if (dollars) {
            for (int i = 0; i < money.chips() && i < CHIPS.length; i++) {
                int chip = money.chip(i);
                set(CHIPS[i], money.chipTile(i), e -> {
                    if (!spinning) {
                        money.add(chip);
                        refresh();
                    }
                });
            }
            set(CHIPS_BACK, chipsTile(), e -> {
                if (!spinning) {
                    money.clear();
                    refresh();
                }
            });
            set(TODAY, money.todayTile(game, settings.dailyLimit(), "spins"), null);
            set(BALANCE, money.balanceTile(), null);
        } else {
            List<Integer> stakes = settings.offered();
            int first = STAKES_CENTRE - (stakes.size() - 1) / 2;
            for (int i = 0; i < stakes.size(); i++) {
                int s = stakes.get(i);
                set(first + i, stakeTile(s), e -> pick(s));
            }
            set(TODAY, Menus.icon(Material.HOPPER, deep.todayLine(viewer.getUniqueId()),
                    "&7Tokens put into games of chance today.", "&7You can set a lower limit in Take a break."), null);
            set(BALANCE, balanceTile(), null);
        }
        if (money.offered() || dollars) {
            set(SWITCH, money.switchTile(), e -> {
                if (!spinning) {
                    money.flip();
                    refresh();
                }
            });
        }
        exitTile();
    }

    // ---- tiles ---------------------------------------------------------------------------------

    /** The marker at both ends of a row: which row it is, and once a spin has stopped, what it gave back. */
    private ItemStack markerTile(int r) {
        String name = "&e" + DeepMineEngine.rowName(r) + " row";
        if (dig == null || spinning) {
            return Menus.icon(Material.YELLOW_STAINED_GLASS_PANE, name, "&7This row is a line of its own.");
        }
        Spin row = dig.rows().get(r);
        boolean paid = row.payout() > 0;
        return Menus.glint(Menus.icon(paid ? Material.LIME_STAINED_GLASS_PANE : Material.YELLOW_STAINED_GLASS_PANE,
                name, MoneyPlay.say(spinMoney, SlotsCopy.deepRow(theme, r, row))), paid);
    }

    private ItemStack resultTile() {
        if (spinning) {
            return Menus.icon(Material.PAPER, "&7Spinning…");
        }
        if (dig == null) {
            return Menus.icon(Material.PAPER, money.on() ? "&7Add your chips, then Spin."
                    : "&7Pick what goes on each row, then Spin.", "&7All three rows count.");
        }
        return Menus.glint(Menus.icon(Material.PAPER, MoneyPlay.say(spinMoney, SlotsCopy.deepResult(theme, dig)),
                MoneyPlay.say(spinMoney, SlotsCopy.deepRows(theme, dig))), dig.win());
    }

    private ItemStack spinTile(int left) {
        if (spinning) {
            return Menus.icon(Material.GRAY_CONCRETE, "&7Spinning…");
        }
        if (left == 0) {
            return Menus.icon(Material.GRAY_CONCRETE, "&7No plays left today",
                    "&7" + game.name() + " gives " + settings.dailyLimit() + " spins a day.",
                    "&7They come back at midnight.");
        }
        boolean dollars = money.on();
        int row = row();
        if (dollars) {
            if (row <= 0) {
                return Menus.icon(Material.GRAY_CONCRETE, "&7Add a chip first");
            }
            String why = live() == null ? game.name() + " can't be played for " + Parlor.dollars(row) + " a row"
                    : deep.rowsBlocker(viewer, row);
            if (why != null) {
                return Menus.icon(Material.GRAY_CONCRETE, "&c" + why);
            }
        } else {
            int total = DeepMineEngine.total(row);
            int balance = plugin.tokens() == null ? 0 : plugin.tokens().balance(viewer.getUniqueId());
            if (balance < total) {
                return Menus.icon(Material.GRAY_CONCRETE, "&cNeed " + (total - balance) + " more tokens",
                        "&7A spin is " + SlotsCopy.deepStake(row, false) + ".");
            }
        }
        return Menus.icon(Material.LIME_CONCRETE, SlotsCopy.deepSpin(row, dollars),
                "&7Every row is a line of its own.", "&7The spin is decided the moment you click.",
                "&7Closing the screen just shows the result.");
    }

    private ItemStack stakeTile(int s) {
        boolean chosen = s == perLine;
        StakeOdds odds = settings.bank().odds(s);
        String line = odds == null ? "" : SlotsCopy.giveBack(settings, odds).replace("&e", "&7");
        ItemStack icon = Menus.icon(Material.GOLD_NUGGET, (chosen ? "&a&l" : "&e") + SlotsCopy.deepStake(s, false),
                chosen ? "&7Picked." : "&eClick to pick", line);
        return Menus.glint(Menus.count(icon, s), chosen);
    }

    /** What the chips add up to on a row, and the whole spin; a click takes them back. */
    private ItemStack chipsTile() {
        if (money.stake() <= 0) {
            return money.stakeTile();
        }
        return Menus.glint(Menus.icon(Material.RAW_GOLD, "&6" + SlotsCopy.deepStake(money.stake(), true),
                "&eClick to take your chips back"), true);
    }

    // ---- clicks --------------------------------------------------------------------------------

    private void pick(int s) {
        if (spinning || s == perLine) {
            return;
        }
        perLine = s;
        refresh();
    }

    /** Play one spin of the three rows (gate, seed, outcome, what goes in and comes back: all here), then show it. */
    private void spin() {
        if (spinning) {
            return;
        }
        GamesService games = plugin.games();
        if (games == null) {
            viewer.sendMessage(Text.of("&c" + Refusal.CLOSED.message()));
            Sounds.refused(viewer);
            return;
        }
        boolean dollars = money.on();
        int row = row();
        if (row <= 0 || live() == null) {
            refresh();
            return;
        }
        DeepMine.Played played = deep.spin(viewer, settings, row, dollars);
        if (played == null) {
            refresh(); // refused, and already told why
            return;
        }
        spinMoney = dollars;
        spinLine = played.line();
        dig = played.dig();
        spinning = true;
        told = false;
        tick = 0;
        stopped = 0;
        cosmetic = new SplittableRandom(ThreadLocalRandom.current().nextLong());
        frame(0);
        refresh();
        task = ticker(ReelShow.period(bedrock), this::step);
    }

    /** What every row shows once {@code now} of its reels have stopped: never a paying line before the last one. */
    private void frame(int now) {
        for (int r = 0; r < DeepMineEngine.LINES; r++) {
            shown.set(r, ReelShow.frame(spinLine, dig.perLine(), dig.rows().get(r).reels(), now, cosmetic));
        }
    }

    /** One frame: the reels stop a column at a time, left to right, on every row together. */
    private void step() {
        if (!spinning) {
            return;
        }
        tick += ReelShow.period(bedrock);
        int now = ReelShow.stopped(bedrock, tick);
        frame(now);
        paintReels();
        if (now > stopped) {
            stopped = now;
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.8f + 0.2f * now);
        }
        if (now >= REELS[0].length) {
            land();
        }
    }

    private void land() {
        spinning = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        refresh();
        tell();
    }

    private void paintReels() {
        for (int r = 0; r < REELS.length; r++) {
            for (int i = 0; i < REELS[r].length; i++) {
                getInventory().setItem(REELS[r][i], SlotsMenu.reelTile(theme, shown.get(r).get(i)));
            }
        }
    }

    /** The result, once: what each row that paid gave back, then the spin's one total. */
    private void tell() {
        if (told || dig == null) {
            return;
        }
        told = true;
        for (String line : SlotsCopy.deepRows(theme, dig)) {
            viewer.sendMessage(Text.of(MoneyPlay.say(spinMoney, line)));
        }
        viewer.sendMessage(Text.of(MoneyPlay.say(spinMoney, SlotsCopy.deepResult(theme, dig))));
        if (dig.win()) {
            Sounds.won(viewer);
        } else {
            Sounds.miss(viewer);
        }
    }

    @Override
    protected void onClose(Player player) {
        super.onClose(player);
        task = null;
        if (spinning) {
            spinning = false;
            for (int r = 0; r < DeepMineEngine.LINES; r++) {
                shown.set(r, dig.rows().get(r).reels());
            }
        }
        tell();
    }

    private static List<Symbol> blank() {
        List<Symbol> out = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            out.add(null);
        }
        return out;
    }
}
