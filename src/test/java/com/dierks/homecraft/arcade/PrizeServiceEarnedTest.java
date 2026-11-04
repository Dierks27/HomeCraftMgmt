package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.Prize;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An earned collector (0.38.6, the Harvest Basket's Pilgrim Hat) is handed over by its event, never bought: it is
 * never on sale, never offered on the shelf, never in a crate's pool and never a present, in its season or out of it.
 * An admin can still give it back ({@link PrizeService#restoreRefusal} lets it through).
 */
class PrizeServiceEarnedTest {

    private static final LocalDate THANKSGIVING = LocalDate.of(2026, 11, 26);

    private static Prize pilgrim() {
        return Collectors.ROWS.stream().filter(p -> p.id().equals(Collectors.PILGRIM_ID)).findFirst().orElseThrow();
    }

    @Test
    void anEarnedCollectorIsNeverOnSaleNorOffered() {
        Prize p = pilgrim();
        assertTrue(p.collector().earned());
        for (LocalDate day = LocalDate.of(2026, 10, 25); day.isBefore(LocalDate.of(2026, 12, 5));
             day = day.plusDays(1)) {
            assertFalse(PrizeService.onSale(p, true, day), "not on sale on " + day);
            assertFalse(PrizeService.offered(p, true, day, Set.of()), "not offered on " + day);
        }
    }

    @Test
    void itHasNoTileAndNoCrateGivesIt() {
        Prize p = pilgrim();
        PrizeService.Shelf shelf = new PrizeService.Shelf(true, THANKSGIVING, Set.of(), true);
        assertFalse(PrizeService.shelved(p, shelf), "no tile on the Looks tab");
        assertFalse(PrizeService.grantable(p, shelf), "never from a crate");
        assertFalse(PrizeService.crateable(p));
    }

    @Test
    void itIsNeverAPresent() {
        Prize p = pilgrim();
        PrizeService.GiftFacts facts = new PrizeService.GiftFacts(true, false, true, p.collector().giftable(),
                PrizeService.onSale(p, true, THANKSGIVING), true, true, false, true, false, false, false, null, true,
                true, true, 1000, 0, "Someone", "Pilgrim Hat 2026");
        assertEquals("That isn't for sale right now.", PrizeService.giftRefusal(facts));
    }

    @Test
    void anAdminCanStillGiveItBack() {
        assertNull(PrizeService.restoreRefusal(pilgrim(), Set.of()), "/hcm give prize and /hcm harvest hat");
    }

    @Test
    void theTurkeyHatBesideItIsSoldAsAnyCollector() {
        Prize turkey = Collectors.ROWS.stream().filter(p -> p.id().equals(Collectors.TURKEY_ID)).findFirst()
                .orElseThrow();
        assertTrue(PrizeService.onSale(turkey, true, THANKSGIVING));
        assertTrue(PrizeService.shelved(turkey, new PrizeService.Shelf(true, THANKSGIVING, Set.of(), true)));
        assertFalse(PrizeService.onSale(turkey, true, LocalDate.of(2026, 12, 1)), "sales stop after 30 Nov");
    }
}
