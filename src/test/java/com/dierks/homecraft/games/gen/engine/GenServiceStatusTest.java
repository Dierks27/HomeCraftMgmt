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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ice Boat's status line with the server's own ice values (MOUNTAIN-V21-SPEC D14), with the engine, a fake
 * clock and world and the real database: {@code /hcm games gen status fresh_boat} reads
 * {@link GenHost#iceFacts} and says the friction the boat planner's model assumes is the server's.
 *
 * <p>Pinned here: the values the model assumes (0.98 / 0.98 / 0.989 / 0.6 / 0.6) give the grey line; one off by
 * more than 0.0005 gives the yellow line with what the model assumes after it; a host that can't read them (the
 * test host) gives no line; and no other course has one.
 */
class GenServiceStatusTest {

    private static final String SLOT = Slots.ICE_BOAT.id();
    private static final String MATCHES = "  &7ice on this server: packed 0.98, plain 0.98, blue 0.989; snow 0.6,"
            + " smooth sandstone 0.6";

    private Host host;
    private GenService gen;

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000);
        host.settings = GenKit.fast(GenKit.weekly());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (gen != null) {
            gen.stop();
        }
        host.connection.close();
    }

    private void boot() {
        Map<String, Planner> planners = new LinkedHashMap<>();
        for (String g : List.of(Slots.PARKOUR, Slots.RINGS, Slots.GOLF, Slots.BOAT)) {
            planners.put(g, new FakePlanner(g));
        }
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
        for (int t = 0; t < 40; t++) {
            gen.tick();
            host.now += 50;
        }
        gen.check();
    }

    private List<String> iceLines(String slot) {
        return gen.status(slot).stream().filter(l -> l.contains("ice on this server")).toList();
    }

    @Test
    void theModelsValuesGiveTheGreyLine() {
        host.ice = new float[]{0.98f, 0.98f, 0.989f, 0.6f, 0.6f};
        boot();
        assertEquals(List.of(MATCHES), iceLines(SLOT), "" + gen.status(SLOT));
    }

    @Test
    void aValueOffTheModelGivesTheYellowLineWithWhatTheModelAssumes() {
        host.ice = new float[]{0.98f, 0.98f, 0.99f, 0.6f, 0.6f};
        boot();
        assertEquals(List.of("  &eice on this server: packed 0.98, plain 0.98, blue 0.99; snow 0.6, smooth sandstone"
                + " 0.6 - the planner's model assumes 0.98 / 0.989 / 0.6"), iceLines(SLOT), "" + gen.status(SLOT));
    }

    @Test
    void aHostThatCantReadThemGivesNoLineAndNoOtherCourseHasOne() {
        boot();
        assertEquals(List.of(), iceLines(SLOT), "the test host's null: " + gen.status(SLOT));
        host.ice = new float[]{0.98f, 0.98f, 0.989f, 0.6f, 0.6f};
        assertEquals(List.of(MATCHES), iceLines(SLOT), "read at every status");
        assertEquals(List.of(), iceLines("fresh_parkour"), "only Ice Boat's status: " + gen.status("fresh_parkour"));
        assertTrue(iceLines(null).isEmpty(), "and not the overview");
    }

    @Test
    void theLineWithinHalfAThousandthIsTheModels() {
        assertEquals(MATCHES.trim(), GenService.iceLine(new float[]{0.9804f, 0.98f, 0.9886f, 0.6f, 0.6004f}),
                "within 0.0005: the model's (shown to three places)");
        assertTrue(GenService.iceLine(new float[]{0.98f, 0.98f, 0.989f, 0.6f, 0.601f}).startsWith("&e"),
                "0.001 off is off");
        assertTrue(GenService.iceLine(new float[]{0.98f, Float.NaN, 0.989f, 0.6f, 0.6f}).startsWith("&e"),
                "a value that isn't a number is off");
        assertNull(GenService.iceLine(null));
        assertNull(GenService.iceLine(new float[]{0.98f}), "not all five: no line");
    }
}
