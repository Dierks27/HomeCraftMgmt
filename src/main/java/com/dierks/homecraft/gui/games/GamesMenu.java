package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.ArcadeService;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.games.Breaks;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Invite;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.gui.MenuStyle;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.arcade.ArcadeIcons;
import com.dierks.homecraft.gui.arcade.CrateMenu;
import com.dierks.homecraft.gui.arcade.GuideMenu;
import com.dierks.homecraft.gui.arcade.ScratchTicketMenu;
import com.dierks.homecraft.storage.GamesDao.BreakRow;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The Games screen (spec §8.1, R1.16, R3.14): every open game in one place, in tabs.
 *
 * <pre>
 *  row 0    0 balance · 1 All · 2 Luck · 3 Cabinets · 4 Courses · 5 Golf · 6 Together · 7 Little ones
 *           (those two only once they have a tile) · 8 an invite waiting
 *  rows 1-4 the tiles (36 a page), sorted by tab, then catalog order, then each game's own order;
 *           on All each tab starts a row of its own
 *  row 5    45 ◀ · 46 Today's pick · 47 High scores · 48 How the games work · 49 Back/Close ·
 *           50 Take a break · 51 NPC players · 53 ▶
 * </pre>
 *
 * <p><b>0.59.0, the face lift</b> (the owner: "the menus for the Games menu when you click on the console are a bit
 * messy again from the changes"). With Texas Hold'em, Bingo Night, Picture Bingo, Video Poker, Creeper Fuse, the
 * Sports Board and the kid race added, All ran every tab into the next on the same row (a crate, then the cabinets,
 * then the Clubhouse, wherever the last one ended), the kid games sat among the grown-ups' on Together, and Take a
 * break and the guide had swapped sides of the way out. Now:
 * <ul>
 *   <li>On All, each tab's tiles start a row of their own, and a tab that fits on one page is never split over two
 *       ({@link #place}), so the screen reads as rows of Luck, Cabinets, Courses, Golf, Together, Little ones.</li>
 *   <li>The free games made for a child who can't read yet (Picture Bingo, the kid race) have their own tab, Little
 *       ones ({@link Game.Tab#LITTLE}), shown, like Together, only while it has a tile.</li>
 *   <li>Row 5 follows the house style ({@link MenuStyle}): How the games work at 48, beside the way out at 49.</li>
 *   <li>A tab with nothing open is drawn grey ("&amp;7Courses &amp;8(0)"), still a click away, so the lit ones stand
 *       out; the Luck tab is a rabbit's foot (it was the gold nugget Coin Flip also wears).</li>
 *   <li>The title says which tab this is ("Games - Luck"), and the page arrows which page they go to.</li>
 * </ul>
 *
 * <p>The tiles are the games' own ({@link Game#tiles}), so each game says its key fact in the
 * name; this screen only sorts, pages and routes a click to {@code /hcm play <id>}'s entry point,
 * which runs the gate. A closed game shows no tile at all. The Luck tab also links to the Scratch
 * Ticket (with its "gives back about" line) and each crate, since Take a break covers them too.
 * Together also holds the Clubhouse's tile while it is open (0.38.3), and the Clubhouse's Games item
 * opens this screen inside it: courses and golf start from there, cabinets play there, and the gate
 * says "That one isn't played in the Clubhouse" for the rest.
 *
 * <p><b>Fresh Courses come first</b> on their tabs (GEN-SPEC §5.4): the Fresh Courses tile, then
 * each daily course, then everything else in catalog order — the courses that are new every
 * morning are the ones worth a look.
 *
 * <p><b>Games of chance are never pushed.</b> A player on a break sees one "Taking a break until
 * ..." tile in their place, on the Luck tab and among All; a player without
 * {@code hcm.games.chance} sees nothing of them at all; if the break can't even be read they read
 * as closed (it fails closed). Skill games are unaffected either way. The featured pick is only
 * ever a skill game, and only a skill tile glints for it.
 */
public final class GamesMenu extends GameMenu {

    /** Tiles on one page: rows 1-4. */
    static final int PER_PAGE = 36;
    private static final int GRID = 9;
    /**
     * All, Luck, Cabinets, Courses, Golf, Together, Little ones: row 0 between the balance (0) and an invite (8).
     * 0.59.0: one to the left (All was at 2), to make room for Little ones at 7.
     */
    static final int[] TAB_SLOTS = {1, 2, 3, 4, 5, 6, 7};
    static final int BALANCE = 0;
    static final int INVITE = 8;
    static final int PICK = 46;
    static final int SCORES = 47;
    /** 0.59.0: How the games work, beside the way out (house style: How to play at 48). */
    static final int GUIDE = MenuStyle.HELP;
    /** 0.59.0: Take a break, the other side of the way out (it was at 48). */
    static final int BREAK = 50;
    /** The NPC players setting. */
    static final int NPCS = 51;
    /** Where the Scratch Ticket and crate links sort among the Luck tiles: after the games. */
    static final int LINK_RANK = Integer.MAX_VALUE;

    /** How games of chance show for one player (R1.16). */
    public enum Luck {
        /** As normal. */
        OPEN,
        /** Taking a break: one "Taking a break until ..." tile instead. */
        PAUSED,
        /** The break can't be read: closed (fails closed). */
        CLOSED,
        /** No {@code hcm.games.chance}: nothing at all. */
        HIDDEN
    }

    /** The luck state and, while paused, when it ends. */
    public record LuckView(Luck state, long until) {
    }

    /**
     * One tile on the grid.
     *
     * @param tab      the tab it shows on
     * @param priority Fresh Courses' tiles first: {@link #priority(String)} of its play id
     * @param rank     the game's place in the catalog ({@link #LINK_RANK} for the Arcade's links)
     * @param order    its order among the game's own tiles
     * @param icon     what is shown
     * @param click    what a click does, or {@code null}
     */
    record Tile(Game.Tab tab, int priority, int rank, int order, ItemStack icon, Consumer<InventoryClickEvent> click) {

        /** A tile that isn't Fresh Courses'. */
        Tile(Game.Tab tab, int rank, int order, ItemStack icon, Consumer<InventoryClickEvent> click) {
            this(tab, OTHERS, rank, order, icon, click);
        }
    }

    /** {@link #priority}: the Fresh Courses screen and the parkour level picker. */
    static final int TODAY = 0;
    /** {@link #priority}: a daily course. */
    static final int DAILY = 1;
    /** {@link #priority}: everything else. */
    static final int OTHERS = 2;

    private final Game.Tab tab;
    private final int page;

    /**
     * @param tab  the tab to show, or {@code null} for All
     * @param page the page (clamped)
     */
    public GamesMenu(HomeCraftManagement plugin, Player viewer, Game.Tab tab, int page, Runnable back) {
        super(plugin, null, viewer, back);
        this.tab = tab;
        this.page = Math.max(0, page);
        init(54, screenTitle("Games", tabName(tab))); // 0.59.0: which tab, in the title
    }

    // ---- the pure parts (tested) -----------------------------------------------------------

    /** How games of chance show: hidden without the permission, closed if unreadable, else paused or open. */
    static Luck luck(boolean permitted, boolean readable, long pausedUntil, long now) {
        if (!permitted) {
            return Luck.HIDDEN;
        }
        if (!readable) {
            return Luck.CLOSED;
        }
        return now < pausedUntil ? Luck.PAUSED : Luck.OPEN;
    }

    /**
     * Where a tile with this play id sorts inside its tab: the Fresh Courses screen (and the tier picker)
     * first, then the daily courses, then everything else.
     */
    static int priority(String playId) {
        if (playId == null) {
            return OTHERS;
        }
        String id = playId.trim().toLowerCase(Locale.ROOT);
        if (id.equals(Slots.DAILY) || id.equals(Slots.DAILY_PARKOUR)) {
            return TODAY;
        }
        return Slots.isSlot(id) ? DAILY : OTHERS;
    }

    /**
     * The tiles for one tab ({@code null} = All), sorted by tab, Fresh Courses first, then rank,
     * then order; the input is untouched.
     */
    static List<Tile> arrange(List<Tile> tiles, Game.Tab only) {
        List<Tile> out = new ArrayList<>();
        for (Tile t : tiles) {
            if (t != null && t.tab() != null && (only == null || t.tab() == only)) {
                out.add(t);
            }
        }
        out.sort(Comparator.comparingInt((Tile t) -> t.tab().ordinal())
                .thenComparingInt(Tile::priority)
                .thenComparingInt(Tile::rank)
                .thenComparingInt(Tile::order));
        return out;
    }

    /** How many pages {@code count} tiles fill (at least one). */
    static int pages(int count) {
        return Math.max(1, (count + PER_PAGE - 1) / PER_PAGE);
    }

    /** {@code page} kept inside the pages that exist. */
    static int clampPage(int page, int count) {
        return Math.max(0, Math.min(pages(count) - 1, page));
    }

    /**
     * Where one tile is drawn: its page, and its slot on that page (9-44).
     */
    record Spot(int page, int slot) {
    }

    /**
     * 0.59.0: where each of {@code arranged} (already {@link #arrange}d) is drawn, in order. Row by row from slot 9, 36
     * a page; with {@code sections} (the All tab) each tab starts a row of its own, and a tab that fits on one page but
     * not on what is left of this one starts the next page, so it is never split over two. Pure, for the tests.
     */
    static List<Spot> place(List<Tile> arranged, boolean sections) {
        Map<Game.Tab, Integer> sizes = counts(arranged);
        List<Spot> out = new ArrayList<>(arranged.size());
        int cell = 0;
        Game.Tab last = null;
        for (Tile t : arranged) {
            if (sections && last != null && t.tab() != last) {
                if (cell % GRID != 0) {
                    cell += GRID - cell % GRID; // a new row
                }
                int left = PER_PAGE - cell % PER_PAGE;
                int size = sizes.getOrDefault(t.tab(), 0);
                if (cell % PER_PAGE != 0 && size <= PER_PAGE && size > left) {
                    cell += left; // a new page, rather than split it
                }
            }
            out.add(new Spot(cell / PER_PAGE, GRID + cell % PER_PAGE));
            cell++;
            last = t.tab();
        }
        return out;
    }

    /** How many pages {@link #place}d tiles fill (at least one). */
    static int pages(List<Spot> spots) {
        return spots.isEmpty() ? 1 : spots.get(spots.size() - 1).page() + 1;
    }

    /** The items on one (clamped) page. */
    static <T> List<T> slice(List<T> all, int page) {
        int p = clampPage(page, all.size());
        int from = p * PER_PAGE;
        return all.subList(Math.min(from, all.size()), Math.min(all.size(), from + PER_PAGE));
    }

    /**
     * Whether the Arcade hub draws its Crate and Scratch Ticket tiles (R1.16): always while the
     * games are off (the hub is then exactly the old one), otherwise only while games of chance
     * are open to the player — paused, closed or not permitted, they aren't shown at all.
     */
    public static boolean hubShowsChance(boolean gamesOn, Luck state) {
        return !gamesOn || state == Luck.OPEN;
    }

    /**
     * Gate step 0 for the Arcade's own links (the Scratch Ticket, a crate): nothing of theirs while
     * the player is in a world game, before anything is taken; in the Clubhouse, its own plain line
     * (0.38.3: luck stays outside it). {@code null} = go ahead.
     */
    static Refusal linkRefusal(Session session) {
        if (session == null) {
            return null;
        }
        return com.dierks.homecraft.games.PlayGate.CLUBHOUSE.equals(session.gameId()) ? Refusal.NOT_IN_CLUBHOUSE
                : Refusal.IN_SESSION;
    }

    /**
     * The invite tile's name: what it is for, then who from ("&amp;eConnect Four invite &amp;7from Sam",
     * "&amp;eRide along invite &amp;7from Alex"): the key fact in the NAME, for Bedrock.
     */
    static String inviteName(String game, String from) {
        return "&e" + (game == null ? "Game" : game) + " invite &7from " + (from == null ? "a player" : from);
    }

    /**
     * The invite tile's name for this invite: from the invite's OWN name, never a lookup of its key, so a
     * ride reads "Ride along invite from Alex", not "Game invite from Alex" (the final gate's #0). The tile
     * calls this, so a test on a real ride invite pins what the tile says.
     */
    public static String inviteName(Invite inv, String from) {
        return inviteName(inv == null ? null : inv.name(), from);
    }

    /** How many tiles each tab has. */
    static Map<Game.Tab, Integer> counts(List<Tile> tiles) {
        Map<Game.Tab, Integer> out = new EnumMap<>(Game.Tab.class);
        for (Game.Tab t : Game.Tab.values()) {
            out.put(t, 0);
        }
        for (Tile t : tiles) {
            if (t != null && t.tab() != null) {
                out.merge(t.tab(), 1, Integer::sum);
            }
        }
        return out;
    }

    // ---- shared with the hub and the Wallet ------------------------------------------------

    /**
     * How games of chance show for {@code player} right now: their permission, then Take a break
     * (unreadable or missing = closed).
     */
    public static LuckView luck(HomeCraftManagement plugin, Player player) {
        boolean permitted = player.hasPermission("hcm.games.chance");
        BreakRow row = null;
        Breaks breaks = plugin.breaks();
        if (permitted && breaks != null) {
            try {
                row = breaks.row(player.getUniqueId());
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not read " + player.getName() + "'s break", e);
            }
        }
        long until = row == null ? 0 : Breaks.pausedUntil(row);
        return new LuckView(luck(permitted, row != null, until, plugin.clock().nowMillis()), until);
    }

    /** "&amp;bTaking a break &amp;7until Thu 12 AM", in place of the games of chance. */
    public static ItemStack breakTile(HomeCraftManagement plugin, long until) {
        return Menus.icon(Material.BLUE_BED, "&bTaking a break &7until " + Breaks.untilText(plugin.clock(), until),
                "&7Games of chance are paused.", "&7Skill games are open as usual.", "&eClick for Take a break");
    }

    /** Games of chance can't be checked right now, so they are closed. */
    public static ItemStack closedTile() {
        return Menus.icon(Material.GRAY_DYE, "&7Games of chance &8- closed right now",
                "&7Skill games are open as usual.");
    }

    // ---- the screen ------------------------------------------------------------------------

    @Override
    protected void build() {
        fill();
        set(BALANCE, balanceTile(), null);
        GamesService games = plugin.games();
        boolean open = games != null && games.config().enabled();
        boolean allowed = viewer.hasPermission("hcm.games.play");
        LuckView luck = luck(plugin, viewer);
        List<Tile> all = new ArrayList<>(open && allowed ? tiles(games, luck) : List.of());
        tabs(luck, counts(all));
        if (open && allowed) {
            invite(games);
            standIn(all, luck);
        }
        List<Tile> shown = arrange(all, tab);
        List<Spot> spots = place(shown, tab == null);
        int pages = pages(spots);
        int p = Math.max(0, Math.min(pages - 1, page));
        boolean any = false;
        for (int i = 0; i < shown.size(); i++) {
            if (spots.get(i).page() == p) {
                Tile t = shown.get(i);
                set(spots.get(i).slot(), Menus.tidy(t.icon()), t.click());
                any = true;
            }
        }
        if (!any) {
            set(22, emptyTile(open, allowed), null);
        }
        if (p > 0) {
            set(MenuStyle.PREV, Menus.prevPage(p, pages), e -> new GamesMenu(plugin, viewer, tab, p - 1, back).open(viewer));
        }
        if (p < pages - 1) {
            set(MenuStyle.NEXT, Menus.nextPage(p + 2, pages),
                    e -> new GamesMenu(plugin, viewer, tab, p + 1, back).open(viewer));
        }
        if (open && allowed) {
            todaysPick(games);
            set(SCORES, Menus.icon(Material.OAK_SIGN, "&bHigh scores", "&7The best on every board.",
                    "&eClick to see them"), e -> new ScoresMenu.Boards(plugin, viewer, 0, this::reopen).open(viewer));
            if (com.dierks.homecraft.games.cpu.CpuPlayers.of(games) != null) {
                set(NPCS, NpcMenu.stateTile(plugin, viewer), e -> new NpcMenu(plugin, viewer, this::reopen).open(viewer));
            }
        }
        set(BREAK, BreakMenu.stateTile(plugin, viewer), e -> new BreakMenu(plugin, viewer, this::reopen).open(viewer));
        exitTile();
        set(GUIDE, Menus.icon(Material.KNOWLEDGE_BOOK, "&bHow the games work",
                "&7What each kind of game gives,", "&7and Take a break.", "&eClick to read"),
                e -> new GuideMenu(plugin, viewer, GuideMenu.GAMES_PAGE, this::reopen).open(viewer));
    }

    private ItemStack emptyTile(boolean open, boolean allowed) {
        if (!open) {
            return Menus.icon(Material.GRAY_DYE, "&7The games are closed right now");
        }
        if (!allowed) {
            return Menus.icon(Material.GRAY_DYE, "&7Games aren't open to you");
        }
        return Menus.icon(Material.PAPER, "&7Nothing to play here yet", "&7Try another tab.");
    }

    /**
     * Which row-0 slot a tab sits in ({@link #TAB_SLOTS}): All ({@code null}) at 1 through Little ones at 7 (0.59.0;
     * All was at 2 and Together at 7). Pure, for the tests.
     */
    static int tabSlot(Game.Tab tab) {
        if (tab == null) {
            return TAB_SLOTS[0];
        }
        return switch (tab) {
            case LUCK -> TAB_SLOTS[1];
            case CABINETS -> TAB_SLOTS[2];
            case COURSES -> TAB_SLOTS[3];
            case GOLF -> TAB_SLOTS[4];
            case TOGETHER -> TAB_SLOTS[5];
            case LITTLE -> TAB_SLOTS[6];
        };
    }

    /** 0.59.0: a tab's name, on its tile and in the title ({@code null} = All). Pure, for the tests. */
    static String tabName(Game.Tab tab) {
        if (tab == null) {
            return "All games";
        }
        return switch (tab) {
            case LUCK -> "Luck";
            case CABINETS -> "Cabinets";
            case COURSES -> "Courses";
            case GOLF -> "Golf";
            case TOGETHER -> "Together";
            case LITTLE -> "Little ones";
        };
    }

    /**
     * 0.59.0: whether a tab is drawn at all: Together and Little ones only while they have a tile (or the viewer is
     * on them, {@link #togetherShown}); every other tab always (Luck follows the break besides). Pure, for the tests.
     */
    static boolean tabShown(Game.Tab which, int count, Game.Tab current) {
        if (which == Game.Tab.TOGETHER || which == Game.Tab.LITTLE) {
            return count > 0 || current == which;
        }
        return true;
    }

    /**
     * Whether the Together tab shows: only while it has a tile (Race Night, Falling Floors or, 0.38.3,
     * the Clubhouse is open), or while the viewer is on it. With all of those off, the screen is exactly
     * as it was before they existed: slot 7 stays filler.
     */
    static boolean togetherShown(int count, Game.Tab current) {
        return count > 0 || current == Game.Tab.TOGETHER;
    }

    /**
     * All, Luck, Cabinets, Courses, Golf, Together, Little ones; the current one lit. Luck follows the break (R1.16);
     * Together and Little ones show only while they have a tile ({@link #tabShown}).
     */
    private void tabs(LuckView luck, Map<Game.Tab, Integer> counts) {
        int total = 0;
        for (int n : counts.values()) {
            total += n;
        }
        tab(TAB_SLOTS[0], null, Material.BOOKSHELF, total);
        switch (luck.state()) {
            // 0.59.0: a rabbit's foot (the gold nugget is Coin Flip's picture too)
            case OPEN -> tab(TAB_SLOTS[1], Game.Tab.LUCK, Material.RABBIT_FOOT, counts.get(Game.Tab.LUCK));
            case PAUSED -> set(TAB_SLOTS[1], breakTile(plugin, luck.until()),
                    e -> new BreakMenu(plugin, viewer, this::reopen).open(viewer));
            case CLOSED -> set(TAB_SLOTS[1], closedTile(), null);
            case HIDDEN -> {
                // nothing at all: the slot stays filler
            }
        }
        tab(TAB_SLOTS[2], Game.Tab.CABINETS, Material.JUKEBOX, counts.get(Game.Tab.CABINETS));
        tab(TAB_SLOTS[3], Game.Tab.COURSES, Material.FEATHER, counts.get(Game.Tab.COURSES));
        tab(TAB_SLOTS[4], Game.Tab.GOLF, Material.SNOWBALL, counts.get(Game.Tab.GOLF));
        int together = counts.getOrDefault(Game.Tab.TOGETHER, 0);
        if (tabShown(Game.Tab.TOGETHER, together, tab)) {
            // the tab glints while a Race Night join window is open (EVENTS-DROPPER-SPEC §A.6)
            tab(tabSlot(Game.Tab.TOGETHER), Game.Tab.TOGETHER, Material.CAKE, together,
                    !com.dierks.homecraft.games.event.RaceNight.hubSuffix(plugin.games()).isEmpty());
        }
        int little = counts.getOrDefault(Game.Tab.LITTLE, 0);
        if (tabShown(Game.Tab.LITTLE, little, tab)) {
            tab(tabSlot(Game.Tab.LITTLE), Game.Tab.LITTLE, Material.TURTLE_EGG, little); // 0.59.0
        }
    }

    private void tab(int slot, Game.Tab which, Material icon, int count) {
        tab(slot, which, icon, count, false);
    }

    /**
     * One tab: lit and glinting where the viewer is, yellow with a click where they could go, and (0.59.0) grey where
     * there is nothing open, still a click away, so the tabs worth a look stand out.
     */
    private void tab(int slot, Game.Tab which, Material icon, int count, boolean glint) {
        boolean here = which == tab;
        String name = tabName(which);
        ItemStack tile = here ? Menus.icon(icon, "&a&l" + name + " &7(" + count + ")", "&7You're here.")
                : count == 0 ? Menus.icon(icon, "&7" + name + " &8(0)", "&7Nothing open here right now.")
                : Menus.icon(icon, "&e" + name + " &7(" + count + ")", "&eClick to see them");
        set(slot, Menus.glint(tile, here || glint),
                here ? null : e -> new GamesMenu(plugin, viewer, which, 0, back).open(viewer));
    }

    /** Every game tile this player may see (none of chance unless open to them), and the Arcade's links. */
    private List<Tile> tiles(GamesService games, LuckView luck) {
        List<Tile> out = new ArrayList<>();
        String pick = games.guard(null, () -> games.featured().today(), null);
        int rank = 0;
        for (Game g : games.games()) {
            int r = rank++;
            if (!listed(games, g, viewer, luck)) {
                continue;
            }
            List<Game.GameTile> own = games.guard(g, () -> g.tiles(viewer), List.of());
            for (Game.GameTile t : own == null ? List.<Game.GameTile>of() : own) {
                if (t == null || t.icon() == null || t.tab() == null) {
                    continue;
                }
                String playId = t.playId() == null || t.playId().isBlank() ? g.id() : t.playId();
                boolean featured = pick != null && g.kind() != GameKind.CHANCE && pick.equalsIgnoreCase(playId);
                out.add(new Tile(t.tab(), priority(playId), r, t.order(), featured ? markPick(t.icon()) : t.icon(),
                        e -> play(games, playId)));
            }
        }
        if (luck.state() == Luck.OPEN) {
            links(out);
        }
        return out;
    }

    /** Whether {@code g}'s tiles are drawn for this viewer: open, here, and of chance only while luck is open. */
    private static boolean listed(GamesService games, Game g, Player viewer, LuckView luck) {
        if (g.kind() == GameKind.CHANCE && luck.state() != Luck.OPEN) {
            return false;
        }
        if (!games.parlor().listed(g, viewer)) {
            return false; // 0.46.0: kept to the Clubhouse's Game Parlor
        }
        return games.enabled(g);
    }

    /**
     * 0.59.0: how many game tiles each tab has for {@code viewer} right now, as this screen would count them (the
     * Arcade's own links aside): the Arcade hub lights its doors by it, so a door never opens onto an empty tab and
     * every tab with something on it has its door.
     */
    public static Map<Game.Tab, Integer> tabCounts(HomeCraftManagement plugin, Player viewer) {
        Map<Game.Tab, Integer> out = counts(List.of());
        GamesService games = plugin.games();
        if (games == null || !games.config().enabled()) {
            return out;
        }
        LuckView luck = luck(plugin, viewer);
        for (Game g : games.games()) {
            if (!listed(games, g, viewer, luck)) {
                continue;
            }
            List<Game.GameTile> own = games.guard(g, () -> g.tiles(viewer), List.of());
            for (Game.GameTile t : own == null ? List.<Game.GameTile>of() : own) {
                if (t != null && t.icon() != null && t.tab() != null) {
                    out.merge(t.tab(), 1, Integer::sum);
                }
            }
        }
        return out;
    }

    /**
     * The one tile that stands in for every game of chance while they aren't open to this player
     * (R1.16): "Taking a break until ..." or "closed right now". It is not a game, so it is added
     * after the tabs are counted. Without the permission there is nothing at all.
     */
    private void standIn(List<Tile> all, LuckView luck) {
        switch (luck.state()) {
            case PAUSED -> all.add(new Tile(Game.Tab.LUCK, 0, 0, breakTile(plugin, luck.until()),
                    e -> new BreakMenu(plugin, viewer, this::reopen).open(viewer)));
            case CLOSED -> all.add(new Tile(Game.Tab.LUCK, 0, 0, closedTile(), null));
            case OPEN, HIDDEN -> {
                // open: the games themselves; hidden: nothing at all
            }
        }
    }

    /** A game's tile marked as today's pick: it glints and says so in its name. */
    private static ItemStack markPick(ItemStack icon) {
        ItemStack out = icon.clone();
        ItemMeta meta = out.getItemMeta();
        if (meta == null) {
            return out;
        }
        Component name = meta.displayName();
        String plain = name == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(name);
        if (name != null && !plain.contains("Today's pick")) {
            meta.displayName(name.append(Text.of(" &e★ Today's pick")));
        }
        meta.setEnchantmentGlintOverride(true);
        out.setItemMeta(meta);
        return out;
    }

    /** The Scratch Ticket and each crate, as links to their own screens (R1.18). */
    private void links(List<Tile> out) {
        if (plugin.arcade() == null) {
            return;
        }
        GamesService parlorGames = plugin.games();
        // 0.50.2 (the casino play-test): in a world game (the Clubhouse, its Parlor) these links only said "isn't
        // played in the Clubhouse"; a tile that can't be used there isn't drawn there (the Parlor's booth sells tickets)
        if (parlorGames != null && linkRefusal(parlorGames.sessions().session(viewer)) != null) {
            return;
        }
        PluginConfig.Arcade arc = plugin.config().arcade();
        PluginConfig.Lotto lotto = arc.lotto();
        boolean ticketHere = parlorGames == null || parlorGames.parlor().ticketListed(viewer); // 0.46.1
        if (!lotto.payouts().isEmpty() && ticketHere) {
            double rtp = ArcadeService.rtp(lotto);
            ItemStack icon = ArcadeService.ticketClosed(lotto)
                    ? ArcadeIcons.of(plugin, viewer, "scratch", Material.FILLED_MAP,
                    "&aScratch Ticket &7- &6" + lotto.ticketTokens() + " tokens", "&7" + ArcadeService.TICKET_CLOSED)
                    : ArcadeIcons.of(plugin, viewer, "scratch", Material.FILLED_MAP,
                    "&aScratch Ticket &7- &6" + lotto.ticketTokens() + " tokens",
                    "&7It " + RtpLimits.playerLine(rtp) + ",", "&7over lots of tickets.",
                    "&7Scratch three squares.", "&eClick to buy one");
            out.add(new Tile(Game.Tab.LUCK, LINK_RANK, 0, icon, e -> {
                if (linkAllowed()) {
                    scratch();
                }
            }));
        }
        int order = 1;
        for (Map.Entry<String, PluginConfig.Crate> c : arc.crates().entrySet()) {
            String id = c.getKey();
            ItemStack icon = ArcadeIcons.of(plugin, viewer, "crate", Material.CHEST,
                    c.getValue().display() + " &7- &6" + c.getValue().costTokens() + " tokens",
                    "&7See what's inside and the chances,", "&7then open it.", "&eClick to look");
            out.add(new Tile(Game.Tab.LUCK, LINK_RANK, order++, icon, e -> {
                if (linkAllowed()) {
                    new CrateMenu(plugin, viewer, id, this::reopen).open(viewer);
                }
            }));
        }
    }

    /** Gate step 0 for a Scratch Ticket or crate link: refused (and told) while in a world game. */
    private boolean linkAllowed() {
        GamesService games = plugin.games();
        if (games == null) {
            return true;
        }
        Refusal r = linkRefusal(games.sessions().session(viewer));
        if (r != null) {
            games.tell(viewer, r);
            return false;
        }
        return true;
    }

    private void scratch() {
        var r = plugin.arcade().scratch(viewer);
        if (r.ok()) {
            new ScratchTicketMenu(plugin, viewer, r, this::reopen).open(viewer);
        } else {
            viewer.sendMessage(Text.of("&c" + r.error()));
            Sounds.refused(viewer);
            refresh();
        }
    }

    /** Open a game or course through the one entry point (it runs the gate and says why not). */
    private void play(GamesService games, String playId) {
        if (!games.open(viewer, playId, this::reopen) && isOpenFor(viewer)) {
            refresh();
        }
    }

    /** Slot 8: the invite waiting for this player, glinting; a click says yes. */
    private void invite(GamesService games) {
        Invite inv = games.guard(null, () -> games.invites().pending(viewer.getUniqueId()), null);
        if (inv == null || inv.expired(plugin.clock().nowMillis())) {
            return;
        }
        String from = Bukkit.getOfflinePlayer(inv.from()).getName();
        set(INVITE, Menus.glint(Menus.icon(Material.WRITABLE_BOOK, inviteName(inv, from),
                "&7" + inv.summary(), "&eClick to say yes", "&7Or type /hcm play deny"), true), e -> {
            if (!games.invites().accept(viewer)) {
                viewer.sendMessage(Text.of("&cThat invite has ended."));
                Sounds.refused(viewer);
                if (isOpenFor(viewer)) {
                    refresh();
                }
            }
        });
    }

    /** Slot 46: today's featured skill game or course, and its bonus. */
    private void todaysPick(GamesService games) {
        String id = games.guard(null, () -> games.featured().today(), null);
        GamesService.Target t = id == null ? null : games.resolve(id);
        if (t == null || t.game().kind() == GameKind.CHANCE || !games.enabled(t.game())) {
            set(PICK, Menus.glint(Menus.icon(Material.NETHER_STAR, "&7No pick today",
                    "&7A skill game is picked", "&7every day at midnight."), false), null);
            return;
        }
        String name = t.playable() != null ? t.playable().name() : t.game().name();
        int bonus = games.config().common().featuredBonus();
        List<String> lore = new ArrayList<>();
        if (bonus > 0) {
            lore.add("&7Finish it today for &6+" + bonus + " token" + (bonus == 1 ? "" : "s") + " &7extra.");
        }
        lore.add("&7A new pick every midnight.");
        lore.add("&eClick to play");
        set(PICK, Menus.glint(Menus.icon(Material.NETHER_STAR, "&eToday's pick: &f" + name,
                lore.toArray(new String[0])), true), e -> play(games, id));
    }

    private void reopen() {
        new GamesMenu(plugin, viewer, tab, page, back).open(viewer);
    }
}
