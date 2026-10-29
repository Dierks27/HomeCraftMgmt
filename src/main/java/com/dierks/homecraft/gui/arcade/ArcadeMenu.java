package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.ArcadeService;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.arena.FallingFloors;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.event.RaceNight;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.gui.Menu;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The Arcade hub — one screen with everything on it, one row for each thing you do (0.38.3, the owner: "a quick
 * reorganization of the Arcade now that we have added all the stuff"), in zones that read as rows even on Bedrock:
 *
 * <pre>
 *  row 0  border ........ [4 Wallet] ........ [8 wild Mini]
 *  row 1  [9 Play]  10 All games · 11 Today's pick · 12 Courses · 13 Mini golf · 14 Cabinets ·
 *                   15 Together · 16 The Clubhouse · 17 High scores      (border while the games are off)
 *  row 2  [18 Prize Counter]  19 Boosts · 20 Hunt Gear · 21 Fireworks &amp; Trails · 22 Looks · 23 Perks ·
 *                             24 Trophies · 25 Minis · 26 Keepers (only while there are Keepers)
 *  row 3  [27 Luck]  28-30 crates · 31 All crates · 32 Scratch Ticket (jackpot in its name) · 33 Card Packs ·
 *                    34 Games of luck · 35 Take a break
 *  row 4  [36 You]  37 Quests · 38 Achievements · 39 My Looks · 40 How It Works · 41 Give a present
 *  row 5  border · 49 Close
 * </pre>
 *
 * Every button says what it is and its key number in its NAME, because Bedrock shows lore only on
 * tap-and-hold. The wild-Mini tile counts down while the hub is open.
 *
 * <p>The Play row shows only while {@code games.enabled} is on and the player may play ({@code hcm.games.play}); with
 * the games off row 1 is border and the rest is as above (spec §8.2). Its doors open the Games screen's tabs. Courses
 * lights only while the Courses tab has a tile ({@link #courseTile}: an open course, or Fresh Courses), Mini golf and
 * Cabinets while a game of their kind is open; otherwise each is a grey "closed" tile. Together shows only while Race
 * Night or Falling Floors is open, and The Clubhouse ("The Clubhouse - 2 here") only while it is open: a click goes in.
 *
 * <p>The Luck row's crates and Scratch Ticket follow Take a break (R1.16): they are games of chance, so a player on a
 * break sees one "Taking a break until ..." tile in their place (or "closed" if the break can't be read), and one
 * without {@code hcm.games.chance} sees none of them at all; with the games off they show as they always did. Games of
 * luck (the Games screen's Luck tab) shows only while games of chance are open to the player. Card Packs and Take a
 * break are always there: the break covers the crates, the ticket and Card Packs bought with tokens.
 */
public final class ArcadeMenu extends Menu {

    static final int WALLET = 4;
    static final int WILD = 8;
    /**
     * The Play row, while the games are on: label, All games, Today's pick, Courses, Mini golf, Cabinets, Together,
     * the Clubhouse, High scores.
     */
    static final int[] PLAY = {9, 10, 11, 12, 13, 14, 15, 16, 17};
    /** The Prize Counter's tabs, in {@link PrizeTab} order: row 2 after its label. */
    static final int[] TABS = {19, 20, 21, 22, 23, 24, 25, 26};
    private static final int LUCK_LABEL = 27;
    private static final int[] CRATES = {28, 29, 30};
    private static final int MORE_CRATES = 31;
    private static final int SCRATCH = 32;
    /** Card Packs: the Luck row, next to the Scratch Ticket (Take a break covers them too). */
    static final int PACKS = 33;
    private static final int LUCK_GAMES = 34;
    private static final int BREAK = 35;
    private static final int YOU_LABEL = 36;
    private static final int QUESTS = 37;
    private static final int ACHIEVEMENTS = 38;
    private static final int MY_LOOKS = 39;
    private static final int GUIDE = 40;
    private static final int GIFT = 41;

    private final Player player;
    private BukkitTask ticker;

    public ArcadeMenu(HomeCraftManagement plugin, Player player) {
        super(plugin);
        this.player = player;
        init(54, Text.of("&5&lArcade"));
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
        set(LUCK_LABEL, Menus.icon(Material.ORANGE_STAINED_GLASS_PANE, "&6&lLuck",
                "&7Crates, the Scratch Ticket,", "&7Card Packs and Take a break."), null);
        set(YOU_LABEL, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a&lYou",
                "&7Your quests, achievements,", "&7looks and how it all works."), null);

        PluginConfig.Arcade arc = plugin.config().arcade();
        wallet(arc);
        set(WILD, wildTile(), null);
        GamesMenu.LuckView luck = playRow ? GamesMenu.luck(plugin, player) : null;
        if (play) {
            play(games);
        }
        prizeTabs();
        if (GamesMenu.hubShowsChance(playRow, luck == null ? null : luck.state())) {
            crates(arc);
            scratch(arc);
        } else {
            chanceStandIn(luck);
        }
        set(PACKS, ArcadeIcons.of(plugin, player, "packs", Material.PAPER, "&bCard Packs",
                "&7Open a pack, get a Card,", "&7print it into a Mini.", "&eClick to shop"),
                e -> new com.dierks.homecraft.gui.mini.PackShopMenu(plugin, player, this::reopen).open(player));
        if (play && luck.state() == GamesMenu.Luck.OPEN) {
            set(LUCK_GAMES, Menus.icon(Material.GOLD_NUGGET, "&6Games of luck",
                    "&7Games of chance, the crates", "&7and the Scratch Ticket.", "&eClick to see them"),
                    e -> tab(Game.Tab.LUCK));
        }
        set(BREAK, BreakMenu.stateTile(plugin, player), e -> new BreakMenu(plugin, player, this::reopen).open(player));
        you();
        set(49, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
    }

    /** The Luck row in place of the crates and the ticket while games of chance aren't open to the player. */
    private void chanceStandIn(GamesMenu.LuckView luck) {
        switch (luck.state()) {
            case PAUSED -> set(CRATES[0], GamesMenu.breakTile(plugin, luck.until()),
                    e -> new BreakMenu(plugin, player, this::reopen).open(player));
            case CLOSED -> set(CRATES[0], GamesMenu.closedTile(), null);
            case OPEN, HIDDEN -> {
                // open: drawn as usual; hidden: nothing at all, the row stays filler
            }
        }
    }

    /** Row 1 while the games are on: the doors into the Games screen's tabs, the Clubhouse and the high scores. */
    private void play(GamesService games) {
        set(PLAY[0], Menus.icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&b&lPlay",
                "&7Courses, golf, cabinets,", "&7and playing together."), null);
        set(PLAY[1], Menus.icon(Material.BOOKSHELF, "&bAll games" + RaceNight.hubSuffix(games),
                "&7Every game that's open.", "&eClick to see them"), e -> tab(null));
        pick(games);
        door(PLAY[3], anyCourse(games), Game.Tab.COURSES, Material.FEATHER, "Courses", "Race the clock.");
        door(PLAY[4], anyGolf(games), Game.Tab.GOLF, Material.SNOWBALL, "Mini golf", "Your Mini is the ball.");
        door(PLAY[5], anyOpen(games, GameKind.CABINET), Game.Tab.CABINETS, Material.JUKEBOX, "Cabinets",
                "Little video games.");
        together(games);
        clubhouse(games);
        set(PLAY[8], Menus.icon(Material.OAK_SIGN, "&bHigh scores", "&7The best on every board.",
                "&eClick to see them"), e -> new ScoresMenu.Boards(plugin, player, 0, this::reopen).open(player));
    }

    /** A tab's door, or a plain "closed" tile when there is nothing of its kind to play. */
    private void door(int slot, boolean open, Game.Tab tab, Material icon, String name, String blurb) {
        if (!open) {
            set(slot, Menus.icon(Material.GRAY_DYE, "&7" + name + " &8- closed"), null);
            return;
        }
        set(slot, Menus.icon(icon, "&b" + name, "&7" + blurb, "&eClick to see them"), e -> tab(tab));
    }

    /** Whether any game of {@code kind} is open (Mini golf, Cabinets). */
    private static boolean anyOpen(GamesService games, GameKind kind) {
        for (Game g : games.games()) {
            if (g.kind() == kind && games.enabled(g)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the Games screen's Golf tab has a tile: some open game passes {@link #golfTile}. */
    private static boolean anyGolf(GamesService games) {
        for (Game g : games.games()) {
            if (games.enabled(g) && golfTile(g.kind(), true, DailyCourses.SPEC.id().equals(g.id()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a game puts a tile on the Golf tab (R383-A2, as {@link #courseTile}): an open GOLF-kind game, or
     * Fresh Courses, whose set is on the Golf tab too. Pure, for the tests.
     */
    static boolean golfTile(GameKind kind, boolean enabled, boolean freshCourses) {
        return enabled && (kind == GameKind.GOLF || freshCourses);
    }

    /** Whether the Games screen's Courses tab has a tile: some open game passes {@link #courseTile}. */
    private static boolean anyCourse(GamesService games) {
        for (Game g : games.games()) {
            if (g.kind() == GameKind.TRIAL && games.enabled(g)
                    && courseTile(g.kind(), true, games.guard(g, g::playables, List.of()).size())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a game puts a course tile on the Courses tab (0.38.3): an open TRIAL-kind game with something to play,
     * Time Trials' open courses or Fresh Courses' parkour levels. The Clubhouse, Race Night, Falling Floors and the
     * Cup are TRIAL-kind too but have none, so on their own they no longer light the door onto an empty tab
     * ("Nothing to play here yet"). Pure, for the tests.
     */
    static boolean courseTile(GameKind kind, boolean enabled, int playables) {
        return kind == GameKind.TRIAL && enabled && playables > 0;
    }

    /** Together, only while Race Night or Falling Floors is open (it glints while Race Night's join is open). */
    private void together(GamesService games) {
        if (!games.enabled(games.game(RaceNight.SPEC.id())) && !games.enabled(games.game(FallingFloors.SPEC.id()))) {
            return; // nothing to play together: the slot stays filler
        }
        String join = RaceNight.hubSuffix(games);
        set(PLAY[6], Menus.glint(Menus.icon(Material.CAKE, "&dTogether" + join, "&7Race Night and Falling Floors.",
                "&eClick to see them"), !join.isEmpty()), e -> tab(Game.Tab.TOGETHER));
    }

    /**
     * The Clubhouse's door, only while it is open (built, checked and on): "The Clubhouse - 2 here", counting who is
     * in it now. A click goes in through the gate, as {@code /hcm play clubhouse} does; a refusal says why.
     */
    private void clubhouse(GamesService games) {
        if (!Clubhouse.offered(games)) {
            return; // off, building or closed: the slot stays filler
        }
        int here = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (Clubhouse.inside(games, p.getUniqueId())) {
                here++;
            }
        }
        set(PLAY[7], Menus.icon(Material.BIRCH_DOOR, "&6The Clubhouse &7- " + here + " here",
                "&7Hang out, see the boards,", "&7then pick a game there.", "&eClick to go in"), e -> {
            if (!games.open(player, Clubhouse.SPEC.id(), this::reopen) && isOpenFor(player)) {
                refresh();
            }
        });
    }

    /** Today's featured skill game or course: a click plays it. */
    private void pick(GamesService games) {
        String id = games.guard(null, () -> games.featured().today(), null);
        GamesService.Target t = id == null ? null : games.resolve(id);
        if (t == null || t.game().kind() == GameKind.CHANCE || !games.enabled(t.game())) {
            set(PLAY[2], Menus.glint(Menus.icon(Material.NETHER_STAR, "&7No pick today",
                    "&7A skill game is picked", "&7every day at midnight."), false), null);
            return;
        }
        String name = t.playable() != null ? t.playable().name() : t.game().name();
        set(PLAY[2], Menus.glint(Menus.icon(Material.NETHER_STAR, "&eToday's pick: &f" + name,
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

    private void crates(PluginConfig.Arcade arc) {
        List<String> ids = new ArrayList<>(arc.crates().keySet());
        if (ids.size() > CRATES.length) {
            // Three crate slots on the hub; the rest are one click away rather than unreachable.
            set(MORE_CRATES, Menus.icon(Material.BARREL, "&6All crates &7(" + ids.size() + ")",
                    "&eClick to see every crate"),
                    e -> new CrateListMenu(plugin, player, this::reopen).open(player));
        }
        for (int i = 0; i < CRATES.length && i < ids.size(); i++) {
            String id = ids.get(i);
            PluginConfig.Crate crate = arc.crates().get(id);
            set(CRATES[i], ArcadeIcons.of(plugin, player, "crate", Material.CHEST,
                    crate.display() + " &7- &6" + crate.costTokens() + " tokens",
                    "&7See what's inside and the chances,", "&7then open it!", "&eClick to look"),
                    e -> new CrateMenu(plugin, player, id, this::reopen).open(player));
        }
    }

    private void scratch(PluginConfig.Arcade arc) {
        int cost = arc.lotto().ticketTokens();
        set(SCRATCH, ArcadeService.ticketClosed(arc.lotto())
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
        });
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
