package com.dierks.homecraft.command;

import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /hcm give prize <id> [player]} (HALLOWEEN-SPEC D19): its words, when it is a present, what
 * it won't give back, and that the help, the tab list and plugin.yml all know it.
 */
class GivePrizeCommandTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DAUGHTER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void theIdIsTheThirdWordAndThePlayerTheFourth() {
        assertEquals("cap_pumpkin_2026", HcmCommand.givePrizeId(new String[] {"give", "prize", "cap_pumpkin_2026"}));
        assertEquals("cap_pumpkin_2026",
                HcmCommand.givePrizeId(new String[] {"give", "prize", "cap_pumpkin_2026", "Someone"}));
        assertEquals("Trail_Leaves_2026", HcmCommand.givePrizeId(new String[] {"give", "prize", " Trail_Leaves_2026 "}),
                "trimmed; the lookup is case-insensitive, so the case is kept");
        assertEquals(3, HcmCommand.GIVE_PRIZE_PLAYER, "the player is the word after the id");
    }

    @Test
    void noIdMeansTheUsage() {
        assertNull(HcmCommand.givePrizeId(new String[] {"give", "prize"}));
        assertNull(HcmCommand.givePrizeId(new String[] {"give", "prize", " "}));
        assertNull(HcmCommand.givePrizeId(null));
    }

    @Test
    void givenByAPlayerToSomeoneElseItIsAPresent() {
        assertEquals("Owner", HcmCommand.presentFrom("Owner", OWNER, DAUGHTER),
                "\"A gift from\" on the cap and the whole moment (kid-bedrock #13)");
        assertNull(HcmCommand.presentFrom("Owner", OWNER, OWNER), "to yourself: a plain restore");
        assertNull(HcmCommand.presentFrom("CONSOLE", null, DAUGHTER), "from the console: a plain restore");
        assertNull(HcmCommand.presentFrom(" ", OWNER, DAUGHTER), "no name to show: a plain restore");
    }

    @Test
    void aPlusOneHomeIsNotGivenBackAsAnItem() {
        assertNotNull(HcmCommand.notGivenBack(PrizeType.HOME_SLOT), "a perk its purchase records");
        assertTrue(HcmCommand.notGivenBack(PrizeType.HOME_SLOT).contains("/hcm homes refresh"));
        for (PrizeType t : new PrizeType[] {PrizeType.HAT, PrizeType.TRAIL, PrizeType.BOOST, PrizeType.TROPHY,
                PrizeType.FIREWORK, PrizeType.COMMAND}) {
            assertNull(HcmCommand.notGivenBack(t), t + " is given back");
        }
    }

    @Test
    void theHelpTheTabListAndPluginYmlKnowIt() throws IOException {
        String cmd = Files.readString(Path.of("src", "main", "java", "com", "dierks", "homecraft", "command",
                "HcmCommand.java"), StandardCharsets.UTF_8);
        assertTrue(cmd.contains("\"&e/hcm give prize <id> [player] &7- give back a lost prize (free)\""), "the help");
        assertTrue(cmd.contains("\"pack\", \"prize\", \"vending\""), "the tab list after /hcm give");
        String yml = Files.readString(Path.of("src", "main", "resources", "plugin.yml"), StandardCharsets.UTF_8);
        assertTrue(yml.contains("\n      /hcm give prize <id> [player]  - admin: give back a lost prize (free)\n"),
                "plugin.yml's usage");
    }
}
