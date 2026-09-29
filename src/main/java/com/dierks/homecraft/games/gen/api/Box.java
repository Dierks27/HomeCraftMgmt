package com.dierks.homecraft.games.gen.api;

/**
 * A box of whole blocks, both corners inclusive: a half, a region, a keep-clear envelope, a
 * plot (GEN-SPEC §2.2, §4.0).
 *
 * <p>Inclusive corners because that is how an admin reads coordinates ("x 4096-4159") and how the
 * planners think about blocks; every size and test here counts blocks, not distances, so the
 * "32 blocks apart" and "16 outside" rules of §2.4 are exact whole-block questions. Pure and
 * immutable, like every contract type in {@code games/gen/api}.
 */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Box {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("a box's max corner is below its min corner: " + minX + "," + minY
                    + "," + minZ + " to " + maxX + "," + maxY + "," + maxZ);
        }
    }

    /** The box between two corners given in any order. */
    public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2),
                Math.max(z1, z2));
    }

    /** The box of {@code sx} x {@code sy} x {@code sz} blocks whose min corner is (x, y, z). */
    public static Box sized(int x, int y, int z, int sx, int sy, int sz) {
        if (sx < 1 || sy < 1 || sz < 1) {
            throw new IllegalArgumentException("a box is at least one block each way: " + sx + "x" + sy + "x" + sz);
        }
        return new Box(x, y, z, x + sx - 1, y + sy - 1, z + sz - 1);
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    /** How many blocks it holds. */
    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    /** Whether block (x, y, z) is inside. */
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /**
     * Whether a point (a player's feet, a ball) is inside the blocks: x from {@code minX} up to,
     * but not including, {@code maxX + 1}.
     */
    public boolean contains(double x, double y, double z) {
        return x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
    }

    /** Whether every block of {@code other} is inside. */
    public boolean contains(Box other) {
        return other.minX >= minX && other.maxX <= maxX && other.minY >= minY && other.maxY <= maxY
                && other.minZ >= minZ && other.maxZ <= maxZ;
    }

    /** Whether the two share at least one block. */
    public boolean intersects(Box other) {
        return other.minX <= maxX && other.maxX >= minX && other.minY <= maxY && other.maxY >= minY
                && other.minZ <= maxZ && other.maxZ >= minZ;
    }

    /**
     * How many whole blocks lie between the two along the axis that separates them most: 0 when
     * they touch, -1 when they share a block. "At least 32 apart" is {@code gap >= 32}.
     */
    public int gap(Box other) {
        int gx = Math.max(other.minX - maxX - 1, minX - other.maxX - 1);
        int gy = Math.max(other.minY - maxY - 1, minY - other.maxY - 1);
        int gz = Math.max(other.minZ - maxZ - 1, minZ - other.maxZ - 1);
        int g = Math.max(gx, Math.max(gy, gz));
        return g < 0 ? -1 : g;
    }

    /** The box grown by {@code n} blocks on every side ({@code n} may be negative while it stays a box). */
    public Box expand(int n) {
        return expand(n, n, n);
    }

    /** The box grown by {@code dx}, {@code dy}, {@code dz} blocks on both sides of each axis. */
    public Box expand(int dx, int dy, int dz) {
        return new Box(minX - dx, minY - dy, minZ - dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** The same box moved by (dx, dy, dz). */
    public Box translate(int dx, int dy, int dz) {
        return new Box(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** How many chunk columns it touches. */
    public int chunkCount() {
        return ((maxX >> 4) - (minX >> 4) + 1) * ((maxZ >> 4) - (minZ >> 4) + 1);
    }

    /** For admins and logs: "x 4096..4159, y 160..207, z 4096..4159". */
    public String describe() {
        return "x " + minX + ".." + maxX + ", y " + minY + ".." + maxY + ", z " + minZ + ".." + maxZ;
    }
}
