package com.dierks.homecraft.games.cpu;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerTextures;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * An NPC's skin as a profile to put on a mannequin or a head ({@link NpcSkin}, Bukkit side).
 *
 * <p><b>Never blank.</b> A built-in skin is a texture every client already has (a profile with no account, only a
 * skin patch naming the game's own texture). A pasted texture was checked to be a skin texture before it was kept. An
 * account's skin is fetched once, off the main thread; until it comes, and for good if it can't be had (no such
 * account, no network), the NPC wears its own built-in skin and the console says why, once.
 */
public final class NpcProfiles {

    private final Plugin plugin;
    private final Logger log;
    /** Accounts' skins fetched so far, by name (lower case). */
    private final Map<String, ResolvableProfile> fetched = new ConcurrentHashMap<>();
    /** Accounts being fetched, or that couldn't be (each said once). */
    private final Set<String> asked = ConcurrentHashMap.newKeySet();

    public NpcProfiles(Plugin plugin, Logger log) {
        this.plugin = plugin;
        this.log = log;
    }

    /**
     * What {@code npc} wears now, in {@code skin}: the profile, or its own built-in skin while an account's is being
     * fetched or when it couldn't be.
     *
     * @param changed run (on the main thread) when a fetched skin arrives, so it can be put on
     */
    public ResolvableProfile profile(CpuPlayer npc, NpcSkin skin, Runnable changed) {
        try {
            if (skin instanceof NpcSkin.Texture t) {
                return ResolvableProfile.resolvableProfile()
                        .addProperty(new ProfileProperty("textures", t.value(), t.signature())).build();
            }
            if (skin instanceof NpcSkin.Account a) {
                String key = a.name().toLowerCase(java.util.Locale.ROOT);
                ResolvableProfile got = fetched.get(key);
                if (got != null) {
                    return got;
                }
                if (asked.add(key)) {
                    fetch(npc, a.name(), key, changed);
                }
                return builtIn(npc.skin());
            }
            if (skin instanceof NpcSkin.BuiltIn b) {
                return builtIn(b);
            }
        } catch (RuntimeException | LinkageError e) {
            log.warning("NPC " + CpuRoster.plain(npc.name()) + ": its chosen skin couldn't be put on (" + e
                    + ") - it wears its own built-in skin");
        }
        return builtIn(npc.skin());
    }

    /** A built-in skin: no account, only the game's own texture as the body. */
    public static ResolvableProfile builtIn(NpcSkin.BuiltIn b) {
        return ResolvableProfile.resolvableProfile().skinPatch(p -> p
                .body(Key.key(Key.MINECRAFT_NAMESPACE, b.texturePath()))
                .model(b.slim() ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC)).build();
    }

    private void fetch(CpuPlayer npc, String name, String key, Runnable changed) {
        if (plugin == null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ResolvableProfile got = null;
            String why = null;
            try {
                PlayerProfile p = Bukkit.createProfile(name);
                if (!p.complete(true) || !p.hasTextures()) {
                    why = "no Minecraft account called " + name + " with a skin could be found";
                } else {
                    got = ResolvableProfile.resolvableProfile(p);
                }
            } catch (RuntimeException | LinkageError e) {
                why = "the skin of " + name + " couldn't be fetched (" + e.getMessage() + ")";
            }
            ResolvableProfile done = got;
            String reason = why;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (done != null) {
                    fetched.put(key, done);
                    if (changed != null) {
                        changed.run();
                    }
                } else {
                    log.warning("NPC " + CpuRoster.plain(npc.name()) + ": " + reason
                            + " - it wears its own built-in skin instead");
                }
            });
        });
    }

    /** Forget fetched skins (the owner changed one): the next wearing fetches again. */
    public void forget() {
        fetched.clear();
        asked.clear();
    }

    /** A head wearing {@code profile} with this name and lore, for a screen (a plain head where it can't be drawn). */
    public static ItemStack head(ResolvableProfile profile, String name, String... lore) {
        ItemStack head = com.dierks.homecraft.gui.Menus.icon(Material.PLAYER_HEAD, name, lore);
        try {
            head.setData(DataComponentTypes.PROFILE, profile);
        } catch (RuntimeException | LinkageError e) {
            // a plain head: still a picture of a person
        }
        return head;
    }
}
