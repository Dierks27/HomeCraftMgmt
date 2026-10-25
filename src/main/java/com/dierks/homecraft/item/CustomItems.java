package com.dierks.homecraft.item;

import com.dierks.homecraft.block.CustomBlockType;
import com.dierks.homecraft.block.DisplayCaseVariant;
import com.dierks.homecraft.block.MailboxVariant;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.TokenPrizes;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the tagged custom-block items (Mini Workbench, PC). Identity travels
 * on the item via a PDC marker so we can recognise it on placement; the visual
 * material, name, lore, and (for the PC) head texture come from config. The
 * Firework Launcher alone is a token prize too (0.38.1).
 */
public final class CustomItems {

    private final PluginConfig config;

    public CustomItems(PluginConfig config) {
        this.config = config;
    }

    /** A Mini Workbench item, ready to place. */
    public ItemStack workbench() {
        PluginConfig.Workbench def = config.workbench();
        return tagged(def.baseBlock(), def.displayName(), def.lore(), CustomBlockType.MINI_WORKBENCH,
                config.skin(CustomBlockType.MINI_WORKBENCH));
    }

    /** A Mini Printer item, ready to place (Phase 9). */
    public ItemStack printer() {
        PluginConfig.Printer def = config.printer();
        return tagged(def.baseBlock(), def.displayName(), def.lore(), CustomBlockType.PRINTER,
                config.skin(CustomBlockType.PRINTER));
    }

    /** A PC item, ready to place. Applies the configured head texture when the base is a head. */
    public ItemStack pc() {
        PluginConfig.Pc def = config.pc();
        // A skins.pc value wins; otherwise fall back to the legacy crafting.pc.head_texture.
        String skin = config.skin(CustomBlockType.PC);
        String texture = skin.isBlank() ? def.headTexture() : skin;
        return tagged(def.baseBlock(), def.displayName(), def.lore(), CustomBlockType.PC, texture);
    }

    /** A Mini Vending Machine item, ready to place (the lower half; the upper head is auto-placed). */
    public ItemStack vendingMachine() {
        PluginConfig.BlockDef def = config.miniBlocks().vending();
        return tagged(def.material(), def.name(), List.of("&7Right-click to sell/buy a Mini."),
                CustomBlockType.MINI_VENDING_MACHINE, config.skin(CustomBlockType.MINI_VENDING_MACHINE));
    }

    /** A plain Mini Display Case item, ready to place (the default pedestal style). */
    public ItemStack displayCase() {
        return displayCase(DisplayCaseVariant.PLAIN);
    }

    /**
     * A Display Case of a pedestal style: named "&lt;Style&gt; Display Case", skinned with
     * {@code skins.display_case.<variant>}, carrying the variant in PDC so placement (and
     * the drop on break) keep the style. The case keeps this skin empty or loaded.
     */
    public ItemStack displayCase(DisplayCaseVariant variant) {
        PluginConfig.BlockDef def = config.miniBlocks().display();
        ItemStack item = tagged(def.material(), config.displayCaseName(variant),
                List.of("&7Place, then load a Mini to show it off.", "&8The Mini floats above the pedestal."),
                CustomBlockType.DISPLAY_CASE, config.displayCaseSkin(variant));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(Keys.DISPLAY_VARIANT, PersistentDataType.STRING, variant.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** @return the Display Case style an item carries (plain when untagged), or null if it isn't a case. */
    public DisplayCaseVariant displayCaseVariant(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        var pdc = item.getItemMeta().getPersistentDataContainer();
        if (!CustomBlockType.DISPLAY_CASE.name().equals(pdc.get(Keys.CUSTOM_BLOCK_TYPE, PersistentDataType.STRING))) {
            return null;
        }
        return DisplayCaseVariant.parseOrPlain(pdc.get(Keys.DISPLAY_VARIANT, PersistentDataType.STRING));
    }

    /** A Mini Auction House item, ready to place. */
    public ItemStack auctionHouse() {
        PluginConfig.BlockDef def = config.miniBlocks().auction();
        return tagged(def.material(), def.name(), List.of("&7Right-click to browse Mini auctions."),
                CustomBlockType.AUCTION_HOUSE, config.skin(CustomBlockType.AUCTION_HOUSE));
    }

    /** A wood Mailbox item, ready to place (the default variant). */
    public ItemStack mailbox() {
        return mailbox(MailboxVariant.WOOD);
    }

    /**
     * A Mailbox item of a colour variant: named "&lt;Colour&gt; Mailbox", skinned with
     * {@code skins.mailbox.<variant>}, and carrying the variant in PDC so placement
     * (and the drop on break) keep the colour.
     */
    public ItemStack mailbox(MailboxVariant variant) {
        PluginConfig.BlockDef def = config.marketplace().mailbox();
        ItemStack item = tagged(def.material(), config.mailboxName(variant),
                List.of("&7Right-click to collect deliveries."),
                CustomBlockType.MAILBOX, config.mailboxSkin(variant));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(Keys.MAILBOX_VARIANT, PersistentDataType.STRING, variant.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** @return the Mailbox variant an item carries (wood when untagged), or null if it isn't a Mailbox. */
    public MailboxVariant mailboxVariant(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        var pdc = item.getItemMeta().getPersistentDataContainer();
        String type = pdc.get(Keys.CUSTOM_BLOCK_TYPE, PersistentDataType.STRING);
        if (!CustomBlockType.MAILBOX.name().equals(type)) {
            return null;
        }
        return MailboxVariant.parseOrWood(pdc.get(Keys.MAILBOX_VARIANT, PersistentDataType.STRING));
    }

    /** A Pallet (sell box) item, ready to place. Always wears the empty-pallet skin. */
    public ItemStack pallet() {
        PluginConfig.BlockDef def = config.marketplace().pallet();
        return tagged(def.material(), def.name(), List.of("&7Place on your land, load an item,",
                "&7set a price to sell on the Marketplace."), CustomBlockType.PALLET,
                config.skin(CustomBlockType.PALLET));
    }

    /** An Arcade machine item, ready to place. */
    public ItemStack arcade() {
        PluginConfig.BlockDef def = config.arcade().block();
        return tagged(def.material(), def.name(), List.of("&7Right-click to open the Arcade.",
                "&7Spend tokens on crates, pity & scratch tickets."),
                CustomBlockType.ARCADE, config.skin(CustomBlockType.ARCADE));
    }

    /**
     * A Sound Muffler, ready to place: a fresh one. A picked-up muffler's drop carries its
     * settings on top of this (see {@code SoundMufflerService#itemFor}).
     */
    public ItemStack soundMuffler() {
        PluginConfig.BlockDef def = config.soundMuffler().block();
        return tagged(def.material(), def.name(), List.of("&7Place it, then pick what to hush:",
                "&7chickens, pistons, villagers, doors…", "&8Quieter or Silent, right where you need it."),
                CustomBlockType.SOUND_MUFFLER, config.skin(CustomBlockType.SOUND_MUFFLER));
    }

    /**
     * A Firework Launcher, ready to place (0.38.1): set up to 3 near each other and a Mega Firework Show fires from
     * them. Only tokens buy it (the Prize Counter's {@code firework_launcher}; no recipe), so it carries the
     * {@code hcm:token_prize} tag here, on every one ever made (bought, from a crate, {@code /hcm give prize}, the drop
     * when it is picked up), and never becomes money: a place and a break can't wash the tag off. A dropper (with no
     * skin) carries its placed state, facing up, so it lands pointing at the sky whichever way its placer looks.
     */
    public ItemStack fireworkLauncher() {
        PluginConfig.BlockDef def = config.launcher().block();
        String skin = config.skin(CustomBlockType.FIREWORK_LAUNCHER);
        ItemStack item = tagged(def.material(), def.name(), List.of("&7Place 3 near each other,",
                "&7then use a &dMega Firework Show&7!", "&8Only you can pick it up."),
                CustomBlockType.FIREWORK_LAUNCHER, skin);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        TokenPrizes.tag(meta, com.dierks.homecraft.arcade.MegaShow.LAUNCHER_PRIZE_ID);
        if ((skin == null || skin.isBlank()) && def.material().isBlock() && meta instanceof BlockDataMeta bdm) {
            BlockData placed = def.material().createBlockData();
            if (placed instanceof Directional d && d.getFaces().contains(BlockFace.UP)) {
                d.setFacing(BlockFace.UP);
                bdm.setBlockData(d);
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    /** An Arcade machine block (crate/scratch/pity/counter). */
    public ItemStack arcadeMachine(String key, CustomBlockType type, String lore) {
        PluginConfig.BlockDef def = config.arcade().machines().get(key);
        return tagged(def.material(), def.name(), List.of(lore), type, config.skin(type));
    }

    /**
     * The item to hand out for a recipe key from the {@code recipes:} section
     * ({@code pc}, {@code printer}, {@code vending}, {@code pallet}, {@code arcade},
     * {@code mailbox.<variant>}), or null if the key isn't craftable.
     */
    public ItemStack forRecipeKey(String key) {
        if (key == null) {
            return null;
        }
        if (key.startsWith("mailbox.")) {
            MailboxVariant v = MailboxVariant.parse(key.substring("mailbox.".length()));
            return v == null ? null : mailbox(v);
        }
        if (key.startsWith("display_case.")) {
            DisplayCaseVariant v = DisplayCaseVariant.parse(key.substring("display_case.".length()));
            return v == null ? null : displayCase(v);
        }
        return switch (key) {
            case "pc" -> pc();
            case "printer" -> printer();
            case "vending" -> vendingMachine();
            case "pallet" -> pallet();
            case "arcade" -> arcade();
            case "sound_muffler" -> soundMuffler();
            default -> null;
        };
    }

    /** Build the item for a given type from current config (used e.g. when dropping on break). */
    public ItemStack of(CustomBlockType type) {
        return switch (type) {
            case PC -> pc();
            case MINI_WORKBENCH -> workbench();
            case PRINTER -> printer();
            case MINI_VENDING_MACHINE -> vendingMachine();
            case DISPLAY_CASE -> displayCase();
            case AUCTION_HOUSE -> auctionHouse();
            case MAILBOX -> mailbox();
            case PALLET -> pallet();
            case ARCADE -> arcade();
            case CRATE_MACHINE -> arcadeMachine("crate", type, "&7Right-click to open loot crates.");
            case SCRATCH_BOOTH -> arcadeMachine("scratch", type, "&7Right-click to buy a scratch ticket.");
            case PITY_KIOSK -> arcadeMachine("pity", type, "&7Right-click: tokens → a guaranteed Rare+ Mini.");
            case TOKEN_COUNTER -> arcadeMachine("counter", type, "&7Right-click to check your tokens.");
            case SOUND_MUFFLER -> soundMuffler();
            case FIREWORK_LAUNCHER -> fireworkLauncher();
        };
    }

    private ItemStack tagged(Material material, String name, List<String> loreLines,
                             CustomBlockType type, String headTexture) {
        // A configured skin renders the block as a textured player head regardless of
        // the base material (you can't paint a head texture onto a barrel/chest). A
        // blank texture leaves the base material — and appearance — untouched.
        boolean skinned = headTexture != null && !headTexture.isBlank();
        // Pass one commits the profile on its own; pass two (below) writes name/lore/PDC
        // on a fresh meta — see Heads#base for why the order matters on the live API.
        ItemStack item = skinned ? com.dierks.homecraft.util.Heads.base(headTexture) : new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item; // should not happen for these materials
        }

        meta.displayName(Text.of(name));

        if (loreLines != null && !loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>(loreLines.size());
            for (String line : loreLines) {
                lore.add(Text.of(line));
            }
            meta.lore(lore);
        }

        meta.getPersistentDataContainer().set(Keys.CUSTOM_BLOCK_TYPE, PersistentDataType.STRING, type.name());
        item.setItemMeta(meta);
        return item;
    }
}
