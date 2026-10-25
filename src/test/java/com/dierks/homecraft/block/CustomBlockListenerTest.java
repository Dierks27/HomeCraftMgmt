package com.dierks.homecraft.block;

import io.papermc.paper.event.block.BlockFailedDispenseEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which of our blocks still open on a tap that arrived already cancelled (vanilla spawn protection or a
 * protection plugin's "use" rule, both of which spare ops): the Arcade family, and nothing else; never for a
 * player in a world game, whose session's guard says no to every tap and screen (review ARC-1); and the one
 * console line such a tap gets names no cause it can't know. And the Firework Launcher's place and guards (0.38.1):
 * the cap is counted after the town check and before the record, and no hopper, dispense, redstone pulse or screen
 * ever acts on a launcher.
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

    /**
     * The launcher's cap in {@code onPlace} (a listener can't run without a server): after the economy and town checks
     * (a place refused there never counts), before the record is written (a refused fourth writes nothing), and the
     * facing before the record, so its tag is written last.
     */
    @Test
    void theLauncherCapIsCountedAfterTheTownCheckAndBeforeTheRecord() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src", "main", "java", "com", "dierks",
                "homecraft", "block", "CustomBlockListener.java")).replaceAll("\\s+", " ");
        int place = src.indexOf("public void onPlace(BlockPlaceEvent event) {");
        int town = src.indexOf("player.sendMessage(Text.of(\"&cYou can't build here.\"));", place);
        int cap = src.indexOf("if (LauncherRules.placeRefused(launchers, perPlayer)) {", place);
        int face = src.indexOf("blocks.faceUp(block);", place);
        int record = src.indexOf("blocks.recordPlacement(block, type, player.getUniqueId());", place);
        assertTrue(place > 0 && town > place && cap > town && face > cap && record > face,
                "town check < launcher cap < facing up < the record");
        String refused = src.substring(cap, face);
        assertTrue(refused.contains("event.setCancelled(true);")
                && refused.contains("LauncherRules.fullLine(perPlayer)")
                && refused.contains("Sounds.refused(player);") && refused.contains("return;"),
                "a fourth: cancelled (the item stays in the hand), one kind line and the refused sound");
        // review LAUNCHER-SPAM-3: a held fourth place says its line and plays its sound once every 2 s
        int due = refused.indexOf("if (launcherLines.due(me, full, System.currentTimeMillis())) {");
        assertTrue(due > refused.indexOf("event.setCancelled(true);")
                        && due < refused.indexOf("player.sendMessage(Text.of(full));")
                        && due < refused.indexOf("Sounds.refused(player);"),
                "cancelled every time, the line and the sound only when due: " + refused);
        assertTrue(src.contains("pb -> pb.owner().equals(me), pb -> sameSpot(pb, block), this::staleLauncher"),
                "hers, not this spot, not a stale record");
        // a Mega show in the hand: the tap is the show's, before the economy and build checks speak
        int deny = src.indexOf("event.setUseInteractedBlock(Event.Result.DENY);");
        int mega = src.indexOf("if (type == CustomBlockType.FIREWORK_LAUNCHER && MegaShow.isMega(");
        int sandbox = src.indexOf("plugin.sandbox().check(player, \"use \"");
        assertTrue(deny > 0 && mega > deny && sandbox > mega, "deny < the Mega show's tap < the economy check");
        int tap = src.indexOf("case FIREWORK_LAUNCHER -> {", mega);
        assertTrue(tap > 0, "its own tap line");
        String tapCase = src.substring(tap, src.indexOf("case SOUND_MUFFLER -> {", tap));
        int quiet = tapCase.indexOf("if (!launcherLines.due(player.getUniqueId(), new MegaShow.Spot(");
        assertTrue(quiet > 0 && quiet < tapCase.indexOf("UUID owner = placed.get().owner();")
                        && quiet < tapCase.indexOf("ownedLaunchers(owner)")
                        && quiet < tapCase.indexOf("LauncherRules.tapLine("),
                "review LAUNCHER-SPAM-3: a held tap's repeat says nothing and reads no records: " + tapCase);
    }

    /** The launcher lines a player was last told go when they quit. */
    @Test
    void aQuitForgetsTheLauncherLines() throws Exception {
        EventHandler h = CustomBlockListener.class.getMethod("onQuit", org.bukkit.event.player.PlayerQuitEvent.class)
                .getAnnotation(EventHandler.class);
        assertNotNull(h, "onQuit is a handler");
        assertEquals(EventPriority.MONITOR, h.priority());
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src", "main", "java", "com", "dierks",
                "homecraft", "block", "CustomBlockListener.java")).replaceAll("\\s+", " ");
        assertTrue(src.contains("launcherLines.forget(event.getPlayer().getUniqueId());"));
    }

    /** The four launcher guards: each early enough, each skipping what someone already said no to. */
    @Test
    void theLauncherGuardsRunEarly() throws Exception {
        Object[][] guards = {{"onLauncherFeed", InventoryMoveItemEvent.class, EventPriority.LOW, true},
                {"onLauncherDispense", BlockDispenseEvent.class, EventPriority.LOW, true},
                {"onLauncherClick", BlockFailedDispenseEvent.class, EventPriority.LOW, false},
                {"onLauncherOpen", InventoryOpenEvent.class, EventPriority.HIGH, true}};
        for (Object[] g : guards) {
            EventHandler h = CustomBlockListener.class.getMethod((String) g[0], (Class<?>) g[1])
                    .getAnnotation(EventHandler.class);
            assertNotNull(h, g[0] + " is a handler");
            assertEquals(g[2], h.priority(), (String) g[0]);
            assertEquals(g[3], h.ignoreCancelled(), g[0] + " (a failed dispense can't be cancelled)");
        }
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src", "main", "java", "com", "dierks",
                "homecraft", "block", "CustomBlockListener.java")).replaceAll("\\s+", " ");
        assertTrue(src.contains("return inv != null && (inv.getType() == InventoryType.DROPPER || inv.getType() =="
                + " InventoryType.DISPENSER) && inv.getHolder(false) instanceof BlockState state"
                + " && CustomBlockService.isLauncher(state);"),
                "a hopper's move costs a type compare, then the tile's own tag: never the database");
        assertTrue(src.contains("return m == Material.DROPPER || m == Material.DISPENSER;"),
                "a dispenser launcher (arcade.launcher.material) is guarded the same");
        assertTrue(src.contains("event.shouldPlayEffect(false);"), "a redstone pulse makes no click or smoke");
    }

    @Test
    void theConsoleLineNamesNoCauseItCanNotKnow() {
        String line = CustomBlockListener.cancelledTapLine("Ava", CustomBlockType.ARCADE, 12, 64, -3, "world");
        assertEquals("Arcade: Ava's tap on arcade at 12 64 -3 in world arrived cancelled (by the server or another "
                + "plugin); opened it", line);
        assertFalse(line.contains("spawn protection"), "spawn protection, a plugin, a spectator, a cooldown: unknown");
    }
}
