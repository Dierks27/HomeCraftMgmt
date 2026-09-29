package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's own look at any plan before a block is set (GEN-SPEC §3.3 step 2), and the row a
 * flip writes: a good plan passes; one for another slot or half, with a block off the palette,
 * outside its half, set twice, or a hash that doesn't match, is refused; a golf plan becomes a golf
 * row that reads back with its tag.
 */
class PlanCheckTest {

    private static final Slots.Def DEF = Slots.DAILY_PARKOUR_EASY;
    private static final Box A = DEF.half('A');

    @Test
    void aGoodPlanPassesAndEachKindOfBadOneIsRefused() {
        Plan good = GenKit.plan(DEF, A, 11, 1);
        assertEquals(List.of(), PlanCheck.problems(good, DEF, A), "the kit's plan is fine");
        assertTrue(PlanCheck.problems(good, Slots.DAILY_PARKOUR_HARD, A).get(0).contains("not daily_parkour_hard"),
                "a plan for another slot");
        assertTrue(PlanCheck.problems(good, DEF, DEF.half('B')).get(0).contains("not x 4192"),
                "a plan for the other half");

        List<BlockOp> twice = new ArrayList<>(good.ops());
        twice.add(good.ops().get(0));
        Plan doubled = Plan.of(good.slot(), 1, 11, A, good.palette(), twice, good.signs(), List.of(), good.course(),
                List.of(), 0);
        assertTrue(PlanCheck.problems(doubled, DEF, A).contains("1 block is set twice"), "a block set twice");

        List<BlockOp> out = new ArrayList<>(good.ops());
        out.add(new BlockOp(A.maxX() + 1, A.minY(), A.minZ(), (short) 0));
        Plan outside = Plan.of(good.slot(), 1, 11, A, good.palette(), out, good.signs(), List.of(), good.course(),
                List.of(), 0);
        assertTrue(PlanCheck.problems(outside, DEF, A).contains("1 block is outside the half"), "a block outside");

        Plan sand = Plan.of(good.slot(), 1, 11, A, List.of("minecraft:sand", "minecraft:light_blue_concrete",
                "minecraft:gold_block"), good.ops(), good.signs(), List.of(), good.course(), List.of(), 0);
        assertTrue(PlanCheck.problems(sand, DEF, A).get(0).contains("minecraft:sand"), "a block off the palette");

        Plan lying = new Plan(good.slot(), 1, 11, A, good.palette(), good.ops(), good.signs(), List.of(),
                good.course(), List.of(), 0, "000000000000");
        assertTrue(PlanCheck.problems(lying, DEF, A).contains("the plan's hash doesn't match its blocks"),
                "a hash that doesn't name the blocks");
        assertTrue(PlanCheck.problems(good, Slots.DAILY_GOLF, Slots.DAILY_GOLF.half('A')).stream()
                .anyMatch(p -> p.contains("no golf course")), "a trial plan for a golf slot");
    }

    @Test
    void aGolfPlanBecomesAGolfRowThatReadsBackWithItsTag() {
        Slots.Def def = Slots.TINY_GOLF;
        Box half = def.half('A');
        int t = half.minY() + 4;
        GolfCourse.Hole hole = new GolfCourse.Hole(new GolfCourse.Tee(half.minX() + 10.5, t, half.minZ() + 3.5, 0f),
                new GolfCourse.Spot(half.minX() + 10, t - 2, half.minZ() + 15), 3,
                new GolfCourse.Spot(half.minX() + 7, t - 3, half.minZ() + 1),
                new GolfCourse.Spot(half.minX() + 13, t + 4, half.minZ() + 20));
        PlannedGolf pg = new PlannedGolf(new GolfCourse(def.id(), def.name(), "", false, 1, List.of(hole)),
                List.of(2), List.of(List.of(new Putt(1.5f, 3), new Putt(-2f, 1))), List.of(2), List.of(3));
        GenTag tag = new GenTag(def.id(), "golf", 1, 20725, 0, 77, 'A', "abcdefabcdef", 0, 0, 0, pg.attempts(),
                pg.witness(), 5);
        GamesDao.CourseRow row = GenService.row(def, "games", pg, tag, null, 1000);
        assertEquals("golf", row.game(), "a golf row");
        assertEquals(def.name(), row.name(), "named for the slot");
        assertEquals("games", row.world(), "in the gen world");
        assertTrue(row.enabled(), "open (the gate decides who may play it)");
        assertEquals(tag, GenService.tagOf(def, row), "the tag, with its attempts and witness, reads back");
        GamesDao.CourseRow again = GenService.row(def, "games", pg, tag, row, 2000);
        assertEquals(2, again.rev(), "the next flip is the next rev");
        assertEquals(1000, again.createdAt(), "made when it was first made");
    }
}
