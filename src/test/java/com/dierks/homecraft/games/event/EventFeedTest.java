package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.FeedWriter.Events;
import com.dierks.homecraft.storage.EventDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Race Night's part of the website feed (EVENTS-DROPPER-SPEC §A.7), its pure pieces. Pinned here:
 * a night's state reads {@code open}, {@code racing}, {@code break} or {@code results} (and a night
 * not on now has none); a past night reads {@code done} or {@code called_off}; its result is by
 * place in points, at most 8 rows, and names only while the owner shows them; {@code racers} is a
 * count of who raced; and it is dated when it ended.
 */
class EventFeedTest {

    private static final long NOW = 1_790_967_600_000L;
    private static final String ID = "rn-20260925-1900";

    @Test
    void aNightsStateReadsAsTheWebsiteExpects() {
        assertEquals("open", EventFeed.liveState(EventMachine.Phase.OPEN), "joining");
        assertEquals("racing", EventFeed.liveState(EventMachine.Phase.WARMUP), "the warm-up is at the track");
        assertEquals("racing", EventFeed.liveState(EventMachine.Phase.GRID), "on the grid");
        assertEquals("racing", EventFeed.liveState(EventMachine.Phase.RACING), "racing");
        assertEquals("break", EventFeed.liveState(EventMachine.Phase.BREAK), "a break");
        assertEquals("results", EventFeed.liveState(EventMachine.Phase.DONE), "the results");
        assertNull(EventFeed.liveState(EventMachine.Phase.SCHEDULED), "not on yet: no live part");
        assertNull(EventFeed.liveState(EventMachine.Phase.CALLED_OFF), "called off: no live part");
        assertEquals("done", EventFeed.recentState(EventDao.DONE), "done");
        assertEquals("called_off", EventFeed.recentState(EventDao.CALLED_OFF), "called off");
        assertNull(EventFeed.recentState(EventDao.RUNNING), "a night still on is never a past one");
    }

    private static EventDao.EntryRow entry(int n, String name, Integer place, int points) {
        return new EventDao.EntryRow(ID, new UUID(0, n), name, NOW + n, EventDao.IN, points, place, 0, null);
    }

    @Test
    void aPastNightIsItsResultByPlaceWithNamesOnlyWhenShown() {
        EventDao.EventRow row = new EventDao.EventRow(ID, "fresh_boat", NOW - 600_000, NOW, EventDao.DONE, "", 3, true,
                "2920", "", NOW, NOW + 420_000, "");
        List<EventDao.EntryRow> entries = List.of(entry(1, "Lee", 3, 18), entry(2, "Sam", 1, 28), entry(3, "Ava", 2, 26),
                entry(4, "Mo", null, 0));
        Events.Recent named = EventFeed.recent(row, entries, "Ice Boat", true);
        assertEquals(ID, named.id(), "its id");
        assertEquals(NOW + 420_000, named.at(), "dated when it ended");
        assertEquals("fresh_boat", named.courseId(), "the track");
        assertEquals("Ice Boat", named.courseName(), "by name");
        assertEquals(3, named.racers(), "a count of who raced (Mo never scored)");
        assertEquals("done", named.state(), "done");
        assertEquals(List.of(new Events.Top(1, 28, "Sam"), new Events.Top(2, 26, "Ava"), new Events.Top(3, 18, "Lee")),
                named.top(), "by place, in points");
        Events.Recent hidden = EventFeed.recent(row, entries, "Ice Boat", false);
        assertTrue(hidden.top().stream().allMatch(t -> t.holder() == null), "no names while they aren't shown");
    }

    @Test
    void aBigNightKeepsAtMostEightRows() {
        EventDao.EventRow row = new EventDao.EventRow(ID, "ice", NOW, NOW, EventDao.DONE, "", 3, false, "", "", NOW,
                null, "");
        List<EventDao.EntryRow> entries = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            entries.add(entry(i, "R" + i, i, 40 - i));
        }
        Events.Recent r = EventFeed.recent(row, entries, "Ice", true);
        assertEquals(Events.ROWS, r.top().size(), "8 rows");
        assertEquals(12, r.racers(), "but every racer counted");
        assertEquals(NOW, r.at(), "no end stored: its start");
    }

    @Test
    void aNightCalledOffBeforeRacingCountsItsSignUps() {
        EventDao.EventRow row = new EventDao.EventRow(ID, "ice", NOW, NOW, EventDao.CALLED_OFF, "", 0, false, "", "",
                NOW, NOW, "called off: too few racers");
        Events.Recent r = EventFeed.recent(row, List.of(entry(1, "Ava", null, 0)), "Ice", true);
        assertEquals("called_off", r.state(), "called off");
        assertEquals(1, r.racers(), "one signed up");
        assertTrue(r.top().isEmpty(), "no result");
    }
}
