package com.dierks.homecraft.courier;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.Container;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Copies a cuboid of blocks out of the world and puts it back later.
 *
 * <p>This is why the Courier needs no WorldEdit dependency. A delivery site is small — a
 * 19×19×16 region is under six thousand blocks, nearly all of it air or one kind of stone —
 * so a <b>palette plus one index per block, GZIPped</b>, compresses to a few hundred bytes and
 * drops straight into a SQLite BLOB. That keeps the undo record in the same transaction and
 * the same backup as the job it belongs to, rather than in a second system that has to be
 * installed and running for a house to clean up after itself.
 *
 * <p>The format is versioned because it is persisted: a row written today has to be readable
 * by whatever reads it after a crash, possibly several releases later.
 *
 * <p><b>Only block data is captured</b> — not container contents, not sign text, not spawner
 * types. That is a deliberate limit rather than an oversight: restoring a chest's inventory
 * from a blob is how an item duplication bug gets written. {@link #hasContainers} exists so a
 * candidate region containing anything with an inventory can be <i>rejected before placement</i>
 * instead, which is the only version of this that cannot eat somebody's chest.
 */
public final class BuildingSnapshot {

    /** Bumped only if the encoding below changes shape. Older blobs stay readable. */
    private static final int FORMAT = 1;

    private BuildingSnapshot() {
    }

    /**
     * Read {@code sizeX × sizeY × sizeZ} blocks starting at the origin into a blob.
     *
     * <p>Must be called on the main thread with the region's chunks loaded, and <b>before the
     * first block is changed</b>.
     */
    public static byte[] capture(World world, int originX, int originY, int originZ,
                                 int sizeX, int sizeY, int sizeZ) throws IOException {
        Map<String, Integer> lookup = new HashMap<>();
        List<String> palette = new ArrayList<>();
        int[] indices = new int[sizeX * sizeY * sizeZ];

        int i = 0;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    String data = world.getBlockAt(originX + x, originY + y, originZ + z)
                            .getBlockData().getAsString();
                    Integer id = lookup.get(data);
                    if (id == null) {
                        id = palette.size();
                        lookup.put(data, id);
                        palette.add(data);
                    }
                    indices[i++] = id;
                }
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(FORMAT);
            out.writeInt(sizeX);
            out.writeInt(sizeY);
            out.writeInt(sizeZ);
            out.writeInt(palette.size());
            for (String entry : palette) {
                out.writeUTF(entry);
            }
            // A site's palette is a few dozen entries, so a short per block is plenty and
            // halves the blob before GZIP even sees it.
            boolean wide = palette.size() > Short.MAX_VALUE;
            out.writeBoolean(wide);
            for (int index : indices) {
                if (wide) {
                    out.writeInt(index);
                } else {
                    out.writeShort(index);
                }
            }
        }
        return bytes.toByteArray();
    }

    /**
     * Put the captured blocks back.
     *
     * <p>Physics is suppressed on every write. Restoring with physics on would let a snapshot
     * of hanging sand fall, water re-flow, and torches pop off mid-restore — the region would
     * settle into something that is not what was captured.
     *
     * @return true if the whole region was written
     */
    public static boolean restore(World world, int originX, int originY, int originZ,
                                  byte[] blob) {
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new ByteArrayInputStream(blob)))) {
            int format = in.readInt();
            if (format != FORMAT) {
                Bukkit.getLogger().warning("[HCM] Courier site snapshot format " + format
                        + " is not readable by this build (expected " + FORMAT + ").");
                return false;
            }
            int sizeX = in.readInt();
            int sizeY = in.readInt();
            int sizeZ = in.readInt();
            int paletteSize = in.readInt();
            BlockData[] palette = new BlockData[paletteSize];
            for (int p = 0; p < paletteSize; p++) {
                String encoded = in.readUTF();
                try {
                    palette[p] = Bukkit.createBlockData(encoded);
                } catch (IllegalArgumentException e) {
                    // A block type this server no longer knows (a removed mod, a version
                    // change). Air is the safe substitute: it never leaves a solid block
                    // where the player has to dig one out.
                    palette[p] = Bukkit.createBlockData(org.bukkit.Material.AIR);
                }
            }
            boolean wide = in.readBoolean();

            // Containers first: clearing a chest before the block under it is replaced stops
            // the placed house's contents dropping as items when its blocks are overwritten.
            clearContainers(world, originX, originY, originZ, sizeX, sizeY, sizeZ);

            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    for (int x = 0; x < sizeX; x++) {
                        int index = wide ? in.readInt() : in.readShort();
                        if (index < 0 || index >= paletteSize) {
                            continue;
                        }
                        world.getBlockAt(originX + x, originY + y, originZ + z)
                                .setBlockData(palette[index], false);
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            Bukkit.getLogger().warning("[HCM] Courier site restore failed: " + e);
            return false;
        }
    }

    /**
     * The first thing in the region that makes it unsafe to build on, described — or null if the
     * ground is clear.
     *
     * <p>Checked <b>before</b> a site is committed to, and the reasoning is the same for every
     * case it catches: a block snapshot restores <i>block data</i> and nothing else. It cannot
     * carry a chest's contents, so a region holding one must not be touched — somebody's
     * unclaimed storage is still somebody's. And {@code avoid} names block types that are
     * usually a marker for something the plugin cannot see: a player head out in a field is
     * either a decoration or a death-storage grave (GravesX and friends), and burying either
     * behind a wall for the length of a delivery is not worth the reroll it costs to move.
     *
     * <p>Returns a description rather than a boolean so a server that keeps refusing to build
     * somewhere can say why.
     */
    public static String blockingFeature(World world, int originX, int originY, int originZ,
                                         int sizeX, int sizeY, int sizeZ,
                                         java.util.Set<org.bukkit.Material> avoid) {
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    Block block = world.getBlockAt(originX + x, originY + y, originZ + z);
                    if (block.getType().isAir()) {
                        continue;
                    }
                    if (avoid.contains(block.getType())) {
                        return block.getType().name().toLowerCase(java.util.Locale.ROOT)
                                .replace('_', ' ');
                    }
                    if (block.getState() instanceof Container) {
                        return "a container";
                    }
                }
            }
        }
        return null;
    }

    /**
     * Empty every container in the region and drop its loot table.
     *
     * <p>Run on the house we just placed, not on the field we captured. Vanilla village
     * templates ship chests and barrels carrying loot tables, and a building that reappears
     * at the end of every delivery would turn village loot into a per-run item faucet — which
     * is exactly the kind of thing §3.1 refuses. Clearing the table costs the house nothing;
     * the furniture is still there to look at.
     */
    public static void clearContainers(World world, int originX, int originY, int originZ,
                                       int sizeX, int sizeY, int sizeZ) {
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    Block block = world.getBlockAt(originX + x, originY + y, originZ + z);
                    if (block.getType().isAir()) {
                        continue;
                    }
                    BlockState state = block.getState();
                    if (state instanceof org.bukkit.loot.Lootable lootable) {
                        lootable.setLootTable(null);
                        state.update(true, false);
                    }
                    if (state instanceof Container container) {
                        container.getInventory().clear();
                        state.update(true, false);
                    }
                }
            }
        }
    }
}
