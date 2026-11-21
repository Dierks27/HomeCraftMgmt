package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.lava.rules.LavaMode;
import com.dierks.homecraft.games.lava.rules.LavaResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a finished round records: one best per mode, on its own board, higher is better; nothing for a round called
 * off or a count of nothing; never a token.
 */
class LavaScoresTest {

    private static final UUID LILAH = UUID.nameUUIDFromBytes("lilah".getBytes());
    private static final UUID DAD = UUID.nameUUIDFromBytes("dad".getBytes());

    @Test
    void littleRecordsTheFullCountForEveryoneThere() {
        LavaResult r = new LavaResult(LavaMode.LITTLE, false, List.of(LILAH, DAD), 10, 3, List.of(), false);
        assertEquals(List.of(new LavaScores.Entry(LILAH, "lava:little", 10), new LavaScores.Entry(DAD, "lava:little",
                10)), LavaScores.entries(r));
    }

    @Test
    void groupRecordsTheSharedTotal() {
        LavaResult r = new LavaResult(LavaMode.GROUP, false, List.of(LILAH, DAD), 37, 5, List.of(), false);
        assertEquals(List.of(new LavaScores.Entry(LILAH, "lava:group", 37), new LavaScores.Entry(DAD, "lava:group",
                37)), LavaScores.entries(r));
        assertEquals(List.of(), LavaScores.entries(new LavaResult(LavaMode.GROUP, false, List.of(LILAH), 0, 5,
                List.of(), true)), "a count of nothing is no best");
    }

    @Test
    void challengeRecordsTheRoundsEachLasted() {
        LavaResult r = new LavaResult(LavaMode.CHALLENGE, false, List.of(LILAH, DAD), 0, 4, List.of(
                new LavaResult.Standing(DAD, 1, 4), new LavaResult.Standing(LILAH, 2, 0)), false);
        assertEquals(List.of(new LavaScores.Entry(DAD, "lava:challenge", 4)), LavaScores.entries(r),
                "out in the first round: nothing lasted, nothing recorded");
    }

    @Test
    void aRoundCalledOffRecordsNothing() {
        assertEquals(List.of(), LavaScores.entries(new LavaResult(LavaMode.LITTLE, true, List.of(LILAH), 4, 1,
                List.of(), true)));
        assertEquals(List.of(), LavaScores.entries(null));
    }

    @Test
    void theBoardsAreTheModesOwn() {
        assertEquals("lava:little", LavaMode.LITTLE.board());
        assertEquals("lava:group", LavaMode.GROUP.board());
        assertEquals("lava:challenge", LavaMode.CHALLENGE.board());
        assertEquals(LavaMode.GROUP, LavaMode.of(" Group "));
    }
}
