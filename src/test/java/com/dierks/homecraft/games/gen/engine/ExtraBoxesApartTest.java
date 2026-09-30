package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.arena.ArenaRegions;
import com.dierks.homecraft.games.arena.FallingFloors;
import com.dierks.homecraft.games.arena.FallingFloorsSettings;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseRegions;
import com.dierks.homecraft.games.clubhouse.ClubhouseSettings;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.admin.GenArgs;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
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
 * The Games world's four kinds of place keep {@value Regions#APART} blocks apart from each other, as
 * shipped and whichever moves (the final events merge, CLUBHOUSE-SPEC §1): the Fresh Courses slots
 * (on or off, the Classics too), the kept courses' area, the Falling Floors arena and the Clubhouse's
 * room. Fresh Courses sees both extra boxes through one list ({@link DailyCourses#extraBoxes}, which
 * its {@link GenHost#extras} and {@code /hcm games check} use), the arena's own check keeps apart from
 * the Clubhouse, and the Clubhouse's from the arena, so none is ever built into another.
 *
 * <p>The shipped places are read from the bundled config.yml through the real parser, so moving one
 * of them in config.yml onto another fails here, not on the owner's server.
 */
class ExtraBoxesApartTest {

    private Host host;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, "fresh_parkour_easy");
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private static GamesConfig.Parsed shipped() throws Exception {
        try (InputStream in = ExtraBoxesApartTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                List<String> warns = new ArrayList<>();
                GamesConfig.Parsed parsed = GamesConfig.parse(c.getConfigurationSection("games"), warns::add, null);
                assertEquals(List.of(), warns, "the shipped games block reads without a WARN");
                return parsed;
            }
        }
    }

    /** Every slot there could ever be, switched on (an admin can switch one on at any time). */
    private static List<DailySettings.SlotConfig> everySlot(DailySettings d) {
        List<DailySettings.SlotConfig> all = new ArrayList<>();
        for (DailySettings.SlotConfig c : d.slots()) {
            all.add(c.withEnabled(true));
        }
        for (DailySettings.SlotConfig c : d.archive().classics()) {
            all.add(c.withEnabled(true));
        }
        return all;
    }

    @Test
    void theShippedClubhouseArenaFreshSlotsAndKeepAreaAreAllApart() throws Exception {
        GamesConfig.Parsed cfg = shipped();
        DailySettings fresh = cfg.settings(DailyCourses.SPEC);
        FallingFloorsSettings ff = cfg.settings(FallingFloors.SPEC);
        ClubhouseSettings club = cfg.settings(Clubhouse.SPEC);
        assertEquals(ClubhouseSettings.defaults().box(), club.box(), "config.yml ships the Clubhouse's own default box");
        assertEquals(FallingFloorsSettings.defaults().box(), ff.box(), "and the arena's");

        List<Regions.Extra> extras = DailyCourses.extraBoxes(ff, club);
        assertEquals(List.of(ArenaRegions.NAME, ClubhouseRegions.NAME), extras.stream().map(Regions.Extra::name).toList(),
                "Fresh Courses keeps apart from both extra boxes, by the names the admins read");
        Box arena = extras.get(0).box();
        Box room = extras.get(1).box();
        assertEquals(ff.box(), arena, "the arena as configured");
        assertEquals(club.box(), room, "the Clubhouse as configured");

        // the two extra boxes, from each other
        assertTrue(arena.gap(room) >= Regions.APART, "the arena and the Clubhouse are " + arena.gap(room)
                + " blocks apart, at least " + Regions.APART);

        // every slot (on or off, the Classics), from each other and from both boxes
        List<DailySettings.SlotConfig> slots = everySlot(fresh);
        for (DailySettings.SlotConfig c : slots) {
            assertNull(Regions.apartProblem(c, slots), c.id() + " keeps apart from every other slot");
            assertNull(Regions.extrasProblem(c.def(), c.origin(), extras),
                    c.id() + " keeps apart from the arena and the Clubhouse (Fresh Courses' vet)");
            for (Regions.Extra e : extras) {
                for (Box half : Regions.halves(c.def(), c.origin())) {
                    assertTrue(half.gap(e.box()) >= Regions.APART, c.id() + "'s half " + half.describe() + " is "
                            + half.gap(e.box()) + " blocks from " + e.name());
                }
            }
        }

        // the keep area, from both boxes
        KeepArea keep = fresh.archive().keep();
        assertNull(fresh.archive().keepProblem(), "the shipped keep area reads");
        assertNull(Regions.keepExtrasProblem(keep, extras), "the keep area keeps apart from both boxes");
        for (Regions.Extra e : extras) {
            assertTrue(keep.area().gap(e.box()) >= Regions.APART, "the keep area is " + keep.area().gap(e.box())
                    + " blocks from " + e.name());
        }

        // all of it at once, as C1's extra-box rule asks it
        assertEquals(Map.of(), Regions.extraProblems(extras, slots, keep), "both boxes fit, together");
        // and each box's own check, with the other as its neighbour
        assertEquals(List.of(), ArenaRegions.problems(arena, fresh, ClubhouseRegions.extras(club), List.of(), null),
                "the arena's own check passes with the Clubhouse beside it");
        assertEquals(List.of(), ClubhouseRegions.problems(room, fresh, DailyCourses.arenaExtras(ff), List.of(), null),
                "the Clubhouse's own check passes with the arena beside it");
    }

    @Test
    void extraBoxesIsTheArenaThenTheClubhouseAndLeavesOutOneThatCantBeRead() {
        List<Regions.Extra> both = DailyCourses.extraBoxes(FallingFloorsSettings.defaults(), ClubhouseSettings.defaults());
        assertEquals(DailyCourses.arenaExtras(FallingFloorsSettings.defaults()).get(0), both.get(0), "the arena first");
        assertEquals(ClubhouseRegions.extras(ClubhouseSettings.defaults()).get(0), both.get(1), "then the Clubhouse");
        assertEquals(List.of(ClubhouseRegions.NAME), DailyCourses.extraBoxes(null, ClubhouseSettings.defaults()).stream()
                .map(Regions.Extra::name).toList(), "no arena settings: the Clubhouse alone");
        assertEquals(List.of(ArenaRegions.NAME), DailyCourses.extraBoxes(FallingFloorsSettings.defaults(), null).stream()
                .map(Regions.Extra::name).toList(), "no Clubhouse settings: the arena alone");
        assertEquals(List.of(), DailyCourses.extraBoxes(null, null), "neither: nothing to keep apart from");
        ClubhouseSettings off = new ClubhouseSettings(false, ClubhouseSettings.ORIGIN, 30, true, true, true);
        assertEquals(both, DailyCourses.extraBoxes(FallingFloorsSettings.defaults(), off),
                "a Clubhouse switched off still counts: its blocks may stand");
    }

    @Test
    void aSlotOrTheKeepAreaCrowdingTheClubhouseIsRefusedWithItsName() {
        Box room = ClubhouseSettings.defaults().box();
        List<Regions.Extra> extras = DailyCourses.extraBoxes(FallingFloorsSettings.defaults(), ClubhouseSettings.defaults());
        Slots.Def def = Slots.FRESH_DROPPER;
        String onTop = Regions.extrasProblem(def, new int[]{room.minX(), room.minY(), room.minZ()}, extras);
        assertNotNull(onTop, "a slot on top of the Clubhouse is refused");
        assertTrue(onTop.contains(ClubhouseRegions.NAME) && onTop.contains("on top of"), onTop);
        String close = Regions.extrasProblem(def, new int[]{room.maxX() + 16, room.minY(), room.minZ()}, extras);
        assertNotNull(close, "a slot within " + Regions.APART + " blocks is refused");
        assertTrue(close.contains(ClubhouseRegions.NAME) && close.contains("only 15 blocks from"), close);

        KeepArea crowding = new KeepArea(room.maxX() + 1, room.minY(), room.minZ(), 4);
        String keep = Regions.keepExtrasProblem(crowding, extras);
        assertNotNull(keep, "a keep area next to the Clubhouse is refused");
        assertTrue(keep.contains(ClubhouseRegions.NAME) && keep.contains("kept courses' area"), keep);
    }

    @Test
    void theArenaAndTheClubhouseKeepApartWhicheverMoved() {
        DailySettings fresh = DailySettings.defaults();
        Box arena = FallingFloorsSettings.defaults().box();
        Box room = ClubhouseSettings.defaults().box();

        // the Clubhouse moved next to the arena: the arena's own check refuses it too
        List<Regions.Extra> nearRoom = List.of(new Regions.Extra(ClubhouseRegions.NAME, arena.translate(0, 0,
                arena.maxZ() - arena.minZ() + 11)));
        List<String> a = ArenaRegions.problems(arena, fresh, nearRoom, List.of(), null);
        assertEquals(1, a.size(), "one problem: " + a);
        assertTrue(a.get(0).contains(ArenaRegions.NAME) && a.get(0).contains(ClubhouseRegions.NAME)
                && a.get(0).contains("only 10 blocks from"), a.get(0));
        assertEquals(List.of(), ArenaRegions.problems(arena, fresh, List.of(), null),
                "the old four-argument check (no neighbours) is unchanged");

        // the arena moved next to the Clubhouse: the Clubhouse's own check refuses it
        List<Regions.Extra> nearArena = List.of(new Regions.Extra(ArenaRegions.NAME, room.translate(40, 0, 0)));
        List<String> c = ClubhouseRegions.problems(room, fresh, nearArena, List.of(), null);
        assertEquals(1, c.size(), "one problem: " + c);
        assertTrue(c.get(0).contains(ClubhouseRegions.NAME) && c.get(0).contains(ArenaRegions.NAME), c.get(0));

        // a neighbour that itself crowds a slot still counts: its blocks may stand
        DailySettings.SlotConfig slot = fresh.slots().get(0);
        Box half = Regions.half(slot.def(), slot.origin(), 'A');
        Box crowdingArena = Box.sized(half.maxX() + 5, half.minY(), half.minZ(), 48, 40, 48);
        assertNotNull(Regions.extraProblem(new Regions.Extra(ArenaRegions.NAME, crowdingArena), everySlot(fresh), null),
                "this arena crowds " + slot.id());
        Box roomBeside = crowdingArena.translate(0, 0, crowdingArena.maxZ() - crowdingArena.minZ() + 6);
        String p = Regions.extraApartProblem(new Regions.Extra(ClubhouseRegions.NAME, roomBeside),
                List.of(new Regions.Extra(ArenaRegions.NAME, crowdingArena)));
        assertNotNull(p, "the Clubhouse still keeps apart from an arena that has a problem of its own");
        assertTrue(p.contains("only 5 blocks from " + ArenaRegions.NAME), p);
        assertNull(Regions.extraApartProblem(new Regions.Extra(ClubhouseRegions.NAME, room),
                List.of(new Regions.Extra(ClubhouseRegions.NAME, room))), "a box is never too near itself");
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

    @Test
    void theEngineTurnsOffASlotCrowdingTheClubhouseAndWontKeepNextToIt() {
        Slots.Def def = Slots.DAILY_PARKOUR_EASY;
        Map<String, Planner> planners = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.GOLF, Slots.BOAT, Slots.DROPPER)) {
            planners.put(id, new FakePlanner(id));
        }
        host.extras = DailyCourses.extraBoxes(FallingFloorsSettings.defaults(), ClubhouseSettings.defaults());
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
        drive(85);
        assertNotNull(gen.liveTag(def.id()), "day 1's Easy Parkour is built (EASY-1): the shipped boxes are clear of it");
        host.now = GenKit.at(2026, 9, 30, 4, 0) + 40_000;
        drive(35);

        int[] o = def.origin();
        ClubhouseSettings moved = new ClubhouseSettings(true, List.of(o[0], o[1], o[2] - 24), 30, true, true, true);
        host.extras = DailyCourses.extraBoxes(FallingFloorsSettings.defaults(), moved);
        host.now += GenService.VET_EVERY_MS;
        drive(1); // the regular re-check
        GenService.SlotReport r = gen.report().stream().filter(x -> x.id().equals(def.id())).findFirst().orElseThrow();
        assertNotNull(r.problem(), "the slot next to the Clubhouse is off");
        assertTrue(r.problem().contains(ClubhouseRegions.NAME), "and says why: " + r.problem());
        assertTrue(host.logged(java.util.logging.Level.SEVERE, ClubhouseRegions.NAME) > 0, "logged, as SEVERE");

        Box k = host.settings.archive().keep().area();
        host.extras = DailyCourses.extraBoxes(FallingFloorsSettings.defaults(),
                new ClubhouseSettings(true, List.of(k.maxX() + 5, k.minY(), k.minZ()), 30, true, true, true));
        said.clear();
        gen.keep(def.id(), GenArgs.which("EASY-1"), "near_the_clubhouse", null, false, true, said::add);
        String heard = String.join("\n", said);
        assertTrue(heard.contains("Keeping is off") && heard.contains(ClubhouseRegions.NAME),
                "no course is kept while the keep area crowds the Clubhouse: " + heard);
        assertFalse(gen.keeper().hasWork(), "and nothing is queued");
    }
}
