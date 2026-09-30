package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.CellState;
import com.dierks.homecraft.games.arena.rules.FloorLayout;
import com.dierks.homecraft.games.arena.rules.FloorWrite;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.HalfWriter;
import com.dierks.homecraft.games.gen.engine.WorldPort;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Supplier;

/**
 * The only thing that changes a block during a Falling Floors round (EVENTS-DROPPER-SPEC §B.3.3,
 * S1): a restricted {@link HalfWriter} for the arena box.
 *
 * <p>Three locks, each enough on its own to keep a round's writes where they belong:
 * <ol>
 *   <li>the {@link HalfWriter} throws for any position outside the box, before anything is written;</li>
 *   <li>a position that isn't one of the round's planned floor cells is refused (counted, never
 *       written);</li>
 *   <li>a block other than that floor's own glass, red glass or air is refused too.</li>
 * </ol>
 * So a round can only ever turn a floor cell red or empty it, and only the reset (a
 * {@code BuildJob} converge to the week's plan) puts glass back.
 *
 * <p>Why a queue with a cap: at most {@value #MAX_PER_TICK} blocks are written a tick (16 players
 * moving a step a tick is exactly that). Anything more waits, in order, so a cell's air is never
 * written before its red. {@link #clear} drops what is waiting when a round ends or a reset starts,
 * so nothing queued can land on a freshly reset floor.
 *
 * <p>Writes go through the world port without physics, so the region guard (which keeps everyone
 * else from changing the box) never sees them. Main thread only.
 */
public final class FloorWriter {

    /** The most blocks written in one tick. */
    public static final int MAX_PER_TICK = 128;
    /** "About to fall". */
    public static final String RED = "minecraft:red_stained_glass";

    private final HalfWriter writer;
    private final Supplier<FloorLayout> layout;
    private final List<String> floorBlocks;
    private final String red;
    private final ArrayDeque<FloorWrite> queue = new ArrayDeque<>();
    private long queued;
    private long written;
    private long refused;
    private int lastTick;
    private int maxTick;

    /**
     * @param port        the arena's world
     * @param box         the arena box (the {@link HalfWriter}'s half)
     * @param layout      the floors of the round being played (asked at every write)
     * @param floorBlocks each floor's own block, top first, as the plan writes it
     */
    public FloorWriter(WorldPort port, Box box, Supplier<FloorLayout> layout, List<String> floorBlocks) {
        if (port == null || box == null || layout == null) {
            throw new IllegalArgumentException("a floor writer needs its world, its box and its floors");
        }
        this.writer = new HalfWriter(port, box);
        this.layout = layout;
        this.floorBlocks = floorBlocks == null ? List.of() : floorBlocks.stream().map(port::canonical).toList();
        this.red = port.canonical(RED);
    }

    /** Queue a round's writes, in order. */
    public void queue(List<FloorWrite> writes) {
        if (writes == null) {
            return;
        }
        for (FloorWrite w : writes) {
            if (w != null) {
                queue.addLast(w);
                queued++;
            }
        }
    }

    /** Write what is waiting, at most {@value #MAX_PER_TICK}; call once a tick. @return blocks written */
    public int flush() {
        int n = 0;
        while (n < MAX_PER_TICK && !queue.isEmpty()) {
            FloorWrite w = queue.pollFirst();
            if (write(w.x(), w.y(), w.z(), w.state() == CellState.RED ? red : WorldPort.AIR)) {
                n++;
            }
        }
        lastTick = n;
        maxTick = Math.max(maxTick, n);
        return n;
    }

    /**
     * One block, checked: throws outside the box ({@link HalfWriter}); refuses (false, counted,
     * nothing written) a position that isn't a planned floor cell of the round's floors, or a block
     * that isn't that floor's own, red glass or air.
     *
     * @param canonical the block in the world's full spelling ({@link WorldPort#AIR} to empty it)
     */
    public boolean write(int x, int y, int z, String canonical) {
        if (!writer.half().contains(x, y, z)) {
            writer.set(x, y, z, canonical); // throws: never outside the box
        }
        FloorLayout l = layout.get();
        int layer = l == null ? -1 : l.layerAtY(y);
        if (layer < 0 || !l.isCell(layer, x, z)) {
            refused++;
            return false;
        }
        boolean own = layer < floorBlocks.size() && floorBlocks.get(layer).equals(canonical);
        if (!own && !red.equals(canonical) && !WorldPort.AIR.equals(canonical)) {
            refused++;
            return false;
        }
        writer.set(x, y, z, canonical);
        written++;
        return true;
    }

    /** Drop everything waiting (a round ended, a reset starts, the game closed). */
    public void clear() {
        queue.clear();
    }

    /** Blocks waiting. */
    public int waiting() {
        return queue.size();
    }

    /** Blocks queued since it was made. */
    public long queued() {
        return queued;
    }

    /** Blocks written since it was made. */
    public long written() {
        return written;
    }

    /** Writes refused since it was made (a planned-cell or block check said no): always 0 unless there is a bug. */
    public long refused() {
        return refused;
    }

    /** Blocks written in the last flush, and the most in any one. */
    public int lastTick() {
        return lastTick;
    }

    public int maxTick() {
        return maxTick;
    }
}
