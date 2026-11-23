package com.dierks.homecraft.games.chance.mobraces;

import java.util.Arrays;

/**
 * One entry in a Mob Race, as its round's data (pure): the pick, what went in, what a win pays, the race's chances
 * and, once the race is drawn, the race's seed. Everything needed to finish the round is in it, so the exit rule reads
 * no config and no memory: {@code v=2;pick=3;in=5;pays=23;card=2104,1180,2933,612,2571,1600}, then
 * {@code ...;race=<seed>} from the moment the race is drawn.
 *
 * <p><b>The chances</b> ({@code card}) are the race card's ({@link RaceCard}): each racer's chance in parts of
 * {@value RaceCard#WHOLE}, by lane, adding up to it exactly. The winner of the race drawn from a seed is
 * {@link RaceCard#winner(long, int[])} of these, so a round is settled from its own data exactly as the race was.
 *
 * <p><b>Old entries</b> ({@code v=1}, from before races had cards: no chances written): every racer was as likely as
 * any other, and a win paid the one amount written in. One still open after the upgrade is finished by that old rule: the winner is
 * the seed's {@code nextInt(6)} ({@link RaceScript#evenWinner(long)}), and the pay is its own.
 *
 * <p><b>The exit rule</b> ({@link #settleOnExit}). A round with no race seed was left while the track was still
 * taking entries: nothing was decided, so what went in comes back whole. A round with a race seed is in a race that
 * was drawn: it pays exactly what that race pays its pick, win or not. Data that can't be read is refused (it
 * throws), and the framework then gives the stake back rather than guess.
 *
 * @param pick  the racer picked (a lane, 0 to 5)
 * @param in    what went on it
 * @param pays  what a win pays
 * @param card  the race's chances by lane, in parts of {@value RaceCard#WHOLE}; {@code null} for an old entry (every
 *              racer as likely)
 * @param drawn whether the race has been drawn
 * @param race  the race's seed (only when {@code drawn})
 */
public record RaceTicket(int pick, int in, int pays, int[] card, boolean drawn, long race) {

    /** The data's version: bump it if the way a race pays ever changes. 2: the race's chances are in it. */
    public static final int VERSION = 2;
    /** The old version: every racer as likely, no chances written. Read and settled, never written for a new entry. */
    public static final int EVEN = 1;

    public RaceTicket {
        if (pick < 0 || pick >= Racer.COUNT) {
            throw new IllegalArgumentException("a pick is a racer, 0 to " + (Racer.COUNT - 1) + ": " + pick);
        }
        if (in <= 0 || pays < 0) {
            throw new IllegalArgumentException("an entry puts something in and pays nothing or more: " + in + ", "
                    + pays);
        }
        if (card != null) {
            if (!RaceCard.valid(card)) {
                throw new IllegalArgumentException("a race's chances add up to " + RaceCard.WHOLE + ": "
                        + Arrays.toString(card));
            }
            card = card.clone();
        }
    }

    /** A new entry on the race whose chances are {@code card}, before the race is drawn. */
    public static RaceTicket entry(int pick, int in, int pays, int[] card) {
        if (card == null) {
            throw new IllegalArgumentException("a new entry carries its race's chances");
        }
        return new RaceTicket(pick, in, pays, card, false, 0L);
    }

    /** An old entry ({@code v=1}): every racer as likely. Only what an older version wrote reads back as this. */
    static RaceTicket even(int pick, int in, int pays) {
        return new RaceTicket(pick, in, pays, null, false, 0L);
    }

    /** This entry in the race drawn from {@code seed}. */
    public RaceTicket in(long seed) {
        return new RaceTicket(pick, in, pays, card, true, seed);
    }

    /** The race's chances (a copy), or {@code null} for an old entry. */
    @Override
    public int[] card() {
        return card == null ? null : card.clone();
    }

    /** Who won the race this entry is in (a lane), or -1 before it is drawn. */
    public int winner() {
        if (!drawn) {
            return -1;
        }
        return card == null ? RaceScript.evenWinner(race) : RaceCard.winner(race, card);
    }

    /** What this entry pays: nothing is decided before the race is drawn (the stake back); then the pick wins or not. */
    public int payout() {
        if (!drawn) {
            return in;
        }
        return winner() == pick ? pays : 0;
    }

    /** Whether the pick won the race (false before it is drawn). */
    public boolean won() {
        return drawn && winner() == pick;
    }

    /** The round's data. */
    public String data() {
        StringBuilder b = new StringBuilder("v=").append(card == null ? EVEN : VERSION).append(";pick=").append(pick)
                .append(";in=").append(in).append(";pays=").append(pays);
        if (card != null) {
            b.append(";card=");
            for (int i = 0; i < card.length; i++) {
                b.append(i == 0 ? "" : ",").append(card[i]);
            }
        }
        if (drawn) {
            b.append(";race=").append(race);
        }
        return b.toString();
    }

    /**
     * Read a round's data back: this version's, or an old one's.
     *
     * @throws IllegalArgumentException when it isn't an entry's data (the framework then gives the stake back)
     */
    public static RaceTicket read(String data) {
        if (data == null || data.isBlank()) {
            throw new IllegalArgumentException("no Mob Races data");
        }
        Integer version = null;
        Integer pick = null;
        Integer in = null;
        Integer pays = null;
        int[] card = null;
        Long race = null;
        for (String part : data.split(";", -1)) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("not Mob Races data: " + data);
            }
            String key = part.substring(0, eq);
            String value = part.substring(eq + 1);
            try {
                switch (key) {
                    case "v" -> version = Integer.parseInt(value);
                    case "pick" -> pick = Integer.parseInt(value);
                    case "in" -> in = Integer.parseInt(value);
                    case "pays" -> pays = Integer.parseInt(value);
                    case "card" -> {
                        String[] each = value.split(",", -1);
                        card = new int[each.length];
                        for (int i = 0; i < each.length; i++) {
                            card[i] = Integer.parseInt(each[i]);
                        }
                    }
                    case "race" -> race = Long.parseLong(value);
                    default -> throw new IllegalArgumentException("not Mob Races data: " + data);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not Mob Races data: " + data);
            }
        }
        if (version == null || pick == null || in == null || pays == null) {
            throw new IllegalArgumentException("not Mob Races data: " + data);
        }
        boolean known = version == VERSION ? card != null : version == EVEN && card == null;
        if (!known) {
            throw new IllegalArgumentException("not Mob Races data: " + data);
        }
        return new RaceTicket(pick, in, pays, card, race != null, race == null ? 0L : race);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RaceTicket t && t.pick == pick && t.in == in && t.pays == pays && t.drawn == drawn
                && t.race == race && Arrays.equals(t.card, card);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(card) + Long.hashCode(race) + 7 * pick + 11 * in + 13 * pays;
    }

    @Override
    public String toString() {
        return data();
    }

    /**
     * The exit rule: what an OPEN round pays when it is finished from its data alone.
     *
     * @param seed  the round's own seed (not used: the race's seed is in the data, one for the whole race)
     * @param stake what the round holds
     * @param data  the round's data
     * @throws IllegalArgumentException for data that isn't an entry's, or that disagrees with what the round holds
     */
    public static int settleOnExit(long seed, int stake, String data) {
        RaceTicket t = read(data);
        if (t.in() != stake) {
            throw new IllegalArgumentException("the round holds " + stake + ", its data says " + t.in());
        }
        return t.payout();
    }
}
