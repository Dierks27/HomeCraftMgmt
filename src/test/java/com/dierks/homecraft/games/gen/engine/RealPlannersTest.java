package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatPlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import com.dierks.homecraft.games.gen.golf.PlotGrid;
import com.dierks.homecraft.games.gen.parkour.ParkourPlanner;
import com.dierks.homecraft.games.gen.rings.RingsPlanner;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine with the four real planners (WP2, WP3) over the fake world: the packages wired
 * together end to end (GEN-SPEC §3.3, §3.5). Every slot (the ice boat switched on too) plans with
 * its generator's real work budget, passes the checks and its generator's validator, converges,
 * verifies (golf replays its witness lines on the blocks), flips and opens: Time Trials' and Mini
 * Golf's own filters open every row through the gate, and {@code /hcm games gen status} shows each
 * live. A restart shuts them all until it re-derives every live layout from its tag and finds
 * nothing to heal, even after the admin changed the golf mix; and a layout that today's
 * {@code trials.fall_depth} no longer allows is never re-opened.
 */
class RealPlannersTest {

    private static final List<String> SHIPPED = shipped();

    /**
     * Every shipped slot. The ice boat only once its planner makes the Mountain Run v2 (boat algo 4, package
     * MA): its shipped half is the v4 one now (480 x 176 x 640), which the v3 spiral planner doesn't plan; the
     * v3 planner keeps its own tests at its 0.36 box (LegacyBoxes.v036).
     */
    private static List<String> shipped() {
        List<String> out = new java.util.ArrayList<>(List.of("fresh_parkour_easy", "fresh_parkour",
                "fresh_parkour_hard", "fresh_rings", "fresh_golf", "fresh_tiny_golf"));
        if (BoatPlanner.ALGO >= PlanCheck.BOAT_MOUNTAIN_ALGO) {
            out.add("fresh_boat");
        }
        return List.copyOf(out);
    }

    private Host host;
    private GenService gen;

    @AfterEach
    void tearDown() throws Exception {
        if (gen != null) {
            gen.stop();
        }
        host.connection.close();
    }

    /**
     * The golf planner the shipped Golf of the Week half holds: Golf v4 once its 128 x 224 area is
     * the shipped one (GOLF-V4-SPEC §4.1, the area package), until then the frozen Adventure Golf
     * planner ({@link GolfPlanner#v3()}), so the engine's end-to-end golf path is proven at the
     * shipped size either way. Tiny Golf plans Golf v4 in its unchanged half.
     */
    static Planner golf() {
        int[] need = PlotGrid.V4.needs(Slots.DAILY_GOLF.tierOrMix().length());
        Box half = Slots.DAILY_GOLF.half('A');
        return half.sizeX() >= need[0] && half.sizeZ() >= need[1] ? new GolfPlanner() : GolfPlanner.v3();
    }

    static Map<String, Planner> planners() {
        Map<String, Planner> out = new LinkedHashMap<>();
        for (Planner p : List.<Planner>of(new ParkourPlanner(), new RingsPlanner(), golf(),
                new BoatPlanner())) {
            out.put(p.id(), p);
        }
        return out;
    }

    private void boot() {
        if (gen != null) {
            gen.stop();
        }
        gen = new GenService(host, planners());
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

    private String problems() {
        StringBuilder b = new StringBuilder();
        for (var r : host.logs) {
            if (r.getLevel().intValue() >= Level.WARNING.intValue()) {
                b.append("\n  ").append(r.getLevel()).append(' ').append(r.getMessage());
            }
        }
        return b.toString();
    }

    @Test
    void everyShippedSlotBuildsWithItsRealPlannerOpensAndIsReDerivedAtTheNextBoot() throws Exception {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, SHIPPED.toArray(new String[0]));
        boot();
        drive(60 + 15 * 60);
        for (String id : SHIPPED) {
            GenTag tag = gen.liveTag(id);
            assertNotNull(tag, id + " was built with its real planner:" + problems());
            assertTrue(gen.live(id, tag), id + " is open once it is verified");
            GamesDao.CourseRow row = host.dao.course(id);
            if (Slots.of(id).golf()) {
                GolfCourse g = com.dierks.homecraft.games.golf.CourseCodec.fromRow(row);
                assertEquals(g.holes().size(), tag.witness().size(), id + " keeps a witness line per hole");
                assertTrue(g.playable(List.of(GenKit.WORLD)), id + " is a playable golf course in the Games world: "
                        + g.problems(List.of(GenKit.WORLD)));
                assertEquals(tag, g.gen(), id + "'s row carries the tag the gate vouches for");
            } else {
                Course c = CourseCodec.decode(row.id(), row.data()).course();
                assertNotNull(c, id + "'s row decodes as a course");
                assertEquals(tag, c.gen(), id + "'s row carries the tag the gate vouches for");
                assertTrue(tag.goldMs() > 0 && tag.silverMs() > tag.goldMs(), id + " has its star times");
            }
        }
        assertEquals(SHIPPED, open(gen), "Time Trials and Mini Golf open every generated row, through the gate,"
                + " Fresh Courses first in slot order");
        assertEquals(List.of(), open(GeneratedCourses.NONE), "and none of them without Fresh Courses");
        long trials = SHIPPED.stream().filter(id -> !Slots.of(id).golf()).count();
        assertTrue(host.changed.getOrDefault("trials", 0) >= trials && host.changed.getOrDefault("golf", 0) >= 2,
                "each flip told Time Trials or Mini Golf to read its courses again: " + host.changed);
        List<String> status = adminSays("status");
        for (String id : SHIPPED) {
            assertTrue(status.stream().anyMatch(l -> l.startsWith(id) && l.contains(" live ")),
                    "/hcm games gen status shows " + id + " live: " + status);
        }
        assertTrue(gen.summary().get(0).startsWith(SHIPPED.size() + " courses up for Tue 29 Sep"),
                "/hcm games status sums them up: " + gen.summary());

        // A restart the same day: every live half is re-derived from its tag and verified untouched.
        long writes = host.world().writes;
        host.now = GenKit.at(2026, 9, 29, 16, 0) + 40_000;
        boot();
        for (String id : SHIPPED) {
            assertFalse(gen.live(id, gen.liveTag(id)), id + " stays shut at boot until it is checked");
        }
        assertEquals(List.of(), open(gen), "so neither Time Trials nor Mini Golf opens one then");
        drive(5 * 60);
        assertEquals(SHIPPED, open(gen), "and both open them all again once checked");
        for (String id : SHIPPED) {
            assertTrue(gen.live(id, gen.liveTag(id)), id + " was re-derived and verified after the restart:"
                    + problems() + "\n" + gen.status(id));
        }
        assertEquals(writes, host.world().writes, "a clean restart heals nothing");
    }

    /**
     * The ids Time Trials and Mini Golf would open now from the database's rows, read as they read
     * them, with {@code gate} as the Fresh Courses gate: their own filters, not a copy of them.
     */
    private List<String> open(GeneratedCourses gate) throws Exception {
        List<Course> trials = new java.util.ArrayList<>();
        for (GamesDao.CourseRow row : host.dao.courses("trials")) {
            trials.add(CourseCodec.decode(row.id(), row.data()).course().withRev(row.rev()));
        }
        List<GolfCourse> golf = new java.util.ArrayList<>();
        for (GamesDao.CourseRow row : host.dao.courses("golf")) {
            golf.add(com.dierks.homecraft.games.golf.CourseCodec.fromRow(row));
        }
        List<String> out = new java.util.ArrayList<>();
        TimeTrials.open(trials, GenKit.WORLD::equalsIgnoreCase, gate).forEach(c -> out.add(c.id()));
        MiniGolf.playable(golf, List.of(GenKit.WORLD), gate).forEach(c -> out.add(c.id()));
        out.sort(java.util.Comparator.comparingInt(SHIPPED::indexOf));
        return out;
    }

    /** What {@code /hcm games gen <line>} tells a console, colour codes stripped. */
    private List<String> adminSays(String line) {
        List<String> said = new java.util.ArrayList<>();
        org.bukkit.command.CommandSender console = (org.bukkit.command.CommandSender) java.lang.reflect.Proxy
                .newProxyInstance(getClass().getClassLoader(), new Class<?>[]{org.bukkit.command.CommandSender.class},
                        (proxy, m, a) -> switch (m.getName()) {
                            case "sendMessage" -> {
                                if (a[0] instanceof net.kyori.adventure.text.Component c) {
                                    said.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                            .legacyAmpersand().serialize(c).replaceAll("&[0-9a-fk-or]", "").trim());
                                }
                                yield null;
                            }
                            case "getName" -> "Console";
                            case "hasPermission" -> true;
                            default -> null;
                        });
        new com.dierks.homecraft.games.gen.admin.GenAdmin(() -> gen, host.logger).handle(console, line.split(" "));
        return said;
    }

    @Test
    void aGolfLayoutIsReDerivedAfterTheAdminChangedItsMix() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, "fresh_golf");
        boot();
        drive(60 + 3 * 60);
        GenTag live = gen.liveTag("fresh_golf");
        assertNotNull(live, "the big golf course was built:" + problems());
        List<String> said = new java.util.ArrayList<>();
        gen.tier("fresh_golf", "EEEEEEEEE", said::add); // "from the next build"
        long writes = host.world().writes;
        host.now = GenKit.at(2026, 9, 29, 16, 0) + 40_000;
        boot();
        drive(3 * 60);
        assertEquals(live, gen.liveTag("fresh_golf"), "the same layout is live after the restart");
        assertTrue(gen.live("fresh_golf", live), "and it opens: it was re-derived with the mix it was made with,"
                + " not the new one:" + problems());
        assertEquals(writes, host.world().writes, "with nothing to heal");
    }

    @Test
    void aParkourLayoutThatTodaysFallDepthNoLongerAllowsIsReplacedNotReOpened() throws Exception {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, "fresh_parkour_hard");
        boot();
        drive(60 + 60);
        GenTag first = gen.liveTag("fresh_parkour_hard");
        assertNotNull(first, "Hard Parkour was built at fall_depth 6:" + problems());
        host.fallDepth = 1; // the admin lowers trials.fall_depth, then restarts
        host.now = GenKit.at(2026, 9, 29, 16, 0) + 40_000;
        boot();
        drive(3 * 60);
        GenTag second = gen.liveTag("fresh_parkour_hard");
        assertTrue(host.logged(Level.SEVERE, "can't be vouched for") >= 1,
                "the layout made for fall_depth 6 fails the check at 1:" + problems());
        assertEquals(first.reroll() + 1, second.reroll(), "its replacement is the set's next reroll, on a fresh board");
        assertTrue(gen.live("fresh_parkour_hard", second), "and it opens, planned for fall_depth 1");
    }
}
