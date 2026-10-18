package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.boat.BoatEnvelope;
import com.dierks.homecraft.games.gen.boat.BoatStyle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Ice Boat flight recorder (MOUNTAIN-V21-SPEC D17): how far boats really fly off a Mountain Run's drops,
 * measured on the rides people make anyway. It measures and nothing else: the ride, the proof and the planner
 * never read it, and it is not an ALGO matter.
 *
 * <p><b>Why.</b> The proof's flight zones come from BoatEnvelope's F3: any boat may leave any lip at the 75 b/s
 * fair-play cap with no air drag, plus a 30% Bedrock margin, so L(1) = 38 and L(2) = 49. Java's own physics
 * (packed ice tops out at 40 b/s, the air takes a tenth of the speed a tick) says a real 2-block flight is
 * about 10-13 blocks. Shrinking the zones needs evidence, and this is it: take-off speed, flight length and
 * the edition, per drop, for 0.38 to decide on.
 *
 * <p><b>What a flight is.</b> {@link Run#move} sees each move of the boat ({@code VehicleMoveEvent}'s from
 * and to) and the server tick:
 * <ul>
 *   <li>the boat is <i>settled</i> when two consecutive moves end within {@value #SETTLE} of each other in
 *       height: that height is its ground level, and the last move that ended at it (within
 *       {@value #SETTLE}) is where a flight would take off;</li>
 *   <li>a flight <i>starts</i> at that last tick at ground level y0 once the boat is more than
 *       {@value #FALL} under it;</li>
 *   <li>it <i>ends</i> where the boat settles again at y1 ≤ y0 − {@value #LANDED}: d = round(y0 − y1), and
 *       only d of 1 or 2 is kept (a fall off the track is no drop). Its length is the distance across the
 *       ground from the take-off to the first settled point (the move that first reached y1);</li>
 *   <li>the <i>take-off speed</i> is the distance across the ground a tick over the last ≤
 *       {@value #SPEED_TICKS} ticks before the take-off, in blocks a second (a tick being the server's, or
 *       one move when the network hands over two in one);</li>
 *   <li>a teleport, a reset or a dismount ({@link Run#drop}, or a move that doesn't start where the last
 *       one ended) forgets the open flight.</li>
 * </ul>
 *
 * <p><b>What a run keeps</b> ({@link Summary}), for each d: the count, the longest flight with its take-off
 * speed, the fastest take-off and the p90 flight length; and the edition (Java or Bedrock), the course's
 * style and tier, and whether the run finished. {@link Summary#line} is the console line written as the run
 * ends. The recorder keeps the last {@value #KEEP} summaries and the longest flight per (edition, d) since
 * the restart; the all-time longest per (edition, d) is kept in {@code hcm_meta} ({@link Store}, under
 * {@value #KEY}) when there is one.
 *
 * <p><b>Cost.</b> A few doubles of arithmetic a move, into a small ring of the last moves: one {@link Run}
 * per recorded run and nothing allocated per move. Pure (no Bukkit), main thread only: the event handlers
 * feed it.
 */
public final class FlightRecorder {

    /** A flight starts once the boat is more than this under the ground level it was at (blocks). */
    public static final double FALL = 0.3;
    /** Two consecutive moves ending within this of each other in height: the boat is settled (blocks). */
    public static final double SETTLE = 0.01;
    /** A flight ends where the boat settles at least this far under where it took off (blocks). */
    public static final double LANDED = 0.5;
    /** The take-off speed is measured over at most this many ticks before the take-off. */
    public static final int SPEED_TICKS = 3;
    /** A move starting farther than this from where the last one ended is a teleport, not a flight (blocks). */
    static final double GAP = 1.0;
    /** A move longer than this across the ground (twice the 75 b/s cap a tick and more) is a teleport (blocks). */
    static final double MAX_STEP = 8.0;
    /** How many run summaries are kept in memory. */
    public static final int KEEP = 50;
    /** How many of them {@link #lines} shows. */
    public static final int SHOW = 10;
    /** The {@code hcm_meta} keys of the all-time longest flights: {@code gen.boat.flights.<edition>.<d>}. */
    public static final String KEY = "gen.boat.flights.";
    /** The editions, in the order the command lists them. */
    static final String JAVA = "Java";
    static final String BEDROCK = "Bedrock";

    /** Ticks a second. */
    private static final int TICKS = 20;
    /** The moves the take-off speed may look back over (more than SPEED_TICKS: two moves can share a tick). */
    private static final int RING = SPEED_TICKS * 2;

    /**
     * Where the all-time longest flights are kept between restarts ({@code hcm_meta} through
     * {@code GenMetaDao}). A store that can't read or write answers {@code null} and drops the write: the
     * memory and the console lines are enough without it.
     */
    public interface Store {

        /** The value of {@code key}, or {@code null} (unset, or it couldn't be read). */
        String get(String key);

        /** Set {@code key} to {@code value}. */
        void set(String key, String value);
    }

    /** The longest flight of a kind: its length and take-off speed (blocks, blocks a second; NaN unknown). */
    record Best(double length, double speed) {

        /** {@code "12.43 39.81"}, as the store keeps it. */
        String text() {
            return String.format(Locale.ROOT, "%.2f %.2f", length, speed);
        }

        /** The stored {@link #text}, or {@code null} for anything else. */
        static Best parse(String text) {
            if (text == null) {
                return null;
            }
            String[] parts = text.trim().split(" ");
            if (parts.length != 2) {
                return null;
            }
            try {
                double length = Double.parseDouble(parts[0]);
                return Double.isFinite(length) && length > 0 ? new Best(length, Double.parseDouble(parts[1])) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private final Store store;
    private final ArrayDeque<Summary> recent = new ArrayDeque<>();
    /** The longest flight per (edition, d) since the restart, by {@link #key}. */
    private final Map<String, Best> longest = new LinkedHashMap<>();
    /** The fastest take-off per (edition, d) since the restart, by {@link #key}. */
    private final Map<String, Double> fastest = new LinkedHashMap<>();
    /** The all-time longest per (edition, d), read from the store on first use; {@code null} until then. */
    private Map<String, Best> allTime;

    /** A recorder keeping its all-time longest flights in {@code store} ({@code null}: memory only). */
    public FlightRecorder(Store store) {
        this.store = store;
    }

    /** A recorder with no store: memory and the console lines only. */
    public FlightRecorder() {
        this(null);
    }

    /**
     * Whether runs on {@code c} are recorded: a Mountain Run of boat planner algo 4 or later
     * ({@link BoatHype#mountainV2}), the live course or an admin's preview of one. Nothing else is.
     */
    public static boolean records(Course c) {
        return BoatHype.mountainV2(c);
    }

    /** A new run's record on {@code c} (which {@link #records}), by a rider on Bedrock or not. */
    public Run start(boolean bedrock, Course c) {
        BoatStyle style = BoatHype.style(c);
        return new Run(bedrock, style == null ? "boat" : style.id(), c == null ? "" : c.tier().id());
    }

    /** A new run's record with its edition, style ({@code road}) and tier ({@code medium}) given. */
    public Run start(boolean bedrock, String style, String tier) {
        return new Run(bedrock, style, tier);
    }

    /**
     * The run ended ({@code finished}: it reached the finish): its summary is kept (the last {@value #KEEP}),
     * the longest flights updated, and a new all-time longest stored.
     *
     * @return the summary, whose {@link Summary#line} is the console line
     */
    public Summary close(Run run, boolean finished) {
        run.drop();
        Summary s = new Summary(run.edition(), run.style, run.tier, finished, run.one.stat(), run.two.stat());
        recent.addLast(s);
        while (recent.size() > KEEP) {
            recent.removeFirst();
        }
        note(s.edition(), 1, s.one());
        note(s.edition(), 2, s.two());
        return s;
    }

    private void note(String edition, int d, Stat st) {
        if (st.count() == 0) {
            return;
        }
        String k = key(edition, d);
        Best b = longest.get(k);
        if (b == null || st.longest() > b.length()) {
            longest.put(k, new Best(st.longest(), st.longestSpeed()));
        }
        if (!Double.isNaN(st.fastest()) && !(fastest.getOrDefault(k, Double.NaN) >= st.fastest())) {
            fastest.put(k, st.fastest());
        }
        Map<String, Best> all = allTime();
        Best was = all.get(k);
        if (was == null || st.longest() > was.length()) {
            Best now = new Best(st.longest(), st.longestSpeed());
            all.put(k, now);
            if (store != null) {
                store.set(KEY + k, now.text());
            }
        }
    }

    /** The all-time longest per (edition, d), read once from the store. */
    private Map<String, Best> allTime() {
        if (allTime == null) {
            allTime = new LinkedHashMap<>();
            if (store != null) {
                for (String edition : List.of(JAVA, BEDROCK)) {
                    for (int d = 2; d >= 1; d--) {
                        Best b = Best.parse(store.get(KEY + key(edition, d)));
                        if (b != null) {
                            allTime.put(key(edition, d), b);
                        }
                    }
                }
            }
        }
        return allTime;
    }

    /** {@code java.2}: the key of (edition, d), in the maps and under {@value #KEY}. */
    static String key(String edition, int d) {
        return edition.toLowerCase(Locale.ROOT) + "." + d;
    }

    /** The kept summaries, oldest first (at most {@value #KEEP}). */
    public List<Summary> recent() {
        return List.copyOf(recent);
    }

    /**
     * What {@code /hcm games boat flights} prints: the last {@value #SHOW} run lines, then the longest flight
     * per (edition, d) since the restart next to the proof's L(d), and the all-time longest; or, with nothing
     * recorded, a plain line saying so.
     */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        out.add("&6Ice Boat flights &7- the drops measured on Mountain Run rides");
        Map<String, Best> all = allTime();
        if (recent.isEmpty()) {
            out.add("&7Nothing recorded since the restart yet. Ride an Ice Boat Mountain Run (a test run of a preview"
                    + " counts), then run this again: every drop you ride is measured.");
        } else {
            List<Summary> list = new ArrayList<>(recent);
            int from = Math.max(0, list.size() - SHOW);
            out.add("&7The last " + (list.size() - from) + " run" + (list.size() - from == 1 ? "" : "s")
                    + ", newest last:");
            for (Summary s : list.subList(from, list.size())) {
                out.add("&f" + s.line());
            }
        }
        boolean any = false;
        for (String edition : List.of(JAVA, BEDROCK)) {
            for (int d = 2; d >= 1; d--) {
                String k = key(edition, d);
                Best now = longest.get(k);
                Best ever = all.get(k);
                if (now == null && ever == null) {
                    continue;
                }
                any = true;
                String head = "&7Longest " + d + "-block jump (" + edition + "): ";
                String since = now == null ? "none since the restart" : "&f" + tenth(now.length()) + " blocks &7at "
                        + tenth(now.speed()) + " b/s" + (fastest.containsKey(k) ? ", fastest take-off "
                        + tenth(fastest.get(k)) + " b/s" : "");
                // the all-time longest when it is longer than this restart's (else this restart's is it)
                String allTimeText = ever == null || (now != null && ever.length() <= now.length()) ? ""
                        : "; all time " + tenth(ever.length()) + " blocks";
                out.add(head + since + allTimeText + " &8(the safety rule allows " + BoatEnvelope.flight(d) + ")");
            }
        }
        if (!any && !recent.isEmpty()) {
            out.add("&7No 1- or 2-block jumps in those runs yet.");
        }
        return out;
    }

    /** {@code 12.4}; {@code ?} when unknown. */
    static String tenth(double v) {
        return Double.isNaN(v) ? "?" : String.format(Locale.ROOT, "%.1f", v);
    }

    // ---- one run --------------------------------------------------------------------------------

    /**
     * One run's record: what it is on, the ground it was last on, the open flight and the flights so far.
     * {@link #move} does a few doubles of arithmetic and allocates nothing.
     */
    public static final class Run {

        final boolean bedrock;
        final String style;
        final String tier;
        final Flights one = new Flights();
        final Flights two = new Flights();

        // the last moves' ends across the ground and their ticks, for the take-off speed (a ring)
        private final double[] rx = new double[RING];
        private final double[] rz = new double[RING];
        private final long[] rt = new long[RING];
        private int ringSize;
        private int ringNext;
        // where the last move ended
        private boolean has;
        private double lastX;
        private double lastY;
        private double lastZ;
        /** The ground level the boat last settled at; NaN until it has. */
        private double level = Double.NaN;
        // the last move that ended at that level: the take-off, if the next fall is a flight
        private boolean at;
        private double atX;
        private double atZ;
        private double atSpeed;
        // the open flight
        private boolean flying;
        private double y0;
        private double sx;
        private double sz;
        private double sSpeed;

        Run(boolean bedrock, String style, String tier) {
            this.bedrock = bedrock;
            this.style = style == null ? "" : style;
            this.tier = tier == null ? "" : tier;
        }

        /** "Bedrock" or "Java". */
        public String edition() {
            return bedrock ? BEDROCK : JAVA;
        }

        /** The boat moved from (fx, fy, fz) to (tx, ty, tz) at server tick {@code tick}. */
        public void move(double fx, double fy, double fz, double tx, double ty, double tz, long tick) {
            if (has && (Math.abs(fx - lastX) > GAP || Math.abs(fy - lastY) > GAP || Math.abs(fz - lastZ) > GAP
                    || across(fx, fz, tx, tz) > MAX_STEP)) {
                drop(); // put somewhere else: nothing before it belongs to what comes next
            }
            double px = has ? lastX : fx;
            double py = has ? lastY : fy;
            double pz = has ? lastZ : fz;
            has = true;
            lastX = tx;
            lastY = ty;
            lastZ = tz;
            rx[ringNext] = tx;
            rz[ringNext] = tz;
            rt[ringNext] = tick;
            ringNext = (ringNext + 1) % RING;
            ringSize = Math.min(RING, ringSize + 1);
            boolean settled = Math.abs(ty - py) <= SETTLE;
            if (flying) {
                if (settled) {
                    landed(px, pz, ty);
                    level = ty;
                    takeOff(tx, tz, tick);
                }
                return;
            }
            if (settled) {
                level = ty;
            }
            if (Double.isNaN(level)) {
                return;
            }
            if (Math.abs(ty - level) <= SETTLE) {
                takeOff(tx, tz, tick);
            } else if (ty < level - FALL && at) {
                flying = true;
                y0 = level;
                sx = atX;
                sz = atZ;
                sSpeed = atSpeed;
            }
        }

        /** The boat settled at {@code y1}, having first reached it at (x, z): the flight ends there. */
        private void landed(double x, double z, double y1) {
            flying = false;
            if (y1 > y0 - LANDED) {
                return;
            }
            long d = Math.round(y0 - y1);
            if (d == 1 || d == 2) {
                (d == 1 ? one : two).add(across(sx, sz, x, z), sSpeed);
            }
        }

        /** The move just ended at ground level at (x, z): a flight would take off here, at this speed. */
        private void takeOff(double x, double z, long tick) {
            at = true;
            atX = x;
            atZ = z;
            atSpeed = speedBefore(tick);
        }

        /**
         * Blocks a second across the ground over the last ≤ {@value #SPEED_TICKS} ticks up to {@code tick}: from
         * the oldest move in the ring at most that many ticks earlier (and at least one) to the newest, over the
         * ticks between them or the moves, whichever is more. A client sends one move a tick of its own, so two
         * moves the network handed over in one server tick are two ticks of riding, not one. NaN when there is
         * no such move.
         */
        private double speedBefore(long tick) {
            int newest = (ringNext - 1 + RING) % RING;
            int best = -1;
            int moves = 0;
            for (int i = 1; i < ringSize; i++) {
                int j = (newest - i + RING) % RING;
                long ago = tick - rt[j];
                if (ago > SPEED_TICKS) {
                    break;
                }
                if (ago >= 1) {
                    best = j;
                    moves = i;
                }
            }
            if (best < 0) {
                return Double.NaN;
            }
            return across(rx[best], rz[best], rx[newest], rz[newest]) / Math.max(tick - rt[best], moves) * TICKS;
        }

        /** A teleport, a reset or a dismount: the open flight is forgotten, and so is the ground it left. */
        public void drop() {
            flying = false;
            has = false;
            at = false;
            level = Double.NaN;
            ringSize = 0;
            ringNext = 0;
        }

        /** Whether a flight is open (in the air off a drop, not yet settled). */
        boolean flying() {
            return flying;
        }
    }

    private static double across(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The flights of one d in one run: their lengths and take-off speeds, in the order flown. */
    static final class Flights {

        private double[] lengths = new double[8];
        private double[] speeds = new double[8];
        private int n;

        void add(double length, double speed) {
            if (n == lengths.length) {
                lengths = Arrays.copyOf(lengths, n * 2);
                speeds = Arrays.copyOf(speeds, n * 2);
            }
            lengths[n] = length;
            speeds[n] = speed;
            n++;
        }

        /** The count, the longest with its take-off, the fastest take-off and the p90 length (nearest rank). */
        Stat stat() {
            if (n == 0) {
                return new Stat(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }
            int longest = 0;
            double fast = Double.NaN;
            for (int i = 0; i < n; i++) {
                if (lengths[i] > lengths[longest]) {
                    longest = i;
                }
                if (!Double.isNaN(speeds[i]) && !(fast >= speeds[i])) {
                    fast = speeds[i];
                }
            }
            double[] sorted = Arrays.copyOf(lengths, n);
            Arrays.sort(sorted);
            double p90 = sorted[(int) Math.ceil(0.9 * n) - 1];
            return new Stat(n, lengths[longest], speeds[longest], fast, p90);
        }
    }

    /**
     * One d's flights in a run.
     *
     * @param count        how many
     * @param longest      the longest, blocks across the ground (NaN with none)
     * @param longestSpeed its take-off speed, blocks a second (NaN unknown)
     * @param fastest      the fastest take-off, blocks a second (NaN unknown)
     * @param p90          the 90th percentile length (nearest rank), blocks
     */
    public record Stat(int count, double longest, double longestSpeed, double fastest, double p90) {
    }

    /**
     * One recorded run.
     *
     * @param edition  "Java" or "Bedrock"
     * @param style    the course's style ({@code road}, {@code slalom})
     * @param tier     its tier ({@code medium})
     * @param finished whether the run reached the finish
     * @param one      its 1-block flights
     * @param two      its 2-block flights
     */
    public record Summary(String edition, String style, String tier, boolean finished, Stat one, Stat two) {

        /** Every flight kept: 1- and 2-block. */
        public int drops() {
            return one.count() + two.count();
        }

        /**
         * The console line: "Ice Boat flights (Java, road medium): 31 drops; 2-block: 18, longest 12.4 at 39.8
         * b/s, p90 11.9; 1-block: 13, longest 8.1 at 38.2 b/s, p90 7.7" (", not finished" in the brackets for a
         * run that ended before the finish). No player's name: the edition only.
         */
        public String line() {
            return "Ice Boat flights (" + edition + ", " + (style + " " + tier).trim()
                    + (finished ? "" : ", not finished") + "): " + drops() + " drop" + (drops() == 1 ? "" : "s") + "; "
                    + part(2, two) + "; " + part(1, one);
        }

        private static String part(int d, Stat s) {
            String head = d + "-block: " + s.count();
            if (s.count() == 0) {
                return head;
            }
            return head + ", longest " + tenth(s.longest()) + " at " + tenth(s.longestSpeed()) + " b/s, p90 "
                    + tenth(s.p90());
        }
    }
}
