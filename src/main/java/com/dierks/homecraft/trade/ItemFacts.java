package com.dierks.homecraft.trade;

import com.dierks.homecraft.arcade.Collectors;
import com.dierks.homecraft.arcade.Keepers;
import com.dierks.homecraft.arcade.PrizeService;
import com.dierks.homecraft.arcade.candy.CandyItems;
import com.dierks.homecraft.courier.CourierPackage;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.TokenPrizes;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the trade window needs to know about one item (SHOP-V2-SPEC §4.9), read once from the real stack, so the
 * rules ({@link TradeRules}) run on plain facts and are tested without a server.
 *
 * <p><b>It fails closed</b> (D36), with {@link TokenPrizes}' own reader and depth limit: a container whose contents
 * can't be read, one that holds anything past {@link TokenPrizes#MAX_DEPTH}, and an item whose tags can't be read
 * are all {@link #unread}, which the rules refuse ("Too many bags inside bags") and T3 counts as carrying a prize.
 * Everything a container holds is read too ({@link #inside}), so a shulker box with a kit item or a Keeper in it is
 * refused with the inner reason, and every Mini in a moved stack has its owner moved, at any depth.
 *
 * <p>It also builds the window's pictures of offered stacks ({@link #ghost}): a ghost is never the real stack.
 *
 * @param name        what the item is called (its own name, or the material's), for messages and summaries
 * @param material    the material's name, for {@code trade.refuse_items}
 * @param amount      the stack's size
 * @param kit         a world game's kit item ({@link KitItems#isKit})
 * @param prizeId     the {@code hcm:token_prize} id, or null
 * @param prize       {@link TokenPrizes#is}: bought or won with tokens, or a kit item
 * @param bound       {@link PrizeService#bound}: a key that stays with one player (a rented look, a Keeper)
 * @param courier     a Courier package
 * @param displayOnly a Museum display copy ({@code Keys.MINI_DISPLAY_ONLY})
 * @param candyBag    the Trick-or-Treat Bag
 * @param ghost       one of the window's own pictures ({@code hcm:trade_ghost})
 * @param keys        every data key on it, as {@code namespace:key} (lower case), for {@code pdc:} refusals
 * @param miniUid     a Mini's per-copy id, or null
 * @param miniId      a Mini's catalog id, or null
 * @param unread      its tags or its contents couldn't be read, or it holds something too deep to open
 * @param inside      what it holds (a shulker box's or a bundle's items), each read the same way
 */
public record ItemFacts(String name, String material, int amount, boolean kit, String prizeId, boolean prize,
                        boolean bound, boolean courier, boolean displayOnly, boolean candyBag, boolean ghost,
                        Set<String> keys, String miniUid, String miniId, boolean unread, List<ItemFacts> inside) {

    /** The plugin's own data namespace (the {@code homecraftmanagement:} keys). */
    static final String PLUGIN_NAMESPACE = "homecraftmanagement";
    /** {@code hcm:bound_to} as {@link #keys} spells it. */
    static final String BOUND_TO_KEY = "hcm:bound_to";
    /** At most this many lines list what a container's ghost holds. */
    static final int INSIDE_LINES = 8;

    public ItemFacts {
        name = name == null ? "" : name;
        material = material == null ? "" : material;
        keys = keys == null ? Set.of() : Set.copyOf(keys);
        inside = inside == null ? List.of() : List.copyOf(inside);
    }

    /** A plain item with nothing of ours on it (tests, and the base the reader fills in). */
    public static ItemFacts plain(String name, String material, int amount) {
        return new ItemFacts(name, material, amount, false, null, false, false, false, false, false, false,
                Set.of(), null, null, false, List.of());
    }

    /** These facts holding {@code inside} instead. */
    public ItemFacts holding(List<ItemFacts> inside) {
        return new ItemFacts(name, material, amount, kit, prizeId, prize, bound, courier, displayOnly, candyBag,
                ghost, keys, miniUid, miniId, unread, inside);
    }

    /** These facts marked unread (its contents or tags couldn't be read). */
    public ItemFacts unreadable() {
        return new ItemFacts(name, material, amount, kit, prizeId, prize, bound, courier, displayOnly, candyBag,
                ghost, keys, miniUid, miniId, true, inside);
    }

    /** A Keeper: a bound key whose prize id is a Keeper's (row 2). */
    public boolean keeper() {
        return bound && Keepers.reserved(prizeId);
    }

    /** A collector cap (or trail item): its prize id is one of the catalogue's (row 8). */
    public boolean collector() {
        return Collectors.isCollectorId(prizeId);
    }

    /** A Mini (it has a catalog id and a per-copy id). */
    public boolean mini() {
        return miniId != null && !miniId.isBlank() && miniUid != null && !miniUid.isBlank();
    }

    /**
     * T3's test (§4.10), as {@link TokenPrizes#carries}: a prize, or something unread, or a prize anywhere inside.
     * Fails closed: what can't be read counts as a prize.
     */
    public boolean carriesPrize() {
        if (prize || unread) {
            return true;
        }
        for (ItemFacts in : inside) {
            if (in.carriesPrize()) {
                return true;
            }
        }
        return false;
    }

    /** Every Mini in this stack, itself first, then inside, at any depth. */
    public List<ItemFacts> minis() {
        List<ItemFacts> out = new ArrayList<>();
        collect(this, out, true);
        return out;
    }

    /** Every collector in this stack, itself first, then inside, at any depth. */
    public List<ItemFacts> collectors() {
        List<ItemFacts> out = new ArrayList<>();
        collect(this, out, false);
        return out;
    }

    private static void collect(ItemFacts f, List<ItemFacts> out, boolean minis) {
        if (minis ? f.mini() : f.collector()) {
            out.add(f);
        }
        for (ItemFacts in : f.inside) {
            collect(in, out, minis);
        }
    }

    // ---- the reader --------------------------------------------------------------------------------

    /** The two things the reader asks of one item: its own facts, and what it holds. */
    interface Reader<T> {
        /** The item's own facts ({@link #inside} empty), or null for nothing (air). */
        ItemFacts self(T item);

        /** What it holds, read at {@code depth} (the item itself is 0); empty when it can't be read. */
        Optional<List<T>> contents(T item, int depth);
    }

    /** Read a real stack: itself and everything inside it (fail closed). Null for nothing or air. */
    public static ItemFacts of(ItemStack item) {
        return read(item, 0, BUKKIT);
    }

    /**
     * The walk {@link #of} makes, over any kind of item (a test walks its own stand-ins): the depth rule is
     * {@link TokenPrizes}' own, applied here too, so a stand-in that answers every depth still stops where a
     * real one does.
     */
    static <T> ItemFacts read(T item, int depth, Reader<T> reader) {
        ItemFacts self = item == null ? null : reader.self(item);
        if (self == null) {
            return null;
        }
        Optional<List<T>> contents;
        try {
            contents = reader.contents(item, depth);
        } catch (RuntimeException e) {
            contents = Optional.empty();
        }
        if (contents.isEmpty() || (depth > TokenPrizes.MAX_DEPTH && !contents.get().isEmpty())) {
            return self.unreadable();
        }
        List<ItemFacts> inside = new ArrayList<>();
        for (T in : contents.get()) {
            ItemFacts f = read(in, depth + 1, reader);
            if (f != null) {
                inside.add(f);
            }
        }
        return inside.isEmpty() ? self : self.holding(inside);
    }

    private static final Reader<ItemStack> BUKKIT = new Reader<>() {
        @Override
        public ItemFacts self(ItemStack item) {
            return selfOf(item);
        }

        @Override
        public Optional<List<ItemStack>> contents(ItemStack item, int depth) {
            return TokenPrizes.contents(item, depth);
        }
    };

    /** One real stack's own facts, every tag read through the one detector each rule names. */
    private static ItemFacts selfOf(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        String material = item.getType().name();
        int amount = item.getAmount();
        String name = label(item);
        try {
            Set<String> keys = new LinkedHashSet<>();
            var pdc = item.getPersistentDataContainer();
            for (NamespacedKey k : pdc.getKeys()) {
                keys.add(k.toString().toLowerCase(Locale.ROOT));
            }
            // bound fails closed too: an hcm:bound_to of any type, or a preview stand's copy, is a key that stays
            // with one player even when PrizeService.bound can't read it as one
            boolean bound = PrizeService.bound(item) || keys.contains(BOUND_TO_KEY)
                    || (Keys.LOOK_PREVIEW != null && pdc.has(Keys.LOOK_PREVIEW));
            return new ItemFacts(name, material, amount, KitItems.isKit(item), TokenPrizes.prizeId(item),
                    TokenPrizes.is(item), bound, CourierPackage.is(item),
                    Keys.MINI_DISPLAY_ONLY != null && pdc.has(Keys.MINI_DISPLAY_ONLY),
                    CandyItems.isBag(item), Keys.TRADE_GHOST != null && pdc.has(Keys.TRADE_GHOST), keys,
                    string(pdc, Keys.MINI_UID), string(pdc, Keys.MINI_ID), false, List.of());
        } catch (RuntimeException e) {
            return plain(name, material, amount).unreadable();
        }
    }

    private static String string(io.papermc.paper.persistence.PersistentDataContainerView pdc, NamespacedKey key) {
        if (key == null) {
            return null;
        }
        try {
            return pdc.get(key, PersistentDataType.STRING);
        } catch (RuntimeException e) {
            return null; // stored as another type: not one of ours
        }
    }

    /** An item's own name, or its material's ("Oak log"). */
    public static String label(ItemStack item) {
        try {
            ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
            if (meta != null && meta.hasDisplayName()) {
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(meta.displayName()).trim();
                if (!plain.isEmpty()) {
                    return plain;
                }
            }
        } catch (RuntimeException ignored) {
            // the material's name below
        }
        return pretty(item.getType().name());
    }

    /** "OAK_LOG" → "Oak log". */
    static String pretty(String constant) {
        if (constant == null || constant.isEmpty()) {
            return "";
        }
        String lower = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    // ---- ghosts ------------------------------------------------------------------------------------

    /** What building a ghost does to one item: the live meta's, or a test's stand-in. */
    interface Ghostable {
        /** Every data key on it, as {@code namespace:key}. */
        Set<String> keys();

        void removeKey(String key);

        /** Add {@code hcm:trade_ghost}. */
        void markGhost();

        /** Empty it: a shulker box's (any block state's) items and a bundle's. */
        void empty();

        /** Add these legacy lines after its own lore. */
        void addLore(List<String> lines);
    }

    /**
     * The picture of an offered stack (§4.3, red team D-m2): a copy with every {@code homecraftmanagement:} and
     * {@code hcm:} key taken off, {@code hcm:trade_ghost} added, its name kept, and the line that says where the
     * real one is. A container's ghost is built EMPTY, with what it holds as lore lines: the key strip is top-level
     * only, so a ghost must never carry a real nested item.
     *
     * @param amount how many are on the table (the picture's stack size)
     */
    public static ItemStack ghost(ItemStack real, int amount, String owner, ItemFacts facts) {
        ItemStack g = real.clone();
        g.setAmount(Math.max(1, Math.min(amount, Math.max(1, g.getMaxStackSize()))));
        ItemMeta meta = g.getItemMeta();
        if (meta == null) {
            return g;
        }
        ghost(new MetaGhost(meta), owner, facts == null ? List.of() : facts.inside(), facts != null && facts.unread());
        g.setItemMeta(meta);
        return g;
    }

    /** {@link #ghost(ItemStack, int, String, ItemFacts)} on any {@link Ghostable}. */
    static void ghost(Ghostable g, String owner, List<ItemFacts> inside, boolean unread) {
        for (String key : List.copyOf(g.keys())) {
            if (stripped(key)) {
                g.removeKey(key);
            }
        }
        g.empty();
        g.markGhost();
        List<String> lore = new ArrayList<>(insideLines(inside, unread));
        lore.add("&8Still in " + owner + "'s bag until you both press Ready");
        g.addLore(lore);
    }

    /** Whether a ghost loses this key: every one of ours, in either namespace. */
    static boolean stripped(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        return k.startsWith(PLUGIN_NAMESPACE + ":") || k.startsWith("hcm:");
    }

    /**
     * What a container's ghost says it holds: one line per kind (same names added up, in order), at most
     * {@value #INSIDE_LINES}, then "and N more"; and a line for contents too deep to read.
     */
    static List<String> insideLines(List<ItemFacts> inside, boolean unread) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemFacts f : inside == null ? List.<ItemFacts>of() : inside) {
            counts.merge(f.name(), Math.max(1, f.amount()), Integer::sum);
        }
        List<String> out = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (shown == INSIDE_LINES) {
                out.add("&7and " + (counts.size() - INSIDE_LINES) + " more");
                break;
            }
            out.add((shown == 0 ? "&7Inside: " : "&7  ") + e.getValue() + " " + e.getKey());
            shown++;
        }
        if (unread) {
            out.add("&7Too many bags inside bags");
        }
        return out;
    }

    /** The live meta as a {@link Ghostable}. */
    private record MetaGhost(ItemMeta meta) implements Ghostable {

        @Override
        public Set<String> keys() {
            Set<String> out = new LinkedHashSet<>();
            for (NamespacedKey k : meta.getPersistentDataContainer().getKeys()) {
                out.add(k.toString());
            }
            return out;
        }

        @Override
        public void removeKey(String key) {
            NamespacedKey k = NamespacedKey.fromString(key);
            if (k != null) {
                meta.getPersistentDataContainer().remove(k);
            }
        }

        @Override
        public void markGhost() {
            meta.getPersistentDataContainer().set(Keys.TRADE_GHOST, PersistentDataType.BYTE, (byte) 1);
        }

        @Override
        public void empty() {
            if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()) {
                BlockState state = bsm.getBlockState();
                if (state instanceof Container c) {
                    c.getSnapshotInventory().clear();
                    bsm.setBlockState(state);
                } else if (state instanceof InventoryHolder holder) {
                    holder.getInventory().clear();
                    bsm.setBlockState(state);
                }
            }
            if (meta instanceof BundleMeta bundle) {
                bundle.setItems(List.of());
            }
        }

        @Override
        public void addLore(List<String> lines) {
            List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore())
                    : new ArrayList<>();
            for (String line : lines) {
                lore.add(Text.of(line));
            }
            meta.lore(lore);
        }
    }
}
