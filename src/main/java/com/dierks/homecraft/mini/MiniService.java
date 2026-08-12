package com.dierks.homecraft.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.MiniCatalogWriter;
import com.dierks.homecraft.integration.EconomyService;
import com.dierks.homecraft.storage.MiniDao;
import com.dierks.homecraft.util.Keys;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Minis engine: owns the config-driven catalog and is the single source of
 * truth for minting. Every copy — bought, admin-given, or (later) crafted/dropped
 * — flows through {@link #mint}/{@link #giveAdmin} so mint counts and per-copy
 * provenance stay honest and caps are enforced.
 */
public final class MiniService {

    public record MintResult(boolean ok, String error, long mintNumber) {
        static MintResult fail(String error) {
            return new MintResult(false, error, 0);
        }
    }

    /** The identity read from a minted Mini item's PDC (the anti-dupe tag + provenance). */
    public record MiniRef(String uid, String miniId, long mintNumber) {
    }

    private final HomeCraftManagement plugin;
    private final MiniDao dao;
    private final EconomyService economy;
    private final MiniItems items = new MiniItems();
    private final MiniCatalogWriter writer;

    private Map<String, MiniDef> catalog = new LinkedHashMap<>();

    public MiniService(HomeCraftManagement plugin, MiniDao dao, EconomyService economy) {
        this.plugin = plugin;
        this.dao = dao;
        this.economy = economy;
        this.writer = new MiniCatalogWriter(plugin);
    }

    /** (Re)build the catalog from config. Mint tallies live in the DB and are untouched. */
    public void reload() {
        Map<String, MiniDef> next = new LinkedHashMap<>();
        for (MiniDef def : plugin.config().minis().catalog()) {
            next.put(def.id(), def);
        }
        this.catalog = next;
        plugin.getLogger().info("Minis catalog loaded " + catalog.size() + " entry(ies).");
    }

    public Collection<MiniDef> catalog() {
        return catalog.values();
    }

    /** A mutable snapshot of the catalog in order (for the Admin Studio to edit). */
    public List<MiniDef> catalogList() {
        return new ArrayList<>(catalog.values());
    }

    public MiniDef def(String id) {
        return catalog.get(id);
    }

    public boolean idExists(String id) {
        return catalog.containsKey(id);
    }

    /** The set of ids currently in use (for unique-id generation on import/add). */
    public Set<String> ids() {
        return new HashSet<>(catalog.keySet());
    }

    /** The admin-defined Type/category list from config (open list). */
    public List<String> categories() {
        return plugin.config().minis().categories();
    }

    /** Persist a fully custom category list (create/rename/remove), then reload live. */
    public void saveCategories(List<String> categories) {
        java.util.List<String> cleaned = new ArrayList<>();
        for (String c : categories) {
            String v = c == null ? "" : c.trim();
            if (!v.isEmpty() && !cleaned.contains(v)) {
                cleaned.add(v);
            }
        }
        plugin.getConfig().set("minis.categories", cleaned);
        plugin.saveConfig();
        plugin.config().load();
        reload();
    }

    /** Add a new category if absent (used when an admin types a brand-new type). */
    public void addCategory(String name) {
        String v = name == null ? "" : name.trim();
        if (v.isEmpty()) {
            return;
        }
        List<String> cats = new ArrayList<>(categories());
        if (cats.stream().noneMatch(c -> c.equalsIgnoreCase(v))) {
            cats.add(v);
            saveCategories(cats);
        }
    }

    /** A blank draft for "Add Mini", pre-filled with COMMON's config defaults. */
    public MiniDraft blankDraft() {
        RarityStyle st = style(Rarity.COMMON);
        String cat = categories().isEmpty() ? "MISC" : categories().get(0);
        return new MiniDraft(null, "New Mini", "New Series", cat, Rarity.COMMON,
                MiniType.HEAD, "", st.defaultCap(), st.defaultPrice(), false);
    }

    /** Reset a draft's cap + price to its rarity's config defaults (smart defaults). */
    public void applyRarityDefaults(MiniDraft draft) {
        RarityStyle st = style(draft.rarity());
        draft.setCap(st.defaultCap());
        draft.setPrice(st.defaultPrice());
    }

    /** The live Wild-Drops loot config. */
    public Loot.MiniLoot loot() {
        return plugin.config().miniLoot();
    }

    /** The stored posed-armor-stand configuration for a Mini id, or null if none. */
    public StandData stand(String id) {
        return plugin.config().miniStands().get(id);
    }

    /** Persist one armor-stand configuration (captured pose/equipment), then reload live. */
    public void saveStand(String id, StandData data) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, StandData> e : plugin.config().miniStands().entrySet()) {
            if (e.getKey().equals(id)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.putAll(e.getValue().toConfig());
            out.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.putAll(data.toConfig());
        out.add(m);
        plugin.getConfig().set("minis.stands", out);
        plugin.saveConfig();
        plugin.config().load();
        reload();
    }

    /** Persist a full loot config (lists + sources), reload, and refresh drop caches. */
    public void saveLoot(Loot.MiniLoot loot) {
        List<Map<String, Object>> lists = new ArrayList<>();
        for (Loot.LootList l : loot.lists()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.id());
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Loot.LootEntry e : l.entries()) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("mini", e.miniId());
                em.put("weight", e.weight());
                entries.add(em);
            }
            m.put("entries", entries);
            lists.add(m);
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Loot.LootSource s : loot.sources()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trigger", s.trigger().name());
            m.put("match", s.match());
            m.put("list", s.listId());
            m.put("chance_percent", s.chancePercent());
            sources.add(m);
        }
        plugin.getConfig().set("minis.loot.lists", lists);
        plugin.getConfig().set("minis.loot.sources", sources);
        plugin.saveConfig();
        plugin.config().load();
        reload();
        if (plugin.wildDrops() != null) {
            plugin.wildDrops().invalidate();
        }
    }

    /**
     * Persist a full catalog edit: write it to config.yml, re-parse, and rebuild
     * the live catalog. Mint tallies in the DB are keyed by id and untouched, so
     * renaming a Mini's display fields keeps its provenance. Applies immediately —
     * open menus reflect it on their next refresh.
     */
    public void saveCatalog(List<MiniDef> defs) {
        writer.write(defs);
        plugin.config().load();
        reload();
    }

    public RarityStyle style(Rarity rarity) {
        return plugin.config().minis().style(rarity);
    }

    public MiniDao.Counts counts(String id) {
        try {
            return dao.counts(id);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read Mini counts for " + id + ": " + e.getMessage());
            return new MiniDao.Counts(0, 0);
        }
    }

    /** Provenance row for a specific minted copy (for the info card). */
    public java.util.Optional<MiniDao.Individual> individual(String uid) {
        try {
            return uid == null || uid.isBlank() ? java.util.Optional.empty() : dao.individual(uid);
        } catch (SQLException e) {
            return java.util.Optional.empty();
        }
    }

    /** Ownership/sale trail for a copy (oldest first). */
    public java.util.List<MiniDao.Sale> salesForUid(String uid) {
        try {
            return uid == null || uid.isBlank() ? java.util.List.of() : dao.salesForUid(uid);
        } catch (SQLException e) {
            return java.util.List.of();
        }
    }

    /** Last sale price for a Mini type (its "market value"), or null if never sold. */
    public Double lastSalePrice(String miniId) {
        try {
            return dao.lastSalePrice(miniId);
        } catch (SQLException e) {
            return null;
        }
    }

    /** Museum display icon (cosmetic; shows live minted/cap + circulation). */
    public ItemStack icon(MiniDef def) {
        MiniDao.Counts c = counts(def.id());
        return items.preview(def, style(def.rarity()), c.minted(), c.circulation(), economy.format(def.price()));
    }

    /** Buy + mint a Mini for a player (charges the price via Vault, enforces the cap). */
    public MintResult mint(Player player, String id) {
        MiniDef def = catalog.get(id);
        if (def == null) {
            return MintResult.fail("No such Mini '" + id + "'.");
        }
        MiniDao.Counts c = counts(id);
        if (!def.uncapped() && c.minted() >= def.cap()) {
            return MintResult.fail(def.name() + " is minted out (" + def.cap() + "/" + def.cap() + ").");
        }
        if (!economy.isEnabled()) {
            return MintResult.fail("The economy is offline (no Vault).");
        }
        if (def.price() > 0) {
            if (!economy.has(player, def.price())) {
                return MintResult.fail("You can't afford " + economy.format(def.price()) + ".");
            }
            if (!economy.withdraw(player, def.price())) {
                return MintResult.fail("Payment failed.");
            }
        }
        return mintInternal(player, def);
    }

    /**
     * Retire a minted copy on destruction (Phase 9): decrement circulation but keep
     * the mint number forever — a burned "#3 of 5" means only 4 will ever exist, and
     * the cap slot is never freed. Safe on any item (no-op for non-Minis), idempotent,
     * and best-effort (some paths — unloaded chunks, ender chests — can't be tracked).
     *
     * @return true if a live copy was actually retired.
     */
    public boolean retire(ItemStack item) {
        MiniRef ref = identify(item);
        if (ref == null) {
            return false;
        }
        try {
            boolean retired = dao.retire(ref.uid(), ref.miniId());
            if (retired) {
                MiniDao.Counts c = dao.counts(ref.miniId());
                plugin.getLogger().info("Retired Mini " + ref.miniId() + " #" + ref.mintNumber()
                        + " on destruction — circulation now " + c.circulation() + ".");
            }
            return retired;
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to retire Mini on destruction: " + e.getMessage());
            return false;
        }
    }

    /** @return the identity of a minted Mini item, or null if the item isn't a tagged Mini. */
    public MiniRef identify(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return refFrom(item.getItemMeta().getPersistentDataContainer());
    }

    /**
     * Read a Mini identity from any PersistentDataContainer (item, placed head
     * block, or armor-stand entity). MINI_ID (a String) is the definitive marker —
     * strings are immune to the numeric-type coercion that can silently shrink a
     * small LONG on Paper's NBT round-trip and make a strict LONG read throw.
     *
     * @return the identity, or null if the container isn't tagged as a Mini.
     */
    public MiniRef refFrom(org.bukkit.persistence.PersistentDataContainer pdc) {
        if (pdc == null) {
            return null;
        }
        String miniId = readString(pdc, Keys.MINI_ID);
        if (miniId == null) {
            return null;
        }
        String uid = readString(pdc, Keys.MINI_UID);
        long mint = readLong(pdc, Keys.MINI_MINT);
        return new MiniRef(uid == null ? "" : uid, miniId, mint);
    }

    private String readString(org.bukkit.persistence.PersistentDataContainer pdc,
                              org.bukkit.NamespacedKey key) {
        try {
            return pdc.get(key, PersistentDataType.STRING);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Read a whole-number PDC value tolerating any numeric type it may have been stored as. */
    private long readLong(org.bukkit.persistence.PersistentDataContainer pdc,
                          org.bukkit.NamespacedKey key) {
        try {
            Long l = pdc.get(key, PersistentDataType.LONG);
            if (l != null) {
                return l;
            }
        } catch (Throwable ignored) {
            // stored as a narrower numeric type — try those
        }
        try {
            Integer i = pdc.get(key, PersistentDataType.INTEGER);
            if (i != null) {
                return i;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            Short s = pdc.get(key, PersistentDataType.SHORT);
            if (s != null) {
                return s;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            Byte b = pdc.get(key, PersistentDataType.BYTE);
            if (b != null) {
                return b;
            }
        } catch (Throwable ignored) {
            // give up — mint number is cosmetic for detection
        }
        return 0;
    }

    public boolean isMini(ItemStack item) {
        return identify(item) != null;
    }

    /** A Mini held in the main hand: the live item stack (mutable) plus its identity. */
    public record HeldMini(ItemStack item, MiniRef ref) {
    }

    /**
     * The single source of truth for "is the player holding a Mini?" — used by the
     * Vending Machine, Display Case, {@code /hcm auction} Sell, and the info card,
     * so held-Mini detection can never drift between paths again.
     *
     * @return the held Mini (item + identity), or null if the main hand isn't a Mini.
     */
    public HeldMini getHeldMini(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        MiniRef ref = identify(held);
        return ref == null ? null : new HeldMini(held, ref);
    }

    /** Transfer provenance ownership of a minted copy (secondary-market sale/auction). */
    public void transferOwner(String uid, java.util.UUID newOwner) {
        try {
            dao.updateOwner(uid, newOwner);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to transfer Mini owner for " + uid + ": " + e.getMessage());
        }
    }

    /** Log a secondary-market sale (for provenance + price history). */
    public void recordSale(String uid, String miniId, double price, java.util.UUID seller,
                           java.util.UUID buyer, String venue) {
        try {
            dao.recordSale(uid, miniId, price, seller, buyer, venue, System.currentTimeMillis());
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to log Mini sale for " + uid + ": " + e.getMessage());
        }
    }

    /**
     * Wild-drop mint path: no charge, but the cap is enforced (a drop is just
     * another mint, so it stops at the cap and counts toward circulation).
     * @return the mint result; not ok if the type is unknown or minted out.
     */
    public MintResult mintWild(Player player, String id) {
        MiniDef def = catalog.get(id);
        if (def == null) {
            return MintResult.fail("No such Mini '" + id + "'.");
        }
        MiniDao.Counts c = counts(id);
        if (!def.uncapped() && c.minted() >= def.cap()) {
            return MintResult.fail(def.name() + " is minted out.");
        }
        return mintInternal(player, def);
    }

    /** Admin: mint + give a Mini with no charge and no cap check. */
    public MintResult giveAdmin(Player target, String id) {
        MiniDef def = catalog.get(id);
        if (def == null) {
            return MintResult.fail("No such Mini '" + id + "'.");
        }
        return mintInternal(target, def);
    }

    private MintResult mintInternal(Player target, MiniDef def) {
        try {
            long mintNumber = dao.mintNext(def.id());
            UUID uid = UUID.randomUUID();
            ItemStack item = items.minted(def, style(def.rarity()), mintNumber, uid);
            target.getInventory().addItem(item).values()
                    .forEach(drop -> target.getWorld().dropItemNaturally(target.getLocation(), drop));
            dao.recordIndividual(uid, def.id(), mintNumber, target.getUniqueId(), System.currentTimeMillis());
            if (plugin.achievements() != null) {
                plugin.achievements().tryAward(target, "first_mini");
            }
            return new MintResult(true, null, mintNumber);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to mint Mini " + def.id() + ": " + e.getMessage());
            return MintResult.fail("Minting failed — try again.");
        }
    }
}
