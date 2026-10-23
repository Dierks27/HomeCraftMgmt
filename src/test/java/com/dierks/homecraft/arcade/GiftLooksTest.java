package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.PrizeService.GiftFacts;
import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.Keeper;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeExtra;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.integration.EconomySandbox;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Presents of looks (SHOP-V2-SPEC §3.13): what can be given (a giftable collector, a rented look, a golf ball; never
 * a Keeper), the widened refusal order, and that a rented hat present never fails for room (the key goes in My Looks
 * when there is nowhere else).
 */
class GiftLooksTest {

    private static Prize row(String id, PrizeType type, PrizeExtra extra) {
        return new Prize(id, PrizeTab.LOOKS, "&f" + id, List.of(), 120, new Icon("", Material.LEATHER_HELMET), type,
                true, null, 1, null, null, null, null, 0, 0, null, 90, "", List.of(), List.of(), null, null, List.of(),
                null, extra);
    }

    private static final Prize CAP = row("cap_pink", PrizeType.HAT,
            new PrizeExtra(Material.LEATHER_HELMET, 0, null, OnlyIf.ANY, 0, null));
    private static final Prize BALL = row("ball_pink", PrizeType.BALL,
            new PrizeExtra(null, 0, Material.PINK_CONCRETE, OnlyIf.ANY, 0, null));
    private static final Prize KEEPER = row("keeper_wizard_hat", PrizeType.HAT,
            new PrizeExtra(null, 0, null, OnlyIf.ANY, 0, new Keeper(null, false, null, null)));
    private static final Prize TROPHY = row("trophy_bronze", PrizeType.TROPHY, PrizeExtra.NONE);

    /** A rented hat present that passes every check, the receiver's head taken and their bag full. */
    private static GiftFacts facts(boolean keeper, boolean giftable, boolean hasIt, String until, boolean cap,
                                   int balance) {
        return new GiftFacts(true, keeper, true, giftable, true, true, true, false, true, false, false, hasIt, until,
                cap, false, false, balance, 120, "Kid", "Pink Cap");
    }

    @Test
    void whatCanBeAPresent() {
        assertTrue(PrizeService.giftable(CAP), "a rented cap: bound, so a present is the only way to give one");
        assertTrue(PrizeService.giftable(BALL), "a golf ball");
        assertTrue(PrizeService.giftable(Collectors.ROWS.get(0)), "a giftable collector");
        assertFalse(PrizeService.giftable(KEEPER), "never a Keeper");
        assertFalse(PrizeService.giftable(TROPHY), "never a trophy");
        assertFalse(PrizeService.giftable(null));
    }

    @Test
    void aKeeperIsRefused() {
        assertEquals("Keepers can't be given.", PrizeService.giftRefusal(facts(true, false, false, null, false, 500)));
        GiftFacts noSpend = new GiftFacts(false, true, true, false, true, true, true, false, true, false, false, false,
                null, false, true, true, 500, 120, "Kid", "Wizard Hat");
        assertEquals(EconomySandbox.reason(), PrizeService.giftRefusal(noSpend), "the sandbox first, as always");
    }

    @Test
    void theWidenedOrder() {
        assertNull(PrizeService.giftRefusal(facts(false, true, false, null, false, 500)));
        assertEquals("That isn't for sale right now.",
                PrizeService.giftRefusal(facts(false, false, false, null, false, 500)), "not giftable");
        assertEquals("Kid has the Pink Cap until 18 Jan.",
                PrizeService.giftRefusal(facts(false, true, true, "18 Jan", false, 500)),
                "a rented look they have: refused before any charge, with its date");
        assertEquals("Kid already has the Pink Cap!",
                PrizeService.giftRefusal(facts(false, true, true, null, false, 500)), "a ball or a collector they have");
        assertEquals("Kid has the Pink Cap until 18 Jan.",
                PrizeService.giftRefusal(facts(false, true, true, "18 Jan", false, 0)), "having it before money");
        assertEquals("You need 20 more tokens.", PrizeService.giftRefusal(facts(false, true, false, null, false, 100)));
    }

    @Test
    void aRentedHatPresentNeverFailsForRoom() {
        assertNull(PrizeService.giftRefusal(facts(false, true, false, null, false, 500)),
                "head taken, bag full: the key goes in My Looks, so it is never refused for room");
        assertEquals("Kid's bag is full.", PrizeService.giftRefusal(facts(false, true, false, null, true, 500)),
                "a collector cap is a real item: it still needs room");
    }
}
