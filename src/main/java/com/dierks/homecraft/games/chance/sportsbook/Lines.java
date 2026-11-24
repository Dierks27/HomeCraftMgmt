package com.dierks.homecraft.games.chance.sportsbook;

/**
 * The Sports Board's numbers (pure, tested): a side's chance from its market's prices, and what a pick on it pays.
 *
 * <p><b>Prices.</b> Kalshi prices a "yes" share in dollars ({@code 0.6200}): what the market says the chance is. Here
 * every price is kept in tenths of a cent ("mills", 620 for 62¢, 625 for a midpoint of 62¢ and 63¢), so a midpoint is
 * exact. A side's chance is the midpoint of the best bid and ask when both are there and no more than
 * {@link #MAX_SPREAD} apart; otherwise the last trade; otherwise it has no sensible price and isn't offered.
 *
 * <p><b>Pays.</b> A pick of S dollars on a side priced p pays {@code S x rtp / p} if it wins, rounded down to the cent:
 * over many picks at honest prices it gives back {@code rtp} of every 100 put in. Never more than the Game Parlor's
 * biggest payout: a side whose pay at a stake would pass it is offered only at smaller stakes.
 *
 * <p><b>Left out.</b> A side priced under {@link #LOW} or over {@link #HIGH} (too lopsided to be interesting, and the
 * rounding gets silly), and a side whose win would give back no more than was put in (a favourite priced at or over
 * {@code rtp}): a "win" that pays back less than the stake is not a win.
 */
public final class Lines {

    /** A whole dollar in mills. */
    public static final int WHOLE = 1000;
    /** The lowest price offered: 5¢. */
    public static final int LOW = 50;
    /** The highest price offered: 95¢. */
    public static final int HIGH = 950;
    /** Bid and ask further apart than this (10¢) are no price: the last trade is used instead. */
    public static final int MAX_SPREAD = 100;
    /** No price. */
    public static final int NONE = -1;

    private Lines() {
    }

    /**
     * A side's price in mills: the bid and ask's midpoint when both are there and close; else the last trade; else
     * {@link #NONE}. Each input is in mills, {@link #NONE} for missing.
     */
    public static int price(int bid, int ask, int last) {
        if (bid > 0 && ask > 0 && ask < WHOLE && ask >= bid && ask - bid <= MAX_SPREAD) {
            return (bid + ask) / 2; // both are whole cents or tenths: the half is exact enough
        }
        if (last > 0 && last < WHOLE) {
            return last;
        }
        return NONE;
    }

    /** The other side of a yes/no market: what "no" is priced at when "yes" is {@code mills}. */
    public static int other(int mills) {
        return mills <= 0 || mills >= WHOLE ? NONE : WHOLE - mills;
    }

    /** Whether a price is inside the band offered (5¢ to 95¢). */
    public static boolean inBand(int mills) {
        return mills >= LOW && mills <= HIGH;
    }

    /**
     * What a winning pick of {@code dollars} on a side priced {@code mills} gives back, in cents, rounded down:
     * {@code dollars x rtp / price}. 0 for no price.
     *
     * @param rtpTenths the return in tenths of a percent (900 for 90)
     */
    public static long payoutCents(int dollars, int mills, int rtpTenths) {
        if (dollars <= 0 || mills <= 0 || rtpTenths <= 0) {
            return 0;
        }
        return (long) dollars * 100L * rtpTenths / mills;
    }

    /**
     * Whether a side priced {@code mills} is offered at all: inside the band, and its win gives back more than was
     * put in (so a favourite priced at or over the return is left out).
     */
    public static boolean offered(int mills, int rtpTenths) {
        return inBand(mills) && payoutCents(1, mills, rtpTenths) > 100;
    }

    /**
     * The biggest stake in whole dollars a side priced {@code mills} takes before its pay passes {@code capDollars}
     * (0 when not even a dollar fits).
     */
    public static int maxStake(int mills, int rtpTenths, int capDollars) {
        if (mills <= 0 || rtpTenths <= 0 || capDollars <= 0) {
            return 0;
        }
        // dollars * 100 * rtp / mills <= cap * 100  <=>  dollars <= cap * mills / rtp
        long most = (long) capDollars * mills / rtpTenths;
        while (most > 0 && payoutCents((int) Math.min(Integer.MAX_VALUE, most), mills, rtpTenths) > capDollars * 100L) {
            most--;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, most));
    }

    /** "about N in 100": the chance in whole hundredths, at least 1 and at most 99. */
    public static int inHundred(int mills) {
        return Math.max(1, Math.min(99, (int) Math.round(mills / 10.0)));
    }

    /**
     * A price written in Kalshi's dollars ({@code "0.6250"}) in mills, or {@link #NONE} when it is missing or isn't a
     * price. Anything finer than a tenth of a cent is rounded to the nearest.
     */
    public static int mills(String dollars) {
        if (dollars == null || dollars.isBlank()) {
            return NONE;
        }
        try {
            double v = Double.parseDouble(dollars.trim());
            if (!Double.isFinite(v) || v < 0 || v > 1) {
                return NONE;
            }
            return (int) Math.round(v * WHOLE);
        } catch (NumberFormatException e) {
            return NONE;
        }
    }

    /** A price in whole cents (Kalshi's older integer fields) in mills, or {@link #NONE}. */
    public static int millsOfCents(long cents) {
        return cents < 0 || cents > 100 ? NONE : (int) cents * 10;
    }
}
