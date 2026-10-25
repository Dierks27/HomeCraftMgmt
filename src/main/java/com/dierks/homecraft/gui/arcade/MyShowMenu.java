package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.FireworkShows;
import com.dierks.homecraft.arcade.HolidayShows;
import com.dierks.homecraft.arcade.MegaShow.Spot;
import com.dierks.homecraft.arcade.MegaShowService;
import com.dierks.homecraft.arcade.RemoteRules;
import com.dierks.homecraft.arcade.RemoteService;
import com.dierks.homecraft.gui.ConfirmMenu;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * My Show (0.38.2): the launchers in a player's Firework Remote set, opened by tapping the air or the ground with the
 * Remote (on Bedrock, the ground: a tap at the sky may send nothing). A chest screen, so it works the same on Bedrock;
 * the facts are in each tile's NAME, as Bedrock shows lore only on a long press.
 *
 * <p>Each of up to ten tiles is that number's wool (its colour is the hologram's and the Show Map flag's; the stack is
 * the number), named with where it is from here, or "not loaded", or the world it is in, then its x/y/z and whose it
 * is ({@link RemoteRules#tileName}; review REMOTE-MYSHOW-5: the coordinates and the owner were in the lore only).
 * "Not loaded" is the show's own test ({@link MegaShowService#ticking}: loaded and entity-ticking, review
 * LINK-LOADED-1), so a tile says "here" only for a launcher a show would fire from now. The tiles are for looking
 * only: a child's tap here never unlinks one (the Remote on the launcher does). "Get my Show Map" gives the map,
 * "Clear all" empties the set after an "are you sure?".
 */
public final class MyShowMenu extends Menu {

    /** The ten numbers' slots: two rows of five. */
    private static final int[] SLOTS = {19, 20, 21, 22, 23, 28, 29, 30, 31, 32};

    private final Player player;

    public MyShowMenu(HomeCraftManagement plugin, Player player) {
        super(plugin);
        this.player = player;
        init(54, Text.of("&5My Show"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        RemoteService remotes = plugin.remotes();
        List<Spot> set = remotes == null ? List.of() : remotes.linked(player.getUniqueId());
        HolidayShows.Theme theme = FireworkShows.themeToday(plugin);
        List<String> head = new ArrayList<>();
        if (set.isEmpty()) {
            head.add("&7Nothing linked yet: a show uses the launchers nearest you.");
        }
        head.add("&7Tap a Firework Launcher with your Remote to add it (up to " + RemoteRules.MAX_LINKED + ").");
        head.add("&7Tap it again to take it off. #1 fires first.");
        head.add("&7Mega uses #1-#3, Super #1-#4, Ultra #1-#5, Ultimate all 10.");
        if (theme != null) {
            head.add("&6" + theme.name() + " colours tonight!");
        }
        set(4, Menus.icon(Material.RECOVERY_COMPASS, "&eMy Show &7· " + set.size() + " linked"
                + (theme != null ? " &6· " + theme.name() + " colours tonight!" : ""),
                head.toArray(new String[0])), null);

        World here = player.getWorld();
        Location at = player.getLocation();
        UUID me = player.getUniqueId();
        Map<Spot, UUID> owners = remotes == null || set.isEmpty() ? Map.of() : remotes.records(); // one read
        for (int i = 0; i < SLOTS.length; i++) {
            int n = i + 1;
            if (i >= set.size()) {
                set(SLOTS[i], Menus.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&8#" + n + " · empty"), null);
                continue;
            }
            Spot s = set.get(i);
            set(SLOTS[i], tile(n, s, here, at, me, owners.get(s), remotes), null); // info only: never unlinks
        }

        set(47, Menus.icon(Material.FILLED_MAP, "&aGet my Show Map",
                "&7A map of where you are: your show's flags,", "&7numbered, and your other launchers.",
                "&7It fills in as you walk with it."), e -> {
            if (remotes != null) {
                remotes.giveMap(player);
            }
            refresh();
        });
        set(49, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
        if (!set.isEmpty() && remotes != null) {
            set(51, Menus.icon(Material.BUCKET, "&cClear all", "&7Take every launcher off your show."), e ->
                    new ConfirmMenu(plugin, "&cClear your show?", Menus.icon(Material.BUCKET, "&cClear all",
                            "&7" + set.size() + " linked now."), List.of("&7A show then uses the launchers",
                            "&7nearest you, as before."), "&7Click to clear it.", () -> {
                        remotes.clear(player);
                        new MyShowMenu(plugin, player).open(player);
                    }, () -> new MyShowMenu(plugin, player).open(player)).open(player));
        }
    }

    /** Linked launcher {@code n}'s tile: where it is from {@code at}, and whose it is, all in its name. */
    private ItemStack tile(int n, Spot s, World here, Location at, UUID me, UUID owner, RemoteService remotes) {
        Material wool = Material.matchMaterial(RemoteRules.wool(n));
        boolean sameWorld = s.world().equals(here.getName());
        boolean ready = sameWorld && MegaShowService.ticking(here, s); // asks the chunk first: never loads it
        String whose = owner == null ? null : owner.equals(me) ? "yours" : remotes.nameOf(owner) + "'s";
        String name = RemoteRules.tileName(n, s, here.getName(), at.getX(), at.getZ(), ready, whose);
        List<String> lore = new ArrayList<>();
        lore.add("&7In " + s.world());
        if (!ready) {
            lore.add("&7It only fires while someone is near it.");
        }
        ItemStack tile = Menus.icon(wool == null ? Material.WHITE_WOOL : wool, name, lore.toArray(new String[0]));
        tile.setAmount(n);
        return tile;
    }
}
