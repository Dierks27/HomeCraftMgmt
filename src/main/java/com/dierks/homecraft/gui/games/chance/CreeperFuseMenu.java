package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Breaks;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.fuse.CreeperFuse;
import com.dierks.homecraft.games.chance.fuse.CreeperFuse.Entry;
import com.dierks.homecraft.games.chance.fuse.CreeperFuse.Phase;
import com.dierks.homecraft.games.chance.fuse.CreeperFuseSettings;
import com.dierks.homecraft.games.chance.fuse.FuseMath;
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

/**
 * 0.57.0: Creeper Fuse's screen (54 slots): the fuse everyone in the Game Parlor is watching, and the one button that
 * matters, Cash out, three slots wide so a controller's thumb can't miss it.
 *
 * <pre>
 *  row 0   0 how to play · 2 what it gives back · 4 the fuse now · 6 today · 8 who is in it
 *  row 1   13 the creeper: the multiple, climbing
 *  row 2   21-23 Cash out (what it pays you now)
 *  row 3   31 Put in
 *  row 4   36 auto cash-out · 37 off · 38-42 its choices (the one picked glows)
 *  row 5   45 the chips' total · 46-48 stakes or chips · 49 Back · 51 your balance · 52 how it works
 *          53 tokens or money
 * </pre>
 *
 * <p>Anyone may keep it open and watch: the fuse repaints it when anything changes, and while it burns its own ticker
 * paints the multiple and the Cash out every other tick. Nothing here moves tokens or money: Put in, Cash out and the
 * auto cash-out ask the fuse ({@link CreeperFuse}), which writes each to the player's round first. Every slot is
 * painted (filler first) and no item can be taken: it is a {@link GameMenu}.
 */
public final class CreeperFuseMenu extends GameMenu {

    private static final int RULES = 0;
    private static final int GIVE_BACK = 2;
    private static final int STATE = 4;
    private static final int TODAY = 6;
    private static final int WHO = 8;
    static final int CREEPER = 13;
    static final int[] CASH_OUT = {21, 22, 23};
    static final int PUT_IN = 31;
    private static final int AUTO_LABEL = 36;
    private static final int AUTO_OFF = 37;
    private static final int AUTO_FIRST = 38;
    private static final int CHIPS_BACK = 45;
    private static final int[] STAKES = {46, 47, 48};
    private static final int BALANCE = 51;
    private static final int HOW = 52;
    private static final int SWITCH = 53;

    private final CreeperFuse fuse;
    private final MoneyPlay money;
    /** The token stake picked. */
    private int stake;
    /** The auto cash-out picked for the next put-in (hundredths, 0: none). */
    private int auto;
    private boolean watching;
    private int frame;

    public CreeperFuseMenu(HomeCraftManagement plugin, CreeperFuse game, Player viewer, Runnable back) {
        super(plugin, game, viewer, back);
        this.fuse = game;
        this.money = new MoneyPlay(plugin, game.games(), viewer);
        List<Integer> open = game.settings().open();
        this.stake = open.isEmpty() ? 0 : open.get(0);
        init(54, Text.of("&2Creeper Fuse"));
    }

    @Override
    public void open(Player player) {
        Entry me = me();
        if (me != null) { // back at the screen while in a fuse: it shows that place
            money.follow(me.money(), me.stake());
            if (!me.money()) {
                stake = me.stake();
            }
            auto = me.auto();
        }
        super.open(player);
        if (showing()) {
            watching = true;
            fuse.watch(viewer.getUniqueId(), this::fuseChanged);
            ticker(2, this::live);
        }
    }

    @Override
    protected void onClose(Player player) {
        super.onClose(player);
        if (watching) {
            watching = false;
            fuse.unwatch(viewer.getUniqueId());
        }
    }

    private void fuseChanged() {
        if (showing()) {
            refresh();
        }
    }

    private Entry me() {
        return fuse.entry(viewer.getUniqueId());
    }

    /** What the next put-in puts in: the tokens picked, or the chips' dollars. */
    private int in() {
        return money.on() ? money.stake() : stake;
    }

    private CreeperFuseSettings.Odds odds() {
        return fuse.odds(in(), money.on());
    }

    // ---- painting --------------------------------------------------------------------------------------------

    @Override
    protected void build() {
        fill();
        CreeperFuseSettings s = fuse.settings();
        boolean dollars = money.on();
        Entry me = me();
        set(RULES, rulesTile(game.rules()), null);
        set(GIVE_BACK, giveBack(s, dollars), null);
        set(TODAY, dollars ? money.todayTile(game, s.dailyLimit(), "fuses")
                : CardGameTiles.today(fuse.games(), game, viewer, plugin.clock(), s.dailyLimit(), "fuses"), null);
        set(WHO, who(), null);
        paintLive();
        paintPutIn(me);
        paintAuto(s, me);
        boolean picking = me == null && (fuse.phase() == Phase.IDLE || fuse.phase() == Phase.GATHERING);
        if (dollars) {
            for (int i = 0; i < money.chips() && i < STAKES.length; i++) {
                int chip = money.chip(i);
                set(STAKES[i], money.chipTile(i), e -> {
                    if (me() == null) {
                        money.add(chip);
                        click();
                    }
                });
            }
            set(CHIPS_BACK, money.stakeTile(), e -> {
                if (me() == null) {
                    money.clear();
                    click();
                }
            });
        } else {
            List<Integer> open = s.open();
            for (int i = 0; i < open.size() && i < STAKES.length; i++) {
                int value = open.get(i);
                CreeperFuseSettings.Odds o = s.odds(value);
                set(STAKES[i], CardGameTiles.stake(value, value == stake, List.of(
                        "&7At 2.00× it pays &f" + FuseMath.payout(value, 200, o.cap()) + " tokens",
                        "&7Cashes out by itself at " + FuseMath.times(o.top()))), e -> {
                    if (me() == null && stake != value) {
                        stake = value;
                        click();
                    }
                });
            }
        }
        exitTile();
        set(BALANCE, dollars ? money.balanceTile() : balanceTile(), null);
        set(HOW, howItWorks(s, dollars), null);
        if ((money.offered() || dollars) && picking) {
            set(SWITCH, money.switchTile(), e -> {
                if (me() == null) {
                    money.flip();
                    click();
                }
            });
        } else if (money.offered() || dollars) {
            set(SWITCH, money.switchTile(), null);
        }
    }

    private void click() {
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
        refresh();
    }

    /** Every other tick while the fuse burns: the multiple, the creeper and Cash out. */
    private void live() {
        if (fuse.phase() == Phase.LIT) {
            frame++;
            paintLive();
        }
    }

    private void paintLive() {
        set(STATE, state(), null);
        set(CREEPER, creeper(), null);
        paintCashOut(me());
    }

    private ItemStack state() {
        return switch (fuse.phase()) {
            case IDLE -> Menus.icon(Material.OAK_SIGN, "&eThe next fuse is ready",
                    "&7The first put-in starts it: the fuse is lit",
                    "&7" + fuse.settings().gatherSeconds() + " seconds later, for everyone in.");
            case GATHERING -> Menus.icon(Material.CLOCK, "&eLit in &f" + fuse.secondsLeft() + "s &7· "
                    + fuse.entries().size() + " in", "&7Put in now to be in this one.");
            case LIT -> Menus.icon(Material.CLOCK, "&aBurning: &f" + FuseMath.times(fuse.multiple()),
                    "&7Climbing faster and faster. Cash out any time.");
            case OVER -> fuse.burnedOut()
                    ? Menus.icon(Material.OAK_SIGN, "&fIt fizzled out at " + FuseMath.times(fuse.lastEnd()),
                    "&7Everyone still in was paid at the top.")
                    : Menus.icon(Material.OAK_SIGN, "&cIt went off at " + FuseMath.times(fuse.lastEnd()),
                    "&7Anyone still in got nothing this time.");
        };
    }

    private ItemStack creeper() {
        return switch (fuse.phase()) {
            case IDLE -> Menus.icon(Material.CREEPER_HEAD, "&aThe creeper waits");
            case GATHERING -> Menus.icon(Material.CREEPER_HEAD, "&eThe fuse is lit in &f" + fuse.secondsLeft() + "s");
            case LIT -> Menus.glint(Menus.icon(Material.CREEPER_HEAD, "&a&l" + FuseMath.times(fuse.multiple()),
                    "&7Ssssss…"), frame % 2 == 0);
            case OVER -> fuse.burnedOut()
                    ? Menus.icon(Material.CREEPER_HEAD, "&fFizzle… &7the fuse ran out at " + FuseMath.times(fuse.lastEnd()))
                    : Menus.icon(Material.TNT, "&c&lBOOM! &7at " + FuseMath.times(fuse.lastEnd()),
                    "&7Only a bang: nothing was broken.");
        };
    }

    private void paintCashOut(Entry me) {
        ItemStack icon;
        boolean live = false;
        if (me == null) {
            icon = CardGameTiles.unlit("Cash out", fuse.phase() == Phase.LIT ? "you're not in this fuse"
                    : "put in first");
        } else if (me.caught()) {
            icon = Menus.icon(Material.GRAY_DYE, "&7It went off with you in. No win this time.",
                    "&7You put in " + CreeperFuse.amount(me.money(), me.stake()) + ".");
        } else if (me.cashedAt() > 0) {
            int paid = me.paid();
            icon = Menus.glint(Menus.icon(Material.GOLD_INGOT, "&aCashed out at " + FuseMath.times(me.cashedAt())
                    + " &7- &6" + CreeperFuse.amount(me.money(), paid), "&7You put in "
                    + CreeperFuse.amount(me.money(), me.stake()) + "."), paid > me.stake());
        } else if (fuse.phase() != Phase.LIT) {
            icon = CardGameTiles.unlit("Cash out", "when the fuse is lit");
        } else {
            int m = Math.min(fuse.multiple(), me.top());
            List<String> lore = new ArrayList<>();
            lore.add("&7Your " + CreeperFuse.amount(me.money(), me.stake()) + " times " + FuseMath.times(m) + ".");
            if (me.auto() > 0) {
                lore.add("&7Auto cash-out at " + FuseMath.times(me.auto()) + ".");
            }
            lore.add("&eClick to cash out");
            icon = Menus.glint(Menus.icon(Material.EMERALD_BLOCK, "&a&lCash out &7- &6"
                    + CreeperFuse.amount(me.money(), me.payAt(m)), lore.toArray(new String[0])), true);
            live = true;
        }
        for (int slot : CASH_OUT) {
            set(slot, icon, live ? e -> cashOut() : null);
        }
    }

    private void paintPutIn(Entry me) {
        Phase phase = fuse.phase();
        if (me != null) {
            set(PUT_IN, Menus.icon(Material.LIME_DYE, "&aYou're in: &6" + CreeperFuse.amount(me.money(), me.stake()),
                    phase == Phase.GATHERING ? "&7The fuse is lit in " + fuse.secondsLeft() + "s." : "&7Watch it burn."),
                    null);
            return;
        }
        if (phase == Phase.LIT || phase == Phase.OVER) {
            set(PUT_IN, CardGameTiles.unlit("Put in", "for the next fuse, when this one is over"), null);
            return;
        }
        CreeperFuseSettings.Odds o = odds();
        String why = blocker(o);
        if (why != null) {
            set(PUT_IN, CardGameTiles.unlit("Put in", why), e -> putIn());
            return;
        }
        set(PUT_IN, Menus.glint(Menus.icon(Material.GREEN_CONCRETE, "&aPut in &7- &6"
                        + CreeperFuse.amount(money.on(), o.stake()),
                phase == Phase.GATHERING ? "&7Into the fuse lit in " + fuse.secondsLeft() + "s."
                        : "&7The fuse is lit " + fuse.settings().gatherSeconds() + "s after the first put-in.",
                auto > 0 ? "&7Auto cash-out at " + FuseMath.times(auto) + "." : "&7No auto cash-out.",
                "&eClick to put in"), true), e -> putIn());
    }

    /** Why Put in can't go now, or {@code null}. The gate still decides on the click. */
    private String blocker(CreeperFuseSettings.Odds o) {
        if (o == null) {
            return money.on() ? (money.stake() <= 0 ? "add a chip first" : "not at this stake") : "closed right now";
        }
        if (fuse.fusesLeft(viewer) == 0) {
            return "no fuses left today";
        }
        if (money.on()) {
            Refusal r = fuse.games().parlor().check(viewer, o.stake(), true);
            if (r == null) {
                return null;
            }
            return switch (r.reason()) {
                case PERSONAL_LIMIT -> "over your limit today";
                case BALANCE -> "not enough money";
                default -> "closed right now";
            };
        }
        Breaks b = fuse.games().breaks();
        int in = b == null ? 0 : b.tokensInToday(viewer.getUniqueId());
        int limit = b == null ? Breaks.NO_LIMIT : b.limit(viewer.getUniqueId());
        if (limit >= 0 && in + o.stake() > limit) {
            return "over your limit today";
        }
        int balance = plugin.tokens() == null ? 0 : plugin.tokens().balance(viewer.getUniqueId());
        if (balance < o.stake()) {
            return "need " + (o.stake() - balance) + " more tokens";
        }
        return null;
    }

    private void paintAuto(CreeperFuseSettings s, Entry me) {
        boolean canChange = me == null ? fuse.phase() != Phase.LIT && fuse.phase() != Phase.OVER
                : me.in() && (fuse.phase() == Phase.GATHERING || fuse.phase() == Phase.LIT);
        int chosen = me != null ? me.auto() : auto;
        set(AUTO_LABEL, Menus.icon(Material.REPEATER, "&eAuto cash-out",
                "&7Pick a multiple: you're cashed out there", "&7by yourself, if the fuse gets that far.",
                "&7You can still cash out sooner."), null);
        set(AUTO_OFF, Menus.glint(Menus.icon(Material.GRAY_DYE, (chosen == 0 ? "&a&l✔ " : "&7") + "No auto cash-out",
                "&7You cash out yourself."), chosen == 0), canChange && chosen != 0 ? e -> pickAuto(0) : null);
        List<Integer> choices = s.autoChoices();
        int stakeNow = me != null ? me.stake() : in();
        for (int i = 0; i < choices.size() && AUTO_FIRST + i <= 42; i++) {
            int m = choices.get(i);
            boolean on = chosen == m;
            List<String> lore = new ArrayList<>();
            CreeperFuseSettings.Odds o = me != null ? fuse.odds(me.stake(), me.money()) : odds();
            if (o != null && m < o.top()) {
                lore.add("&7Pays " + CreeperFuse.amount(me != null ? me.money() : money.on(),
                        FuseMath.payout(stakeNow, m, o.cap())) + " for " + CreeperFuse.amount(me != null
                        ? me.money() : money.on(), stakeNow) + " in.");
            }
            lore.add("&7The fuse gets this far " + chance(s, m) + ".");
            lore.add(on ? "&aChosen." : canChange ? "&eClick to choose" : "&8Can't change now.");
            ItemStack icon = Menus.icon(on ? Material.LIME_CONCRETE : Material.LIME_STAINED_GLASS_PANE,
                    (on ? "&a&l✔ " : "&a") + FuseMath.times(m), lore.toArray(new String[0]));
            set(AUTO_FIRST + i, Menus.glint(icon, on), canChange && !on ? e -> pickAuto(m) : null);
        }
    }

    /** "about 47 times in 100": how often the fuse is still burning past {@code m}. */
    static String chance(CreeperFuseSettings s, int m) {
        double p = s.rtpFraction() * FuseMath.ONE / m;
        return "about " + Math.max(1, Math.round(p * 100)) + " times in 100";
    }

    private ItemStack who() {
        List<Entry> in = fuse.entries();
        List<String> lore = new ArrayList<>();
        for (Entry e : in) {
            String what = e.caught() ? "&7caught when it went off" : e.cashedAt() > 0 ? "&acashed out at "
                    + FuseMath.times(e.cashedAt()) + " &7(" + CreeperFuse.amount(e.money(), e.paid()) + ")"
                    : "&fstill in";
            lore.add("&7" + e.name() + ": " + CreeperFuse.amount(e.money(), e.stake()) + " · " + what);
        }
        if (lore.isEmpty()) {
            lore.add("&7Nobody yet.");
        }
        return Menus.count(Menus.icon(Material.NAME_TAG, "&eIn this fuse: &f" + in.size(),
                lore.toArray(new String[0])), Math.max(1, Math.min(64, in.size())));
    }

    private ItemStack giveBack(CreeperFuseSettings s, boolean dollars) {
        return Menus.icon(Material.KNOWLEDGE_BOOK, "&7Cashing out anywhere gives back about &f"
                        + RtpLimits.wholePercent(s.rtpFraction()) + " &7of every " + (dollars ? "$100" : "100 tokens"),
                "&7The creeper goes off at once " + Math.round((1 - s.rtpFraction()) * 100) + " times in 100.",
                "&7It is still burning at 2.00× " + chance(s, 200) + ",",
                "&7at 5.00× " + chance(s, 500) + ".",
                "&7Where it goes off is drawn before it is lit.");
    }

    private ItemStack howItWorks(CreeperFuseSettings s, boolean dollars) {
        return Menus.icon(Material.WRITABLE_BOOK, "&eHow it works",
                "&7Put in: the first put-in lights the fuse " + s.gatherSeconds() + "s later,",
                "&7and everyone in is in the same fuse.",
                "&7The multiple starts at 1.00× and doubles every " + s.doubleSeconds() + "s.",
                "&7Cash out to get your stake times the multiple" + (dollars ? "," : ""),
                dollars ? "&7in whole dollars, rounded down." : "&7in whole tokens, rounded down.",
                "&7When the creeper goes off, anyone still in gets nothing.",
                "&7At " + FuseMath.times(s.top()) + " it fizzles out: everyone still in is paid there.",
                "&7The bang is only a show: nothing is broken.");
    }

    // ---- clicks ----------------------------------------------------------------------------------------------

    private void putIn() {
        hold(ClickHold.ACTION_MS);
        if (me() != null) {
            return;
        }
        CreeperFuseSettings.Odds o = odds();
        if (o == null) {
            refresh();
            return;
        }
        if (fuse.enter(viewer, o.stake(), money.on(), auto)) {
            com.dierks.homecraft.util.Sounds.paid(viewer);
        }
        refresh();
    }

    private void cashOut() {
        hold(ClickHold.ACTION_MS);
        fuse.cashOut(viewer); // the fuse says how it went, and repaints every screen
        refresh();
    }

    private void pickAuto(int m) {
        if (me() != null) {
            fuse.setAuto(viewer, m);
        }
        auto = m;
        click();
    }
}
