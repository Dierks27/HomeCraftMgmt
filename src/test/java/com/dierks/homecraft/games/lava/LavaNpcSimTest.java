package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.cpu.CpuChoice;
import com.dierks.homecraft.games.cpu.CpuLevel;
import com.dierks.homecraft.games.lava.rules.LavaMode;
import com.dierks.homecraft.games.lava.rules.LavaRound;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NPC players in The Floor Is Lava (stage 2): they walk the room by its own rule, climb to dry ground when the
 * warning starts (easy ones later and slower), and are only ever on a dry stand while the lava is up: put back in
 * Group and Little, out in Challenge. And Little, Lilah's mode, takes at most one easy NPC, only for a player who
 * wants them easy.
 */
class LavaNpcSimTest {

    private static final UUID A = new UUID(2, 1);
    private static final UUID LILAH = new UUID(9, 1);
    private static final UUID DAD = new UUID(9, 2);

    /**
     * A 12 x 12 floor at y 65 (the box's bottom at 64) with a staircase to a shelf in one corner: half a block a
     * step, from the floor up to 67 (dry with the lava at 1 or 2).
     */
    static final class Room implements LavaNpcSim.Room {
        final List<LavaSite.Stand> stands = new ArrayList<>();

        Room() {
            for (int x = 0; x < 12; x++) {
                for (int z = 0; z < 12; z++) {
                    double y = 65;
                    if (z >= 9) {
                        y = x <= 7 ? 65 + 0.5 * Math.max(0, x - 3) : 67; // 65 ... 67 along x, then the shelf
                    }
                    stands.add(new LavaSite.Stand(x, z, y));
                }
            }
        }

        @Override
        public List<LavaSite.Stand> stands() {
            return stands;
        }

        @Override
        public boolean walks(LavaSite.Stand a, LavaSite.Stand b) {
            return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) == 1 && b.y() <= a.y() + 0.5 + 1e-9;
        }

        @Override
        public boolean dry(LavaSite.Stand s, int level) {
            return s.y() >= 64 + 1 + level - 1e-9;
        }

        @Override
        public double floorY() {
            return 65;
        }
    }

    /** One lava cycle: safe, the warning (100 ticks), the lava, the drain; the touches it brought. */
    private static List<LavaNpcSim.Touched> cycle(LavaNpcSim sim, long[] t, int level, LavaMode mode, int round,
                                                  Room room) {
        List<LavaNpcSim.Touched> touched = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            touched.addAll(sim.step(t[0]++, LavaRound.Phase.SAFE, 0, level, mode, round));
        }
        for (int i = 0; i < LavaRound.WARN_TICKS; i++) {
            touched.addAll(sim.step(t[0]++, LavaRound.Phase.WARNING, 0, level, mode, round));
        }
        for (int i = 0; i < 100; i++) {
            touched.addAll(sim.step(t[0]++, LavaRound.Phase.LAVA, level, level, mode, round));
            for (LavaNpcSim.Walker w : sim.walkers()) {
                if (!w.out()) {
                    assertTrue(room.dry(w.at(), level), "while the lava is up an NPC is only ever on a dry stand: "
                            + w.at());
                    assertEquals(w.at().y(), w.y(), 1e-9, "standing on it");
                }
            }
        }
        for (int i = 0; i < LavaRound.DRAIN_TICKS; i++) {
            touched.addAll(sim.step(t[0]++, LavaRound.Phase.DRAIN, 0, level, mode, round));
        }
        return touched;
    }

    @Test
    void hardNpcsClimbInTimeAndEasyOnesSometimesTouchTheLavaAndArePutBack() {
        Room room = new Room();
        int hard = 0;
        int easy = 0;
        for (int seed = 0; seed < 30; seed++) {
            for (CpuLevel level : List.of(CpuLevel.HARD, CpuLevel.EASY)) {
                LavaNpcSim sim = new LavaNpcSim(room, new SplittableRandom(seed));
                sim.add(A, level, new LavaSite.Stand(5, 4, 65), 0);
                long[] t = {0};
                for (int round = 1; round <= 3; round++) {
                    int n = cycle(sim, t, 1, LavaMode.GROUP, round, room).size();
                    if (level == CpuLevel.HARD) {
                        hard += n;
                    } else {
                        easy += n;
                    }
                }
                assertTrue(!sim.walkers().get(0).out(), "nobody is out of Group");
            }
        }
        assertEquals(0, hard, "a hard NPC always makes it to dry ground");
        assertTrue(easy > 0, "an easy one sometimes doesn't, and is put back on dry ground");
    }

    @Test
    void itOnlyEverStepsWhereThePlayroomLetsAPlayerWalk() {
        Room room = new Room();
        LavaNpcSim sim = new LavaNpcSim(room, new SplittableRandom(4));
        LavaNpcSim.Walker w = sim.add(A, CpuLevel.MEDIUM, new LavaSite.Stand(2, 2, 65), 0);
        LavaSite.Stand was = w.at();
        long[] t = {0};
        for (int round = 1; round <= 4; round++) {
            for (int i = 0; i < 400; i++) {
                LavaRound.Phase p = i < 120 ? LavaRound.Phase.SAFE : i < 220 ? LavaRound.Phase.WARNING
                        : i < 320 ? LavaRound.Phase.LAVA : LavaRound.Phase.DRAIN;
                sim.step(t[0]++, p, p == LavaRound.Phase.LAVA ? 2 : 0, 2, LavaMode.GROUP, round);
                if (!w.at().equals(was)) {
                    boolean putBack = w.touches() > 0 && p == LavaRound.Phase.LAVA;
                    assertTrue(putBack || room.walks(was, w.at()), "a step from " + was + " to " + w.at());
                    was = w.at();
                }
            }
        }
    }

    @Test
    void inAChallengeAnNpcThatDoesntMakeItIsOutWithTheRoundsItLasted() {
        Room room = new Room();
        LavaNpcSim sim = new LavaNpcSim(room, new SplittableRandom(1));
        LavaNpcSim.Walker w = sim.add(A, CpuLevel.HARD, new LavaSite.Stand(5, 4, 65), 0);
        long[] t = {0};
        cycle(sim, t, 1, LavaMode.CHALLENGE, 1, room);
        cycle(sim, t, 2, LavaMode.CHALLENGE, 2, room);
        assertTrue(!w.out(), "a hard NPC lasts while there is dry ground");
        List<LavaNpcSim.Touched> t3 = cycle(sim, t, 3, LavaMode.CHALLENGE, 3, room);
        assertEquals(1, t3.size(), "the lava at 3 covers the whole room");
        assertTrue(w.out(), "out");
        assertEquals(2, w.lasted(), "it lasted 2 rounds");
    }

    @Test
    void littleTakesOneEasyNpcAtMostAndOnlyForPlayersWhoWantThemEasy() {
        Map<UUID, CpuChoice.Pref> prefs = new java.util.HashMap<>(Map.of(LILAH, CpuChoice.Pref.EASY,
                DAD, CpuChoice.Pref.ANY));
        assertEquals(1, LavaNpcs.room(LavaMode.LITTLE, List.of(LILAH), id -> true, prefs::get, 8),
                "Lilah with NPCs on, set to easy: one friendly NPC");
        assertEquals(0, LavaNpcs.room(LavaMode.LITTLE, List.of(LILAH), id -> false, prefs::get, 8),
                "NPCs off: Little stays NPC-free");
        assertEquals(0, LavaNpcs.room(LavaMode.LITTLE, List.of(DAD), id -> true, prefs::get, 8),
                "not set to easy: none in Little");
        assertEquals(0, LavaNpcs.room(LavaMode.LITTLE, List.of(LILAH, DAD), id -> true, prefs::get, 8),
                "with someone who doesn't want them easy: none");
        assertEquals(3, LavaNpcs.room(LavaMode.GROUP, List.of(DAD), id -> true, prefs::get, 8), "Group: up to three");
        assertEquals(1, LavaNpcs.room(LavaMode.CHALLENGE, List.of(LILAH, DAD), id -> true, prefs::get, 3),
                "only the places no real player has");
        assertEquals(0, LavaNpcs.room(LavaMode.GROUP, List.of(LILAH, DAD), id -> !id.equals(DAD), prefs::get, 8),
                "anyone with NPCs off: none");
    }
}
