package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenArchiveDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the website gets (GEN-SPEC-KEEP §8): the golden {@code freshHistory} JSON (newest first, at
 * most {@code feed_history} per slot, short seeds, a time or strokes record, kept and classic marks);
 * a record's holder only when names may be shown; never an edition that isn't live yet; and the
 * per-course {@code fresh} and {@code classic} objects, with {@code to} left out for "forever".
 */
class FreshFeedTest {

    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static GenArchiveDao.Row row(String slot, String edition, String code, int seq, String kind, String tier,
                                        String name, long from, Long to, long seed, String kept) {
        return new GenArchiveDao.Row(slot, edition, code, seq, 20731, seed, "x/1", kind, tier, name, from, to, null, 0,
                0, from, kept);
    }

    private static List<GenArchiveDao.Row> rows() {
        return List.of(
                row("fresh_parkour_hard", "7:40", "HARD-40", 40, "parkour", "hard", "Hard Parkour", 1_790_000_000_000L,
                        1_790_604_800_000L, 0x3f2a9c01b7de55b0L, "dragon_run"),
                row("fresh_parkour_hard", "7:41", "HARD-41", 41, "parkour", "hard", "Hard Parkour", 1_790_604_800_000L,
                        null, 0x0000000000000001L, null),
                row("fresh_golf", "7:41", "GOLF-3", 3, "golf", "EEEMMMMHH", "Golf of the Week", 1_790_604_900_000L, null,
                        0xabcdef0123456789L, null));
    }

    private static List<FreshFeed.Entry> history(boolean names, int perSlot, long now) {
        return FreshFeed.history(rows(), board -> switch (board) {
            case "gfresh:fresh_parkour_hard:7:40" -> new FreshFeed.Board(14, new GamesDao.ScoreRow(SAM, 62_300,
                    1_790_100_000_000L));
            case "gfresh:fresh_golf:7:41" -> new FreshFeed.Board(3, new GamesDao.ScoreRow(SAM, 27, 1_790_700_000_000L));
            default -> new FreshFeed.Board(0, null);
        }, slot -> slot.equals("fresh_golf") ? null : 1_791_209_600_000L, Set.of("fresh_parkour_hard|7:40"), perSlot,
                now, names, id -> id.equals(SAM) ? "Sam \"the fast\"" : "?");
    }

    @Test
    void theHistoryJsonIsGolden() {
        String json = FreshFeed.json(history(true, 26, 1_791_000_000_000L));
        assertEquals("[{\"code\":\"GOLF-3\",\"slot\":\"fresh_golf\",\"name\":\"Golf of the Week\",\"kind\":\"golf\","
                + "\"from\":1790604900000,\"seed\":\"abcdef012345\",\"plays\":3,\"record\":{\"strokes\":27,"
                + "\"at\":1790700000000,\"holder\":\"Sam \\\"the fast\\\"\"}},"
                + "{\"code\":\"HARD-41\",\"slot\":\"fresh_parkour_hard\",\"name\":\"Hard Parkour\",\"kind\":\"parkour\","
                + "\"tier\":\"hard\",\"from\":1790604800000,\"to\":1791209600000,\"seed\":\"000000000000\",\"plays\":0},"
                + "{\"code\":\"HARD-40\",\"slot\":\"fresh_parkour_hard\",\"name\":\"Hard Parkour\",\"kind\":\"parkour\","
                + "\"tier\":\"hard\",\"from\":1790000000000,\"to\":1790604800000,\"seed\":\"3f2a9c01b7de\",\"plays\":14,"
                + "\"record\":{\"ms\":62300,\"at\":1790100000000,\"holder\":\"Sam \\\"the fast\\\"\"},"
                + "\"kept\":\"dragon_run\",\"classic\":true}]", json,
                "newest first; golf has strokes and no tier; a live one ends at its scheduled change (none while"
                        + " pinned forever); a kept one names its course; a recalled one is marked classic");
    }

    @Test
    void theHolderIsNamedOnlyWhenNamesMayBeShown() {
        for (FreshFeed.Entry e : history(false, 26, 1_791_000_000_000L)) {
            if (e.record() != null) {
                assertNull(e.record().holder(), e.code() + "'s record has no name when names are hidden");
            }
        }
        assertFalse(FreshFeed.json(history(false, 26, 1_791_000_000_000L)).contains("holder"), "no holder key at all");
        assertTrue(FreshFeed.json(history(true, 26, 1_791_000_000_000L)).contains("holder"), "and one when allowed");
    }

    @Test
    void anEditionThatIsntLiveYetIsNeverInTheFeed() {
        List<FreshFeed.Entry> early = history(true, 26, 1_790_604_850_000L);
        assertEquals(List.of("HARD-41", "HARD-40"), early.stream().map(FreshFeed.Entry::code).toList(),
                "GOLF-3 starts after now: nothing of it is published");
        assertNull(FreshFeed.json(history(true, 26, 1_789_000_000_000L)), "nothing live yet: the array is left out");
        assertEquals(List.of("GOLF-3", "HARD-41"), history(true, 1, 1_791_000_000_000L).stream()
                .map(FreshFeed.Entry::code).toList(), "at most feed_history per slot, the newest");
        assertNull(FreshFeed.json(history(true, 0, 1_791_000_000_000L)), "feed_history 0 publishes none");
    }

    @Test
    void theFreshAndClassicObjects() {
        assertEquals("{\"code\":\"HARD-40\",\"seed\":\"3f2a9c01b7de\",\"from\":1790000000000,\"to\":1790604800000,"
                + "\"cadenceDays\":7}", new FreshFeed.Fresh("HARD-40", "3f2a9c01b7de", 1_790_000_000_000L,
                1_790_604_800_000L, 7).json(), "a live course's fresh object");
        assertEquals("{\"code\":\"HARD-40\",\"seed\":\"3f2a9c01b7de\",\"from\":1790000000000,\"cadenceDays\":7}",
                new FreshFeed.Fresh("HARD-40", "3f2a9c01b7de", 1_790_000_000_000L, null, 7).json(),
                "pinned forever: no to");
        assertEquals("{\"code\":\"HARD-40\",\"from\":1795000000000,\"to\":1795604800000}",
                new FreshFeed.Classic("HARD-40", 1_795_000_000_000L, 1_795_604_800_000L).json(), "a recall's window");
        assertEquals("{\"code\":\"HARD-40\",\"from\":1795000000000}", new FreshFeed.Classic("HARD-40",
                1_795_000_000_000L, null).json(), "recalled forever: no to");
        assertEquals("3f2a9c01b7de", FreshFeed.shortSeed(0x3f2a9c01b7de55b0L), "the website's short seed: 12 hex");
        Map<String, Object> m = FreshFeed.map(history(true, 26, 1_791_000_000_000L).get(2));
        assertTrue(FreshFeed.KEYS.containsAll(m.keySet()), "only the published keys: " + m.keySet());
    }
}
