package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A course as YAML, with no server.
 *
 * <p>Pinned here: a full course and a brand-new one both come back exactly as written (points,
 * radii, facing, fall height, shortest time, the switches and the layout version); text that isn't
 * YAML, or a course of no known kind, can't be read; everything else reads forgivingly and says
 * what was wrong — an unknown tier as easy, a garbled checkpoint left out (the others kept in
 * order), a radius kept in range, a name cleaned as if just typed, a shortest time kept in range —
 * and a course marked open without a start and a finish reads as closed.
 */
class CourseCodecTest {

    private static final Course FULL = new Course("river_run", TrialKind.BOAT, "River Run", Tier.MEDIUM, "games",
            new Course.Spot(10.5, 63.25, -4.125, 90.5f, -12.5f),
            List.of(new Course.Mark(20, 63, -4, 3), new Course.Mark(40.75, 63, 12.5, 4.25)),
            new Course.Mark(60, 63, 20, 5), 50.5, 30, true, true, 7);

    @Test
    void aFullCourseComesBackExactlyAsWritten() {
        CourseCodec.Decoded d = CourseCodec.decode("river_run", CourseCodec.encode(FULL));
        assertEquals(FULL, d.course(), "every field survives the round trip");
        assertEquals(List.of(), d.problems(), "and reading it found nothing wrong");
    }

    @Test
    void aBrandNewCourseComesBackWithNothingPlaced() {
        Course fresh = Course.create("sky_high", TrialKind.ELYTRA, Tier.EXTREME);
        Course back = CourseCodec.decode("sky_high", CourseCodec.encode(fresh)).course();
        assertEquals(fresh, back, "a course with no start, checkpoints or finish round-trips too");
        assertNull(back.start(), "no start yet");
        assertNull(back.finish(), "no finish yet");
        assertEquals("Sky High", back.name(), "named from its id");
        assertFalse(back.enabled(), "closed until it is finished");
    }

    @Test
    void textThatIsNotACourseCanNotBeRead() {
        CourseCodec.Decoded junk = CourseCodec.decode("x", "start: [unclosed");
        assertNull(junk.course(), "not YAML: nothing to read");
        assertFalse(junk.problems().isEmpty(), "and it says so");
        CourseCodec.Decoded kind = CourseCodec.decode("x", "kind: rollercoaster\nname: X\n");
        assertNull(kind.course(), "a course of no known kind can't run");
        assertTrue(kind.problems().get(0).contains("rollercoaster"), "the problem names the bad kind");
    }

    @Test
    void anUnknownTierReadsAsEasy() {
        CourseCodec.Decoded d = CourseCodec.decode("x", "kind: parkour\ntier: legendary\n");
        assertEquals(Tier.EASY, d.course().tier(), "an unknown tier is easy");
        assertEquals(1, d.problems().size(), "and it says so");
    }

    @Test
    void aGarbledCheckpointIsLeftOutAndTheOthersKeepTheirOrder() {
        String yaml = """
                kind: parkour
                checkpoints:
                - {x: 1, y: 2, z: 3, radius: 1.5}
                - {x: nope, y: 2, z: 3, radius: 1}
                - {x: 7, y: 8, z: 9, radius: 2}
                """;
        CourseCodec.Decoded d = CourseCodec.decode("x", yaml);
        assertEquals(List.of(new Course.Mark(1, 2, 3, 1.5), new Course.Mark(7, 8, 9, 2)), d.course().checkpoints(),
                "the readable checkpoints, in order");
        assertTrue(d.problems().get(0).startsWith("checkpoint 2"), "the unreadable one is named");
    }

    @Test
    void radiiAndShortestTimesAreKeptInRange() {
        String yaml = """
                kind: elytra
                finish: {x: 0, y: 0, z: 0, radius: 500}
                checkpoints:
                - {x: 1, y: 2, z: 3, radius: 0}
                min_seconds: 99999
                """;
        Course c = CourseCodec.decode("x", yaml).course();
        assertEquals(Course.MAX_RADIUS, c.finish().radius(), 1e-9, "a huge finish is cut to the largest radius");
        assertEquals(Course.MIN_RADIUS, c.checkpoints().get(0).radius(), 1e-9, "a zero radius is the smallest one");
        assertEquals(3600, c.minSeconds(), "the shortest time is at most an hour");
    }

    @Test
    void aNameIsCleanedAsIfJustTyped() {
        Course c = CourseCodec.decode("cliff_run", "kind: parkour\nname: '&cCliff  Run'\n").course();
        assertEquals("Cliff Run", c.name(), "colour codes out, spaces squeezed");
        Course blank = CourseCodec.decode("cliff_run", "kind: parkour\nname: '   '\n").course();
        assertEquals("Cliff Run", blank.name(), "a blank name is made from the id");
    }

    @Test
    void aCourseMarkedOpenWithoutAStartAndAFinishReadsAsClosed() {
        CourseCodec.Decoded d = CourseCodec.decode("x", "kind: boat\nenabled: true\nworld: games\n");
        assertNotNull(d.course(), "readable");
        assertFalse(d.course().enabled(), "but closed: it can't be run");
        assertEquals(1, d.problems().size(), "and it says why");
    }

    @Test
    void theFallHeightAndShortestTimeAreOptional() {
        Course c = CourseCodec.decode("x", "kind: parkour\n").course();
        assertNull(c.fallY(), "no fall_y: the default applies");
        assertNull(c.minSeconds(), "no min_seconds: the server's applies");
        assertEquals(1, c.rev(), "a course starts at layout 1");
    }
}
