package com.dierks.homecraft.golem;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The {@code golem:} section of config.yml (0.38.4): the switch, and the Copper Golem Brush's recipe (the shape's
 * rows and the material(s) each symbol takes). Read once at start and again on {@code /hcm reload}; anything
 * unusable falls back to the shipped value with a WARN line each, so the brush is always craftable as shipped.
 */
public record GolemConfig(boolean enabled, boolean recipeEnabled, List<String> shape,
                          Map<Character, List<Material>> ingredients) {

    /** Three feathers across the top, a slime ball in the middle, any copper block below (the owner's shape). */
    public static final List<String> DEFAULT_SHAPE = List.of("FFF", " S ", " C ");
    /** Every copper block, weathered or waxed: "any" copper block (the owner: "lots of copper"). */
    public static final List<Material> COPPER_BLOCKS = List.of(Material.COPPER_BLOCK, Material.EXPOSED_COPPER,
            Material.WEATHERED_COPPER, Material.OXIDIZED_COPPER, Material.WAXED_COPPER_BLOCK,
            Material.WAXED_EXPOSED_COPPER, Material.WAXED_WEATHERED_COPPER, Material.WAXED_OXIDIZED_COPPER);

    public GolemConfig {
        shape = List.copyOf(shape);
        Map<Character, List<Material>> copy = new LinkedHashMap<>();
        ingredients.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        ingredients = Collections.unmodifiableMap(copy);
    }

    /** On, with the shipped recipe. */
    public static GolemConfig defaults() {
        return new GolemConfig(true, true, DEFAULT_SHAPE, defaultIngredients());
    }

    static Map<Character, List<Material>> defaultIngredients() {
        Map<Character, List<Material>> m = new LinkedHashMap<>();
        m.put('F', List.of(Material.FEATHER));
        m.put('S', List.of(Material.SLIME_BALL));
        m.put('C', COPPER_BLOCKS);
        return m;
    }

    /**
     * Read the {@code golem} section ({@code c}; null is the shipped section). An ingredient value is a Material
     * name, or several separated by commas (any of them), or a list of names; an unknown name is dropped with a
     * WARN, a symbol left with none takes its shipped list (or is dropped), and a shape that can't be a recipe
     * ({@link #problem}) falls back to the shipped recipe whole.
     */
    public static GolemConfig read(ConfigurationSection c, Consumer<String> warn) {
        if (c == null) {
            return defaults();
        }
        boolean enabled = c.getBoolean("enabled", true);
        ConfigurationSection recipe = c.getConfigurationSection("recipe");
        if (recipe == null) {
            return new GolemConfig(enabled, true, DEFAULT_SHAPE, defaultIngredients());
        }
        boolean recipeEnabled = recipe.getBoolean("enabled", true);
        List<String> shape = recipe.isList("shape") ? recipe.getStringList("shape") : DEFAULT_SHAPE;
        Map<Character, List<Material>> shipped = defaultIngredients();
        Map<Character, List<Material>> ingredients = new LinkedHashMap<>();
        ConfigurationSection ing = recipe.getConfigurationSection("ingredients");
        if (ing != null) {
            for (String key : ing.getKeys(false)) {
                if (key.length() != 1 || key.charAt(0) == ' ') {
                    warn.accept("golem.recipe.ingredients." + key + ": a symbol is one character - ignored.");
                    continue;
                }
                char sym = key.charAt(0);
                List<Material> found = new ArrayList<>();
                for (String name : names(ing, key)) {
                    Material m = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
                    if (m == null) {
                        warn.accept("golem.recipe.ingredients." + key + ": unknown material '" + name + "' - skipped.");
                    } else if (!found.contains(m)) {
                        found.add(m);
                    }
                }
                if (found.isEmpty()) {
                    List<Material> fallback = shipped.get(sym);
                    if (fallback == null) {
                        warn.accept("golem.recipe.ingredients." + key + ": nothing usable - the symbol is dropped.");
                        continue;
                    }
                    warn.accept("golem.recipe.ingredients." + key + ": nothing usable - using the shipped "
                            + fallback.get(0).name() + (fallback.size() > 1 ? " (and the other copper blocks)" : "") + ".");
                    found = fallback;
                }
                ingredients.put(sym, found);
            }
        }
        if (ingredients.isEmpty()) {
            ingredients = shipped;
        }
        String problem = problem(shape, ingredients.keySet());
        if (problem != null) {
            warn.accept("golem.recipe.shape is unusable - " + problem + " (found " + shape + " with ingredients "
                    + ingredients.keySet() + "). Using the shipped Copper Golem Brush recipe.");
            shape = DEFAULT_SHAPE;
            ingredients = shipped;
        }
        return new GolemConfig(enabled, recipeEnabled, shape, ingredients);
    }

    /** The names under one symbol: a list, or one string split on commas. */
    private static List<String> names(ConfigurationSection ing, String key) {
        List<String> out = new ArrayList<>();
        if (ing.isList(key)) {
            for (String s : ing.getStringList(key)) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
            return out;
        }
        String raw = ing.getString(key, "");
        for (String s : raw.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    /**
     * Why {@code shape} with these {@code symbols} can't be a recipe, or null: 1 to 3 rows, 1 to 3 characters each,
     * every row the same width, every non-space symbol among the ingredients, at least one non-space symbol.
     */
    public static String problem(List<String> shape, Set<Character> symbols) {
        if (shape == null || shape.isEmpty() || shape.size() > 3) {
            return "1 to 3 rows";
        }
        int width = -1;
        boolean any = false;
        for (String row : shape) {
            if (row == null || row.isEmpty() || row.length() > 3) {
                return "rows of 1 to 3 characters";
            }
            if (width >= 0 && row.length() != width) {
                return "every row the same width";
            }
            width = row.length();
            for (char ch : row.toCharArray()) {
                if (ch == ' ') {
                    continue;
                }
                any = true;
                if (symbols == null || !symbols.contains(ch)) {
                    return "no ingredient for '" + ch + "'";
                }
            }
        }
        return any ? null : "all spaces";
    }

    /** The symbols the shape uses, in order of first use. */
    public List<Character> used() {
        List<Character> out = new ArrayList<>();
        for (String row : shape) {
            for (char ch : row.toCharArray()) {
                if (ch != ' ' && !out.contains(ch)) {
                    out.add(ch);
                }
            }
        }
        return out;
    }
}
