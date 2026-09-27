package com.dierks.homecraft.market.sim;

/**
 * Where an event is at a moment in time. Always derived from the row and {@code now}
 * ({@link MarketEvent#phase(long)}), never stored.
 *
 * <ul>
 *   <li>HOT/DEAL: {@link #RAMP} (silent, no badge, limits already active) &rarr;
 *       {@link #FULL} (badge; the announcement is due) &rarr; {@link #FADING} (badge plus
 *       "cooling off" / "ending soon") &rarr; {@link #OVER}. A stop after the ramp fades for
 *       1 h as {@link #FADING}; a stop during the ramp stays {@link #RAMP} (silent) until it
 *       is over, because it was never announced.</li>
 *   <li>UP/DOWN: {@link #BADGE} while the contribution is at least 5% &rarr; {@link #TAIL}
 *       (no badge, limits still active) &rarr; {@link #OVER}.</li>
 *   <li>WANTED, SEASON, REAL: {@link #FIRED} from the start until {@code ends_at}, then
 *       {@link #OVER}.</li>
 *   <li>Any kind before {@code started_at}: {@link #PENDING}.</li>
 * </ul>
 */
public enum Phase {
    PENDING, RAMP, FULL, FADING, BADGE, TAIL, FIRED, OVER;

    /** Started and not over yet. */
    public boolean live() {
        return this != PENDING && this != OVER;
    }
}
