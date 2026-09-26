package com.dierks.homecraft.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * Server-wide Mini announcements (§3.4, Phase 12): a one-line "found" broadcast
 * whenever a Mini is minted by a wild drop or caught in the wild hunt (the name is a
 * hover card showing the item's full tooltip and click-runs {@code /hcm museum
 * <id>}), the wild hunt's coordinate-free hints, and a "got away" line when a hunt
 * ends without a catch. Every broadcast plays a short sound to
 * everyone. Gated by {@code minis.announce} (global, per-kind, min rarity, per rarity).
 */
public final class AnnounceService {

    private final HomeCraftManagement plugin;

    public AnnounceService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** "&lt;finder&gt; found a [Mini] while mining!" — never sent for Printer/pack mints. */
    public void found(Player finder, MiniDef def, ItemStack item, String verb) {
        PluginConfig.Announce a = plugin.config().announce();
        if (a == null || !a.found() || !a.allows(def.rarity())) {
            return;
        }
        RarityStyle style = plugin.miniService().style(def.rarity());
        String label = "[" + def.name() + (item != null ? " " + plugin.miniService().gradeOf(item).symbol() : "") + "]";
        Component name = Component.text(label, style.nameColor())
                .clickEvent(ClickEvent.runCommand("/hcm museum " + def.id()));
        if (item != null) {
            try {
                name = name.hoverEvent(item.asHoverEvent());
            } catch (Throwable ignored) {
                // hover unavailable — the link still works
            }
        }
        Component msg = Component.text(finder.getName(), NamedTextColor.GOLD)
                .append(Component.text(" found a ", NamedTextColor.GRAY))
                .append(name)
                .append(Component.text(" " + (verb == null ? "somewhere" : verb) + "!", NamedTextColor.GRAY));
        broadcast(msg);
    }

    /**
     * One wild-hunt hint, to everyone: the opening "A Rare Mini appeared near Kaden!" and each
     * escalating hint after it (biome, direction, the beam). {@code legacy} is already rendered
     * by the hunt — rarity coloured, placeholders filled, never a coordinate.
     */
    public void hint(Rarity rarity, String legacy) {
        PluginConfig.Announce a = plugin.config().announce();
        if (a == null || !a.spawnHint() || !a.allows(rarity) || legacy == null || legacy.isBlank()) {
            return;
        }
        broadcast(Text.of(legacy));
    }

    /** "The Rare Mini got away!" — a wild spawn ended without a catch. */
    public void escaped(Rarity rarity) {
        PluginConfig.Announce a = plugin.config().announce();
        if (a == null || !a.slippedAway() || !a.allows(rarity)) {
            return;
        }
        broadcast(Text.of("&7The " + plugin.miniService().rarityText(rarity) + " &7Mini got away!"));
    }

    private void broadcast(Component msg) {
        Bukkit.broadcast(msg);
        Sound sound = sound(plugin.config().announce().sound());
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                p.playSound(p.getLocation(), sound, 0.7f, 1.2f);
            } catch (Throwable ignored) {
                // a client-side sound is never worth an error
            }
        }
    }

    /** Resolve a config sound name (e.g. ENTITY_EXPERIENCE_ORB_PICKUP or minecraft:entity.experience_orb.pickup). */
    public static Sound sound(String name) {
        Sound fallback = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            String key = name.trim().toLowerCase(Locale.ROOT);
            if (!key.contains(":")) {
                key = key.replace('_', '.');
            }
            NamespacedKey nk = key.contains(":") ? NamespacedKey.fromString(key) : NamespacedKey.minecraft(key);
            Sound s = nk == null ? null : Registry.SOUNDS.get(nk);
            return s != null ? s : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
