package com.dierks.homecraft.games.chance.mobraces;

/**
 * One entry in a Mob Race, as its round's data (pure): the pick, what went in, what a win pays and, once the race is
 * drawn, the race's seed. Everything needed to finish the round is in it, so the exit rule reads no config and no
 * memory: {@code v=1;pick=3;in=5;pays=27}, then {@code ...;race=<seed>} from the moment the race is drawn.
 *
 * <p><b>The exit rule</b> ({@link #settleOnExit}). A round with no race seed was left while the track was still
 * taking entries: nothing was decided, so what went in comes back whole. A round with a race seed is in a race that
 * was drawn: it pays exactly what that race pays its pick, win or not. Data that can't be read is refused (it
 * throws), and the framework then gives the stake back rather than guess.
 *
 * @param pick  the racer picked (a lane, 0 to 5)
 * @param in    what went on it
 * @param pays  what a win pays
 * @param drawn whether the race has been drawn
 * @param race  the race's seed (only when {@code drawn})
 */
public record RaceTicket(int pick, int in, int pays, boolean drawn, long race) {

    /** The data's version: bump it if the way a race pays ever changes. */
    public static final int VERSION = 1;

    public RaceTicket {
        if (pick < 0 || pick >= Racer.COUNT) {
            throw new IllegalArgumentException("a pick is a racer, 0 to " + (Racer.COUNT - 1) + ": " + pick);
        }
        if (in <= 0 || pays < 0) {
            throw new IllegalArgumentException("an entry puts something in and pays nothing or more: " + in + ", "
                    + pays);
        }
    }

    /** A new entry, before the race is drawn. */
    public static RaceTicket entry(int pick, int in, int pays) {
        return new RaceTicket(pick, in, pays, false, 0L);
    }

    /** This entry in the race drawn from {@code seed}. */
    public RaceTicket in(long seed) {
        return new RaceTicket(pick, in, pays, true, seed);
    }

    /** What this entry pays: nothing is decided before the race is drawn (the stake back); then the pick wins or not. */
    public int payout() {
        if (!drawn) {
            return in;
        }
        return RaceScript.winner(race) == pick ? pays : 0;
    }

    /** Whether the pick won the race (false before it is drawn). */
    public boolean won() {
        return drawn && RaceScript.winner(race) == pick;
    }

    /** The round's data. */
    public String data() {
        return "v=" + VERSION + ";pick=" + pick + ";in=" + in + ";pays=" + pays + (drawn ? ";race=" + race : "");
    }

    /**
     * Read a round's data back.
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
                    case "race" -> race = Long.parseLong(value);
                    default -> throw new IllegalArgumentException("not Mob Races data: " + data);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not Mob Races data: " + data);
            }
        }
        if (version == null || version != VERSION || pick == null || in == null || pays == null) {
            throw new IllegalArgumentException("not Mob Races data: " + data);
        }
        return new RaceTicket(pick, in, pays, race != null, race == null ? 0L : race);
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
