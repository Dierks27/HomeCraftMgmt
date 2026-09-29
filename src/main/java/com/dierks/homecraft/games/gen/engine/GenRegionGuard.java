package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.util.Text;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Nobody changes a generated course (GEN-SPEC §6 S6): inside any Daily Courses half, while the
 * {@code daily} game is open, every change is refused — for everyone, admins included — with
 * "This area is built by Daily Courses - use /hcm games gen".
 *
 * <p>Why admins too: the halves are rebuilt from their plans and verified block for block, so an
 * admin's "quick fix" would be undone at the next build or boot, and a block placed into an idle
 * half makes the next build's verify fail. To change a course, an admin uses the commands. The
 * existing Games-world guard (which exempts admins) is unchanged; this one only covers the halves.
 *
 * <p>Covered: placing and breaking, buckets, sign edits, hanging and taking down, placing boats,
 * carts and stands, fluids flowing in, blocks forming, spreading and fading, mobs and falling
 * blocks changing blocks, fire, pistons touching a half, and explosions (the blocks inside a half
 * are taken out of the list; the rest still go). WorldEdit can't be intercepted: the next boot or
 * {@code /hcm games gen rebuild} heals what it did.
 *
 * <p>The decisions are pure ({@link #refused}, {@link #pistonRefused}, {@link #spared}); the
 * handlers are registered through the game ({@link #register}), so they live and die with it.
 */
public final class GenRegionGuard {

    /** The refusal is shown at most this often per player. */
    static final long TELL_EVERY_MS = 2_000L;

    /** Where the halves are (the engine's {@code inArea}). */
    @FunctionalInterface
    public interface Area {
        boolean in(String world, int x, int y, int z);
    }

    /** A kind of change the guard sees. */
    public enum Change {
        PLACE, BREAK, BUCKET, SIGN, HANG, UNHANG, ENTITY_PLACE, FLOW_INTO, FORM, SPREAD, FADE, ENTITY_CHANGE, PISTON,
        EXPLODE, BURN, IGNITE
    }

    private final Map<UUID, Long> told = new HashMap<>();

    private GenRegionGuard() {
    }

    // ---- the decisions (pure) -------------------------------------------------------------------

    /**
     * Whether a change at a block is refused: when the block is inside a half. Nobody is exempt —
     * {@code admin} is here so the rule says so.
     */
    public static boolean refused(Change change, boolean inArea, boolean admin) {
        return change != null && inArea;
    }

    /**
     * Whether a piston push or pull is refused: the piston itself, a block it moves, or where that
     * block ends up, is inside a half.
     *
     * @param moved the blocks it moves {x, y, z}
     * @param dx    the direction they move
     */
    public static boolean pistonRefused(Area area, String world, int[] piston, List<int[]> moved, int dx, int dy,
                                        int dz) {
        if (piston != null && area.in(world, piston[0], piston[1], piston[2])) {
            return true;
        }
        if (piston != null && area.in(world, piston[0] + dx, piston[1] + dy, piston[2] + dz)) {
            return true; // the head reaches in
        }
        for (int[] b : moved) {
            if (area.in(world, b[0], b[1], b[2]) || area.in(world, b[0] + dx, b[1] + dy, b[2] + dz)) {
                return true;
            }
        }
        return false;
    }

    /** Which of an explosion's blocks are spared: those inside a half. */
    public static List<int[]> spared(Area area, String world, List<int[]> blocks) {
        List<int[]> out = new ArrayList<>();
        for (int[] b : blocks) {
            if (area.in(world, b[0], b[1], b[2])) {
                out.add(b);
            }
        }
        return out;
    }

    // ---- the handlers ---------------------------------------------------------------------------

    /**
     * Register the guard's handlers for {@code daily}: they run only while it is open, inside its
     * guard, and go when it stops. {@code area} is asked at the moment of each event.
     */
    public static void register(GamesService games, Game daily, Supplier<Area> area, Logger log) {
        GenRegionGuard g = new GenRegionGuard();
        EventPriority p = EventPriority.LOW;
        games.on(daily, BlockPlaceEvent.class, p, true,
                e -> g.player(area, e, Change.PLACE, e.getPlayer(), e.getBlock(), log));
        games.on(daily, BlockBreakEvent.class, p, true,
                e -> g.player(area, e, Change.BREAK, e.getPlayer(), e.getBlock(), log));
        games.on(daily, PlayerBucketEmptyEvent.class, p, true,
                e -> g.player(area, e, Change.BUCKET, e.getPlayer(), e.getBlock(), log));
        games.on(daily, PlayerBucketFillEvent.class, p, true,
                e -> g.player(area, e, Change.BUCKET, e.getPlayer(), e.getBlock(), log));
        games.on(daily, SignChangeEvent.class, p, true,
                e -> g.player(area, e, Change.SIGN, e.getPlayer(), e.getBlock(), log));
        games.on(daily, HangingPlaceEvent.class, p, true,
                e -> g.player(area, e, Change.HANG, e.getPlayer(), e.getEntity().getLocation().getBlock(), log));
        games.on(daily, HangingBreakEvent.class, p, true,
                e -> g.block(area, e, Change.UNHANG, e.getEntity().getLocation().getBlock(), log));
        games.on(daily, EntityPlaceEvent.class, p, true,
                e -> g.player(area, e, Change.ENTITY_PLACE, e.getPlayer(), e.getEntity().getLocation().getBlock(), log));
        games.on(daily, BlockFromToEvent.class, p, true, e -> g.block(area, e, Change.FLOW_INTO, e.getToBlock(), log));
        games.on(daily, BlockFormEvent.class, p, true, e -> g.block(area, e, Change.FORM, e.getBlock(), log));
        games.on(daily, BlockSpreadEvent.class, p, true, e -> g.block(area, e, Change.SPREAD, e.getBlock(), log));
        games.on(daily, BlockFertilizeEvent.class, p, true, e -> {
            for (org.bukkit.block.BlockState grown : e.getBlocks()) {
                g.block(area, e, Change.SPREAD, grown.getBlock(), log);
            }
        });
        games.on(daily, BlockFadeEvent.class, p, true, e -> g.block(area, e, Change.FADE, e.getBlock(), log));
        games.on(daily, BlockBurnEvent.class, p, true, e -> g.block(area, e, Change.BURN, e.getBlock(), log));
        games.on(daily, BlockIgniteEvent.class, p, true, e -> g.block(area, e, Change.IGNITE, e.getBlock(), log));
        games.on(daily, EntityChangeBlockEvent.class, p, true,
                e -> g.block(area, e, Change.ENTITY_CHANGE, e.getBlock(), log));
        games.on(daily, BlockPistonExtendEvent.class, p, true,
                e -> g.piston(area, e, e.getBlock(), e.getBlocks(), e.getDirection(), log));
        games.on(daily, BlockPistonRetractEvent.class, p, true,
                e -> g.piston(area, e, e.getBlock(), e.getBlocks(), e.getDirection(), log));
        games.on(daily, EntityExplodeEvent.class, p, true, e -> g.explode(area, e.blockList(), log));
        games.on(daily, BlockExplodeEvent.class, p, true, e -> g.explode(area, e.blockList(), log));
    }

    /** A player's change: refused inside a half, and they are told (at most every two seconds). */
    private void player(Supplier<Area> area, Cancellable e, Change change, Player player, Block block, Logger log) {
        try {
            if (block == null || !refused(change, in(area, block), player == null || player.isOp())) {
                return;
            }
            e.setCancelled(true);
            if (player != null) {
                long now = System.currentTimeMillis();
                Long last = told.get(player.getUniqueId());
                if (last == null || now - last >= TELL_EVERY_MS) {
                    told.put(player.getUniqueId(), now);
                    player.sendActionBar(Text.of(GenCopy.GUARDED));
                }
            }
        } catch (RuntimeException ex) {
            log.warning("Daily Courses: the area guard failed: " + ex);
        }
    }

    /** A change nobody made by hand (flowing, forming, mobs, fire): refused inside a half. */
    private void block(Supplier<Area> area, Cancellable e, Change change, Block block, Logger log) {
        try {
            if (block != null && refused(change, in(area, block), false)) {
                e.setCancelled(true);
            }
        } catch (RuntimeException ex) {
            log.warning("Daily Courses: the area guard failed: " + ex);
        }
    }

    private void piston(Supplier<Area> area, Cancellable e, Block piston, List<Block> blocks, BlockFace dir,
                        Logger log) {
        try {
            List<int[]> moved = new ArrayList<>();
            for (Block b : blocks) {
                moved.add(new int[]{b.getX(), b.getY(), b.getZ()});
            }
            if (pistonRefused(area.get(), piston.getWorld().getName(),
                    new int[]{piston.getX(), piston.getY(), piston.getZ()}, moved, dir.getModX(), dir.getModY(),
                    dir.getModZ())) {
                e.setCancelled(true);
            }
        } catch (RuntimeException ex) {
            log.warning("Daily Courses: the area guard failed: " + ex);
        }
    }

    private void explode(Supplier<Area> area, List<Block> blocks, Logger log) {
        try {
            Area a = area.get();
            blocks.removeIf(b -> a.in(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        } catch (RuntimeException ex) {
            log.warning("Daily Courses: the area guard failed: " + ex);
        }
    }

    private static boolean in(Supplier<Area> area, Block b) {
        return area.get().in(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }
}
