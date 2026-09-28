package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.ArcadeService;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A Scratch Ticket you scratch: three covered squares, "Tap to scratch!". Each tap uncovers one;
 * three the same is a win. The result was decided — and paid — when the ticket was bought, so
 * this is only the fun part: closing it early just tells you what you got.
 *
 * <p>The squares show exactly what happened, never an "almost". A ticket that gave back some of
 * its price used to show two suns and a lump of coal — an engineered near miss that also dressed a
 * loss up as nearly a win. It now shows three plain squares that say "Tokens back", and the result
 * line says how many (spec §2, R1.18). Which result it was comes from the outcome's own
 * {@link ArcadeService.Outcome#someBack()} flag, not from the icon the service drew it with.
 */
public final class ScratchTicketMenu extends Menu {

    private static final int[] SQUARES = {11, 13, 15};
    private static final int HEADER = 4;
    private static final int EXIT = 22;
    /** The plain square of a ticket that gave some tokens back: not a symbol that wins anything. */
    private static final Material BACK = Material.IRON_NUGGET;

    private final Player player;
    private final ArcadeService.Outcome outcome;
    private final Runnable back;
    /** The symbols in scratch order. */
    private final Material[] symbols;
    /** What each square shows once scratched (null while covered). */
    private final Material[] shown = new Material[SQUARES.length];
    private int scratched;
    private boolean told;

    public ScratchTicketMenu(HomeCraftManagement plugin, Player player, ArcadeService.Outcome outcome, Runnable back) {
        super(plugin);
        this.player = player;
        this.outcome = outcome;
        this.back = back;
        this.symbols = symbols(outcome);
        init(27, Text.of("&aScratch Ticket"));
    }

    /**
     * Big win: three stars. Win: three suns. Some tokens back: three plain "Tokens back" squares,
     * none of them a winning symbol. Nothing: three different things.
     */
    static Material[] symbols(ArcadeService.Outcome o) {
        if (o.big()) {
            return new Material[] {Material.NETHER_STAR, Material.NETHER_STAR, Material.NETHER_STAR};
        }
        if (o.win()) {
            return new Material[] {Material.SUNFLOWER, Material.SUNFLOWER, Material.SUNFLOWER};
        }
        if (o.someBack()) {
            return new Material[] {BACK, BACK, BACK};
        }
        return new Material[] {Material.COAL, Material.FLINT, Material.CLAY_BALL};
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }
        set(HEADER, done() && outcome.icon() != null ? outcome.icon()
                : Menus.icon(Material.FILLED_MAP, "&aMatch three to win!", "&7Tap each square."), null);
        for (int i = 0; i < SQUARES.length; i++) {
            int square = i;
            if (shown[i] != null) {
                set(SQUARES[i], symbolIcon(shown[i], outcome.returned()), null);
            } else {
                set(SQUARES[i], Menus.icon(Material.GRAY_CONCRETE, "&7Tap to scratch!"), e -> scratch(square));
            }
        }
        set(EXIT, Menus.icon(Material.BARRIER, done() ? "&cBack" : "&7Back &8(shows your result)"), e -> {
            if (back != null) {
                back.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    private static ItemStack symbolIcon(Material m, int returned) {
        String name = switch (m) {
            case NETHER_STAR -> "&6Star!";
            case SUNFLOWER -> "&eToken!";
            case IRON_NUGGET -> "&7Tokens back: &f" + returned;
            default -> "&8Nothing";
        };
        return Menus.glint(Menus.icon(m, name), m == Material.NETHER_STAR);
    }

    private boolean done() {
        return scratched >= SQUARES.length;
    }

    private void scratch(int square) {
        if (shown[square] != null || done()) {
            return;
        }
        shown[square] = symbols[scratched++];
        player.playSound(player.getLocation(), Sound.ITEM_BRUSH_BRUSHING_GENERIC, 1.0f, 0.9f + 0.2f * scratched);
        if (done()) {
            tell();
        }
        refresh();
    }

    /** The result, once — when the last square comes off, or when the ticket is closed early. */
    private void tell() {
        if (told) {
            return;
        }
        told = true;
        if (outcome.win()) {
            Sounds.won(player);
            player.sendMessage(Text.of("&aYou got " + outcome.label() + "&a!"));
            if (outcome.big()) {
                BigWin.celebrate(player, outcome.label());
            }
        } else {
            Sounds.miss(player);
            player.sendMessage(Text.of(outcome.someBack() && outcome.label() != null
                    ? "&7No win this time. " + outcome.label() + "&7."
                    : "&7No win this time."));
        }
    }

    /** Paid already: if the screen could not open, say what the ticket was right away. */
    @Override
    public void open(Player viewer) {
        super.open(viewer);
        if (!isOpenFor(viewer)) {
            tell();
        }
    }

    @Override
    protected void onClose(Player viewer) {
        tell();
    }
}
