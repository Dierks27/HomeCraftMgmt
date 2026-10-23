package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Arcade Trophy costs 750, 1,000, 1,250, then 1,500 for good (SHOP-V2-SPEC D18): a {@code costs} list and a
 * LIFETIME 99 limit, so every buy is counted (without a limit nothing would be, and the price would never rise),
 * each one in its own transaction with its charge (D30).
 */
class ArcadeTrophyPriceTest {

    @Test
    void theBundledRowHasTheRisingPriceAndTheLimitThatCountsIt() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        try (var r = new InputStreamReader(ArcadeTrophyPriceTest.class.getResourceAsStream("/config.yml"),
                StandardCharsets.UTF_8)) {
            c.load(r);
        }
        Map<?, ?> row = c.getMapList("arcade.prizes").stream().filter(m -> "arcade_trophy".equals(m.get("id")))
                .findFirst().orElseThrow();
        assertEquals(List.of(750, 1000, 1250, 1500), row.get("costs"));
        assertEquals(Map.of("per", "LIFETIME", "count", 99), row.get("limit"));
        assertEquals("trophy_serial", PrizeService.trophySerialKey("arcade_trophy"), "its serial is the old one");
    }

    @Test
    void fiveBuysCost750Then1000Then1250Then1500Then1500() throws Exception {
        Prize arcade = ShopTx.row("arcade_trophy", PrizeType.TROPHY, 750, ShopTx.limit(LimitPer.LIFETIME, 99),
                List.of(750, 1000, 1250, 1500), OnlyIf.ANY);
        try (ShopTx tx = new ShopTx(10_000)) {
            for (int i = 0; i < 5; i++) {
                assertNull(tx.confirm(arcade, "life", false, "trophy_serial", true, null), "buy " + (i + 1));
            }
            assertEquals(List.of(-750, -1000, -1250, -1500, -1500), tx.ledger());
            assertEquals(5, tx.bought(arcade, "life"), "each one counted");
            assertEquals(5, tx.prizes.state("trophy_serial", 0), "and numbered");
            assertEquals(10_000 - 6000, tx.balance());
        }
    }
}
