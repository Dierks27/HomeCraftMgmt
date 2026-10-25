package com.dierks.homecraft.arcade;

import com.dierks.homecraft.block.CustomBlockType;
import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeExtra;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Using a Mega Firework Show (0.38.1): every refusal comes, in order, before anything is held, claimed or used up (in a
 * game, the long show still on, no launcher near enough, the launchers busy, the sky full), so the item stays in the
 * hand; a tap on another HomeCraft block is that block's and keeps the show, while a tap on a launcher starts it; and,
 * in the source (a listener can't run without a server): the Mega branch comes before 0.38.1's long-show path, the item
 * is used up only after the show started, a chunk is never loaded to find or fire from a launcher, a quit ends the
 * user's shows and the plugin's stop ends them all.
 */
class MegaShowRulesTest {

    private static Prize firework(String id, int seconds, int launchers) {
        return new Prize(id, PrizeTab.COSMETICS, "&dShow", List.of(), 75, new Icon("", Material.FIREWORK_ROCKET),
                PrizeType.FIREWORK, true, null, 1, null, null, null, null, 0, 0, null, 0, "", List.of(), List.of(),
                null, null, List.of(), null, new PrizeExtra(null, 0, null, OnlyIf.ANY, seconds, null, launchers));
    }

    @Test
    void theRefusalsComeInOrderAndNothingIsUsedUp() {
        assertNull(MegaShow.refusal(true, false, 3, false, 0), "home, no long show, three free launchers: it starts");
        assertNull(MegaShow.refusal(true, false, 1, false, 3), "one launcher is enough, and three shows on is fine");
        assertEquals("Save your show for after the games!", MegaShow.refusal(false, true, 0, true, 4),
                "in a game or a Games world, first of all (0.38.1's line)");
        assertEquals("Wait for your show to finish!", MegaShow.refusal(true, true, 0, true, 4),
                "then the player's long show (a Grand show, or a Mega one) still on (0.38.1's guard and line)");
        assertEquals("Set up a Firework Launcher nearby first!", MegaShow.refusal(true, false, 0, true, 4),
                "then no launcher near enough: refused kindly, and kept");
        assertEquals("Those launchers are in a show right now. Try again when it ends!",
                MegaShow.refusal(true, false, 3, true, 4), "then one of the nearest is in a show");
        assertEquals("The sky is full of shows! Try again in a minute.", MegaShow.refusal(true, false, 3, false, 4),
                "then four shows on already");
        assertEquals(4, MegaShow.MAX_RUNNING);
        for (String line : List.of(MegaShow.NO_LAUNCHER, MegaShow.BUSY, MegaShow.SKY_FULL, MegaShow.CANT_START)) {
            assertTrue(line.length() <= 70, "short enough for a Bedrock chat line: " + line);
        }
        assertEquals("&d✦ Mega Firework Show! &7Look at the launchers!", MegaShow.startLine(3));
        assertEquals("&d✦ Mega Firework Show! &7Look at the launcher!", MegaShow.startLine(1));
        assertEquals("&d✦ Someone started a Mega Firework Show!", MegaShow.announceLine("Someone"));
    }

    @Test
    void aTapOnAnotherHomeCraftBlockKeepsTheShowButOnALauncherStartsIt() {
        assertTrue(MegaShow.usesOnTap(false, false, false), "a tap in the air (Java): it starts");
        assertTrue(MegaShow.usesOnTap(true, false, false), "a tap on a plain block (Bedrock's every tap): it starts");
        assertTrue(MegaShow.usesOnTap(true, true, true), "a tap on a Firework Launcher: it starts");
        assertTrue(MegaShow.usesOnTap(true, false, true), "(a launcher is asked first, so ours isn't looked up)");
        assertFalse(MegaShow.usesOnTap(true, true, false),
                "a tap on the Arcade Machine, a join sign, a Mini: that block's, and the show is kept");
    }

    /**
     * Review MEGA-RT-1 / LAUNCHER-NONTILE-2: CustomBlockListener leaves a Mega show's tap on a launcher to the show by
     * the placed-block record, so the show knows a launcher by its tile's tag or else that record. A launcher with no
     * tag (made of a block with no tile) starts the show; before, nobody answered the tap at all.
     */
    @Test
    void aLauncherIsKnownByItsTagOrElseItsRecordAsTheBlockListenerKnowsIt() throws IOException {
        assertTrue(MegaShow.onLauncher(true, null), "its tile's tag, with no record read");
        assertTrue(MegaShow.onLauncher(false, CustomBlockType.FIREWORK_LAUNCHER),
                "no tag: its record says it is one, as CustomBlockListener reads it");
        assertFalse(MegaShow.onLauncher(false, CustomBlockType.ARCADE), "another of ours is not");
        assertFalse(MegaShow.onLauncher(false, null), "nor a plain block");
        assertTrue(MegaShow.usesOnTap(true, true, MegaShow.onLauncher(false, CustomBlockType.FIREWORK_LAUNCHER)),
                "so the tap CustomBlockListener leaves to the show starts it");

        String listener = source("arcade", "PrizeItemListener.java");
        String body = method(listener, "private void megaShow(");
        assertTrue(body.contains("boolean launcher = on != null && launcher(plugin, on);"), body);
        String launcher = method(listener, "private static boolean launcher(");
        int tag = launcher.indexOf("CustomBlockService.isLauncher(on.getState(false))");
        int record = launcher.indexOf("blocks.at(on.getLocation())");
        assertTrue(tag > 0 && record > tag, "the tag first (no database), the record only without it: " + launcher);
        assertTrue(launcher.contains("MegaShow.onLauncher(false,"), launcher);
        assertTrue(launcher.contains("catch (RuntimeException | LinkageError e) { return false; }"),
                "a lookup that fails is no");
        String blocks = source("block", "CustomBlockListener.java");
        assertTrue(blocks.contains("Optional<PlacedBlock> placed = blocks.at(clicked.getLocation());")
                        && blocks.contains("if (type == CustomBlockType.FIREWORK_LAUNCHER && MegaShow.isMega("),
                "the block listener's side: the record's type");
    }

    @Test
    void aRowIsAMegaShowByItsLaunchers() {
        assertTrue(MegaShow.isMega(firework("firework_mega", 60, 3)));
        assertTrue(MegaShow.isMega(firework("x", 60, 1)), "one launcher is a Mega show too");
        assertFalse(MegaShow.isMega(firework("firework_grand", 15, 0)), "the Grand show is played around her");
        assertFalse(MegaShow.isMega(firework("firework_show", 3, 0)), "and so is the Firework Show");
        assertFalse(MegaShow.isMega(null), "a row that's gone: 0.38.1's short show");
        assertEquals(60, MegaShow.seconds(firework("firework_mega", 60, 3)));
        assertEquals(90, MegaShow.seconds(firework("x", 200, 3)));
        assertEquals(15, FireworkShow.seconds(firework("firework_grand", 15, 0)), "0.38.1's lengths as they were");
        assertEquals(3, FireworkShow.seconds(firework("firework_show", 3, 0)));
        assertEquals("firework_launcher", MegaShow.LAUNCHER_PRIZE_ID, "the launcher's tag is its row's id");
    }

    @Test
    void theMegaBranchComesFirstAndTheItemIsUsedOnlyOnceItStarted() throws IOException {
        String use = between(source("arcade", "PrizeItemListener.java"), "case FIREWORK -> {", "case HAT -> {");
        int mega = use.indexOf("if (MegaShow.isMega(row)) { megaShow(event, player, hand, row); return; }");
        assertTrue(mega >= 0, "the Mega branch: " + use);
        assertTrue(mega < use.indexOf("FireworkShow.isLong(seconds)"), "before 0.38.1's long-show path");
        assertTrue(mega < use.indexOf("shows.start(player, seconds)"), "so a Mega row never plays around the player");

        String body = method(source("arcade", "PrizeItemListener.java"), "private void megaShow(");
        int tap = body.indexOf("if (!MegaShow.usesOnTap(");
        int cancel = body.indexOf("cancel(event);");
        int start = body.indexOf("shows.start(player, row)");
        int consume = body.indexOf("consume(player, hand);");
        assertTrue(tap >= 0 && tap < cancel, "a tap on another of ours returns before anything is cancelled: " + body);
        assertTrue(cancel < start && start < consume, "the item is used up only after the show started");
        assertTrue(body.indexOf("if (refused == null) { consume(player, hand); return; }") > start,
                "and only when it did");
        assertTrue(body.contains("refusalDue(player.getUniqueId())"), "a refusal is said at most once every 2 s");
        int launcher = body.indexOf("launcher(plugin, on)");
        assertTrue(launcher > 0 && launcher < body.indexOf("ours(plugin, on)"),
                "a launcher is known (its tag, else its record) before ours is looked up");

        String service = method(source("arcade", "MegaShowService.java"), "public String start(");
        int refusal = service.indexOf("MegaShow.refusal(");
        int hold = service.indexOf("shows.holdLong(id)");
        int claim = service.indexOf("claims.tryClaim(id, found)");
        assertTrue(refusal > 0 && refusal < hold && hold < claim, "every refusal before the hold and the claim");
        assertTrue(service.indexOf("shows.releaseLong(id, held);", claim) > claim,
                "a claim that fails gives the long show back");
        assertTrue(service.indexOf("runTaskTimer(") > claim, "the timer only once both are held");
        assertTrue(service.contains("plugin.prizes().announce(player, MegaShow.announceLine(player.getName()))"),
                "one server-wide line, at most one of hers a minute");
    }

    @Test
    void aChunkIsNeverLoadedToFindOrFireFromALauncher() throws IOException {
        String src = source("arcade", "MegaShowService.java");
        String near = method(src, "private List<Spot> near(");
        int loaded = near.indexOf("!w.isChunkLoaded(pb.x() >> 4, pb.z() >> 4)");
        assertTrue(loaded > 0, near);
        assertTrue(loaded < near.indexOf("getChunkAt(") && loaded < near.indexOf("getBlockAt("),
                "isChunkLoaded is asked before the chunk or the block");
        assertTrue(near.indexOf("Chunk.LoadLevel.ENTITY_TICKING") < near.indexOf("getBlockAt("),
                "and only a ticking chunk's launcher counts (a rocket elsewhere would hang frozen)");
        assertTrue(near.contains("if (b.getType().isAir()) { blocks.removeAt(b.getLocation()); continue; }"),
                "a stale record (air) is forgotten");
        String ticking = method(src, "private static boolean ticking(");
        assertTrue(ticking.indexOf("isChunkLoaded(") < ticking.indexOf("getChunkAt("), ticking);
        String fire = method(src, "private static void fire(");
        assertTrue(fire.indexOf("!ticking(w, s)") < fire.indexOf("getBlockAt("), "each rocket asks the same first");
        String timer = between(src, "runTaskTimer(plugin, () -> {", "}, 0L, 1L);");
        assertTrue(timer.indexOf("if (w == null || !anyTicking(w, ordered)) { end(show); return; }")
                < timer.indexOf("fire("), "an unloaded world or area ends the show before it fires: " + timer);
        assertFalse(timer.contains("findByType") || timer.contains("blockService"), "no database in the loop");
        assertFalse(timer.contains("getPlayer") || timer.contains("isOnline"),
                "walking away (or anything the user does) never ends it");
    }

    @Test
    void aQuitEndsTheUsersShowsAndTheStopEndsThemAll() throws Exception {
        EventHandler quit = MegaShowService.class.getMethod("onQuit", PlayerQuitEvent.class)
                .getAnnotation(EventHandler.class);
        assertNotNull(quit, "onQuit is a handler");
        assertEquals(EventPriority.MONITOR, quit.priority());
        String main = source("", "HomeCraftManagement.java");
        assertTrue(main.contains("registerEvents(megaShows, this);"), "it listens");
        String disable = method(main, "public void onDisable(");
        assertTrue(disable.contains("if (megaShows != null) { megaShows.stop();"), "the plugin's stop ends every show");
        assertTrue(disable.indexOf("megaShows.stop()") < disable.indexOf("database.close()"),
                "before the database closes");
        assertTrue(main.contains("this.megaShows = new com.dierks.homecraft.arcade.MegaShowService(this);"));
    }

    /** {@code src} from {@code from} to {@code to}. */
    private static String between(String src, String from, String to) {
        int at = src.indexOf(from);
        assertTrue(at >= 0, "fixture: " + from);
        int end = src.indexOf(to, at + from.length());
        assertTrue(end >= 0, "fixture: " + to);
        return src.substring(at + from.length(), end);
    }

    /** A method's body, from its signature to its closing brace. */
    private static String method(String src, String signature) {
        int at = src.indexOf(signature);
        assertTrue(at >= 0, "fixture: " + signature);
        int open = src.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return src.substring(open, i + 1);
            }
        }
        throw new AssertionError("fixture: the end of " + signature);
    }

    /** A source file, its line comments gone and its whitespace one space (as {@code FireworkShowTest}). */
    private static String source(String pkg, String file) throws IOException {
        Path dir = Path.of("src/main/java/com/dierks/homecraft");
        return Files.readString(pkg.isEmpty() ? dir.resolve(file) : dir.resolve(pkg).resolve(file),
                StandardCharsets.UTF_8).replaceAll("//[^\\n]*", "").replaceAll("\\s+", " ");
    }
}
