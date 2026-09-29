package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.gui.games.trial.WarmupChoiceMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
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
 *
 * <p><b>A restart due soon ends every warm-up.</b> While the restart hold is on (the server restarts
 * in a few minutes), a solo warm-up goes straight to its 3-2-1, and a party race's or Race Night's
 * shared warm-up goes straight to the grid, so the counted run isn't eaten by free laps.
 *
 * <p>The choice is kept for the course it was made on only, and forgotten when the start doesn't
 * happen or the player leaves the server, so a leftover choice never starts a warm-up elsewhere.
 */
final class Warmups {

    private final TimeTrials trials;
    /** Players who chose "Warm up" and are on their way to the start → the course they chose it on. */
    private final Map<UUID, String> wanted = new HashMap<>();

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
            want(player.getUniqueId(), c.id());
        } else {
            wanted.remove(player.getUniqueId());
        }
        trials.begin(player, c, false);
    }

    /** The player chose "Warm up" on {@code courseId} and is on the way to its start. */
    void want(UUID player, String courseId) {
        if (player != null && courseId != null) {
            wanted.put(player, courseId);
        }
    }

    /** The course the player chose a warm-up on and is on the way to, or {@code null}. */
    String wanted(UUID player) {
        return wanted.get(player);
    }

    /** The start didn't happen: forget the choice. */
    void forget(Player player) {
        if (player != null) {
            wanted.remove(player.getUniqueId());
        }
    }

    /**
     * The run just began at the start: its warm-up, if the player chose one and the course offers
     * one now (never a Dropper, whose practice drop plays that part, whatever choice was left over).
     */
    void begin(Player p, TrialRun run) {
        if (!wants(wanted.remove(p.getUniqueId()), run, trials.settings())) {
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
     * Whether a run just begun at its start warms up: the player chose it ON THIS COURSE
     * ({@code wantedCourse}), it isn't a test or a race, and the course offers one now (never a
     * Dropper, whatever choice was left over).
     */
    static boolean wants(String wantedCourse, TrialRun run, TimeTrialsSettings s) {
        return wantedCourse != null && run != null && !run.test && run.race == null
                && wantedCourse.equalsIgnoreCase(run.course.id()) && Warmup.offered(s, run.test, run.course.kind());
    }

    /**
     * A warm-up tick (from the running tick): a solo warm-up whose time is up, or with a restart due
     * soon (the restart hold), goes back to the start for the 3-2-1. True when it ended here. A
     * race's ends only when the racers go to the grid (its coordinator watches the hold).
     */
    boolean tick(Player p, TrialRun run, long now) {
        if (run.race != null) {
            return false;
        }
        String restart = restartHeld();
        if (!Warmup.over(run, now, restart != null)) {
            return false;
        }
        end(p, run, restart != null && !run.warmupOver(now) ? Warmup.endedForRestart(restart) : Warmup.ended(false));
        return true;
    }

    /** The restart the hold is for ("4:00 PM"), or {@code null} when none is minutes away. */
    private String restartHeld() {
        try {
            return trials.games().restartHeld();
        } catch (RuntimeException e) {
            return null;
        }
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
            end(p, run, Warmup.ended(true));
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
    private void end(Player p, TrialRun run, String line) {
        Warmup.toCountdown(run);
        p.getInventory().setItem(Warmup.KIT_SLOT, null);
        p.sendMessage(Text.of(line));
        trials.toStart(p, run);
    }
}
