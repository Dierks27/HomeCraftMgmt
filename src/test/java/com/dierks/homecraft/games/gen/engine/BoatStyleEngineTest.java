package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatStyle;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine picks a Mountain Run v2 week's seed by Ice Boat's {@code style} (MOUNTAIN-V2-SPEC §5.1, §13 item 2,
 * red-team F05), with the engine, a fake clock and world and the real database, at the shipped weekly cadence,
 * Ice Boat in its v4 480 x 176 x 640 halves.
 *
 * <p>Pinned here, at the four seed sites: the week's build is the first of its seeds with the configured
 * style ({@code road} or {@code slalom}), the Winding Road every week while Race Night is on with
 * {@code random}, and the week's own seed (unchanged) with {@code random} and no Race Night; {@code plan} and
 * {@code plan next} show the seed a build would use; {@code preview} (now and {@code next}) without a seed is
 * of the style a build would have, or of the {@code style:} asked; a typed seed is used as given, with a note
 * when it isn't the style asked; and {@code style:} on another course is refused with a line that says why.
 */
class BoatStyleEngineTest {

    private static final String SLOT = Slots.ICE_BOAT.id();
    /** Monday 28 September 2026: the week the tests start in. */
    private static final long MON_28_SEP = 20724;
    private static final Pattern SEED = Pattern.compile("seed ([0-9a-f]{16})");

    private Host host;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

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

    private void style(BoatStyle s, boolean on) {
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        for (DailySettings.SlotConfig c : host.settings.slots()) {
            slots.add(c.id().equals(SLOT) ? c.withStyle(s).withEnabled(on) : c);
        }
        host.settings = host.settings.withSlots(slots);
    }

    private void boot() {
        Map<String, Planner> planners = new LinkedHashMap<>();
        for (String g : List.of(Slots.PARKOUR, Slots.RINGS, Slots.GOLF, Slots.BOAT)) {
            planners.put(g, new FakePlanner(g));
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

    private long secret() throws Exception {
        return host.store.secret();
    }

    /** The last seed the replies named. */
    private long lastSeed() {
        Long seed = null;
        for (String line : said) {
            Matcher m = SEED.matcher(line);
            while (m.find()) {
                seed = GenSeed.parse(m.group(1));
            }
        }
        assertNotNull(seed, "a reply named a seed: " + said);
        return seed;
    }

    private GenTag builtWith(BoatStyle style, boolean raceNight) throws Exception {
        style(style, true);
        host.raceNight = raceNight;
        boot();
        drive(90);
        GenTag live = gen.liveTag(SLOT);
        assertNotNull(live, "fixture: this week's Ice Boat went up");
        assertEquals(MON_28_SEP, live.day(), "fixture: this week's");
        return live;
    }

    @Test
    void styleRoadBuildsTheWindingRoad() throws Exception {
        GenTag live = builtWith(BoatStyle.ROAD, false);
        assertEquals(BoatStyle.ROAD, BoatStyle.of(live.seed()), "style: road: the week's course is a Winding Road");
        assertEquals(StyleSeed.seed(secret(), 7, MON_28_SEP, SLOT, 0, BoatStyle.ROAD), live.seed(),
                "the first of the week's seeds of that style");
    }

    @Test
    void styleSlalomBuildsTheSlalomEvenWithRaceNightOn() throws Exception {
        GenTag live = builtWith(BoatStyle.SLALOM, true);
        assertEquals(BoatStyle.SLALOM, BoatStyle.of(live.seed()), "style: slalom is what the owner asked for");
        assertEquals(StyleSeed.seed(secret(), 7, MON_28_SEP, SLOT, 0, BoatStyle.SLALOM), live.seed(),
                "the first of the week's seeds of that style");
    }

    @Test
    void randomWithRaceNightOnIsTheWindingRoad() throws Exception {
        GenTag live = builtWith(null, true);
        assertEquals(BoatStyle.ROAD, BoatStyle.of(live.seed()), "F05: Race Night on, random: the Winding Road");
        assertEquals(StyleSeed.seed(secret(), 7, MON_28_SEP, SLOT, 0, BoatStyle.ROAD), live.seed(), "its first road seed");
    }

    @Test
    void randomWithNoRaceNightIsTheWeeksOwnSeedAsBefore() throws Exception {
        GenTag live = builtWith(null, false);
        assertEquals(GenSeed.seed(secret(), 7, MON_28_SEP, SLOT, 0), live.seed(), "the week's own seed, unchanged");
    }

    @Test
    void planShowsTheSeedABuildWouldUse() throws Exception {
        style(BoatStyle.SLALOM, false);
        boot();
        drive(2);
        gen.plan(SLOT, null, said::add);
        drive(1);
        assertEquals(StyleSeed.seed(secret(), 7, MON_28_SEP, SLOT, 0, BoatStyle.SLALOM), lastSeed(),
                "plan: this set's Slalom seed: " + said);
        said.clear();
        gen.plan(SLOT, "next", said::add);
        drive(1);
        assertEquals(StyleSeed.seed(secret(), 7, MON_28_SEP + 7, SLOT, 0, BoatStyle.SLALOM), lastSeed(),
                "plan next: next set's: " + said);
        said.clear();
        String typed = GenSeed.hex(MountainSeeds.ROAD);
        gen.plan(SLOT, typed, said::add);
        drive(1);
        assertEquals(MountainSeeds.ROAD, lastSeed(), "a typed seed is used as given");
    }

    @Test
    void aPreviewIsOfTheStyleABuildWouldHaveOrTheOneAsked() throws Exception {
        style(null, false);
        host.raceNight = true;
        boot();
        drive(70);
        gen.preview(SLOT, null, said::add);
        long seed = lastSeed();
        assertEquals(StyleSeed.seed(secret(), 7, MON_28_SEP, SLOT, 1, BoatStyle.ROAD), seed,
                "Race Night on: the next reroll's first Winding Road seed: " + said);
        assertTrue(String.join("\n", said).contains("It is the Winding Road."), "the reply names the style: " + said);
        drive(10);
        said.clear();
        gen.preview(SLOT, null, BoatStyle.SLALOM, said::add);
        assertEquals(StyleSeed.seed(secret(), 7, MON_28_SEP, SLOT, 1, BoatStyle.SLALOM), lastSeed(),
                "style:slalom: the next reroll's first Slalom seed: " + said);
        assertTrue(String.join("\n", said).contains("It is the Slalom."), "said: " + said);
        drive(10);
        said.clear();
        gen.previewNext(SLOT, null, BoatStyle.SLALOM, said::add);
        assertEquals(BoatStyle.SLALOM, BoatStyle.of(lastSeed()), "preview next style:slalom: a Slalom candidate");
        drive(10);
        said.clear();
        gen.previewNext(SLOT, null, null, said::add);
        assertEquals(BoatStyle.ROAD, BoatStyle.of(lastSeed()), "preview next: of the style the next build would have");
    }

    @Test
    void aTypedSeedIsUsedAsGivenWithANote() throws Exception {
        style(null, false);
        boot();
        drive(70);
        gen.preview(SLOT, GenSeed.hex(MountainSeeds.ROAD), BoatStyle.SLALOM, said::add);
        String all = String.join("\n", said);
        assertEquals(MountainSeeds.ROAD, lastSeed(), "the typed seed: " + all);
        assertTrue(all.contains("It is the Winding Road (that seed is used as typed, so it isn't the Slalom)."),
                "and a note that it isn't the style asked: " + all);
    }

    @Test
    void styleOnAnotherCourseOrTheOldBoxIsRefused() throws Exception {
        style(null, false);
        boot();
        drive(70);
        gen.preview("fresh_parkour", null, BoatStyle.ROAD, said::add);
        assertEquals(List.of("&cOnly Ice Boat has styles (style:road or style:slalom)."), said, "another course");
        assertNull(gen.slot("fresh_parkour").preview, "and nothing was queued");
        said.clear();
        gen.previewNext("fresh_golf", null, BoatStyle.SLALOM, said::add);
        assertEquals(List.of("&cOnly Ice Boat has styles (style:road or style:slalom)."), said, "preview next too");
    }

    /** Seeds of each style, found once. */
    static final class MountainSeeds {
        static final long ROAD = first(BoatStyle.ROAD);
        static final long SLALOM = first(BoatStyle.SLALOM);

        private static long first(BoatStyle s) {
            long seed = 0x5eed0000L;
            while (BoatStyle.of(seed) != s) {
                seed++;
            }
            return seed;
        }
    }
}
