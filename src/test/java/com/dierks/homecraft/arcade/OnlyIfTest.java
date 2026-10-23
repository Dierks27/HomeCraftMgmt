package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.storage.TokenDao;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Make it Day and Sunny Day ({@code only_if}, SHOP-V2-SPEC §5.11; red team D-m18): refused before any charge when
 * they'd do nothing (day, no rain); a command that ran but changed nothing (still night) is refunded and uncounted,
 * because {@code dispatchCommand}'s true only means the command was found; the rows run as the buyer's UUID in the
 * buyer's world.
 */
class OnlyIfTest {

    @Test
    void theRefusalsComeBeforeAnyCharge() {
        assertEquals("It's already day!", PrizeService.onlyIfRefusal(OnlyIf.NIGHT, true, false));
        assertNull(PrizeService.onlyIfRefusal(OnlyIf.NIGHT, false, false), "at night it may be bought");
        assertEquals("It isn't raining!", PrizeService.onlyIfRefusal(OnlyIf.RAIN, true, false));
        assertNull(PrizeService.onlyIfRefusal(OnlyIf.RAIN, false, true), "in the rain it may");
        assertNull(PrizeService.onlyIfRefusal(OnlyIf.ANY, true, false), "a row without one, always");
        assertNull(PrizeService.onlyIfRefusal(null, true, false));
    }

    @Test
    void afterTheCommandsTheWorldMustHaveChanged() {
        assertTrue(PrizeService.didNothing(OnlyIf.NIGHT, false, false), "still night: it did nothing");
        assertFalse(PrizeService.didNothing(OnlyIf.NIGHT, true, false), "day now: it worked");
        assertTrue(PrizeService.didNothing(OnlyIf.RAIN, true, true), "still raining");
        assertFalse(PrizeService.didNothing(OnlyIf.RAIN, true, false), "clear now");
        assertFalse(PrizeService.didNothing(OnlyIf.ANY, false, true), "a row without only_if is never refunded for it");
        assertFalse(PrizeService.didNothing(null, false, true));
    }

    /**
     * Red-team finding ECON-1: the after-check read {@code World#isDayTime}, a sky value the server works out again
     * only on its next tick, so right after {@code time set day} it still said night and every Make it Day was
     * refunded and given back. Day and night are read on the world's clock now, which the command moves at once.
     */
    @Test
    void dayIsReadOnTheClockTheCommandJustSet() {
        assertTrue(PrizeService.dayOnClock(1_000), "time set day sets 1000: day");
        assertFalse(PrizeService.didNothing(OnlyIf.NIGHT, PrizeService.dayOnClock(1_000), false),
                "so the after-check keeps the buy");
        assertTrue(PrizeService.dayOnClock(6_000), "noon");
        assertEquals("It's already day!", PrizeService.onlyIfRefusal(OnlyIf.NIGHT, PrizeService.dayOnClock(6_000),
                true), "noon in a thunderstorm is still day on the clock: refused before any charge");
        assertFalse(PrizeService.dayOnClock(13_000), "time set night");
        assertFalse(PrizeService.dayOnClock(18_000), "midnight");
        assertTrue(PrizeService.dayOnClock(PrizeService.DUSK - 1), "the last light tick");
        assertFalse(PrizeService.dayOnClock(PrizeService.DUSK), "the first dark one");
        assertFalse(PrizeService.dayOnClock(PrizeService.DAWN), "the last dark one");
        assertTrue(PrizeService.dayOnClock(PrizeService.DAWN + 1), "dawn");
        assertFalse(PrizeService.dayOnClock(24_000L * 40 + 18_000), "a clock many days in is read on its day");
        assertTrue(PrizeService.dayOnClock(-23_000), "and never a negative tick");
    }

    @Test
    void aCommandThatChangedNothingIsRefundedAndUncounted() throws Exception {
        Prize day = ShopTx.row("make_it_day", PrizeType.COMMAND, 20, ShopTx.limit(LimitPer.DAY, 1), List.of(),
                OnlyIf.NIGHT);
        try (ShopTx tx = new ShopTx(100)) {
            assertNull(tx.confirm(day, "d1"), "bought at night: charged and counted");
            assertEquals(80, tx.balance());
            assertEquals(1, tx.bought(day, "d1"));
            // the commands ran, and it is still night
            assertTrue(PrizeService.didNothing(OnlyIf.NIGHT, false, false));
            PrizeService.undo(tx.db, () -> tx.prizes.removePurchase(tx.buyer, "make_it_day", "d1"), () -> {
                if (tx.tokens.change(tx.buyer, 20, "REFUND", "Make it Day!", 2) == TokenDao.REFUSED) {
                    throw new SQLException("refund refused");
                }
            });
            assertEquals(100, tx.balance(), "refunded");
            assertEquals(List.of(-20, 20), tx.ledger(), "one refund line");
            assertEquals(0, tx.bought(day, "d1"), "and the day's one is free again");
            assertNull(tx.confirm(day, "d1"), "so it can be bought again tonight");
        }
    }

    @Test
    void aFailedRefundLeavesTheCountToo() throws Exception {
        Prize day = ShopTx.row("make_it_day", PrizeType.COMMAND, 20, ShopTx.limit(LimitPer.DAY, 1), List.of(),
                OnlyIf.NIGHT);
        try (ShopTx tx = new ShopTx(100)) {
            assertNull(tx.confirm(day, "d1"));
            try {
                PrizeService.undo(tx.db, () -> tx.prizes.removePurchase(tx.buyer, "make_it_day", "d1"), () -> {
                    throw new SQLException("disk full");
                });
            } catch (SQLException expected) {
                // logged SEVERE by the plugin
            }
            assertEquals(1, tx.bought(day, "d1"), "both writes or neither: the count stays with the charge");
        }
    }

    @Test
    void theShippedRowsRunAsTheBuyersUuidInTheirWorld() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        try (var r = new InputStreamReader(OnlyIfTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            c.load(r);
        }
        Map<String, String> want = Map.of("make_it_day", "execute as %uuid% at @s run time set day",
                "sunny_day", "execute as %uuid% at @s run weather clear 12000");
        Map<String, String> when = Map.of("make_it_day", "night", "sunny_day", "rain");
        int seen = 0;
        for (Map<?, ?> row : c.getMapList("arcade.prizes")) {
            String id = String.valueOf(row.get("id"));
            if (want.containsKey(id)) {
                seen++;
                assertEquals(List.of(want.get(id)), row.get("commands"), id + " names %uuid%, never a name");
                assertEquals(when.get(id), row.get("only_if"), id);
            }
        }
        assertEquals(2, seen);
    }
}
