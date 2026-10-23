package com.dierks.homecraft.block;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * A placed Arcade Trophy taps as the Arcade Machine (SHOP-V2-SPEC §5.8): its type is ARCADE, so a tap that arrived
     * cancelled opens it by the machine's own rule, and never in a world game; a sneak passes through and a player
     * without hcm.arcade.use is refused; its console line says "(trophy)".
     */
    @Test
    void theTrophyTapsAsTheMachineDoes() {
        assertTrue(CustomBlockListener.opensWhenCancelled(CustomBlockType.ARCADE, false),
                "the trophy's type is the machine's: the same cancelled-tap rule");
        assertFalse(CustomBlockListener.opensWhenCancelled(CustomBlockType.ARCADE, true), "never in a world game");
        assertEquals(com.dierks.homecraft.arcade.ArcadeTrophies.Tap.PASS,
                com.dierks.homecraft.arcade.ArcadeTrophies.tap(true, true), "a sneak passes the tap through");
        assertEquals(com.dierks.homecraft.arcade.ArcadeTrophies.Tap.REFUSE,
                com.dierks.homecraft.arcade.ArcadeTrophies.tap(false, false), "no hcm.arcade.use: refused");
        assertEquals(com.dierks.homecraft.arcade.ArcadeTrophies.Tap.OPEN,
                com.dierks.homecraft.arcade.ArcadeTrophies.tap(false, true));
        assertEquals("Arcade: Ava's tap on arcade (trophy) at 1 2 3 in world arrived cancelled (by the server or "
                        + "another plugin); opened it",
                CustomBlockListener.cancelledTapLine("Ava", CustomBlockType.ARCADE, 1, 2, 3, "world", true));
    }

    /**
     * The trophy path in the source (a listener can't run without a server): the record is read only for one of our
     * blocks, a trophy becomes ARCADE before the cancelled-tap check, a sneak returns before anything is cancelled, and
     * the permission is asked after the economy-free checks it shares with the machine.
     */
    @Test
    void theTrophyPathRunsOnTheMachinesLines() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src", "main", "java", "com", "dierks",
                "homecraft", "block", "CustomBlockListener.java")).replaceAll("\\s+", " ");
        int trophy = src.indexOf("if (!ArcadeTrophies.isPlacedArcadeTrophy(clicked)");
        int arcade = src.indexOf("type = CustomBlockType.ARCADE;");
        int cancelled = src.indexOf("if (arrivedCancelled && !opensWhenCancelled(type, inGameSession(event.getPlayer())))");
        int deny = src.indexOf("event.setUseInteractedBlock(Event.Result.DENY);", cancelled);
        int perm = src.indexOf("player.hasPermission(\"hcm.arcade.use\")");
        int sandbox = src.indexOf("plugin.sandbox().check(player, \"use \"", perm);
        assertTrue(trophy > 0 && arcade > trophy && cancelled > arcade && deny > cancelled && perm > deny
                && sandbox > perm, "trophy -> ARCADE -> the cancelled-tap rule -> ours -> the permission -> economy");
        assertTrue(src.contains("ArcadeTrophies.tap(event.getPlayer().isSneaking(), true) == ArcadeTrophies.Tap.PASS) "
                + "{ return; }"), "a sneak returns before the tap is cancelled");
    }

    @Test
    void theConsoleLineNamesNoCauseItCanNotKnow() {
        String line = CustomBlockListener.cancelledTapLine("Ava", CustomBlockType.ARCADE, 12, 64, -3, "world");
        assertEquals("Arcade: Ava's tap on arcade at 12 64 -3 in world arrived cancelled (by the server or another "
                + "plugin); opened it", line);
        assertFalse(line.contains("spawn protection"), "spawn protection, a plugin, a spectator, a cooldown: unknown");
    }
}
