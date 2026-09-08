package com.dierks.homecraft.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Keys;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/**
 * Startup self-test for the item builders on the live API: builds one Card and one
 * Legendary Mini and checks that lore, the PDC tags and the enchantment glint all
 * survive the meta round-trip. Any failure is logged as a warning (never fatal); a
 * missing glint switches {@link MiniItems#glintFallback} on so Minis still shine.
 */
public final class ItemSelfTest {

    private ItemSelfTest() {
    }

    public static void run(HomeCraftManagement plugin) {
        MiniService minis = plugin.miniService();
        RarityStyle legendary = minis.style(Rarity.LEGENDARY);
        plugin.getLogger().info("Self-test: rarity_styles glint — LEGENDARY=" + legendary.glint()
                + ", EPIC=" + minis.style(Rarity.EPIC).glint() + ", RARE=" + minis.style(Rarity.RARE).glint());

        MiniDef sample = minis.catalog().stream().findFirst().orElse(null);
        MiniDef def = sample != null ? sample : new MiniDef("selftest", "Self Test", "Self Test", "MISC",
                Rarity.LEGENDARY, MiniType.HEAD, "", 5, 1, false);

        // 1) A Card must keep its lore and CARD_ID.
        try {
            ItemStack card = new CardItems().card(def, CardSpec.defaultsFor(def.rarity()), minis.style(def.rarity()));
            ItemMeta meta = roundTrip(card);
            boolean loreOk = meta != null && meta.hasLore() && meta.lore() != null && !meta.lore().isEmpty();
            boolean idOk = meta != null
                    && meta.getPersistentDataContainer().has(Keys.CARD_ID, PersistentDataType.STRING);
            if (loreOk && idOk) {
                plugin.getLogger().info("Self-test: Card lore + CARD_ID OK (" + meta.lore().size() + " lore lines).");
            } else {
                plugin.getLogger().warning("Self-test FAILED: a freshly built Card lost its "
                        + (!loreOk ? "lore" : "") + (!loreOk && !idOk ? " and " : "") + (!idOk ? "CARD_ID tag" : "")
                        + " after setItemMeta — Cards will not print. Report this with the server version.");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Self-test: Card build threw " + t);
        }

        // 2) A Legendary Mini must glint (rarity_styles.LEGENDARY.glint) after the round-trip.
        try {
            MiniDef leg = new MiniDef(def.id(), def.name(), def.series(), def.category(), Rarity.LEGENDARY,
                    MiniType.HEAD, def.texture(), 5, 1, false, List.of());
            RarityStyle style = new RarityStyle(legendary.pane(), NamedTextColor.GOLD, true,
                    legendary.defaultCap(), legendary.defaultPrice());
            ItemStack mini = new MiniItems().minted(leg, style, 1, UUID.randomUUID(), Grade.MINT, null);
            ItemMeta meta = roundTrip(mini);
            boolean glintOk = meta != null && meta.hasEnchantmentGlintOverride()
                    && Boolean.TRUE.equals(meta.getEnchantmentGlintOverride());
            boolean loreOk = meta != null && meta.hasLore();
            if (glintOk && loreOk) {
                plugin.getLogger().info("Self-test: Legendary Mini glint + lore OK.");
            } else if (!glintOk) {
                MiniItems.glintFallback = true;
                plugin.getLogger().warning("Self-test: enchantment_glint_override did not survive on a "
                        + Material.PLAYER_HEAD + " — enabling the hidden-enchant glint fallback for Minis.");
            } else {
                plugin.getLogger().warning("Self-test FAILED: a minted Mini lost its lore after setItemMeta.");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Self-test: Mini build threw " + t);
        }
    }

    /** Re-read the meta through a fresh copy, the way an inventory slot would. */
    private static ItemMeta roundTrip(ItemStack item) {
        ItemStack copy = new ItemStack(item.getType());
        copy.setItemMeta(item.getItemMeta());
        return copy.getItemMeta();
    }
}
