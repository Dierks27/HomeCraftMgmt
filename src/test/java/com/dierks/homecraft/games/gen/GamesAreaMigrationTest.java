package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.games.gen.api.Slots;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config revision 21 ({@link GamesAreaMigration}): the v4 areas. Golf of the Week and Classic Golf move to
 * x 8768, the Ice Boat north to z 2880, wherever the file still holds an origin this plugin shipped (0.36's,
 * or 0.35's that the legacy guard wrote back, with its {@code half_gap: 32}); an owner's own spot stays, with
 * a WARN, and so does an owner's {@code half_gap: 32} beside 0.36's spot; nothing else is touched; and a
 * second run changes nothing.
 */
class GamesAreaMigrationTest {

    private static final String SLOTS = "games.fresh.slots.";
    private static final String CLASSICS = "games.fresh.classics.slots.";

    /** The bundled file as 0.36 shipped the three moved areas. */
    private static YamlConfiguration v036() {
        YamlConfiguration c = LayoutFixtures.bundled();
        c.set(SLOTS + "fresh_golf.origin", List.of(7488, 160, 4096));
        c.set(SLOTS + "fresh_boat.origin", List.of(6080, 160, 5888));
        c.set(CLASSICS + "fresh_classic_golf.origin", List.of(7488, 160, 4800));
        return c;
    }

    private static List<Integer> list(Object raw) {
        return ((List<?>) raw).stream().map(o -> ((Number) o).intValue()).toList();
    }

    @Test
    void the036SpotsOfTheThreeGrownAreasMoveAndNothingElseDoes() {
        YamlConfiguration c = v036();
        String before = c.saveToString();
        List<String> log = new ArrayList<>();
        assertTrue(GamesAreaMigration.apply(c, log), "the file changes");
        assertEquals(List.of(8768, 160, 4096), list(c.get(SLOTS + "fresh_golf.origin")), "Golf of the Week: Col G");
        assertEquals(List.of(6080, 96, 2880), list(c.get(SLOTS + "fresh_boat.origin")), "Ice Boat: north");
        assertEquals(List.of(8768, 160, 4896), list(c.get(CLASSICS + "fresh_classic_golf.origin")), "Classic Golf");
        assertEquals(List.of(7488, 160, 5504), list(c.get(SLOTS + "fresh_tiny_golf.origin")), "Tiny Golf stays");
        assertEquals(LayoutFixtures.bundled().saveToString(), c.saveToString(), "now exactly the bundled file: every"
                + " other key as it was");
        assertFalse(before.equals(c.saveToString()), "(it did change)");
        assertEquals(1, log.size(), "one line: " + log);
        assertFalse(log.get(0).startsWith(LayoutGuard.WARN), "an INFO: nothing for the owner to do");
        assertTrue(log.get(0).contains("Golf of the Week [7488, 160, 4096] -> [8768, 160, 4096]")
                && log.get(0).contains("emptied by themselves"), "saying what moved and what happens next: " + log);
        assertFalse(GamesAreaMigration.apply(c, new ArrayList<>()), "a second run changes nothing");
    }

    @Test
    void the035SpotsTheGuardWroteBackMoveTooAndTheirOldGapGoesWithThem() {
        YamlConfiguration c = LayoutFixtures.v035();
        c.set(SLOTS + "fresh_golf.half_gap", 32);
        c.set(SLOTS + "fresh_boat.half_gap", 32);
        c.set(SLOTS + "fresh_tiny_golf.half_gap", 32);
        c.set(CLASSICS + "fresh_classic_golf", List.of(4352, 160, 4736)); // a bare list, as 0.35 allowed
        GamesAreaMigration.apply(c, new ArrayList<>());
        assertEquals(List.of(8768, 160, 4096), list(c.get(SLOTS + "fresh_golf.origin")), "0.35's golf spot is ours");
        assertNull(c.get(SLOTS + "fresh_golf.half_gap"), "and its 0.35 gap goes: the default 576");
        assertEquals(List.of(6080, 96, 2880), list(c.get(SLOTS + "fresh_boat.origin")), "0.35's boat spot too");
        assertNull(c.get(SLOTS + "fresh_boat.half_gap"), "and its gap");
        assertEquals(List.of(8768, 160, 4896), list(c.get(CLASSICS + "fresh_classic_golf")),
                "a bare Classic list stays a bare list, at its new spot");
        assertEquals(32, c.getInt(SLOTS + "fresh_tiny_golf.half_gap"), "Tiny Golf didn't grow: its shape stays");
        assertEquals(List.of(5120, 160, 4096), list(c.get(SLOTS + "fresh_tiny_golf.origin")), "and its spot");
    }

    @Test
    void anOwnersOwnSpotStaysWithAWarnNamingTheShippedOne() {
        YamlConfiguration c = v036();
        c.set(SLOTS + "fresh_golf.origin", List.of(9024, 160, 4096));
        c.set(SLOTS + "fresh_golf.half_gap", 32);
        List<String> log = new ArrayList<>();
        GamesAreaMigration.apply(c, log);
        assertEquals(List.of(9024, 160, 4096), list(c.get(SLOTS + "fresh_golf.origin")), "the owner's spot stays");
        assertEquals(32, c.getInt(SLOTS + "fresh_golf.half_gap"), "with the gap they set");
        assertEquals(List.of(6080, 96, 2880), list(c.get(SLOTS + "fresh_boat.origin")), "the untouched boat moves");
        assertTrue(log.stream().anyMatch(l -> l.startsWith(LayoutGuard.WARN) && l.contains("kept your own"
                + " games.fresh.slots.fresh_golf.origin [9024, 160, 4096]") && l.contains("128 x 16 x 224")
                && l.contains("[8768, 160, 4096]")), "one WARN: theirs, the new size, the shipped spot: " + log);
    }

    @Test
    void anOwnersHalfGap32BesideThe036SpotStaysWithOneWarnEach() {
        // the v4 audit, ECON03: 0.36 never wrote a 32 beside its own spot (only beside 0.35's), so it is the
        // owner's own choice, a knob the README documents (32-4096): kept and named, never dropped silently
        YamlConfiguration c = v036();
        c.set(SLOTS + "fresh_golf.half_gap", 32);
        c.set(SLOTS + "fresh_boat.half_gap", 32);
        c.set(SLOTS + "fresh_tiny_golf.half_gap", 32);
        List<String> log = new ArrayList<>();
        GamesAreaMigration.apply(c, log);
        assertEquals(List.of(8768, 160, 4096), list(c.get(SLOTS + "fresh_golf.origin")), "the untouched spot moves");
        assertEquals(32, c.getInt(SLOTS + "fresh_golf.half_gap"), "while the owner's gap stays");
        assertEquals(List.of(6080, 96, 2880), list(c.get(SLOTS + "fresh_boat.origin")), "the boat's spot moves too");
        assertEquals(32, c.getInt(SLOTS + "fresh_boat.half_gap"), "and keeps its gap");
        assertEquals(32, c.getInt(SLOTS + "fresh_tiny_golf.half_gap"), "Tiny Golf doesn't move: untouched");
        List<String> warns = log.stream().filter(l -> l.startsWith(LayoutGuard.WARN)).toList();
        assertEquals(2, warns.size(), "one WARN for each kept gap: " + log);
        assertTrue(warns.get(0).contains("kept your own games.fresh.slots.fresh_golf.half_gap 32 for Golf of the Week"
                + " at [8768, 160, 4096]") && warns.get(0).contains("576"), "naming the key, its new spot and the"
                + " shipped gap: " + warns);
        assertTrue(warns.get(1).contains("games.fresh.slots.fresh_boat.half_gap 32"), warns.toString());
        assertTrue(GamesAreaMigration.legacyShape(List.of(4864, 160, 4096), GamesAreaMigration.MOVES.get(0)),
                "beside 0.35's spot a 32 is 0.35's shape");
        assertFalse(GamesAreaMigration.legacyShape(List.of(7488, 160, 4096), GamesAreaMigration.MOVES.get(0)),
                "beside 0.36's it is the owner's");
        GamesAreaMigration.apply(c, new ArrayList<>());
        assertEquals(32, c.getInt(SLOTS + "fresh_golf.half_gap"), "a second run keeps it too");
    }

    @Test
    void aMissingOrUnreadableOriginIsLeftToTheBackfillOrTakesTheNewSpot() {
        YamlConfiguration c = v036();
        c.set(SLOTS + "fresh_golf.origin", null);
        c.set(SLOTS + "fresh_golf.half_gap", 32);
        c.set(SLOTS + "fresh_boat.origin", Arrays.asList(6080, 160, "5888"));
        List<String> log = new ArrayList<>();
        GamesAreaMigration.apply(c, log);
        assertNull(c.get(SLOTS + "fresh_golf.origin"), "missing: the backfill writes the shipped one");
        assertNull(c.get(SLOTS + "fresh_golf.half_gap"), "but the old shape's gap goes");
        assertEquals(List.of(6080, 96, 2880), list(c.get(SLOTS + "fresh_boat.origin")),
                "one the plugin can't read was never where anything stood: it takes the new spot");
        assertTrue(log.stream().noneMatch(l -> l.startsWith(LayoutGuard.WARN)), "no WARN for either: " + log);
    }

    @Test
    void everythingElseOfTheirEntriesIsLeftAlone() {
        YamlConfiguration c = v036();
        c.set(SLOTS + "fresh_boat.enabled", true);
        c.set(SLOTS + "fresh_boat.tier", "hard");
        c.set(SLOTS + "fresh_golf.mix", "EEMMH");
        GamesAreaMigration.apply(c, new ArrayList<>());
        ConfigurationSection boat = c.getConfigurationSection(SLOTS + "fresh_boat");
        assertNotNull(boat, "still a section");
        assertTrue(boat.getBoolean("enabled"), "the owner's switch stays (Ice Boat ships off; theirs is on)");
        assertEquals("hard", boat.getString("tier"), "their tier");
        assertEquals("EEMMH", c.getString(SLOTS + "fresh_golf.mix"), "their golf mix");
    }

    @Test
    void theMovesAreTheMergedTable() {
        assertEquals(List.of(Slots.DAILY_GOLF, Slots.ICE_BOAT, Slots.CLASSIC_GOLF),
                GamesAreaMigration.MOVES.stream().map(GamesAreaMigration.Move::def).toList(), "the three grown areas");
        assertEquals(LayoutGuard.RESIZED, java.util.Set.copyOf(GamesAreaMigration.MOVES.stream()
                .map(m -> m.def().id()).toList()), "the same three the legacy guard never keeps at 0.35's shape");
        for (GamesAreaMigration.Move m : GamesAreaMigration.MOVES) {
            LayoutGuard.Area a = LayoutGuard.AREAS.stream().filter(x -> x.id().equals(m.def().id())).findFirst()
                    .orElseThrow();
            assertTrue(m.shipped().contains(a.legacy()), m.def().id() + ": 0.35's spot counts as ours");
            assertEquals(a.shipped(), m.to(), m.def().id() + ": it moves to the shipped spot");
        }
        assertEquals(21, GamesAreaMigration.REVISION, "revision 21 (20 is the token balance)");
    }
}
