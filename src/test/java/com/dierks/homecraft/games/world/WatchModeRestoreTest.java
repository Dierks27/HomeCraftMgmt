package com.dierks.homecraft.games.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Clubhouse watcher's game mode (CLUBHOUSE-SPEC §10, "game mode safety"): a watcher is in SPECTATOR
 * inside their session, but the session's saved state holds the mode they came in with, and every way
 * out (Leave, a quit, the restart hold, the games off, and a crash boot at the next join) restores that
 * saved state, game mode FIRST. So nobody is ever left in spectator mode.
 */
class WatchModeRestoreTest {

    /** A body in spectator mode that remembers every call. */
    static final class Body implements SavedStateCodec.Body {
        String mode = "SPECTATOR";
        final List<String> calls = new ArrayList<>();

        @Override
        public void gameMode(String m) {
            mode = m;
            calls.add("gameMode " + m);
        }

        @Override
        public void clearEffects() {
            calls.add("clearEffects");
        }

        @Override
        public void addEffect(SavedStateCodec.Effect effect) {
        }

        @Override
        public double maxHealth() {
            return 20;
        }

        @Override
        public void health(double health) {
        }

        @Override
        public double maxAbsorption() {
            return 16;
        }

        @Override
        public void absorption(double amount) {
        }

        @Override
        public void food(int level, float saturation, float exhaustion) {
        }

        @Override
        public void xp(int level, float progress, int total) {
        }

        @Override
        public void speeds(float walk, float fly) {
        }

        @Override
        public void flight(boolean allowFlight, boolean flying) {
            calls.add("flight " + allowFlight + " " + flying);
        }

        @Override
        public void fire(int ticks) {
        }

        @Override
        public void air(int ticks) {
        }

        @Override
        public void contents() {
            calls.add("contents");
        }

        @Override
        public void clearContents() {
        }
    }

    private static SavedState saved(String mode) {
        return new SavedState(UUID.randomUUID(), "trials", "", SavedState.ACTIVE, "s1", "games", new byte[0], null,
                0, 0f, 0, 20, 20, 5f, 0f, 0, 300, mode, false, false, 0.2f, 0.1f, 0, "", "world", 0, 64, 0, 0f, 0f,
                1L, null);
    }

    @Test
    void aWatchersOwnModeComesBackFirstOnEveryWayOut() {
        for (String own : List.of("SURVIVAL", "CREATIVE", "ADVENTURE")) {
            Body watcher = new Body();
            SavedStateCodec.apply(saved(own), List.of(), watcher); // the restore every exit and a crash boot run
            assertEquals(own, watcher.mode, "a watcher in spectator mode gets their own mode back: " + own);
            assertEquals("gameMode " + own, watcher.calls.get(0), "first, before anything else");
            assertEquals("flight false false", watcher.calls.get(watcher.calls.indexOf("clearEffects") + 1),
                    "spectator flight is gone with it");
        }
        Body blank = new Body();
        SavedStateCodec.apply(saved(""), List.of(), blank);
        assertEquals("SURVIVAL", blank.mode, "an unreadable saved mode is never left as spectator");
    }
}
