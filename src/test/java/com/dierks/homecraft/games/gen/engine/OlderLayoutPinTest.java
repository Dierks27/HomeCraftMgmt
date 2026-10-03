package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.admin.GenArgs;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenArchiveDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * A pin across a planner update (CV final gate): after the update every live Fresh course is still the
 * layout the older planner made (Golf of the Week's, Tiny Golf's, Ice Boat's algo 2) until its set ends,
 * and a pin is a seed for today's planner. With the engine, a fake clock and world and the real
 * database, at the shipped weekly cadence: this week's set made by planner v2, then the update to v3.
 *
 * <p>Pinned here: {@code pin <course> live} (or {@code today}) on that layout is refused and stores no
 * pin, saying why (an older planner made it, it can't be made again, it stays until its set ends) and
 * that {@code keep} keeps it for good; so the next set is its own, not "the pinned course" built anew
 * from that seed; once a layout of today's planner is up, {@code pin live} works as before. A typed
 * seed an archived edition of another planner version was made from is pinned, with a warning that the
 * pin makes a new course from that seed and how to have that edition back by a command that is taken:
 * the course up now stays until its set ends and {@code keep <course> current} keeps it (a recall of it is
 * refused); once its set is over, {@code recall <code>} when its Classics slot is on, else (Ice Boat has
 * none, or that slot is off) {@code keep <code>}. Any other seed is pinned as before, with no warning.
 */
class OlderLayoutPinTest {

    private static final String SLOT = "fresh_parkour_easy";
    private static final String BOAT = "fresh_boat";
    /** Monday 28 September 2026: the week the tests start in. */
    private static final long MON_28_SEP = 20724;
    private static final long MON_5_OCT = MON_28_SEP + 7;

    private Host host;
    private FakePlanner parkour;
    private FakePlanner boat;
    private Map<String, Planner> planners;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, SLOT);
        host.settings = GenKit.weekly(SLOT);
        parkour = new FakePlanner(Slots.PARKOUR);
        planners = new LinkedHashMap<>();
        planners.put(Slots.PARKOUR, parkour);
        planners.put(Slots.RINGS, new FakePlanner(Slots.RINGS));
        planners.put(Slots.GOLF, new FakePlanner(Slots.GOLF));
        boat = new FakePlanner(Slots.BOAT);
        planners.put(Slots.BOAT, boat);
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

    private GenTag tag() {
        return gen.liveTag(SLOT);
    }

    private String heard() {
        return String.join("\n", said);
    }

    /** This week's set made by planner v2, then the update: planner v3, with the v2 layout still up and open. */
    private GenTag upgradeWeek() {
        return upgradeWeek(SLOT, parkour);
    }

    private GenTag upgradeWeek(String slot, FakePlanner planner) {
        planner.algo = 2;
        boot();
        drive(70);
        GenTag v2 = gen.liveTag(slot);
        assertNotNull(v2, "fixture: this week's set is up");
        assertEquals(2, v2.algo(), "fixture: made by planner v2");
        planner.algo = 3; // the update
        boot();
        drive(5);
        assertEquals(v2, gen.liveTag(slot), "fixture: after the update the v2 layout is still up");
        assertTrue(gen.live(slot, v2), "fixture: and open (checked structurally)");
        return v2;
    }

    @Test
    void pinLiveOnALayoutAnOlderPlannerMadeIsRefusedAndTheNextSetIsItsOwn() throws Exception {
        GenTag v2 = upgradeWeek();

        gen.pin(SLOT, "live", 0, said::add);
        assertTrue(heard().contains("course up now was made by an older planner (parkour v2; this is v3), so it can't"
                + " be made again after the update"), "refused, saying why: " + heard());
        assertTrue(heard().contains("Nothing was pinned.") && heard().contains("It stays up until its set (Mon 28 Sep-Sun"
                + " 4 Oct) ends"), "that it stays until its set ends: " + heard());
        assertTrue(heard().contains("&e/hcm games gen keep " + SLOT + " current <new-id> confirm"),
                "and how to keep it for good: " + heard());
        assertNull(host.store.meta(GenAdminKeys.pin(SLOT)), "no pin is stored");
        assertNull(gen.slot(SLOT).pin, "none is held");
        said.clear();
        gen.pin(SLOT, "today", 3, said::add);
        assertTrue(heard().contains("made by an older planner"), "'today' is the same word: " + heard());
        assertNull(host.store.meta(GenAdminKeys.pin(SLOT)), "still no pin");
        assertTrue(gen.status(SLOT).stream().noneMatch(l -> l.contains("pinned seed")), "status shows none: "
                + gen.status(SLOT));

        // The next set: its own seed with today's planner, never "the pinned course" built anew from v2's seed.
        host.now = GenKit.at(2026, 10, 5, 4, 0) + 40_000;
        drive(30);
        GenTag next = tag();
        assertEquals(MON_5_OCT, next.day(), "next week's set is up");
        assertEquals(3, next.algo(), "made by today's planner");
        assertEquals(GenSeed.seed(host.store.secret(), 7, MON_5_OCT, SLOT, 0), next.seed(), "on its own seed");
        assertFalse(next.seed() == v2.seed(), "not the old layout's seed");

        // A layout of today's planner: pin live works as it always did.
        said.clear();
        gen.pin(SLOT, "live", 0, said::add);
        assertTrue(heard().contains("is pinned to seed " + GenSeed.hex(next.seed()) + " until unpinned"),
                "pinned: " + heard());
        assertEquals(GenSeed.hex(next.seed()) + ":3:0", host.store.meta(GenAdminKeys.pin(SLOT)), "stored as before");
    }

    @Test
    void aTypedSeedOfAnArchivedEditionOfAnotherPlannerIsPinnedWithAWarningAndOtherSeedsAsBefore() throws Exception {
        GenTag v2 = upgradeWeek();
        GenArchiveDao.Row archived = host.store.edition(SLOT, v2.editionKey());
        assertNotNull(archived, "fixture: the v2 edition was archived when it went live");
        assertEquals(2, archived.algoVersion(), "fixture: as planner v2's");
        String hex = GenSeed.hex(v2.seed());
        String code = archived.code();

        // Still the course up now: a recall of it is refused, so the warning gives the pin-live advice instead.
        gen.pin(SLOT, hex, 0, said::add);
        assertEquals(2, said.size(), "pinned, and one warning: " + heard());
        assertTrue(said.get(0).contains("is pinned to seed " + hex), "the pin is what was asked for: " + heard());
        assertTrue(said.get(1).startsWith("&eSeed " + hex + " was " + code + " ("),
                "the warning names the edition that seed made: " + said.get(1));
        assertTrue(said.get(1).contains("made by parkour planner v2. &7This pin makes a new course from that seed with"
                + " planner v3, not that one."), "and that the pin makes a new course: " + said.get(1));
        assertTrue(said.get(1).contains(code + " stays up until its set (Mon 28 Sep-Sun 4 Oct) ends; to keep it for"
                + " good: &e/hcm games gen keep " + SLOT + " current <new-id> confirm"),
                "that it stays until its set ends, and how to keep it for good, as pin live says: " + said.get(1));
        assertFalse(said.get(1).contains("recall"), "never a recall, which is refused for the course up now: "
                + said.get(1));
        assertEquals(hex + ":3:0", host.store.meta(GenAdminKeys.pin(SLOT)), "the pin is stored for today's planner");
        assertTaken(() -> keep(SLOT, "current", "old_easy"), "This keeps " + code, "the advised keep");

        said.clear();
        gen.pin(SLOT, "5eed", 0, said::add);
        assertEquals(1, said.size(), "a seed no archived edition has: no warning: " + heard());
        assertTrue(said.get(0).contains("is pinned to seed 0000000000005eed"), heard());

        // An archived edition of today's planner: its seed makes that same course again, so no warning.
        gen.unpin(SLOT, said::add);
        host.now = GenKit.at(2026, 10, 5, 4, 0) + 40_000;
        drive(30);
        GenTag v3 = tag();
        assertEquals(3, v3.algo(), "fixture: next week's set is planner v3's");
        said.clear();
        gen.pin(SLOT, GenSeed.hex(v3.seed()), 0, said::add);
        assertEquals(1, said.size(), "the seed of an edition of today's planner: no warning: " + heard());

        // Its set over, the v2 edition is only archived: recalled into Classic Parkour, when that is on.
        assertTrue(gen.slot(Slots.CLASSIC_PARKOUR.id()).on(), "fixture: Classic Parkour is on");
        gen.unpin(SLOT, said::add);
        said.clear();
        gen.pin(SLOT, hex, 0, said::add);
        assertEquals(2, said.size(), "pinned, and one warning: " + heard());
        assertTrue(said.get(1).contains("This pin makes a new course from that seed with planner v3, not that one. To"
                + " bring " + code + " back as it was: &e/hcm games gen recall " + code), "a recall now: "
                + said.get(1));
        assertFalse(said.get(1).contains("keep"), "not a keep: " + said.get(1));

        // Classic Parkour off (a hand-built course next to its area): the recall would be refused, so keep by code.
        Box c = gen.slot(Slots.CLASSIC_PARKOUR.id()).half('A');
        Course near = new Course("river_run", TrialKind.PARKOUR, "River Run", Tier.EASY, GenKit.WORLD,
                new Course.Spot(c.minX() - 10, c.minY() + 5, c.minZ() + 5, 0f, 0f), List.of(), null, null, null,
                false, false, 1);
        host.dao.saveCourse(new GamesDao.CourseRow("river_run", "trials", "parkour", "River Run", GenKit.WORLD, false,
                CourseCodec.encode(near), 1, 0, 0));
        vetted();
        assertFalse(gen.slot(Slots.CLASSIC_PARKOUR.id()).on(), "fixture: Classic Parkour is off");
        said.clear();
        gen.pin(SLOT, hex, 0, said::add);
        assertEquals(2, said.size(), "pinned, and one warning: " + heard());
        assertTrue(said.get(1).contains("not that one. To keep " + code + " as it was, for good: &e/hcm games gen keep "
                + code + " <new-id> confirm"), "keep by its code: " + said.get(1));
        assertFalse(said.get(1).contains("recall"), "never a recall Classic Parkour can't take: " + said.get(1));
        said.clear();
        gen.recall(null, null, GenArgs.which(code), GenArgs.DAYS_DEFAULT, false, said::add);
        assertTrue(heard().contains("Classic Parkour is off"), "fixture: the recall is refused: " + heard());
        assertTaken(() -> keep(null, code, "old_easy"), "This keeps " + code, "the advised keep");

        // Classic Parkour on again: the recall it suggests is taken.
        host.dao.deleteCourse("river_run");
        vetted();
        said.clear();
        gen.pin(SLOT, hex, 0, said::add);
        assertTrue(said.get(1).contains("&e/hcm games gen recall " + code), "a recall again: " + said.get(1));
        assertTaken(() -> gen.recall(null, null, GenArgs.which(code), GenArgs.DAYS_DEFAULT, false, said::add),
                "Bringing back &f" + code, "the advised recall");
    }

    @Test
    void aTypedSeedOfAnOlderIceBoatPointsToKeepNeverToARecallItHasNoClassicsSlotFor() throws Exception {
        assertNull(Slots.classicFor(Slots.ICE_BOAT), "fixture: Ice Boat has no Classics slot");
        host.settings = GenKit.fast(GenKit.weekly(BOAT)); // the Mountain Run v2's halves are big
        GenTag v2 = upgradeWeek(BOAT, boat);
        GenArchiveDao.Row archived = host.store.edition(BOAT, v2.editionKey());
        String hex = GenSeed.hex(v2.seed());
        String code = archived.code();

        gen.pin(BOAT, hex, 0, said::add);
        assertEquals(2, said.size(), "pinned, and one warning: " + heard());
        assertTrue(said.get(1).contains("made by boat planner v2. &7This pin makes a new course from that seed with"
                + " planner v3, not that one. " + code + " stays up until its set (Mon 28 Sep-Sun 4 Oct) ends; to keep"
                + " it for good: &e/hcm games gen keep " + BOAT + " current <new-id> confirm"),
                "up now: the pin-live advice: " + said.get(1));
        assertFalse(said.get(1).contains("recall"), "no recall: " + said.get(1));

        gen.unpin(BOAT, said::add);
        host.now = GenKit.at(2026, 10, 5, 4, 0) + 40_000;
        drive(30);
        assertEquals(3, gen.liveTag(BOAT).algo(), "fixture: next week's set is planner v3's");
        said.clear();
        gen.pin(BOAT, hex, 0, said::add);
        assertEquals(2, said.size(), "pinned, and one warning: " + heard());
        assertTrue(said.get(1).contains("not that one. To keep " + code + " as it was, for good: &e/hcm games gen keep "
                + code + " <new-id> confirm"), "its set over: keep by its code: " + said.get(1));
        assertFalse(said.get(1).contains("recall"), "never a recall, which Ice Boat can't have: " + said.get(1));
        said.clear();
        gen.recall(null, null, GenArgs.which(code), GenArgs.DAYS_DEFAULT, false, said::add);
        assertTrue(heard().contains("has no Classics slot"), "fixture: a recall is always refused: " + heard());
        // This fixture's boats are built at today's shipped half, the Mountain Run v2's 480 x 176 x 640, which is
        // bigger than a kept plot: the recall doesn't advise a keep that would be refused, and the keep itself
        // answers that it stays in the archive (KeepArea.fits). A real older Ice Boat (128 x 16 x 128) fits a plot
        // and is kept (KeepPlotPinTest).
        assertTrue(heard().contains("can't be kept: &7Mountain Run v2 courses are too big to keep; they stay in the"
                + " archive."), "a course too big for a plot is told so at the recall: " + heard());
        assertFalse(heard().contains("Keep it for good instead"), "and never sent to a keep that is refused: " + heard());
        assertTaken(() -> keep(null, code, "old_boat"), "Mountain Run v2 courses are too big to keep; they stay in the"
                + " archive", "the advised keep, for a course that big,");
    }

    /** {@code keep} as typed without {@code confirm}: it says what it would do, or why not. */
    private void keep(String slot, String which, String id) {
        gen.keep(slot, GenArgs.which(which), id, null, false, false, said::add);
    }

    /** The command the warning suggests is taken, not refused. */
    private void assertTaken(Runnable command, String taken, String what) {
        said.clear();
        command.run();
        assertTrue(heard().contains(taken), what + " is taken: " + heard());
    }

    /** The next five-minute check of every area has run (a Classics slot's area problem is found, or gone). */
    private void vetted() {
        host.now += GenService.VET_EVERY_MS;
        gen.check();
    }
}
