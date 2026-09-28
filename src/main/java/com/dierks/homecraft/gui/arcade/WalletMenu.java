package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.storage.TokenDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The Wallet: your tokens, how you're earning them, and where they went.
 *
 * <p>Balance on top; the login streak (with tomorrow's reward), playtime progress, an armed Mini
 * Lure and your trail (click to switch it) in the next row; the ways to earn in the row after; and
 * the last ten token moves at the bottom, one per tile, in plain words — "+5 Quest: Catch 8 fish",
 * "−25 Mini Radar" — with the words in each tile's NAME so they read on Bedrock too. Replaces the
 * old Token Counter.
 *
 * <p>Slot 51 is Take a break (a blue bed, spec §4.3): the player's own daily limit and pause for
 * games of chance, its state in the name ("Take a break - limit 25 a day", "Paused until Tue 12
 * AM"). It sits here, next to where the tokens go, and works whether or not the games are on,
 * because it also covers Crates, the Scratch Ticket and Card Packs bought with tokens.
 */
public final class WalletMenu extends Menu {

    /** History tiles: two centred rows of five. */
    private static final int[] HISTORY = {29, 30, 31, 32, 33, 38, 39, 40, 41, 42};

    private final Player player;
    private final Runnable back;

    public WalletMenu(HomeCraftManagement plugin, Player player, Runnable back) {
        super(plugin);
        this.player = player;
        this.back = back;
        init(54, Text.of("&6Wallet"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        PluginConfig.Arcade arc = plugin.config().arcade();
        TokenService tokens = plugin.tokens();
        int balance = tokens.balance(player.getUniqueId());
        set(4, Menus.glint(ArcadeIcons.of(plugin, player, "wallet", Material.SUNFLOWER,
                "&eYou have &6" + balance + " tokens", "&7Spend them on games and prizes",
                "&7in the Arcade."), true), null);

        int streak = tokens.streak(player.getUniqueId());
        set(10, Menus.icon(Material.CLOCK, "&dLogin streak: &f" + streak + " day" + (streak == 1 ? "" : "s"),
                "&7Tomorrow: &a+" + arc.streakReward(streak + 1) + " tokens",
                "&7Log in every day to keep it going!"), null);

        int toNext = tokens.minutesToNextPlaytimeToken(player);
        set(12, Menus.icon(Material.EXPERIENCE_BOTTLE, toNext >= 0
                        ? "&bNext playtime token: &f" + toNext + " min" : "&7Playtime tokens are off",
                "&7You get a token for every", "&7" + arc.playtimeMinutesPerToken() + " minutes you play."), null);

        boolean lure = plugin.hunt() != null && plugin.hunt().lureArmed(player.getUniqueId());
        set(14, Menus.glint(Menus.icon(lure ? Material.HEART_OF_THE_SEA : Material.GRAY_DYE,
                lure ? "&dMini Lure: &aready!" : "&7Mini Lure: none set",
                lure ? "&7The next wild Mini will appear near you." : "&7Get one at the Prize Counter (Hunt Gear)."),
                lure), null);

        var trail = plugin.trails() == null ? null : plugin.trails().current(player);
        boolean hasAny = plugin.trails() != null && !plugin.trails().owned(player).isEmpty();
        set(16, Menus.glint(Menus.icon(trail != null ? Material.BLAZE_POWDER : Material.GRAY_DYE,
                trail != null ? "&dTrail: &f" + Text.plain(trail.prize().display()) + " &aon"
                        : hasAny ? "&dTrail: &7off" : "&7No trail yet",
                trail != null ? "&7Ends in " + Menus.duration(trail.expiresAt() - System.currentTimeMillis()) : "",
                hasAny ? "&eClick to turn it " + (trail != null ? "off" : "on") : "&7Get one at the Prize Counter."),
                trail != null), hasAny ? e -> {
                    player.sendMessage(Text.of(plugin.trails().toggle(player)));
                    refresh();
                } : null);

        set(18, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a&lWays to earn"), null);
        set(19, Menus.icon(Material.OAK_DOOR, "&aLog in every day", "&7Your streak pays more each day."), null);
        set(20, Menus.icon(Material.DIAMOND_PICKAXE, "&aJust play", arc.playtimeEnabled()
                && arc.playtimeMinutesPerToken() > 0 ? "&7A token every " + arc.playtimeMinutesPerToken()
                + " minutes you play." : "&7Playtime tokens are off."), null);
        set(21, Menus.icon(Material.WRITABLE_BOOK, "&aQuests", "&7Three daily and two weekly jobs.",
                "&eClick to see them"), e -> new QuestsMenu(plugin, player, this::reopen).open(player));
        set(22, Menus.icon(Material.TOTEM_OF_UNDYING, "&aAchievements", "&7Big moments pay once.",
                "&eClick to see them"), e -> new AchievementsMenu(plugin, player, this::reopen, 0).open(player));
        set(23, Menus.icon(Material.SPYGLASS, "&aWild Minis", "&7Catch a wild Mini for bonus tokens."), null);
        set(24, Menus.icon(Material.NAME_TAG, "&aCard trade-in", "&7Swap spare Cards for tokens",
                "&7at the Prize Counter (Minis)."), null);

        set(27, Menus.icon(Material.YELLOW_STAINED_GLASS_PANE, "&e&lToken History", "&7Your last 10 changes."), null);
        List<TokenDao.LedgerRow> rows = tokens.history(player.getUniqueId(), HISTORY.length);
        if (rows.isEmpty()) {
            set(31, Menus.icon(Material.PAPER, "&7Nothing yet", "&7Earn some tokens and they show here."), null);
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < rows.size() && i < HISTORY.length; i++) {
            TokenDao.LedgerRow r = rows.get(i);
            set(HISTORY[i], Menus.icon(r.delta() >= 0 ? Material.LIME_DYE : Material.ORANGE_DYE,
                    (r.delta() >= 0 ? "&a" : "&6") + friendly(r.delta(), TokenService.Source.of(r.source()), r.detail()),
                    "&7" + Menus.duration(now - r.at()) + " ago", "&7Left you with &6" + r.balanceAfter()), null);
        }

        set(49, Menus.icon(Material.BARRIER, back != null ? "&cBack" : "&cClose"), e -> {
            if (back != null) {
                back.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
        // Take a break: a Material.BLUE_BED named with its state (not a CLOCK: that's the streak).
        set(51, com.dierks.homecraft.gui.games.BreakMenu.stateTile(plugin, player),
                e -> new com.dierks.homecraft.gui.games.BreakMenu(plugin, player, this::reopen).open(player));
    }

    /**
     * One ledger line in plain words: "+5 Quest: Catch 8 fish", "−25 Mini Radar". A Prize Counter
     * line is just the prize — "Prize Counter: Mini Radar" says less than "Mini Radar".
     */
    static String friendly(int delta, TokenService.Source source, String detail) {
        String amount = (delta >= 0 ? "+" : "−") + Math.abs(delta);
        String what = detail == null ? "" : Text.plain(detail).trim();
        if (source == TokenService.Source.PRIZE && !what.isEmpty()) {
            return amount + " " + what;
        }
        String label = source == null ? "Tokens" : source.label();
        return amount + " " + label + (what.isEmpty() ? "" : ": " + what);
    }

    private void reopen() {
        new WalletMenu(plugin, player, back).open(player);
    }
}
