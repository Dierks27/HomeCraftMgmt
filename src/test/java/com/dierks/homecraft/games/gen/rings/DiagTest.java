package com.dierks.homecraft.games.gen.rings;

import org.junit.jupiter.api.Test;

class DiagTest {
    @Test
    void diag() {
        for (String tier : new String[]{"easy"}) {
            RingsPlanner.DIAG.clear();
            try {
                new RingsPlanner().plan(RingsPlannerTest.input('A', 1, tier));
                System.out.println("DIAG " + tier + " ok");
            } catch (Exception e) {
                System.out.println("DIAG " + tier + " failed " + e.getMessage());
            }
            System.out.println("DIAG " + tier + " " + new java.util.TreeMap<>(RingsPlanner.DIAG));
        }
    }
}
