package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Dropper mix set with the mix command wins over config.yml, so revision 25's move of the file's EEMMH to
 * EEMHX never reaches it. A stored mix with no X, where the shipped mix has the Long Drop, is said as one WARN
 * at each start, naming the command that sets the shipped mix; one with an X, and the Easy Dropper's own
 * mixes, say nothing. Before the fix only the migration's INFO line hinted at it.
 */
class DropperMixCommandWarnTest {

    private static final String SLOT = "fresh_dropper";
    private static final String WARN = "fresh_dropper uses the mix EEMMH you set with the mix command, not the"
            + " shipped EEMHX, so it has no Long Drop: /hcm games gen mix fresh_dropper EEMHX";

    private Host host;
    private Map<String, Planner> planners;
    private GenService gen;

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, SLOT, "fresh_dropper_easy");
        planners = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.GOLF, Slots.BOAT, Slots.DROPPER)) {
            planners.put(id, new FakePlanner(id));
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (gen != null) {
            gen.stop();
        }
        host.connection.close();
    }

    private void boot() {
        gen = new GenService(host, planners);
        gen.start();
    }

    private String said() {
        return String.join("\n", host.logs.stream().map(r -> r.getMessage()).toList());
    }

    @Test
    void anEemmhSetByCommandIsWarnedOnceAStart() throws Exception {
        host.store.meta(GenAdminKeys.tier(SLOT), "EEMMH");
        boot();
        assertEquals(1, host.logged(Level.WARNING, WARN), "one WARN naming the command: " + said());
        gen.worldsReady();
        gen.check();
        gen.check();
        assertEquals(1, host.logged(Level.WARNING, WARN), "and not again before the next start");
    }

    @Test
    void aMixWithTheLongDropOrNoneSetSaysNothing() throws Exception {
        boot();
        assertEquals(0, host.logged(Level.WARNING, "you set with the mix command"), "nothing set: " + said());
        gen.stop();
        host.store.meta(GenAdminKeys.tier(SLOT), "EEMHX");
        host.store.meta(GenAdminKeys.tier("fresh_dropper_easy"), "EMH");
        boot();
        assertEquals(0, host.logged(Level.WARNING, "you set with the mix command"), "an X, and the Easy Dropper: "
                + said());
    }

    @Test
    void onlyAMixWithoutAnXWhereTheShippedOneHasItLosesTheLongDrop() {
        assertTrue(GenService.lostLongDrop(Slots.FRESH_DROPPER, "EEMMH"));
        assertFalse(GenService.lostLongDrop(Slots.FRESH_DROPPER, "EEMHX"));
        assertFalse(GenService.lostLongDrop(Slots.FRESH_DROPPER, ""));
        assertFalse(GenService.lostLongDrop(Slots.EASY_DROPPER, "EEE"));
        assertFalse(GenService.lostLongDrop(Slots.SKY_RINGS, "easy"));
    }
}
