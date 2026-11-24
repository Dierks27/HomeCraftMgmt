package com.dierks.homecraft.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The side rooms' models (0.41.0): every id the owner was promised, a placeholder for each made of vanilla textures
 * only (so the rooms look right before the owner's models exist, and never purple-black), a vanilla stand-in for each
 * for a player without the pack, and HCM's placeholder left out for every name the owner's drop-in has.
 */
class ClubModelsTest {

    @Test
    void everyPromisedIdIsThere() {
        Set<String> ids = new HashSet<>();
        for (ClubModels.Model m : ClubModels.all()) {
            assertTrue(ids.add(m.id()), "once: " + m.id());
            assertTrue(m.id().startsWith("hcm:"), m.id());
        }
        assertTrue(ids.containsAll(List.of("hcm:cabinet_whack", "hcm:cabinet_simon", "hcm:cabinet_match",
                "hcm:cabinet_tictactoe", "hcm:cabinet_snake", "hcm:trophy_gold", "hcm:trophy_silver",
                "hcm:trophy_bronze", "hcm:door_arcade", "hcm:door_trophy", "hcm:cabinet_sweeper", "hcm:cabinet_merge",
                "hcm:cabinet_connect")), ids.toString());
        assertEquals("hcm:cabinet_whack", ClubModels.cabinetFor("whack_a_zombie").id());
        assertEquals("hcm:cabinet_tictactoe", ClubModels.cabinetFor("TIC_TAC_TOE").id());
        assertEquals("hcm:cabinet_connect", ClubModels.cabinetFor("connect_four").id(), "plan 11: Connect Four's own");
        assertEquals("hcm:cabinet_sweeper", ClubModels.cabinetFor("creeper_sweeper").id());
        assertEquals("hcm:cabinet_merge", ClubModels.cabinetFor("ore_merge").id());
        assertEquals("hcm:cabinet", ClubModels.cabinetFor("some_new_game").id(), "any other cabinet game: the plain one");
        assertEquals("hcm:trophy_gold", ClubModels.trophy(1).id());
        assertEquals("hcm:trophy_silver", ClubModels.trophy(2).id());
        assertEquals("hcm:trophy_bronze", ClubModels.trophy(3).id());
        assertEquals("hcm:door_arcade", ClubModels.door(true).id());
        assertEquals("hcm:door_trophy", ClubModels.door(false).id());
    }

    /**
     * 0.55.0: the Game Parlor's Sports Board has its own placeholder (a cabinet's size: a dark board with a lit screen,
     * block-atlas textures only, so never purple-black) and a picture stand-in for a player without the pack.
     */
    @Test
    void theSportsBoardHasACabinetSizedPlaceholder() {
        ClubModels.Model m = ClubModels.machineFor("sportsbook");
        assertNotNull(m, "machine:sportsbook has a model");
        assertEquals("hcm:machine_sportsbook", m.id());
        assertEquals(ClubModels.Kind.CABINET, m.kind(), "a cabinet's size");
        assertEquals(Material.CLOCK, m.fallback(), "a clock stands in without the pack: tonight's games");
        JsonObject json = JsonParser.parseString(ClubModels.placeholderFiles(Set.of())
                .get("assets/hcm/models/item/placeholder/machine_sportsbook.json")).getAsJsonObject();
        JsonObject tex = json.getAsJsonObject("textures");
        assertEquals("minecraft:block/black_concrete", tex.get("body").getAsString(), "a dark board");
        assertEquals("minecraft:block/sea_lantern", tex.get("screen").getAsString(), "a lit screen");
        for (Map.Entry<String, com.google.gson.JsonElement> t : tex.entrySet()) {
            assertTrue(t.getValue().getAsString().startsWith("minecraft:block/"), t.getKey());
        }
        assertTrue(json.getAsJsonArray("elements").size() > 4, "the board's score lines are drawn on it");
    }

    /**
     * 0.57.0: Texas Hold'em's card table has its own placeholder (cards on a card table, like Twenty-One's: green felt,
     * two face-down cards, a stack of chips), block-atlas textures only, and its Games-screen icon as the stand-in.
     */
    @Test
    void holdemHasCardsOnATable() {
        ClubModels.Model m = ClubModels.machineFor("holdem");
        assertNotNull(m, "machine:holdem has a model");
        assertEquals("hcm:machine_holdem", m.id());
        assertEquals(ClubModels.Kind.TROPHY, m.kind(), "it lies on its card table, as Twenty-One's cards do");
        assertEquals(Material.PAPER, m.fallback());
        JsonObject json = JsonParser.parseString(ClubModels.placeholderFiles(Set.of())
                .get("assets/hcm/models/item/placeholder/machine_holdem.json")).getAsJsonObject();
        assertEquals("minecraft:block/green_wool", json.getAsJsonObject("textures").get("felt").getAsString());
        assertTrue(json.getAsJsonArray("elements").size() >= 5, "felt, two cards and chips");
    }

    /**
     * 0.57.0: Bingo Night's machine in the Game Parlor and Picture Bingo's cabinet in the Arcade Room have their own
     * placeholders (a cabinet's size, block-atlas textures only) and a picture stand-in for a player without the pack.
     */
    @Test
    void theBingosHaveTheirOwnMachineAndCabinet() {
        ClubModels.Model m = ClubModels.machineFor("bingo");
        assertNotNull(m, "machine:bingo has a model");
        assertEquals("hcm:machine_bingo", m.id());
        assertEquals(ClubModels.Kind.CABINET, m.kind());
        assertEquals(Material.FILLED_MAP, m.fallback(), "a card stands in without the pack");
        ClubModels.Model c = ClubModels.cabinetFor("kid_bingo");
        assertEquals("hcm:cabinet_bingo", c.id(), "Picture Bingo's own cabinet, not the plain one");
        assertEquals(Material.PIG_SPAWN_EGG, c.fallback(), "a picture stands in: the first animal");
        for (String name : List.of("machine_bingo", "cabinet_bingo")) {
            JsonObject json = JsonParser.parseString(ClubModels.placeholderFiles(Set.of())
                    .get("assets/hcm/models/item/placeholder/" + name + ".json")).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> t : json.getAsJsonObject("textures").entrySet()) {
                assertTrue(t.getValue().getAsString().startsWith("minecraft:block/"), name + ": " + t.getKey());
            }
            assertTrue(json.getAsJsonArray("elements").size() > 4, name + ": its card is drawn on its screen");
        }
    }

    /** Without the pack each model has a vanilla stand-in: an item every client draws (never the missing cube). */
    @Test
    void everyModelHasAVanillaStandIn() {
        for (ClubModels.Model m : ClubModels.all()) {
            assertNotNull(m.fallback(), m.name());
            assertFalse(m.fallback() == Material.AIR, m.name() + ": something a client draws: " + m.fallback());
        }
        assertEquals(Material.ZOMBIE_HEAD, ClubModels.cabinetFor("whack_a_zombie").fallback(),
                "a cabinet's is its game's Games-screen icon");
        assertEquals(Material.GOLD_INGOT, ClubModels.trophy(1).fallback());
    }

    /**
     * The placeholders: an item definition at {@code assets/hcm/items/<name>.json} pointing at a model under
     * {@code models/item/placeholder/} (never a path the owner's models use), each valid JSON, every texture a vanilla
     * one, every box inside Minecraft's -16..32 limit.
     */
    @Test
    void thePlaceholdersAreValidAndUseOnlyVanillaTextures() {
        Map<String, String> files = ClubModels.placeholderFiles(Set.of());
        assertEquals(2 * ClubModels.all().size(), files.size(), "a definition and a model each");
        for (ClubModels.Model m : ClubModels.all()) {
            JsonObject def = JsonParser.parseString(files.get("assets/hcm/items/" + m.name() + ".json")).getAsJsonObject();
            String model = def.getAsJsonObject("model").get("model").getAsString();
            assertEquals("hcm:item/placeholder/" + m.name(), model);
            assertEquals("minecraft:model", def.getAsJsonObject("model").get("type").getAsString());
            JsonObject json = JsonParser.parseString(files.get("assets/hcm/models/item/placeholder/" + m.name() + ".json"))
                    .getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> t : json.getAsJsonObject("textures").entrySet()) {
                String tex = t.getValue().getAsString();
                assertTrue(tex.startsWith("minecraft:block/"), m.name() + ": a vanilla block-atlas texture only"
                        + " (0.41.1: on 26.x an item/ texture on a model's boxes is the missing texture): " + tex);
            }
            assertFalse(json.getAsJsonArray("elements").isEmpty(), m.name());
            for (com.google.gson.JsonElement e : json.getAsJsonArray("elements")) {
                for (String k : new String[]{"from", "to"}) {
                    for (com.google.gson.JsonElement v : e.getAsJsonObject().getAsJsonArray(k)) {
                        assertTrue(v.getAsDouble() >= -16 && v.getAsDouble() <= 32, m.name() + " " + k);
                    }
                }
            }
        }
    }

    /** A name the owner's drop-in has: HCM's placeholder for it is left out, the rest stay. */
    @Test
    void theOwnersModelsWinByLeavingOursOut() {
        Set<String> owners = PackModels.providedBy(List.of(
                "assets/hcm/items/cabinet_whack.json",
                "my-pack/assets/hcm/items/trophy_gold.json", // a zip with one extra folder
                "assets/hcm/models/item/cabinet_whack.json",
                "assets/hcm/items/placeholder/door_arcade.json", // never ours to count
                "assets/hcm/items/something_else.json",
                "assets/minecraft/items/paper.json"));
        assertEquals(Set.of("cabinet_whack", "trophy_gold"), owners);
        Map<String, String> files = ClubModels.placeholderFiles(owners);
        assertFalse(files.containsKey("assets/hcm/items/cabinet_whack.json"), "no conflict: the owner's is the only one");
        assertFalse(files.containsKey("assets/hcm/items/trophy_gold.json"));
        assertTrue(files.containsKey("assets/hcm/items/cabinet_simon.json"), "the others are still ours");
        assertEquals(2 * (ClubModels.all().size() - 2), files.size());
    }

    /**
     * 0.41.1, the self-test on the owner's drop-ins: a model whose item definition, model file or own texture is
     * missing, or that puts an item/ texture on its boxes, can't be drawn, so HCM keeps its placeholder for it.
     */
    @Test
    void theSelfTestFindsAModelThatCantBeDrawn() {
        Map<String, String> json = new java.util.HashMap<>();
        Set<String> paths = new HashSet<>();
        json.put("assets/hcm/items/cabinet_whack.json",
                "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"hcm:item/cabinet_whack\"}}");
        assertTrue(PackModels.problem("cabinet_whack", json, paths).contains("isn't there"), "no model file");
        json.put("assets/hcm/models/item/cabinet_whack.json",
                "{\"textures\":{\"0\":\"hcm:item/cabinet_whack\",\"particle\":\"#0\"},\"elements\":[]}");
        assertTrue(PackModels.problem("cabinet_whack", json, paths).contains("hcm:item/cabinet_whack"), "no texture");
        paths.add("assets/hcm/textures/item/cabinet_whack.png");
        assertEquals(null, PackModels.problem("cabinet_whack", json, paths), "all there: drawn");
        json.put("assets/hcm/models/item/cabinet_whack.json",
                "{\"textures\":{\"0\":\"minecraft:item/rotten_flesh\"},\"elements\":[]}");
        assertTrue(PackModels.problem("cabinet_whack", json, paths).contains("item texture"), "an item/ texture");
        json.put("assets/hcm/models/item/cabinet_whack.json",
                "{\"textures\":{\"0\":\"minecraft:block/target_side\"},\"elements\":[]}");
        assertEquals(null, PackModels.problem("cabinet_whack", json, paths), "a block/ texture is fine");
        Map<String, String> zipped = new java.util.HashMap<>();
        for (Map.Entry<String, String> e : json.entrySet()) {
            zipped.put("my-pack/" + e.getKey(), e.getValue());
        }
        assertEquals(null, PackModels.problem("cabinet_whack", zipped, Set.of()), "one extra folder in a zip");
        assertTrue(PackModels.problem("trophy_gold", json, paths).contains("has no"), "a name it doesn't have");
    }

    /** HCM's own placeholders pass the same self-test. */
    @Test
    void ourOwnPlaceholdersPassTheSelfTest() {
        Map<String, String> files = ClubModels.placeholderFiles(Set.of());
        for (ClubModels.Model m : ClubModels.all()) {
            assertEquals(null, PackModels.problem(m.name(), files, files.keySet()), m.name());
        }
    }
}
