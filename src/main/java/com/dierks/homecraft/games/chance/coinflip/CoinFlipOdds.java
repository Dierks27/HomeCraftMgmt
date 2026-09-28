package com.dierks.homecraft.games.chance.coinflip;

import com.dierks.homecraft.games.RtpLimits;

/**
 * Coin Flip at one stake, as solved (spec §5.7, R1.8): both players put in {@code stake}, one
 * flip picks the winner, and the winner gets {@code pays} of the {@code 2 · stake} put in. The rest
 * is simply gone. Each player has exactly a 1 in 2 chance, so each gets back
 * {@code pays / (2 · stake)} of what they put in, on average — that is the published return.
 *
 * @param stake the tokens each player puts in
 * @param pays  what the winner gets (always more than {@code stake}: inside the band it is at
 *              least 1.7 times it)
 */
public record CoinFlipOdds(int stake, int pays) {

    /** Both players' tokens together. */
    public int pot() {
        return 2 * stake;
    }

    /** The exact return, as a fraction: {@code pays / (2 · stake)}. */
    public RtpLimits.Ratio ratio() {
        return new RtpLimits.Ratio(pays, pot());
    }

    /** The exact return as a double: publish and floor THIS, never the target. */
    public double rtp() {
        return ratio().value();
    }

    /** "The winner gets 18 of the 20 tokens put in". */
    public String winnerGets() {
        return "The winner gets " + pays + " of the " + pot() + " tokens put in";
    }

    /** "Gives back about 90 of every 100 tokens". */
    public String giveBack() {
        String line = RtpLimits.playerLine(rtp());
        return Character.toUpperCase(line.charAt(0)) + line.substring(1);
    }
}
