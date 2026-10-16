package com.dierks.homecraft.games.gen.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@link GenKit.FakeWorld} for a Mountain Run v2's hundreds of thousands of blocks: the same world (its
 * {@code blocks} map stays the truth for every helper), with the blocks also kept per chunk, so a chunk
 * snapshot copies one chunk's blocks instead of walking the whole world's. Only {@link #put} and
 * {@link #set} change blocks here (a test that edits {@code blocks} directly should use the plain world).
 */
final class ChunkedWorld extends GenKit.FakeWorld {

    private final Map<Long, Map<Long, String>> byChunk = new HashMap<>();

    ChunkedWorld(String name) {
        super(name);
    }

    /**
     * A world whose block map is already big enough for {@code blocks} (a real world's storage doesn't stop
     * to rehash itself while a build writes): the map grows once now, not in the middle of a timed tick.
     */
    ChunkedWorld(String name, int blocks) {
        super(name);
        for (long i = 0; i < blocks; i++) {
            this.blocks.put(Long.MIN_VALUE + i, AIR);
        }
        this.blocks.clear(); // a cleared HashMap keeps its table
    }

    @Override
    void put(int x, int y, int z, String state) {
        super.put(x, y, z, state);
        mirror(x, y, z);
    }

    @Override
    public void set(int x, int y, int z, String state) {
        super.set(x, y, z, state);
        mirror(x, y, z);
    }

    private void mirror(int x, int y, int z) {
        long p = GenKit.pos(x, y, z);
        String now = blocks.get(p);
        Map<Long, String> chunk = byChunk.computeIfAbsent(chunk(x >> 4, z >> 4), k -> new HashMap<>());
        if (now == null) {
            chunk.remove(p);
        } else {
            chunk.put(p, now);
        }
    }

    @Override
    public ChunkView snapshot(int cx, int cz) {
        if (!tickets.contains(chunk(cx, cz))) {
            return null;
        }
        Map<Long, String> copy = new HashMap<>(byChunk.getOrDefault(chunk(cx, cz), Map.of()));
        Set<Integer> sections = new HashSet<>();
        for (long p : copy.keySet()) {
            sections.add((int) ((p << 52) >> 52) >> 4);
        }
        return new ChunkView() {
            @Override
            public boolean sectionEmpty(int y) {
                return !distrusted && !sections.contains(y >> 4);
            }

            @Override
            public boolean air(int x, int y, int z) {
                return !copy.containsKey(GenKit.pos(x, y, z));
            }

            @Override
            public String block(int x, int y, int z) {
                String s = copy.get(GenKit.pos(x, y, z));
                return s == null ? AIR : s;
            }
        };
    }
}
