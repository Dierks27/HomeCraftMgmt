package com.dierks.homecraft.games.clubhouse;

import org.bukkit.JukeboxSong;
import org.bukkit.Material;
import org.bukkit.Registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The records the jukebox corner can play (0.38.4): every song the server knows ({@link Registry#JUKEBOX_SONG}),
 * each with its disc and its length, and the pure queue that says what a request does: play now, play next, or
 * nothing new. The live side ({@link JukeboxCorner}) puts the disc in the block and takes it out again.
 *
 * <p>A song's title is made from its key ("pigstep" is "Pigstep", "creator_music_box" is "Creator Music Box"):
 * the game's own description is a translatable the client renders, so there is no plain text to show on a
 * Bedrock name or in chat. {@link #KEYS} is the list in this build's API, pinned by a test against the jar; the
 * live list comes from the registry, so a new song in a later server shows up by itself.
 */
public final class Songs {

    /** The songs in this API (each resolves to a {@code MUSIC_DISC_<KEY>}; a test checks every one). */
    static final List<String> KEYS = List.of("11", "13", "5", "blocks", "bounce", "cat", "chirp", "creator",
            "creator_music_box", "far", "lava_chicken", "mall", "mellohi", "otherside", "pigstep", "precipice",
            "relic", "stal", "strad", "tears", "wait", "ward");
    /** A title is cut to this many characters (a Bedrock tile's NAME). */
    static final int TITLE_MAX = 32;
    /** A second's grace after a song's length before the next one starts. */
    static final long GRACE_MS = 1_000L;

    private Songs() {
    }

    /** A record: its key, its title, the disc that plays it, and how long it lasts. */
    public record Song(String key, String title, Material disc, int seconds) {
    }

    /** What a request does. */
    public enum Act {
        /** Nothing was playing: it plays now. */
        PLAY,
        /** One is playing and nothing was next: it plays next. */
        QUEUE,
        /** One is playing and another was next: this one takes its place. */
        REPLACE_NEXT,
        /** It is the one playing, or already next: nothing new. */
        SAME
    }

    /** The disc that plays the song with {@code key}, or {@code null} for none in this build. */
    public static Material disc(String key) {
        return key == null ? null : Material.matchMaterial("music_disc_" + key.toLowerCase(Locale.ROOT));
    }

    /** "Pigstep", "Creator Music Box", "13": the key's words, each capitalised, cut to {@value #TITLE_MAX}. */
    public static String title(String key) {
        StringBuilder b = new StringBuilder();
        for (String word : (key == null ? "" : key).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return ClubBoardText.cut(b.length() == 0 ? "a record" : b.toString(), TITLE_MAX);
    }

    /**
     * Every song the server knows, by title; a song whose disc this build has no material for is skipped. Needs
     * the server (the registry): the tests use {@link #KEYS}.
     */
    public static List<Song> all() {
        List<Song> out = new ArrayList<>();
        for (JukeboxSong s : Registry.JUKEBOX_SONG) {
            String key = s.getKey().getKey();
            Material disc = disc(key);
            if (disc != null) {
                out.add(new Song(key, title(key), disc, (int) Math.ceil(s.getLengthInSeconds())));
            }
        }
        out.sort(Comparator.comparing(Song::title, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** The song with {@code key} in {@code songs}, or {@code null}. */
    public static Song find(List<Song> songs, String key) {
        for (Song s : songs == null ? List.<Song>of() : songs) {
            if (s.key().equals(key)) {
                return s;
            }
        }
        return null;
    }

    /** "3:05": a song's length. */
    public static String length(int seconds) {
        return ClubBoardText.clock(Math.max(0, seconds) * 1000L);
    }

    /** What is playing and what is next: the pure half of the corner. */
    public static final class Queue {
        private Song now;
        private long endsAt;
        private Song next;

        /** A request for {@code s} at {@code at}: what it does (the caller plays it on PLAY). */
        public Act request(Song s, long at) {
            if (now == null || at >= endsAt) {
                start(s, at);
                next = null;
                return Act.PLAY;
            }
            if (s.key().equals(now.key()) || (next != null && s.key().equals(next.key()))) {
                return Act.SAME;
            }
            Act act = next == null ? Act.QUEUE : Act.REPLACE_NEXT;
            next = s;
            return act;
        }

        /**
         * Once a second: the next song when the one playing is over (it becomes the one playing), or
         * {@code null}: nothing to start.
         */
        public Song due(long at) {
            if (now == null || at < endsAt) {
                return null;
            }
            if (next == null) {
                now = null;
                return null;
            }
            Song s = next;
            next = null;
            start(s, at);
            return s;
        }

        /** Whether the song playing has run out and nothing is next (the disc may come out). */
        public boolean over(long at) {
            return now != null && at >= endsAt && next == null;
        }

        private void start(Song s, long at) {
            now = s;
            endsAt = at + s.seconds() * 1000L + GRACE_MS;
        }

        /** Nothing playing, nothing next. */
        public void clear() {
            now = null;
            next = null;
            endsAt = 0;
        }

        /** The song playing (until {@link #due} says it is over), or {@code null}. */
        public Song now() {
            return now;
        }

        /** The song next, or {@code null}. */
        public Song next() {
            return next;
        }
    }
}
