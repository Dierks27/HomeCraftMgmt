package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A course whose origin or half_gap config can't read ({@code SlotConfig#placed} false), on the real
 * engine: it stays where it was claimed and is off, even with an admin's {@code on}, so it is never
 * read as moved (no reroll at a spot nobody chose, the built course never orphaned), and once config
 * reads again at the same spot it simply opens there, as it was. What a server that kept 0.35's spots
 * (a 32-block gap) needs when an owner's edit goes wrong.
 */
class UnplacedSlotEngineTest {

    private static final String SLOT = "fresh_parkour_easy";
    private static final Slots.Def DEF = Slots.DAILY_PARKOUR_EASY;
    private static final String W = GenKit.WORLD;
    /** Where it was built: far from everything, at 0.35's gap. */
    private static final int[] BUILT = {16_384, 160, 16_384};
    private static final int GAP = Slots.LEGACY_HALF_GAP;

    private Host host;
    private Map<String, Planner> planners;
    private GenService gen;

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, SLOT);
        planners = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.GOLF, Slots.BOAT, Slots.DROPPER)) {
            planners.put(id, new FakePlanner(id));
        }
        change(c -> c.withOrigin(BUILT).withHalfGap(GAP));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (gen != null) {
            gen.stop();
        }
        host.connection.close();
    }

    private void change(UnaryOperator<DailySettings.SlotConfig> f) {
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        for (DailySettings.SlotConfig c : host.settings.slots()) {
            slots.add(c.id().equals(SLOT) ? f.apply(c) : c);
        }
        host.settings = host.settings.withSlots(slots);
    }

    /** Config as a bad edit leaves it: the spot can't be read (the placeholder is the shipped one). */
    private void unreadable() {
        change(c -> c.withOrigin(DEF.origin()).withHalfGap(Slots.HALF_GAP).unplaced());
    }

    private void fixed() {
        change(c -> DailySettings.SlotConfig.shipped(DEF).withOrigin(BUILT).withHalfGap(GAP)
                .withDailyClear(c.dailyClear()));
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

    private GenService.SlotReport report() {
        return gen.report().stream().filter(r -> r.id().equals(SLOT)).findFirst().orElseThrow();
    }

    private String logs() {
        return String.join("\n", host.logs.stream().map(r -> r.getLevel() + " " + r.getMessage()).toList());
    }

    private int writtenAtTheShippedSpot() {
        int n = 0;
        for (Box b : Regions.halves(DEF, DEF.origin(), Slots.HALF_GAP)) {
            n += host.world().count(b);
        }
        return n;
    }

    /** Built and open at its spot, and switched on by an admin (so config's switch isn't what holds it). */
    private GenTag built() throws Exception {
        boot();
        drive(70);
        GenTag live = gen.liveTag(SLOT);
        assertNotNull(live, "the course is up");
        assertEquals(Regions.claim(DEF, W, BUILT, GAP), host.store.meta(GenAdminKeys.claim(SLOT)), "claimed at its spot");
        host.store.meta(GenAdminKeys.enabled(SLOT), "true");
        host.logs.clear();
        return live;
    }

    private void assertStaysPut(GenTag live, String when) throws Exception {
        assertFalse(logs().contains(SLOT + " moved from"), when + ": never read as a move: " + logs());
        assertFalse(logs().contains("claimed at another place"), when + ": nor as claimed elsewhere: " + logs());
        assertEquals(Regions.claim(DEF, W, BUILT, GAP), host.store.meta(GenAdminKeys.claim(SLOT)),
                when + ": the claim is left as it is");
        assertTrue(report().claimed(), when + ": still claimed where it stands");
        assertFalse(report().healFailed(), when + ": nothing is given up there");
        assertEquals(live, gen.liveTag(SLOT), when + ": the same course, no reroll");
        assertEquals(0, writtenAtTheShippedSpot(), when + ": nothing is built at the shipped spot");
        assertTrue(gen.inArea(W, BUILT[0], BUILT[1], BUILT[2]), when + ": and its area is still guarded");
    }

    @Test
    void aCourseWhoseSpotCantBeReadStaysWhereItStandsAndIsOffEvenWithAnAdminsOn() throws Exception {
        GenTag live = built();
        unreadable();
        gen.check();
        drive(90);
        assertStaysPut(live, "config can't say where it is");
        GenService.SlotReport r = report();
        assertFalse(r.wanted(), "off, though an admin switched it on: nothing is built while config can't say where");
        assertEquals(GenService.UNPLACED, r.problem(), "and status says why");
        assertTrue(host.logged(Level.SEVERE, GenService.UNPLACED) > 0, "said once: " + logs());

        fixed();
        gen.check();
        drive(60);
        assertStaysPut(live, "the edit fixed");
        assertTrue(report().wanted() && report().verified(), "on again, and open where it stood");
    }

    @Test
    void aStartWithTheSpotUnreadableKeepsItWhereItWasClaimedToo() throws Exception {
        GenTag live = built();
        gen.stop();
        gen = null;
        unreadable();
        boot();
        drive(90);
        assertStaysPut(live, "a start with the bad edit");
        assertFalse(report().wanted(), "off");

        fixed();
        gen.check();
        drive(60);
        assertStaysPut(live, "fixed after that start");
        assertTrue(report().verified(), "healed and open at the spot it was built at");
    }
}
