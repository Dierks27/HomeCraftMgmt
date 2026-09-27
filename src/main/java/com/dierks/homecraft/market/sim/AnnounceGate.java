package com.dierks.homecraft.market.sim;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides which market announcement, if any, goes out at a tick (spec §6.1). At most one per
 * tick, and — except for admin-forced ones — only when every gate holds:
 *
 * <ul>
 *   <li>local time is inside {@code news.hours} (07:00-21:00);</li>
 *   <li>at least {@code announce.min_gap_minutes} (20) since the last market broadcast;</li>
 *   <li>fewer than {@code announce.max_per_day} (6) broadcasts this local day;</li>
 *   <li>someone online whose session is at least that announcement's join delay,
 *       {@code lerp(join_delay_minutes, u("announce.join|" + kind + "|" + item, started_at / 60000))}
 *       (a news flash uses its own delay, fixed when it was scheduled).</li>
 * </ul>
 *
 * <p><b>Priority</b>, highest first ({@link Type} order): INTRO (once ever), this tick's news
 * flash or WANTED, a HOT/DEAL start (while FULL), a SEASON (up to 7 days), a REAL move (up to
 * 12 h), LAST CALL ({@code [t1 - last_call_hours, t1 - 30 min]}, once), ENDING (within 30 min
 * after {@code ends_at}). A higher one that is still waiting for its join delay does not block a
 * lower one that is ready. Anything past its window is never broadcast; it stays in the log,
 * the website and the catch-up.
 *
 * <p>Plain Java, no Bukkit: unit-tested without a server ({@code AnnounceGateTest}).
 */
public final class AnnounceGate {

    /** A season's start may be announced for this long. */
    public static final long SEASON_WINDOW_MS = 7 * SimMath.DAY_MS;
    /** A real-world move may be announced for this long. */
    public static final long REAL_WINDOW_MS = 12 * SimMath.HOUR_MS;
    /** "Last call!" stops this long before the fade starts. */
    public static final long LAST_CALL_END_MS = 30 * SimMath.MINUTE_MS;
    /** The ending line may go out this long after {@code ends_at}. */
    public static final long ENDING_WINDOW_MS = 30 * SimMath.MINUTE_MS;

    /** What kind of announcement; declaration order is the priority, highest first. */
    public enum Type {
        /** "The Crate Market is LIVE!", once ever. */
        INTRO,
        /** This tick's UP/DOWN/WANTED flash (only when it fired ready). */
        FLASH,
        /** A HOT/DEAL at full strength. */
        STORY,
        /** A calendar season began. */
        SEASON,
        /** A real-world commodity move. */
        REAL,
        /** "Last call!" before a HOT/DEAL starts to fade. */
        LAST_CALL,
        /** A HOT cooled off / a DEAL is over. */
        ENDING
    }

    /**
     * One announcement waiting for its turn.
     *
     * @param type        what it is
     * @param event       the event it is about (already marked sent when returned by the
     *                    simulator as the tick's broadcast); {@code null} for INTRO
     * @param dueAt       the earliest moment it may go out
     * @param windowEnd   it must go out before this moment (exclusive), or never
     * @param joinDelayMs the session length someone online needs; negative = work it out from
     *                    the rule ({@link #joinDelayMs(SimSettings, SimRandom, String, String, long)})
     * @param forced      an admin forced it: it skips every gate
     */
    public record Pending(Type type, MarketEvent event, long dueAt, long windowEnd, long joinDelayMs,
                          boolean forced) {

        public Pending {
            Objects.requireNonNull(type, "type");
        }

        /** The kind of event it is about, or {@code null} for INTRO. */
        public EventKind kind() {
            return event == null ? null : event.kind();
        }

        /** The item it is about, or {@code null} (INTRO, SEASON). */
        public String itemId() {
            return event == null ? null : event.itemId();
        }

        /** A copy about {@code e} instead. */
        public Pending withEvent(MarketEvent e) {
            return new Pending(type, e, dueAt, windowEnd, joinDelayMs, forced);
        }
    }

    private static final Comparator<Pending> ORDER = Comparator
            .comparingInt((Pending p) -> p.type().ordinal())
            .thenComparingLong(Pending::dueAt)
            .thenComparingLong(p -> p.event() == null ? Long.MIN_VALUE : p.event().startedAt())
            .thenComparing(p -> p.event() == null || p.event().itemId() == null ? "" : p.event().itemId())
            .thenComparingLong(p -> p.event() == null ? 0L : p.event().id());

    private AnnounceGate() {
    }

    // ---- the gate ------------------------------------------------------------------------

    /**
     * The shared part of the gate: inside news hours, at least {@code min_gap_minutes} since the
     * last market broadcast, and under {@code max_per_day} for the local day. The planner uses it
     * too, to decide whether a due flash is "ready".
     *
     * @param broadcastsToday broadcasts so far on {@code now}'s local day
     */
    public static boolean gateOpen(long now, ZoneId zone, long lastBroadcastAt, int broadcastsToday, SimSettings s) {
        LocalTime local = Instant.ofEpochMilli(now).atZone(zoneOr(zone)).toLocalTime();
        if (!s.news().hours().contains(local)) {
            return false;
        }
        if (now - lastBroadcastAt < s.announce().minGapMinutes() * SimMath.MINUTE_MS) {
            return false;
        }
        return broadcastsToday < s.announce().maxPerDay();
    }

    /**
     * Pick the one announcement to send at {@code now}: the first forced one, else the
     * highest-priority one that is inside its window and whose join delay someone online has
     * met — provided {@link #gateOpen} holds. Ties go to the one due first.
     */
    public static Optional<Pending> choose(List<Pending> pending, long now, ZoneId zone, OnlineInfo online,
                                           long lastBroadcastAt, int broadcastsToday, SimSettings s, SimRandom rng) {
        if (pending == null || pending.isEmpty()) {
            return Optional.empty();
        }
        List<Pending> sorted = new ArrayList<>(pending.size());
        for (Pending p : pending) {
            if (p != null) {
                sorted.add(p);
            }
        }
        sorted.sort(ORDER);
        for (Pending p : sorted) {
            if (p.forced()) {
                return Optional.of(p);
            }
        }
        if (!gateOpen(now, zone, lastBroadcastAt, broadcastsToday, s)) {
            return Optional.empty();
        }
        OnlineInfo on = online == null ? OnlineInfo.NOBODY : online;
        for (Pending p : sorted) {
            if (now < p.dueAt() || now >= p.windowEnd()) {
                continue;
            }
            if (on.ready(delayOf(p, s, rng))) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    // ---- what is waiting -----------------------------------------------------------------

    /**
     * Every announcement the events are waiting on at {@code now}, apart from a news flash
     * (a flash can only go out in the tick it fires, so the simulator adds it itself). Windows:
     *
     * <ul>
     *   <li>INTRO: while {@code introPending} and {@code announce.intro}.</li>
     *   <li>STORY: an unannounced HOT/DEAL that was not stopped, from {@code announce_due_at}
     *       (the end of the silent ramp) until {@code t1}, the end of the hold.</li>
     *   <li>SEASON: an unannounced SEASON row, for 7 days from {@code started_at} (never past
     *       the season's end).</li>
     *   <li>REAL: an unannounced REAL row picked as that fetch's headline
     *       ({@code announce_due_at} set), for 12 h.</li>
     *   <li>LAST CALL: an announced HOT/DEAL, not stopped, once, in
     *       {@code [t1 - last_call_hours, t1 - 30 min]}; never when {@code last_call_hours} is 0.</li>
     *   <li>ENDING: an announced HOT/DEAL that ended on its own (or by {@code sim stop}), once,
     *       within 30 min after {@code ends_at}, when {@code announce.endings}.</li>
     * </ul>
     *
     * UP, DOWN and WANTED rows never appear here: one that fired silently stays silent.
     */
    public static List<Pending> collect(List<MarketEvent> events, long now, SimSettings s, SimRandom rng,
                                        boolean introPending) {
        List<Pending> out = new ArrayList<>();
        if (introPending && s.announce().intro()) {
            out.add(new Pending(Type.INTRO, null, Long.MIN_VALUE, Long.MAX_VALUE,
                    joinDelayMs(s, rng, "INTRO", "", 0L), false));
        }
        if (events == null) {
            return out;
        }
        for (MarketEvent e : events) {
            if (e == null) {
                continue;
            }
            switch (e.kind()) {
                case HOT, DEAL -> story(e, now, s, rng, out);
                case SEASON -> {
                    if (!e.announced() && e.announceDueAt() != null) {
                        long end = Math.min(e.startedAt() + SEASON_WINDOW_MS, e.endsAt());
                        add(out, Type.SEASON, e, e.announceDueAt(), end, now, s, rng);
                    }
                }
                case REAL -> {
                    if (!e.announced() && e.announceDueAt() != null) {
                        long end = Math.min(e.startedAt() + REAL_WINDOW_MS, e.endsAt());
                        add(out, Type.REAL, e, e.announceDueAt(), end, now, s, rng);
                    }
                }
                default -> {
                    // UP, DOWN, WANTED: only in the tick they fire.
                }
            }
        }
        return out;
    }

    private static void story(MarketEvent e, long now, SimSettings s, SimRandom rng, List<Pending> out) {
        long t1 = e.holdEndsAt();
        if (!e.announced()) {
            if (e.stoppedAt() == null && e.announceDueAt() != null) {
                add(out, Type.STORY, e, e.announceDueAt(), t1, now, s, rng);
            }
            return;
        }
        SimSettings.Story k = s.story(e.kind());
        long lastCall = k == null ? 0L : k.lastCallMs();
        if (e.lastCallAt() == null && e.stoppedAt() == null && lastCall > 0) {
            add(out, Type.LAST_CALL, e, t1 - lastCall, t1 - LAST_CALL_END_MS + 1, now, s, rng);
        }
        if (s.announce().endings() && e.endLineAt() == null && endsWithLine(e.stopReason())) {
            add(out, Type.ENDING, e, e.endsAt(), e.endsAt() + ENDING_WINDOW_MS, now, s, rng);
        }
    }

    /** Only a natural end or an admin {@code sim stop} gets the ending line. */
    private static boolean endsWithLine(String stopReason) {
        return stopReason == null || MarketSimulator.STOP_STOPPED.equals(stopReason);
    }

    private static void add(List<Pending> out, Type type, MarketEvent e, long due, long end, long now,
                            SimSettings s, SimRandom rng) {
        if (now < due || now >= end) {
            return;
        }
        String key = e.kind() == EventKind.SEASON ? String.valueOf(e.tag()) : String.valueOf(e.itemId());
        out.add(new Pending(type, e, due, end, joinDelayMs(s, rng, e.kind().name(), key, e.startedAt()), false));
    }

    // ---- join delay, forcing, marking ----------------------------------------------------

    /**
     * An announcement's join delay: {@code lerp(join_delay_minutes, u("announce.join|" + kind +
     * "|" + key, startedAt / 60000))} minutes. Fixed for a given event: logging in and out
     * cannot re-roll it.
     */
    public static long joinDelayMs(SimSettings s, SimRandom rng, String kind, String key, long startedAt) {
        double u = rng.uniform("announce.join|" + kind + "|" + key, Math.floorDiv(startedAt, SimMath.MINUTE_MS));
        return Math.round(s.news().joinDelayMinutes().lerp(u) * SimMath.MINUTE_MS);
    }

    private static long delayOf(Pending p, SimSettings s, SimRandom rng) {
        if (p.joinDelayMs() >= 0) {
            return p.joinDelayMs();
        }
        MarketEvent e = p.event();
        if (e == null) {
            return joinDelayMs(s, rng, p.type().name(), "", 0L);
        }
        String key = e.kind() == EventKind.SEASON ? String.valueOf(e.tag()) : String.valueOf(e.itemId());
        return joinDelayMs(s, rng, e.kind().name(), key, e.startedAt());
    }

    /** An admin-forced announcement about {@code e}: it skips every gate. */
    public static Pending forced(MarketEvent e) {
        Type type = e.kind().story() ? Type.STORY : Type.FLASH;
        return new Pending(type, e, Long.MIN_VALUE, Long.MAX_VALUE, 0L, true);
    }

    /**
     * {@code p}'s event with the matching column set to {@code now}: {@code announced_at} for a
     * flash, start, season or real move; {@code last_call_at} for LAST CALL; {@code end_line_at}
     * for ENDING. {@code null} for INTRO.
     */
    public static MarketEvent markSent(Pending p, long now) {
        MarketEvent e = p.event();
        if (e == null) {
            return null;
        }
        return switch (p.type()) {
            case LAST_CALL -> e.withLastCallAt(now);
            case ENDING -> e.withEndLineAt(now);
            case INTRO -> e;
            default -> e.withAnnouncedAt(now);
        };
    }

    private static ZoneId zoneOr(ZoneId zone) {
        return zone == null ? ZoneOffset.UTC : zone;
    }
}
