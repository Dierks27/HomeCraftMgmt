package com.dierks.homecraft.gui.games.golf;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.golf.GolfCard;
import com.dierks.homecraft.games.golf.GolfGroup;
import com.dierks.homecraft.games.golf.GolfRun;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.games.trial.PartyLobby;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Golf together's shared scorecard (54, EVENTS-OWNER-DECISIONS D4): every player of the group, one
 * row each, shown between holes, from the kit's "Scorecard", and at the end with the group ranking.
 *
 * <pre>
 *  row 0     4 the course, the hole everyone is on (or "final")
 *  rows 1-4  one per player: the player (their place and total at the end, where they are before),
 *            then 8 holes (the stack count is the hole number): green under par, white par, yellow
 *            over, red picked up, a star for a hole-in-one, light blue being played, grey not yet
 *  row 5     45 / 53 more holes (18-hole courses), 47 who is still out (with the hole clock's
 *            "picked up in 1:45" in its NAME), 49 Back/Close,
 *            51 "Play again together" at the end, once you're home
 * </pre>
 *
 * It shows a snapshot ({@link GolfGroup.Card}), never the live group. The layout is {@link #tiles}
 * (pure, tested).
 */
public final class GolfGroupCardMenu extends GameMenu {

    public static final int SIZE = 54;
    public static final int HEADER = 4;
    /** Holes shown per row (a page). */
    public static final int PER_PAGE = 8;
    public static final int PREV = 45;
    public static final int STATUS = 47;
    public static final int EXIT = 49;
    public static final int AGAIN = 51;
    public static final int NEXT = 53;

    /**
     * One painted slot.
     *
     * @param amount the stack count (a hole's number), 1 for everything else
     * @param glint  a hole-in-one shines
     */
    public record Tile(int slot, Material material, String name, List<String> lore, int amount, boolean glint) {

        public Tile {
            lore = List.copyOf(lore);
        }

        Tile(int slot, Material material, String name, List<String> lore) {
            this(slot, material, name, lore, 1, false);
        }
    }

    private final MiniGolf golf;
    private final GolfGroup.Card card;
    private final int page;

    public GolfGroupCardMenu(HomeCraftManagement plugin, MiniGolf golf, Player viewer, GolfGroup.Card card, Runnable back) {
        this(plugin, golf, viewer, card, 0, back);
    }

    private GolfGroupCardMenu(HomeCraftManagement plugin, MiniGolf golf, Player viewer, GolfGroup.Card card, int page,
                              Runnable back) {
        super(plugin, golf, viewer, back);
        this.golf = golf;
        this.card = card;
        this.page = Math.max(0, Math.min(page, pages(card) - 1));
        init(SIZE, Text.of("&d" + card.courseName() + " &8· &5Together"));
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        PartyLobby party = golf.together().party(viewer.getUniqueId());
        boolean again = card.over() && party != null && party.state() == PartyLobby.State.OPEN
                && golf.games().sessions().home(viewer) && golf.playableCourse(card.courseId()) != null;
        for (Tile t : tiles(card, page, again)) {
            if (t.slot() == EXIT || t.material() == Material.GRAY_STAINED_GLASS_PANE) {
                continue;
            }
            ItemStack item = Menus.glint(Menus.icon(t.material(), t.name(), t.lore().toArray(new String[0])), t.glint());
            set(t.slot(), t.amount() > 1 ? Menus.count(item, t.amount()) : item, e -> click(t.slot(), party));
        }
    }

    private void click(int slot, PartyLobby party) {
        switch (slot) {
            case PREV -> new GolfGroupCardMenu(plugin, golf, viewer, card, page - 1, back).open(viewer);
            case NEXT -> new GolfGroupCardMenu(plugin, golf, viewer, card, page + 1, back).open(viewer);
            case AGAIN -> {
                if (party != null) {
                    new GolfPartyMenu(plugin, golf, viewer, party.id(), null).open(viewer);
                }
            }
            default -> {
                // looking only
            }
        }
    }

    // ---- the layout (pure) ------------------------------------------------------------------------

    /** How many pages of holes the card has. */
    public static int pages(GolfGroup.Card card) {
        return Math.max(1, (card.pars().size() + PER_PAGE - 1) / PER_PAGE);
    }

    /**
     * Every slot of the card on {@code page}, in slot order.
     *
     * @param again show "Play again together" (the round is over, the viewer is home and still in the party)
     */
    public static List<Tile> tiles(GolfGroup.Card card, int page, boolean again) {
        Tile[] out = new Tile[SIZE];
        int holes = card.pars().size();
        int p = Math.max(0, Math.min(page, pages(card) - 1));
        out[HEADER] = new Tile(HEADER, Material.PAPER, "&dGolf together &8· &f" + card.courseName() + " &7- "
                + (card.over() ? "final" : "hole " + (card.hole() + 1) + " of " + holes),
                List.of("&7" + MiniGolf.holes(holes) + ", par " + card.par() + ".", "&7Every player, hole by hole."));
        List<GolfGroup.Standing> ranking = GolfGroup.ranking(card.rows(), holes);
        List<String> out1 = new ArrayList<>();
        for (int i = 0; i < card.rows().size() && i < GolfGroup.MAX; i++) {
            GolfGroup.Row row = card.rows().get(i);
            int base = 9 + 9 * i;
            out[base] = playerTile(base, row, place(ranking, row), card);
            for (int j = 0; j < PER_PAGE; j++) {
                int h = p * PER_PAGE + j;
                if (h < holes) {
                    out[base + 1 + j] = holeTile(base + 1 + j, row, h, card);
                }
            }
            if (row.seat() == GolfGroup.Seat.PLAYING) {
                out1.add(row.name());
            }
        }
        if (p > 0) {
            out[PREV] = new Tile(PREV, Material.ARROW, "&eHoles " + ((p - 1) * PER_PAGE + 1) + "-" + (p * PER_PAGE),
                    List.of());
        }
        if (p + 1 < pages(card)) {
            out[NEXT] = new Tile(NEXT, Material.ARROW, "&eHoles " + ((p + 1) * PER_PAGE + 1) + "-"
                    + Math.min(holes, (p + 2) * PER_PAGE), List.of());
        }
        if (card.over()) {
            String winner = null;
            for (GolfGroup.Standing s : ranking) {
                if (s.place() == 1) {
                    winner = winner == null ? s.name() : winner + " and " + s.name();
                }
            }
            out[STATUS] = new Tile(STATUS, Material.GOLD_INGOT, winner == null ? "&7The round is over"
                    : "&6Fewest strokes: " + winner, List.of("&7Every round counts on its own board."));
        } else {
            String clock = card.clock() >= 0 ? " &8- &epicked up in " + GolfGroup.clockText(card.clock()) : "";
            out[STATUS] = new Tile(STATUS, Material.CLOCK, out1.isEmpty() ? "&aEveryone's done with this hole"
                    : "&7Still playing: " + String.join(", ", out1) + clock,
                    List.of("&7Everyone moves to the next tee", "&7together when all balls are in.",
                            "&7The first ball in starts a " + GolfGroup.clockText(GolfGroup.HOLE_CLOCK_SECONDS),
                            "&7hole clock. Balls still out when", "&7it runs out are picked up."));
        }
        out[EXIT] = new Tile(EXIT, Material.BARRIER, "&cClose", List.of());
        if (again) {
            out[AGAIN] = new Tile(AGAIN, Material.LIME_CONCRETE, "&aPlay again together",
                    List.of("&7Back to your party.", "&eClick to open it"));
        }
        List<Tile> all = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            all.add(out[i] != null ? out[i] : new Tile(i, Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        }
        return all;
    }

    private static int place(List<GolfGroup.Standing> ranking, GolfGroup.Row row) {
        for (GolfGroup.Standing s : ranking) {
            if (s.player().equals(row.player())) {
                return s.place();
            }
        }
        return 0;
    }

    /** A player's tile: their place and total at the end, where they are before. */
    private static Tile playerTile(int slot, GolfGroup.Row row, int place, GolfGroup.Card card) {
        int done = row.scores().size();
        String total = GolfRun.strokesText(row.total()) + " (" + GolfRun.vsParText(row.vsPar()) + ")";
        if (row.seat() == GolfGroup.Seat.LEFT) {
            return new Tile(slot, Material.GRAY_DYE, "&7" + row.name() + " &8- left the round",
                    List.of("&7" + done + " of " + card.pars().size() + " holes played."));
        }
        if (card.over() && place > 0) {
            return new Tile(slot, Material.PLAYER_HEAD, (place == 1 ? "&6" : "&f") + GolfGroup.ordinal(place) + " "
                    + row.name() + " &7- " + total, List.of("&7Every hole played."));
        }
        String where = row.seat() == GolfGroup.Seat.WAITING ? "done with hole " + done
                : "hole " + (done + 1) + ": " + GolfRun.strokesText(row.strokes()) + " so far";
        return new Tile(slot, Material.PLAYER_HEAD, "&f" + row.name() + " &7- " + where,
                List.of(done == 0 ? "&7No holes done yet." : "&7So far: " + total));
    }

    /** One player's hole: its score, or being played, or not played yet. */
    private static Tile holeTile(int slot, GolfGroup.Row row, int h, GolfGroup.Card card) {
        int n = h + 1;
        int par = card.pars().get(h);
        if (h < row.scores().size()) {
            GolfRun.HoleScore s = row.scores().get(h);
            Material m = s.pickedUp() ? Material.RED_CONCRETE : s.holeInOne() ? Material.NETHER_STAR
                    : s.vsPar() < 0 ? Material.LIME_CONCRETE : s.vsPar() == 0 ? Material.WHITE_CONCRETE
                    : Material.YELLOW_CONCRETE;
            return new Tile(slot, m, "&f" + row.name() + " &7- hole " + n + ": " + GolfCard.colour(s)
                    + GolfRun.strokesText(s.strokes()), List.of("&7Par " + par + ". " + GolfRun.holeWord(s)), n,
                    s.holeInOne());
        }
        if (h == row.scores().size() && row.seat() == GolfGroup.Seat.PLAYING && !card.over()) {
            return new Tile(slot, Material.LIGHT_BLUE_CONCRETE, "&b" + row.name() + " &7- hole " + n + ": playing",
                    List.of("&7Par " + par + ". Strokes so far: " + row.strokes()), n, false);
        }
        return new Tile(slot, Material.GRAY_CONCRETE, "&7" + row.name() + " - hole " + n + " (par " + par + ")",
                List.of("&7Not played yet."), n, false);
    }
}
