package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.lava.rules.LavaResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a finished round of The Floor Is Lava puts on the score boards the games already keep: one best per mode,
 * higher is better.
 *
 * <ul>
 *   <li><b>Little</b> ({@code lava:little}): the stars collected, for everyone in the room at the end. A finished
 *       round collected every star, so it is the full count.</li>
 *   <li><b>Group</b> ({@code lava:group}): the group's shared count, for everyone in the room at the end.</li>
 *   <li><b>Challenge</b> ({@code lava:challenge}): the rounds each player lasted.</li>
 * </ul>
 * A round called off (everyone left) records nothing, and neither does a count of nothing. The boards are bests,
 * never tallies: the secret trophy room shows each as one trophy ({@code SecretShelf#scoreBoard}), so a child's
 * first finished round of Little puts a new trophy in her room. Nothing here is worth a token.
 *
 * <p>Pure.
 */
public final class LavaScores {

    /**
     * One score to record.
     *
     * @param board the mode's board ({@code LavaMode#board})
     */
    public record Entry(UUID player, String board, long score) {
    }

    private LavaScores() {
    }

    /** The scores a result records, in the players' order. */
    public static List<Entry> entries(LavaResult r) {
        List<Entry> out = new ArrayList<>();
        if (r == null || r.calledOff()) {
            return out;
        }
        String board = r.mode().board();
        switch (r.mode()) {
            case LITTLE, GROUP -> {
                if (r.stars() > 0) {
                    for (UUID p : r.players()) {
                        out.add(new Entry(p, board, r.stars()));
                    }
                }
            }
            case CHALLENGE -> {
                for (LavaResult.Standing s : r.standings()) {
                    if (s.survived() > 0) {
                        out.add(new Entry(s.player(), board, s.survived()));
                    }
                }
            }
        }
        return out;
    }
}
