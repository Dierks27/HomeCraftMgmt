package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.arcade.Collectors;
import com.dierks.homecraft.arcade.TrailService;
import com.dierks.homecraft.arcade.looks.LookService.CapWhere;
import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.gui.arcade.MyLooksMenu.Entry;
import com.dierks.homecraft.gui.arcade.MyLooksMenu.State;
import com.dierks.homecraft.gui.arcade.MyLooksMenu.Tap;
import com.dierks.homecraft.storage.LookDao;
import com.dierks.homecraft.storage.LookDao.Look;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One tap acts in My Looks (SHOP-V2-SPEC §3.11, red team O6): a hat tile wears it, a trail tile switches it on, the
 * worn, on or ended tile opens the look screen, slot 6 turns the last trail back on, and a collector cap tile puts it
 * on from the bag or says where it is. Also the order the tiles come in.
 */
class MyLooksTapTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 400 * DAY;

    private static Prize row(String id, PrizeType type) {
        return new Prize(id, PrizeTab.LOOKS, "&f" + id, List.of(), 60, new Icon("", Material.LEATHER_HELMET), type, true,
                null, 1, null, null, null, null, 0, 0, null, 90, "", List.of(), List.of(), null, null, List.of(), null);
    }

    private static Look right(String id, long end) {
        return new Look(id, LookDao.HAT, end, NOW - 10 * DAY, false, 0, 0, "");
    }

    private static Entry hat(String id, long end, boolean worn) {
        Look r = right(id, end);
        return new Entry(row(id, PrizeType.HAT), r, true, MyLooksMenu.state(r, NOW, worn), null);
    }

    private static Entry trail(String id, long end, boolean on) {
        Look r = right(id, end);
        return new Entry(row(id, PrizeType.TRAIL), r, false, MyLooksMenu.state(r, NOW, on), null);
    }

    @Test
    void aHatTileWearsATrailTileSwitches() {
        assertEquals(Tap.WEAR, MyLooksMenu.tap(hat("cap_lime", NOW + 50 * DAY, false)), "live: one tap wears it");
        assertEquals(Tap.WEAR, MyLooksMenu.tap(hat("cap_lime", NOW + 3 * DAY, false)), "still live in its last week");
        assertEquals(Tap.SWITCH_ON, MyLooksMenu.tap(trail("trail_snow", NOW + 20 * DAY, false)));
        assertEquals(Tap.LOOK, MyLooksMenu.tap(hat("cap_lime", NOW + 50 * DAY, true)),
                "the worn one: the look screen (take it off)");
        assertEquals(Tap.LOOK, MyLooksMenu.tap(trail("trail_snow", NOW + 20 * DAY, true)));
        assertEquals(Tap.LOOK, MyLooksMenu.tap(hat("cap_lime", NOW - DAY, false)), "ended: the look screen (buy again)");
    }

    @Test
    void slotSixTurnsTheLastTrailBackOn() {
        List<String> owned = List.of("trail_leaves_2026", "trail_snow", "trail_stars");
        assertEquals(1, TrailService.lastOnIndex(owned, "trail_snow"), "the one last on, not the first");
        assertEquals(1, TrailService.lastOnIndex(owned, "TRAIL_SNOW"));
        assertEquals(0, TrailService.lastOnIndex(owned, "trail_hearts"), "one that ran out: the first owned");
        assertEquals(0, TrailService.lastOnIndex(owned, null), "none remembered: the first");
    }

    @Test
    void aCollectorCapTilePutsItOnOrSaysWhereItIs() {
        Prize pumpkin = Collectors.ROWS.get(0);
        assertEquals(Tap.CAP_ON, MyLooksMenu.tap(new Entry(pumpkin, null, true, State.KEPT, CapWhere.BAG)),
                "in the bag: one tap puts it on");
        for (CapWhere w : List.of(CapWhere.HEAD, CapWhere.ENDER_CHEST, CapWhere.AWAY)) {
            assertEquals(Tap.CAP_HINT, MyLooksMenu.tap(new Entry(pumpkin, null, true, State.KEPT, w)), w.name());
        }
        assertEquals("&6Pumpkin Cap 2026 &7- yours for good, on your head",
                MyLooksMenu.capName("Pumpkin Cap 2026", CapWhere.HEAD));
        assertEquals("&6Pumpkin Cap 2026 &7- yours for good, in your bag",
                MyLooksMenu.capName("Pumpkin Cap 2026", CapWhere.BAG));
    }

    /**
     * Red-team finding BR-5: a cap in the off-hand read "in your bag - Tap to wear it", and the tap said "It isn't
     * with you." "Wear it" takes it from the bag first, then the off-hand: the same places the tile calls the bag.
     */
    @Test
    void aCapInTheOffHandIsWornFromThere() {
        String cap = "cap_pumpkin_2026";
        java.util.List<String> bag = new ArrayList<>(java.util.Collections.nCopies(36, (String) null));
        assertEquals(com.dierks.homecraft.arcade.looks.LookRules.OFF_HAND_SLOT,
                com.dierks.homecraft.arcade.looks.LookRules.capSlot(cap, bag, cap), "[X] the off-hand");
        bag.set(7, cap);
        assertEquals(7, com.dierks.homecraft.arcade.looks.LookRules.capSlot(cap, bag, cap), "the bag first");
        assertEquals(-1, com.dierks.homecraft.arcade.looks.LookRules.capSlot(cap,
                java.util.Collections.nCopies(36, (String) null), "hat_dragon"), "not with her");
    }

    /**
     * Red-team finding BR-6: slot 2 said "Nothing on your head" while her collector cap was on, right above the tile
     * that says it is on her head. A helmet that isn't one of her keys is named there now, with no tap.
     */
    @Test
    void slotTwoNamesACapThatIsntAKey() {
        assertEquals("&fOn your head: Pumpkin Cap 2026 &7(yours for good)",
                MyLooksMenu.headName("Pumpkin Cap 2026", true));
        assertEquals("&fOn your head: Iron Helmet", MyLooksMenu.headName("Iron Helmet", false));
    }

    @Test
    void hatsComeWornFirstThenCollectorsThenSoonestToEndThenEnded() {
        List<Entry> hats = new ArrayList<>(List.of(
                hat("cap_ended", NOW - DAY, false),
                hat("cap_later", NOW + 60 * DAY, false),
                new Entry(Collectors.ROWS.get(0), null, true, State.KEPT, CapWhere.BAG),
                hat("cap_soon", NOW + 5 * DAY, false),
                hat("cap_worn", NOW + 80 * DAY, true)));
        hats.sort(MyLooksMenu.HAT_ORDER);
        assertEquals(List.of("cap_worn", "cap_pumpkin_2026", "cap_soon", "cap_later", "cap_ended"),
                hats.stream().map(e -> e.prize().id()).toList());
    }

    @Test
    void trailsComeOnFirstThenCollectorsNewestThenSoonestToEnd() {
        List<Entry> trails = new ArrayList<>(List.of(
                trail("trail_snow", NOW + 20 * DAY, false),
                new Entry(Collectors.ROWS.get(1), right("trail_leaves_2026", NOW + 36000 * DAY), false, State.KEPT, null),
                new Entry(Collectors.ROWS.get(3), right("trail_twinkle_2026", NOW + 36000 * DAY), false, State.KEPT, null),
                trail("trail_stars", NOW + 2 * DAY, false),
                trail("trail_on", NOW + 9 * DAY, true)));
        trails.sort(MyLooksMenu.TRAIL_ORDER);
        assertEquals(List.of("trail_on", "trail_twinkle_2026", "trail_leaves_2026", "trail_stars", "trail_snow"),
                trails.stream().map(e -> e.prize().id()).toList());
    }
}
