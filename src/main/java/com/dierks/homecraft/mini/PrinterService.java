package com.dierks.homecraft.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.storage.CardDao;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.sql.SQLException;
import java.util.Map;

/**
 * The Mini Printer (Phase 9, Part D) — the single place a Mini is minted. A player
 * brings a sealed {@link CardItems Card}, the Printer validates filament + a fee,
 * rolls a grade from the card's odds, plays a print animation, and mints a graded
 * Mini through the standard cap-aware, anti-dupe mint pipeline
 * ({@link MiniService#mintGraded}). The Card is consumed per print (no print cap).
 *
 * <p>A printer flagged <b>public</b> (a Mall printer) prints for free — the house
 * covers filament and the fee, and only basic finishes are available (no Shiny). A
 * private/home printer charges filament + fee and unlocks the Shiny finish.
 */
public final class PrinterService {

    /** Outcome of a print attempt (a failure carries a player-facing reason). */
    public record PrintResult(boolean ok, String error, Grade grade, long mintNumber, boolean shiny) {
        static PrintResult fail(String error) {
            return new PrintResult(false, error, null, 0, false);
        }
    }

    private final HomeCraftManagement plugin;
    private final CardDao cardDao;

    public PrinterService(HomeCraftManagement plugin, CardDao cardDao) {
        this.plugin = plugin;
        this.cardDao = cardDao;
    }

    /** @return true if the printer at this location is a free public (Mall) printer. */
    public boolean isPublic(Location loc) {
        try {
            return cardDao.isPublicPrinter(loc);
        } catch (SQLException e) {
            return false;
        }
    }

    /** Flag / unflag a printer as a free public (Mall) printer. */
    public void setPublic(Location loc, boolean isPublic) {
        try {
            cardDao.setPublicPrinter(loc, isPublic);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to set public printer flag: " + e.getMessage());
        }
    }

    /**
     * Try to print the held card. On a public printer this is free (Shiny is refused);
     * on a private printer it consumes the card's filament + the fee, plus the Shiny
     * finish material when {@code shiny} is requested. Everything is validated before
     * anything is consumed, so a failure leaves the player's items untouched.
     */
    public PrintResult print(Player player, Location printer, ItemStack card, boolean shiny) {
        String id = plugin.miniService().cardItems().cardIdOf(card);
        if (id == null) {
            return PrintResult.fail("Hold a Card in your main hand to print.");
        }
        MiniDef def = plugin.miniService().def(id);
        if (def == null) {
            return PrintResult.fail("That Card's Mini no longer exists.");
        }
        // The Printer is the mint source, so it honours the Mini cap up front.
        var counts = plugin.miniService().counts(id);
        if (!def.uncapped() && counts.minted() >= def.cap()) {
            return PrintResult.fail(def.name() + " is minted out — no more can be printed.");
        }

        boolean free = isPublic(printer);
        boolean wantShiny = shiny && !free; // public printers offer basic finishes only
        CardSpec spec = plugin.miniService().cardSpec(def);
        PluginConfig.Printer cfg = plugin.config().printer();
        var filaments = plugin.miniService().filamentItems();

        if (!free) {
            // 1) Filament: enough of every required colour?
            for (Map.Entry<DyeColor, Integer> e : spec.filament().entrySet()) {
                int need = e.getValue() == null ? 0 : e.getValue();
                if (need > 0 && filaments.count(player, e.getKey()) < need) {
                    return PrintResult.fail("You need " + plugin.miniService().cardItems().filamentCost(spec)
                            + " filament to print this.");
                }
            }
            // 2) Shiny finish material (private printer only).
            if (wantShiny && cfg.shinyAmount() > 0
                    && filaments.count(player, cfg.shinyDye()) < cfg.shinyAmount()) {
                return PrintResult.fail("A Shiny finish needs " + cfg.shinyAmount() + " "
                        + pretty(cfg.shinyDye().name()) + " Filament.");
            }
            // 3) Money fee.
            double fee = cfg.fee() + (wantShiny ? cfg.shinyFee() : 0);
            if (fee > 0) {
                if (!plugin.economy().isEnabled()) {
                    return PrintResult.fail("The economy is offline — can't charge the print fee.");
                }
                if (!plugin.economy().has(player, fee)) {
                    return PrintResult.fail("You can't afford the " + plugin.economy().format(fee) + " print fee.");
                }
            }

            // Everything validated — now consume filament, shiny material, and the fee.
            for (Map.Entry<DyeColor, Integer> e : spec.filament().entrySet()) {
                int need = e.getValue() == null ? 0 : e.getValue();
                if (need > 0) {
                    filaments.consume(player, e.getKey(), need);
                }
            }
            if (wantShiny && cfg.shinyAmount() > 0) {
                filaments.consume(player, cfg.shinyDye(), cfg.shinyAmount());
            }
            if (fee > 0) {
                plugin.economy().withdraw(player, fee);
            }
        }

        // Roll the grade and mint through the single cap-aware pipeline.
        Grade grade = plugin.miniService().rollGrade(spec);
        String finish = wantShiny ? "SHINY" : null;
        MiniService.MintResult mr = plugin.miniService().mintGraded(player, id, grade, finish);
        if (!mr.ok()) {
            return PrintResult.fail(mr.error());
        }

        consumeOneCard(player, card);
        animate(printer, def, grade, wantShiny);
        return new PrintResult(true, null, grade, mr.mintNumber(), wantShiny);
    }

    private void consumeOneCard(Player player, ItemStack card) {
        // Prefer the exact stack the menu handed us; fall back to any matching card in hand.
        if (card != null && card.getAmount() > 0
                && player.getInventory().getItemInMainHand().isSimilar(card)) {
            ItemStack held = player.getInventory().getItemInMainHand();
            held.setAmount(held.getAmount() - 1);
            return;
        }
        if (card != null) {
            card.setAmount(Math.max(0, card.getAmount() - 1));
        }
    }

    /**
     * A short print animation: a rising ItemDisplay of the Mini above the printer,
     * a particle column, and escalating sound — capped by a completion burst. Purely
     * cosmetic; the minted Mini is already in the player's inventory.
     */
    private void animate(Location printer, MiniDef def, Grade grade, boolean shiny) {
        World world = printer.getWorld();
        if (world == null) {
            return;
        }
        Location base = printer.toCenterLocation().add(0, 1.0, 0);
        ItemStack head = plugin.miniService().icon(def);
        ItemDisplay display;
        try {
            display = world.spawn(base, ItemDisplay.class, d -> {
                d.setItemStack(head);
                d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                Transformation t = d.getTransformation();
                d.setTransformation(new Transformation(
                        new Vector3f(0f, 0f, 0f), t.getLeftRotation(),
                        new Vector3f(0.6f, 0.6f, 0.6f), t.getRightRotation()));
            });
        } catch (Throwable t) {
            display = null; // display entities unavailable — fall back to particles only
        }
        final ItemDisplay disp = display;

        world.playSound(base, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.4f);
        final int[] tick = {0};
        final int steps = 24;
        org.bukkit.scheduler.BukkitTask[] taskRef = new org.bukkit.scheduler.BukkitTask[1];
        taskRef[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int i = tick[0]++;
            double y = 0.9 * (i / (double) steps);
            Location p = base.clone().add(0, y, 0);
            world.spawnParticle(Particle.END_ROD, p, 3, 0.12, 0.05, 0.12, 0.0);
            if (i % 3 == 0) {
                world.spawnParticle(Particle.HAPPY_VILLAGER, p, 4, 0.2, 0.2, 0.2, 0.0);
                float pitch = 0.8f + 1.2f * (i / (float) steps);
                world.playSound(base, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, pitch);
            }
            if (disp != null && disp.isValid()) {
                Transformation tr = disp.getTransformation();
                disp.setTransformation(new Transformation(
                        new Vector3f(0f, (float) y, 0f), tr.getLeftRotation(),
                        tr.getScale(), tr.getRightRotation()));
            }
            if (i >= steps) {
                Location top = base.clone().add(0, 0.9, 0);
                world.spawnParticle(Particle.FIREWORK, top, 30, 0.3, 0.3, 0.3, 0.08);
                world.playSound(top, shiny ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP,
                        0.8f, grade == Grade.GOLD ? 1.3f : 1.0f);
                if (disp != null && disp.isValid()) {
                    disp.remove();
                }
                taskRef[0].cancel();
            }
        }, 1L, 1L);
    }

    private String pretty(String enumName) {
        String n = enumName.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
