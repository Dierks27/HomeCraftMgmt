package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.LiveBlocks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * {@link WorldPort} over a real Paper world: the ONLY file in Fresh Courses that touches chunk
 * loads, chunk tickets, snapshots, {@code setBlockData}, sign sides, waxing and game rules
 * (GEN-SPEC §8.8 risk 1), so an API difference between Paper versions lands here and nowhere else.
 *
 * <p>Chunks are loaded with {@code getChunkAtAsync} (as the courier's buildings are), a few at a
 * time, and held with a plugin chunk ticket until the job lets go (Paper drops a plugin's tickets
 * when it is disabled). Snapshots skip empty sections: a section index is counted from the world's
 * floor, and the moment a snapshot calls a section empty that holds a block the plan put there,
 * that answer is never trusted again this run ({@link #distrustEmptySections}). Blocks are set
 * with {@code setBlockData(data, false)}: no physics, no updates, no drops. Game rules are found by
 * their registry keys, and a key this version doesn't know is skipped with one line.
 */
public final class BukkitWorldPort implements WorldPort {

    /** Whether "this section is empty" answers are trusted (see the class notes). */
    private static volatile boolean trustEmpty = true;

    private final Plugin plugin;
    private final World world;
    private final Logger log;
    private final Map<String, String> canonical = new HashMap<>();
    private final Map<String, BlockData> data = new HashMap<>();

    public BukkitWorldPort(Plugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        this.log = plugin.getLogger();
    }

    @Override
    public String name() {
        return world.getName();
    }

    @Override
    public int minHeight() {
        return world.getMinHeight();
    }

    @Override
    public int maxHeight() {
        return world.getMaxHeight();
    }

    @Override
    public Box border() {
        WorldBorder b = world.getWorldBorder();
        double half = b.getSize() / 2.0;
        Location c = b.getCenter();
        int minX = (int) Math.max(-30_000_000, Math.ceil(c.getX() - half));
        int maxX = (int) Math.min(30_000_000, Math.floor(c.getX() + half) - 1);
        int minZ = (int) Math.max(-30_000_000, Math.ceil(c.getZ() - half));
        int maxZ = (int) Math.min(30_000_000, Math.floor(c.getZ() + half) - 1);
        if (maxX < minX || maxZ < minZ) {
            return null;
        }
        return new Box(minX, world.getMinHeight(), minZ, maxX, world.getMaxHeight() - 1, maxZ);
    }

    @Override
    public int[] spawn() {
        Location s = world.getSpawnLocation();
        return new int[]{s.getBlockX(), s.getBlockY(), s.getBlockZ()};
    }

    @Override
    public void load(int cx, int cz, Consumer<Boolean> done) {
        world.getChunkAtAsync(cx, cz, true).whenComplete((chunk, error) -> {
            Runnable back = () -> {
                boolean ok = error == null && chunk != null;
                if (ok) {
                    try {
                        chunk.addPluginChunkTicket(plugin);
                    } catch (RuntimeException e) {
                        ok = false;
                    }
                }
                done.accept(ok);
            };
            if (Bukkit.isPrimaryThread()) {
                back.run();
            } else if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, back);
            }
        });
    }

    @Override
    public void release(int cx, int cz) {
        try {
            world.removePluginChunkTicket(cx, cz, plugin);
        } catch (RuntimeException e) {
            // the plugin is going away: Paper drops its tickets anyway
        }
    }

    @Override
    public ChunkView snapshot(int cx, int cz) {
        if (!world.isChunkLoaded(cx, cz)) {
            return null;
        }
        Chunk chunk = world.getChunkAt(cx, cz);
        ChunkSnapshot s = chunk.getChunkSnapshot(false, false, false, false); // no heights, biomes or light
        int floor = world.getMinHeight();
        return new ChunkView() {
            @Override
            public boolean sectionEmpty(int y) {
                if (!trustEmpty) {
                    return false;
                }
                try {
                    return s.isSectionEmpty((y - floor) >> 4);
                } catch (RuntimeException e) {
                    return false;
                }
            }

            @Override
            public boolean air(int x, int y, int z) {
                return s.getBlockType(x & 15, y, z & 15).isAir();
            }

            @Override
            public String block(int x, int y, int z) {
                return s.getBlockData(x & 15, y, z & 15).getAsString();
            }
        };
    }

    @Override
    public String canonical(String blockData) {
        String c = canonical.get(blockData);
        if (c == null) {
            BlockData d = Bukkit.createBlockData(blockData);
            c = d.getAsString();
            canonical.put(blockData, c);
            data.put(c, d);
        }
        return c;
    }

    @Override
    public void set(int x, int y, int z, String state) {
        BlockData d = data.get(state);
        if (d == null) {
            d = Bukkit.createBlockData(state);
            data.put(state, d);
        }
        world.getBlockAt(x, y, z).setBlockData(d.clone(), false);
    }

    @Override
    public void sign(int x, int y, int z, List<String> lines) {
        BlockState st = world.getBlockAt(x, y, z).getState();
        if (!(st instanceof Sign sign)) {
            return;
        }
        SignSide front = sign.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) {
            front.line(i, Component.text(i < lines.size() && lines.get(i) != null ? lines.get(i) : ""));
        }
        front.setGlowingText(true);
        sign.setWaxed(true);
        sign.update(true, false);
    }

    @Override
    public List<String> signLines(int x, int y, int z) {
        Block b = world.getBlockAt(x, y, z);
        if (!(b.getState() instanceof Sign sign)) {
            return null;
        }
        SignSide front = sign.getSide(Side.FRONT);
        if (!front.isGlowingText() || !sign.isWaxed()) {
            return null; // not the way we left it: written again
        }
        List<String> out = new ArrayList<>(4);
        for (Component line : front.lines()) {
            out.add(PlainTextComponentSerializer.plainText().serialize(line));
        }
        return out;
    }

    @Override
    public BallPhysics.Blocks ballBlocks() {
        return new LiveBlocks(world);
    }

    @Override
    public BallPhysics.Blocks ballBlocks(boolean sand) {
        return new LiveBlocks(world, sand);
    }

    @Override
    public void worldRules(Consumer<String> changed) {
        rule(changed, Boolean.FALSE, "spawn_mobs");
        rule(changed, Boolean.FALSE, "mob_griefing");
        rule(changed, 0, "fire_spread_radius_around_player");
        rule(changed, 0, "random_tick_speed");
        if (rule(changed, Boolean.FALSE, "advance_time") && Math.floorMod(world.getTime(), 24_000L) != 6000L) {
            world.setTime(6000L);
            changed.accept("time set to noon");
        }
        if (rule(changed, Boolean.FALSE, "advance_weather") && (world.hasStorm() || world.isThundering())) {
            world.setStorm(false);
            world.setThundering(false);
            changed.accept("weather cleared");
        }
    }

    /**
     * Set the first of {@code keys} this version knows to {@code value}; report it only when it
     * changed. @return whether a rule was found (and is now {@code value})
     */
    private boolean rule(Consumer<String> changed, Object value, String... keys) {
        for (String k : keys) {
            GameRule<?> r;
            try {
                r = Registry.GAME_RULE.get(NamespacedKey.minecraft(k));
            } catch (RuntimeException e) {
                r = null;
            }
            if (r != null && r.getType().isInstance(value)) {
                set(r, value, k, changed);
                return true;
            }
        }
        log.info("Fresh Courses: this server has no game rule " + String.join(" or ", keys) + " - skipped");
        return false;
    }

    private <T> void set(GameRule<T> rule, Object value, String key, Consumer<String> changed) {
        T v = rule.getType().cast(value);
        T was = world.getGameRuleValue(rule);
        if (!v.equals(was)) {
            world.setGameRule(rule, v);
            changed.accept("game rule " + key + " " + was + " -> " + v);
        }
    }

    @Override
    public void distrustEmptySections() {
        if (trustEmpty) {
            trustEmpty = false;
            log.warning("Fresh Courses: a chunk snapshot said a section was empty that isn't; every section is"
                    + " now read block by block (slower, still correct).");
        }
    }
}
