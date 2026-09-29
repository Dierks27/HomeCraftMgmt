package com.dierks.homecraft.gui.games.golf;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfGroup;
import com.dierks.homecraft.games.golf.GolfTogether;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.games.trial.PartyLobby;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A golf party's screen (27, EVENTS-OWNER-DECISIONS D4): who's in and who's ready, and the buttons
 * to invite, get ready, start and leave. What "Play with friends" opens.
 *
 * <pre>
 *  4  the course and how many are in
 *  10-13 the players: the host first, "ready" in the NAME
 *  15 Invite a friend     16 Ready / Not ready
 *  20 Start (the host)    22 Back/Close     24 Leave the party
 * </pre>
 *
 * Every build paints every slot; the facts are in the item NAMES. The layout is {@link #tiles}
 * (pure, tested); the screen paints it and wires the clicks.
 */
public final class GolfPartyMenu extends GameMenu {

    public static final int SIZE = 27;
    public static final int HEADER = 4;
    public static final int FIRST_MEMBER = 10;
    public static final int INVITE = 15;
    public static final int READY = 16;
    public static final int START = 20;
    public static final int EXIT = 22;
    public static final int LEAVE = 24;

    /** One player in the party. */
    public record Member(String name, boolean host, boolean ready, boolean you) {
    }

    /**
     * The party as the screen shows it.
     *
     * @param course     the course's name
     * @param open       the party is gathering (not playing a round now)
     * @param startBlock why the viewer can't start now ({@code null}: they can)
     */
    public record View(String course, int holes, int par, List<Member> members, int max, boolean youHost,
                       boolean youReady, boolean open, String startBlock) {

        public View {
            members = List.copyOf(members);
        }
    }

    /** One painted slot. */
    public record Tile(int slot, Material material, String name, List<String> lore) {

        public Tile {
            lore = List.copyOf(lore);
        }
    }

    private final MiniGolf golf;
    private final long partyId;

    public GolfPartyMenu(HomeCraftManagement plugin, MiniGolf golf, Player viewer, long partyId, Runnable back) {
        super(plugin, golf, viewer, back);
        this.golf = golf;
        this.partyId = partyId;
        init(SIZE, Text.of("&dGolf together"));
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        PartyLobby l = golf.together().lobby(partyId);
        if (l == null || !l.has(viewer.getUniqueId())) {
            set(13, Menus.icon(Material.GRAY_DYE, "&7That party has ended", "&7Tap Play with friends to make one."), null);
            return;
        }
        for (Tile t : tiles(view(l), back != null)) {
            if (t.slot() == EXIT || t.material() == Material.GRAY_STAINED_GLASS_PANE) {
                continue;
            }
            set(t.slot(), Menus.icon(t.material(), t.name(), t.lore().toArray(new String[0])), e -> click(t.slot(), l));
        }
    }

    private View view(PartyLobby l) {
        GolfCourse c = golf.course(l.course());
        UUID me = viewer.getUniqueId();
        List<Member> members = new ArrayList<>();
        for (UUID id : l.members()) {
            members.add(new Member(GolfTogether.name(id), l.isHost(id), l.isReady(id), id.equals(me)));
        }
        PartyLobby.Why why = l.canStart(me);
        return new View(c == null ? l.course() : c.name(), c == null ? 0 : c.holes().size(), c == null ? 0 : c.par(),
                members, l.max(), l.isHost(me), l.isReady(me), l.state() == PartyLobby.State.OPEN,
                why == null ? null : why.message());
    }

    private void click(int slot, PartyLobby l) {
        switch (slot) {
            case INVITE -> golf.together().invite(viewer, partyId, this::reopen);
            case READY -> {
                golf.together().ready(viewer, !l.isReady(viewer.getUniqueId()));
                reopen();
            }
            case START -> {
                if (l.canStart(viewer.getUniqueId()) != null) {
                    Sounds.refused(viewer);
                    return;
                }
                viewer.closeInventory();
                golf.together().start(viewer);
            }
            case LEAVE -> {
                golf.together().leave(viewer);
                viewer.closeInventory();
            }
            default -> {
                // looking only
            }
        }
    }

    private void reopen() {
        new GolfPartyMenu(plugin, golf, viewer, partyId, back).open(viewer);
    }

    // ---- the layout (pure) ------------------------------------------------------------------------

    /** Every slot of the screen, in slot order. */
    public static List<Tile> tiles(View v, boolean hasBack) {
        Tile[] out = new Tile[SIZE];
        out[HEADER] = new Tile(HEADER, Material.SNOWBALL, "&dGolf together &7- " + v.course() + ", " + v.members().size()
                + " of " + v.max() + " in", List.of("&7" + MiniGolf.holes(v.holes()) + ", par " + v.par() + ".",
                "&7Everyone plays the same hole at once,", "&7each with their own ball.",
                "&7Each round counts on its own, as always."));
        for (int i = 0; i < GolfGroup.MAX; i++) {
            int slot = FIRST_MEMBER + i;
            if (i < v.members().size()) {
                Member m = v.members().get(i);
                String facts = (m.host() ? "host" : "") + (m.host() && m.ready() ? ", " : "") + (m.ready() ? "ready" : "");
                out[slot] = new Tile(slot, Material.PLAYER_HEAD, (m.you() ? "&a" : "&f") + m.name()
                        + (m.you() ? " &7(you)" : "") + (facts.isEmpty() ? "" : " &7- " + facts),
                        List.of(m.ready() ? "&aReady to play." : "&7Not ready yet."));
            } else {
                out[slot] = new Tile(slot, Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7A free spot",
                        List.of("&7Invite a friend to fill it."));
            }
        }
        boolean full = v.members().size() >= v.max();
        out[INVITE] = !v.open() ? new Tile(INVITE, Material.GRAY_DYE, "&7Playing now", List.of("&7Invite after the round."))
                : full ? new Tile(INVITE, Material.GRAY_DYE, "&7The party is full &8(" + v.max() + ")", List.of())
                : new Tile(INVITE, Material.WRITABLE_BOOK, "&bInvite a friend", List.of("&7Pick someone to ask.",
                "&7Java: they click [Accept].", "&7Bedrock: /hcm play accept", "&eClick to pick"));
        out[READY] = v.youReady()
                ? new Tile(READY, Material.LIME_DYE, "&aYou're ready", List.of("&eClick if you're not"))
                : new Tile(READY, Material.GRAY_DYE, "&7Not ready &8- click when you are", List.of("&7So the host knows."));
        String host = null;
        for (Member m : v.members()) {
            if (m.host()) {
                host = m.name();
            }
        }
        out[START] = v.startBlock() == null
                ? new Tile(START, Material.LIME_CONCRETE, "&aStart &7- everyone to hole 1", List.of(
                "&7Everyone goes to the course with", "&7just their clubs. Things are kept", "&7safe until you're back.",
                "&eClick to start"))
                : new Tile(START, Material.GRAY_DYE, v.youHost() ? "&7" + v.startBlock()
                : "&7Waiting for " + (host == null ? "the host" : host) + " to start", List.of("&7Only the host starts."));
        out[EXIT] = new Tile(EXIT, Material.BARRIER, hasBack ? "&cBack" : "&cClose", List.of());
        out[LEAVE] = new Tile(LEAVE, Material.RED_CONCRETE, "&cLeave the party", List.of("&7You can always make another."));
        List<Tile> all = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            all.add(out[i] != null ? out[i] : new Tile(i, Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        }
        return all;
    }
}
