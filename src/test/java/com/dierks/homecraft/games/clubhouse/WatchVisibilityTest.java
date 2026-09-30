package com.dierks.homecraft.games.clubhouse;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Watchers are never in a racer's way (CLUBHOUSE-SPEC §10): hidden from every online non-watcher, seen
 * by other watchers, hidden from a late joiner, and shown again on every way out.
 */
class WatchVisibilityTest {

    /** The server: who is online, and every viewer-to-watcher pair hidden now. */
    static final class Viewers implements WatchVisibility.Viewers {
        final List<UUID> online = new ArrayList<>();
        final Set<String> hidden = new HashSet<>();

        @Override
        public List<UUID> online() {
            return online;
        }

        @Override
        public void hide(UUID viewer, UUID watcher) {
            hidden.add(viewer + ">" + watcher);
        }

        @Override
        public void show(UUID viewer, UUID watcher) {
            hidden.remove(viewer + ">" + watcher);
        }

        boolean sees(UUID viewer, UUID watcher) {
            return !hidden.contains(viewer + ">" + watcher);
        }
    }

    private final UUID racer = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();
    private final UUID w1 = UUID.randomUUID();
    private final UUID w2 = UUID.randomUUID();

    private Viewers viewers() {
        Viewers v = new Viewers();
        v.online.addAll(List.of(racer, friend, w1, w2));
        return v;
    }

    @Test
    void aWatcherIsHiddenFromEveryNonWatcherAndWatchersSeeEachOther() {
        Viewers v = viewers();
        WatchVisibility vis = new WatchVisibility(v);
        vis.watch(w1);
        assertFalse(v.sees(racer, w1), "a racer never sees a watcher");
        assertFalse(v.sees(friend, w1), "nor does anyone who isn't watching");
        assertTrue(v.sees(w2, w1) || !vis.watching(w2), "w2 isn't watching yet");
        vis.watch(w2);
        assertTrue(v.sees(w2, w1), "watchers see each other");
        assertTrue(v.sees(w1, w2), "both ways");
        assertFalse(v.sees(racer, w2), "and the second is hidden from the racer too");
        assertEquals(Set.of(racer, friend), vis.hiddenFrom(w1), "w1 is hidden from exactly the non-watchers");
    }

    @Test
    void aLateJoinerAWorldChangeOrANewSessionIsHiddenFrom() {
        Viewers v = viewers();
        WatchVisibility vis = new WatchVisibility(v);
        vis.watch(w1);
        UUID late = UUID.randomUUID();
        v.online.add(late);
        vis.arrived(late); // joined, changed world, or started a session
        assertFalse(v.sees(late, w1), "a late joiner never sees the watcher");
        UUID quiet = UUID.randomUUID();
        v.online.add(quiet);
        vis.reconcile(); // the once-a-second check catches anyone else new
        assertFalse(v.sees(quiet, w1), "the reconcile hides them from anyone new");
    }

    @Test
    void everyWayOutShowsThemAgain() {
        for (String how : List.of("back to the Clubhouse", "gone")) {
            Viewers v = viewers();
            WatchVisibility vis = new WatchVisibility(v);
            vis.watch(w1);
            vis.watch(w2);
            if (how.equals("gone")) {
                vis.gone(w1); // Leave, a quit, the restart hold, the Clubhouse off, a reload: the session ended
            } else {
                vis.unwatch(w1);
            }
            assertTrue(v.sees(racer, w1) && v.sees(friend, w1), how + ": everyone sees them again");
            assertFalse(vis.watching(w1), how + ": no longer watching");
            if (how.equals("back to the Clubhouse")) {
                assertFalse(v.sees(w1, w2), "back in the Clubhouse, they no longer see the watchers either");
            } else {
                assertTrue(v.sees(w1, w2), "gone: nothing is hidden from them any more");
            }
        }
        Viewers v = viewers();
        WatchVisibility vis = new WatchVisibility(v);
        vis.watch(w1);
        vis.watch(w2);
        vis.clear(); // the Clubhouse stopping
        assertTrue(v.hidden.isEmpty(), "everyone sees everyone again: " + v.hidden);
    }
}
