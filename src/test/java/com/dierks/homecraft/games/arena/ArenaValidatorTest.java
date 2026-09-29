package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.Cell;
import com.dierks.homecraft.games.arena.rules.FloorLayout;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arena's independent check (EVENTS-DROPPER-SPEC §B.3.2): a good plan passes, and one bad plan
 * per rule is refused by that rule (box, ops, palette, floors, walls, footprint, area, pieces,
 * spawns, gallery, out).
 */
class ArenaValidatorTest {

    private static final Box BOX = ArenaPlannerTest.BOX;
    private static final List<Integer> FLOORS = List.of(200, 192, 184);

    private static ArenaSite good() {
        return ArenaPlannerTest.site(42);
    }

    /** The same site with other blocks, palette, spawns or out height. */
    private static ArenaSite with(ArenaSite s, Box half, List<String> palette, List<BlockOp> ops, List<Cell> spawns,
                                  int outY) {
        Plan p = Plan.of(s.plan().slot(), s.plan().algo(), s.plan().seed(), half, palette, ops, List.of(), List.of(),
                null, List.of(), 0);
        FloorLayout l = FloorLayout.fromPlan(p, FLOORS, outY, spawns);
        return new ArenaSite(p, l, s.week(), s.shapes(), s.floorBlocks(), s.galleryY(), s.gallerySpots());
    }

    private static ArenaSite withOps(List<BlockOp> ops) {
        ArenaSite s = good();
        return with(s, s.plan().half(), s.plan().palette(), ops, s.layout().spawns(), 181);
    }

    private static List<BlockOp> ops() {
        return new ArrayList<>(good().plan().ops());
    }

    private static void refusedBy(String rule, ArenaSite bad) {
        List<String> problems = ArenaValidator.problems(bad);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith(rule + ":")), "the " + rule + " rule refuses it: "
                + problems);
    }

    private static void refusedOnlyBy(String rule, ArenaSite bad) {
        List<String> problems = ArenaValidator.problems(bad);
        assertTrue(!problems.isEmpty() && problems.stream().allMatch(p -> p.startsWith(rule + ":")),
                "only the " + rule + " rule refuses it: " + problems);
    }

    @Test
    void aGoodPlanPasses() {
        assertEquals(List.of(), ArenaValidator.problems(good()), "the planner's plan is fine");
        assertEquals(List.of("box: there is no plan"), ArenaValidator.problems(null), "no plan is a problem, not a throw");
    }

    @Test
    void theBoxRule() {
        List<BlockOp> ops = ops();
        ops.add(new BlockOp(BOX.maxX() + 1, 206, BOX.minZ() + 20, (short) 4));
        refusedBy("box", withOps(ops));
        ArenaSite s = good();
        refusedOnlyBy("box", with(s, Box.sized(BOX.minX(), BOX.minY(), BOX.minZ(), 48, 41, 48), s.plan().palette(),
                s.plan().ops(), s.layout().spawns(), 181));
    }

    @Test
    void theOpsRule() {
        List<BlockOp> ops = ops();
        BlockOp walk = new BlockOp(BOX.minX() + 1, 206, BOX.minZ() + 1, (short) 4);
        while (ops.size() <= ArenaPlanner.MAX_OPS) {
            ops.add(walk);
        }
        refusedOnlyBy("ops", withOps(ops));
    }

    @Test
    void thePaletteRuleRefusesWaterAndTnt() {
        ArenaSite s = good();
        List<String> palette = new ArrayList<>(s.plan().palette());
        palette.add("minecraft:water[level=0]");
        refusedOnlyBy("palette", with(s, BOX, palette, s.plan().ops(), s.layout().spawns(), 181));
        palette.set(palette.size() - 1, "minecraft:tnt");
        refusedOnlyBy("palette", with(s, BOX, palette, s.plan().ops(), s.layout().spawns(), 181));
    }

    @Test
    void theFloorsRuleWantsEachFloorsOwnGlass() {
        List<BlockOp> ops = ops();
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i).y() == 200) {
                BlockOp o = ops.get(i);
                ops.set(i, new BlockOp(o.x(), o.y(), o.z(), (short) 1)); // pink on the yellow floor
                break;
            }
        }
        refusedOnlyBy("floors", withOps(ops));
    }

    @Test
    void theWallsRuleRefusesAnythingButFloorsAndTheGallery() {
        List<BlockOp> ops = ops();
        ops.add(new BlockOp(5384, 201, 4360, (short) 3)); // glass standing on the top floor's corner: a wall top
        refusedOnlyBy("walls", withOps(ops));
    }

    @Test
    void theFootprintRuleKeepsFloorsInTheMiddle() {
        List<BlockOp> ops = ops();
        for (int x = 5381; x <= 5383; x++) {
            for (int z = 4370; z <= 4372; z++) {
                ops.add(new BlockOp(x, 192, z, (short) 1)); // a 3 x 3 of pink just outside the footprint
            }
        }
        refusedBy("footprint", withOps(ops));
    }

    @Test
    void theAreaRuleWantsFourHundredAndFiftyGiveOrTakeTenPercent() {
        List<BlockOp> ops = new ArrayList<>();
        for (BlockOp o : ops()) {
            if (o.y() != 184 || o.x() < 5400) {
                ops.add(o); // half of the bottom floor
            }
        }
        refusedOnlyBy("area", withOps(ops));
    }

    @Test
    void thePiecesRuleRefusesALoneBlock() {
        List<BlockOp> ops = ops();
        ops.add(new BlockOp(5384, 192, 4360, (short) 1)); // the middle floor's empty corner
        refusedOnlyBy("pieces", withOps(ops));
    }

    @Test
    void theSpawnsRule() {
        ArenaSite s = good();
        refusedOnlyBy("spawns", with(s, BOX, s.plan().palette(), s.plan().ops(), s.layout().spawns().subList(0, 5),
                181));
        List<Cell> close = new ArrayList<>(s.layout().spawns());
        Cell first = close.get(0);
        Cell next = null;
        for (Cell c : s.layout().layer(0).cells()) {
            if (Math.abs(c.x() - first.x()) + Math.abs(c.z() - first.z()) == 1 && !close.contains(c)) {
                next = c;
                break;
            }
        }
        close.set(1, next);
        refusedOnlyBy("spawns", with(s, BOX, s.plan().palette(), s.plan().ops(), close, 181));
    }

    @Test
    void theGalleryRuleWantsAWholeWalkAndBothRails() {
        List<BlockOp> noWalk = ops();
        noWalk.removeIf(o -> o.y() == 206 && o.x() == BOX.minX() + 2 && o.z() == BOX.minZ() + 20);
        refusedOnlyBy("gallery", withOps(noWalk));
        List<BlockOp> noRail = ops();
        noRail.removeIf(o -> o.y() == 208 && o.x() == BOX.minX() + 3 && o.z() == BOX.minZ() + 20);
        refusedOnlyBy("gallery", withOps(noRail));
    }

    @Test
    void theOutRuleWantsOutBelowTheBottomFloor() {
        ArenaSite s = good();
        refusedOnlyBy("out", with(s, BOX, s.plan().palette(), s.plan().ops(), s.layout().spawns(), 184));
    }

    @Test
    void everyRuleIsNamed() {
        assertEquals(List.of("box", "ops", "palette", "floors", "walls", "footprint", "area", "pieces", "spawns",
                "gallery", "out"), ArenaValidator.RULES, "the rules, each tested above");
    }
}
