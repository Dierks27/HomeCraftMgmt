package com.dierks.homecraft.games.gen.dropper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A witness's input program (EVENTS-DROPPER-SPEC §B.1.5 step 2): what a player holds, tick by tick,
 * from the walk-off to the splash. Walk-air only, never a sprint.
 *
 * <p>It is a list of segments, each a compass direction held for some ticks (or no key at all),
 * the last held until the end: {@code "S7 -*"} is "keep walking south for 7 ticks, then let go". Up
 * is north (-z), as on a map. This text goes in the plan's summary, so an admin can read the proof
 * of a level ({@code /hcm games gen plan}) and the validator can replay it from the plan alone.
 *
 * <p><b>Input changes.</b> A player walking off is already holding the key toward the shaft
 * ("forward"), so a change is any tick whose input differs from the tick before, counting from
 * forward: {@code "S*"} off a south-facing ledge is no change at all, {@code "S7 -*"} one. The
 * tier limits them and says how early they must come (§B.1.3): that is what keeps a level "one real
 * choice, then a smooth line".
 */
public record DropProgram(List<Segment> segments) {

    /** The compass: no key, then the eight directions clockwise from north. */
    public enum Dir {
        NONE("-", 0, 0), N("N", 0, -1), NE("NE", 1, -1), E("E", 1, 0), SE("SE", 1, 1), S("S", 0, 1),
        SW("SW", -1, 1), W("W", -1, 0), NW("NW", -1, -1);

        private final String word;
        private final double ux;
        private final double uz;

        Dir(String word, int dx, int dz) {
            this.word = word;
            double len = Math.sqrt(dx * dx + dz * dz);
            this.ux = len == 0 ? 0 : dx / len;
            this.uz = len == 0 ? 0 : dz / len;
        }

        public String word() {
            return word;
        }

        /** The input vector: a unit vector, or zero for no key. */
        public double[] input() {
            return new double[]{ux, uz};
        }

        public double ux() {
            return ux;
        }

        public double uz() {
            return uz;
        }

        /** The direction pointing the other way. */
        public Dir opposite() {
            return this == NONE ? NONE : values()[(ordinal() - 1 + 4) % 8 + 1];
        }

        /** The eight directions, clockwise from north. */
        public static List<Dir> compass() {
            return List.of(N, NE, E, SE, S, SW, W, NW);
        }

        /** The direction of a unit step (dx, dz) along an axis or a diagonal, or {@code null}. */
        public static Dir of(int dx, int dz) {
            for (Dir d : values()) {
                if (d != NONE && Math.round(Math.signum(d.ux)) == Integer.signum(dx)
                        && Math.round(Math.signum(d.uz)) == Integer.signum(dz)) {
                    return d;
                }
            }
            return null;
        }

        /** The direction called {@code word} (any case), or {@code null}. */
        public static Dir of(String word) {
            for (Dir d : values()) {
                if (d.word.equalsIgnoreCase(word)) {
                    return d;
                }
            }
            return null;
        }
    }

    /**
     * One segment: {@code dir} held for {@code ticks} ticks; {@code ticks} is ignored on the last
     * segment, which is held until the end.
     */
    public record Segment(Dir dir, int ticks) {

        public Segment {
            if (dir == null) {
                throw new IllegalArgumentException("a segment needs its direction");
            }
            ticks = Math.max(0, ticks);
        }
    }

    public DropProgram {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("a program has at least one segment");
        }
        segments = List.copyOf(segments);
    }

    /** A program of the given segments; the last one's ticks don't matter. */
    public static DropProgram of(Segment... segments) {
        return new DropProgram(List.of(segments));
    }

    /** "Walk off and let go": forward for {@code ticks} ticks, then nothing. */
    public static DropProgram coast(Dir forward, int ticks) {
        return of(new Segment(forward, ticks), new Segment(Dir.NONE, 0));
    }

    /** The direction held on tick {@code t} (0 = the first tick of the fall). */
    public Dir at(int t) {
        int start = 0;
        for (int i = 0; i < segments.size() - 1; i++) {
            Segment s = segments.get(i);
            if (t < start + s.ticks()) {
                return s.dir();
            }
            start += s.ticks();
        }
        return segments.get(segments.size() - 1).dir();
    }

    /** The ticks at which the input changes, counting from {@code forward} before tick 0. */
    public List<Integer> changeTicks(Dir forward) {
        List<Integer> out = new ArrayList<>();
        Dir prev = forward;
        int t = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            boolean last = i == segments.size() - 1;
            if (!last && s.ticks() == 0) {
                continue;
            }
            if (s.dir() != prev) {
                out.add(t);
                prev = s.dir();
            }
            t += last ? 0 : s.ticks();
        }
        return out;
    }

    /** How many times the input changes, counting from {@code forward}. */
    public int changes(Dir forward) {
        return changeTicks(forward).size();
    }

    /** The tick of the last change, or -1 when there is none. */
    public int lastChange(Dir forward) {
        List<Integer> c = changeTicks(forward);
        return c.isEmpty() ? -1 : c.get(c.size() - 1);
    }

    /**
     * The same program mirrored across the line straight out from the ledge: every key's sideways part
     * swapped, so the path is the same shape on the other side.
     */
    public DropProgram mirrored(Dir forward) {
        boolean alongZ = forward == Dir.N || forward == Dir.S;
        List<Segment> out = new ArrayList<>();
        for (Segment s : segments) {
            Dir d = s.dir();
            if (d != Dir.NONE) {
                int dx = (int) Math.round(Math.signum(d.ux()));
                int dz = (int) Math.round(Math.signum(d.uz()));
                d = alongZ ? Dir.of(-dx, dz) : Dir.of(dx, -dz);
            }
            out.add(new Segment(d, s.ticks()));
        }
        return new DropProgram(out);
    }

    /**
     * The same keys in the fewest segments: a segment of no ticks (other than the last) is dropped,
     * and a segment holding the key the one before it held is merged into it. {@code "S8 S*"} is
     * {@code "S*"}. What it holds on every tick, and so its changes, stay exactly the same.
     */
    public DropProgram normalised() {
        List<Segment> out = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            boolean last = i == segments.size() - 1;
            if (!last && s.ticks() == 0) {
                continue;
            }
            if (!out.isEmpty() && out.get(out.size() - 1).dir() == s.dir()) {
                Segment prev = out.remove(out.size() - 1);
                out.add(new Segment(s.dir(), last ? 0 : prev.ticks() + s.ticks()));
                continue;
            }
            out.add(last ? new Segment(s.dir(), 0) : s);
        }
        return new DropProgram(out);
    }

    /** It as a controller for {@link DropRun}. */
    public DropRun.Controller controller() {
        return (tick, body) -> at(tick).input();
    }

    /** The text form: {@code "S7 SE3 -*"}. */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(s.dir().word()).append(i == segments.size() - 1 ? "*" : String.valueOf(s.ticks()));
        }
        return sb.toString();
    }

    /** The program {@code text} writes ({@link #encode}), or {@code null} when it isn't one. */
    public static DropProgram parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] words = text.trim().split("\\s+");
        List<Segment> out = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            String w = words[i].toUpperCase(Locale.ROOT);
            boolean last = i == words.length - 1;
            int cut = 0;
            while (cut < w.length() && (Character.isLetter(w.charAt(cut)) || w.charAt(cut) == '-')) {
                cut++;
            }
            Dir d = Dir.of(w.substring(0, cut));
            String rest = w.substring(cut);
            if (d == null) {
                return null;
            }
            if (last) {
                if (!rest.equals("*")) {
                    return null;
                }
                out.add(new Segment(d, 0));
            } else {
                try {
                    int ticks = Integer.parseInt(rest);
                    if (ticks < 0 || ticks > DropSim.MAX_TICKS) {
                        return null;
                    }
                    out.add(new Segment(d, ticks));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return new DropProgram(out);
    }

    @Override
    public String toString() {
        return encode();
    }
}
