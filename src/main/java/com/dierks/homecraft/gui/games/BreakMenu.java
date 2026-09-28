package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.Breaks;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.gui.ConfirmMenu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.storage.GamesDao.BreakRow;
import com.dierks.homecraft.util.GameClock;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

/**
 * Take a break (spec §4.3, R1.11-R1.13): a player's own daily limit on the tokens they put into
 * games of chance, and a pause.
 *
 * <pre>
 *  row 0  2 Today: 35 of 100 tokens · 4 the state · 6 the limits (yours, set for you, everyone)
 *  row 1  [9 Daily limit]  10-15 the choices + "No limit of my own" · 17 a raise waiting (cancel)
 *  row 2  [18 Pause]  20 · 22 · 24 pause for 1, 7 or 30 days (a confirm says it can't be undone)
 *  row 3  [27 What it covers]  28 the exact list · 31 Coin Flip invites on/off
 *  row 5  49 Back/Close
 * </pre>
 *
 * <p>Everything leans the careful way, and the screen says so before anything happens: a lower
 * limit starts now, a higher one (or none) waits {@code games.break.raise_delay_days} and starts
 * at midnight; a pause can be made longer but never shorter or undone. The rules themselves live
 * in {@link Breaks}; this screen only shows them and calls it. It works while the games are off
 * too, since it also covers Crates, the Scratch Ticket and Card Packs bought with tokens — and if
 * the break can't be read it says so and changes nothing (games of chance stay closed).
 */
public final class BreakMenu extends GameMenu {

    /** What it covers, exactly (R1.11). */
    public static final String COVERS = "Ore Slots, Twenty-One, the Wheel, Higher or Lower, Coin Flip, Crates, "
            + "Scratch Tickets and Card Packs bought with tokens.";
    private static final int[] CHOICE_SLOTS = {10, 11, 12, 13, 14, 15};
    private static final int PENDING = 17;
    private static final int[] PAUSE_SLOTS = {20, 22, 24, 19, 21, 23, 25};
    private static final int COIN_FLIP = 31;
    private static final String COIN_FLIP_ID = "coin_flip";

    /** What picking a limit would do. */
    enum Choice {
        /** It is the limit now. */
        CURRENT,
        /** It is the raise already waiting. */
        WAITING,
        /** Stricter (or the first): starts now. */
        NOW,
        /** Looser (or none): waits, then starts at midnight. */
        LATER
    }

    public BreakMenu(HomeCraftManagement plugin, Player viewer, Runnable back) {
        super(plugin, null, viewer, back);
        init(54, Text.of("&bTake a break"));
    }

    // ---- the words (pure, tested) ---------------------------------------------------------

    /** "Today: 35 of 100 tokens", or "Today: 35 tokens" with no limit at all. */
    static String todayLine(int in, int limit) {
        if (limit >= 0) {
            return "Today: " + in + " of " + limit + " tokens";
        }
        return "Today: " + in + " token" + (in == 1 ? "" : "s");
    }

    /** "25 tokens a day", "1 token a day", or "no limit". */
    static String limitText(int tokens) {
        if (tokens < 0) {
            return "no limit";
        }
        return tokens + " token" + (tokens == 1 ? "" : "s") + " a day";
    }

    /**
     * The state as a tile name: "&amp;bPaused until Tue 12 AM", "&amp;bTake a break &amp;7- limit 25 a
     * day", or just "&amp;bTake a break".
     */
    static String stateName(long pausedUntil, long now, int limit, String untilText) {
        if (now < pausedUntil) {
            return "&bPaused until " + untilText;
        }
        if (limit >= 0) {
            return "&bTake a break &7- limit " + limit + " a day";
        }
        return "&bTake a break";
    }

    /** What picking {@code wanted} does, given the current own limit and any waiting one. */
    static Choice choice(int current, int pending, int wanted) {
        if (wanted == current) {
            return Choice.CURRENT;
        }
        if (pending != Breaks.NO_PENDING && wanted == pending) {
            return Choice.WAITING;
        }
        return Breaks.isRaise(current, wanted) ? Choice.LATER : Choice.NOW;
    }

    /** Whether a pause ending at {@code end} makes the player's own pause longer (it can never shorten it). */
    static boolean lengthens(long ownUntil, long end) {
        return end > ownUntil;
    }

    /** {@code text} in lines of at most {@code width} characters, broken between words (lore). */
    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    /** {@link #COVERS} as lore lines (the guide shows the same list). */
    public static String[] coversLore() {
        return wrap(COVERS, 34).stream().map(line -> "&7" + line).toArray(String[]::new);
    }

    /** The limit choices: the configured ones (at most five), then "No limit of my own". */
    static List<Integer> choices(List<Integer> configured) {
        List<Integer> out = new ArrayList<>();
        for (Integer c : configured) {
            if (c != null && c >= 0 && !out.contains(c) && out.size() < CHOICE_SLOTS.length - 1) {
                out.add(c);
            }
        }
        out.add(Breaks.NO_LIMIT);
        return out;
    }

    // ---- shared tile --------------------------------------------------------------------

    /**
     * The Take a break tile the Wallet, the Games screen and the hub show: a {@code BLUE_BED} whose
     * name carries the state.
     */
    public static ItemStack stateTile(HomeCraftManagement plugin, Player player) {
        Breaks breaks = plugin.breaks();
        BreakRow row = read(plugin, breaks, player.getUniqueId());
        if (row == null) {
            return Menus.icon(Material.BLUE_BED, "&bTake a break",
                    "&7A daily limit or a pause", "&7for games of chance.", "&eClick to see");
        }
        GameClock clock = plugin.clock();
        long until = Breaks.pausedUntil(row);
        int limit = Breaks.effectiveLimit(row.dailyTokens(), row.adminTokens(), serverLimit(plugin));
        return Menus.icon(Material.BLUE_BED,
                stateName(until, clock.nowMillis(), limit, Breaks.untilText(clock, until)),
                "&7A daily limit or a pause", "&7for games of chance.", "&eClick to see");
    }

    /** The server's daily limit, which counts only while the games are on (R1.9). */
    private static int serverLimit(HomeCraftManagement plugin) {
        GamesConfig.Parsed cfg = plugin.config().games();
        return cfg.enabled() ? cfg.common().chanceDailyTokens() : 0;
    }

    /** The player's row, or {@code null} if there is no service or it can't be read. */
    private static BreakRow read(HomeCraftManagement plugin, Breaks breaks, UUID player) {
        if (breaks == null) {
            return null;
        }
        try {
            return breaks.row(player);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read a break", e);
            return null;
        }
    }

    // ---- the screen ----------------------------------------------------------------------

    @Override
    protected void build() {
        fill();
        exitTile();
        Breaks breaks = plugin.breaks();
        BreakRow row = read(plugin, breaks, viewer.getUniqueId());
        if (row == null) {
            set(4, Menus.icon(Material.BLUE_BED, "&bTake a break"), null);
            set(22, Menus.icon(Material.GRAY_DYE, "&cTake a break can't be read right now",
                    "&7Games of chance stay closed", "&7until it can. Nothing changed."), null);
            return;
        }
        GameClock clock = plugin.clock();
        long now = clock.nowMillis();
        GamesConfig.Common common = plugin.config().games().common();
        int server = serverLimit(plugin);
        int own = row.dailyTokens();
        int limit = Breaks.effectiveLimit(own, row.adminTokens(), server);
        long until = Breaks.pausedUntil(row);
        int in = safe(() -> breaks.tokensInToday(viewer.getUniqueId()), 0);

        set(2, Menus.icon(Material.CLOCK, "&e" + todayLine(in, limit),
                "&7Tokens put into games of chance", "&7since midnight."), null);
        set(4, Menus.icon(Material.BLUE_BED, stateName(until, now, limit, Breaks.untilText(clock, until)),
                "&7Put fewer tokens into games", "&7of chance, or pause them."), null);
        set(6, limitsTile(own, row, server, now, clock), null);

        set(9, Menus.icon(Material.YELLOW_STAINED_GLASS_PANE, "&e&lDaily limit",
                "&7A lower limit starts now.", "&7A higher one waits " + days(common.breakRaiseDelayDays())
                        + ",", "&7then starts at midnight."), null);
        List<Integer> choices = choices(common.breakDailyChoices());
        for (int i = 0; i < choices.size(); i++) {
            choiceTile(CHOICE_SLOTS[i], row, choices.get(i), common, clock, now);
        }
        pendingTile(row);

        set(18, Menus.icon(Material.ORANGE_STAINED_GLASS_PANE, "&6&lPause",
                "&7Take a break from games", "&7of chance for a while."), null);
        List<Integer> pauses = common.breakPauseDays();
        for (int i = 0; i < pauses.size() && i < PAUSE_SLOTS.length; i++) {
            pauseTile(PAUSE_SLOTS[i], row, pauses.get(i), clock, now);
        }

        set(27, Menus.icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&b&lWhat it covers"), null);
        set(28, Menus.icon(Material.BOOK, "&bIt covers games of chance", coversLore()), null);
        coinFlipTile();
    }

    private ItemStack limitsTile(int own, BreakRow row, int server, long now, GameClock clock) {
        List<String> lore = new ArrayList<>();
        if (row.adminTokens() >= 0) {
            lore.add("&7Set for you: &e" + limitText(row.adminTokens()));
        }
        if (now < row.adminPausedUntil()) {
            lore.add("&7Paused for you until &e" + Breaks.untilText(clock, row.adminPausedUntil()));
        }
        if (server > 0) {
            lore.add("&7Everyone: &e" + limitText(server));
        }
        lore.add("&7The lowest limit is the one that counts.");
        return Menus.icon(Material.PAPER, "&fYour own limit: &e" + (own < 0 ? "none" : limitText(own)),
                lore.toArray(new String[0]));
    }

    private void choiceTile(int slot, BreakRow row, int wanted, GamesConfig.Common common, GameClock clock, long now) {
        String name = wanted < 0 ? "No limit of my own" : limitText(wanted);
        Choice c = choice(row.dailyTokens(), row.pendingTokens(), wanted);
        switch (c) {
            case CURRENT -> set(slot, Menus.glint(Menus.icon(wanted < 0 ? Material.GRAY_DYE : Material.LIME_DYE,
                    "&a&l" + name, "&7That's yours now."), true), null);
            case WAITING -> set(slot, Menus.icon(Material.CLOCK, "&e" + name + " &7- waiting",
                    "&7Starts on " + Breaks.dateText(row.pendingDay()) + "."), null);
            case NOW -> set(slot, Menus.icon(Material.LIME_DYE, "&e" + name,
                    "&7Starts now.", "&eClick to choose it"), e -> setLimit(wanted, c));
            case LATER -> set(slot, Menus.icon(wanted < 0 ? Material.GRAY_DYE : Material.YELLOW_DYE, "&e" + name,
                    "&7A looser limit waits " + days(common.breakRaiseDelayDays()) + ":",
                    "&7it would start on " + Breaks.dateText(Breaks.pendingDay(clock, now,
                            common.breakRaiseDelayDays())) + ".",
                    "&7You can cancel it before then.", "&eClick to choose it"), e -> setLimit(wanted, c));
        }
    }

    private void setLimit(int wanted, Choice c) {
        Breaks breaks = plugin.breaks();
        if (breaks == null || !safe(() -> breaks.setLimit(viewer.getUniqueId(), wanted))) {
            couldNotSave();
            return;
        }
        if (c == Choice.NOW) {
            viewer.sendMessage(Text.of("&aYour limit is now " + limitText(wanted) + "."));
        } else {
            BreakRow row = read(plugin, breaks, viewer.getUniqueId());
            String when = row == null || row.pendingTokens() == Breaks.NO_PENDING ? "soon"
                    : "on " + Breaks.dateText(row.pendingDay());
            viewer.sendMessage(Text.of(wanted < 0 ? "&eYour own limit comes off " + when + "."
                    : "&eYour new limit of " + limitText(wanted) + " starts " + when + "."));
        }
        click();
        refresh();
    }

    /** Slot 17: the raise (or removal) waiting, and a button to drop it. */
    private void pendingTile(BreakRow row) {
        if (row.pendingTokens() == Breaks.NO_PENDING) {
            return;
        }
        set(PENDING, Menus.icon(Material.CLOCK, "&eWaiting: " + limitText(row.pendingTokens()) + " from "
                        + Breaks.dateText(row.pendingDay()),
                "&7Until then yours stays " + limitText(row.dailyTokens()) + ".", "&eClick to cancel it"), e -> {
            Breaks breaks = plugin.breaks();
            if (breaks == null || !safe(() -> breaks.cancelPending(viewer.getUniqueId()))) {
                couldNotSave();
                return;
            }
            viewer.sendMessage(Text.of("&aCancelled. Your limit stays " + limitText(row.dailyTokens()) + "."));
            click();
            refresh();
        });
    }

    private void pauseTile(int slot, BreakRow row, int days, GameClock clock, long now) {
        long end = Breaks.pauseEnd(clock, now, days);
        String until = Breaks.untilText(clock, end);
        if (!lengthens(row.pausedUntil(), end)) {
            set(slot, Menus.icon(Material.GRAY_DYE, "&7Pause for " + days(days) + " &8- you're paused longer",
                    "&7A pause can only be made longer."), null);
            return;
        }
        set(slot, Menus.icon(Material.BLUE_BED, "&ePause for " + days(days),
                "&7Until " + until + ".", "&7It can't be made shorter", "&7or cancelled.", "&eClick to choose"),
                e -> confirmPause(days, until));
    }

    private void confirmPause(int days, String until) {
        ItemStack display = Menus.icon(Material.BLUE_BED, "&bPause until " + until,
                "&7Games of chance stay closed", "&7until then. Skill games stay open.");
        new ConfirmMenu(plugin, "&bPause for " + days(days) + "?", display,
                List.of("&7Until " + until + ".", "&cIt can't be made shorter", "&cor cancelled."),
                "&7Click to start the pause.", () -> {
                    Breaks breaks = plugin.breaks();
                    if (breaks == null || !safe(() -> breaks.pause(viewer.getUniqueId(), days))) {
                        couldNotSave();
                    } else {
                        viewer.sendMessage(Text.of("&bYou're taking a break from games of chance until "
                                + until + "."));
                        click();
                    }
                    reopen();
                }, this::reopen).open(viewer);
    }

    /** Slot 31: Coin Flip invites, off until the player turns them on here (§5.7). */
    private void coinFlipTile() {
        GamesService games = plugin.games();
        if (games == null || !viewer.hasPermission("hcm.games.chance")) {
            return;
        }
        Game coinFlip = games.game(COIN_FLIP_ID);
        if (coinFlip == null || !games.enabled(coinFlip)) {
            return;
        }
        UUID id = viewer.getUniqueId();
        boolean on = safe(() -> games.invites().accepts(id, COIN_FLIP_ID));
        set(COIN_FLIP, Menus.icon(on ? Material.LIME_DYE : Material.GRAY_DYE,
                "&eCoin Flip invites: " + (on ? "&aon" : "&7off"),
                "&7When they're on, other players", "&7can ask you to flip a coin.",
                "&eClick to turn them " + (on ? "off" : "on")), e -> {
            games.invites().setAccepts(id, COIN_FLIP_ID, !on);
            viewer.sendMessage(Text.of("&eCoin Flip invites are " + (on ? "&7off" : "&aon") + "&e."));
            click();
            refresh();
        });
    }

    private void couldNotSave() {
        viewer.sendMessage(Text.of("&cThat couldn't be saved right now. Nothing changed."));
        Sounds.refused(viewer);
        refresh();
    }

    private void click() {
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
    }

    /** "1 day", "7 days". */
    private static String days(int n) {
        return n + " day" + (n == 1 ? "" : "s");
    }

    /** {@code task}, or false if it threw (logged): a failed save is just "couldn't be saved". */
    private boolean safe(BooleanSupplier task) {
        try {
            return task.getAsBoolean();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Take a break failed for " + viewer.getName(), e);
            return false;
        }
    }

    private int safe(java.util.function.IntSupplier task, int fallback) {
        try {
            return task.getAsInt();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Take a break failed for " + viewer.getName(), e);
            return fallback;
        }
    }

    private void reopen() {
        new BreakMenu(plugin, viewer, back).open(viewer);
    }
}
