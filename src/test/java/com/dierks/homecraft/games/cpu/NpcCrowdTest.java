package com.dierks.homecraft.games.cpu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who of the NPC players joins a world game, and when (stage 2): only while every real player there wants them,
 * after a real player has waited alone for {@code join_after_seconds}, then one more now and then, never into a
 * place a real player has, and never one already in another game.
 */
class NpcCrowdTest {

    private static final UUID JEFF = new UUID(0, 1);
    private static final UUID LILAH = new UUID(0, 2);

    @Test
    void npcsAreWelcomeOnlyWhileEveryRealPlayerWantsThem() {
        assertTrue(NpcCrowd.welcome(List.of(JEFF), id -> true));
        assertFalse(NpcCrowd.welcome(List.of(JEFF, LILAH), id -> id.equals(JEFF)), "one with NPCs off: none");
        assertFalse(NpcCrowd.welcome(List.of(), id -> true), "nobody real: no NPCs (never a game of NPCs alone)");
    }

    @Test
    void npcsHaveOnlyThePlacesRealPlayersDont() {
        assertEquals(3, NpcCrowd.room(1, 12), "at most three");
        assertEquals(1, NpcCrowd.room(3, 4), "golf's four: one left for an NPC");
        assertEquals(0, NpcCrowd.room(4, 4), "a full game: none");
    }

    @Test
    void theFirstJoinsAfterTheWaitAloneAndTheNextAWhileAfter() {
        NpcCrowd.Alone a = new NpcCrowd.Alone();
        assertFalse(a.due(true, 0, 0, 20_000), "the wait starts");
        assertFalse(a.due(true, 0, 19_999, 20_000));
        assertTrue(a.due(true, 0, 20_000, 20_000), "after join_after_seconds alone");
        a.joined(20_000);
        assertFalse(a.due(true, 1, 20_000 + NpcCrowd.NEXT_AFTER_MS - 1, 20_000));
        assertTrue(a.due(true, 1, 20_000 + NpcCrowd.NEXT_AFTER_MS, 20_000), "then one more a while after");
        assertFalse(a.due(false, 0, 99_000, 20_000), "a friend came: no more join");
        assertFalse(a.due(true, 0, 99_001, 20_000), "alone again: the wait starts over");
        assertTrue(a.due(true, 0, 119_001, 20_000));
    }

    @Test
    void anNpcAlreadyInAnotherGameIsNeverPicked() {
        Set<UUID> busy = Set.of(CpuRoster.byKey("pip").id(), CpuRoster.byKey("ozzie").id());
        for (int seed = 0; seed < 50; seed++) {
            List<CpuPlayer> picked = NpcCrowd.pick(CpuRoster.ALL, busy, 3, new SplittableRandom(seed));
            assertEquals(3, picked.size());
            assertTrue(picked.stream().noneMatch(c -> busy.contains(c.id())));
            assertEquals(3, picked.stream().distinct().count(), "three different NPCs");
        }
        assertEquals(4, NpcCrowd.pick(CpuRoster.ALL, busy, 9, new SplittableRandom(1)).size(), "only the free ones");
    }
}
