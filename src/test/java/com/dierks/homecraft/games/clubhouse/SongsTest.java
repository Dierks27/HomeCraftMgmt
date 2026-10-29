package com.dierks.homecraft.games.clubhouse;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The jukebox corner's records (0.38.4): every song key of this API has a disc material in the jar, a title is
 * the key's words, and the queue plays now, queues one next, replaces the next, says SAME for a repeat, and
 * hands the next one over when the one playing is over.
 */
class SongsTest {

    private static Songs.Song song(String key, int seconds) {
        return new Songs.Song(key, Songs.title(key), Material.MUSIC_DISC_CAT, seconds);
    }

    @Test
    void everySongKeyHasItsDiscInThisJar() {
        assertEquals(22, Songs.KEYS.size(), "the songs of this API");
        for (String key : Songs.KEYS) {
            Material disc = Songs.disc(key);
            assertNotNull(disc, "a disc for " + key);
            assertTrue(disc.name().startsWith("MUSIC_DISC_"), disc.name());
        }
        assertEquals(Material.MUSIC_DISC_PIGSTEP, Songs.disc("pigstep"));
        assertNull(Songs.disc("not_a_song"), "a song with no disc is skipped");
    }

    @Test
    void aTitleIsTheKeysWords() {
        assertEquals("Pigstep", Songs.title("pigstep"));
        assertEquals("Creator Music Box", Songs.title("creator_music_box"));
        assertEquals("13", Songs.title("13"));
        assertEquals("Lava Chicken", Songs.title("lava_chicken"));
        assertEquals("3:05", Songs.length(185), "a length in minutes and seconds");
        for (String key : Songs.KEYS) {
            assertTrue(Songs.title(key).length() <= Songs.TITLE_MAX, "short enough for a Bedrock name: " + key);
        }
    }

    @Test
    void theQueuePlaysQueuesReplacesAndHandsOver() {
        Songs.Queue q = new Songs.Queue();
        Songs.Song cat = song("cat", 180);
        Songs.Song pigstep = song("pigstep", 150);
        Songs.Song stal = song("stal", 100);
        assertEquals(Songs.Act.PLAY, q.request(cat, 0), "nothing playing: it plays now");
        assertEquals(cat, q.now());
        assertEquals(Songs.Act.SAME, q.request(cat, 1_000), "the one playing again: nothing new");
        assertEquals(Songs.Act.QUEUE, q.request(pigstep, 2_000), "one playing, nothing next: it plays next");
        assertEquals(pigstep, q.next());
        assertEquals(Songs.Act.SAME, q.request(pigstep, 3_000), "already next");
        assertEquals(Songs.Act.REPLACE_NEXT, q.request(stal, 4_000), "another takes the next place");
        assertEquals(stal, q.next());
        assertNull(q.due(100_000), "not over yet");
        assertFalse(q.over(100_000));
        assertEquals(stal, q.due(180_000 + Songs.GRACE_MS), "over: the next one starts");
        assertEquals(stal, q.now());
        assertNull(q.next());
        assertNull(q.due(200_000), "nothing due while it plays");
        assertTrue(q.over(281_000), "over with nothing next: the disc may come out");
        assertNull(q.due(281_000), "nothing next: nothing starts");
        assertNull(q.now(), "and nothing plays");
        assertEquals(Songs.Act.PLAY, q.request(cat, 300_000), "a request after that plays at once");
        q.clear();
        assertNull(q.now());
        assertEquals(Songs.Act.PLAY, q.request(pigstep, 300_001), "cleared: the next request plays");
        assertEquals(Songs.Act.PLAY, q.request(cat, 300_001 + 151_000), "a request after the song ran out plays now");
        assertNull(q.next(), "and nothing is next");
        List<Songs.Song> songs = List.of(cat, pigstep);
        assertEquals(pigstep, Songs.find(songs, "pigstep"));
        assertNull(Songs.find(songs, "stal"));
    }
}
