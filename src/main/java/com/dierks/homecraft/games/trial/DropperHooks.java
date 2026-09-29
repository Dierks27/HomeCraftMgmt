package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Where Time Trials meets the Dropper on the server (EVENTS-DROPPER-SPEC §B.1.7, the practice drop
 * of EVENTS-OWNER-DECISIONS D3): the one call per hook {@link TimeTrials} makes for a dropper run,
 * the player-side {@link DropperRun.Port}, the Dropper's kits and the fall-damage bonk. Every rule
 * is {@link DropperRun}'s (pure, tested); this class only reads the player and does what it says.
 *
 * <p>Everything here runs inside Time Trials' own handlers, ticks and kit clicks, so it is inside
 * the game's guard like the rest of the game: a bug closes Time Trials, never the server.
 */
final class DropperHooks {

    private final TimeTrials trials;
    /** The games' no-push team ({@code GamesService.noPush()}), or {@code null}: none (a test). */
    private final Supplier<NoPush> noPush;
    /** A run's player as a {@link DropperRun.Port}: the live one, or a test's. */
    private final BiFunction<Player, TrialRun, DropperRun.Port> ports;
    /** The pools the flow guard holds in ({@link #pools}), the course list they came from, and when. */
    private static final long POOLS_EVERY_NANOS = 1_000_000_000L;
    private DropperPools pools = DropperPools.NONE;
    private List<Course> poolsFrom;
    private long poolsRead;

    DropperHooks(TimeTrials trials) {
        this(trials, null, null);
    }

    /**
     * With the no-push team and the player-side port a test gives ({@code null}: the live ones), so
     * the hooks' routing runs with no server.
     */
    DropperHooks(TimeTrials trials, Supplier<NoPush> noPush, BiFunction<Player, TrialRun, DropperRun.Port> ports) {
        this.trials = trials;
        this.noPush = noPush != null ? noPush : () -> trials == null ? null : trials.games().noPush();
        this.ports = ports != null ? ports : this::livePort;
    }

    // ---- the hooks ------------------------------------------------------------------------------

    /** A dropper run begins on level 1's ledge: not collidable, and the practice drop's offer (or not). */
    DropperRun start(Player p, TrialRun run) {
        return DropperRun.start(run, port(p, run), DropperRun.offers(trials.settings()));
    }

    /** The countdown's tick: true while the offer or the practice drop holds it (nothing counts down). */
    boolean countdown(Player p, TrialRun run) {
        DropperRun d = run.drop;
        if (d == null || !d.holdsCountdown()) {
            return false;
        }
        d.waiting(port(p, run), feet(p), inWater(p));
        return true;
    }

    /** The running tick: the hop, and the landing watch. */
    void running(Player p, TrialRun run) {
        if (run.drop != null) {
            run.drop.running(port(p, run), feet(p), inWater(p));
        }
    }

    /** A move: the practice drop's splash, or the timed run's splash, hop landing and floor. */
    void moved(Player p, TrialRun run, Location to) {
        DropperRun d = run.drop;
        if (d == null || to == null || to.getWorld() == null || !to.getWorld().getName().equals(run.course.world())) {
            return;
        }
        Point at = new Point(to.getX(), to.getY(), to.getZ());
        if (d.stage() == DropperRun.Stage.PRACTICE) {
            d.practiceMove(port(p, run), at);
        } else if (run.running()) {
            d.moved(port(p, run), at, System.nanoTime());
        }
    }

    /**
     * A Dropper kit item: the offer's two, the practice drop's "Start timed run", and "Back to the
     * top" (a bonk). False when it isn't the Dropper's to answer (Time Trials says "Wait for the
     * countdown." as for any course).
     */
    boolean kit(Player p, TrialRun run, String action) {
        DropperRun d = run.drop;
        if (d == null || action == null) {
            return false;
        }
        switch (action) {
            case DropperRun.PRACTICE_ACTION -> d.choose(port(p, run), true);
            case DropperRun.STRAIGHT_ACTION -> d.choose(port(p, run), false);
            case DropperRun.TIMED_ACTION -> d.skipPractice(port(p, run));
            case DropperRun.BACK_ACTION -> {
                if (!run.running() && d.stage() != DropperRun.Stage.PRACTICE) {
                    return false;
                }
                d.bonk(port(p, run), DropperRules.Why.KIT);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Something else went wrong for the run (the void, a fall past the floor): a bonk.
     *
     * @return whether it was one (false: not now, like while the run's own teleport is on its way)
     */
    boolean bonk(Player p, TrialRun run, DropperRules.Why why) {
        return run.drop != null && run.drop.bonk(port(p, run), why);
    }

    /**
     * A fall-damage event (the kit guard cancelled it, so this sees it at MONITOR with the cancelled
     * ones): a landing on anything but water, a bonk.
     */
    void hurt(EntityDamageEvent e) {
        hurt(e.getCause(), e.getEntity());
    }

    /** {@link #hurt(EntityDamageEvent)} by its cause and who was hurt: only a runner's fall is a bonk. */
    void hurt(EntityDamageEvent.DamageCause cause, Entity entity) {
        if (cause != EntityDamageEvent.DamageCause.FALL || !(entity instanceof Player p)) {
            return;
        }
        TrialRun run = trials.run(p.getUniqueId());
        if (run != null && run.drop != null) {
            run.drop.bonk(port(p, run), DropperRules.Why.FALL_DAMAGE);
        }
    }

    /**
     * A fluid flowing, in any world (Time Trials' own handler, so it works with Fresh Courses on or
     * off): one whose source is a Dropper course's water never moves, so breaking a pool's wall can't
     * spill it ({@link DropperPools}). Anywhere else is a quick no.
     */
    void flow(BlockFromToEvent e) {
        if (held(e, pools())) {
            e.setCancelled(true);
        }
    }

    /** Whether {@code e}'s fluid is a Dropper's water in {@code pools} (its source, {@code getBlock}). */
    static boolean held(BlockFromToEvent e, DropperPools pools) {
        Block from = e.getBlock();
        World w = from == null ? null : from.getWorld();
        return w != null && pools.covers(w.getName()) && pools.holds(w.getName(), from.getX(), from.getY(), from.getZ());
    }

    /** Every Dropper course's pools, read again at most once a second (the course list is cached). */
    private DropperPools pools() {
        long now = System.nanoTime();
        if (poolsRead == 0 || now - poolsRead >= POOLS_EVERY_NANOS) {
            poolsRead = now;
            List<Course> courses = trials == null ? List.of() : trials.courses();
            if (courses != poolsFrom) {
                poolsFrom = courses;
                pools = DropperPools.of(courses);
            }
        }
        return pools;
    }

    /** Time Trials' "send back" for a dropper: the top of the level it is on, never a bonk. */
    boolean back(Player p, TrialRun run) {
        return run.drop != null && run.drop.back(port(p, run));
    }

    /**
     * The last splash was judged: the bonk line; for a run that counted, the splash title (with its
     * stars on a Fresh course), and a clean drop tells the achievements ({@code game_dropper_clean}).
     */
    void finished(Player p, TrialRun run, long ms, boolean counted, int stars) {
        DropperRun d = run.drop;
        if (d == null) {
            return;
        }
        p.sendMessage(Text.of(DropperText.bonks(d.bonks())));
        if (!counted) {
            return; // a test, void or stale run keeps Time Trials' own title, and never a clean drop
        }
        TimeTrials.title(p, DropperText.SPLASH_TITLE, DropperText.splashSubtitle(ms, stars), 50);
        if (d.clean()) {
            String course = run.course.id();
            trials.games().tellProgress(g -> g.dropperClean(p, course));
        }
    }

    /**
     * The run is over however it ended: off the no-push team and collidable as before (once). A player
     * who has gone is still taken off the team (it is saved with the world's scoreboard); there is no
     * collidability to give back (it isn't saved with them).
     */
    void end(TrialRun run, Player p) {
        if (run == null || run.drop == null || run.drop.ended()) {
            return;
        }
        if (p == null || !p.isOnline()) {
            run.drop.end(new Offline(team(), run.player));
            return;
        }
        run.drop.end(port(p, run));
    }

    private NoPush team() {
        try {
            return noPush == null ? null : noPush.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- the kits -------------------------------------------------------------------------------

    /** Hand the player one of the Dropper's kits: slot 0 (and 1), and "Leave game" in 8. */
    void giveKit(Player p, DropperRun.Kit kit) {
        PlayerInventory inv = p.getInventory();
        inv.setItem(1, null);
        switch (kit) {
            case OFFER -> {
                inv.setItem(0, item(DropperRun.PRACTICE_ACTION, Material.LIGHT_BLUE_DYE, DropperText.PRACTICE_ITEM,
                        DropperText.PRACTICE_LORE));
                inv.setItem(1, item(DropperRun.STRAIGHT_ACTION, Material.LIME_DYE, DropperText.STRAIGHT_ITEM,
                        DropperText.STRAIGHT_LORE));
            }
            case PRACTICE -> inv.setItem(0, item(DropperRun.TIMED_ACTION, Material.CLOCK, DropperText.TIMED_ITEM,
                    DropperText.TIMED_LORE));
            case DROP -> inv.setItem(0, item(DropperRun.BACK_ACTION, Material.RECOVERY_COMPASS, DropperText.BACK_ITEM,
                    DropperText.BACK_LORE));
        }
        inv.setItem(8, KitItems.item(trials, "leave", Material.OAK_DOOR, "&cLeave game", "&7Click twice to leave.",
                "&7Your things come back."));
        inv.setHeldItemSlot(0);
    }

    private ItemStack item(String action, Material m, String name, List<String> lore) {
        return KitItems.item(trials, action, m, name, lore.toArray(new String[0]));
    }

    // ---- the player's side ------------------------------------------------------------------------

    private static Point feet(Player p) {
        Location l = p.getLocation();
        return new Point(l.getX(), l.getY(), l.getZ());
    }

    private static boolean inWater(Player p) {
        try {
            return p.isInWater();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private DropperRun.Port port(Player p, TrialRun run) {
        return ports.apply(p, run);
    }

    /** The run's player as a {@link DropperRun.Port}. */
    private DropperRun.Port livePort(Player p, TrialRun run) {
        return new DropperRun.Port() {
            @Override
            public long tick() {
                return Bukkit.getCurrentTick();
            }

            @Override
            public boolean teleport(double[] stand) {
                World w = Bukkit.getWorld(run.course.world());
                if (w == null || stand == null) {
                    return false;
                }
                run.lastReset = Bukkit.getCurrentTick();
                return trials.move(p, run, new Location(w, stand[0], stand[1], stand[2], (float) stand[3],
                        (float) stand[4]));
            }

            @Override
            public void kit(DropperRun.Kit kit) {
                giveKit(p, kit);
            }

            @Override
            public void title(String big, String small, int stayTicks) {
                TimeTrials.title(p, big, small, stayTicks);
            }

            @Override
            public void actionBar(String line) {
                p.sendActionBar(Text.of(line));
            }

            @Override
            public void chat(String line) {
                p.sendMessage(Text.of(line));
            }

            @Override
            public void sound(DropperRun.Cue cue) {
                switch (cue) { // the plugin's sound vocabulary (Sounds never throws)
                    case SPLASH, CHOICE -> Sounds.received(p);
                    case BONK -> Sounds.refused(p);
                }
            }

            @Override
            public boolean collidable() {
                return p.isCollidable();
            }

            @Override
            public void collidable(boolean on) {
                p.setCollidable(on);
            }

            @Override
            public void noPush(boolean on) {
                NoPush team = team();
                if (team == null) {
                    return;
                }
                if (on) {
                    team.on(p);
                } else {
                    team.off(p);
                }
            }

            @Override
            public void finish(long nanos) {
                trials.finish(p, run, nanos);
            }
        };
    }

    /**
     * A player who has gone: collidability isn't saved with them, so there is nothing to give back,
     * but the no-push team is (the world's scoreboard), so they come off it by id.
     */
    private record Offline(NoPush team, UUID player) implements DropperRun.Port {

        @Override
        public void noPush(boolean on) {
            if (!on && team != null) {
                team.off(player, null);
            }
        }

        @Override
        public long tick() {
            return 0;
        }

        @Override
        public boolean teleport(double[] stand) {
            return false;
        }

        @Override
        public void kit(DropperRun.Kit kit) {
        }

        @Override
        public void title(String big, String small, int stayTicks) {
        }

        @Override
        public void actionBar(String line) {
        }

        @Override
        public void chat(String line) {
        }

        @Override
        public void sound(DropperRun.Cue cue) {
        }

        @Override
        public boolean collidable() {
            return true;
        }

        @Override
        public void collidable(boolean on) {
        }

        @Override
        public void finish(long nanos) {
        }
    }
}
