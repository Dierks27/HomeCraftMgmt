package com.dierks.homecraft.gui.games.trial;

import com.dierks.homecraft.games.trial.TimeTrials;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The result screen's star tile (14) on a Fresh course: what the next star needs and the week. After a
 * bonked run on a Hard dropper the third star needs a run with no bonks, so a 0:22 run with a bonk is not
 * told "Next star: under 0:24", a time it already beat.
 */
class ResultMenuStarsTest {

    @Test
    void aBonkedRunOnAHardDropperIsAskedForARunWithNoBonks() {
        TimeTrials.Daily capped = new TimeTrials.Daily("gday:fresh_dropper", 2, 9, 24_000, 35_000, 7, null, true);
        assertEquals(List.of("&7Next star: a run with no bonks under 0:24.", "&7This week: &69★"),
                List.of(ResultMenu.starsLore(capped)), "the clean-run rule, in the line the player reads");
        TimeTrials.Daily plain = new TimeTrials.Daily("gday:fresh_dropper", 2, 9, 24_000, 35_000, 7, null);
        assertEquals(List.of("&7Next star: under 0:24.", "&7This week: &69★"), List.of(ResultMenu.starsLore(plain)),
                "every other run: the time alone");
    }
}
