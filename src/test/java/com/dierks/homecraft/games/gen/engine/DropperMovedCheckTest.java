package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.admin.GenArgs;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.dropper.DropperPlanner;
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
 * A moved Dropper is proven again where it will stand on the planner thread, not the main one (the
 * WP-D review's #4; tens of milliseconds a check), on the real engine with the real Dropper planner:
 * a recall into Classic Dropper and a keep into a plot each wait for the planner thread before a
 * single block is set, and build once it answers.
 */
class DropperMovedCheckTest {

    private static final String SLOT = "fresh_dropper_easy";
    private static final String CLASSIC = "fresh_classic_dropper";

    private Host host;
    private Map<String, Planner> planners;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(on(1), SLOT);
        planners = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.GOLF, Slots.BOAT)) {
            planners.put(id, new FakePlanner(id));
        }
        planners.put(Slots.DROPPER, new DropperPlanner());
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

    /** Two days of Easy Dropper: EDROP-1 is in the archive and no longer up. */
    private void twoDays() {
        host.now = on(1);
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
        drive(70);
        drive(15);
        GenTag first = gen.liveTag(SLOT);
        assertNotNull(first, "day 1's Easy Dropper is up");
        host.now = on(2);
        drive(35);
        assertNotNull(gen.liveTag(SLOT), "and day 2's");
        assertFalse(first.editionKey().equals(gen.liveTag(SLOT).editionKey()), "a new one: EDROP-1 is archived");
    }

    private String heard() {
        return String.join("\n", said);
    }

    @Test
    void aRecalledDropperIsProvenOnThePlannerThreadBeforeABlockIsSet() {
        twoDays();
        host.holdPlans = true;
        gen.recall(null, null, GenArgs.which("EDROP-1"), GenArgs.DAYS_DEFAULT, true, said::add);
        drive(20);
        assertFalse(host.plannerQueue.isEmpty(), "the moved dropper's check waits on the planner thread: " + heard());
        Box a = Slots.CLASSIC_DROPPER.half('A');
        Box b = Slots.CLASSIC_DROPPER.half('B');
        assertEquals(0, host.world().count(a) + host.world().count(b), "and not a block is set meanwhile");
        assertNull(gen.liveTag(CLASSIC), "nothing is up");

        host.holdPlans = false;
        host.runPlans();
        drive(60);
        GenTag c = gen.liveTag(CLASSIC);
        assertNotNull(c, "once it answers, the recall is built: " + heard());
        assertTrue(host.world().count(Slots.CLASSIC_DROPPER.half(c.half())) > 0, "block for block");
    }

    @Test
    void aKeptDropperIsProvenOnThePlannerThreadBeforeABlockIsSet() throws Exception {
        twoDays();
        host.holdPlans = true;
        gen.keep(SLOT, GenArgs.which("EDROP-1"), "my_drop", null, false, true, said::add);
        Box plot = host.settings.archive().keep().plot(1);
        for (int s = 0; s < 120 && host.plannerQueue.isEmpty(); s++) {
            drive(1);
        }
        assertFalse(host.plannerQueue.isEmpty(), "the kept dropper's check waits on the planner thread: " + heard());
        assertEquals(0, host.world().count(plot), "and not a block is set in its plot meanwhile");
        assertNull(host.dao.course("my_drop"), "nothing is registered");

        host.holdPlans = false;
        host.runPlans();
        for (int s = 0; s < 120 && host.dao.course("my_drop") == null; s++) {
            drive(1);
        }
        assertNotNull(host.dao.course("my_drop"), "once it answers, the keep is built and registered: " + heard());
        assertTrue(host.world().count(plot) > 0, "in its plot");
    }
}
