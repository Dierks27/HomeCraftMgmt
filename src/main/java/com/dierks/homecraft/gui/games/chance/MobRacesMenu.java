package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.mobraces.MobRaces;
import com.dierks.homecraft.games.chance.mobraces.MobRaces.Entry;
import com.dierks.homecraft.games.chance.mobraces.MobRaces.Phase;
import com.dierks.homecraft.games.chance.mobraces.MobRacesSettings;
import com.dierks.homecraft.games.chance.mobraces.MobRacesSettings.Odds;
import com.dierks.homecraft.games.chance.mobraces.Racer;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.ClickHold;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Mob Races' screen (54 slots): pick a racer and what to put on it, then Enter. The race itself is watched on the
 * track in the room, so this screen closes when the race starts.
 *
 * <pre>
 *  row 0   4 the track: free, or how long until the race starts and how many are in
 *  row 1   10-12, 14-16 the six racers, each in its own colour (a click picks it)
 *  row 2   under each racer, who has entered on it
 *  row 3   29 how to play · 31 what a win pays and what the game gives back · 33 today
 *  row 5   45 the chips' total · 46-48 stakes or chips · 49 Close · 50 Enter, then Start now · 51 your balance
 *          53 tokens or money
 * </pre>
 *
 * Nothing here moves tokens or money: Enter asks the race, which puts the stake in through the play gate.
 */
public final class MobRacesMenu extends GameMenu {

    private static final int STATE = 4;
    private static final int[] RACERS = {10, 11, 12, 14, 15, 16};
    private static final int RULES = 29;
    private static final int PAYS = 31;
    private static final int TODAY = 33;
    private static final int CHIPS_BACK = 45;
    private static final int[] STAKES = {46, 47, 48};
    private static final int ENTER = 50;
    private static final int BALANCE = 51;
    private static final int SWITCH = 53;

    private final MobRaces races;
    private final MoneyPlay money;
    /** The racer picked, or {@code null}. */
    private Racer pick;
    /** The token stake picked. */
    private int stake;
    private boolean watching;

    public MobRacesMenu(HomeCraftManagement plugin, MobRaces game, Player viewer, Runnable back) {
        super(plugin, game, viewer, back);
        this.races = game;
        this.money = new MoneyPlay(plugin, game.games(), viewer);
        List<Integer> open = game.settings().open();
        this.stake = open.isEmpty() ? 0 : open.get(0);
        init(54, Text.of("&2Mob Races"));
    }

    @Override
    public void open(Player player) {
        Entry me = me();
        if (me != null) { // back at the screen after entering: it shows what they are in
            pick = me.pick();
            money.follow(me.money(), me.stake());
            if (!me.money()) {
                stake = me.stake();
            }
        }
        super.open(player);
        if (showing()) {
            watching = true;
            races.watch(viewer.getUniqueId(), this::raceChanged);
        }
    }

    @Override
    protected void onClose(Player player) {
        super.onClose(player);
        if (watching) {
            watching = false;
            races.unwatch(viewer.getUniqueId());
        }
    }

    /** The race changed: paint it again, or, once the race is on, close so its player watches the track. */
    private void raceChanged() {
        if (races.phase() == Phase.RUNNING || races.phase() == Phase.RESULT) {
            if (showing()) {
                closeNow(viewer);
                if (me() != null) {
                    viewer.sendMessage(Text.of("&aThey're off! &7Watch the track."));
                }
            }
            return;
        }
        refresh();
    }

    private Entry me() {
        return races.entry(viewer.getUniqueId());
    }

    /** What the next entry puts in: the tokens picked, or the chips' dollars. */
    private int in() {
        return money.on() ? money.stake() : stake;
    }

    // ---- painting -----------------------------------------------------------------------------------------

    @Override
    protected void build() {
        fill();
        Entry me = me();
        boolean dollars = me != null ? me.money() : money.on();
        MobRacesSettings s = races.settings();
        paintState();
        paintRacers(me);
        set(RULES, rulesTile(game.rules()), null);
        Odds odds = me != null ? new Odds(me.stake(), me.pays()) : races.odds(in(), dollars);
        paintPays(odds, dollars);
        set(TODAY, dollars ? money.todayTile(game, s.dailyLimit(), "races")
                : CardGameTiles.today(races.games(), game, viewer, plugin.clock(), s.dailyLimit(), "races"), null);
        set(BALANCE, dollars ? money.balanceTile() : balanceTile(), null);
        exitTile();
        if (me == null) {
            paintStakes(s, dollars);
            paintEnter(odds, dollars);
        } else {
            paintStartNow(me);
        }
    }

    private void paintState() {
        int in = races.entries().size();
        if (races.phase() == Phase.GATHERING) {
            int left = Math.max(0, races.secondsLeft());
            set(STATE, Menus.count(Menus.glint(Menus.icon(Material.CLOCK, "&eThe race starts in " + left + " s",
                    "&7" + in + (in == 1 ? " is" : " are") + " in so far.",
                    "&7Anyone in the room can enter until then."), true), left), null);
        } else {
            set(STATE, Menus.icon(Material.CLOCK, "&aThe track is free",
                    "&7The first to enter starts the wait", "&7for everyone else in the room."), null);
        }
    }

    private void paintRacers(Entry me) {
        List<Entry> all = races.entries();
        for (Racer r : Racer.values()) {
            int slot = RACERS[r.lane()];
            boolean mine = me != null ? me.pick() == r : pick == r;
            List<String> lore = new ArrayList<>();
            lore.add("&7Wins 1 race in " + Racer.COUNT + ", like every racer.");
            if (me != null) {
                if (mine) {
                    lore.add("&aYou're on this one.");
                }
            } else {
                lore.add(mine ? "&aYour pick" : "&eClick to pick");
            }
            ItemStack icon = Menus.count(Menus.glint(Menus.icon(banner(r), r.label() + (mine ? " &f- your pick" : ""),
                    lore.toArray(new String[0])), mine), r.number());
            set(slot, icon, me != null ? null : e -> {
                pick = r;
                refresh();
            });
            List<String> names = new ArrayList<>();
            for (Entry e : all) {
                if (e.pick() == r) {
                    names.add(e.id().equals(viewer.getUniqueId()) ? "You" : e.name());
                }
            }
            if (!names.isEmpty()) {
                set(slot + 9, Menus.count(Menus.icon(Material.NAME_TAG, "&f" + String.join(", ", names),
                        "&7In this race on " + r.label()), names.size()), null);
            }
        }
    }

    private static Material banner(Racer r) {
        Material m = Material.getMaterial(r.dye() + "_BANNER");
        return m == null ? Material.WHITE_BANNER : m;
    }

    private void paintPays(Odds odds, boolean dollars) {
        if (odds == null) {
            set(PAYS, Menus.icon(Material.PAPER, "&eWhat a win pays",
                    dollars ? "&7Add your chips to see." : "&7Pick what to put in to see."), null);
            return;
        }
        set(PAYS, Menus.icon(Material.PAPER, "&eA winning pick gets &6" + MoneyPlay.amount(dollars, odds.pays()),
                "&7" + MoneyPlay.amount(dollars, odds.stake()) + " in → " + MoneyPlay.amount(dollars, odds.pays())
                        + " back if your pick wins.",
                "&7If another racer wins, nothing comes back.",
                "&7Every racer wins 1 race in " + Racer.COUNT + ".",
                "&7Over lots of races it gives back about",
                "&7" + RtpLimits.wholePercent(odds.rtp()) + " of every " + (dollars ? "$100" : "100 tokens") + "."), null);
    }

    // ---- before entering ----------------------------------------------------------------------------------

    private void paintStakes(MobRacesSettings s, boolean dollars) {
        if (dollars) {
            for (int i = 0; i < money.chips() && i < STAKES.length; i++) {
                int chip = money.chip(i);
                set(STAKES[i], money.chipTile(i), e -> {
                    money.add(chip);
                    refresh();
                });
            }
            set(CHIPS_BACK, money.stakeTile(), e -> {
                money.clear();
                refresh();
            });
        } else {
            List<Integer> open = s.open();
            int at = Math.max(0, open.indexOf(stake));
            int from = Math.max(0, Math.min(at - 1, open.size() - STAKES.length));
            for (int j = 0; j < STAKES.length && from + j < open.size(); j++) {
                int value = open.get(from + j);
                Odds o = s.odds(value);
                set(STAKES[j], CardGameTiles.stake(value, value == stake,
                        List.of("&7A winning pick gets " + o.pays() + " back.")), e -> {
                    stake = value;
                    refresh();
                });
            }
        }
        if (money.offered() || dollars) {
            set(SWITCH, money.switchTile(), e -> {
                money.flip();
                refresh();
            });
        }
    }

    /** Why a token entry of {@code stake} can't go now (the day's limit, the balance), or {@code null}. */
    private String tokenBlocker(int stake) {
        java.util.UUID id = viewer.getUniqueId();
        com.dierks.homecraft.games.Breaks b = plugin.games() == null ? null : plugin.games().breaks();
        int in = b == null ? 0 : b.tokensInToday(id);
        int limit = b == null ? com.dierks.homecraft.games.Breaks.NO_LIMIT : b.limit(id);
        int balance = plugin.tokens() == null ? 0 : plugin.tokens().balance(id);
        if (limit >= 0 && in + stake > limit) {
            return "over your limit today";
        }
        return balance < stake ? "need " + (stake - balance) + " more tokens" : null;
    }

    /** Why a money entry of {@code dollars} can't go now, or {@code null}. */
    private String moneyBlocker(int dollars) {
        com.dierks.homecraft.games.Refusal r = plugin.games() == null ? null
                : plugin.games().parlor().check(viewer, dollars, true);
        if (r == null) {
            return null;
        }
        return switch (r.reason()) {
            case PERSONAL_LIMIT -> "over your limit today";
            case BALANCE -> "not enough money";
            default -> "closed right now";
        };
    }

    private void paintEnter(Odds odds, boolean dollars) {
        if (pick == null) {
            set(ENTER, CardGameTiles.unlit("Enter", "pick a racer first"), null);
            return;
        }
        if (odds == null) {
            set(ENTER, CardGameTiles.unlit("Enter", dollars && money.stake() <= 0 ? "add a chip first"
                    : dollars ? "not at this stake" : "closed right now"), null);
            return;
        }
        int left = races.racesLeft(viewer);
        if (left == 0) {
            set(ENTER, CardGameTiles.unlit("Enter", "no races left today"), null);
            return;
        }
        // 0.50.2 (the casino play-test): Enter stayed lit past the day's limit and the balance; the click was refused
        String why = dollars ? moneyBlocker(odds.stake()) : tokenBlocker(odds.stake());
        if (why != null) {
            set(ENTER, CardGameTiles.unlit("Enter", why), null);
            return;
        }
        Racer on = pick;
        set(ENTER, Menus.icon(Material.GREEN_CONCRETE, "&aEnter &7- &6" + MoneyPlay.amount(dollars, odds.stake())
                        + " &7on " + on.label(),
                "&7If " + on.label() + " &7wins you get &6" + MoneyPlay.amount(dollars, odds.pays()) + "&7.",
                "&7Your " + (dollars ? "money goes" : "tokens go") + " in now.",
                "&eClick to enter the race"), e -> {
            hold(ClickHold.ACTION_MS);
            races.enter(viewer, on, odds.stake(), dollars);
            if (showing()) {
                refresh();
            }
        });
    }

    // ---- after entering -----------------------------------------------------------------------------------

    private void paintStartNow(Entry me) {
        String mine = "&7You're in: &6" + MoneyPlay.amount(me.money(), me.stake()) + " &7on " + me.pick().label()
                + "&7.";
        int waiting = 0;
        for (Entry e : races.entries()) {
            if (!e.startNow()) {
                waiting++;
            }
        }
        int left = Math.max(0, races.secondsLeft());
        if (me.startNow()) {
            set(ENTER, Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&aYou asked to start now",
                    mine, "&7Waiting for " + waiting + " more to ask,", "&7or " + left + " s."), true), null);
            return;
        }
        set(ENTER, Menus.icon(Material.LIME_CONCRETE, "&aStart now", mine,
                "&7The race starts when everyone in it", "&7asks, or in " + left + " s.", "&eClick to ask"), e -> {
            hold(ClickHold.ACTION_MS);
            races.startNow(viewer);
        });
    }
}
