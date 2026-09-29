package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who has to be out of a half before it is built (GEN-SPEC §6.3), decided without a server.
 *
 * <p>Everyone inside the half grown by {@value #MARGIN} is dealt with, every check until the
 * blocks are verified:
 * <ul>
 *   <li><b>Someone mid-run on the layout that half still holds</b> (only possible after a second
 *       quick reroll: at the daily build nobody can be there) gets time: "A new course is coming
 *       here! Finish in the next 20 minutes - your time still counts.", a reminder on the action
 *       bar every 30 seconds, "1 minute left!", and when the time is up their run ends and their
 *       things come back. The build waits for them. A run is known by its session on the slot,
 *       not by where the feet are: a glider on that layout can be well outside its half for a
 *       while, so anyone playing the slot who isn't on the live half (grown by the margin) counts
 *       too.</li>
 *   <li><b>Anyone else</b> — an admin, a player who logged out there, a stray glider — is moved to
 *       the safe spot at once: "A new course is being built here, so we moved you somewhere
 *       safe."</li>
 * </ul>
 * One per build: it remembers whom it told what.
 */
public final class Evacuator {

    /** The half is grown by this much. */
    public static final int MARGIN = 8;
    /** The action-bar reminder comes this often. */
    public static final long REMIND_MS = 30_000L;

    /** Something to do to one player. */
    public record Action(Kind kind, UUID player, String line) {

        public enum Kind {
            /** Teleport them to safety and tell them why ({@link Action#line()}). */
            MOVE,
            /** A chat line. */
            TELL,
            /** An action-bar line. */
            BAR,
            /** End their run (their things come back). */
            END
        }
    }

    private final Map<UUID, Long> reminded = new HashMap<>();
    private final Set<UUID> lastMinute = new HashSet<>();

    /**
     * What to do now.
     *
     * @param people   everyone online
     * @param world    the half's world
     * @param half     the half being built
     * @param slotId   its course
     * @param holdsRun whether the half still holds a layout runs may be on (a standing previous layout)
     * @param live     the live layout's half, or {@code null}: someone playing the slot outside it is
     *                 taken to be on the layout {@code half} holds
     * @param deadline when time is up for them (epoch ms)
     */
    public List<Action> step(List<Person> people, String world, Box half, String slotId, boolean holdsRun,
                             Box live, long now, long deadline) {
        List<Action> out = new ArrayList<>();
        Box area = half.expand(MARGIN);
        for (Person p : people) {
            boolean runner = holdsRun && runner(p, world, area, slotId, live);
            if (!runner && !p.in(world, area)) {
                continue;
            }
            if (runner) {
                long left = deadline - now;
                if (left <= 0) {
                    out.add(new Action(Action.Kind.END, p.id(), null));
                    out.add(new Action(Action.Kind.TELL, p.id(), GenCopy.timesUp(slotId)));
                    reminded.remove(p.id());
                    continue;
                }
                Long last = reminded.get(p.id());
                if (last == null) {
                    reminded.put(p.id(), now);
                    out.add(new Action(Action.Kind.TELL, p.id(), GenCopy.comingHere(minutes(left))));
                } else if (now - last >= REMIND_MS) {
                    reminded.put(p.id(), now);
                    out.add(new Action(Action.Kind.BAR, p.id(), GenCopy.comingHere(minutes(left))));
                }
                if (left <= 60_000L && lastMinute.add(p.id())) {
                    out.add(new Action(Action.Kind.TELL, p.id(), GenCopy.ONE_MINUTE));
                }
                continue;
            }
            out.add(new Action(Action.Kind.MOVE, p.id(), GenCopy.MOVED));
        }
        return out;
    }

    /**
     * Whether someone may still be mid-run on the layout the half holds (the build waits): playing
     * the slot and in the half, or anywhere off the {@code live} half.
     */
    public static boolean waiting(List<Person> people, String world, Box half, String slotId, boolean holdsRun,
                                  Box live) {
        if (!holdsRun) {
            return false;
        }
        Box area = half.expand(MARGIN);
        for (Person p : people) {
            if (runner(p, world, area, slotId, live)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether someone playing {@code slotId} is off its {@code live} half grown by {@value #MARGIN}
     * (so they may be on the layout before it): CLEAR_OLD waits for them as for someone standing in
     * the old half.
     */
    public static boolean strayRunner(List<Person> people, String world, String slotId, Box live) {
        for (Person p : people) {
            if (p.playing(slotId) && (live == null || !p.in(world, live.expand(MARGIN)))) {
                return true;
            }
        }
        return false;
    }

    /** On the held layout: playing the slot, and in its half or off the live one. */
    private static boolean runner(Person p, String world, Box area, String slotId, Box live) {
        return p.playing(slotId) && (p.in(world, area) || (live != null && !p.in(world, live.expand(MARGIN))));
    }

    /** Whether anyone at all is in the half grown by {@value #MARGIN} (CLEAR_OLD waits for none). */
    public static boolean anyone(List<Person> people, String world, Box half) {
        Box area = half.expand(MARGIN);
        for (Person p : people) {
            if (p.in(world, area)) {
                return true;
            }
        }
        return false;
    }

    /** Whole minutes left, rounded up (never 0 while time is left). */
    static int minutes(long millis) {
        return (int) Math.max(1, (millis + 59_999L) / 60_000L);
    }
}
