package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.Evacuator.Action;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who has to be out of a half before it is built (GEN-SPEC §6.3): a player mid-run on the layout it
 * holds gets their minutes, reminders and a last-minute line, then their run ends; anyone else is
 * moved at once; the half is grown by 8; nobody outside it is touched.
 */
class EvacuatorTest {

    private static final String SLOT = "fresh_golf";
    private static final Box HALF = Slots.DAILY_GOLF.half('A');
    private static final long MIN = 60_000L;

    private static Person at(double x, double y, double z, String course) {
        return new Person(UUID.randomUUID(), "P", "games", x, y, z, course == null ? null : "golf", course);
    }

    @Test
    void someoneMidRunGetsTheirMinutesRemindersAndThenTheirRunEnds() {
        Person runner = at(HALF.minX() + 3, HALF.minY() + 5, HALF.minZ() + 3, SLOT);
        Evacuator e = new Evacuator();
        long start = 1_000_000L;
        long deadline = start + 20 * MIN;
        List<Action> first = e.step(List.of(runner), "games", HALF, SLOT, true, null, start, deadline);
        assertEquals(List.of(new Action(Action.Kind.TELL, runner.id(), GenCopy.comingHere(20))), first,
                "first they are told they have 20 minutes and it still counts");
        assertTrue(Evacuator.waiting(List.of(runner), "games", HALF, SLOT, true, null), "the build waits for them");
        assertEquals(List.of(), e.step(List.of(runner), "games", HALF, SLOT, true, null, start + 10_000, deadline),
                "nothing more ten seconds later");
        List<Action> reminder = e.step(List.of(runner), "games", HALF, SLOT, true, null, start + 30_000, deadline);
        assertEquals(Action.Kind.BAR, reminder.get(0).kind(), "a reminder on the action bar after 30 seconds");
        List<Action> last = e.step(List.of(runner), "games", HALF, SLOT, true, null, deadline - 50_000, deadline);
        assertTrue(last.contains(new Action(Action.Kind.TELL, runner.id(), GenCopy.ONE_MINUTE)), "1 minute left!");
        assertFalse(e.step(List.of(runner), "games", HALF, SLOT, true, null, deadline - 20_000, deadline)
                .contains(new Action(Action.Kind.TELL, runner.id(), GenCopy.ONE_MINUTE)), "said once");
        List<Action> up = e.step(List.of(runner), "games", HALF, SLOT, true, null, deadline, deadline);
        assertEquals(List.of(new Action(Action.Kind.END, runner.id(), null),
                new Action(Action.Kind.TELL, runner.id(), GenCopy.timesUp(SLOT))), up,
                "at the deadline their run ends and they are told why");
    }

    @Test
    void anyoneElseIsMovedAtOnceAndNobodyOutsideIsTouched() {
        Person visitor = at(HALF.minX() - 7.5, HALF.minY() + 2, HALF.minZ() + 3, null);
        Person player = at(HALF.minX() + 3, HALF.minY() + 2, HALF.minZ() + 3, "fresh_tiny_golf");
        Person away = at(HALF.minX() - 9.5, HALF.minY() + 2, HALF.minZ() + 3, null);
        Person ours = at(HALF.minX() + 3, HALF.minY() + 2, HALF.minZ() + 3, SLOT);
        List<Action> out = new Evacuator().step(List.of(visitor, player, away, ours), "games", HALF, SLOT, false,
                null, 0, 20 * MIN);
        assertEquals(List.of(new Action(Action.Kind.MOVE, visitor.id(), GenCopy.MOVED),
                new Action(Action.Kind.MOVE, player.id(), GenCopy.MOVED),
                new Action(Action.Kind.MOVE, ours.id(), GenCopy.MOVED)), out,
                "within 8 blocks, everyone not on a layout this half holds is moved, and told why");
        assertFalse(Evacuator.waiting(List.of(ours), "games", HALF, SLOT, false, null),
                "with no layout here to finish, nobody is waited for");
        assertTrue(Evacuator.anyone(List.of(visitor), "games", HALF), "CLEAR_OLD sees the visitor");
        assertFalse(Evacuator.anyone(List.of(away), "games", HALF), "but not someone 9.5 blocks off");
        assertFalse(Evacuator.anyone(List.of(visitor), "world", HALF), "or anyone in another world");
        assertEquals(1, Evacuator.minutes(1), "a moment left is a minute");
        assertEquals(20, Evacuator.minutes(20 * MIN), "twenty minutes are twenty");
    }

    @Test
    void aRunnerOffTheLiveHalfIsTakenToBeOnTheOldLayoutWhereverTheyAre() {
        Box live = Slots.DAILY_GOLF.half('B');
        Person glider = at(HALF.minX() - 20.5, HALF.minY() + 5, HALF.minZ() + 3, SLOT);
        Person onLive = at(live.minX() + 3, live.minY() + 5, live.minZ() + 3, SLOT);
        Person visitor = at(HALF.minX() - 20.5, HALF.minY() + 5, HALF.minZ() + 3, null);
        Evacuator e = new Evacuator();
        long deadline = 20 * MIN;
        assertEquals(List.of(new Action(Action.Kind.TELL, glider.id(), GenCopy.comingHere(20))),
                e.step(List.of(glider, onLive, visitor), "games", HALF, SLOT, true, live, 0, deadline),
                "a runner of this slot off the live half may be on the old layout, even 20 blocks out: told, "
                        + "while the runner on the live half and the passer-by far away are left alone");
        assertTrue(Evacuator.waiting(List.of(glider), "games", HALF, SLOT, true, live), "and the build waits");
        assertFalse(Evacuator.waiting(List.of(onLive), "games", HALF, SLOT, true, live),
                "but never for someone on the live half");
        assertTrue(e.step(List.of(glider), "games", HALF, SLOT, true, live, deadline, deadline)
                .contains(new Action(Action.Kind.END, glider.id(), null)), "their run ends when the time is up");
        assertTrue(Evacuator.strayRunner(List.of(glider), "games", SLOT, live),
                "CLEAR_OLD waits for them too (it has no time limit)");
        assertFalse(Evacuator.strayRunner(List.of(onLive, visitor), "games", SLOT, live),
                "and not for the live half's runners or anyone not playing it");
    }
}
