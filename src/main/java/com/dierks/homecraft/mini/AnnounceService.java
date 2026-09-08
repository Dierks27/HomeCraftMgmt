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
 * whenever a Mini is minted by a wild drop, natural spawn, or crate (the name is a
 * hover card showing the item's full tooltip and click-runs {@code /hcm museum
 * <id>}), a coordinate-free hint when a natural spawn lands, and a "slipped away"
 * line when one despawns untouched. Every broadcast plays a short sound to
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

    /** "A &lt;Rarity&gt; Mini has spawned within 100 blocks of a player! Good luck!" — no coordinates, no names. */
    public void spawnHint(Rarity rarity) {
        PluginConfig.Announce a = plugin.config().announce();
        if (a == null || !a.spawnHint() || !a.allows(rarity)) {
            return;
        }
        RarityStyle style = plugin.miniService().style(rarity);
        Component msg = Component.text("A ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(pretty(rarity.name()), style.nameColor()))
                .append(Component.text(" Mini has spawned " + a.hintRadiusText() + "! Good luck!",
                        NamedTextColor.LIGHT_PURPLE));
        broadcast(msg);
    }

    /** "The Mini slipped away..." — a natural spawn despawned untouched. */
    public void slippedAway(Rarity rarity) {
        PluginConfig.Announce a = plugin.config().announce();
        if (a == null || !a.slippedAway() || !a.allows(rarity)) {
            return;
        }
        broadcast(Text.of("&7The Mini slipped away..."));
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

    private static String pretty(String enumName) {
        String n = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
