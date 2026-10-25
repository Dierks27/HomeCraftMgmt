package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.MegaShow.Spot;
import com.dierks.homecraft.arcade.RemoteRules.Parsed;
import com.dierks.homecraft.arcade.RemoteRules.Result;
import com.dierks.homecraft.arcade.RemoteRules.Toggle;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.DyeColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Firework Remote's rules (0.38.2): the stored set reads back as it was written, and a bad, doubled or eleventh
 * entry is dropped (and the row cleaned); another version reads as empty; a tap links at the end, unlinks (the later
 * ones move up) or says the set is full; where a launcher is, in eight ways; the lore, the lines and the My Show tile
 * names; ten numbers in ten colours; a held tap counts once; an off-hand Remote's tap; and a failed read's pause.
 */
class RemoteRulesTest {

    private static Spot at(int x) {
        return new Spot("world", x, 70, -340);
    }

    @Test
    void theStoredSetReadsBackAsItWasWritten() {
        List<Spot> set = List.of(new Spot("world", 120, 70, -340), new Spot("world", 10, 64, 5),
                new Spot("my, world", -7, -60, 3));
        List<String> warns = new ArrayList<>();
        String value = RemoteRules.format(set, warns::add);
        assertEquals("v1|120,70,-340,world|10,64,5,world|-7,-60,3,my, world", value, "the world last: it may hold a ,");
        assertEquals(List.of(), warns);
        Parsed p = RemoteRules.parse(value);
        assertEquals(set, p.spots(), "in link order");
        assertFalse(p.cleaned());
        assertFalse(p.newer());
        assertNull(RemoteRules.format(List.of(), warns::add), "an empty set: the row goes");
        assertEquals(List.of(), RemoteRules.parse(null).spots());
        assertEquals(List.of(), RemoteRules.parse("  ").spots());
        assertEquals("v1|1,2,3,world", RemoteRules.format(List.of(new Spot("world", 1, 2, 3),
                new Spot("bad|world", 1, 2, 3)), warns::add), "a world with a | can't round-trip: left out");
        assertEquals(1, warns.size(), warns.toString());
        assertEquals("arcade.launcher.linked", RemoteRules.PREF);
        assertEquals("arcade.launcher.show_map", RemoteRules.MAP_PREF);
    }

    @Test
    void aBadDoubledOrEleventhEntryIsDroppedAndTheRowCleaned() {
        Parsed bad = RemoteRules.parse("v1|1,2,3,world|x,2,3,world|1,2,world|4,5,6,|7,99999,8,world"
                + "|40000000,1,1,world|1,2,3,world|9,9,9,world");
        assertEquals(List.of(new Spot("world", 1, 2, 3), new Spot("world", 9, 9, 9)), bad.spots(),
                "a word, a missing field, no world, a height off the world, a far x and a second copy are dropped");
        assertTrue(bad.cleaned(), "so the row is written back clean");
        StringBuilder twelve = new StringBuilder("v1");
        for (int i = 0; i < 12; i++) {
            twelve.append('|').append(i).append(",64,0,world");
        }
        Parsed full = RemoteRules.parse(twelve.toString());
        assertEquals(RemoteRules.MAX_LINKED, full.spots().size(), "past ten are dropped");
        assertEquals(new Spot("world", 9, 64, 0), full.spots().get(9));
        assertTrue(full.cleaned());
        Parsed newer = RemoteRules.parse("v2|whatever|it|is");
        assertEquals(List.of(), newer.spots(), "another version: empty here");
        assertTrue(newer.newer(), "and said so");
        assertFalse(newer.cleaned(), "but never rewritten (a newer HomeCraft's set is kept until the player changes it)");
    }

    @Test
    void aTapLinksUnlinksOrSaysTheSetIsFull() {
        List<Spot> set = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Toggle t = RemoteRules.toggle(set, at(i));
            assertEquals(Result.LINKED, t.result());
            assertEquals(i, t.number(), "the tap order is the firing order");
            set = t.set();
        }
        assertEquals(2, RemoteRules.linkedAs(set, at(2)));
        assertEquals(0, RemoteRules.linkedAs(set, at(9)), "not linked");
        assertEquals(0, RemoteRules.linkedAs(null, at(1)));
        Toggle off = RemoteRules.toggle(set, at(2));
        assertEquals(Result.UNLINKED, off.result());
        assertEquals(2, off.number(), "it was #2");
        assertEquals(List.of(at(1), at(3)), off.set(), "and #3 moved up");
        assertEquals(2, RemoteRules.linkedAs(off.set(), at(3)));
        List<Spot> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(at(100 + i));
        }
        Toggle full = RemoteRules.toggle(ten, at(1));
        assertEquals(Result.FULL, full.result(), "an 11th tap");
        assertEquals(ten, full.set(), "changes nothing");
        assertEquals(Result.UNLINKED, RemoteRules.toggle(ten, at(105)).result(), "a linked one still comes off");
        assertEquals("&7Your show has 10 launchers already. Tap one of them to take it off first!", RemoteRules.FULL);
    }

    @Test
    void theLinesSayTheNumberInItsColour() {
        assertEquals("&a✔ Linked as &6#2&a! &7(2 linked)", RemoteRules.linkedLine(2, 2));
        assertEquals("&7Took #2 off your show. The ones after it moved up. &8(3 linked)",
                RemoteRules.unlinkedLine(2, 3, false));
        assertEquals("&7Took #4 off your show. &8(3 linked)", RemoteRules.unlinkedLine(4, 3, true), "the last one");
        assertEquals("&eFirework Remote &7(3 linked)", RemoteRules.remoteName("&eFirework Remote", 3),
                "the facts in the NAME, for Bedrock");
        assertEquals("&eFirework Remote &7(0 linked)", RemoteRules.remoteName(null, 0));
        assertEquals(List.of("&7Tap your Firework Launchers", "&7Your show:", "&8Nothing linked yet.",
                RemoteRules.USE_LINE, "&8Arcade prize"), RemoteRules.remoteLore(List.of("Tap your Firework Launchers"),
                List.of()));
        assertEquals(List.of("&7Your show:", "&c#1 &7· in world_nether", RemoteRules.USE_LINE, "&8Arcade prize"),
                RemoteRules.remoteLore(List.of(), List.of(RemoteRules.loreLine(1, new Spot("world_nether", 1, 2, 3),
                        "world", 0, 0))));
        assertEquals(List.of("&3&l#6"), RemoteRules.markerLines(6, null), "the viewer's own: the number");
        assertEquals(List.of("&c&l#1", "&7Owner's"), RemoteRules.markerLines(1, "Owner"), "someone else's: whose");
    }

    @Test
    void whereALauncherIsInEightWays() {
        assertEquals("north", RemoteRules.direction(0, -50), "north is -Z");
        assertEquals("north-east", RemoteRules.direction(30, -30));
        assertEquals("east", RemoteRules.direction(50, 0));
        assertEquals("south-east", RemoteRules.direction(30, 30));
        assertEquals("south", RemoteRules.direction(0, 50));
        assertEquals("south-west", RemoteRules.direction(-30, 30));
        assertEquals("west", RemoteRules.direction(-50, 0));
        assertEquals("north-west", RemoteRules.direction(-30, -30));
        assertEquals("north", RemoteRules.direction(10, -50), "the nearest of the eight");
        assertEquals("right here", RemoteRules.direction(1, -2), "within 3 blocks");
        assertEquals(45, RemoteRules.blocks(27, -36));
        Spot s = new Spot("world", 120, 70, -340);
        assertEquals("&e#3 &7· north-east, 45 blocks · x 120 y 70 z -340",
                RemoteRules.loreLine(3, s, "world", 120.5 - 32, -340 + 0.5 + 32), "the owner's example");
        assertEquals("&9#8 &7· in world_nether", RemoteRules.loreLine(8, new Spot("world_nether", 1, 2, 3), "world",
                0, 0), "another world");
        assertEquals("&c#1 &7· right here · x 120 y 70 z -340", RemoteRules.loreLine(1, s, "world", 120.5, -339.5));
    }

    /**
     * Review REMOTE-MYSHOW-5: a My Show tile's NAME says everything (Bedrock shows lore only on a long press): where it
     * is, its x/y/z and whose it is; "not loaded" for one that can't fire now, and the world for one elsewhere.
     */
    @Test
    void aMyShowTileSaysEverythingInItsName() {
        Spot s = new Spot("world", 120, 70, -340);
        assertEquals("&e#3 &7· north-east, 45 blocks · x 120 y 70 z -340 · yours",
                RemoteRules.tileName(3, s, "world", 120.5 - 32, -340 + 0.5 + 32, true, "yours"), "the owner's example");
        assertEquals("&c#1 &7· not loaded (300 blocks north) · x 0 y 70 z -300 · Someone's",
                RemoteRules.tileName(1, new Spot("world", 0, 70, -300), "world", 0.5, 0.5, false, "Someone's"),
                "can't fire now: the show's \"not loaded\", with the way to it, and someone else's");
        assertEquals("&c#1 &7· not loaded (right here) · x 0 y 70 z 0",
                RemoteRules.tileName(1, new Spot("world", 0, 70, 0), "world", 0.5, 0.5, false, null),
                "right next to it, and no record to say whose: nothing said");
        assertEquals("&9#8 &7· in world_nether · x 1 y 2 z 3 · yours",
                RemoteRules.tileName(8, new Spot("world_nether", 1, 2, 3), "world", 0, 0, true, "yours"),
                "another world: its name, ready or not");
        assertEquals("&9#8 &7· in world_nether · x 1 y 2 z 3 · yours",
                RemoteRules.tileName(8, new Spot("world_nether", 1, 2, 3), "world", 0, 0, false, "yours"));
    }

    /**
     * Review REMOTE-TAP-3: a launcher tapped with nothing of ours in the main hand and a Remote in the off hand is the
     * Remote's tap (a Java tap on a dropper never sends the off hand's); a prize in the main hand keeps its own.
     */
    @Test
    void aRemoteInTheOffHandTapsWhenTheMainHandHoldsNothingOfOurs() {
        assertTrue(RemoteRules.offHandTap(null, PrizeType.REMOTE), "an empty hand, a Show Map or a block: the Remote");
        assertFalse(RemoteRules.offHandTap(null, null), "no Remote: the launcher's own line");
        assertFalse(RemoteRules.offHandTap(null, PrizeType.FIREWORK), "a show in the off hand isn't a Remote");
        for (PrizeType main : PrizeType.values()) {
            assertFalse(RemoteRules.offHandTap(main, PrizeType.REMOTE),
                    "a prize in the main hand is that prize's tap (a Remote there is the main hand's own): " + main);
        }
    }

    /**
     * Review REMOTE-PREF-6: a set that couldn't be read isn't read again on every marker pass (twice a second) but
     * after a pause, and only the first failure in a row warns.
     */
    @Test
    void aFailedReadIsTriedAgainAfterAPauseAndWarnsOnce() {
        RemoteRules.Retries r = new RemoteRules.Retries();
        UUID p = UUID.randomUUID();
        assertTrue(r.due(p, 0), "never failed: read");
        assertTrue(r.failed(p, 1000), "the first failure warns");
        for (long t = 1500; t < 6000; t += 500) {
            assertFalse(r.due(p, t), "not read again on the marker pass: " + t);
        }
        assertTrue(r.due(p, 6000), "after the pause: read again");
        assertFalse(r.failed(p, 6000), "and a second failure in a row is quiet");
        assertFalse(r.due(p, 10_999));
        assertTrue(r.due(p, 11_000));
        r.ok(p);
        assertTrue(r.due(p, 11_001), "a good read: no pause");
        assertTrue(r.failed(p, 12_000), "and the next failure warns again");
        UUID q = UUID.randomUUID();
        assertTrue(r.due(q, 12_001), "each player their own");
        assertEquals(5_000, RemoteRules.Retries.RETRY_MS);
    }

    @Test
    void tenNumbersInTenColours() {
        assertEquals(10, RemoteRules.NUMBER_DYES.size());
        assertEquals(10, new HashSet<>(RemoteRules.NUMBER_DYES).size(), "ten distinct dyes (and so banner flags)");
        assertEquals(10, new HashSet<>(RemoteRules.CODES).size(), "and ten distinct chat colours");
        assertEquals(DyeColor.RED, RemoteRules.dye(1));
        assertEquals(DyeColor.MAGENTA, RemoteRules.dye(10));
        assertEquals("RED_WOOL", RemoteRules.wool(1));
        assertEquals("LIGHT_BLUE_WOOL", RemoteRules.wool(7));
        assertEquals("&c#1", RemoteRules.tag(1));
        assertEquals("&d#10", RemoteRules.tag(10));
        assertEquals(MegaShow.MAX_LAUNCHERS, RemoteRules.MAX_LINKED, "the biggest show's size");
    }

    @Test
    void aHeldTapCountsOnce() {
        RemoteRules.Taps taps = new RemoteRules.Taps();
        UUID p = UUID.randomUUID();
        Spot a = at(1);
        assertTrue(taps.counts(p, a, 0), "the first tap");
        for (long t = 200; t <= 2000; t += 200) {
            assertFalse(taps.counts(p, a, t), "a held tap repeats every 200 ms: one toggle until it is let go: " + t);
        }
        assertTrue(taps.counts(p, a, 2600), "600 ms after the last repeat: a new tap");
        assertTrue(taps.counts(p, at(2), 2700), "another launcher counts at once");
        assertTrue(taps.counts(p, a, 2800), "and back again");
        assertFalse(taps.counts(p, RemoteRules.Taps.SCREEN, 3000), "My Show never opens within the gap of a link");
        assertFalse(taps.counts(p, RemoteRules.Taps.SCREEN, 3200), "nor on a held tap on the ground");
        assertTrue(taps.counts(p, RemoteRules.Taps.SCREEN, 3900), "a fresh tap on the ground opens it");
        UUID q = UUID.randomUUID();
        assertTrue(taps.counts(q, a, 2801), "each player their own");
        taps.forget(p);
        assertTrue(taps.counts(p, a, 3901), "a quit forgets them");
        assertEquals(600, RemoteRules.Taps.GAP_MS);
    }
}
