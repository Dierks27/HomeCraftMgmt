package com.dierks.homecraft.block;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which of our blocks still open on a tap that arrived already cancelled (vanilla spawn protection or a
 * protection plugin's "use" rule, both of which spare ops): the Arcade family, and nothing else.
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
        }
    }
}
