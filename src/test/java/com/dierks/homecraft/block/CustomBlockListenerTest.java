package com.dierks.homecraft.block;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Which of our blocks still open on a tap that arrived already cancelled (vanilla spawn protection or a
 * protection plugin's "use" rule, both of which spare ops): the Arcade family, and nothing else; never for a
 * player in a world game, whose session's guard says no to every tap and screen (review ARC-1); and the one
 * console line such a tap gets names no cause it can't know.
 */
class CustomBlockListenerTest {

    @Test
    void onlyTheArcadeFamilyOpensOnATapThatArrivedCancelled() {
        Set<CustomBlockType> family = EnumSet.of(CustomBlockType.ARCADE, CustomBlockType.CRATE_MACHINE,
                CustomBlockType.SCRATCH_BOOTH, CustomBlockType.PITY_KIOSK, CustomBlockType.TOKEN_COUNTER);
        for (CustomBlockType type : CustomBlockType.values()) {
            assertEquals(family.contains(type), CustomBlockListener.opensWhenCancelled(type),
                    type + (family.contains(type) ? " opens anyway: it only opens a screen"
                            : " keeps the old rule: a cancelled tap does nothing"));
            assertEquals(family.contains(type), CustomBlockListener.opensWhenCancelled(type, false),
                    type + ": out of a world game, the same");
            assertFalse(CustomBlockListener.opensWhenCancelled(type, true),
                    type + ": in a world game a cancelled tap is dropped silently, as it always was");
        }
    }

    @Test
    void theConsoleLineNamesNoCauseItCanNotKnow() {
        String line = CustomBlockListener.cancelledTapLine("Ava", CustomBlockType.ARCADE, 12, 64, -3, "world");
        assertEquals("Arcade: Ava's tap on arcade at 12 64 -3 in world arrived cancelled (by the server or another "
                + "plugin); opened it", line);
        assertFalse(line.contains("spawn protection"), "spawn protection, a plugin, a spectator, a cooldown: unknown");
    }
}
