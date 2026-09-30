package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlanShift;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.games.gen.parkour.ParkourPlanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-2 audit, G2 #2: a pick whose seed is the live one still goes up as the course that was tried.
 * The same seed is not the same course: a medium or hard parkour layout is shaped by
 * {@code trials.fall_depth} (up to 6), and the boot check keeps a live layout made at a shallower
 * depth, since a deeper setting only lowers the fall floor. So when the owner previews the live seed
 * for next week at the new depth, tries it and chooses it, the change must put up that layout, not
 * restamp the old one under the new set's key while the tried one still stands in the spare half.
 *
 * <p>The planner here is {@link GenKit}'s, with its pads lifted by the design depth
 * ({@link ParkourPlanner#designDepth}), and a rederive that, like the real one, tries the live
 * depth and then 6 down to 1 until the hash matches.
 */
class ChosenLiveSeedTest {

    private static final String SLOT = "fresh_parkour_easy";
    private static final long MON_28_SEP = 20724;
    private static final long MON_5_OCT = MON_28_SEP + 7;

    private Host host;
    private Map<String, Planner> planners;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    /** GenKit's plan, its pads lifted by the depth it is shaped for: another depth, another layout. */
    private static Plan shaped(PlanInput in, int algo) {
        Plan p = GenKit.plan(in.slot(), in.half(), in.seed(), algo);
        int design = ParkourPlanner.designDepth(in.tierOrMix(), in.fallDepth());
        Plan lifted = PlanShift.by(p, 0, design, 0);
        return Plan.of(p.slot(), p.algo(), p.seed(), p.half(), lifted.palette(), lifted.ops(), lifted.signs(),
                lifted.keepClear(), lifted.course(), lifted.summary(), lifted.work());
    }

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, SLOT);
        host.settings = medium(GenKit.weekly(SLOT));
        planners = new LinkedHashMap<>();
        planners.put(Slots.PARKOUR, new Planner() {
            @Override
            public String id() {
                return Slots.PARKOUR;
            }

            @Override
            public int algo() {
                return 1;
            }

            @Override
            public Plan plan(PlanInput in) {
                return shaped(in, algo());
            }

            @Override
            public Plan rederive(PlanInput in, GenTag tag) throws GenFailed {
                List<Integer> depths = new ArrayList<>(List.of(in.fallDepth()));
                for (int d = ParkourPlanner.FALL_DESIGN; d >= 1; d--) {
                    depths.add(d);
                }
                for (int d : depths) {
                    Plan p = shaped(new PlanInput(in.slot(), in.half(), in.halfId(), tag.day(), tag.reroll(), tag.seed(),
                            in.tierOrMix(), d, in.workBudget(), in.cancelled()), algo());
                    if (p.hash().equals(tag.planHash())) {
                        return p; // a deeper setting only lowers the floor: the old layout is kept
                    }
                }
                throw new GenFailed("no depth makes " + tag.planHash());
            }
        });
        for (String id : List.of(Slots.RINGS, Slots.GOLF, Slots.BOAT)) {
            planners.put(id, new FakePlanner(id));
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private static DailySettings medium(DailySettings d) {
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        for (DailySettings.SlotConfig c : d.slots()) {
            slots.add(c.id().equals(SLOT) ? c.withTierOrMix("medium") : c);
        }
        return d.withSlots(slots);
    }

    private void boot() {
        if (gen != null) {
            gen.stop();
        }
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
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

    private void stepUntil(java.util.function.BooleanSupplier done, int max) {
        for (int t = 1; t <= max && !done.getAsBoolean(); t++) {
            gen.tick();
            host.now += 50;
            if (t % 20 == 0) {
                gen.check();
            }
        }
    }

    private GenTag tag() {
        return gen.liveTag(SLOT);
    }

    private String heard() {
        return String.join("\n", said);
    }

    @Test
    void aPickOfTheLiveSeedTriedAtANewFallDepthGoesUpAsTriedNotAsTheOldLayout() throws Exception {
        host.fallDepth = 4;
        boot();
        drive(70);
        GenTag week = tag();
        assertNotNull(week, "this week's set is up, shaped for fall_depth 4");
        assertEquals('A', week.half(), "in half A");

        host.fallDepth = 6; // the owner deepens the fall and restarts
        host.now += 60_000;
        boot();
        drive(10);
        assertEquals(week.planHash(), tag().planHash(), "the boot check keeps the depth-4 layout (a deeper floor only"
                + " makes it easier)");

        said.clear();
        gen.previewNext(SLOT, GenSeed.hex(week.seed()), said::add); // the same course next week, tried at 6
        drive(10);
        SlotState.Preview pv = gen.slot(SLOT).preview;
        assertNotNull(pv, "the preview stands: " + heard());
        assertEquals(6, pv.fallDepth(), "made at the new depth");
        said.clear();
        gen.choose(SLOT, false, said::add);
        assertNotNull(host.store.meta(GenAdminKeys.choose(SLOT)), "chosen: " + heard());
        String tried = pv.plan().hash();
        assertNotEquals(week.planHash(), PlanShift.to(pv.plan(), gen.slot(SLOT).half('A')).hash(),
                "the tried layout is another course than the live one, wherever it stands");

        host.now = GenKit.at(2026, 10, 5, 4, 0) + 40_000;
        stepUntil(() -> tag() != null && tag().day() == MON_5_OCT, 20 * 120);
        GenTag next = tag();
        assertEquals(MON_5_OCT, next.day(), "next week's set is up");
        assertEquals(week.seed(), next.seed(), "on the chosen seed");
        assertEquals(tried, next.planHash(), "and it is the layout that was tried, not the depth-4 one restamped");
        assertEquals(pv.half(), next.half(), "where the tried preview stood");
        assertNull(gen.slot(SLOT).preview, "the tried preview is the live course now, not a preview left beside it");
        assertTrue(String.join("\n", gen.status(SLOT)).contains("this set: chosen seed " + GenSeed.hex(week.seed())),
                "status names the pick: " + gen.status(SLOT));
    }

    @Test
    void aPlainPinOfTheLiveSeedIsStillOnlyRestampedAtTheChange() {
        host.fallDepth = 4;
        boot();
        drive(70);
        GenTag week = tag();
        said.clear();
        gen.pin(SLOT, "live", 0, said::add);
        long writes = host.world().writes;
        host.now = GenKit.at(2026, 10, 5, 4, 0) + 40_000;
        stepUntil(() -> tag() != null && tag().day() == MON_5_OCT, 20 * 120);
        assertEquals(MON_5_OCT, tag().day(), "next week's set is up: " + heard());
        assertEquals(week.planHash(), tag().planHash(), "a pin keeps its course");
        assertEquals(week.half(), tag().half(), "restamped where it stands");
        assertEquals(writes, host.world().writes, "no block written");
    }
}
