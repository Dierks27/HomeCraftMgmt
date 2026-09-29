package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.gui.games.trial.WarmupChoiceMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Warm-ups before a counted run (owner decision D3), the server side; the words and the clock are
 * {@link Warmup}'s, the flags {@code TrialRun}'s.
 *
 * <p><b>The flow.</b> Starting a course (Start on its screen, {@code /hcm play <course>}, a sign,
 * Play again) opens a small choice first while {@code games.trials.warmup_seconds} is above 0:
 * "Warm up (3:00)" or "Go straight to the timed run". Going straight is the run exactly as it
 * always was. Warming up takes the player to the start as usual, but the run begins in its one
 * warm-up: the course runs freely, checkpoints and Back to checkpoint work, and every finish is a
 * "warm-up lap" that sends them round again. Nothing in it is ever timed for the record, submitted,
 * paid or counted for the Weekly Cup: TimeTrials' finish hands a warm-up lap here before anything
 * is judged. When the time is up, or the player taps "Start timed run", they go back to the start
 * and the normal 3-2-1 begins; the run can't warm up again.
 *
 * <p>A race's shared warm-up (D3, D4) uses the same laps and action bar; it ends when the
 * coordinator sends everyone to the grid ({@link RaceMode#regrid}), and its kit item is "Ready".
 */
final class Warmups {

    private final TimeTrials trials;
    /** Players who chose "Warm up" and are on their way to the start. */
    private final Set<UUID> wanted = new HashSet<>();

    Warmups(TimeTrials trials) {
        this.trials = trials;
    }

    /**
     * Offer the choice when warm-ups are on: true when the choice screen opened (the start waits for
     * it); false to start at once, as before.
     */
    boolean offer(Player player, Course c) {
        if (player == null || c == null || !Warmup.offered(trials.settings(), false, c.kind())
                || trials.raceMode().holds().refusal(c.id()) != null) {
            return false; // no choice to make: the start goes ahead (or is refused) as before
        }
        new WarmupChoiceMenu(trials.plugin(), trials, c, player).open(player);
        return true;
    }

    /** A choice on the screen: the gate again, the course, then the run (warming up first or not). */
    void choose(Player player, String courseId, boolean warmUp) {
        Refusal r = trials.games().canOpen(player, trials);
        if (r != null) {
            trials.games().tell(player, r);
            return;
        }
        Course c = trials.openCourse(courseId);
        if (c == null) {
            trials.games().tell(player, Refusal.of("That course is closed right now."));
            return;
        }
        if (warmUp && Warmup.offered(trials.settings(), false, c.kind())) {
            wanted.add(player.getUniqueId());
        } else {
            wanted.remove(player.getUniqueId());
        }
        trials.begin(player, c, false);
    }

    /** The start didn't happen: forget the choice. */
    void forget(Player player) {
        if (player != null) {
            wanted.remove(player.getUniqueId());
        }
    }

    /** The run just began at the start: its warm-up, if the player chose one. */
    void begin(Player p, TrialRun run) {
        if (!wanted.remove(p.getUniqueId()) || run.test || run.race != null) {
            return;
        }
        int seconds = trials.settings().warmupSeconds();
        if (!Warmup.begin(run, Bukkit.getCurrentTick(), seconds, TimeTrials.position(p, run), System.nanoTime())) {
            return;
        }
        p.getInventory().setItem(Warmup.KIT_SLOT, KitItems.item(trials, Warmup.TIMED, Material.LIME_DYE,
                Warmup.TIMED_NAME, "&7Ends the warm-up now.", "&7Then the 3-2-1 at the start,",
                "&7and your timed run."));
        p.sendMessage(Text.of(Warmup.started(seconds)));
        p.sendMessage(Text.of(Warmup.HOW_TO_END));
    }

    /**
     * A warm-up tick (from the running tick): a solo warm-up whose time is up goes back to the start
     * for the 3-2-1. True when it ended here. A race's ends only when the racers go to the grid.
     */
    boolean tick(Player p, TrialRun run, long now) {
        if (run.race != null || !run.warmupOver(now)) {
            return false;
        }
        end(p, run, false);
        return true;
    }

    /** The action bar in a warm-up: "Warm-up 2:14 left - not counted". */
    String bar(TrialRun run, long now) {
        if (run.race != null) {
            if (run.warmupReady) {
                return Warmup.BAR_READY;
            }
            return run.warmupEnds > now ? Warmup.bar(Warmup.secondsLeft(now, run.warmupEnds)) : Warmup.BAR_TO_GRID;
        }
        return Warmup.bar(Warmup.secondsLeft(now, run.warmupEnds));
    }

    /** "Start timed run": end a solo warm-up early. */
    void timed(Player p, TrialRun run) {
        if (run != null && run.warmup && run.race == null) {
            end(p, run, true);
        }
    }

    /** "Ready" in a race's shared warm-up: tell the coordinator, once. */
    void ready(Player p, TrialRun run) {
        if (run == null || !run.warmup || run.race == null || run.warmupReady) {
            return;
        }
        run.warmupReady = true;
        p.getInventory().setItem(Warmup.KIT_SLOT, null);
        p.sendActionBar(Text.of(Warmup.BAR_READY));
        TimeTrials.ping(p, 1.6f);
        RaceRun rr = run.race;
        UUID id = p.getUniqueId();
        trials.raceMode().call(rr.link, () -> {
            rr.link.ready(id);
            return null;
        }, null);
    }

    /**
     * The finish crossed in a warm-up: a lap, never judged or recorded. It says the lap's time
     * ("not counted") and sends the player round again from the start, on the next tick.
     */
    void lap(Player p, TrialRun run, long nanos) {
        String time = TrialText.time(run.elapsedMs(nanos));
        p.sendMessage(Text.of(Warmup.lap(time)));
        TimeTrials.title(p, "&f" + time, "&7Warm-up lap - not counted", 30);
        TimeTrials.ping(p, 1.2f);
        Warmup.lapDone(run, System.nanoTime()); // round again from the start, sent there on the next tick
    }

    /** The warm-up is over: back to the start, and the normal 3-2-1. */
    private void end(Player p, TrialRun run, boolean early) {
        Warmup.toCountdown(run);
        p.getInventory().setItem(Warmup.KIT_SLOT, null);
        p.sendMessage(Text.of(Warmup.ended(early)));
        trials.toStart(p, run);
    }
}
