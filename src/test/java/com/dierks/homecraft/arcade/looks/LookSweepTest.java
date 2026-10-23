package com.dierks.homecraft.arcade.looks;

import com.dierks.homecraft.arcade.looks.LookRules.Area;
import com.dierks.homecraft.arcade.looks.LookRules.Decision;
import com.dierks.homecraft.arcade.looks.LookRules.Found;
import com.dierks.homecraft.arcade.looks.LookRules.Key;
import com.dierks.homecraft.arcade.looks.LookRules.Verdict;
import com.dierks.homecraft.storage.LookDao;
import com.dierks.homecraft.storage.LookDao.Look;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dierks.homecraft.arcade.looks.LookFixtures.DAY;
import static com.dierks.homecraft.arcade.looks.LookFixtures.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sweep as a plan over slots (SHOP-V2-SPEC §3.8): what it reads (the ender chest too, and inside bundles and
 * shulker boxes), when it doesn't run, and that an unknown answer deletes nothing. The items are stand-ins, walked by
 * the same {@link LookRules#find} the service walks real items with.
 */
class LookSweepTest {

    private static final UUID HER = UUID.fromString("0b0a0c0d-1e1f-4a2b-8c3d-4e5f6a7b8c9d");
    private static final long END = 100 * DAY;
    private static final long NOW = 50 * DAY;
    private static final Map<String, Look> RIGHTS = Map.of(
            "cap_lime", row("cap_lime", LookDao.HAT, END, 1),
            "cap_pink", row("cap_pink", LookDao.HAT, 10 * DAY, 1)); // ended

    /** A stand-in item: a key, plain stuff, or a container (null slots are empty; unreadable = can't be read). */
    private record Thing(Key key, List<Thing> inside, boolean unreadable) {
        static Thing key(String id) {
            return new Thing(new Key(id, HER, END, null), null, false);
        }

        static Thing plain() {
            return new Thing(null, null, false);
        }

        static Thing bundle(Thing... in) {
            return new Thing(null, java.util.Arrays.asList(in), false);
        }

        static Thing broken() {
            return new Thing(null, null, true);
        }
    }

    private static List<Found> find(Area area, int slot, Thing t) {
        return LookRules.find(area, slot, t, Thing::key, (x, depth) -> x.unreadable() ? Optional.empty()
                : Optional.of(x.inside() == null ? List.of() : x.inside()));
    }

    @Test
    void theEnderChestIsSwept() {
        List<Found> found = find(Area.ENDER_CHEST, 3, Thing.key("cap_pink"));
        assertEquals(1, found.size());
        List<Decision> plan = LookRules.plan(true, HER, RIGHTS, NOW, found, id -> null);
        assertEquals(Verdict.REMOVE, plan.get(0).verdict(), "an ended key in the ender chest goes too");
        assertEquals("ended", plan.get(0).reason());
    }

    @Test
    void aPlayerInAGameSessionIsSkipped() {
        assertFalse(LookRules.sweeps(true, false, false), "in a world game: its snapshot is never touched");
        assertFalse(LookRules.sweeps(false, false, false), "on the way back from one");
        assertFalse(LookRules.sweeps(false, true, true), "on the death screen");
        assertTrue(LookRules.sweeps(false, true, false));
    }

    @Test
    void aFailedRightsReadDeletesNothing() {
        List<Found> found = new ArrayList<>();
        found.addAll(find(Area.HEAD, 0, Thing.key("cap_pink")));
        found.addAll(find(Area.BAG, 0, Thing.key("cap_lime")));
        found.addAll(find(Area.BAG, 1, Thing.key("cap_lime")));
        found.addAll(find(Area.BAG, 2, new Thing(new Key("cap_lime", UUID.randomUUID(), END, null), null, false)));
        List<Decision> plan = LookRules.plan(false, HER, null, NOW, found, id -> null);
        assertEquals(4, plan.size());
        assertTrue(plan.stream().allMatch(d -> d.verdict() == Verdict.KEEP),
                "not loaded: not the ended one, not the copy, not even someone else's");
    }

    @Test
    void aKeyInsideABundleIsRemovedAnUnreadableOneIsLeftAlone() {
        Thing bag = Thing.bundle(Thing.plain(), Thing.key("cap_pink"), null, Thing.bundle(Thing.key("cap_lime")));
        List<Found> found = find(Area.BAG, 7, bag);
        assertEquals(2, found.size(), "both keys, one two bundles deep");
        assertEquals(List.of(1), found.get(0).path());
        assertEquals(List.of(3, 0), found.get(1).path());
        found.addAll(find(Area.HEAD, 0, Thing.key("cap_lime")));
        List<Decision> plan = LookRules.plan(true, HER, RIGHTS, NOW, found, id -> null);
        for (Decision d : plan) {
            if (d.found().worn()) {
                assertEquals(Verdict.KEEP, d.verdict(), "the worn one stays");
            } else {
                assertEquals(Verdict.REMOVE, d.verdict(), d.found().path() + ": taken out of its bundle");
            }
        }
        assertTrue(find(Area.BAG, 8, Thing.broken()).isEmpty(),
                "a bundle that can't be read is left alone: nothing in it is found, so nothing is deleted");
        assertTrue(find(Area.BAG, 9, Thing.bundle(Thing.broken(), Thing.plain())).isEmpty());
    }

    @Test
    void wearItWithTheKeyInTheEnderChestMovesIt() {
        Found ender = find(Area.ENDER_CHEST, 5, Thing.key("cap_lime")).get(0);
        assertEquals(ender, LookRules.wearFrom(List.of(ender)), "taken from the ender chest: no new key is made");
        Found bag = find(Area.BAG, 30, Thing.key("cap_lime")).get(0);
        assertEquals(bag, LookRules.wearFrom(List.of(ender, bag)), "the bag first");
        Found worn = find(Area.HEAD, 0, Thing.key("cap_lime")).get(0);
        assertTrue(LookRules.wearFrom(List.of(bag, worn)).worn(), "on already");
        Found nested = find(Area.BAG, 1, Thing.bundle(Thing.key("cap_lime"))).get(0);
        assertNull(LookRules.wearFrom(List.of(nested)), "one inside a bundle is never taken: a new key is made");
        assertNull(LookRules.wearFrom(List.of()));
    }
}
