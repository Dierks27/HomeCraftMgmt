package com.dierks.homecraft.gui.games.kidbingo;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.kidbingo.KidBingo;
import com.dierks.homecraft.games.kidbingo.KidBingo.Entry;
import com.dierks.homecraft.games.kidbingo.KidBingo.Phase;
import com.dierks.homecraft.games.kidbingo.Picture;
import com.dierks.homecraft.games.kidbingo.PictureCard;
import com.dierks.homecraft.gui.MenuStyle;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.ClickHold;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;

/**
 * Picture Bingo's screen (0.57.0, 54 slots), made for a four-year-old with a controller who can't read yet: pictures
 * only, nothing to tap during the game (the card lights itself), and the two things a cursor ever needs, Go and the
 * door, side by side in the bottom row.
 *
 * <pre>
 *  row 0        everyone in the round, a head each (the stack's count: how many squares their card has lit)
 *  rows 1-3     columns 1-3 your card: eight animals round a star, a lit square glows ·
 *               columns 5-7 the big caller: the animal just called, nine times over, so it is big
 *  row 4        the animals called so far, newest last
 *  row 5        a big green Go while the round waits (it starts when everyone tapped it) · 49 the door (All done)
 * </pre>
 *
 * When the round is won the screen turns into the kid screen ({@link #ended}): the card stays, the caller is
 * fireworks if your card won (or a cake, a happy picture, if another did), and one big green Play again picture fills
 * the two rows under it, beside the door.
 */
public final class KidBingoMenu extends GameMenu {

    /** Your card's nine squares, by square. */
    static final int[] CARD = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    /** The big caller's nine slots. */
    static final int[] CALLER = {14, 15, 16, 23, 24, 25, 32, 33, 34};
    /** The frames round the card and the caller. */
    private static final int[] CARD_FRAME = {9, 13, 18, 22, 27, 31};
    private static final int[] CALLER_FRAME = {17, 26, 35};
    private static final int CALLED_FROM = 36;
    private static final int GO_FROM = 45;
    private static final int GO_TO = 53;

    private final KidBingo bingo;
    private boolean watching;
    /** Set when the round has ended: the kid screen. */
    private boolean ended;
    private boolean mineWon;

    public KidBingoMenu(HomeCraftManagement plugin, KidBingo game, Player viewer, Runnable back) {
        super(plugin, game, viewer, back);
        this.bingo = game;
        init(54, Text.of(MenuStyle.title("Picture Bingo")));
    }

    /** This screen as the kid screen after a round ({@code won}: the viewer's card won). */
    public KidBingoMenu ended(boolean won) {
        this.ended = true;
        this.mineWon = won;
        return this;
    }

    @Override
    public void open(Player player) {
        super.open(player);
        if (ended) {
            hold(KID_SETTLE_MS); // the win is seen before a stray tap starts the next round
            return;
        }
        if (showing()) {
            watching = true;
            bingo.watch(viewer.getUniqueId(), this::roundChanged);
        }
    }

    @Override
    protected void onClose(Player player) {
        super.onClose(player);
        if (watching) {
            watching = false;
            bingo.unwatch(viewer.getUniqueId());
        }
    }

    private void roundChanged() {
        if (showing()) {
            refresh();
        }
    }

    // ---- pictures (pure) ----------------------------------------------------------------------------------------

    /** An animal's picture: its spawn egg (every client draws one), or a plain egg. */
    static Material egg(Picture p) {
        Material m = p == null ? null : Material.getMaterial(p.egg());
        return m == null ? Material.EGG : m;
    }

    // ---- painting -----------------------------------------------------------------------------------------

    @Override
    protected void build() {
        fill();
        Entry me = ended ? bingo.lastCard(viewer.getUniqueId()) : bingo.entry(viewer.getUniqueId());
        frames();
        if (!ended) {
            paintPlayers();
        }
        paintCard(me);
        paintCaller();
        paintCalled();
        if (ended) {
            bigAgain(slots(CALLED_FROM, GO_TO), () -> plugin.games().open(viewer, KidBingo.SPEC.id(), null));
        } else if (me != null && bingo.phase() == Phase.GATHERING) {
            paintGo(me);
        }
        kidExit();
    }

    private void frames() {
        ItemStack card = Menus.icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " ");
        ItemStack caller = Menus.icon(Material.YELLOW_STAINED_GLASS_PANE, " ");
        for (int s : CARD_FRAME) {
            set(s, card, null);
        }
        for (int s : CALLER_FRAME) {
            set(s, caller, null);
        }
    }

    /** Row 0: a head for everyone in the round, its count the squares their card has lit. */
    private void paintPlayers() {
        List<Entry> all = bingo.entries();
        for (int i = 0; i < all.size() && i < 9; i++) {
            Entry e = all.get(i);
            int lit = e.card().litCount(e.caught());
            ItemStack head = Menus.count(Menus.glint(Menus.icon(e.npc() ? Material.ARMOR_STAND : Material.PLAYER_HEAD,
                    (e.id().equals(viewer.getUniqueId()) ? "&aYou" : e.npc() ? e.name() : "&f" + e.name())), e.won()),
                    lit);
            set(i, head, null);
        }
    }

    private void paintCard(Entry me) {
        if (me == null) {
            ItemStack wait = Menus.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7Watching",
                    "&7You play the next one.");
            for (int slot : CARD) {
                set(slot, wait, null);
            }
            return;
        }
        PictureCard card = me.card();
        Set<Picture> caught = me.caught();
        int[] line = ended && me.won() ? card.line(caught) : null;
        for (int sq = 0; sq < PictureCard.SQUARES; sq++) {
            boolean inLine = false;
            if (line != null) {
                for (int s : line) {
                    inLine |= s == sq;
                }
            }
            if (sq == PictureCard.FREE) {
                set(CARD[sq], Menus.glint(Menus.icon(Material.NETHER_STAR, "&eFree star"), true), null);
                continue;
            }
            Picture p = card.at(sq);
            boolean lit = card.lit(sq, caught);
            ItemStack icon = Menus.glint(Menus.icon(egg(p), (inLine ? "&6&l" : lit ? "&a" : "&f") + p.label()), lit);
            set(CARD[sq], icon, null);
        }
    }

    /** The big caller: the animal just called, nine times over (or what the round is waiting for). */
    private void paintCaller() {
        ItemStack big;
        if (ended) {
            big = mineWon ? Menus.glint(Menus.icon(Material.FIREWORK_ROCKET, "&6&lBINGO!"), true)
                    : Menus.glint(Menus.icon(Material.CAKE, "&aHooray!"), true);
        } else if (bingo.phase() == Phase.GATHERING) {
            int left = Math.max(1, bingo.secondsLeft());
            big = Menus.count(Menus.icon(Material.BELL, "&eSoon!"), left);
        } else {
            Picture now = bingo.current();
            big = now == null ? Menus.icon(Material.BELL, "&eListen!")
                    : Menus.glint(Menus.icon(egg(now), "&e&l" + now.label()), true);
        }
        for (int slot : CALLER) {
            set(slot, big, null);
        }
    }

    /** Row 4: the animals called so far, newest last (the last nine). */
    private void paintCalled() {
        if (ended) {
            return; // Play again goes there
        }
        List<Picture> so = bingo.calledSoFar();
        int from = Math.max(0, so.size() - 9);
        for (int i = from; i < so.size(); i++) {
            Picture p = so.get(i);
            set(CALLED_FROM + (i - from), Menus.icon(egg(p), "&7" + p.label()), null);
        }
    }

    /** While the round waits: the bottom row is one big green Go, the door in its middle. */
    private void paintGo(Entry me) {
        ItemStack go = me.startNow()
                ? Menus.glint(Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&aReady!"), true)
                : Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&a&l▶ Go!"), true);
        for (int slot = GO_FROM; slot <= GO_TO; slot++) {
            set(slot, go, me.startNow() ? null : e -> {
                hold(ClickHold.ACTION_MS);
                bingo.startNow(viewer);
            });
        }
    }
}
