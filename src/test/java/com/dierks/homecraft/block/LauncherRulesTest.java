package com.dierks.homecraft.block;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Firework Launcher's own rules (0.38.1; the owner: "3 droppers that I have to set up"): a player may have
 * {@code per_player} placed at once (3, admins too), a fourth refused kindly with the item kept; the spot being placed
 * and a stale record don't count; what a placement and a tap say; and the item, as built, is a token prize pointing up.
 */
class LauncherRulesTest {

    /** A placed launcher's record: whose, where, and whether its block is gone. */
    private record Rec(String owner, int x, boolean gone) {
    }

    @Test
    void aFourthIsRefusedKindly() {
        for (int placed = 0; placed < 3; placed++) {
            assertFalse(LauncherRules.placeRefused(placed, 3), placed + " set up: one more is fine");
        }
        assertTrue(LauncherRules.placeRefused(3, 3), "three set up: a fourth is refused");
        assertTrue(LauncherRules.placeRefused(4, 3), "and more than that (per_player lowered later) too");
        assertFalse(LauncherRules.placeRefused(4, 5), "per_player is the owner's: 5 lets a fifth go up");
        assertTrue(LauncherRules.placeRefused(1, 1), "1 means one at a time");
        assertEquals("&7You already have 3 Firework Launchers set up. Pick one up to move it!",
                LauncherRules.fullLine(3));
        assertEquals("&7You already have 1 Firework Launcher set up. Pick one up to move it!",
                LauncherRules.fullLine(1));
    }

    @Test
    void theSpotBeingPlacedAndAnEmptyRecordDoNotCount() {
        List<Rec> records = List.of(new Rec("her", 1, false), new Rec("her", 2, false), new Rec("him", 3, false),
                new Rec("her", 4, true), new Rec("her", 5, false));
        List<Rec> forgotten = new ArrayList<>();
        int n = LauncherRules.counted(records, r -> r.owner().equals("her"), r -> r.x() == 5, r -> {
            if (r.gone()) {
                forgotten.add(r);
            }
            return r.gone();
        });
        assertEquals(2, n, "hers, not the spot being placed (its record is written again), not the stale one");
        assertEquals(List.of(new Rec("her", 4, true)), forgotten, "the stale one is forgotten as it is found");
        assertFalse(LauncherRules.placeRefused(n, 3), "so her third goes up");
        List<Rec> looked = new ArrayList<>();
        LauncherRules.counted(records, r -> r.owner().equals("her"), r -> false, r -> {
            looked.add(r);
            return false;
        });
        assertFalse(looked.stream().anyMatch(r -> r.owner().equals("him")),
                "someone else's launcher is never looked at in the world");
        assertEquals(4, LauncherRules.counted(records, r -> r.owner().equals("her"), r -> false, r -> false),
                "a record that can't be looked at (its chunk not loaded) counts");
        assertEquals(0, LauncherRules.counted(List.<Rec>of(), r -> true, r -> false, r -> false));
    }

    @Test
    void whatATapSays() {
        assertEquals("&6Your Firework Launcher &7(2 of 3 set up). Use a &dMega Firework Show &7near it!",
                LauncherRules.tapLine(true, "Owner", 2, 3, false), "hers");
        assertEquals("&6Owner's Firework Launcher&7. Use a &dMega Firework Show &7near it!",
                LauncherRules.tapLine(false, "Owner", 0, 3, false), "someone else's: anyone may use it");
        assertEquals("&6Owner's Firework Launcher&7. Use a &dMega Firework Show &7near it! &dIt's in a show right now!",
                LauncherRules.tapLine(false, "Owner", 0, 3, true), "busy");
        assertTrue(LauncherRules.tapLine(true, "Owner", 3, 3, true).endsWith("It's in a show right now!"));
        assertEquals("&7Keep the sky above it clear! &8(1 of 3 set up)", LauncherRules.placedLine(1, 3),
                "after \"Placed a Firework Launcher.\"");
        assertEquals("Firework Launcher", LauncherRules.NAME);
        assertEquals("Firework Launcher", CustomBlockGuard.name(CustomBlockType.FIREWORK_LAUNCHER));
    }

    /** The item can't be built without a server, so its source says what it is (CustomItems.fireworkLauncher). */
    @Test
    void theItemIsATokenPrizePointingUp() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/dierks/homecraft/item/CustomItems.java"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        int at = src.indexOf("public ItemStack fireworkLauncher() {");
        assertTrue(at >= 0, "fixture");
        String body = src.substring(at, src.indexOf("public ItemStack arcadeMachine(", at));
        assertTrue(body.contains("config.launcher().block()"), "its look is arcade.launcher's");
        assertTrue(body.contains("config.skin(CustomBlockType.FIREWORK_LAUNCHER)"), "or skins.firework_launcher's");
        assertTrue(body.contains("TokenPrizes.tag(meta, com.dierks.homecraft.arcade.MegaShow.LAUNCHER_PRIZE_ID);"),
                "tagged hcm:token_prize on every one made, so it never becomes money");
        assertTrue(body.contains("d.setFacing(BlockFace.UP); bdm.setBlockData(d);"), "a dropper item places facing up");
        assertTrue(body.indexOf("skin == null || skin.isBlank()") < body.indexOf("bdm.setBlockData"),
                "a head (a skin) has no facing to set");
        assertTrue(src.contains("case FIREWORK_LAUNCHER -> fireworkLauncher();"), "and of() builds it for the drop");
    }
}
