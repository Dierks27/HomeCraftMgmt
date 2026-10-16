package com.dierks.homecraft.games.trial;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a racer sees at each checkpoint (MOUNTAIN-V2-SPEC §12 "quiet checkpoints"), through
 * {@link TimeTrials#announce} with a fake player.
 *
 * <p>Pinned here: a run down a Mountain Run v2 shows the big title only at every 10th checkpoint, at the
 * halfway one ("Halfway!") and before the Final Drop ("Final drop!"); every other checkpoint is the action-bar
 * line "Checkpoint 37/74 · 1:12.4", which the clock line leaves alone for 1.5 s; and the algo-3 Mountain Run
 * shows its title at every checkpoint, exactly as before.
 */
class QuietCheckpointsTest {

    /** What one fake player was shown: each title as "big | small", each action-bar line. */
    private record Seen(List<String> titles, List<String> bars, Player player) {
    }

    private static Seen player() {
        List<String> titles = new ArrayList<>();
        List<String> bars = new ArrayList<>();
        Player p = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, m, a) -> {
                    switch (m.getName()) {
                        case "showTitle" -> {
                            if (a != null && a.length == 1 && a[0] instanceof Title t) {
                                titles.add(plain(t.title()) + " | " + plain(t.subtitle()));
                            }
                            return null;
                        }
                        case "sendActionBar" -> {
                            if (a != null && a.length == 1 && a[0] instanceof Component c) {
                                bars.add(plain(c));
                            }
                            return null;
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "equals" -> {
                            return proxy == a[0];
                        }
                        default -> {
                            return m.getReturnType() == boolean.class ? false : null;
                        }
                    }
                });
        return new Seen(titles, bars, p);
    }

    private static String plain(Component c) {
        return LegacyComponentSerializer.legacySection().serialize(c).replaceAll("§.", "");
    }

    /** A run down {@code c} reaching every checkpoint, one a second, each announced; returns the run. */
    private static TrialRun ride(Course c, Seen seen, List<Integer> holds) {
        TrialRun run = new TrialRun(UUID.randomUUID(), c, false, 0);
        run.phase = TrialRun.Phase.RUNNING;
        run.progress = new Progress(c, c.start().point(), 0);
        long s = 1_000_000_000L;
        List<Course.Mark> cps = c.checkpoints();
        for (int i = 0; i < cps.size(); i++) {
            Progress.Reached r = run.progress.reachNext((i + 1) * s); // checkpoint i, exactly (i + 1) s after Go
            run.ticks = (i + 1) * 20;
            TimeTrials.announce(seen.player(), run, r.index());
            holds.add(run.barHold);
        }
        return run;
    }

    @Test
    void aV2RunIsQuietExceptAtItsLoudCheckpoints() {
        Course c = MountainRunsV2.road();
        Seen seen = player();
        List<Integer> holds = new ArrayList<>();
        ride(c, seen, holds);
        int of = MountainRunsV2.CHECKPOINTS;
        int half = BoatHype.halfway(c);
        List<String> wantTitles = new ArrayList<>();
        List<String> wantBars = new ArrayList<>();
        for (int i = 0; i < of; i++) {
            String time = TrialText.time((i + 1) * 1000L);
            if ((i + 1) % 10 == 0 || i == half || i == of - 1) {
                String big = i == of - 1 ? "Final drop!" : i == half ? "Halfway!" : "";
                wantTitles.add(big + " | Checkpoint " + (i + 1) + " of " + of + " - " + time);
            } else {
                wantBars.add("Checkpoint " + (i + 1) + "/" + of + " · " + time);
            }
        }
        assertEquals(wantTitles, seen.titles(), "the big title only at every 10th, halfway and before the Final Drop");
        assertEquals(wantBars, seen.bars(), "every other checkpoint is a line on the action bar");
        assertTrue(seen.bars().contains("Checkpoint 37/74 · 0:37.0") || half == 36,
                "§12's own example line: " + seen.bars());
        assertEquals(1 * 20 + TimeTrials.QUIET_HOLD, (int) holds.get(0),
                "a quiet checkpoint holds the clock line off the bar for 1.5 s");
        assertEquals(30, TimeTrials.QUIET_HOLD, "30 ticks");
    }

    @Test
    void theSpiralShowsEveryCheckpointsTitleAsBefore() {
        Course c = MountainRuns.medium();
        Seen seen = player();
        List<Integer> holds = new ArrayList<>();
        ride(c, seen, holds);
        assertEquals(c.checkpoints().size(), seen.titles().size(), "a title at every checkpoint: " + seen.titles());
        assertEquals(List.of(), seen.bars(), "and no quiet line");
        assertEquals("Final drop! | Checkpoint 10 of 10 - " + TrialText.time(10_000L),
                seen.titles().get(MountainRuns.FINAL_DROP_AT), "its Final drop! as before");
        assertEquals(" | Checkpoint 1 of 10 - " + TrialText.time(1_000L), seen.titles().get(0), "the rest plain");
        assertTrue(holds.stream().allMatch(h -> h == 0), "the clock line is never held");
    }
}
