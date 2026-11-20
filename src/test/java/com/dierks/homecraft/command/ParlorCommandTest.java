package com.dierks.homecraft.command;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /hcm games parlor pot cap <dollars|off>}: the pot machines' one rule that is set by command. Pinned
 * here: the verb is offered, with its help line and its Tab words, and plugin.yml's usage lists it; the rules that
 * were there before are still offered as they were.
 */
class ParlorCommandTest {

    @Test
    void potIsAVerbWithItsHelpLineAndItsTabWords() {
        assertEquals(List.of("only", "money", "limit", "stake", "payout", "chips", "ticket", "pot"),
                ParlorCommand.VERBS, "the old verbs in their order, then pot");
        assertTrue(ParlorCommand.help().contains("&e/hcm games parlor pot cap <dollars|off> &7- the most a pot"
                + " machine's pot may hold"), ParlorCommand.help().toString());
        assertEquals(ParlorCommand.VERBS.size() + 2, ParlorCommand.help().size(),
                "a line a verb, the status line, and limit's second form");
        assertEquals(ParlorCommand.VERBS, ParlorCommand.tab(new String[]{""}));
        assertEquals(List.of("cap"), ParlorCommand.tab(new String[]{"pot", ""}));
        assertEquals(List.of("off", "500", "1000", "2500"), ParlorCommand.tab(new String[]{"pot", "cap", ""}));
        assertEquals(List.of(), ParlorCommand.tab(new String[]{"pot", "size", ""}), "cap is the only word after pot");
        assertEquals(List.of(), ParlorCommand.tab(new String[]{"pot", "cap", "500", ""}));
        assertEquals(List.of("5", "10", "tokens"), ParlorCommand.tab(new String[]{"ticket", ""}), "as before");
        assertEquals(List.of("default", "off", "100", "250", "500"), ParlorCommand.tab(new String[]{"limit", "Alex", ""}));
    }

    @Test
    void pluginYmlsUsageListsThePotCap() throws Exception {
        String yml = Files.readString(Path.of("src", "main", "resources", "plugin.yml"), StandardCharsets.UTF_8);
        assertTrue(yml.contains("\n      /hcm games parlor [only on|off | money on|off | limit <dollars|off> | limit"
                + " <player> <dollars|off|default> | stake <dollars> | payout <dollars> | chips <a> [b] [c] | ticket"
                + " <dollars|tokens> | pot cap <dollars|off>]  - admin: the Game Parlor's rules (games of chance only"
                + " there; playing for money; the pot machines' cap)\n"), "plugin.yml's /hcm games parlor line");
    }
}
