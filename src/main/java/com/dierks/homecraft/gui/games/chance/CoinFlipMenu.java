package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.chance.coinflip.CoinFlip;
import com.dierks.homecraft.games.chance.coinflip.CoinFlipOdds;
import com.dierks.homecraft.games.chance.coinflip.CoinFlipSettings;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Coin Flip's first screen (spec §5.7, R1.8): the rules and the odds for the chosen stake, the
 * player's day, and the way to invite someone.
 *
 * <pre>
 *  tokens  .  rules  .  give-back  .  today  .  flips-left
 *    .     .  stake  .    stake    .  stake  .     .
 *  invites .    .    .    BACK     .    .    .   invite
 * </pre>
 *
 * <p>Nothing is put in here: "Invite a player" opens the shared player picker, and only the
 * invited player's Confirm moves tokens. The invites tile shows whether this player can be asked
 * themselves; it can only be switched in Take a break, which it opens.
 */
public final class CoinFlipMenu extends GameMenu {

    private static final int BALANCE = 0;
    private static final int RULES = 2;
    private static final int GIVE_BACK = 4;
    private static final int TODAY = 6;
    private static final int PLAYS = 8;
    private static final int[] STAKES = {11, 13, 15};
    private static final int INVITES = 18;
    private static final int INVITE = 26;

    private final CoinFlip coinFlip;
    private final CoinFlipSettings settings;
    private int stake;

    public CoinFlipMenu(HomeCraftManagement plugin, CoinFlip coinFlip, Player viewer, Runnable back,
                        CoinFlipSettings settings, int stake) {
        super(plugin, coinFlip, viewer, back);
        this.coinFlip = coinFlip;
        this.settings = settings;
        this.stake = settings.odds(stake) != null || settings.open().isEmpty() ? stake : settings.open().get(0);
        init(27, Text.of("&5Coin Flip"));
    }

    private void reopen() {
        new CoinFlipMenu(plugin, coinFlip, viewer, back, coinFlip.settings(), stake).open(viewer);
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        CoinFlipOdds odds = settings.odds(stake);
        if (odds == null) {
            return;
        }
        set(BALANCE, balanceTile(), null);
        set(RULES, rulesTile(game.rules()), null);
        set(GIVE_BACK, Menus.icon(Material.COMPARATOR, "&e" + odds.giveBack(),
                "&7" + odds.winnerGets() + ".", "&7Each of you has a 1 in 2 chance."), null);
        set(TODAY, Menus.icon(Material.GOLD_NUGGET, "&e" + coinFlip.today(viewer),
                "&7Tokens put into games of chance today."), null);
        int left = coinFlip.playsLeft(viewer, settings);
        set(PLAYS, Menus.icon(Material.CLOCK, left < 0 ? "&eFlips left today" : "&eFlips left today: &f" + left,
                "&7" + settings.dailyLimit() + " flips a day, " + settings.pairDailyLimit() + " with the same player."),
                null);
        stakeButtons();
        boolean on = coinFlip.takesInvites(viewer.getUniqueId());
        set(INVITES, Menus.icon(on ? Material.LIME_DYE : Material.GRAY_DYE,
                on ? "&aYour Coin Flip invites: on" : "&7Your Coin Flip invites: off",
                "&7Others can only ask you when this is on.", "&eClick to change it in Take a break"),
                e -> coinFlip.takeABreak(viewer, this::reopen));
        set(INVITE, inviteTile(odds), e -> coinFlip.invite(viewer, settings, stake, this::reopen));
    }

    /** Up to three stake buttons; with more stakes, a window of three around the chosen one. */
    private void stakeButtons() {
        List<Integer> open = settings.open();
        int idx = Math.max(0, open.indexOf(stake));
        int from = Math.max(0, Math.min(idx - 1, open.size() - STAKES.length));
        for (int j = 0; j < STAKES.length && from + j < open.size(); j++) {
            int s = open.get(from + j);
            CoinFlipOdds o = settings.odds(s);
            boolean chosen = s == stake;
            ItemStack icon = Menus.icon(Material.GOLD_NUGGET,
                    (chosen ? "&a&l" : "&e") + s + " tokens each &7- winner gets &6" + o.pays(),
                    "&7" + o.winnerGets() + ".", chosen ? "&7Chosen." : "&7Click to choose.");
            Menus.glint(icon, chosen);
            if (s <= 64) {
                Menus.count(icon, s);
            }
            set(STAKES[j], icon, chosen ? null : e -> {
                stake = s;
                refresh();
            });
        }
    }

    /** "Invite a player", unlit with the reason when something on screen stops it. */
    private ItemStack inviteTile(CoinFlipOdds odds) {
        CoinFlip.Offer out = coinFlip.outgoing(viewer.getUniqueId());
        if (out != null) {
            return Menus.glint(Menus.icon(Material.CLOCK, "&7Waiting for " + out.toName() + "…",
                    "&7You asked them to flip for " + out.stake() + " tokens each."), false);
        }
        String why = coinFlip.blocker(viewer, settings, stake);
        if (why != null) {
            return Menus.glint(Menus.icon(Material.PLAYER_HEAD, "&7Invite a player &8- " + why), false);
        }
        return Menus.glint(Menus.icon(Material.PLAYER_HEAD, "&aInvite a player &7- &6" + stake + " tokens each",
                "&7" + odds.winnerGets() + ".", "&7Only players close by who have", "&7Coin Flip invites on are listed.",
                "&eClick to pick a player"), true);
    }
}
