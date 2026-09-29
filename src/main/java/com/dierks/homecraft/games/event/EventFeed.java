package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.FeedWriter.Events;
import com.dierks.homecraft.storage.EventDao;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Race Night's part of the website feed, {@code /api/arcade}'s {@code events} section
 * (EVENTS-DROPPER-SPEC §A.7), written through C1's {@code FeedWriter.events}: the next night, the
 * start times after it, the night on now with its standings, the last five nights and the month's
 * season board. The writer drops empty parts, and names ({@code holder}) unless the owner shows
 * them; nothing here ever carries a UUID, a balance or a player's prize, and {@code racers} is a
 * count. Skipped nights never appear: only nights that fit are listed.
 */
public final class EventFeed {

    /** At most this many start times after the next night. */
    public static final int UPCOMING = 4;
    /** How far ahead the upcoming nights are looked for (days). */
    static final int AHEAD_DAYS = 14;

    private EventFeed() {
    }

    /** The {@code live.state} of a night in {@code phase}, or {@code null} when it isn't on now. */
    public static String liveState(EventMachine.Phase phase) {
        if (phase == null) {
            return null;
        }
        return switch (phase) {
            case OPEN -> "open";
            case WARMUP, GRID, RACING -> "racing";
            case BREAK -> "break";
            case SETTLING, DONE -> "results";
            case SCHEDULED, CALLED_OFF -> null;
        };
    }

    /** A stored night's state as the feed says it: {@code done} or {@code called_off} ({@code null} if still live). */
    public static String recentState(String stored) {
        if (EventDao.DONE.equals(stored)) {
            return "done";
        }
        return EventDao.CALLED_OFF.equals(stored) ? "called_off" : null;
    }

    /**
     * A past night: when it ended, its track, how many raced, and its result by place (points), each
     * holder only when {@code names}.
     */
    public static Events.Recent recent(EventDao.EventRow row, List<EventDao.EntryRow> entries, String courseName,
                                       boolean names) {
        List<EventDao.EntryRow> placed = new ArrayList<>();
        int racers = 0;
        for (EventDao.EntryRow e : entries) {
            if (e.place() != null) {
                placed.add(e);
            }
            if (e.place() != null || e.points() > 0) {
                racers++;
            }
        }
        if (racers == 0) {
            racers = entries.size();
        }
        placed.sort(Comparator.comparingInt((EventDao.EntryRow e) -> e.place()).thenComparing(e -> e.name()
                .toLowerCase(Locale.ROOT)));
        List<Events.Top> top = new ArrayList<>();
        for (EventDao.EntryRow e : placed) {
            if (top.size() >= Events.ROWS) {
                break;
            }
            top.add(new Events.Top(e.place(), e.points(), names ? e.name() : null));
        }
        long at = row.endedAt() == null ? row.startsAt() : row.endedAt();
        return new Events.Recent(row.id(), at, row.course(), courseName, racers, recentState(row.state()), top);
    }

    /** The standings of a night on now, best first (at most {@link Events#ROWS}), each with the lap they are on. */
    public static List<Events.Standing> standings(NightRunner n, boolean names) {
        List<Events.Standing> out = new ArrayList<>();
        boolean racing = n.phase() == EventMachine.Phase.RACING;
        int targets = Math.max(1, n.targets());
        for (NightStandings.Ranked s : n.standings()) {
            if (out.size() >= Events.ROWS) {
                break;
            }
            NightRunner.Racer r = n.racer(s.player());
            int lap = racing && r != null ? LivePlaces.lap(r.reached(), targets, n.laps()) : 0;
            out.add(new Events.Standing(s.place(), s.points(), lap, n.laps(), names && r != null ? r.name() : null));
        }
        return out;
    }

    /** The whole section now, from the live game. */
    static Events events(RaceNight game, boolean names) {
        NightRunner n = game.night();
        NightRunner shownLive = n != null && liveState(n.phase()) != null ? n
                : game.last() != null ? game.last() : null;
        Events.Live live = null;
        if (shownLive != null) {
            live = new Events.Live(shownLive.plan().id(), liveState(shownLive.phase()), Math.max(0, shownLive.race()),
                    shownLive.plan().races(), shownLive.joined().size(), standings(shownLive, names));
        }
        Events.Next next = null;
        String nextId = null;
        if (n != null && (n.phase() == EventMachine.Phase.SCHEDULED || n.phase() == EventMachine.Phase.OPEN)) {
            next = next(n);
            nextId = n.plan().id();
        }
        List<Long> upcoming = new ArrayList<>();
        for (EventSchedule.Occurrence o : game.upcoming(AHEAD_DAYS)) {
            if (!o.fits() || o.id().equals(nextId) || (n != null && o.id().equals(n.plan().id()))) {
                continue;
            }
            if (next == null) {
                next = next(game, o);
                nextId = o.id();
            } else if (upcoming.size() < UPCOMING) {
                upcoming.add(o.startsAt());
            }
        }
        List<Events.Recent> recent = new ArrayList<>();
        for (EventDao.EventRow row : game.recent(Events.RECENT)) {
            recent.add(recent(row, game.entries(row.id()), game.courseName(row.course()), names));
        }
        String board = game.seasonBoard();
        Events.Season season = null;
        if (board != null) {
            String key = board.substring(EventCopy.SEASON_PREFIX.length());
            season = new Events.Season(key, EventCopy.seasonName(key), EventCopy.seasonEnds(key, game.zone()),
                    RaceNight.SPEC.id(), board);
        }
        return new Events(next, upcoming, live, recent, season);
    }

    private static Events.Next next(NightRunner n) {
        NightRules r = n.plan().rules();
        return new Events.Next(n.plan().id(), "Race Night", n.plan().joinAt(), n.startsAt(), n.track().base().id(),
                n.track().name(), r.races(), n.laps(), r.prizes(), r.finisherPrize(), n.prizeNight(),
                n.joined().size(), n.maxRacers());
    }

    private static Events.Next next(RaceNight game, EventSchedule.Occurrence o) {
        RaceNightSettings s = game.settings();
        var c = game.nextTrack(o);
        return new Events.Next(o.id(), "Race Night", o.joinAt(), o.startsAt(), c == null ? null : c.id(),
                c == null ? null : c.name(), s.races(), c == null ? Math.max(1, s.laps()) : RaceTrack.laps(c, s.laps()),
                s.prizes(), s.finisherPrize(), s.prizeEventsPerWeek() > game.prizedThisWeek(), 0, s.maxRacers());
    }
}
