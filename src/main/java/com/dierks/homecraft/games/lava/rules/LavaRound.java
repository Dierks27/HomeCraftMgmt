package com.dierks.homecraft.games.lava.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One round of The Floor Is Lava as a pure state machine: who is in, which phase it is, how high the lava is, which
 * stars are out and what has been collected. Driven once a tick; it never sees a block or a player's feet. The room
 * tells it who touched the lava ({@link #touched}) and who walked into a star ({@link #collected}), and does what
 * the {@link LavaEvent}s say.
 *
 * <p><b>The loop.</b> {@link Phase#SAFE} (stars are out) &rarr; {@link Phase#WARNING} (a rumble and a glow: climb!)
 * &rarr; {@link Phase#LAVA} (the floor is lava up to {@link #level}) &rarr; {@link Phase#DRAIN} &rarr; the next
 * round's SAFE.
 *
 * <ul>
 *   <li><b>Little</b> can't be lost. The lava is always one block deep. A touch puts the player back, and costs
 *       nothing. Stars stay collected over however many rounds it takes; it ends exactly when the last is
 *       collected. There is no clock to beat.</li>
 *   <li><b>Group</b> plays {@link Rules#rounds} rounds with one shared count. A new set of stars each round; the
 *       lava one block higher each round (never over {@link Rules#safeTop}, so there is always dry ground); a touch
 *       puts the player back and costs the group one star, never below zero.</li>
 *   <li><b>Challenge</b> gathers its players first, and nobody joins later. The lava rises one block a round and
 *       never drains. A touch puts the player out. With two or more it ends when one is left standing; alone, when
 *       that player is out, and the score is the rounds lasted. At {@link Rules#flood} everything is under.</li>
 * </ul>
 * A round nobody is left in is over, called off ({@link LavaResult#calledOff}).
 */
public final class LavaRound {

    public static final int TICKS_PER_SECOND = 20;
    /** The warning before the lava: a rumble, a glow and a rising sound. */
    public static final int WARN_TICKS = 5 * TICKS_PER_SECOND;
    /** The drain after it: long enough for the highest lava to go down a layer at a time. */
    public static final int DRAIN_TICKS = 2 * TICKS_PER_SECOND;
    /** How long Group and Challenge wait for others to join (anyone may start sooner). */
    public static final int GATHER_TICKS = 15 * TICKS_PER_SECOND;
    /** Challenge's climbing time never gets shorter than this. */
    public static final int MIN_CLIMB_TICKS = 4 * TICKS_PER_SECOND;

    /** Where a round is. */
    public enum Phase {
        /** Waiting for players (Group, Challenge). */
        GATHER,
        /** The floor is safe: stars are out. */
        SAFE,
        /** The lava is coming. */
        WARNING,
        /** The floor is lava. */
        LAVA,
        /** The lava is going down. */
        DRAIN,
        /** Finished. */
        OVER;

        /** Whether the round is being played (not gathering, not over). */
        public boolean inPlay() {
            return this != GATHER && this != OVER;
        }
    }

    /** What {@link #join} says. */
    public enum Join {
        JOINED, ALREADY_IN, FULL,
        /** A Challenge already going: nobody joins it. */
        TOO_LATE,
        /** The round is over: a new one is needed. */
        OVER
    }

    /**
     * A round's knobs.
     *
     * @param stars       how many stars are out (Little: in all; Group: each round)
     * @param safeTicks   the safe phase
     * @param warnTicks   the warning
     * @param lavaTicks   how long the lava stays
     * @param drainTicks  the drain
     * @param rounds      Group's rounds
     * @param safeTop     the highest lava with dry ground left for everyone (Group's lava stops there)
     * @param flood       the lava level that covers every piece of furniture (Challenge's last)
     * @param maxPlayers  the most players in a round
     * @param gatherTicks the wait for players before a round starts (0: at once)
     */
    public record Rules(int stars, int safeTicks, int warnTicks, int lavaTicks, int drainTicks, int rounds,
                        int safeTop, int flood, int maxPlayers, int gatherTicks) {

        public Rules {
            stars = Math.max(1, stars);
            safeTicks = Math.max(1, safeTicks);
            warnTicks = Math.max(1, warnTicks);
            lavaTicks = Math.max(1, lavaTicks);
            drainTicks = Math.max(1, drainTicks);
            rounds = Math.max(1, rounds);
            safeTop = Math.max(1, safeTop);
            flood = Math.max(safeTop + 1, flood);
            maxPlayers = Math.max(1, maxPlayers);
            gatherTicks = Math.max(0, gatherTicks);
        }

        /** The safe phase of {@code round}: as set, but Challenge's is a fifth shorter every round. */
        public int safeTicks(LavaMode mode, int round) {
            if (mode != LavaMode.CHALLENGE) {
                return safeTicks;
            }
            double t = safeTicks;
            for (int r = 1; r < round; r++) {
                t *= 0.8;
            }
            return Math.max(Math.min(MIN_CLIMB_TICKS, safeTicks), (int) Math.round(t));
        }

        /** How high the lava comes in {@code round} (blocks over the floor). */
        public int level(LavaMode mode, int round) {
            return switch (mode) {
                case LITTLE -> 1;
                case GROUP -> Math.max(1, Math.min(round, safeTop));
                case CHALLENGE -> Math.max(0, Math.min(round, flood));
            };
        }
    }

    private final LavaMode mode;
    private final Rules rules;
    private Phase phase = Phase.GATHER;
    private int left;
    private int round;
    private int level;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final List<UUID> started = new ArrayList<>();
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Map<UUID, Integer> places = new HashMap<>();
    private final Map<UUID, Integer> survived = new HashMap<>();
    private final TreeSet<Integer> starsOut = new TreeSet<>();
    /** Little: the stars not collected yet. */
    private final TreeSet<Integer> remaining = new TreeSet<>();
    private int collected;
    private int starSet;
    private int lavaCame;
    private LavaResult result;

    public LavaRound(LavaMode mode, Rules rules) {
        if (mode == null || rules == null) {
            throw new IllegalArgumentException("a round needs its mode and its rules");
        }
        this.mode = mode;
        this.rules = rules;
        this.left = rules.gatherTicks();
    }

    // ---- players --------------------------------------------------------------------------------

    /** A player comes in. Little and Group take players at any time; Challenge only while it gathers. */
    public Join join(UUID player) {
        if (phase == Phase.OVER) {
            return Join.OVER;
        }
        if (members.contains(player)) {
            return Join.ALREADY_IN;
        }
        if (members.size() >= rules.maxPlayers()) {
            return Join.FULL;
        }
        if (mode == LavaMode.CHALLENGE && phase != Phase.GATHER) {
            return Join.TOO_LATE;
        }
        members.add(player);
        if (phase != Phase.GATHER) {
            started.add(player);
        }
        return Join.JOINED;
    }

    /** A player left (any way). The last one out ends the round, called off. */
    public List<LavaEvent> leave(UUID player) {
        List<LavaEvent> ev = new ArrayList<>();
        if (!members.remove(player) || phase == Phase.OVER) {
            return ev;
        }
        if (members.isEmpty()) {
            alive.remove(player);
            end(ev, true);
            return ev;
        }
        if (mode == LavaMode.CHALLENGE && phase != Phase.GATHER && alive.remove(player)) {
            int place = alive.size() + 1;
            places.put(player, place);
            ev.add(new LavaEvent.Out(player, place, survived(player), true));
            settleChallenge(ev);
        }
        return ev;
    }

    /** Start now, without waiting for more players (Group, Challenge). */
    public List<LavaEvent> startNow() {
        List<LavaEvent> ev = new ArrayList<>();
        if (phase == Phase.GATHER && !members.isEmpty()) {
            begin(ev);
        }
        return ev;
    }

    // ---- the clock ------------------------------------------------------------------------------

    /** One tick. */
    public List<LavaEvent> tick() {
        List<LavaEvent> ev = new ArrayList<>();
        if (phase == Phase.OVER) {
            return ev;
        }
        if (phase == Phase.GATHER) {
            if (members.isEmpty()) {
                return ev; // nobody here yet: the wait hasn't begun
            }
            if (left > 0) {
                left--;
            }
            if (left <= 0) {
                begin(ev);
            }
            return ev;
        }
        left--;
        if (left > 0) {
            return ev;
        }
        switch (phase) {
            case SAFE -> {
                phase = Phase.WARNING;
                left = rules.warnTicks();
                ev.add(new LavaEvent.PhaseChanged(phase, round, level, left));
            }
            case WARNING -> {
                phase = Phase.LAVA;
                left = rules.lavaTicks();
                level = rules.level(mode, round);
                starsIn(ev);
                ev.add(new LavaEvent.PhaseChanged(phase, round, level, left));
            }
            case LAVA -> lavaDone(ev);
            case DRAIN -> {
                if (mode == LavaMode.GROUP && round >= rules.rounds()) {
                    end(ev, false);
                } else {
                    round++;
                    safe(ev);
                }
            }
            default -> {
                // GATHER and OVER are handled above
            }
        }
        return ev;
    }

    private void begin(List<LavaEvent> ev) {
        started.clear();
        started.addAll(members);
        alive.clear();
        if (mode == LavaMode.CHALLENGE) {
            alive.addAll(members);
        }
        remaining.clear();
        for (int i = 0; i < rules.stars(); i++) {
            remaining.add(i);
        }
        round = 1;
        ev.add(new LavaEvent.Started(mode, started));
        safe(ev);
    }

    private void safe(List<LavaEvent> ev) {
        phase = Phase.SAFE;
        left = rules.safeTicks(mode, round);
        level = mode == LavaMode.CHALLENGE ? rules.level(mode, round - 1) : 0;
        ev.add(new LavaEvent.PhaseChanged(phase, round, level, left));
        starsOut.clear();
        if (mode == LavaMode.LITTLE) {
            starsOut.addAll(remaining);
        } else if (mode == LavaMode.GROUP) {
            for (int i = 0; i < rules.stars(); i++) {
                starsOut.add(i);
            }
        }
        if (!starsOut.isEmpty()) {
            starSet++;
            ev.add(new LavaEvent.StarsOut(starSet, new ArrayList<>(starsOut)));
        }
    }

    private void lavaDone(List<LavaEvent> ev) {
        lavaCame++;
        if (mode == LavaMode.CHALLENGE) {
            for (UUID p : alive) {
                survived.merge(p, 1, Integer::sum);
            }
            if (round >= rules.flood()) {
                end(ev, false); // nothing is higher: whoever is still up is the last one standing
                return;
            }
            round++;
            safe(ev); // the lava stays where it is
            return;
        }
        phase = Phase.DRAIN;
        left = rules.drainTicks();
        level = 0;
        ev.add(new LavaEvent.PhaseChanged(phase, round, level, left));
    }

    private void starsIn(List<LavaEvent> ev) {
        if (!starsOut.isEmpty()) {
            starsOut.clear();
            ev.add(new LavaEvent.StarsIn());
        }
    }

    // ---- what the room saw ----------------------------------------------------------------------

    /**
     * These players are in the lava now (all of one tick's together, so two who go under at the same moment share a
     * place). Little and Group: each is put back, and in Group each costs a star while there is one. Challenge: each
     * is out.
     */
    public List<LavaEvent> touched(Collection<UUID> players) {
        List<LavaEvent> ev = new ArrayList<>();
        if (!phase.inPlay() || players == null) {
            return ev;
        }
        if (mode != LavaMode.CHALLENGE) {
            for (UUID p : players) {
                if (!members.contains(p)) {
                    continue;
                }
                boolean cost = mode == LavaMode.GROUP && collected > 0;
                if (cost) {
                    collected--;
                }
                ev.add(new LavaEvent.PutBack(p, cost, collected));
            }
            return ev;
        }
        List<UUID> hit = new ArrayList<>();
        for (UUID p : players) {
            if (alive.contains(p)) {
                hit.add(p);
            } else if (members.contains(p)) {
                ev.add(new LavaEvent.PutBack(p, false, 0)); // a watcher: back to the gallery
            }
        }
        if (hit.isEmpty()) {
            return ev;
        }
        alive.removeAll(hit);
        int place = alive.size() + 1;
        for (UUID p : hit) {
            places.put(p, place);
            ev.add(new LavaEvent.Out(p, place, survived(p), false));
        }
        settleChallenge(ev);
        return ev;
    }

    /** A player walked into star {@code id}. Only a star that is out counts, and only while the floor is safe. */
    public List<LavaEvent> collected(UUID player, int id) {
        List<LavaEvent> ev = new ArrayList<>();
        if ((phase != Phase.SAFE && phase != Phase.WARNING) || !members.contains(player) || !starsOut.remove(id)) {
            return ev;
        }
        collected++;
        remaining.remove(id);
        ev.add(new LavaEvent.StarTaken(player, id, collected, goal()));
        if (mode == LavaMode.LITTLE && remaining.isEmpty()) {
            end(ev, false);
        }
        return ev;
    }

    private void settleChallenge(List<LavaEvent> ev) {
        if (alive.isEmpty() || (started.size() > 1 && alive.size() == 1)) {
            end(ev, false);
        }
    }

    private void end(List<LavaEvent> ev, boolean calledOff) {
        starsIn(ev);
        phase = Phase.OVER;
        level = 0;
        left = 0;
        List<LavaResult.Standing> standings = new ArrayList<>();
        if (mode == LavaMode.CHALLENGE) {
            for (UUID p : started) {
                standings.add(new LavaResult.Standing(p, alive.contains(p) ? 1 : places.getOrDefault(p, 1),
                        survived(p)));
            }
            standings.sort(Comparator.comparingInt(LavaResult.Standing::place));
        }
        result = new LavaResult(mode, calledOff, new ArrayList<>(members), mode == LavaMode.CHALLENGE ? 0 : collected,
                lavaCame, standings, started.size() == 1);
        ev.add(new LavaEvent.Ended(result));
    }

    // ---- reading it -----------------------------------------------------------------------------

    public LavaMode mode() {
        return mode;
    }

    public Rules rules() {
        return rules;
    }

    public Phase phase() {
        return phase;
    }

    /** Whether the round is finished. */
    public boolean over() {
        return phase == Phase.OVER;
    }

    /** Ticks left in this phase. */
    public int left() {
        return left;
    }

    /** The round now, from 1 (0 while gathering). */
    public int round() {
        return round;
    }

    /** How high the lava should be now, in blocks over the floor (0: none). */
    public int level() {
        return level;
    }

    /** How high the lava comes next (the warning's height). */
    public int nextLevel() {
        return phase == Phase.OVER || phase == Phase.GATHER ? 0 : rules.level(mode, round);
    }

    /** Everyone in the room for this round, in the order they came. */
    public List<UUID> members() {
        return List.copyOf(members);
    }

    public boolean isMember(UUID player) {
        return members.contains(player);
    }

    /** Challenge: who is still standing. */
    public List<UUID> alive() {
        return List.copyOf(alive);
    }

    /** Whether a player is playing now: a member, and in Challenge still standing. */
    public boolean playing(UUID player) {
        return members.contains(player) && phase.inPlay() && (mode != LavaMode.CHALLENGE || alive.contains(player));
    }

    /** Little: stars collected so far; Group: the shared count. */
    public int stars() {
        return collected;
    }

    /** Little's full count; 0 where there is none. */
    public int goal() {
        return mode == LavaMode.LITTLE ? rules.stars() : 0;
    }

    /** The stars out now. */
    public List<Integer> starsOut() {
        return List.copyOf(starsOut);
    }

    /** How many times the lava has come and gone. */
    public int lavaCame() {
        return lavaCame;
    }

    /** Challenge: the rounds a player has lasted. */
    public int survived(UUID player) {
        return survived.getOrDefault(player, 0);
    }

    /** How it ended, once it is {@link #over}. */
    public LavaResult result() {
        return result;
    }
}
