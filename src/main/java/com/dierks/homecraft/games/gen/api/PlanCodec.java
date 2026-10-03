package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A plan as the archive keeps it (GEN-SPEC-KEEP §1): every block, sign and keep-clear box, and the
 * whole course (start, checkpoints, finish and fall height; or every hole with its par, bounds,
 * attempt and witness line), so a past course is rebuilt EXACTLY from its row — never by running
 * the planner again, which a newer generator version would change.
 *
 * <p><b>Versioned.</b> The payload starts with {@code HCMP} and a version byte; a reader never
 * guesses at a version it doesn't know. The stored form is the payload gzipped ({@link #encode});
 * the payload itself is pinned by golden bytes, and so is decoding a fixed gzip blob (a compressor
 * may write other bytes for the same payload, but every gzip reader reads them the same). Both
 * versions are read forever: every archive row, kept course, recall and {@code gfresh:} board
 * written before version 2 decodes exactly as it always did.
 *
 * <p><b>Version 1</b> ({@value #V1}) writes each block as 14 bytes (x, y, z as ints, the palette
 * index as a short). A Mountain Run v2 plan is some 350,000 blocks: 4.9 MB, ~1.2-1.6 MB gzipped a
 * course, ~70 MB a year of archive (MOUNTAIN-V2-SPEC §3.6).
 *
 * <p><b>Version 2</b> ({@value #VERSION}, MOUNTAIN-V2-SPEC §13.5) is version 1 with only the
 * blocks written differently, as <em>columns</em> and <em>runs</em>, in exactly the plan's order:
 * <ul>
 *   <li>a column is a stretch of consecutive blocks with the same x and z: its x and z as
 *       zigzag varints, the change from the previous column's (the first from 0, 0), then how many
 *       runs it holds;</li>
 *   <li>a run is a stretch of consecutive blocks in a column, each one above the last with the same
 *       palette index: its first y as a zigzag varint (the change from the previous run's last y,
 *       or for a column's first run from the previous column's first y, the first from 0), its
 *       length and its palette index as varints.</li>
 * </ul>
 * A block count (an int, as version 1) comes first, and the columns are read until exactly that
 * many blocks are back.
 *
 * <p><b>Why it keeps the order, and when it is written.</b> {@link Plan#hash} sorts the blocks, so
 * the order a planner emits is free for the hash (F30), and the builder writes every pass bottom-up
 * anyway. But the order is not free everywhere: a plan with two blocks at one spot is judged by its
 * last one in some validators ({@code PlanSurface}, {@code Pools}) and refused by others, and the
 * builder names the first blocks that differ in plan order. So version 2 does NOT sort: the deltas
 * are signed, a column or run is simply cut wherever the next block doesn't continue it, and so
 * ANY sequence of blocks (unsorted, repeated, anywhere in the int range, any palette index) comes
 * back as exactly the same list: the same blocks, the same order, the same hash. That is why
 * {@code decode(encode(plan))} equals the plan for every plan, and the encoder needs no "is it
 * sorted" test. What sorting buys is size: a planner that emits its blocks in (x, z, y) order (as
 * MOUNTAIN-V2-SPEC §3.6 asks of the Mountain Run's) gets one column per x, z and one run per stretch
 * of a block, so a 350,000-block mountain is ~1.6 MB raw and ~0.2-0.5 MB gzipped (version 1: ~1.1 MB
 * and more), some 20-25 MB a year. A plan in any other order is still exact, only less compact.
 *
 * <p>{@link #encode} and {@link #payload} write version 2 for every plan, unless its blocks would
 * take more bytes than version 1's 14 an op (only blocks scattered across the whole int range do:
 * no planner's), when they write version 1; so a row is never bigger than version 1 would have made
 * it, and never further from {@link #MAX_BYTES}. {@link #encode(Plan, int)} writes a version asked
 * for by name (golden tests pin version 1's bytes through it).
 *
 * <p><b>Never throws on junk.</b> {@link #decode} answers {@link Read}: a plan, or why the row
 * can't be read (not gzip, too big, the wrong magic or version, cut short, trailing bytes, a value
 * no plan can hold, or blocks that don't match the stored hash). Every list and run is bounded by
 * {@link #MAX_COUNT} before anything is made for it, and every column and run gives at least one
 * block, so cut-short or corrupted bytes fail at once and never make a reader allocate or loop
 * without bound. An unreadable row is reported, never built. Pure: no server, no Bukkit.
 */
public final class PlanCodec {

    /** The payload version {@link #encode} writes: blocks as columns of runs. */
    public static final int VERSION = 2;
    /** The first payload version: 14 bytes a block. Still read, and written when asked for by name. */
    public static final int V1 = 1;
    /** An archived plan is never inflated past this (a Mountain Run v2 is ~1-2 MB in version 2). */
    public static final int MAX_BYTES = 8 << 20;
    /** No list in a plan is longer than this. */
    static final int MAX_COUNT = 1_000_000;
    /** The bytes a version-1 block takes: x, y, z and a palette index. */
    static final int V1_OP_BYTES = 14;
    /** The longest varint version 2 writes: a zigzag delta between two ints is 34 bits. */
    static final int MAX_VARINT_BYTES = 5;

    private static final byte[] MAGIC = {'H', 'C', 'M', 'P'};
    private static final byte TRIAL = 1;
    private static final byte GOLF = 2;

    /**
     * What reading a stored plan gave.
     *
     * @param plan    the plan, or {@code null} when it can't be read
     * @param problem why it can't be read (admin words), or {@code null}
     */
    public record Read(Plan plan, String problem) {

        /** Whether a plan came back. */
        public boolean ok() {
            return plan != null;
        }

        static Read bad(String why) {
            return new Read(null, why);
        }
    }

    private PlanCodec() {
    }

    // ---- writing ------------------------------------------------------------------------------------

    /**
     * The stored form: the payload, gzipped. Version 2, unless the plan's blocks take fewer bytes in
     * version 1 ({@link #payload(Plan)}).
     */
    public static byte[] encode(Plan plan) {
        return gzip(payload(plan));
    }

    /** The stored form in version {@code version} ({@link #V1} or {@link #VERSION}), gzipped. */
    public static byte[] encode(Plan plan, int version) {
        return gzip(payload(plan, version));
    }

    /**
     * The payload {@link #encode} gzips: version 2, or version 1 when the plan's blocks would take
     * more bytes in version 2 than version 1's 14 an op (blocks scattered across the int range).
     */
    public static byte[] payload(Plan p) {
        whole(p);
        byte[] columns = columns(p.ops());
        return columns.length <= 4L + (long) V1_OP_BYTES * p.ops().size() ? write(p, VERSION, columns)
                : write(p, V1, null);
    }

    /** The payload in version {@code version}: {@link #V1} or {@link #VERSION}. */
    public static byte[] payload(Plan p, int version) {
        whole(p);
        if (version == V1) {
            return write(p, V1, null);
        }
        if (version == VERSION) {
            return write(p, VERSION, columns(p.ops()));
        }
        throw new IllegalArgumentException("plan format " + version + " isn't one this writes");
    }

    private static void whole(Plan p) {
        if (p == null || p.half() == null || p.course() == null) {
            throw new IllegalArgumentException("only a whole plan is archived");
        }
    }

    private static byte[] gzip(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 3 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        } catch (IOException e) {
            throw new IllegalStateException("a byte array can't fail to write", e);
        }
        return out.toByteArray();
    }

    /** The payload: {@code columns} is version 2's block section ({@link #columns}), {@code null} for version 1. */
    private static byte[] write(Plan p, int version, byte[] columns) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096 + (columns == null
                ? V1_OP_BYTES * p.ops().size() : columns.length));
        try (DataOutputStream o = new DataOutputStream(bytes)) {
            o.write(MAGIC);
            o.writeByte(version);
            o.writeUTF(p.slot());
            o.writeInt(p.algo());
            o.writeLong(p.seed());
            box(o, p.half());
            o.writeInt(p.palette().size());
            for (String s : p.palette()) {
                o.writeUTF(s);
            }
            if (columns != null) {
                o.write(columns);
            } else {
                o.writeInt(p.ops().size());
                for (BlockOp op : p.ops()) {
                    o.writeInt(op.x());
                    o.writeInt(op.y());
                    o.writeInt(op.z());
                    o.writeShort(op.state());
                }
            }
            o.writeInt(p.signs().size());
            for (SignText s : p.signs()) {
                o.writeInt(s.x());
                o.writeInt(s.y());
                o.writeInt(s.z());
                o.writeUTF(s.blockData());
                o.writeByte(s.lines().size());
                for (String line : s.lines()) {
                    o.writeUTF(line);
                }
            }
            o.writeInt(p.keepClear().size());
            for (Box b : p.keepClear()) {
                box(o, b);
            }
            o.writeInt(p.summary().size());
            for (String line : p.summary()) {
                o.writeUTF(line);
            }
            o.writeLong(p.work());
            o.writeUTF(p.hash());
            if (p.course() instanceof PlannedTrial t) {
                o.writeByte(TRIAL);
                trial(o, t);
            } else if (p.course() instanceof PlannedGolf g) {
                o.writeByte(GOLF);
                golf(o, g);
            }
        } catch (IOException e) {
            throw new IllegalStateException("a byte array can't fail to write", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Version 2's block section: the count, then the blocks as columns of runs, in exactly their order
     * (see the class comment). Deltas are taken in {@code long}, so no pair of ints overflows.
     */
    static byte[] columns(List<BlockOp> ops) {
        int n = ops.size();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 + 3 * n);
        try (DataOutputStream o = new DataOutputStream(bytes)) {
            o.writeInt(n);
            long px = 0;
            long pz = 0;
            long py = 0;
            int i = 0;
            while (i < n) {
                BlockOp first = ops.get(i);
                int end = i + 1;
                while (end < n && ops.get(end).x() == first.x() && ops.get(end).z() == first.z()) {
                    end++;
                }
                zigzag(o, (long) first.x() - px);
                zigzag(o, (long) first.z() - pz);
                int runs = 0;
                for (int k = i; k < end; k = runEnd(ops, k, end)) {
                    runs++;
                }
                varint(o, runs);
                long base = py;
                for (int k = i; k < end; ) {
                    int e = runEnd(ops, k, end);
                    BlockOp start = ops.get(k);
                    zigzag(o, (long) start.y() - base);
                    varint(o, e - k);
                    varint(o, start.state());
                    base = (long) start.y() + (e - k) - 1;
                    k = e;
                }
                px = first.x();
                pz = first.z();
                py = first.y();
                i = end;
            }
        } catch (IOException e) {
            throw new IllegalStateException("a byte array can't fail to write", e);
        }
        return bytes.toByteArray();
    }

    /** Where the run starting at {@code k} ends (exclusive), inside the column ending at {@code end}. */
    private static int runEnd(List<BlockOp> ops, int k, int end) {
        int e = k + 1;
        while (e < end && ops.get(e).state() == ops.get(e - 1).state()
                && (long) ops.get(e).y() == (long) ops.get(e - 1).y() + 1) {
            e++;
        }
        return e;
    }

    /** An unsigned varint: 7 bits a byte, low first, the top bit set on every byte but the last. */
    private static void varint(DataOutputStream o, long v) throws IOException {
        while ((v & ~0x7FL) != 0) {
            o.writeByte((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.writeByte((int) v);
    }

    /** A signed varint: zigzag (0, -1, 1, -2 ... as 0, 1, 2, 3 ...), so a small change of either sign is one byte. */
    private static void zigzag(DataOutputStream o, long v) throws IOException {
        varint(o, (v << 1) ^ (v >> 63));
    }

    private static void trial(DataOutputStream o, PlannedTrial t) throws IOException {
        Course c = t.course();
        o.writeUTF(c.id());
        o.writeUTF(c.kind().id());
        o.writeUTF(c.name());
        o.writeUTF(c.tier().id());
        o.writeUTF(c.world());
        o.writeBoolean(c.start() != null);
        if (c.start() != null) {
            o.writeDouble(c.start().x());
            o.writeDouble(c.start().y());
            o.writeDouble(c.start().z());
            o.writeFloat(c.start().yaw());
            o.writeFloat(c.start().pitch());
        }
        o.writeInt(c.checkpoints().size());
        for (Course.Mark m : c.checkpoints()) {
            mark(o, m);
        }
        o.writeBoolean(c.finish() != null);
        if (c.finish() != null) {
            mark(o, c.finish());
        }
        o.writeBoolean(c.fallY() != null);
        if (c.fallY() != null) {
            o.writeDouble(c.fallY());
        }
        o.writeBoolean(c.minSeconds() != null);
        if (c.minSeconds() != null) {
            o.writeInt(c.minSeconds());
        }
        o.writeBoolean(c.enabled());
        o.writeBoolean(c.pinned());
        o.writeInt(c.rev());
        o.writeLong(t.refMs());
    }

    private static void golf(DataOutputStream o, PlannedGolf g) throws IOException {
        GolfCourse c = g.course();
        o.writeUTF(c.id());
        o.writeUTF(c.name());
        o.writeUTF(c.world());
        o.writeBoolean(c.enabled());
        o.writeInt(c.rev());
        o.writeInt(c.holes().size());
        for (GolfCourse.Hole h : c.holes()) {
            o.writeBoolean(h.tee() != null);
            if (h.tee() != null) {
                o.writeDouble(h.tee().x());
                o.writeDouble(h.tee().y());
                o.writeDouble(h.tee().z());
                o.writeFloat(h.tee().yaw());
            }
            spot(o, h.cup());
            o.writeInt(h.par());
            spot(o, h.corner1());
            spot(o, h.corner2());
        }
        ints(o, g.attempts());
        o.writeInt(g.witness().size());
        for (List<Putt> line : g.witness()) {
            o.writeInt(line.size());
            for (Putt p : line) {
                o.writeFloat(p.yaw());
                o.writeInt(p.power());
            }
        }
        ints(o, g.expert());
        ints(o, g.kid());
    }

    private static void box(DataOutputStream o, Box b) throws IOException {
        o.writeInt(b.minX());
        o.writeInt(b.minY());
        o.writeInt(b.minZ());
        o.writeInt(b.maxX());
        o.writeInt(b.maxY());
        o.writeInt(b.maxZ());
    }

    private static void mark(DataOutputStream o, Course.Mark m) throws IOException {
        o.writeDouble(m.x());
        o.writeDouble(m.y());
        o.writeDouble(m.z());
        o.writeDouble(m.radius());
    }

    private static void spot(DataOutputStream o, GolfCourse.Spot s) throws IOException {
        o.writeBoolean(s != null);
        if (s != null) {
            o.writeInt(s.x());
            o.writeInt(s.y());
            o.writeInt(s.z());
        }
    }

    private static void ints(DataOutputStream o, List<Integer> list) throws IOException {
        o.writeInt(list.size());
        for (int v : list) {
            o.writeInt(v);
        }
    }

    // ---- reading ------------------------------------------------------------------------------------

    /**
     * What a stored plan says before its blocks: the slot it was made for, its planner version, its seed
     * and its half.
     */
    public record Head(String slot, int algo, long seed, Box half) {
    }

    /**
     * A stored plan's {@link Head}, inflating only its first few hundred bytes (not its blocks, and
     * nothing is checked past the half: {@link #decode} is the proof a row can be built), or
     * {@code null} when even that can't be read. Never throws. For a quick "is it too big to keep?"
     * before a Mountain Run's whole row is read.
     */
    public static Head head(byte[] stored) {
        if (stored == null || stored.length == 0) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(stored)))) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            int version = in.readUnsignedByte();
            if (!java.util.Arrays.equals(magic, MAGIC) || (version != V1 && version != VERSION)) {
                return null;
            }
            return new Head(in.readUTF(), in.readInt(), in.readLong(), box(in));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A stored plan read back, or why it can't be. Never throws. */
    public static Read decode(byte[] stored) {
        if (stored == null || stored.length == 0) {
            return Read.bad("no plan was stored");
        }
        byte[] raw;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(stored))) {
            raw = in.readNBytes(MAX_BYTES + 1);
        } catch (IOException | RuntimeException e) {
            return Read.bad("the stored plan isn't gzip (" + e.getClass().getSimpleName() + ")");
        }
        if (raw.length > MAX_BYTES) {
            return Read.bad("the stored plan is too big to be one");
        }
        return decodePayload(raw);
    }

    /** A payload ({@link #payload}) read back, or why it can't be. Never throws. */
    public static Read decodePayload(byte[] raw) {
        if (raw == null) {
            return Read.bad("no plan was stored");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                return Read.bad("it isn't an archived plan");
            }
            int version = in.readUnsignedByte();
            if (version != V1 && version != VERSION) {
                return Read.bad("it was written in plan format " + version + ", which this version can't read");
            }
            String slot = in.readUTF();
            int algo = in.readInt();
            long seed = in.readLong();
            Box half = box(in);
            List<String> palette = new ArrayList<>();
            for (int i = count(in); i > 0; i--) {
                palette.add(in.readUTF());
            }
            List<BlockOp> ops = version == V1 ? opsV1(in) : columns(in);
            List<SignText> signs = new ArrayList<>();
            for (int i = count(in); i > 0; i--) {
                int x = in.readInt();
                int y = in.readInt();
                int z = in.readInt();
                String data = in.readUTF();
                List<String> lines = new ArrayList<>();
                for (int n = in.readUnsignedByte(); n > 0; n--) {
                    lines.add(in.readUTF());
                }
                signs.add(new SignText(x, y, z, data, lines));
            }
            List<Box> keepClear = new ArrayList<>();
            for (int i = count(in); i > 0; i--) {
                keepClear.add(box(in));
            }
            List<String> summary = new ArrayList<>();
            for (int i = count(in); i > 0; i--) {
                summary.add(in.readUTF());
            }
            long work = in.readLong();
            String hash = in.readUTF();
            PlannedCourse course = switch (in.readByte()) {
                case TRIAL -> trial(in);
                case GOLF -> golf(in);
                default -> throw new IllegalArgumentException("its course is of no known kind");
            };
            if (in.read() != -1) {
                return Read.bad("it has bytes after its end");
            }
            for (BlockOp op : ops) {
                if (op.state() >= palette.size()) {
                    return Read.bad("a block has no palette entry");
                }
            }
            if (!hash.equals(Plan.hash(palette, ops, signs, course))) {
                return Read.bad("its blocks don't match its hash");
            }
            return new Read(new Plan(slot, algo, seed, half, palette, ops, signs, keepClear, course, summary, work,
                    hash), null);
        } catch (IOException e) {
            return Read.bad("it is cut short");
        } catch (RuntimeException e) {
            return Read.bad("it holds something no plan can (" + e.getMessage() + ")");
        }
    }

    private static List<BlockOp> opsV1(DataInputStream in) throws IOException {
        List<BlockOp> ops = new ArrayList<>();
        for (int i = count(in); i > 0; i--) {
            ops.add(new BlockOp(in.readInt(), in.readInt(), in.readInt(), in.readShort()));
        }
        return ops;
    }

    /**
     * Version 2's block section read back ({@link #columns(List)}): exactly the count it starts with.
     * Every column holds at least one run and every run at least one block, and neither may hold more
     * than the blocks still to come, so the loop ends within the count and nothing is made for a
     * number the bytes merely claim.
     */
    static List<BlockOp> columns(DataInputStream in) throws IOException {
        int n = count(in);
        List<BlockOp> ops = new ArrayList<>(n);
        long px = 0;
        long pz = 0;
        long py = 0;
        while (ops.size() < n) {
            long x = coordinate(px + unzig(varint(in)));
            long z = coordinate(pz + unzig(varint(in)));
            long runs = varint(in);
            if (runs < 1 || runs > n - ops.size()) {
                throw new IllegalArgumentException("a column of " + runs + " runs");
            }
            long first = 0;
            long base = py;
            for (long r = 0; r < runs; r++) {
                long y = coordinate(base + unzig(varint(in)));
                long length = varint(in);
                if (length < 1 || length > n - ops.size()) {
                    throw new IllegalArgumentException("a run of " + length + " blocks");
                }
                long state = varint(in);
                if (state > Short.MAX_VALUE) {
                    throw new IllegalArgumentException("a palette index of " + state);
                }
                long last = coordinate(y + length - 1);
                for (long at = y; at <= last; at++) {
                    ops.add(new BlockOp((int) x, (int) at, (int) z, (short) state));
                }
                if (r == 0) {
                    first = y;
                }
                base = last;
            }
            px = x;
            pz = z;
            py = first;
        }
        return ops;
    }

    /** A varint ({@link #varint(DataOutputStream, long)}) of at most {@value #MAX_VARINT_BYTES} bytes. */
    private static long varint(DataInputStream in) throws IOException {
        long v = 0;
        for (int i = 0; i < MAX_VARINT_BYTES; i++) {
            int b = in.readUnsignedByte();
            v |= (long) (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return v;
            }
        }
        throw new IllegalArgumentException("a number longer than any it writes");
    }

    private static long unzig(long v) {
        return (v >>> 1) ^ -(v & 1);
    }

    private static long coordinate(long v) {
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("a block at " + v);
        }
        return v;
    }

    private static PlannedTrial trial(DataInputStream in) throws IOException {
        String id = in.readUTF();
        TrialKind kind = TrialKind.of(in.readUTF());
        if (kind == null) {
            throw new IllegalArgumentException("its course kind is unknown");
        }
        String name = in.readUTF();
        Tier tier = Tier.of(in.readUTF());
        String world = in.readUTF();
        Course.Spot start = null;
        if (in.readBoolean()) {
            start = new Course.Spot(in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat());
        }
        List<Course.Mark> cps = new ArrayList<>();
        for (int i = count(in); i > 0; i--) {
            cps.add(mark(in));
        }
        Course.Mark finish = in.readBoolean() ? mark(in) : null;
        Double fallY = in.readBoolean() ? in.readDouble() : null;
        Integer minSeconds = in.readBoolean() ? in.readInt() : null;
        boolean enabled = in.readBoolean();
        boolean pinned = in.readBoolean();
        int rev = in.readInt();
        long refMs = in.readLong();
        return new PlannedTrial(new Course(id, kind, name, tier, world, start, cps, finish, fallY, minSeconds, enabled,
                pinned, rev), refMs);
    }

    private static PlannedGolf golf(DataInputStream in) throws IOException {
        String id = in.readUTF();
        String name = in.readUTF();
        String world = in.readUTF();
        boolean enabled = in.readBoolean();
        int rev = in.readInt();
        List<GolfCourse.Hole> holes = new ArrayList<>();
        for (int i = count(in); i > 0; i--) {
            GolfCourse.Tee tee = null;
            if (in.readBoolean()) {
                tee = new GolfCourse.Tee(in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat());
            }
            GolfCourse.Spot cup = spot(in);
            int par = in.readInt();
            GolfCourse.Spot c1 = spot(in);
            GolfCourse.Spot c2 = spot(in);
            holes.add(new GolfCourse.Hole(tee, cup, par, c1, c2));
        }
        List<Integer> attempts = ints(in);
        List<List<Putt>> witness = new ArrayList<>();
        for (int i = count(in); i > 0; i--) {
            List<Putt> line = new ArrayList<>();
            for (int n = count(in); n > 0; n--) {
                line.add(new Putt(in.readFloat(), in.readInt()));
            }
            witness.add(line);
        }
        List<Integer> expert = ints(in);
        List<Integer> kid = ints(in);
        return new PlannedGolf(new GolfCourse(id, name, world, enabled, rev, holes), attempts, witness, expert, kid);
    }

    private static int count(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_COUNT) {
            throw new IllegalArgumentException("a list of " + n);
        }
        return n;
    }

    private static Box box(DataInputStream in) throws IOException {
        return new Box(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
    }

    private static Course.Mark mark(DataInputStream in) throws IOException {
        return new Course.Mark(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble());
    }

    private static GolfCourse.Spot spot(DataInputStream in) throws IOException {
        return in.readBoolean() ? new GolfCourse.Spot(in.readInt(), in.readInt(), in.readInt()) : null;
    }

    private static List<Integer> ints(DataInputStream in) throws IOException {
        List<Integer> out = new ArrayList<>();
        for (int i = count(in); i > 0; i--) {
            out.add(in.readInt());
        }
        return out;
    }
}
