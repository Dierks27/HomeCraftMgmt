package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.ArcadeService;
import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.BreakMenu;
import com.dierks.homecraft.gui.games.Screens;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * How It Works: five pages, each a picture of how one thing flows — items joined by arrow panes,
 * the step in each item's name, one or two short lines of lore. Reachable from the Arcade hub,
 * the pack shop's "?", the PC's Guide site, the Games screen and {@code /hcm guide}.
 *
 * <p>Written for the youngest player on the server: short words, one idea per tile, and the
 * important part in the name, since Bedrock shows lore only on tap-and-hold.
 *
 * <p>The Games page (spec §8.4) never states a number it made up: what each game of chance gives
 * back is read from the game's own engine (the same object it plays with, via what it publishes),
 * and the limits and daily amounts come from the live config.
 */
public final class GuideMenu extends Menu {

    public static final int TOKENS = 0;
    public static final int MINIS = 1;
    public static final int WILD = 2;
    /** Crates, the Scratch Ticket, the Rare Card and trade-in. */
    public static final int ARCADE = 3;
    /** The Games: what games of chance give back, what skill games pay, Take a break. */
    public static final int GAMES_PAGE = 4;
    private static final String[] TITLES = {"Tokens", "Minis", "Wild Minis", "Arcade", "Games"};
    private static final Material[] TAB_ICONS = {Material.SUNFLOWER, Material.PAPER, Material.SPYGLASS,
            Material.CHEST, Material.TARGET};
    private static final int[] TAB_SLOTS = {0, 2, 4, 6, 8};
    /** Where the games of chance line up on the Games page. */
    private static final int[] CHANCE_SLOTS = {14, 15, 16, 23, 24, 25};

    private final Player player;
    private final int page;
    private final Runnable back;

    public GuideMenu(HomeCraftManagement plugin, Player player, int page, Runnable back) {
        super(plugin);
        this.player = player;
        this.page = Math.max(0, Math.min(TITLES.length - 1, page));
        this.back = back;
        init(54, Text.of("&bHow It Works &8· &3" + TITLES[this.page]));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        for (int i = 0; i < TITLES.length; i++) {
            int p = i;
            boolean here = p == page;
            set(TAB_SLOTS[i], Menus.glint(Menus.icon(TAB_ICONS[i], (here ? "&a&l" : "&e") + TITLES[i],
                    here ? "&7You're here." : "&eClick to read"), here),
                    here ? null : e -> new GuideMenu(plugin, player, p, back).open(player));
        }
        switch (page) {
            case TOKENS -> tokens();
            case MINIS -> minis();
            case WILD -> wild();
            case GAMES_PAGE -> gamesPage();
            default -> arcade();
        }
        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&fPrevious: " + TITLES[page - 1]),
                    e -> new GuideMenu(plugin, player, page - 1, back).open(player));
        }
        if (page < TITLES.length - 1) {
            set(53, Menus.icon(Material.ARROW, "&fNext: " + TITLES[page + 1]),
                    e -> new GuideMenu(plugin, player, page + 1, back).open(player));
        }
        set(49, Menus.icon(Material.BARRIER, back != null ? "&cBack" : "&cClose"), e -> {
            if (back != null) {
                back.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    private static ItemStack arrow(String glyph) {
        return Menus.icon(Material.WHITE_STAINED_GLASS_PANE, "&f" + glyph);
    }

    private void step(int slot, Material m, String name, String... lore) {
        set(slot, Menus.icon(m, name, lore), null);
    }

    /** "A token for every hour." — from the config, so the guide never disagrees with the Wallet. */
    private String playtimeLine() {
        var arc = plugin.config().arcade();
        int m = arc.playtimeMinutesPerToken();
        if (!arc.playtimeEnabled() || m <= 0) {
            return "Playtime tokens are off.";
        }
        return m == 60 ? "A token for every hour." : "A token every " + m + " minutes.";
    }

    /** Four ways in → tokens → four ways to spend them, one lane per row. */
    private void tokens() {
        step(11, Material.OAK_DOOR, "&aLog in every day", "&7Your streak pays more", "&7each day in a row.");
        step(20, Material.CLOCK, "&aPlay", "&7" + playtimeLine());
        step(29, Material.WRITABLE_BOOK, "&aDo quests", "&7Three daily jobs and", "&7two weekly ones.");
        step(38, Material.TOTEM_OF_UNDYING, "&aGet achievements", "&7Big moments pay once.");
        for (int row = 0; row < 4; row++) {
            set(12 + row * 9, arrow("→"), null);
            set(13 + row * 9, Menus.glint(Menus.icon(Material.SUNFLOWER, "&6Tokens!",
                    "&7See them in your Wallet."), true), null);
            set(14 + row * 9, arrow("→"), null);
        }
        step(15, Material.CHEST, "&dOpen a crate", "&7Boosts, trails, hats…", "&7or a Card!");
        step(24, Material.FILLED_MAP, "&dScratch a ticket", "&7Match three to win.");
        step(33, Material.ITEM_FRAME, "&dPrize Counter", "&7Pick exactly what you want.");
        step(42, Material.PAPER, "&dCard Packs", "&7Some packs take tokens too.");
    }

    /** Card Pack → Card → Printer (+ filament) → Mini → Display Case / Museum. */
    private void minis() {
        step(18, Material.PAPER, "&b1. Open a Card Pack", "&7Right-click it.", "&7You get a Card.");
        set(19, arrow("→"), null);
        step(20, Material.FLOWER_BANNER_PATTERN, "&b2. A Card", "&7It says which Mini", "&7it makes.");
        set(21, arrow("→"), null);
        step(13, Material.STRING, "&e+ Filament", "&7The Printer needs it.", "&7Craft it or buy it.");
        step(22, Material.SMITHING_TABLE, "&b3. Printer", "&7Put the Card in", "&7and print!");
        set(23, arrow("→"), null);
        step(24, Material.PLAYER_HEAD, "&b4. Your Mini!", "&7Every one is numbered.");
        set(25, arrow("→"), null);
        step(26, Material.GLASS, "&b5. Show it off", "&7In a Display Case…");
        step(35, Material.LECTERN, "&b…or the Museum", "&7See every Mini there is.");
        step(40, Material.SPYGLASS, "&dOr catch one wild!", "&7See the Wild Minis page.");
    }

    /** The hunt: announcement → hints → beam → catch; the Radar and the Lure help. */
    private void wild() {
        step(19, Material.BELL, "&d1. Everyone hears", "&7\"A wild Mini appeared", "&7near someone!\"");
        set(20, arrow("→"), null);
        step(21, Material.MAP, "&d2. Hints", "&7The biome, then which", "&7way to go.");
        set(22, arrow("→"), null);
        step(23, Material.BEACON, "&d3. A light beam", "&7Near the end, look", "&7for the beam!");
        set(24, arrow("→"), null);
        step(25, Material.PLAYER_HEAD, "&d4. Catch it!", "&7Right-click it to keep it.", "&7Be quick — it runs away.");
        step(38, Material.COMPASS, "&bMini Radar", "&7Cold, Warm, Hot…", "&7Burning!");
        step(40, Material.CLOCK, "&bAbout 5 minutes", "&7Then it gets away.");
        step(42, Material.HEART_OF_THE_SEA, "&bMini Lure", "&7The next wild Mini", "&7appears near you.");
    }

    /** Crate, Scratch Ticket, Rare Card and Card trade-in, each over what it gives. */
    private void arcade() {
        step(19, Material.CHEST, "&6Crates", "&7Pay tokens, get a surprise.");
        step(28, Material.FIREWORK_ROCKET, "&7Boosts, trails, hats…", "&7and sometimes a Card!");
        step(21, Material.FILLED_MAP, "&6Scratch Ticket",
                "&7" + plugin.config().arcade().lotto().ticketTokens() + " tokens a ticket.");
        step(30, Material.GOLD_BLOCK, "&7Match three to win", "&7The jackpot grows", "&7with every ticket!");
        step(23, Material.NETHER_STAR, "&6Rare Card", "&7At the Prize Counter.");
        int perWeek = plugin.config().arcade().pityPerWeek();
        step(32, Material.AMETHYST_SHARD, "&7Rare or better",
                "&7" + (perWeek <= 0 ? "Any time." : perWeek == 1 ? "One a week." : perWeek + " a week."));
        step(25, Material.HOPPER, "&6Trade In Cards", "&7Spare Cards?");
        step(34, Material.SUNFLOWER, "&7Swap them for tokens", "&7Rarer Cards give more.");
    }

    /**
     * Pick a game → games of chance (each with what it gives back, from its engine) → skill games
     * (milestones, the daily challenge, a daily amount) → Take a break.
     */
    private void gamesPage() {
        GamesService games = plugin.games();
        GamesConfig.Common common = plugin.config().games().common();
        boolean open = games != null && plugin.config().games().enabled();
        if (!open) {
            step(22, Material.GRAY_DYE, "&7The games are closed right now",
                    "&7Take a break still covers", "&7Crates, tickets and packs.");
        } else {
            step(10, Material.CHEST, "&bPick a game", "&7In the Arcade, or", "&7type /hcm play.");
            if (player.hasPermission("hcm.games.chance")) {
                set(11, arrow("→"), null);
                step(12, Material.GOLD_NUGGET, "&6Games of chance", "&7You put tokens in. Each one",
                        "&7gives back less than it takes.");
                set(13, arrow("→"), null);
                chanceGames(games);
                step(21, Material.PAPER, "&6Before you play", "&7Its screen shows the rules",
                        "&7and what it gives back.");
                if (common.chanceDailyTokens() > 0) {
                    step(22, Material.HOPPER, "&6Everyone: up to " + common.chanceDailyTokens() + " a day",
                            "&7Tokens put into all games", "&7of chance, each day.");
                }
            }
            step(28, Material.TARGET, "&aSkill games", "&7Free to play.", "&7Beat your best!");
            set(29, arrow("→"), null);
            step(30, Material.GOLD_INGOT, "&aMilestones", "&7Bronze, silver and gold.", "&7Each pays once, ever.");
            step(31, Material.CLOCK, "&aDaily challenge", "&7One scored try a day.", "&7Meet the goal to earn.");
            set(32, arrow("→"), null);
            int cap = common.skillDailyCap();
            step(33, Material.SUNFLOWER, "&6Up to " + cap + " token" + (cap == 1 ? "" : "s") + " a day",
                    "&7from skill games.", "&7Scores always count.");
            if (common.featuredBonus() > 0) {
                step(34, Material.NETHER_STAR, "&eToday's pick", "&7Finish it for a bonus",
                        "&7of " + common.featuredBonus() + " more.");
            }
        }
        int wait = common.breakRaiseDelayDays();
        step(37, Material.BLUE_BED, "&bTake a break", "&7Set a daily limit, or", "&7pause games of chance.");
        set(38, arrow("→"), null);
        step(39, Material.BOOK, "&bIt covers", BreakMenu.coversLore());
        step(41, Material.LIME_DYE, "&aLower: right away", "&7A lower limit starts now.");
        step(42, Material.YELLOW_DYE, "&eHigher: later", "&7A higher one waits",
                "&7" + wait + " day" + (wait == 1 ? "" : "s") + ", then midnight.");
        step(43, Material.GRAY_DYE, "&7A pause stays", "&7It can't be made shorter.");
    }

    /** Every open game of chance, and the Scratch Ticket, with what it gives back. */
    private void chanceGames(GamesService games) {
        int i = 0;
        for (Game g : games.games()) {
            if (g.kind() != GameKind.CHANCE || i >= CHANCE_SLOTS.length) {
                continue;
            }
            double back = Screens.giveBack(games, g);
            if (Double.isNaN(back)) {
                continue;
            }
            step(CHANCE_SLOTS[i++], Material.GOLD_NUGGET, "&6" + g.name() + " &7- about "
                            + RtpLimits.wholePercent(back) + " of every 100 back",
                    "&7It " + RtpLimits.playerLine(back) + ",", "&7over lots of plays.");
        }
        var lotto = plugin.config().arcade().lotto();
        if (i < CHANCE_SLOTS.length && plugin.arcade() != null && !lotto.payouts().isEmpty()) {
            double back = ArcadeService.rtp(lotto);
            step(CHANCE_SLOTS[i++], Material.FILLED_MAP, "&6Scratch Ticket &7- about "
                            + RtpLimits.wholePercent(back) + " of every 100 back",
                    "&7It " + RtpLimits.playerLine(back) + ",", "&7over lots of tickets.");
        }
        if (i == 0) {
            step(CHANCE_SLOTS[0], Material.GRAY_DYE, "&7None are open right now");
        }
    }
}
