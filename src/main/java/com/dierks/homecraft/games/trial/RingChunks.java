package com.dierks.homecraft.games.trial;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Sky Rings' chunks, held in memory for as long as someone flies the course (beside {@link TrackChunks},
 * which loads a race track's chunks before it is read).
 *
 * <p>Why: Sky Rings' legs run 80 to 120 blocks across a 512-wide square, and a ring is blocks, seen only in
 * a chunk the server has loaded and sent. So when a run is armed every chunk column a ring's frame touches,
 * and the tower's, is loaded off the main thread and given a plugin ticket: the rings are then already in
 * memory and show the moment they come into the player's view, instead of loading as they fly at them.
 * The tickets go when the run finishes or ends (left, quit, session over, the game stopped). Two players
 * on one course share a chunk's ticket: it is counted, and goes with the last of them. A load that
 * comes in after its run ended takes no ticket. Ring detection never needed the chunk (it is geometric,
 * {@link Progress}); this is only for the eye. About 80 void columns a course.
 *
 * <p>Pure but for {@link #server}, the thin piece over Paper, so the counting is tested without a server.
 * The tickets are the plugin's own, as a build's are; a build only ever works the half nobody plays.
 */
public final class RingChunks {

    /**
     * How far round a ring's centre its frame can reach: the checkpoint is the frame's radius less one, and
     * the frame is half a block thick.
     */
    static final double FRAME_GROW = 1.5;
    /** How far round the start the tower reaches: the 5 x 5 platform and its 3-block diving board. */
    static final double TOWER_REACH = 8;

    /** The least of a world's chunk tickets, so the rule is tested without a server. */
    public interface Tickets {
        /** Load the chunk off the main thread and hold it, if {@code still} says it is still wanted then. */
        void hold(String world, int cx, int cz, BooleanSupplier still);

        /** Let the chunk go. */
        void release(String world, int cx, int cz);
    }

    /** One chunk of one world. */
    record Chunk(String world, int cx, int cz) {
    }

    private final Tickets tickets;
    private final Map<UUID, List<Chunk>> held = new HashMap<>();
    private final Map<Chunk, Integer> users = new HashMap<>();

    /** Chunks held through {@code tickets}. */
    public RingChunks(Tickets tickets) {
        this.tickets = tickets;
    }

    /**
     * Hold {@code course}'s ring and tower chunks for {@code player}'s run: a Sky Rings course (an elytra
     * course with a rocket budget) only. What the player held before is let go first.
     */
    public void arm(UUID player, Course course) {
        release(player);
        if (!wanted(course)) {
            return;
        }
        List<Chunk> mine = new ArrayList<>();
        for (int[] c : columns(course)) {
            Chunk ch = new Chunk(course.world(), c[0], c[1]);
            mine.add(ch);
            if (users.merge(ch, 1, Integer::sum) == 1) {
                tickets.hold(ch.world(), ch.cx(), ch.cz(), () -> users.containsKey(ch));
            }
        }
        held.put(player, mine);
    }

    /** Let go of what {@code player}'s run held; a chunk another run still holds stays. */
    public void release(UUID player) {
        List<Chunk> mine = held.remove(player);
        if (mine == null) {
            return;
        }
        for (Chunk ch : mine) {
            Integer left = users.computeIfPresent(ch, (k, n) -> n > 1 ? n - 1 : null);
            if (left == null) {
                tickets.release(ch.world(), ch.cx(), ch.cz());
            }
        }
    }

    /** Let go of everything (the game stopped). */
    public void releaseAll() {
        for (UUID player : new ArrayList<>(held.keySet())) {
            release(player);
        }
    }

    /** How many chunks are held now, for every run together. */
    int holding() {
        return users.size();
    }

    /** Whether a course's chunks are held during a run: Sky Rings (an elytra course with a rocket budget). */
    static boolean wanted(Course course) {
        return course != null && course.kind() == TrialKind.ELYTRA && course.elytra() != null
                && !course.world().isBlank();
    }

    /**
     * The chunk columns ({x, z}) a course's rings and tower touch, each once: a square of
     * {@link #FRAME_GROW} past each ring's checkpoint radius, and {@link #TOWER_REACH} round the start.
     */
    static List<int[]> columns(Course course) {
        Set<Long> seen = new LinkedHashSet<>();
        List<int[]> out = new ArrayList<>();
        if (course.start() != null) {
            square(course.start().x(), course.start().z(), TOWER_REACH, seen, out);
        }
        for (Course.Mark m : course.targets()) {
            square(m.x(), m.z(), m.radius() + FRAME_GROW, seen, out);
        }
        return out;
    }

    private static void square(double x, double z, double reach, Set<Long> seen, List<int[]> out) {
        int x0 = Math.floorDiv((int) Math.floor(x - reach), 16);
        int x1 = Math.floorDiv((int) Math.floor(x + reach), 16);
        int z0 = Math.floorDiv((int) Math.floor(z - reach), 16);
        int z1 = Math.floorDiv((int) Math.floor(z + reach), 16);
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                if (seen.add(TrackChunks.key(cx, cz))) {
                    out.add(new int[]{cx, cz});
                }
            }
        }
    }

    /**
     * {@link Tickets} on the server: Paper's async load, then {@code plugin}'s ticket on the main thread if the
     * chunk is still wanted; the ticket removed on release. Never a load on the main thread, and never a throw
     * out of a run: a chunk that isn't held only loads as the player flies at it, as it always did.
     */
    public static Tickets server(Supplier<Plugin> plugin) {
        return new Tickets() {
            @Override
            public void hold(String world, int cx, int cz, BooleanSupplier still) {
                try {
                    Plugin pl = plugin.get();
                    World w = Bukkit.getWorld(world);
                    if (pl == null || w == null) {
                        return;
                    }
                    w.getChunkAtAsync(cx, cz, true).whenComplete((chunk, error) -> {
                        Runnable back = () -> {
                            if (error == null && chunk != null && pl.isEnabled() && still.getAsBoolean()) {
                                try {
                                    chunk.addPluginChunkTicket(pl);
                                } catch (RuntimeException e) {
                                    // not held: it loads as the player flies at it
                                }
                            }
                        };
                        if (Bukkit.isPrimaryThread()) {
                            back.run();
                        } else if (pl.isEnabled()) {
                            Bukkit.getScheduler().runTask(pl, back);
                        }
                    });
                } catch (RuntimeException | LinkageError e) {
                    // no server (a test) or the world went: nothing held
                }
            }

            @Override
            public void release(String world, int cx, int cz) {
                try {
                    Plugin pl = plugin.get();
                    World w = Bukkit.getWorld(world);
                    if (pl != null && w != null) {
                        w.removePluginChunkTicket(cx, cz, pl);
                    }
                } catch (RuntimeException | LinkageError e) {
                    // the plugin is going away: Paper drops its tickets anyway
                }
            }
        };
    }
}
