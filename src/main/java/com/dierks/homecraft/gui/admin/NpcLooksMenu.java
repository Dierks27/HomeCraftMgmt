package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.games.cpu.NpcProfiles;
import com.dierks.homecraft.games.cpu.NpcSkin;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.MenuStyle;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * The NPC players' looks (the admin screen; the owner: "Can I pick some of the Skins?"). Two screens in one class:
 * the roster (each NPC as a head wearing its skin, its name with the gear and stars), and one NPC's looks: Minecraft's
 * built-in skins as heads (wide on one row, slim on the next), "Type a Minecraft name" and "Paste a texture" (both
 * typed in chat), Rename, and its own skin back. The same as {@code /hcm games npc name|skin}.
 *
 * <pre>
 *   roster:   10-15 the NPCs · 22 back                                    (27 slots)
 *   one NPC:  4 it now · 9-17 the built-in skins, wide · 18-26 slim
 *             29 a Minecraft name · 31 a pasted texture · 33 rename · 35 its own skin · 49 back   (54 slots)
 * </pre>
 */
public final class NpcLooksMenu extends Menu {

    private final Player admin;
    private final CpuPlayer npc;
    private final Runnable back;

    /** The roster ({@code npc} null) or one NPC's looks. */
    public NpcLooksMenu(HomeCraftManagement plugin, Player admin, CpuPlayer npc, Runnable back) {
        super(plugin);
        this.admin = admin;
        this.npc = npc;
        this.back = back;
        init(npc == null ? 27 : 54, Text.of(npc == null ? MenuStyle.title("NPC players") : MenuStyle.title("NPC", npc.key())));
    }

    private CpuPlayers npcs() {
        return CpuPlayers.any(plugin.games());
    }

    @Override
    protected void build() {
        int size = getInventory().getSize();
        for (int i = 0; i < size; i++) {
            set(i, Menus.FILLER, null);
        }
        set(size - 5, Menus.icon(Material.BARRIER, "&cBack"), e -> {
            if (back != null) {
                back.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
        CpuPlayers npcs = npcs();
        if (npcs == null) {
            set(13, Menus.icon(Material.GRAY_DYE, "&7The games aren't running"), null);
            return;
        }
        if (npc == null) {
            for (int i = 0; i < CpuRoster.ALL.size() && i < 7; i++) {
                CpuPlayer c = CpuRoster.ALL.get(i);
                set(10 + i, NpcProfiles.head(npcs.profile(c), npcs.label(c),
                        "&7" + c.personality().name().toLowerCase(Locale.ROOT) + ", wears " + npcs.skin(c).describe(),
                        "&eClick to change its skin or name"), e -> sub(c));
            }
            return;
        }
        set(4, NpcProfiles.head(npcs.profile(npc), npcs.label(npc), "&7Wears " + npcs.skin(npc).describe() + "."), null);
        for (int i = 0; i < NpcSkin.BUILT_IN.size(); i++) {
            String name = NpcSkin.BUILT_IN.get(i);
            for (boolean slim : new boolean[]{false, true}) {
                NpcSkin.BuiltIn b = new NpcSkin.BuiltIn(name, slim);
                boolean on = b.equals(npcs.skin(npc));
                set((slim ? 18 : 9) + i, Menus.glint(NpcProfiles.head(NpcProfiles.builtIn(b), "&f" + b.describe(),
                        on ? "&aWearing it now" : "&eClick to wear it"), on), e -> wear(b));
            }
        }
        set(29, Menus.icon(Material.NAME_TAG, "&eA Minecraft player's skin", "&7Type their Minecraft name in chat.",
                "&7If it can't be fetched, the NPC", "&7wears its own skin."), e -> plugin.chatPrompts().prompt(admin,
                "Whose skin? (a Minecraft Java name)", input -> parse("player:" + input.trim())));
        set(31, Menus.icon(Material.PAPER, "&eA pasted skin texture", "&7Paste the long Value from a skin site",
                "&7(and its Signature after a space)."), e -> plugin.chatPrompts().prompt(admin,
                "Paste the texture Value (then a space and the Signature, if you have one):",
                input -> parse("texture:" + input.trim())));
        set(33, Menus.icon(Material.WRITABLE_BOOK, "&eRename", "&7The gear " + CpuRoster.MARKER
                + " and stars always stay."), e -> plugin.chatPrompts().prompt(admin,
                "New name for " + npcs.name(npc) + "? (up to " + CpuRoster.MAX_NAME + " letters; 'reset' for its own)",
                input -> {
                    CpuPlayers n = npcs();
                    String now = n == null ? null : n.rename(npc, input.trim().equalsIgnoreCase("reset") ? null : input);
                    admin.sendMessage(Text.of(now == null ? "&cThat name has nothing left to show."
                            : "&aIt is now " + n.label(npc) + "&a."));
                    logged("name " + npc.key() + " " + now);
                    reopen();
                }));
        set(35, Menus.icon(Material.ARMOR_STAND, "&eIts own skin", "&7" + npc.skin().describe()), e -> wear(null));
    }

    private void sub(CpuPlayer c) {
        new NpcLooksMenu(plugin, admin, c, this::reopen).open(admin);
    }

    private void wear(NpcSkin skin) {
        CpuPlayers npcs = npcs();
        if (npcs == null) {
            return;
        }
        npcs.reskin(npc, skin);
        logged("skin " + npc.key() + " " + (skin == null ? "reset" : skin instanceof NpcSkin.Texture ? "texture"
                : skin.text()));
        refresh();
    }

    private void parse(String typed) {
        try {
            NpcSkin skin = NpcSkin.parse(typed);
            wear(skin);
            admin.sendMessage(Text.of("&aIt now wears " + skin.describe() + "."));
        } catch (IllegalArgumentException e) {
            admin.sendMessage(Text.of("&c" + e.getMessage()));
        }
        reopen();
    }

    private void logged(String what) {
        plugin.getLogger().info("[Games] " + admin.getName() + " (admin screen): /hcm games npc " + what);
    }

    private void reopen() {
        new NpcLooksMenu(plugin, admin, npc, back).open(admin);
    }
}
