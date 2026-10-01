package com.dierks.homecraft.command;

import com.dierks.homecraft.command.GamesCheck.Line;
import com.dierks.homecraft.command.GamesCheck.Status;
import com.dierks.homecraft.command.SightCheck.Ground;
import com.dierks.homecraft.command.SightCheck.Kind;
import com.dierks.homecraft.command.SightCheck.Place;
import com.dierks.homecraft.games.event.RaceTrack;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Sight;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "What players can see" in {@code /hcm games check} (LAYOUT-SPEC §5.1, LAYOUT-VOID-ADDENDUM item
 * 3): each Games world is void or not (an OK line either way), a spawn with nothing under it is a
 * WARN, the view distance used is said, and every pair of places in sight is a WARN naming both, how
 * far apart they are and how to move one by hand, nearest first and at most eight; nothing here is
 * ever a FAIL.
 */
class SightCheckTest {

    private static final int VIEW = 10;

    private static List<Place> halves(Kind kind, Slots.Def def, Box a, Box b) {
        return List.of(new Place(kind, def.id(), def.name(), a), new Place(kind, def.id(), def.name(), b));
    }

    private static List<Line> rows(SightCheck.Facts f) {
        List<Line> out = new ArrayList<>();
        SightCheck.rows(f, out);
        return out;
    }

    private static SightCheck.Facts facts(List<Place> places, List<Ground> grounds) {
        return new SightCheck.Facts("games", VIEW, "games' view distance 10, its send distance 8, nobody there now",
                places, grounds);
    }

    @Test
    void eachGamesWorldSaysWhetherItIsVoidAndASpawnOverNothingIsAWarn() {
        List<Line> out = rows(new SightCheck.Facts("", VIEW, "", List.of(), List.of(
                new Ground("sky", true, true, true), new Ground("games", true, false, true),
                new Ground("drop", true, true, false), new Ground("gone", false, false, null),
                new Ground("dark", true, false, null))));
        assertEquals(Line.ok("Games world 'sky' is void (nothing below the courses)"), out.get(0), "ours: void");
        assertEquals(Line.ok("Games world 'games' isn't a void world: you'll see the ground far below the courses."
                + " See README 'A void world'"), out.get(1), "another generator: said, as an OK (the check has no INFO)");
        assertEquals(Status.WARN, out.get(3).status(), "a spawn with nothing under it is a WARN");
        assertEquals("The spawn of 'drop' has nothing under it - someone arriving there would fall", out.get(3).what(),
                "in the addendum's words");
        assertTrue(out.get(3).fix().contains("/mv create <name> normal -g HomeCraftManagement"), "with the fix: "
                + out.get(3).fix());
        assertEquals(5, out.size(), "an unloaded world is its own FAIL elsewhere; one that can't be read says"
                + " nothing about its spawn: " + out);
        assertTrue(out.stream().noneMatch(l -> l.status() == Status.FAIL), "never a FAIL");
    }

    @Test
    void theNewLayoutIsClearAndTheLineSaysHowFarApartTheClosestTwoAre() {
        List<Place> places = new ArrayList<>();
        Slots.Def rings = Slots.SKY_RINGS;
        places.addAll(halves(Kind.SLOT, rings, rings.half(6080, 128, 4096, 'A', Sight.GAP),
                rings.half(6080, 128, 4096, 'B', Sight.GAP)));
        places.add(new Place(Kind.CLUBHOUSE, "clubhouse", "the Clubhouse", Box.sized(6080, 160, 8544, 32, 16, 32)));
        places.add(new Place(Kind.SPAWN, "spawn", "the spawn of games", new Box(0, 100, 0, 0, 100, 0)));
        List<Line> out = rows(facts(places, List.of()));
        assertEquals(Line.ok("View distance used: 10 (games' view distance 10, its send distance 8, nobody there"
                + " now)"), out.get(0), "where the view distance came from");
        assertEquals(Line.ok("Nothing else built by the games can be seen from any course, the Clubhouse or the arena"
                        + " (view distance 10; the closest two places are 36 chunks apart, clear up to view distance 34)"),
                out.get(1), "all clear, and by how much");
        assertEquals(2, out.size(), "nothing else: " + out);
    }

    @Test
    void a035SlotSeesItsOwnSpareHalfAndIsToldHowToSpreadItsHalves() {
        Slots.Def d = Slots.DAILY_PARKOUR_MEDIUM;
        List<Line> out = rows(facts(halves(Kind.SLOT, d, LegacyBoxes.half(d, 'A'), LegacyBoxes.half(d, 'B')),
                List.of()));
        Line l = out.get(1);
        assertEquals(Status.WARN, l.status(), "a WARN");
        assertEquals("From Parkour, players can see its own spare half, where the next course is built (2 chunks"
                + " away; view distance 10)", l.what(), "in the spec's words");
        assertEquals("move it by hand to a spot with 576 free blocks along x: /hcm games gen clear fresh_parkour"
                + " confirm, then set half_gap: 576 under games.fresh.slots.fresh_parkour", l.fix(),
                "2 chunks is too close to fix with the view distance, so it isn't offered");
        Slots.Def c = Slots.CLASSIC_GOLF;
        Line classic = rows(facts(halves(Kind.CLASSIC, c, LegacyBoxes.half(c, 'A'), LegacyBoxes.half(c, 'B')),
                List.of())).get(1);
        assertTrue(classic.fix().endsWith("under games.fresh.classics.slots.fresh_classic_golf"), "a Classic's own"
                + " section: " + classic.fix());
    }

    @Test
    void aServerThatKeptThe035LayoutIsToldWhyAndAClassicIsEmptiedByClosingIt() {
        Slots.Def c = Slots.CLASSIC_GOLF;
        List<Place> places = new ArrayList<>(halves(Kind.CLASSIC, c, LegacyBoxes.half(c, 'A'),
                LegacyBoxes.half(c, 'B')));
        places.add(new Place(Kind.SLOT, "fresh_parkour", "Parkour", LegacyBoxes.half(Slots.DAILY_PARKOUR_MEDIUM, 'A')));
        List<Line> out = rows(new SightCheck.Facts("games", VIEW, "why", places, List.of(), "30 Sep 2026"));
        assertEquals(Status.OK, out.get(0).status(), "context, not a problem of its own: the pairs below are the WARNs");
        assertTrue(out.get(0).what().contains("on 30 Sep 2026") && out.get(0).what().contains("its 0.35 spot and shape")
                && out.get(0).what().contains("Moving an area by hand"), "why the old spots stayed: " + out.get(0));
        List<Line> classic = out.stream().filter(l -> l.what().startsWith("From Classic Golf")).toList();
        assertFalse(classic.isEmpty(), "Classic Golf sees things at 0.35's spots: " + out);
        for (Line l : classic) {
            assertTrue(l.fix().contains("/hcm games gen unrecall fresh_classic_golf confirm (wait until its halves are"
                    + " empty)"), "a Classic is emptied by closing it: " + l.fix());
            assertFalse(l.fix().contains("gen clear fresh_classic"), "clear doesn't take a Classic: " + l.fix());
        }
        assertTrue(rows(facts(places, List.of())).stream().noneMatch(l -> l.what().contains("0.35 spot")),
                "a server on the new layout isn't told about it");
        assertTrue(out.stream().noneMatch(l -> l.status() == Status.FAIL), "never a FAIL");
    }

    @Test
    void twoPlacesInSightNameTheOneThatMovesMostSimplyAndTheViewDistanceThatWouldHideThem() {
        Box club = Box.sized(0, 160, 0, 32, 16, 32);
        Box golf = Box.sized(32 + 16 * 7, 160, 0, 64, 16, 48); // 7 chunk columns after the club's reach ends
        List<Place> places = List.of(new Place(Kind.CLUBHOUSE, "clubhouse", "the Clubhouse", club),
                new Place(Kind.SLOT, "fresh_tiny_golf", "Tiny Golf", golf));
        Line l = rows(facts(places, List.of())).get(1);
        assertEquals("From the Clubhouse, players can see Tiny Golf (7 chunks away; view distance 10)", l.what(),
                "both named, how far and at which view distance");
        assertEquals("move Tiny Golf by hand: /hcm games gen clear fresh_tiny_golf confirm, then change"
                + " games.fresh.slots.fresh_tiny_golf.origin (576 blocks from everything else keeps it out of sight),"
                + " or lower view-distance to 5", l.fix(), "the course moves (its clear is clean), or view distance 5");

        Box arena = Box.sized(1000, 176, 0, 48, 40, 48);
        Line extras = rows(facts(List.of(new Place(Kind.CLUBHOUSE, "clubhouse", "the Clubhouse", club),
                new Place(Kind.ARENA, "falling_floors", "Falling Floors", Box.sized(80, 176, 0, 48, 40, 48)),
                new Place(Kind.ARENA, "far", "far", arena)), List.of())).get(1);
        assertTrue(extras.fix().startsWith("move the Clubhouse with games.clubhouse.origin (the old room's blocks stay"
                + " where they are)"), "the first of two extras, with what stays behind: " + extras.fix());

        Line kept = rows(facts(List.of(new Place(Kind.KEPT, "1", "the kept course \"a\" (plot 1)",
                        LegacyBoxes.keep().plot(1)),
                new Place(Kind.KEPT, "2", "the kept course \"b\" (plot 2)", LegacyBoxes.keep().plot(2))), List.of()))
                .get(1);
        assertEquals("From the kept course \"a\" (plot 1), players can see the kept course \"b\" (plot 2) (0 chunks"
                + " away; view distance 10)", kept.what(), "0.35's touching plots");
        assertTrue(kept.fix().startsWith("a kept course stays where it was kept; set games.fresh.keep.plot_gap: 576"),
                "a kept course isn't moved; new ones can be kept apart: " + kept.fix());
    }

    @Test
    void aPointSuchAsTheSpawnIsAlwaysTheOneThatSees() {
        Box course = Box.sized(64, 160, 0, 64, 48, 64);
        Line l = rows(facts(List.of(new Place(Kind.SLOT, "fresh_parkour", "Parkour", course),
                new Place(Kind.SPAWN, "spawn", "the spawn of games", new Box(0, 64, 0, 0, 64, 0))), List.of())).get(1);
        assertTrue(l.what().startsWith("From the spawn of games, players can see Parkour"), "from the spawn: " + l.what());
        assertTrue(l.fix().startsWith("move Parkour by hand"), "the course is still the one to move: " + l.fix());
        Line safe = rows(facts(List.of(new Place(Kind.SAFE_SPOT, "safe_spot", "the safe spot", new Box(0, 64, 0, 0, 64,
                0)), new Place(Kind.SLOT, "fresh_parkour", "Parkour", course)), List.of())).get(1);
        assertTrue(safe.what().startsWith("From the safe spot, players can see Parkour"), "from the safe spot too: "
                + safe.what());
    }

    @Test
    void theSpawnAndTheSafeSpotAreNeverAPairNothingBuiltStandsAtEither() {
        Place safe = new Place(Kind.SAFE_SPOT, "safe_spot", "the safe spot (games.fresh.safe_spot)",
                new Box(0, 100, 0, 0, 100, 0));
        Place spawn = new Place(Kind.SPAWN, "spawn", "the spawn of sky", new Box(0, 100, 0, 0, 100, 0));
        List<Line> out = rows(facts(List.of(safe, spawn), List.of()));
        assertEquals(List.of(Line.ok("View distance used: 10 (games' view distance 10, its send distance 8, nobody"
                        + " there now)"), Line.ok("Nothing else built by the games can be seen from any course, the"
                        + " Clubhouse or the arena (view distance 10)")), out,
                "the safe spot on the void world's platform, at the spawn, is not a WARN, so the check can say All good");
        Slots.Def rings = Slots.SKY_RINGS;
        List<Place> places = new ArrayList<>(halves(Kind.SLOT, rings, rings.half(6080, 128, 4096, 'A', Sight.GAP),
                rings.half(6080, 128, 4096, 'B', Sight.GAP)));
        places.add(safe);
        places.add(spawn);
        assertEquals(Line.ok("Nothing else built by the games can be seen from any course, the Clubhouse or the arena"
                        + " (view distance 10; the closest two places are 36 chunks apart, clear up to view distance 34)"),
                rows(facts(places, List.of())).get(1), "the closest two places are the courses' halves, not the two"
                        + " points 0 chunks apart");
    }

    @Test
    void aPairExactlyViewPlusOneChunksApartIsAWarnAndViewPlusTwoIsNot() {
        Box club = Box.sized(0, 160, 0, 32, 16, 32); // chunks 0-1; its reach ends in chunk 2
        Box edge = Box.sized(16 * (VIEW + 3), 160, 0, 64, 16, 48);
        Box past = Box.sized(16 * (VIEW + 4), 160, 0, 64, 16, 48);
        Line l = rows(facts(List.of(new Place(Kind.CLUBHOUSE, "clubhouse", "the Clubhouse", club),
                new Place(Kind.SLOT, "fresh_tiny_golf", "Tiny Golf", edge)), List.of())).get(1);
        assertEquals(Status.WARN, l.status(), "V + 1 chunks away is sent, so it is a WARN: " + l);
        assertEquals("From the Clubhouse, players can see Tiny Golf (" + (VIEW + 1) + " chunks away; view distance "
                + VIEW + ")", l.what(), "exactly V + 1 away");
        Line clear = rows(facts(List.of(new Place(Kind.CLUBHOUSE, "clubhouse", "the Clubhouse", club),
                new Place(Kind.SLOT, "fresh_tiny_golf", "Tiny Golf", past)), List.of())).get(1);
        assertEquals(Status.OK, clear.status(), "V + 2 chunks away is not sent: " + clear);
        assertTrue(clear.what().contains("the closest two places are " + (VIEW + 2) + " chunks apart, clear up to view"
                + " distance " + VIEW + ")"), "and clear up to exactly V: " + clear.what());
    }

    @Test
    void aRaceNightStandSetFarFromItsCourseIsAWarnThatSaysToSetItNearer() {
        Slots.Def rings = Slots.SKY_RINGS;
        Box a = rings.half(6080, 128, 4096, 'A', Sight.GAP);
        Place stand = new Place(Kind.STAND, "cool_jumps", "the Race Night stand of \"Cool Jumps\"",
                new Box(a.minX() - 16 * 20, 150, a.minZ(), a.minX() - 16 * 20, 150, a.minZ()), "cool_jumps");
        List<Place> places = new ArrayList<>(halves(Kind.SLOT, rings, a, rings.half(6080, 128, 4096, 'B', Sight.GAP)));
        places.add(stand);
        List<Line> out = rows(new SightCheck.Facts("games", 32, "why", places, List.of()));
        Line l = out.get(1);
        assertEquals(Status.WARN, l.status(), "spectators on the stand see a course: " + out);
        assertEquals("From the Race Night stand of \"Cool Jumps\", players can see Sky Rings (19 chunks away; view"
                + " distance 32)", l.what(), "in the spec's words: the stand is the one that sees");
        assertTrue(l.fix().startsWith("set the stand nearer its course: /hcm games event stand cool_jumps set"),
                "the stand is what moves: " + l.fix());
        assertEquals(Sight.REACH, stand.reach(), "spectators move about on a stand: a spectator's reach");

        Place kept = new Place(Kind.KEPT, "3", "the kept course \"cool_jumps\" (plot 3)",
                Box.sized(1760, 128, 7296, 144, 176, 336), "cool_jumps");
        Place own = new Place(Kind.STAND, "cool_jumps", "the Race Night stand of \"Cool Jumps\"",
                new Box(1700, 150, 7300, 1700, 150, 7300), "cool_jumps");
        Place spawn = new Place(Kind.SPAWN, "spawn", "the spawn of games", new Box(1690, 150, 7300, 1690, 150, 7300));
        List<Line> beside = rows(facts(List.of(kept, own, spawn), List.of()));
        assertEquals(Status.WARN, beside.get(1).status(), "the spawn still sees the kept course: " + beside);
        assertTrue(beside.get(1).what().startsWith("From the spawn of games, players can see the kept course"),
                "that pair is one: " + beside.get(1));
        assertEquals(2, beside.size(), "but a stand beside its own kept course, and a stand and the spawn (nothing built"
                + " at either), are not: " + beside);
    }

    @Test
    void theStandsReadAreTheKeptAndHandBuiltCoursesOwnInTheGamesWorldForTheirLayout() {
        Course hand = new Course("cool_jumps", TrialKind.BOAT, "Cool Jumps", Tier.EASY, "games",
                new Course.Spot(100.5, 150, 200.5, 0f, 0f), List.of(), new Course.Mark(140.5, 150, 200.5, 2.0),
                140.0, null, true, false, 1);
        Course elsewhere = new Course("far_away", TrialKind.BOAT, "Far Away", Tier.EASY, "creative",
                new Course.Spot(0.5, 70, 0.5, 0f, 0f), List.of(), new Course.Mark(10.5, 70, 0.5, 2.0), 60.0, null, true,
                false, 1);
        GenTag tag = new GenTag("fresh_boat", "boat", 2, 20_731, 0, 1L, 'A', "abc", 1, 2, 3, List.of(), List.of(), 1L, 7);
        Course fresh = new Course("fresh_boat", TrialKind.BOAT, "Ice Boat", Tier.EASY, "games",
                new Course.Spot(6090.5, 161, 5890.5, 0f, 0f), List.of(), new Course.Mark(6100.5, 161, 5890.5, 2.0),
                150.0, null, true, false, 1, tag);
        Map<String, Course> courses = Map.of(hand.id(), hand, elsewhere.id(), elsewhere, fresh.id(), fresh);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("race.stand.cool_jumps", RaceTrack.encodeStand(hand.layoutHash(), new Point(90.5, 151, 210.5)));
        meta.put("race.stand.far_away", RaceTrack.encodeStand(elsewhere.layoutHash(), new Point(5, 70, 5)));
        meta.put("race.stand.fresh_boat", RaceTrack.encodeStand(fresh.layoutHash(), new Point(6100, 170, 5900)));
        meta.put("race.stand.gone", RaceTrack.encodeStand(1, new Point(0, 0, 0)));
        meta.put("race.grid.cool_jumps", "1|0,0,0,0");
        List<Place> stands = SightCheck.stands(meta, courses, "GAMES");
        assertEquals(List.of(new Place(Kind.STAND, "cool_jumps", "the Race Night stand of \"Cool Jumps\"",
                new Box(90, 151, 210, 90, 151, 210), "cool_jumps")), stands, "only the hand-built course's stand in"
                + " the Games world (matched ignoring case): not another world's, a Fresh course's (built in), a gone"
                + " course's, or a grid");
        meta.put("race.stand.cool_jumps", RaceTrack.encodeStand(hand.layoutHash() + 1, new Point(90.5, 151, 210.5)));
        assertEquals(List.of(), SightCheck.stands(meta, courses, "games"), "a stand set for another layout is"
                + " dropped when it is next used, so it isn't one");
    }

    @Test
    void atMostEightPairsGetALineAndTheRestAreCountedNearestFirst() {
        List<Place> places = new ArrayList<>();
        for (Slots.Def d : List.of(Slots.DAILY_PARKOUR_EASY, Slots.DAILY_PARKOUR_MEDIUM, Slots.DAILY_PARKOUR_HARD,
                Slots.SKY_RINGS, Slots.DAILY_GOLF, Slots.TINY_GOLF, Slots.EASY_DROPPER, Slots.FRESH_DROPPER)) {
            places.addAll(halves(Kind.SLOT, d, LegacyBoxes.half(d, 'A'), LegacyBoxes.half(d, 'B')));
        }
        List<Line> out = rows(facts(places, List.of()));
        List<Line> pairs = out.subList(1, out.size());
        assertEquals(SightCheck.MAX_PAIRS + 1, pairs.size(), "eight lines and one more: " + out.size());
        assertTrue(pairs.stream().allMatch(l -> l.status() == Status.WARN), "each a WARN");
        int last = -1;
        for (Line l : pairs.subList(0, SightCheck.MAX_PAIRS)) {
            int chunks = Integer.parseInt(l.what().replaceAll(".*\\((\\d+) chunks? away.*", "$1"));
            assertTrue(chunks >= last, "nearest first: " + l.what());
            last = chunks;
        }
        assertTrue(pairs.get(SightCheck.MAX_PAIRS).what().matches("\\.\\.\\.and \\d+ more pairs of places that can"
                + " see each other"), "the rest counted: " + pairs.get(SightCheck.MAX_PAIRS).what());
    }

    @Test
    void noWorldSaysNothingMoreAndNoFactsNothingAtAll() {
        assertEquals(List.of(), rows(null), "no facts: no lines");
        assertEquals(List.of(), rows(new SightCheck.Facts(" ", VIEW, "", List.of(new Place(Kind.SPAWN, "spawn", "s",
                new Box(0, 0, 0, 0, 0, 0))), List.of())), "no world: Fresh Courses' section says so");
        List<Line> alone = rows(facts(List.of(new Place(Kind.SPAWN, "spawn", "the spawn", new Box(0, 0, 0, 0, 0, 0))),
                List.of()));
        assertEquals(Line.ok("Nothing else built by the games can be seen from any course, the Clubhouse or the arena"
                + " (view distance 10)"), alone.get(1), "one place: clear, with no closest pair to name");
    }

    @Test
    void theSectionRunsInTheCheckAfterTheClubhouseAndNeverFails() {
        GamesCheck.Facts f = new Minimal() {
            @Override
            public SightCheck.Facts sight() {
                Slots.Def d = Slots.DAILY_PARKOUR_EASY;
                return facts(halves(Kind.SLOT, d, LegacyBoxes.half(d, 'A'), LegacyBoxes.half(d, 'B')),
                        List.of(new Ground("games", true, false, false)));
            }
        };
        List<Line> out = GamesCheck.run(f);
        assertTrue(out.stream().anyMatch(l -> l.what().startsWith("From Easy Parkour, players can see its own spare"
                + " half")), "the pair is there: " + out);
        assertTrue(out.stream().anyMatch(l -> l.what().startsWith("The spawn of 'games' has nothing under it")),
                "and the spawn line");
        assertTrue(out.stream().noneMatch(l -> l.status() == Status.FAIL && l.what().contains("see")),
                "none of it is a FAIL");
        GamesCheck.Facts none = new Minimal();
        assertTrue(GamesCheck.run(none).stream().noneMatch(l -> l.what().startsWith("View distance used")),
                "no sight facts: no section");
    }

    @Test
    void keptPlotsThatDontFitTheWorldAreOneWarnWithTheirNumbers() {
        GamesCheck.Fresh fr = new GamesCheck.Fresh(true, "weekly", "New courses every Monday", "games", true, true,
                List.of(), List.of(), null, null, "x 1760..5503, 24 plots", Map.of(24, "plot 24 reaches past the world"
                + " border (x 5360..5503)", 19, "plot 19 reaches past the world border (x 1760..1903)", 20, "x",
                21, "x", 22, "x", 23, "x", 3, "the world's spawn is inside plot 3"));
        List<Line> out = new ArrayList<>();
        GamesCheck.fresh(fr, out);
        Line l = out.get(out.size() - 1);
        assertEquals(Status.WARN, l.status(), "a WARN, not a FAIL: the other plots are used");
        List<Line> plots = out.stream().filter(x -> x.what().contains("kept-course plot")).toList();
        assertEquals(3, plots.size(), "one WARN per kind of problem: " + plots);
        assertEquals("1 kept-course plot (3) can't be used: the world's spawn is inside plot 3", plots.get(0).what(),
                "the spawn's plot, lowest first");
        assertTrue(plots.get(0).fix().startsWith("move the world's spawn out of the keep area (stand elsewhere in games"
                + " and use /setworldspawn)"), "a bigger border can't fix a spawn inside a plot: " + plots.get(0).fix());
        assertEquals("2 kept-course plots (19, 24) can't be used: plot 19 reaches past the world border (x"
                + " 1760..1903)", plots.get(1).what(), "the border's plots, as ranges, with the first one's problem");
        assertTrue(plots.get(1).fix().startsWith("make the world border bigger (stand in games and use /worldborder"
                + " set)"), "with the border's fix: " + plots.get(1).fix());
        assertEquals("4 kept-course plots (20-23) can't be used: x", plots.get(2).what(),
                "a problem of no known kind is still said, with the general fix");
        assertEquals(Status.WARN, plots.get(2).status(), "each a WARN");
        assertEquals("1", GamesCheck.plotList(List.of(1)), "one plot");
        assertEquals("1-3, 5, 7-8", GamesCheck.plotList(List.of(1, 2, 3, 5, 7, 8)), "runs as ranges");
        List<Line> fine = new ArrayList<>();
        GamesCheck.fresh(new GamesCheck.Fresh(true, "weekly", "s", "games", true, true, List.of(), List.of(), null,
                null, "k"), fine);
        assertTrue(fine.stream().noneMatch(x -> x.what().contains("kept-course plot")), "every plot fits: no line");
    }

    /** A server with nothing set up beyond what the check needs to run. */
    private static class Minimal implements GamesCheck.Facts {

        @Override
        public boolean serviceUp() {
            return true;
        }

        @Override
        public boolean gamesEnabled() {
            return true;
        }

        @Override
        public List<String> economyWorlds() {
            return List.of("world");
        }

        @Override
        public List<String> gamesWorlds() {
            return List.of("games");
        }

        @Override
        public boolean worldLoaded(String world) {
            return true;
        }

        @Override
        public String gameMode(String world) {
            return "ADVENTURE";
        }

        @Override
        public GamesCheck.MvInv mvInventories() {
            return null;
        }

        @Override
        public List<Object> restartTimes() {
            return List.of();
        }

        @Override
        public String restartStatus() {
            return "";
        }

        @Override
        public GamesCheck.Fresh fresh() {
            return null;
        }

        @Override
        public List<GamesCheck.Course> courses() {
            return List.of();
        }

        @Override
        public GamesCheck.Web web() {
            return null;
        }
    }
}
