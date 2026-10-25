package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.MegaShow.Spot;
import com.dierks.homecraft.block.CustomBlockService;
import com.dierks.homecraft.block.CustomBlockType;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.gui.arcade.MyShowMenu;
import com.dierks.homecraft.storage.PlacedBlock;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.TokenPrizes;
import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.CartographyInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Firework Remote (0.38.2; the owner: "a launcher tool that we can assign the launchers that we want to use in the
 * show ... I can build launchers around the city, leave them there, and when I buy the 5 launcher show ... simply
 * tag/click on the launchers I want to use"): each player's show set, and everything that shows it.
 *
 * <ul>
 *   <li><b>The set</b>: a tap on a launcher with the Remote links it (the tap order is the firing order: #1 is the
 *       first lane), a tap on a linked one unlinks it and the later ones move up, up to {@value RemoteRules#MAX_LINKED}
 *       ({@link #tap}). Anyone's launchers can be linked, as the shows already use anyone's. It is kept in
 *       {@code game_prefs} ({@link RemoteRules#PREF}), read once a player and cached, pruned at join and at use against
 *       the placed-block records (a WorldEdit clear), and a launcher that breaks leaves every set at once
 *       ({@link #launcherGone}). The Remote never fires anything by itself: a Mega, Super, Ultra or Ultimate show
 *       fires from the set ({@link MegaShowService}).</li>
 *   <li><b>Seeing which is which</b>: the numbered holograms while the Remote is held ({@link RemoteMarkers}), the
 *       Remote's own name and lore (a line a launcher, way and distance from where the player stands when they pick
 *       it up or swap to it: {@link #refreshRemotes}), the launcher's tap line ("Linked as #2 in your show."), the My
 *       Show screen ({@link #openMyShow}) and the Show Map ({@link #giveMap}), all in the same ten colours.</li>
 * </ul>
 *
 * <p>All on the main thread. A pref read or write is one indexed row; a broken launcher reads every set's row once.
 * Nothing here loads a chunk.
 */
public final class RemoteService implements Listener {

    /** The marker pass, in ticks. */
    private static final long MARKER_TICKS = 10;

    private final HomeCraftManagement plugin;
    /** Each player's set, in link order (a cache of their row). */
    private final Map<UUID, List<Spot>> sets = new HashMap<>();
    /** Bumped on any change a Show Map must redraw: the owner's set, or their own launchers. */
    private final Map<UUID, Integer> versions = new HashMap<>();
    /** Who placed each launcher seen (from the records), for the holograms' and My Show's owner lines. */
    private final Map<Spot, UUID> owners = new HashMap<>();
    /** Names by UUID, for the owner lines. */
    private final Map<UUID, String> names = new HashMap<>();
    /** Each player's Show Map id. */
    private final Map<UUID, Integer> mapIds = new HashMap<>();
    /** The renderer on each Show Map, by map id. */
    private final Map<Integer, ShowMapRenderer> renderers = new HashMap<>();
    /** Players whose row a newer HomeCraft wrote, already warned about. */
    private final Set<UUID> warnedNewer = new HashSet<>();
    /** Each Show Map owner's last dropped copy (its item entity), taken away when the map is given again. */
    private final Map<UUID, UUID> droppedMaps = new HashMap<>();
    private final RemoteRules.Taps taps = new RemoteRules.Taps();
    /** Players whose set couldn't be read: not read again until a pause is over. */
    private final RemoteRules.Retries unread = new RemoteRules.Retries();
    private final RemoteMarkers markers;
    private BukkitTask timer;

    public RemoteService(HomeCraftManagement plugin) {
        this.plugin = plugin;
        this.markers = new RemoteMarkers(plugin, this);
    }

    // ---- the set --------------------------------------------------------------------------------------

    /**
     * {@code player}'s set in link order: cached, else their row, read ({@link RemoteRules#parse}) and pruned of
     * launchers with no record; a row that had to be cleaned is written back (one WARN), and so redrawn on their map. A
     * read that fails is an empty set for now and isn't cached: it is read again only after a pause, and only the
     * first failure in a row warns ({@link RemoteRules.Retries}, review REMOTE-PREF-6).
     */
    public List<Spot> linked(UUID player) {
        List<Spot> cached = sets.get(player);
        if (cached != null) {
            return cached;
        }
        PrizeService prizes = plugin.prizes();
        if (prizes == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        if (!unread.due(player, now)) {
            return List.of(); // a read failed a moment ago: not again on every marker pass
        }
        String raw;
        try {
            raw = prizes.dao().pref(player, RemoteRules.PREF);
        } catch (SQLException e) {
            if (unread.failed(player, now)) {
                plugin.getLogger().warning("Couldn't read " + nameOf(player) + "'s Firework Remote show (tried again"
                        + " every " + RemoteRules.Retries.RETRY_MS / 1000 + " s, quietly): " + e.getMessage());
            }
            return List.of();
        }
        unread.ok(player);
        RemoteRules.Parsed parsed = RemoteRules.parse(raw);
        if (parsed.newer() && warnedNewer.add(player)) {
            plugin.getLogger().warning(nameOf(player) + "'s Firework Remote show was saved by a newer HomeCraft: it is"
                    + " empty here, and kept as it is until they change it.");
        }
        List<Spot> kept = prune(parsed.spots());
        if (parsed.cleaned() || kept.size() != parsed.spots().size()) {
            if (parsed.cleaned()) {
                plugin.getLogger().warning("Cleaned " + nameOf(player) + "'s Firework Remote show: dropped what"
                        + " couldn't be read (or past " + RemoteRules.MAX_LINKED + ").");
            }
            save(player, kept);
        }
        sets.put(player, kept);
        return kept;
    }

    /** {@code s}'s number in {@code player}'s set (1 fires first), or 0. */
    public int linkedAs(UUID player, Spot s) {
        return RemoteRules.linkedAs(linked(player), s);
    }

    /** The version of {@code owner}'s set and launchers a Show Map last needs to match. */
    int version(UUID owner) {
        return versions.getOrDefault(owner, 0);
    }

    /** Who placed the launcher at {@code s}, as last read from the records, or null. */
    public UUID ownerOf(Spot s) {
        return owners.get(s);
    }

    /** {@code id}'s name, or "Someone" when the server doesn't know it. */
    public String nameOf(UUID id) {
        return names.computeIfAbsent(id, k -> {
            String n = plugin.getServer().getOfflinePlayer(k).getName();
            return n == null || n.isBlank() ? "Someone" : n;
        });
    }

    /** Every launcher {@code owner} has placed (one records read; never per frame). */
    public List<Spot> ownLaunchers(UUID owner) {
        List<Spot> out = new ArrayList<>();
        for (Map.Entry<Spot, UUID> e : records().entrySet()) {
            if (e.getValue().equals(owner)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Every launcher record, by spot: who placed it (and the owners cache refreshed from it). One records read. */
    public Map<Spot, UUID> records() {
        CustomBlockService blocks = plugin.blockService();
        Map<Spot, UUID> out = new LinkedHashMap<>();
        if (blocks == null) {
            return out;
        }
        for (PlacedBlock pb : blocks.findByType(CustomBlockType.FIREWORK_LAUNCHER)) {
            out.put(new Spot(pb.world(), pb.x(), pb.y(), pb.z()), pb.owner());
        }
        owners.putAll(out);
        return out;
    }

    /**
     * {@code set} without the launchers that have no record. No records at all reads as a records read that failed
     * ({@code findByType} is empty on a database error), never as every launcher gone: the set is kept as it is.
     */
    private List<Spot> prune(List<Spot> set) {
        if (set.isEmpty() || plugin.blockService() == null) {
            return set;
        }
        Map<Spot, UUID> recorded = records();
        if (recorded.isEmpty()) {
            return set;
        }
        List<Spot> out = new ArrayList<>();
        for (Spot s : set) {
            if (recorded.containsKey(s)) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Write {@code player}'s set; false (one WARN) when it couldn't be. Every write of a set goes through here and
     * bumps its version, so their Show Map redraws whoever changed it and wherever they are (review REMOTE-MAP-1: a
     * set changed while they were away, or pruned at their join, kept its old flags on a map that stayed loaded).
     */
    private boolean save(UUID player, List<Spot> set) {
        PrizeService prizes = plugin.prizes();
        if (prizes == null) {
            return false;
        }
        try {
            prizes.dao().setPref(player, RemoteRules.PREF, RemoteRules.format(set, plugin.getLogger()::warning));
            bump(player);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("Couldn't save " + nameOf(player) + "'s Firework Remote show: " + e.getMessage());
            return false;
        }
    }

    private void bump(UUID owner) {
        versions.merge(owner, 1, Integer::sum);
    }

    // ---- tapping ---------------------------------------------------------------------------------------

    /**
     * A tap on the launcher {@code launcher} with the Remote: links it at the end of the set, or unlinks it (the later
     * ones move up), or says the set is full. A held tap counts once ({@link RemoteRules.Taps}). A write that fails
     * changes nothing and says so.
     */
    public void tap(Player player, Block launcher) {
        UUID id = player.getUniqueId();
        Spot spot = spotOf(launcher);
        if (!taps.counts(id, spot, System.currentTimeMillis())) {
            return;
        }
        List<Spot> set = linked(id);
        if (!sets.containsKey(id)) {
            // the set couldn't be read: a link now would write a one-launcher set over the one that is there
            player.sendMessage(Text.of(RemoteRules.NOT_SAVED));
            Sounds.refused(player);
            return;
        }
        RemoteRules.Toggle t = RemoteRules.toggle(set, spot);
        if (t.result() == RemoteRules.Result.FULL) {
            player.sendMessage(Text.of(RemoteRules.FULL));
            Sounds.refused(player);
            return;
        }
        if (!save(id, t.set())) {
            player.sendMessage(Text.of(RemoteRules.NOT_SAVED));
            Sounds.refused(player);
            return;
        }
        sets.put(id, t.set()); // the save bumped the version: their map redraws
        if (t.result() == RemoteRules.Result.LINKED) {
            rememberOwner(launcher, spot);
            player.sendMessage(Text.of(RemoteRules.linkedLine(t.number(), t.set().size())));
            Sounds.received(player);
        } else {
            player.sendMessage(Text.of(RemoteRules.unlinkedLine(t.number(), t.set().size(),
                    t.number() > t.set().size())));
            Sounds.collected(player);
        }
        changed(player);
    }

    /**
     * A tap on the launcher {@code launcher} with the Remote in the off hand and nothing of ours in the main hand
     * ({@link RemoteRules#offHandTap}, review REMOTE-TAP-3), from {@code CustomBlockListener}: as {@link #tap}, and
     * nothing in a game, as for the main hand's Remote. A sneaking tap also sends the off hand's own use, which
     * {@link RemoteRules.Taps} counts as the same tap.
     */
    public void tapFromOffHand(Player player, Block launcher) {
        if (!com.dierks.homecraft.util.Whereabouts.inGameSession(plugin, player)) {
            tap(player, launcher);
        }
    }

    /** A tap on the air or a plain block with the Remote: the My Show screen (once for a held tap). */
    public void openMyShow(Player player) {
        if (!taps.counts(player.getUniqueId(), RemoteRules.Taps.SCREEN, System.currentTimeMillis())) {
            return;
        }
        new MyShowMenu(plugin, player).open(player);
    }

    /** My Show's Clear all: the set is empty (a show uses the nearest launchers again). False when it couldn't be. */
    public boolean clear(Player player) {
        UUID id = player.getUniqueId();
        if (!save(id, List.of())) {
            player.sendMessage(Text.of(RemoteRules.NOT_SAVED));
            Sounds.refused(player);
            return false;
        }
        sets.put(id, List.of());
        player.sendMessage(Text.of(RemoteRules.CLEARED));
        changed(player);
        return true;
    }

    /** Everything that shows {@code player}'s set, after it changed: their Remotes, holograms and Show Map. */
    private void changed(Player player) {
        refreshRemotes(player);
        markers.update(player);
        sendMap(player);
    }

    private void rememberOwner(Block launcher, Spot spot) {
        CustomBlockService blocks = plugin.blockService();
        if (blocks != null) {
            blocks.at(launcher.getLocation()).ifPresent(pb -> owners.put(spot, pb.owner()));
        }
    }

    // ---- launchers placed and gone -------------------------------------------------------------------

    /**
     * The launcher at {@code s} is gone (a final break, or a stale record found): it leaves every set at once, the
     * later ones moving up, online from the cache (their lore, holograms and map follow) and everyone else's through
     * one read of every set's row (each write bumps that player's version, so a map of theirs left loaded redraws).
     * Its owner's map loses its X.
     */
    public void launcherGone(Spot s, UUID owner) {
        owners.remove(s);
        if (owner != null) {
            bump(owner);
            Player o = plugin.getServer().getPlayer(owner);
            if (o != null) {
                sendMap(o);
            }
        }
        for (Map.Entry<UUID, List<Spot>> e : new ArrayList<>(sets.entrySet())) {
            if (!e.getValue().contains(s)) {
                continue;
            }
            List<Spot> set = without(e.getValue(), s);
            save(e.getKey(), set);
            sets.put(e.getKey(), set);
            bump(e.getKey()); // the cache changed, saved or not
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p != null) {
                changed(p);
            }
        }
        PrizeService prizes = plugin.prizes();
        if (prizes == null) {
            return;
        }
        try {
            for (Map.Entry<UUID, String> e : prizes.dao().prefsNamed(RemoteRules.PREF).entrySet()) {
                if (sets.containsKey(e.getKey())) {
                    continue; // done above
                }
                RemoteRules.Parsed parsed = RemoteRules.parse(e.getValue());
                if (!parsed.newer() && parsed.spots().contains(s)) {
                    save(e.getKey(), without(parsed.spots(), s)); // and bumped: their map redraws when next seen
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Couldn't take a broken Firework Launcher off the shows of players who are"
                    + " away (each show skips it, and drops it at their next join): " + ex.getMessage());
        }
    }

    /** A launcher was placed at {@code s} by {@code owner}: their Show Map draws it. */
    public void launcherPlaced(Spot s, UUID owner) {
        owners.put(s, owner);
        bump(owner);
        Player o = plugin.getServer().getPlayer(owner);
        if (o != null) {
            sendMap(o);
        }
    }

    private static List<Spot> without(List<Spot> set, Spot s) {
        List<Spot> out = new ArrayList<>(set);
        out.remove(s);
        return List.copyOf(out);
    }

    // ---- the Remote's name and lore -------------------------------------------------------------------

    /**
     * Every Firework Remote {@code player} carries (slots 0-40) gets its name and lore for their set now, the way and
     * distance from where they stand: "(3 linked)" in the name, a line a launcher in the lore. Only the meta's name and
     * lore change: its tags stay. Held or not: a world change, a pickup and a Prize Counter hand-over refresh every
     * one carried (review REMOTE-LORE-4). With none carried it is 41 slot reads and nothing else.
     */
    public void refreshRemotes(Player player) {
        if (!player.isOnline()) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot <= 40; slot++) {
            if (PrizeItems.kindOf(inv.getItem(slot)) == PrizeType.REMOTE) {
                slots.add(slot);
            }
        }
        if (slots.isEmpty()) {
            return;
        }
        List<Spot> set = linked(player.getUniqueId());
        Location at = player.getLocation();
        String world = player.getWorld().getName();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < set.size(); i++) {
            lines.add(RemoteRules.loreLine(i + 1, set.get(i), world, at.getX(), at.getZ()));
        }
        for (int slot : slots) {
            ItemStack item = inv.getItem(slot);
            ItemMeta meta = item == null ? null : item.getItemMeta();
            if (meta == null) {
                continue;
            }
            Prize row = plugin.config().arcade().prize(TokenPrizes.prizeId(item));
            meta.displayName(Text.of(RemoteRules.remoteName(row == null ? null : row.display(), set.size())));
            List<Component> lore = new ArrayList<>();
            for (String line : RemoteRules.remoteLore(row == null ? List.of() : row.description(), lines)) {
                lore.add(Text.of(line));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(slot, item);
        }
    }

    // ---- the Show Map ---------------------------------------------------------------------------------

    /**
     * My Show's "Get my Show Map": a vanilla filled map of where {@code player} stands ({@link ShowMap}), with their
     * set's flags and their other launchers' X's on it. Their current map is given again (no new map id) when it is in
     * this world and its centre within {@value ShowMap#REUSE_BLOCKS} blocks; otherwise a new one is made, centred here,
     * and the old one keeps only its terrain. One a player: it goes where their old one is (any other is taken out),
     * else in the first empty slot; with none, nothing changes.
     */
    public void giveMap(Player player) {
        UUID id = player.getUniqueId();
        World w = player.getWorld();
        Location at = player.getLocation();
        MapView old = mapOf(id);
        MapView view = old != null && w.equals(old.getWorld())
                && Math.hypot(old.getCenterX() - at.getBlockX(), old.getCenterZ() - at.getBlockZ())
                <= ShowMap.REUSE_BLOCKS ? old : null;
        PlayerInventory inv = player.getInventory();
        int slot = -1;
        for (int i = 0; i <= 40; i++) {
            if (showMapOf(inv.getItem(i), id)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            slot = inv.firstEmpty();
        }
        if (slot < 0) {
            player.sendMessage(Text.of("&7Make room in your bag for your Show Map."));
            Sounds.refused(player);
            return;
        }
        if (view == null) {
            view = plugin.getServer().createMap(w);
            view.setScale(MapView.Scale.NORMAL);
            view.setCenterX(at.getBlockX());
            view.setCenterZ(at.getBlockZ());
            view.setTrackingPosition(true);
            view.setUnlimitedTracking(true);
            view.setLocked(false);
            if (!saveMapId(id, view.getId())) {
                player.sendMessage(Text.of(RemoteRules.NOT_SAVED));
                Sounds.refused(player);
                return;
            }
            if (old != null) {
                detach(old); // an old copy left in a chest or a frame shows its terrain only
            }
        }
        ensureRenderer(view, id);
        bump(id); // drawn fresh on the map given
        for (int i = 0; i <= 40; i++) {
            if (i != slot && showMapOf(inv.getItem(i), id)) {
                inv.setItem(i, null); // one Show Map a player: no pile of them
            }
        }
        takeOldCopies(player);
        inv.setItem(slot, mapItem(view, id));
        player.sendMap(view);
        player.sendMessage(Text.of("&a✔ Here's your Show Map! &7It fills in as you walk with it, like any map."));
        Sounds.received(player);
    }

    /**
     * The other copies of {@code player}'s Show Map they can reach go as a new one is given (review REMOTE-MAP-2,
     * "giving it again replaces the old one"): one on their cursor, any in their ender chest, and the last one they
     * dropped, if it is still on the ground in a loaded chunk. One dropped before that despawns like any map
     * ({@link PrizeSafety#plainOnGround}), so pressing "Get my Show Map" again and again never piles them up. A copy
     * put in a chest or a frame is kept there, like any map.
     */
    private void takeOldCopies(Player player) {
        UUID id = player.getUniqueId();
        if (showMapOf(player.getItemOnCursor(), id)) {
            player.setItemOnCursor(null);
        }
        org.bukkit.inventory.Inventory ender = player.getEnderChest();
        for (int i = 0; i < ender.getSize(); i++) {
            if (showMapOf(ender.getItem(i), id)) {
                ender.setItem(i, null);
            }
        }
        UUID dropped = droppedMaps.remove(id);
        if (dropped != null && plugin.getServer().getEntity(dropped) instanceof Item item && item.isValid()
                && showMapOf(item.getItemStack(), id)) {
            item.remove();
        }
    }

    /** {@code player}'s Show Map's view, or null (none yet, or its data is gone). */
    private MapView mapOf(UUID player) {
        Integer mapId = mapIds.get(player);
        if (mapId == null) {
            mapId = readMapId(player);
        }
        return mapId == null ? null : plugin.getServer().getMap(mapId);
    }

    private Integer readMapId(UUID player) {
        PrizeService prizes = plugin.prizes();
        if (prizes == null) {
            return null;
        }
        try {
            Integer mapId = parseId(prizes.dao().pref(player, RemoteRules.MAP_PREF));
            if (mapId != null) {
                mapIds.put(player, mapId);
            }
            return mapId;
        } catch (SQLException e) {
            return null;
        }
    }

    private boolean saveMapId(UUID player, int mapId) {
        PrizeService prizes = plugin.prizes();
        if (prizes == null) {
            return false;
        }
        try {
            prizes.dao().setPref(player, RemoteRules.MAP_PREF, String.valueOf(mapId));
            mapIds.put(player, mapId);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("Couldn't save " + nameOf(player) + "'s Show Map: " + e.getMessage());
            return false;
        }
    }

    private static Integer parseId(String raw) {
        try {
            return raw == null ? null : Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code view} draws {@code owner}'s markers: our renderer added once, after the map's own. */
    private void ensureRenderer(MapView view, UUID owner) {
        for (MapRenderer r : view.getRenderers()) {
            if (r instanceof ShowMapRenderer) {
                return;
            }
        }
        ShowMapRenderer r = new ShowMapRenderer(this, owner);
        view.addRenderer(r);
        renderers.put(view.getId(), r);
    }

    /** Our renderer comes off {@code view}: it shows terrain only. */
    private void detach(MapView view) {
        for (MapRenderer r : new ArrayList<>(view.getRenderers())) {
            if (r instanceof ShowMapRenderer) {
                view.removeRenderer(r);
            }
        }
        renderers.remove(view.getId());
    }

    /** {@code player}'s Show Map sent at once after a change (one packet), when they are online and have one. */
    private void sendMap(Player player) {
        try {
            MapView view = mapIds.containsKey(player.getUniqueId()) ? mapOf(player.getUniqueId()) : null;
            if (view != null && player.isOnline()) {
                player.sendMap(view);
            }
        } catch (RuntimeException ignored) {
            // the frame-by-frame update follows while it is held anyway
        }
    }

    /** The map item: a token prize, tagged as {@code owner}'s Show Map, named for Bedrock. */
    private static ItemStack mapItem(MapView view, UUID owner) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        if (item.getItemMeta() instanceof MapMeta meta) {
            meta.setMapView(view);
            meta.displayName(Text.of("&eMy Show Map"));
            meta.lore(List.of(Text.of("&7Flags: your show (#1 fires first)."),
                    Text.of("&7White X's: your other launchers."), Text.of("&8Arcade prize")));
            TokenPrizes.tag(meta, ShowMap.PRIZE_ID);
            meta.getPersistentDataContainer().set(Keys.SHOW_MAP, PersistentDataType.STRING, owner.toString());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Whether {@code item} is a Show Map (anyone's when {@code owner} is null). */
    private static boolean showMapOf(ItemStack item, UUID owner) {
        String tag = showMapOwner(item);
        return tag != null && (owner == null || tag.equals(owner.toString()));
    }

    /** Whose Show Map {@code item} is (their UUID, as tagged), or null when it isn't one. */
    private static String showMapOwner(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP || !item.hasItemMeta() || Keys.SHOW_MAP == null) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(Keys.SHOW_MAP, PersistentDataType.STRING);
    }

    // ---- start and stop -------------------------------------------------------------------------------

    /**
     * At enable: the marker pass every {@value #MARKER_TICKS} ticks, every online player's set, Remotes and map (a
     * reload), the holograms left over in loaded worlds swept, and a renderer back on every Show Map (renderers aren't
     * saved with a map).
     */
    public void start() {
        for (World w : plugin.getServer().getWorlds()) {
            try {
                for (TextDisplay td : w.getEntitiesByClass(TextDisplay.class)) {
                    sweep(td);
                }
            } catch (RuntimeException ignored) {
                // a world we can't look at has nothing of ours to sweep now
            }
        }
        PrizeService prizes = plugin.prizes();
        if (prizes != null) {
            try {
                for (Map.Entry<UUID, String> e : prizes.dao().prefsNamed(RemoteRules.MAP_PREF).entrySet()) {
                    Integer mapId = parseId(e.getValue());
                    MapView view = mapId == null ? null : plugin.getServer().getMap(mapId);
                    if (view != null) {
                        mapIds.put(e.getKey(), mapId);
                        ensureRenderer(view, e.getKey());
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Couldn't read the Show Maps: they show terrain only until their owners"
                        + " join: " + e.getMessage());
            }
        }
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            linked(p.getUniqueId());
            refreshRemotes(p);
        }
        timer = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                markers.update(p);
            }
        }, MARKER_TICKS, MARKER_TICKS);
    }

    /** At disable: the timer stops and every hologram comes down, while the worlds are still up. */
    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        markers.clearAll();
        for (Map.Entry<Integer, ShowMapRenderer> e : renderers.entrySet()) {
            try {
                MapView view = plugin.getServer().getMap(e.getKey());
                if (view != null) {
                    view.removeRenderer(e.getValue());
                }
            } catch (RuntimeException ignored) {
                // the server is going anyway
            }
        }
        renderers.clear();
    }

    // ---- events ---------------------------------------------------------------------------------------

    /**
     * A join: their set (pruned), their Remotes for where they stand, and their map's renderer, which redraws once
     * (review REMOTE-MAP-1: the renderer stays on a map across a quit, so anything that changed their set while they
     * were away is drawn now, not only at the next change).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        later(p, () -> {
            linked(p.getUniqueId());
            bump(p.getUniqueId());
            refreshRemotes(p);
            MapView view = mapOf(p.getUniqueId());
            if (view != null) {
                ensureRenderer(view, p.getUniqueId());
            }
        });
    }

    /** A quit: their holograms come down, and nothing of theirs is kept but their row. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        markers.clear(id);
        taps.forget(id);
        sets.remove(id);
        unread.ok(id);
    }

    /** A Show Map dropped: the copy on the ground is its owner's last dropped one ({@link #takeOldCopies}). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDropMap(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        String owner = showMapOwner(item.getItemStack());
        if (owner != null) {
            try {
                droppedMaps.put(UUID.fromString(owner), item.getUniqueId());
            } catch (IllegalArgumentException ignored) {
                // a tag we didn't write: nothing to take later
            }
        }
    }

    /**
     * A change of the held slot: picked up the Remote (its lore is refreshed for where they stand now, the owner's ask:
     * "the direction and distance are from where they stand at that moment"), and the holograms follow.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        Player p = event.getPlayer();
        later(p, () -> held(p));
    }

    /** A swap to or from the off hand: as a change of the held slot. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        later(p, () -> held(p));
    }

    /**
     * A Remote picked up off the ground: its lines are this player's set now, wherever it lands in the bag (one
     * dropped by someone else showed theirs: review REMOTE-LORE-4), and the holograms follow.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p
                && PrizeItems.kindOf(event.getItem().getItemStack()) == PrizeType.REMOTE) {
            later(p, () -> carried(p));
        }
    }

    /**
     * A world change: every Remote carried says "in <world>" for the old world's launchers now, held or in the bag
     * (review REMOTE-LORE-4), and the old world's holograms go.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        Player p = event.getPlayer();
        later(p, () -> carried(p));
    }

    /** A hologram of ours that isn't live (left over from anywhere) goes as its chunk loads. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (e instanceof TextDisplay td) {
                sweep(td);
            }
        }
    }

    /**
     * A Show Map in a cartography table makes nothing (no copies, no zoom: one map a player, always theirs). Crafting
     * one is refused already ({@code PrizeItemListener.onPrepareCraft}: a token prize is never an ingredient).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCartography(PrepareResultEvent event) {
        if (!(event.getInventory() instanceof CartographyInventory table)) {
            return;
        }
        for (ItemStack in : table.getContents()) {
            if (showMapOf(in, null)) {
                event.setResult(null);
                return;
            }
        }
    }

    /** A held-slot change or a swap: the Remote's lines when one is in a hand now (not every scroll), the holograms. */
    private void held(Player p) {
        if (!p.isOnline()) {
            return;
        }
        if (RemoteMarkers.holdsRemote(p)) {
            refreshRemotes(p);
        }
        markers.update(p);
    }

    /** A pickup or a world change: every Remote carried, held or not, and the holograms. */
    private void carried(Player p) {
        if (!p.isOnline()) {
            return;
        }
        refreshRemotes(p);
        markers.update(p);
    }

    private void sweep(TextDisplay td) {
        if (Keys.REMOTE_MARKER != null && td.getPersistentDataContainer().has(Keys.REMOTE_MARKER,
                PersistentDataType.STRING) && !markers.live(td)) {
            td.remove();
        }
    }

    /** {@code r} on the next tick, once the slot really holds the item, while {@code p} is still on. */
    private void later(Player p, Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                r.run();
            }
        });
    }

    /** A launcher block's spot. */
    public static Spot spotOf(Block b) {
        return new Spot(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }
}
