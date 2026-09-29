package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.clubhouse.ClubBoard;
import com.dierks.homecraft.games.clubhouse.ClubDoor;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseText;
import com.dierks.homecraft.gui.games.golf.GolfGroupCardMenu;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Golf together's side of the Clubhouse (CLUBHOUSE-SPEC §3; WP-CH): when a group's round ends, the
 * group goes to the Clubhouse instead of home, and its board shows the group ranking. One line in
 * {@link GolfRounds}'s group end ({@code Port.home}); a round alone still goes home as before, and
 * with the Clubhouse off or not built (or {@code golf_after} off) so does the group.
 */
final class ClubGolf {

    private ClubGolf() {
    }

    /**
     * The group's round is over: {@code p} goes to the Clubhouse (their round was recorded at their own
     * last hole already), and the board shows the ranking. False when the Clubhouse can't take them:
     * the caller sends them home as before.
     */
    static boolean toClubhouse(MiniGolf golf, GolfRounds rounds, Player p, LiveRound round, GolfGroup.Card card) {
        return take(Clubhouse.door(golf.games()), p, p.getWorld().getName(), () -> rounds.forget(round), card,
                q -> new GolfGroupCardMenu(golf.plugin(), golf, q, card, null).open(q));
    }

    /**
     * {@link #toClubhouse} with the server's parts passed in: the Clubhouse's door ({@code null} while
     * it is off), the world the player is in, how to forget their finished round, and the results
     * screen. The board gets the group ranking.
     */
    static boolean take(ClubDoor club, Player p, String world, Runnable forget, GolfGroup.Card card,
                        java.util.function.Consumer<Player> opener) {
        if (club == null || !club.golfAfter() || world == null || !world.equalsIgnoreCase(club.world())) {
            return false;
        }
        forget.run();
        club.result(sheet(card), opener);
        return club.takeIn(p, ClubVisits.Kind.GOLF, ClubhouseText.BACK_GOLF);
    }

    /** The group ranking on the board: "1. Sam 24 strokes (-2)". */
    static ClubBoard.Sheet sheet(GolfGroup.Card card) {
        List<String> rows = new ArrayList<>();
        for (GolfGroup.Standing s : GolfGroup.ranking(card.rows(), card.pars().size())) {
            rows.add(s.place() > 0 ? ClubBoard.strokesRow(s.place(), s.name(), s.total(), s.vsPar())
                    : ClubBoard.noTimeRow(s.name(), "left"));
        }
        return new ClubBoard.Sheet("&d&lGolf together: &f" + card.courseName(), rows, false,
                "golf:" + card.courseId() + ":" + rows.hashCode());
    }
}
