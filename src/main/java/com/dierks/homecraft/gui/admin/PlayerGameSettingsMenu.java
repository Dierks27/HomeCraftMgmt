package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.NpcMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Player game settings (the admin screen): pick a player, then set their NPC players setting and lock it (the owner,
 * for Lilah: easy). The players online, each by their head, and "Type a name" for someone who isn't on. For now it
 * holds only the NPC setting; the same as {@code /hcm games npc player <player> ...}.
 *
 * <pre>
 *   rows 0-4  the players online (up to 45)
 *   row 5     48 type a name · 49 back
 * </pre>
 */
public final class PlayerGameSettingsMenu extends Menu {

    /** Who may use it: the games' admins (parents included). */
    public static final String PERMISSION = "hcm.games.admin";

    private final Player admin;
    private final Runnable back;

    public PlayerGameSettingsMenu(HomeCraftManagement plugin, Player admin, Runnable back) {
        super(plugin);
        this.admin = admin;
        this.back = back;
        init(54, Text.of("&ePlayer game settings"));
    }

    @Override
    protected void build() {
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        for (int i = 0; i < online.size() && i < 45; i++) {
            Player p = online.get(i);
            set(i, head(p), e -> open(p));
        }
        set(48, Menus.icon(Material.NAME_TAG, "&eType a player's name", "&7For someone who isn't on now."),
                e -> plugin.chatPrompts().prompt(admin, "Which player? (their Minecraft name)", input -> {
                    OfflinePlayer p = Bukkit.getOfflinePlayerIfCached(input.trim());
                    if (p == null || !(p.hasPlayedBefore() || p.isOnline())) {
                        admin.sendMessage(Text.of("&cNo player called " + input.trim() + " has played here."));
                        reopen();
                        return;
                    }
                    open(p);
                }));
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> {
            if (back != null) {
                back.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    private static ItemStack head(Player p) {
        ItemStack head = Menus.icon(Material.PLAYER_HEAD, "&f" + p.getName(), "&7NPC players: on or off, and how",
                "&7they play in their games.", "&eClick to choose");
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(p);
            head.setItemMeta(skull);
        }
        return head;
    }

    private void open(OfflinePlayer p) {
        if (!admin.hasPermission(PERMISSION)) {
            admin.sendMessage(Text.of("&cOnly a games admin can set that."));
            return;
        }
        String name = p.getName() == null ? "that player" : p.getName();
        new NpcMenu(plugin, admin, p.getUniqueId(), name, true, this::reopen).open(admin);
    }

    private void reopen() {
        new PlayerGameSettingsMenu(plugin, admin, back).open(admin);
    }
}
