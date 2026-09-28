package com.dierks.homecraft.games.cabinet;

import java.util.List;

/**
 * What {@link CabinetGame} needs from a cabinet's own settings record.
 *
 * <p>Each cabinet keeps its own record (its own keys, its own parse) and implements this so the
 * shared finish logic can pay milestones and the daily challenge without knowing the record.
 */
public interface CabinetSettings {

    boolean enabled();

    /** Tokens for meeting the daily challenge, once a day. */
    int dailyReward();

    /** The most tokens this game pays a player a day (milestones and the daily challenge together). */
    int dailyCap();

    /** Tokens for each milestone, paid once ever. 0 when the game has no milestones. */
    default int milestoneReward() {
        return 0;
    }

    /**
     * Bronze, silver and gold for one board, as score thresholds (a time in seconds, apples, a
     * tile…); empty when the board has none.
     */
    default List<Integer> milestonesFor(String board) {
        return List.of();
    }
}
