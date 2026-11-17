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
                "hcm:trophy_bronze", "hcm:door_arcade", "hcm:door_trophy")), ids.toString());
        assertEquals("hcm:cabinet_whack", ClubModels.cabinetFor("whack_a_zombie").id());
        assertEquals("hcm:cabinet_tictactoe", ClubModels.cabinetFor("TIC_TAC_TOE").id());
        assertEquals("hcm:cabinet", ClubModels.cabinetFor("connect_four").id(), "any other cabinet game: the plain one");
        assertEquals("hcm:trophy_gold", ClubModels.trophy(1).id());
        assertEquals("hcm:trophy_silver", ClubModels.trophy(2).id());
        assertEquals("hcm:trophy_bronze", ClubModels.trophy(3).id());
        assertEquals("hcm:door_arcade", ClubModels.door(true).id());
        assertEquals("hcm:door_trophy", ClubModels.door(false).id());
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
                assertTrue(tex.startsWith("minecraft:block/") || tex.startsWith("minecraft:item/"),
                        m.name() + ": vanilla only: " + tex);
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
}
