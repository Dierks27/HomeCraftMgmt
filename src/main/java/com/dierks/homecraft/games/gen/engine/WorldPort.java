package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.golf.BallPhysics;

import java.util.List;
import java.util.function.Consumer;

/**
 * Everything the engine does to a world, as one narrow port (GEN-SPEC §3.3, §8.8 risk 1).
 *
 * <p>Why a port: the build is the part that must be right every day — converge, verify, never a
 * block outside the half, the same result after any stop — and it is tested on a map-backed fake
 * world. {@link BukkitWorldPort} is the only file that touches the Paper API for chunk loads,
 * chunk tickets, snapshots, {@code setBlockData}, sign sides, waxing and game rules, so a
 * difference between Paper versions lands in one place.
 *
 * <p>Blocks are block-data text. Plans carry their own spelling
 * ({@code minecraft:smooth_stone_slab[type=bottom]}); {@link #canonical} turns that into the
 * world's full spelling (every state spelled out), and the chunk views speak the same full
 * spelling, so "is this block what the plan says" is a string comparison. Everything here runs on
 * the main thread.
 */
public interface WorldPort {

    /** Air, as the plan's "nothing here" is written. */
    String AIR = "minecraft:air";

    /** The world's name. */
    String name();

    /** Its lowest block. */
    int minHeight();

    /** One above its highest block. */
    int maxHeight();

    /** The world border's blocks along x and z (y is the world's), or {@code null} for none. */
    Box border();

    /** The spawn block {x, y, z}. */
    int[] spawn();

    /**
     * Load chunk ({@code cx}, {@code cz}) without blocking the main thread and hold it with a
     * ticket until {@link #release}. {@code done} runs on the main thread with whether it loaded.
     */
    void load(int cx, int cz, Consumer<Boolean> done);

    /** Drop the ticket {@link #load} took. */
    void release(int cx, int cz);

    /** A snapshot of a loaded chunk, or {@code null} when it isn't loaded. */
    ChunkView snapshot(int cx, int cz);

    /**
     * The full spelling of a block-data text ({@code minecraft:oak_sign[rotation=4,waterlogged=false]});
     * throws {@link IllegalArgumentException} for text that isn't a block.
     */
    String canonical(String blockData);

    /** Set one block, without physics. Only {@link HalfWriter} calls this. */
    void set(int x, int y, int z, String canonical);

    /** Write a sign's front text (glowing, then waxed, so nobody can edit it). Only {@link HalfWriter}. */
    void sign(int x, int y, int z, List<String> lines);

    /** A sign's front lines as plain text (four of them), or {@code null} when there is no sign. */
    List<String> signLines(int x, int y, int z);

    /** The real blocks as the golf ball sees them (for the live witness replay). */
    BallPhysics.Blocks ballBlocks();

    /**
     * The real blocks as the golf ball sees them on a course that plays Adventure Golf's rules
     * ({@code sand}: a layout of golf algo 3 or later, Course Variety §3.4 — its smooth sandstone is
     * sand, and a ball at rest over water has fallen in) or not (a hand-built course, and every
     * older layout: sandstone is any stone). A port whose blocks have no sand or water to tell apart
     * may ignore it.
     */
    default BallPhysics.Blocks ballBlocks(boolean sand) {
        return ballBlocks();
    }

    /**
     * No mobs, fire, random ticks or weather cycle; always noon (GEN-SPEC §2.1). Each rule actually
     * changed is reported through {@code changed} once, in admin words.
     */
    void worldRules(Consumer<String> changed);

    /**
     * Stop trusting "this section is empty" answers (a snapshot said a section was empty that
     * holds a planned block): every section is read block by block from now on.
     */
    void distrustEmptySections();

    /** One chunk as it was when the snapshot was taken; world coordinates. */
    interface ChunkView {

        /** Whether the 16-high section holding block height {@code y} has nothing but air. */
        boolean sectionEmpty(int y);

        /** Whether block (x, y, z) is air of any kind. */
        boolean air(int x, int y, int z);

        /** Block (x, y, z) in the full spelling. */
        String block(int x, int y, int z);
    }
}
