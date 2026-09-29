package com.dierks.homecraft.games.golf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Golf together on the server (EVENTS-OWNER-DECISIONS D4): the groups being played and their turn
 * flow ({@link GolfGroup}) around the rounds ({@link GolfRounds}). Everything the server does for it
 * (chat, screens, tees, the record, the trip home) goes through {@link Port}, so the whole flow runs
 * in a test with no server.
 *
 * <p><b>Each round is its player's own.</b> A player's round is finished at THEIR last hole: its
 * finish lines, then the board and the rewards, on the day and the set it was played, exactly once.
 * After that they stay with the group only for the shared card: leaving, a quit or a disconnect while
 * the others finish loses nothing, and at the group's end they only go home.
 *
 * <p><b>Nobody is waited for who isn't there.</b> Once a second, a player whose ball is still out but
 * who has no round in the group and is in no world session (an entry that never arrived, or was called
 * off on arrival) leaves the group, and the others are told. A round alone never starts while its
 * player is still listed in a group: they leave it first, so it never waits for them.
 *
 * <p><b>Nobody is waited for for ever.</b> The first ball of a hole in starts the hole clock
 * ({@value GolfGroup#HOLE_CLOCK_SECONDS} seconds, on the action bar of anyone still out and on the
 * shared card); when it runs out, every ball still out is picked up at the course's pick-up score
 * (par + {@code max_over_par}), exactly as if the strokes had reached it.
 */
final class GolfGroups {

    /** The end of the round shows this long after the last ball of the last hole is in (ticks). */
    static final long LAST_TICKS = 40;

    /** What the flow needs from the server: {@link GolfRounds}'s, or a test's fake. */
    interface Port {

        /** The player's round in progress, or {@code null}. */
        LiveRound round(UUID player);

        /** Whether the player is online and in a world session (one on its way in counts). */
        boolean inSession(UUID player);

        /** Take the player to hole 1 in {@code group}'s round: whether the trip started (else they were told why). */
        boolean enter(UUID player, GolfGroup group);

        /** A chat line to the player, if they are online. */
        void tell(UUID player, String line);

        /** Open the shared scorecard for the player. */
        void showCard(UUID player, GolfGroup.Card card);

        /** The player's ball on the next tee (their round has moved on a hole), the player there too. */
        void nextTee(UUID player, LiveRound round);

        /**
         * The player's round is over: its finish lines, then its score on the board and its rewards.
         * Called exactly once per round, at the player's own last hole.
         */
        void finished(UUID player, LiveRound round);

        /** A hole the hole clock picked up: its title and chat line (the score is already on the round). */
        void pickedUp(UUID player, LiveRound round);

        /** The group's round is over: the player goes home, and {@code card} shows once they are. */
        void home(UUID player, LiveRound round, GolfGroup.Card card);

        /** Run {@code task} in {@code ticks} ticks, inside the game's guard. */
        void later(long ticks, Runnable task);

        /** The group's round is over: its party opens again. */
        void roundOver(long partyId);

        /** The player's name. */
        String name(UUID player);
    }

    private final Port port;
    /** Each group player's group, while they are with it: on the way, playing, or waiting for the card. */
    private final Map<UUID, GolfGroup> groups = new HashMap<>();
    /** Groups whose next tee (or end) is on its way, so it is scheduled once. */
    private final Set<GolfGroup> advancing = new HashSet<>();

    GolfGroups(Port port) {
        this.port = port;
    }

    /** The group the player is with, or {@code null}. */
    GolfGroup of(UUID player) {
        return groups.get(player);
    }

    // ---- starting -------------------------------------------------------------------------------

    /**
     * Everyone in {@code players} (in join order, with their names) goes to hole 1 of {@code course}
     * at once, in one group. A player who can't go (told why) isn't in it; the rest play.
     *
     * @return whether anyone went (false: the group is over already and its party open again)
     */
    boolean start(long partyId, GolfCourse course, Map<UUID, String> players) {
        GolfGroup g = new GolfGroup(partyId, course.id(), course.name(), course.pars(), players);
        for (UUID id : players.keySet()) {
            GolfGroup old = groups.get(id);
            if (old != null && old != g) {
                leave(id, null); // never listed in two groups
            }
            groups.put(id, g);
        }
        for (UUID id : players.keySet()) {
            if (groups.get(id) == g && !port.enter(id, g)) {
                groups.remove(id);
                drop(g, id, missedLine(id));
            }
        }
        return groups.containsValue(g);
    }

    /**
     * A round is starting for the player (they arrived at hole 1): the group it plays in, or
     * {@code null} for a round alone. A player still listed in another group (or starting a round
     * alone) leaves that group first, so it never waits for them.
     *
     * @param group the group the round was started for, or {@code null} for a round alone
     */
    GolfGroup joins(UUID player, GolfGroup group) {
        GolfGroup cur = groups.get(player);
        if (cur != null && cur != group) {
            leave(player, null);
        }
        return group != null && groups.get(player) == group && group.seat(player) == GolfGroup.Seat.PLAYING
                ? group : null;
    }

    // ---- a hole ends ----------------------------------------------------------------------------

    /**
     * A group player's hole ended (in the cup or picked up): they wait, with the shared card, and at
     * their own last hole their round is finished and recorded now. When every ball still in the group
     * is in, everyone moves on together.
     */
    void holeDone(UUID player, LiveRound r) {
        GolfGroup g = r.group;
        r.state = LiveRound.State.BETWEEN;
        r.between++;
        boolean clockWasOff = g.clock() < 0;
        boolean all = g.holeDone(player, r.last);
        if (r.run.finished()) {
            complete(player, r);
        } else {
            port.tell(player, r.card().line());
        }
        if (all) {
            advanceSoon(g);
        } else {
            port.tell(player, "&7Waiting for " + names(g.waitingFor()) + " to finish the hole...");
            tell(g, player, "&d" + port.name(player) + " &7finished hole " + r.run.hole() + ".");
            if (clockWasOff && g.clock() >= 0) {
                for (UUID id : g.out()) {
                    if (groups.get(id) == g) {
                        port.tell(id, clockLine(g.clock()));
                    }
                }
            }
        }
        port.showCard(player, card(g));
    }

    /** "Hole clock: 2:00 to finish this hole - then any ball still out is picked up." */
    static String clockLine(int seconds) {
        return "&eHole clock: &f" + GolfGroup.clockText(seconds)
                + " &7to finish this hole - then any ball still out is picked up.";
    }

    /** The player's round is over: finished and recorded, once. */
    private void complete(UUID player, LiveRound r) {
        if (r.state == LiveRound.State.DONE) {
            return;
        }
        r.state = LiveRound.State.DONE;
        port.finished(player, r);
    }

    /**
     * "Next hole" when every ball is in: don't wait (a tick later, outside the click).
     *
     * @return whether the group moves on now (false: a ball is still out)
     */
    boolean nextNow(GolfGroup g) {
        if (!g.allDone()) {
            return false;
        }
        advancing.add(g);
        port.later(1, () -> advance(g));
        return true;
    }

    /** Everyone is done with the hole: the next tee in a moment (or the end of the round). */
    private void advanceSoon(GolfGroup g) {
        if (!advancing.add(g)) {
            return;
        }
        boolean last = g.hole() + 1 >= g.holes();
        tell(g, null, last ? "&dEveryone's done! &7The round is over - here's how the group did."
                : "&dEveryone's done! &7Next tee in a few seconds.");
        port.later(last ? LAST_TICKS : GolfRounds.BETWEEN_TICKS, () -> advance(g));
    }

    /**
     * The group moves on together: everyone still in to the next tee; or, after the last hole, the
     * ranking, and everyone still with the group goes home (their rounds were recorded at their last
     * hole already).
     */
    void advance(GolfGroup g) {
        if (!advancing.remove(g) || !g.allDone()) {
            return;
        }
        boolean more = g.advance();
        Map<UUID, LiveRound> here = new LinkedHashMap<>();
        for (UUID id : g.active()) {
            LiveRound r = here(g, id);
            if (r != null) {
                here.put(id, r);
            }
        }
        if (more) {
            here.forEach(port::nextTee);
            return;
        }
        GolfGroup.Card end = card(g);
        over(g);
        for (Map.Entry<UUID, LiveRound> e : here.entrySet()) {
            LiveRound r = e.getValue();
            if (r.run.finished()) {
                complete(e.getKey(), r); // already, at their last hole: never twice
            }
            port.home(e.getKey(), r, end);
        }
    }

    // ---- leaving --------------------------------------------------------------------------------

    /**
     * The player's round ended (Leave game, a quit, a session that ended) or a round alone starts: a
     * finished round stays on the card as played; anyone else leaves the group ("left"), and the others
     * carry on. If they were the last ball out, the hole ends now.
     *
     * @param round the round that ended, or {@code null} when there was none
     */
    void leave(UUID player, LiveRound round) {
        GolfGroup g = groups.remove(player);
        if (g == null || !g.has(player)) {
            return;
        }
        if (round != null && round.group == g && round.state == LiveRound.State.DONE) {
            return; // recorded at their last hole: nothing to wait for, their row stays as played
        }
        drop(g, player, "&7" + port.name(player) + " left the round - the rest of you carry on.");
    }

    /** Out of the group (already out of the map): the others are told, and carry on. */
    private void drop(GolfGroup g, UUID player, String line) {
        boolean all = g.left(player);
        tell(g, player, line);
        if (g.active().isEmpty()) {
            over(g);
        } else if (all) {
            advanceSoon(g);
        }
    }

    /** "Sam didn't make it to the course - the rest of you carry on." */
    private String missedLine(UUID player) {
        return "&7" + port.name(player) + " didn't make it to the course - the rest of you carry on.";
    }

    // ---- once a second --------------------------------------------------------------------------

    /**
     * Once a second: anyone whose ball is still out but who isn't there leaves the group, and the hole
     * clock counts down; when it runs out, every ball still out is picked up.
     */
    void second() {
        for (GolfGroup g : new ArrayList<>(new LinkedHashSet<>(groups.values()))) {
            for (UUID id : g.out()) {
                boolean with = groups.get(id) == g;
                if (with && (here(g, id) != null || port.inSession(id))) {
                    continue;
                }
                miss(g, id);
            }
            if (groups.containsValue(g) && g.second()) {
                timeUp(g);
            }
        }
    }

    /** The hole clock ran out: every ball still out is picked up (and anyone not there leaves). */
    private void timeUp(GolfGroup g) {
        for (UUID id : g.out()) {
            LiveRound r = here(g, id);
            if (r == null) {
                miss(g, id);
                continue;
            }
            if (r.state != LiveRound.State.PLAYING || r.run.finished()) {
                continue;
            }
            r.ball.place(r.ball.x(), r.ball.y(), r.ball.z()); // stopped where it is
            r.rolling = 0;
            r.last = r.run.pickUp();
            port.tell(id, "&eTime's up on this hole &7- your ball is picked up.");
            port.pickedUp(id, r);
            holeDone(id, r);
        }
    }

    /** A player whose ball is still out isn't there (never arrived): out of the group, everyone told. */
    private void miss(GolfGroup g, UUID player) {
        if (groups.get(player) == g) {
            groups.remove(player);
            port.tell(player, "&7Your golf group carries on without you.");
        }
        drop(g, player, missedLine(player));
    }

    // ---- the card and the end -------------------------------------------------------------------

    /** The shared scorecard now, with each player's strokes on the hole being played. */
    GolfGroup.Card card(GolfGroup g) {
        for (UUID id : g.active()) {
            LiveRound r = here(g, id);
            if (r != null && r.state == LiveRound.State.PLAYING) {
                g.strokes(id, r.run.strokes());
            }
        }
        return g.card();
    }

    /** The group's round is over: the ranking to everyone still with it, and the party opens again. */
    private void over(GolfGroup g) {
        advancing.remove(g);
        StringBuilder b = new StringBuilder("&dGolf together &7- ");
        int n = 0;
        for (GolfGroup.Standing s : g.ranking()) {
            if (s.place() <= 0) {
                continue;
            }
            b.append(n++ > 0 ? "&7, " : "").append("&f").append(GolfGroup.ordinal(s.place())).append(' ')
                    .append(s.name()).append(" &7").append(s.total());
        }
        if (n > 0) {
            tell(g, null, b.toString());
        }
        groups.values().removeIf(x -> x == g);
        port.roundOver(g.id());
    }

    /** The game stops: every group goes (the framework ends the sessions, golf closes the parties). */
    void stop() {
        groups.clear();
        advancing.clear();
    }

    /** The player's round in {@code g}, when they are still with it; else {@code null}. */
    private LiveRound here(GolfGroup g, UUID player) {
        if (groups.get(player) != g) {
            return null;
        }
        LiveRound r = port.round(player);
        return r != null && r.group == g ? r : null;
    }

    /** A line to everyone still with the group (but {@code except}). */
    private void tell(GolfGroup g, UUID except, String line) {
        for (UUID id : g.active()) {
            if (!id.equals(except) && groups.get(id) == g) {
                port.tell(id, line);
            }
        }
    }

    /** "Sam", "Sam and Ava", "Sam, Ava and Lee". */
    static String names(List<String> names) {
        if (names.isEmpty()) {
            return "the others";
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }
}
