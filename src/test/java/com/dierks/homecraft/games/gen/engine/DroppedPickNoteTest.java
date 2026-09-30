package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.gui.games.daily.FreshAdmin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-2 audit, G2 #3: a pick dropped by a config or schedule change says so where the owner looks,
 * and for as long as it matters. With the shipped restart at the change, the boot drops a pick whose
 * tier was edited in config.yml, and a minute later the set's own build flipped, which cleared the
 * one status line: on Monday morning the pick had simply gone, with one console line from 4:01 as the
 * only trace, and nothing on the admin tools. The note now lives with the slot in {@code hcm_meta}
 * (so a restart keeps it), stays through the set it was for, and is gone once that set is over or
 * the admin picks again.
 */
class DroppedPickNoteTest {

    private static final String SLOT = "fresh_parkour_easy";
    private static final long MON_5_OCT = 20731;
    private static final long MON_12_OCT = MON_5_OCT + 7;
    private static final String PICK_HEX = "0000000000003f2a";
    private static final String NOTE = "pick for Mon 5 Oct-Sun 11 Oct dropped";

    private Host host;
    private Map<String, Planner> planners;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, SLOT);
        host.settings = GenKit.weekly(SLOT);
        planners = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.GOLF, Slots.BOAT)) {
            planners.put(id, new FakePlanner(id));
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
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

    private String status() {
        return String.join("\n", gen.status(SLOT));
    }

    private static DailySettings withTier(DailySettings d, String tier) {
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        for (DailySettings.SlotConfig c : d.slots()) {
            slots.add(c.id().equals(SLOT) ? c.withTierOrMix(tier) : c);
        }
        return d.withSlots(slots);
    }

    /** The week is up, and next week's course is picked from a tried preview. */
    private void picked() throws Exception {
        boot();
        drive(70);
        assertNotNull(tag(), "this week's set is up");
        gen.previewNext(SLOT, PICK_HEX, said::add);
        drive(10);
        gen.choose(SLOT, false, said::add);
        assertNotNull(host.store.meta(GenAdminKeys.choose(SLOT)), "picked: " + said);
    }

    @Test
    void aPickDroppedByTheRestartAtTheChangeIsStillShownAfterTheSetsOwnBuildAndAnotherRestart() throws Exception {
        picked();
        host.settings = withTier(host.settings, "hard"); // edited in config.yml over the weekend, no reload

        host.now = GenKit.at(2026, 10, 5, 4, 0) + 5_000; // the Monday 04:00 restart, at the change
        boot();
        drive(2);
        assertNull(host.store.meta(GenAdminKeys.choose(SLOT)), "the pick no longer fits: dropped");
        assertEquals(1, host.logged(Level.WARNING, "is dropped"), "with one console line");
        stepUntil(() -> tag() != null && tag().day() == MON_5_OCT, 20 * 180);
        assertEquals(MON_5_OCT, tag().day(), "the set's own course went up a minute later");
        assertTrue(status().contains(NOTE), "status still says the pick was dropped, after the set's own flip: "
                + status());
        assertTrue(status().contains("tried as easy"), "and why: " + status());
        GenOps.Tools tools = gen.tools(SLOT);
        assertNotNull(tools.dropped(), "the admin tools know it too");
        assertTrue(tools.dropped().contains("Your pick for Mon 5 Oct-Sun 11 Oct (seed " + PICK_HEX + ") was dropped")
                && tools.dropped().contains("tried as easy"), tools.dropped());
        assertTrue(FreshAdmin.itemName(tools).contains("your pick was dropped"), "the course screen's item NAME says"
                + " so (Bedrock): " + FreshAdmin.itemName(tools));
        assertTrue(FreshAdmin.headerName(Slots.DAILY_PARKOUR_EASY, tools).contains("your pick was dropped"),
                "and the tools screen's header NAME: " + FreshAdmin.headerName(Slots.DAILY_PARKOUR_EASY, tools));
        String lore = String.join(" ", FreshAdmin.state(tools));
        assertTrue(lore.contains("was dropped") && lore.contains("choose again"), "and its lore says why and what to"
                + " do: " + lore);

        host.now = GenKit.at(2026, 10, 5, 16, 0) + 5_000; // the afternoon restart
        boot();
        drive(5);
        assertTrue(status().contains(NOTE), "a restart keeps it: " + status());

        host.now = GenKit.at(2026, 10, 12, 4, 0) + 40_000;
        stepUntil(() -> tag() != null && tag().day() == MON_12_OCT, 20 * 180);
        drive(2);
        assertFalse(status().contains("dropped"), "once the set it was for is over, it is old news: " + status());
        assertNull(gen.tools(SLOT).dropped(), "on the tools too");
        assertNull(host.store.meta(GenAdminKeys.dropped(SLOT)), "and it is gone from the database");
        assertEquals(1, host.logged(Level.WARNING, "is dropped"), "and it was said once in the console");
    }

    @Test
    void aNewPickClearsTheNoteOfTheOneThatWasDropped() throws Exception {
        picked();
        host.settings = withTier(host.settings, "hard");
        host.now += 60_000;
        boot(); // a restart mid-week: the pick is dropped
        drive(2);
        assertTrue(status().contains(NOTE), "status says so: " + status());

        said.clear();
        gen.previewNext(SLOT, "5eed", said::add);
        drive(10);
        gen.choose(SLOT, false, said::add);
        assertFalse(status().contains("dropped"), "a new pick: the old note goes: " + status());
        assertNull(gen.tools(SLOT).dropped(), "on the tools too");
        host.now += 60_000;
        boot();
        drive(2);
        assertFalse(status().contains("dropped"), "and it doesn't come back after a restart: " + status());
        assertTrue(status().contains("next set: chosen seed 0000000000005eed"), status());
    }
}
