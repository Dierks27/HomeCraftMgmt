package com.dierks.homecraft.arcade.candy;

import com.dierks.homecraft.arcade.TokenService.Source;
import com.dierks.homecraft.arcade.candy.CandyRules.State;
import com.dierks.homecraft.storage.TokenDao;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shower engine's rules for a second shower (0.38.6): the keys under the shower's own prefix, its own daily
 * cap read from its own ledger lines, its words on the bars, the tap that eats a Golden Pie for a full Bedrock
 * player, the Overworld-only skip, and the milestones toward 26. The candy rules themselves are
 * {@code CandyRulesTest}'s, unchanged.
 */
class ShowerRulesTest {

    @Test
    void eachShowerKeepsItsKeysUnderItsOwnPrefix() {
        assertEquals("harvest:2026", CandyRules.counterKey("harvest", 2026));
        assertEquals("harvest:2026:golden_seen", CandyRules.goldenSeenKey("harvest", 2026));
        assertEquals("harvest:2026:hat", CandyRules.rewardKey("harvest", "hat", 2026));
        assertEquals(CandyRules.counterKey(2026), CandyRules.counterKey("candy", 2026), "candy's keys never move");
        assertEquals(CandyRules.goldenSeenKey(2026), CandyRules.goldenSeenKey("candy", 2026));
        assertEquals(CandyRules.bagKey(2026), CandyRules.rewardKey("candy", "bag", 2026));
        assertEquals("harvest:2026:hat", ShowerProfile.harvest().rewardKey(2026), "the Pilgrim Hat's mark");
        assertEquals("harvest:2026:golden_seen", ShowerProfile.harvest().goldenSeenKey(2026));
        assertEquals("candy:2026:bag", ShowerProfile.candy().rewardKey(2026), "the bag's mark, as 0.38.5 wrote it");
        assertEquals("candy:2026", ShowerProfile.candy().counterKey(2026));
    }

    @Test
    void eachShowerCountsOnlyItsOwnLedgerLinesTowardItsCap() {
        UUID p = UUID.randomUUID();
        List<TokenDao.SourceTotal> totals = List.of(new TokenDao.SourceTotal(p, "EVENT_CANDY", 8, 0),
                new TokenDao.SourceTotal(p, "EVENT_HARVEST", 3, 0), new TokenDao.SourceTotal(p, "QUEST", 20, 0));
        assertEquals(3, CandyRules.paidToday(totals, Source.EVENT_HARVEST), "the basket ignores candy's lines");
        assertEquals(8, CandyRules.paidToday(totals, Source.EVENT_CANDY), "and candy ignores the basket's");
        assertEquals(8, CandyRules.paidToday(totals), "the old call is candy's");
        assertEquals(0, CandyRules.paidToday(null, Source.EVENT_HARVEST));
        assertEquals(0, CandyRules.paidToday(totals, null));
        // the basket's cap of 6 at 3 a Golden Pie: 3, 3, then nothing more today
        assertEquals(3, CandyRules.tokensToPay(0, 3, 6, true, true));
        assertEquals(3, CandyRules.tokensToPay(3, 3, 6, true, true));
        assertEquals(0, CandyRules.tokensToPay(6, 3, 6, true, true));
        assertEquals(0, CandyRules.tokensToPay(0, 3, 6, true, CandyRules.paysTokens(GameMode.CREATIVE)),
                "Creative gets the pie and the moment, never tokens");
    }

    @Test
    void theBarsSayTheShowersOwnWordsInPlainLetters() {
        ShowerText t = ShowerText.harvest();
        assertEquals("&6Harvest Basket &f17 &7/ 26", t.barLine(17, 26, false, "hat"));
        assertEquals("&6Harvest Basket: &f26 &7- &aYou got your hat!", t.barLine(26, 26, false, "hat"));
        assertEquals("&6Harvest Basket: &f30 &7- &eYour hat is waiting!", t.barLine(30, 26, true, "hat"));
        assertEquals("&6Harvest Basket: &f17 &7/ 26", t.actionBar(17, 26));
        assertEquals("&6Harvest Basket: &f30", t.actionBar(30, 26), "no 30 / 26");
        for (String line : List.of(t.barLine(30, 26, true, "hat"), t.actionBar(17, 26), t.title(), t.subtitle(),
                t.goldenLine(), t.joinLine(60, "29 Nov", 3, 26))) {
            assertTrue(line.chars().allMatch(c -> c < 0x80), "plain letters for Bedrock: " + line);
        }
        ShowerText candy = ShowerText.candy();
        assertEquals(CandyRules.barLine(17, 31, false), candy.barLine(17, 31, false, "bag"), "candy's bar as it was");
        assertEquals(CandyRules.barLine(35, 31, true), candy.barLine(35, 31, true, "bag"));
        assertEquals(CandyRules.actionBar(17, 31), candy.actionBar(17, 31));
    }

    @Test
    void theJoinLineSaysHowOftenAndTheCount() {
        assertEquals("&6Treats fall every 60 minutes until 29 Nov! &7You've found &f3 &7of 26&7.",
                ShowerText.harvest().joinLine(60, "29 Nov", 3, 26));
        assertEquals("&6Treats fall every 30 minutes until 29 Nov! &7You've found &f27&7.",
                ShowerText.harvest().joinLine(30, "29 Nov", 27, 26), "no '27 of 26'");
        assertEquals("&6✦ Candy apples fall every 60 minutes until 31 Oct! &7You've found &f5 &7of 31&7.",
                ShowerText.candy().joinLine(60, "31 Oct", 5, 31), "candy's line as 0.38.5 said it");
    }

    /** Her Golden Pie: a full Bedrock player eats it with a tap, as she does a plain treat. */
    @Test
    void aFullBedrockPlayerEatsAnyTreatOnATap() {
        boolean goldenPie = HarvestShowerItems.edible(true, true);
        assertTrue(goldenPie, "the Golden Pie is edible on a tap");
        assertTrue(CandyRules.eatsOnTap(true, 20, true, true, goldenPie, true), "Bedrock, full, the Golden Pie, home");
        assertTrue(CandyRules.eatsOnTap(true, 20, true, true, HarvestShowerItems.edible(true, false), true));
        assertFalse(CandyRules.eatsOnTap(false, 20, true, true, goldenPie, true), "Java eats it itself");
        assertFalse(CandyRules.eatsOnTap(true, 19, true, true, goldenPie, true), "hungry: Bedrock eats it itself");
        assertFalse(CandyRules.eatsOnTap(true, 20, true, true, HarvestShowerItems.edible(false, false), true),
                "a plain loaf of bread is left to vanilla");
    }

    /** A player who gets a shower: alive, Survival, in an economy world, home, active, on the ground. */
    private static State who(boolean dead, GameMode mode, boolean economy, boolean games, long idleMillis) {
        return new State(dead, false, mode, economy, games, false, true, idleMillis, false, false, 65.0, 64, false,
                false, false, 0, 0);
    }

    @Test
    void theBasketFallsInTheOverworldOnly() {
        State ok = who(false, GameMode.SURVIVAL, true, false, 0);
        assertNull(CandyRules.skip(ok, true, true), "the Overworld");
        assertEquals(CandyRules.NOT_OVERWORLD, CandyRules.skip(ok, true, false), "the Nether or the End");
        assertNull(CandyRules.skip(ok, false, false), "a shower that may fall anywhere (candy, as 0.38.5)");
        assertEquals("not an economy world", CandyRules.skip(who(false, GameMode.SURVIVAL, false, false, 0), true,
                false), "the earlier reasons first");
        assertEquals("in the Games world", CandyRules.skip(who(false, GameMode.SURVIVAL, true, true, 0), true, false));
        assertEquals("not here", CandyRules.skip(who(true, GameMode.SURVIVAL, true, false, 0), true, false));
        assertEquals(CandyRules.NOT_OVERWORLD, CandyRules.skip(who(false, GameMode.SURVIVAL, true, false, 600_000),
                true, false), "before idle: the world is the first thing to fix");
        assertEquals("idle 10 min", CandyRules.skip(who(false, GameMode.SURVIVAL, true, false, 600_000), true, true));
        assertTrue(ShowerProfile.harvest().overworldOnly());
        assertTrue(ShowerProfile.candy().overworldOnly(), "candy never falls in the Nether or the End either");
    }

    @Test
    void theMilestonesOnTheWayTo26() {
        assertTrue(CandyRules.milestone(9, 10, 26), "10");
        assertTrue(CandyRules.milestone(19, 20, 26), "20");
        assertFalse(CandyRules.milestone(20, 25, 26));
        assertFalse(CandyRules.milestone(25, 26, 26), "the goal has its own moment");
        assertTrue(CandyRules.crossed(25, 26, 26), "the 26th treat is the hat's moment");
        assertFalse(CandyRules.crossed(26, 27, 26), "once");
        assertEquals(1f, CandyRules.progress(30, 26));
    }
}
