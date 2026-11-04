package com.dierks.homecraft.arcade.feast;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.HolidayShows;
import com.dierks.homecraft.arcade.candy.CandyItems;
import com.dierks.homecraft.arcade.candy.HarvestItems;
import com.dierks.homecraft.config.FeastConfig;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.golem.GolemRules;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.storage.AchievementDao;
import com.dierks.homecraft.storage.FeastDao;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Fireworks;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.TokenPrizes;
import com.dierks.homecraft.util.Whereabouts;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Family Feast (0.38.6), the live side of {@link FeastCore}: the Feast Pot in the home world, its taps and its
 * guard, the family bar, each helper's moment, the show over the pot, and what the Clubhouse's tables read.
 *
 * <p><b>The Feast Pot</b> is one block an admin picks ({@code /hcm feast pot}) in an Overworld economy world: food
 * can't be handed in inside the Clubhouse (a visitor carries only the kit), so the Clubhouse's tables only show the
 * progress. A floating "Feast Pot" name and slow sparkles mark it. A tap holding food hands in one item (a sneak-tap
 * the stack, up to the day's points), with a munch, a chime that rises as the table fills and a 5-second family bar;
 * an empty hand shows the bar; anything the pot won't take gets a soft low note and stays in the hand. The tap is
 * taken at LOWEST with the block and the item denied, so it never eats the food or opens the block, and a tap on the
 * pot never eats a basket treat ({@code PrizeItemListener.ours} says the pot is ours). While open the pot can't be
 * broken (an admin sneaking can), blown up, pushed or burnt.
 *
 * <p><b>Fair.</b> Every helper is paid the same, once, offline too; each one sees their own moment the first time
 * they are home after the fill, and gets one Turkey Mini then (a year's edition only, inside the Mini's own window).
 *
 * <p><b>Off by itself.</b> Outside its window the tap falls through to vanilla, the guard stops, the name goes and
 * the tables empty. After {@code to} it stops; a table never filled is one INFO line, and nothing is paid. Works with
 * the Games off: there is simply no table to look at then.
 */
public final class FeastService implements Listener, FeastHost {

    /** The name over the pot and its height above the block. */
    static final String POT_NAME = "&6Feast Pot";
    static final double NAME_UP = 1.6;
    /** "The Feast is ready!": the moment's title, plain ASCII for Bedrock. */
    public static final String READY = "&6The Feast is ready!";
    public static final String THANKS = "&eThank you, everyone!";
    /** A joining helper is settled this long after the join (ticks), once they have arrived. */
    static final long JOIN_SETTLE_TICKS = 60;

    private final HomeCraftManagement plugin;
    private final GolemRules.Taps taps = new GolemRules.Taps();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Long> barUntil = new HashMap<>();
    private FeastCore core;
    private FeastDao meta;
    private FeastRules.Spot pot;
    private UUID potName;
    private BukkitTask task;
    private BukkitTask show;
    private boolean wasOpen;
    private long ticks;

    public FeastService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    // ---- life ---------------------------------------------------------------------------------------

    /**
     * Start (and on {@code /hcm reload}, start again): the settings and the pot read, the table read, every pot name a
     * crash left swept, and the once-a-second task, unless the window is already over.
     */
    public void start() {
        stop();
        meta = new FeastDao(plugin.database());
        core = new FeastCore(this, new GamesDao(plugin.database()), new AchievementDao(plugin.database()), meta);
        try {
            pot = FeastRules.Spot.of(meta.get(FeastRules.POT_KEY));
        } catch (SQLException e) {
            pot = null;
            plugin.getLogger().warning("The Family Feast could not read its pot: " + e.getMessage());
        }
        core.refresh();
        sweep();
        FeastConfig.Feast c = settings();
        if (!c.enabled() || plugin.clock().today().isAfter(c.to())) {
            return; // over (or off): nothing ticks
        }
        wasOpen = open();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::second, 20L, 20L);
    }

    /** Stop: the task and the show cancelled, the pot's name and every bar gone. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (show != null) {
            show.cancel();
            show = null;
        }
        removeName();
        for (Map.Entry<UUID, BossBar> e : bars.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                p.hideBossBar(e.getValue());
            }
        }
        bars.clear();
        barUntil.clear();
    }

    /** Once a second: the window, the pot's name and sparkles, the bars, and the core's settling. */
    private void second() {
        ticks++;
        hideBars();
        FeastConfig.Feast c = settings();
        boolean open = open();
        if (!open) {
            if (wasOpen) {
                closed(c);
            }
            wasOpen = false;
            removeName();
            if (!c.enabled() || plugin.clock().today().isAfter(c.to())) {
                if (task != null) {
                    task.cancel();
                    task = null;
                }
            }
            return;
        }
        wasOpen = true;
        drawPot();
        core.second();
    }

    /** The window just closed: one line when the table was never filled. */
    private void closed(FeastConfig.Feast c) {
        if (!core.done()) {
            plugin.getLogger().info("The Family Feast " + core.edition() + " closed at " + core.total() + " / "
                    + c.goal() + ". Nothing was paid.");
        }
    }

    // ---- the host -----------------------------------------------------------------------------------

    @Override
    public long now() {
        return plugin.clock().nowMillis();
    }

    @Override
    public long epochDay() {
        return plugin.clock().dayKey();
    }

    @Override
    public FeastConfig.Feast settings() {
        FeastConfig.Feast c = plugin.config().feast();
        return c == null ? FeastConfig.Feast.OFF : c;
    }

    @Override
    public boolean open() {
        return settings().open(plugin.clock().today()) && plugin.config().arcade().enabled();
    }

    @Override
    public Collection<UUID> online() {
        List<UUID> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            out.add(p.getUniqueId());
        }
        return out;
    }

    @Override
    public boolean home(UUID player) {
        Player p = Bukkit.getPlayer(player);
        return p != null && p.isOnline() && Whereabouts.homeFromGames(plugin, p)
                && !Whereabouts.gamesWorld(plugin, p.getWorld());
    }

    @Override
    public String name(UUID player) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            return p.getName();
        }
        try {
            return Bukkit.getOfflinePlayer(player).getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean hasRoom(UUID player) {
        Player p = Bukkit.getPlayer(player);
        return p != null && p.isOnline() && MiniService.hasRoom(p);
    }

    @Override
    public boolean mintMini(UUID player, String miniId) {
        Player p = Bukkit.getPlayer(player);
        var minis = plugin.miniService();
        return p != null && minis != null && minis.mintEvent(p, miniId, "Family Feast");
    }

    @Override
    public void moment(UUID player, boolean withLine, String thankfulLine) {
        Player p = Bukkit.getPlayer(player);
        if (p == null) {
            return;
        }
        title(p, READY, THANKS);
        Fireworks.burstInView(p);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        if (withLine) {
            p.sendMessage(Text.of(thankfulLine));
        }
    }

    @Override
    public void paid(UUID player, int tokens) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            p.sendMessage(Text.of("&e+" + tokens + " token" + (tokens == 1 ? "" : "s") + " &7(Family Feast)"));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
        }
    }

    @Override
    public void broadcast(String line) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(Text.of(line));
        }
    }

    @Override
    public void homeShow() {
        Location at = potCentre();
        if (at == null) {
            return;
        }
        if (show != null) {
            show.cancel();
        }
        HolidayShows.Theme theme = theme();
        List<Color> colours = theme == null ? List.of(Color.ORANGE, Color.fromRGB(0x8B4513), Color.fromRGB(0xFFD700))
                : theme.colours();
        Color fade = theme == null || theme.fades().isEmpty() ? Color.YELLOW : theme.fades().get(0);
        FireworkEffect.Type shape = theme == null || theme.shape() == null ? FireworkEffect.Type.BURST : theme.shape();
        int[] left = {FeastRules.HOME_SHOW_SECONDS};
        show = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (left[0]-- <= 0) {
                if (show != null) {
                    show.cancel();
                    show = null;
                }
                return;
            }
            World w = at.getWorld();
            if (w == null || !w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                return;
            }
            ThreadLocalRandom r = ThreadLocalRandom.current();
            Location l = at.clone().add(r.nextDouble(-2, 2), 1, r.nextDouble(-2, 2));
            Fireworks.spawn(l, 1, colours, fade, shape, true, true);
        }, 0L, 20L);
    }

    @Override
    public void info(String line) {
        plugin.getLogger().info(line);
    }

    @Override
    public void warn(String line) {
        plugin.getLogger().warning(line);
    }

    /** The Thanksgiving show's look, or null for the built-in colours. */
    HolidayShows.Theme theme() {
        try {
            HolidayShows shows = plugin.config().holidayShows();
            return shows == null ? null : shows.byId("thanksgiving").orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- what the Clubhouse and the admin read --------------------------------------------------------

    /** The tables now, from memory (never the database). */
    public FeastRules.FeastView view() {
        return core == null ? FeastRules.FeastView.CLOSED : core.view();
    }

    /** Whether {@code player} helped fill the table and hasn't seen the Clubhouse's ceremony. */
    public boolean unseenHelper(UUID player) {
        return core != null && open() && core.unseenHelper(player);
    }

    /** {@code player} has seen the Clubhouse's ceremony (written before it plays). */
    public void markRoomSeen(UUID player) {
        if (core != null) {
            core.markRoomSeen(player);
        }
    }

    /** "Thankful for: ...": the helpers in the order of their first gift. */
    public String thankfulLine() {
        return core == null ? FeastRules.thankfulLine(List.of()) : core.thankfulLine();
    }

    /** The Thanksgiving colours for the room's bursts: the show's own, or the built-in ones. */
    public List<Color> colours() {
        HolidayShows.Theme t = theme();
        return t == null ? List.of(Color.ORANGE, Color.fromRGB(0x8B4513), Color.fromRGB(0xFFD700)) : t.colours();
    }

    /** Whether {@code block} is the recorded Feast Pot. */
    public boolean isPot(Block block) {
        FeastRules.Spot at = pot;
        return at != null && block != null && at.is(block.getWorld().getName(), block.getX(), block.getY(),
                block.getZ());
    }

    /** The helpers in the order of their first gift (for the admin's status). */
    public List<UUID> helpers() {
        return core == null ? List.of() : core.helpers();
    }

    /** Whether the fill's show over the pot was given (for the admin's status). */
    public boolean homeShown() {
        return core != null && core.homeShown();
    }

    /** The pot's spot, or null. */
    public FeastRules.Spot pot() {
        return pot;
    }

    // ---- the admin ----------------------------------------------------------------------------------

    /**
     * The block {@code p} looks at (within 5) becomes the Feast Pot. Refused outside an Overworld economy world and in
     * a Games world. @return the admin's line
     */
    public String setPot(Player p) {
        Block b = p.getTargetBlockExact(5);
        if (b == null || b.getType().isAir()) {
            return "&cLook at the block that should be the Feast Pot (within 5 blocks).";
        }
        World w = b.getWorld();
        if (!plugin.sandbox().allowed(w) || Whereabouts.gamesWorld(plugin, w)
                || w.getEnvironment() != World.Environment.NORMAL) {
            return "&cThe Feast Pot goes in the home world (an Overworld economy world), not " + w.getName() + ".";
        }
        FeastRules.Spot at = new FeastRules.Spot(w.getName(), b.getX(), b.getY(), b.getZ());
        try {
            meta.set(FeastRules.POT_KEY, at.text());
        } catch (SQLException e) {
            return "&cThat can't be saved to the database right now.";
        }
        removeName();
        pot = at;
        return "&aThe Feast Pot is the " + b.getType().name().toLowerCase(java.util.Locale.ROOT) + " at " + at.x()
                + ", " + at.y() + ", " + at.z() + " in " + at.world() + ".";
    }

    /** No Feast Pot any more. @return the admin's line */
    public String clearPot() {
        try {
            meta.set(FeastRules.POT_KEY, null);
        } catch (SQLException e) {
            return "&cThat can't be saved to the database right now.";
        }
        removeName();
        pot = null;
        return "&aThe Feast Pot is cleared. &7Set a new one with /hcm feast pot.";
    }

    /** Whether the pot's block is still there (its chunk loaded, not air); null when that can't be told. */
    public Boolean potStanding() {
        FeastRules.Spot at = pot;
        World w = at == null ? null : Bukkit.getWorld(at.world());
        if (w == null || !w.isChunkLoaded(at.x() >> 4, at.z() >> 4)) {
            return null;
        }
        return !w.getBlockAt(at.x(), at.y(), at.z()).getType().isAir();
    }

    /** {@code /hcm feast test}: the fill's moment for {@code p} and the show over the pot, with no marker and no pay. */
    public void test(Player p) {
        moment(p.getUniqueId(), true, thankfulLine());
        homeShow();
    }

    /** {@code /hcm feast reset confirm}: wipe the configured edition, then read the table again. */
    public FeastDao.Reset reset() throws SQLException {
        FeastDao.Reset r = meta.reset(settings().edition());
        if (!r.refused()) {
            core = new FeastCore(this, new GamesDao(plugin.database()), new AchievementDao(plugin.database()), meta);
            core.refresh();
        }
        return r;
    }

    // ---- the pot ------------------------------------------------------------------------------------

    /**
     * A right-click (a Bedrock tap) on the Feast Pot, at LOWEST: the block and the item are denied (no eating, no
     * opening), then for the main hand the food goes in, the empty hand shows the bar, and anything else gets the
     * soft no. Outside the window, or with no pot, it is vanilla's.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onTap(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || !isPot(e.getClickedBlock()) || core == null || !open()) {
            return;
        }
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player p = e.getPlayer();
        if (!taps.counts(p.getUniqueId(), FeastRules.POT_KEY, now())) {
            return;
        }
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            showBar(p);
            return;
        }
        String type = held.getType().name();
        FeastRules.Refusal no = FeastRules.refusal(type, HarvestItems.isHarvest(held), TokenPrizes.carries(held),
                CandyItems.isCandy(held), KitItems.isKit(held), named(held), true);
        if (no != FeastRules.Refusal.NONE) {
            softNo(p);
            return;
        }
        int each = FeastRules.points(type);
        FeastCore.Gift g = core.give(p.getUniqueId(), each, held.getAmount(),
                FeastRules.wholeStack(p.isSneaking(), Bedrock.is(p)), p.getGameMode() == GameMode.CREATIVE);
        switch (g.outcome()) {
            case TAKEN -> {
                ItemStack inHand = p.getInventory().getItemInMainHand();
                if (inHand.isSimilar(held)) {
                    inHand.setAmount(Math.max(0, inHand.getAmount() - g.taken()));
                    p.getInventory().setItemInMainHand(inHand.getAmount() <= 0 ? null : inHand);
                }
                FeastRules.FeastView v = core.view();
                p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.8f, 1.0f);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.9f, FeastRules.pitch(v.total(), v.goal()));
                Location c = potCentre();
                if (c != null) {
                    c.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, c.clone().add(0, 0.8, 0), 6, 0.3, 0.2, 0.3, 0);
                }
                showBar(p);
                core.afterGift(p.getUniqueId());
            }
            case FULL -> {
                softNo(p);
                p.sendActionBar(Text.of("&7Full for today - come back tomorrow!"));
                showBar(p);
            }
            case FAILED -> {
                softNo(p);
                p.sendMessage(Text.of("&cThe Feast Pot can't take that right now. Try again in a moment."));
            }
            case CLOSED -> {
                // the window closed between the checks: nothing
            }
        }
    }

    /** Whether the item has a name of its own (a renamed sword, a prize): the pot takes plain food only. */
    static boolean named(ItemStack item) {
        try {
            return item.hasData(DataComponentTypes.CUSTOM_NAME);
        } catch (RuntimeException | LinkageError e) {
            return true; // can't tell: not taken
        }
    }

    /** The soft "no": a low note, and the item stays in the hand. */
    private static void softNo(Player p) {
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
    }

    /** The family bar for five seconds: it fills as the table does (no reading needed). */
    private void showBar(Player p) {
        FeastRules.FeastView v = view();
        float progress = v.goal() <= 0 ? 0f : (float) Math.min(1.0, (double) v.total() / v.goal());
        BossBar bar = bars.get(p.getUniqueId());
        net.kyori.adventure.text.Component name = Text.of(FeastRules.line(v.total(), v.goal()));
        if (bar == null) {
            bar = BossBar.bossBar(name, progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            bars.put(p.getUniqueId(), bar);
        } else {
            bar.name(name);
            bar.progress(progress);
        }
        p.showBossBar(bar);
        barUntil.put(p.getUniqueId(), now() + FeastRules.BAR_MS);
    }

    private void hideBars() {
        long now = now();
        for (UUID id : new ArrayList<>(barUntil.keySet())) {
            if (barUntil.get(id) <= now) {
                barUntil.remove(id);
                BossBar bar = bars.remove(id);
                Player p = Bukkit.getPlayer(id);
                if (p != null && bar != null) {
                    p.hideBossBar(bar);
                }
            }
        }
    }

    /** The pot's middle, or null with no pot or its world not loaded. */
    private Location potCentre() {
        FeastRules.Spot at = pot;
        World w = at == null ? null : Bukkit.getWorld(at.world());
        return w == null ? null : new Location(w, at.x() + 0.5, at.y() + 0.5, at.z() + 0.5);
    }

    /** The floating name over the pot (made again when its chunk unloaded it), and slow sparkles. */
    private void drawPot() {
        Location c = potCentre();
        if (c == null || !c.getWorld().isChunkLoaded(c.getBlockX() >> 4, c.getBlockZ() >> 4)) {
            return;
        }
        Entity e = potName == null ? null : Bukkit.getEntity(potName);
        if (e == null || !e.isValid()) {
            TextDisplay d = c.getWorld().spawn(c.clone().add(0, NAME_UP - 0.5, 0), TextDisplay.class, t -> {
                t.setPersistent(false);
                t.getPersistentDataContainer().set(Keys.FEAST, PersistentDataType.BYTE, (byte) 1);
                t.setBillboard(Display.Billboard.CENTER);
                t.setShadowed(true);
                t.setDefaultBackground(false);
                t.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
                t.setBrightness(new Display.Brightness(15, 15));
                t.text(Text.of(POT_NAME));
            });
            potName = d.getUniqueId();
        }
        if (ticks % 2 == 0) {
            c.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, c.clone().add(0, 0.7, 0), 3, 0.35, 0.25, 0.35, 0);
        }
    }

    private void removeName() {
        Entity e = potName == null ? null : Bukkit.getEntity(potName);
        if (e != null) {
            e.remove();
        }
        potName = null;
    }

    /** Every pot name a crash or a reload left, in every loaded world. */
    private void sweep() {
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class)) {
                if (tagged(d)) {
                    d.remove();
                }
            }
        }
    }

    private static boolean tagged(Entity e) {
        return Keys.FEAST != null && e.getPersistentDataContainer().has(Keys.FEAST, PersistentDataType.BYTE);
    }

    /** A pot name loaded with its chunk is an old one (they are never saved): it goes. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (tagged(e) && !e.getUniqueId().equals(potName)) {
                e.remove();
            }
        }
    }

    // ---- the guard (only while open and set) -----------------------------------------------------------

    /** Whether the pot is guarded now. */
    private boolean guarded() {
        return pot != null && core != null && open();
    }

    /** Nobody breaks the pot while the Feast is open, except an admin sneaking. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!guarded() || !isPot(e.getBlock())) {
            return;
        }
        Player p = e.getPlayer();
        if (p.hasPermission("hcm.admin") && p.isSneaking()) {
            p.sendMessage(Text.of("&eYou broke the Feast Pot. &7Set a new one with /hcm feast pot."));
            return;
        }
        e.setCancelled(true);
        softNo(p);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (guarded()) {
            e.blockList().removeIf(this::isPot);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (guarded()) {
            e.blockList().removeIf(this::isPot);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (guarded() && e.getBlocks().stream().anyMatch(this::isPot)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (guarded() && e.getBlocks().stream().anyMatch(this::isPot)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (guarded() && isPot(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    // ---- joins and quits ----------------------------------------------------------------------------

    /** A helper who missed the fill is settled a few seconds after they join: paid, their Mini, their moment. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (core != null && open()) {
                core.settle(id);
            }
        }, JOIN_SETTLE_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        taps.forget(id);
        barUntil.remove(id);
        BossBar bar = bars.remove(id);
        if (bar != null) {
            e.getPlayer().hideBossBar(bar);
        }
    }

    /** A title; it is decoration. */
    private static void title(Player p, String big, String small) {
        try {
            p.showTitle(Title.title(Text.of(big), Text.of(small), Title.Times.times(Duration.ofMillis(300),
                    Duration.ofMillis(3_500), Duration.ofMillis(600))));
        } catch (RuntimeException | LinkageError ignored) {
            // a title is decoration
        }
    }
}
