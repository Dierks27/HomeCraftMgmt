package com.dierks.homecraft.command;

import com.dierks.homecraft.arcade.looks.LookService;
import com.dierks.homecraft.command.LooksCommand.Sub;
import com.dierks.homecraft.storage.LookDao;
import com.dierks.homecraft.storage.LookDao.Look;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /hcm looks ...} (SHOP-V2-SPEC §3.14): give, end, list, renew and gift as they parse, who may run each, the
 * days each takes, and the admin's list line.
 */
class LooksCommandTest {

    private static String[] args(String line) {
        return line.split(" ");
    }

    /**
     * Red-team finding BR-4: {@code /hcm looks give} threw away where the key went, so with a cap on her head and a
     * full bag her screen said "It's yours until..." and the owner's chat said nothing, with the key in My Looks.
     * Both lines now say where it went.
     */
    @Test
    void aGiveSaysWhereTheKeyWent() {
        String none = com.dierks.homecraft.arcade.looks.LookRules.where(
                com.dierks.homecraft.arcade.looks.LookRules.Placement.NOWHERE);
        String line = com.dierks.homecraft.arcade.looks.LookRules.givenLine("23 Oct", none);
        assertEquals("It's yours until 23 Oct! It's in My Looks - tap Wear it.", line, "her present's line");
        assertEquals("It's yours until 23 Oct!", com.dierks.homecraft.arcade.looks.LookRules.givenLine("23 Oct", null),
                "a trail has no key to place");
        assertEquals("&aGave Kid the Lime Cap for 2 days. &7Kid reads: \"" + line + "\"",
                LooksCommand.givenConfirmation("Kid", "Lime Cap", 2, line, false), "[X] the owner's line says it too");
        assertEquals("&aGave Kid the Lime Cap.", LooksCommand.givenConfirmation("Kid", "Lime Cap", null, null, false));
    }

    /**
     * Red-team finding CMD-3: {@code [days]} on a Keeper was parsed, ignored, and repeated back ("for 2 days"), while
     * the Keeper was given for good and nothing can end one. It is refused now, and a Keeper's give says "for good".
     */
    @Test
    void aKeeperTakesNoDays() {
        assertEquals("&cA Keeper is kept for good - no days. /hcm looks preview keeper_wizard_hat shows one without"
                + " giving it.", LooksCommand.keeperNoDays("keeper_wizard_hat"));
        assertEquals("&aGave Helper the Wizard Hat for good. &7Helper reads: \"It's on your head!\"",
                LooksCommand.givenConfirmation("Helper", "Wizard Hat", null, "It's on your head!", true));
        String give = body(source("LooksCommand.java"), "private void give(CommandSender sender, String[] args) {");
        assertTrue(give.indexOf("if (p.keeper()) { sender.sendMessage(Text.of(keeperNoDays(p.id()))); return; }")
                < give.indexOf("days = giveDays(args[4]);"), "refused before the days are read, so nothing is given");
        assertTrue(give.indexOf("plugin.looks().give(") > give.indexOf("keeperNoDays"));
    }

    /**
     * Red-team finding CMD-6: {@code /hcm looks give} from the console played the present's moment, "CONSOLE gave you
     * the Lime Cap". From the console it is a plain give, as {@code /hcm give prize} from the console is; and
     * {@code renew} and {@code gift}, which open a screen, now answer the console instead of saying nothing.
     */
    @Test
    void fromTheConsoleAGiveIsNoPresent() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID her = UUID.randomUUID();
        assertFalse(com.dierks.homecraft.arcade.looks.LookRules.givenAsPresent(null, her), "the console");
        assertFalse(com.dierks.homecraft.arcade.looks.LookRules.givenAsPresent(her, her), "to yourself");
        assertTrue(com.dierks.homecraft.arcade.looks.LookRules.givenAsPresent(owner, her), "from the owner to her");
        assertEquals("&a✔ &fLime Cap &7- It's yours until 23 Oct! It's on your head!",
                com.dierks.homecraft.arcade.looks.LookRules.plainGivenLine("&fLime Cap",
                        "It's yours until 23 Oct! It's on your head!"));
        String service = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/dierks/homecraft/arcade/looks/LookService.java")).replaceAll("\\s+", " ");
        String give = body(service, "java.util.function.Consumer<String> told) {");
        assertTrue(give.contains("boolean present = LookRules.givenAsPresent(adminId, to);"), give);
        assertFalse(give.contains("if (!to.equals(adminId)) {"), "the moment follows givenAsPresent");
        assertEquals(2, give.split("if \\(present\\) \\{ plugin.prizes\\(\\).presentMoment", -1).length - 1,
                "a Keeper's and a rental's moment");
        String looks = body(source("LooksCommand.java"), "void looks(CommandSender sender, String[] args) {");
        assertTrue(looks.contains("} else { sender.sendMessage(Text.of(ONLY_PLAYERS_OPEN)); }"), "renew");
        assertTrue(body(source("LooksCommand.java"), "private void gift(CommandSender sender, String id, String name) {")
                .startsWith(" if (!(sender instanceof Player giver)) { sender.sendMessage(Text.of(ONLY_PLAYERS_OPEN));"
                        + " return; }"), "gift");
        assertEquals("&cOnly players can open that.", LooksCommand.ONLY_PLAYERS_OPEN);
    }

    /**
     * Red-team finding CMD-2: the dry run said "trade them over first", but a trade hands her Minis to the new account
     * and the move then refuses. Items go over after the move, before linking (README "Her own Java account, later").
     */
    @Test
    void theDryRunSaysToTradeAfterTheMove() {
        assertEquals("&7Items stay with the old account: after the move, trade them over in the trade window, before "
                + "linking. Run it again with &fconfirm &7to move.", LooksCommand.DRY_RUN_LAST);
        assertFalse(LooksCommand.DRY_RUN_LAST.contains("first"));
    }

    private static String source(String file) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/dierks/homecraft/command",
                    file)).replaceAll("\\s+", " ");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** The text of a method from its head to its closing brace, whitespace already one space. */
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

    @Test
    void eachSubCommandAndWhoMayRunIt() {
        assertEquals(Sub.OPEN, LooksCommand.sub(args("looks")), "/hcm looks: My Looks");
        assertEquals(Sub.RENEW, LooksCommand.sub(args("looks renew cap_lime")), "the [Buy again] link");
        assertEquals(Sub.GIFT, LooksCommand.sub(args("looks gift cap_lime Kid")), "the [Give it again] link");
        assertEquals(Sub.LIST, LooksCommand.sub(args("looks list Kid")));
        assertEquals(Sub.GIVE, LooksCommand.sub(args("looks give Kid cap_lime 2")));
        assertEquals(Sub.GIVE, LooksCommand.sub(args("looks give Kid cap_lime")), "the row's days");
        assertEquals(Sub.END, LooksCommand.sub(args("looks end Kid cap_lime 0.5")));
        assertEquals(Sub.END, LooksCommand.sub(args("looks end Kid cap_lime")), "now");
        assertEquals(Sub.PREVIEW, LooksCommand.sub(args("looks preview keeper_wizard_hat")));
        assertEquals(Sub.USAGE, LooksCommand.sub(args("looks gift cap_lime")), "a present needs a person");
        assertEquals(Sub.USAGE, LooksCommand.sub(args("looks give Kid")));
        assertEquals(Sub.USAGE, LooksCommand.sub(args("looks buy cap_lime")), "nothing is bought from chat");
        assertFalse(LooksCommand.admin(Sub.OPEN));
        assertFalse(LooksCommand.admin(Sub.RENEW));
        assertFalse(LooksCommand.admin(Sub.GIFT), "a present is the giver's own tap on the confirm");
        assertTrue(LooksCommand.admin(Sub.LIST));
        assertTrue(LooksCommand.admin(Sub.GIVE));
        assertTrue(LooksCommand.admin(Sub.END));
        assertTrue(LooksCommand.admin(Sub.PREVIEW));
    }

    @Test
    void theDaysEachTakes() {
        assertEquals(5.0, LooksCommand.endDays("5"));
        assertEquals(0.5, LooksCommand.endDays("0.5"), "half a day: the 1-day heads-up");
        assertEquals(0.0, LooksCommand.endDays("0"));
        assertNull(LooksCommand.endDays("-1"));
        assertNull(LooksCommand.endDays("soon"));
        assertNull(LooksCommand.endDays("NaN"));
        assertEquals(2, LooksCommand.giveDays("2"), "a free test look for two days (§11)");
        assertNull(LooksCommand.giveDays("0"));
        assertNull(LooksCommand.giveDays("1.5"), "whole days: a look ends at a local midnight");
        assertNull(LooksCommand.giveDays("400"));
        assertEquals(UUID.fromString("0b0a0c0d-1e1f-4a2b-8c3d-4e5f6a7b8c9d"),
                LooksCommand.uuidOf("0b0a0c0d-1e1f-4a2b-8c3d-4e5f6a7b8c9d"), "an account named by its UUID");
        assertNull(LooksCommand.uuidOf("Kid"));
    }

    @Test
    void theListLine() {
        ZoneId zone = ZoneId.of("America/Chicago");
        long now = ZonedDateTime.of(2026, 11, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli();
        long jan19 = ZonedDateTime.of(2027, 1, 19, 0, 0, 0, 0, zone).toInstant().toEpochMilli();
        Look live = new Look("cap_lime", LookDao.HAT, jan19, now - 1, false, jan19, 7, "someone");
        assertEquals("&fcap_lime &7hat &auntil 18 Jan &8- last heads-up: 7-day &8- a present",
                LookService.listLine(live, now, zone));
        Look ended = new Look("trail_snow", LookDao.TRAIL, now - 1, 1, true, now - 1, -1, "");
        assertEquals("&ftrail_snow &7trail &7ended 1 Nov &d(on) &8- last heads-up: ended",
                LookService.listLine(ended, now, zone));
        Look kept = new Look("trail_leaves_2026", LookDao.TRAIL, now + 36_500L * 86_400_000L, 1, false, 0, 0, "");
        assertTrue(LookService.listLine(kept, now, zone).contains("for good"));
        Look stale = new Look("cap_lime", LookDao.HAT, jan19, 1, false, jan19 - 5, 1, "");
        assertTrue(LookService.listLine(stale, now, zone).contains("(for an older end)"));
    }
}
