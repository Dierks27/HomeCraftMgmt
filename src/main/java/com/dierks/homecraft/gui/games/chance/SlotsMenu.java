package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.chance.slots.FreeSpinsEngine;
import com.dierks.homecraft.games.chance.slots.Line;
import com.dierks.homecraft.games.chance.slots.MobSlots;
import com.dierks.homecraft.games.chance.slots.MobSlotsSettings;
import com.dierks.homecraft.games.chance.slots.PotSlots;
import com.dierks.homecraft.games.chance.slots.ReelShow;
import com.dierks.homecraft.games.chance.slots.SlotMachine;
import com.dierks.homecraft.games.chance.slots.SlotOdds;
import com.dierks.homecraft.games.chance.slots.SlotSettings;
import com.dierks.homecraft.games.chance.slots.SlotTable;
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
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A slot machine's screen (spec §5.3): the paytable, the line, the stakes and Spin on one screen, so
 * the rules and the odds are in front of the player before the first spin. One screen for every machine:
 * Ore Slots, Nether Slots and the pot machines differ only in their {@link SlotTheme} (the pictures and names) and
 * in what the stakes' row holds.
 *
 * <pre>
 *  row 0   How to play | the paytable, top line first (7 lines, "×N · 1 in N")
 *  row 1               result (13)
 *  row 2            ▶  reel  reel  reel  ◀        (21 22 23: the one line, nothing above or below)
 *  row 3                  Spin (31)
 *  row 4          stake buttons, centred on 40 (a glint on the one picked); a pot machine: the pot (40)
 *  row 5   46 gives back about N · 47 plays left · 48 today's tokens · 49 Back · 50 balance
 * </pre>
 *
 * <p>The spin is decided and paid before the reels move ({@code ChanceRounds.play}); this screen
 * is the show. The reels stop on fixed ticks with cosmetic frames that never show a paying line
 * early ({@link ReelShow}), closing the screen just prints the result, and the result is told
 * once: a win in green with {@code Sounds.won} (and a private title for a line of ×20 or more),
 * "Your N back" or "No win this time." with {@code Sounds.miss}. The buttons stay where they are
 * after any result; nothing asks the player to spin again. Every number shown comes from the
 * engine snapshot this screen was opened with.
 *
 * <p><b>A pot machine</b> ({@link PotSlots}: The Motherlode, Farm Slots) has one set price in dollars and is played
 * for money only: no tokens-or-money switch, no chips and no stake buttons. The pot is where the stakes would be;
 * the three-Wilds line on the paytable reads "takes the pot"; and Spin is lit only for a player who may play for
 * money where they stand, with one plain reason when it is not ("Only in the Game Parlor").
 *
 * <p><b>Mob Slots</b> ({@link MobSlots}) is this screen too. Its Creeper line on the paytable says the free spins it
 * gives, and what it "gives back" is the whole round's exact return, the free spins counted. When the Creepers land,
 * the result tile reads "N free spins!" and each free spin's reels then land in turn with what the round has given
 * back so far, and last the round's one total. All of them were decided and paid with the first spin: closing the
 * screen early just prints the total.
 */
public final class SlotsMenu extends GameMenu {

    private static final int RULES = 0;
    /** The paytable: one slot per {@link Line}, in its order. */
    private static final int PAYTABLE = 1;
    private static final int RESULT = 13;
    private static final int[] REELS = {21, 22, 23};
    private static final int POINT_RIGHT = 20;
    private static final int POINT_LEFT = 24;
    private static final int SPIN = 31;
    private static final int STAKES_CENTRE = 40;
    private static final int GIVE_BACK = 46;
    private static final int PLAYS = 47;
    private static final int TODAY = 48;
    private static final int BALANCE = 50;
    /** 0.46.0, in the Game Parlor: tokens or money, the chips (on the stakes' row) and what they add up to. */
    private static final int SWITCH = 53;
    private static final int[] CHIPS = {39, 40, 41};
    private static final int CHIPS_BACK = 42;

    /** Ticks between two spins of a round with free spins: long enough to read what the last one gave. */
    private static final int REST_TICKS = 16;

    private final SlotMachine slots;
    /** The machine as a pot machine, or {@code null} for one with stakes and no pot. */
    private final PotSlots pot;
    /** The machine as Mob Slots (free spins), or {@code null}. */
    private final MobSlots mob;
    private final SlotTable settings;
    private final SlotTheme theme;
    private final boolean bedrock;
    private int stake;
    /** What the line shows now ({@code null} = a blank reel). */
    private List<Symbol> shown = blank();
    /** The spin on screen: the one spinning, or the last one. */
    private Spin spin;
    /** A pot machine: the whole pot the spin on screen took, in cents (0 for any other spin). */
    private long potWon;
    private boolean spinning;
    private boolean told = true;
    private int tick;
    private int stopped;
    private SplittableRandom cosmetic;
    private BukkitTask task;
    /** 0.46.0: tokens or money for the next spin. */
    private final MoneyPlay money;
    /** Whether the spin on screen is (or was) for dollars, and the engine it was decided by. */
    private boolean spinMoney;
    private SlotsEngine spinEngine;
    /**
     * Mob Slots: the whole round on screen (its first spin and any free spins), already decided and paid; {@code spin}
     * is the one of its spins the reels show now.
     */
    private FreeSpinsEngine.Round round;
    /** How many of the round's spins have landed on screen (1: the first spin; more: free spins). */
    private int landed;
    /** Ticks left before the round's next free spin starts (0: reels are moving, or nothing is). */
    private int rest;

    public SlotsMenu(HomeCraftManagement plugin, SlotMachine game, Player viewer, Runnable back,
                     SlotTable settings) {
        super(plugin, game, viewer, back);
        this.slots = game;
        this.pot = game instanceof PotSlots p && settings instanceof SlotSettings ? p : null;
        this.mob = game instanceof MobSlots m && settings instanceof MobSlotsSettings ? m : null;
        this.settings = settings;
        this.theme = settings.theme();
        this.bedrock = Bedrock.is(viewer);
        this.stake = settings.offered().isEmpty() ? 0 : settings.offered().get(0);
        this.spinEngine = settings.engine();
        this.money = new MoneyPlay(plugin, plugin.games(), viewer);
        init(54, Text.of(MenuStyle.title(game.name())));
    }

    /** Whether the next spin is for dollars: always on a pot machine, else as the switch says. */
    private boolean dollars() {
        return pot != null || money.on();
    }

    /** The engine the next spin is decided by: the screen's own, or the reels solved for the dollars put in. */
    private SlotsEngine live() {
        if (pot != null) {
            return slots.moneyEngine(settings, price());
        }
        return money.on() ? slots.moneyEngine(settings, money.stake()) : settings.engine(stake);
    }

    /** What the next spin puts in: tokens picked, the chips' dollars, or a pot machine's price. */
    private int in() {
        return pot != null ? price() : money.on() ? money.stake() : stake;
    }

    /** A pot machine's one price, in dollars. */
    private int price() {
        return settings.stakes().isEmpty() ? 0 : settings.stakes().get(0);
    }

    /** A line written for tokens, as this screen says it now. */
    private String say(String line) {
        return MoneyPlay.say(dollars(), line);
    }

    @Override
    protected void build() {
        fill();
        boolean dollars = dollars();
        SlotsEngine live = live();
        int stake = in();
        long potNow = pot == null ? 0 : pot.potCents();
        set(RULES, rulesTile(game.rules()), null);
        StakeOdds odds = live == null ? null : live.odds(stake);
        if (odds != null) {
            for (Line line : Line.values()) {
                LineOdds l = odds.line(line);
                if (l != null) {
                    set(PAYTABLE + line.ordinal(), payTile(l, potNow), null);
                }
            }
            List<String> lore = new ArrayList<>();
            lore.add(say("&7For " + SlotsCopy.tokens(stake) + " in, worked out"));
            lore.add("&7exactly from the paytable.");
            if (pot != null && settings.feedPercent() > 0) {
                lore.add("&7The pot's share is counted in.");
            }
            if (settings.freeSpins() > 0) {
                lore.add("&7The free spins are counted in.");
            }
            lore.add(SlotsCopy.hitLine(odds));
            if (settings.freeSpins() > 0) {
                lore.add(SlotsCopy.bonusHitLine(theme, settings.freeSpins(), SlotOdds.threeWilds(live, stake)));
            }
            set(GIVE_BACK, Menus.glint(Menus.icon(Material.KNOWLEDGE_BOOK, say(SlotsCopy.giveBack(settings, odds)),
                    lore.toArray(new String[0])), false), null);
        }
        set(RESULT, resultTile(), null);
        set(POINT_RIGHT, Menus.icon(Material.YELLOW_STAINED_GLASS_PANE, "&e▶"), null);
        set(POINT_LEFT, Menus.icon(Material.YELLOW_STAINED_GLASS_PANE, "&e◀"), null);
        for (int i = 0; i < REELS.length; i++) {
            set(REELS[i], reelTile(shown.get(i)), null);
        }
        int left = slots.playsLeft(viewer.getUniqueId(), settings);
        set(SPIN, spinTile(left), e -> spin());
        set(PLAYS, Menus.icon(Material.CLOCK, "&ePlays left today: &f" + (left < 0 ? "?" : left),
                "&7" + settings.dailyLimit() + " spins a day.", "&7They come back at midnight."), null);
        if (pot != null) {
            set(STAKES_CENTRE, Menus.glint(Menus.icon(Material.GOLD_BLOCK, SlotsCopy.potName(potNow),
                    SlotsCopy.potLore(theme, pot.feedCents((SlotSettings) settings),
                            pot.seedCents((SlotSettings) settings)).toArray(new String[0])), true), null);
            set(TODAY, money.todayTile(game, settings.dailyLimit(), "spins"), null);
            set(BALANCE, money.balanceTile(), null);
            exitTile();
            return;
        }
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
            set(CHIPS_BACK, money.stakeTile(), e -> {
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
            set(TODAY, Menus.icon(Material.HOPPER, slots.todayLine(viewer.getUniqueId()),
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

    private ItemStack payTile(LineOdds l, long potNow) {
        if (pot != null && l.line() == Line.THREE_WILDS) {
            return Menus.glint(Menus.icon(theme.icon(l.line()), SlotsCopy.potLineName(theme, l),
                    SlotsCopy.potLineLore(theme, l, potNow).toArray(new String[0])), true);
        }
        if (settings.freeSpins() > 0 && l.line() == Line.THREE_WILDS) {
            // Mob Slots: the Creeper line also gives the free spins
            return Menus.glint(Menus.icon(theme.icon(l.line()),
                    say(SlotsCopy.bonusLineName(theme, l, settings.freeSpins())),
                    MoneyPlay.say(dollars(), SlotsCopy.bonusLineLore(theme, l, settings.freeSpins()))), true);
        }
        return lineTile(theme, l, dollars(), SlotsCopy.lineLore(theme, l));
    }

    /** A line's tile on any machine's paytable: its picture, "×N · 1 in N", and {@code lore} under it. */
    static ItemStack lineTile(SlotTheme theme, LineOdds l, boolean dollars, List<String> lore) {
        ItemStack icon = Menus.icon(theme.icon(l.line()), MoneyPlay.say(dollars, SlotsCopy.lineName(theme, l)),
                MoneyPlay.say(dollars, lore));
        if (l.line() == Line.TWO_THE_SAME) {
            Menus.count(icon, 2);
        }
        return Menus.glint(icon, false);
    }

    private ItemStack reelTile(Symbol s) {
        return reelTile(theme, s);
    }

    /** One reel's picture on any machine ({@code null}: a blank reel). */
    static ItemStack reelTile(SlotTheme theme, Symbol s) {
        if (s == null) {
            return Menus.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7…");
        }
        return Menus.glint(Menus.icon(theme.icon(s), "&f" + theme.label(s)), false);
    }

    private ItemStack resultTile() {
        if (spinning && round != null && round.bonus() && landed >= 1) {
            // Mob Slots, the free spins landing: what they are, then what the round has given back so far
            String name = landed == 1 ? SlotsCopy.bonusStart(theme, round.free().size())
                    : MoneyPlay.say(spinMoney, SlotsCopy.freeSpinLine(landed - 1, round.free().size(),
                    round.backAfter(landed - 1)));
            return Menus.glint(Menus.icon(Material.PAPER, name, "&7They are already played.",
                    "&7Closing the screen just shows the result."), true);
        }
        if (spinning) {
            return Menus.icon(Material.PAPER, "&7Spinning…");
        }
        if (round != null && round.bonus()) {
            return Menus.glint(Menus.icon(Material.PAPER,
                    MoneyPlay.say(spinMoney, SlotsCopy.bonusResult(theme, round))), round.win());
        }
        if (spin == null) {
            return Menus.icon(Material.PAPER, pot != null ? "&7Spin for " + Parlor.dollars(price()) + "."
                    : money.on() ? "&7Add your chips, then Spin." : "&7Pick your tokens, then Spin.",
                    "&7Only the line between the arrows counts.");
        }
        if (potWon > 0) {
            return Menus.glint(Menus.icon(Material.PAPER, SlotsCopy.potResult(theme, potWon)), true);
        }
        return Menus.glint(Menus.icon(Material.PAPER, MoneyPlay.say(spinMoney, SlotsCopy.result(theme, spin))),
                spin.win());
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
        if (pot != null) {
            String why = pot.blocker(viewer);
            if (why != null) {
                return Menus.icon(Material.GRAY_CONCRETE, "&c" + why,
                        "&7" + game.name() + " is played for money,", "&7in the Clubhouse's Game Parlor.");
            }
            return Menus.icon(Material.LIME_CONCRETE, "&a&lSpin &7- &6" + Parlor.dollars(price()) + " in",
                    "&7The spin is decided the moment you click.", "&7Closing the screen just shows the result.");
        }
        if (money.on()) {
            int dollars = money.stake();
            if (dollars <= 0) {
                return Menus.icon(Material.GRAY_CONCRETE, "&7Add a chip first");
            }
            SlotsEngine live = live();
            String why = live == null ? game.name() + " can't be played for " + Parlor.dollars(dollars)
                    : slots.moneyBlocker(viewer, dollars);
            if (why != null) {
                return Menus.icon(Material.GRAY_CONCRETE, "&c" + why);
            }
            return Menus.icon(Material.LIME_CONCRETE, "&a&lSpin &7- &6" + Parlor.dollars(dollars) + " in",
                    "&7The spin is decided the moment you click.", "&7Closing the screen just shows the result.");
        }
        String why = slots.tokenBlocker(viewer, settings, stake);
        if (why != null) {
            return Menus.icon(Material.GRAY_CONCRETE, "&c" + why, "&7A spin is " + SlotsCopy.tokens(stake) + " in.");
        }
        return Menus.icon(Material.LIME_CONCRETE, "&a&lSpin &7- &6" + SlotsCopy.tokens(stake) + " in",
                "&7The spin is decided the moment you click.", "&7Closing the screen just shows the result.");
    }

    private ItemStack stakeTile(int s) {
        boolean chosen = s == stake;
        SlotsEngine at = settings.engine(s);
        StakeOdds odds = at == null ? null : at.odds(s);
        String line = odds == null ? "" : SlotsCopy.giveBack(settings, odds).replace("&e", "&7");
        ItemStack icon = Menus.icon(Material.GOLD_NUGGET,
                (chosen ? "&a&l" : "&e") + SlotsCopy.tokens(s) + " in",
                chosen ? "&7Picked." : "&eClick to pick", line);
        return Menus.glint(Menus.count(icon, s), chosen);
    }

    // ---- clicks --------------------------------------------------------------------------------

    private void pick(int s) {
        if (spinning || s == stake) {
            return;
        }
        stake = s;
        refresh();
    }

    /** Play one spin (gate, seed, outcome, what goes in and comes back: all done here), then show it. */
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
        if (pot != null) {
            PotSlots.PotSpin played = pot.spin(viewer); // the round, then the pot: fed, or taken
            if (played == null) {
                refresh(); // refused, and already told why
                return;
            }
            show(played.spin(), played.engine(), true, played.potCents());
            return;
        }
        boolean dollars = money.on();
        SlotsEngine live = live();
        int stake = in();
        if (live == null || live.odds(stake) == null) {
            refresh();
            return;
        }
        if (mob != null) {
            // the spin and, if the Creepers land, its free spins: one round, decided and paid here
            MobSlots.Played played = mob.spin(viewer, (MobSlotsSettings) settings, stake, dollars);
            if (played == null) {
                refresh(); // refused, and already told why
                return;
            }
            round = played.round();
            show(round.base(), played.spin(), dollars, 0);
            return;
        }
        Spin result = games.rounds().play(viewer, game, stake, live, dollars);
        if (result == null) {
            refresh(); // refused, and already told why
            return;
        }
        show(result, live, dollars, 0);
    }

    /** Start the reels for a spin that is already decided and paid. */
    private void show(Spin result, SlotsEngine live, boolean dollars, long pot) {
        spinMoney = dollars;
        spinEngine = live;
        spin = result;
        potWon = pot;
        spinning = true;
        told = false;
        landed = 0;
        rest = 0;
        tick = 0;
        stopped = 0;
        cosmetic = new SplittableRandom(ThreadLocalRandom.current().nextLong());
        shown = ReelShow.frame(live, result.stake(), result.reels(), 0, cosmetic);
        getInventory().setItem(RESULT, resultTile());
        getInventory().setItem(SPIN, spinTile(1));
        paintReels();
        task = ticker(ReelShow.period(bedrock), this::step);
    }

    /** One frame: stop the reels whose tick has come, spin the others. */
    private void step() {
        if (!spinning) {
            return;
        }
        if (rest > 0) {
            rest -= ReelShow.period(bedrock);
            if (rest <= 0) {
                nextFreeSpin();
            }
            return;
        }
        tick += ReelShow.period(bedrock);
        int now = ReelShow.stopped(bedrock, tick);
        shown = ReelShow.frame(spinEngine, spin.stake(), spin.reels(), now, cosmetic);
        paintReels();
        if (now > stopped) {
            stopped = now;
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.8f + 0.2f * now);
        }
        if (now >= REELS.length) {
            land();
        }
    }

    /** Mob Slots: start the reels for the round's next free spin (it was decided and paid with the round). */
    private void nextFreeSpin() {
        rest = 0;
        spin = round.free().get(landed - 1);
        tick = 0;
        stopped = 0;
        shown = ReelShow.frame(spinEngine, spin.stake(), spin.reels(), 0, cosmetic);
        paintReels();
    }

    private void land() {
        landed++;
        if (round != null && landed <= round.free().size()) {
            // Mob Slots: a free spin is still to land. A breath to read this one, then the next
            rest = REST_TICKS;
            getInventory().setItem(RESULT, resultTile());
            if (landed == 1) {
                viewer.playSound(viewer.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 0.6f, 1.0f);
            }
            return;
        }
        spinning = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        refresh();
        tell();
    }

    private void paintReels() {
        for (int i = 0; i < REELS.length; i++) {
            getInventory().setItem(REELS[i], reelTile(shown.get(i)));
        }
    }

    /** The result, once: when the reels stop, or when the screen closes first. */
    private void tell() {
        if (told || spin == null) {
            return;
        }
        told = true;
        if (potWon > 0) {
            viewer.sendMessage(Text.of(SlotsCopy.potResult(theme, potWon)));
            Sounds.won(viewer);
            String[] t = SlotsCopy.potTitle(potWon);
            title(t[0], t[1]);
            return;
        }
        if (round != null && round.bonus()) {
            // Mob Slots, a round with free spins: its one total, once
            viewer.sendMessage(Text.of(MoneyPlay.say(spinMoney, SlotsCopy.bonusResult(theme, round))));
            if (!round.win()) {
                Sounds.miss(viewer);
                return;
            }
            Sounds.won(viewer);
            String[] t = SlotsCopy.bonusTitle(round);
            title(t[0], MoneyPlay.say(spinMoney, t[1]));
            return;
        }
        viewer.sendMessage(Text.of(MoneyPlay.say(spinMoney, SlotsCopy.result(theme, spin))));
        if (!spin.win()) {
            Sounds.miss(viewer);
            return;
        }
        Sounds.won(viewer);
        if (spin.big()) {
            String[] t = SlotsCopy.title(theme, spin);
            title(t[0], MoneyPlay.say(spinMoney, t[1]));
        }
    }

    private void title(String top, String under) {
        try {
            viewer.showTitle(Title.title(Text.of(top), Text.of(under),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(500))));
        } catch (RuntimeException ignored) {
            // cosmetic: the round is already paid and the chat line said so
        }
    }

    @Override
    protected void onClose(Player player) {
        super.onClose(player);
        task = null;
        if (spinning) {
            spinning = false;
            shown = spin.reels();
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
