package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.mini.Rarity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Where each player left off in the browsing menus, for the length of their session.
 *
 * <p>The menus are rebuilt from scratch on every open — checkout, a quantity picker or a
 * detail card all construct a fresh instance — so without this, stepping into checkout and
 * back would dump the player at page 1 of "All" every time. Kept per player and per menu
 * kind, seeded from {@code menus.*} config defaults on first use, and dropped on quit so
 * the map cannot grow without bound.
 *
 * <p>Deliberately NOT persisted: it is a view preference, not data, and starting a new
 * session at the configured default is the behaviour an admin sets those defaults for.
 */
public final class BrowseState implements Listener {

    /** How a shop grid is sorted. */
    public enum Sort {
        NAME("Name A–Z"),
        PRICE_UP("Price ↑"),
        PRICE_DOWN("Price ↓"),
        STOCK("Stock");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }

        /** Parse a config value; anything unrecognised falls back to NAME. */
        public static Sort of(String raw) {
            if (raw != null) {
                for (Sort s : values()) {
                    if (s.name().equalsIgnoreCase(raw.trim())) {
                        return s;
                    }
                }
            }
            return NAME;
        }
    }

    /** How the Museum groups its entries. */
    public enum View {
        SERIES("By Series"),
        RARITY("By Rarity"),
        TYPE("By Type");

        private final String label;

        View(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public View next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public static View of(String raw) {
            if (raw != null) {
                for (View v : values()) {
                    if (v.name().equalsIgnoreCase(raw.trim())) {
                        return v;
                    }
                }
            }
            return SERIES;
        }
    }

    /** The Store / Market view: which department tab, which page, which sort. */
    public static final class Shop {
        public String department;
        public Sort sort;
        public int page;

        Shop(String department, Sort sort) {
            this.department = department;
            this.sort = sort;
        }
    }

    /** The Museum view: grouping, rarity filter, owned-only, page. */
    public static final class Museum {
        public View view;
        /** null = every rarity. */
        public Rarity rarity;
        public boolean ownedOnly;
        public int page;

        Museum(View view) {
            this.view = view;
        }

        /** Cycle All → COMMON → … → LEGENDARY → All. */
        public void cycleRarity() {
            if (rarity == null) {
                rarity = Rarity.values()[0];
                return;
            }
            int next = rarity.ordinal() + 1;
            rarity = next >= Rarity.values().length ? null : Rarity.values()[next];
        }

        public String rarityLabel() {
            return rarity == null ? "All" : pretty(rarity.name());
        }
    }

    private final HomeCraftManagement plugin;
    private final Map<UUID, Shop> store = new HashMap<>();
    private final Map<UUID, Shop> market = new HashMap<>();
    private final Map<UUID, Museum> museum = new HashMap<>();

    public BrowseState(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    public Shop store(Player player) {
        return store.computeIfAbsent(player.getUniqueId(), id -> newShop());
    }

    public Shop market(Player player) {
        return market.computeIfAbsent(player.getUniqueId(), id -> newShop());
    }

    public Museum museum(Player player) {
        return museum.computeIfAbsent(player.getUniqueId(),
                id -> new Museum(View.of(plugin.config().menuDefaults().museumView())));
    }

    private Shop newShop() {
        PluginConfig.MenuDefaults d = plugin.config().menuDefaults();
        String dept = d.storeDepartment() == null || d.storeDepartment().isBlank()
                ? Departments.ALL : d.storeDepartment().trim();
        return new Shop(dept, Sort.of(d.storeSort()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        store.remove(id);
        market.remove(id);
        museum.remove(id);
    }

    /** "LEGENDARY" → "Legendary". */
    static String pretty(String constant) {
        String lower = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
