package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.world.KitItems;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Today's pick among the courses, with no server.
 *
 * <p>Pinned here: a course is today's pick by its own id, and every course is when the game
 * itself ({@code /hcm games feature trials}) is — pinning the game used to pay no course at all.
 *
 * <p>And daily courses (GEN-SPEC §4.2, §5.4): they come first, in slot order, before the
 * hand-built courses' easiest-first order; their times go on the layout's own board; and only
 * Sky Rings (a generated elytra course) gets the "open your wings" tip.
 *
 * <p>And Sky Rings' rocket budget (RINGS-SPEC §5): the kit holds the whole run's rockets with lore that says
 * so; reaching a ring fills nothing; going back tops the stack up to what the rest of the course needs, only
 * when it holds fewer and never above; the clock line counts rings and rockets; a ring's title says what the
 * next leg is; every timed run starts on the whole budget, whatever a warm-up spent; and a hand-built elytra
 * course hands out 3 and fills them back at every ring, as it always did.
 */
class TimeTrialsTest {

    @Test
    void aCourseIsTodaysPickByItsOwnIdOrWhenTheWholeGameIs() {
        assertTrue(TimeTrials.featured(Set.of("cliffs")::contains, "cliffs"), "the course itself is pinned");
        assertFalse(TimeTrials.featured(Set.of("cliffs")::contains, "river_run"), "another course is not");
        assertTrue(TimeTrials.featured(Set.of("trials")::contains, "river_run"),
                "the game pinned: every course is today's pick");
        assertFalse(TimeTrials.featured(Set.of("test_snake")::contains, "river_run"), "another game pinned: no course");
    }

    private static final GenTag RINGS = new GenTag("fresh_rings", "rings", 1, 20_725, 0, 1L, 'A', "abcabcabcabc",
            30_000, 60_000, 90_000, List.of(), List.of(), 1L);

    private static Course course(String id, TrialKind kind, Tier tier, String name, GenTag gen) {
        return new Course(id, kind, name, tier, "games", new Course.Spot(0, 64, 0, 0, 0), List.of(),
                new Course.Mark(10, 64, 0, 2), null, null, true, false, 1, gen);
    }

    private static GenTag tag(String slot, int reroll) {
        return new GenTag(slot, "parkour", 1, 20_725, reroll, 1L, 'A', "abcabcabcabc", 1, 2, 3, List.of(), List.of(),
                1L);
    }

    @Test
    void dailyCoursesComeFirstInSlotOrder() {
        Course cliffs = course("cliffs", TrialKind.PARKOUR, Tier.EASY, "Cliffs", null);
        Course river = course("river_run", TrialKind.BOAT, Tier.MEDIUM, "River Run", null);
        Course hard = course("fresh_parkour_hard", TrialKind.PARKOUR, Tier.HARD, "Hard Parkour",
                tag("fresh_parkour_hard", 0));
        Course easy = course("fresh_parkour_easy", TrialKind.PARKOUR, Tier.EASY, "Easy Parkour",
                tag("fresh_parkour_easy", 0));
        Course rings = course("fresh_rings", TrialKind.ELYTRA, Tier.EASY, "Sky Rings", RINGS);
        assertEquals(List.of(easy, hard, rings, cliffs, river), TimeTrials.sorted(List.of(river, rings, cliffs, hard,
                easy)), "the daily ones in slot order, then the rest easiest first as before");
    }

    @Test
    void aDailyCoursesTimesGoOnItsLayoutsBoard() {
        Course easy = course("fresh_parkour_easy", TrialKind.PARKOUR, Tier.EASY, "Easy Parkour",
                tag("fresh_parkour_easy", 2));
        assertEquals(GenBoards.day("fresh_parkour_easy", "1:267r2"), TimeTrials.board(easy),
                "the layout's own board: a daily edition's key, reroll and all");
        assertEquals("course:cliffs", TimeTrials.board(course("cliffs", TrialKind.PARKOUR, Tier.EASY, "Cliffs", null)),
                "a hand-built course keeps its all-time board");
    }

    @Test
    void onlySkyRingsGetsTheWingsTip() {
        assertTrue(TimeTrials.wingsTipFor(course("fresh_rings", TrialKind.ELYTRA, Tier.EASY, "Sky Rings", RINGS)),
                "a generated elytra course: the tip");
        assertFalse(TimeTrials.wingsTipFor(course("canyon", TrialKind.ELYTRA, Tier.EASY, "Canyon", null)),
                "a hand-built elytra course: as before");
        assertFalse(TimeTrials.wingsTipFor(course("fresh_parkour_easy", TrialKind.PARKOUR, Tier.EASY, "Easy Parkour",
                tag("fresh_parkour_easy", 0))), "parkour has no wings");
        assertFalse(TimeTrials.wingsTipFor(null), "no course");
    }

    // ---- Sky Rings' rocket budget ---------------------------------------------------------------

    /** Four rings and the gold one: a glide, a climb, a level leg, a glide; 2 rockets needed, 4 handed out. */
    private static final Course.Elytra BUDGET = new Course.Elytra(4, List.of(2, 2, 2, 1, 0), "GLFG");

    private static Course skyRings() {
        List<Course.Mark> rings = List.of(new Course.Mark(0, 270, -25, 7), new Course.Mark(0, 255, -125, 7),
                new Course.Mark(0, 290, -230, 7), new Course.Mark(0, 288, -325, 7));
        return new Course("fresh_rings", TrialKind.ELYTRA, "Sky Rings", Tier.EASY, "games",
                new Course.Spot(0.5, 280, 0.5, 180, 0), rings, new Course.Mark(90, 270, -330, 7), 130.0, 32, true,
                false, 1, RINGS, BUDGET);
    }

    private static Course handBuiltElytra() {
        return new Course("canyon", TrialKind.ELYTRA, "Canyon", Tier.EASY, "games", new Course.Spot(0, 100, 0, 0, 0),
                List.of(new Course.Mark(0, 90, 30, 4), new Course.Mark(0, 80, 60, 4)), new Course.Mark(0, 70, 90, 4),
                null, null, true, false, 1);
    }

    /** Items are strings "kit:trials:firework x5"; anything else is someone's own. */
    private static final class Inv implements KitItems.Slots<String> {
        final String[] slots = new String[41];

        @Override
        public String get(int slot) {
            return slots[slot];
        }

        @Override
        public void set(int slot, String item) {
            slots[slot] = item;
        }

        @Override
        public int firstEmpty() {
            for (int i = 0; i < 36; i++) {
                if (slots[i] == null) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public boolean empty(String item) {
            return item == null;
        }

        @Override
        public boolean kit(String item) {
            return item.startsWith("kit:");
        }

        int rockets() {
            return TimeTrials.rocketsLeft(this, 41, Inv::rocket, Inv::amount);
        }

        void topUp(int floor) {
            TimeTrials.topUp(this, 41, Inv::rocket, Inv::amount, (it, n) -> "kit:trials:firework x" + n,
                    n -> "kit:trials:firework x" + n + " (new)", floor);
        }

        long count(String prefix) {
            return Arrays.stream(slots).filter(it -> it != null && it.startsWith(prefix)).count();
        }

        static boolean rocket(String it) {
            return it.startsWith("kit:trials:firework");
        }

        static int amount(String it) {
            String n = it.substring(it.indexOf(" x") + 2);
            return Integer.parseInt(n.contains(" ") ? n.substring(0, n.indexOf(' ')) : n);
        }
    }

    @Test
    void skyRingsHandsOutTheWholeRunsRocketsAndSaysSo() {
        Course c = skyRings();
        assertEquals(4, TimeTrials.kitRockets(c), "the course's budget, not 3");
        assertEquals(List.of("&74 for the whole course.", "&7No refills at the rings.",
                "&7Back to checkpoint gives you only what you still need."), TimeTrials.rocketLore(c), "the lore");
        assertEquals("&7Fly 4 rings in order, then the gold ring. 4 rockets for the whole run, none at the rings.",
                TimeTrials.ringsReady(c), "the line as the run is armed");
    }

    @Test
    void reachingASkyRingsRingFillsNothing() {
        assertFalse(TimeTrials.refillsAtRings(skyRings()), "Sky Rings: none at the rings");
        assertTrue(TimeTrials.refillsAtRings(handBuiltElytra()), "a hand-built elytra course: back to 3, as before");
        assertFalse(TimeTrials.refillsAtRings(course("cliffs", TrialKind.PARKOUR, Tier.EASY, "Cliffs", null)),
                "parkour has no rockets");
    }

    @Test
    void goingBackTopsUpToWhatTheRestNeedsOnlyWhenBelowIt() {
        Course c = skyRings();
        assertEquals(2, TimeTrials.topUpTo(c, -1), "back to the start: need[0]");
        assertEquals(2, TimeTrials.topUpTo(c, 1), "back to ring 2: need[2], the climb and the level leg to come");
        assertEquals(1, TimeTrials.topUpTo(c, 2), "back to ring 3: need[3], the level leg");
        assertEquals(0, TimeTrials.topUpTo(c, 3), "back to the last ring: a glide to the gold, none");
        assertEquals(0, TimeTrials.topUpTo(handBuiltElytra(), 0), "no budget: no top-up rule");

        Inv low = new Inv();
        low.slots[TimeTrials.ROCKET_SLOT] = "kit:trials:firework x1";
        low.topUp(2);
        assertEquals("kit:trials:firework x2", low.slots[TimeTrials.ROCKET_SLOT], "1 under a need of 2: up to 2");

        Inv saved = new Inv();
        saved.slots[4] = "kit:trials:firework x3";
        saved.topUp(2);
        assertEquals("kit:trials:firework x3", saved.slots[4], "3 saved over a need of 2: kept, never cut, never more");
        assertEquals(3, saved.rockets());

        Inv none = new Inv();
        none.slots[TimeTrials.ROCKET_SLOT] = "Mini #42";
        none.topUp(1);
        assertEquals("kit:trials:firework x1 (new)", none.slots[TimeTrials.ROCKET_SLOT], "used up: a fresh stack of 1");
        assertEquals(1, none.count("Mini #42"), "and what landed in the slot moves aside, never lost");

        Inv done = new Inv();
        done.topUp(0);
        assertEquals(0, done.count("kit:"), "nothing needed and none held: no empty stack handed out");
        assertEquals(0, done.rockets());
    }

    @Test
    void theClockLineCountsRingsAndRockets() {
        Course c = skyRings();
        assertEquals("2/4 rings &7· &65 rockets", TimeTrials.clockWhere(c, 2, 4, 5), "rings and the rockets held");
        assertEquals("on to the gold ring! &7· &61 rocket", TimeTrials.clockWhere(c, 4, 4, 1), "after the last ring");
        assertEquals("1/2 rings", TimeTrials.clockWhere(handBuiltElytra(), 1, 2, -1),
                "a hand-built elytra course: rings, no budget to count");
        assertEquals("on to the finish!", TimeTrials.clockWhere(handBuiltElytra(), 2, 2, -1), "and its finish");
        assertEquals("2/5 checkpoints", TimeTrials.clockWhere(course("cliffs", TrialKind.PARKOUR, Tier.EASY, "Cliffs",
                null), 2, 5, -1), "parkour as it always was");
    }

    @Test
    void aRingsTitleSaysWhatTheNextLegIs() {
        Course c = skyRings();
        assertEquals(TimeTrials.LEG_GLIDE, TimeTrials.nextLeg(c, 0), "ring 1, then a glide");
        assertEquals(TimeTrials.LEG_CLIMB, TimeTrials.nextLeg(c, 1), "ring 2, then a climb");
        assertEquals(TimeTrials.LEG_LEVEL, TimeTrials.nextLeg(c, 2), "ring 3, then a level leg");
        assertEquals(TimeTrials.LEG_GLIDE, TimeTrials.nextLeg(c, 3), "the last ring, then a glide to the gold");
        assertEquals("", TimeTrials.nextLeg(c, 4), "past the end: nothing");
        assertEquals("", TimeTrials.nextLeg(handBuiltElytra(), 0), "a hand-built course: as before");
        for (String word : List.of(TimeTrials.LEG_GLIDE, TimeTrials.LEG_CLIMB, TimeTrials.LEG_LEVEL)) {
            assertTrue(word.chars().allMatch(ch -> ch >= 0x20 && ch < 0x7F), "plain ASCII for Bedrock: " + word);
        }
    }

    @Test
    void goingBackSaysTheRingAndTheRocketsOnlyWhenTheyChanged() {
        Course c = skyRings();
        assertEquals("&eBack to ring 3 &7· &62 rockets", TimeTrials.backLine(c, 2, 1, 2), "topped up: says so");
        assertEquals("&eBack to ring 3", TimeTrials.backLine(c, 2, 3, 3), "nothing changed: no count");
        assertEquals("&eBack to the start", TimeTrials.backLine(c, -1, 2, 2), "the start");
        assertEquals("&eBack to checkpoint 1", TimeTrials.backLine(handBuiltElytra(), 0, -1, -1),
                "a hand-built course: as before");
    }

    @Test
    void aTimedRunStartsOnTheWholeBudgetWhateverTheWarmUpSpent() throws IOException {
        Course c = skyRings();
        assertEquals(4, TimeTrials.goRockets(c), "Sky Rings: the whole budget at the start of a timed run");
        assertEquals(0, TimeTrials.goRockets(handBuiltElytra()), "a hand-built course fills at the rings: no rule");
        assertEquals(0, TimeTrials.goRockets(course("cliffs", TrialKind.PARKOUR, Tier.EASY, "Cliffs", null)),
                "parkour has no rockets");

        Inv flown = new Inv();
        flown.slots[TimeTrials.ROCKET_SLOT] = "kit:trials:firework x1"; // a warm-up lap, then a reset to need[0]
        flown.topUp(TimeTrials.goRockets(c));
        assertEquals("kit:trials:firework x4", flown.slots[TimeTrials.ROCKET_SLOT], "back to the whole 4, not need[0]");

        Inv spent = new Inv();
        spent.topUp(TimeTrials.goRockets(c));
        assertEquals(4, spent.rockets(), "every rocket fired in the warm-up: a fresh stack of 4");

        String code = Files.readString(Path.of("src/main/java/com/dierks/homecraft/games/trial/TimeTrials.java"));
        assertTrue(body(code, "void toStart(Player p, TrialRun run)").contains("fullRockets(p, run.course);"),
                "back at the start for the 3-2-1 (a solo warm-up's end): the whole budget");
        String countdown = body(code, "private void countdown(Player p, TrialRun run, long now)");
        int go = countdown.indexOf("run.phase = TrialRun.Phase.RUNNING;");
        int full = countdown.lastIndexOf("fullRockets(p, run.course);");
        assertTrue(go > 0 && full > 0 && full < go, "a solo Go: the whole budget as the clock starts");
    }

    /** The body of the method declared by {@code signature} in {@code code}, up to its closing brace. */
    static String body(String code, String signature) {
        int at = code.indexOf(signature);
        assertTrue(at >= 0, "found: " + signature);
        int end = code.indexOf("\n    }\n", at);
        return code.substring(at, end);
    }

    @Test
    void aHandBuiltElytraCourseIsUnchanged() {
        Course c = handBuiltElytra();
        assertEquals(TimeTrials.ROCKETS, TimeTrials.kitRockets(c), "3 rockets");
        assertEquals(List.of("&7A boost while you glide.", "&7Back to 3 at every ring."), TimeTrials.rocketLore(c),
                "its lore says they come back at every ring");
        assertEquals(TrialKind.ELYTRA.rules(), TimeTrials.rulesFor(c), "its kind's rules");
    }
}
