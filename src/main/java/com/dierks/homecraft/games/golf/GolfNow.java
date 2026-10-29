package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.clubhouse.ClubData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mini golf as the Clubhouse's boards see it (0.38.3): read-only, from what is in memory, on the main thread. Who
 * is on which hole now (Now playing), and the playable courses with their boards and par (this week's best and My
 * stats, read from the database elsewhere: {@link ClubData}). Nothing here changes a round or reads the database.
 */
public final class GolfNow {

    private GolfNow() {
    }

    /**
     * One round in progress.
     *
     * @param course   the course's name
     * @param hole     the hole being played, from 1 (the last once done)
     * @param holes    the course's holes
     * @param together a golf-together group's round
     * @param done     every hole done
     */
    public record Round(UUID player, String course, int hole, int holes, boolean together, boolean done) {
    }

    /** Every round now; none while Mini golf is off. */
    public static List<Round> rounds(GamesService games) {
        MiniGolf golf = golf(games);
        if (golf == null) {
            return List.of();
        }
        List<Round> out = new ArrayList<>();
        for (LiveRound r : golf.rounds().all()) {
            int holes = r.run.holes();
            out.add(new Round(r.player, r.course.name(), Math.min(r.run.hole() + 1, holes), holes, r.group != null,
                    r.state == LiveRound.State.DONE));
        }
        return out;
    }

    /** The playable courses (the Games screen's order), each with its board (a Fresh course's set board) and par. */
    public static List<ClubData.GolfBoards> boards(GamesService games) {
        MiniGolf golf = golf(games);
        if (golf == null) {
            return List.of();
        }
        List<ClubData.GolfBoards> out = new ArrayList<>();
        for (GolfCourse c : golf.playable()) {
            out.add(new ClubData.GolfBoards(c.id(), c.name(), MiniGolf.board(c), c.par()));
        }
        return out;
    }

    private static MiniGolf golf(GamesService games) {
        Game g = games == null ? null : games.game(MiniGolf.SPEC.id());
        return g instanceof MiniGolf m && games.enabled(m) ? m : null;
    }
}
