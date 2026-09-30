package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.PlayerAs;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every way into the Clubhouse from outside runs the play gate (the final gate's #2): {@code /hcm play
 * watch}, a Watch button and "Go to the Clubhouse" / "Wait in the Clubhouse" refuse, with the gate's own
 * plain reason and before anything is taken or moved, a player who isn't in a world games are played in
 * (the nether, an admin's creative build world) or whose parent took {@code hcm.games.play} away, exactly
 * as {@code /hcm play clubhouse} does. The room is an owner-built one over a fake world, open at once.
 */
class ClubhouseEntryGateTest {

    private GamesBench bench;
    private GamesService games;

    @BeforeEach
    void setUp() {
        bench = new GamesBench(GamesBench.at(2026, 9, 29, 19, 0), List.of(Clubhouse.SPEC), "clubhouse",
                ClubhouseSettings.defaults());
        games = bench.games();
        Clubhouse club = (Clubhouse) games.game("clubhouse");
        Map<UUID, Player> online = new HashMap<>();
        club.watch(new WatchLive(club, new WatchVisibility(new WatchVisibilityTest.Viewers()), online::get));
        ClubhouseRoom room = new ClubhouseRoom(new ClubhouseRoomTest.Host(), ClubhouseSettings.defaults());
        room.start();
        room.here(new ClubhouseRoom.Place("games", 100.5, 70, -20.5, 90f)); // owner-built: open at once
        assertTrue(room.open(), "the room is open");
        club.room(room);
        assertTrue(Clubhouse.offered(games), "the Clubhouse is open, so its ways in are offered");
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    private String heard(Player p) {
        return bench.heard(p.getUniqueId());
    }

    @Test
    void watchFromAWorldGamesArentPlayedInIsRefusedAsTheClubhouseCommandIs() {
        Player nia = PlayerAs.limited(bench.player("Nia"), Set.of(), "world_nether");
        Clubhouse.watchCommand(games, nia, null);
        assertTrue(heard(nia).contains("Games can't be played in this world."),
                "/hcm play watch in the nether is refused like /hcm play clubhouse: " + heard(nia));
        assertEquals(0, bench.severe(), "refused before anything is taken or moved, never a failure");
        assertFalse(Clubhouse.inside(games, nia.getUniqueId()), "and they are not in the Clubhouse");
    }

    @Test
    void aWatchButtonOrGoToTheClubhouseFromThereIsRefusedToo() {
        Player nia = PlayerAs.limited(bench.player("Nia"), Set.of(), "creative_build");
        Clubhouse.watch(games, nia);
        Clubhouse.go(games, nia);
        assertEquals(2, heard(nia).lines().filter(l -> l.contains("Games can't be played in this world.")).count(),
                "both ways in say why: " + heard(nia));
        assertEquals(0, bench.severe(), "never a failure");
    }

    @Test
    void aPlayerWhoseParentTookTheGamesAwayIsNeverLetIn() {
        Player kim = PlayerAs.limited(bench.player("Kim"), Set.of("hcm.games.play"), null);
        Clubhouse.watchCommand(games, kim, null);
        Clubhouse.watch(games, kim);
        Clubhouse.go(games, kim);
        assertEquals(3, heard(kim).lines().filter(l -> l.contains("Games aren't open to you.")).count(),
                "every way in is refused, never more said: " + heard(kim));
        assertEquals(0, bench.severe(), "never a failure");
    }
}
