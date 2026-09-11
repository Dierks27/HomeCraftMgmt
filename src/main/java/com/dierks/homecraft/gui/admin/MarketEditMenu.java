package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.ConfirmMenu;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.market.MarketDraft;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.market.MarketState;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The commodity form, shared by Add and Edit.
 *
 * <p>The id is only editable while adding. An existing commodity's id is fixed on purpose:
 * renaming one is a remove-plus-add to the engine — the old id's stock goes dormant and the
 * new id seeds fresh from {@code initial_stock} — so a rename would quietly reset stock in
 * exactly the case the removal rule refuses to allow. To change an id, add the new
 * commodity and remove the old one, which puts the stock rule back in charge.
 */
public final class MarketEditMenu extends Menu {

    private final Player player;
    private final MarketDraft draft;
    private final boolean existing;
    private final Runnable onBack;

    public MarketEditMenu(HomeCraftManagement plugin, Player player, MarketDraft draft,
                          boolean existing, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.draft = draft;
        this.existing = existing;
        this.onBack = onBack;
        init(54, Text.of(existing ? "&aEdit commodity" : "&aNew commodity"));
    }

    /** Every id in the catalog except this draft's own — the duplicate check. */
    private Set<String> otherIds() {
        Set<String> ids = new HashSet<>();
        for (MarketItem item : plugin.market().catalog()) {
            if (draft.id() == null || !item.id().equals(draft.id())) {
                ids.add(item.id());
            }
        }
        return ids;
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }

        // ---- identity ----------------------------------------------------------
        if (existing) {
            set(10, Menus.icon(Material.NAME_TAG, "&eId: &f" + draft.id(),
                    "&7Fixed once a commodity exists.",
                    "&7To rename: add the new id, then",
                    "&7remove this one."), null);
        } else {
            set(10, Menus.icon(Material.NAME_TAG, "&eId: &f" + (draft.id() == null ? "&8(unset)" : draft.id()),
                    "&7The name used by &f/hcm market&7.",
                    "&7a-z, 0-9 and _ only.",
                    "&eClick to set."), e ->
                    prompt("Id for the new commodity (a-z, 0-9, _):", v -> draft.setId(v)));
        }

        set(11, Menus.icon(draft.material() == null ? Material.BARRIER : draft.material(),
                "&eMaterial: &f" + (draft.material() == null ? "&8(unset)" : draft.material().name()),
                "&7What players actually trade.",
                "&eClick to pick."), e ->
                new MaterialPickerMenu(plugin, player, "", m -> {
                    draft.setMaterial(m);
                    reopen();
                }, this::reopen).open(player));

        set(12, Menus.icon(Material.OAK_SIGN, "&eDisplay name: &f"
                        + (draft.displayName() == null ? "&8(material name)" : draft.displayName()),
                "&7Shown in the store and market.",
                "&7Type &fnone&7 to clear it.",
                "&eClick to set."), e ->
                prompt("Display name (or 'none'):", v -> draft.setDisplayName(v)));

        // ---- price band --------------------------------------------------------
        set(14, Menus.icon(Material.GOLD_NUGGET, "&eFloor: &f" + plugin.economy().format(draft.floor()),
                "&7Price when the market is FULL.",
                "&eClick to set."), e ->
                promptDouble("Floor price:", v -> draft.setFloor(v)));

        set(15, Menus.icon(Material.GOLD_INGOT, "&eCeiling: &f" + plugin.economy().format(draft.ceiling()),
                "&7Price when stock hits 0.",
                "&7Must be above the floor.",
                "&eClick to set."), e ->
                promptDouble("Ceiling price:", v -> draft.setCeiling(v)));

        // ---- stock -------------------------------------------------------------
        set(19, Menus.icon(Material.CHEST, "&eFull stock: &f" + draft.fullStock(),
                "&7The price curve's denominator —",
                "&7not a target. Real stock tops out",
                "&7one below it. Minimum 2.",
                "&eClick to set."), e ->
                promptLong("Full stock:", v -> draft.setFullStock(v)));

        set(20, Menus.icon(Material.HOPPER, "&eInitial stock: &f" + draft.initialStock(),
                "&7Seeded only when the commodity is",
                "&7first added. Must be below full stock.",
                "&8Editing this does NOT move live stock.",
                "&eClick to set."), e ->
                promptLong("Initial stock:", v -> draft.setInitialStock(v)));

        // ---- daily caps --------------------------------------------------------
        set(23, Menus.icon(Material.REDSTONE, "&eMax daily sell: &f"
                        + (draft.maxDailySell() == 0 ? "&8none" : String.valueOf(draft.maxDailySell())),
                "&7Per player, per day. 0 = no cap.",
                "&8Guide: about 2% of full stock",
                "&eClick to set."), e ->
                promptLong("Max daily sell (0 = none):", v -> draft.setMaxDailySell(v)));

        set(24, Menus.icon(Material.REPEATER, "&eMax daily buy: &f"
                        + (draft.maxDailyBuy() == 0 ? "&8none" : String.valueOf(draft.maxDailyBuy())),
                "&7Per player, per day. 0 = no cap.",
                "&8Guide: about 4% of full stock",
                "&eClick to set."), e ->
                promptLong("Max daily buy (0 = none):", v -> draft.setMaxDailyBuy(v)));

        // ---- live state, for an existing commodity -----------------------------
        if (existing) {
            MarketItem item = plugin.market().item(draft.id());
            MarketState st = plugin.market().state(draft.id());
            long stock = st == null ? 0 : st.stock();
            List<String> lore = new ArrayList<>();
            lore.add("&7Stock: &f" + stock);
            if (item != null) {
                lore.add("&7Max holdable: &f" + MarketService.maxStock(item));
                lore.add("&7Price: &6" + plugin.economy().format(plugin.market().price(item.id())));
            }
            lore.add("&8Live figures, not config values.");
            set(31, Menus.icon(Material.CLOCK, "&bLive state", lore.toArray(new String[0])), null);

            set(39, Menus.icon(Material.WATER_BUCKET, "&eDrain stock to 0",
                    "&7Sets live stock to 0 and snaps",
                    "&7the price to the curve.",
                    "&8Needed before a removal."), e -> {
                MarketService.StockResult r = plugin.market().setStock(draft.id(), 0);
                player.sendMessage(Text.of(r.ok() ? "&aStock drained to 0." : "&c" + r.error()));
                reopen();
            });

            set(41, Menus.icon(Material.TNT, "&c&lRemove commodity",
                    "&7Refused while it holds stock",
                    "&7or has uncollected orders."), e -> delete());
        }

        // ---- save / back -------------------------------------------------------
        List<String> problems = draft.problems(otherIds());
        if (problems.isEmpty()) {
            set(49, Menus.icon(Material.LIME_DYE, "&a&lSave",
                    "&7Writes it to config.yml and",
                    "&7reloads the market."), e -> save());
        } else {
            List<String> lore = new ArrayList<>(problems);
            lore.add("&8Fix these to save.");
            // RED_DYE, not GRAY_DYE: slot 49 sits on a background of grey pane filler, and the
            // reasons it cannot save are hover-only, so the button itself has to say "blocked".
            set(49, Menus.icon(Material.RED_DYE, "&8Save (not ready)", lore.toArray(new String[0])), null);
        }
        set(53, Menus.icon(Material.BARRIER, "&cBack"), e -> back());
    }

    private void save() {
        if (!draft.problems(otherIds()).isEmpty()) {
            return; // the button is repainted every build; this is the belt-and-braces re-check
        }
        if (!plugin.market().saveCatalogRow(draft)) {
            player.sendMessage(Text.of("&cCould not write config.yml — see the console."));
            return;
        }
        player.sendMessage(Text.of("&aSaved &f" + draft.id() + "&a."));
        if (!existing) {
            // Re-adding an id that existed before resurrects its old stock rather than
            // seeding initial_stock, so report what it actually landed on.
            MarketState st = plugin.market().state(draft.id());
            if (st != null) {
                player.sendMessage(Text.of("&7Starting stock: &f" + st.stock()));
            }
        }
        back();
    }

    /**
     * The removal gate. Both guards are re-checked here rather than trusted from build():
     * a player can sell into the market or place an order between the screen being painted
     * and the button being clicked.
     */
    private void delete() {
        MarketItem item = plugin.market().item(draft.id());
        if (item == null) {
            player.sendMessage(Text.of("&cThat commodity no longer exists."));
            back();
            return;
        }
        String refusal = removalRefusal(draft.id());
        if (refusal != null) {
            player.sendMessage(Text.of(refusal));
            manualOverrideHint();
            return;
        }
        new ConfirmMenu(plugin, "&cRemove " + item.label() + "?",
                Menus.icon(item.material(), "&c" + item.label(), "&7id: &f" + item.id()),
                List.of("&7Stock is 0 and nothing is on order.",
                        "&7Its stock row stays in the database,",
                        "&7so re-adding this id restores it."),
                "&7Click to remove this commodity.",
                () -> {
                    String again = removalRefusal(draft.id());
                    if (again != null) {
                        player.sendMessage(Text.of(again));
                        manualOverrideHint();
                        reopen();
                        return;
                    }
                    if (!plugin.market().removeCatalogRow(draft.id())) {
                        player.sendMessage(Text.of("&cCould not write config.yml — see the console."));
                        reopen();
                        return;
                    }
                    player.sendMessage(Text.of("&aRemoved &f" + draft.id() + "&a."));
                    back();
                },
                this::reopen).open(player);
    }

    /** The '&c'-coded reason this commodity may not be removed, or null when it may. */
    private String removalRefusal(String id) {
        MarketState st = plugin.market().state(id);
        long stock = st == null ? 0 : st.stock();
        if (stock != 0) {
            return "&cCannot remove &f" + id + "&c — it still holds &f" + stock + "&c unit(s).";
        }
        int pending = plugin.orderService().pendingFor(id);
        if (pending > 0) {
            return "&cCannot remove &f" + id + "&c — &f" + pending
                    + "&c order(s) are paid but not collected.";
        }
        return null;
    }

    private void manualOverrideHint() {
        player.sendMessage(Text.of("&7Drain the stock here first, or remove the row from"));
        player.sendMessage(Text.of("&7&fconfig.yml&7 and run &f/hcm reload&7 to force it."));
        player.sendMessage(Text.of("&7Either way the stock is kept and returns if you re-add the id."));
    }

    private void prompt(String question, Consumer<String> apply) {
        plugin.chatPrompts().prompt(player, question, input -> {
            apply.accept(input);
            reopen();
        });
    }

    private void promptDouble(String question, Consumer<Double> apply) {
        plugin.chatPrompts().prompt(player, question, input -> {
            try {
                apply.accept(Double.parseDouble(input.trim()));
            } catch (NumberFormatException ex) {
                player.sendMessage(Text.of("&cThat is not a number."));
            }
            reopen();
        });
    }

    private void promptLong(String question, Consumer<Long> apply) {
        plugin.chatPrompts().prompt(player, question, input -> {
            try {
                apply.accept(Long.parseLong(input.trim()));
            } catch (NumberFormatException ex) {
                player.sendMessage(Text.of("&cThat is not a whole number."));
            }
            reopen();
        });
    }

    private void back() {
        if (onBack != null) {
            onBack.run();
        } else {
            player.closeInventory();
        }
    }

    private void reopen() {
        new MarketEditMenu(plugin, player, draft, existing, onBack).open(player);
    }
}
