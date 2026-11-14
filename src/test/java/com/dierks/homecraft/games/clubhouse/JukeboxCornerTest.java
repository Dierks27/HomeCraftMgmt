package com.dierks.homecraft.games.clubhouse;

import org.bukkit.Material;
import org.bukkit.block.Jukebox;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.39.1, the owner's report of 13 Nov: a record played about 2 seconds, then "There's no jukebox in the room now".
 * On Paper 26.x the record going in starts the song, so {@code startPlaying()} answers {@code false} (already
 * playing); that is a start, the song stays in the queue and nothing takes the disc out before its length.
 */
class JukeboxCornerTest {

    /** A jukebox answering {@code startPlaying()} and {@code isPlaying()} as given. */
    private static Jukebox jukebox(boolean startPlaying, boolean isPlaying) {
        return (Jukebox) Proxy.newProxyInstance(Jukebox.class.getClassLoader(), new Class<?>[]{Jukebox.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "startPlaying" -> startPlaying;
                    case "isPlaying" -> isPlaying;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "jukebox";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void aRecordThatStartedItselfIsAStart() {
        assertTrue(JukeboxCorner.started(jukebox(false, true)), "26.x: already playing once the record is in");
    }

    @Test
    void aJukeboxThatStartsOnAskingIsAStart() {
        assertTrue(JukeboxCorner.started(jukebox(true, true)));
    }

    @Test
    void aJukeboxThatNeitherStartsNorPlaysIsNot() {
        assertFalse(JukeboxCorner.started(jukebox(false, false)), "the real no-jukebox case stays refused");
    }

    @Test
    void theSongStaysQueuedUntilItsLengthSoTheDiscIsNotCleared() {
        Songs.Queue q = new Songs.Queue();
        Songs.Song s = new Songs.Song("pigstep", Songs.title("pigstep"), Material.MUSIC_DISC_PIGSTEP, 148);
        assertEquals(Songs.Act.PLAY, q.request(s, 0));
        for (long at = 1_000; at < 148_000; at += 1_000) {
            assertNull(q.due(at), "nothing due at " + at);
            assertSame(s, q.now(), "still playing at " + at + ": second() leaves the disc in");
        }
    }
}
