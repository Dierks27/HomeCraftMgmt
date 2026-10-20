package com.dierks.homecraft.games;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a refusal also goes to the console ({@link RefusalLog}): once a player and message a minute, each
 * player and each message on its own, never an empty line, and a flood of lines never grows it without end.
 */
class RefusalLogTest {

    private static final UUID SAM = new UUID(0, 1);
    private static final UUID AVA = new UUID(0, 2);

    @Test
    void theSameLineToTheSamePlayerIsWrittenOnceAMinute() {
        RefusalLog log = new RefusalLog();
        assertTrue(log.due(SAM, "Stand still and safe to start.", 0), "the first time");
        assertFalse(log.due(SAM, "Stand still and safe to start.", 59_999), "again within the minute: no");
        assertTrue(log.due(SAM, "Stand still and safe to start.", 60_000), "a minute on: yes");
    }

    @Test
    void eachPlayerAndEachMessageIsItsOwn() {
        RefusalLog log = new RefusalLog();
        assertTrue(log.due(SAM, "Close what you have open first.", 0));
        assertTrue(log.due(AVA, "Close what you have open first.", 1), "another player");
        assertTrue(log.due(SAM, "Put down what you're holding first.", 2), "another message");
        assertFalse(log.due(SAM, "", 3), "an empty line is never written");
        assertFalse(log.due(null, "Close what you have open first.", 4), "nor one to nobody");
    }

    @Test
    void aFloodOfLinesForgetsTheOldOnes() {
        RefusalLog log = new RefusalLog();
        for (int i = 0; i < RefusalLog.KEPT * 3; i++) {
            assertTrue(log.due(SAM, "line " + i, i), "every different line is written");
        }
        assertTrue(log.due(SAM, "line 0", RefusalLog.EVERY_MS), "and an old one comes round again after its minute");
    }
}
