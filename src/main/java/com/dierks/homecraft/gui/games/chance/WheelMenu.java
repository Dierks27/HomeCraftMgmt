package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.chance.wheel.Wheel;
import com.dierks.homecraft.games.chance.wheel.WheelOdds;
import com.dierks.homecraft.games.chance.wheel.WheelRing;
import com.dierks.homecraft.games.chance.wheel.WheelSettings;
import com.dierks.homecraft.games.chance.wheel.WheelSpin;
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

/**
 * The Wheel's screen (spec §5.5, R1.7): the wheel, its rules and its odds on one page, so a player
 * sees what every space gives before the first spin.
 *
 * <pre>
 *  ring ring ring ring ring ring ring ring ring      the 24 spaces, clockwise from the top left;
 *  ring  .   rules  .  give-back .  plays   .  ring  each names its exact prize for the chosen
 *  ring  .    .     .  result    .   .      .  ring  stake and how many of 24 spaces show it
 *  ring  .    .     .  today     .   .      .  ring
 *  ring ring ring ring ring ring ring ring ring
 *   .   stake stake stake BACK SPIN tokens how  .    (45 and 53 stay filler: no page arrows here)
 * </pre>
 *
 * <p>The spin was decided and paid before the highlight moves ({@link Wheel#play}); the highlight
 * then travels a fixed path on a fixed schedule to the space it landed on ({@link WheelRing}), and
 * closing the screen just skips to the result. The screen keeps the settings it was opened with, so
 * the prizes on the tiles are the prizes the spin pays even if config is reloaded meanwhile. A
 * space that gives the tokens back reads "Your 10 back" with a neutral sound; only a real win
 * glints or plays the win sound, and a loss just says so — the Spin button stays where it is and
 * nothing asks for another go.
 */
public final class WheelMenu extends GameMenu {

    private static final int RULES = 11;
    private static final int GIVE_BACK = 13;
    private static final int PLAYS = 15;
    private static final int RESULT = 22;
    private static final int TODAY = 31;
    private static final int[] STAKES = {46, 47, 48};
    private static final int SPIN = 50;
    private static final int BALANCE = 51;
    private static final int HOW = 52;
    /** 0.46.0, in the Game Parlor: tokens or money, and the chips' stake (a click takes them back). */
    private static final int SWITCH = 53;
    private static final int CHIPS_BACK = 45;
    /** Win tiles by size, smallest first; bigger prizes than there are colours share the last. */
    private static final Material[] WINS = {Material.YELLOW_CONCRETE, Material.ORANGE_CONCRETE,
            Material.LIME_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.MAGENTA_CONCRETE, Material.PURPLE_CONCRETE};

    private final Wheel wheel;
    /** The settings this screen shows and plays: one snapshot for its whole life. */
    private final WheelSettings settings;
    private final WheelRing.Plan plan;
    private int stake;
    /** The space under the highlight. */
    private int at;
    /** The last spin, or null before the first. */
    private WheelSpin spin;
    private boolean spinning;
    private boolean told = true;
    private int[] path = new int[0];
    private long[] schedule = new long[0];
    private int frame;
    private long ticks;
    private BukkitTask task;
    /** 0.46.0: tokens or money for the next spin. */
    private final MoneyPlay money;
    /** Whether the spin on screen is (or was) for dollars, and the ring it was spun on. */
    private boolean spinMoney;
    private WheelOdds spinOdds;
    /**
     * 0.50.0 (the owner: "anything that shows that you did something"): the prize whose spaces a tap on the ring made
     * glow, or -1. It only shows: where the wheel stops decides the prize, and every space is just as likely.
     */
    private int glowing = -1;

    public WheelMenu(HomeCraftManagement plugin, Wheel wheel, Player viewer, Runnable back, WheelSettings settings) {
        super(plugin, wheel, viewer, back);
        this.wheel = wheel;
        this.settings = settings;
        this.plan = Bedrock.is(viewer) ? WheelRing.BEDROCK : WheelRing.JAVA;
        List<Integer> open = settings.open();
        this.stake = open.isEmpty() ? 0 : open.get(0);
        this.money = new MoneyPlay(plugin, plugin.games(), viewer);
        init(54, Text.of("&5The Wheel"));
    }

    /** The settings the next spin plays: the screen's own, or the ring solved for the dollars the chips add up to. */
    private WheelSettings table() {
        return money.on() ? wheel.moneySettings(settings, money.stake()) : settings;
    }

    /** What the next spin puts in: tokens picked, or the chips' dollars. */
    private int in() {
        return money.on() ? money.stake() : stake;
    }

    /** A line written for tokens, as this screen says it now. */
    private String say(String line) {
        return MoneyPlay.say(money.on(), line);
    }

    @Override
    protected void build() {
        fill();
        boolean dollars = money.on();
        WheelSettings table = table();
        int stake = in();
        // while a spin is shown, the ring is the one it was spun on (the chips may have changed since)
        WheelOdds odds = spinning && spinOdds != null ? spinOdds : table == null ? null : table.odds(stake);
        exitTile();
        if (money.offered() || dollars) {
            set(SWITCH, money.switchTile(), spinning ? null : e -> {
                money.flip();
                spin = null;
                refresh();
            });
        }
        if (dollars) {
            chipButtons();
            set(BALANCE, money.balanceTile(), null);
        }
        if (odds == null) {
            if (dollars) {
                set(RESULT, Menus.icon(Material.PAPER, money.stake() <= 0 ? "&eAdd your chips, then Spin"
                        : "&7The Wheel can't be spun for " + Parlor.dollars(money.stake())), null);
            }
            return;
        }
        for (int i = 0; i < WheelRing.SLOTS.length; i++) {
            int prize = odds.prize(i);
            set(WheelRing.SLOTS[i], space(odds, i), spinning ? null : e -> {
                glowing = glowing == prize ? -1 : prize; // a second tap on the same colour puts it out
                viewer.playSound(viewer.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
                refresh();
            });
        }
        if (spinning || spin != null) {
            set(WheelRing.SLOTS[at], highlight(odds, at), null);
        }
        set(RULES, rulesTile(game.rules()), null);
        set(GIVE_BACK, Menus.icon(Material.COMPARATOR, "&e" + say(odds.giveBack()),
                say("&7At " + odds.stake() + " tokens a spin."), "&7Every space is just as likely."), null);
        int left = wheel.playsLeft(viewer, settings);
        set(PLAYS, Menus.icon(Material.CLOCK, left < 0 ? "&ePlays left today" : "&ePlays left today: &f" + left,
                "&7" + settings.dailyLimit() + " spins a day. More at midnight."), null);
        set(RESULT, glowing >= 0 && !spinning && spin == null ? glowTile(odds) : resultTile(odds), null);
        if (dollars) {
            set(TODAY, money.todayTile(game, settings.dailyLimit(), "spins"), null);
        } else {
            set(TODAY, Menus.icon(Material.GOLD_NUGGET, "&e" + wheel.today(viewer),
                    "&7Tokens put into games of chance today."), null);
            stakeButtons();
            set(BALANCE, balanceTile(), null);
        }
        set(SPIN, spinTile(), e -> spin());
        List<String> how = odds.howItPays();
        set(HOW, Menus.icon(Material.PAPER, "&eHow it pays &7- " + say(odds.stake() + " tokens") + " in",
                MoneyPlay.say(dollars, how)), null);
    }

    /** The chips (money play): each adds to the stake; the stake's own tile takes them back. */
    private void chipButtons() {
        for (int i = 0; i < money.chips() && i < STAKES.length; i++) {
            int chip = money.chip(i);
            set(STAKES[i], money.chipTile(i), spinning ? null : e -> {
                money.add(chip);
                spin = null;
                refresh();
            });
        }
        set(CHIPS_BACK, money.stakeTile(), spinning ? null : e -> {
            money.clear();
            spin = null;
            refresh();
        });
    }

    /** Up to three stake buttons; with more stakes, a window of three around the chosen one. */
    private void stakeButtons() {
        List<Integer> open = settings.open();
        int idx = Math.max(0, open.indexOf(stake));
        int from = Math.max(0, Math.min(idx - 1, open.size() - STAKES.length));
        for (int j = 0; j < STAKES.length && from + j < open.size(); j++) {
            int s = open.get(from + j);
            boolean chosen = s == stake;
            // 0.50.0: the chosen amount is a gold block, the others nuggets, so the choice shows at a glance
            ItemStack icon = Menus.icon(chosen ? Material.GOLD_BLOCK : Material.GOLD_NUGGET,
                    (chosen ? "&a&l✔ " : "&e") + s + " tokens a spin", chosen ? "&7Chosen." : "&7Click to choose.");
            Menus.glint(icon, chosen);
            if (s <= 64) {
                Menus.count(icon, s);
            }
            set(STAKES[j], icon, chosen || spinning ? null : e -> {
                stake = s;
                viewer.playSound(viewer.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
                refresh();
            });
        }
    }

    /**
     * A space: its exact prize for the chosen stake in the name, and how many spaces show it. The spaces of the colour
     * tapped last glow.
     */
    private ItemStack space(WheelOdds odds, int space) {
        int prize = odds.prize(space);
        boolean glow = prize == glowing;
        return Menus.glint(Menus.icon(material(odds, prize), (glow ? "&f» " : "") + say(odds.label(prize)) + " &8· "
                        + odds.spaces(prize) + " of 24", "&7" + odds.odds(prize) + " give this",
                say("&7for " + odds.stake() + " tokens in."), glow ? "&8Tap again to stop the glow." : "&8Tap to make"
                        + " every space like it glow."), glow);
    }

    /** What the glowing spaces give, in the result's place until the next spin. */
    private ItemStack glowTile(WheelOdds odds) {
        return Menus.icon(material(odds, glowing), "&f» " + say(odds.label(glowing)),
                "&7" + odds.spaces(glowing) + " of the 24 spaces give this, the ones glowing.",
                "&7Where the wheel stops decides what you get;", "&7every space is just as likely.");
    }

    private static Material material(WheelOdds odds, int prize) {
        return switch (odds.result(prize)) {
            case NOTHING -> Material.GRAY_CONCRETE;
            case BACK -> Material.WHITE_CONCRETE;
            case WIN -> {
                List<Integer> wins = new ArrayList<>();
                for (int p : odds.distinct()) {
                    if (odds.result(p) == WheelOdds.Result.WIN) {
                        wins.add(0, p); // smallest first
                    }
                }
                yield WINS[Math.min(WINS.length - 1, Math.max(0, wins.indexOf(prize)))];
            }
        };
    }

    /** The highlight: while spinning it names the space it passes; once landed, the result. */
    private ItemStack highlight(WheelOdds odds, int space) {
        int prize = odds.prize(space);
        if (spinning || spin == null) {
            return Menus.glint(Menus.icon(Material.SEA_LANTERN, "&e» " + say(odds.label(prize))), false);
        }
        return switch (spin.result()) {
            case WIN -> Menus.glint(Menus.icon(Material.SEA_LANTERN, "&a» You won &6" + won(spin.prize())), true);
            case BACK -> Menus.glint(Menus.icon(Material.SEA_LANTERN, "&f» Your " + back(spin.prize()) + " back"),
                    false);
            case NOTHING -> Menus.glint(Menus.icon(Material.SEA_LANTERN, "&7» No win this time."), false);
        };
    }

    private ItemStack resultTile(WheelOdds odds) {
        if (spinning) {
            return Menus.icon(Material.COMPASS, "&eSpinning…", "&7Close the screen to skip.");
        }
        if (spin == null) {
            return Menus.icon(Material.PAPER, money.on() ? "&eAdd your chips, then Spin" : "&ePick your tokens, then Spin",
                    "&7Each space shows what it gives.", "&7The biggest: &6" + say(odds.top() + " tokens"));
        }
        return switch (spin.result()) {
            case WIN -> Menus.glint(Menus.icon(Material.GOLD_INGOT, "&aYou won &6" + won(spin.prize()),
                    "&7You put in " + back(spin.stake()) + "."), true);
            case BACK -> Menus.glint(Menus.icon(Material.WHITE_DYE, "&fYour " + back(spin.prize()) + " back",
                    spinMoney ? "&7The money you put in came back." : "&7The tokens you put in came back."), false);
            case NOTHING -> Menus.glint(Menus.icon(Material.GRAY_DYE, "&7No win this time."), false);
        };
    }

    /** Spin, lit when nothing on screen stops it; otherwise unlit with the reason in its name. */
    private ItemStack spinTile() {
        if (spinning) {
            return Menus.glint(Menus.icon(Material.LEVER, "&7Spinning…"), false);
        }
        String why = money.on() ? wheel.moneyBlocker(viewer, settings, money.stake())
                : wheel.blocker(viewer, settings, stake);
        if (why != null) {
            return Menus.glint(Menus.icon(Material.LEVER, "&7Spin &8- " + why), false);
        }
        return Menus.glint(Menus.icon(Material.LEVER, "&aSpin &7- &6" + say(in() + " tokens"), "&eClick to spin"),
                true);
    }

    /** A prize of the spin on screen, as won: "17 tokens" or "$17". */
    private String won(int prize) {
        return spinMoney ? Parlor.dollars(prize) : prize + " tokens";
    }

    /** An amount of the spin on screen: "10" or "$10". */
    private String back(int amount) {
        return spinMoney ? Parlor.dollars(amount) : Integer.toString(amount);
    }

    // ---- the spin ----------------------------------------------------------------------------

    private void spin() {
        if (spinning) {
            return;
        }
        boolean dollars = money.on();
        WheelSettings table = table();
        int stake = in();
        if (table == null || table.odds(stake) == null) {
            refresh();
            return;
        }
        WheelSpin s = wheel.play(viewer, table, stake, dollars);
        if (s == null) {
            refresh(); // refused and told: show the fresh numbers
            return;
        }
        spin = s;
        spinMoney = dollars;
        spinOdds = table.odds(stake);
        told = false;
        spinning = true;
        path = WheelRing.path(at, s.space(), plan);
        schedule = WheelRing.schedule(plan);
        frame = 0;
        ticks = 0;
        refresh();
        task = ticker(1, this::tick);
    }

    /** One server tick of the spin: show every frame that is due, then land after the last. */
    private void tick() {
        if (!spinning) {
            return;
        }
        WheelOdds odds = spinOdds;
        ticks++;
        boolean moved = false;
        while (frame < path.length && ticks >= schedule[frame]) {
            int from = at;
            at = path[frame++];
            getInventory().setItem(WheelRing.SLOTS[from], space(odds, from));
            getInventory().setItem(WheelRing.SLOTS[at], highlight(odds, at));
            moved = true;
        }
        if (moved) {
            float pitch = 0.6f + 1.0f * Math.min(1f, (float) frame / path.length);
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, pitch);
        }
        if (frame >= path.length) {
            land();
        }
    }

    private void land() {
        spinning = false;
        if (spin != null) {
            at = spin.space();
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
        refresh();
        tell();
    }

    /** The result, once per spin — on landing, or when the screen is closed early. */
    private void tell() {
        if (told || spin == null) {
            return;
        }
        told = true;
        switch (spin.result()) {
            case WIN -> {
                Sounds.won(viewer);
                viewer.sendMessage(Text.of("&aThe Wheel: you won &6" + won(spin.prize()) + "&a."));
            }
            case BACK -> {
                Sounds.received(viewer);
                viewer.sendMessage(Text.of("&fThe Wheel: your " + back(spin.prize()) + " back."));
            }
            case NOTHING -> {
                Sounds.miss(viewer);
                viewer.sendMessage(Text.of("&7The Wheel: no win this time."));
            }
        }
    }

    @Override
    protected void onClose(Player player) {
        super.onClose(player);
        task = null;
        if (spinning) {
            spinning = false;
            at = spin.space();
        }
        tell();
    }
}
