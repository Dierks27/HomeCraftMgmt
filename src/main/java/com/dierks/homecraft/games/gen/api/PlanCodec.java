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
 * <p><b>Versioned.</b> The payload starts with {@code HCMP} and a version byte ({@value #VERSION});
 * a reader never guesses at a version it doesn't know. The stored form is the payload gzipped
 * ({@link #encode}); the payload itself is pinned by golden bytes, and so is decoding a fixed gzip
 * blob (a compressor may write other bytes for the same payload, but every gzip reader reads them
 * the same).
 *
 * <p><b>Never throws on junk.</b> {@link #decode} answers {@link Read}: a plan, or why the row
 * can't be read (not gzip, too big, the wrong magic or version, cut short, trailing bytes, a value
 * no plan can hold, or blocks that don't match the stored hash). An unreadable row is reported,
 * never built. Pure: no server, no Bukkit.
 */
public final class PlanCodec {

    /** The payload version this writes and reads. */
    public static final int VERSION = 1;
    /** An archived plan is never inflated past this (a real one is a few KB). */
    public static final int MAX_BYTES = 8 << 20;
    /** No list in a plan is longer than this. */
    static final int MAX_COUNT = 1_000_000;

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

    /** The stored form: the payload, gzipped. */
    public static byte[] encode(Plan plan) {
        byte[] raw = payload(plan);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 3 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        } catch (IOException e) {
            throw new IllegalStateException("a byte array can't fail to write", e);
        }
        return out.toByteArray();
    }

    /** The version-{@value #VERSION} payload (what {@link #encode} gzips). */
    public static byte[] payload(Plan p) {
        if (p == null || p.half() == null || p.course() == null) {
            throw new IllegalArgumentException("only a whole plan is archived");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        try (DataOutputStream o = new DataOutputStream(bytes)) {
            o.write(MAGIC);
            o.writeByte(VERSION);
            o.writeUTF(p.slot());
            o.writeInt(p.algo());
            o.writeLong(p.seed());
            box(o, p.half());
            o.writeInt(p.palette().size());
            for (String s : p.palette()) {
                o.writeUTF(s);
            }
            o.writeInt(p.ops().size());
            for (BlockOp op : p.ops()) {
                o.writeInt(op.x());
                o.writeInt(op.y());
                o.writeInt(op.z());
                o.writeShort(op.state());
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
            if (version != VERSION) {
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
            List<BlockOp> ops = new ArrayList<>();
            for (int i = count(in); i > 0; i--) {
                ops.add(new BlockOp(in.readInt(), in.readInt(), in.readInt(), in.readShort()));
            }
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
