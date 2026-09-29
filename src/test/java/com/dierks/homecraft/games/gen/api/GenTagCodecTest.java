package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.golf.CourseCodec;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code gen:} block (GEN-SPEC §5.1) in both course codecs.
 *
 * <p>Pinned here: a generated trial and a generated golf course (attempts and witness lines
 * included) come back exactly as written; a row without the block is written byte for byte as
 * before Daily Courses (golden text captured from the old codecs) and reads back with no tag;
 * seeds and hashes that look like numbers survive YAML; every yaw comes back as exactly the same
 * float; and a damaged block is never read as something else — the trial comes back closed with
 * no tag and says why, the golf row refuses to read.
 */
class GenTagCodecTest {

    /** The trial codec's text for CourseCodecTest's FULL course, as the codec wrote it before gen:. */
    private static final String FULL_TRIAL_TEXT = """
            name: River Run
            kind: boat
            tier: medium
            world: games
            enabled: true
            pinned: true
            rev: 7
            start:
              x: 10.5
              y: 63.25
              z: -4.125
              yaw: 90.5
              pitch: -12.5
            checkpoints:
            - x: 20.0
              y: 63.0
              z: -4.0
              radius: 3.0
            - x: 40.75
              y: 63.0
              z: 12.5
              radius: 4.25
            finish:
              x: 60.0
              y: 63.0
              z: 20.0
              radius: 5.0
            fall_y: 50.5
            min_seconds: 30
            """;

    private static final String FRESH_TRIAL_TEXT = """
            name: Sky High
            kind: elytra
            tier: extreme
            world: ''
            enabled: false
            pinned: false
            rev: 1
            checkpoints: []
            """;

    /** The golf codec's text for two holes (one complete, one empty), as written before gen:. */
    private static final String GOLF_TEXT = """
            format: 1
            holes:
            - par: 3
              tee:
                x: 10.5
                y: 64.0
                z: -3.5
                yaw: 90.0
              cup:
                x: 18
                y: 63
                z: -3
              corner1:
                x: 8
                y: 63
                z: -6
              corner2:
                x: 20
                y: 66
                z: 0
            - par: 2
            """;

    private static final Course FULL = new Course("river_run", TrialKind.BOAT, "River Run", Tier.MEDIUM, "games",
            new Course.Spot(10.5, 63.25, -4.125, 90.5f, -12.5f),
            List.of(new Course.Mark(20, 63, -4, 3), new Course.Mark(40.75, 63, 12.5, 4.25)),
            new Course.Mark(60, 63, 20, 5), 50.5, 30, true, true, 7);

    private static final GolfCourse.Hole HOLE = new GolfCourse.Hole(new GolfCourse.Tee(10.5, 64.0, -3.5, 90f),
            new GolfCourse.Spot(18, 63, -3), 3, new GolfCourse.Spot(8, 63, -6), new GolfCourse.Spot(20, 66, 0));
    private static final GolfCourse.Hole EMPTY_HOLE = new GolfCourse.Hole(null, null, 2, null, null);

    static GenTag trialTag() {
        return new GenTag("daily_parkour_easy", "parkour", 1, 20725, 0, 0x3F2A91C07D1E55B0L, 'B', "3c9e51aa07b2",
                38_000, 76_000, 114_000, List.of(), List.of(), 1_790_661_730_000L);
    }

    static GenTag golfTag() {
        return new GenTag("daily_golf", "golf", 2, 20725, 3, -7L, 'A', "0123456789ab", 0, 0, 0, List.of(0, 2, 0),
                List.of(List.of(new Putt(12.5f, 5)), List.of(new Putt(270f, 4), new Putt(265.3f, 2)),
                        List.of(new Putt(-33.333f, 1), new Putt(0.1f, 3), new Putt(359.99f, 5))),
                1_790_661_730_000L);
    }

    @Test
    void aGeneratedTrialComesBackExactlyAsWritten() {
        Course c = FULL.withGen(trialTag());
        String text = com.dierks.homecraft.games.trial.CourseCodec.encode(c);
        assertTrue(text.startsWith(FULL_TRIAL_TEXT), "the course part is written as it always was, then gen:");
        assertTrue(text.contains("\ngen:\n  slot: daily_parkour_easy\n  generator: parkour\n"),
                "the block is readable, in the documented order:\n" + text);
        assertTrue(text.contains("seed: 3f2a91c07d1e55b0"), "the seed is hex");
        assertTrue(text.contains("date: '2026-09-29'"), "the date is there for people");
        assertFalse(text.contains("attempts") || text.contains("witness"), "a trial has no golf parts");
        com.dierks.homecraft.games.trial.CourseCodec.Decoded d =
                com.dierks.homecraft.games.trial.CourseCodec.decode("river_run", text);
        assertEquals(c, d.course(), "every field survives, the tag included");
        assertEquals(List.of(), d.problems(), "and nothing was wrong");
        assertTrue(d.course().generated(), "it knows it was generated");
    }

    @Test
    void aGeneratedGolfCourseComesBackWithItsAttemptsAndWitness() {
        GolfCourse c = new GolfCourse("daily_golf", "Daily Golf", "games", true, 12, List.of(HOLE, HOLE, HOLE),
                golfTag());
        GamesDao.CourseRow row = CourseCodec.toRow(c, 1, 2);
        assertTrue(row.data().startsWith("format: 1\nholes:\n"), "the holes come first, as always");
        assertTrue(row.data().contains("\ngen:\n  slot: daily_golf\n"), "then the gen: block");
        GolfCourse back = CourseCodec.fromRow(row);
        assertEquals(c, back, "every hole, attempt and putt survives");
        assertEquals(265.3f, back.gen().witness().get(1).get(1).yaw(), "a yaw is exactly the float it was");
        assertEquals(c.gen(), CourseCodec.readGen(row.data()), "the tag alone reads too");
        assertEquals(List.of(HOLE, HOLE, HOLE), CourseCodec.read(row.data()), "and the holes alone ignore it");
    }

    @Test
    void rowsWithoutATagAreWrittenByteForByteAsBefore() {
        assertEquals(FULL_TRIAL_TEXT, com.dierks.homecraft.games.trial.CourseCodec.encode(FULL),
                "a hand-built trial's text is unchanged");
        assertEquals(FRESH_TRIAL_TEXT, com.dierks.homecraft.games.trial.CourseCodec.encode(
                Course.create("sky_high", TrialKind.ELYTRA, Tier.EXTREME)), "so is a brand-new one's");
        assertEquals(GOLF_TEXT, CourseCodec.write(List.of(HOLE, EMPTY_HOLE)), "a hand-built golf course's too");
        assertEquals(GOLF_TEXT, CourseCodec.write(List.of(HOLE, EMPTY_HOLE), null), "with no tag given");
        GolfCourse plain = new GolfCourse("meadow", "Meadow", "games", true, 3, List.of(HOLE, EMPTY_HOLE));
        assertEquals(GOLF_TEXT, CourseCodec.toRow(plain, 1, 2).data(), "and as a row");
        assertNull(CourseCodec.fromRow(CourseCodec.toRow(plain, 1, 2)).gen(), "which reads back hand-built");
        assertNull(com.dierks.homecraft.games.trial.CourseCodec.decode("river_run", FULL_TRIAL_TEXT).course().gen(),
                "an old trial row reads back hand-built");
        assertNull(CourseCodec.readGen(""), "an empty row has no tag");
    }

    @Test
    void theBlockRoundTripsOnItsOwn() {
        for (GenTag t : List.of(trialTag(), golfTag())) {
            assertEquals(t, GenTagCodec.read(GenTagCodec.write(t)), "a tag comes back from its own map: " + t.slot());
        }
        GenTag hex = new GenTag("tiny_golf", "golf", 1, 20725, 0, 0x1234567890123456L, 'a', "123456789012", 0, 0, 0,
                List.of(), List.of(), 0);
        String text = com.dierks.homecraft.games.trial.CourseCodec.encode(FULL.withGen(hex));
        Course back = com.dierks.homecraft.games.trial.CourseCodec.decode("river_run", text).course();
        assertEquals(hex, back.gen(), "a seed and a hash made only of digits survive YAML as text");
        assertEquals('A', back.gen().half(), "a half is kept upper-case");
    }

    @Test
    void everyYawComesBackAsTheSameFloat() {
        GenRandom r = new GenRandom(11);
        List<List<Putt>> lines = new ArrayList<>();
        for (int h = 0; h < 20; h++) {
            List<Putt> line = new ArrayList<>();
            for (int p = 0; p < 10; p++) {
                line.add(new Putt((float) r.nextDouble(-720, 720), r.nextInt(1, 5)));
            }
            lines.add(line);
        }
        GenTag t = new GenTag("daily_golf", "golf", 1, 1, 0, 1, 'A', "x", 0, 0, 0, List.of(), lines, 0);
        GolfCourse c = new GolfCourse("daily_golf", "Daily Golf", "games", true, 1, List.of(HOLE), t);
        assertEquals(lines, CourseCodec.fromRow(CourseCodec.toRow(c, 1, 1)).gen().witness(),
                "two hundred random yaws and powers come back bit for bit");
    }

    @Test
    void aDamagedBlockIsNeverReadAsSomethingElse() {
        String good = com.dierks.homecraft.games.trial.CourseCodec.encode(FULL.withGen(trialTag()));
        String bad = good.replace("seed: 3f2a91c07d1e55b0", "seed: not-hex");
        com.dierks.homecraft.games.trial.CourseCodec.Decoded d =
                com.dierks.homecraft.games.trial.CourseCodec.decode("river_run", bad);
        assertNull(d.course().gen(), "the unreadable tag is left out");
        assertFalse(d.course().enabled(), "and the course is closed: nothing vouches for it");
        assertTrue(d.problems().stream().anyMatch(p -> p.contains("gen") && p.contains("seed")),
                "the problem names the block and the part: " + d.problems());
        GolfCourse c = new GolfCourse("daily_golf", "Daily Golf", "games", true, 1, List.of(HOLE), golfTag());
        String golf = CourseCodec.toRow(c, 1, 1).data().replace("half: A", "half: C");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CourseCodec.fromRow(new GamesDao.CourseRow("daily_golf", "golf", "golf", "Daily Golf", "games",
                        true, golf, 1, 1, 1)), "a golf row with a damaged tag can't be read");
        assertTrue(e.getMessage().contains("half"), "and says which part: " + e.getMessage());
    }

    @Test
    void theReaderNamesWhatIsMissingOrWrong() {
        Map<String, Object> good = GenTagCodec.write(golfTag());
        for (String key : List.of("slot", "generator", "algo", "day", "seed", "half")) {
            Map<String, Object> m = new LinkedHashMap<>(good);
            m.remove(key);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> GenTagCodec.read(m),
                    "without " + key + " it can't be read");
            assertTrue(e.getMessage().contains(key), "and says so: " + e.getMessage());
        }
        Map<String, Object> noExtras = new LinkedHashMap<>(good);
        for (String key : List.of("reroll", "plan", "ref_ms", "gold_ms", "silver_ms", "built_at", "attempts",
                "witness", "date")) {
            noExtras.remove(key);
        }
        GenTag bare = GenTagCodec.read(noExtras);
        assertEquals(0, bare.reroll(), "a missing reroll is 0");
        assertEquals("", bare.planHash(), "a missing plan hash is empty");
        assertEquals(List.of(), bare.witness(), "no witness is an empty one");
        Map<String, Object> power = new LinkedHashMap<>(good);
        power.put("witness", List.of(List.of(List.of(10.0, 9))));
        assertThrows(IllegalArgumentException.class, () -> GenTagCodec.read(power), "a club above 5 is refused");
        Map<String, Object> pair = new LinkedHashMap<>(good);
        pair.put("witness", List.of(List.of(List.of(10.0))));
        assertThrows(IllegalArgumentException.class, () -> GenTagCodec.read(pair), "a putt is [yaw, power]");
        assertThrows(IllegalArgumentException.class, () -> GenTagCodec.read(null), "no block is not a tag");
    }

    @Test
    void theTagKnowsItsEditionAndLayout() {
        GenTag t = trialTag();
        assertEquals("20725", t.editionKey(), "no reroll: the day");
        assertEquals("20725r3", golfTag().editionKey(), "a reroll: day r n");
        assertEquals('A', t.otherHalf(), "B's other half is A");
        GenTag restamped = t.withEdition(20726, 0);
        assertTrue(t.sameLayout(restamped), "a restamp is the same blocks on a new day");
        assertEquals(20726, restamped.day(), "with the new day");
        assertFalse(t.sameLayout(golfTag()), "another slot is another layout");
        assertFalse(t.sameLayout(null), "nothing is not a layout");
        assertEquals(5L, t.withBuiltAt(5).builtAt(), "a new verified time");
        assertThrows(IllegalArgumentException.class, () -> new GenTag("x", "parkour", 1, 1, 0, 1, 'C', "", 0, 0, 0,
                null, null, 0), "a half is A or B");
    }
}
