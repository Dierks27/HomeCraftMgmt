package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.ArcadeService;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.event.RaceNight;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.MenuStyle;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.BreakMenu;
import com.dierks.homecraft.gui.games.GamesMenu;
import com.dierks.homecraft.gui.games.ScoresMenu;
import com.dierks.homecraft.hunt.HuntService;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The Arcade hub — one screen with everything on it, one row for each thing you do (0.38.3, the owner: "a quick
 * reorganization of the Arcade now that we have added all the stuff"), in zones that read as rows even on Bedrock:
 *
 * <pre>
 *  row 0  [0 The Hub] border ........ [4 Wallet] ........ [8 wild Mini]
 *  row 1  [9 Play]  10-16 the doors that are open, side by side: All games · Today's pick · Cabinets · Courses ·
 *                   Mini golf · Together · The Clubhouse;  17 High scores      (border while the games are off)
 *  row 2  [18 Prize Counter]  19 Boosts · 20 Hunt Gear · 21 Fireworks &amp; Trails · 22 Looks · 23 Perks ·
 *                             24 Trophies · 25 Minis · 26 Keepers (only while there are Keepers)
 *  row 3  [27 Luck]  28-35 side by side: the crates (three) · All crates · Scratch Ticket (jackpot in its name) ·
 *                    Card Packs · Games of luck · Take a break
 *  row 4  [36 You]  37 Quests · 38 Achievements · 39 My Looks · 40 How It Works · 41 Give a present
 *  row 5  border · 49 Close
 * </pre>
 *
 * Every button says what it is and its key number in its NAME, because Bedrock shows lore only on
 * tap-and-hold. The wild-Mini tile counts down while the hub is open.
 *
 * <p>The Play row shows only while {@code games.enabled} is on and the player may play ({@code hcm.games.play}); with
 * the games off row 1 is border and the rest is as above (spec §8.2). Its doors open the Games screen's tabs, each
 * only while its tab has something on it ({@link #doorOpen}, counted as the Games screen counts:
 * {@link GamesMenu#tabCounts}), and The Clubhouse ("The Clubhouse - 2 here") only while it is open: a click goes in.
 *
 * <p><b>0.59.0, the face lift</b> (the owner: "the menus for the Games menu when you click on the console are a bit
 * messy again from the changes"). The Play and Luck rows used to keep a slot for every button, lit or not: grey
 * "Courses - closed" and "Mini golf - closed" tiles between the live doors, and with one crate on the server four
 * empty panes between it and Card Packs. Now each row packs the buttons it has from the left, side by side
 * ({@link #pack}), so a controller's cursor walks along them with nothing dead in between; a door that would open onto
 * an empty tab isn't drawn (nothing a click did is lost: a closed door had no click). Together lights for anything on
 * its tab (it used to know only Race Night and Falling Floors, not the Floor Is Lava), the title is in the house
 * style ({@link MenuStyle}) and Games of luck is a rabbit's foot, the Games screen's Luck tab.
 *
 * <p>The Luck row's crates and Scratch Ticket follow Take a break (R1.16): they are games of chance, so a player on a
 * break sees one "Taking a break until ..." tile in their place (or "closed" if the break can't be read), and one
 * without {@code hcm.games.chance} sees none of them at all; with the games off they show as they always did. Games of
 * luck (the Games screen's Luck tab) shows only while games of chance are open to the player. Card Packs and Take a
 * break are always there: the break covers the crates, the ticket and Card Packs bought with tokens.
 */
public final class ArcadeMenu extends Menu {

    static final int WALLET = 4;
    /** 0.44.0: the Hub's button, top left, while it can be visited. */
    static final int HUB = 0;
    static final int WILD = 8;
    /**
     * The Play row, while the games are on: its label (9), the doors packed from 10 to 16 ({@link #DOORS}), and High
     * scores at the end (17).
     */
    static final int[] PLAY = {9, 10, 11, 12, 13, 14, 15, 16, 17};
    /** 0.59.0: where the Play row's doors go, side by side from the left. */
    static final int[] DOORS = {10, 11, 12, 13, 14, 15, 16};
    private static final int SCORES = 17;
    /** The Prize Counter's tabs, in {@link PrizeTab} order: row 2 after its label. */
    static final int[] TABS = {19, 20, 21, 22, 23, 24, 25, 26};
    private static final int LUCK_LABEL = 27;
    /**
     * 0.59.0: the Luck row after its label, its buttons side by side from the left: up to three crates (or the break's
     * stand-in), All crates, the Scratch Ticket, Card Packs, Games of luck, Take a break. Eight buttons at most, eight
     * slots.
     */
    static final int[] LUCK_ROW = {28, 29, 30, 31, 32, 33, 34, 35};
    /** How many crates the hub shows itself; the rest are behind All crates. */
    static final int HUB_CRATES = 3;
    private static final int YOU_LABEL = 36;
    private static final int QUESTS = 37;
    private static final int ACHIEVEMENTS = 38;
    private static final int MY_LOOKS = 39;
    private static final int GUIDE = 40;
    private static final int GIFT = 41;

    /** One button waiting for its place in a packed row. */
    private record Button(ItemStack icon, Consumer<InventoryClickEvent> click) {
    }

    private final Player player;
    private BukkitTask ticker;

    public ArcadeMenu(HomeCraftManagement plugin, Player player) {
        super(plugin);
        this.player = player;
        init(54, Text.of(MenuStyle.title("Arcade")));
    }

    /**
     * 0.59.0: the slots {@code count} buttons take in a row of {@code slots}, side by side from the left (any that
     * don't fit are left out). Pure, for the tests.
     */
    static int[] pack(int[] slots, int count) {
        int n = Math.max(0, Math.min(count, slots.length));
        int[] out = new int[n];
        System.arraycopy(slots, 0, out, 0, n);
        return out;
    }

    /** 0.59.0: whether a tab's door is drawn: only while its tab has something on it. Pure, for the tests. */
    static boolean doorOpen(Map<Game.Tab, Integer> counts, Game.Tab tab) {
        Integer n = counts == null ? null : counts.get(tab);
        return n != null && n > 0;
    }

    private void place(int[] row, List<Button> buttons) {
        int[] at = pack(row, buttons.size());
        for (int i = 0; i < at.length; i++) {
            set(at[i], buttons.get(i).icon(), buttons.get(i).click());
        }
    }

    @Override
    protected void build() {
        GamesService games = plugin.games();
        boolean playRow = games != null && games.config().enabled();
        boolean play = playRow && player.hasPermission("hcm.games.play");
        // made here, not as the class loads, so the slot plan's constants can be read with no server
        ItemStack border = Menus.icon(Material.PURPLE_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            boolean edge = i < 9 || i >= 45 || (!play && i < 18);
            set(i, edge ? border : Menus.FILLER, null);
        }
        set(18, Menus.icon(Material.YELLOW_STAINED_GLASS_PANE, "&e&lPrize Counter",
                "&7Pick what you want. No luck needed."), null);
        boolean ticketHere = games == null || games.parlor().ticketListed(player);
        set(LUCK_LABEL, ticketHere
                ? Menus.icon(Material.ORANGE_STAINED_GLASS_PANE, "&6&lLuck",
                "&7Crates, the Scratch Ticket,", "&7Card Packs and Take a break.")
                : Menus.icon(Material.ORANGE_STAINED_GLASS_PANE, "&6&lLuck",
                "&7Crates, Card Packs", "&7and Take a break."), null);
        set(YOU_LABEL, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a&lYou",
                "&7Your quests, achievements,", "&7looks and how it all works."), null);

        PluginConfig.Arcade arc = plugin.config().arcade();
        wallet(arc);
        set(WILD, wildTile(), null);
        GamesMenu.LuckView luck = playRow ? GamesMenu.luck(plugin, player) : null;
        if (play) {
            play(games);
            hub(games);
        }
        prizeTabs();
        List<Button> luckRow = new ArrayList<>();
        if (GamesMenu.hubShowsChance(playRow, luck == null ? null : luck.state())) {
            crates(arc, luckRow);
            scratch(arc, luckRow);
        } else {
            chanceStandIn(luck, luckRow);
        }
        luckRow.add(new Button(ArcadeIcons.of(plugin, player, "packs", Material.PAPER, "&bCard Packs",
                "&7Open a pack, get a Card,", "&7print it into a Mini.", "&eClick to shop"),
                e -> new com.dierks.homecraft.gui.mini.PackShopMenu(plugin, player, this::reopen).open(player)));
        if (play && luck.state() == GamesMenu.Luck.OPEN) {
            // 0.46.0: while the games of chance are kept to the Clubhouse's Game Parlor, this is the crates (0.46.1:
            // the Scratch Ticket went to the Parlor with them). 0.59.0: the Luck tab's rabbit's foot.
            boolean parlorOnly = games != null && games.parlor().onlyHere();
            luckRow.add(new Button(parlorOnly
                    ? Menus.icon(Material.RABBIT_FOOT, "&6Games of luck", "&7The crates.", "&eClick to see them")
                    : Menus.icon(Material.RABBIT_FOOT, "&6Games of luck",
                    "&7Games of chance, the crates", "&7and the Scratch Ticket.", "&eClick to see them"),
                    e -> tab(Game.Tab.LUCK)));
        }
        luckRow.add(new Button(BreakMenu.stateTile(plugin, player),
                e -> new BreakMenu(plugin, player, this::reopen).open(player)));
        place(LUCK_ROW, luckRow);
        you();
        set(MenuStyle.EXIT, Menus.exit(false), e -> e.getWhoClicked().closeInventory());
    }

    /** The Luck row's first button in place of the crates and the ticket while games of chance aren't open. */
    private void chanceStandIn(GamesMenu.LuckView luck, List<Button> row) {
        switch (luck.state()) {
            case PAUSED -> row.add(new Button(GamesMenu.breakTile(plugin, luck.until()),
                    e -> new BreakMenu(plugin, player, this::reopen).open(player)));
            case CLOSED -> row.add(new Button(GamesMenu.closedTile(), null));
            case OPEN, HIDDEN -> {
                // open: drawn as usual; hidden: nothing at all
            }
        }
    }

    /**
     * Row 1 while the games are on: the doors into the Games screen's tabs, the Clubhouse, packed from the left, and
     * the high scores at the end.
     */
    private void play(GamesService games) {
        set(PLAY[0], Menus.icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&b&lPlay",
                "&7Cabinets, courses, golf,", "&7and playing together."), null);
        Map<Game.Tab, Integer> counts = GamesMenu.tabCounts(plugin, player);
        List<Button> doors = new ArrayList<>();
        doors.add(new Button(Menus.icon(Material.BOOKSHELF, "&bAll games" + RaceNight.hubSuffix(games),
                "&7Every game that's open.", "&eClick to see them"), e -> tab(null)));
        doors.add(pick(games));
        door(doors, counts, Game.Tab.CABINETS, Material.JUKEBOX, "Cabinets", "Little video games.");
        door(doors, counts, Game.Tab.COURSES, Material.FEATHER, "Courses", "Race the clock.");
        door(doors, counts, Game.Tab.GOLF, Material.SNOWBALL, "Mini golf", "Your Mini is the ball.");
        together(games, counts, doors);
        clubhouse(games, doors);
        place(DOORS, doors);
        set(SCORES, Menus.icon(Material.OAK_SIGN, "&bHigh scores", "&7The best on every board.",
                "&eClick to see them"), e -> new ScoresMenu.Boards(plugin, player, 0, this::reopen).open(player));
    }

    /** A tab's door, only while its tab has something on it (0.59.0: no more grey "closed" tiles in the row). */
    private void door(List<Button> doors, Map<Game.Tab, Integer> counts, Game.Tab tab, Material icon, String name,
                      String blurb) {
        if (doorOpen(counts, tab)) {
            doors.add(new Button(Menus.icon(icon, "&b" + name + " &7(" + counts.get(tab) + ")", "&7" + blurb,
                    "&eClick to see them"), e -> tab(tab)));
        }
    }

    /** Together, while its tab has a tile (it glints while Race Night's join is open). */
    private void together(GamesService games, Map<Game.Tab, Integer> counts, List<Button> doors) {
        if (!doorOpen(counts, Game.Tab.TOGETHER)) {
            return; // nothing to play together
        }
        String join = RaceNight.hubSuffix(games);
        String name = "&dTogether &7(" + counts.get(Game.Tab.TOGETHER) + ")" + join;
        doors.add(new Button(Menus.glint(Menus.icon(Material.CAKE, name, "&7Race Night, Falling Floors,",
                "&7the Floor Is Lava and more.", "&eClick to see them"), !join.isEmpty()), e -> tab(Game.Tab.TOGETHER)));
    }

    /**
     * The Clubhouse's door, only while it is open (built, checked and on): "The Clubhouse - 2 here", counting who is
     * in it now. A click goes in through the gate, as {@code /hcm play clubhouse} does; a refusal says why.
     */
    private void clubhouse(GamesService games, List<Button> doors) {
        if (!Clubhouse.offered(games)) {
            return; // off, building or closed
        }
        int here = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (Clubhouse.inside(games, p.getUniqueId())) {
                here++;
            }
        }
        doors.add(new Button(Menus.icon(Material.BIRCH_DOOR, "&6The Clubhouse &7- " + here + " here",
                "&7Hang out, see the boards,", "&7then pick a game there.", "&eClick to go in"), e -> {
            if (!games.open(player, Clubhouse.SPEC.id(), this::reopen) && isOpenFor(player)) {
                refresh();
            }
        }));
    }

    /**
     * The Hub's button (0.44.0), top left, while the Hub can be visited: one tap goes, through the gate, as
     * {@code /hcm hub} does; a refusal says why.
     */
    private void hub(GamesService games) {
        if (!com.dierks.homecraft.games.hub.Hub.offered(games)) {
            return; // off or not set up: the slot stays border
        }
        set(HUB, Menus.glint(Menus.icon(Material.BEACON, "&b&lThe Hub", "&7The big spawn, where the events are.",
                "&7What you win comes home", "&7in Your Bag.", "&eClick to go"), true), e -> {
            if (!games.open(player, com.dierks.homecraft.games.hub.Hub.SPEC.id(), this::reopen) && isOpenFor(player)) {
                refresh();
            }
        });
    }

    /** Today's featured skill game or course: a click plays it. */
    private Button pick(GamesService games) {
        String id = games.guard(null, () -> games.featured().today(), null);
        GamesService.Target t = id == null ? null : games.resolve(id);
        if (t == null || t.game().kind() == GameKind.CHANCE || !games.enabled(t.game())) {
            return new Button(Menus.glint(Menus.icon(Material.NETHER_STAR, "&7No pick today",
                    "&7A skill game is picked", "&7every day at midnight."), false), null);
        }
        String name = t.playable() != null ? t.playable().name() : t.game().name();
        return new Button(Menus.glint(Menus.icon(Material.NETHER_STAR, "&eToday's pick: &f" + name,
                "&7A new pick every midnight.", "&eClick to play"), true), e -> games.open(player, id, this::reopen));
    }

    private void tab(Game.Tab tab) {
        new GamesMenu(plugin, player, tab, 0, this::reopen).open(player);
    }

    private void wallet(PluginConfig.Arcade arc) {
        int tokens = plugin.tokens().balance(player.getUniqueId());
        int streak = plugin.tokens().streak(player.getUniqueId());
        List<String> lore = new ArrayList<>();
        lore.add("&7Login streak: &f" + streak + " day" + (streak == 1 ? "" : "s")
                + " &7(tomorrow &a+" + arc.streakReward(streak + 1) + "&7)");
        int toNext = plugin.tokens().minutesToNextPlaytimeToken(player);
        if (toNext >= 0) {
            lore.add("&7Next playtime token in &f" + toNext + " min");
        }
        lore.add("&eClick for your Wallet");
        set(WALLET, Menus.glint(ArcadeIcons.of(plugin, player, "wallet", Material.SUNFLOWER,
                "&eWallet: &6" + tokens + " tokens", lore), true),
                e -> new WalletMenu(plugin, player, this::reopen).open(player));
    }

    /** The first three crates, then All crates when there are more. */
    private void crates(PluginConfig.Arcade arc, List<Button> row) {
        List<String> ids = new ArrayList<>(arc.crates().keySet());
        for (int i = 0; i < HUB_CRATES && i < ids.size(); i++) {
            String id = ids.get(i);
            PluginConfig.Crate crate = arc.crates().get(id);
            row.add(new Button(ArcadeIcons.of(plugin, player, "crate", Material.CHEST,
                    crate.display() + " &7- &6" + crate.costTokens() + " tokens",
                    "&7See what's inside and the chances,", "&7then open it!", "&eClick to look"),
                    e -> new CrateMenu(plugin, player, id, this::reopen).open(player)));
        }
        if (ids.size() > HUB_CRATES) {
            // Three crate slots on the hub; the rest are one click away rather than unreachable.
            row.add(new Button(Menus.icon(Material.BARREL, "&6All crates &7(" + ids.size() + ")",
                    "&eClick to see every crate"),
                    e -> new CrateListMenu(plugin, player, this::reopen).open(player)));
        }
    }

    private void scratch(PluginConfig.Arcade arc, List<Button> row) {
        GamesService games = plugin.games();
        if (games != null && !games.parlor().ticketListed(player)) {
            return; // 0.46.1: kept to the Clubhouse's Game Parlor with the games of chance
        }
        int cost = arc.lotto().ticketTokens();
        row.add(new Button(ArcadeService.ticketClosed(arc.lotto())
                ? ArcadeIcons.of(plugin, player, "scratch", Material.FILLED_MAP, "&aScratch Ticket",
                "&7" + ArcadeService.TICKET_CLOSED)
                : Menus.glint(ArcadeIcons.of(plugin, player, "scratch", Material.FILLED_MAP,
                "&aScratch Ticket &7- Jackpot &6" + plugin.arcade().pot(),
                "&7Costs &6" + cost + " tokens&7.",
                "&7Scratch three squares to see what you win.",
                "&7The jackpot grows with every ticket!",
                "&eClick to buy one"), true), e -> {
            var r = plugin.arcade().scratch(player);
            if (r.ok()) {
                new ScratchTicketMenu(plugin, player, r, this::reopen).open(player);
            } else {
                player.sendMessage(Text.of("&c" + r.error()));
                com.dierks.homecraft.util.Sounds.refused(player);
                refresh();
            }
        }));
    }

    /** "No wild Minis right now", or who it's near and how long is left, with the hints so far. */
    private ItemStack wildTile() {
        HuntService hunt = plugin.hunt();
        HuntService.Hunt h = null;
        if (hunt != null) {
            for (HuntService.Hunt each : hunt.live()) {
                h = each;
                break;
            }
        }
        if (h == null) {
            return ArcadeIcons.of(plugin, player, "wild_none", Material.GRAY_DYE, "&7No wild Minis right now",
                    "&7When one appears, everyone", "&7hears about it in chat.");
        }
        MiniDef def = plugin.miniService().def(h.miniId());
        String rarity = def == null ? "&dwild" : plugin.miniService().rarityText(def.rarity());
        String near = null;
        if (h.target() != null) {
            near = Bukkit.getOfflinePlayer(h.target()).getName();
        }
        String left = clock(hunt.msLeft(h));
        List<String> lore = new ArrayList<>();
        for (String hint : hunt.hintsSoFar(h)) {
            lore.add(hint);
        }
        if (lore.isEmpty()) {
            lore.add("&7Hints come as time goes by.");
        }
        lore.add("&7A Mini Radar says if you're close.");
        String article = def == null ? "A" : Character.toUpperCase(def.rarity().article().charAt(0))
                + def.rarity().article().substring(1);
        return Menus.glint(ArcadeIcons.of(plugin, player, "wild", Material.SPYGLASS,
                "&d" + article + " " + rarity + " &dMini is loose" + (near != null ? " near " + near : "")
                        + "! &f" + left + " &7left", lore), true);
    }

    /** m:ss */
    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private void prizeTabs() {
        PrizeTab[] tabs = PrizeTab.values();
        for (int i = 0; i < TABS.length && i < tabs.length; i++) {
            PrizeTab t = tabs[i];
            if (!PrizeCounterMenu.tabShown(t, plugin.config().arcade())) {
                continue;
            }
            set(TABS[i], PrizeCounterMenu.tabIcon(plugin, player, t, false),
                    e -> new PrizeCounterMenu(plugin, player, this::reopen, t, 0).open(player));
        }
    }

    /** The You row: Quests, Achievements, My Looks, How It Works, and Give a present while one can be given. */
    private void you() {
        var quests = plugin.config().quests();
        List<String> qLore = new ArrayList<>();
        String qName = "&dQuests";
        if (plugin.quests() != null && quests != null && quests.enabled()) {
            var daily = plugin.quests().assigned(player.getUniqueId(), PluginConfig.QuestPeriod.DAILY);
            int done = 0;
            for (var q : daily) {
                if (plugin.quests().done(player.getUniqueId(), q)) {
                    done++;
                }
            }
            qName = "&dQuests &7- &f" + done + " of " + daily.size() + " done today";
            qLore.add("&7Daily and weekly jobs that pay tokens.");
        } else {
            qLore.add("&7Quests are off right now.");
        }
        qLore.add("&eClick to see them");
        set(QUESTS, ArcadeIcons.of(plugin, player, "quests", Material.WRITABLE_BOOK, qName, qLore),
                e -> new QuestsMenu(plugin, player, this::reopen).open(player));

        int total = 0;
        int have = 0;
        Set<String> unlocked = plugin.achievements() == null ? Set.of()
                : plugin.achievements().unlocked(player.getUniqueId());
        for (var a : plugin.config().achievements().values()) {
            if (a.enabled()) {
                total++;
                if (unlocked.contains(a.id())) {
                    have++;
                }
            }
        }
        set(ACHIEVEMENTS, ArcadeIcons.of(plugin, player, "achievements", Material.TOTEM_OF_UNDYING,
                "&6Achievements &7- &f" + have + " / " + total,
                "&7Big moments that pay tokens once.", "&eClick to see them"),
                e -> new AchievementsMenu(plugin, player, this::reopen, 0).open(player));

        set(MY_LOOKS, WalletMenu.myLooksTile(plugin, player), plugin.looks() == null ? null
                : e -> new MyLooksMenu(plugin, player, this::reopen).open(player));

        set(GUIDE, ArcadeIcons.of(plugin, player, "guide", Material.KNOWLEDGE_BOOK, "&bHow It Works",
                "&7Tokens, Minis, wild Minis", "&7and the Arcade games.", "&eClick to read"),
                e -> new GuideMenu(plugin, player, 0, this::reopen).open(player));

        if (GiftPickerMenu.entryShown(plugin)) {
            set(GIFT, Menus.icon(Material.CAKE, "&dGive a present", PrizeCounterMenu.giftEntryLore()),
                    e -> new GiftPickerMenu(plugin, player, this::reopen).open(player));
        }
    }

    @Override
    public void open(Player viewer) {
        super.open(viewer);
        if (ticker == null && isOpenFor(viewer)) {
            // Only the wild tile changes by itself; repaint just that slot, and less often on
            // Bedrock, where every inventory update is a stutter. The tick also stops itself once
            // nobody is looking, in case a close event never reaches this menu.
            long every = Bedrock.is(player) ? 100L : 20L;
            ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                if (!isOpenFor(player)) {
                    onClose(player);
                    return;
                }
                getInventory().setItem(WILD, wildTile());
            }, every, every);
        }
    }

    @Override
    protected void onClose(Player viewer) {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    private void reopen() {
        new ArcadeMenu(plugin, player).open(player);
    }
}
