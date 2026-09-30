package com.dierks.homecraft.games.cup;

/**
 * The three ledger sources of the Weekly Cup (§D2 rules). The names are exactly the
 * {@code TokenService.Source} constants the contracts add, so the wiring maps one to the other with
 * {@code Source.valueOf(source.name())} and this package stays free of the token service.
 */
public enum CupSource {
    /** An entry: a plain token spend, one per player per course per week. */
    GAMES_CUP_ENTRY,
    /** A share of the pool, paid at the week's rollover. */
    GAMES_CUP_PRIZE,
    /** An entry given back: a lone entrant, too few Cup times, or a voided course. */
    GAMES_CUP_REFUND
}
