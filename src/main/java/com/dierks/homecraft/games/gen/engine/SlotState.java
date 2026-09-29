package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.Slots;

import java.util.Arrays;

/**
 * One slot as the engine holds it in memory (GEN-SPEC §3.2-§3.5): what is live and whether it was
 * vouched for, the layout still standing beside it, and the course day's tries.
 *
 * <p>Nothing here is stored. The database has the live row (and so the live tag), and
 * {@code hcm_meta} has the admin's overrides and the claim; everything else is worked out again at
 * every start — that is why every boot verifies every live half before its gate opens (R5).
 */
final class SlotState {

    /**
     * A preview built into the idle half without a flip ({@code /hcm games gen preview}).
     *
     * @param half    the half it stands in
     * @param plan    its plan
     * @param day     the first day of the edition it was made for
     * @param seed    its seed
     * @param mix     the tier or mix it was made with
     * @param cadence that edition's length in days
     */
    record Preview(char half, Plan plan, long day, long seed, String mix, int cadence) {
    }

    final Slots.Def def;
    /** A Classics slot (GEN-SPEC-KEEP §3): empty until an admin recalls an archived course into it. */
    final boolean classic;

    // ---- where and how (refreshed from config and hcm_meta at every check) ----------------------
    String world = "";
    int[] origin;
    boolean configOn;
    /** The admin's on/off, or {@code null} for none. */
    Boolean override;
    /** The tier or mix a build would use now. */
    String mix = "";
    GenScheduler.Pin pin;
    /** The current edition's reroll count. */
    int reroll;
    /** The claim matches this world and origin. */
    boolean claimed;
    /** Why the slot can't be built or opened (world, hand-built course, foreign blocks), or {@code null}. */
    String problem;

    // ---- the layouts -------------------------------------------------------------------------------
    /** The live row's tag, or {@code null} for no generated row. */
    GenTag live;
    /** Its row's {@code rev}. */
    int rev;
    /** The tier or mix the live layout was made with. */
    String liveMix = "";
    /** The live half matches its plan (the gate). */
    boolean verified;
    /** The live layout couldn't be vouched for this run: a new one is due. */
    boolean healFailed;
    /** The layout that was live before the last flip: it stands until its half starts being cleared. */
    GenTag previous;
    /** CLEAR_OLD has started on the previous layout's half: it no longer stands. */
    boolean clearing;
    /** The idle half may still hold blocks. */
    boolean oldDirty;
    /** A preview in the idle half, or {@code null}. */
    Preview preview;

    // ---- tries and status -----------------------------------------------------------------------
    long triesDay = Long.MIN_VALUE;
    int tries;
    long lastTryAt;
    String lastError;
    /** Why a due build waits, or {@code null}. */
    String waiting;
    /** The status line about the last build ("last: 4:01:44 plan 0.05s ..."). */
    String lastLine;
    long builtAt;
    long lastClearCheck;

    // ---- a Classics slot ------------------------------------------------------------------------
    /** What an admin recalled into it ({@code gen.<slot>.recall}), or {@code null}: empty. */
    ClassicWant want;
    /** It holds nothing live, but either half may still hold blocks (cleared once nobody is on them). */
    boolean bothDirty;
    /** The recall the slot held before the one being built (kept if the new one can never be built). */
    ClassicWant prior;
    /** Where the last recall's outcome goes (the admin who asked), or {@code null}. */
    java.util.function.Consumer<String> recallReport;

    SlotState(Slots.Def def) {
        this.def = def;
        this.origin = def.origin();
        this.classic = Slots.isClassic(def.id());
    }

    /** Switched on: the admin's override, else config. */
    boolean wanted() {
        return override != null ? override : configOn;
    }

    /** Switched on and nothing in the way. */
    boolean on() {
        return wanted() && problem == null;
    }

    Box half(char which) {
        return Regions.half(def, origin, which);
    }

    /**
     * The half the next layout goes into: the other one than the live layout's, or than a layout
     * still standing after a Classics slot closed (runs on it finish there).
     */
    char idleHalf() {
        if (live != null) {
            return live.otherHalf();
        }
        return previous != null && !clearing ? previous.otherHalf() : 'A';
    }

    /** Failed tries on course day {@code day}. */
    int triesOn(long day) {
        return triesDay == day ? tries : 0;
    }

    void failedTry(long day, long now, String why) {
        if (triesDay != day) {
            triesDay = day;
            tries = 0;
        }
        tries++;
        lastTryAt = now;
        lastError = why;
    }

    boolean sameRegion(String w, int[] o) {
        return world.equalsIgnoreCase(w) && Arrays.equals(origin, o);
    }
}
