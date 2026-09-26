package com.dierks.homecraft.arcade.homes;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The +1 Home perk adds to what a player has, the way EssentialsX actually counts homes. */
class HomeMathTest {

    /** A server's sethome-multiple: default 3, a mayor rank, a staff rank, and our tiers. */
    private static Map<String, Integer> tiers() {
        Map<String, Integer> t = new LinkedHashMap<>();
        t.put("default", 3);
        t.put("mayor", 5);
        t.put("staff", 99);
        for (int n = 2; n <= 101; n++) {
            t.put("hcm_" + n, n);
        }
        return t;
    }

    private static Predicate<String> holds(String... nodes) {
        Set<String> s = Set.of(nodes);
        return s::contains;
    }

    private static int base(Predicate<String> has) {
        return HomeMath.base(tiers(), has, Set.of());
    }

    @Test
    void noMultiplePermissionIsOneHome() {
        assertEquals(1, base(holds()));
    }

    @Test
    void theMultiplePermissionGivesTheDefault() {
        assertEquals(3, base(holds(HomeMath.MULTIPLE)));
    }

    @Test
    void aRankGivesItsTierEvenWithoutTheMultiplePermission() {
        // EssentialsX's tier loop is not gated on essentials.sethome.multiple.
        assertEquals(5, base(holds(HomeMath.MULTIPLE + ".mayor")));
        assertEquals(5, base(holds(HomeMath.MULTIPLE, HomeMath.MULTIPLE + ".mayor")));
        assertEquals(99, base(holds(HomeMath.MULTIPLE, HomeMath.MULTIPLE + ".mayor", HomeMath.MULTIPLE + ".staff")));
    }

    @Test
    void theHighestTierWinsNotTheSum() {
        assertEquals(5, base(holds(HomeMath.MULTIPLE + ".mayor", HomeMath.MULTIPLE)), "5, not 3 + 5");
    }

    @Test
    void ourOwnTiersNeverCountTowardTheBase() {
        assertEquals(3, base(holds(HomeMath.MULTIPLE, HomeMath.node(6))),
                "a player holding hcm_6 still has a base of 3, or every refresh would add again");
    }

    @Test
    void ignoredTiersDoNotCount() {
        Map<String, Integer> t = tiers();
        t.put("homes2", 2);
        assertEquals(1, HomeMath.base(t, holds(HomeMath.MULTIPLE + ".homes2"), Set.of("homes2")));
    }

    @Test
    void aTierWithoutANumberFallsBackToTheDefaultThenThree() {
        Map<String, Integer> t = new LinkedHashMap<>();
        t.put("vip", 7);
        assertEquals(3, HomeMath.base(t, holds(HomeMath.MULTIPLE), Set.of()), "no default key: Essentials uses 3");
    }

    @Test
    void baseAndBonusForEachKindOfPlayer() {
        // base + bonus, and the one tier that grants it
        int[][] cases = {{1, 1}, {3, 2}, {5, 1}, {99, 2}};
        Predicate<String>[] players = new Predicate[] {holds(), holds(HomeMath.MULTIPLE),
                holds(HomeMath.MULTIPLE + ".mayor"), holds(HomeMath.MULTIPLE + ".staff")};
        for (int i = 0; i < cases.length; i++) {
            int base = base(players[i]);
            assertEquals(cases[i][0], base);
            int total = base + cases[i][1];
            assertNull(HomeMath.missingTierLine(tiers(), total), "hcm_" + total + " is defined");
            assertEquals("essentials.sethome.multiple.hcm_" + total, HomeMath.node(total));
        }
    }

    @Test
    void unlimitedAndBigBasesAreNotOffered() {
        assertFalse(HomeMath.offered(3, true), "unlimited");
        assertFalse(HomeMath.offered(99, false), "a base of 20 or more");
        assertFalse(HomeMath.offered(20, false));
        assertTrue(HomeMath.offered(19, false));
        assertTrue(HomeMath.offered(1, false));
    }

    @Test
    void aMissingTierIsRefusedWithTheLineToAdd() {
        Map<String, Integer> t = new LinkedHashMap<>();
        t.put("default", 3);
        assertEquals("  hcm_4: 4", HomeMath.missingTierLine(t, 4));
        t.put("hcm_4", 3); // defined with the wrong number is as good as missing
        assertNotNull(HomeMath.missingTierLine(t, 4));
        t.put("hcm_4", 4);
        assertNull(HomeMath.missingTierLine(t, 4));
    }

    @Test
    void nothingIsWrittenWhenThePlayerIsAlreadyRight() {
        String want = HomeMath.node(6);
        assertTrue(HomeMath.change(Set.of(want), want, Set.of()).none());
        assertTrue(HomeMath.change(Set.of(), null, Set.of()).none(), "no slots, no tier");
    }

    @Test
    void aChangeAddsTheNewTierAndClearsTheRest() {
        HomeMath.Change c = HomeMath.change(Set.of(HomeMath.node(4)), HomeMath.node(6),
                Set.of("essentials.sethome.multiple.homes2"));
        assertEquals(Set.of(HomeMath.node(6)), c.add());
        assertEquals(Set.of(HomeMath.node(4), "essentials.sethome.multiple.homes2"), c.remove());
    }
}
