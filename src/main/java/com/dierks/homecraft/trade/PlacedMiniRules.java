package com.dierks.homecraft.trade;

/**
 * What a player's break of a Mini head does, and when (0.37.3, red team D-M6). Pure, so the order is tested without
 * a server.
 *
 * <p>{@link MiniHeadListener} decides at HIGH and acts at MONITOR, the rule the shop blocks and placed prizes follow:
 * the exact copy drops only once the break is final. Dropped at HIGH, a break that a protection plugin cancelled
 * later left the head standing with its copy still on it and a second copy on the ground.
 */
public final class PlacedMiniRules {

    private PlacedMiniRules() {
    }

    /** What the HIGH handler does with a player's break of a Mini head. */
    public enum Decision {
        /** A wild Mini: whoever broke it catches it. It carries no copy, so nothing drops at MONITOR. */
        CATCH,
        /** Someone who may not take it here: the break is cancelled. */
        REFUSE,
        /** Allowed: nothing happens yet. The exact copy drops at MONITOR if the break stands. */
        WAIT
    }

    /**
     * @param wild     the head is a wild spawn (the hunt is on and the head is tagged wild)
     * @param mayBreak the breaker may take a placed one here (its owner, an admin, or build rights)
     */
    public static Decision decide(boolean wild, boolean mayBreak) {
        if (wild) {
            return Decision.CATCH;
        }
        return mayBreak ? Decision.WAIT : Decision.REFUSE;
    }

    /** Whether a final break (MONITOR) drops the head's exact copy: any Mini head but a wild one, caught at HIGH. */
    public static boolean dropsCopy(boolean wild) {
        return !wild;
    }
}
