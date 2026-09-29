package com.dierks.homecraft.games.cup;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * How one Weekly Cup ends: who gets what, and why (§D2). It is worked out whole before a single token
 * moves, by {@link CupRules#settle} at the week's rollover or {@link CupRules#voided} when the course
 * goes away mid-week, and then written in ONE transaction together with the settlement row, so a Cup
 * pays out exactly once.
 *
 * <p>Every plan balances: the tokens it pays out are exactly the entries plus the top-up, and the
 * top-up is 0 unless two or more entrants raced for the pool. {@link CupRules#problems} proves it
 * for a given plan.
 *
 * @param key     which Cup
 * @param outcome how it ended
 * @param reason  why it was voided; {@code null} unless {@link Outcome#VOIDED}
 * @param entries the sum of what the entrants paid
 * @param topup   the server's top-up (0 unless {@link Outcome#PRIZES})
 * @param pool    {@code entries + topup}: what the plan pays out, all of it
 * @param lines   one line per entrant, in place order (refunds in entry order)
 */
public record CupPlan(CupKey key, Outcome outcome, VoidReason reason, int entries, int topup, int pool,
                      List<CupPayout> lines) {

    /** How a Cup ended. */
    public enum Outcome {
        /** Nobody entered: nothing to pay, the week is simply closed. */
        EMPTY,
        /** Two or more Cup times: the pool is shared by Cup time. */
        PRIZES,
        /** One entrant: their entry comes back, with no top-up. */
        REFUND_ALONE,
        /** Two or more entrants, but fewer than two set a Cup time: every entry comes back. */
        REFUND_NO_CONTEST,
        /** The course was deleted, changed layout or closed mid-week: every entry comes back. */
        VOIDED;

        /** Whether this outcome gives every entry back. */
        public boolean refunds() {
            return this == REFUND_ALONE || this == REFUND_NO_CONTEST || this == VOIDED;
        }
    }

    /** Why a Cup was voided; the player is told which. */
    public enum VoidReason {
        /** The course was deleted. */
        DELETED,
        /** The course's layout changed (an edit, or a new Fresh layout mid-week). */
        CHANGED,
        /** The course was closed. */
        CLOSED
    }

    public CupPlan {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(outcome, "outcome");
        lines = List.copyOf(lines == null ? List.of() : lines);
    }

    /** The tokens this plan pays out: the sum of every line. */
    public int paidOut() {
        long sum = 0;
        for (CupPayout l : lines) {
            sum += l.tokens();
        }
        return Math.toIntExact(sum);
    }

    /** The lines that move tokens, in order. */
    public List<CupPayout> payouts() {
        List<CupPayout> out = new ArrayList<>();
        for (CupPayout l : lines) {
            if (l.pays()) {
                out.add(l);
            }
        }
        return out;
    }

    /** {@code player}'s line, or {@code null} when they weren't in this Cup. */
    public CupPayout lineFor(UUID player) {
        for (CupPayout l : lines) {
            if (l.player().equals(player)) {
                return l;
            }
        }
        return null;
    }

    /**
     * The plan as the settlement row's {@code payouts} JSON: stable key order, one object per line,
     * so the same plan always writes the same text. For example
     * {@code {"outcome":"PRIZES","reason":null,"entries":25,"topup":10,"pool":35,"lines":[{"player":"...",
     * "kind":"PRIZE","tokens":18,"place":1,"tied":1,"ms":41230,"paid":5}]}}.
     */
    public String json() {
        StringBuilder b = new StringBuilder(96 + lines.size() * 120);
        b.append("{\"outcome\":\"").append(outcome.name()).append('"')
                .append(",\"reason\":").append(reason == null ? "null" : "\"" + reason.name() + "\"")
                .append(",\"entries\":").append(entries)
                .append(",\"topup\":").append(topup)
                .append(",\"pool\":").append(pool)
                .append(",\"lines\":[");
        for (int i = 0; i < lines.size(); i++) {
            CupPayout l = lines.get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"player\":\"").append(l.player()).append('"')
                    .append(",\"kind\":\"").append(l.kind().name()).append('"')
                    .append(",\"tokens\":").append(l.tokens())
                    .append(",\"place\":").append(l.place())
                    .append(",\"tied\":").append(l.tied())
                    .append(",\"ms\":").append(l.bestMs() > 0 ? Long.toString(l.bestMs()) : "null")
                    .append(",\"paid\":").append(l.paid())
                    .append('}');
        }
        return b.append("]}").toString();
    }
}
