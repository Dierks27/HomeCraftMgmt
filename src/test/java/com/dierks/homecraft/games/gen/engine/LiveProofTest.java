package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.engine.GenKit.FakeWorld;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The last word on a built course (GEN-SPEC §3.3 step 6, §3.5): each golf hole's witness line must
 * hole out in exactly its own number of putts on the blocks as they are; and a layout from an older
 * planner gets the quick check of a solid block under every place a player or ball must stand.
 */
class LiveProofTest {

    private static final int FLOOR = 63;

    /** A walled straight lane along +z with a sunken cup 5 ahead of the tee. */
    private static FakeWorld lane() {
        FakeWorld w = new FakeWorld("games");
        for (int x = -3; x <= 3; x++) {
            for (int z = -2; z <= 14; z++) {
                boolean wall = Math.abs(x) == 3 || z == -2 || z == 14;
                w.put(x, FLOOR, z, "minecraft:lime_concrete");
                if (wall) {
                    w.put(x, FLOOR + 1, z, "minecraft:stripped_spruce_wood");
                }
            }
        }
        w.blocks.remove(GenKit.pos(0, FLOOR, 5));
        w.put(0, FLOOR - 1, 5, "minecraft:black_concrete");
        return w;
    }

    private static GolfCourse.Hole hole() {
        return new GolfCourse.Hole(new GolfCourse.Tee(0.5, FLOOR + 1, 0.5, 0f), new GolfCourse.Spot(0, FLOOR - 1, 5), 2,
                new GolfCourse.Spot(-3, FLOOR - 3, -2), new GolfCourse.Spot(3, FLOOR + 4, 14));
    }

    /** A one-putt line down the lane, found the way the planner would (or null). */
    private static List<Putt> onePutt(FakeWorld w) {
        for (int power = 1; power <= 5; power++) {
            for (float yaw = -4f; yaw <= 4f; yaw += 0.5f) {
                List<Putt> line = List.of(new Putt(yaw, power));
                GolfShot.Replay r = GolfShot.replay(w.ballBlocks(), hole(), line);
                if (r.holed() && r.strokes() == 1) {
                    return line;
                }
            }
        }
        return null;
    }

    @Test
    void aWitnessThatHolesOutPassesAndOneThatDoesNotFailsNamingTheHole() {
        FakeWorld w = lane();
        List<Putt> line = onePutt(w);
        assertNotNull(line, "a straight 5-block lane has a one-putt line");
        assertEquals(List.of(), LiveProof.replay(w.ballBlocks(), List.of(hole()), List.of(line)),
                "the witness replays on the blocks it was proven on");
        List<Putt> tooLong = List.of(line.get(0), new Putt(0f, 1));
        List<String> extra = LiveProof.replay(w.ballBlocks(), List.of(hole()), List.of(tooLong));
        assertFalse(extra.isEmpty(), "a line that holes out before its last putt isn't the proven line");

        w.blocks.remove(GenKit.pos(0, FLOOR - 1, 5)); // the cup floor is gone on the real blocks
        List<String> broken = LiveProof.replay(w.ballBlocks(), List.of(hole()), List.of(line));
        assertEquals(1, broken.size(), "one problem: " + broken);
        assertTrue(broken.get(0).startsWith("hole 1"), "naming the hole: " + broken);
        assertFalse(LiveProof.replay(w.ballBlocks(), List.of(hole(), hole()), List.of(line)).isEmpty(),
                "every hole needs its witness line");
    }

    @Test
    void theQuickCheckWantsASolidBlockUnderEveryStandingPlace() {
        FakeWorld w = new FakeWorld("games");
        w.put(10, 99, 10, "minecraft:lime_concrete");
        w.put(20, 99, 10, "minecraft:light_blue_concrete");
        w.put(30, 99, 10, "minecraft:gold_block");
        LiveProof.Solid solid = (x, y, z) -> w.at(x, y, z) != null;
        Course c = new Course("fresh_parkour_easy", TrialKind.PARKOUR, "Easy Parkour", Tier.EASY, "games",
                new Course.Spot(10.5, 100, 10.5, 0f, 0f), List.of(new Course.Mark(20.5, 100, 10.5, 2.2)),
                new Course.Mark(30.5, 100, 10.5, 3), null, null, true, false, 1);
        assertEquals(List.of(), LiveProof.structure(c, solid), "every pad is there");
        w.blocks.remove(GenKit.pos(20, 99, 10));
        assertEquals(List.of("nothing solid under checkpoint 1"), LiveProof.structure(c, solid), "a missing pad");
        Course rings = new Course("fresh_rings", TrialKind.ELYTRA, "Sky Rings", Tier.EASY, "games",
                new Course.Spot(10.5, 100, 10.5, 0f, 0f), List.of(new Course.Mark(20.5, 150, 10.5, 5)),
                new Course.Mark(30.5, 120, 10.5, 5), null, null, true, false, 1);
        assertEquals(List.of(), LiveProof.structure(rings, solid), "rings float: only the tower is checked");

        GolfCourse golf = new GolfCourse("fresh_tiny_golf", "Tiny Golf", "games", true, 1, List.of(new GolfCourse.Hole(
                new GolfCourse.Tee(10.5, 100, 10.5, 0f), new GolfCourse.Spot(30, 99, 10), 2,
                new GolfCourse.Spot(0, 90, 0), new GolfCourse.Spot(40, 110, 20))));
        assertEquals(List.of(), LiveProof.structure(golf, solid), "a tee on turf and a cup block");
        w.blocks.remove(GenKit.pos(30, 99, 10));
        assertEquals(List.of("hole 1's cup block is missing"), LiveProof.structure(golf, solid), "a missing cup");
    }
}
