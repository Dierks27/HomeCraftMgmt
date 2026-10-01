package com.dierks.homecraft.games;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A built-in void world for the Games (LAYOUT-VOID-ADDENDUM item 1, LAYOUT-SPEC §3.8): nothing
 * below the courses but sky, so a player on one sees only that course.
 *
 * <p>The owner makes one with {@code /mv create sky normal -g HomeCraftManagement} (Multiverse 4 and
 * 5; {@code -g Plugin[:id]} is standard Bukkit, and the id is ignored): the plugin's
 * {@code getDefaultWorldGenerator} hands out a {@link Generator}. It generates nothing at all (no
 * noise, surface, caves, decorations, mobs or structures, and the {@code minecraft:the_void} biome
 * everywhere) except a small {@link #platform()} at the fixed spawn (0, 100, 0): 5 x 5 of smooth
 * stone under it and one light block above head height, placed by the generator itself so it is
 * there from the world's first load. Anyone arriving at the spawn stands on something; anyone who
 * walks off a course is caught by the game session's own "fell out of the world" check long before
 * vanilla's void damage (64 blocks under the floor).
 *
 * <p>The generator isn't stored with the world: at every start Multiverse makes it again and asks
 * Bukkit for this plugin's generator by name, and Bukkit hands out only an enabled plugin's. So
 * plugin.yml loads this plugin before Multiverse-Core ({@code loadbefore}); a chunk first made while
 * the generator was missing would keep vanilla ground for good ({@code /hcm games check} says
 * "isn't a void world").
 *
 * <p>The platform and the spawn are pure data here ({@link #platform()}, {@link #inChunk}) and tested
 * without a server; only {@link Generator} touches Bukkit. The platform is hundreds of chunks from
 * every place the games build, far past {@code Regions.CLEARANCE}.
 */
public final class VoidWorld {

    /** The fixed spawn block (the player's feet): x, y, z. */
    public static final int SPAWN_X = 0;
    public static final int SPAWN_Y = 100;
    public static final int SPAWN_Z = 0;
    /** The platform reaches this far from the spawn column on x and z: 5 x 5. */
    public static final int PLATFORM_RADIUS = 2;
    /** The platform's floor block, one under the spawn. */
    public static final String FLOOR = "minecraft:smooth_stone";
    /** The light above the spawn, out of reach of anyone's head (and never in the way: it has no collision). */
    public static final String LIGHT = "minecraft:light[level=15]";
    /** Where the light stands above the spawn block. */
    public static final int LIGHT_ABOVE = 2;

    private VoidWorld() {
    }

    /**
     * One block the generator places.
     *
     * @param data the block as a block-data string ({@code minecraft:smooth_stone})
     */
    public record Block(int x, int y, int z, String data) {
    }

    /** Every block of the spawn platform: the 5 x 5 floor under the spawn, then the light above it. */
    public static List<Block> platform() {
        List<Block> out = new ArrayList<>();
        for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
            for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
                out.add(new Block(SPAWN_X + dx, SPAWN_Y - 1, SPAWN_Z + dz, FLOOR));
            }
        }
        out.add(new Block(SPAWN_X, SPAWN_Y + LIGHT_ABOVE, SPAWN_Z, LIGHT));
        return List.copyOf(out);
    }

    /** The platform's blocks that fall in chunk ({@code chunkX}, {@code chunkZ}), in world coordinates. */
    public static List<Block> inChunk(int chunkX, int chunkZ) {
        List<Block> out = new ArrayList<>();
        for (Block b : platform()) {
            if (b.x() >> 4 == chunkX && b.z() >> 4 == chunkZ) {
                out.add(b);
            }
        }
        return out;
    }

    /** Whether {@code generator} is this plugin's void generator (by class name: safe across a plugin reload). */
    public static boolean ours(ChunkGenerator generator) {
        return generator != null && generator.getClass().getName().equals(Generator.class.getName());
    }

    /** The biomes of a void world: {@code minecraft:the_void} everywhere. */
    public static BiomeProvider biomes() {
        return new VoidBiomes();
    }

    /**
     * The generator {@code /mv create <world> normal -g HomeCraftManagement} gets. Stateless apart
     * from a cache of the two block states, so it is safe on any generation thread, and it needs
     * nothing of the plugin: Bukkit may ask for it before the plugin is enabled (a world named in
     * {@code bukkit.yml}).
     */
    public static final class Generator extends ChunkGenerator {

        private final Map<String, BlockData> data = new ConcurrentHashMap<>();

        @Override
        public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ,
                                    @NotNull ChunkData chunk) {
            for (Block b : inChunk(chunkX, chunkZ)) {
                if (b.y() >= chunk.getMinHeight() && b.y() < chunk.getMaxHeight()) {
                    BlockData state = data.computeIfAbsent(b.data(), Bukkit::createBlockData);
                    chunk.setBlock(b.x() & 15, b.y(), b.z() & 15, state);
                }
            }
        }

        @Override
        public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
            return biomes();
        }

        @Override
        public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
            return new Location(world, SPAWN_X + 0.5, SPAWN_Y, SPAWN_Z + 0.5);
        }

        @Override
        public boolean shouldGenerateNoise() {
            return false;
        }

        @Override
        public boolean shouldGenerateSurface() {
            return false;
        }

        @Override
        public boolean shouldGenerateCaves() {
            return false;
        }

        @Override
        public boolean shouldGenerateDecorations() {
            return false;
        }

        @Override
        public boolean shouldGenerateMobs() {
            return false;
        }

        @Override
        public boolean shouldGenerateStructures() {
            return false;
        }
    }

    /** {@code minecraft:the_void} everywhere. */
    private static final class VoidBiomes extends BiomeProvider {

        @Override
        public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
            return Biome.THE_VOID;
        }

        @Override
        public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
            return List.of(Biome.THE_VOID);
        }
    }
}
