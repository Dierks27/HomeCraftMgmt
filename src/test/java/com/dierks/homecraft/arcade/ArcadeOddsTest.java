package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.Jackpot;
import com.dierks.homecraft.config.PluginConfig.Lotto;
import com.dierks.homecraft.config.PluginConfig.LottoPayout;
import com.dierks.homecraft.config.PluginConfig.Quest;
import com.dierks.homecraft.config.PluginConfig.QuestPeriod;
import com.dierks.homecraft.config.PluginConfig.QuestType;
import com.dierks.homecraft.games.TokenBalance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Scratch Ticket must be a sink — it returns less than it takes — without feeling like a
 * robbery; and a quest draw never hands a player two of the same kind of chore.
 */
class ArcadeOddsTest {

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = ArcadeOddsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(reader);
                return c;
            }
        }
    }

    private static Lotto shippedLotto() throws Exception {
        YamlConfiguration c = bundled();
        List<LottoPayout> payouts = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList("arcade.lotto.payouts")) {
            double weight = ((Number) row.get("weight")).doubleValue();
            if (Boolean.TRUE.equals(row.get("jackpot"))) {
                payouts.add(new LottoPayout(0, true, weight));
            } else {
                payouts.add(new LottoPayout(((Number) row.get("tokens")).intValue(), false, weight));
            }
        }
        return new Lotto(c.getInt("arcade.lotto.ticket_tokens"), payouts,
                new Jackpot(c.getInt("arcade.lotto.jackpot.seed"), c.getInt("arcade.lotto.jackpot.per_ticket"),
                        c.getInt("arcade.lotto.jackpot.cap")));
    }

    @Test
    void theShippedTicketReturnsInsideTheGamesBand() throws Exception {
        // the 0.37 token balance brings it from 77.6% into the house's 85-95 band
        double rtp = ArcadeService.rtp(shippedLotto());
        assertTrue(rtp >= 0.85 && rtp <= 0.95, "RTP " + rtp + " is outside the 85-95 band");
        double back = 0;
        double weights = 0;
        for (java.util.Map<String, Object> row : TokenBalance.TICKET_PAYOUTS) {
            int w = ((Number) row.get("weight")).intValue();
            weights += w;
            back += w * (Boolean.TRUE.equals(row.get("jackpot")) ? 150.0 : ((Number) row.get("tokens")).doubleValue());
        }
        assertEquals(back / weights / shippedLotto().ticketTokens(), rtp, 1e-9,
                "the token balance's table, with the pot at its steady 150, over a ticket's 10");
        assertTrue(rtp < 1.0, "and it is still a sink: it returns less than it takes");
    }

    @Test
    void theJackpotIsCountedAtItsSteadyStateSize() throws Exception {
        Lotto l = shippedLotto();
        // 1 in 100 tickets wins it, and each adds 1: the pot is 50 + 100 when it goes.
        assertEquals(150.0, ArcadeService.steadyStatePot(l.jackpot(), 0.01), 1e-9);
        assertEquals(1000.0, ArcadeService.steadyStatePot(new Jackpot(50, 1, 1000), 0.0001), 1e-9,
                "never past the cap");
    }

    @Test
    void theNearMissIsTheMostCommonResult() throws Exception {
        LottoPayout most = null;
        for (LottoPayout p : shippedLotto().payouts()) {
            if (most == null || p.weight() > most.weight()) {
                most = p;
            }
        }
        assertNotNull(most);
        assertTrue(most.tokens() > 0 && !most.jackpot(), "the most common result is a small win, not the dud");
    }

    @Test
    void aDrawNeverRepeatsAType() throws Exception {
        YamlConfiguration c = bundled();
        List<Quest> pool = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList("arcade.quests.daily_pool")) {
            pool.add(new Quest(String.valueOf(row.get("id")), QuestPeriod.DAILY,
                    QuestType.valueOf(String.valueOf(row.get("type"))),
                    ((Number) row.get("target")).longValue(), ((Number) row.get("reward")).intValue(),
                    String.valueOf(row.get("display"))));
        }
        // Two rows of one type, so a naive shuffle-and-take would sometimes pick both.
        pool.add(new Quest("fish_extra", QuestPeriod.DAILY, QuestType.CATCH_FISH, 3, 1, "Fish"));
        Random random = new Random(42);
        Set<Set<String>> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            List<Quest> drawn = QuestService.draw(pool, 3, random);
            assertEquals(3, drawn.size());
            Set<QuestType> types = EnumSet.noneOf(QuestType.class);
            Set<String> ids = new HashSet<>();
            for (Quest q : drawn) {
                assertTrue(types.add(q.type()), "two " + q.type() + " quests in one draw: " + drawn);
                ids.add(q.id());
            }
            seen.add(ids);
        }
        assertTrue(seen.size() > 50, "draws vary from player to player and day to day");
    }

    @Test
    void aPoolWithFewerTypesThanTheDrawHandsOutFewer() {
        List<Quest> pool = List.of(
                new Quest("a", QuestPeriod.DAILY, QuestType.CATCH_FISH, 1, 1, "a"),
                new Quest("b", QuestPeriod.DAILY, QuestType.CATCH_FISH, 2, 1, "b"));
        assertEquals(1, QuestService.draw(pool, 3, new Random(1)).size());
    }
}
