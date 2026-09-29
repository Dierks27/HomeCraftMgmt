package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;

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

    DropperHooks(TimeTrials trials) {
        this.trials = trials;
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

    /** Something else went wrong for the run (the void, a fall past the floor): a bonk. */
    void bonk(Player p, TrialRun run, DropperRules.Why why) {
        if (run.drop != null) {
            run.drop.bonk(port(p, run), why);
        }
    }

    /**
     * A fall-damage event (the kit guard cancelled it, so this sees it at MONITOR with the cancelled
     * ones): a landing on anything but water, a bonk.
     */
    void hurt(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) {
            return;
        }
        TrialRun run = trials.run(p.getUniqueId());
        if (run != null && run.drop != null) {
            run.drop.bonk(port(p, run), DropperRules.Why.FALL_DAMAGE);
        }
    }

    /** Time Trials' "send back" for a dropper: the top of the level it is on, never a bonk. */
    boolean back(Player p, TrialRun run) {
        return run.drop != null && run.drop.back(port(p, run));
    }

    /**
     * The last splash was judged: the bonk line, the splash title (with its stars on a Fresh course
     * that counted), and a clean counted drop tells the achievements ({@code game_dropper_clean}).
     */
    void finished(Player p, TrialRun run, long ms, boolean counted, int stars) {
        DropperRun d = run.drop;
        if (d == null) {
            return;
        }
        p.sendMessage(Text.of(DropperText.bonks(d.bonks())));
        TimeTrials.title(p, DropperText.SPLASH_TITLE, DropperText.splashSubtitle(ms, counted ? stars : 0), 50);
        if (counted && d.clean()) {
            String course = run.course.id();
            trials.games().tellProgress(g -> g.dropperClean(p, course));
        }
    }

    /** The run is over however it ended: the player collides as before (once; offline, nothing to do). */
    void end(TrialRun run, Player p) {
        if (run == null || run.drop == null || run.drop.ended()) {
            return;
        }
        if (p == null || !p.isOnline()) {
            run.drop.end(new Offline());
            return;
        }
        run.drop.end(port(p, run));
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

    /** The run's player as a {@link DropperRun.Port}. */
    private DropperRun.Port port(Player p, TrialRun run) {
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
                try {
                    switch (cue) {
                        case SPLASH -> p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_SPLASH, 0.7f, 1.2f);
                        case BONK -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.8f);
                        case CHOICE -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.4f);
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // a sound is decoration
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
            public void finish(long nanos) {
                trials.finish(p, run, nanos);
            }
        };
    }

    /** A player who has gone: collidability isn't saved with them, so there is nothing to give back. */
    private static final class Offline implements DropperRun.Port {
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
