package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.ArcadeService;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.BreakMenu;
import com.dierks.homecraft.gui.games.GamesMenu;
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
 * The Arcade hub — one screen with everything on it, in zones that read as rows even on Bedrock:
 *
 * <pre>
 *  row 0  border ........ [4 Wallet]
 *  row 1  [9 Games / Luck]  10-12 crates · 14 Scratch Ticket (jackpot in its name) · 16 wild Mini
 *  row 2  [18 Prize Counter]  19 Boosts · 20 Hunt Gear · 21 Cosmetics · 22 Perks · 23 Trophies ·
 *                             24 Minis · 25 Card Packs
 *  row 3  [27 You]  29 Quests · 31 Achievements · 33 How It Works
 *  row 4  border — or, while the games are on, [36 Play]  37 All games · 38 Today's pick ·
 *         39 Luck · 40 Cabinets · 41 Courses · 42 Mini golf · 43 Take a break
 *  row 5  border · 49 Close
 * </pre>
 *
 * Every button says what it is and its key number in its NAME, because Bedrock shows lore only on
 * tap-and-hold. The wild-Mini tile counts down while the hub is open.
 *
 * <p>While {@code games.enabled} is false the hub is exactly what it was before the Games: row 1
 * is labelled "Games" and row 4 is border. Once they are on, row 1 reads "Luck" (it is the
 * crates and the ticket) and row 4 becomes the Play row (spec §8.2). Its Luck button follows Take
 * a break (R1.16): "Taking a break until ..." while paused, border for a player who may not play
 * games of chance, and closed if the break can't be read. Row 1's crates and Scratch Ticket follow
 * it too: they are games of chance, so a player on a break sees the same "Taking a break" tile in
 * their place (or "closed"), and one without {@code hcm.games.chance} sees none of them at all.
 */
public final class ArcadeMenu extends Menu {

    static final int WALLET = 4;
    static final int WILD = 16;
    private static final int[] CRATES = {10, 11, 12};
    private static final int MORE_CRATES = 13;
    private static final int SCRATCH = 14;
    private static final int[] TABS = {19, 20, 21, 22, 23, 24};
    private static final int PACKS = 25;
    private static final int QUESTS = 29;
    private static final int ACHIEVEMENTS = 31;
    private static final int GUIDE = 33;
    /** The Play row, while the games are on: label, All, pick, Luck, Cabinets, Courses, golf, break. */
    private static final int[] PLAY = {36, 37, 38, 39, 40, 41, 42, 43};

    private static final ItemStack BORDER = Menus.icon(Material.PURPLE_STAINED_GLASS_PANE, " ");

    private final Player player;
    private BukkitTask ticker;

    public ArcadeMenu(HomeCraftManagement plugin, Player player) {
        super(plugin);
        this.player = player;
        init(54, Text.of("&5&lArcade"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            boolean border = i < 9 || i >= 36;
            set(i, border ? BORDER : Menus.FILLER, null);
        }
        GamesService games = plugin.games();
        boolean playRow = games != null && games.config().enabled();
        set(9, Menus.icon(Material.ORANGE_STAINED_GLASS_PANE, playRow ? "&6&lLuck" : "&6&lGames",
                "&7Crates and the Scratch Ticket."), null);
        set(18, Menus.icon(Material.YELLOW_STAINED_GLASS_PANE, "&e&lPrize Counter",
                "&7Pick what you want. No luck needed."), null);
        set(27, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a&lYou",
                "&7Your quests, achievements", "&7and how it all works."), null);

        PluginConfig.Arcade arc = plugin.config().arcade();
        wallet(arc);
        GamesMenu.LuckView luck = playRow ? GamesMenu.luck(plugin, player) : null;
        if (GamesMenu.hubShowsChance(playRow, luck == null ? null : luck.state())) {
            crates(arc);
            scratch(arc);
        } else {
            chanceStandIn(luck);
        }
        set(WILD, wildTile(), null);
        prizeTabs();
        set(PACKS, ArcadeIcons.of(plugin, player, "packs", Material.PAPER, "&bCard Packs",
                "&7Open a pack, get a Card,", "&7print it into a Mini.", "&eClick to shop"),
                e -> new com.dierks.homecraft.gui.mini.PackShopMenu(plugin, player, this::reopen).open(player));
        you();
        if (playRow && player.hasPermission("hcm.games.play")) {
            play(games, luck);
        }
        set(49, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
    }

    /** Row 1 in place of the crates and the ticket while games of chance aren't open to the player. */
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

    /** Row 4 while the games are on: the doors into the Games screen's tabs, and Take a break. */
    private void play(GamesService games, GamesMenu.LuckView luck) {
        set(PLAY[0], Menus.icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&b&lPlay",
                "&7Games of luck and skill,", "&7for tokens."), null);
        set(PLAY[1], Menus.icon(Material.BOOKSHELF, "&bAll games"
                + com.dierks.homecraft.games.event.RaceNight.hubSuffix(games), "&7Every game that's open.",
                "&eClick to see them"), e -> tab(null));
        pick(games);
        switch (luck.state()) {
            case OPEN -> set(PLAY[3], Menus.icon(Material.GOLD_NUGGET, "&6Luck",
                    "&7Games of chance, crates", "&7and the Scratch Ticket.", "&eClick to see them"),
                    e -> tab(Game.Tab.LUCK));
            case PAUSED -> set(PLAY[3], GamesMenu.breakTile(plugin, luck.until()),
                    e -> new BreakMenu(plugin, player, this::reopen).open(player));
            case CLOSED -> set(PLAY[3], GamesMenu.closedTile(), null);
            case HIDDEN -> {
                // no games of chance for this player: the slot stays border
            }
        }
        door(games, PLAY[4], GameKind.CABINET, Game.Tab.CABINETS, Material.JUKEBOX, "Cabinets",
                "Little video games.");
        door(games, PLAY[5], GameKind.TRIAL, Game.Tab.COURSES, Material.FEATHER, "Courses",
                "Race the clock.");
        door(games, PLAY[6], GameKind.GOLF, Game.Tab.GOLF, Material.SNOWBALL, "Mini golf",
                "Your Mini is the ball.");
        set(PLAY[7], BreakMenu.stateTile(plugin, player), e -> new BreakMenu(plugin, player, this::reopen).open(player));
    }

    /** A tab's door, or a plain "closed" tile when no game of its kind is open. */
    private void door(GamesService games, int slot, GameKind kind, Game.Tab tab, Material icon, String name,
                      String blurb) {
        boolean any = false;
        for (Game g : games.games()) {
            if (g.kind() == kind && games.enabled(g)) {
                any = true;
                break;
            }
        }
        if (!any) {
            set(slot, Menus.icon(Material.GRAY_DYE, "&7" + name + " &8- closed"), null);
            return;
        }
        set(slot, Menus.icon(icon, "&b" + name, "&7" + blurb, "&eClick to see them"), e -> tab(tab));
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
            set(TABS[i], PrizeCounterMenu.tabIcon(plugin, player, t, false),
                    e -> new PrizeCounterMenu(plugin, player, this::reopen, t, 0).open(player));
        }
    }

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

        set(GUIDE, ArcadeIcons.of(plugin, player, "guide", Material.KNOWLEDGE_BOOK, "&bHow It Works",
                "&7Tokens, Minis, wild Minis", "&7and the Arcade games.", "&eClick to read"),
                e -> new GuideMenu(plugin, player, 0, this::reopen).open(player));
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
