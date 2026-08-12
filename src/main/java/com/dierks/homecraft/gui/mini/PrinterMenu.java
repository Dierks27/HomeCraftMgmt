package com.dierks.homecraft.gui.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.CardSpec;
import com.dierks.homecraft.mini.FilamentItems;
import com.dierks.homecraft.mini.Grade;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.PrinterService;
import com.dierks.homecraft.util.Text;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Mini Printer GUI (Phase 9, Part D): hold a Card in your main hand, review the
 * filament + fee (or "free" on a public Mall printer), and press Print. The grade is
 * rolled at print time from the card's odds. A private printer also offers a Shiny
 * finish. The printer never holds items — the Card stays in the player's hand and is
 * consumed on a successful print.
 */
public final class PrinterMenu extends Menu {

    private final Player player;
    private final Location printer;
    private final boolean isPublic;

    public PrinterMenu(HomeCraftManagement plugin, Player player, Location printer) {
        super(plugin);
        this.player = player;
        this.printer = printer;
        this.isPublic = plugin.printers().isPublic(printer);
        init(27, Text.of(isPublic ? "&bMini Printer &8· &aPublic (free)" : "&bMini Printer"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }

        // The Printer doubles as the light craft station for the PC (the Mini
        // Workbench is retired in Phase 9), so a PC is always reachable here.
        set(26, Menus.icon(Material.CRAFTING_TABLE, "&bCraft a PC",
                "&7Open the craft grid to build a PC",
                "&7from the configured recipe."),
                e -> com.dierks.homecraft.crafting.WorkbenchHolder.open(player));

        ItemStack held = player.getInventory().getItemInMainHand();
        String id = plugin.miniService().cardItems().cardIdOf(held);
        MiniDef def = id == null ? null : plugin.miniService().def(id);

        if (def == null) {
            set(13, Menus.icon(Material.MAP, "&eHold a Card",
                    "&7Put a Mini Card in your main hand,",
                    "&7then reopen the Printer to print it."), null);
            set(22, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
            return;
        }

        CardSpec spec = plugin.miniService().cardSpec(def);
        PluginConfig.Printer cfg = plugin.config().printer();
        FilamentItems filaments = plugin.miniService().filamentItems();

        // The card being printed.
        ItemStack cardIcon = plugin.miniService().cardFor(id);
        set(10, cardIcon, null);

        // Grade odds.
        List<String> oddsLore = new ArrayList<>();
        oddsLore.add("&7Grade is rolled at print:");
        oddsLore.add("&f" + plugin.miniService().cardItems().gradeOdds(spec));
        set(12, Menus.icon(Material.EXPERIENCE_BOTTLE, "&dPrint Odds", oddsLore.toArray(new String[0])), null);

        // Filament + fee requirements (or free on a public printer).
        boolean canAfford;
        if (isPublic) {
            set(14, Menus.icon(Material.EMERALD_BLOCK, "&aPublic Printer",
                    "&7The house covers filament & fee.",
                    "&7Basic finishes only — no Shiny."), null);
            canAfford = true;
        } else {
            List<String> req = new ArrayList<>();
            boolean haveAll = true;
            for (Map.Entry<DyeColor, Integer> e : spec.filament().entrySet()) {
                int need = e.getValue() == null ? 0 : e.getValue();
                if (need <= 0) {
                    continue;
                }
                int have = filaments.count(player, e.getKey());
                boolean ok = have >= need;
                haveAll &= ok;
                req.add((ok ? "&a✔ " : "&c✗ ") + need + " " + pretty(e.getKey().name())
                        + " &7(" + have + " held)");
            }
            double fee = cfg.fee();
            boolean feeOk = fee <= 0 || (plugin.economy().isEnabled() && plugin.economy().has(player, fee));
            req.add("");
            req.add((feeOk ? "&a✔ " : "&c✗ ") + "Fee: " + (fee <= 0 ? "&aFree" : plugin.economy().format(fee)));
            canAfford = haveAll && feeOk;
            set(14, Menus.icon(canAfford ? Material.LIME_DYE : Material.RED_DYE,
                    "&bFilament & Fee", req.toArray(new String[0])), null);
        }

        // Print button.
        if (canAfford) {
            set(20, Menus.icon(Material.LIME_CONCRETE, "&a&lPRINT",
                    "&7Roll the grade and print your Mini."),
                    e -> doPrint(false));
        } else {
            set(20, Menus.icon(Material.GRAY_CONCRETE, "&7Print",
                    "&cYou're missing filament or the fee."), null);
        }

        // Shiny (private printer only).
        if (!isPublic) {
            boolean shinyOk = canAfford && cfg.shinyAmount() >= 0
                    && (cfg.shinyAmount() == 0 || filaments.count(player, cfg.shinyDye()) >= cfg.shinyAmount())
                    && (cfg.shinyFee() <= 0 || (plugin.economy().isEnabled()
                        && plugin.economy().has(player, cfg.fee() + cfg.shinyFee())));
            String shinyCost = "&7Adds " + (cfg.shinyAmount() > 0
                    ? cfg.shinyAmount() + " " + pretty(cfg.shinyDye().name()) + " Filament" : "no material")
                    + (cfg.shinyFee() > 0 ? " + " + plugin.economy().format(cfg.shinyFee()) : "");
            if (shinyOk) {
                set(24, Menus.icon(Material.NETHER_STAR, "&f✦ &lPRINT SHINY",
                        "&7A premium glint + sparkle aura.", shinyCost),
                        e -> doPrint(true));
            } else {
                set(24, Menus.icon(Material.GRAY_DYE, "&8✦ Print Shiny",
                        "&cCan't afford the Shiny finish.", shinyCost), null);
            }
        }

        set(22, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
    }

    private void doPrint(boolean shiny) {
        ItemStack held = player.getInventory().getItemInMainHand();
        PrinterService.PrintResult r = plugin.printers().print(player, printer, held, shiny);
        if (!r.ok()) {
            player.sendMessage(Text.of("&c" + r.error()));
            refresh();
            return;
        }
        Grade g = r.grade();
        player.sendMessage(Text.of("&b❐ Printed a " + g.symbol() + " &f" + gradeName(g) + " &bMini"
                + (r.shiny() ? " &f✦Shiny" : "") + " &7(Mint #" + r.mintNumber() + ")!"));
        player.closeInventory();
    }

    private String gradeName(Grade g) {
        return g.display();
    }

    private String pretty(String enumName) {
        String n = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
