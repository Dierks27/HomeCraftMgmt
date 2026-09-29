package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.gen.admin.GenArgs;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.PlanCodec;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenArchiveDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive and the Classics slots on the real engine (GEN-SPEC-KEEP §1-§3, §5, §6), with a fake
 * clock, a fake world and a real in-memory database.
 *
 * <p>Pinned here: every flip archives its edition (code, dates, seed, plan) in the flip's own
 * transaction, and one the archive can't take rolls the whole flip back; pruning keeps an archived
 * edition's board as long as its row; the history pages newest first; a recall rebuilds the
 * archived plan block for block (the planner is never asked), on the ORIGINAL board, paying the
 * first-finish reward once per player per original edition; replacing a recall lets a run on the
 * old one finish; unrecall and the end of a recall's time close it and clear both halves once
 * nobody is on them; a recall is refused in the restart hold; one stopped halfway is simply built
 * again at the next start and nothing half-built opens; an unreadable plan is refused with the
 * seed to make it again, and that makes it "(re-made)".
 */
class ArchiveRecallTest {

    private static final String SLOT = "fresh_parkour_hard";
    private static final Slots.Def DEF = Slots.DAILY_PARKOUR_HARD;
    private static final Box A = DEF.half('A');
    private static final String CLASSIC = "fresh_classic_parkour";
    private static final Slots.Def CDEF = Slots.CLASSIC_PARKOUR;

    private Host host;
    private FakePlanner parkour;
    private Map<String, Planner> planners;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(on(1), SLOT);
        parkour = new FakePlanner(Slots.PARKOUR);
        planners = new LinkedHashMap<>();
        planners.put(Slots.PARKOUR, parkour);
        planners.put(Slots.RINGS, new FakePlanner(Slots.RINGS));
        planners.put(Slots.GOLF, new FakePlanner(Slots.GOLF));
        planners.put(Slots.BOAT, new FakePlanner(Slots.BOAT));
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    /** 40 seconds after 04:00 on day {@code n} (day 1 is Tue 29 Sep 2026), whatever the clocks do. */
    private static long on(int n) {
        LocalDate d = LocalDate.of(2026, 9, 29).plusDays(n - 1);
        return GenKit.at(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), 4, 0) + 40_000;
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

    /** The day's set, built: day 1 boots and waits out the startup delay; later days just build. */
    private GenTag build(int day) {
        host.now = on(day);
        if (gen == null) {
            boot();
            drive(70);
        } else {
            drive(20);
        }
        drive(15); // and the old half is emptied (nobody is on it)
        return gen.liveTag(SLOT);
    }

    /** What a box holds, by position relative to its min corner (signs included). */
    private Map<String, String> relative(Box b) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<Long, String> e : host.world().copy(b).entrySet()) {
            long p = e.getKey();
            int x = (int) (p >> 38);
            int z = (int) ((p << 26) >> 38);
            int y = (int) ((p << 52) >> 52);
            out.put((x - b.minX()) + "," + (y - b.minY()) + "," + (z - b.minZ()), e.getValue());
        }
        return out;
    }

    private void recall(String code, int days, boolean confirm) {
        said.clear();
        gen.recall(null, null, GenArgs.which(code), days, confirm, said::add);
    }

    private String heard() {
        return String.join("\n", said);
    }

    // ---- the archive ------------------------------------------------------------------------------

    @Test
    void everyFlipArchivesItsEditionWithItsCodeDatesSeedAndPlan() throws Exception {
        GenTag t1 = build(1);
        GenArchiveDao.Row r1 = host.store.edition(SLOT, t1.editionKey());
        assertNotNull(r1, "the edition that went live is archived");
        assertEquals("HARD-1", r1.code(), "the first Hard Parkour is HARD-1");
        assertEquals(t1.seed(), r1.seed(), "with its seed");
        assertEquals("parkour/1", r1.algo(), "its generator and version");
        assertEquals("parkour", r1.kind(), "its kind");
        assertEquals("hard", r1.tierOrMix(), "its tier");
        assertEquals("Hard Parkour", r1.name(), "its name");
        assertNull(r1.endsAt(), "it is live");
        PlanCodec.Read plan = PlanCodec.decode(r1.plan());
        assertTrue(plan.ok(), "its plan reads back: " + plan.problem());
        assertEquals(GenKit.plan(DEF, A, t1.seed(), 1), plan.plan(), "exactly the plan that was built");
        assertEquals(t1.planHash(), plan.plan().hash(), "the one the live tag names");
        assertEquals("HARD-1", gen.code(t1), "the engine knows its code");
        GenTag t2 = build(2);
        GenArchiveDao.Row r2 = host.store.edition(SLOT, t2.editionKey());
        assertEquals("HARD-2", r2.code(), "the next edition is HARD-2");
        GenArchiveDao.Row ended = host.store.edition(SLOT, t1.editionKey());
        assertEquals(r2.startsAt(), ended.endsAt(), "the flip that made HARD-2 live ended HARD-1");
        assertEquals(2, host.store.editionCount(SLOT), "two editions archived");
    }

    @Test
    void aPinnedCourseRestampedForANewSetIsArchivedAsANewEditionWithItsPlan() throws Exception {
        GenTag t1 = build(1);
        said.clear();
        gen.pin(SLOT, "live", 0, said::add);
        GenTag t2 = build(2);
        assertEquals(t1.planHash(), t2.planHash(), "the pinned layout stands again");
        assertNotEquals(t1.editionKey(), t2.editionKey(), "for a new set");
        GenArchiveDao.Row r2 = host.store.edition(SLOT, t2.editionKey());
        assertEquals("HARD-2", r2.code(), "which is archived as an edition of its own");
        assertEquals(t1.planHash(), PlanCodec.decode(r2.plan()).plan().hash(), "with the plan it was made from");
        assertNotNull(host.store.edition(SLOT, t1.editionKey()).endsAt(), "and the set before it ended");
    }

    @Test
    void anArchiveRowThatCantBeWrittenRollsTheWholeFlipBack() throws Exception {
        GenStore store = GenStore.of(host.db);
        GenTag tag = new GenTag(SLOT, "parkour", 1, 20725, 0, 5, 'A', GenKit.plan(DEF, A, 5, 1).hash(), 30_000, 37_500,
                54_000, List.of(), List.of(), 1000, 1);
        GamesDao.CourseRow row = GenService.row(DEF, GenKit.WORLD, GenKit.plan(DEF, A, 5, 1).course(), tag, null, 1000);
        GenArchiveDao.Row bad = new GenArchiveDao.Row("river_run", "1:267", null, 0, 20725, 5, "parkour/1", "parkour",
                "hard", "River Run", 1000, null, null, 0, 0, 1000, null);
        assertThrows(SQLException.class, () -> store.flip(row, Map.of("gen.test.key", "x"), bad, 1000),
                "an archive row that can't be written fails the flip");
        assertNull(store.course(SLOT), "and the course row was rolled back with it");
        assertNull(store.meta("gen.test.key"), "and so was the bookkeeping");
        GenArchiveDao.Row good = new GenArchiveDao.Row(SLOT, tag.editionKey(), null, 0, 20725, 5, "parkour/1", "parkour",
                "hard", "Hard Parkour", 1000, null, PlanCodec.encode(GenKit.plan(DEF, A, 5, 1)), 0, 0, 1000, null);
        GenStore.Flipped f = store.flip(row, Map.of("gen.test.key", "x"), good, 1000);
        assertEquals("HARD-1", f.archived().code(), "a good one lands");
        assertNotNull(store.course(SLOT), "with the course row");
        assertEquals("x", store.meta("gen.test.key"), "and the bookkeeping, together");
    }

    @Test
    void pruningKeepsAnArchivedEditionsBoardAsLongAsItsRow() throws Exception {
        GenTag t1 = build(1);
        UUID amy = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        host.dao.submit(amy, "trials", GenBoards.day(t1), 60_000, true, host.now);
        String orphan = GenBoards.day(SLOT, "1:100"); // an old edition from before the archive
        host.dao.submit(bob, "trials", orphan, 50_000, true, host.now);
        long idx = com.dierks.homecraft.games.gen.api.Edition.index(1, LocalDate.of(2026, 9, 29).plusDays(49)
                .toEpochDay());
        for (int k = 0; k < GenService.KEEP_EDITIONS; k++) {
            host.dao.submit(UUID.randomUUID(), "trials", GenBoards.day(SLOT, "1:" + (idx - 1 - k)), 70_000, true,
                    host.now);
        }
        build(50); // 49 days on: a flip, then the day's pruning
        assertNotNull(host.dao.best(amy, "trials", GenBoards.day(t1)), "an archived edition's board is kept");
        assertNull(host.dao.best(bob, "trials", orphan), "an old board with no archive row is pruned as before");
        List<String> boards = List.of(GenBoards.day(t1), orphan);
        assertEquals(List.of(orphan), GenService.oldEditionBoards(boards, Long.MAX_VALUE, 0,
                java.util.Set.of(GenBoards.day(t1))), "the rule itself: archived boards are spared");
    }

    @Test
    void historyPagesEightNewestFirstWithCodesDatesSeedsRecordsAndPlays() throws Exception {
        GenTag first = build(1);
        UUID sam = UUID.randomUUID();
        host.names.put(sam, "Sam");
        host.dao.submit(sam, "trials", GenBoards.day(first), 62_300, true, host.now);
        for (int d = 2; d <= 10; d++) {
            build(d);
        }
        List<String> p1 = gen.history(SLOT, 1);
        assertTrue(p1.get(0).contains("page 1 of 2") && p1.get(0).contains("10 in all"), p1.get(0));
        assertTrue(p1.get(1).startsWith("&fHARD-10"), "newest first: " + p1.get(1));
        assertTrue(p1.get(1).contains("(up now)"), "the live one says so: " + p1.get(1));
        assertEquals(8, p1.stream().filter(l -> l.startsWith("&fHARD-")).count(), "8 a page");
        assertTrue(p1.stream().anyMatch(l -> l.contains("history " + SLOT + " 2")), "and where the next page is");
        List<String> p2 = gen.history(SLOT, 2);
        String h1 = p2.stream().filter(l -> l.startsWith("&fHARD-1 ")).findFirst().orElseThrow();
        assertTrue(h1.contains("Tue 29 Sep - Wed 30 Sep"), "its dates: " + h1);
        assertTrue(h1.contains("seed " + FreshFeed.shortSeed(first.seed())), "its short seed: " + h1);
        assertTrue(h1.contains("record &f1:02.3 &7by &fSam"), "its record and holder: " + h1);
        assertTrue(h1.contains("1 play"), "its plays: " + h1);
        assertEquals(p2, gen.history(SLOT, 99), "a page past the end shows the last one");
        List<String> one = gen.historyOf(SLOT, GenArgs.which("HARD-1"));
        assertTrue(one.get(0).startsWith("&6HARD-1"), one.get(0));
        assertTrue(one.stream().anyMatch(l -> l.contains("1. Sam")), "its top 5: " + one);
        assertTrue(one.stream().anyMatch(l -> l.contains("brought back exactly")), "its plan is readable: " + one);
        assertTrue(gen.history(null, 1).get(0).contains("every course"), "every slot's history too");
    }

    // ---- recall ---------------------------------------------------------------------------------------

    @Test
    void aRecalledCourseIsRebuiltBlockForBlockFromItsArchivedPlanOnTheOriginalBoard() throws Exception {
        GenTag t1 = build(1);
        Map<String, String> original = relative(A);
        assertFalse(original.isEmpty(), "the first course stands in half A");
        build(2);
        assertEquals(0, host.world().count(A), "a day later half A is empty again");
        parkour.fail = new GenFailed("the generator changed since");
        parkour.failAlways = true; // a recall must never plan again
        int plans = parkour.plans;
        recall("HARD-1", GenArgs.DAYS_DEFAULT, false);
        assertTrue(heard().contains("Bringing back"), heard());
        drive(10);
        GenTag c = gen.liveTag(CLASSIC);
        assertNotNull(c, "HARD-1 is up in Classic Parkour: " + heard());
        assertEquals(plans, parkour.plans, "the planner was never asked");
        assertEquals(original, relative(CDEF.half(c.half())), "block for block the original half, signs and all");
        assertEquals(SLOT, c.slot(), "the tag is the original edition's");
        assertEquals(t1.editionKey(), c.editionKey(), "the same edition");
        assertEquals(CLASSIC, c.recall().slot(), "standing in Classic Parkour");
        assertEquals(GenBoards.day(t1), GenBoards.day(c), "its board is the ORIGINAL board");
        assertTrue(gen.live(CLASSIC, c), "and it is open");
        GamesDao.CourseRow row = host.dao.course(CLASSIC);
        assertEquals("trials", row.game(), "an ordinary trials row");
        assertTrue(row.name().startsWith("Classic: Hard Parkour"), "players see what it is: " + row.name());
        Course course = CourseCodec.decode(row.id(), row.data()).course();
        assertEquals(c, course.gen(), "carrying the recall's tag");
        assertTrue(gen.history(SLOT, 1).stream().anyMatch(l -> l.startsWith("&fHARD-1 ") && l.contains("(recalled now)")),
                "history marks it while it is back");
        FreshFeed.Classic feed = gen.classic(CLASSIC);
        assertEquals("HARD-1", feed.code(), "the website's classic object");
        assertEquals(c.recall().from() + 7 * 86_400_000L, feed.to(), "for classics.days (7)");
        assertTrue(gen.status(null).stream().anyMatch(l -> l.contains(CLASSIC) && l.contains("HARD-1")),
                "status says what it holds: " + gen.status(null));
    }

    @Test
    void theFirstFinishRewardIsPaidOncePerPlayerPerOriginalEdition() throws Exception {
        GenTag t1 = build(1);
        build(2);
        recall("HARD-1", GenArgs.DAYS_DEFAULT, false);
        drive(10);
        GenTag c = gen.liveTag(CLASSIC);
        assertEquals(GenBoards.clearRef(t1), GenBoards.clearRef(c), "the recall's reward ref is the original's");
        UUID amy = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        TokenService.Source src = TokenService.Source.GAMES_DAILY;
        assertEquals(3, host.dao.payReward(amy, "trials", src, 20725, RewardKind.DAILY_CLEAR, GenBoards.clearRef(t1), 3,
                -1, -1, true, "first finish", host.now), "Amy cleared it back then");
        assertEquals(0, host.dao.payReward(amy, "trials", src, 20732, RewardKind.DAILY_CLEAR, GenBoards.clearRef(c), 3,
                -1, -1, true, "first finish", host.now), "and isn't paid again on the recall");
        assertEquals(3, host.dao.payReward(bob, "trials", src, 20732, RewardKind.DAILY_CLEAR, GenBoards.clearRef(c), 3,
                -1, -1, true, "first finish", host.now), "a new player is");
        assertEquals(0, host.dao.payReward(bob, "trials", src, 20733, RewardKind.DAILY_CLEAR, GenBoards.clearRef(c), 3,
                -1, -1, true, "first finish", host.now), "once");
        assertNotEquals(GenBoards.stars(t1), GenBoards.stars(c), "while its stars count afresh, this week");
    }

    @Test
    void replacingARecallLetsARunOnTheOldOneFinish() throws Exception {
        build(1);
        GenTag t2 = build(2);
        recall("HARD-1", GenArgs.DAYS_DEFAULT, false);
        drive(10);
        GenTag c1 = gen.liveTag(CLASSIC);
        Box h1 = CDEF.half(c1.half());
        Person sam = new Person(UUID.randomUUID(), "Sam", GenKit.WORLD, h1.minX() + 4.5, h1.minY() + 11,
                h1.minZ() + 4.5, "trials", CLASSIC);
        host.people.add(sam);
        recall("HARD-2", GenArgs.DAYS_DEFAULT, false);
        assertTrue(heard().contains("1 player is on Classic Parkour") && heard().contains("confirm"),
                "someone is playing it: confirm first: " + heard());
        drive(5);
        assertEquals(c1, gen.liveTag(CLASSIC), "nothing changed without confirm");
        recall("HARD-2", GenArgs.DAYS_DEFAULT, true);
        drive(10);
        GenTag c2 = gen.liveTag(CLASSIC);
        assertEquals(t2.editionKey(), c2.editionKey(), "HARD-2 is up now");
        assertNotEquals(c1.half(), c2.half(), "in the other half");
        assertFalse(gen.live(CLASSIC, c1), "HARD-1 can't be started any more");
        assertTrue(gen.standing(c1), "but Sam's run on it still counts");
        drive(40);
        assertTrue(gen.standing(c1), "for as long as Sam is on it");
        assertTrue(host.world().count(h1) > 0, "and its blocks stay");
        host.people.clear();
        drive(15);
        assertEquals(0, host.world().count(h1), "once Sam is off it, its half is emptied");
        assertFalse(gen.standing(c1), "and it no longer stands");
        assertTrue(gen.standing(c2), "HARD-2 does");
    }

    @Test
    void unrecallAndTheEndOfARecallsTimeCloseItAndClearBothHalves() throws Exception {
        build(1);
        build(2);
        recall("HARD-1", GenArgs.DAYS_DEFAULT, false);
        drive(10);
        GenTag c1 = gen.liveTag(CLASSIC);
        said.clear();
        gen.unrecall(CLASSIC, false, said::add);
        assertTrue(heard().contains("is closed"), heard());
        assertFalse(gen.live(CLASSIC, c1), "the gate shuts at once");
        assertNull(host.dao.course(CLASSIC), "its row is gone");
        assertTrue(gen.standing(c1), "a run on it would still finish");
        drive(15);
        assertEquals(0, host.world().count(CDEF.half('A')) + host.world().count(CDEF.half('B')),
                "both halves are cleared once nobody is on them");
        assertFalse(gen.standing(c1), "and it no longer stands");
        said.clear();
        gen.unrecall(CLASSIC, false, said::add);
        assertTrue(heard().contains("already empty"), heard());

        recall("HARD-2", 1, false);
        drive(10);
        assertNotNull(gen.liveTag(CLASSIC), "HARD-2 is up for a day");
        host.now += 86_400_000L;
        drive(15);
        assertNull(gen.liveTag(CLASSIC), "a day later it closes on its own");
        assertNull(host.dao.course(CLASSIC), "row and all");
        assertEquals(0, host.world().count(CDEF.half('A')) + host.world().count(CDEF.half('B')), "and is cleared");
    }

    @Test
    void aRecallIsRefusedInTheRestartHold() throws Exception {
        build(1);
        build(2);
        host.restarts = List.of(LocalTime.of(4, 10)); // ten minutes away
        recall("HARD-1", GenArgs.DAYS_DEFAULT, false);
        assertTrue(heard().contains("A restart is coming at 4:10 AM - try after it."), heard());
        drive(5);
        assertNull(gen.liveTag(CLASSIC), "nothing was recalled");
        assertNull(host.store.meta(GenAdminKeys.recall(CLASSIC)), "or even asked for");
        said.clear();
        gen.keep(SLOT, GenArgs.which("HARD-1"), "dragon_run", null, false, true, said::add);
        assertTrue(heard().contains("A restart is coming"), "keep too: " + heard());
    }

    @Test
    void aRecallStoppedHalfwayIsBuiltAgainAndNothingHalfBuiltOpens() throws Exception {
        GenTag t1 = build(1);
        Map<String, String> original = relative(A);
        build(2);
        recall("HARD-1", GenArgs.DAYS_DEFAULT, false);
        host.world().killAfter = 5; // the server dies a few blocks into the build
        drive(3);
        assertNull(host.dao.course(CLASSIC), "a half-built recall has no row: nobody can play it");
        assertNull(gen.liveTag(CLASSIC), "and isn't live");
        gen.stop();
        gen = null;
        host.now += 60_000;
        boot();
        drive(70);
        GenTag c = gen.liveTag(CLASSIC);
        assertNotNull(c, "the next start builds it again, like a due build");
        assertEquals(t1.editionKey(), c.editionKey(), "the same course");
        assertEquals(original, relative(CDEF.half(c.half())), "block for block");
        assertTrue(gen.live(CLASSIC, c), "and opens it");
    }

    @Test
    void anUnreadablePlanIsRefusedWithItsSeedAndThatMakesItReMade() throws Exception {
        GenTag t1 = build(1);
        build(2);
        try (PreparedStatement ps = host.connection.prepareStatement(
                "UPDATE gen_editions SET plan = X'1f8b0800' WHERE code = 'HARD-1'")) {
            ps.executeUpdate();
        }
        recall("HARD-1", GenArgs.DAYS_DEFAULT, false);
        assertTrue(heard().contains("can't be read"), heard());
        String hex = com.dierks.homecraft.games.gen.api.GenSeed.hex(t1.seed());
        assertTrue(heard().contains("seed:" + hex), "it offers the seed to make it again: " + heard());
        assertNull(host.store.meta(GenAdminKeys.recall(CLASSIC)), "and recalls nothing");
        said.clear();
        gen.recall(CLASSIC, SLOT, GenArgs.which("seed:" + hex.substring(0, 12)), GenArgs.DAYS_DEFAULT, false, said::add);
        drive(10);
        GenTag c = gen.liveTag(CLASSIC);
        assertNotNull(c, "made again from its seed: " + heard());
        assertEquals(t1.editionKey(), c.editionKey(), "as that edition, on its board");
        assertTrue(host.dao.course(CLASSIC).name().contains("re-made"), "and clearly marked re-made");
        assertTrue(gen.history(SLOT, 1).stream().anyMatch(l -> l.startsWith("&fHARD-1 ")), "history still lists it");
    }
}
