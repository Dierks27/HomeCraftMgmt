package com.dierks.homecraft.mini;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.dierks.homecraft.util.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds Mini items: textured heads styled by <b>rarity</b> (name colour + glint
 * always come from the rarity palette), with the <b>grade</b> shown as a star
 * suffix on the name and in lore — never as a competing colour. Shiny is an
 * independent finish (extra glint + "✦ Shiny"). A minted Mini carries a unique
 * per-copy id in its PDC (the anti-dupe tag) plus its type id, mint number, grade
 * and finish; a preview icon is a cosmetic-only version for the Museum GUI.
 */
public final class MiniItems {

    /** Render revision stamped on every minted Mini; bump when the name/lore layout changes. */
    public static final int RENDER_VERSION = 2;

    /** A real, minted Mini item given to a player (uniquely tagged); Standard grade. */
    public ItemStack minted(MiniDef def, RarityStyle style, long mintNumber, UUID uid) {
        return minted(def, style, mintNumber, uid, Grade.STANDARD, null);
    }

    /**
     * A real, minted Mini: the {@code grade} adds its star suffix and a lore line, a
     * {@code finish} (e.g. SHINY) adds an extra glint. Still carries the anti-dupe
     * uid + type id + mint number.
     */
    public ItemStack minted(MiniDef def, RarityStyle style, long mintNumber, UUID uid, Grade grade, String finish) {
        ItemStack item = baseItem(def);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            boolean shiny = isShiny(finish);
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(Keys.MINI_ID, PersistentDataType.STRING, def.id());
            pdc.set(Keys.MINI_UID, PersistentDataType.STRING, uid.toString());
            pdc.set(Keys.MINI_MINT, PersistentDataType.LONG, mintNumber);
            pdc.set(Keys.MINI_GRADE, PersistentDataType.STRING, grade.name());
            if (shiny) {
                pdc.set(Keys.MINI_FINISH, PersistentDataType.STRING, "SHINY");
            }
            pdc.set(Keys.MINI_RENDER, PersistentDataType.INTEGER, RENDER_VERSION);
            render(meta, def, style, mintNumber, grade, shiny);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Re-draw an existing Mini's name/lore/glint from its PDC — migrates items minted
     * under the retired five-grade ladder (Gray…Gold → Standard/Graded/Mint) and any
     * older render layout. Mutates {@code item} in place.
     *
     * @return true if the item was re-rendered.
     */
    public boolean refresh(ItemStack item, MiniDef def, RarityStyle style) {
        if (item == null || !item.hasItemMeta() || def == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Integer version = null;
        try {
            version = pdc.get(Keys.MINI_RENDER, PersistentDataType.INTEGER);
        } catch (Throwable ignored) {
            // absent or stored oddly — treat as legacy
        }
        String rawGrade = pdc.get(Keys.MINI_GRADE, PersistentDataType.STRING);
        if (version != null && version >= RENDER_VERSION && !Grade.isLegacyName(rawGrade)) {
            return false;
        }
        Grade grade = Grade.parse(rawGrade);
        boolean shiny = isShiny(pdc.get(Keys.MINI_FINISH, PersistentDataType.STRING));
        long mint = readMint(pdc);
        pdc.set(Keys.MINI_GRADE, PersistentDataType.STRING, grade.name());
        pdc.set(Keys.MINI_RENDER, PersistentDataType.INTEGER, RENDER_VERSION);
        render(meta, def, style, mint, grade, shiny);
        item.setItemMeta(meta);
        return true;
    }

    /** The grade a minted item carries (legacy names mapped); STANDARD for anything unknown. */
    public Grade gradeOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Grade.STANDARD;
        }
        return Grade.parse(item.getItemMeta().getPersistentDataContainer()
                .get(Keys.MINI_GRADE, PersistentDataType.STRING));
    }

    /** True if the minted item carries the Shiny finish. */
    public boolean isShiny(ItemStack item) {
        return item != null && item.hasItemMeta()
                && isShiny(item.getItemMeta().getPersistentDataContainer()
                .get(Keys.MINI_FINISH, PersistentDataType.STRING));
    }

    /**
     * A cosmetic-only Museum icon (no unique id — not a real Mini): live minted/cap,
     * circulation and the Standard→Mint value appraisal. The Museum is browse-only, so
     * the action line points at the Printer rather than a purchase.
     */
    public ItemStack preview(MiniDef def, RarityStyle style, long minted, long circulation, String valueText) {
        ItemStack item = baseItem(def);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(def.name(), style.nameColor()).decoration(TextDecoration.ITALIC, false));
            if (style.glint()) {
                meta.setEnchantmentGlintOverride(true);
            }
            List<Component> lore = new ArrayList<>();
            lore.add(line("Type: ", def.category(), NamedTextColor.GRAY));
            lore.add(line("Series: ", def.series(), NamedTextColor.GRAY));
            lore.add(line("Rarity: ", def.rarity().name(), style.nameColor()));
            lore.add(line("Minted: ", minted + (def.uncapped() ? " (uncapped)" : " / " + def.cap()), NamedTextColor.GRAY));
            lore.add(line("In circulation: ", Long.toString(circulation), NamedTextColor.GRAY));
            if (valueText != null && !valueText.isBlank()) {
                lore.add(Component.text("Est. value: ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(valueText, NamedTextColor.AQUA))
                        .decoration(TextDecoration.ITALIC, false));
            }
            if (!def.tags().isEmpty()) {
                lore.add(line("Drops from: ", String.join(", ", def.tags()), NamedTextColor.GRAY));
            }
            lore.add(Component.empty());
            boolean soldOut = !def.uncapped() && minted >= def.cap();
            lore.add((soldOut
                    ? Component.text("Minted out — trade only", NamedTextColor.RED)
                    : Component.text("Printed from a Card at a Printer", NamedTextColor.YELLOW))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Click for details", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** @return the Mini catalog id an item represents, or null if it isn't a Mini. */
    public String miniIdOf(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null
                : meta.getPersistentDataContainer().get(Keys.MINI_ID, PersistentDataType.STRING);
    }

    // ---- rendering ---------------------------------------------------------

    /** Name (rarity colour + grade stars), glint (rarity, or Shiny), and the provenance lore. */
    private void render(ItemMeta meta, MiniDef def, RarityStyle style, long mintNumber, Grade grade, boolean shiny) {
        meta.displayName(Component.text(def.name() + " " + grade.symbol(), style.nameColor())
                .decoration(TextDecoration.ITALIC, false));
        meta.setEnchantmentGlintOverride(style.glint() || shiny ? Boolean.TRUE : null);
        meta.lore(lore(def, style, mintNumber, grade, shiny));
    }

    private ItemStack baseItem(MiniDef def) {
        Material material = def.type() == MiniType.ARMOR_STAND ? Material.ARMOR_STAND : Material.PLAYER_HEAD;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (material == Material.PLAYER_HEAD && meta instanceof SkullMeta skull
                && def.texture() != null && !def.texture().isBlank()) {
            try {
                PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                profile.setProperty(new ProfileProperty("textures", def.texture()));
                skull.setPlayerProfile(profile);
            } catch (Throwable t) {
                Bukkit.getLogger().warning("[HomeCraft] Failed to apply Mini texture for " + def.id() + ": " + t.getMessage());
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    private List<Component> lore(MiniDef def, RarityStyle style, long mintNumber, Grade grade, boolean shiny) {
        List<Component> lore = new ArrayList<>();
        lore.add(line("Type: ", def.category(), NamedTextColor.GRAY));
        lore.add(line("Series: ", def.series(), NamedTextColor.GRAY));
        lore.add(line("Rarity: ", def.rarity().name(), style.nameColor()));
        lore.add(Component.text("Grade: ", NamedTextColor.DARK_GRAY)
                .append(Component.text(grade.symbol() + " " + grade.display(), NamedTextColor.WHITE))
                .append(shiny ? Component.text("  ✦ Shiny", NamedTextColor.WHITE) : Component.empty())
                .decoration(TextDecoration.ITALIC, false));
        lore.add(line("Mint #", mintNumber + (def.uncapped() ? "" : " of " + def.cap()), NamedTextColor.GRAY));
        return lore;
    }

    private Component line(String label, String value, NamedTextColor valueColor) {
        return Component.text(label, NamedTextColor.DARK_GRAY)
                .append(Component.text(value, valueColor))
                .decoration(TextDecoration.ITALIC, false);
    }

    private static boolean isShiny(String finish) {
        return finish != null && finish.equalsIgnoreCase("SHINY");
    }

    private static long readMint(PersistentDataContainer pdc) {
        try {
            Long l = pdc.get(Keys.MINI_MINT, PersistentDataType.LONG);
            if (l != null) {
                return l;
            }
        } catch (Throwable ignored) {
            // narrower numeric type — try int
        }
        try {
            Integer i = pdc.get(Keys.MINI_MINT, PersistentDataType.INTEGER);
            if (i != null) {
                return i;
            }
        } catch (Throwable ignored) {
            // give up
        }
        return 0;
    }
}
