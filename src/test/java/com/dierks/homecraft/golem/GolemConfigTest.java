package com.dierks.homecraft.golem;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code golem:} section (0.38.4): the defaults; the bundled config.yml reads to the shipped recipe (the owner's
 * shape: three feathers, a slime ball, any of the eight copper blocks) without a WARN; an unknown material falls
 * back with a WARN; a shape that can't be a recipe falls back whole; and {@link GolemConfig#problem}'s table.
 */
class GolemConfigTest {

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = GolemConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return c;
            }
        }
    }

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(text);
        return c;
    }

    @Test
    void theDefaultsAreOnAndTheOwnersShape() {
        GolemConfig d = GolemConfig.defaults();
        assertTrue(d.enabled());
        assertTrue(d.recipeEnabled());
        assertEquals(List.of("FFF", " S ", " C "), d.shape());
        assertEquals(List.of(Material.FEATHER), d.ingredients().get('F'));
        assertEquals(List.of(Material.SLIME_BALL), d.ingredients().get('S'));
        assertEquals(GolemConfig.COPPER_BLOCKS, d.ingredients().get('C'));
        assertEquals(8, GolemConfig.COPPER_BLOCKS.size(), "every copper block: four weathers, waxed and not");
        assertEquals(List.of('F', 'S', 'C'), d.used());
        assertNull(GolemConfig.problem(d.shape(), d.ingredients().keySet()));
    }

    @Test
    void theBundledConfigReadsToTheShippedRecipeWithoutAWarn() throws Exception {
        YamlConfiguration yml = bundled();
        assertTrue(yml.getBoolean("golem.enabled"), "config.yml: the brush ships on");
        assertTrue(yml.getBoolean("golem.recipe.enabled"), "config.yml: craftable");
        List<String> warns = new ArrayList<>();
        GolemConfig c = GolemConfig.read(yml.getConfigurationSection("golem"), warns::add);
        assertEquals(List.of(), warns, "the shipped section reads clean");
        assertEquals(GolemConfig.defaults(), c, "and is the shipped recipe exactly (8 copper materials)");
        for (String name : yml.getString("golem.recipe.ingredients.C").split(",")) {
            assertNotNull(Material.matchMaterial(name.trim()), "a Material in the jar: " + name);
        }
    }

    @Test
    void aMissingSectionOrRecipeIsTheShippedOne() throws Exception {
        assertEquals(GolemConfig.defaults(), GolemConfig.read(null, w -> {
            throw new AssertionError(w);
        }));
        GolemConfig c = GolemConfig.read(yaml("golem:\n  enabled: false\n").getConfigurationSection("golem"), w -> {
            throw new AssertionError(w);
        });
        assertFalse(c.enabled());
        assertEquals(GolemConfig.DEFAULT_SHAPE, c.shape());
    }

    @Test
    void anOwnersRecipeReadsListsAndCommaStringsAlike() throws Exception {
        List<String> warns = new ArrayList<>();
        GolemConfig c = GolemConfig.read(yaml("""
                golem:
                  recipe:
                    enabled: false
                    shape: ["CIC", " S "]
                    ingredients:
                      C: [copper_block, waxed_copper_block]
                      I: "copper_ingot , copper_ingot"
                      S: slime_ball
                """).getConfigurationSection("golem"), warns::add);
        assertEquals(List.of(), warns);
        assertTrue(c.enabled(), "enabled defaults on");
        assertFalse(c.recipeEnabled(), "/hcm give only");
        assertEquals(List.of("CIC", " S "), c.shape());
        assertEquals(List.of(Material.COPPER_BLOCK, Material.WAXED_COPPER_BLOCK), c.ingredients().get('C'));
        assertEquals(List.of(Material.COPPER_INGOT), c.ingredients().get('I'), "lower case, spaces and a double");
        assertEquals(List.of(Material.SLIME_BALL), c.ingredients().get('S'));
    }

    @Test
    void anUnknownMaterialFallsBackWithAWarn() throws Exception {
        List<String> warns = new ArrayList<>();
        GolemConfig c = GolemConfig.read(yaml("""
                golem:
                  recipe:
                    ingredients:
                      F: FEATHERS
                      S: "SLIME_BALL, NOT_A_THING"
                      C: COPPER_BLOCK
                """).getConfigurationSection("golem"), warns::add);
        assertEquals(3, warns.size(), "the unknown name, F's fallback, the other unknown name: " + warns);
        assertTrue(warns.get(0).contains("FEATHERS"));
        assertTrue(warns.get(1).contains("shipped FEATHER"), warns.get(1));
        assertTrue(warns.get(2).contains("NOT_A_THING"));
        assertEquals(List.of(Material.FEATHER), c.ingredients().get('F'), "the shipped F");
        assertEquals(List.of(Material.SLIME_BALL), c.ingredients().get('S'), "the good name stays");
        assertEquals(List.of(Material.COPPER_BLOCK), c.ingredients().get('C'));
        assertEquals(GolemConfig.DEFAULT_SHAPE, c.shape());
    }

    @Test
    void aShapeThatCantBeARecipeFallsBackWhole() throws Exception {
        List<String> warns = new ArrayList<>();
        GolemConfig c = GolemConfig.read(yaml("""
                golem:
                  recipe:
                    shape: ["FF", "XXX"]
                    ingredients:
                      F: FEATHER
                      X: COPPER_BLOCK
                """).getConfigurationSection("golem"), warns::add);
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).contains("same width"), warns.get(0));
        assertEquals(GolemConfig.defaults().shape(), c.shape());
        assertEquals(GolemConfig.defaults().ingredients(), c.ingredients());
    }

    @Test
    void theProblemTable() {
        Set<Character> syms = Set.of('F', 'S', 'C');
        assertNull(GolemConfig.problem(List.of("FFF", " S ", " C "), syms));
        assertNull(GolemConfig.problem(List.of("F"), syms), "one by one is a recipe");
        assertNull(GolemConfig.problem(List.of("FS", "SC"), syms), "two by two too");
        assertEquals("1 to 3 rows", GolemConfig.problem(List.of(), syms));
        assertEquals("1 to 3 rows", GolemConfig.problem(null, syms));
        assertEquals("1 to 3 rows", GolemConfig.problem(List.of("F", "F", "F", "F"), syms));
        assertEquals("rows of 1 to 3 characters", GolemConfig.problem(List.of("FFFF"), syms));
        assertEquals("rows of 1 to 3 characters", GolemConfig.problem(List.of(""), syms));
        assertEquals("every row the same width", GolemConfig.problem(List.of("FF", "FFF"), syms));
        assertEquals("no ingredient for 'X'", GolemConfig.problem(List.of("FXF"), syms));
        assertEquals("no ingredient for 'F'", GolemConfig.problem(List.of("F"), Set.of()));
        assertEquals("all spaces", GolemConfig.problem(List.of("   ", "   "), syms));
    }
}
