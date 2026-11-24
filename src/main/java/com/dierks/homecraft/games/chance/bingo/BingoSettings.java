package com.dierks.homecraft.games.chance.bingo;

import com.dierks.homecraft.config.GamesConfig;

import java.util.List;

/**
 * Bingo Night's settings (0.57.0): {@code games.bingo}.
 *
 * <p>What a round pays is not set here: it is worked out from the cards in play ({@link BingoPrize}), aiming at
 * {@link #rtp} inside the 85-95 band locked in code. What is set is what a card costs (in tokens, and in dollars for
 * the Game Parlor's money play), how the prize is split between the line and four corners, how many cards a round
 * always has ({@code room_cards}: the room's own and the NPCs' fill it up to that) and may have ({@code max_cards}),
 * and the clocks. A price under {@value BingoPrize#MIN_PRICE} is raised to it (one WARN): under it a small round may
 * have no whole prize inside the band. {@code max_cards} is lowered (one WARN) until a lone winner's prize fits
 * {@code games.max_payout}.
 *
 * @param enabled       the game's own switch
 * @param cardPrice     what one card costs in tokens
 * @param cardDollars   what one card costs in dollars (the Game Parlor's money play)
 * @param rtp           the target return, a percent (85-95)
 * @param cornersShare  four corners' part of a round's prize, a percent (0 = the line only)
 * @param roomCards     the fewest cards a round is played with (the room's own and the NPCs' fill it up)
 * @param maxCards      the most cards in a round
 * @param dailyLimit    rounds a player may play a day
 * @param gatherSeconds how long cards are sold after the first is bought
 * @param callSeconds   seconds between calls
 */
public record BingoSettings(boolean enabled, int cardPrice, int cardDollars, double rtp, int cornersShare,
                            int roomCards, int maxCards, int dailyLimit, int gatherSeconds, int callSeconds) {

    public static final String ID = "bingo";
    /** The leaves under {@code games.bingo}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "card_price", "card_dollars", "rtp", "corners_share",
            "room_cards", "max_cards", "daily_limit", "gather_seconds", "call_seconds");

    public static final int SHARE_MAX = 80;
    public static final int CARDS_MAX = 40;
    public static final int GATHER_MIN = 5;
    public static final int GATHER_MAX = 120;
    public static final int CALL_MIN = 1;
    public static final int CALL_MAX = 10;

    /**
     * The shipped settings: 10 tokens or $10 a card, 90 of every 100 back, 60 of every 100 of the prize to four
     * corners, at least 8 cards and at most 24 in a round, 30 rounds a day, a 20-second wait and a call every 2 s.
     */
    public static BingoSettings defaults() {
        return new BingoSettings(true, 10, 10, 90, 60, 8, 24, 30, 20, 2);
    }

    /** Read {@code games.bingo} over {@code d}; never throws. */
    public static BingoSettings parse(GamesConfig.Node n, BingoSettings d) {
        boolean enabled = n.enabled(d.enabled());
        int price = n.whole("card_price", d.cardPrice(), BingoPrize.MIN_PRICE, BingoPrize.MAX_PRICE);
        int dollars = n.whole("card_dollars", d.cardDollars(), BingoPrize.MIN_PRICE, BingoPrize.MAX_PRICE);
        double rtp = n.rtp("rtp", d.rtp());
        int share = n.whole("corners_share", d.cornersShare(), 0, SHARE_MAX);
        int room = n.whole("room_cards", d.roomCards(), 1, CARDS_MAX);
        int most = n.whole("max_cards", d.maxCards(), BingoTicket.MAX_CARDS, CARDS_MAX);
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        int gather = n.whole("gather_seconds", d.gatherSeconds(), GATHER_MIN, GATHER_MAX);
        int call = n.whole("call_seconds", d.callSeconds(), CALL_MIN, CALL_MAX);
        GamesConfig.Common common = n.common() == null ? GamesConfig.Common.defaults() : n.common();
        int cap = common.maxPayoutFor(List.of(price * BingoTicket.MAX_CARDS));
        int fits = BingoPrize.mostCards(price, rtp, cap, most);
        if (fits < most) {
            n.warn(n.key("max_cards") + " " + most + " is lowered to " + fits + ": a lone winner of a round of more"
                    + " cards would be paid over max_payout " + cap);
            most = Math.max(1, fits);
        }
        if (room > most) {
            n.warn(n.key("room_cards") + " " + room + " is more than max_cards - using " + most);
            room = most;
        }
        return new BingoSettings(enabled, price, dollars, rtp, share, room, most, dailyLimit, gather, call);
    }

    /** Seconds between calls, in milliseconds. */
    public long callMs() {
        return callSeconds * 1000L;
    }

    /** The price of a card: in dollars for money play, else in tokens. */
    public int price(boolean money) {
        return money ? cardDollars : cardPrice;
    }
}
