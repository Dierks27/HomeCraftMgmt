package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.chance.coinflip.CoinFlip;
import com.dierks.homecraft.games.chance.coinflip.CoinFlipOdds;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The invited player's Coin Flip screen (spec §5.7, R1.8): what is on offer, and the one button
 * that can move tokens.
 *
 * <pre>
 *  tokens  .   .   .  offer  .   .   .  today
 *    .     .   NO  .  what   .  YES  .    .
 *    .     .   .   .  CLOSE  .   .   .    .
 * </pre>
 *
 * <p>Accepting the invite only opened this; Confirm is the moment every check runs again for both
 * players ({@link CoinFlip#confirm}). "No thanks", Close, or letting the invite run out all turn it
 * down, and the inviter is told plainly. The winner's share shown here is the one the flip pays: if
 * a reload changed it in between, the flip is called off rather than paying something else.
 */
public final class CoinFlipConfirmMenu extends GameMenu {

    private static final int BALANCE = 0;
    private static final int OFFER = 4;
    private static final int NO = 11;
    private static final int WHAT = 13;
    private static final int YES = 15;
    private static final int TODAY = 8;

    private final CoinFlip coinFlip;
    private final long inviteId;
    private final CoinFlip.Offer offer;
    private final CoinFlipOdds odds;
    private boolean decided;

    public CoinFlipConfirmMenu(HomeCraftManagement plugin, CoinFlip coinFlip, Player viewer, long inviteId) {
        super(plugin, coinFlip, viewer, null);
        this.coinFlip = coinFlip;
        this.inviteId = inviteId;
        this.offer = coinFlip.offer(inviteId);
        this.odds = offer == null ? null : coinFlip.settings().odds(offer.stake());
        init(27, Text.of("&5Coin Flip"));
    }

    @Override
    public void open(Player player) {
        if (offer == null || odds == null) {
            decided = true;
            coinFlip.decline(player, inviteId, false);
            player.sendMessage(Text.of("&7That Coin Flip invite is gone."));
            return;
        }
        super.open(player);
        if (!isOpenFor(player)) {
            onClose(player); // the screen never opened: turn it down now, not when it runs out
            return;
        }
        ticker(20, this::tick);
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        if (offer == null || odds == null) {
            return;
        }
        set(BALANCE, balanceTile(), null);
        set(OFFER, offerTile(), null);
        set(TODAY, Menus.icon(Material.GOLD_NUGGET, "&e" + coinFlip.today(viewer),
                "&7Tokens put into games of chance today."), null);
        set(NO, Menus.icon(Material.RED_STAINED_GLASS_PANE, "&c&l✗ No thanks"), e -> {
            decided = true;
            coinFlip.decline(viewer, inviteId, false);
            viewer.closeInventory();
        });
        set(WHAT, Menus.icon(Material.GOLD_BLOCK, "&e" + offer.stake() + " tokens each &7- winner gets &6" + odds.pays(),
                "&7" + odds.winnerGets() + ".", "&7Each of you has a 1 in 2 chance.",
                "&7" + odds.giveBack() + ".", "&7Nothing is taken until you confirm."), null);
        set(YES, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a&l✓ Flip for " + offer.stake() + " tokens",
                "&7You put in " + offer.stake() + " and so does " + offer.fromName() + "."), e -> {
            decided = true;
            coinFlip.confirm(viewer, inviteId, odds.pays());
            if (isOpenFor(viewer)) {
                viewer.closeInventory(); // called off: the flip's own screen didn't replace this one
            }
        });
    }

    private ItemStack offerTile() {
        long left = Math.max(0, (offer.expiresAt() - System.currentTimeMillis() + 999) / 1000);
        return Menus.icon(Material.PLAYER_HEAD, "&6Coin Flip with " + offer.fromName(),
                "&7" + offer.fromName() + " asked you to flip", "&7for " + offer.stake() + " tokens each.",
                "&7Answer within " + left + " seconds.");
    }

    /** Once a second: the time left, and turning it down if the invite ran out or went away. */
    private void tick() {
        if (decided) {
            return;
        }
        if (coinFlip.offer(inviteId) == null) {
            decided = true;
            viewer.closeInventory();
            viewer.sendMessage(Text.of("&7That Coin Flip invite is gone."));
            return;
        }
        if (System.currentTimeMillis() >= offer.expiresAt()) {
            decided = true;
            coinFlip.decline(viewer, inviteId, true);
            viewer.closeInventory();
            return;
        }
        getInventory().setItem(OFFER, offerTile());
    }

    @Override
    protected void onClose(Player player) {
        super.onClose(player);
        if (!decided) {
            decided = true;
            coinFlip.decline(player, inviteId, false);
        }
    }
}
