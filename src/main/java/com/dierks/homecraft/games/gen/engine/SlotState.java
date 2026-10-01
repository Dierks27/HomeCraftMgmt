package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenSeed;
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
     * @param half      the half it stands in
     * @param plan      its plan
     * @param day       the first day of the edition it was made for
     * @param seed      its seed
     * @param mix       the tier or mix it was made with
     * @param cadence   that edition's length in days
     * @param reroll    the reroll it was made as (0 for a preview of the next set)
     * @param fallDepth the {@code trials.fall_depth} it was made with (fix2-D: choose keeps the depth
     *                  that shapes its layout), or 0 when unknown
     */
    record Preview(char half, Plan plan, long day, long seed, String mix, int cadence, int reroll, int fallDepth) {

        /** A preview whose fall depth is unknown. */
        Preview(char half, Plan plan, long day, long seed, String mix, int cadence, int reroll) {
            this(half, plan, day, seed, mix, cadence, reroll, 0);
        }
    }

    final Slots.Def def;
    /** A Classics slot (GEN-SPEC-KEEP §3): empty until an admin recalls an archived course into it. */
    final boolean classic;

    // ---- where and how (refreshed from config and hcm_meta at every check) ----------------------
    String world = "";
    int[] origin;
    /** The blocks between its halves ({@code half_gap}): half B stands {@code def.sizeX() + gap} from the origin. */
    int gap = Slots.HALF_GAP;
    boolean configOn;
    /**
     * Config can't say where it stands (its origin or half_gap can't be read: {@code SlotConfig#placed}):
     * it stays where it was claimed, and is off whatever an admin's override says, so nothing is built
     * or moved until config is fixed.
     */
    boolean unplaced;
    /** The admin's on/off, or {@code null} for none. */
    Boolean override;
    /** The tier or mix a build would use now. */
    String mix = "";
    GenScheduler.Pin pin;
    /**
     * An admin's pick for the next set ({@code gen.<slot>.choose}, WP-ADM): a one-set pin, used over
     * {@link #pin} for the one set it names, forgotten once that set is over, or once it no longer
     * names the next set or fits the settings it was tried with (fix2-D).
     */
    GenScheduler.Choice chosen;
    /**
     * A pick a config or schedule change dropped (fix2-D), as status and the admin tools show it.
     *
     * <p>Round 2, G2 #3: kept in {@code hcm_meta} ({@link GenAdminKeys#dropped}) and through the flips of
     * the set it was for, since the usual drop is at the restart at the change, a minute before that
     * set's own build flips, and an owner who uses the course screens looks later; gone once that set is
     * over ({@link #over}), or at the next pick or cancel.
     *
     * @param seed    the pick's seed
     * @param from    its set's first day (local epoch day)
     * @param cadence its set's length in days, or 0 when unknown
     * @param why     why, in the owner's terms ("it was tried as easy, and that set will be hard, ...")
     */
    record DroppedPick(long seed, long from, int cadence, String why) {

        /** Whether it is old news for a slot showing the set that starts on {@code start}: its set is over. */
        boolean over(long start) {
            return start > from;
        }

        /** As stored: {@code seed:from:cadence:why}. */
        String text() {
            return GenSeed.hex(seed) + ":" + from + ":" + cadence + ":" + why;
        }

        /** A stored note, or {@code null} when unset or unreadable. */
        static DroppedPick parse(String text) {
            if (text == null) {
                return null;
            }
            String[] p = text.split(":", 4);
            Long seed = p.length == 4 ? GenSeed.parse(p[0]) : null;
            try {
                return seed == null ? null : new DroppedPick(seed, Long.parseLong(p[1]), Integer.parseInt(p[2]), p[3]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** Why the last pick was dropped ({@link DroppedPick}), or {@code null}. */
    DroppedPick pickDropped;
    /** The current edition's reroll count. */
    int reroll;
    /** The claim matches this world, origin and gap. */
    boolean claimed;
    /**
     * A Dropper's old regions that may still hold its pools ({@link GenAdminKeys#wet}), as last read:
     * guarded until it is claimed there again.
     */
    java.util.List<String> wet = java.util.List.of();
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

    /** Switched on: the admin's override, else config; never while config can't say where it stands. */
    boolean wanted() {
        return !unplaced && (override != null ? override : configOn);
    }

    /** Switched on and nothing in the way. */
    boolean on() {
        return wanted() && problem == null;
    }

    Box half(char which) {
        return Regions.half(def, origin, gap, which);
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

    boolean sameRegion(String w, int[] o, int g) {
        return world.equalsIgnoreCase(w) && Arrays.equals(origin, o) && gap == g;
    }
}
