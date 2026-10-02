package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.Jackpot;
import com.dierks.homecraft.config.PluginConfig.Lotto;
import com.dierks.homecraft.config.PluginConfig.LottoPayout;
import com.dierks.homecraft.games.TokenBalance;
import com.dierks.homecraft.games.gen.LayoutFixtures;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Scratch Ticket's safety net when config.yml loads (the v4 audit, ECON00): unlike the other games of
 * chance it isn't clamped into the house's 85-95 band, since its prizes are a table the owner writes, so a
 * return outside the band gives one WARN, and at 100% or more (every ticket gains on average: a faucet) the
 * ticket is closed until it is fixed.
 *
 * <p>Pinned here: the shipped ticket is in the band, quiet and open; 0.36's prizes (77.6%) and a ticket
 * retuned past 95 each give one WARN that says which way to go; at 100% exactly and above it is closed and
 * the WARN says sales are refused; a ticket with no prizes is neither; and {@link PluginConfig#lotto} reads
 * the file as the plugin plays it.
 */
class ScratchTicketBandTest {

    private static Lotto read(YamlConfiguration c) {
        return PluginConfig.lotto(c, null);
    }

    /** The bundled ticket with {@code key} set to {@code value}. */
    private static Lotto shippedWith(String key, Object value) {
        YamlConfiguration c = LayoutFixtures.bundled();
        c.set(key, value);
        return read(c);
    }

    @Test
    void theShippedTicketIsInTheBandQuietAndOpen() {
        Lotto l = read(LayoutFixtures.bundled());
        assertEquals(10, l.ticketTokens(), "10 tokens a ticket");
        assertEquals(new Jackpot(50, 1, 1000), l.jackpot(), "the shipped pot");
        assertEquals(PluginConfig.lottoPayouts(TokenBalance.TICKET_PAYOUTS, null), l.payouts(), "the token balance's prizes");
        assertEquals(0.895, ArcadeService.rtp(l), 1e-9, "89.5%");
        assertNull(ArcadeService.ticketWarning(l), "no WARN");
        assertFalse(ArcadeService.ticketClosed(l), "and open");
    }

    @Test
    void outsideTheBandGivesOneWarnSayingWhichWay() {
        Lotto old = read(LayoutFixtures.v036());
        assertEquals("arcade.lotto: the Scratch Ticket gives back 77.6% (its ticket_tokens, payouts and jackpot together),"
                + " below the house's 85-95 band: raise its prizes or jackpot, or lower ticket_tokens.",
                ArcadeService.ticketWarning(old), "0.36's prizes, kept by an owner who changed them");
        assertFalse(ArcadeService.ticketClosed(old), "still a sink: open");

        Lotto nine = shippedWith("arcade.lotto.ticket_tokens", 9);
        assertEquals("arcade.lotto: the Scratch Ticket gives back 99.4% (its ticket_tokens, payouts and jackpot together),"
                + " above the house's 85-95 band: lower its prizes or jackpot, or raise ticket_tokens.",
                ArcadeService.ticketWarning(nine), "0.37's prizes on a 9-token ticket");
        assertFalse(ArcadeService.ticketClosed(nine), "under 100: open, with the WARN");
    }

    @Test
    void atOrAbove100PercentTheTicketIsClosed() {
        for (Object[] k : new Object[][] {
                {"arcade.lotto.ticket_tokens", 8, "111.8"},
                {"arcade.lotto.jackpot.seed", 200, "104.5"}}) {
            Lotto l = shippedWith((String) k[0], k[1]);
            assertTrue(ArcadeService.ticketClosed(l), k[0] + " " + k[1] + ": every ticket gains on average");
            assertEquals("arcade.lotto: the Scratch Ticket gives back " + k[2] + "% (its ticket_tokens, payouts and"
                    + " jackpot together), as much as it costs or more, so every ticket would gain tokens on average."
                    + " Ticket sales are refused until it gives back less: lower its prizes or jackpot, or raise"
                    + " ticket_tokens, into the house's 85-95 band.", ArcadeService.ticketWarning(l), k[0] + " " + k[1]);
        }
        Lotto even = new Lotto(10, List.of(new LottoPayout(10, false, 1)), new Jackpot(50, 1, 1000));
        assertEquals(1.0, ArcadeService.rtp(even), 1e-12, "every ticket back, exactly");
        assertTrue(ArcadeService.ticketClosed(even), "100% is not a game of chance either: closed");
        assertEquals("The Scratch Ticket is closed right now.", ArcadeService.TICKET_CLOSED, "what a player reads");
    }

    @Test
    void aTicketWithNoPrizesIsNotSetUpRatherThanClosed() {
        Lotto none = new Lotto(10, List.of(), new Jackpot(50, 1, 1000));
        assertNull(ArcadeService.ticketWarning(none), "no WARN: the screens hide it");
        assertFalse(ArcadeService.ticketClosed(none), "\"isn't set up\" says it");
        assertNull(ArcadeService.ticketWarning(null), "nor for no ticket at all");
    }

    @Test
    void aClosedTicketIsRefusedBeforeATokenIsTakenAndTheWarnIsLoggedAtLoad() throws Exception {
        // the wiring, which needs a server to run: ArcadeService.scratch and PluginConfig's arcade reader
        String service = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/dierks/homecraft/arcade/ArcadeService.java"));
        String scratch = service.substring(service.indexOf("public Outcome scratch(Player player) {"));
        int closed = scratch.indexOf("if (ticketClosed(l)) {");
        assertTrue(closed > 0, "scratch() asks whether the ticket is closed");
        assertTrue(closed < scratch.indexOf("tokens().spend("), "before it takes a token");
        assertTrue(scratch.substring(closed, scratch.indexOf("}", closed)).contains("Outcome.fail(TICKET_CLOSED)"),
                "and refuses with the player's line");
        String config = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/dierks/homecraft/config/PluginConfig.java"));
        String arcade = config.substring(config.indexOf("private Arcade readArcade("),
                config.indexOf("public static Lotto lotto("));
        assertTrue(arcade.contains("Lotto lotto = lotto(c, log::warning);")
                && arcade.contains("ArcadeService.ticketWarning(lotto)") && arcade.contains("log.warning(ticket)"),
                "config.yml's load reads the ticket here and logs its WARN");
    }

    @Test
    void theReaderClampsAsBeforeAndSkipsARowWithNoPrize() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("arcade.lotto.ticket_tokens", 0);
        c.set("arcade.lotto.jackpot.seed", -5);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("tokens", 3, "weight", 2));
        rows.add(Map.of("weight", 1));
        rows.add(Map.of("jackpot", true, "weight", 1));
        c.set("arcade.lotto.payouts", rows);
        List<String> warns = new ArrayList<>();
        Lotto l = PluginConfig.lotto(c, warns::add);
        assertEquals(1, l.ticketTokens(), "a ticket costs at least 1");
        assertEquals(new Jackpot(0, 1, 1000), l.jackpot(), "no negative seed; the rest as shipped");
        assertEquals(List.of(new LottoPayout(3, false, 2), new LottoPayout(0, true, 1)), l.payouts(),
                "the row with no prize is skipped");
        assertEquals(1, warns.size(), "with one line: " + warns);
    }
}
