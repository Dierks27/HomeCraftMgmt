package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.GenRandom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The frame of a Mountain Run v2 (MOUNTAIN-V2-SPEC §4.2, §5.2 Stage A): where the bands lie and how
 * they join, drawn first and cheaply, so the {@link Skeleton} only fills each band with beats.
 *
 * <p><b>Bands and links.</b> The road can't climb, so it folds down the face in N traverses across x,
 * stacked from the summit band (z ≈ 56 ± 8) toward the finish band (z ≈ 572 ± 4) in front of the stand,
 * joined at alternate ends by a link: a HAIRPIN (one 180° arc, R = pitch / 2), ELBOWS (90°, a fall-line
 * plunge, 90°: R₁ + L_p + R₂ = pitch) or a BULB (-30°, 240°, -30°: a wider turn where the band ends short
 * of the margin). The links alternate ends, so they alternate right and left by construction; the N-th
 * link drops into the finish band, whose straight heads for the stand's column. Every traverse starts and
 * ends on its band's axis, heading along it, so a link joins two band centres exactly.
 *
 * <p><b>Length.</b> The frame aims at a model time drawn inside the tier's window: from the tier's
 * typical model speed it estimates the length each N would give with the traverses running margin to
 * margin, picks an N that can reach the target, and pulls the links in from the margins (insets) until
 * the estimate meets it. The {@link BoatLine} then measures the real time, and the flow score's F-T gate
 * keeps only candidates inside the window. The mirror (start at the west or the east end) is seeded.
 *
 * <p>Half-local coordinates (x 0..479, z 0..639), never mirrored in z: the summit is north, the stand
 * south. Pure.
 */
public final class Frame {

    /** The centreline stays within x 28..452 (§4.2). */
    public static final double X_MIN = 28;
    public static final double X_MAX = 452;
    /** The pit's back wall is this far in from its end of the half. */
    public static final double PIT_X = 36;
    /** The start is this far along the pit (30 behind it for the grid). */
    public static final double START = 30;
    /** The pit straight's length: the grid, the start, 4 in front and the first beats' run-in. */
    public static final double PIT = 56;
    /** The finish mark's column: the stand's (§4.1). */
    public static final double FINISH_X = 240.5;
    /** After the finish: 14 of run-out, 6 of sand paddock, then the end wall (§4.2). */
    public static final double RUN_OUT = 14;
    public static final double PADDOCK = 6;
    /** A traverse spans at least this much x. */
    static final double MIN_SPAN = 200;
    /** A pitch between bands (§4.2). */
    public static final double PITCH_MIN = 60;
    public static final double PITCH_MAX = 110;
    /** A bulb: two counter-arcs of R 60 and 30° round a 240° main arc of R_b 40-56. */
    public static final double BULB_COUNTER_R = 60;
    public static final double BULB_COUNTER_DEG = 30;
    public static final double BULB_MAIN_DEG = 240;
    static final double BULB_R_MIN = 40;
    static final double BULB_R_MAX = 56;
    /** An elbow's plunge with no lip is at most this long, so its second arc is entered at its own speed. */
    static final double SHORT_PLUNGE = 8;
    /** How much longer a traverse is than its x span (its beats wiggle), for the estimate. */
    static final double WIGGLE = 1.07;

    /** How two bands join. */
    public enum LinkKind {
        HAIRPIN, ELBOWS, BULB
    }

    /**
     * One link: its kind and radii. A hairpin's {@code r1} is its radius; elbows have {@code r1}, the
     * plunge {@code plunge} and {@code r2}; a bulb's main arc is {@code r1}.
     */
    public record Link(LinkKind kind, double r1, double plunge, double r2) {

        /** How far past the band's end (its entry x) the link reaches across x. */
        public double width() {
            return switch (kind) {
                case HAIRPIN, ELBOWS -> r1;
                case BULB -> bulbReach(r1);
            };
        }

        /** How far the link's exit x is past its entry x (elbows with r1 != r2). */
        public double shift() {
            return kind == LinkKind.ELBOWS ? r1 - r2 : 0;
        }

        /** Its length along the centreline. */
        public double length() {
            return switch (kind) {
                case HAIRPIN -> Math.PI * r1;
                case ELBOWS -> Math.PI / 2 * (r1 + r2) + plunge;
                case BULB -> 2 * BULB_COUNTER_R * Math.toRadians(BULB_COUNTER_DEG)
                        + r1 * Math.toRadians(BULB_MAIN_DEG);
            };
        }

        /** How far it drops the line across the bands (z). */
        public double drop() {
            return switch (kind) {
                case HAIRPIN -> 2 * r1;
                case ELBOWS -> r1 + plunge + r2;
                case BULB -> bulbDrop(r1);
            };
        }
    }

    public final MountainTier tier;
    /** True when the start is at the west end (the first traverse runs east). */
    public final boolean west;
    public final int bands;
    /** Band centres z (index 0 = the summit band), half-local, each x.5. */
    public final double[] z;
    /** The finish band's z (x.5). */
    public final double zFinish;
    /** Link k joins band k to band k + 1 (the last into the finish band). */
    public final Link[] links;
    /** Each link's entry pulled in from the margin by this much. */
    public final double[] inset;
    /** The model time the frame aims at, seconds, and the length it estimates for it. */
    public final double targetSeconds;
    public final double estimate;

    Frame(MountainTier tier, boolean west, double[] z, double zFinish, Link[] links, double[] inset,
          double targetSeconds, double estimate) {
        this.tier = tier;
        this.west = west;
        this.bands = z.length;
        this.z = z;
        this.zFinish = zFinish;
        this.links = links;
        this.inset = inset;
        this.targetSeconds = targetSeconds;
        this.estimate = estimate;
    }

    /** +1 when band {@code k}'s traverse runs east, -1 west; {@code k == bands} is the finish band. */
    public int dir(int k) {
        int first = west ? 1 : -1;
        return k % 2 == 0 ? first : -first;
    }

    /** The pit's back wall x. */
    public double pitX() {
        return west ? PIT_X : 480 - PIT_X;
    }

    /** Where link {@code k} starts (band k's end), x: inside the margin by the widest the link may be sized to. */
    public double linkX(int k) {
        int d = dir(k);
        double reach = maxWidth(k) + inset[k];
        return d > 0 ? X_MAX - reach : X_MIN + reach;
    }

    /** The widest link {@code k} may be once sized to the band ends (a hairpin's or bulb's radius varies). */
    double maxWidth(int k) {
        return switch (links[k].kind()) {
            case HAIRPIN -> tier.hairRMax;
            case ELBOWS -> links[k].r1();
            case BULB -> bulbReach(BULB_R_MAX);
        };
    }

    /** Where band {@code k}'s traverse starts, x (the pit's back for the first). */
    public double startX(int k) {
        if (k == 0) {
            return pitX();
        }
        return linkX(k - 1) + dir(k - 1) * links[k - 1].shift();
    }

    /** The bulb's farthest reach past its entry: a counter-arc's 30 across and its main arc's 1.5 R_b. */
    static double bulbReach(double rb) {
        double c = BULB_COUNTER_R * Math.sin(Math.toRadians(BULB_COUNTER_DEG));
        return c + rb * (1 + Math.sin(Math.toRadians(BULB_COUNTER_DEG)));
    }

    /** How far a bulb of main radius R_b drops across the bands: 1.732 R_b - 16.08. */
    static double bulbDrop(double rb) {
        double cos = Math.cos(Math.toRadians(BULB_COUNTER_DEG));
        return 2 * rb * cos - 2 * BULB_COUNTER_R * (1 - cos);
    }

    /** The bulb's main radius that drops {@code pitch}. */
    static double bulbRadius(double pitch) {
        double cos = Math.cos(Math.toRadians(BULB_COUNTER_DEG));
        return (pitch + 2 * BULB_COUNTER_R * (1 - cos)) / (2 * cos);
    }

    /** The estimated length of the whole run with these links and insets. */
    double estimateLength() {
        double total = 0;
        for (int k = 0; k < bands; k++) {
            double span = Math.abs(linkX(k) - startX(k));
            total += span * WIGGLE + links[k].length();
        }
        double fx = startX(bands);
        total += Math.abs(fx - FINISH_X) + RUN_OUT + PADDOCK;
        return total;
    }

    /** The shortest x span of any traverse. */
    double minSpan() {
        double m = Double.MAX_VALUE;
        for (int k = 0; k < bands; k++) {
            m = Math.min(m, Math.abs(linkX(k) - startX(k)));
        }
        return m;
    }

    // ---- drawing -----------------------------------------------------------------------------------------

    /** A seeded frame for {@code tier}; {@code null} when no band count can reach the drawn time. */
    public static Frame draw(GenRandom r, MountainTier tier) {
        boolean west = r.nextBoolean();
        double target = r.nextDouble(tier.tMin + 6, tier.tMax - 6);
        double z1 = 56 + r.nextInt(-8, 8) + 0.5;
        double zf = MountainTier.FINISH_BAND + r.nextInt(-4, 4) + 0.5;
        double want = target * tier.vEstimate;
        // a seeded band count among those that reach the time; the nearest miss when none does
        Frame best = null;
        double bestMiss = Double.MAX_VALUE;
        List<Frame> hit = new ArrayList<>();
        for (int n = tier.bandsMin; n <= tier.bandsMax; n++) {
            GenRandom rn = r.fork("bands:" + n);
            Frame f = withBands(rn, tier, west, n, z1, zf, target, want);
            if (f == null) {
                continue;
            }
            double miss = Math.abs(f.estimate - want);
            if (miss <= want * 0.04) {
                hit.add(f);
            }
            if (miss < bestMiss - 1e-9) {
                bestMiss = miss;
                best = f;
            }
        }
        return hit.isEmpty() ? best : hit.get(r.nextInt(hit.size()));
    }

    /** A frame of {@code n} bands from z1 to zf aiming at {@code want} blocks, or {@code null}. */
    static Frame withBands(GenRandom r, MountainTier tier, boolean west, int n, double z1, double zf,
                           double target, double want) {
        double total = zf - z1;
        Link[] links = null;
        for (int attempt = 0; attempt < 12 && links == null; attempt++) {
            links = links(r, tier, n, total);
        }
        if (links == null) {
            return null;
        }
        double[] z = new double[n];
        z[0] = z1;
        for (int k = 1; k < n; k++) {
            z[k] = z[k - 1] + links[k - 1].drop();
        }
        double[] inset = new double[n];
        Frame full = new Frame(tier, west, z, zf, links, inset, target, 0);
        double longest = full.estimateLength();
        double excess = longest - want;
        if (excess > 0) {
            // pull the links in: each unit of inset shortens the two traverses it sits between
            double[] weight = new double[n];
            double sum = 0;
            for (int k = 0; k < n; k++) {
                weight[k] = 0.4 + r.nextDouble();
                sum += weight[k];
            }
            double need = excess / (2 * WIGGLE);
            for (int k = 0; k < n; k++) {
                inset[k] = need * weight[k] / sum;
            }
            // keep every traverse at least MIN_SPAN across; what can't be taken there is taken elsewhere
            for (int pass = 0; pass < 4; pass++) {
                Frame f = new Frame(tier, west, z, zf, links, inset, target, 0);
                if (f.minSpan() >= MIN_SPAN) {
                    break;
                }
                for (int k = 0; k < n; k++) {
                    double s = Math.min(Math.abs(f.linkX(k) - f.startX(k)),
                            k + 1 < n ? Math.abs(f.linkX(k + 1) - f.startX(k + 1)) : Double.MAX_VALUE);
                    if (s < MIN_SPAN) {
                        inset[k] = Math.max(0, inset[k] - (MIN_SPAN - s));
                    }
                }
            }
        }
        Frame f = new Frame(tier, west, z, zf, links, inset, target, 0);
        if (f.minSpan() < MIN_SPAN * 0.9) {
            return null;
        }
        return new Frame(tier, west, z, zf, links, inset, target, f.estimateLength());
    }

    /**
     * Seeded links for {@code n} bands spanning {@code total} of z: at most the tier's hairpins, bulbs on a
     * road now and then, elbows taking up the rest; {@code null} when the pitches can't add up.
     */
    static Link[] links(GenRandom r, MountainTier tier, int n, double total) {
        Frame.LinkKind[] kinds = new Frame.LinkKind[n];
        double avg = total / n;
        int must = tier.road() ? r.nextInt(n) : -1;
        int hairpins = must >= 0 ? 1 : 0; // the road's own hairpin counts toward the tier's most from the start
        for (int k = 0; k < n; k++) {
            if (k == must) {
                kinds[k] = LinkKind.HAIRPIN;
                continue;
            }
            double h = hairpins < tier.hairMost && 2 * tier.hairRMax >= Math.min(avg, PITCH_MAX) - 30 ? 0.45 : 0;
            // a bulb is 260 blocks of arc with no straight for a lip: only where the descent asks little (easy)
            double b = tier.road() && tier.easy() ? 0.2 : 0;
            int pick = r.weighted(h, b, 1 - h - b);
            kinds[k] = pick == 0 ? LinkKind.HAIRPIN : pick == 1 ? LinkKind.BULB : LinkKind.ELBOWS;
            if (kinds[k] == LinkKind.HAIRPIN) {
                hairpins++;
            }
        }
        double elbowMin = Math.max(PITCH_MIN, 2 * elbowRMin(tier));
        double[] lo = new double[n];
        double[] hi = new double[n];
        double[] p = new double[n];
        for (int k = 0; k < n; k++) {
            switch (kinds[k]) {
                case HAIRPIN -> {
                    lo[k] = Math.max(PITCH_MIN, 2 * tier.hairRMin);
                    hi[k] = Math.min(PITCH_MAX, 2 * tier.hairRMax);
                }
                case BULB -> {
                    lo[k] = Math.max(PITCH_MIN, bulbDrop(BULB_R_MIN));
                    hi[k] = Math.min(PITCH_MAX, bulbDrop(BULB_R_MAX));
                }
                default -> {
                    lo[k] = elbowMin;
                    hi[k] = PITCH_MAX;
                }
            }
            if (lo[k] > hi[k]) {
                return null;
            }
            p[k] = r.nextDouble(lo[k], hi[k]);
        }
        // make them add up: share the difference by each link's room in that direction
        for (int pass = 0; pass < 6; pass++) {
            double diff = total - Arrays.stream(p).sum();
            if (Math.abs(diff) < 1e-6) {
                break;
            }
            double room = 0;
            for (int k = 0; k < n; k++) {
                room += diff > 0 ? hi[k] - p[k] : p[k] - lo[k];
            }
            if (room < 1e-9) {
                return null;
            }
            for (int k = 0; k < n; k++) {
                double mine = diff > 0 ? hi[k] - p[k] : p[k] - lo[k];
                p[k] += diff * mine / room;
                p[k] = Math.max(lo[k], Math.min(hi[k], p[k]));
            }
        }
        if (Math.abs(total - Arrays.stream(p).sum()) > 1e-6) {
            return null;
        }
        Link[] out = new Link[n];
        for (int k = 0; k < n; k++) {
            out[k] = switch (kinds[k]) {
                case HAIRPIN -> new Link(LinkKind.HAIRPIN, p[k] / 2, 0, p[k] / 2);
                case BULB -> new Link(LinkKind.BULB, bulbRadius(p[k]), 0, bulbRadius(p[k]));
                default -> elbows(r, tier, p[k]);
            };
        }
        return out;
    }

    /** The least radius an elbow's arc may have: the tier's bends, but never under 30. */
    static double elbowRMin(MountainTier tier) {
        return Math.max(30, tier.bendRMin);
    }

    /** Elbows dropping {@code pitch}: two arcs (the second no tighter than the first, so it is carried) and the plunge. */
    static Link elbows(GenRandom r, MountainTier tier, double pitch) {
        double lo = elbowRMin(tier);
        // a plunge long enough for a brake drop where the pitch allows (its second arc is then entered after
        // the lip), else a short one the second arc, at least as wide as the first, carries (§5.2's link)
        double lipPlunge = MountainTier.runUp(tier.width) + MountainTier.landing(2, false) + 2;
        double r1;
        double r2;
        if (pitch - 2 * lo >= lipPlunge && r.chance(0.7)) {
            double arcs = pitch - lipPlunge - r.nextDouble(0, Math.max(0, pitch - 2 * lo - lipPlunge));
            r1 = r.nextDouble(lo, Math.max(lo, arcs / 2));
            r2 = arcs - r1;
        } else {
            double plunge = r.nextDouble(0, Math.min(SHORT_PLUNGE, Math.max(0, pitch - 2 * lo)));
            double arcs = pitch - plunge;
            r1 = r.nextDouble(lo, Math.max(lo, arcs / 2));
            r2 = arcs - r1;
        }
        double plunge = Math.max(0, pitch - r1 - r2);
        return new Link(LinkKind.ELBOWS, r1, plunge, pitch - r1 - plunge);
    }
}
