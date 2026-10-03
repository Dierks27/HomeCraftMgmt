package com.dierks.homecraft.games.gen.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mutation proof for the recorded-size guard (V4-DECISIONS "Fix: wetRegions and every old-region guard
 * use the claim's recorded sizes"; GOLF-V4-SPEC §5.2 step 2): give the engine the old bug, an old claim's
 * halves at TODAY's size for its slot ({@link #TODAYS_SIZE}), and the scenarios must notice. On the owner's
 * server golf's and the boat's old halves are then guarded in the wrong place; on a 0.35-shaped claim with
 * blocks in both halves, half B isn't emptied. With the fix ({@link GenService#RECORDED}) both come out right
 * ({@code OwnerServerUpgradeTest}, {@code RetireTest}).
 */
class RetireMutationTest {

    /** The bug the fix removes: an old claim's halves at the slot's size now, not the claim's own. */
    static final GenService.OldHalves TODAYS_SIZE = (def, claim) -> Regions.halves(def, Regions.claimOrigin(claim),
            Regions.claimGap(claim));

    private OwnerServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            if (server.gen != null) {
                server.gen.stop();
            }
            server.host.connection.close();
        }
    }

    @Test
    void guardingTheOwnersOldAreasAtTodaysSizesIsCaught() throws Exception {
        server = new OwnerServer().at036();
        server.upgrade(TODAYS_SIZE, 20);
        List<String> failed = server.failures();
        assertTrue(failed.contains("golf's old halves weren't guarded at the sizes its claim recorded"),
                "the mutant guards golf's old area in the wrong place, and the scenario says so: " + failed);
        assertTrue(failed.contains("the boat's old halves weren't guarded at the sizes its claim recorded"),
                "and the boat's: " + failed);
    }

    @Test
    void emptyingAnOldAreaAtTodaysSizesIsCaught() throws Exception {
        List<String> failed = RetireTest.bothHalves(TODAYS_SIZE);
        assertTrue(failed.stream().anyMatch(f -> f.startsWith("the old halves still hold")),
                "the mutant leaves part of the old half B standing, and the scenario says so: " + failed);
        assertTrue(failed.contains("the old halves weren't guarded where the claim recorded them"),
                "and guards the wrong boxes meanwhile: " + failed);
    }

    @Test
    void theFixPassesBothScenarios() throws Exception {
        assertEquals(List.of(), RetireTest.bothHalves(GenService.RECORDED), "the recorded sizes: all right");
    }
}
