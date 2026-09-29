package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * One layout as pure data (GEN-SPEC §0.2 R2, §4.0): every block, every sign and the course, made
 * from (seed, day, slot) with no server, checked by an independent validator before a single
 * block is set, then built as "make this half equal to the plan" — air wherever it has no op.
 *
 * <p>The {@link #hash} names the layout: a SHA-256 over the canonical ops, signs and course
 * ({@link #hash(List, List, List, PlannedCourse)}), so the same blocks and course hash the same
 * whatever order a planner emitted them in and however it numbered its palette. Golden tests pin
 * it for three seeds per planner.
 *
 * @param slot      the slot id
 * @param algo      the planner's version
 * @param seed      the seed it was made from
 * @param half      the half it is for; every op and sign is inside it
 * @param palette   the block-data texts the ops index ({@link Palette#ALLOWED} blocks only)
 * @param ops       the blocks
 * @param signs     the signs (their blocks are placed after the solid ops)
 * @param keepClear boxes that must be air apart from the plan's own blocks (headroom, flight tubes)
 * @param course    the course the engine runs
 * @param summary   admin lines for {@code /hcm games gen plan} (no seeds of other days, no secret)
 * @param work      the counted work it took (shots, nodes)
 * @param hash      12 hex digits of the canonical SHA-256
 */
public record Plan(String slot, int algo, long seed, Box half, List<String> palette, List<BlockOp> ops,
                   List<SignText> signs, List<Box> keepClear, PlannedCourse course, List<String> summary,
                   long work, String hash) {

    public Plan {
        palette = List.copyOf(palette == null ? List.of() : palette);
        ops = List.copyOf(ops == null ? List.of() : ops);
        signs = List.copyOf(signs == null ? List.of() : signs);
        keepClear = List.copyOf(keepClear == null ? List.of() : keepClear);
        summary = List.copyOf(summary == null ? List.of() : summary);
        hash = hash == null ? "" : hash;
    }

    /** A plan with its {@link #hash} worked out from the rest. */
    public static Plan of(String slot, int algo, long seed, Box half, List<String> palette, List<BlockOp> ops,
                          List<SignText> signs, List<Box> keepClear, PlannedCourse course, List<String> summary,
                          long work) {
        return new Plan(slot, algo, seed, half, palette, ops, signs, keepClear, course, summary, work,
                hash(palette, ops, signs, course));
    }

    /** The block-data text an op places. */
    public String blockOf(BlockOp op) {
        return palette.get(op.state());
    }

    /**
     * The layout's hash: 12 hex digits of a SHA-256 over the ops (by position, each as its block
     * text), the signs (by position) and the course's geometry. World, name, switches, rev and tag
     * are left out: they aren't the layout.
     */
    public static String hash(List<String> palette, List<BlockOp> ops, List<SignText> signs, PlannedCourse course) {
        StringBuilder sb = new StringBuilder();
        List<BlockOp> sortedOps = new ArrayList<>(ops == null ? List.of() : ops);
        sortedOps.sort(Comparator.comparingInt(BlockOp::x).thenComparingInt(BlockOp::y).thenComparingInt(BlockOp::z)
                .thenComparing(op -> block(palette, op)));
        for (BlockOp op : sortedOps) {
            sb.append("op ").append(op.x()).append(' ').append(op.y()).append(' ').append(op.z()).append(' ')
                    .append(block(palette, op)).append('\n');
        }
        List<SignText> sortedSigns = new ArrayList<>(signs == null ? List.of() : signs);
        sortedSigns.sort(Comparator.comparingInt(SignText::x).thenComparingInt(SignText::y)
                .thenComparingInt(SignText::z).thenComparing(SignText::blockData)
                .thenComparing(s -> String.join("|", s.lines())));
        for (SignText s : sortedSigns) {
            sb.append("sign ").append(s.x()).append(' ').append(s.y()).append(' ').append(s.z()).append(' ')
                    .append(s.blockData()).append(' ').append(String.join("|", s.lines())).append('\n');
        }
        sb.append(canonical(course));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime ships SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The course's geometry as stable text: numbers through Double/Float.toString, in course order. */
    static String canonical(PlannedCourse course) {
        StringBuilder sb = new StringBuilder();
        if (course instanceof PlannedTrial t) {
            Course c = t.course();
            sb.append("trial ").append(c.kind().id()).append(" ref ").append(t.refMs()).append('\n');
            if (c.start() != null) {
                Course.Spot s = c.start();
                sb.append("start ").append(s.x()).append(' ').append(s.y()).append(' ').append(s.z()).append(' ')
                        .append(s.yaw()).append(' ').append(s.pitch()).append('\n');
            }
            for (Course.Mark m : c.checkpoints()) {
                sb.append("cp ").append(mark(m)).append('\n');
            }
            if (c.finish() != null) {
                sb.append("finish ").append(mark(c.finish())).append('\n');
            }
            sb.append("fall ").append(c.fallY()).append(" min ").append(c.minSeconds()).append('\n');
        } else if (course instanceof PlannedGolf g) {
            sb.append("golf\n");
            for (GolfCourse.Hole h : g.course().holes()) {
                sb.append("hole par ").append(h.par());
                if (h.tee() != null) {
                    sb.append(" tee ").append(h.tee().x()).append(' ').append(h.tee().y()).append(' ')
                            .append(h.tee().z()).append(' ').append(h.tee().yaw());
                }
                sb.append(" cup ").append(spot(h.cup())).append(" c1 ").append(spot(h.corner1())).append(" c2 ")
                        .append(spot(h.corner2())).append('\n');
            }
        }
        return sb.toString();
    }

    private static String block(List<String> palette, BlockOp op) {
        return palette != null && op.state() < palette.size() ? palette.get(op.state()) : "#" + op.state();
    }

    private static String mark(Course.Mark m) {
        return m.x() + " " + m.y() + " " + m.z() + " " + m.radius();
    }

    private static String spot(GolfCourse.Spot s) {
        return s == null ? "-" : s.x() + " " + s.y() + " " + s.z();
    }
}
