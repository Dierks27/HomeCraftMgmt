package com.dierks.homecraft.games.clubhouse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Watchers are never in a racer's way (CLUBHOUSE-SPEC §10, the owner's rule): on top of spectator mode,
 * a watcher is hidden at the server level from every online player who isn't watching, so no client
 * (Java, Bedrock through Geyser, a modded one) is ever sent their body or their name. Watchers still
 * see each other.
 *
 * <ul>
 *   <li>{@link #watch}: hidden from every non-watcher online now (and they see the other watchers);</li>
 *   <li>{@link #reconcile}, once a second, and {@link #arrived} for someone who joins, changes world or
 *       starts a session: anyone new is kept from seeing a watcher;</li>
 *   <li>{@link #unwatch} and {@link #gone}: shown again to everyone they were hidden from, on EVERY
 *       way out (back to the Clubhouse, Leave, a quit, the restart hold, the Clubhouse off, a reload).
 *       A crash forgets it all anyway; the live side also shows a joining player to everyone.</li>
 * </ul>
 * Pure: the server's hide and show come through {@link Viewers}, so a test checks every pair.
 */
public final class WatchVisibility {

    /** The server's side: who is online, and hide/show one player from another. */
    public interface Viewers {
        List<UUID> online();

        /** {@code viewer} no longer sees {@code watcher}. */
        void hide(UUID viewer, UUID watcher);

        /** {@code viewer} sees {@code watcher} again. */
        void show(UUID viewer, UUID watcher);
    }

    private final Viewers port;
    private final Set<UUID> watchers = new LinkedHashSet<>();
    /** Watcher to the viewers it is hidden from now. */
    private final Map<UUID, Set<UUID>> hiddenFrom = new HashMap<>();

    public WatchVisibility(Viewers port) {
        this.port = port;
    }

    /** {@code watcher} starts watching: hidden from every non-watcher, and seeing the other watchers. */
    public void watch(UUID watcher) {
        if (!watchers.add(watcher)) {
            return;
        }
        for (UUID other : watchers) {
            Set<UUID> hid = hiddenFrom.get(other);
            if (other != watcher && hid != null && hid.remove(watcher)) {
                port.show(watcher, other); // watchers see each other
            }
        }
        reconcile();
    }

    /** {@code watcher} is back (in the Clubhouse): everyone sees them again, and they no longer see watchers. */
    public void unwatch(UUID watcher) {
        if (!watchers.remove(watcher)) {
            return;
        }
        showAgain(watcher);
        for (UUID w : watchers) {
            hideFrom(watcher, w);
        }
    }

    /**
     * {@code player} is gone (a quit, their session ended): if they were watching, everyone sees them
     * again; and nobody is hidden from them any more (they may come back as anyone).
     */
    public void gone(UUID player) {
        if (watchers.remove(player)) {
            showAgain(player);
        }
        for (Map.Entry<UUID, Set<UUID>> e : hiddenFrom.entrySet()) {
            if (e.getValue().remove(player)) {
                port.show(player, e.getKey());
            }
        }
    }

    /** Someone joined, changed world or started a session: if they aren't watching, they don't see watchers. */
    public void arrived(UUID viewer) {
        if (watchers.contains(viewer)) {
            return;
        }
        for (UUID w : watchers) {
            hideFrom(viewer, w);
        }
    }

    /** Once a second: every watcher hidden from every online non-watcher. */
    public void reconcile() {
        List<UUID> online = port.online();
        for (UUID w : watchers) {
            for (UUID v : online) {
                if (!watchers.contains(v)) {
                    hideFrom(v, w);
                }
            }
        }
    }

    /** Everyone shown again (the Clubhouse stopping). */
    public void clear() {
        for (UUID w : new ArrayList<>(watchers)) {
            unwatchQuietly(w);
        }
        watchers.clear();
        hiddenFrom.clear();
    }

    /** Whether {@code player} is watching. */
    public boolean watching(UUID player) {
        return watchers.contains(player);
    }

    /** Who {@code watcher} is hidden from now. */
    public Set<UUID> hiddenFrom(UUID watcher) {
        Set<UUID> s = hiddenFrom.get(watcher);
        return s == null ? Set.of() : Set.copyOf(s);
    }

    private void unwatchQuietly(UUID watcher) {
        watchers.remove(watcher);
        showAgain(watcher);
    }

    private void showAgain(UUID watcher) {
        Set<UUID> hid = hiddenFrom.remove(watcher);
        if (hid != null) {
            for (UUID v : hid) {
                port.show(v, watcher);
            }
        }
    }

    private void hideFrom(UUID viewer, UUID watcher) {
        if (viewer.equals(watcher)) {
            return;
        }
        if (hiddenFrom.computeIfAbsent(watcher, k -> new LinkedHashSet<>()).add(viewer)) {
            port.hide(viewer, watcher);
        }
    }
}
