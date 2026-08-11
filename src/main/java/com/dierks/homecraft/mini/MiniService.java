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

    /** @return the identity of a minted Mini item, or null if the item isn't a tagged Mini. */
    public MiniRef identify(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        var pdc = meta.getPersistentDataContainer();
        String uid = pdc.get(Keys.MINI_UID, PersistentDataType.STRING);
        String miniId = pdc.get(Keys.MINI_ID, PersistentDataType.STRING);
        if (uid == null || miniId == null) {
            return null;
        }
        Long mint = pdc.get(Keys.MINI_MINT, PersistentDataType.LONG);
        return new MiniRef(uid, miniId, mint == null ? 0 : mint);
    }

    public boolean isMini(ItemStack item) {
        return identify(item) != null;
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
            return new MintResult(true, null, mintNumber);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to mint Mini " + def.id() + ": " + e.getMessage());
            return MintResult.fail("Minting failed — try again.");
        }
    }
}
