package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.chance.coinflip.CoinFlip;
import com.dierks.homecraft.games.chance.coinflip.CoinFlipOdds;
import com.dierks.homecraft.games.chance.coinflip.CoinFlipRules;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * The flip, as each of the two players sees it (spec §5.7): a coin turning between the two
 * players' sides and landing on the winner's.
 *
 * <pre>
 *  tokens  .   .   .   result  .   .   .   .
 *    .     .  gold  .   coin   .  blue  .   .
 *    .     .   .   .   CLOSE   .   .   .   .
 * </pre>
 *
 * <p>The winner was drawn and both players' tokens were settled before this opened; the coin only
 * turns a fixed number of times on a fixed schedule ({@link CoinFlipRules#faces},
 * {@link CoinFlipRules#schedule}) and closing the screen skips to the result. Only the winner's
 * screen glints or plays the win sound; the other just reads "No win this time." Nothing is said
 * to anybody else.
 */
public final class CoinFlipShowMenu extends GameMenu {

    private static final int BALANCE = 0;
    private static final int RESULT = 4;
    private static final int GOLD = 11;
    private static final int COIN = 13;
    private static final int BLUE = 15;
    /** Java: 12 turns in 1.5 seconds. Bedrock: 4 turns in 1.4 seconds. */
    private static final int JAVA_FRAMES = 12;
    private static final int JAVA_TICKS = 30;
    private static final int BEDROCK_FRAMES = 4;
    private static final int BEDROCK_TICKS = 28;

    private final String inviter;
    private final String invited;
    private final boolean inviterWins;
    /** Whether the viewer is the one who asked. */
    private final boolean asked;
    private final CoinFlipOdds odds;
    private final boolean[] faces;
    private final long[] schedule;
    private int frame;
    private long ticks;
    private boolean landed;
    private boolean told;
    private BukkitTask task;

    public CoinFlipShowMenu(HomeCraftManagement plugin, CoinFlip coinFlip, Player viewer, String inviter,
                            String invited, boolean inviterWins, boolean asked, CoinFlipOdds odds) {
        super(plugin, coinFlip, viewer, null);
        this.inviter = inviter;
        this.invited = invited;
        this.inviterWins = inviterWins;
        this.asked = asked;
        this.odds = odds;
        boolean bedrock = Bedrock.is(viewer);
        this.faces = CoinFlipRules.faces(inviterWins, bedrock ? BEDROCK_FRAMES : JAVA_FRAMES);
        this.schedule = CoinFlipRules.schedule(faces.length, bedrock ? BEDROCK_TICKS : JAVA_TICKS);
        init(27, Text.of("&5Coin Flip"));
    }

    @Override
    public void open(Player player) {
        super.open(player);
        if (!isOpenFor(player)) {
            landed = true;
            tell(); // the screen couldn't open: just the result
            return;
        }
        task = ticker(1, this::tick);
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        set(BALANCE, balanceTile(), null);
        set(RESULT, resultTile(), null);
        set(GOLD, Menus.icon(Material.YELLOW_CONCRETE, "&e" + inviter + (asked ? " &7(you)" : ""), "&7Gold side"), null);
        set(BLUE, Menus.icon(Material.LIGHT_BLUE_CONCRETE, "&b" + invited + (asked ? "" : " &7(you)"), "&7Blue side"),
                null);
        set(COIN, coin(landed ? inviterWins : frame == 0 ? !faces[0] : faces[frame - 1]), null);
    }

    private ItemStack coin(boolean inviterSide) {
        return inviterSide
                ? Menus.icon(Material.GOLD_BLOCK, "&e" + inviter + "'s side")
                : Menus.icon(Material.LAPIS_BLOCK, "&b" + invited + "'s side");
    }

    private boolean viewerWon() {
        return asked == inviterWins;
    }

    private ItemStack resultTile() {
        if (!landed) {
            return Menus.icon(Material.COMPASS, "&eFlipping…", "&7Close the screen to skip.");
        }
        if (viewerWon()) {
            return Menus.glint(Menus.icon(Material.GOLD_INGOT, "&aYou won &6" + odds.pays() + " tokens",
                    "&7You put in " + odds.stake() + "."), true);
        }
        return Menus.glint(Menus.icon(Material.GRAY_DYE, "&7No win this time.",
                "&7" + (inviterWins ? inviter : invited) + " won the flip."), false);
    }

    private void tick() {
        if (landed) {
            return;
        }
        ticks++;
        boolean turned = false;
        while (frame < faces.length && ticks >= schedule[frame]) {
            getInventory().setItem(COIN, coin(faces[frame++]));
            turned = true;
        }
        if (turned) {
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f,
                    0.8f + 0.6f * Math.min(1f, (float) frame / faces.length));
        }
        if (frame >= faces.length) {
            landed = true;
            if (task != null) {
                task.cancel();
                task = null;
            }
            refresh();
            tell();
        }
    }

    /** The result, once — on landing, or when the screen is closed early. */
    private void tell() {
        if (told) {
            return;
        }
        told = true;
        if (viewerWon()) {
            Sounds.won(viewer);
            viewer.sendMessage(Text.of("&aCoin Flip: you won &6" + odds.pays() + " tokens&a."));
        } else {
            Sounds.miss(viewer);
            viewer.sendMessage(Text.of("&7Coin Flip: no win this time."));
        }
    }

    @Override
    protected void onClose(Player player) {
        super.onClose(player);
        task = null;
        landed = true;
        tell();
    }
}
