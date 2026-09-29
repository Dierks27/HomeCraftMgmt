package com.dierks.homecraft.games.cup;

/**
 * Whether this week's Cup on a course still stands, from what the course is now (§D2: "if the course
 * is deleted, changes layout, or is closed mid-week, every entry is refunded in full and the player
 * is told why"). Pure: the wiring reads the course row and asks this once a minute, and the admin
 * commands that delete, edit or close a course void the Cup at once on top of it.
 *
 * <p><b>Why by looking, not only by the commands.</b> A Fresh course changes by an admin's reroll, a
 * promote or a recall, by the slot switched off, or by a Classics slot's time running out, each in a
 * different part of the engine. Comparing what the Cup remembered ({@link CupLayout}) with what is
 * there now catches every one of them, and a heal that puts the same layout back voids nothing.
 *
 * <p><b>What never voids.</b> A read that failed, or a course that is merely not open for a moment
 * (Fresh Courses checking its blocks at boot, its engine off during a reload): the watch only acts
 * on a row that is gone, a course an admin closed, a Fresh slot an admin switched off, or a new
 * layout. And the scheduled flip from last week's layout to the week's own one is the Cup's layout
 * going up ({@link CupLayout#covers}): runs on last week's layout already counted for no Cup.
 */
public final class CupWatch {

    private CupWatch() {
    }

    /** What to do with the Cup. */
    public enum Action {
        /** Nothing changed. */
        NONE,
        /** Remember the course's layout as it is now (the first look, or the week's own layout went up). */
        ADOPT,
        /** Call the Cup off and refund every entry, telling the players {@link Verdict#reason()}. */
        VOID
    }

    /**
     * The watch's answer.
     *
     * @param action what to do
     * @param reason why the Cup is called off ({@link Action#VOID} only)
     */
    public record Verdict(Action action, CupPlan.VoidReason reason) {

        public static final Verdict NONE = new Verdict(Action.NONE, null);
        public static final Verdict ADOPT = new Verdict(Action.ADOPT, null);

        static Verdict voided(CupPlan.VoidReason reason) {
            return new Verdict(Action.VOID, reason);
        }
    }

    /**
     * The course as it is now.
     *
     * @param exists    its row is there
     * @param enabled   an admin hasn't closed it ({@code /hcm games course <id> disable})
     * @param freshSlot its id is a Fresh Courses slot's (its row going means the slot closed)
     * @param wanted    for a Fresh slot: whether it is switched on; {@code null} when that can't be
     *                  told (Fresh Courses isn't running)
     * @param layout    its layout now, or {@code null} when it can't be read
     */
    public record Seen(boolean exists, boolean enabled, boolean freshSlot, Boolean wanted, CupLayout layout) {

        /** A course whose row is gone. */
        public static Seen gone(boolean freshSlot) {
            return new Seen(false, false, freshSlot, null, null);
        }
    }

    /**
     * What to do with the Cup of week {@code week} that remembered {@code stored} ({@code null}: nothing
     * remembered yet), now that the course is {@code now}.
     *
     * <ol>
     *   <li>The row is gone: void, "the course was removed" ({@link CupPlan.VoidReason#DELETED}), or for
     *       a Fresh slot "the course closed" (a Classics slot's recall ended, or the slot was cleared).</li>
     *   <li>An admin closed the course, or switched its Fresh slot off: void, CLOSED.</li>
     *   <li>Its layout can't be read: nothing (never void on a guess).</li>
     *   <li>Nothing remembered: adopt the layout now.</li>
     *   <li>The same layout: nothing.</li>
     *   <li>The remembered layout wasn't the week's own (last week's, still standing after the
     *       rollover): adopt the new one, it is the scheduled flip.</li>
     *   <li>Otherwise the layout changed under the Cup: void, CHANGED.</li>
     * </ol>
     */
    public static Verdict judge(CupLayout stored, Seen now, long week) {
        if (now == null) {
            return Verdict.NONE;
        }
        if (!now.exists()) {
            return Verdict.voided(now.freshSlot() ? CupPlan.VoidReason.CLOSED : CupPlan.VoidReason.DELETED);
        }
        if (!now.enabled() || (now.freshSlot() && Boolean.FALSE.equals(now.wanted()))) {
            return Verdict.voided(CupPlan.VoidReason.CLOSED);
        }
        if (now.layout() == null) {
            return Verdict.NONE;
        }
        if (stored == null) {
            return Verdict.ADOPT;
        }
        if (stored.same(now.layout())) {
            return Verdict.NONE;
        }
        if (!stored.covers(week)) {
            return Verdict.ADOPT;
        }
        return Verdict.voided(CupPlan.VoidReason.CHANGED);
    }
}
