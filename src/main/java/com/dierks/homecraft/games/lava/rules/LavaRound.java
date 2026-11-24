package com.dierks.homecraft.games.lava.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
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
 * <p><b>0.57.0: no more "grab the stars and wait".</b> The owner: "the floor is lava game is WAY too easy ... Maybe a
 * bit more random on the lava as it seems like all you have to do is get the stars and wait now". So:
 * <ul>
 *   <li><b>The clock can't be counted.</b> Every safe, warning and lava phase is drawn from its {@link Span} with
 *       the round's own random numbers (from its seed: the same seed, the same round, so tests and replays agree).
 *       The harder the mode, the shorter the warning.</li>
 *   <li><b>Stars come out during the lava too</b>, up on the furniture ({@link LavaEvent.StarsOut#lava}), a few at
 *       a time, a new few when those are collected: you hop piece to piece instead of standing still.</li>
 *   <li><b>The room moves</b> ({@link Rules#shiftTicks}): from the second round of Group and Challenge the furniture
 *       glows, rumbles and moves while the floor is safe ({@link LavaEvent.ShiftComing}, {@link LavaEvent.Shift});
 *       the stars come out after.</li>
 *   <li><b>A surprise</b> most rounds ({@link Surprise}), chosen by the round's random numbers, never the same twice
 *       running.</li>
 * </ul>
 *
 * <ul>
 *   <li><b>Little</b> can't be lost. The lava is always one block deep. A touch puts the player back, and costs
 *       nothing. Stars stay collected over however many rounds it takes (during the lava, two of them wait on the low
 *       furniture); it ends exactly when the last is collected. Its only surprises are friendly: a shower of stars, a
 *       bouncy cushion.</li>
 *   <li><b>Group</b> plays {@link Rules#rounds} rounds with one shared count and {@link Rules#hearts} hearts. Each
 *       round has a star goal ({@link Rules#goal}): the floor's stars and the lava's together, less a star for every
 *       touch; a round that misses it costs a heart, and with no hearts left the game is over. The lava is a block
 *       higher every round (never over {@link Rules#safeTop}).</li>
 *   <li><b>Challenge</b> gathers its players first, and nobody joins later. The lava rises a block a round and never
 *       drains. A touch puts the player out, and so does a round without its stars ({@link Rules#need}): you must
 *       keep collecting. With two or more it ends when one is left standing; alone, when that player is out, and the
 *       score is the rounds lasted. At {@link Rules#flood} everything is under.</li>
 * </ul>
 * A round nobody is left in is over, called off ({@link LavaResult#calledOff}).
 */
public final class LavaRound {

    public static final int TICKS_PER_SECOND = 20;
    /** The drain after it: long enough for the highest lava to go down a layer at a time. */
    public static final int DRAIN_TICKS = 2 * TICKS_PER_SECOND;
    /** How long Group and Challenge wait for others to join (anyone may start sooner). */
    public static final int GATHER_TICKS = 15 * TICKS_PER_SECOND;
    /** Challenge's floor time never gets shorter than this. */
    public static final int MIN_CLIMB_TICKS = 4 * TICKS_PER_SECOND;
    /** 0.57.0: Little's stars waiting on the furniture during the lava, at a time. */
    public static final int LITTLE_LAVA_STARS = 2;

    /** Where a round is. */
    public enum Phase {
        /** Waiting for players (Group, Challenge). */
        GATHER,
        /** The floor is safe: stars are out (after the furniture moved, when it moves). */
        SAFE,
        /** The lava is coming. */
        WARNING,
        /** The floor is lava (and stars are out on the furniture). */
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
     * 0.57.0: a round's surprise, each with a warning anyone can see and hear (the room's: {@code LavaService}).
     */
    public enum Surprise {
        /** A piece wobbles and crumbles during the warning, then sinks into the lava: less dry ground. */
        SINK,
        /** The floor glows on one side first, and the lava comes up there first. */
        PATCHES,
        /** A quicker rumble, and the lava comes all at once (in Group a block higher too). */
        FAST_RISE,
        /** Twice the stars during the lava. */
        STAR_SHOWER,
        /** A cushion turns to slime for the round: boing. */
        BOUNCY;

        /** The friendly ones: Little's. */
        public boolean gentle() {
            return this == STAR_SHOWER || this == BOUNCY;
        }
    }

    /** 0.57.0: a length in ticks drawn between {@code min} and {@code max}, both in. */
    public record Span(int min, int max) {

        public Span {
            min = Math.max(1, min);
            max = Math.max(min, max);
        }

        public static Span of(int ticks) {
            return new Span(ticks, ticks);
        }

        public int draw(SplittableRandom rng) {
            return min == max ? min : min + rng.nextInt(max - min + 1);
        }
    }

    /**
     * A round's knobs.
     *
     * @param stars       how many stars are out while the floor is safe (Little: in all; Group: each round)
     * @param lavaStars   0.57.0: Group's stars during the lava each round (Challenge's come two waves of one each
     *                    standing; Little's are its own, two at a time)
     * @param safe        the safe phase (Challenge's shrinks round by round: {@link #safeTicks})
     * @param warn        the warning
     * @param lava        how long the lava stays
     * @param drainTicks  the drain
     * @param rounds      Group's rounds
     * @param safeTop     the highest lava with dry ground left for everyone (Group's lava stops there)
     * @param flood       the lava level that covers every piece of furniture (Challenge's last)
     * @param maxPlayers  the most players in a round
     * @param gatherTicks the wait for players before a round starts (0: at once)
     * @param shiftTicks  0.57.0: the furniture's warning before it moves, from the second round (0: it never moves
     *                    between rounds)
     * @param hearts      0.57.0: Group's hearts (a missed goal costs one)
     * @param surprises   0.57.0: whether rounds have surprises
     */
    public record Rules(int stars, int lavaStars, Span safe, Span warn, Span lava, int drainTicks, int rounds,
                        int safeTop, int flood, int maxPlayers, int gatherTicks, int shiftTicks, int hearts,
                        boolean surprises) {

        public Rules {
            stars = Math.max(1, stars);
            lavaStars = Math.max(0, lavaStars);
            safe = safe == null ? Span.of(1) : safe;
            warn = warn == null ? Span.of(1) : warn;
            lava = lava == null ? Span.of(1) : lava;
            drainTicks = Math.max(1, drainTicks);
            rounds = Math.max(1, rounds);
            safeTop = Math.max(1, safeTop);
            flood = Math.max(safeTop + 1, flood);
            maxPlayers = Math.max(1, maxPlayers);
            gatherTicks = Math.max(0, gatherTicks);
            shiftTicks = Math.max(0, shiftTicks);
            hearts = Math.max(1, hearts);
        }

        /** The safe phase of {@code round}, drawn: as set, but Challenge's is a tenth shorter every round. */
        public int safeTicks(LavaMode mode, int round, SplittableRandom rng) {
            int t = safe.draw(rng);
            if (mode != LavaMode.CHALLENGE) {
                return t;
            }
            double s = t;
            for (int r = 1; r < round; r++) {
                s *= 0.9;
            }
            return Math.max(Math.min(MIN_CLIMB_TICKS, safe.min()), (int) Math.round(s));
        }

        /** How high the lava comes in {@code round} (blocks over the floor); {@code fast}: a {@link Surprise#FAST_RISE}. */
        public int level(LavaMode mode, int round, boolean fast) {
            return switch (mode) {
                case LITTLE -> 1;
                case GROUP -> Math.max(1, Math.min(round + (fast ? 1 : 0), safeTop));
                case CHALLENGE -> Math.max(0, Math.min(round, flood));
            };
        }

        /**
         * 0.57.0, Group: the stars a round must bring in (its floor's and its lava's, less a star a touch): more each
         * round, more with more players, never all of them.
         */
        public int goal(int round, int players) {
            int most = Math.max(1, stars + lavaStars - 2);
            return Math.max(1, Math.min(most, 4 + round + 2 * Math.max(0, players - 1)));
        }

        /** 0.57.0, Challenge: the stars each player must collect in {@code round} to stay in. */
        public int need(int round) {
            return round >= 4 ? 2 : 1;
        }
    }

    private final LavaMode mode;
    private final Rules rules;
    private final long seed;
    private final SplittableRandom rng;
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
    // 0.57.0
    private int nextStar;
    private int shiftAt = -1;
    private long shiftSeed;
    private Surprise surprise;
    private Surprise lastSurprise;
    private int lavaLeft;
    private int waveSize;
    private int roundGot;
    private int hearts;
    private int goalsMade;
    private final Map<UUID, Integer> got = new HashMap<>();

    /** A round with its own random numbers from {@code seed} (the same seed, the same round). */
    public LavaRound(LavaMode mode, Rules rules, long seed) {
        if (mode == null || rules == null) {
            throw new IllegalArgumentException("a round needs its mode and its rules");
        }
        this.mode = mode;
        this.rules = rules;
        this.seed = seed;
        this.rng = new SplittableRandom(seed);
        this.left = rules.gatherTicks();
        this.hearts = rules.hearts();
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
            ev.add(new LavaEvent.Out(player, place, survived(player), true, false));
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
        if (phase == Phase.SAFE && shiftAt >= 0 && left <= shiftAt) {
            shiftAt = -1;
            ev.add(new LavaEvent.Shift(round, shiftSeed));
            starsOut(ev);
        }
        if (left > 0) {
            return ev;
        }
        switch (phase) {
            case SAFE -> {
                phase = Phase.WARNING;
                int warn = rules.warn().draw(rng);
                if (surprise == Surprise.FAST_RISE) {
                    warn = Math.max(TICKS_PER_SECOND, warn / 2);
                }
                left = warn;
                ev.add(new LavaEvent.PhaseChanged(phase, round, level, left));
            }
            case WARNING -> {
                phase = Phase.LAVA;
                left = rules.lava().draw(rng);
                level = rules.level(mode, round, surprise == Surprise.FAST_RISE);
                starsIn(ev);
                ev.add(new LavaEvent.PhaseChanged(phase, round, level, left));
                lavaStars(ev);
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
        if (mode == LavaMode.LITTLE) {
            for (int i = 0; i < rules.stars(); i++) {
                remaining.add(i);
            }
            nextStar = rules.stars();
        }
        round = 1;
        ev.add(new LavaEvent.Started(mode, started));
        safe(ev);
    }

    private void safe(List<LavaEvent> ev) {
        phase = Phase.SAFE;
        boolean shift = rules.shiftTicks() > 0 && round >= 2;
        int t = rules.safeTicks(mode, round, rng);
        left = t + (shift ? rules.shiftTicks() : 0);
        level = mode == LavaMode.CHALLENGE ? rules.level(mode, round - 1, false) : 0;
        roundGot = 0;
        got.clear();
        lastSurprise = surprise == null ? lastSurprise : surprise;
        surprise = pickSurprise();
        int pick = rng.nextInt(1 << 16);
        ev.add(new LavaEvent.PhaseChanged(phase, round, level, left));
        starsOut.clear();
        if (shift) {
            shiftSeed = rng.nextLong();
            shiftAt = t;
            ev.add(new LavaEvent.ShiftComing(round, shiftSeed, rules.shiftTicks()));
            pendingPick = pick;
            return; // the stars come out once the furniture has moved
        }
        shiftAt = -1;
        pendingPick = pick;
        starsOut(ev);
    }

    private int pendingPick;

    /** The floor's stars come out (and the round's surprise is told): at the safe phase's start, or after a shift. */
    private void starsOut(List<LavaEvent> ev) {
        if (surprise != null) {
            ev.add(new LavaEvent.SurpriseComing(surprise, round, pendingPick));
        }
        starsOut.clear();
        if (mode == LavaMode.LITTLE) {
            starsOut.addAll(remaining);
        } else {
            int n = mode == LavaMode.GROUP ? rules.stars() : Math.max(1, alive.size() + 2);
            for (int i = 0; i < n; i++) {
                starsOut.add(nextStar++);
            }
        }
        if (!starsOut.isEmpty()) {
            starSet++;
            ev.add(new LavaEvent.StarsOut(starSet, new ArrayList<>(starsOut), false));
        }
    }

    /** This round's surprise, or none: never in round 1, never the same twice running; Little's only friendly. */
    private Surprise pickSurprise() {
        if (!rules.surprises() || round < 2) {
            return null;
        }
        List<Surprise> from = new ArrayList<>();
        for (Surprise s : Surprise.values()) {
            boolean fits = switch (mode) {
                case LITTLE -> s.gentle();
                case GROUP -> true;
                case CHALLENGE -> s != Surprise.BOUNCY;
            };
            if (fits && s != lastSurprise) {
                from.add(s);
            }
        }
        if (mode == LavaMode.LITTLE && rng.nextInt(2) == 0) {
            return null; // Little: now and then
        }
        return from.isEmpty() ? null : from.get(rng.nextInt(from.size()));
    }

    /** The lava came: stars on the furniture, a few at a time. */
    private void lavaStars(List<LavaEvent> ev) {
        boolean shower = surprise == Surprise.STAR_SHOWER;
        switch (mode) {
            case LITTLE -> {
                lavaLeft = Integer.MAX_VALUE;
                waveSize = shower ? 2 * LITTLE_LAVA_STARS : LITTLE_LAVA_STARS;
            }
            case GROUP -> {
                lavaLeft = rules.lavaStars() * (shower ? 2 : 1);
                waveSize = shower ? 3 : 2;
            }
            case CHALLENGE -> {
                waveSize = Math.max(1, alive.size()) * (shower ? 2 : 1);
                lavaLeft = 2 * waveSize;
            }
        }
        wave(ev);
    }

    /** The next few stars on the furniture, while the lava is up and there are stars left to come. */
    private void wave(List<LavaEvent> ev) {
        if (phase != Phase.LAVA || !starsOut.isEmpty() || lavaLeft <= 0) {
            return;
        }
        List<Integer> ids = new ArrayList<>();
        if (mode == LavaMode.LITTLE) {
            for (int id : remaining) {
                if (ids.size() >= waveSize) {
                    break;
                }
                ids.add(id);
            }
        } else {
            int n = Math.min(waveSize, lavaLeft);
            for (int i = 0; i < n; i++) {
                ids.add(nextStar++);
            }
            lavaLeft -= n;
        }
        if (ids.isEmpty()) {
            return;
        }
        starsOut.addAll(ids);
        starSet++;
        ev.add(new LavaEvent.StarsOut(starSet, ids, true));
    }

    private void lavaDone(List<LavaEvent> ev) {
        lavaCame++;
        starsIn(ev);
        if (mode == LavaMode.GROUP) {
            int goal = rules.goal(round, members.size());
            boolean made = roundGot >= goal;
            if (made) {
                goalsMade++;
            } else {
                hearts = Math.max(0, hearts - 1);
            }
            ev.add(new LavaEvent.Goal(round, roundGot, goal, made, hearts));
            if (hearts == 0) {
                end(ev, false);
                return;
            }
        }
        if (mode == LavaMode.CHALLENGE) {
            List<UUID> starless = new ArrayList<>();
            for (UUID p : alive) {
                if (got.getOrDefault(p, 0) < rules.need(round)) {
                    starless.add(p);
                }
            }
            for (UUID p : alive) {
                if (!starless.contains(p)) {
                    survived.merge(p, 1, Integer::sum);
                }
            }
            if (!starless.isEmpty()) {
                alive.removeAll(starless);
                int place = alive.size() + 1;
                for (UUID p : starless) {
                    places.put(p, place);
                    ev.add(new LavaEvent.Out(p, place, survived(p), false, true));
                }
                settleChallenge(ev);
                if (phase == Phase.OVER) {
                    return;
                }
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
     * place). Little and Group: each is put back, and in Group each costs a star while there is one (and a star of
     * the round's goal). Challenge: each is out.
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
                if (mode == LavaMode.GROUP) {
                    roundGot = Math.max(0, roundGot - 1);
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
            ev.add(new LavaEvent.Out(p, place, survived(p), false, false));
        }
        settleChallenge(ev);
        return ev;
    }

    /**
     * A player walked into star {@code id}. Only a star that is out counts, only while the round is being played
     * and the player is in it (in Challenge, still standing).
     */
    public List<LavaEvent> collected(UUID player, int id) {
        List<LavaEvent> ev = new ArrayList<>();
        if ((phase != Phase.SAFE && phase != Phase.WARNING && phase != Phase.LAVA) || !members.contains(player)
                || (mode == LavaMode.CHALLENGE && !alive.contains(player)) || !starsOut.remove(id)) {
            return ev;
        }
        collected++;
        roundGot++;
        int mine = got.merge(player, 1, Integer::sum);
        remaining.remove(id);
        int have = switch (mode) {
            case LITTLE, GROUP -> collected;
            case CHALLENGE -> mine;
        };
        int goal = switch (mode) {
            case LITTLE -> rules.stars();
            case GROUP -> rules.goal(round, members.size());
            case CHALLENGE -> rules.need(round);
        };
        ev.add(new LavaEvent.StarTaken(player, id, have, goal));
        if (mode == LavaMode.LITTLE && remaining.isEmpty()) {
            end(ev, false);
            return ev;
        }
        wave(ev);
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
        shiftAt = -1;
        List<LavaResult.Standing> standings = new ArrayList<>();
        if (mode == LavaMode.CHALLENGE) {
            for (UUID p : started) {
                standings.add(new LavaResult.Standing(p, alive.contains(p) ? 1 : places.getOrDefault(p, 1),
                        survived(p)));
            }
            standings.sort(Comparator.comparingInt(LavaResult.Standing::place));
        }
        result = new LavaResult(mode, calledOff, new ArrayList<>(members), mode == LavaMode.CHALLENGE ? 0 : collected,
                lavaCame, standings, started.size() == 1, goalsMade, hearts);
        ev.add(new LavaEvent.Ended(result));
    }

    // ---- reading it -----------------------------------------------------------------------------

    public LavaMode mode() {
        return mode;
    }

    public Rules rules() {
        return rules;
    }

    /** The seed its random numbers come from. */
    public long seed() {
        return seed;
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
        return phase == Phase.OVER || phase == Phase.GATHER ? 0
                : rules.level(mode, round, surprise == Surprise.FAST_RISE);
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

    // ---- 0.57.0 ----

    /** This round's surprise, or {@code null}. */
    public Surprise surprise() {
        return surprise;
    }

    /** Whether the furniture is waiting to move this round (its warning is on). */
    public boolean shifting() {
        return phase == Phase.SAFE && shiftAt >= 0;
    }

    /** Group: this round's stars so far (less its touches); the goal; the hearts left. */
    public int roundStars() {
        return roundGot;
    }

    public int roundGoal() {
        return mode == LavaMode.GROUP ? rules.goal(Math.max(1, round), members.size()) : 0;
    }

    public int hearts() {
        return hearts;
    }

    /** Challenge: a player's stars this round, and what each needs. */
    public int starsThisRound(UUID player) {
        return got.getOrDefault(player, 0);
    }

    public int need() {
        return mode == LavaMode.CHALLENGE ? rules.need(Math.max(1, round)) : 0;
    }

    /** Whether the stars out now are the lava's (up on the furniture). */
    public boolean lavaStarsOut() {
        return phase == Phase.LAVA && !starsOut.isEmpty();
    }
}
