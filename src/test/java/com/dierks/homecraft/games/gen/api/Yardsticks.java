package com.dierks.homecraft.games.gen.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Two fixed pieces of work the planner benches time beside their plans, so a CPU-time cap scales
 * with the machine instead of failing a release on a slow runner (the golf bench's precedent): a
 * breadth-first walk and a ball roll, each doing exactly the same work every time, and the
 * microseconds each took on the machine the caps were measured on.
 */
public final class Yardsticks {

    /**
     * Each yardstick's mean (all but its quickest and slowest timing), in microseconds of CPU, beside the
     * courses planned one at a time, on the machine the {@code FreshBench} times were measured on (the walk
     * 28.0-35.3 ms there, the roll 30.5-34.8 ms).
     */
    public static final long WALK_REF_MICROS = 29_000;
    public static final long ROLL_REF_MICROS = 32_000;
    /** What each yardstick adds up to; each does exactly the same work every time. */
    public static final long WALK_SUM = 4 * 177_572_020L;
    public static final long ROLL_SUM = -1_993_121_983_815_145_486L;

    private Yardsticks() {
    }

    /**
     * How much slower this machine is than the one the caps were measured on, from the yardsticks' mean
     * microseconds here (all but the quickest and slowest timing of each): never under 1, to the hundredth.
     */
    public static double slower(long[] walkMicros, long[] rollMicros) {
        long walk = Math.round(Arrays.stream(walkMicros, 1, walkMicros.length - 1).average().orElseThrow());
        long roll = Math.round(Arrays.stream(rollMicros, 1, rollMicros.length - 1).average().orElseThrow());
        double walkRatio = (double) walk / WALK_REF_MICROS;
        double rollRatio = (double) roll / ROLL_REF_MICROS;
        return Math.max(1.0, Math.max(Math.round(100 * walkRatio), Math.round(100 * rollRatio)) / 100.0);
    }

    /**
     * One yardstick, a fixed piece of the bookkeeping beside a planner's search: a breadth-first walk over a
     * 192 x 192 grid with about a fifth of it walled off, the cells it reaches kept in a hash of packed
     * {@code long} keys (as {@link Palette#blockKey} packs them), walked four times so one timing spans a
     * busy machine's time slices.
     */
    public static long walk() {
        int size = 192;
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        long sum = 0;
        for (int round = 0; round < 4; round++) {
            boolean[] wall = new boolean[size * size];
            long x = 0x9E3779B97F4A7C15L;
            for (int i = size; i < wall.length; i++) { // the first row stays open, so the walk always gets going
                x = x * 6364136223846793005L + 1442695040888963407L;
                wall[i] = (x >>> 59) < 6;
            }
            Map<Long, Integer> dist = new HashMap<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            dist.put(0L, 0);
            queue.add(0L);
            long walked = 0;
            while (!queue.isEmpty()) {
                long at = queue.poll();
                int d = dist.get(at);
                walked += d;
                int ax = (int) (at >>> 38);
                int az = (int) (at >>> 12) & 0x3FFFFFF;
                for (int[] s : steps) {
                    int bx = ax + s[0];
                    int bz = az + s[1];
                    if (bx >= 0 && bz >= 0 && bx < size && bz < size && !wall[bz * size + bx]) {
                        long key = ((long) bx << 38) | ((long) bz << 12);
                        if (dist.putIfAbsent(key, d + 1) == null) {
                            queue.add(key);
                        }
                    }
                }
            }
            sum += walked * 31 + dist.size();
        }
        return sum;
    }

    /**
     * The other yardstick, a fixed piece of a simulation's kind of work: 4,500 balls rolled over a grid of
     * block ids the size of Golf of the Week's box (128 x 16 x 224), in sub-steps of at most a quarter block
     * that read the block under the ball, climb a step and lose speed for it, bounce off a wall, fall, and
     * slow by their block's grip each tick, with the floors, square roots and hypotenuses a putt takes. It is
     * shaped like {@code BallPhysics} but isn't it, so a slower physics still fails.
     */
    public static long roll() {
        int sx = 128;
        int sy = 16;
        int sz = 224;
        byte[] cells = new byte[sx * sy * sz];
        double[] tops = {-1, 1, 0.5, 1.5, 0.125, 1, 0.5, 1};
        double[] grip = {0.9, 0.96, 0.98, 0.9, 0.99, 0.95, 0.97, 0.985};
        long x = 0x2545F4914F6CDD1DL;
        for (int cx = 0; cx < sx; cx++) {
            for (int cz = 0; cz < sz; cz++) {
                x = x * 6364136223846793005L + 1442695040888963407L;
                int ground = 3 + (int) (x >>> 62);
                for (int cy = 0; cy <= ground; cy++) {
                    cells[(cy * sz + cz) * sx + cx] = (byte) (cy < ground ? 1 : 1 + (int) ((x >>> 40) & 0xFF) % 7);
                }
                if (((x >>> 20) & 63) == 0) {
                    cells[((ground + 1) * sz + cz) * sx + cx] = 3; // a fence post now and then
                }
            }
        }
        long sum = 0;
        for (int ball = 0; ball < 4_500; ball++) {
            double bx = 4.5 + ball * 37 % 119;
            double by = 8;
            double bz = 4.5 + ball * 11 % 23;
            double vx = (ball % 9 - 4) * 0.06;
            double vy = 0;
            double vz = 0.6 + ball % 5 * 0.08;
            for (int tick = 0; tick < 300; tick++) {
                double speed = StrictMath.hypot(vx, vz);
                if (speed < 0.005) {
                    break;
                }
                int n = (int) Math.max(1, Math.ceil(speed / 0.25));
                int id = 0;
                for (int i = 0; i < n; i++) {
                    double nx = bx + vx / n;
                    double nz = bz + vz / n;
                    if (nx < 0.5 || nx >= sx - 0.5) {
                        vx = -vx * 0.6;
                        nx = bx;
                    }
                    if (nz < 0.5 || nz >= sz - 0.5) {
                        vz = -vz * 0.6;
                        nz = bz;
                    }
                    int cx = (int) Math.floor(nx);
                    int cz = (int) Math.floor(nz);
                    int cy = Math.min(sy - 1, (int) Math.floor(by + 0.5));
                    while (cy > 0 && cells[(cy * sz + cz) * sx + cx] == 0) {
                        cy--;
                    }
                    id = cells[(cy * sz + cz) * sx + cx];
                    double top = cy + tops[id];
                    double rise = top - by;
                    double s = StrictMath.hypot(vx, vz);
                    if (rise > 0.5 + 1e-9 || (rise > 1e-9 && s * s <= 2 * 0.08 * rise)) { // a wall: bounce
                        if (Math.abs(nx - bx) > Math.abs(nz - bz)) {
                            vx = -vx * 0.6;
                        } else {
                            vz = -vz * 0.6;
                        }
                        continue;
                    }
                    if (rise > 1e-9) { // a step: climb it, slower
                        double kept = Math.sqrt(s * s - 2 * 0.08 * rise) / s;
                        vx *= kept;
                        vz *= kept;
                        by = top;
                    }
                    bx = nx;
                    bz = nz;
                    if (by > top + 1e-9) { // in the air: fall
                        vy = (vy - 0.08 / n) * 0.98;
                        by = Math.max(top, by + vy / n);
                    } else {
                        vy = 0;
                        by = top;
                    }
                }
                double f = vy == 0 ? grip[id] : 0.99;
                vx *= f;
                vz *= f;
                sum += (long) Math.floor(by * 16);
            }
            sum = sum * 31 + (long) Math.floor(bx * 64) * 7 + (long) Math.floor(bz * 64);
        }
        return sum;
    }

    /** The machine, for a failed time: its processor count and what /proc/cpuinfo says its cores are. */
    public static String machine() {
        String cpu = "no /proc/cpuinfo";
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc/cpuinfo"));
            cpu = String.join(", ", Arrays.stream(new String[]{"model name", "cpu cores", "siblings"})
                    .map(key -> lines.stream().filter(l -> l.startsWith(key)).findFirst()
                            .map(l -> key + " " + l.substring(l.indexOf(':') + 1).trim()).orElse(key + " unknown"))
                    .toList());
        } catch (IOException | RuntimeException e) {
            // not Linux, or not readable: the processor count is still worth having
        }
        return Runtime.getRuntime().availableProcessors() + " processors (" + cpu + ")";
    }
}
