package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.CellState;
import com.dierks.homecraft.games.cpu.CpuLevel;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NPC players on Falling Floors (stage 2): they only ever stand where the real floors hold them, step to cells that
 * aren't falling yet, fall a floor when what is under them goes (an easy one sometimes waits too long), go out under
 * the last floor, and never change the floors: an NPC's own steps are its own.
 */
class FloorsNpcSimTest {

    /** Three square floors, 10 apart, with cells the test can take away (a real player's steps, sudden death). */
    static final class Fake implements FloorsNpcSim.Floors {
        final int size;
        final Map<String, CellState> cells = new HashMap<>();

        Fake(int size) {
            this.size = size;
        }

        @Override
        public int layers() {
            return 3;
        }

        @Override
        public int topY(int layer) {
            return 100 - 10 * layer;
        }

        @Override
        public int outY() {
            return 70;
        }

        @Override
        public CellState state(int layer, int x, int z) {
            if (x < 0 || z < 0 || x >= size || z >= size) {
                return CellState.AIR;
            }
            return cells.getOrDefault(layer + ":" + x + ":" + z, CellState.SOLID);
        }

        void set(int layer, int x, int z, CellState s) {
            cells.put(layer + ":" + x + ":" + z, s);
        }
    }

    private static final UUID A = new UUID(1, 1);

    @Test
    void anNpcOnlyEverStandsWhereTheRealFloorsHoldIt() {
        for (CpuLevel level : CpuLevel.values()) {
            for (int seed = 0; seed < 20; seed++) {
                Fake f = new Fake(10);
                SplittableRandom real = new SplittableRandom(seed + 1000);
                FloorsNpcSim sim = new FloorsNpcSim(10, new SplittableRandom(seed));
                sim.add(A, level, 0, 5, 5, f, 0);
                for (long t = 1; t <= 2_000; t++) {
                    // the real floors go a cell at a time (a real player's steps, then sudden death)
                    f.set(real.nextInt(3), real.nextInt(10), real.nextInt(10), t % 3 == 0 ? CellState.RED : CellState.AIR);
                    sim.step(t, f);
                    FloorsNpcSim.Walker w = sim.walkers().get(0);
                    if (!w.out() && !w.falling() && !w.moving()) {
                        assertTrue(f.state(w.layer(), w.cellX(), w.cellZ()).holds(),
                                level + " seed " + seed + " at " + t + ": standing on " + w.cellX() + "," + w.cellZ()
                                        + " of floor " + w.layer() + ", which the floors hold");
                        assertEquals(f.topY(w.layer()), w.y(), 1e-9, "on top of its floor");
                    }
                    if (w.out()) {
                        assertTrue(w.y() < f.outY(), "out under the last floor");
                        break;
                    }
                }
            }
        }
    }

    @Test
    void itStepsOnlyOntoWholeCellsAndFallsAFloorWhenItsCellGoes() {
        Fake f = new Fake(10);
        FloorsNpcSim sim = new FloorsNpcSim(10, new SplittableRandom(3));
        FloorsNpcSim.Walker w = sim.add(A, CpuLevel.HARD, 0, 5, 5, f, 0);
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 6; z++) {
                if (x != 6 || z != 6) {
                    f.set(0, x, z, x == 5 && z == 5 ? CellState.SOLID : CellState.RED);
                }
            }
        }
        sim.step(1, f);
        assertTrue(w.moving(), "it steps");
        assertEquals(6, w.cellX(), "to the one neighbour that isn't falling");
        assertEquals(6, w.cellZ());
        f.set(0, 6, 6, CellState.AIR); // it went under it while it stepped
        for (long t = 2; t < 40 && !w.falling() && w.layer() == 0; t++) {
            sim.step(t, f);
        }
        for (long t = 40; t < 80 && w.falling(); t++) {
            sim.step(t, f);
        }
        assertEquals(1, w.layer(), "it fell to the next floor, which holds it there");
        assertEquals(90, w.y(), 1e-9);
    }

    @Test
    void easyNpcsWaitTooLongAndDropSoonerThanHardOnes() {
        double easy = 0;
        double hard = 0;
        int lingered = 0;
        for (int seed = 0; seed < 40; seed++) {
            for (CpuLevel level : List.of(CpuLevel.EASY, CpuLevel.HARD)) {
                Fake f = new Fake(8);
                FloorsNpcSim sim = new FloorsNpcSim(10, new SplittableRandom(seed));
                FloorsNpcSim.Walker w = sim.add(A, level, 0, 4, 4, f, 0);
                long t = 1;
                boolean waited = false;
                for (; t <= 6_000 && !w.out(); t++) {
                    sim.step(t, f);
                    waited |= w.lingering();
                }
                if (level == CpuLevel.EASY) {
                    easy += t;
                    lingered += waited ? 1 : 0;
                } else {
                    hard += t;
                    assertTrue(!waited, "a hard NPC never waits too long");
                }
            }
        }
        assertTrue(lingered > 20, "easy NPCs wait too long now and then (" + lingered + " of 40)");
        assertTrue(easy < hard, "and so last less long on the same floors: " + easy / 40 + " vs " + hard / 40);
    }

    @Test
    void anNpcsOwnStepsNeverChangeTheFloors() {
        Fake f = new Fake(10);
        FloorsNpcSim sim = new FloorsNpcSim(10, new SplittableRandom(9));
        sim.add(A, CpuLevel.MEDIUM, 0, 5, 5, f, 0);
        for (long t = 1; t <= 3_000; t++) {
            sim.step(t, f);
        }
        assertTrue(f.cells.isEmpty(), "every cell of the real floors is as it was: nothing an NPC did wrote to them");
        assertTrue(sim.walkers().get(0).layer() > 0 || sim.walkers().get(0).out(),
                "yet its own steps fell away under it, as a player's would");
    }
}
