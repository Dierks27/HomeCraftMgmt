package com.dierks.homecraft.crafting;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.item.CustomItems;
import com.dierks.homecraft.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns all data-driven, reloadable recipes:
 * <ul>
 *   <li>The <b>Mini Workbench</b> bootstrap recipe — a normal (vanilla) shaped
 *       recipe, since you need a way to obtain a bench before you have one.</li>
 *   <li>The <b>PC</b> recipe — matched inside the Workbench GUI so we can enforce
 *       our own rules (and, later, mint caps). Never a vanilla recipe.</li>
 * </ul>
 * Both are empty by default and re-read on {@code /hcm reload}.
 */
public final class RecipeManager {

    /** The result of a successful PC match: the item to give + per-input-slot amounts to consume. */
    public record CraftAttempt(ItemStack result, int[] consume) {
    }

    private final HomeCraftManagement plugin;
    private final PluginConfig config;
    private final CustomItems items;

    private boolean workbenchRecipeRegistered = false;

    public RecipeManager(HomeCraftManagement plugin, PluginConfig config, CustomItems items) {
        this.plugin = plugin;
        this.config = config;
        this.items = items;
    }

    // ---------------------------------------------------------------------
    //  Vanilla Workbench bootstrap recipe
    // ---------------------------------------------------------------------

    /**
     * (Re)register data-driven recipes. Phase 9 retires the Mini Workbench: it is no
     * longer craftable (Minis now come from Cards + a Printer), so any previously
     * registered Workbench recipe is removed and none is added. Existing placed
     * benches keep working as a light PC-craft station so placements migrate
     * gracefully. The PC recipe is still matched inside that craft GUI (see
     * {@link #matchPc}).
     */
    public void registerRecipes() {
        unregisterRecipes();
        plugin.getLogger().info("Mini Workbench is retired (Phase 9) — no longer craftable; "
                + "print Minis from Cards at a Printer.");
    }

    /** Remove our registered recipes (called on reload and disable). */
    public void unregisterRecipes() {
        if (workbenchRecipeRegistered) {
            Bukkit.removeRecipe(Keys.WORKBENCH_RECIPE);
            workbenchRecipeRegistered = false;
        }
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
                    Material required = recipe.ingredients().get(sym);
                    if (required == null || cell == null || cell.getType() != required) {
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
