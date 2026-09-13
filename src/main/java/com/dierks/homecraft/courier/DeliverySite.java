package com.dierks.homecraft.courier;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.structure.StructureRotation;

import java.util.Locale;
import java.util.UUID;

/**
 * A building placed at the far end of a delivery, and the field it replaced.
 *
 * <p>The row is the <b>undo record</b>, which is why it carries the region's origin and size
 * rather than deriving them from the template: a template list an admin edits between
 * placement and restore must not be able to change which blocks get put back.
 *
 * @param snapshot the ORIGINAL blocks of {@code origin..origin+size}, taken before the first
 *                 one was changed — see {@link BuildingSnapshot} for the encoding
 * @param door     the standing position outside the front door, where the villager waits
 */
public record DeliverySite(
        long jobId, String world,
        int originX, int originY, int originZ,
        int sizeX, int sizeY, int sizeZ,
        String template, StructureRotation rotation,
        int doorX, int doorY, int doorZ,
        UUID villager, State state, byte[] snapshot, long placedAt) {

    /** Where a site is in its life. Only {@link #RESTORED} rows are safe to delete. */
    public enum State {
        /** Standing in the world. The region is protected and the villager is live. */
        PLACED,
        /**
         * The job is over but the blocks are still there — almost always because the chunk
         * was not loaded when the restore was attempted. A {@code ChunkLoadEvent} for the
         * region, or the next plugin enable, finishes the job.
         */
        RESTORE_PENDING,
        /** The field is back. The row is deleted immediately after this is written. */
        RESTORED;

        public static State parse(String s) {
            try {
                return valueOf(String.valueOf(s).trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                return RESTORE_PENDING; // unreadable state errs towards cleaning up
            }
        }
    }

    /** The world this site sits in, or null if an admin has removed it. */
    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    /** True if this block coordinate is inside the placed region. */
    public boolean contains(int x, int y, int z) {
        return x >= originX && x < originX + sizeX
                && y >= originY && y < originY + sizeY
                && z >= originZ && z < originZ + sizeZ;
    }

    /** True if the region overlaps the horizontal footprint of another, on the same world. */
    public boolean overlapsColumn(String otherWorld, int minX, int minZ, int maxX, int maxZ) {
        return world.equals(otherWorld)
                && originX < maxX && minX < originX + sizeX
                && originZ < maxZ && minZ < originZ + sizeZ;
    }

    /** Every chunk coordinate the region touches, as packed {@code (cx << 32) | cz} longs. */
    public long[] chunks() {
        int cx0 = originX >> 4;
        int cz0 = originZ >> 4;
        int cx1 = (originX + sizeX - 1) >> 4;
        int cz1 = (originZ + sizeZ - 1) >> 4;
        long[] out = new long[(cx1 - cx0 + 1) * (cz1 - cz0 + 1)];
        int i = 0;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                out[i++] = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
            }
        }
        return out;
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public DeliverySite withState(State next) {
        return new DeliverySite(jobId, world, originX, originY, originZ, sizeX, sizeY, sizeZ,
                template, rotation, doorX, doorY, doorZ, villager, next, snapshot, placedAt);
    }
}
