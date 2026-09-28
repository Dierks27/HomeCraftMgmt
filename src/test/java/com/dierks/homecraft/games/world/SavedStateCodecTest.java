package com.dierks.homecraft.games.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server-free half of a saved state (spec §7.3): the potion-effect text round-trips (infinite
 * durations too) and a bad line is skipped rather than costing the rest; the return point reads
 * back; and the ORDER of a restore and of the clear for a game — game mode first, effects before
 * health so Health Boost counts, health clamped to max health as it then stands, the inventory
 * last; ADVENTURE before anything is emptied.
 */
class SavedStateCodecTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");

    @Test
    void effectsRoundTripIncludingInfiniteOnes() {
        List<SavedStateCodec.Effect> effects = List.of(
                new SavedStateCodec.Effect("minecraft:speed", 1, 600, false, true, true),
                new SavedStateCodec.Effect("minecraft:night_vision", 0, SavedStateCodec.INFINITE, true, false, false),
                new SavedStateCodec.Effect("homecraft:glow_boost", 3, 20, false, false, true));
        String text = SavedStateCodec.encodeEffects(effects);
        assertEquals("minecraft:speed|1|600|false|true|true\n"
                        + "minecraft:night_vision|0|-1|true|false|false\n"
                        + "homecraft:glow_boost|3|20|false|false|true", text,
                "one line per effect: key|amplifier|duration|ambient|particles|icon");
        assertEquals(effects, SavedStateCodec.decodeEffects(text), "every field, and an infinite duration, comes back");
        assertEquals("", SavedStateCodec.encodeEffects(List.of()), "no effects is an empty string");
        assertTrue(SavedStateCodec.decodeEffects("").isEmpty(), "and reads back as none");
        assertTrue(SavedStateCodec.decodeEffects(null).isEmpty(), "a null column reads as none");
    }

    @Test
    void aBadLineIsSkippedAndTheRestStillComeBack() {
        String text = String.join("\n",
                "minecraft:speed|1|600|false|true|true",
                "garbage",
                "minecraft:haste|x|600|false|true|true",
                "Not A Key|0|600|false|true|true",
                "minecraft:regeneration|0|0|false|true|true",
                "minecraft:regeneration|0|-7|false|true|true",
                "minecraft:regeneration|300|60|false|true|true",
                "minecraft:regeneration|0|60|maybe|true|true",
                "minecraft:regeneration|0|60|false|true",
                "minecraft:jump_boost|2|-1|true|true|false",
                "");
        List<SavedStateCodec.Effect> read = SavedStateCodec.decodeEffects(text);
        assertEquals(List.of(new SavedStateCodec.Effect("minecraft:speed", 1, 600, false, true, true),
                        new SavedStateCodec.Effect("minecraft:jump_boost", 2, -1, true, true, false)), read,
                "only the two good lines survive: bad numbers, keys, booleans, zero or negative durations "
                        + "and short lines are skipped, and none of them costs the others");
        assertEquals(1, SavedStateCodec.decodeEffects("MINECRAFT:SPEED|0|20|false|false|false\r\n").size(),
                "a key is read case-blind and Windows line ends are fine");
    }

    @Test
    void anEffectThatCantBeWrittenIsLeftOutOfTheText() {
        List<SavedStateCodec.Effect> effects = new ArrayList<>();
        effects.add(new SavedStateCodec.Effect("minecraft:speed|evil", 0, 20, false, false, false));
        effects.add(null);
        effects.add(new SavedStateCodec.Effect("minecraft:luck", 0, 20, false, false, false));
        assertEquals("minecraft:luck|0|20|false|false|false", SavedStateCodec.encodeEffects(effects),
                "a key that would break the line format is never written");
    }

    @Test
    void theReturnPointReadsBackFromTheRow() {
        SavedState s = state("SURVIVAL", 20, false, false);
        Place from = SavedStateCodec.from(s);
        assertEquals(new Place("world", 10.5, 64, -3.25, 90f, -12.5f), from, "world, position and facing");
        assertNull(SavedStateCodec.from(null), "no row, no return point");
    }

    @Test
    void aRestoreSetsTheGameModeFirstTheEffectsBeforeHealthAndTheInventoryLast() {
        Recorder body = new Recorder(20);
        body.maxAfterEffects = 28; // Health Boost is in the saved effects
        SavedState s = state("SURVIVAL", 26, true, true);
        SavedStateCodec.apply(s, SavedStateCodec.decodeEffects(s.effects()), body);
        assertEquals(List.of("gameMode SURVIVAL", "clearEffects", "addEffect minecraft:health_boost", "health 26.0",
                        "absorption 2.0", "food 17 5.5 1.5", "xp 30 0.5 900", "speeds 0.2 0.1", "flight true true",
                        "fire 0", "air 300", "contents"), body.calls,
                "game mode first (it resets flight), effects before health (Health Boost raises the max), "
                        + "health kept at 26 because the max is now 28, the inventory last");
    }

    @Test
    void healthIsClampedToMaxHealthAsItNowStands() {
        Recorder body = new Recorder(20);
        SavedStateCodec.apply(state("SURVIVAL", 26, false, false), List.of(), body);
        assertTrue(body.calls.contains("health 20.0"), "26 saved but the max is 20 now: clamped, never an exception");
        Recorder dead = new Recorder(20);
        SavedStateCodec.apply(state("SURVIVAL", 0, false, false), List.of(), dead);
        assertTrue(dead.calls.contains("health 20.0"), "a saved 0 would kill them: full health instead");
    }

    @Test
    void flyingComesBackOnlyWithFlight() {
        Recorder body = new Recorder(20);
        SavedStateCodec.apply(state("SURVIVAL", 20, false, true), List.of(), body);
        assertTrue(body.calls.contains("flight false false"), "flying without allowFlight would throw; it stays off");
    }

    @Test
    void junkNumbersAreClampedToWhatTheServerAccepts() {
        SavedState s = new SavedState(ALICE, "trials", "", SavedState.ACTIVE, "s1", "games", new byte[0], null,
                -3, 7f, -1, Double.NaN, 99, Float.NaN, 999f, 0, 300, "", false, false, 5f, Float.NaN, -4,
                "", "world", 0, 64, 0, 0f, 0f, 1L, null);
        Recorder body = new Recorder(20);
        SavedStateCodec.apply(s, List.of(), body);
        assertEquals(List.of("gameMode SURVIVAL", "clearEffects", "health 20.0", "absorption 0.0", "food 20 5.0 40.0",
                        "xp 0 1.0 0", "speeds 1.0 0.1", "flight false false", "fire 0", "air 300", "contents"),
                body.calls, "a blank mode reads as survival, and every number lands inside its range");
    }

    @Test
    void clearingForAGameSetsAdventureBeforeTouchingAnything() {
        Recorder body = new Recorder(24);
        SavedStateCodec.clearForGame(body);
        assertEquals(List.of("gameMode ADVENTURE", "clearContents", "clearEffects", "fire 0", "flight false false",
                        "health 24.0", "food 20 5.0 0.0", "xp 0 0.0 0"), body.calls,
                "ADVENTURE first (a game-mode inventory profile can't swap items in under us), then empty, "
                        + "full health from the MAX_HEALTH attribute, full food, no XP; speeds and air untouched");
        assertFalse(body.calls.stream().anyMatch(c -> c.startsWith("speeds") || c.startsWith("air")),
                "a world session never changes walk or fly speed");
    }

    private static SavedState state(String mode, double health, boolean allowFlight, boolean flying) {
        return new SavedState(ALICE, "trials", "river_run", SavedState.ACTIVE, "s1", "games", new byte[]{1}, null,
                30, 0.5f, 900, health, 17, 5.5f, 1.5f, 0, 300, mode, allowFlight, flying, 0.2f, 0.1f, 2.0,
                "minecraft:health_boost|1|600|false|true|true", "world", 10.5, 64, -3.25, 90f, -12.5f, 1L, null);
    }

    /** Records every call, in order; max health goes up once an effect has been added. */
    private static final class Recorder implements SavedStateCodec.Body {
        final List<String> calls = new ArrayList<>();
        final double max;
        double maxAfterEffects;
        boolean effectsAdded;

        Recorder(double max) {
            this.max = max;
            this.maxAfterEffects = max;
        }

        @Override
        public void gameMode(String mode) {
            calls.add("gameMode " + mode);
        }

        @Override
        public void clearEffects() {
            calls.add("clearEffects");
        }

        @Override
        public void addEffect(SavedStateCodec.Effect effect) {
            effectsAdded = true;
            calls.add("addEffect " + effect.key());
        }

        @Override
        public double maxHealth() {
            return effectsAdded ? maxAfterEffects : max;
        }

        @Override
        public void health(double health) {
            calls.add("health " + health);
        }

        @Override
        public double maxAbsorption() {
            return 4;
        }

        @Override
        public void absorption(double amount) {
            calls.add("absorption " + amount);
        }

        @Override
        public void food(int level, float saturation, float exhaustion) {
            calls.add("food " + level + " " + saturation + " " + exhaustion);
        }

        @Override
        public void xp(int level, float progress, int total) {
            calls.add("xp " + level + " " + progress + " " + total);
        }

        @Override
        public void speeds(float walk, float fly) {
            calls.add("speeds " + walk + " " + fly);
        }

        @Override
        public void flight(boolean allowFlight, boolean flying) {
            calls.add("flight " + allowFlight + " " + flying);
        }

        @Override
        public void fire(int ticks) {
            calls.add("fire " + ticks);
        }

        @Override
        public void air(int ticks) {
            calls.add("air " + ticks);
        }

        @Override
        public void contents() {
            calls.add("contents");
        }

        @Override
        public void clearContents() {
            calls.add("clearContents");
        }
    }
}
