package com.dierks.homecraft.arcade.looks;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a heads-up may go as far as the join and our own titles go (SHOP-V2-SPEC §3.10, red team O20), and the wiring
 * of the notice rules into {@link LookService}: the join's own task ends its wait, a Bedrock chest screen holds a
 * notice, a quiet end offers no price, and the title counts only titled notices.
 */
class NoticeGateTest {

    private static final long WAIT = LookService.JOIN_NOTICE_DELAY * 50;
    private final UUID her = UUID.randomUUID();

    /**
     * Red-team finding TT-1: the join's task runs 101 ticks on, but a server catching up after a slow tick runs ticks
     * back to back, so the task could come before the wall-clock stamp and its heads-up waited up to a minute. The
     * task's delay is the wait: it ends the wait itself.
     */
    @Test
    void theJoinsOwnTaskEndsItsWait() {
        NoticeGate gate = new NoticeGate();
        long join = 1_000_070L;
        long from = gate.joined(her, join, WAIT);
        assertFalse(gate.allows(her, join + 1_000), "a minute tick in the first 100 ticks: held");
        long task = join + WAIT - 20; // 101 ticks, run back to back after a slow tick
        assertFalse(gate.allows(her, task), "the stamp alone would hold it");
        gate.joinWaitOver(her, from);
        assertTrue(gate.allows(her, task), "the join's own task sends it now");
    }

    /** A quick rejoin: the first join's task never opens the second join's wait. */
    @Test
    void aLaterJoinsWaitStays() {
        NoticeGate gate = new NoticeGate();
        long first = gate.joined(her, 0, WAIT);
        gate.drop(her); // she left...
        gate.joined(her, 3_000, WAIT); // ...and came back 3 s later
        gate.joinWaitOver(her, first); // the first join's task
        assertFalse(gate.allows(her, WAIT + 100), "the second join's wait holds");
        assertTrue(gate.allows(her, 3_000 + WAIT));
    }

    @Test
    void aTitleOfOursHoldsItAndAnAdminsEndOpensIt() {
        NoticeGate gate = new NoticeGate();
        gate.titleShown(her, 9_000);
        gate.titleShown(her, 4_000);
        assertFalse(gate.allows(her, 8_999), "the later title holds");
        assertTrue(gate.allows(her, 9_000));
        gate.joined(her, 10_000, WAIT);
        gate.open(her);
        assertTrue(gate.allows(her, 10_001), "/hcm looks end shows what is due now");
        gate.titleShown(her, 20_000);
        gate.drop(her);
        assertTrue(gate.allows(her, 10_001), "she left: nothing is kept");
    }

    @Test
    void theServiceIsWiredToTheRules() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/dierks/homecraft/arcade/looks/LookService.java"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String joined = body(src, "void joined(Player player) {");
        assertTrue(joined.contains("long from = gate.joined(id, now(), JOIN_NOTICE_DELAY * 50);"), joined);
        assertTrue(joined.indexOf("gate.joinWaitOver(id, from);") >= 0
                && joined.indexOf("gate.joinWaitOver(id, from);") < joined.indexOf("notices(player);"),
                "TT-1: the task ends its wait, then sends");
        String notices = body(src, "public void notices(Player player) {");
        assertTrue(notices.contains("LookRules.screenFree(Bedrock.is(player), "
                + "player.getOpenInventory().getTopInventory().getType() == InventoryType.CRAFTING)"),
                "DJ-2: a Bedrock chest screen holds a notice");
        assertTrue(notices.contains("&& gate.allows(owner, now);"));
        assertTrue(notices.contains("String[] title = LookRules.titleFor(sent, now, this::name);"),
                "F2: only titled notices count");
        assertTrue(notices.contains("int price = !LookRules.quietEnd(row.since(), row.expiresAt()) &&"),
                "F1: a quiet end offers no price, so no [Buy again] either");
        assertTrue(notices.contains("if (!bedrock && price >= 0) {"));
    }

    /**
     * Red-team finding TT1-STALE-REJOIN: Paper answers {@code isOnline} by UUID, so after a quick rejoin the first
     * join's tasks still ran, on the old Player: they took the giver's waiting lines and sent them to the closed
     * connection, and on a slow server saved the heads-ups as sent. Each task acts only while its own join's
     * connection is open.
     */
    @Test
    void aJoinsTasksActOnlyOnThatJoinsConnection() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/dierks/homecraft/arcade/looks/LookService.java"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String joined = body(src, "void joined(Player player) {");
        assertFalse(joined.contains("isOnline()"), "[X] not by UUID: " + joined);
        assertTrue(joined.contains("if (player.isConnected()) { ends(player); sweep(player); }"), joined);
        assertTrue(joined.contains("if (player.isConnected()) { gate.joinWaitOver(id, from);"), joined);
        int guard = joined.lastIndexOf("if (player.isConnected())");
        assertTrue(guard < joined.indexOf("takeGiverLines(id)") && guard < joined.indexOf("notices(player);"),
                "[X] the giver's lines are taken only for a live connection");
        assertTrue(body(src, "void changedWorld(Player player) {").contains("if (player.isConnected()) {"));
    }

    /** The text of a method from its head to its closing brace, whitespace already one space (as RentalWiringTest). */
    private static String body(String src, String head) {
        int from = src.indexOf(head);
        assertTrue(from >= 0, "fixture: " + head);
        int at = from + head.length();
        int depth = 1;
        for (int i = at; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return src.substring(at, i);
            }
        }
        throw new AssertionError("no end to " + head);
    }
}
