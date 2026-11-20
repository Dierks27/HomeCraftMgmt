package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.Spin;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * One Deep Mine spin: three rows that all count, decided together from the round's one seed.
 *
 * <p>Each row is a line of its own, drawn exactly as the one-line {@link SlotsEngine} draws a spin (the round's seed
 * gives each row a seed of its own, and the row is that engine's spin for it), so a row's odds are that engine's
 * odds and nothing here models reel strips. The player puts the same on every row, so a spin costs
 * {@link #LINES} times the stake on a line, and what it gives back is the three rows added up.
 *
 * <p><b>The return.</b> Three rows at the same stake each give back the line's exact return, so the whole spin
 * gives back exactly that too. <b>The cap.</b> The line engine is solved with a cap of a third of what the whole
 * spin may pay ({@link #lineCap}), so three rows can never add up to more than it: by construction, never by
 * clamping the sum.
 */
public final class DeepMineEngine implements ChanceRounds.InstantEngine<DeepMineEngine.Dig> {

    /** The rows on the screen. Every one of them is a line, and every spin plays them all. */
    public static final int LINES = 3;
    /** The engine version stored with every round. */
    public static final int VERSION = 1;

    private static final String[] ROW_NAMES = {"Top", "Middle", "Bottom"};

    private final SlotsEngine line;
    private final int perLine;

    /**
     * @param line    the one-line engine every row is drawn by, solved for {@code perLine} under {@link #lineCap}
     * @param perLine what goes on each row
     * @throws IllegalArgumentException when the engine does not take that stake
     */
    public DeepMineEngine(SlotsEngine line, int perLine) {
        if (line == null || line.odds(perLine) == null) {
            throw new IllegalArgumentException("Deep Mine does not take " + perLine + " a line");
        }
        this.line = line;
        this.perLine = perLine;
    }

    /** The most one row may pay when the whole spin may pay {@code roundCap}: a third of it, rounded down. */
    public static int lineCap(int roundCap) {
        return roundCap / LINES;
    }

    /** What a spin with {@code perLine} on each row costs. */
    public static int total(int perLine) {
        return perLine * LINES;
    }

    /** "Top", "Middle" or "Bottom". */
    public static String rowName(int row) {
        return ROW_NAMES[row];
    }

    /** The one-line engine every row is drawn by. */
    public SlotsEngine line() {
        return line;
    }

    /** What goes on each row. */
    public int perLine() {
        return perLine;
    }

    /** What a spin costs: three rows. */
    public int total() {
        return total(perLine);
    }

    /** The most a whole spin can pay: three rows of the line's most. */
    public int most() {
        int top = 0;
        for (SlotsEngine.LineOdds l : odds().lines()) {
            top = Math.max(top, l.payout());
        }
        return top * LINES;
    }

    /** One row's solved odds. */
    public StakeOdds odds() {
        return line.odds(perLine);
    }

    /** What a whole spin gives back per token in, exactly: the line's own return. */
    public Frac givesBack() {
        return SlotOdds.line(line, perLine);
    }

    /** The exact chance at least one of the three rows pays: {@code 1 - (1 - p)³}. */
    public Frac anyRowPays() {
        return SlotOdds.atLeastOnce(SlotOdds.pays(line, perLine), LINES);
    }

    /**
     * The spin for this seed: the seed gives each row its own, top row first, and each row is the line engine's spin
     * for its seed. Same seed, same spin.
     *
     * @param stake what the whole spin costs: three times what goes on a row
     * @throws IllegalArgumentException for any other amount
     */
    @Override
    public Dig decide(long seed, int stake) {
        if (stake != total()) {
            throw new IllegalArgumentException("A Deep Mine spin with " + perLine + " a line costs " + total()
                    + ", not " + stake);
        }
        SplittableRandom rows = new SplittableRandom(seed);
        List<Spin> out = new ArrayList<>(LINES);
        int payout = 0;
        for (int r = 0; r < LINES; r++) {
            Spin row = line.decide(rows.nextLong(), perLine);
            out.add(row);
            payout += row.payout();
        }
        return new Dig(perLine, out, payout);
    }

    @Override
    public int payout(Dig dig) {
        return dig.payout();
    }

    /**
     * What the round stores: {@code v=1;lines=3;stake=2;in=6;row1=coal,wild,stone:two:2;row2=...;row3=...;payout=2;
     * stone=656;total=4056}. The symbols are the engine's own keys, whatever the machine calls them.
     */
    @Override
    public String data(Dig dig) {
        StringBuilder sb = new StringBuilder("v=").append(VERSION).append(";lines=").append(LINES)
                .append(";stake=").append(dig.perLine()).append(";in=").append(dig.stake());
        for (int r = 0; r < dig.rows().size(); r++) {
            sb.append(";row").append(r + 1).append('=').append(row(dig.rows().get(r)));
        }
        sb.append(";payout=").append(dig.payout());
        StakeOdds o = line.odds(dig.perLine());
        if (o != null) {
            sb.append(";stone=").append(o.stone()).append(";total=").append(o.total());
        }
        return sb.toString();
    }

    /** One spin as text: {@code coal,wild,stone:two:4} (its reels, its line or {@code none}, what it paid). */
    static String row(Spin spin) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < spin.reels().size(); i++) {
            sb.append(i == 0 ? "" : ",").append(spin.reels().get(i).key());
        }
        return sb.append(':').append(spin.line() == null ? "none" : spin.line().key()).append(':')
                .append(spin.payout()).toString();
    }

    /** {@link #row(Spin)} read back. */
    static Spin row(String text, int stake) {
        String[] parts = text.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("not a row: " + text);
        }
        List<Symbol> reels = new ArrayList<>(3);
        for (String key : parts[0].split(",")) {
            reels.add(symbol(key));
        }
        if (reels.size() != 3) {
            throw new IllegalArgumentException("not three reels: " + text);
        }
        return new Spin(stake, reels, line(parts[1]), Integer.parseInt(parts[2]));
    }

    private static Symbol symbol(String key) {
        for (Symbol s : Symbol.values()) {
            if (s.key().equals(key)) {
                return s;
            }
        }
        throw new IllegalArgumentException("not a symbol: " + key);
    }

    private static Line line(String key) {
        if (key.equals("none")) {
            return null;
        }
        for (Line l : Line.values()) {
            if (l.key().equals(key)) {
                return l;
            }
        }
        throw new IllegalArgumentException("not a line: " + key);
    }

    /** {@code key=value;key=value} as a lookup. */
    static java.util.Map<String, String> fields(String data) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String part : data.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                out.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        return out;
    }

    /**
     * A stored round read back: the spin exactly as it was decided (to show it again, or to check it).
     *
     * @throws IllegalArgumentException when {@code data} is not a Deep Mine round
     */
    public static Dig parse(String data) {
        java.util.Map<String, String> f = fields(data);
        try {
            int perLine = Integer.parseInt(f.get("stake"));
            int lines = Integer.parseInt(f.get("lines"));
            List<Spin> rows = new ArrayList<>(lines);
            for (int r = 1; r <= lines; r++) {
                rows.add(row(f.get("row" + r), perLine));
            }
            return new Dig(perLine, rows, Integer.parseInt(f.get("payout")));
        } catch (NullPointerException | NumberFormatException e) {
            throw new IllegalArgumentException("not a Deep Mine round: " + data, e);
        }
    }

    /**
     * One spin of the three rows.
     *
     * @param perLine what went on each row
     * @param rows    the rows, top first, each a line of its own
     * @param payout  what the whole spin gives back: the rows added up
     */
    public record Dig(int perLine, List<Spin> rows, int payout) {

        public Dig {
            rows = List.copyOf(rows);
        }

        /** What the whole spin cost: every row's stake. */
        public int stake() {
            return perLine * rows.size();
        }

        /** More came back than the whole spin cost: the only result ever called a win. */
        public boolean win() {
            return payout > stake();
        }

        /** How many rows paid anything. */
        public int paidRows() {
            int n = 0;
            for (Spin row : rows) {
                n += row.payout() > 0 ? 1 : 0;
            }
            return n;
        }
    }
}
