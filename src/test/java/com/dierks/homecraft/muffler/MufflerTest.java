package com.dierks.homecraft.muffler;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One muffler: its box, how a pick and a group combine, and what it stores. */
class MufflerTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final MufflerPos POS = new MufflerPos("world", 10, 64, -5);
    private static final String CLUCK = "minecraft:entity.chicken.ambient";
    private static final String EGG = "minecraft:entity.chicken.egg";
    private static final String STEP = "minecraft:entity.chicken.step";

    private static Muffler fresh() {
        return Muffler.placed(POS, OWNER, 8, 25);
    }

    // ---- the box ----

    @Test
    void theBoxIsEveryBlockWithinTheRangeInEachDirection() {
        Muffler m = fresh().withRadius(2);
        assertEquals(5, m.boxSize());
        // Blocks 8..12 on x: the far faces are x = 8.0 and x = 13.0.
        assertTrue(m.contains(8.0, 64.5, -5.5));
        assertTrue(m.contains(12.99, 64.5, -5.5));
        assertFalse(m.contains(13.0, 64.5, -5.5));
        assertFalse(m.contains(7.99, 64.5, -5.5));
        assertTrue(m.contains(10.5, 62.0, -7.0), "the bottom corner block is inside");
        assertFalse(m.contains(10.5, 67.0, -5.5), "y 67 is three blocks up — outside a range of 2");
    }

    @Test
    void theRangeAndQuieterVolumeAreClamped() {
        assertEquals(Muffler.HARD_MAX_RADIUS, fresh().withRadius(500).radius());
        assertEquals(Muffler.MIN_RADIUS, fresh().withRadius(-3).radius());
        assertEquals(90, fresh().withQuietPercent(100).quietPercent(), "at 100% Quieter would do nothing");
        assertEquals(1, fresh().withQuietPercent(0).quietPercent());
    }

    // ---- decisions ----

    @Test
    void nothingPickedMeansNothingHushed() {
        assertNull(fresh().levelFor(CLUCK));
    }

    @Test
    void aGroupHushesEverySoundInIt() {
        Muffler m = fresh().withGroup("chickens", MuffleLevel.SILENT);
        assertEquals(MuffleLevel.SILENT, m.levelFor(CLUCK));
        assertEquals(MuffleLevel.SILENT, m.levelFor(EGG));
        assertNull(m.levelFor("minecraft:entity.cow.ambient"));
    }

    @Test
    void theStrongestGroupWins() {
        Muffler m = fresh().withGroup("chickens", MuffleLevel.QUIETER).withGroup("footsteps", MuffleLevel.SILENT);
        assertEquals(MuffleLevel.SILENT, m.levelFor(STEP), "a chicken's step is a chicken AND a footstep");
        assertEquals(MuffleLevel.QUIETER, m.levelFor(CLUCK));
        assertEquals("footsteps", m.decidingGroup(STEP).id());
    }

    @Test
    void aSinglePickBeatsItsGroupEitherWay() {
        Muffler m = fresh().withGroup("chickens", MuffleLevel.SILENT)
                .withSound(EGG, MuffleLevel.ALLOW)
                .withSound(CLUCK, MuffleLevel.QUIETER);
        assertEquals(MuffleLevel.ALLOW, m.levelFor(EGG), "Always play is the exception to a hushed group");
        assertEquals(MuffleLevel.QUIETER, m.levelFor(CLUCK), "a pick can be gentler than its group");
        assertEquals(MuffleLevel.SILENT, m.levelFor(STEP));
    }

    @Test
    void aGroupNeverHoldsAlwaysPlay() {
        assertTrue(fresh().withGroup("chickens", MuffleLevel.ALLOW).groups().isEmpty());
        Muffler m = fresh().withGroup("chickens", MuffleLevel.SILENT).withGroup("chickens", null);
        assertTrue(m.groups().isEmpty(), "null puts it back to Normal");
    }

    @Test
    void picksStopAtTheLimit() {
        Muffler m = fresh();
        for (int i = 0; i < Muffler.MAX_PICKS; i++) {
            m = m.withSound("minecraft:test.sound_" + i, MuffleLevel.SILENT);
        }
        assertEquals(Muffler.MAX_PICKS, m.sounds().size());
        Muffler over = m.withSound("minecraft:test.one_too_many", MuffleLevel.SILENT);
        assertSame(m, over, "past the limit nothing changes");
        Muffler changed = m.withSound("minecraft:test.sound_0", MuffleLevel.QUIETER);
        assertEquals(MuffleLevel.QUIETER, changed.sounds().get("minecraft:test.sound_0"),
                "changing a sound already picked is always allowed");
    }

    @Test
    void clearingKeepsPowerRangeAndVolume() {
        Muffler m = fresh().withEnabled(false).withRadius(4).withQuietPercent(10)
                .withGroup("pistons", MuffleLevel.SILENT).withSound(EGG, MuffleLevel.QUIETER).cleared();
        assertEquals(0, m.ruleCount());
        assertFalse(m.enabled());
        assertEquals(4, m.radius());
        assertEquals(10, m.quietPercent());
    }

    @Test
    void aMufflerIsImmutable() {
        Muffler m = fresh();
        Muffler changed = m.withGroup("pigs", MuffleLevel.SILENT);
        assertTrue(m.groups().isEmpty());
        assertEquals(1, changed.groups().size());
    }

    // ---- storage ----

    @Test
    void rulesSurviveARoundTrip() {
        Muffler m = fresh().withGroup("chickens", MuffleLevel.SILENT).withGroup("pistons", MuffleLevel.QUIETER)
                .withSound(EGG, MuffleLevel.ALLOW).withSound("mypack:door.creak", MuffleLevel.SILENT);
        Muffler.Rules back = Muffler.decodeRules(m.encodeRules());
        assertEquals(m.groups(), back.groups());
        assertEquals(m.sounds(), back.sounds());
        assertEquals(List.of("chickens", "pistons"), List.copyOf(back.groups().keySet()), "order is kept");
    }

    @Test
    void junkInTheRulesIsSkippedNotFatal() {
        Muffler.Rules r = Muffler.decodeRules("""
                g chickens SILENT
                g pigs LOUDER
                g cows ALLOW
                s not a key
                s NotAKey SILENT
                x minecraft:a.b SILENT

                s minecraft:entity.cow.ambient QUIETER
                """);
        assertEquals(Map.of("chickens", MuffleLevel.SILENT), r.groups());
        assertEquals(Map.of("minecraft:entity.cow.ambient", MuffleLevel.QUIETER), r.sounds());
        assertTrue(Muffler.decodeRules(null).groups().isEmpty());
    }

    @Test
    void aPickedUpMufflerRemembersEverything() {
        Muffler m = fresh().withEnabled(false).withRadius(12).withQuietPercent(10)
                .withGroup("villagers", MuffleLevel.QUIETER).withSound(EGG, MuffleLevel.ALLOW);
        MufflerPos elsewhere = new MufflerPos("world_nether", 0, 70, 0);
        UUID someoneElse = UUID.randomUUID();
        Muffler back = Muffler.fromMemory(m.encodeMemory(), elsewhere, someoneElse);
        assertEquals(m.movedTo(elsewhere, someoneElse), back);
    }

    @Test
    void memoryThatIsntOursIsIgnored() {
        assertNull(Muffler.fromMemory(null, POS, OWNER));
        assertNull(Muffler.fromMemory("", POS, OWNER));
        assertNull(Muffler.fromMemory("2 1 8 25\n", POS, OWNER), "an unknown format version");
        assertNull(Muffler.fromMemory("1 1 eight 25\n", POS, OWNER));
        assertNull(Muffler.fromMemory("hello", POS, OWNER));
    }

    @Test
    void onlyAChangedMufflerIsWorthRemembering() {
        assertTrue(fresh().isDefault(8, 25));
        assertFalse(fresh().withRadius(4).isDefault(8, 25));
        assertFalse(fresh().withEnabled(false).isDefault(8, 25));
        assertFalse(fresh().withGroup("pigs", MuffleLevel.QUIETER).isDefault(8, 25));
    }

    // ---- the buttons ----

    @Test
    void theRangeStepsUpAndDownAndStopsAtTheServerMaximum() {
        assertEquals(10, Muffler.stepRadius(8, 16, true));
        assertEquals(6, Muffler.stepRadius(8, 16, false));
        assertEquals(16, Muffler.stepRadius(12, 16, true));
        assertEquals(16, Muffler.stepRadius(16, 16, true), "no further than the maximum");
        assertEquals(1, Muffler.stepRadius(1, 16, false), "no smaller than one block");
        assertEquals(14, Muffler.stepRadius(12, 14, true), "an odd maximum is still reachable");
        assertEquals(12, Muffler.stepRadius(14, 14, false));
        assertEquals(10, Muffler.stepRadius(11, 16, false), "a range set by hand steps to its neighbour");
    }

    @Test
    void theQuieterVolumeCyclesBothWays() {
        assertEquals(25, Muffler.stepQuiet(50, true));
        assertEquals(10, Muffler.stepQuiet(25, true));
        assertEquals(50, Muffler.stepQuiet(10, true), "wraps round");
        assertEquals(10, Muffler.stepQuiet(50, false));
        assertEquals(50, Muffler.stepQuiet(33, true), "an odd value from config starts the cycle again");
    }

    @Test
    void theClickCycles() {
        assertEquals(MuffleLevel.QUIETER, MuffleLevel.nextForGroup(null));
        assertEquals(MuffleLevel.SILENT, MuffleLevel.nextForGroup(MuffleLevel.QUIETER));
        assertNull(MuffleLevel.nextForGroup(MuffleLevel.SILENT));
        assertEquals(MuffleLevel.SILENT, MuffleLevel.previousForGroup(null));
        assertEquals(MuffleLevel.QUIETER, MuffleLevel.previousForGroup(MuffleLevel.SILENT));

        MuffleLevel l = null;
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            l = MuffleLevel.nextForSound(l);
            seen.append(l).append(' ');
        }
        assertEquals("QUIETER SILENT ALLOW null ", seen.toString());
        assertEquals(MuffleLevel.ALLOW, MuffleLevel.previousForSound(null));
        assertNull(MuffleLevel.previousForSound(MuffleLevel.QUIETER));
    }
}
