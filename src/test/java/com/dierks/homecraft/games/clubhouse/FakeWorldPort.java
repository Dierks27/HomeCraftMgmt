package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.WorldPort;
import com.dierks.homecraft.games.golf.BallPhysics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A world of block texts for the Clubhouse's tests (a copy of the arena's) (like Fresh Courses' own fake): chunks "load" at once
 * with a ticket, a snapshot is a copy, and every write is counted. A test can make chunk loads fail
 * (a reset that can never verify).
 */
final class FakeWorldPort implements WorldPort {

    final String name;
    final Map<Long, String> blocks = new HashMap<>();
    final Set<Long> tickets = new HashSet<>();
    final Set<Long> failLoads = new HashSet<>();
    /** Blocks a write never changes (something in the world keeps putting them back): a verify that can't pass. */
    final Set<Long> sticky = new HashSet<>();
    long writes;
    long released;
    /** Every write, "x,y,z=block", in order. */
    final List<String> log = new ArrayList<>();

    FakeWorldPort(String name) {
        this.name = name;
    }

    static long pos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static long chunk(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    static String canonicalOf(String blockData) {
        String s = blockData.trim().toLowerCase(Locale.ROOT);
        if (s.contains("bogus")) {
            throw new IllegalArgumentException("not a block: " + blockData);
        }
        return s.indexOf(':') < 0 ? "minecraft:" + s : s;
    }

    String at(int x, int y, int z) {
        String s = blocks.get(pos(x, y, z));
        return s == null ? AIR : s;
    }

    void put(int x, int y, int z, String state) {
        if (AIR.equals(canonicalOf(state))) {
            blocks.remove(pos(x, y, z));
        } else {
            blocks.put(pos(x, y, z), canonicalOf(state));
        }
    }

    /** Blocks that aren't air inside a box. */
    long count(Box b) {
        long n = 0;
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int y = b.minY(); y <= b.maxY(); y++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    if (blocks.containsKey(pos(x, y, z))) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** Make every chunk of a box fail to load (or load again). */
    void failLoads(Box b, boolean fail) {
        for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) {
            for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
                if (fail) {
                    failLoads.add(chunk(cx, cz));
                } else {
                    failLoads.remove(chunk(cx, cz));
                }
            }
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int minHeight() {
        return -64;
    }

    @Override
    public int maxHeight() {
        return 320;
    }

    @Override
    public Box border() {
        return null;
    }

    @Override
    public int[] spawn() {
        return new int[]{0, 64, 0};
    }

    @Override
    public void load(int cx, int cz, Consumer<Boolean> done) {
        long k = chunk(cx, cz);
        boolean ok = !failLoads.contains(k);
        if (ok) {
            tickets.add(k);
        }
        done.accept(ok);
    }

    @Override
    public void release(int cx, int cz) {
        tickets.remove(chunk(cx, cz));
        released++;
    }

    @Override
    public ChunkView snapshot(int cx, int cz) {
        if (!tickets.contains(chunk(cx, cz))) {
            return null;
        }
        Map<Long, String> copy = new HashMap<>();
        Set<Integer> sections = new HashSet<>();
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            long p = e.getKey();
            int x = (int) (p >> 38);
            int z = (int) ((p << 26) >> 38);
            int y = (int) ((p << 52) >> 52);
            if (x >> 4 == cx && z >> 4 == cz) {
                copy.put(p, e.getValue());
                sections.add(y >> 4);
            }
        }
        return new ChunkView() {
            @Override
            public boolean sectionEmpty(int y) {
                return !sections.contains(y >> 4);
            }

            @Override
            public boolean air(int x, int y, int z) {
                return !copy.containsKey(pos(x, y, z));
            }

            @Override
            public String block(int x, int y, int z) {
                String s = copy.get(pos(x, y, z));
                return s == null ? AIR : s;
            }
        };
    }

    @Override
    public String canonical(String blockData) {
        return canonicalOf(blockData);
    }

    @Override
    public void set(int x, int y, int z, String state) {
        writes++;
        log.add(x + "," + y + "," + z + "=" + state);
        if (sticky.contains(pos(x, y, z))) {
            return;
        }
        if (AIR.equals(state)) {
            blocks.remove(pos(x, y, z));
        } else {
            blocks.put(pos(x, y, z), state);
        }
    }

    @Override
    public void sign(int x, int y, int z, List<String> lines) {
    }

    @Override
    public List<String> signLines(int x, int y, int z) {
        return null;
    }

    @Override
    public BallPhysics.Blocks ballBlocks() {
        return null;
    }

    @Override
    public void worldRules(Consumer<String> changed) {
    }

    @Override
    public void distrustEmptySections() {
    }
}
