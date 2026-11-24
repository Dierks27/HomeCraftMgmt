package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.arena.ArenaRegions;
import com.dierks.homecraft.games.arena.FallingFloors;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseRegions;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.BukkitWorldPort;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.Regions;
import com.dierks.homecraft.games.gen.engine.WorldPort;
import com.dierks.homecraft.games.lava.rules.LavaMode;
import com.dierks.homecraft.games.lava.rules.LavaResult;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.gui.games.lava.LavaEndMenu;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenMetaDao;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The running server as {@link LavaService} sees it: the clock, the world, the database, the players, and everything
 * shown and played to them. Main thread only; nothing here throws on purpose: a failure is logged, a picture or a
 * sound that fails is skipped (it is decoration), and a player who went offline is simply skipped.
 *
 * <p><b>Every sound is soft.</b> Chimes, note blocks, a sizzle, a bucket's glug: nothing loud, sudden or
 * frightening, and the end's fireworks are sparks with a twinkle, never a bang.
 */
final class LiveLavaHost implements LavaHost {

    /** The warning's rising scale: eight notes up, one for each beat. */
    static final float[] SCALE = {0.5f, 0.56f, 0.63f, 0.67f, 0.75f, 0.84f, 0.94f, 1.0f, 1.12f, 1.26f};

    private final FloorIsLava game;
    private final Map<String, BukkitWorldPort> ports = new HashMap<>();
    private final Map<String, World> portWorlds = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<Integer, UUID> stars = new HashMap<>();

    LiveLavaHost(FloorIsLava game) {
        this.game = game;
    }

    private GamesService games() {
        return game.games();
    }

    // ---- time, settings, world ------------------------------------------------------------------

    @Override
    public long now() {
        return games().clock().nowMillis();
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }

    @Override
    public Logger logger() {
        return game.log();
    }

    /** 0.57.0: the next layout on the server's async pool (pure: it touches nothing of the server's). */
    @Override
    public <T> java.util.concurrent.CompletableFuture<T> offThread(java.util.function.Supplier<T> work) {
        try {
            return java.util.concurrent.CompletableFuture.supplyAsync(work,
                    r -> Bukkit.getScheduler().runTaskAsynchronously(game.plugin(), r));
        } catch (RuntimeException e) { // the plugin is going away: do it here
            return LavaHost.super.offThread(work);
        }
    }

    @Override
    public LavaSettings settings() {
        return game.settings();
    }

    /** Fresh Courses' settings (its world, slots, keep area and safe spot), or {@code null}. */
    private DailySettings fresh() {
        try {
            return games().settings(DailyCourses.SPEC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The Games world, as the Clubhouse and Falling Floors find it: {@code games.fresh.world}, else the first listed. */
    @Override
    public String worldName() {
        DailySettings d = fresh();
        if (d != null && !d.world().isBlank()) {
            return d.world();
        }
        for (String w : games().config().common().worlds()) {
            if (w != null && !w.isBlank()) {
                return w;
            }
        }
        return "";
    }

    @Override
    public WorldPort world(String name) {
        World w = name == null || name.isBlank() ? null : Bukkit.getWorld(name);
        if (w == null) {
            return null;
        }
        String key = w.getName();
        if (portWorlds.get(key) != w) {
            portWorlds.put(key, w);
            ports.put(key, new BukkitWorldPort(game.plugin(), w));
        }
        return ports.get(key);
    }

    @Override
    public String claim() throws Exception {
        return new GenMetaDao(game.plugin().database()).get(LavaService.CLAIM_KEY);
    }

    @Override
    public void claim(String value) throws Exception {
        new GenMetaDao(game.plugin().database()).set(LavaService.CLAIM_KEY, value);
    }

    @Override
    public List<String> regionProblems(Box box, String world) {
        DailySettings d = fresh();
        WorldPort port = world(world);
        Regions.WorldFacts facts = null;
        if (port != null) {
            boolean listed = false;
            for (String w : games().config().common().worlds()) {
                listed |= w != null && w.equalsIgnoreCase(port.name());
            }
            facts = new Regions.WorldFacts(port.name(), listed, port.minHeight(), port.maxHeight(), port.border(),
                    port.spawn(), d == null ? null : d.safeSpot());
        }
        return LavaRegions.problems(box, d, others(games()), handBuilt(), facts);
    }

    /** The other boxes the room keeps apart from: the Falling Floors arena's and the Clubhouse's, as configured. */
    static List<Regions.Extra> others(GamesService g) {
        List<Regions.Extra> out = new ArrayList<>();
        try {
            out.add(new Regions.Extra(ArenaRegions.NAME, g.settings(FallingFloors.SPEC).box()));
        } catch (RuntimeException e) {
            // its settings can't be read: nothing to keep apart from
        }
        try {
            // plan 13: the Clubhouse as far as it may reach (its grown wing only where it crowds nothing, this room too)
            DailySettings fresh = null;
            try {
                fresh = g.settings(DailyCourses.SPEC);
            } catch (RuntimeException e) {
                // unreadable: no slots to ask about
            }
            com.dierks.homecraft.games.arena.FallingFloorsSettings ff = null;
            try {
                ff = g.settings(FallingFloors.SPEC);
            } catch (RuntimeException e) {
                // unreadable: nothing to keep apart from
            }
            out.addAll(DailyCourses.clubhouseExtras(fresh, ff, g.settings(Clubhouse.SPEC),
                    g.settings(FloorIsLava.SPEC)));
        } catch (RuntimeException e) {
            try {
                out.addAll(ClubhouseRegions.extras(g.settings(Clubhouse.SPEC)));
            } catch (RuntimeException again) {
                // as above
            }
        }
        return out;
    }

    /** Every hand-built course's footprint, or {@code null} when the rows can't be read now. */
    private List<Regions.Area> handBuilt() {
        try {
            List<GamesDao.CourseRow> rows = new ArrayList<>(games().dao().courses(Slots.GAME_TRIALS));
            rows.addAll(games().dao().courses(Slots.GAME_GOLF));
            return Regions.handBuilt(rows);
        } catch (SQLException | RuntimeException e) {
            logger().log(Level.WARNING, "The Floor Is Lava: could not read the hand-built courses to check its box", e);
            return null;
        }
    }

    // ---- people ---------------------------------------------------------------------------------

    @Override
    public List<Person> people() {
        List<Person> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            Session s = games().sessions().session(p);
            out.add(new Person(p.getUniqueId(), p.getName(), l.getWorld() == null ? "" : l.getWorld().getName(),
                    l.getX(), l.getY(), l.getZ(), s == null ? null : s.gameId(), s == null ? null : s.ref()));
        }
        return out;
    }

    @Override
    public boolean anyoneOnline() {
        return !Bukkit.getOnlinePlayers().isEmpty();
    }

    @Override
    public double mspt() {
        return Bukkit.getAverageTickTime();
    }

    @Override
    public double[] feet(UUID player) {
        Player p = Bukkit.getPlayer(player);
        if (p == null) {
            return null;
        }
        Location l = p.getLocation();
        return new double[]{l.getX(), l.getY(), l.getZ()};
    }

    @Override
    public boolean inLava(UUID player) {
        Player p = Bukkit.getPlayer(player);
        try {
            return p != null && p.isInLava();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * A player in the room's session goes through the session's own teleport (it also becomes their safe point);
     * anyone else through a plain one. They land still, with no fall and no fire carried over.
     */
    @Override
    public boolean teleport(UUID player, String world, LavaSite.Spot spot) {
        return teleport(player, world, spot, 0f);
    }

    private boolean teleport(UUID player, String world, LavaSite.Spot spot, float pitch) {
        Player p = Bukkit.getPlayer(player);
        World w = world == null ? null : Bukkit.getWorld(world);
        if (p == null || w == null || spot == null) {
            return false;
        }
        Location to = new Location(w, spot.x(), spot.y(), spot.z(), spot.yaw(), pitch);
        boolean ok = games().sessions().session(p) != null ? games().sessions().teleport(p, to) : p.teleport(to);
        if (ok) {
            p.setVelocity(new Vector(0, 0, 0));
            p.setFallDistance(0f);
            p.setFireTicks(0);
        }
        return ok;
    }

    /** 0.57.0: as {@link #teleport}, keeping the way they look (they rode a piece, or one lifted them). */
    @Override
    public boolean carry(UUID player, String world, LavaSite.Spot spot) {
        Player p = Bukkit.getPlayer(player);
        if (p == null || spot == null) {
            return false;
        }
        Location l = p.getLocation();
        return teleport(player, world, new LavaSite.Spot(spot.x(), spot.y(), spot.z(), l.getYaw()), l.getPitch());
    }

    /** To {@code games.fresh.safe_spot}, or the world's spawn, as Fresh Courses moves people. */
    @Override
    public void toSafety(UUID player, String world) {
        Player p = Bukkit.getPlayer(player);
        World w = Bukkit.getWorld(world);
        if (p == null || w == null) {
            return;
        }
        DailySettings d = fresh();
        double[] spot = d == null ? null : d.safeSpot();
        Location at = p.getLocation();
        Location to = spot != null ? new Location(w, spot[0], spot[1], spot[2], at.getYaw(), at.getPitch())
                : w.getSpawnLocation();
        p.teleport(to);
        p.sendMessage(Text.of(LavaText.MOVED));
    }

    @Override
    public void endSession(UUID player) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            game.sendBack(p);
        }
    }

    /**
     * Nothing the lava would leave on a player stays: no fire, no fall, a full breath, and fire resistance (no
     * particles, no icon), which is what makes the lava clear to see through and the screen stay calm in it. The
     * world session already refuses every hurt, hunger and flame; this is the belt to its braces.
     */
    @Override
    public void calm(UUID player) {
        Player p = Bukkit.getPlayer(player);
        if (p == null) {
            return;
        }
        try {
            if (p.getFireTicks() > 0) {
                p.setFireTicks(0);
            }
            if (p.getFallDistance() > 0f) {
                p.setFallDistance(0f);
            }
            if (p.getRemainingAir() < p.getMaximumAir()) {
                p.setRemainingAir(p.getMaximumAir());
            }
            if (!p.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0,
                        true, false, false));
            }
        } catch (RuntimeException | LinkageError e) {
            // the session's own guard still keeps them from harm
        }
    }

    /** A player is going back to the Clubhouse in the same session: what this game put on them comes off. */
    void forget(Player p) {
        try {
            p.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
            p.setFireTicks(0);
            p.setFallDistance(0f);
        } catch (RuntimeException | LinkageError e) {
            // nothing of it can hurt in the Clubhouse either
        }
        hideBar(p.getUniqueId());
    }

    // ---- the kit --------------------------------------------------------------------------------

    /**
     * The kit, key facts in the NAMES and a picture for each: a green dye to start now, a green block to play
     * again, and always a door in the last slot to leave (two clicks, the kit guard's). Nothing else is handed out.
     */
    @Override
    public void kit(UUID player, Kit kit) {
        Player p = Bukkit.getPlayer(player);
        if (p == null || kit == null || games().sessions().session(p) == null) {
            return; // only the room's own players: nobody else's inventory is ever touched
        }
        games().sessions().stripKit(p);
        PlayerInventory inv = p.getInventory();
        if (kit == Kit.GATHER) {
            KitItems.put(inv, 0, KitItems.item(game, FloorIsLava.START, Material.LIME_DYE, LavaText.KIT_START,
                    "&7Don't wait for more players."));
        } else if (kit == Kit.OVER) {
            KitItems.put(inv, 0, KitItems.item(game, FloorIsLava.AGAIN, Material.LIME_CONCRETE, LavaText.KIT_AGAIN,
                    "&7Another round, right away."));
        }
        KitItems.put(inv, 8, KitItems.item(game, FloorIsLava.LEAVE, Material.OAK_DOOR, LavaText.KIT_LEAVE,
                "&7Click twice to leave.", game.cameFromClub(player) ? "&7Back to the Clubhouse."
                        : "&7Your things come back."));
        inv.setHeldItemSlot(kit == Kit.GATHER || kit == Kit.OVER ? 0 : 4);
    }

    // ---- words, titles, sounds, pictures -----------------------------------------------------------

    @Override
    public void tell(UUID player, String line) {
        Player p = Bukkit.getPlayer(player);
        if (p != null && line != null) {
            p.sendMessage(Text.of(line));
        }
    }

    @Override
    public void actionBar(UUID player, String line) {
        Player p = Bukkit.getPlayer(player);
        if (p != null && line != null) {
            p.sendActionBar(Text.of(line));
        }
    }

    @Override
    public void title(UUID player, String big, String small, int stayTicks) {
        Player p = Bukkit.getPlayer(player);
        if (p == null) {
            return;
        }
        try {
            p.showTitle(Title.title(Text.of(big), Text.of(small == null ? "" : small), Title.Times.times(
                    Duration.ofMillis(200), Duration.ofMillis(stayTicks * 50L), Duration.ofMillis(400))));
        } catch (RuntimeException | LinkageError ignored) {
            // a title is decoration
        }
    }

    @Override
    public void sound(UUID player, Cue cue, int step) {
        Player p = Bukkit.getPlayer(player);
        if (p == null || cue == null) {
            return;
        }
        try {
            Location at = p.getLocation();
            switch (cue) {
                case STAR -> p.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f,
                        Math.min(2.0f, 0.8f + 0.06f * Math.max(0, step)));
                case SIZZLE -> p.playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.35f, 1.3f);
                case RUMBLE -> p.playSound(at, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.6f, 0.5f);
                case RISE -> {
                    p.playSound(at, Sound.BLOCK_NOTE_BLOCK_HARP, 0.7f, SCALE[Math.floorMod(step, SCALE.length)]);
                    p.playSound(at, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.35f, 0.5f);
                }
                case LAVA -> p.playSound(at, Sound.ITEM_BUCKET_EMPTY_LAVA, 0.5f, 0.8f);
                case CLEAR -> {
                    p.playSound(at, Sound.ITEM_BUCKET_FILL_LAVA, 0.4f, 1.1f);
                    p.playSound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.5f);
                }
                case OUT -> p.playSound(at, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                case WIN -> {
                    p.playSound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
                    p.playSound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.2f);
                }
                case COUNT -> p.playSound(at, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.0f);
                case START -> p.playSound(at, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.6f);
                case SHIFT -> {
                    p.playSound(at, Sound.BLOCK_BEACON_AMBIENT, 0.8f, 1.4f);
                    p.playSound(at, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.5f, 0.6f);
                }
                case MOVED -> p.playSound(at, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.7f, 1.2f);
                case WOBBLE -> p.playSound(at, Sound.BLOCK_POINTED_DRIPSTONE_LAND, 0.5f,
                        0.7f + 0.05f * Math.floorMod(step, 6));
                case SANK -> p.playSound(at, Sound.ITEM_BUCKET_EMPTY_LAVA, 0.6f, 0.6f);
                case BOING -> p.playSound(at, Sound.BLOCK_SLIME_BLOCK_FALL, 0.8f, 1.0f);
                case SHOWER -> {
                    p.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.6f);
                    p.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.35f, 1.4f);
                }
                case GOAL -> {
                    p.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.2f);
                    p.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.6f);
                }
                case MISSED -> p.playSound(at, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // a sound is decoration
        }
    }

    @Override
    public void fx(String world, Fx fx, double x, double y, double z) {
        World w = world == null ? null : Bukkit.getWorld(world);
        if (w == null || fx == null) {
            return;
        }
        try {
            Location at = new Location(w, x, y, z);
            switch (fx) {
                case SMOKE -> {
                    w.spawnParticle(Particle.POOF, at, 12, 0.25, 0.3, 0.25, 0.02);
                    w.spawnParticle(Particle.WHITE_SMOKE, at, 10, 0.25, 0.4, 0.25, 0.01);
                }
                case SPARKLE -> {
                    w.spawnParticle(Particle.END_ROD, at, 14, 0.3, 0.4, 0.3, 0.03);
                    w.spawnParticle(Particle.WAX_ON, at, 10, 0.35, 0.45, 0.35, 0.0);
                }
                case EMBERS -> w.spawnParticle(Particle.SMALL_FLAME, at, 8, 1.2, 0.2, 1.2, 0.01);
                case FIREWORKS -> {
                    w.spawnParticle(Particle.FIREWORK, at, 60, 0.6, 0.6, 0.6, 0.12);
                    w.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 30, 0.5, 0.5, 0.5, 0.3);
                    w.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.35f, 1.2f);
                }
                case SHIFT -> {
                    w.spawnParticle(Particle.ELECTRIC_SPARK, at, 14, 1.2, 0.3, 1.2, 0.05);
                    w.spawnParticle(Particle.END_ROD, at, 4, 1.0, 0.2, 1.0, 0.01);
                }
                case LANDED -> w.spawnParticle(Particle.POOF, at, 24, 1.4, 0.3, 1.4, 0.02);
                case WOBBLE -> {
                    w.spawnParticle(Particle.FALLING_DUST, at, 16, 1.0, 0.3, 1.0, 0.0,
                            Material.ORANGE_CONCRETE_POWDER.createBlockData());
                    w.spawnParticle(Particle.SMOKE, at, 6, 0.8, 0.2, 0.8, 0.01);
                }
                case SLIME -> w.spawnParticle(Particle.ITEM_SLIME, at, 10, 0.9, 0.2, 0.9, 0.05);
                case SHOWER -> w.spawnParticle(Particle.END_ROD, at, 30, 3.0, 0.5, 3.0, 0.02);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // a picture is decoration
        }
    }

    @Override
    public void bar(UUID player, String title, float progress, boolean red) {
        Player p = Bukkit.getPlayer(player);
        if (p == null) {
            return;
        }
        float at = Math.max(0f, Math.min(1f, progress));
        BossBar.Color color = red ? BossBar.Color.RED : BossBar.Color.YELLOW;
        BossBar bar = bars.get(player);
        if (bar == null) {
            bar = BossBar.bossBar(Text.of(title), at, color, BossBar.Overlay.PROGRESS);
            bars.put(player, bar);
            p.showBossBar(bar);
        } else {
            bar.name(Text.of(title));
            bar.progress(at);
            bar.color(color);
        }
    }

    @Override
    public void hideBar(UUID player) {
        BossBar bar = bars.remove(player);
        Player p = Bukkit.getPlayer(player);
        if (bar != null && p != null) {
            p.hideBossBar(bar);
        }
    }

    // ---- the stars --------------------------------------------------------------------------------

    /**
     * A star: a nether star as an item display, facing whoever looks, glowing and fully lit. A game entity
     * ({@link WorldEntities#tag}): never saved, swept at every start, and nothing anyone can break or take.
     */
    @Override
    public void showStar(String world, int id, double x, double y, double z) {
        hideStar(id);
        World w = world == null ? null : Bukkit.getWorld(world);
        if (w == null || !w.isChunkLoaded((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4)) {
            return;
        }
        try {
            Location at = new Location(w, x, y, z);
            ItemDisplay star = w.spawn(at, ItemDisplay.class, e -> {
                WorldEntities.tag(e, game, null);
                e.setItemStack(new ItemStack(Material.NETHER_STAR));
                e.setBillboard(Display.Billboard.CENTER);
                e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.1f, 1.1f,
                        1.1f), new Quaternionf()));
                e.setBrightness(new Display.Brightness(15, 15));
                e.setGlowing(true);
            });
            stars.put(id, star.getUniqueId());
            w.spawnParticle(Particle.END_ROD, at, 6, 0.2, 0.3, 0.2, 0.01);
        } catch (RuntimeException | LinkageError e) {
            logger().log(Level.WARNING, "The Floor Is Lava: could not put a star out", e);
        }
    }

    @Override
    public void hideStar(int id) {
        UUID uuid = stars.remove(id);
        Entity e = uuid == null ? null : Bukkit.getEntity(uuid);
        if (e != null) {
            e.remove();
        }
    }

    @Override
    public void hideStars() {
        for (Integer id : new ArrayList<>(stars.keySet())) {
            hideStar(id);
        }
        stars.clear();
    }

    /** Every star and every bar away (the game stopped). */
    void hideAll() {
        hideStars();
        for (UUID id : new ArrayList<>(bars.keySet())) {
            hideBar(id);
        }
        bars.clear();
    }

    @Override
    public String name(UUID player) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            return p.getName();
        }
        try {
            OfflinePlayer o = Bukkit.getOfflinePlayer(player);
            return o.getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- results --------------------------------------------------------------------------------

    @Override
    public Long best(UUID player, LavaMode mode) {
        return games().scores().best(player, game.id(), mode.board());
    }

    /** The round's bests onto the boards the games already keep ({@link LavaScores}). No tokens: none are ever paid. */
    @Override
    public void scored(LavaResult result) {
        for (LavaScores.Entry e : LavaScores.entries(result)) {
            games().scores().submit(e.player(), game.id(), e.board(), e.score(), false);
        }
    }

    @Override
    public void endScreen(UUID player, LavaResult result) {
        Player p = Bukkit.getPlayer(player);
        if (p != null && games().sessions().session(p) != null) {
            new LavaEndMenu(game.plugin(), game, p, result).open(p);
        }
    }
}
