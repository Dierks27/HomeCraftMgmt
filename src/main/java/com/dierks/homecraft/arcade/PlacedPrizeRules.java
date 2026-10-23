package com.dierks.homecraft.arcade;

/**
 * Who may break a placed prize head (a hat or a trophy placed as a block), 0.37.3 (spec D17, Q7). Pure, so the rule
 * is tested without a server.
 *
 * <p>Only the player who placed it, or an admin. A head placed before 0.37.3 remembers no placer, so it stays
 * breakable by anyone, as it always was. Whoever may break it gets the prize in their bag (or their Mailbox when
 * the bag is full), never on the ground. To let anyone break any placed prize again, make
 * {@link #breakRefused} return false.
 */
public final class PlacedPrizeRules {

    private PlacedPrizeRules() {
    }

    /**
     * Whether a break of a placed prize is refused.
     *
     * @param isPlacer  the breaker is the player who placed it
     * @param admin     the breaker has {@code hcm.admin}
     * @param hasPlacer the head remembers a placer (it was placed on 0.37.3 or later)
     */
    public static boolean breakRefused(boolean isPlacer, boolean admin, boolean hasPlacer) {
        return hasPlacer && !isPlacer && !admin;
    }

    /** Where a broken placed prize ends up. */
    public enum Goes {
        /** Into the breaker's bag. */
        BAG,
        /** The bag was full: into the breaker's Mailbox. */
        MAILBOX,
        /** Not even the Mailbox could be written: the break is called off and the prize stays standing. */
        STAYS
    }

    /** The two places a broken placed prize can go, in their order. */
    public interface Hand<T> {
        /** Put it in the breaker's bag; returns what didn't fit, or null when it all went in. */
        T toBag();

        /** Write {@code rest} to the breaker's Mailbox. */
        void toMailbox(T rest) throws java.sql.SQLException;
    }

    /**
     * Hand a broken placed prize over (once the break is final): the breaker's bag, and what doesn't fit their
     * Mailbox. Never the ground, where anyone could pick it up.
     */
    public static <T> Goes handOver(Hand<T> hand) {
        T rest = hand.toBag();
        if (rest == null) {
            return Goes.BAG;
        }
        try {
            hand.toMailbox(rest);
            return Goes.MAILBOX;
        } catch (java.sql.SQLException | RuntimeException e) {
            return Goes.STAYS;
        }
    }
}
