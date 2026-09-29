package com.dierks.homecraft.games.trial;

import java.util.List;

/**
 * One Dropper run's own rules (EVENTS-DROPPER-SPEC §B.1.7, the practice drop of
 * EVENTS-OWNER-DECISIONS D3): the offer, the practice drop, the splash, the hop to the next ledge,
 * the bonk and the clock line. Pure: everything it does to the player goes through a {@link Port}
 * ({@link DropperHooks} on the server, a fake in the tests), so every rule here runs without one.
 *
 * <p><b>The practice drop.</b> Before the timed drop, when warm-ups are on
 * ({@code games.trials.warmup_seconds > 0}), the player stands on level 1's ledge, held, with two
 * items: "Practice drop (not timed)" and "Go straight to the timed run". A practice drop is the run's
 * one warm-up ({@link TrialRun#beginWarmup} with no time limit, so a run has at most one): the hold
 * lets go, there is no clock and no {@link Progress}, and it ends on its first splash into level 1's
 * pool, its first bonk, or "Start timed run". Then the player is back on the ledge and the normal
 * 3-2-1 starts the timed run; the clock starts at Go. Nothing in a practice drop is ever timed,
 * submitted, paid, counted for the Cup or for a clean drop, and its bonks aren't the run's.
 *
 * <p><b>The timed run.</b> Its targets alternate pool, ledge, pool... ({@link DropperLayout}). A
 * splash ({@link DropperRules#splash}) reaches the pool at the moment the feet crossed into the water;
 * the title plays, and {@value DropperRules#HOP_TICKS} ticks later the run's own teleport takes the
 * player to the next ledge (velocity and fall distance zeroed, and {@link Progress#jump} when it
 * lands, so the next move starts inside the ledge mark and reaches it at once). Bonks are ignored
 * during the hop. A bonk sends the run back to the top of the level it is on and counts one; the
 * clock keeps running. The last splash is the finish.
 *
 * <p><b>Collisions.</b> The runner can't be pushed by another faller in the same shaft: it is set not
 * collidable at the start, and {@link #end} gives back what it was, once, on every way a run ends.
 */
final class DropperRun {

    /** What the run does to its player (the server's, or a test's). */
    interface Port {

        /** The server tick now. */
        long tick();

        /**
         * The run's own teleport to {x, y, z, yaw, pitch}: velocity and fall distance zeroed, and the
         * run's {@link Progress} jumps there when it lands. False when it couldn't be made.
         */
        boolean teleport(double[] stand);

        /** Hand the player one of the Dropper's kits. */
        void kit(Kit kit);

        void title(String big, String small, int stayTicks);

        void actionBar(String line);

        void chat(String line);

        void sound(Cue cue);

        /** Whether the player collides with others now. */
        boolean collidable();

        void collidable(boolean on);

        /** The last splash: finish the run at {@code nanos} (Time Trials' own finish). */
        void finish(long nanos);
    }

    /** The Dropper's kits. */
    enum Kit {
        /** The offer: "Practice drop (not timed)" and "Go straight to the timed run". */
        OFFER,
        /** During the practice drop: "Start timed run". */
        PRACTICE,
        /** The timed run: "Back to the top - of this level". */
        DROP
    }

    /** What a sound means. */
    enum Cue {
        CHOICE, SPLASH, BONK
    }

    /** Where the run is. */
    enum Stage {
        /** On level 1's ledge, held, picking: practice drop or go straight. */
        OFFER,
        /** The practice drop: untimed, uncounted. */
        PRACTICE,
        /** The countdown, then the timed run. */
        TIMED
    }

    /** How a practice drop ended. */
    enum PracticeEnd {
        SPLASHED, BONKED, SKIPPED
    }

    /** The kit actions ({@code KitItems} "gameId:action"). */
    static final String PRACTICE_ACTION = "dropper_practice";
    static final String STRAIGHT_ACTION = "dropper_straight";
    static final String TIMED_ACTION = "dropper_timed";
    static final String BACK_ACTION = "checkpoint";
    /** Every kit action the Dropper answers. */
    static final List<String> ACTIONS = List.of(PRACTICE_ACTION, STRAIGHT_ACTION, TIMED_ACTION, BACK_ACTION);
    /** The offer's reminder on the action bar this often (ticks). */
    static final int BAR_EVERY = 20;

    private final TrialRun run;
    private final Course course;
    private Stage stage;
    private int bonks;
    /** The tick the hop to the next ledge is due, or -1 once it has been sent (or there is none). */
    private long hopAt = -1;
    /** From a splash until the hop's teleport has landed: nothing counts, no bonk. */
    private boolean hopping;
    private long lastBonk = Long.MIN_VALUE / 2;
    private boolean tipShown;
    /** Where the practice drop's last move ended. */
    private Point practiceLast;
    private final DropperRules.Landing landing = new DropperRules.Landing();
    private final boolean collidableBefore;
    private boolean released;
    private int ticks;

    private DropperRun(TrialRun run, boolean collidableBefore) {
        this.run = run;
        this.course = run.course;
        this.collidableBefore = collidableBefore;
    }

    /** Whether a run is offered its practice drop: warm-ups are on ({@code games.trials.warmup_seconds > 0}). */
    static boolean offers(TimeTrialsSettings s) {
        return s != null && s.warmupsOn();
    }

    /**
     * A dropper run begins (the player is on level 1's ledge, held by the countdown): not collidable,
     * and either the offer ({@code offer}) or straight to the 3-2-1 with the drop kit.
     */
    static DropperRun start(TrialRun run, Port port, boolean offer) {
        DropperRun d = new DropperRun(run, port.collidable());
        port.collidable(false);
        if (offer && !run.warmupUsed) {
            d.stage = Stage.OFFER;
            port.kit(Kit.OFFER);
            port.title(DropperText.OFFER_TITLE, DropperText.OFFER_SUBTITLE, 60);
            port.chat(DropperText.OFFER);
            port.sound(Cue.CHOICE);
        } else {
            d.stage = Stage.TIMED;
            port.kit(Kit.DROP);
        }
        return d;
    }

    // ---- what it is -----------------------------------------------------------------------------

    Stage stage() {
        return stage;
    }

    /** Bonks in the timed run (never the practice drop's). */
    int bonks() {
        return bonks;
    }

    /** No bonk in the timed run: a clean drop ({@code game_dropper_clean}). */
    boolean clean() {
        return bonks == 0;
    }

    /** Whether the countdown waits: the offer is up, or the practice drop is on. */
    boolean holdsCountdown() {
        return stage != Stage.TIMED;
    }

    /** Whether the hold on the ledge lets go: only in the practice drop (the offer and the 3-2-1 hold). */
    boolean letsGo() {
        return stage == Stage.PRACTICE;
    }

    /** From a splash until its hop has landed. */
    boolean hopping() {
        return hopping;
    }

    /** The level (0-based) the run is on now. */
    int level() {
        return DropperLayout.levelNow(course, run.progress == null ? 0 : run.progress.reachedTargets());
    }

    // ---- the offer and the practice drop -------------------------------------------------------

    /**
     * The player picked: the practice drop ({@code practice}), or straight to the timed run. Only while
     * the offer is up; a run that already had its warm-up goes straight.
     */
    void choose(Port port, boolean practice) {
        if (stage != Stage.OFFER) {
            return;
        }
        if (practice && run.beginWarmup(0)) {
            stage = Stage.PRACTICE;
            practiceLast = null;
            landing.reset();
            port.kit(Kit.PRACTICE);
            port.title(DropperText.PRACTICE_TITLE, DropperText.PRACTICE_SUBTITLE, 40);
            port.actionBar(DropperText.PRACTICE_BAR);
            port.sound(Cue.CHOICE);
            return;
        }
        stage = Stage.TIMED;
        run.countdown = TimeTrials.COUNTDOWN_TICKS + 1;
        port.kit(Kit.DROP);
    }

    /** "Start timed run" in the practice drop, or anything else that ends it early. */
    void skipPractice(Port port) {
        endPractice(port, PracticeEnd.SKIPPED);
    }

    /**
     * The practice drop is over: the warm-up ends (the run can't have another), the player goes back
     * to level 1's ledge with the drop kit, and the 3-2-1 starts again from the top.
     */
    void endPractice(Port port, PracticeEnd why) {
        if (stage != Stage.PRACTICE) {
            return;
        }
        run.endWarmup();
        stage = Stage.TIMED;
        practiceLast = null;
        landing.reset();
        run.countdown = TimeTrials.COUNTDOWN_TICKS + 1;
        port.kit(Kit.DROP);
        port.teleport(DropperLayout.stand(course, 0));
        port.chat(switch (why) {
            case SPLASHED -> DropperText.PRACTICE_SPLASH;
            case BONKED -> DropperText.PRACTICE_BONK;
            case SKIPPED -> DropperText.PRACTICE_SKIPPED;
        });
        port.sound(why == PracticeEnd.SPLASHED ? Cue.SPLASH : why == PracticeEnd.BONKED ? Cue.BONK : Cue.CHOICE);
    }

    /**
     * One tick while the countdown waits ({@link #holdsCountdown}): the offer's reminder, or the
     * practice drop's action bar and landing watch.
     *
     * @param here    the feet now
     * @param inWater in water now
     */
    void waiting(Port port, Point here, boolean inWater) {
        ticks++;
        if (stage == Stage.OFFER) {
            if (ticks % BAR_EVERY == 1) {
                port.actionBar(DropperText.OFFER_BAR);
            }
            return;
        }
        if (stage != Stage.PRACTICE || run.suspended || here == null) {
            return;
        }
        if (ticks % TimeTrials.CLOCK_EVERY == 0) {
            port.actionBar(DropperText.PRACTICE_BAR);
        }
        Course.Mark ledge = DropperLayout.ledgeOf(course, 0);
        boolean water = inWater || DropperLayout.inPool(DropperLayout.poolOf(course, 0), here);
        if (ledge != null && landing.tick(here.y(), ledge.y(), DropperRules.inShaft(ledge, here), water)) {
            endPractice(port, PracticeEnd.BONKED);
        }
    }

    /** A move in the practice drop: its first splash into level 1's pool (or a fall past the floor) ends it. */
    void practiceMove(Port port, Point to) {
        if (stage != Stage.PRACTICE || run.suspended || to == null) {
            return;
        }
        Point from = practiceLast;
        practiceLast = to;
        if (from != null && !Double.isNaN(DropperRules.splash(from, to, DropperLayout.poolOf(course, 0)))) {
            endPractice(port, PracticeEnd.SPLASHED);
            return;
        }
        if (course.fallY() != null && to.y() < course.fallY()) {
            endPractice(port, PracticeEnd.BONKED);
        }
    }

    // ---- the timed run ------------------------------------------------------------------------------

    /**
     * A move while the clock runs (and the run isn't on its way somewhere by its own teleport): a
     * splash into the next pool (its box, at the moment the feet crossed in), a ledge reached after
     * the hop, or a fall past the course's floor (a bonk).
     */
    void moved(Port port, Point to, long nanos) {
        Progress pr = run.progress;
        if (pr == null || stage != Stage.TIMED || hopping || run.suspended || to == null) {
            return;
        }
        int next = pr.reachedTargets();
        List<Course.Mark> targets = course.targets();
        if (next < targets.size() && DropperLayout.isPool(next)) {
            double t = DropperRules.splash(pr.last(), to, targets.get(next));
            if (!Double.isNaN(t)) {
                Progress.Reached r = pr.reachNext(DropperRules.splashNanos(pr.lastNanos(), nanos, t));
                pr.jump(to, nanos);
                splashed(port, r);
                return;
            }
        }
        for (Progress.Reached r : pr.move(to, nanos)) {
            if (DropperLayout.isPool(r.index())) {
                splashed(port, r);
                return;
            }
        }
        if (course.fallY() != null && to.y() < course.fallY()) {
            bonk(port, DropperRules.Why.FALL_Y);
        }
    }

    private void splashed(Port port, Progress.Reached r) {
        port.sound(Cue.SPLASH);
        if (r.finish()) {
            port.finish(r.nanos());
            return;
        }
        int levels = DropperLayout.levels(course);
        int nowOn = DropperLayout.levelOf(r.index()) + 1; // 0-based: the level after the one splashed
        hopping = true;
        hopAt = port.tick() + DropperRules.HOP_TICKS;
        landing.reset();
        port.title(DropperText.levelTitle(nowOn + 1), DropperText.levelSubtitle(levels), 25);
    }

    /**
     * One tick while the clock runs: the hop when it is due (and done once its teleport has landed),
     * else the landing watch.
     */
    void running(Port port, Point here, boolean inWater) {
        if (stage != Stage.TIMED || run.progress == null) {
            return;
        }
        if (hopping) {
            if (hopAt >= 0 && port.tick() >= hopAt) {
                hopAt = port.teleport(DropperLayout.stand(course, level())) ? -1 : port.tick() + 1;
            } else if (hopAt < 0 && !run.suspended) {
                hopping = false; // the hop's teleport landed: the next move starts on the ledge
                landing.reset();
            }
            return;
        }
        if (run.suspended || here == null) {
            landing.reset();
            return;
        }
        int level = level();
        Course.Mark ledge = DropperLayout.ledgeOf(course, level);
        boolean water = inWater || DropperLayout.inPool(DropperLayout.poolOf(course, level), here);
        if (ledge != null && landing.tick(here.y(), ledge.y(), DropperRules.inShaft(ledge, here), water)) {
            bonk(port, DropperRules.Why.LANDING);
        }
    }

    /**
     * Something went wrong ({@code why}): in the practice drop it ends the practice; in the timed run
     * it is a bonk, when {@link DropperRules#counts} says so: back to the top of this level, one more
     * bonk, the clock running. The first bonk of a run adds the steering tip.
     *
     * @return whether it did anything
     */
    boolean bonk(Port port, DropperRules.Why why) {
        if (stage == Stage.PRACTICE) {
            endPractice(port, PracticeEnd.BONKED);
            return true;
        }
        long now = port.tick();
        if (stage != Stage.TIMED || !DropperRules.counts(run.running(), hopping, run.suspended, now, lastBonk)) {
            return false;
        }
        lastBonk = now;
        bonks++;
        landing.reset();
        int level = level();
        port.teleport(DropperLayout.stand(course, level));
        port.title(DropperText.BONK_TITLE, DropperText.bonkSubtitle(level + 1), 30);
        port.sound(Cue.BONK);
        if (!tipShown) {
            tipShown = true;
            port.chat(DropperText.TIP);
        }
        return true;
    }

    /**
     * Back to the top of this level without a bonk (the run was moved by someone else, or its own
     * teleport never came): where Time Trials' "send back" goes for a dropper.
     */
    boolean back(Port port) {
        landing.reset();
        return port.teleport(DropperLayout.stand(course, stage == Stage.TIMED ? level() : 0));
    }

    /** The clock line: "&amp;e0:12.4 &amp;7· level 2 of 5 · 1 bonk". */
    String clockLine(long nanos) {
        return DropperText.clock(run.elapsedMs(nanos), level() + 1, DropperLayout.levels(course), bonks, run.test,
                run.voided != null);
    }

    /** The run is over, however it ended: the player collides again as before. Once. */
    void end(Port port) {
        if (released) {
            return;
        }
        released = true;
        port.collidable(collidableBefore);
    }

    /** Whether {@link #end} has run. */
    boolean ended() {
        return released;
    }
}
