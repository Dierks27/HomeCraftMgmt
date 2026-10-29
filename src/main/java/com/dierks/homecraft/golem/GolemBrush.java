package com.dierks.homecraft.golem;

import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The Copper Golem Brush itself (0.38.4): a vanilla {@link Material#BRUSH} wearing a gold name, lore, the enchanted
 * glint, UNCOMMON rarity and our tag ({@link Keys#GOLEM_BRUSH}), unbreakable so it never wears on suspicious sand
 * (and {@link GolemService} denies its use there anyway). The vanilla brush texture cannot be tinted without a
 * resource pack, so the gold name, the glint and the rarity's colour are its "different colour" (a copper texture
 * through CraftBridge's pack is a later option). A plain brush, and CraftBridge's Golem Chest Marker (its own
 * {@code craftbridge:golem_marker} tag), are not the brush. Given, never bought: not a token prize, never money.
 *
 * <p>Its recipe is a {@link ShapedRecipe} of the plugin's own ({@link #register}), registered by
 * {@link GolemService#start}, again on a reload, and removed on disable; never through {@code RecipeManager}, so
 * nothing in {@code recipes:} changes.
 */
public final class GolemBrush {

    private GolemBrush() {
    }

    /** {@code amount} brushes. */
    public static ItemStack item(int amount) {
        ItemStack item = new ItemStack(Material.BRUSH);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.of(GolemText.BRUSH_NAME));
            List<Component> lore = new ArrayList<>();
            for (String line : GolemText.LORE) {
                lore.add(Text.of(line));
            }
            meta.lore(lore);
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
            meta.setRarity(ItemRarity.UNCOMMON);
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            meta.getPersistentDataContainer().set(Keys.GOLEM_BRUSH, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        item.setAmount(Math.max(1, amount));
        return item;
    }

    /** Whether {@code item} is a Copper Golem Brush (our tag; a plain brush or another plugin's isn't). */
    public static boolean is(ItemStack item) {
        if (item == null || item.getType() != Material.BRUSH || !item.hasItemMeta() || Keys.GOLEM_BRUSH == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(Keys.GOLEM_BRUSH, PersistentDataType.BYTE);
    }

    /** Whether {@code p} holds one in the main hand (the off hand never counts: a tap is the main hand's). */
    public static boolean holdsInMainHand(Player p) {
        return is(p.getInventory().getItemInMainHand());
    }

    // ---- the recipe ------------------------------------------------------------------------------

    /**
     * (Re)register the brush's recipe under {@code key} from {@code config}: removed first, skipped while the recipe
     * is off, the shipped recipe when the configured shape can't be one (a WARN). After a reload with players on, the
     * recipe list is pushed to them and the recipe unlocked in their books, as {@code RecipeManager} does.
     */
    public static boolean register(JavaPlugin plugin, NamespacedKey key, GolemConfig config, Logger log) {
        Bukkit.removeRecipe(key);
        if (!config.recipeEnabled()) {
            return false;
        }
        List<String> shape = config.shape();
        Map<Character, List<Material>> ingredients = config.ingredients();
        String problem = GolemConfig.problem(shape, ingredients.keySet());
        if (problem != null) {
            log.warning("golem.recipe.shape is unusable - " + problem + ". Using the shipped Copper Golem Brush recipe.");
            shape = GolemConfig.DEFAULT_SHAPE;
            ingredients = GolemConfig.defaultIngredients();
        }
        try {
            ShapedRecipe recipe = new ShapedRecipe(key, item(1));
            recipe.shape(shape.toArray(new String[0]));
            try {
                recipe.setGroup("homecraft");
                recipe.setCategory(org.bukkit.inventory.recipe.CraftingBookCategory.MISC);
            } catch (Throwable ignored) {
                // older API without categories - the recipe still registers
            }
            for (Map.Entry<Character, List<Material>> e : ingredients.entrySet()) {
                if (shape.stream().anyMatch(row -> row.indexOf(e.getKey()) >= 0)) {
                    recipe.setIngredient(e.getKey(), new RecipeChoice.MaterialChoice(e.getValue()));
                }
            }
            if (!Bukkit.addRecipe(recipe)) {
                log.warning("The Copper Golem Brush recipe was rejected by the server; /hcm give golem_brush still"
                        + " hands one out.");
                return false;
            }
        } catch (RuntimeException ex) {
            log.warning("The Copper Golem Brush recipe could not be registered (" + ex.getMessage()
                    + "); /hcm give golem_brush still hands one out.");
            return false;
        }
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            try {
                Bukkit.updateRecipes();
            } catch (Throwable ignored) {
                // older API without a resend hook - clients pick it up on relog
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    p.discoverRecipes(List.of(key));
                } catch (Throwable ignored) {
                    // the book is a nicety
                }
            }
        }
        return true;
    }

    /** The recipe gone (disable, or switched off). */
    public static void unregister(NamespacedKey key) {
        try {
            Bukkit.removeRecipe(key);
        } catch (RuntimeException ignored) {
            // the server is going, or it was never there
        }
    }
}
