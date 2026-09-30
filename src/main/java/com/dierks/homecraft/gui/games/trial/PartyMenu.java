package com.dierks.homecraft.gui.games.trial;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseText;
import com.dierks.homecraft.games.cup.live.CupLink;
import com.dierks.homecraft.games.trial.Course;
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
 * 19-25 and 28-34 everyone in, in join order, the host marked and ready or not in each NAME; 36 Go
 * to the Clubhouse and 37 Watch, while the Clubhouse is open (WP-CH); 38 Invite a friend; 39 Take a
 * rider, on a boat course while ride along is on (WP-CH); 40 Ready; 42 Warm up first (the host's
 * choice); 44 this week's Cup on the course, when it runs one ({@link CupLink#button}: "Enter this
 * week's Cup: 5 tokens..." in its NAME, since a party race's finish counts for the Cup like any run);
 * 49 the way out; 45 and 53 stay filler. It repaints every second, so a friend who joins shows up at
 * once. Every key fact is in an item NAME for Bedrock.
 */
public final class PartyMenu extends GameMenu {

    /** Where the members go: the middle two rows, seven each. */
    static final int[] MEMBER_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    /** This week's Cup on the party's course (the course screen's own Cup item). */
    public static final int CUP_SLOT = 44;
    /** WP-CH: "Go to the Clubhouse" and "Watch", while the Clubhouse is open. */
    public static final int CLUB_SLOT = 36;
    public static final int WATCH_SLOT = 37;
    /** WP-CH: "Take a rider (back seat)" on a boat course's party. */
    public static final int RIDER_SLOT = 39;
    /** The Cup item is read again at most this often (it reads the Cup's rows), not on every repaint. */
    static final long CUP_EVERY_MS = 5_000;

    private final TimeTrials trials;
    private final PartyRaces party;
    private CupLink.Button cup;
    private long cupAt = Long.MIN_VALUE / 2;

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
                set(13, Menus.glint(Menus.icon(Material.LIME_CONCRETE, startName(!last.isEmpty(),
                                Clubhouse.offered(plugin.games())),
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
            CupLink.Button b = cupButton(lobby);
            if (b != null) {
                set(CUP_SLOT, b.icon(), e -> b.click().run());
            }
        }
        Course raced = trials.course(lobby.course());
        clubButtons(clubSlots(Clubhouse.offered(plugin.games()), raced != null
                && com.dierks.homecraft.games.trial.RideAlong.offered(plugin.games(), raced.kind()), racing,
                trials.onRun(me)), raced); // WP-CH
        exitTile();
    }

    /**
     * The Start tile's NAME: "Race again!" only after a race while the Clubhouse is open (everyone
     * still there is seated again from it); with the Clubhouse off it is always "Start the race!"
     * (the Clubhouse review, #10).
     */
    static String startName(boolean raced, boolean clubhouseOpen) {
        return raced && clubhouseOpen ? "&aRace again!" : "&aStart the race!";
    }

    /**
     * Which of the Clubhouse's items show (WP-CH), like the other pre-race items: before the race, Go
     * to the Clubhouse (36), Watch (37) and, on a boat course, Take a rider (39). While the party is
     * racing, Go and Take a rider are hidden as Invite, Ready, Warm up and the Cup are (nobody joins a
     * race under way, and a driver can't take a rider mid-race); Watch stays for a member who isn't in
     * the race, since watching live is for a race going on, and is hidden from a racer.
     */
    static java.util.Set<Integer> clubSlots(boolean clubhouseOpen, boolean ridersOffered, boolean racing,
                                            boolean viewerRacing) {
        java.util.Set<Integer> out = new java.util.TreeSet<>();
        if (clubhouseOpen && !racing) {
            out.add(CLUB_SLOT);
        }
        if (clubhouseOpen && !viewerRacing) {
            out.add(WATCH_SLOT);
        }
        if (ridersOffered && !racing) {
            out.add(RIDER_SLOT);
        }
        return out;
    }

    /** WP-CH: the Clubhouse's items in {@code slots}. */
    private void clubButtons(java.util.Set<Integer> slots, Course c) {
        if (slots.contains(CLUB_SLOT)) {
            set(CLUB_SLOT, Menus.icon(Material.OAK_DOOR, ClubhouseText.GO_BUTTON, "&7Hang out with friends until",
                    "&7the race; you go straight to the grid."), e -> {
                viewer.closeInventory();
                Clubhouse.go(plugin.games(), viewer);
            });
        }
        if (slots.contains(WATCH_SLOT)) {
            set(WATCH_SLOT, Menus.icon(Material.SPYGLASS, ClubhouseText.WATCH_BUTTON, "&7Watch the race and the results",
                    "&7without racing this time."), e -> {
                viewer.closeInventory();
                Clubhouse.watch(plugin.games(), viewer);
            });
        }
        if (slots.contains(RIDER_SLOT) && c != null) {
            set(RIDER_SLOT, Menus.icon(Material.OAK_BOAT, com.dierks.homecraft.games.trial.RideAlong.BUTTON,
                    "&7A friend rides in the back of your", "&7boat: not a racer, not counted."),
                    e -> com.dierks.homecraft.games.trial.RideAlong.take(plugin.games(), viewer, c.id(),
                            com.dierks.homecraft.games.trial.RideAlong.Purpose.PARTY, this::reopen));
        }
    }

    /** The Cup's item for the party's course, read again every few seconds; {@code null} when none runs. */
    private CupLink.Button cupButton(PartyLobby lobby) {
        long now = System.currentTimeMillis();
        if (now - cupAt >= CUP_EVERY_MS) {
            Course c = trials.course(lobby.course());
            cup = c == null ? null : CupLink.button(plugin.games(), viewer, c, this::reopen);
            cupAt = now;
        }
        return cup;
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
