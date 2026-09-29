package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.SignText;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Make this half equal to the plan" (GEN-SPEC §0.2 R3, §3.3 steps 4-6 and 8), a few blocks a tick.
 *
 * <p>There is no undo log and no list of blocks placed: the plan says what every block of the
 * half must be — its blocks where it has them, air everywhere else — so a build scans the half
 * chunk by chunk, writes only what differs, then scans again. A scan that finds nothing to change
 * is the proof the half is right. Because the goal is a state and not a list of steps, running it
 * again after any stop (a crash, a reload, a restart) simply finishes the job; building a half
 * that is already right writes nothing at all. The same job with no plan empties a half
 * (CLEAR_OLD, {@code clear}), and in {@link Mode#SCAN} it only counts what isn't air (a first
 * claim).
 *
 * <ol>
 *   <li><b>LOAD</b>: every chunk of the half, a few at a time, held with a ticket until
 *       {@link #release}.</li>
 *   <li><b>PASS</b>: per chunk, a snapshot (empty sections skipped) and the list of blocks that
 *       differ, written solids bottom-up, then signs (their text glowing and waxed). A write whose
 *       block meets a person (their box grown by one) waits and is tried again every tick; after
 *       {@value #STUCK_MS} ms {@link #stuckPeople} names who is in the way, to be moved.</li>
 *   <li><b>Water last, and gone first</b> (EVENTS-DROPPER-SPEC §B.1.9, a Dropper's sealed pools): a
 *       pass writes in three stages over the WHOLE half. First every water block the plan doesn't
 *       want is drained (while the chunks are read); then the solids and signs of every chunk;
 *       and only when those are all written, the planned water, bottom-up. So a pool is drained
 *       before its walls go (clearing a half), and filled only once every wall round it stands.
 *       A stop between two stages is just the next converge. With writes made without physics,
 *       sealed pools and the area guard's flow rules, water in a half never moves.</li>
 *   <li><b>VERIFY</b>: the next pass. None differ: {@link Phase#DONE}. Otherwise the pass wrote
 *       them, and up to {@value #MAX_PASSES} passes in all are made before it is
 *       {@link Phase#FAILED} naming the first five.</li>
 * </ol>
 * Every write goes through a {@link HalfWriter}, which refuses anything outside the half.
 */
public final class BuildJob {

    /** Converge (write) or only count. */
    public enum Mode {
        CONVERGE, SCAN
    }

    /** Where it is. */
    public enum Phase {
        LOAD, WORK, DONE, FAILED
    }

    /**
     * A pass's write stages (EVENTS-DROPPER-SPEC §B.1.9): unwanted water drained while the chunks are
     * read, then every solid and sign, then the planned water.
     */
    enum Stage {
        DRAIN, BODY, FILL
    }

    /** The converge and up to three rounds of verify-and-heal. */
    public static final int MAX_PASSES = 4;
    /** A write kept waiting by a person this long gets them moved. */
    public static final long STUCK_MS = 30_000L;
    /** A person's box is grown by this much before a write near them waits. */
    public static final double KEEP_OUT = 1.0;
    /** How many differing blocks a failure names. */
    public static final int NAMED = 5;

    /** One write: a block (maybe air) and, for a sign, its text. */
    private static final class Op {
        final int x;
        final int y;
        final int z;
        final String state;
        final List<String> sign;
        long since = -1;

        Op(int x, int y, int z, String state, List<String> sign) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.state = state;
            this.sign = sign;
        }

        String at() {
            return x + "," + y + "," + z;
        }
    }

    /** The plan's writes inside one chunk. */
    private static final class ChunkPlan {
        final List<Op> blocks = new ArrayList<>();
        final List<Op> signs = new ArrayList<>();
        final Set<Long> at = new HashSet<>();
    }

    private final WorldPort port;
    private final Box half;
    private final HalfWriter writer;
    private final Mode mode;
    private final List<int[]> chunks = new ArrayList<>();
    private final Map<Long, ChunkPlan> plan = new HashMap<>();
    private final Set<Long> ticketed = new LinkedHashSet<>();
    private final ArrayDeque<Op> pending = new ArrayDeque<>();
    private final List<Op> deferred = new ArrayList<>();
    /** This pass's solids, air and signs, written once every chunk is read and drained. */
    private final List<Op> body = new ArrayList<>();
    /** This pass's planned water, written once every solid is. */
    private final List<Op> fills = new ArrayList<>();
    /** Which of a pass's three write stages it is on (drain while reading, then body, then fill). */
    private Stage stage = Stage.DRAIN;
    private final List<String> named = new ArrayList<>();
    private final Set<UUID> stuck = new LinkedHashSet<>();
    private Phase phase = Phase.LOAD;
    private int nextLoad;
    private int inFlight;
    private int loaded;
    private boolean released;
    private int pass = 1;
    private int chunkIndex;
    private long passDiffs;
    private long totalDiffs;
    private String error;

    /**
     * @param plan what the half must hold, or {@code null} for nothing (empty it, or count what is there)
     * @throws IllegalArgumentException when a plan block or sign is outside the half or isn't a block
     */
    public BuildJob(WorldPort port, Box half, Plan plan, Mode mode) {
        this.port = port;
        this.half = half;
        this.writer = new HalfWriter(port, half);
        this.mode = mode;
        for (int cx = half.minX() >> 4; cx <= half.maxX() >> 4; cx++) {
            for (int cz = half.minZ() >> 4; cz <= half.maxZ() >> 4; cz++) {
                chunks.add(new int[]{cx, cz});
            }
        }
        if (plan != null) {
            index(plan);
        }
    }

    private void index(Plan p) {
        String[] states = new String[p.palette().size()];
        for (int i = 0; i < states.length; i++) {
            states[i] = port.canonical(p.palette().get(i));
        }
        for (BlockOp op : p.ops()) {
            inside(op.x(), op.y(), op.z());
            ChunkPlan cp = plan.computeIfAbsent(key(op.x() >> 4, op.z() >> 4), k -> new ChunkPlan());
            cp.blocks.add(new Op(op.x(), op.y(), op.z(), states[op.state()], null));
            cp.at.add(pos(op.x(), op.y(), op.z()));
        }
        for (SignText s : p.signs()) {
            inside(s.x(), s.y(), s.z());
            ChunkPlan cp = plan.computeIfAbsent(key(s.x() >> 4, s.z() >> 4), k -> new ChunkPlan());
            cp.signs.add(new Op(s.x(), s.y(), s.z(), port.canonical(s.blockData()), pad(s.lines())));
            cp.at.add(pos(s.x(), s.y(), s.z()));
        }
    }

    private void inside(int x, int y, int z) {
        if (!half.contains(x, y, z)) {
            throw new IllegalArgumentException("the plan has a block at " + x + "," + y + "," + z
                    + ", outside its half (" + half.describe() + ")");
        }
    }

    // ---- driving it ---------------------------------------------------------------------------

    /**
     * One tick of work. {@code budget} has already been begun for this tick; {@code people} are
     * everyone in this world (for S5); {@code now} is the clock, for how long a write has waited.
     */
    public void tick(BuildBudget budget, int loadsInFlight, List<Person> people, long now) {
        if (phase == Phase.LOAD) {
            load(Math.max(1, loadsInFlight));
        }
        if (phase == Phase.WORK) {
            work(budget, people == null ? List.of() : people, now);
        }
    }

    private void load(int max) {
        while (inFlight < max && nextLoad < chunks.size() && error == null) {
            int[] c = chunks.get(nextLoad++);
            inFlight++;
            port.load(c[0], c[1], ok -> loaded(c[0], c[1], Boolean.TRUE.equals(ok)));
        }
        if (error != null) {
            fail(error);
        } else if (loaded == chunks.size()) {
            phase = Phase.WORK;
        }
    }

    private void loaded(int cx, int cz, boolean ok) {
        inFlight--;
        if (!ok) {
            if (error == null) {
                error = "chunk " + cx + "," + cz + " could not be loaded";
            }
            return;
        }
        if (released) {
            port.release(cx, cz); // it came in after the job was given up
            return;
        }
        ticketed.add(key(cx, cz));
        loaded++;
    }

    private void work(BuildBudget b, List<Person> people, long now) {
        for (Iterator<Op> it = deferred.iterator(); it.hasNext(); ) {
            Op op = it.next();
            if (blocked(op, people, now)) {
                continue;
            }
            if (!b.op()) {
                return;
            }
            apply(op);
            it.remove();
        }
        while (phase == Phase.WORK) {
            if (!pending.isEmpty()) {
                Op op = pending.peekFirst();
                if (blocked(op, people, now)) {
                    pending.pollFirst();
                    deferred.add(op);
                    continue;
                }
                if (!b.op()) {
                    return;
                }
                pending.pollFirst();
                apply(op);
                continue;
            }
            if (chunkIndex < chunks.size()) {
                if (!b.snapshot()) {
                    return;
                }
                int[] c = chunks.get(chunkIndex);
                WorldPort.ChunkView v = port.snapshot(c[0], c[1]);
                if (v == null) {
                    fail("chunk " + c[0] + "," + c[1] + " is not loaded");
                    return;
                }
                Diff d = diff(v, c[0], c[1]);
                if (d == null) {
                    continue; // the snapshot contradicted itself: read the same chunk again
                }
                chunkIndex++;
                passDiffs += d.blocks;
                totalDiffs += d.blocks;
                for (String at : d.at) {
                    if (named.size() >= NAMED) {
                        break;
                    }
                    named.add(at);
                }
                if (mode == Mode.CONVERGE) {
                    pending.addAll(d.drain); // stage 1 as the chunks are read: every other write waits
                    body.addAll(d.body);
                    fills.addAll(d.fill);
                }
                continue;
            }
            if (!deferred.isEmpty()) {
                return;
            }
            if (stage == Stage.DRAIN) {
                stage = Stage.BODY; // every chunk read and every unwanted water block gone
                pending.addAll(body);
                body.clear();
                continue;
            }
            if (stage == Stage.BODY) {
                stage = Stage.FILL; // every solid of the half written: now the water
                pending.addAll(fills);
                fills.clear();
                continue;
            }
            endPass();
        }
    }

    private void endPass() {
        if (mode == Mode.SCAN || passDiffs == 0) {
            phase = Phase.DONE;
            return;
        }
        if (pass >= MAX_PASSES) {
            fail(passDiffs + " block" + (passDiffs == 1 ? "" : "s") + " still differ after " + pass
                    + " passes, first at " + String.join(" ", named));
            return;
        }
        pass++;
        chunkIndex = 0;
        passDiffs = 0;
        named.clear();
        stage = Stage.DRAIN;
    }

    /** Whether a person is in the way of {@code op}; notes who has kept it waiting too long. */
    private boolean blocked(Op op, List<Person> people, long now) {
        boolean in = false;
        for (Person p : people) {
            if (p.touches(op.x, op.y, op.z, KEEP_OUT)) {
                if (op.since < 0) {
                    op.since = now;
                }
                if (now - op.since >= STUCK_MS) {
                    stuck.add(p.id());
                }
                in = true;
            }
        }
        if (!in) {
            op.since = -1;
        }
        return in;
    }

    private void apply(Op op) {
        writer.set(op.x, op.y, op.z, op.state);
        if (op.sign != null) {
            writer.sign(op.x, op.y, op.z, op.sign);
        }
    }

    /**
     * One chunk's differences, by write stage: unwanted water to drain, solids, air and signs, and
     * planned water; {@code blocks} counts each differing block once, {@code at} names them.
     */
    private static final class Diff {
        final List<Op> drain = new ArrayList<>();
        final List<Op> body = new ArrayList<>();
        final List<Op> fill = new ArrayList<>();
        final List<String> at = new ArrayList<>();
        long blocks;

        void differs(Op op) {
            blocks++;
            if (at.size() < NAMED) {
                at.add(op.at());
            }
        }
    }

    /**
     * Whether a block-data text is a fluid a half's writes stage (EVENTS-DROPPER-SPEC §B.1.9): water,
     * of any level. Only a Dropper's plan places it ({@code Palette.POOL_WATER}).
     */
    static boolean fluid(String state) {
        return state != null && Palette.id(state).equals("minecraft:water");
    }

    /**
     * What differs in one chunk: planned blocks that aren't what the plan says, planned signs with
     * the wrong block or text, and anything that isn't air where the plan has nothing. Bottom-up,
     * signs last; water the plan doesn't want is drained first, and planned water comes last
     * (bottom-up). {@code null} when the snapshot's "empty section" answer is contradicted by a
     * planned block it holds (the port stops trusting those answers, and the chunk is read again).
     */
    private Diff diff(WorldPort.ChunkView v, int cx, int cz) {
        ChunkPlan cp = plan.get(key(cx, cz));
        Diff d = new Diff();
        List<Op> out = new ArrayList<>();
        List<Op> signs = new ArrayList<>();
        if (cp != null) {
            for (Op b : cp.blocks) {
                boolean air = v.air(b.x, b.y, b.z);
                if (!air && v.sectionEmpty(b.y)) {
                    port.distrustEmptySections();
                    return null;
                }
                String cur = air ? WorldPort.AIR : v.block(b.x, b.y, b.z);
                if (!b.state.equals(cur)) {
                    d.differs(b);
                    if (fluid(b.state)) {
                        d.fill.add(b);
                        continue;
                    }
                    if (fluid(cur)) {
                        d.drain.add(new Op(b.x, b.y, b.z, WorldPort.AIR, null));
                    }
                    out.add(b);
                }
            }
            for (Op s : cp.signs) {
                String cur = v.air(s.x, s.y, s.z) ? WorldPort.AIR : v.block(s.x, s.y, s.z);
                if (!s.state.equals(cur) || !s.sign.equals(pad(port.signLines(s.x, s.y, s.z)))) {
                    d.differs(s);
                    if (fluid(cur)) {
                        d.drain.add(new Op(s.x, s.y, s.z, WorldPort.AIR, null));
                    }
                    signs.add(s);
                }
            }
        }
        int x0 = Math.max(half.minX(), cx << 4);
        int x1 = Math.min(half.maxX(), (cx << 4) + 15);
        int z0 = Math.max(half.minZ(), cz << 4);
        int z1 = Math.min(half.maxZ(), (cz << 4) + 15);
        for (int sy = half.minY() >> 4; sy <= half.maxY() >> 4; sy++) {
            int y0 = Math.max(half.minY(), sy << 4);
            int y1 = Math.min(half.maxY(), (sy << 4) + 15);
            if (v.sectionEmpty(y0)) {
                continue;
            }
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        if (cp != null && cp.at.contains(pos(x, y, z))) {
                            continue;
                        }
                        if (!v.air(x, y, z)) {
                            Op clear = new Op(x, y, z, WorldPort.AIR, null);
                            d.differs(clear);
                            (fluid(v.block(x, y, z)) ? d.drain : out).add(clear);
                        }
                    }
                }
            }
        }
        Comparator<Op> up = Comparator.comparingInt((Op o) -> o.y).thenComparingInt(o -> o.x)
                .thenComparingInt(o -> o.z);
        out.sort(up);
        out.addAll(signs);
        d.body.addAll(out);
        d.drain.sort(up.reversed()); // a pool empties from the top
        d.fill.sort(up); // and fills from its floor
        return d;
    }

    // ---- ending it ----------------------------------------------------------------------------

    /** Drop every chunk ticket (at the end, or when the job is given up). Safe to call twice. */
    public void release() {
        if (released) {
            return;
        }
        released = true;
        for (long k : ticketed) {
            port.release((int) (k >> 32), (int) k);
        }
        ticketed.clear();
    }

    private void fail(String why) {
        error = why;
        phase = Phase.FAILED;
    }

    // ---- what it did --------------------------------------------------------------------------

    public Phase phase() {
        return phase;
    }

    /** Whether it finished: the half now matches (converge), or the count is in (scan). */
    public boolean done() {
        return phase == Phase.DONE;
    }

    public boolean failed() {
        return phase == Phase.FAILED;
    }

    /** Why it failed, or {@code null}. */
    public String error() {
        return phase == Phase.FAILED ? error : null;
    }

    /** Blocks written. */
    public long writes() {
        return writer.writes();
    }

    /** Differences found over every pass (a scan: the blocks that aren't air). */
    public long found() {
        return totalDiffs;
    }

    /** The first few differing blocks of the latest pass, "x,y,z". */
    public List<String> firstFound() {
        return List.copyOf(named);
    }

    /** Which pass it is on (1 = the converge). */
    public int pass() {
        return pass;
    }

    /** Chunks in the half. */
    public int chunkCount() {
        return chunks.size();
    }

    /** Chunks loaded so far. */
    public int loadedCount() {
        return loaded;
    }

    /** Writes still to make in this pass (for status: "converging 312/745"). */
    public int waiting() {
        return pending.size() + deferred.size() + body.size() + fills.size();
    }

    /** The write stage this pass is on (drain while reading, then solids and signs, then water). */
    Stage stage() {
        return stage;
    }

    /** Whether some write is waiting for a person to move. */
    public boolean deferring() {
        return !deferred.isEmpty();
    }

    /** People who have kept a write waiting for {@value #STUCK_MS} ms, to be moved; cleared by reading. */
    public Set<UUID> stuckPeople() {
        Set<UUID> out = Set.copyOf(stuck);
        stuck.clear();
        return out;
    }

    /** The half it works on. */
    public Box half() {
        return half;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static long pos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Sign lines as the world shows them: exactly four, missing ones blank. */
    static List<String> pad(List<String> lines) {
        List<String> out = new ArrayList<>(4);
        if (lines != null) {
            for (int i = 0; i < Math.min(4, lines.size()); i++) {
                out.add(lines.get(i) == null ? "" : lines.get(i));
            }
        }
        while (out.size() < 4) {
            out.add("");
        }
        return out;
    }
}
