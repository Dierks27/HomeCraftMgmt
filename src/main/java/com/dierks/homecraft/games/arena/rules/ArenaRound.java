package com.dierks.homecraft.games.arena.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The life of the Falling Floors arena, one round after another (EVENTS-DROPPER-SPEC §B.3.3): who
 * is in the gallery, when a round starts, who is still standing, who went out when and in what
 * place, and when the floors must be reset.
 *
 * <pre>
 *   RESET --verified--&gt; LOBBY --2+ here, min ready or 20 s--&gt; COUNTDOWN (10 s) --&gt; TELEPORT (2 a tick)
 *     ^  \--3 failed verifies--&gt; CLOSED          \--"Play solo", alone--------------^        |
 *     |                                                                               HOLD (3 s)
 *     +------------------------ the round ended (results) &lt;------ PLAYING &lt;-----------------+
 * </pre>
 *
 * <p>Why it starts in RESET: a round needs the box verified equal to this week's plan, and at boot
 * nobody knows what a crash left behind. The gate is simply "not in RESET": the first verify opens
 * it, and every round ends by going back through it, so no round is ever played on half a floor.
 *
 * <p>Why places are settled once a tick: players out on the same tick share their place, and the
 * game learns about them one by one. {@link #out} only notes it; {@link #tick} settles everyone
 * noted that tick together, so the order the server happened to check players in never decides who
 * came 2nd.
 *
 * <p>Pure: players are UUIDs, time is ticks, and everything the game must do comes out of
 * {@link #drain} as {@link RoundEvent}s. Not thread-safe (the main thread drives it).
 */
public final class ArenaRound {

    /** Where the arena is. */
    public enum Phase {
        /** The floors are being put back and verified (the gate is shut). */
        RESET,
        /** Waiting in the gallery for players. */
        LOBBY,
        /** The 10 s bar before a multiplayer round. */
        COUNTDOWN,
        /** Moving the round's players to the spawns, two a tick. */
        TELEPORT,
        /** Everyone on a spawn, held in place: 3-2-1. */
        HOLD,
        /** The floors are falling. */
        PLAYING,
        /** Closed: no rounds until {@link #reopen}. */
        CLOSED;

        /** Whether a round has started and not yet ended. */
        public boolean inRound() {
            return this == TELEPORT || this == HOLD || this == PLAYING;
        }
    }

    /** What joining the gallery did. */
    public enum Join {
        JOINED, ALREADY_IN, FULL, CLOSED
    }

    /** What "Play solo" did. */
    public enum Solo {
        STARTED,
        /** {@code solo: false}. */
        OFF,
        /** Not in the gallery. */
        NOT_IN,
        /** Someone else is here: play together instead. */
        NOT_ALONE,
        /** A round is already going. */
        BUSY,
        /** The floors are being fixed (the gate is shut). */
        NOT_READY,
        /** A restart is close. */
        HELD,
        CLOSED
    }

    private final RoundSettings settings;
    private int spawnCount;
    private final LinkedHashSet<UUID> members = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> ready = new LinkedHashSet<>();
    private final List<RoundEvent> outbox = new ArrayList<>();
    private Phase phase;
    private long clock;
    private long pairSince = -1;
    private int countdownLeft;
    private int holdLeft;
    private int resetAttempt;
    private int roundNo;
    private String closedReason = "";

    // ---- the round going now ----
    private boolean solo;
    private final List<UUID> starters = new ArrayList<>();
    private final Map<UUID, Integer> spawnOf = new HashMap<>();
    private final ArrayDeque<UUID> toTeleport = new ArrayDeque<>();
    private final LinkedHashSet<UUID> alive = new LinkedHashSet<>();
    private final Map<UUID, OutReason> pendingOut = new HashMap<>();
    private final List<Standing> outs = new ArrayList<>();
    private long playTicks;
    private boolean suddenDeath;

    /**
     * A closed gate: it starts in {@link Phase#RESET} and asks for the boot verify at once.
     *
     * @param spawnCount how many spawns the week's layout has
     */
    public ArenaRound(RoundSettings settings, int spawnCount) {
        this.settings = settings == null ? RoundSettings.defaults() : settings;
        spawns(spawnCount);
        resetNeeded();
    }

    // ---- players --------------------------------------------------------------------------------

    /** A player enters the gallery. They play the next round that starts (a running one is theirs to watch). */
    public Join join(UUID player) {
        if (player == null) {
            throw new IllegalArgumentException("who is joining?");
        }
        if (phase == Phase.CLOSED) {
            return Join.CLOSED;
        }
        if (members.contains(player)) {
            return Join.ALREADY_IN;
        }
        if (members.size() >= settings.maxPlayers()) {
            return Join.FULL;
        }
        members.add(player);
        if (phase == Phase.LOBBY && members.size() == 2 && pairSince < 0) {
            pairSince = clock; // the second arrival starts the 20 s
        }
        return Join.JOINED;
    }

    /**
     * A player leaves the gallery ("Leave game", {@code /hcm leave}, a disconnect). Mid-round they
     * are out (LEFT) on this tick; before Go they are simply dropped, and a round left with too few
     * is called off.
     *
     * @return whether they were here
     */
    public boolean leave(UUID player) {
        if (player == null || !members.remove(player)) {
            return false;
        }
        ready.remove(player);
        if (members.size() < 2) {
            pairSince = -1;
        }
        switch (phase) {
            case COUNTDOWN -> {
                if (members.size() < 2) {
                    cancel(RoundEvent.Why.TOO_FEW);
                }
            }
            case TELEPORT, HOLD -> dropBeforeGo(player);
            case PLAYING -> {
                if (alive.contains(player)) {
                    pendingOut.putIfAbsent(player, OutReason.LEFT);
                }
            }
            default -> {
            }
        }
        return true;
    }

    /** "Ready" on or off, in the lobby or during the countdown. Returns whether it changed. */
    public boolean ready(UUID player, boolean on) {
        if (!members.contains(player) || (phase != Phase.LOBBY && phase != Phase.COUNTDOWN)) {
            return false;
        }
        return on ? ready.add(player) : ready.remove(player);
    }

    /** "Play solo": only when alone, with solo on, the floors ready and no restart close. Starts at once. */
    public Solo solo(UUID player, boolean hold) {
        if (phase == Phase.CLOSED) {
            return Solo.CLOSED;
        }
        if (!settings.solo()) {
            return Solo.OFF;
        }
        if (!members.contains(player)) {
            return Solo.NOT_IN;
        }
        if (phase.inRound()) {
            return Solo.BUSY;
        }
        if (phase == Phase.RESET) {
            return Solo.NOT_READY;
        }
        if (members.size() > 1) {
            return Solo.NOT_ALONE;
        }
        if (hold) {
            return Solo.HELD;
        }
        startRound(List.of(player), true);
        return Solo.STARTED;
    }

    /**
     * A player in the round is out this tick (their feet went below {@code out_y}, or the void).
     * Settled with everyone else out this tick at the next {@link #tick}.
     *
     * @return whether it was noted (false when they aren't still in, or already noted)
     */
    public boolean out(UUID player, OutReason reason) {
        if (phase != Phase.PLAYING || reason == null || !alive.contains(player)) {
            return false;
        }
        return pendingOut.putIfAbsent(player, reason) == null;
    }

    // ---- time -----------------------------------------------------------------------------------

    /**
     * One server tick. {@code hold} is the restart hold: while it is on no new round starts (a
     * countdown stops), and a round already going finishes.
     */
    public void tick(boolean hold) {
        clock++;
        switch (phase) {
            case LOBBY -> lobbyTick(hold);
            case COUNTDOWN -> countdownTick(hold);
            case TELEPORT -> teleportTick();
            case HOLD -> holdTick();
            case PLAYING -> playTick();
            default -> {
            }
        }
    }

    private void lobbyTick(boolean hold) {
        if (hold || members.size() < 2) {
            return;
        }
        boolean enoughReady = ready.size() >= settings.minPlayers();
        boolean waitedLongEnough = pairSince >= 0 && clock - pairSince >= RoundSettings.AUTO_START_TICKS;
        if (enoughReady || waitedLongEnough) {
            phase = Phase.COUNTDOWN;
            countdownLeft = RoundSettings.COUNTDOWN_TICKS;
            outbox.add(new RoundEvent.CountdownStarted(RoundSettings.COUNTDOWN_TICKS));
        }
    }

    private void countdownTick(boolean hold) {
        if (hold) {
            cancel(RoundEvent.Why.HOLD);
            return;
        }
        if (members.size() < 2) {
            cancel(RoundEvent.Why.TOO_FEW);
            return;
        }
        if (--countdownLeft <= 0) {
            List<UUID> players = new ArrayList<>(members);
            startRound(players.subList(0, Math.min(players.size(), settings.maxPlayers())), false);
        }
    }

    private void teleportTick() {
        for (int i = 0; i < RoundSettings.TELEPORTS_PER_TICK && !toTeleport.isEmpty(); i++) {
            UUID p = toTeleport.pollFirst();
            outbox.add(new RoundEvent.TeleportTo(p, spawnOf.get(p)));
        }
        if (toTeleport.isEmpty()) {
            phase = Phase.HOLD;
            holdLeft = RoundSettings.HOLD_TICKS;
            outbox.add(new RoundEvent.HoldStarted(RoundSettings.HOLD_TICKS));
        }
    }

    private void holdTick() {
        if (--holdLeft <= 0) {
            phase = Phase.PLAYING;
            playTicks = 0;
            outbox.add(new RoundEvent.Go(roundNo));
        }
    }

    private void playTick() {
        if (!suddenDeath && playTicks >= settings.roundTicks()) {
            suddenDeath = true;
            outbox.add(new RoundEvent.SuddenDeath(roundNo));
        }
        settle();
        if (solo ? alive.isEmpty() : alive.size() <= 1) {
            end();
            return;
        }
        playTicks++;
    }

    /** Everyone noted out this tick goes out together, sharing the place: 1 + how many are still in. */
    private void settle() {
        if (pendingOut.isEmpty()) {
            return;
        }
        List<UUID> group = new ArrayList<>();
        for (UUID p : starters) {
            if (pendingOut.containsKey(p)) {
                group.add(p);
            }
        }
        alive.removeAll(group);
        int place = 1 + alive.size();
        boolean tied = group.size() > 1;
        for (UUID p : group) {
            OutReason r = pendingOut.get(p);
            outs.add(new Standing(p, place, playTicks, r, tied, false));
            outbox.add(new RoundEvent.Out(p, playTicks, place, starters.size(), tied, r, solo));
        }
        pendingOut.clear();
    }

    private void end() {
        List<Standing> all = new ArrayList<>();
        for (UUID p : alive) {
            all.add(new Standing(p, 1, playTicks, null, false, false));
        }
        all.addAll(outs);
        int playedOut = 0;
        for (Standing s : all) {
            if (!s.left()) {
                playedOut++;
            }
        }
        boolean contested = !solo && playedOut >= 2;
        List<Standing> ranked = new ArrayList<>();
        for (Standing s : all) {
            boolean win = contested && s.place() == 1 && !s.left();
            ranked.add(new Standing(s.player(), s.place(), s.survivedTicks(), s.reason(), s.tied(), win));
        }
        ranked.sort(Comparator.comparingInt(Standing::place).thenComparingInt(s -> starters.indexOf(s.player())));
        outbox.add(new RoundEvent.Ended(new RoundResult(roundNo, solo, false, contested, starters.size(), playTicks,
                ranked)));
        clearRound();
        resetNeeded();
    }

    // ---- the reset and the gate -----------------------------------------------------------------

    /**
     * The reset asked for by {@link RoundEvent.ResetNeeded} is over: {@code verified} when the box
     * now equals the week's plan. Verified opens the lobby; a failure asks again, and the third
     * failure in a row closes the game.
     *
     * @return false when no reset was waiting (a late answer is ignored)
     */
    public boolean resetDone(boolean verified) {
        if (phase != Phase.RESET) {
            return false;
        }
        if (verified) {
            phase = Phase.LOBBY;
            pairSince = members.size() >= 2 ? clock : -1;
            outbox.add(new RoundEvent.LobbyOpen());
            return true;
        }
        if (resetAttempt >= RoundSettings.RESET_ATTEMPTS) {
            close("The floors could not be put back after " + RoundSettings.RESET_ATTEMPTS + " tries");
            return true;
        }
        resetAttempt++;
        outbox.add(new RoundEvent.ResetNeeded(resetAttempt));
        return true;
    }

    /**
     * The floors must be rebuilt now (a new week's shape, an admin reset): from the lobby or the
     * countdown at once; mid-round it waits for the reset every round ends with anyway.
     *
     * @return whether a reset was asked for now
     */
    public boolean requestReset() {
        switch (phase) {
            case COUNTDOWN -> cancel(RoundEvent.Why.RESET);
            case LOBBY, RESET -> {
            }
            default -> {
                return false;
            }
        }
        resetNeeded();
        return true;
    }

    /** Close the game: a round going is called off, and nothing starts until {@link #reopen}. */
    public void close(String reason) {
        if (phase == Phase.CLOSED) {
            return;
        }
        if (phase == Phase.COUNTDOWN) {
            outbox.add(new RoundEvent.CountdownCancelled(RoundEvent.Why.CLOSED));
        }
        if (phase.inRound()) {
            outbox.add(new RoundEvent.Ended(RoundResult.calledOff(roundNo, solo, starters.size(), playTicks)));
        }
        clearRound();
        ready.clear();
        pairSince = -1;
        phase = Phase.CLOSED;
        closedReason = reason == null ? "" : reason;
        outbox.add(new RoundEvent.Closed(closedReason));
    }

    /** Open a closed game again: it starts with a reset, like a boot. */
    public boolean reopen() {
        if (phase != Phase.CLOSED) {
            return false;
        }
        closedReason = "";
        resetNeeded();
        return true;
    }

    /** A new week's layout with this many spawns; used from the next round on. */
    public void spawns(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("a layout has at least one spawn: " + count);
        }
        this.spawnCount = count;
    }

    /** Everything that happened since the last drain, in order. */
    public List<RoundEvent> drain() {
        List<RoundEvent> out = List.copyOf(outbox);
        outbox.clear();
        return out;
    }

    // ---- inside ---------------------------------------------------------------------------------

    private void startRound(List<UUID> players, boolean soloRound) {
        roundNo++;
        solo = soloRound;
        starters.clear();
        starters.addAll(players);
        alive.clear();
        alive.addAll(players);
        toTeleport.clear();
        toTeleport.addAll(players);
        spawnOf.clear();
        for (int i = 0; i < players.size(); i++) {
            spawnOf.put(players.get(i), spawnIndex(i, players.size(), spawnCount, roundNo));
        }
        outs.clear();
        pendingOut.clear();
        playTicks = 0;
        suddenDeath = false;
        ready.clear();
        phase = Phase.TELEPORT;
        outbox.add(new RoundEvent.RoundStarting(roundNo, players, soloRound));
    }

    /**
     * Player {@code i} of {@code n}'s spawn: spread evenly round the spawns (every other one for
     * half as many players), turned by one each round so nobody always gets the same spot.
     */
    static int spawnIndex(int i, int n, int spawnCount, int round) {
        int base = n >= spawnCount ? i % spawnCount : (int) ((long) i * spawnCount / n);
        return Math.floorMod(base + round - 1, spawnCount);
    }

    private void dropBeforeGo(UUID player) {
        if (!starters.remove(player)) {
            return;
        }
        alive.remove(player);
        toTeleport.remove(player);
        spawnOf.remove(player);
        if (solo ? starters.isEmpty() : starters.size() < 2) {
            outbox.add(new RoundEvent.Ended(RoundResult.calledOff(roundNo, solo, starters.size(), 0)));
            clearRound();
            resetNeeded();
        }
    }

    private void cancel(RoundEvent.Why why) {
        phase = Phase.LOBBY;
        countdownLeft = 0;
        pairSince = members.size() >= 2 ? clock : -1;
        outbox.add(new RoundEvent.CountdownCancelled(why));
    }

    private void resetNeeded() {
        phase = Phase.RESET;
        resetAttempt = 1;
        outbox.add(new RoundEvent.ResetNeeded(1));
    }

    private void clearRound() {
        starters.clear();
        alive.clear();
        toTeleport.clear();
        spawnOf.clear();
        pendingOut.clear();
        outs.clear();
        holdLeft = 0;
        countdownLeft = 0;
        suddenDeath = false;
    }

    // ---- reading it -----------------------------------------------------------------------------

    public Phase phase() {
        return phase;
    }

    /** Everyone in the gallery or the round, in arrival order. */
    public List<UUID> members() {
        return List.copyOf(members);
    }

    public boolean isMember(UUID player) {
        return members.contains(player);
    }

    public int readyCount() {
        return ready.size();
    }

    public boolean isReady(UUID player) {
        return ready.contains(player);
    }

    /** The round's players, in start order (empty between rounds). */
    public List<UUID> starters() {
        return List.copyOf(starters);
    }

    /** The round's players still in, in start order. */
    public List<UUID> alive() {
        return List.copyOf(alive);
    }

    public boolean isAlive(UUID player) {
        return alive.contains(player);
    }

    /** Members not in the round going now: they watch from the gallery and play the next one. */
    public List<UUID> waiting() {
        List<UUID> out = new ArrayList<>();
        for (UUID p : members) {
            if (!starters.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Play ticks since Go: the tick being played now. */
    public long playTicks() {
        return playTicks;
    }

    /** Ticks since this arena was made. */
    public long clock() {
        return clock;
    }

    /** The number of the latest round (0 before the first). */
    public int roundNo() {
        return roundNo;
    }

    /** Whether the round going now is solo. */
    public boolean solo() {
        return phase.inRound() && solo;
    }

    public boolean suddenDeath() {
        return suddenDeath;
    }

    /** Ticks left on the countdown bar (0 when there is none). */
    public int countdownLeft() {
        return phase == Phase.COUNTDOWN ? countdownLeft : 0;
    }

    /** Ticks left of the 3-2-1 hold (0 when there is none). */
    public int holdLeft() {
        return phase == Phase.HOLD ? holdLeft : 0;
    }

    /** Ticks until the lobby starts the countdown by itself, or -1 when it isn't waiting to. */
    public long autoStartIn() {
        if (phase != Phase.LOBBY || pairSince < 0) {
            return -1;
        }
        return Math.max(0, RoundSettings.AUTO_START_TICKS - (clock - pairSince));
    }

    /** Which try of the reset this is (1-3), while in RESET. */
    public int resetAttempt() {
        return resetAttempt;
    }

    /** Why it is closed ("" when it isn't). */
    public String closedReason() {
        return closedReason;
    }

    public RoundSettings settings() {
        return settings;
    }

    /** How many spawns the next round spreads its players over. */
    public int spawnCount() {
        return spawnCount;
    }
}
