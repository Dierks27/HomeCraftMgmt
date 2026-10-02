package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.GenRandom;

import java.util.ArrayList;
import java.util.List;

/**
 * A Mountain Run v2's route (MOUNTAIN-V2-SPEC §4.2, §5.3, §5.4): the {@link Frame}'s bands filled with
 * beats, joined by its links, from the start pit on the summit band to the finish straight in front of
 * the stand, as one {@link Centreline} with every element tagged with what it is for.
 *
 * <p><b>A traverse</b> is a chain of beats drawn by a seeded Markov chain (LONG, MED and SHORT straights,
 * a SWEEP, an S-curve, a CHICANE, a tighter BEND; a slalom only straights and gentle sweeps), worked in
 * the band's own frame: a along the band, b across it, ψ the heading off its axis. Every beat keeps the
 * heading within ±50° of the axis, the line within the band's lateral room (half the pitch either side,
 * less the corridor and 4 of terrain), and consecutive arcs alternating in sign unless both are gentle
 * sweeps (≤ 25°). <b>Lookahead repair:</b> a beat is kept only while a closure still exists after it, an
 * arc, a straight and an arc (radii from the tier's sweepers) that bring the line back onto the band's
 * axis heading along it with room for an approach straight of at least {@value #APPROACH} to the link
 * (where a brake drop fits); after {@value #REDRAWS} refused draws the closure is laid at once, and a band
 * that can't close drops the candidate ({@code null}).
 *
 * <p><b>Gate sets</b> (slalom): runs of gates at a seeded spacing on the traverses' straights and sweeps,
 * an open run between sets for a drop; their gate line is what {@link #line} gives {@link BoatLine}.
 *
 * <p>Pure and deterministic: the same frame and stream give the same route on every host.
 */
public final class Skeleton {

    /**
     * An approach straight of at least this much runs into every link (§5.3: a link wants a brake drop
     * before it): on a slalom room for one lip, on a road for two (the brake drop and one more, 2-block
     * drops 63 apart), so the drops a run needs find their straights.
     */
    public static final double APPROACH = 46;
    /** How much more often a long straight comes on the tiers whose descent needs 2-block drops. */
    static final double LONG_LEAN = 3;
    static final double APPROACH_ROAD = 104;
    /** Draws refused before a band is closed at once. */
    static final int REDRAWS = 32;
    /** The steepest a traverse may run off its band's axis, degrees. */
    static final double MAX_OFF_AXIS = 50;
    /** Arcs gentler than this are never drawn (they read as a wobble, not a beat), degrees. */
    static final double MIN_ARC_DEG = 8;
    /** A sweep this gentle may follow another of the same hand. */
    static final double GENTLE_DEG = 25;
    /** A closure that can't reach the end window from where it is may first turn this much the other way, degrees. */
    static final double[] LEAD_DEG = {14, 22, 30};
    /** A band starts within this share of its lateral room either side of its axis. */
    static final double START_SHARE = 0.5;
    /** An arc straight after another keeps at least this share of its v_c (entered under 1.15 v_c). */
    static final double LINKED = 0.88;
    /** Each corridor keeps this much terrain to the half of the pitch it shares with the next band. */
    static final double BAND_ROOM = 4;
    /** No beat is begun with less than this left before the approach. */
    static final double MIN_ROOM = 40;
    /** A corridor stays at least this far from the half's north edge. */
    static final double NORTH = 22;
    /** The index's reach: the widest lane, its widenings and a margin. */
    static final double REACH = 16;
    /** Gate sets keep this far from a traverse's start and from its link (its approach and brake drop). */
    static final double GATE_LEAD = 24;
    static final double GATE_TAIL = 56;
    /** The open run between two gate sets: room for a drop, its zone and the checkpoints round it. */
    static final double OPEN_MIN = 92;
    static final double OPEN_MAX = 124;

    /** What an element is for. */
    public enum Role {
        PIT, LONG, MED, SHORT, SWEEP, S_CURVE, CHICANE, BEND, CLOSE, APPROACH, HAIRPIN, ELBOW, BULB, FINISH;

        public boolean link() {
            return this == HAIRPIN || this == ELBOW || this == BULB;
        }
    }

    /** An element's tag: its role, its band (the finish band is {@code bands}), and its beat's number. */
    public record Tag(Role role, int band, int beat) {
    }

    /**
     * A slalom gate set: the gates' places along the centreline, their spacing, and the side the first
     * opening is on (+1 right of the line, -1 left); openings alternate.
     */
    public record GateSet(int band, double[] at, double spacing, int firstSide) {

        public int gates() {
            return at.length;
        }

        public double from() {
            return at[0];
        }

        public double to() {
            return at[at.length - 1];
        }

        /** The side gate {@code i}'s opening is on. */
        public int side(int i) {
            return i % 2 == 0 ? firstSide : -firstSide;
        }
    }

    public final Frame frame;
    public final MountainTier tier;
    public final Centreline line;
    /** One tag per element, in order. */
    public final List<Tag> tags;
    /** The start (30 along the pit), the finish mark and the lane's end (after the paddock), along s. */
    public final double start;
    public final double finish;
    public final double end;
    /** Where each band's traverse starts and its link starts, along s. */
    public final double[] bandFrom;
    public final double[] linkFrom;
    /** The slalom's gate sets, in order (empty on a road). */
    public final List<GateSet> gates;

    Skeleton(Frame frame, Centreline line, List<Tag> tags, double finish, double end, double[] bandFrom,
             double[] linkFrom, List<GateSet> gates) {
        this.frame = frame;
        this.tier = frame.tier;
        this.line = line;
        this.tags = List.copyOf(tags);
        this.start = Frame.START;
        this.finish = finish;
        this.end = end;
        this.bandFrom = bandFrom;
        this.linkFrom = linkFrom;
        this.gates = List.copyOf(gates);
    }

    public Tag tag(Centreline.Element e) {
        return tags.get(e.index);
    }

    /** The tag of the element {@code s} along. */
    public Tag tagAt(double s) {
        return tag(line.elementAt(s));
    }

    /** The gate set holding {@code s} (its first gate less a spacing to its last plus one), or {@code null}. */
    public GateSet gateSetAt(double s) {
        for (GateSet g : gates) {
            if (s >= g.from() - g.spacing() && s <= g.to() + g.spacing()) {
                return g;
            }
        }
        return null;
    }

    /** The lane's width at {@code s} (the corridor's in a slalom). */
    public double width(double s) {
        return s < Frame.PIT ? tier.pitWidth : tier.width;
    }

    // ---- drawing ---------------------------------------------------------------------------------------

    /** A route through {@code frame} from stream {@code r}, or {@code null} when a band can't close. */
    public static Skeleton draw(GenRandom r, Frame frame) {
        MountainTier tier = frame.tier;
        int n = frame.bands;
        double h0 = frame.dir(0) > 0 ? 0 : Math.PI;
        Centreline.Builder out = new Centreline.Builder(frame.pitX(), frame.z[0], h0);
        List<Tag> tags = new ArrayList<>();
        int[] beat = {0};
        out.straight(Frame.PIT);
        tags.add(new Tag(Role.PIT, 0, beat[0]++));
        double[] bandFrom = new double[n];
        double[] linkFrom = new double[n];
        List<GateSet> gates = new ArrayList<>();
        double lastSign = 0; // world hand of the last arc (+1 right)
        int[] made = new int[2]; // S-curves and chicanes laid so far (the grammar's quota)
        for (int k = 0; k < n; k++) {
            bandFrom[k] = k == 0 ? 0 : out.s();
            Walker w = new Walker(r.fork("band:" + k), frame, k, out, tags, beat, lastSign);
            w.made = made;
            if (!w.walk()) {
                return null;
            }
            if (tier.slalom()) {
                gates.addAll(gateSets(r.fork("gates:" + k), frame, k, bandFrom[k], out.s(), out.elements(), tags));
            }
            linkFrom[k] = out.s();
            if (!link(frame, k, out, tags, beat[0]++)) {
                return null;
            }
            lastSign = frame.dir(k);
        }
        // the finish band: straight to the stand's column, then the run-out, the paddock and the end wall
        double fx = out.x();
        double toFinish = Math.abs(fx - Frame.FINISH_X);
        double finish = out.s() + toFinish;
        out.straight(toFinish + Frame.RUN_OUT + Frame.PADDOCK);
        tags.add(new Tag(Role.FINISH, n, beat[0]));
        Centreline line = out.build(480, 640, REACH);
        return new Skeleton(frame, line, tags, finish, line.length(), bandFrom, linkFrom, gates);
    }

    /**
     * Link {@code k}: the frame's hairpin, elbows or bulb, turning toward the next band, sized from where
     * band k actually ended (§4.2: a hairpin's R is half the distance between the two band ends; elbows take
     * up the difference in their plunge; a bulb in its main radius). The next band starts as near its own
     * axis as the link allows (exactly on it for the finish band). False when no size fits.
     */
    static boolean link(Frame frame, int k, Centreline.Builder out, List<Tag> tags, int beat) {
        double[] dz = drops(frame, k);
        double ze = out.z();
        boolean last = k == frame.bands - 1;
        double zNext = last ? frame.zFinish : frame.z[k + 1];
        double[] next = last ? new double[]{0, 0} : startRoom(frame, k + 1);
        // the next band starts as near its axis as the link allows (its own walk then has room either way)
        double lo = Math.max(next[0], ze + dz[0] - zNext);
        double hi = Math.min(next[1], ze + dz[1] - zNext);
        if (lo > hi + 1e-9) {
            return false;
        }
        double bs = Math.max(lo, Math.min(hi, 0));
        double drop = zNext + bs - ze;
        Frame.Link planned = frame.links[k];
        Frame.Link l = switch (planned.kind()) {
            case HAIRPIN -> new Frame.Link(Frame.LinkKind.HAIRPIN, drop / 2, 0, drop / 2);
            case ELBOWS -> new Frame.Link(Frame.LinkKind.ELBOWS, planned.r1(), drop - planned.r1() - planned.r2(),
                    planned.r2());
            case BULB -> new Frame.Link(Frame.LinkKind.BULB, Frame.bulbRadius(drop), 0, Frame.bulbRadius(drop));
        };
        int hand = frame.dir(k); // east-running bands turn right (south), west-running left
        switch (l.kind()) {
            case HAIRPIN -> {
                out.arc(l.r1(), hand * Math.PI);
                tags.add(new Tag(Role.HAIRPIN, k, beat));
            }
            case ELBOWS -> {
                out.arc(l.r1(), hand * Math.PI / 2);
                tags.add(new Tag(Role.ELBOW, k, beat));
                if (l.plunge() > 1e-9) {
                    out.straight(l.plunge());
                    tags.add(new Tag(Role.ELBOW, k, beat));
                }
                out.arc(l.r2(), hand * Math.PI / 2);
                tags.add(new Tag(Role.ELBOW, k, beat));
            }
            case BULB -> {
                double c = Math.toRadians(Frame.BULB_COUNTER_DEG);
                out.arc(Frame.BULB_COUNTER_R, -hand * c);
                tags.add(new Tag(Role.BULB, k, beat));
                out.arc(l.r1(), hand * Math.toRadians(Frame.BULB_MAIN_DEG));
                tags.add(new Tag(Role.BULB, k, beat));
                out.arc(Frame.BULB_COUNTER_R, -hand * c);
                tags.add(new Tag(Role.BULB, k, beat));
            }
        }
        return true;
    }

    /** The lateral room of band {@code k}'s traverse: {least b, most b} off its axis (§4.2's U_k either side). */
    static double[] limits(Frame frame, int k) {
        MountainTier tier = frame.tier;
        double half = tier.width / 2.0 + 1 + BAND_ROOM;
        double down = frame.links[k].drop() / 2 - half;
        double up = k == 0 ? frame.z[0] - NORTH - half : frame.links[k - 1].drop() / 2 - half;
        // a bulb swings back toward the band above by its counter-arc: keep clear of it
        if (k > 0 && frame.links[k - 1].kind() == Frame.LinkKind.BULB) {
            up = Math.min(up, frame.links[k - 1].drop() / 2 - half - 9);
        }
        return new double[]{-Math.max(0, up), Math.max(0, down)};
    }

    /** Where band {@code k} may start, off its axis: the middle half of its room. */
    static double[] startRoom(Frame frame, int k) {
        double[] room = limits(frame, k);
        return new double[]{room[0] * START_SHARE, room[1] * START_SHARE};
    }

    /** The drops across the bands link {@code k} can make: {least, most}. */
    static double[] drops(Frame frame, int k) {
        MountainTier tier = frame.tier;
        Frame.Link l = frame.links[k];
        return switch (l.kind()) {
            case HAIRPIN -> new double[]{2 * tier.hairRMin, 2 * tier.hairRMax};
            case ELBOWS -> {
                double lip = MountainTier.runUp(tier.width) + MountainTier.landing(2, false) + 2;
                double least = l.plunge() >= lip - 1e-9 ? lip : 0;
                yield new double[]{l.r1() + l.r2() + least, l.r1() + l.r2() + Math.max(least, l.plunge()) + 24};
            }
            case BULB -> new double[]{Frame.bulbDrop(Frame.BULB_R_MIN), Frame.bulbDrop(Frame.BULB_R_MAX)};
        };
    }

    /** Where band {@code k}'s traverse may end, off its axis, so its link can reach the next band's room. */
    static double[] endWindow(Frame frame, int k) {
        double[] dz = drops(frame, k);
        boolean last = k == frame.bands - 1;
        double zNext = last ? frame.zFinish : frame.z[k + 1];
        double[] next = last ? new double[]{0, 0} : startRoom(frame, k + 1);
        double[] own = limits(frame, k);
        double lo = Math.max(own[0], zNext + next[0] - dz[1] - frame.z[k]);
        double hi = Math.min(own[1], zNext + next[1] - dz[0] - frame.z[k]);
        return new double[]{lo, hi};
    }

    // ---- one traverse ----------------------------------------------------------------------------------

    /** One primitive: a straight of {@code len}, or an arc of radius {@code r} turning {@code angle} (local, radians). */
    record Prim(double len, double r, double angle, Role role) {

        boolean arc() {
            return r > 0;
        }
    }

    /** A pose in the band's own frame: a along, b across (toward +z), ψ off the axis (toward +z positive). */
    record Pose(double a, double b, double psi) {
    }

    /** Walks one band from where the line is now to its link's entry. */
    static final class Walker {
        final GenRandom r;
        final MountainTier tier;
        final int dir;
        final int band;
        final Centreline.Builder out;
        final List<Tag> tags;
        final int[] beat;
        final double x0;
        final double axis;
        final double aEnd;
        final double bMin;
        final double bMax;
        /** The approach this band's link wants. */
        final double approach;
        /** Where the traverse may end, off its axis (its link takes up the rest). */
        final double bLo;
        final double bHi;
        /** Local hand of the last arc (+1 toward +z), 0 for none; whether it was a gentle sweep. */
        double lastSign;
        boolean lastGentle;
        /** The radius of the last element when it was an arc (0 after a straight). */
        double lastArcR;
        /** S-curves and chicanes laid so far in the run, shared by its bands. */
        int[] made = new int[2];
        final int bands;
        Role prev;
        Pose pose;

        Walker(GenRandom r, Frame frame, int k, Centreline.Builder out, List<Tag> tags, int[] beat,
               double worldLastSign) {
            this.r = r;
            this.tier = frame.tier;
            this.dir = frame.dir(k);
            this.band = k;
            this.bands = frame.bands;
            this.out = out;
            this.tags = tags;
            this.beat = beat;
            this.x0 = out.x();
            this.axis = frame.z[k];
            this.aEnd = dir * (frame.linkX(k) - x0);
            this.approach = tier.road() && aEnd > APPROACH_ROAD + 120 ? APPROACH_ROAD : APPROACH;
            double[] room = limits(frame, k);
            this.bMin = room[0];
            this.bMax = room[1];
            double[] window = endWindow(frame, k);
            this.bLo = window[0];
            this.bHi = window[1];
            this.lastSign = dir * worldLastSign;
            this.lastGentle = false;
            this.prev = k == 0 ? Role.PIT : Role.HAIRPIN;
            this.pose = local(out.x(), out.z(), out.heading());
        }

        Pose local(double x, double z, double h) {
            double psi = dir > 0 ? h : Math.PI - h;
            psi = psi - 2 * Math.PI * Math.floor((psi + Math.PI) / (2 * Math.PI));
            return new Pose(dir * (x - x0), z - axis, psi);
        }

        /** Walk the band; false when it can't close. */
        boolean walk() {
            while (aEnd - approach - pose.a() > MIN_ROOM) {
                List<Prim> next = null;
                Role kind = null;
                for (int t = 0; t < REDRAWS && next == null; t++) {
                    kind = nextBeat();
                    List<Prim> cand = draw(kind);
                    if (cand != null && fits(pose, cand) && closure(after(pose, cand), handAfter(cand), gentleAfter(cand),
                            arcAfter(cand), true) != null) {
                        next = cand;
                    }
                }
                if (next == null) {
                    break;
                }
                commit(next, kind);
                prev = kind;
                if (kind == Role.S_CURVE) {
                    made[0]++;
                } else if (kind == Role.CHICANE) {
                    made[1]++;
                }
            }
            List<Prim> close = closure(pose, lastSign, lastGentle, lastArcR, false);
            if (close == null) {
                return false;
            }
            if (!close.isEmpty()) {
                commit(close, Role.CLOSE);
            }
            double rest = aEnd - pose.a();
            if (rest < approach - 1e-6 || pose.b() < bLo - 0.05 || pose.b() > bHi + 0.05
                    || Math.abs(pose.psi()) > 1e-6) {
                return false;
            }
            commit(List.of(new Prim(rest, 0, 0, Role.APPROACH)), Role.APPROACH);
            return true;
        }

        /** The next beat from the last (§5.3's grammar, a seeded Markov chain). */
        Role nextBeat() {
            if (tier.slalom()) {
                boolean straightLast = prev == Role.LONG || prev == Role.MED || prev == Role.SHORT;
                if (straightLast) {
                    return r.weighted(0.85, 0.15) == 0 ? Role.SWEEP : Role.MED;
                }
                double lean = tier.descentMin > tier.dropsMin ? LONG_LEAN : 1;
                return switch (r.weighted(0.3 * lean, 0.5, 0.2)) {
                    case 0 -> Role.LONG;
                    case 1 -> Role.MED;
                    default -> Role.SHORT;
                };
            }
            double chic = tier.chicMax > 0 ? 1 : 0;
            // quota: a run behind on the tier's S-curves or chicanes leans on them for the bands left
            double left = Math.max(1, bands - band);
            double sBoost = 1 + 4 * Math.max(0, tier.sCurvesMin + 1 - made[0]) / left;
            double cBoost = 1 + 4 * Math.max(0, tier.chicMin - made[1]) / left;
            double[] w = switch (prev) {
                // LONG, MED, SHORT, SWEEP, S_CURVE, CHICANE, BEND
                case LONG -> new double[]{0, 0, 0, 0.46, 0.34, 0.1 * chic, 0.1};
                case MED -> new double[]{0, 0, 0, 0.38, 0.36, 0.14 * chic, 0.12};
                case SHORT -> new double[]{0, 0, 0, 0.6, 0.4, 0, 0};
                case SWEEP -> new double[]{0.46, 0.4, 0.04, 0.06, 0.04, 0, 0};
                case S_CURVE -> new double[]{0.5, 0.42, 0.04, 0.04, 0, 0, 0};
                case CHICANE -> new double[]{0.5, 0.46, 0.04, 0, 0, 0, 0};
                case BEND -> new double[]{0.5, 0.46, 0.04, 0, 0, 0, 0};
                default -> new double[]{0.3, 0.45, 0.0, 0.2, 0.15, 0, 0};
            };
            w[4] *= sBoost;
            w[5] *= cBoost;
            // where the descent floor asks for more than a block a lip, long straights hold the 2-block drops
            w[0] *= tier.descentMin > tier.dropsMin ? LONG_LEAN : 1;
            return new Role[]{Role.LONG, Role.MED, Role.SHORT, Role.SWEEP, Role.S_CURVE, Role.CHICANE,
                    Role.BEND}[r.weighted(w)];
        }

        /** The primitives of one beat of {@code kind}, or {@code null} when none fits the heading rules. */
        List<Prim> draw(Role kind) {
            double room = aEnd - approach - pose.a();
            return switch (kind) {
                case LONG -> straight(100, 200, room, kind);
                case MED -> straight(40, 99, room, kind);
                case SHORT -> straight(12, 39, room, kind);
                case SWEEP -> {
                    double rad = r.nextDouble(tier.sweepRMin, tier.sweepRMax);
                    // most sweeps are gentle; the tier's sharpest come now and then
                    double u = r.nextDouble();
                    double deg = tier.sweepDegMin + (tier.sweepDegMax - tier.sweepDegMin) * u * u;
                    yield arcBeat(rad, deg, kind, true);
                }
                case BEND -> {
                    double rad = r.nextDouble(tier.bendRMin, tier.bendRMax);
                    double deg = r.nextDouble(20, 45);
                    yield arcBeat(rad, deg, kind, false);
                }
                case S_CURVE -> pair(50, Math.min(120, Math.max(70, tier.sweepRMin + 20)), 20, 45, 0, 20, kind);
                case CHICANE -> pair(30, 40, 15, 30, 0, 8, kind);
                default -> null;
            };
        }

        List<Prim> straight(double lo, double hi, double room, Role kind) {
            double len = r.nextDouble(lo, hi);
            double keep = 30;
            if (len > room - keep) {
                return null;
            }
            return List.of(new Prim(len, 0, 0, kind));
        }

        /** Whether the run is behind on the tier's S-curves or chicanes for the bands left. */
        boolean behind() {
            if (tier.slalom()) {
                return false;
            }
            return made[0] < tier.sCurvesMin + 1 || made[1] < tier.chicMin;
        }

        /**
         * The least radius an arc may have when it follows the last one with no straight between: its v_c no
         * more than {@value #LINKED} under the last arc's, so it is never entered braking hard (F-K) where no
         * lip can stand.
         */
        double linkedMin() {
            return linkedMin(lastArcR);
        }

        static double linkedMin(double arcR) {
            if (arcR <= 0) {
                return 0;
            }
            double want = BoatLine.cornerSpeed(arcR, BoatLine.PACKED) * LINKED;
            double lo = 10;
            double hi = Math.max(10, arcR);
            for (int i = 0; i < 40; i++) {
                double mid = (lo + hi) / 2;
                if (BoatLine.cornerSpeed(mid, BoatLine.PACKED) >= want) {
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
            return hi;
        }

        /** One arc: its hand alternating from the last unless both are gentle, steered back toward the axis. */
        List<Prim> arcBeat(double rad, double deg, Role kind, boolean sweep) {
            rad = Math.max(rad, linkedMin());
            boolean gentle = sweep && deg <= GENTLE_DEG && tier.road();
            double sign;
            if (lastSign == 0 || (gentle && lastGentle)) {
                // free hand: lean back toward the axis
                double toward = pose.psi() > Math.toRadians(5) ? -1 : pose.psi() < -Math.toRadians(5) ? 1
                        : (pose.b() > 0 ? -1 : 1);
                sign = r.chance(0.75) ? toward : -toward;
            } else {
                sign = -lastSign;
            }
            double angle = sign * Math.toRadians(deg);
            double psi = pose.psi() + angle;
            if (Math.abs(psi) > Math.toRadians(MAX_OFF_AXIS)) {
                // as much as the heading rule allows, if that is still a beat
                double room = Math.toRadians(MAX_OFF_AXIS) - sign * pose.psi();
                if (room < Math.toRadians(MIN_ARC_DEG + 4)) {
                    return null;
                }
                angle = sign * room * 0.95;
            }
            return List.of(new Prim(rad * Math.abs(angle), rad, angle, kind));
        }

        /** Two arcs of opposite hands with a short straight between: an S-curve or a chicane. */
        List<Prim> pair(double rLo, double rHi, double dLo, double dHi, double gLo, double gHi, Role kind) {
            double r1 = Math.max(r.nextDouble(rLo, rHi), linkedMin());
            if (r1 > rHi + 1e-9) {
                return null;
            }
            double r2 = r.nextDouble(rLo, rHi);
            double d1 = Math.toRadians(r.nextDouble(dLo, dHi));
            double gap = r.nextDouble(gLo, gHi);
            double sign = lastSign != 0 ? -lastSign : (pose.b() > 0 ? -1 : 1);
            if (Math.abs(pose.psi() + sign * d1) > Math.toRadians(MAX_OFF_AXIS)) {
                return null;
            }
            // the second arc settles the heading near the axis (a seeded few degrees either way)
            double settle = pose.psi() + sign * d1 + Math.toRadians(r.nextDouble(-8, 8));
            double d2 = Math.max(Math.toRadians(dLo), Math.min(Math.toRadians(dHi), sign * settle));
            List<Prim> out = new ArrayList<>();
            out.add(new Prim(r1 * d1, r1, sign * d1, kind));
            if (gap > 0.5) {
                out.add(new Prim(gap, 0, 0, kind));
            }
            out.add(new Prim(r2 * d2, r2, -sign * d2, kind));
            return out;
        }

        /** Whether {@code prims} from {@code p} keep the heading and the band's room, never running past the approach. */
        boolean fits(Pose p, List<Prim> prims) {
            Pose q = p;
            for (Prim pr : prims) {
                int steps = Math.max(1, (int) Math.ceil(pr.len() / 4));
                for (int i = 1; i <= steps; i++) {
                    Pose m = step(q, pr, pr.len() * i / steps);
                    if (m.b() < bMin - 1e-6 || m.b() > bMax + 1e-6
                            || Math.abs(m.psi()) > Math.toRadians(MAX_OFF_AXIS) + 1e-9) {
                        return false;
                    }
                }
                q = step(q, pr, pr.len());
            }
            return q.a() <= aEnd - approach + 1e-6;
        }

        /** The hand of the last arc once {@code prims} are laid (the current one when they have none). */
        double handAfter(List<Prim> prims) {
            double h = lastSign;
            for (Prim pr : prims) {
                if (pr.arc()) {
                    h = Math.signum(pr.angle());
                }
            }
            return h;
        }

        /** The radius of the last element once {@code prims} are laid when it is an arc, else 0. */
        double arcAfter(List<Prim> prims) {
            double a = lastArcR;
            for (Prim pr : prims) {
                a = pr.arc() ? pr.r() : pr.len() > 1e-6 ? 0 : a;
            }
            return a;
        }

        boolean gentleAfter(List<Prim> prims) {
            boolean g = lastGentle;
            for (Prim pr : prims) {
                if (pr.arc()) {
                    g = tier.road() && pr.role() == Role.SWEEP && Math.abs(pr.angle()) <= Math.toRadians(GENTLE_DEG);
                }
            }
            return g;
        }

        Pose after(Pose p, List<Prim> prims) {
            Pose q = p;
            for (Prim pr : prims) {
                q = step(q, pr, pr.len());
            }
            return q;
        }

        /** {@code t} blocks along primitive {@code pr} from {@code p}, in the band's frame. */
        static Pose step(Pose p, Prim pr, double t) {
            if (!pr.arc()) {
                return new Pose(p.a() + t * Math.cos(p.psi()), p.b() + t * Math.sin(p.psi()), p.psi());
            }
            double sigma = Math.signum(pr.angle());
            double psi = p.psi() + sigma * t / pr.r();
            double a = p.a() + sigma * pr.r() * (Math.sin(psi) - Math.sin(p.psi()));
            double b = p.b() - sigma * pr.r() * (Math.cos(psi) - Math.cos(p.psi()));
            return new Pose(a, b, psi);
        }

        /**
         * The closure from {@code p}: an arc to a crossing heading ψm, a straight, an arc back onto the axis
         * heading along it, leaving at least the approach; the empty list when already there; {@code null}
         * when none of the tried radii and headings fits. {@code probe}: only whether one exists.
         */
        List<Prim> closure(Pose p, double hand, boolean gentle, double arcR, boolean probe) {
            if (bLo > bHi + 1e-9) {
                return null;
            }
            if (Math.abs(p.psi()) < 1e-9 && p.b() >= bLo - 0.05 && p.b() <= bHi + 0.05) {
                return List.of();
            }
            double room = aEnd - approach - p.a();
            if (room < 0) {
                return null;
            }
            double lo = Math.max(tier.sweepRMin, Math.min(Math.min(tier.sweepRMax, 150), linkedMin(arcR)));
            double hi = Math.min(tier.sweepRMax, 150);
            double[] radii = {lo, lo + (hi - lo) * 0.25, lo + (hi - lo) * 0.5, lo + (hi - lo) * 0.75, hi};
            List<List<Prim>> found = new ArrayList<>();
            int start = probe ? 0 : r.nextInt(radii.length);
            for (int ri = 0; ri < radii.length; ri++) {
                double rad = radii[(start + ri) % radii.length];
                for (int k = -18; k <= 18; k++) {
                    double m = Math.toRadians(k * 2.5);
                    List<Prim> c = closeVia(p, rad, m, room, hand, gentle);
                    if (c != null) {
                        if (probe) {
                            return c;
                        }
                        found.add(c);
                    }
                }
                if (found.size() >= 6 && !(behind() && made[0] < tier.sCurvesMin + 1)) {
                    break;
                }
            }
            if (found.isEmpty()) {
                // lead with one arc of the hand the alternation asks for, then close from there
                for (int ri = 0; ri < radii.length && found.isEmpty(); ri++) {
                    double rad = radii[(start + ri) % radii.length];
                    for (double deg : LEAD_DEG) {
                        double sign = hand != 0 ? -hand : (p.b() > 0 ? 1 : -1);
                        Prim lead = new Prim(rad * Math.toRadians(deg), rad, sign * Math.toRadians(deg), Role.CLOSE);
                        if (!fits(p, List.of(lead))) {
                            continue;
                        }
                        Pose q = step(p, lead, lead.len());
                        double qRoom = aEnd - approach - q.a();
                        for (int k = -18; k <= 18 && qRoom > 0; k++) {
                            List<Prim> c = closeVia(q, rad, Math.toRadians(k * 2.5), qRoom, sign, false);
                            if (c != null) {
                                List<Prim> all = new ArrayList<>();
                                all.add(lead);
                                all.addAll(c);
                                if (probe) {
                                    return all;
                                }
                                found.add(all);
                            }
                        }
                        if (!found.isEmpty()) {
                            break;
                        }
                    }
                }
                if (found.isEmpty()) {
                    return null;
                }
                return found.get(r.nextInt(found.size()));
            }
            if (behind() && made[0] < tier.sCurvesMin + 1) {
                List<List<Prim>> shaped = new ArrayList<>();
                for (List<Prim> c : found) {
                    if (sShaped(c)) {
                        shaped.add(c);
                    }
                }
                if (!shaped.isEmpty()) {
                    made[0]++;
                    return shaped.get(r.nextInt(shaped.size()));
                }
            }
            return found.get(r.nextInt(found.size()));
        }

        /** Whether a closure reads as an S-curve (§5.3: R 50-120, 20-45° each, at most 20 between). */
        static boolean sShaped(List<Prim> c) {
            List<Prim> arcs = new ArrayList<>();
            double between = 0;
            for (Prim p : c) {
                if (p.arc()) {
                    arcs.add(p);
                } else if (arcs.size() == 1) {
                    between += p.len();
                }
            }
            if (arcs.size() != 2 || between > 20 || Math.signum(arcs.get(0).angle()) == Math.signum(arcs.get(1).angle())) {
                return false;
            }
            for (Prim a : arcs) {
                double deg = Math.toDegrees(Math.abs(a.angle()));
                if (a.r() < 50 || a.r() > 120 || deg < 20 || deg > 45) {
                    return false;
                }
            }
            return true;
        }

        /** The closure through crossing heading {@code m} on arcs of radius {@code rad}, or {@code null}. */
        List<Prim> closeVia(Pose p, double rad, double m, double room, double lastSign, boolean lastGentle) {
            double d1 = m - p.psi();
            double d2 = -m;
            double minArc = Math.toRadians(MIN_ARC_DEG);
            boolean settle = Math.abs(m) < 1e-9; // one arc straight back to the axis's heading, however small
            if ((!settle && Math.abs(d1) > 1e-9 && Math.abs(d1) < minArc)
                    || (Math.abs(d2) > 1e-9 && Math.abs(d2) < minArc)) {
                return null;
            }
            if (Math.abs(m) > Math.toRadians(45) + 1e-9) {
                return null;
            }
            double maxArc = Math.toRadians(tier.sweepDegMax);
            if (Math.abs(d1) > maxArc + 1e-9 || Math.abs(d2) > maxArc + 1e-9) {
                return null;
            }
            // hands: the first against the last arc's (unless both gentle), the second against the first
            double s1 = Math.signum(d1);
            double s2 = Math.signum(d2);
            if (s1 != 0 && lastSign != 0 && s1 == lastSign
                    && !(lastGentle && Math.abs(d1) <= Math.toRadians(GENTLE_DEG))) {
                return null;
            }
            double before = s1 != 0 ? s1 : lastSign;
            boolean gentle1 = s1 != 0 ? Math.abs(d1) <= Math.toRadians(GENTLE_DEG) : lastGentle;
            if (s2 != 0 && before != 0 && s2 == before && !(gentle1 && Math.abs(d2) <= Math.toRadians(GENTLE_DEG))) {
                return null;
            }
            Prim a1 = s1 == 0 ? null : new Prim(rad * Math.abs(d1), rad, d1, Role.CLOSE);
            Prim a2 = s2 == 0 ? null : new Prim(rad * Math.abs(d2), rad, d2, Role.CLOSE);
            Pose q = a1 == null ? p : step(p, a1, a1.len());
            Pose end2 = a2 == null ? new Pose(0, 0, 0) : step(new Pose(0, 0, m), a2, a2.len());
            double len;
            if (settle) {
                double bEnd = q.b();
                if (bEnd < bLo - 0.05 || bEnd > bHi + 0.05) {
                    return null;
                }
                len = 0;
            } else {
                // the straight between reaches the end window: the shortest that does, a little longer at random
                double a = (bLo - q.b() - end2.b()) / Math.sin(m);
                double b = (bHi - q.b() - end2.b()) / Math.sin(m);
                double lo = Math.max(0, Math.min(a, b));
                double hi = Math.max(a, b);
                if (hi < lo - 1e-9) {
                    return null;
                }
                len = lo + Math.min(hi - lo, 30) * 0.5;
            }
            double total = (q.a() - p.a()) + len * Math.cos(m) + end2.a();
            if (total > room + 1e-6) {
                return null;
            }
            List<Prim> out = new ArrayList<>();
            if (a1 != null) {
                out.add(a1);
            }
            if (len > 1e-6) {
                out.add(new Prim(len, 0, 0, Role.CLOSE));
            }
            if (a2 != null) {
                out.add(a2);
            }
            return fits(p, out) ? out : null;
        }

        /** Lay {@code prims} on the line, tagged as one beat of {@code kind}. */
        void commit(List<Prim> prims, Role kind) {
            int b = beat[0]++;
            for (Prim pr : prims) {
                Role role = kind == Role.CLOSE ? (pr.arc() ? Role.SWEEP : roleOfLength(pr.len())) : pr.role();
                if (pr.arc()) {
                    out.arc(pr.r(), dir * pr.angle());
                    lastSign = Math.signum(pr.angle());
                    lastGentle = tier.road() && role == Role.SWEEP
                            && Math.abs(pr.angle()) <= Math.toRadians(GENTLE_DEG);
                    lastArcR = pr.r();
                } else {
                    out.straight(pr.len());
                    lastArcR = pr.len() > 1e-6 ? 0 : lastArcR;
                }
                tags.add(new Tag(role, band, b));
            }
            pose = local(out.x(), out.z(), out.heading());
        }
    }

    /** A straight's beat by its length (§6: Ls >= 100, Ms 40-99, Ss < 40). */
    static Role roleOfLength(double len) {
        return len >= 100 ? Role.LONG : len >= 40 ? Role.MED : Role.SHORT;
    }

    // ---- slalom gate sets ------------------------------------------------------------------------------

    /**
     * The gate sets of band {@code k} (its traverse from {@code from} to {@code to} along s): sets of the
     * tier's gates at a seeded spacing, on its straights and sweeps, clear of its start and its approach,
     * an open run of {@value #OPEN_MIN}-{@value #OPEN_MAX} between sets for a drop.
     */
    /** The world hand (+1 right) of the last turn before {@code s} in band {@code k}: an arc, else the link before. */
    static int handBefore(Frame frame, int k, List<Centreline.Element> elements, double s) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            Centreline.Element e = elements.get(i);
            if (e.arc() && e.s0 < s) {
                return e.turn;
            }
        }
        return k > 0 ? frame.dir(k - 1) : 0;
    }

    /** The world hand of the first turn at or after {@code s} in band {@code k}: an arc, else the band's own link. */
    static int handAfter(Frame frame, int k, List<Centreline.Element> elements, double s) {
        for (Centreline.Element e : elements) {
            if (e.arc() && e.s1() > s) {
                return e.turn;
            }
        }
        return frame.dir(k);
    }

    static List<GateSet> gateSets(GenRandom r, Frame frame, int k, double from, double to,
                                  List<Centreline.Element> elements, List<Tag> tags) {
        MountainTier tier = frame.tier;
        List<GateSet> out = new ArrayList<>();
        // the first band's sets start past the pit, the 40 kept clear after the start and the first lip's room
        double s = from + (k == 0 ? Frame.START + 40 : GATE_LEAD) + r.nextDouble(0, 20);
        double last = to - GATE_TAIL;
        while (true) {
            int n = r.nextInt(tier.gatesMin, tier.gatesMax);
            double gap = Math.round(r.nextDouble(tier.gapMin, tier.gapMax) * 2) / 2.0;
            double first = s + gap; // a spacing of lead-in before the first gate
            double lastGate = first + (n - 1) * gap;
            while (n > tier.gatesMin && lastGate + gap > last) {
                n--;
                lastGate = first + (n - 1) * gap;
            }
            if (lastGate + gap > last) {
                break;
            }
            // F-R (a slalom has no three turns of one hand in a row): the swings start against the turn
            // before the set and end against the turn after it, by the first opening's side and the count
            int before = handBefore(frame, k, elements, first - gap);
            int side = before != 0 ? -before : (r.nextBoolean() ? 1 : -1);
            int after = handAfter(frame, k, elements, lastGate + gap);
            int endSide = (n - 1) % 2 == 0 ? side : -side;
            if (after != 0 && endSide == after) {
                if (n > tier.gatesMin) {
                    n--;
                } else if (first + n * gap + gap <= last) {
                    n++;
                } else {
                    break;
                }
                lastGate = first + (n - 1) * gap;
            }
            double[] at = new double[n];
            for (int i = 0; i < n; i++) {
                at[i] = first + i * gap;
            }
            out.add(new GateSet(k, at, gap, side));
            s = lastGate + gap + r.nextDouble(OPEN_MIN, OPEN_MAX);
        }
        return out;
    }
}
