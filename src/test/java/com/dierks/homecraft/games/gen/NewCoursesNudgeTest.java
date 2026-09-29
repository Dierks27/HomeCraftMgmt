package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "New courses this week!" (EXTRAS E2): one line per player per set, never during a world game or
 * outside the games' worlds, after a screen closes (or a minute), only once the whole set is up (or
 * a while after its first course), not again for a reroll or after a restart, never for a player
 * who turned it off or with {@code announce} off; and its words follow the cadence.
 */
class NewCoursesNudgeTest {

    private static final long MIN = 60_000L;
    /** The first day of a weekly edition (a Monday). */
    private static final long WEEK = Edition.EPOCH_DAY + 7 * 38;

    /** The server, as far as the nudge can tell. */
    private static class Fake implements NewCoursesNudge.Host {
        long now = 1_000_000_000L;
        boolean announce = true;
        final Map<String, NewCoursesNudge.Slot> slots = new LinkedHashMap<>();
        final Map<UUID, NewCoursesNudge.Viewer> online = new LinkedHashMap<>();
        final Map<String, String> prefs = new HashMap<>();
        final List<String> said = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        boolean prefsBroken;

        Fake() {
            for (Slots.Def d : Slots.ALL) {
                slots.put(d.id(), new NewCoursesNudge.Slot(d.id(), d.enabled(), null, false));
            }
        }

        /** Every shipped-on slot up in the weekly edition starting {@code day} (reroll {@code reroll}). */
        void allUp(long day, int reroll) {
            for (Slots.Def d : Slots.ALL) {
                slots.put(d.id(), new NewCoursesNudge.Slot(d.id(), d.enabled(), d.enabled() ? tag(d, day, reroll, 7) : null,
                        d.enabled()));
            }
        }

        void up(Slots.Def d, long day) {
            slots.put(d.id(), new NewCoursesNudge.Slot(d.id(), true, tag(d, day, 0, 7), true));
        }

        void join(UUID id) {
            online.put(id, new NewCoursesNudge.Viewer(id, true, true, false, false));
        }

        @Override
        public long now() {
            return now;
        }

        @Override
        public boolean announce() {
            return announce;
        }

        @Override
        public List<NewCoursesNudge.Slot> slots() {
            return new ArrayList<>(slots.values());
        }

        @Override
        public List<NewCoursesNudge.Viewer> online() {
            return new ArrayList<>(online.values());
        }

        @Override
        public String pref(UUID player, String key) throws SQLException {
            if (prefsBroken) {
                throw new SQLException("disk full");
            }
            return prefs.get(player + "|" + key);
        }

        @Override
        public void setPref(UUID player, String key, String value) throws SQLException {
            if (prefsBroken) {
                throw new SQLException("disk full");
            }
            prefs.put(player + "|" + key, value);
        }

        @Override
        public void tell(UUID player, String line) {
            said.add(player + " " + line);
        }

        @Override
        public void failed(String what, Exception e) {
            failures.add(what);
        }
    }

    private static GenTag tag(Slots.Def d, long day, int reroll, int cadence) {
        return new GenTag(d.id(), d.generator(), 1, day, reroll, 42L, 'A', "hash", 60_000L, 0, 0, List.of(), List.of(),
                0L, cadence);
    }

    private final Fake host = new Fake();
    private final NewCoursesNudge nudge = new NewCoursesNudge(host);
    private final UUID alex = UUID.randomUUID();
    private final UUID sam = UUID.randomUUID();

    /** Tick once a second for {@code seconds}. */
    private void run(int seconds) {
        for (int i = 0; i < seconds; i++) {
            nudge.tick();
            host.now += 1000;
        }
    }

    private long toldTo(UUID who) {
        return host.said.stream().filter(l -> l.startsWith(who.toString())).count();
    }

    @Test
    void everyoneHearsItOncePerSetAndAgainForTheNextSet() {
        host.join(alex);
        host.join(sam);
        host.allUp(WEEK, 0);
        run(10);
        assertEquals(1, toldTo(alex), "Alex hears it once");
        assertEquals(1, toldTo(sam), "and so does Sam");
        assertEquals(alex + " &aNew courses this week! &7Easy, Parkour, Hard, Sky Rings and Golf - &e/hcm play",
                host.said.get(0), "the line names this week's courses");
        run(120);
        assertEquals(2, host.said.size(), "not again for the same set");

        host.allUp(WEEK + 7, 0);
        run(3);
        assertEquals(2, toldTo(alex), "next week's set is told again");
    }

    @Test
    void aRerollIsNotANewSetAndARestartDoesNotRepeatIt() {
        host.join(alex);
        host.allUp(WEEK, 0);
        run(10);
        host.allUp(WEEK, 1);
        run(10);
        assertEquals(1, toldTo(alex), "an admin's reroll of this week's courses is the same set");

        NewCoursesNudge afterRestart = new NewCoursesNudge(host);
        for (int i = 0; i < 20; i++) {
            afterRestart.tick();
            host.now += 1000;
        }
        assertEquals(1, toldTo(alex), "who was told is kept, so a restart doesn't say it again");
    }

    @Test
    void someoneWhoJustJoinedHearsItAFewSecondsLater() {
        host.allUp(WEEK, 0);
        run(30);
        host.join(alex);
        run(3);
        assertEquals(0, toldTo(alex), "not in the first seconds, among the join messages");
        run(5);
        assertEquals(1, toldTo(alex), "then once");
    }

    @Test
    void notDuringAWorldGameNorOutsideTheGamesWorldsNorWithoutPermission() {
        host.allUp(WEEK, 0);
        host.online.put(alex, new NewCoursesNudge.Viewer(alex, true, true, true, false));
        host.online.put(sam, new NewCoursesNudge.Viewer(sam, true, false, false, false));
        UUID kim = UUID.randomUUID();
        host.online.put(kim, new NewCoursesNudge.Viewer(kim, false, true, false, false));
        run(10 * 60);
        assertEquals(List.of(), host.said, "in a course, in a world without games, or unable to play: nobody hears it");

        host.online.put(alex, new NewCoursesNudge.Viewer(alex, true, true, false, false));
        host.online.put(sam, new NewCoursesNudge.Viewer(sam, true, true, false, false));
        run(2);
        assertEquals(1, toldTo(alex), "out of the course, Alex hears it");
        assertEquals(1, toldTo(sam), "back in a games world, Sam hears it");
        assertEquals(0, toldTo(kim), "Kim still can't play");
    }

    @Test
    void someoneOnAScreenHearsItWhenTheyCloseItOrAfterAMinute() {
        host.allUp(WEEK, 0);
        host.online.put(alex, new NewCoursesNudge.Viewer(alex, true, true, false, true));
        host.online.put(sam, new NewCoursesNudge.Viewer(sam, true, true, false, true));
        run(20);
        assertEquals(List.of(), host.said, "nobody mid-screen");
        host.online.put(alex, new NewCoursesNudge.Viewer(alex, true, true, false, false));
        run(1);
        assertEquals(1, toldTo(alex), "Alex closed the screen");
        run(30);
        assertEquals(0, toldTo(sam), "Sam is still on it, under a minute");
        run(20);
        assertEquals(1, toldTo(sam), "a minute later Sam hears it anyway");
    }

    @Test
    void itWaitsForTheWholeSetOrAWhileAfterItsFirstCourse() {
        host.join(alex);
        host.up(Slots.DAILY_PARKOUR_EASY, WEEK);
        run(60);
        assertEquals(List.of(), host.said, "the other courses are still being built");
        for (Slots.Def d : List.of(Slots.DAILY_PARKOUR_MEDIUM, Slots.DAILY_PARKOUR_HARD, Slots.SKY_RINGS)) {
            host.up(d, WEEK);
        }
        run((int) (NewCoursesNudge.SETTLE_MS / 1000));
        assertEquals(1, toldTo(alex), "golf's build failed: the line goes out a while after the first course");
        assertTrue(host.said.get(0).contains("Easy, Parkour, Hard and Sky Rings - "), "naming what is up: " + host.said);
    }

    @Test
    void newsOffIsNeverToldAndAnnounceOffTellsNobody() {
        host.join(alex);
        host.join(sam);
        host.prefs.put(sam + "|" + NewCoursesNudge.PREF_NEWS, "off");
        host.allUp(WEEK, 0);
        run(10);
        assertEquals(1, toldTo(alex), "Alex has it on");
        assertEquals(0, toldTo(sam), "Sam turned it off");

        host.prefs.remove(sam + "|" + NewCoursesNudge.PREF_NEWS); // /hcm play news on
        host.announce = false;
        host.allUp(WEEK + 7, 0);
        run(30);
        assertEquals(1, toldTo(alex), "games.fresh.announce: false tells nobody");
        assertEquals(0, toldTo(sam), "not even with it back on");
        host.announce = true;
        run(3);
        assertEquals(2, toldTo(alex), "on again: this set is told");
        assertEquals(1, toldTo(sam), "Sam turned it back on, so Sam hears this one");
    }

    @Test
    void nothingUpIsNothingToSayAndABrokenDatabaseNeverSaysItTwice() {
        host.join(alex);
        run(30);
        assertEquals(List.of(), host.said, "no course is up");
        host.prefsBroken = true;
        host.allUp(WEEK, 0);
        run(30);
        assertEquals(List.of(), host.said, "can't tell whether Alex heard it: better not to say it twice");
        assertFalse(host.failures.isEmpty(), "and the console hears why");
    }

    @Test
    void aFailingTickIsLoggedOnceAndNeverThrown() {
        Fake broken = new Fake() {
            @Override
            public List<NewCoursesNudge.Viewer> online() {
                throw new IllegalStateException("the server said no");
            }
        };
        NewCoursesNudge n = new NewCoursesNudge(broken);
        for (int i = 0; i < 5; i++) {
            n.tickSafely(); // must not throw: Fresh Courses' guard would switch the whole game off
        }
        assertEquals(1, broken.failures.size(), "logged once, not every second: " + broken.failures);
    }

    @Test
    void theNewestSetNamesItsCoursesInOrderWithBothGolfCoursesAsGolf() {
        Fake f = new Fake();
        assertNull(NewCoursesNudge.newest(f.slots()), "nothing up, no set");
        f.allUp(WEEK, 0);
        NewCoursesNudge.NewSet set = NewCoursesNudge.newest(f.slots());
        assertEquals("7:38", set.key(), "the edition, without its reroll");
        assertEquals(List.of("Easy", "Parkour", "Hard", "Sky Rings", "Golf"), set.names(), "golf and tiny golf are Golf");
        assertTrue(set.complete(), "every course that is on is up");
        f.slots.put(Slots.SKY_RINGS.id(), new NewCoursesNudge.Slot(Slots.SKY_RINGS.id(), true,
                tag(Slots.SKY_RINGS, WEEK - 7, 0, 7), false));
        set = NewCoursesNudge.newest(f.slots());
        assertFalse(set.complete(), "Sky Rings still shows last week's");
        assertFalse(set.names().contains("Sky Rings"), "and isn't named");
    }

    @Test
    void theWordsFollowTheCadenceAndAreKidSafe() {
        List<String> names = List.of("Easy", "Parkour", "Hard", "Sky Rings", "Golf");
        assertEquals("&aNew courses this week! &7Easy, Parkour, Hard, Sky Rings and Golf - &e/hcm play",
                NewCoursesNudge.line(7, names), "weekly");
        assertEquals("&aNew courses today! &7Easy, Parkour, Hard, Sky Rings and Golf - &e/hcm play",
                NewCoursesNudge.line(1, names), "daily says today");
        assertEquals("&aNew courses are up! &7(new every 3 days) Easy, Parkour, Hard, Sky Rings and Golf - &e/hcm play",
                NewCoursesNudge.line(3, names), "any other cadence says every N days");
        assertEquals("&aNew courses this week! &7Golf - &e/hcm play", NewCoursesNudge.line(7, List.of("Golf")), "one");
        assertEquals("Easy and Hard", NewCoursesNudge.list(List.of("Easy", "Hard")), "two");
        for (int cadence : new int[]{1, 2, 3, 7, 14, 28}) {
            String line = NewCoursesNudge.line(cadence, names);
            assertEquals(List.of(), GenCopy.copyProblems(line), "no banned word, nothing Bedrock can't draw: " + line);
            assertFalse(line.toLowerCase().contains("daily"), "it never says daily: " + line);
            if (cadence != 1) {
                assertFalse(line.contains("today"), "only a daily cadence says today: " + line);
            }
        }
        for (Slots.Def d : Slots.ALL) {
            assertEquals(List.of(), GenCopy.copyProblems(NewCoursesNudge.shortName(d.id())), d.id());
        }
        assertEquals("Ice Boat", NewCoursesNudge.shortName(Slots.ICE_BOAT.id()), "the boat keeps its name");
    }
}
