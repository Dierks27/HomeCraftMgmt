package com.dierks.homecraft.golem;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.Whereabouts;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.TileState;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The Copper Golem Brush (0.38.4; the owner: "a special tool ... take something like the brush and make it a
 * different colour and name it something different"): which chests a Copper Golem may put things in, and
 * everything that shows it.
 *
 * <ul>
 *   <li><b>Picking</b>: a tap (plain or shift) on a golem with the brush in the main hand opens a 60 s "pick the
 *       chests" session ({@link GolemRules.Sessions}); a tap on a chest, trapped chest or barrel links it (both
 *       halves of a double chest) or unlinks it, and the container does not open; a plain tap on the golem again,
 *       the brush put away, a swap, a quit, a world change or the 60 s end it ("keeps N chests"). Two shift-taps on
 *       the golem within 2 s clear its chests; a single tap never does. Outside a session the brush passes a chest
 *       through, so a child holding it still opens chests.</li>
 *   <li><b>The filter</b>: Paper asks {@link ItemTransportingEntityValidateTargetEvent} for every container a golem
 *       considers; for a golem with chests of its own, a copper chest (the source) is always allowed and any other
 *       container only when it is one of its own ({@link GolemRules#allow}, O(1): one map get, one tag test, one set
 *       lookup, no allocation). A denied block is simply not a container for that search and is asked again next
 *       time; with no chest of its own in reach the golem keeps holding its item (vanilla), and its tag says so.</li>
 *   <li><b>What shows</b>: the golem's name tag ("Rusty -> 3 chests", "Rusty - holding Cobblestone"; a name it was
 *       given is kept), "#N" holograms over the linked chests while the brush is held ({@link GolemMarkers}) and plain
 *       chat lines ({@link GolemText}): no hover, no click, so the daughter on Bedrock reads all of it.</li>
 *   <li><b>Storage</b>: the golem's own {@link PersistentDataContainer} ({@link Keys#GOLEM_CHESTS} packed positions,
 *       {@link Keys#GOLEM_WORLD}, {@link Keys#GOLEM_NAME}), so it survives restarts and dies with the golem; an
 *       in-memory cache by golem, filled as golems load. A position whose block is no longer a container is dropped
 *       lazily (the pass, every second, loaded assigned golems only). A linked chest is also marked as a CraftBridge
 *       golem chest ({@code craftbridge:golem_chest}, a string key: never a compile dependency) so CraftBridge keeps
 *       one of every stack there; an unlink does NOT remove that mark (the owner may want it kept).</li>
 * </ul>
 *
 * <p>All on the main thread. Nothing here loads a chunk, moves a token or touches money.
 */
public final class GolemService implements Listener {

    /** The session and marker pass, in ticks; the golem pass runs every other one. */
    private static final long PASS_TICKS = 10;
    /** CraftBridge's block mark (its {@code GolemChests.MARK}), by name: HomeCraft never compiles against it. */
    private static final NamespacedKey CB_MARK = Objects.requireNonNull(NamespacedKey.fromString("craftbridge:golem_chest"));

    /** A golem's chests (in link order), their world and the golem's own name. The cache of its PDC. */
    static final class Assignment {
        private final String world;
        private final LinkedHashSet<Long> chests;
        private String base;

        Assignment(String world, LinkedHashSet<Long> chests, String base) {
            this.world = world;
            this.chests = chests;
            this.base = base;
        }

        String world() {
            return world;
        }

        LinkedHashSet<Long> chests() {
            return chests;
        }

        String base() {
            return base;
        }
    }

    private final HomeCraftManagement plugin;
    private GolemConfig config = GolemConfig.defaults();
    /** Every loaded golem with chests of its own (and, for a session's sake, one being given its first). */
    private final Map<UUID, Assignment> golems = new HashMap<>();
    private final GolemRules.Sessions sessions = new GolemRules.Sessions();
    private final GolemRules.Taps taps = new GolemRules.Taps();
    private final GolemMarkers markers;
    /** What each golem's tag read last, so it is set only on a change. */
    private final Map<UUID, String> lastTag = new HashMap<>();
    private final NamespacedKey recipeKey;
    private BukkitTask timer;
    private boolean golemPass;

    public GolemService(HomeCraftManagement plugin) {
        this.plugin = plugin;
        this.markers = new GolemMarkers(plugin, this);
        this.recipeKey = new NamespacedKey(plugin, "golem_brush");
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    /**
     * At enable: the config, the recipe, the holograms left over in loaded worlds swept, every loaded golem with
     * chests adopted, and the pass every {@value #PASS_TICKS} ticks. Off ({@code golem.enabled: false}): nothing
     * runs and every handler returns at once.
     */
    public void start() {
        config = GolemConfig.read(plugin.getConfig().getConfigurationSection("golem"), plugin.getLogger()::warning);
        if (!config.enabled()) {
            plugin.getLogger().info("Copper Golem Brush: off (golem.enabled).");
            return;
        }
        GolemBrush.register(plugin, recipeKey, config, plugin.getLogger());
        for (World w : plugin.getServer().getWorlds()) {
            try {
                for (TextDisplay td : w.getEntitiesByClass(TextDisplay.class)) {
                    sweep(td);
                }
                for (CopperGolem g : w.getEntitiesByClass(CopperGolem.class)) {
                    adopt(g);
                }
            } catch (RuntimeException ignored) {
                // a world we can't look at has nothing of ours to sweep or adopt now
            }
        }
        timer = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PASS_TICKS, PASS_TICKS);
        plugin.getLogger().info("Copper Golem Brush: on" + (config.recipeEnabled() ? ", craftable" : ", /hcm give only")
                + "; " + golems.size() + " golem(s) with chests loaded.");
    }

    /**
     * {@code /hcm reload}: the config again; the recipe re-registered or removed; a switch to off ends every
     * session and takes every hologram down (the golems keep their chests in their own data, ignored while off).
     */
    public void reload() {
        boolean wasOn = config.enabled();
        config = GolemConfig.read(plugin.getConfig().getConfigurationSection("golem"), plugin.getLogger()::warning);
        if (!config.enabled()) {
            GolemBrush.unregister(recipeKey);
            for (UUID id : sessions.endAll()) {
                Player p = plugin.getServer().getPlayer(id);
                if (p != null) {
                    p.sendMessage(Text.of(GolemText.OFF));
                }
            }
            markers.clearAll();
            if (wasOn) {
                plugin.getLogger().info("Copper Golem Brush: off (golem.enabled).");
            }
            return;
        }
        GolemBrush.register(plugin, recipeKey, config, plugin.getLogger());
        if (!wasOn) {
            start2();
        }
    }

    /** The rest of {@link #start} after a reload switched the brush on. */
    private void start2() {
        for (World w : plugin.getServer().getWorlds()) {
            try {
                for (CopperGolem g : w.getEntitiesByClass(CopperGolem.class)) {
                    adopt(g);
                }
            } catch (RuntimeException ignored) {
                // nothing to adopt there now
            }
        }
        if (timer == null) {
            timer = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PASS_TICKS, PASS_TICKS);
        }
    }

    /** At disable: the pass stops, every hologram comes down while the worlds are up, the recipe goes. */
    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        sessions.endAll(); // nothing said: the server is going
        markers.clearAll();
        GolemBrush.unregister(recipeKey);
    }

    /** One Copper Golem Brush, for {@code /hcm give golem_brush}. */
    public ItemStack brush() {
        return GolemBrush.item(1);
    }

    /** Whether the brush is on ({@code golem.enabled}). */
    public boolean enabled() {
        return config.enabled();
    }

    // ---- the cache ------------------------------------------------------------------------------------

    GolemRules.Session sessionOf(UUID player) {
        return sessions.of(player);
    }

    Assignment assignmentOf(UUID golem) {
        return golems.get(golem);
    }

    /** {@code g} into the cache from its own data, when it carries any. */
    private void adopt(CopperGolem g) {
        PersistentDataContainer pdc = g.getPersistentDataContainer();
        if (!pdc.has(Keys.GOLEM_CHESTS, PersistentDataType.LONG_ARRAY)) {
            return;
        }
        long[] stored = pdc.get(Keys.GOLEM_CHESTS, PersistentDataType.LONG_ARRAY);
        String world = pdc.get(Keys.GOLEM_WORLD, PersistentDataType.STRING);
        String base = pdc.get(Keys.GOLEM_NAME, PersistentDataType.STRING);
        if (world == null) {
            world = g.getWorld().getName();
        }
        golems.put(g.getUniqueId(), new Assignment(world, new LinkedHashSet<>(GolemRules.parse(stored)),
                GolemRules.base(plainName(g), base)));
    }

    /** The cache entry for {@code g}, made (unwritten) if it has none: a session's golem. */
    private Assignment ensure(CopperGolem g) {
        return golems.computeIfAbsent(g.getUniqueId(), id -> new Assignment(g.getWorld().getName(),
                new LinkedHashSet<>(), GolemRules.base(plainName(g), null)));
    }

    /**
     * {@code a} into {@code g}'s own data: its chests, their world and its base name; with no chests left, the keys
     * go, the cache forgets it and its name is its own again.
     */
    private void write(CopperGolem g, Assignment a) {
        PersistentDataContainer pdc = g.getPersistentDataContainer();
        if (a.chests().isEmpty()) {
            pdc.remove(Keys.GOLEM_CHESTS);
            pdc.remove(Keys.GOLEM_WORLD);
            pdc.remove(Keys.GOLEM_NAME);
            golems.remove(g.getUniqueId());
            resetTag(g, a);
            return;
        }
        pdc.set(Keys.GOLEM_CHESTS, PersistentDataType.LONG_ARRAY, GolemRules.format(a.chests()));
        pdc.set(Keys.GOLEM_WORLD, PersistentDataType.STRING, a.world());
        pdc.set(Keys.GOLEM_NAME, PersistentDataType.STRING, a.base());
    }

    private static String plainName(Entity e) {
        Component name = e.customName();
        if (name == null) {
            return null;
        }
        try {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(name);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ---- the name tag ---------------------------------------------------------------------------------

    /** The tag for where {@code g} stands now, set only when it changed. */
    private void refreshTag(CopperGolem g, Assignment a, String holding) {
        String tag = GolemRules.tag(a.base(), a.chests().size(), holding);
        if (tag.equals(lastTag.get(g.getUniqueId()))) {
            return;
        }
        g.customName(Text.of(tag));
        g.setCustomNameVisible(true);
        lastTag.put(g.getUniqueId(), tag);
    }

    /** {@code g}'s name is its own again: the one it was given, or none. */
    private void resetTag(CopperGolem g, Assignment a) {
        lastTag.remove(g.getUniqueId());
        if (GolemRules.DEFAULT_NAME.equals(a.base())) {
            g.customName(null);
            g.setCustomNameVisible(false);
        } else {
            g.customName(Text.of(a.base()));
        }
    }

    /** What {@code g} holds, prettily, or null. */
    private static String holding(CopperGolem g) {
        if (g.getEquipment() == null) {
            return null;
        }
        ItemStack in = g.getEquipment().getItemInMainHand();
        return in.getType().isAir() ? null : GolemRules.pretty(in.getType().name());
    }

    // ---- the filter -----------------------------------------------------------------------------------

    /**
     * The one decision: a golem with chests of its own may use a copper chest (always: the source) and its own
     * chests, nothing else. Kept to a map get, a tag test and a set lookup: Paper asks this for every container a
     * golem considers, several times a tick near a copper chest. Not Cancellable, so {@code setAllowed(false)} is
     * the only lever, and never {@code ignoreCancelled}.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onValidate(ItemTransportingEntityValidateTargetEvent event) {
        if (!(event.getEntity() instanceof CopperGolem g)) {
            return;
        }
        Assignment a = golems.get(g.getUniqueId());
        if (a == null || a.chests().isEmpty()) {
            return;
        }
        Block b = event.getBlock();
        if (!a.world().equals(b.getWorld().getName())) {
            return; // its chests are in another world: any chest here, as vanilla
        }
        if (!GolemRules.allow(Tag.COPPER_CHESTS.isTagged(b.getType()), a.chests(),
                GolemRules.pack(b.getX(), b.getY(), b.getZ()))) {
            event.setAllowed(false);
        }
    }

    // ---- golems coming and going ----------------------------------------------------------------------

    /** A chunk's entities: its golems with chests adopted, a hologram of ours that isn't live swept. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!config.enabled()) {
            return;
        }
        for (Entity e : event.getEntities()) {
            if (e instanceof CopperGolem g) {
                adopt(g);
            } else if (e instanceof TextDisplay td) {
                sweep(td);
            }
        }
    }

    /** A golem added to a world (spawned, loaded, through a portal): adopted when it carries chests. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdded(EntityAddToWorldEvent event) {
        if (config.enabled() && event.getEntity() instanceof CopperGolem g) {
            adopt(g);
        }
    }

    /** A golem leaving a world (unloaded, died, a portal): the cache forgets it; its own data keeps the chests. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemoved(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof CopperGolem g) {
            golems.remove(g.getUniqueId());
            lastTag.remove(g.getUniqueId());
        }
    }

    /** A golem died: forgotten, and every session on it ends ("<name> is gone"). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof CopperGolem g)) {
            return;
        }
        Assignment a = golems.remove(g.getUniqueId());
        lastTag.remove(g.getUniqueId());
        String name = a == null ? GolemRules.base(plainName(g), null) : a.base();
        for (UUID id : sessions.onGolem(g.getUniqueId())) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) {
                p.sendMessage(Text.of(GolemText.gone(name)));
            }
            markers.clear(id);
        }
    }

    private void sweep(TextDisplay td) {
        if (Keys.GOLEM_MARKER != null && td.getPersistentDataContainer().has(Keys.GOLEM_MARKER,
                PersistentDataType.STRING) && !markers.live(td)) {
            td.remove();
        }
    }

    // ---- the taps -------------------------------------------------------------------------------------

    /**
     * A tap on a golem with the brush in the main hand: the session opens (a plain tap or a shift-tap: the daughter
     * never needs the sneak button), closes (a plain tap on its golem), switches golems, or (two shift-taps within
     * 2 s) clears its chests ({@link GolemRules.Sessions#tap}). Vanilla gets nothing of the tap; a held tap is one
     * ({@link GolemRules.Taps}). Only {@code PlayerInteractEntityEvent}: its companion {@code PlayerInteractAtEntityEvent}
     * fires for the same click. Nothing for a player in a world game.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onTapGolem(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof CopperGolem g)) {
            return;
        }
        Player p = event.getPlayer();
        if (!GolemBrush.holdsInMainHand(p) || Whereabouts.inGameSession(plugin, p)) {
            return;
        }
        event.setCancelled(true); // nothing of vanilla's, whatever that would be
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        if (!taps.counts(id, g.getUniqueId(), now)) {
            return;
        }
        if (!config.enabled()) {
            p.sendMessage(Text.of(GolemText.OFF));
            return;
        }
        GolemRules.Session before = sessions.of(id);
        switch (sessions.tap(id, g.getUniqueId(), p.isSneaking(), now)) {
            case OPEN -> open(p, g);
            case CLOSE -> {
                markers.clear(id);
                p.sendMessage(Text.of(GolemText.keeps(nameOf(g), count(g))));
                forgetUnwritten(g.getUniqueId());
            }
            case SWITCH -> {
                markers.clear(id);
                if (before != null) { // the old session's golem keeps what it has
                    Assignment old = golems.get(before.golem());
                    p.sendMessage(Text.of(GolemText.keeps(old == null ? GolemRules.DEFAULT_NAME : old.base(),
                            old == null ? 0 : old.chests().size())));
                    forgetUnwritten(before.golem());
                }
                open(p, g);
            }
            case ARM_CLEAR -> p.sendMessage(Text.of(GolemText.armClear(nameOf(g))));
            case CLEAR -> {
                Assignment a = ensure(g);
                String name = a.base();
                a.chests().clear();
                write(g, a); // the keys go, the name is its own again; CraftBridge's marks stay
                markers.clear(id);
                p.sendMessage(Text.of(GolemText.cleared(name)));
                Sounds.received(p);
            }
        }
    }

    /** {@code g}'s name: its cached base, else the name it has (never one of our tags), else "Rusty". */
    private String nameOf(CopperGolem g) {
        Assignment a = golems.get(g.getUniqueId());
        return a == null ? GolemRules.base(plainName(g), null) : a.base();
    }

    private int count(CopperGolem g) {
        Assignment a = golems.get(g.getUniqueId());
        return a == null ? 0 : a.chests().size();
    }

    private void open(Player p, CopperGolem g) {
        Assignment a = ensure(g);
        p.sendMessage(Text.of(GolemText.pick(a.base())));
        Sounds.received(p);
        refreshTag(g, a, holding(g));
        markers.update(p);
    }

    /**
     * A tap on a block with the brush. On suspicious sand or gravel it never brushes (the brush is unbreakable too).
     * Outside a session the brush passes through: the chest opens as usual. In one, a tap on a chest, trapped chest
     * or barrel links or unlinks it and the container does not open: LOWEST, the block denied and the event
     * cancelled, so CraftBridge's LOW + ignoreCancelled marker handler and the vanilla open both skip it. Any other
     * block (a door) is its own. Nothing for a player in a world game.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTapBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || !GolemBrush.is(event.getItem())) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        if (clicked.getType() == Material.SUSPICIOUS_SAND || clicked.getType() == Material.SUSPICIOUS_GRAVEL) {
            event.setUseItemInHand(Event.Result.DENY); // never brushes anything
            return;
        }
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        GolemRules.Session s = config.enabled() ? sessions.of(id) : null;
        if (s == null || !container(clicked.getType()) || Whereabouts.inGameSession(plugin, p)) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        event.setCancelled(true);
        long packed = GolemRules.pack(clicked.getX(), clicked.getY(), clicked.getZ());
        if (!taps.counts(id, packed, System.currentTimeMillis())) {
            return;
        }
        toggle(p, s, clicked);
    }

    private static boolean container(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL;
    }

    /** {@code clicked} linked to the session's golem, or unlinked: both halves of a double chest at once. */
    private void toggle(Player p, GolemRules.Session s, Block clicked) {
        UUID id = p.getUniqueId();
        if (!(plugin.getServer().getEntity(s.golem()) instanceof CopperGolem g) || !g.isValid()) {
            sessions.end(id);
            markers.clear(id);
            Assignment gone = golems.get(s.golem());
            p.sendMessage(Text.of(GolemText.gone(gone == null ? GolemRules.DEFAULT_NAME : gone.base())));
            return;
        }
        Assignment a = ensure(g);
        if (!a.world().equals(clicked.getWorld().getName())) {
            p.sendMessage(Text.of(GolemText.otherWorld(a.base())));
            Sounds.refused(p);
            return;
        }
        List<Block> halves = halves(clicked);
        long first = GolemRules.pack(halves.get(0).getX(), halves.get(0).getY(), halves.get(0).getZ());
        if (a.chests().contains(first)) {
            for (Block half : halves) {
                a.chests().remove(GolemRules.pack(half.getX(), half.getY(), half.getZ()));
            }
            write(g, a);
            p.sendMessage(Text.of(GolemText.unlinked(a.chests().size())));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.8f);
        } else {
            if (a.chests().size() + halves.size() > GolemRules.MAX_CHESTS) {
                p.sendMessage(Text.of(GolemText.FULL));
                Sounds.refused(p);
                return;
            }
            int from = a.chests().size() + 1;
            for (Block half : halves) {
                a.chests().add(GolemRules.pack(half.getX(), half.getY(), half.getZ()));
                markCraftBridge(half);
            }
            write(g, a);
            p.sendMessage(Text.of(GolemText.linked(from, a.chests().size())));
            Sounds.received(p);
            Block at = g.getLocation().getBlock();
            if (!GolemRules.nearEnough(at.getX(), at.getY(), at.getZ(), clicked.getX(), clicked.getY(),
                    clicked.getZ())) {
                p.sendMessage(Text.of(GolemText.farHint(a.base())));
            }
        }
        refreshTag(g, ensure(g), holding(g)); // the last chest unlinked: "no chests yet" while the session is on
        markers.update(p);
    }

    /** Both halves of a double chest (the left first), else the block itself (a barrel, a single chest). */
    private static List<Block> halves(Block clicked) {
        try {
            if (clicked.getState(false) instanceof Chest c
                    && c.getInventory().getHolder(false) instanceof DoubleChest dc
                    && dc.getLeftSide(false) instanceof Chest left && dc.getRightSide(false) instanceof Chest right) {
                return List.of(left.getBlock(), right.getBlock());
            }
        } catch (RuntimeException ignored) {
            // a chest we can't read both halves of is linked as the one block tapped
        }
        return List.of(clicked);
    }

    /**
     * The CraftBridge mark on {@code b} ({@code craftbridge:golem_chest = 1}) when it isn't there: CraftBridge then
     * keeps one of every stack in it, so the golem's sorting holds. Never removed by an unlink.
     */
    private static void markCraftBridge(Block b) {
        try {
            if (b.getState(false) instanceof TileState live
                    && live.getPersistentDataContainer().has(CB_MARK, PersistentDataType.BYTE)) {
                return;
            }
            if (b.getState() instanceof TileState snapshot) {
                snapshot.getPersistentDataContainer().set(CB_MARK, PersistentDataType.BYTE, (byte) 1);
                snapshot.update(true, false);
            }
        } catch (RuntimeException ignored) {
            // a mark is a nicety for the other plugin; the golem's own list is what counts
        }
    }

    // ---- the brush put away, a quit, a world change ---------------------------------------------------

    /** A change of the held slot: the brush put away ends the session; the holograms follow. */
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

    private void held(Player p) {
        if (sessions.of(p.getUniqueId()) != null && !GolemBrush.holdsInMainHand(p)) {
            close(p);
        }
        markers.update(p);
    }

    /** A quit: the session ends without a word, and nothing of theirs is kept. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        GolemRules.Session s = sessions.end(id);
        if (s != null) {
            forgetUnwritten(s.golem());
        }
        taps.forget(id);
        markers.clear(id);
    }

    /** A world change: the session ends ("keeps"), the old world's holograms go. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        Player p = event.getPlayer();
        if (sessions.of(p.getUniqueId()) != null) {
            close(p);
        }
        markers.clear(p.getUniqueId());
    }

    /** The brush never wears (belt and braces to unbreakable). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(PlayerItemDamageEvent event) {
        if (GolemBrush.is(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /** {@code p}'s session ends: "<name> keeps N chests", the holograms down. */
    private void close(Player p) {
        UUID id = p.getUniqueId();
        GolemRules.Session s = sessions.end(id);
        markers.clear(id);
        if (s == null) {
            return;
        }
        Assignment a = golems.get(s.golem());
        p.sendMessage(Text.of(GolemText.keeps(a == null ? GolemRules.DEFAULT_NAME : a.base(),
                a == null ? 0 : a.chests().size())));
        forgetUnwritten(s.golem());
    }

    /** A session's golem that was never given a chest leaves the cache, its name its own again. */
    private void forgetUnwritten(UUID golem) {
        Assignment a = golems.get(golem);
        if (a == null || !a.chests().isEmpty()) {
            return;
        }
        golems.remove(golem);
        if (plugin.getServer().getEntity(golem) instanceof CopperGolem g && g.isValid()) {
            resetTag(g, a);
        } else {
            lastTag.remove(golem);
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

    // ---- the pass -------------------------------------------------------------------------------------

    /**
     * Every {@value #PASS_TICKS} ticks: sessions that ran out end ("keeps"), every holder's holograms follow them;
     * every other pass (once a second), each loaded golem with chests: a position whose block is no longer a
     * container is dropped, and its tag says how many chests it has or what it holds. Only assigned golems, only
     * loaded and entity-ticking ones, the tag set only on a change.
     */
    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, GolemRules.Session> e : sessions.expired(now)) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            markers.clear(e.getKey());
            Assignment a = golems.get(e.getValue().golem());
            if (p != null) {
                p.sendMessage(Text.of(GolemText.keeps(a == null ? GolemRules.DEFAULT_NAME : a.base(),
                        a == null ? 0 : a.chests().size())));
            }
            forgetUnwritten(e.getValue().golem());
        }
        if (!sessions.isEmpty()) {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (sessions.of(p.getUniqueId()) != null) {
                    markers.update(p);
                }
            }
        }
        golemPass = !golemPass;
        if (golemPass) {
            golemPass(now);
        }
    }

    private void golemPass(long now) {
        if (golems.isEmpty()) {
            return;
        }
        for (UUID id : new ArrayList<>(golems.keySet())) {
            Assignment a = golems.get(id);
            if (a == null || a.chests().isEmpty()) {
                continue;
            }
            if (!(plugin.getServer().getEntity(id) instanceof CopperGolem g) || !g.isValid() || !ticking(g)) {
                continue;
            }
            World cw = plugin.getServer().getWorld(a.world());
            if (cw != null) {
                boolean changed = false;
                for (Iterator<Long> it = a.chests().iterator(); it.hasNext(); ) {
                    long packed = it.next();
                    int x = GolemRules.x(packed);
                    int z = GolemRules.z(packed);
                    if (cw.isChunkLoaded(x >> 4, z >> 4)
                            && !container(cw.getBlockAt(x, GolemRules.y(packed), z).getType())) {
                        it.remove(); // the chest is gone: lazily, never loading a chunk to look
                        changed = true;
                    }
                }
                if (changed) {
                    write(g, a); // once per golem per pass at most; with none left its name is its own again
                    if (a.chests().isEmpty()) {
                        continue;
                    }
                }
            }
            refreshTag(g, a, holding(g));
        }
    }

    private static boolean ticking(Entity e) {
        World w = e.getWorld();
        int cx = e.getLocation().getBlockX() >> 4;
        int cz = e.getLocation().getBlockZ() >> 4;
        return w.isChunkLoaded(cx, cz) && w.getChunkAt(cx, cz).getLoadLevel() == Chunk.LoadLevel.ENTITY_TICKING;
    }
}
