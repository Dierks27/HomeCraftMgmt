package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.mobraces.MobRaces;
import com.dierks.homecraft.games.chance.mobraces.MobRaces.Entry;
import com.dierks.homecraft.games.chance.mobraces.MobRaces.Phase;
import com.dierks.homecraft.games.chance.mobraces.MobRacesSettings;
import com.dierks.homecraft.games.chance.mobraces.RaceCard;
import com.dierks.homecraft.games.chance.mobraces.Racer;
import com.dierks.homecraft.gui.MenuStyle;
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
 *  row 1   10-12, 14-16 the six racers on this race's card, each in its own colour (a click picks it): its chance,
 *          what it pays at the stake chosen and what that gives back, its stats, its form today, its last finishes;
 *          the favourite and the longest shot marked
 *  row 2   under each racer, who has entered on it
 *  row 3   29 how to play · 31 what your pick pays · 33 today
 *  row 4   40 how the odds work
 *  row 5   45 the chips' total · 46-48 stakes or chips · 49 Close · 50 Enter, then Start now · 51 your balance
 *          53 tokens or money
 * </pre>
 *
 * Every number here is the race card's ({@link RaceCard}) and the race's own pay ({@link MobRaces#pay}): the
 * same ones the race is drawn and paid with. Nothing here moves tokens or money: Enter asks the race, which puts the
 * stake in through the play gate.
 */
public final class MobRacesMenu extends GameMenu {

    private static final int STATE = 4;
    private static final int[] RACERS = {10, 11, 12, 14, 15, 16};
    private static final int RULES = 29;
    private static final int PAYS = 31;
    private static final int TODAY = 33;
    private static final int HOW = 40;
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
        init(54, Text.of(MenuStyle.title("Mob Races")));
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

    // ---- what a tile says (pure) ----------------------------------------------------------------------------

    /** "★★★★☆": a rating of 1 to 5 as gold stars and grey ones. */
    static String stars(int rating) {
        return "&e" + "★".repeat(rating) + "&8" + "☆".repeat(5 - rating);
    }

    /** Today's form as arrows and a word: "&a▲▲ great form", "&7– as usual", "&c▼ off form". */
    static String form(int form) {
        return switch (form) {
            case 2 -> "&a▲▲ great form";
            case 1 -> "&a▲ good form";
            case 0 -> "&7– as usual";
            case -1 -> "&c▼ off form";
            default -> "&c▼▼ well off form";
        };
    }

    /** "1st 4th 2nd 6th 3rd", most recent first, or that it hasn't raced here yet. */
    static String finishes(List<Integer> places) {
        if (places.isEmpty()) {
            return "&7No races here yet";
        }
        List<String> out = new ArrayList<>();
        for (int p : places) {
            out.add((p == 1 ? "&a" : "&f") + p + switch (p) {
                case 1 -> "st";
                case 2 -> "nd";
                case 3 -> "rd";
                default -> "th";
            });
        }
        return String.join(" ", out);
    }

    /**
     * A racer's tile lines on this card (pure): its chance, what it pays at {@code stake} ({@code pay}, or not offered
     * there), what that gives back, its stats, its form today and its last finishes, then its mark.
     *
     * @param pay    what it pays at {@code stake}, or {@code null} when that pair isn't offered (or no stake is chosen)
     * @param stake  the stake chosen, or 0 for none yet
     * @param recent its last finishes, most recent first
     */
    static List<String> racerLines(Racer r, RaceCard card, RaceCard.Pay pay, int stake, boolean dollars,
                                   List<Integer> recent) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Wins about &f1 race in " + card.oneIn(r) + " &8(" + card.percent(r) + ")");
        if (stake <= 0) {
            lore.add(dollars ? "&7Add a chip to see what it pays." : "&7Pick what to put in to see what it pays.");
        } else if (pay == null) {
            lore.add("&8Not offered at " + MoneyPlay.amount(dollars, stake) + " this race.");
        } else {
            lore.add("&f" + MoneyPlay.amount(dollars, pay.stake()) + " in → &6" + MoneyPlay.amount(dollars, pay.pays())
                    + " back &7if it wins");
            lore.add("&7A pick on it gives back about " + RtpLimits.tenthPercent(pay.rtp()));
            lore.add("&7of every 100 " + MoneyPlay.unit(dollars) + " over lots of races.");
        }
        lore.add("&7Speed    " + stars(r.speed()));
        lore.add("&7Stamina " + stars(r.stamina()));
        lore.add("&7Start     " + stars(r.start()));
        lore.add("&7Today: " + form(card.form(r)));
        lore.add("&7Last 5: " + finishes(recent));
        if (card.favourite() == r) {
            lore.add(RaceCard.FAVOURITE + " &7The favourite this race");
        } else if (card.longest() == r) {
            lore.add(RaceCard.LONGEST + " &7The longest shot this race");
        }
        return lore;
    }

    /** The "How the odds work" tile's lines (pure); {@code low} is the lowest return offered, or {@code NaN}. */
    static List<String> howLines(double low) {
        List<String> out = new ArrayList<>();
        out.add("&7Every racer has Speed, Stamina and Start,");
        out.add("&7and a form today: &a▲ &7up, &c▼ &7down.");
        out.add("&7Together they make its chance of winning:");
        out.add("&7the stronger it is, the more often it wins.");
        out.add("&7A favourite pays less and a longshot more,");
        out.add("&7so every pick gives back about the same");
        out.add("&7over lots of races" + (Double.isNaN(low) ? "." : ": at least " + RtpLimits.wholePercent(low)
                + " of every 100."));
        out.add("&7No pick is a better deal than another.");
        out.add("&7Each race has its own card, and it stays");
        out.add("&7the same until that race is run.");
        out.add("&7The winner is decided when entries close.");
        out.add(RaceCard.FAVOURITE + " &7the favourite  " + RaceCard.LONGEST + " &7the longest shot");
        return out;
    }

    // ---- painting -----------------------------------------------------------------------------------------

    @Override
    protected void build() {
        fill();
        Entry me = me();
        boolean dollars = me != null ? me.money() : money.on();
        MobRacesSettings s = races.settings();
        RaceCard card = races.card();
        int in = me != null ? me.stake() : in();
        paintState();
        paintRacers(me, card, in, dollars);
        set(RULES, rulesTile(game.rules()), null);
        RaceCard.Pay pay = me != null ? new RaceCard.Pay(me.stake(), me.pays(), card.parts(me.pick()))
                : races.pay(pick, in, dollars);
        paintPays(pay, dollars, in);
        set(HOW, Menus.icon(Material.PAPER, "&eHow the odds work",
                howLines(races.lowestRtp()).toArray(new String[0])), null);
        set(TODAY, dollars ? money.todayTile(game, s.dailyLimit(), "races")
                : CardGameTiles.today(races.games(), game, viewer, plugin.clock(), s.dailyLimit(), "races"), null);
        set(BALANCE, dollars ? money.balanceTile() : balanceTile(), null);
        exitTile();
        if (me == null) {
            paintStakes(s, dollars);
            paintEnter(pay, dollars, in);
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

    private void paintRacers(Entry me, RaceCard card, int in, boolean dollars) {
        List<Entry> all = races.entries();
        for (Racer r : Racer.values()) {
            int slot = RACERS[r.lane()];
            boolean mine = me != null ? me.pick() == r : pick == r;
            RaceCard.Pay pay = me != null && mine ? new RaceCard.Pay(me.stake(), me.pays(), card.parts(r))
                    : races.pay(r, in, dollars);
            List<String> lore = racerLines(r, card, pay, in, dollars, races.recent(r));
            if (me != null) {
                if (mine) {
                    lore.add("&aYou're on this one.");
                }
            } else {
                lore.add(mine ? "&aYour pick" : "&eClick to pick");
            }
            String mark = card.favourite() == r ? " " + RaceCard.FAVOURITE
                    : card.longest() == r ? " " + RaceCard.LONGEST : "";
            ItemStack icon = Menus.count(Menus.glint(Menus.icon(spawnEgg(r), r.label() + mark
                    + (mine ? " &f- your pick" : ""), lore.toArray(new String[0])), mine), r.number());
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
                set(slot + 9, Menus.count(Menus.icon(banner(r), "&f" + String.join(", ", names),
                        "&7In this race on " + r.label()), names.size()), null);
            }
        }
    }

    /** The racer's picture: its own mob's spawn egg (a block-free item every client has), or its banner. */
    static Material spawnEgg(Racer r) {
        Material m = Material.getMaterial(r.mob() + "_SPAWN_EGG");
        return m == null ? banner(r) : m;
    }

    static Material banner(Racer r) {
        Material m = Material.getMaterial(r.dye() + "_BANNER");
        return m == null ? Material.WHITE_BANNER : m;
    }

    private void paintPays(RaceCard.Pay pay, boolean dollars, int in) {
        if (pick == null) {
            set(PAYS, Menus.icon(Material.PAPER, "&eWhat a win pays",
                    "&7Each racer pays its own amount:", "&7a favourite less, a longshot more.",
                    "&7Pick a racer to see yours."), null);
            return;
        }
        if (pay == null) {
            set(PAYS, Menus.icon(Material.PAPER, "&eWhat a win pays",
                    in <= 0 ? (dollars ? "&7Add your chips to see." : "&7Pick what to put in to see.")
                            : "&7" + pick.label() + " &7isn't offered at " + MoneyPlay.amount(dollars, in) + ".",
                    "&7(Its pay there would be outside 85-95", "&7of every 100, or over the biggest payout.)"),
                    null);
            return;
        }
        RaceCard card = races.card();
        set(PAYS, Menus.icon(Material.PAPER, "&eIf " + pick.label() + " &ewins: &6" + MoneyPlay.amount(dollars,
                        pay.pays()),
                "&7" + MoneyPlay.amount(dollars, pay.stake()) + " in → " + MoneyPlay.amount(dollars, pay.pays())
                        + " back if your pick wins.",
                "&7If another racer wins, nothing comes back.",
                "&7" + pick.label() + " &7wins about 1 race in " + card.oneIn(pick) + " (" + card.percent(pick) + ").",
                "&7Over lots of races a pick on it gives back",
                "&7about " + RtpLimits.tenthPercent(pay.rtp()) + " of every 100 " + MoneyPlay.unit(dollars) + "."),
                null);
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
                RaceCard.Pay p = races.pay(pick, value, false);
                String line = pick == null ? "&7What it pays depends on the racer."
                        : p == null ? "&8" + pick.mobName() + " isn't offered at " + value + "."
                        : "&7If " + pick.label() + " &7wins: " + p.pays() + " back.";
                set(STAKES[j], CardGameTiles.stake(value, value == stake, List.of(line)), e -> {
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

    private void paintEnter(RaceCard.Pay pay, boolean dollars, int in) {
        if (pick == null) {
            set(ENTER, CardGameTiles.unlit("Enter", "pick a racer first"), null);
            return;
        }
        if (pay == null) {
            set(ENTER, CardGameTiles.unlit("Enter", dollars && in <= 0 ? "add a chip first"
                    : in > 0 ? "not offered at this stake" : "closed right now"), null);
            return;
        }
        int left = races.racesLeft(viewer);
        if (left == 0) {
            set(ENTER, CardGameTiles.unlit("Enter", "no races left today"), null);
            return;
        }
        // 0.50.2 (the casino play-test): Enter stayed lit past the day's limit and the balance; the click was refused
        String why = dollars ? moneyBlocker(pay.stake()) : tokenBlocker(pay.stake());
        if (why != null) {
            set(ENTER, CardGameTiles.unlit("Enter", why), null);
            return;
        }
        Racer on = pick;
        set(ENTER, Menus.icon(Material.GREEN_CONCRETE, "&aEnter &7- &6" + MoneyPlay.amount(dollars, pay.stake())
                        + " &7on " + on.label(),
                "&7If " + on.label() + " &7wins you get &6" + MoneyPlay.amount(dollars, pay.pays()) + "&7.",
                "&7Your " + (dollars ? "money goes" : "tokens go") + " in now.",
                "&eClick to enter the race"), e -> {
            hold(ClickHold.ACTION_MS);
            races.enter(viewer, on, pay.stake(), dollars);
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
