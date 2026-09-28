package com.dierks.homecraft.games.world;

import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The thin Bukkit side of a saved state (spec §7.3): read a {@code Player} into a
 * {@link SavedState}, and put one back through {@link SavedStateCodec#apply} (which owns the
 * order). The item blobs are {@code ItemStack.serializeItemsAsBytes} of the whole
 * {@code getContents()} — storage, armour and off-hand, empty slots kept — and the same codec for
 * the carry.
 *
 * <p>A snapshot is decoded completely BEFORE anything about the player changes, so a blob that no
 * longer reads (an upgrade gone wrong) leaves the player exactly as they were.
 *
 * <p>Game-mode changes made here are flagged, so the session guard can tell ours from
 * Multiverse-Core re-applying a world's mode (R2.7).
 */
final class BukkitStateAdapter {

    /** Players whose game mode we are changing right now. */
    private final Set<UUID> ownModeChange = new HashSet<>();

    /** Whether the game-mode change under way for this player is one of ours. */
    boolean ownModeChange(UUID player) {
        return ownModeChange.contains(player);
    }

    /** Set a game mode as our own flagged change. */
    void gameMode(Player p, GameMode mode) {
        if (p.getGameMode() == mode) {
            return;
        }
        ownModeChange.add(p.getUniqueId());
        try {
            p.setGameMode(mode);
        } finally {
            ownModeChange.remove(p.getUniqueId());
        }
    }

    // ---- capture and restore --------------------------------------------------------------------

    SavedState capture(Player p, String sessionId, String gameId, String ref, String sessionWorld, Place from, long now) {
        PlayerInventory inv = p.getInventory();
        byte[] items = ItemStack.serializeItemsAsBytes(inv.getContents());
        List<SavedStateCodec.Effect> effects = new ArrayList<>();
        for (PotionEffect e : p.getActivePotionEffects()) {
            effects.add(new SavedStateCodec.Effect(e.getType().getKey().toString(), e.getAmplifier(),
                    e.isInfinite() ? SavedStateCodec.INFINITE : e.getDuration(), e.isAmbient(), e.hasParticles(), e.hasIcon()));
        }
        return new SavedState(p.getUniqueId(), gameId, ref, SavedState.ACTIVE, sessionId, sessionWorld, items, null,
                p.getLevel(), p.getExp(), p.getTotalExperience(), p.getHealth(), p.getFoodLevel(), p.getSaturation(),
                p.getExhaustion(), p.getFireTicks(), p.getRemainingAir(), p.getGameMode().name(), p.getAllowFlight(),
                p.isFlying(), p.getWalkSpeed(), p.getFlySpeed(), p.getAbsorptionAmount(),
                SavedStateCodec.encodeEffects(effects), from.world(), from.x(), from.y(), from.z(), from.yaw(),
                from.pitch(), now, null);
    }

    /** Decode {@code s} completely; throws (changing nothing) if it can't be read. */
    SessionCore.Restore<Player> prepare(SavedState s) {
        if (s.items() == null) {
            throw new IllegalArgumentException("no item blob");
        }
        ItemStack[] contents = ItemStack.deserializeItemsFromBytes(s.items());
        List<SavedStateCodec.Effect> effects = SavedStateCodec.decodeEffects(s.effects());
        return player -> SavedStateCodec.apply(s, effects, new Body(player, contents));
    }

    /** ADVENTURE first, then an empty, healthy, fed player with no effects or XP. */
    void clearForGame(Player p) {
        SavedStateCodec.clearForGame(new Body(p, null));
    }

    /** The {@link SavedStateCodec.Body} over a live player. */
    private final class Body implements SavedStateCodec.Body {

        private final Player p;
        private final ItemStack[] contents;

        Body(Player p, ItemStack[] contents) {
            this.p = p;
            this.contents = contents;
        }

        @Override
        public void gameMode(String mode) {
            GameMode m;
            try {
                m = GameMode.valueOf(mode.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                m = GameMode.SURVIVAL;
            }
            BukkitStateAdapter.this.gameMode(p, m);
        }

        @Override
        public void clearEffects() {
            for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) {
                p.removePotionEffect(e.getType());
            }
        }

        @Override
        public void addEffect(SavedStateCodec.Effect e) {
            NamespacedKey key = NamespacedKey.fromString(e.key());
            PotionEffectType type = key == null ? null : Registry.MOB_EFFECT.get(key);
            if (type != null) {
                p.addPotionEffect(new PotionEffect(type, e.duration(), e.amplifier(), e.ambient(), e.particles(), e.icon()));
            }
        }

        @Override
        public double maxHealth() {
            AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
            return a == null ? 20.0 : Math.max(1.0, a.getValue());
        }

        @Override
        public void health(double health) {
            p.setHealth(Math.max(0.5, Math.min(health, maxHealth())));
        }

        @Override
        public double maxAbsorption() {
            AttributeInstance a = p.getAttribute(Attribute.MAX_ABSORPTION);
            return a == null ? 0.0 : Math.max(0.0, a.getValue());
        }

        @Override
        public void absorption(double amount) {
            p.setAbsorptionAmount(Math.max(0.0, Math.min(amount, maxAbsorption())));
        }

        @Override
        public void food(int level, float saturation, float exhaustion) {
            p.setFoodLevel(level);
            p.setSaturation(saturation);
            p.setExhaustion(exhaustion);
        }

        @Override
        public void xp(int level, float progress, int total) {
            p.setLevel(level);
            p.setExp(progress);
            p.setTotalExperience(total);
        }

        @Override
        public void speeds(float walk, float fly) {
            p.setWalkSpeed(walk);
            p.setFlySpeed(fly);
        }

        @Override
        public void flight(boolean allowFlight, boolean flying) {
            p.setAllowFlight(allowFlight);
            if (allowFlight) {
                p.setFlying(flying);
            }
        }

        @Override
        public void fire(int ticks) {
            p.setFireTicks(ticks);
        }

        @Override
        public void air(int ticks) {
            p.setRemainingAir(Math.max(-20, Math.min(ticks, p.getMaximumAir())));
        }

        @Override
        public void contents() {
            PlayerInventory inv = p.getInventory();
            int size = inv.getContents().length;
            ItemStack[] fitted = new ItemStack[size];
            List<ItemStack> overflow = new ArrayList<>();
            for (int i = 0; i < contents.length; i++) {
                if (i < size) {
                    fitted[i] = empty(contents[i]) ? null : contents[i];
                } else if (!empty(contents[i])) {
                    overflow.add(contents[i]); // a snapshot from a bigger inventory: never lose a slot
                }
            }
            inv.setContents(fitted);
            if (!overflow.isEmpty()) {
                for (ItemStack left : inv.addItem(overflow.toArray(new ItemStack[0])).values()) {
                    p.getWorld().dropItem(p.getLocation(), left);
                }
            }
        }

        @Override
        public void clearContents() {
            p.setItemOnCursor(null);
            PlayerInventory inv = p.getInventory();
            inv.setContents(new ItemStack[inv.getContents().length]);
        }
    }

    // ---- items ------------------------------------------------------------------------------------

    static boolean empty(ItemStack item) {
        return item == null || item.isEmpty();
    }

    /** The player's own 2x2 crafting grid, if their own inventory view is the open one. */
    static CraftingInventory grid(Player p) {
        InventoryView view = p.getOpenInventory();
        if (view.getType() == InventoryType.CRAFTING && view.getTopInventory() instanceof CraftingInventory grid) {
            return grid;
        }
        return null;
    }

    static boolean handsFree(Player p) {
        if (!empty(p.getItemOnCursor())) {
            return false;
        }
        CraftingInventory grid = grid(p);
        if (grid != null) {
            for (ItemStack i : grid.getMatrix()) {
                if (!empty(i)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Empty the cursor, the crafting grid and every slot; return copies of what wasn't a kit item. */
    static List<ItemStack> takeExtras(Player p) {
        List<ItemStack> out = new ArrayList<>();
        ItemStack cursor = p.getItemOnCursor();
        if (!empty(cursor)) {
            if (!KitItems.isKit(cursor)) {
                out.add(cursor.clone());
            }
            p.setItemOnCursor(null);
        }
        CraftingInventory grid = grid(p);
        if (grid != null) {
            ItemStack[] matrix = grid.getMatrix();
            for (ItemStack i : matrix) {
                if (!empty(i) && !KitItems.isKit(i)) {
                    out.add(i.clone());
                }
            }
            grid.setMatrix(new ItemStack[matrix.length]);
        }
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        for (ItemStack i : contents) {
            if (!empty(i) && !KitItems.isKit(i)) {
                out.add(i.clone());
            }
        }
        inv.setContents(new ItemStack[contents.length]);
        return out;
    }

    /** Throw away the cursor and the crafting grid (an overwrite restore), then close the view. */
    static void discardHeld(Player p) {
        p.setItemOnCursor(null);
        CraftingInventory grid = grid(p);
        if (grid != null) {
            grid.setMatrix(new ItemStack[grid.getMatrix().length]);
        }
        p.closeInventory();
    }

    /** Remove every kit item from the inventory, the cursor, the crafting grid and the ender chest. */
    static int stripKit(Player p) {
        int removed = 0;
        if (KitItems.isKit(p.getItemOnCursor())) {
            removed += p.getItemOnCursor().getAmount();
            p.setItemOnCursor(null);
        }
        CraftingInventory grid = grid(p);
        if (grid != null) {
            removed += strip(grid);
        }
        removed += strip(p.getInventory());
        removed += strip(p.getEnderChest());
        return removed;
    }

    private static int strip(Inventory inventory) {
        int removed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (KitItems.isKit(contents[slot])) {
                removed += contents[slot].getAmount();
                inventory.setItem(slot, null);
            }
        }
        return removed;
    }

    /** Add items to the inventory; return what didn't fit. */
    static List<ItemStack> give(Player p, List<ItemStack> items) {
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack item : items) {
            if (empty(item)) {
                continue;
            }
            left.addAll(p.getInventory().addItem(item.clone()).values());
        }
        return left;
    }

    /**
     * How many of {@code items}, from the first, surely fit in the player's storage now. Each one
     * needs whole empty storage slots (more than one for a stack over its maximum, as addItem
     * splits it); topping up a partial stack is not counted, so the answer is never too high and
     * {@link #give} will take all of them. Nothing changes.
     */
    static int room(Player p, List<ItemStack> items) {
        PlayerInventory inv = p.getInventory();
        int free = 0;
        for (ItemStack i : inv.getStorageContents()) {
            if (empty(i)) {
                free++;
            }
        }
        int fits = 0;
        for (ItemStack item : items) {
            int need = 0;
            if (!empty(item)) {
                int max = Math.max(1, Math.min(item.getMaxStackSize(), inv.getMaxStackSize()));
                need = (item.getAmount() + max - 1) / max;
            }
            if (need > free) {
                break;
            }
            free -= need;
            fits++;
        }
        return fits;
    }

    static byte[] encode(List<ItemStack> items) {
        List<ItemStack> real = new ArrayList<>();
        for (ItemStack i : items) {
            if (!empty(i)) {
                real.add(i);
            }
        }
        return real.isEmpty() ? null : ItemStack.serializeItemsAsBytes(real);
    }

    static List<ItemStack> decode(byte[] blob) {
        List<ItemStack> out = new ArrayList<>();
        if (blob == null || blob.length == 0) {
            return out;
        }
        for (ItemStack i : ItemStack.deserializeItemsFromBytes(blob)) {
            if (!empty(i)) {
                out.add(i);
            }
        }
        return out;
    }

    /** "2x DIAMOND" or "1x PLAYER_HEAD (Mini #42)", for the log. */
    static String describe(ItemStack item) {
        if (empty(item)) {
            return "nothing";
        }
        String out = item.getAmount() + "x " + item.getType().name();
        try {
            if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                String name = PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
                out += " (" + Text.plain(name) + ")";
            }
        } catch (RuntimeException ignored) {
            // the type and amount are enough
        }
        return out;
    }
}
