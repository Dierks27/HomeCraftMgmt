package com.dierks.homecraft.market.sim;

/**
 * What one item looks like to players right now: its badge, its displayed price and how far
 * that is from the usual (balanced) price. Built by {@link MoodEngine#status}; read by the
 * GUIs, displays, placeholders and feeds from the immutable snapshot.
 *
 * @param badge      the badge to show ({@link Badge#NONE} when there is none)
 * @param endsAt     when the badge goes: a HOT/DEAL's {@code ends_at}, an UP/DOWN's badge end
 *                   ({@link MarketEvent#badgeEndsAt()}); 0 for WANTED and NONE
 * @param multiplier {@code M}, already clamped to the configured band inside {@code [0.75, 1.25]};
 *                   exactly 1.0 when the sim is off or the item is {@code sim: false}
 * @param usual      the balanced price {@code B = clamp(currentPrice, F, C)} ("Usually $X")
 * @param price      the displayed mid {@code P = clamp(B * m(S), F, C)}; exactly the ceiling at
 *                   stock 0 whatever {@code M} is
 * @param pct        {@code (P / usual - 1) * 100}, the % a player sees
 * @param event      the event behind the badge (HOT/DEAL/UP/DOWN, or the item's live WANTED row),
 *                   or {@code null}
 * @param phase      that event's phase (FULL or FADING for HOT/DEAL, BADGE for UP/DOWN), or
 *                   {@code null} when there is no event
 */
public record ItemStatus(Badge badge, long endsAt, double multiplier, double usual, double price,
                         double pct, MarketEvent event, Phase phase) {

    public ItemStatus {
        badge = badge == null ? Badge.NONE : badge;
    }

    /** No badge, no mood: the price is the usual price. */
    public static ItemStatus plain(double usual) {
        return new ItemStatus(Badge.NONE, 0L, 1.0, usual, usual, 0.0, null, null);
    }

    /** True while a badge shows. */
    public boolean shown() {
        return badge.shown();
    }

    /** True while a HOT or DEAL is cooling off ("cooling off" / "ending soon"). */
    public boolean fading() {
        return phase == Phase.FADING;
    }
}
