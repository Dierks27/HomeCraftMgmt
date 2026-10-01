package com.dierks.homecraft.games.gen.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * What a player can see from where the games put them (LAYOUT-SPEC §1.1-§1.3), as pure arithmetic
 * on chunk columns.
 *
 * <p>The owner's words: "cosmetically it will look really weird if you can see the other stuff
 * around you floating." What a player sees is what the server sends, and the server sends whole
 * chunk columns: vanilla sends chunk (x, z) to a player in chunk (cx, cz) when
 * {@code max(0,|x-cx|-2)^2 + max(0,|z-cz|-2)^2 < V^2}, which along one axis is {@code |d| <= V + 1},
 * and Paper sends no wider. This class uses the square bound "Chebyshev chunk distance at most
 * V + 1": a superset of what is sent, so it errs on the safe side. Height never matters (a column
 * is sent whole), so {@link Box#gap}, which counts y, is never used here.
 *
 * <p>A player is never only inside a box: a spectator watches a Fresh course from its half grown by
 * 8, a kept course's watchers from its bounds grown by 16. So a place is judged from its box grown
 * by a <b>reach</b> on x and z ({@link #REACH} for every place the games build; 0 for a point, such
 * as the world's spawn). {@code D(P, Q)} is the Chebyshev gap, in chunk columns, between the chunks
 * of P grown by its reach and the chunks of Q; Q is out of sight of P exactly when
 * {@code D(P, Q) >= V + 2}. Two chunk-aligned boxes with G empty blocks between them on the
 * separating axis are {@code G / 16} apart, so {@link #GAP} (576, 36 chunks) keeps them out of
 * sight at every view distance up to 34: Paper's largest is {@link #DESIGN_VIEW}, with two chunks
 * to spare for anyone's off-by-one and a player up to 32 blocks outside a box.
 *
 * <p>Pure and immutable, like every contract type in {@code games/gen/api}; negative coordinates
 * floor to their chunk ({@code x >> 4}: block -1 is chunk -1).
 */
public final class Sight {

    /** The largest view distance the layout is built for: Paper's largest. */
    public static final int DESIGN_VIEW = 32;
    /** How far outside a place the games put a player, on x and z (LAYOUT-SPEC §1.1). */
    public static final int REACH = 16;
    /**
     * The blocks between two places that keep each out of the other's sight at {@link #DESIGN_VIEW}
     * with two chunks to spare: 16 x 36 = 576, so {@code D} = 36 and nothing shows up to view
     * distance 34.
     */
    public static final int GAP = 16 * (DESIGN_VIEW + 4);

    private Sight() {
    }

    /**
     * {@code D(on, seen)}: how many chunk columns lie between the chunks a player on {@code on}
     * (grown by {@code reach} on x and z) can stand in and the chunks {@code seen} touches, along
     * the axis that separates them most; 0 when they share a column. x and z only.
     *
     * @param reach blocks outside {@code on} a player can be, 0 or more
     */
    public static int chunksApart(Box on, int reach, Box seen) {
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(seen, "seen");
        if (reach < 0) {
            throw new IllegalArgumentException("a reach is 0 or more blocks: " + reach);
        }
        long dx = axis(chunk((long) on.minX() - reach), chunk((long) on.maxX() + reach), chunk(seen.minX()),
                chunk(seen.maxX()));
        long dz = axis(chunk((long) on.minZ() - reach), chunk((long) on.maxZ() + reach), chunk(seen.minZ()),
                chunk(seen.maxZ()));
        return (int) Math.max(dx, dz);
    }

    /**
     * Whether any of {@code seen} can be sent to a player on {@code on} (within its reach) at view
     * distance {@code view}.
     */
    public static boolean inSight(Box on, int reach, Box seen, int view) {
        return within(chunksApart(on, reach, seen), view);
    }

    /**
     * The one sight rule, for {@link #inSight} and {@link #pairs} alike: {@code chunks} apart is in sight
     * at view distance {@code view} exactly when {@code chunks <= view + 1} (out of sight from
     * {@code view + 2} on).
     */
    public static boolean within(long chunks, int view) {
        return chunks <= (long) view + 1;
    }

    /**
     * The largest view distance at which nothing of {@code seen} reaches a player on {@code on}:
     * {@code chunksApart - 2}. Negative when it shows at every view distance (it is within a chunk).
     */
    public static int clearUpTo(Box on, int reach, Box seen) {
        return chunksApart(on, reach, seen) - 2;
    }

    /**
     * A place players can be (and so can be seen from).
     *
     * @param name  what the caller calls it (an id it maps back, or words)
     * @param world its world ({@code null} is a world of its own)
     * @param box   its blocks; a point (a spawn, a stand) is a 1 x 1 x 1 box
     * @param reach how far outside it a player can be on x and z: {@link #REACH} for a place the games
     *              build, 0 for a point
     */
    public record Spot(String name, String world, Box box, int reach) {

        public Spot {
            Objects.requireNonNull(box, "box");
            if (reach < 0) {
                throw new IllegalArgumentException("a reach is 0 or more blocks: " + reach);
            }
        }
    }

    /**
     * Two places, and how far apart they are seen: the nearer way round ({@link #apart}).
     *
     * @param chunks the smaller {@code D} of the two ways round
     */
    public record Pair(Spot a, Spot b, int chunks) {

        /** The largest view distance at which neither sees the other (negative: always in sight). */
        public int clearUpTo() {
            return chunks - 2;
        }
    }

    /** {@code D} the nearer way round: from {@code a} (with its reach) to {@code b}, or from {@code b} to {@code a}. */
    public static int apart(Spot a, Spot b) {
        return Math.min(chunksApart(a.box(), a.reach(), b.box()), chunksApart(b.box(), b.reach(), a.box()));
    }

    /**
     * Every unordered pair of spots in one world where either can see the other at view distance
     * {@code view}, nearest first (ties in the order given). Spots in different worlds never see
     * each other; a world is matched ignoring case.
     */
    public static List<Pair> pairs(List<Spot> spots, int view) {
        List<Pair> out = new ArrayList<>();
        for (Pair p : every(spots)) {
            if (within(p.chunks(), view)) {
                out.add(p);
            }
        }
        return out;
    }

    /** The nearest pair of spots in one world, or {@code null} when no world holds two. */
    public static Pair nearest(List<Spot> spots) {
        List<Pair> all = every(spots);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Every unordered pair in one world, nearest first (a stable sort: ties in the order given), in sight
     * or not: for a caller that leaves some pairs out before it applies {@link #within}.
     */
    public static List<Pair> every(List<Spot> spots) {
        List<Pair> out = new ArrayList<>();
        List<Spot> list = spots == null ? List.of() : spots;
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                Spot a = list.get(i);
                Spot b = list.get(j);
                if (a != null && b != null && sameWorld(a.world(), b.world())) {
                    out.add(new Pair(a, b, apart(a, b)));
                }
            }
        }
        out.sort(Comparator.comparingInt(Pair::chunks));
        return out;
    }

    private static boolean sameWorld(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    /** The chunk a block coordinate is in (floored, so block -1 is chunk -1). */
    private static long chunk(long block) {
        return Math.floorDiv(block, 16L);
    }

    /** Chunk columns between two chunk ranges on one axis: 0 when they meet. */
    private static long axis(long a0, long a1, long b0, long b1) {
        return Math.max(0, Math.max(b0 - a1, a0 - b1));
    }
}
