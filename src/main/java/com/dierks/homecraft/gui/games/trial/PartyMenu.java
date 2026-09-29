package com.dierks.homecraft.gui.games.trial;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.trial.PartyLobby;
import com.dierks.homecraft.games.trial.PartyRace;
import com.dierks.homecraft.games.trial.PartyRaces;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.Warmup;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * A party race's lobby (54, owner decision D4): who's in, who's ready, and the host's Start.
 *
 * <p>4 the party ("Party race: River Run - 3 of 8 in", free and just for fun); 11 the last race's
 * results; 13 Start (the host's; greyed with the reason in its NAME otherwise); 15 Leave the party;
 * 19-25 and 28-34 everyone in, in join order, the host marked and ready or not in each NAME; 38
 * Invite a friend; 40 Ready; 42 Warm up first (the host's choice); 49 the way out. It repaints every
 * second, so a friend who joins shows up at once. Every key fact is in an item NAME for Bedrock.
 */
public final class PartyMenu extends GameMenu {

    /** Where the members go: the middle two rows, seven each. */
    static final int[] MEMBER_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    private final TimeTrials trials;
    private final PartyRaces party;

    public PartyMenu(HomeCraftManagement plugin, TimeTrials trials, Player viewer, Runnable back) {
        super(plugin, trials, viewer, back);
        this.trials = trials;
        this.party = trials.party();
        init(54, Text.of("&dRace with friends"));
    }

    @Override
    public void open(Player player) {
        super.open(player);
        ticker(20, this::refresh);
    }

    @Override
    protected void build() {
        fill();
        PartyLobby lobby = party.lobby(viewer.getUniqueId());
        if (lobby == null) {
            set(22, Menus.icon(Material.PAPER, "&7You're not in a party any more"), null);
            exitTile();
            return;
        }
        UUID me = viewer.getUniqueId();
        boolean host = lobby.isHost(me);
        boolean racing = party.racing(lobby);
        String course = party.courseName(lobby);
        set(4, Menus.icon(Material.CAKE, "&dParty race: " + course + " &7- " + lobby.size() + " of " + lobby.max() + " in",
                "&7Free, just for fun: no entry, no prizes.", "&7Your time also counts on the course,",
                "&7like any run.", "&7Host: &f" + party.name(lobby.host())), null);

        List<PartyRace.Line> last = party.lastResults(lobby);
        if (!last.isEmpty()) {
            PartyRace.Line first = last.get(0);
            String won = first.result() == PartyRace.Result.FINISHED ? "won by " + first.name() : "no finishers";
            set(11, Menus.icon(Material.OAK_SIGN, "&eLast race &7- " + won, "&7Click for the results."),
                    e -> new PartyResultsMenu(plugin, trials, viewer, course, last).open(viewer));
        }

        if (racing) {
            set(13, Menus.icon(Material.CLOCK, "&eRacing now &7- " + course), null);
        } else if (host) {
            String problem = party.startProblem(viewer);
            if (problem == null) {
                set(13, Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&aStart the race!",
                        "&7Everyone free goes to the grid.", "&7One 3-2-1 for all."), true), e -> {
                    viewer.closeInventory();
                    party.start(viewer);
                });
            } else {
                set(13, Menus.icon(Material.GRAY_DYE, "&7Start &8- " + problem), null);
            }
        } else {
            set(13, Menus.icon(Material.GRAY_DYE, "&7Waiting for " + party.name(lobby.host()) + " to start"), null);
        }

        set(15, Menus.icon(Material.RED_CONCRETE, "&cLeave the party", "&7The others carry on."), e -> {
            party.leave(viewer);
            viewer.closeInventory();
        });

        List<UUID> members = lobby.members();
        for (int i = 0; i < members.size() && i < MEMBER_SLOTS.length; i++) {
            UUID id = members.get(i);
            boolean ready = lobby.isReady(id);
            String name = party.name(id) + (lobby.isHost(id) ? " &6(host)" : "");
            set(MEMBER_SLOTS[i], racing ? Menus.icon(Material.OAK_BOAT, "&e" + name + " &7- racing")
                    : ready ? Menus.icon(Material.LIME_DYE, "&a" + name + " &7- ready")
                    : Menus.icon(Material.GRAY_DYE, "&f" + name + " &7- not ready yet"), null);
        }

        if (!racing) {
            int left = lobby.max() - lobby.size();
            if (left > 0) {
                set(38, Menus.icon(Material.WRITABLE_BOOK, "&aInvite a friend &7- " + left + " place"
                                + (left == 1 ? "" : "s") + " left", "&7They get [Accept] in chat,",
                        "&7or type /hcm play accept."), e -> party.invite(viewer, this::reopen));
            } else {
                set(38, Menus.icon(Material.BOOK, "&7The party is full"), null);
            }
            boolean ready = lobby.isReady(me);
            set(40, Menus.icon(ready ? Material.LIME_CONCRETE : Material.YELLOW_CONCRETE,
                    ready ? "&aYou're ready &7- click to undo" : "&eClick when you're ready"), e -> {
                party.toggleReady(viewer);
                refresh();
            });
            boolean warm = party.warmup(lobby);
            int seconds = trials.settings().warmupSeconds();
            String warmName = !trials.settings().warmupsOn() ? "&7Warm up first: off on this server"
                    : warm ? "&bWarm up first: on &7(" + Warmup.clock(seconds) + ")" : "&7Warm up first: off";
            set(42, Menus.icon(Material.CLOCK, warmName, host ? "&7Click to change." : "&7The host chooses.",
                    "&7Free laps before the grid,", "&7never timed or counted."), host ? e -> {
                party.toggleWarmup(viewer);
                refresh();
            } : null);
        }
        exitTile();
    }

    /**
     * "Race with friends" on a course's screen (D4): "&amp;dRace with friends &amp;7- free, up to 8", or the
     * party the viewer is already in.
     */
    public static org.bukkit.inventory.ItemStack tile(TimeTrials trials, Player viewer) {
        PartyLobby mine = trials.party().lobby(viewer.getUniqueId());
        if (mine != null) {
            return Menus.icon(Material.CAKE, "&dYour party &7- " + trials.party().courseName(mine) + ", "
                    + mine.size() + " of " + mine.max() + " in", "&7Click to open it.");
        }
        return Menus.icon(Material.CAKE, "&dRace with friends &7- free, up to " + trials.settings().partyMax(),
                "&7Invite friends and race together,", "&7one 3-2-1 for everyone.",
                "&7Your time counts as a normal run.", "&7No entry and no prizes - just fun.");
    }

    private void reopen() {
        new PartyMenu(plugin, trials, viewer, back).open(viewer);
    }
}
