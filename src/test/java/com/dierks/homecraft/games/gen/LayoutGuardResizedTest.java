package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.games.gen.LayoutGuard.Area;
import com.dierks.homecraft.games.gen.LayoutGuard.Decision;
import com.dierks.homecraft.games.gen.LayoutGuard.Held;
import com.dierks.homecraft.games.gen.api.Slots;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The legacy guard's v4 entries ({@link LayoutGuard#RESIZED}): Golf of the Week, Classic Golf and the Ice
 * Boat are bigger from v4 on, so neither decision keeps their 0.35 spot or shape (a 128-wide golf half at
 * 0.35's spot would overlap Tiny Golf's 0.35 box). Each takes its shipped spot and the default gap in both
 * decisions; an owner's own readable spot stays (the slot grows in place there), with no 0.35 gap added.
 */
class LayoutGuardResizedTest {

    private static Area area(String id) {
        return LayoutGuard.AREAS.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<Integer> list(Object raw) {
        return ((List<?>) raw).stream().map(o -> ((Number) o).intValue()).toList();
    }

    @Test
    void theThreeGrownPlacesAreTheResizedOnes() {
        assertEquals(Set.of("fresh_golf", "fresh_classic_golf", "fresh_boat"), LayoutGuard.RESIZED, "golf's two and the boat");
        for (Area a : LayoutGuard.AREAS) {
            assertEquals(LayoutGuard.RESIZED.contains(a.id()), a.resized(), a.id());
        }
        assertEquals(List.of(8768, 160, 4096), area("fresh_golf").shipped(), "golf's shipped spot is Col G's");
        assertEquals(List.of(6080, 96, 2880), area("fresh_boat").shipped(), "the boat's is north");
        assertEquals(List.of(4864, 160, 4096), area("fresh_golf").legacy(), "0.35's table is frozen as it was");
    }

    @Test
    void aServerThatKept035sSpotsGivesTheGrownPlacesTheirNewSpotsAndGaps() {
        YamlConfiguration c = LayoutFixtures.v035();
        LayoutGuard.markPending(c);
        List<String> log = new ArrayList<>();
        LayoutGuard.apply(c, Decision.LEGACY, true, List.of("Parkour's area is claimed"), null, log);
        for (String id : LayoutGuard.RESIZED) {
            assertEquals(Held.SHIPPED, LayoutGuard.held(c, area(id)), id + ": its new spot, even on a legacy server");
            assertNull(c.get(area(id).gapPath()), id + ": and the default gap");
        }
        assertEquals(Held.LEGACY, LayoutGuard.held(c, area("fresh_tiny_golf")), "Tiny Golf keeps 0.35's spot");
        assertEquals(32, c.getInt(area("fresh_tiny_golf").gapPath()), "and shape");
        assertTrue(LayoutGuard.legacyShaped(c), "the file still reads as 0.35's shape for every place that keeps one");
    }

    @Test
    void anOwnersOwnSpotForAGrownPlaceStaysInEitherDecisionWithNoOldGap() {
        for (Decision d : Decision.values()) {
            YamlConfiguration c = LayoutFixtures.v035();
            c.set(area("fresh_golf").originPath(), List.of(9024, 160, 4096));
            c.set(area("fresh_boat").originPath(), Arrays.asList(4480, 160, "4352")); // 0.35 couldn't read it either
            LayoutGuard.markPending(c);
            List<String> log = new ArrayList<>();
            LayoutGuard.apply(c, d, true, List.of("x"), null, log);
            assertEquals(List.of(9024, 160, 4096), list(c.get(area("fresh_golf").originPath())), d + ": the owner's");
            assertNull(c.get(area("fresh_golf").gapPath()), d + ": and no 0.35 gap: it grows in place at 576");
            assertEquals(List.of(6080, 96, 2880), list(c.get(area("fresh_boat").originPath())),
                    d + ": an unreadable one was never where anything stood: the shipped spot");
            assertFalse(log.stream().anyMatch(l -> l.contains(area("fresh_golf").originPath())),
                    d + ": not named as a spot kept at its old shape: " + log);
        }
    }

    @Test
    void aGrownPlaceIsNeverKeptAt035sSpotToMakeRoomForAnOwnersSpot() {
        YamlConfiguration c = LayoutFixtures.v035();
        // the owner's parkour on Tiny Golf's new spot: Tiny Golf keeps 0.35's (it didn't grow) ...
        c.set(area("fresh_parkour").originPath(), new ArrayList<>(area("fresh_tiny_golf").shipped()));
        List<String> log = new ArrayList<>();
        LayoutGuard.apply(c, Decision.NEW, true, List.of(), null, log);
        assertEquals(Held.LEGACY, LayoutGuard.held(c, area("fresh_tiny_golf")), "Tiny Golf stays at 0.35's");
        for (String id : LayoutGuard.RESIZED) {
            assertEquals(Held.SHIPPED, LayoutGuard.held(c, area(id)), id + ": ... but a grown place always moves");
        }
        assertTrue(log.stream().anyMatch(l -> l.contains(Slots.DAILY_GOLF.name())), "and is named as moved: " + log);
    }
}
