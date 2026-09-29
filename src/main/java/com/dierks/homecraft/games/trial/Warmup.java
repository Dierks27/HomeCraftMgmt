package com.dierks.homecraft.games.trial;

/**
 * The warm-up before a counted run (owner decision D3), its words and its clock, pure and tested.
 *
 * <p><b>Why a warm-up.</b> A timed run starts the clock at Go, so the first go at a new course is
 * always spent learning it. The owner asked for a few free minutes first: starting a course offers
 * "Warm up (3:00)" or "Go straight to the timed run". A warm-up is the course as usual, checkpoints
 * guiding and Back to checkpoint working, as many laps as you like, but nothing in it is ever timed
 * for the record, submitted, paid or counted for the Weekly Cup. It ends when its time is up or the
 * player taps "Start timed run"; then they go back to the start and the normal 3-2-1 begins. A run
 * gets at most one ({@code TrialRun.beginWarmup}). {@code games.trials.warmup_seconds} sets its
 * length; 0 turns warm-ups off, and the start goes straight to the timed run as it always did.
 *
 * <p>A race has a shared warm-up instead ({@link RaceLink#warmupUntil()}), with "Ready" in place of
 * "Start timed run": the coordinator sends everyone to the grid together.
 *
 * <p>The words carry the key facts in item NAMES (Bedrock shows no lore without a tap and hold).
 */
public final class Warmup {

    /** The kit slot of "Start timed run" (and a race's "Ready"). */
    public static final int KIT_SLOT = 4;
    /** The kit action of "Start timed run". */
    public static final String TIMED = "timed";
    /** The kit action of a race warm-up's "Ready". */
    public static final String READY = "ready";

    /** The choice that skips the warm-up. */
    public static final String STRAIGHT = "Go straight to the timed run";

    private Warmup() {
    }

    /** Whether starting {@code test} (an admin's test run) on these settings offers a warm-up. */
    public static boolean offered(TimeTrialsSettings s, boolean test) {
        return s != null && s.warmupsOn() && !test;
    }

    /** The server tick a warm-up of {@code seconds} started at tick {@code now} ends. */
    public static long endsAt(long now, int seconds) {
        return now + Math.max(0, seconds) * 20L;
    }

    /** "3:00", "0:45": whole minutes and seconds. */
    public static String clock(long seconds) {
        long s = Math.max(0, seconds);
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    /** The seconds left, rounded up, at tick {@code now} of a warm-up ending at tick {@code ends}. */
    public static long secondsLeft(long now, long ends) {
        return ends <= now ? 0 : (ends - now + 19) / 20;
    }

    /** The choice that warms up first: "Warm up (3:00)". */
    public static String choice(int seconds) {
        return "Warm up (" + clock(seconds) + ")";
    }

    /** The action bar while warming up: "Warm-up 2:14 left - not counted". */
    public static String bar(long secondsLeft) {
        return "&bWarm-up " + clock(secondsLeft) + " left &7- not counted";
    }

    /** The action bar once a race's warm-up is over and the racer waits for the grid. */
    public static final String BAR_TO_GRID = "&eWarm-up over &7- off to the grid!";
    /** The action bar once a racer tapped Ready. */
    public static final String BAR_READY = "&aReady! &7Warm up as much as you like - not counted";

    /** What a player reads when a warm-up starts. */
    public static String started(int seconds) {
        return "&bWarm-up: " + clock(seconds) + " of free laps. &7Nothing is timed or counted.";
    }

    /** How to end a solo warm-up early. */
    public static final String HOW_TO_END = "&7Tap &aStart timed run &7when you're ready.";
    /** How to end a race warm-up early. */
    public static final String HOW_TO_READY = "&7Tap &aReady &7when you're set - the race starts when everyone is.";

    /** A lap finished in the warm-up. */
    public static String lap(String time) {
        return "&7Warm-up lap: &f" + time + " &8(not counted)";
    }

    /** The warm-up ended; the timed run's 3-2-1 is next. {@code early}: "Start timed run" was tapped. */
    public static String ended(boolean early) {
        return early ? "&aTimed run! &7Back to the start for the 3-2-1."
                : "&eWarm-up over! &7Back to the start for the 3-2-1.";
    }

    /** The kit item's NAME that ends a solo warm-up. */
    public static final String TIMED_NAME = "&aStart timed run &7- ends the warm-up";
    /** The kit item's NAME a racer taps when set. */
    public static final String READY_NAME = "&aReady &7- set for the race";
}
