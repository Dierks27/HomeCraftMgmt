package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.trial.Course;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * NPC boats at Race Night (stage 2): only a small night gets them, only while everyone on its list wants NPCs, and
 * only on grid spots no real racer has. They are never Race Night's racers: {@link NightRunner}, its standings, its
 * stored rows and {@link RacePrizes} never see one (the NPC boats live in {@link NightNpcs} alone), so every real
 * racer's place, points and prize are what they would be without them.
 */
class NightNpcsTest {

    @Test
    void npcsFillASmallNightUpToFourAndOnlyWhenWelcome() {
        assertEquals(2, NightNpcs.count(2, 6, true), "two racers: two NPC boats make four");
        assertEquals(1, NightNpcs.count(3, 6, true), "three: one");
        assertEquals(0, NightNpcs.count(4, 6, true), "four or more: none");
        assertEquals(1, NightNpcs.count(2, 1, true), "never more than the free spots");
        assertEquals(0, NightNpcs.count(2, 6, false), "someone on the list has NPCs off: none");
        assertEquals(0, NightNpcs.count(0, 6, true), "nobody racing: none (never a night of NPCs alone)");
    }

    @Test
    void anNpcOnlyGetsASpotNoRealRacerHas() {
        Course.Spot a = new Course.Spot(0, 65, 0, 0, 0);
        Course.Spot b = new Course.Spot(2, 65, 0, 0, 0);
        Course.Spot c = new Course.Spot(0, 65, -4, 0, 0);
        Course.Spot d = new Course.Spot(2, 65, -4, 0, 0);
        assertEquals(List.of(b, d), NightNpcs.freeSpots(List.of(a, b, c, d), Set.of(a, c)),
                "the real racers' spots are theirs; the NPCs take what is left, in grid order");
        assertEquals(List.of(), NightNpcs.freeSpots(List.of(a, b), Set.of(a, b)), "a full grid: none");
    }
}
