package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No crate ever gives a collector (HALLOWEEN-SPEC E11): a crate's "any trail" pool would otherwise pay
 * the kept-for-good trail for a day, and a crated collector would skip its one-each claim. Nor a hat
 * (SHOP-V2-SPEC D11), a golf ball or a party, which are bought.
 */
class GrantableTest {

    private static Prize row(String id, PrizeType type, String particle, int days) {
        return new Prize(id, PrizeTab.COSMETICS, "&f" + id, List.of(), 100, new Icon("", Material.SUGAR), type, true,
                null, 1, null, null, null, null, 0, 0, particle, days, "", List.of(), List.of(), null, null,
                List.of(), null);
    }

    @Test
    void noCatalogueRowIsCrateable() {
        for (Prize p : Collectors.ROWS) {
            assertFalse(PrizeService.crateable(p), p.id());
        }
    }

    @Test
    void configRowsAreAsBeforeExceptWhatIsBought() {
        assertTrue(PrizeService.crateable(row("trail_heart", PrizeType.TRAIL, "HEART", 7)), "a 7-day config trail");
        assertFalse(PrizeService.crateable(row("hat_creeper", PrizeType.HAT, null, 0)),
                "a hat is bought for its days, never won (D11)");
        assertFalse(PrizeService.crateable(row("ball_pink", PrizeType.BALL, null, 0)), "a golf ball is bought");
        assertFalse(PrizeService.crateable(row("party_time", PrizeType.PARTY, null, 0)), "and so is a party");
        assertTrue(PrizeService.crateable(row("speed", PrizeType.BOOST, null, 0)));
        assertFalse(PrizeService.crateable(row("hat_command", PrizeType.COMMAND, null, 0)), "perks are bought");
        assertFalse(PrizeService.crateable(row("pity", PrizeType.PITY, null, 0)));
    }
}
