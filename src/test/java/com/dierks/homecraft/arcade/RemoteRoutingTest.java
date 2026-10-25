package com.dierks.homecraft.arcade;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a Firework Remote's tap goes, and the holograms' safety (0.38.2), in the source (a listener can't run without
 * a server), as {@code MegaShowRulesTest} reads it: the block listener steps aside for a Remote on a launcher after the
 * Mega check and before the sandbox and the switch; the Remote knows a launcher before "ours", cancels only after
 * another block's tap has returned, and is never used up; a hologram is hidden and never saved inside its spawn's
 * consumer and shown to its one viewer after, and a chunk is asked before anything is spawned; the plugin's stop takes
 * them down before the database closes; and a final break unlinks the launcher everywhere.
 */
class RemoteRoutingTest {

    @Test
    void theBlockListenerStepsAsideForARemoteOnALauncher() throws IOException {
        String body = method(source("block", "CustomBlockListener.java"), "public void onInteract(");
        int mega = body.indexOf("if (type == CustomBlockType.FIREWORK_LAUNCHER && MegaShow.isMega(");
        int remote = body.indexOf("if (type == CustomBlockType.FIREWORK_LAUNCHER && com.dierks.homecraft.arcade"
                + ".PrizeItems.kindOf(event.getItem()) == PluginConfig.PrizeType.REMOTE) { return; }");
        assertTrue(mega > 0 && remote > mega, "the Remote's return, right after the Mega check: " + body);
        assertTrue(remote < body.indexOf("plugin.sandbox().check("), "before the sandbox check");
        assertTrue(remote < body.indexOf("switch (type)"), "and the switch, so the launcher says nothing");
        assertTrue(body.indexOf("event.setUseInteractedBlock(Event.Result.DENY);") < remote,
                "after the deny: nothing opens");
        String tapLine = body.substring(body.indexOf("case FIREWORK_LAUNCHER -> {"));
        assertTrue(tapLine.contains("plugin.remotes().linkedAs(player.getUniqueId(), spotOf(clicked.getLocation()))"),
                "the launcher's own tap names its number in the tapper's set");
    }

    @Test
    void theRemoteKnowsALauncherFirstAndIsNeverUsedUp() throws IOException {
        String listener = source("arcade", "PrizeItemListener.java");
        assertTrue(listener.contains("case REMOTE -> remote(event, player);"), "the Remote's case");
        String body = method(listener, "private void remote(");
        int launcher = body.indexOf("boolean launcher = on != null && launcher(plugin, on);");
        int ours = body.indexOf("ours(plugin, on)");
        int cancel = body.indexOf("cancel(event);");
        assertTrue(launcher > 0 && launcher < ours, "a launcher (its tag, else its record) before ours: " + body);
        assertTrue(body.indexOf("return;", ours) < cancel, "another block's tap returns before anything is cancelled");
        assertFalse(body.contains("consume("), "the Remote is kept: never used up");
        assertTrue(body.contains("Whereabouts.inGameSession(plugin, player)"), "nothing in a game");
        assertTrue(body.indexOf("remotes.tap(player, on)") > cancel && body.contains("remotes.openMyShow(player)"),
                "a launcher links or unlinks; anything else opens My Show");
        assertFalse(listener.contains("case REMOTE -> { consume"), "and no other path spends it");
    }

    @Test
    void aHologramIsHiddenAndUnsavedInItsSpawnAndAChunkIsAskedFirst() throws IOException {
        String src = source("arcade", "RemoteMarkers.java");
        String spawn = method(src, "private TextDisplay spawn(");
        int consumer = spawn.indexOf("TextDisplay.class, d -> {");
        int hidden = spawn.indexOf("d.setVisibleByDefault(false);");
        int unsaved = spawn.indexOf("d.setPersistent(false);");
        int shown = spawn.indexOf("viewer.showEntity(plugin, td);");
        assertTrue(consumer > 0 && hidden > consumer && unsaved > consumer, "inside the spawn's consumer: " + spawn);
        assertTrue(hidden < spawn.indexOf("d.text("), "hidden first of all");
        assertTrue(shown > spawn.indexOf("});", consumer), "shown to its viewer after the spawn");
        assertTrue(spawn.contains("Keys.REMOTE_MARKER"), "tagged, so a stray one is swept");
        String update = method(src, "void update(Player p)");
        int loaded = update.indexOf("!MegaShowService.ticking(w, s)");
        assertTrue(loaded > 0 && loaded < update.indexOf("spawn(p, w,"),
                "a chunk is asked (the show's own test: loaded first, then ticking) before a spawn");
        assertFalse(update.contains("getBlockAt(") || update.contains("getChunkAt("), "and never loaded");
        assertTrue(update.contains("s.display.text(text(e.getValue()));"), "a renumber is an update in place");
        String service = source("arcade", "RemoteService.java");
        assertTrue(service.contains("td.getPersistentDataContainer().has(Keys.REMOTE_MARKER, PersistentDataType.STRING)"
                + " && !markers.live(td)"), "the sweep removes only one that isn't live");
    }

    @Test
    void theStopTakesThemDownBeforeTheDatabaseClosesAndABreakUnlinks() throws Exception {
        String main = source("", "HomeCraftManagement.java");
        String disable = method(main, "public void onDisable(");
        assertTrue(disable.contains("if (remotes != null) { remotes.stop();"), "the plugin's stop");
        assertTrue(disable.indexOf("remotes.stop()") < disable.indexOf("database.close()"), "before the database");
        assertTrue(main.contains("registerEvents(remotes, this);"), "it listens");
        assertTrue(main.contains("this.remotes.start();"), "and starts");
        String blocks = source("block", "CustomBlockListener.java");
        String forget = method(blocks, "public void forget(");
        assertTrue(forget.indexOf("blocks.removeAt(loc);") < forget.indexOf("plugin.remotes().launcherGone(spotOf(loc),"
                + " record.owner());"), "a final break unlinks it from every set: " + forget);
        assertTrue(blocks.contains("plugin.remotes().launcherPlaced(spotOf(block.getLocation()), player.getUniqueId());"),
                "and a place tells the owner's map");
        for (String name : new String[]{"onJoin", "onQuit", "onHeld", "onEntitiesLoad"}) {
            Class<?> type = switch (name) {
                case "onJoin" -> PlayerJoinEvent.class;
                case "onQuit" -> PlayerQuitEvent.class;
                case "onHeld" -> PlayerItemHeldEvent.class;
                default -> EntitiesLoadEvent.class;
            };
            EventHandler h = RemoteService.class.getMethod(name, type).getAnnotation(EventHandler.class);
            assertNotNull(h, name + " is a handler");
            assertEquals(EventPriority.MONITOR, h.priority(), name + " only watches");
        }
    }

    /**
     * A database hiccup never empties a set: {@code findByType} is empty on an error, so no records at all keeps a set
     * as it is (at load) and knows a linked launcher by its block alone (at use), and a tap on a set that couldn't be
     * read changes nothing rather than writing a one-launcher set over it.
     */
    @Test
    void aFailedReadNeverUnlinksAnything() throws IOException {
        String service = source("arcade", "RemoteService.java");
        String prune = method(service, "private List<Spot> prune(");
        assertTrue(prune.indexOf("if (recorded.isEmpty()) { return set; }") < prune.indexOf("recorded.containsKey(s)"),
                prune);
        String tap = method(service, "public void tap(");
        int unread = tap.indexOf("if (!sets.containsKey(id)) {");
        assertTrue(unread > 0 && unread < tap.indexOf("RemoteRules.toggle(set, spot)")
                && unread < tap.indexOf("save(id,"), "an unread set is never toggled or saved: " + tap);
        String show = method(source("arcade", "MegaShowService.java"), "private MegaShow.Pick pickLinked(");
        assertTrue(show.contains("Predicate<Spot> isRecorded = recorded.isEmpty() ? s -> true : recorded::containsKey;"),
                show);
    }

    /**
     * Review REMOTE-TAP-3: a Remote in the off hand, with nothing of ours in the main hand, links a launcher: the block
     * listener sends that tap to the Remote (a Java tap on a dropper never sends the off hand's use), after the main
     * hand's own Remote and before the sandbox and the switch, so the launcher's own line isn't said; nothing in a
     * game, as for the main hand.
     */
    @Test
    void aRemoteInTheOffHandLinksALauncher() throws IOException {
        String body = method(source("block", "CustomBlockListener.java"), "public void onInteract(");
        int main = body.indexOf("== PluginConfig.PrizeType.REMOTE) { return; }");
        int off = body.indexOf("com.dierks.homecraft.arcade.RemoteRules.offHandTap("
                + " com.dierks.homecraft.arcade.PrizeItems.kindOf(event.getItem()),"
                + " com.dierks.homecraft.arcade.PrizeItems.kindOf(player.getInventory().getItemInOffHand())))");
        int tap = body.indexOf("plugin.remotes().tapFromOffHand(player, clicked); return; }");
        assertTrue(main > 0 && off > main && tap > off, "after the main hand's Remote: " + body);
        assertTrue(tap < body.indexOf("plugin.sandbox().check(") && tap < body.indexOf("switch (type)"),
                "before the sandbox and the switch: the launcher says nothing of its own");
        assertTrue(body.indexOf("event.setUseInteractedBlock(Event.Result.DENY);") < off, "and nothing opens");
        String service = source("arcade", "RemoteService.java");
        assertTrue(method(service, "public void tapFromOffHand(").contains(
                "if (!com.dierks.homecraft.util.Whereabouts.inGameSession(plugin, player)) { tap(player, launcher); }"),
                "the same tap, and nothing in a game");
    }

    /**
     * Review REMOTE-MAP-1: a Show Map redraws whenever its owner's stored set changes, wherever that happens: every
     * write of a set goes through {@code save}, which bumps the version (so the rows of players who are away, and a set
     * pruned at a join, redraw too), a join redraws once, and a stale record found at a place or a tap leaves every
     * set as a final break does.
     */
    @Test
    void theShowMapRedrawsWheneverTheSetChanges() throws IOException {
        String service = source("arcade", "RemoteService.java");
        String save = method(service, "private boolean save(");
        assertTrue(save.indexOf("bump(player); return true;") > save.indexOf("setPref("), "a write bumps: " + save);
        String gone = method(service, "public void launcherGone(");
        String away = gone.substring(gone.indexOf("prefsNamed(RemoteRules.PREF)"));
        assertTrue(away.contains("save(e.getKey(), without(parsed.spots(), s));"),
                "the sets of players who are away are written through save, so bumped: " + away);
        assertEquals(1, count(service, "setPref(player, RemoteRules.PREF,"), "and nothing else writes a set");
        assertTrue(save.contains("setPref(player, RemoteRules.PREF,"), save);
        String linked = method(service, "public List<Spot> linked(");
        assertTrue(linked.contains("save(player, kept);"), "a set pruned or cleaned at a read is written (and bumped)");
        String join = method(service, "public void onJoin(");
        assertTrue(join.indexOf("bump(p.getUniqueId());") > join.indexOf("linked(p.getUniqueId());"),
                "a join redraws once, after the set is read: " + join);
        String stale = method(source("block", "CustomBlockListener.java"), "private boolean staleLauncher(");
        assertTrue(stale.indexOf("blocks.removeAt(b.getLocation());") < stale.indexOf(
                "plugin.remotes().launcherGone(spotOf(b.getLocation()), pb.owner());"),
                "a stale record leaves every set: " + stale);
    }

    /**
     * Review REMOTE-MAP-2: "Get my Show Map" takes away the other copies its owner can reach (the bag, the cursor, the
     * ender chest and the last one dropped), so giving it again replaces the old one.
     */
    @Test
    void gettingTheShowMapAgainTakesTheOldCopies() throws IOException {
        String service = source("arcade", "RemoteService.java");
        String give = method(service, "public void giveMap(");
        assertTrue(give.indexOf("takeOldCopies(player);") < give.indexOf("inv.setItem(slot, mapItem(view, id));"),
                "before the new one goes in: " + give);
        String take = method(service, "private void takeOldCopies(");
        assertTrue(take.contains("player.setItemOnCursor(null);"), "the cursor");
        assertTrue(take.contains("player.getEnderChest()"), "the ender chest");
        assertTrue(take.contains("droppedMaps.remove(id)") && take.contains("item.remove();"), "the last one dropped");
        assertTrue(method(service, "public void onDropMap(").contains("droppedMaps.put(UUID.fromString(owner),"
                + " item.getUniqueId());"), "which a drop remembers");
    }

    /**
     * Review REMOTE-LORE-4: the Remote's lines are refreshed held or not on a pickup and a world change, and right
     * after the Prize Counter hands one over (an item put in the bag fires no held-slot event).
     */
    @Test
    void theRemotesLinesAreRefreshedHeldOrNot() throws IOException {
        String service = source("arcade", "RemoteService.java");
        assertTrue(method(service, "public void onPickup(").contains("later(p, () -> carried(p));"), "a pickup");
        assertTrue(method(service, "public void onWorld(").contains("later(p, () -> carried(p));"), "a world change");
        String carried = method(service, "private void carried(");
        assertFalse(carried.contains("holdsRemote"), "held or not: " + carried);
        assertTrue(carried.contains("refreshRemotes(p);"), carried);
        String prizes = source("arcade", "PrizeService.java");
        assertEquals(2, count(prizes, "giveOrDrop(player, item); remoteHandedOver(player, p);"),
                "both hand-overs refresh a Remote's lines");
        assertTrue(method(prizes, "private void remoteHandedOver(").contains(
                "if (p.type() == PrizeType.REMOTE && plugin.remotes() != null) {"
                        + " plugin.remotes().refreshRemotes(player);"),
                "only for a Remote");
    }

    /**
     * Review REMOTE-PREF-6: a read that failed isn't tried again on every marker pass: the pause is asked before the
     * read, and only the first failure in a row warns.
     */
    @Test
    void aFailedReadPausesAndWarnsOnce() throws IOException {
        String linked = method(source("arcade", "RemoteService.java"), "public List<Spot> linked(");
        int due = linked.indexOf("if (!unread.due(player, now)) { return List.of();");
        assertTrue(due > 0 && due < linked.indexOf("prizes.dao().pref("), "the pause first: " + linked);
        assertTrue(linked.indexOf("if (unread.failed(player, now)) { plugin.getLogger().warning(") > 0,
                "a WARN only for the first failure in a row");
        assertTrue(linked.indexOf("unread.ok(player);") > linked.indexOf("catch (SQLException e)"), "a good read");
    }

    /**
     * Review LINK-LOADED-1: My Show and the holograms ask the show's own test (loaded and entity-ticking), so a tile
     * that says where a launcher is means a show would fire from it now.
     */
    @Test
    void myShowAndTheHologramsAskTheShowsOwnTest() throws IOException {
        String tile = method(source("gui/arcade", "MyShowMenu.java"), "private ItemStack tile(");
        assertTrue(tile.contains("boolean ready = sameWorld && MegaShowService.ticking(here, s);"), tile);
        assertFalse(tile.contains("isChunkLoaded("), "not a test of its own");
        assertTrue(tile.contains("RemoteRules.tileName(n, s, here.getName(), at.getX(), at.getZ(), ready, whose)"),
                "every fact in the name (review REMOTE-MYSHOW-5)");
        assertTrue(method(source("arcade", "MegaShowService.java"), "public static boolean ticking(")
                .contains("Chunk.LoadLevel.ENTITY_TICKING"), "the one test");
    }

    private static int count(String src, String what) {
        int n = 0;
        for (int at = src.indexOf(what); at >= 0; at = src.indexOf(what, at + what.length())) {
            n++;
        }
        return n;
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

    /** A source file, its line comments gone and its whitespace one space (as {@code MegaShowRulesTest}). */
    private static String source(String pkg, String file) throws IOException {
        Path dir = Path.of("src/main/java/com/dierks/homecraft");
        return Files.readString(pkg.isEmpty() ? dir.resolve(file) : dir.resolve(pkg).resolve(file),
                StandardCharsets.UTF_8).replaceAll("//[^\\n]*", "").replaceAll("\\s+", " ");
    }
}
