package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which placed head opens the Arcade (SHOP-V2-SPEC §5.8): only an Arcade Trophy (the row {@code arcade_trophy}, a
 * TROPHY), never a ladder trophy, the Monthly or a hat; the material is read first, so a tap on any other block never
 * reads skull data; one placed since 0.37.3 is told by its plain id; one placed before is decoded once and its id
 * written back.
 */
class ArcadeTrophiesTest {

    @Test
    void onlyTheArcadeTrophyOpensTheArcade() {
        assertTrue(ArcadeTrophies.opensArcade("arcade_trophy", PrizeType.TROPHY));
        assertFalse(ArcadeTrophies.opensArcade("trophy_bronze", PrizeType.TROPHY), "a ladder trophy does nothing");
        assertFalse(ArcadeTrophies.opensArcade("trophy_month", PrizeType.TROPHY), "nor the Monthly");
        assertFalse(ArcadeTrophies.opensArcade("arcade_trophy", PrizeType.HAT), "the id alone is not enough");
        assertFalse(ArcadeTrophies.opensArcade("hat_bunny", PrizeType.HAT));
        assertFalse(ArcadeTrophies.opensArcade(null, PrizeType.TROPHY));
        assertEquals("arcade_trophy", ArcadeTrophies.ID);
    }

    @Test
    void sneakingPassesThroughAndThePermissionIsChecked() {
        assertEquals(ArcadeTrophies.Tap.PASS, ArcadeTrophies.tap(true, true), "she builds next to her trophy shelf");
        assertEquals(ArcadeTrophies.Tap.PASS, ArcadeTrophies.tap(true, false));
        assertEquals(ArcadeTrophies.Tap.REFUSE, ArcadeTrophies.tap(false, false), "no hcm.arcade.use");
        assertEquals(ArcadeTrophies.Tap.OPEN, ArcadeTrophies.tap(false, true));
    }

    /** A stand-in skull: its two tags, and every read and write counted. */
    private static final class FakeSkull implements ArcadeTrophies.SkullTags {
        String placedId;
        final String stored;
        int reads;
        final List<String> written = new ArrayList<>();

        FakeSkull(String placedId, String stored) {
            this.placedId = placedId;
            this.stored = stored;
        }

        @Override
        public String placedId() {
            reads++;
            return placedId;
        }

        @Override
        public String storedItem() {
            reads++;
            return stored;
        }

        @Override
        public void writePlacedId(String id) {
            written.add(id);
            placedId = id;
        }
    }

    @Test
    void aTapOnAnyOtherBlockNeverReadsSkullData() {
        int[] asked = {0};
        int[] decoded = {0};
        for (Material m : List.of(Material.STONE, Material.CHEST, Material.CREEPER_HEAD, Material.SKELETON_SKULL,
                Material.OAK_SIGN)) {
            assertFalse(ArcadeTrophies.isArcadeTrophy(m, () -> {
                asked[0]++;
                return new FakeSkull("arcade_trophy", null);
            }, b64 -> {
                decoded[0]++;
                return null;
            }), m.name());
        }
        assertEquals(0, asked[0], "the material is tested first");
        assertEquals(0, decoded[0]);
    }

    @Test
    void aTrophyPlacedSince0373IsToldByItsPlainId() {
        int[] decoded = {0};
        FakeSkull skull = new FakeSkull("arcade_trophy", "b64");
        assertTrue(ArcadeTrophies.isArcadeTrophy(Material.PLAYER_HEAD, () -> skull, b64 -> {
            decoded[0]++;
            return null;
        }));
        assertTrue(ArcadeTrophies.isArcadeTrophy(Material.PLAYER_WALL_HEAD, () -> new FakeSkull("arcade_trophy", null),
                b64 -> null), "on a wall too");
        assertEquals(0, decoded[0], "nothing decoded on the hot path");
        assertFalse(ArcadeTrophies.isArcadeTrophy(Material.PLAYER_HEAD, () -> new FakeSkull("trophy_bronze", "b64"),
                b64 -> new ArcadeTrophies.Decoded("arcade_trophy", PrizeType.TROPHY)), "a Bronze Trophy does nothing");
        assertFalse(ArcadeTrophies.isArcadeTrophy(Material.PLAYER_HEAD, () -> new FakeSkull(null, null), b64 -> null),
                "a plain head, a Mini or a Display Case: not a placed prize");
        assertFalse(ArcadeTrophies.isArcadeTrophy(Material.PLAYER_HEAD, () -> null, b64 -> null), "no skull");
    }

    @Test
    void aTrophyPlacedBefore0373IsDecodedOnceAndGetsItsIdWrittenBack() {
        int[] decoded = {0};
        FakeSkull old = new FakeSkull(null, "b64-of-arcade-trophy");
        java.util.function.Function<String, ArcadeTrophies.Decoded> decode = b64 -> {
            decoded[0]++;
            return new ArcadeTrophies.Decoded("arcade_trophy", PrizeType.TROPHY);
        };
        assertTrue(ArcadeTrophies.isArcadeTrophy(Material.PLAYER_HEAD, () -> old, decode));
        assertEquals(List.of("arcade_trophy"), old.written, "the id is written back");
        assertTrue(ArcadeTrophies.isArcadeTrophy(Material.PLAYER_HEAD, () -> old, decode));
        assertEquals(1, decoded[0], "decoded once: the next tap reads the string");
        assertEquals(1, old.written.size());

        FakeSkull oldHat = new FakeSkull(null, "b64-of-a-hat");
        assertFalse(ArcadeTrophies.isArcadeTrophy(Material.PLAYER_HEAD, () -> oldHat,
                b64 -> new ArcadeTrophies.Decoded("hat_bunny", PrizeType.HAT)));
        assertEquals(List.of("hat_bunny"), oldHat.written, "any old prize gets its id, so it is decoded once too");
        assertFalse(ArcadeTrophies.isArcadeTrophy(Material.PLAYER_HEAD, () -> new FakeSkull(null, "junk"),
                b64 -> null), "an item that can't be read: no");
    }

    @Test
    void anArcadeTrophySaysItOpensTheArcadeAndOnlyIt() {
        var arcade = new com.dierks.homecraft.config.PluginConfig.Prize("arcade_trophy",
                com.dierks.homecraft.config.PluginConfig.PrizeTab.TROPHIES, "&6Arcade Trophy",
                List.of("A numbered trophy", "with your name on it."), 750,
                new com.dierks.homecraft.config.PluginConfig.Icon("", Material.GOLD_BLOCK), PrizeType.TROPHY, true,
                null, 1, null, null, null, null, 0, 0, null, 0, "", List.of(), List.of(), null, null, List.of(), null);
        List<String> lore = PrizeItems.trophyLore(arcade, "a player");
        assertTrue(lore.contains("&7Place it and tap it to open the Arcade!"), lore.toString());
        assertEquals("&8Arcade prize", lore.get(lore.size() - 1));
    }
}
