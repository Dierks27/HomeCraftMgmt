package com.dierks.homecraft.command;

import com.dierks.homecraft.storage.DeliveryDao;
import com.dierks.homecraft.storage.TradeDao;
import com.dierks.homecraft.trade.TradeStore;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trade window's commands (SHOP-V2-SPEC §4.2, §4.12): {@code /hcm trade} is for players with
 * {@code hcm.trade.use}, {@code /hcm trades} for admins; the help and the tab list know both; and the log's lines
 * say who gave what, and how to settle a trade that didn't finish. The gates are walked through
 * {@link HcmCommand#onCommand} itself, with no plugin behind it: a refusal must come before the command reaches for
 * the trade window.
 */
class TradeCommandTest {

    private static final Set<String> PLAYER = Set.of("hcm.trade.use");
    private static final Set<String> ADMIN = Set.of("hcm.admin", "hcm.trade.use");

    private static CommandSender sender(Set<String> perms, List<String> told) {
        return (CommandSender) Proxy.newProxyInstance(TradeCommandTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, m, a) -> switch (m.getName()) {
                    case "hasPermission" -> a[0] instanceof String node && perms.contains(node);
                    case "getName" -> "Tester";
                    case "sendMessage" -> {
                        if (a[0] instanceof Component c) {
                            told.add(Text.plain(LegacyComponentSerializer.legacyAmpersand().serialize(c)));
                        } else if (a[0] instanceof String s) {
                            told.add(Text.plain(s));
                        }
                        yield null;
                    }
                    case "equals" -> proxy == a[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Tester";
                    default -> m.getReturnType() == boolean.class ? false : null;
                });
    }

    private static List<String> run(Set<String> perms, String... args) {
        List<String> told = new ArrayList<>();
        new HcmCommand(null).onCommand(sender(perms, told), null, "hcm", args);
        return told;
    }

    private static List<String> tab(Set<String> perms, String... args) {
        return new HcmCommand(null).onTabComplete(sender(perms, new ArrayList<>()), null, "hcm", args);
    }

    @Test
    void tradingIsForPlayersAndTheLogForAdmins() {
        assertEquals(List.of("Only players can trade."), run(PLAYER, "trade", "Helper"), "the console can't trade");
        assertEquals(List.of("You don't have permission."), run(PLAYER, "trades"));
        assertEquals(List.of("You don't have permission."), run(PLAYER, "trades", "settle", "4", "release"));
        assertEquals(List.of("The trade log isn't available."), run(ADMIN, "trades"),
                "an admin gets past the gate (no plugin here)");
    }

    @Test
    void theHelpAndTheTabListKnowBoth() {
        String player = String.join("\n", run(PLAYER));
        assertTrue(player.contains("/trade <player> - trade with someone (or /hcm trade <player>)"), player);
        assertFalse(player.contains("/hcm trades"), "the log is not in a player's help");
        assertTrue(String.join("\n", run(ADMIN)).contains(
                "/hcm trades [player] [n] | show <id> | settle <id> release|void [player] [confirm] - the trade log"));
        assertTrue(tab(PLAYER, "tr").contains("trade"));
        assertFalse(tab(PLAYER, "tr").contains("trades"));
        assertTrue(tab(ADMIN, "tr").contains("trades"));
        assertEquals(List.of("settle"), tab(ADMIN, "trades", "se"));
        assertEquals(List.of("release"), tab(ADMIN, "trades", "settle", "4", "re"));
        assertEquals(List.of("confirm"), tab(ADMIN, "trades", "settle", "4", "release", "co"));
        assertEquals(List.of("confirm"), tab(ADMIN, "trades", "settle", "4", "void", "Owner", ""));
        assertFalse(tab(Set.of(), "tr").contains("trade"), "no hcm.trade.use, no trade word");
    }

    @Test
    void pluginYmlKnowsTheCommandTheNodeAndTheLines() throws Exception {
        String yml = java.nio.file.Files.readString(java.nio.file.Path.of("src", "main", "resources", "plugin.yml"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(yml.contains("\n  trade:\n"), "the /trade command");
        assertTrue(yml.contains("usage: /trade <player>|accept|deny|cancel|on|off"), "its usage line (§4.14)");
        int node = yml.indexOf("\n  hcm.trade.use:\n");
        assertTrue(node > 0, "the hcm.trade.use node");
        assertTrue(yml.substring(node).contains("default: true"), "for everyone");
        assertTrue(yml.contains("\n      /hcm trades settle <id> release|void [player] [confirm]  - admin: a trade the"
                + " server didn't see finish\n"));
        assertTrue(yml.contains("\n      /hcm trade <player>    - trade with someone"));
    }

    private static TradeDao.Trade row(String outcome) {
        return row(outcome, "");
    }

    private static TradeDao.Trade row(String outcome, String reason) {
        return new TradeDao.Trade(12, UUID.randomUUID(), "Owner", UUID.randomUUID(), "Helper", "world", 0, 60_000,
                outcome, reason, "b64-a\nb64-b", "", "3 Diamond, Creeper Mini", "", 0, 50, 1);
    }

    @Test
    void aLogLineSaysWhoGaveWhat() {
        String line = Text.plain(TradeCommand.line(row(TradeDao.DONE)));
        assertTrue(line.startsWith("#12 "), line);
        assertTrue(line.contains("DONE Owner: 3 Diamond, Creeper Mini | Helper: $50.00 (1 to Mailbox)"), line);
    }

    @Test
    void showSaysHowToSettleATradeThatDidntFinish() {
        DeliveryDao.Delivery held = new DeliveryDao.Delivery(7, UUID.randomUUID(), "b64", "From your trade with Owner",
                "TRADE#12", 0, 0, DeliveryDao.HELD);
        String text = Text.plain(String.join("\n", TradeCommand.showLines(row(TradeDao.PENDING), List.of(held),
                TradeStore.Saved.BOTH)));
        assertTrue(text.contains("Trade #12 - PENDING"), text);
        assertTrue(text.contains("Owner gave: 3 Diamond, Creeper Mini (2 stack(s) stored)"), text);
        assertTrue(text.contains("Helper gave: $50.00"), text);
        assertTrue(text.contains("#7 HELD"), text);
        assertTrue(text.contains("/hcm trades settle 12 release"), text);
        assertFalse(Text.plain(String.join("\n", TradeCommand.showLines(row(TradeDao.DONE), List.of(), null)))
                .contains("settle"), "a finished trade has nothing to settle");
    }

    private static String show(TradeDao.Trade t, TradeStore.Saved state) {
        return Text.plain(String.join("\n", TradeCommand.showLines(t, List.of(), state)));
    }

    /**
     * Red-team findings TW-2, TCS-2, TCS-3, TCR4-WORTH-1: a PENDING trade's bags can be from before it, from after it,
     * or one of each, and one verb fits each. {@code show} names it, from where the bags are (the mark each bag is
     * saved with, written on the row at the start); nothing is rolled back on its own.
     */
    @Test
    void showSaysWhichWayToSettle() {
        String neither = show(row(TradeDao.PENDING, "bags not saved"), TradeStore.Saved.NEITHER);
        assertTrue(neither.contains("Void it: neither bag was saved with it, so both came back from before it, each"
                + " still holding its own offer: /hcm trades settle 12 void (release would hand out copies)"), neither);
        assertFalse(neither.contains("Why:"), "where the bags are is not a reason to show");
        assertTrue(neither.contains("release hands the held rows out (done); void deletes them and puts Mini owners"
                + " and claims back; void <player> also sends that player's own offer to their Mailbox; add confirm to"
                + " go against the read."), neither);
        String both = show(row(TradeDao.PENDING, "bags saved"), TradeStore.Saved.BOTH);
        assertTrue(both.contains("Release it: both bags were saved with it, so its held Mailbox rows are owed: /hcm"
                + " trades settle 12 release"), both);
        assertFalse(both.contains("Void it"), both);
        // only the owner's was saved; the helper gave only money, so nothing is to be taken out of the owner's bag
        String one = show(row(TradeDao.PENDING, "bag a saved"), TradeStore.Saved.A);
        assertTrue(one.contains("Void it: only Owner's bag was saved with it (what Owner gave is in no bag, and what"
                + " Helper gave is in both): /hcm trades settle 12 void Owner: what Owner gave goes to their Mailbox."),
                one);
        String unknown = show(row(TradeDao.PENDING, "bags unread"), TradeStore.Saved.UNKNOWN);
        assertTrue(unknown.contains("Look first: its bags couldn't be read"), unknown);
        // a row an earlier build left rolling the helper's bag back reads as only the helper's saved
        TradeDao.Trade rolling = row(TradeDao.PENDING, "rolling back b");
        assertEquals(TradeStore.Saved.B, TradeStore.recorded(rolling));
        String b = show(rolling, TradeStore.Saved.B);
        assertTrue(b.contains("Void it: only Helper's bag was saved with it (what Helper gave is in no bag, and what"
                + " Owner gave is in both): take what Owner gave (3 Diamond, Creeper Mini) out of Helper's bag, less"
                + " what /hcm trades show 12 lists as held for Helper (that goes with the trade), then /hcm trades settle"
                + " 12 void Helper: what Helper gave goes to their Mailbox"), b);
        assertFalse(b.contains("Rolling back"), b);
        // SETTLE-REHELD-COPY-2: one bag alone read after they played on names no verb
        String played = show(row(TradeDao.PENDING, "bags unread, played on"), TradeStore.Saved.UNKNOWN);
        assertEquals(TradeStore.Saved.UNKNOWN, TradeStore.recorded(row(TradeDao.PENDING, "bags unread, played on")));
        assertTrue(played.contains("Look first: only one bag reads as saved with it, but they played on after the"
                + " swap"), played);
        // R5-PLAYEDON-HELD-1: void <player> after a look subtracts what is held, as for one bag saved
        for (String look : List.of(unknown, played)) {
            assertTrue(look.contains("void <player> if only that player's does (take what the other gave out of it"
                    + " first, less what /hcm trades show 12 lists as held for that player, which goes with the"
                    + " trade;"), look);
        }
    }

    /**
     * Red-team finding SETTLE-HELD-AMOUNTS-4: the advice for one bag saved says to take the other side's offer out of
     * it less what is held, but {@code show} printed each Mailbox row as an id, a status and a label, so the admin
     * couldn't tell 40 held logs from 64. Each row now says what it holds.
     */
    @Test
    void showSaysWhatEachMailboxRowHolds() {
        UUID her = UUID.randomUUID();
        DeliveryDao.Delivery held = new DeliveryDao.Delivery(41, her, "logs-b64", "From your trade with Owner",
                "TRADE#12", 0, 0, DeliveryDao.HELD);
        String text = Text.plain(String.join("\n", TradeCommand.showLines(row(TradeDao.PENDING, "bag b saved"),
                List.of(held), TradeStore.Saved.B, null, "", b64 -> b64.equals("logs-b64") ? "40 Oak Log" : "?")));
        assertTrue(text.contains("#41 HELD for "), text);
        assertTrue(text.contains(": 40 Oak Log (From your trade with Owner)"), text);
        assertTrue(text.contains("less what /hcm trades show 12 lists as held for Helper"), text);
    }

    /**
     * Red-team finding AW-1: a finished trade is never reopened; {@code show} reads each bag now for it too, so an
     * admin who put a player file back can see which trades since that backup it undid in that bag.
     */
    @Test
    void showReadsTheBagsOfAFinishedTradeToo() {
        String text = Text.plain(String.join("\n", TradeCommand.showLines(row(TradeDao.DONE), List.of(), null,
                new Boolean[] {false, true}, "", b64 -> b64)));
        assertTrue(text.contains("Its bags now: Owner's is from before it; Helper's holds the trade (a finished trade"
                + " is never reopened: a bag from before it was put back from an older copy, so set right by hand only"
                + " what you want undone)"), text);
        assertFalse(text.contains("settle"), "nothing to settle");
    }

    /** Red-team finding R5-NORMAL-1: a normal finished trade's {@code show} has no note about an older copy. */
    @Test
    void showOfANormalFinishedTradeHasNoOlderCopyNote() {
        for (Boolean[] now : List.of(new Boolean[] {true, true}, new Boolean[] {null, null},
                new Boolean[] {true, null})) {
            String text = Text.plain(String.join("\n", TradeCommand.showLines(row(TradeDao.DONE), List.of(), null,
                    now, "", b64 -> b64)));
            assertTrue(text.contains("Its bags now: "), text);
            assertFalse(text.contains("older copy"), text);
            assertFalse(text.contains("settle"), "nothing to settle");
        }
    }

    /** Red-team finding TCR4-2: {@code show} reads each bag now, so the admin who looks knows which holds it. */
    @Test
    void showReadsEachBagNowAndNamesTheMoney() {
        String text = Text.plain(String.join("\n", TradeCommand.showLines(row(TradeDao.PENDING, "bags unread"),
                List.of(), TradeStore.Saved.UNKNOWN, new Boolean[] {true, null},
                "Money isn't paid back by a settle: Owner paid $0.00, Helper paid $50.00 (had $80.00 just before)",
                b64 -> b64)));
        assertTrue(text.contains("Its bags now: Owner's holds the trade; Helper's can't say (a later trade, or no file"
                + " or mark)"), text);
        assertTrue(text.contains("Money isn't paid back by a settle: Owner paid $0.00, Helper paid $50.00 (had $80.00"
                + " just before)"), text);
        assertTrue(Text.plain(String.join("\n", TradeCommand.showLines(row(TradeDao.PENDING, "bags not saved"),
                List.of(), TradeStore.Saved.NEITHER, new Boolean[] {false, false}, "", b64 -> b64))).contains(
                "Its bags now: Owner's is from before it; Helper's is from before it"));
    }

    /** Red-team findings TCR4-OVERRIDE-3, TCR4-2: {@code settle <id> release|void [player] [confirm]}. */
    @Test
    void settleReadsAPlayerAndConfirm() {
        assertEquals(new TradeCommand.Settle(12, true, null, false),
                TradeCommand.settleArgs(new String[] {"trades", "settle", "12", "release"}));
        assertEquals(new TradeCommand.Settle(12, true, null, true),
                TradeCommand.settleArgs(new String[] {"trades", "settle", "12", "release", "confirm"}));
        assertEquals(new TradeCommand.Settle(12, false, "Owner", false),
                TradeCommand.settleArgs(new String[] {"trades", "settle", "12", "void", "Owner"}));
        assertEquals(new TradeCommand.Settle(12, false, "Owner", true),
                TradeCommand.settleArgs(new String[] {"trades", "settle", "12", "VOID", "Owner", "CONFIRM"}));
        assertEquals(new TradeCommand.Settle(12, false, null, true),
                TradeCommand.settleArgs(new String[] {"trades", "settle", "12", "void", "confirm"}));
        assertEquals(null, TradeCommand.settleArgs(new String[] {"trades", "settle", "12", "release", "Owner"}),
                "release names no player");
        assertEquals(null, TradeCommand.settleArgs(new String[] {"trades", "settle", "12", "void", "confirm", "Owner"}),
                "confirm comes last");
        assertEquals(null, TradeCommand.settleArgs(new String[] {"trades", "settle", "x", "void"}));
        assertEquals(null, TradeCommand.settleArgs(new String[] {"trades", "settle", "12"}));
    }

    /**
     * Red-team findings TT-2 and CMD-7: {@code /hcm trades <name>} turned any word into a UUID with
     * {@code Bukkit.getOfflinePlayer(String)}, which may look the name up on the web on the main thread, and a typo
     * then listed "No trades yet." for a made-up id. A name is now read from the server's own records only, and one
     * nobody here has is said so.
     */
    @Test
    void anUnknownNameIsSaidSoAndNeverLookedUp() throws Exception {
        UUID her = UUID.randomUUID();
        java.util.function.Function<String, UUID> here = name -> name.equalsIgnoreCase("Daughter") ? her : null;
        TradeCommand.ListQuery q = TradeCommand.listQuery(new String[] {"trades", "Daugter"}, here);
        assertEquals("Daugter", q.unknown(), "a typo is never listed as \"No trades yet\"");
        q = TradeCommand.listQuery(new String[] {"trades", "Daughter", "5"}, here);
        assertEquals(her, q.player());
        assertEquals("Daughter", q.who());
        assertEquals(5, q.n());
        assertEquals(null, q.unknown());
        q = TradeCommand.listQuery(new String[] {"trades", "500"}, here);
        assertEquals(null, q.player(), "everyone's");
        assertEquals("everyone", q.who());
        assertEquals(100, q.n(), "at most 100");
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/dierks/homecraft/command/TradeCommand.java")).replaceAll("\\s+", " ");
        assertFalse(src.contains("getOfflinePlayer(args"), "no web lookup by name");
        assertTrue(src.contains("OfflinePlayer p = LooksCommand.offline(name);"), "the cache-only lookup");
        assertTrue(src.contains("\"&cNo player called '\" + q.unknown() + \"' has been here.\""));
    }
}
