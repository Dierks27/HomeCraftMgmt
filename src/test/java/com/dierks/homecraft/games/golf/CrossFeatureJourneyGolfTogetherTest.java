package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GameProgress;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.clubhouse.ClubBench;
import com.dierks.homecraft.games.clubhouse.ClubBoard;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseSettings;
import com.dierks.homecraft.games.trial.PartyLobby;
import com.dierks.homecraft.games.world.SessionBench;
import com.dierks.homecraft.gui.games.golf.GolfGroupCardMenu;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journey 5: golf together ends in the Clubhouse, then "Play again together" from there (the real golf
 * together flow, Clubhouse hand-overs, record and rewards over the framework, the database and the world
 * sessions; {@link GolfBench} mirrors only GolfRounds' server part).
 *
 * <p>Sam hosts a golf party on a three-hole course; Ava and Lee join. The round ends in the Clubhouse (each
 * round recorded at its player's own last hole); Lee leaves for home; an auction Mini reaches Ava in the
 * Clubhouse; the card offers Play again to everyone, and round two takes Sam and Ava from the Clubhouse in
 * the same world session and Lee from home in a new one; round two ends in the Clubhouse and everyone leaves.
 */
class CrossFeatureJourneyGolfTogetherTest {

    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);

    /** A main scoreboard: entry to team. */
    private static final class Board implements NoPush.Board {
        final Map<String, String> teamOf = new HashMap<>();
        final Set<String> teams = new HashSet<>(Set.of("red", "blue"));

        @Override
        public String teamOf(String entry) {
            return teamOf.get(entry);
        }

        @Override
        public void ensureNoCollision(String team) {
            teams.add(team);
        }

        @Override
        public boolean exists(String team) {
            return teams.contains(team);
        }

        @Override
        public void add(String team, String entry) {
            teamOf.put(entry, team);
        }

        @Override
        public void remove(String team, String entry) {
            teamOf.remove(entry, team);
        }

        @Override
        public Set<String> entries(String team) {
            Set<String> out = new HashSet<>();
            teamOf.forEach((e, t) -> {
                if (t.equals(team)) {
                    out.add(e);
                }
            });
            return out;
        }
    }

    /** What the quests heard of golf. */
    private static final class Told implements GameProgress {
        final Map<UUID, List<Integer>> golf = new HashMap<>();

        @Override
        public void golfFinished(Player player, String courseId, int strokes, int par, int holesInOne, boolean fresh) {
            golf.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(strokes);
        }

        int count(UUID id) {
            return golf.getOrDefault(id, List.of()).size();
        }
    }

    private GamesBench bench;
    private GamesService games;
    private SessionBench rail;
    private ClubBench club;
    private GolfBench golf;
    private final Board board = new Board();
    private final Told told = new Told();
    private final Map<UUID, Player> players = new LinkedHashMap<>();
    private final Map<UUID, SessionBench.Spot> homes = new HashMap<>();
    private final Map<UUID, String> modes = new HashMap<>();
    private GolfCourse meadow;
    private Player sam;
    private Player ava;
    private Player lee;

    @BeforeEach
    void setUp() throws Exception {
        bench = new GamesBench(T0, List.of(MiniGolf.SPEC, Clubhouse.SPEC), "golf", MiniGolfSettings.defaults(),
                "clubhouse", ClubhouseSettings.defaults());
        games = bench.games();
        games.progress(told);
        rail = new SessionBench(games, bench.dao());
        club = new ClubBench(games, rail, new NoPush(() -> board), players::get, () -> List.copyOf(players.keySet()));
        golf = new GolfBench(games, rail, club, players::get);
        GolfBench.save(games, GolfBench.course("meadow", "Meadow Links", 3));
        meadow = golf.golf().playableCourse("meadow");
        assertNotNull(meadow, "the course plays");
        sam = player("Sam", "SURVIVAL", "red");
        ava = player("Ava", "CREATIVE", "blue");
        lee = player("Lee", "ADVENTURE", "red");
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    private Player player(String name, String mode, String team) {
        Player p = bench.player(name);
        SessionBench.Spot home = new SessionBench.Spot("world", 100 + 4 * players.size(), 64, 100);
        rail.body(p, home, mode, "diamond x3", "sword:" + name);
        board.teamOf.put(name, team);
        players.put(p.getUniqueId(), p);
        homes.put(p.getUniqueId(), home);
        modes.put(p.getUniqueId(), mode);
        return p;
    }

    private static UUID id(Player p) {
        return p.getUniqueId();
    }

    private String sid(Player p) {
        return rail.session(id(p)) == null ? null : rail.session(id(p)).id();
    }

    /** GolfTogether.start's loop: each member may start (waiting in the Clubhouse, or free at home). */
    private void startTogether(PartyLobby lobby) {
        List<Player> go = new ArrayList<>();
        for (UUID m : lobby.members()) {
            boolean waiting = ClubGolf.waiting(club.door(), m, meadow.world());
            boolean free = rail.session(m) == null && rail.home(m) && games.canOpen(players.get(m), golf.golf()) == null;
            assertTrue(waiting || free, players.get(m).getName() + " may start");
            go.add(players.get(m));
        }
        assertNull(lobby.start(id(sam)), "Sam starts the party");
        assertTrue(golf.start(lobby.id(), meadow.id(), go), "the group goes to hole 1");
        rail.step();
        rail.arriveAll();
        for (Player p : go) {
            assertTrue(golf.playing(id(p)), p.getName() + " is playing");
            assertEquals("golf", rail.session(id(p)).gameId(), p.getName() + " is in a golf session");
        }
    }

    /** A hole: each player in, in order, with their strokes; then the group moves on. */
    private void hole(Player[] order, int[] strokes) {
        for (int i = 0; i < order.length; i++) {
            golf.holeIn(id(order[i]), strokes[i]);
        }
        golf.runTasks();
    }

    private long rewards(Player p, String kind) throws Exception {
        try (PreparedStatement ps = bench.connection().prepareStatement(
                "SELECT COUNT(*) FROM game_rewards WHERE player = ? AND kind = ?")) {
            ps.setString(1, id(p).toString());
            ps.setString(2, kind);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private String homeProblem(Player p) {
        UUID id = id(p);
        List<String> out = new ArrayList<>();
        if (!homes.get(id).equals(rail.place(id))) {
            out.add("at " + rail.place(id));
        }
        for (String own : rail.own(id)) {
            if (rail.count(id, own) != 1) {
                out.add(own + " x" + rail.count(id, own));
            }
        }
        if (rail.holdsKit(id) || !rail.dropped(id).isEmpty()) {
            out.add("kit or dropped: " + rail.items(id) + " " + rail.dropped(id));
        }
        if (!modes.get(id).equals(rail.gameMode(id))) {
            out.add("mode " + rail.gameMode(id));
        }
        if (rail.row(id) != null || rail.session(id) != null) {
            out.add("still in a game");
        }
        return out.isEmpty() ? null : String.join("; ", out);
    }

    @Test
    void golfTogetherEndsInTheClubhouseAndPlaysAgainFromThereRecordingEachRoundOnce() throws Exception {
        // 1. Sam's golf party; Ava and Lee join
        PartyLobby lobby = games.parties().create(PartyLobby.Kind.GOLF, meadow.id(), id(sam), GolfGroup.MAX);
        assertNotNull(lobby, "Sam's party");
        assertNull(games.parties().join(lobby.id(), id(ava)), "Ava joins");
        assertNull(games.parties().join(lobby.id(), id(lee)), "Lee joins");

        // 2-3. Round 1: everyone to hole 1; three holes; Lee holes out first on the last
        startTogether(lobby);
        String samSid = sid(sam);
        String avaSid = sid(ava);
        String leeSid = sid(lee);
        Player[] order = {sam, ava, lee};
        hole(order, new int[]{3, 3, 4});
        hole(order, new int[]{3, 2, 3});
        golf.holeIn(id(lee), 3);
        assertEquals(1, golf.recorded.getOrDefault(id(lee), 0), "Lee's round is recorded at his own last hole");
        assertEquals(1, told.count(id(lee)), "and the quests hear it then");
        assertEquals(0, golf.recorded.getOrDefault(id(sam), 0), "Sam's isn't, yet");
        golf.holeIn(id(sam), 3);
        golf.holeIn(id(ava), 3);
        for (Player p : order) {
            assertEquals(1, told.count(id(p)), p.getName() + ": golfFinished once, at their own last hole");
        }

        // 4. The group's end: the Clubhouse, the board, and the party open again
        golf.runTasks();
        for (Player p : order) {
            assertEquals(1, golf.toClub.getOrDefault(id(p), 0), p.getName() + " went to the Clubhouse");
            assertEquals(ClubVisits.Kind.GOLF, club.visits().get(id(p)).kind(), p.getName() + " is a golfer there");
            assertEquals(1, told.count(id(p)), p.getName() + ": never told again at the group's end");
            assertFalse(golf.playing(id(p)), p.getName() + "'s finished round was forgotten by ClubGolf.take");
        }
        assertEquals(PartyLobby.State.OPEN, lobby.state(), "the party is open again");
        ClubBoard.Sheet first = club.door().last();
        assertTrue(first.rows().get(0).contains("1. ") && first.rows().get(0).contains("Ava")
                && first.rows().get(0).contains("8 strokes (-1)"), "the board: " + first.rows());
        assertEquals(samSid, sid(sam), "Sam's one session holds on in the Clubhouse");
        assertTrue(bench.heard(id(ava)).contains("Back in the Clubhouse!"), bench.heard(id(ava)));

        // 5. Lee leaves for home; a Mini reaches Ava in the Clubhouse
        rail.leave(id(lee), EndReason.QUIT_ITEM);
        rail.arriveAll();
        assertNull(homeProblem(lee), "Lee is home as he was: " + homeProblem(lee));
        assertTrue(rail.deliver(id(ava), "Mini #42"), "an auction win reaches Ava in the Clubhouse");

        // 6. The card offers Play again together to everyone
        for (Player p : order) {
            boolean in = club.visits().in(id(p));
            boolean home = rail.home(id(p));
            assertTrue(GolfGroupCardMenu.offersAgain(true, lobby.state() == PartyLobby.State.OPEN, home, in,
                    golf.golf().playableCourse(meadow.id()) != null), p.getName() + " is offered Play again");
        }

        // 7. Play again together: Sam and Ava from the Clubhouse (in place), Lee from home
        assertTrue(ClubGolf.waiting(club.door(), id(sam), "games"), "Sam waits in the Clubhouse");
        assertTrue(ClubGolf.waiting(club.door(), id(ava), "games"), "so does Ava");
        assertFalse(ClubGolf.waiting(club.door(), id(lee), "games"), "Lee is home");
        startTogether(lobby);
        assertEquals(samSid, sid(sam), "Sam keeps his one session into round 2");
        assertEquals(avaSid, sid(ava), "Ava too");
        assertNotEquals(leeSid, sid(lee), "Lee is in a new one");
        assertEquals(List.of("Mini #42"), rail.carry(id(ava)), "Ava's Mini was banked at the hand-over");
        assertEquals(2, rail.saves(id(sam)), "Sam's things were saved going in and at the hand-over's bank"
                + " (final gate #17: a bank is saved at once)");
        assertFalse(club.visits().in(id(sam)), "out of the Clubhouse");
        for (Player p : order) {
            assertFalse(golf.done(id(p)), p.getName() + " starts a fresh round, not the done one");
        }
        assertEquals(1, golf.recorded.get(id(sam)), "no second record of round 1 at the new start");

        // 8. Round 2, back to the Clubhouse, and everyone leaves
        hole(order, new int[]{2, 3, 3});
        hole(order, new int[]{3, 3, 3});
        hole(order, new int[]{3, 3, 3});
        for (Player p : order) {
            assertEquals(2, told.count(id(p)), p.getName() + ": golfFinished twice, once a round");
            assertEquals(2, golf.recorded.get(id(p)), p.getName() + ": recorded once a round");
        }
        assertEquals(2, golf.toClub.get(id(sam)), "back in the Clubhouse after round 2");
        ClubBoard.Sheet second = club.door().last();
        assertNotEquals(first, second, "the second round's ranking replaced the first");
        assertTrue(second.rows().get(0).contains("Sam") && second.rows().get(0).contains("8 strokes (-1)"),
                "round 2's board: " + second.rows());
        for (Player p : order) {
            rail.leave(id(p), EndReason.QUIT_ITEM);
        }
        rail.arriveAll();

        for (Player p : order) {
            assertNull(homeProblem(p), p.getName() + " is home as they were: " + homeProblem(p));
            assertEquals(1, rewards(p, "FIRST_CLEAR"), p.getName() + ": the first finish, once ever");
            assertEquals(2, told.count(id(p)), p.getName() + ": never told again at a Leave");
        }
        assertEquals(1, rail.count(id(ava), "Mini #42"), "Ava's Mini comes home once");
        assertEquals(1, rail.applies(id(sam)), "Sam's things were put back once");
        assertEquals(3, rail.saves(id(sam)), "Sam's things were saved going in, at the hand-over's bank and once"
                + " put back: one session (a second one would have saved twice more)");
        assertEquals(1, rewards(sam, "PAR"), "Sam: par or better once today on this course");
        assertEquals(1, rewards(ava, "PAR"), "Ava: once today, though both her rounds were at par or better");
        assertEquals(1, rewards(lee, "PAR"), "Lee: his round 2 was at par");
        assertEquals(8L, bench.dao().best(id(ava), MiniGolf.SPEC.id(), Scores.golf(meadow.id())),
                "Ava's board best is the better of her rounds");
        assertEquals(8L, bench.dao().best(id(sam), MiniGolf.SPEC.id(), Scores.golf(meadow.id())), "Sam's too");
        assertEquals(9L, bench.dao().best(id(lee), MiniGolf.SPEC.id(), Scores.golf(meadow.id())), "Lee's too");
        assertTrue(board.entries(NoPush.TEAM).isEmpty(), "nobody is left on the no-push team");
        assertEquals(PartyLobby.State.OPEN, lobby.state(), "the party is open for another round");
        assertEquals(0, bench.severe(), "nothing threw: " + bench.severeLines());
    }
}
