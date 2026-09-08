package com.dierks.homecraft.crafting;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.item.CustomItems;
import com.dierks.homecraft.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Owns all data-driven, reloadable recipes:
 * <ul>
 *   <li>Every <b>craftable block</b> from the {@code recipes:} section (PC, Printer,
 *       Vending Machine, Pallet, Arcade, one Mailbox per colour) — registered as
 *       normal (vanilla) shaped recipes under the plugin's namespace so they craft
 *       at any crafting table and show in the recipe book.</li>
 *   <li>The <b>PC</b> recipe is additionally matched inside the Printer / legacy
 *       Workbench craft grid ({@link #matchPc}) — same config entry, same result.</li>
 * </ul>
 * The retired Mini Workbench has no recipe. Everything re-registers on {@code /hcm reload}.
 */
public final class RecipeManager {

    /** The result of a successful PC match: the item to give + per-input-slot amounts to consume. */
    public record CraftAttempt(ItemStack result, int[] consume) {
    }

    private final HomeCraftManagement plugin;
    private final PluginConfig config;
    private final CustomItems items;

    private boolean workbenchRecipeRegistered = false;
    /** Keys of the vanilla recipes currently registered (for unregister + recipe-book unlock). */
    private final List<NamespacedKey> registered = new ArrayList<>();

    public RecipeManager(HomeCraftManagement plugin, PluginConfig config, CustomItems items) {
        this.plugin = plugin;
        this.config = config;
        this.items = items;
    }

    // ---------------------------------------------------------------------
    //  Vanilla Workbench bootstrap recipe
    // ---------------------------------------------------------------------

    /**
     * (Re)register every data-driven block recipe as a vanilla shaped recipe. The
     * Mini Workbench stays retired (no recipe). Each entry's result is the fully
     * built custom item — skin, PDC type tag and (for Mailboxes) variant included —
     * so a crafted block is indistinguishable from a {@code /hcm give} one. After a
     * reload the server's recipe list is pushed to online players and the new
     * recipes are unlocked in their recipe books.
     */
    public void registerRecipes() {
        unregisterRecipes();
        int added = 0;
        List<String> skipped = new ArrayList<>();
        for (PluginConfig.BlockRecipe def : config.recipes().values()) {
            if (def.isEmpty()) {
                skipped.add(def.key());
                continue;
            }
            ItemStack result = items.forRecipeKey(def.key());
            if (result == null) {
                plugin.getLogger().warning("Recipe '" + def.key() + "' has no matching block — ignored.");
                continue;
            }
            NamespacedKey key = new NamespacedKey(plugin, recipeKeyName(def.key()));
            try {
                ShapedRecipe recipe = new ShapedRecipe(key, result);
                recipe.shape(def.shape().toArray(new String[0]));
                brand(recipe);
                for (Map.Entry<Character, RecipeChoice> e : def.ingredients().entrySet()) {
                    if (usesSymbol(def.shape(), e.getKey())) {
                        recipe.setIngredient(e.getKey(), e.getValue());
                    }
                }
                if (Bukkit.addRecipe(recipe)) {
                    registered.add(key);
                    added++;
                } else {
                    plugin.getLogger().warning("Recipe '" + def.key() + "' was rejected by the server.");
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Recipe '" + def.key() + "' is invalid: " + ex.getMessage());
            }
        }
        added += registerFilament();
        plugin.getLogger().info("Registered " + added + " block recipe(s)"
                + (skipped.isEmpty() ? "" : " (empty/disabled: " + String.join(", ", skipped) + ")")
                + ". Mini Workbench is retired — no recipe.");
        // Reload path: push the new recipe set to connected clients + unlock it for them.
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            try {
                Bukkit.updateRecipes();
            } catch (Throwable ignored) {
                // Older API without a resend hook — clients pick recipes up on relog.
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                unlockFor(p);
            }
        }
    }

    /**
     * One recipe per DyeColor from the {@code recipes.filament} template: the
     * {@code <dye>} symbol becomes that colour's dye and the result is N filament of
     * the colour. Returns how many registered.
     */
    private int registerFilament() {
        PluginConfig.FilamentRecipe def = config.filamentRecipe();
        if (def == null || def.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (org.bukkit.DyeColor color : org.bukkit.DyeColor.values()) {
            ItemStack result = new com.dierks.homecraft.mini.FilamentItems().filament(color, def.output());
            NamespacedKey key = new NamespacedKey(plugin, "filament_" + color.name().toLowerCase(Locale.ROOT));
            try {
                ShapedRecipe recipe = new ShapedRecipe(key, result);
                recipe.shape(def.shape().toArray(new String[0]));
                brand(recipe);
                boolean ok = true;
                for (Map.Entry<Character, String> e : def.ingredients().entrySet()) {
                    if (!usesSymbol(def.shape(), e.getKey())) {
                        continue;
                    }
                    String raw = e.getValue() == null ? "" : e.getValue().trim();
                    Material m;
                    if (raw.equalsIgnoreCase("<dye>") || raw.equalsIgnoreCase("dye")) {
                        m = Material.matchMaterial(color.name() + "_DYE");
                    } else {
                        m = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
                    }
                    if (m == null) {
                        plugin.getLogger().warning("Filament recipe: unknown ingredient '" + raw + "' for symbol '"
                                + e.getKey() + "' — filament recipes disabled.");
                        ok = false;
                        break;
                    }
                    recipe.setIngredient(e.getKey(), new RecipeChoice.MaterialChoice(m));
                }
                if (!ok) {
                    return added;
                }
                if (Bukkit.addRecipe(recipe)) {
                    registered.add(key);
                    added++;
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Filament recipe for " + color + " is invalid: " + ex.getMessage());
            }
        }
        return added;
    }

    /** Remove our registered recipes (called on reload and disable). */
    public void unregisterRecipes() {
        if (workbenchRecipeRegistered) {
            Bukkit.removeRecipe(Keys.WORKBENCH_RECIPE);
            workbenchRecipeRegistered = false;
        }
        for (NamespacedKey key : registered) {
            Bukkit.removeRecipe(key);
        }
        registered.clear();
    }

    /** Discover every registered HomeCraft recipe in a player's recipe book (idempotent). */
    public void unlockFor(Player player) {
        if (registered.isEmpty()) {
            return;
        }
        try {
            player.discoverRecipes(new ArrayList<>(registered));
        } catch (Throwable t) {
            plugin.getLogger().fine("Could not unlock recipes for " + player.getName() + ": " + t.getMessage());
        }
    }

    /** The keys currently registered (read-only view). */
    public List<NamespacedKey> registeredKeys() {
        return List.copyOf(registered);
    }

    /**
     * Group + category so recipe-sync bridges (JEIServerProxy) and the vanilla book file
     * ours under one visible "homecraft" group instead of dropping them as unknown.
     */
    private static void brand(ShapedRecipe recipe) {
        try {
            recipe.setGroup("homecraft");
            recipe.setCategory(org.bukkit.inventory.recipe.CraftingBookCategory.MISC);
        } catch (Throwable ignored) {
            // older API without categories — the recipe still registers
        }
    }

    /** "mailbox.light_blue" → "mailbox_light_blue" (NamespacedKey-safe). */
    private static String recipeKeyName(String configKey) {
        return configKey.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_").replace('.', '_');
    }

    private static boolean usesSymbol(List<String> shape, char sym) {
        for (String row : shape) {
            if (row.indexOf(sym) >= 0) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------
    //  PC matching (inside the Workbench GUI)
    // ---------------------------------------------------------------------

    /**
     * @param grid the 3x3 input grid, row-major (index 0..8), nulls for empties.
     * @return a {@link CraftAttempt} if the current PC recipe matches, else null.
     */
    public CraftAttempt matchPc(ItemStack[] grid) {
        PluginConfig.PcRecipe recipe = config.pc().recipe();
        if (recipe.isEmpty()) {
            return null;
        }
        return recipe.type() == PluginConfig.RecipeType.SHAPELESS
                ? matchShapeless(grid, recipe)
                : matchShaped(grid, recipe);
    }

    private CraftAttempt matchShaped(ItemStack[] grid, PluginConfig.PcRecipe recipe) {
        // Bounding box of filled grid cells.
        int minR = 3, maxR = -1, minC = 3, maxC = -1;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (grid[r * 3 + c] != null && grid[r * 3 + c].getType() != Material.AIR) {
                    minR = Math.min(minR, r);
                    maxR = Math.max(maxR, r);
                    minC = Math.min(minC, c);
                    maxC = Math.max(maxC, c);
                }
            }
        }
        if (maxR < 0) {
            return null; // empty grid
        }

        // Bounding box of the recipe pattern.
        List<String> pattern = recipe.shape();
        int pMinR = pattern.size(), pMaxR = -1, pMinC = 3, pMaxC = -1;
        for (int r = 0; r < pattern.size(); r++) {
            String row = pattern.get(r);
            for (int c = 0; c < row.length(); c++) {
                if (row.charAt(c) != ' ') {
                    pMinR = Math.min(pMinR, r);
                    pMaxR = Math.max(pMaxR, r);
                    pMinC = Math.min(pMinC, c);
                    pMaxC = Math.max(pMaxC, c);
                }
            }
        }
        if (pMaxR < 0) {
            return null; // empty pattern
        }

        if ((maxR - minR) != (pMaxR - pMinR) || (maxC - minC) != (pMaxC - pMinC)) {
            return null; // different dimensions
        }

        int[] consume = new int[9];
        for (int r = 0; r <= maxR - minR; r++) {
            for (int c = 0; c <= maxC - minC; c++) {
                int gi = (minR + r) * 3 + (minC + c);
                ItemStack cell = grid[gi];
                String prow = pattern.get(pMinR + r);
                char sym = (pMinC + c) < prow.length() ? prow.charAt(pMinC + c) : ' ';

                if (sym == ' ') {
                    if (cell != null && cell.getType() != Material.AIR) {
                        return null; // extra item where pattern is empty
                    }
                } else {
                    RecipeChoice required = recipe.ingredients().get(sym);
                    if (required == null || cell == null || cell.getType() == Material.AIR
                            || !required.test(cell)) {
                        return null;
                    }
                    consume[gi] = 1;
                }
            }
        }
        return new CraftAttempt(items.pc(), consume);
    }

    private CraftAttempt matchShapeless(ItemStack[] grid, PluginConfig.PcRecipe recipe) {
        Map<Material, Integer> required = new HashMap<>();
        for (PluginConfig.Ingredient ing : recipe.shapeless()) {
            required.merge(ing.material(), ing.amount(), Integer::sum);
        }

        Map<Material, Integer> available = new LinkedHashMap<>();
        for (ItemStack cell : grid) {
            if (cell != null && cell.getType() != Material.AIR) {
                available.merge(cell.getType(), cell.getAmount(), Integer::sum);
            }
        }

        if (!available.equals(required)) {
            return null; // must place exactly the ingredients, no more, no less
        }

        // Consume the required amount of each material across the slots holding it.
        int[] consume = new int[9];
        Map<Material, Integer> remaining = new HashMap<>(required);
        for (int i = 0; i < 9; i++) {
            ItemStack cell = grid[i];
            if (cell == null || cell.getType() == Material.AIR) {
                continue;
            }
            int need = remaining.getOrDefault(cell.getType(), 0);
            if (need <= 0) {
                continue;
            }
            int take = Math.min(cell.getAmount(), need);
            consume[i] = take;
            remaining.put(cell.getType(), need - take);
        }
        return new CraftAttempt(items.pc(), consume);
    }
}
