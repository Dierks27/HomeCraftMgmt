package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.admin.GenArgs;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanShift;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Sky Rings edition recalled into Classic Sky Rings is proven again where it will stand, on the real
 * engine: the Classic stands outside the range the elytra sim was made in, so the moved lap is flown
 * again by {@code RingsValidator} on the planner thread before a block is set, and an edition made by
 * Sky Rings v2 is refused plainly at once and stays in the archive (the want is forgotten, not tried
 * again). Before the fix a recall into the Classic ran only the shared checks and built either one.
 */
class RingsRecallTest {

    private static final String SLOT = "fresh_rings";
    private static final String CLASSIC = "fresh_classic_rings";

    private Host host;
    private FakePlanner rings;
    private Map<String, Planner> planners;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(on(1), SLOT);
        rings = new FakePlanner(Slots.RINGS);
        planners = new LinkedHashMap<>();
        planners.put(Slots.RINGS, rings);
        for (String id : List.of(Slots.PARKOUR, Slots.GOLF, Slots.BOAT, Slots.DROPPER)) {
            planners.put(id, new FakePlanner(id));
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private static long on(int n) {
        LocalDate d = LocalDate.of(2026, 9, 29).plusDays(n - 1);
        return GenKit.at(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), 4, 0) + 40_000;
    }

    private void drive(int seconds) {
        for (int s = 0; s < seconds; s++) {
            for (int t = 0; t < 20; t++) {
                gen.tick();
                host.now += 50;
            }
            gen.check();
        }
    }

    /** Two days of Sky Rings made by planner {@code algo}: RINGS-1 is in the archive and no longer up. */
    private void twoDays(int algo) {
        rings.algo = algo;
        host.now = on(1);
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
        drive(70);
        for (int s = 0; s < 600 && gen.liveTag(SLOT) == null; s++) {
            drive(1);
        }
        drive(15);
        GenTag first = gen.liveTag(SLOT);
        assertNotNull(first, "day 1's Sky Rings is up");
        assertEquals(algo, first.algo(), "made by planner " + algo);
        host.now = on(2);
        drive(35);
        assertNotNull(gen.liveTag(SLOT), "and day 2's");
        assertFalse(first.editionKey().equals(gen.liveTag(SLOT).editionKey()), "a new one: RINGS-1 is archived");
    }

    private String heard() {
        return String.join("\n", said);
    }

    private long classicBlocks() {
        return host.world().count(Slots.CLASSIC_RINGS.half('A')) + host.world().count(Slots.CLASSIC_RINGS.half('B'));
    }

    @Test
    void aV2EditionIsRefusedPlainlyAndStaysInTheArchive() throws Exception {
        twoDays(2);
        gen.recall(null, null, GenArgs.which("RINGS-1"), GenArgs.DAYS_DEFAULT, true, said::add);
        for (int s = 0; s < 600 && !heard().contains("stays in the archive"); s++) {
            drive(1);
        }
        assertTrue(heard().contains("made by Sky Rings v2 (planner 2); it stays in the archive"),
                "the validator's own plain line: " + heard());
        assertNull(gen.liveTag(CLASSIC), "nothing is up");
        assertEquals(0L, classicBlocks(), "and not a block is set");
        assertNull(host.store.meta(GenAdminKeys.recall(CLASSIC)), "the want is forgotten, not tried again");
        assertTrue(host.plannerQueue.isEmpty(), "and nothing waits on the planner thread");
    }

    @Test
    void aV3EditionIsFlownAgainWhereItStandsBeforeABlockIsSet() {
        twoDays(3);
        host.holdPlans = true;
        gen.recall(null, null, GenArgs.which("RINGS-1"), GenArgs.DAYS_DEFAULT, true, said::add);
        for (int s = 0; s < 600 && host.plannerQueue.isEmpty(); s++) {
            drive(1);
        }
        assertFalse(host.plannerQueue.isEmpty(), "the moved lap's check waits on the planner thread: " + heard());
        assertEquals(0L, classicBlocks(), "and not a block is set meanwhile");
        assertNull(gen.liveTag(CLASSIC), "nothing is up");

        // a v3 row whose plan is no elytra course (the test planner's blocks): the validator refuses it there
        host.holdPlans = false;
        host.runPlans();
        for (int s = 0; s < 120 && !heard().contains("refused"); s++) {
            drive(1);
        }
        assertTrue(heard().contains("its plan was refused") && heard().contains("the plan isn't an elytra course"),
                "flown by the validator where it would stand, and refused: " + heard());
        assertNull(gen.liveTag(CLASSIC), "nothing is up");
        assertEquals(0L, classicBlocks(), "and not a block is set");
    }

    @Test
    void movedSkyRingsPlansGoThroughTheValidator() {
        Box classic = Slots.CLASSIC_RINGS.half('A');
        Plan v2 = GenKit.plan(Slots.SKY_RINGS, Slots.SKY_RINGS.half('A'), 7L, 2);
        Plan moved = PlanShift.to(v2, classic);
        assertTrue(PlanCheck.refusedForGood(moved, Slots.CLASSIC_RINGS), "a v2 edition can never be proven there");
        assertEquals(List.of("made by Sky Rings v2 (planner 2); it stays in the archive"),
                PlanCheck.movedProblems(moved, Slots.CLASSIC_RINGS, "easy"), "one plain line");
        assertFalse(PlanCheck.movedProblems(moved, Slots.CLASSIC_RINGS).isEmpty(), "the tier read from its course");
        Plan v3 = GenKit.plan(Slots.SKY_RINGS, Slots.SKY_RINGS.half('A'), 7L, 3);
        assertFalse(PlanCheck.refusedForGood(v3, Slots.CLASSIC_RINGS), "a v3 one is flown");
        assertFalse(PlanCheck.movedProblems(PlanShift.to(v3, classic), Slots.SKY_RINGS, "easy").isEmpty(),
                "and a v3 plan that is no elytra course is refused there");
    }
}
