package com.dierks.homecraft.hunt;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.courier.Ground;
import com.dierks.homecraft.mini.Grade;
import com.dierks.homecraft.mini.Loot;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.mini.MiniType;
import com.dierks.homecraft.mini.Rarity;
import com.dierks.homecraft.storage.MiniSpawnDao;
import com.dierks.homecraft.storage.WildSpawnDao;
import com.dierks.homecraft.util.Heads;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The wild hunt (the NATURAL_SPAWN trigger).
 *
 * <p><b>A spawn is a blueprint until somebody catches it.</b> It used to mint the moment it
 * appeared and retire the copy when it escaped, so every Mini that got away burned a mint number
 * and a cap slot forever — and the Museum's "Minted" count went up for Minis nobody ever held.
 * Now the head carries only which Mini it is and the grade and finish it will have. Catching it
 * mints the copy then and there; an escape removes the head, counts the escape, and mints nothing.
 * While it stands, the spawn holds its cap slot as a reservation ({@link #reserved}), so a Card or
 * a print cannot take the last copy out from under it.
 *
 * <p><b>A hunt is a shared event.</b> One Mini at a time ({@code max_live: 1}), announced with
 * the name of the player it appeared near, then narrowed by escalating hints — the biome, a
 * compass direction from where that player stood — and finally a light beam. Never coordinates,
 * never a distance.
 *
 * <p>The throttles are unchanged in kind: {@code chance_percent} is rolled per player per tick,
 * {@code player_cooldown_minutes} caps how often one player is targeted and {@code max_live} how
 * many stand at once. Hunt gear changes WHO a spawn lands near (a Mini Lure) and how warm you are
 * (a Mini Radar), never how many spawn.
 */
public final class HuntService {

    /** Tokens paid instead when a caught Mini cannot be minted after all. */
    public static final int CRUMBLE_TOKENS = 25;

    /** Spots closer than this to ANY player are refused, so nobody is handed one at their feet. */
    private static final int MIN_PLAYER_GAP = 16;
    private static final int SPOT_ATTEMPTS = 32;
    private static final int GROUND_DEPTH = 32;

    private static final BlockFace[] FACINGS = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    /** One live wild spawn. Mutable only in its hint stage and cached words. */
    public static final class Hunt {
        private final long id;
        private final String world;
        private final int x;
        private final int y;
        private final int z;
        private final String miniId;
        private final Grade grade;
        private final String finish;
        private final UUID target;
        private final int anchorX;
        private final int anchorZ;
        private final long spawnedAt;
        private final long expiresAt;
        private int hintStage;
        private String biome;

        Hunt(WildSpawnDao.Blueprint b) {
            this.id = b.id();
            this.world = b.world();
            this.x = b.x();
            this.y = b.y();
            this.z = b.z();
            this.miniId = b.miniId();
            this.grade = Grade.parse(b.grade());
            this.finish = b.finish();
            this.target = b.target();
            this.anchorX = b.anchorX();
            this.anchorZ = b.anchorZ();
            this.spawnedAt = b.spawnedAt();
            this.expiresAt = b.expiresAt();
            this.hintStage = b.hintStage();
        }

        public String world() {
            return world;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int z() {
            return z;
        }

        public String miniId() {
            return miniId;
        }

        public Grade grade() {
            return grade;
        }

        public boolean shiny() {
            return "SHINY".equalsIgnoreCase(finish);
        }

        /** The player it appeared near, or null (a spawn converted from before this build). */
        public UUID target() {
            return target;
        }

        public long spawnedAt() {
            return spawnedAt;
        }

        public long expiresAt() {
            return expiresAt;
        }

        public int hintStage() {
            return hintStage;
        }

        public Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }

        String key() {
            return HuntService.key(world, x, y, z);
        }
    }

    private final HomeCraftManagement plugin;
    private final WildSpawnDao dao;
    private final MiniSpawnDao legacy;
    private final Map<String, Hunt> live = new LinkedHashMap<>();
    /** Player → the moment they may be targeted again. Memory-only: a restart forgives it. */
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** Admin override: the next natural spawn lands near this player. */
    private UUID nextTarget;
    private BukkitTask spawnTask;
    private BukkitTask tickTask;

    public HuntService(HomeCraftManagement plugin, WildSpawnDao dao, MiniSpawnDao legacy) {
        this.plugin = plugin;
        this.dao = dao;
        this.legacy = legacy;
    }

    // ---- lifecycle -------------------------------------------------------------------

    /** (Re)arm the spawn roll and the 2-second hunt tick (expiry, hints, missing heads). */
    public void start() {
        stop();
        Loot.Natural n = natural();
        long interval = Math.max(200, n.intervalTicks());
        spawnTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::spawnTick, interval, interval);
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::huntTick, 40L, 40L);
    }

    public void stop() {
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    /**
     * Load live spawns (converting any left over from the mint-on-spawn days), check their heads
     * are still standing, and put their effects back. Run once the worlds are up.
     */
    public void rebuild() {
        convertLegacy();
        live.clear();
        List<WildSpawnDao.Blueprint> rows;
        try {
            rows = dao.all();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load wild Mini spawns: " + e.getMessage());
            return;
        }
        for (WildSpawnDao.Blueprint b : rows) {
            Hunt h = new Hunt(b);
            live.put(h.key(), h);
        }
        for (Hunt h : new ArrayList<>(live.values())) {
            Location loc = h.location();
            if (loc == null) {
                continue; // its world is not loaded; the row waits for it
            }
            if (loc.getWorld().isChunkLoaded(h.x >> 4, h.z >> 4) && !isWild(loc.getBlock())) {
                escape(h, false); // the head was edited away while we were off
                continue;
            }
            register(h, false);
        }
    }

    /**
     * The upgrade from mint-on-spawn. A legacy spawn holds a minted copy nobody has caught: that
     * copy is retired now — exactly the shape an escaped copy has always had, so
     * {@code /hcm mini repair-escaped} gives its number back — and the spawn carries on as a
     * blueprint with the same grade, finish and remaining time. Its head loses the copy's
     * identity (uid, mint number, stored item). Idempotent: converted rows are deleted.
     */
    private void convertLegacy() {
        List<MiniSpawnDao.Spawn> rows;
        try {
            rows = legacy.all();
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not read legacy wild spawns: " + e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            return;
        }
        MiniService minis = plugin.miniService();
        int converted = 0;
        for (MiniSpawnDao.Spawn s : rows) {
            ItemStack item = Items.fromBase64(s.itemB64());
            Grade grade = item != null ? minis.gradeOf(item) : Grade.STANDARD;
            String finish = item != null && minis.isShiny(item) ? "SHINY" : null;
            try {
                // hint_stage 1: the old build already sent its spawn hint for this one.
                dao.insert(new WildSpawnDao.Blueprint(0, s.world(), s.x(), s.y(), s.z(), s.miniId(),
                        grade.name(), finish, null, s.x(), s.z(), s.spawnedAt(), s.expiresAt(), 1));
                legacy.deleteById(s.id());
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not convert the legacy wild spawn at " + s.world() + " "
                        + s.x() + "," + s.y() + "," + s.z() + ": " + e.getMessage());
                continue;
            }
            if (item != null) {
                minis.retire(item);
            }
            World w = Bukkit.getWorld(s.world());
            if (w != null && w.isChunkLoaded(s.x() >> 4, s.z() >> 4)) {
                Block block = w.getBlockAt(s.x(), s.y(), s.z());
                if (block.getState() instanceof Skull skull) {
                    PersistentDataContainer pdc = skull.getPersistentDataContainer();
                    pdc.remove(Keys.MINI_UID);
                    pdc.remove(Keys.MINI_MINT);
                    pdc.remove(Keys.MINI_ITEM);
                    pdc.set(Keys.WILD_GRADE, PersistentDataType.STRING, grade.name());
                    if (finish != null) {
                        pdc.set(Keys.WILD_FINISH, PersistentDataType.STRING, finish);
                    }
                    skull.update(true, false);
                }
            }
            converted++;
        }
        plugin.getLogger().info("Wild hunt: converted " + converted + " live spawn(s) from mint-on-spawn to "
                + "blueprints. Their pre-minted copies were retired; run /hcm mini repair-escaped to give "
                + "those numbers back.");
    }

    // ---- reading ---------------------------------------------------------------------

    /** Every live wild spawn, oldest first. */
    public Collection<Hunt> live() {
        return Collections.unmodifiableCollection(new ArrayList<>(live.values()));
    }

    /** Live spawns of one Mini — the cap slots they are holding. */
    public int reserved(String miniId) {
        int n = 0;
        for (Hunt h : live.values()) {
            if (h.miniId.equals(miniId)) {
                n++;
            }
        }
        return n;
    }

    /** The live spawn nearest a player in their world, or null. */
    public Hunt nearest(Player player) {
        Hunt best = null;
        double bestD = Double.MAX_VALUE;
        Location at = player.getLocation();
        for (Hunt h : live.values()) {
            if (!h.world.equals(at.getWorld().getName())) {
                continue;
            }
            double d = distance(at, h);
            if (d < bestD) {
                bestD = d;
                best = h;
            }
        }
        return best;
    }

    /** How warm a player is: the radar band for the nearest live spawn in their world. */
    public HuntMath.Band distanceBand(Player player) {
        Hunt h = nearest(player);
        return h == null ? HuntMath.Band.NONE : HuntMath.band(distance(player.getLocation(), h));
    }

    private static double distance(Location at, Hunt h) {
        double dx = at.getX() - (h.x + 0.5);
        double dy = at.getY() - h.y;
        double dz = at.getZ() - (h.z + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Milliseconds left on a hunt, never negative. */
    public long msLeft(Hunt h) {
        return Math.max(0, h.expiresAt - System.currentTimeMillis());
    }

    /** The hint lines sent so far for one hunt, rendered, oldest first. */
    public List<String> hintsSoFar(Hunt h) {
        List<String> out = new ArrayList<>();
        List<Loot.Hint> hints = natural().hints();
        for (int i = 0; i < Math.min(h.hintStage, hints.size()); i++) {
            if (hints.get(i).enabled()) {
                out.add(render(h, hints.get(i)));
            }
        }
        return out;
    }

    // ---- lures and targeting ---------------------------------------------------------

    /**
     * Arm a Mini Lure for {@code player}: the next natural spawn that rolls for ANYONE lands
     * around the earliest-armed eligible holder instead. It never adds a spawn, only moves one.
     *
     * @return false if they already have one armed (one at a time), or it could not be stored
     */
    public boolean armLure(Player player) {
        try {
            return dao.armLure(player.getUniqueId(), System.currentTimeMillis());
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not arm a Mini Lure: " + e.getMessage());
            return false;
        }
    }

    public boolean lureArmed(UUID player) {
        try {
            return dao.lures().containsKey(player);
        } catch (SQLException e) {
            return false;
        }
    }

    /** Every armed lure, earliest first. */
    public Map<UUID, Long> lures() {
        try {
            return dao.lures();
        } catch (SQLException e) {
            return Map.of();
        }
    }

    /** Admin: the next natural spawn lands near this player, whoever rolls it. */
    public void overrideNextTarget(UUID player) {
        this.nextTarget = player;
    }

    public UUID nextTarget() {
        return nextTarget;
    }

    /** Whether a player can have a Mini appear near them at all (cooldown aside). */
    private boolean eligible(Player p) {
        if (p == null || !p.isOnline()) {
            return false;
        }
        GameMode gm = p.getGameMode();
        return (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE) && plugin.sandbox().allowed(p.getWorld());
    }

    // ---- spawning --------------------------------------------------------------------

    private void spawnTick() {
        Loot.MiniLoot loot = plugin.config().miniLoot();
        List<Loot.LootSource> sources = loot.sourcesFor(Loot.Trigger.NATURAL_SPAWN);
        if (sources.isEmpty()) {
            return;
        }
        Loot.Natural n = loot.natural();
        long now = System.currentTimeMillis();
        for (Player roller : Bukkit.getOnlinePlayers()) {
            if (n.maxLive() > 0 && live.size() >= n.maxLive()) {
                return;
            }
            if (!eligible(roller)) {
                continue;
            }
            Long eligibleAt = cooldowns.get(roller.getUniqueId());
            if (eligibleAt != null && eligibleAt > now) {
                continue;
            }
            for (Loot.LootSource source : sources) {
                if (source.chancePercent() <= 0
                        || ThreadLocalRandom.current().nextDouble() * 100.0 >= source.chancePercent()) {
                    continue;
                }
                MiniDef def = plugin.wildDrops().pick(source);
                if (def == null || def.type() != MiniType.HEAD) {
                    continue; // only head Minis can sit in the world as a block
                }
                Grade grade = plugin.wildDrops().rollGrade(source, def);
                String finish = plugin.wildDrops().rollShiny(source) ? "SHINY" : null;
                if (spawnForRoll(roller, def, grade, finish)) {
                    break; // at most one spawn per player per tick
                }
            }
        }
    }

    /**
     * Land a rolled spawn: near the admin's override if set, else near the earliest-armed lure
     * holder who can have one, else near whoever rolled it. A lure holder's own cooldown is
     * ignored — that is what the lure is for — and the lure is spent only if a Mini actually lands.
     */
    private boolean spawnForRoll(Player roller, MiniDef def, Grade grade, String finish) {
        if (nextTarget != null) {
            Player p = Bukkit.getPlayer(nextTarget);
            if (eligible(p) && spawn(p, def, grade, finish) != null) {
                nextTarget = null;
                return true;
            }
        }
        for (UUID holder : lures().keySet()) {
            Player p = Bukkit.getPlayer(holder);
            if (!eligible(p)) {
                continue;
            }
            if (spawn(p, def, grade, finish) != null) {
                try {
                    dao.disarmLure(holder);
                } catch (SQLException e) {
                    plugin.getLogger().warning("Could not spend a Mini Lure: " + e.getMessage());
                }
                p.sendMessage(Text.of("&d✦ Your Mini Lure worked! &7A wild Mini appeared near you."));
                return true;
            }
        }
        return spawn(roller, def, grade, finish) != null;
    }

    /**
     * Admin: put a wild Mini near {@code target} now. Respects the caps (including every spawn
     * already holding a slot) and {@code max_live}; ignores the chance roll and cooldowns.
     *
     * @param rarity only this rarity, or null for a rarity-weighted pick across every Mini
     * @return null on success, else why not
     */
    public String forceSpawn(Rarity rarity, Player target) {
        Loot.Natural n = natural();
        if (n.maxLive() > 0 && live.size() >= n.maxLive()) {
            return "A hunt is already running (max_live " + n.maxLive() + "). /hcm hunt clear first.";
        }
        MiniService minis = plugin.miniService();
        List<MiniDef> pool = new ArrayList<>();
        for (MiniDef d : minis.catalog()) {
            if (d.type() == MiniType.HEAD && (rarity == null || d.rarity() == rarity) && !minis.mintedOut(d)) {
                pool.add(d);
            }
        }
        MiniDef def = minis.pickByRarity(pool);
        if (def == null) {
            return "No " + (rarity == null ? "" : rarity.display() + " ") + "head Mini has a copy left.";
        }
        Grade grade = minis.rollGrade(minis.cardSpec(def));
        String finish = minis.rollShiny(plugin.config().miniLoot().shinyPercent()) ? "SHINY" : null;
        Hunt h = spawn(target, def, grade, finish);
        return h == null ? "Found no ground to put it on near " + target.getName()
                + " (tried " + SPOT_ATTEMPTS + " spots " + n.minDistance() + "–" + n.maxDistance() + " blocks out)." : null;
    }

    /** Place a blueprint near {@code target}; null if there was no spot or no copy left. */
    private Hunt spawn(Player target, MiniDef def, Grade grade, String finish) {
        if (plugin.miniService().mintedOut(def)) {
            return null;
        }
        Loot.Natural n = natural();
        Location spot = findSpot(target, n);
        if (spot == null) {
            return null;
        }
        Block block = spot.getBlock();
        if (!placeHead(block, def, grade, finish)) {
            return null;
        }
        long now = System.currentTimeMillis();
        long expires = now + n.despawnMinutes() * 60_000L;
        Location anchor = target.getLocation();
        WildSpawnDao.Blueprint b = new WildSpawnDao.Blueprint(0, spot.getWorld().getName(), spot.getBlockX(),
                spot.getBlockY(), spot.getBlockZ(), def.id(), grade.name(), finish, target.getUniqueId(),
                anchor.getBlockX(), anchor.getBlockZ(), now, expires, 0);
        long id;
        try {
            id = dao.insert(b);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to store a wild Mini spawn: " + e.getMessage());
            block.setType(Material.AIR, false);
            return null;
        }
        Hunt h = new Hunt(new WildSpawnDao.Blueprint(id, b.world(), b.x(), b.y(), b.z(), b.miniId(), b.grade(),
                b.finish(), b.target(), b.anchorX(), b.anchorZ(), b.spawnedAt(), b.expiresAt(), 0));
        h.biome = biomeAt(spot);
        live.put(h.key(), h);
        if (n.playerCooldownMinutes() > 0) {
            cooldowns.put(target.getUniqueId(), now + n.playerCooldownMinutes() * 60_000L);
        }
        register(h, true);
        advanceHints(h, now);
        plugin.getLogger().info("Wild hunt: a " + def.rarity().display() + " " + def.name() + " (" + grade.name()
                + (finish != null ? ", " + finish : "") + ") appeared near " + target.getName() + " at "
                + b.world() + " " + b.x() + "," + b.y() + "," + b.z() + ".");
        return h;
    }

    /**
     * A spot on real ground {@code min_distance}–{@code max_distance} blocks from the player.
     *
     * <p>It used to take {@code getHighestBlockAt}, which is MOTION_BLOCKING — the top of the
     * tree canopy in any wood — so Minis landed on treetops. {@link Ground#solid} looks down
     * through leaves, trunks and plants to the actual ground (the same fix the Courier needed),
     * and only a column that IS ground is accepted: no lake bed, no cave void. The head goes on
     * top of it, into air (or a tuft of grass it replaces) with open air above.
     *
     * <p>Also refused: anywhere within {@value #MIN_PLAYER_GAP} blocks of any player, somewhere
     * the target could not build, and a HomeCraft block's position.
     */
    private Location findSpot(Player player, Loot.Natural n) {
        World world = player.getWorld();
        Location origin = player.getLocation();
        for (int attempt = 0; attempt < SPOT_ATTEMPTS; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            double dist = n.minDistance() + ThreadLocalRandom.current().nextDouble() * (n.maxDistance() - n.minDistance());
            int x = origin.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = origin.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Ground.Column col = Ground.solid(world, x, z, GROUND_DEPTH);
            if (!col.isGround()) {
                continue;
            }
            int gy = col.y();
            if (gy <= world.getMinHeight() || gy + 3 >= world.getMaxHeight()) {
                continue;
            }
            Block ground = world.getBlockAt(x, gy, z);
            if (ground.getType() == Material.LIGHT) {
                continue;
            }
            Block spot = ground.getRelative(BlockFace.UP);
            Block above = spot.getRelative(BlockFace.UP);
            if (!(spot.getType().isAir() || (isReplaceable(spot) && !spot.isLiquid()))
                    || !above.getType().isAir()) {
                continue;
            }
            Location loc = spot.getLocation();
            if (nearAnyPlayer(world, x, z)) {
                continue;
            }
            if (plugin.config().respectTownPerms() && !plugin.protection().canBuild(player, loc)) {
                continue;
            }
            if (plugin.blockService().at(loc).isPresent()) {
                continue;
            }
            return loc;
        }
        return null;
    }

    private static boolean isReplaceable(Block b) {
        try {
            return Tag.REPLACEABLE.isTagged(b.getType());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean nearAnyPlayer(World world, int x, int z) {
        for (Player p : world.getPlayers()) {
            double dx = p.getLocation().getX() - x;
            double dz = p.getLocation().getZ() - z;
            if (dx * dx + dz * dz < MIN_PLAYER_GAP * MIN_PLAYER_GAP) {
                return true;
            }
        }
        return false;
    }

    /**
     * Put the head down: textured from the Mini, carrying only what it will BE — MINI_ID, the
     * wild marker, and the grade and finish it will be minted with. No uid, no mint number.
     */
    private boolean placeHead(Block block, MiniDef def, Grade grade, String finish) {
        try {
            block.setType(Material.PLAYER_HEAD, false);
            BlockData data = block.getBlockData();
            if (data instanceof Rotatable rot) {
                rot.setRotation(FACINGS[ThreadLocalRandom.current().nextInt(FACINGS.length)]);
                block.setBlockData(rot, false);
            }
            if (!(block.getState() instanceof Skull skull)) {
                return false;
            }
            ItemStack head = Heads.base(def.texture());
            if (head.getItemMeta() instanceof SkullMeta meta && meta.getPlayerProfile() != null) {
                skull.setPlayerProfile(meta.getPlayerProfile());
            }
            PersistentDataContainer pdc = skull.getPersistentDataContainer();
            pdc.set(Keys.MINI_ID, PersistentDataType.STRING, def.id());
            pdc.set(Keys.WILD_SPAWN, PersistentDataType.BYTE, (byte) 1);
            pdc.set(Keys.WILD_GRADE, PersistentDataType.STRING, grade.name());
            if (finish != null) {
                pdc.set(Keys.WILD_FINISH, PersistentDataType.STRING, finish);
            }
            skull.update(true, false);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not place a wild Mini head: " + t.getMessage());
            block.setType(Material.AIR, false);
            return false;
        }
    }

    private void register(Hunt h, boolean justPlaced) {
        MiniDef def = plugin.miniService().def(h.miniId);
        Location loc = h.location();
        if (def == null || loc == null) {
            return;
        }
        plugin.effects().registerWild(loc, def, h.grade, h.shiny(), justPlaced);
        if (beamDue(h)) {
            plugin.effects().setWildBeam(loc, true);
        }
    }

    // ---- claiming ----------------------------------------------------------------------

    /**
     * True if the block is a still-claimable wild Mini head. Cheap on anything that is not a
     * floor head — it is asked on every liquid flow, piston push and explosion — and reads the
     * live block state rather than taking a snapshot.
     */
    public boolean isWild(Block block) {
        return block != null && block.getType() == Material.PLAYER_HEAD
                && block.getState(false) instanceof Skull skull
                && skull.getPersistentDataContainer().has(Keys.WILD_SPAWN, PersistentDataType.BYTE);
    }

    /**
     * {@code player} caught the wild Mini at {@code block}: release its reservation, mint it now,
     * hand it over and announce the find. If it cannot be minted after all (an admin lowered a
     * cap mid-hunt), it crumbles and pays {@value #CRUMBLE_TOKENS} tokens instead.
     *
     * @param removeBlock clear the head (false when a BlockBreakEvent is already removing it)
     * @return true if this was a wild spawn and it was dealt with
     */
    public boolean claim(Player player, Block block, boolean removeBlock) {
        if (!isWild(block)) {
            return false;
        }
        Hunt h = live.get(key(block.getLocation()));
        if (removeBlock) {
            block.setType(Material.AIR, false);
        }
        if (h == null) {
            // A wild head with no hunt behind it (cleared, or left over from a failed write).
            // It carries no copy, so there is nothing to give — just tidy it away.
            plugin.getLogger().info("Wild hunt: removed a stale wild head at " + block.getWorld().getName() + " "
                    + block.getX() + "," + block.getY() + "," + block.getZ() + " touched by " + player.getName() + ".");
            plugin.effects().unregisterBlock(block.getLocation());
            return true;
        }
        if (!forget(h)) {
            return true; // already taken this tick
        }
        MiniDef def = plugin.miniService().def(h.miniId);
        MiniService.Minted m = def == null ? null : plugin.miniService().mintCaught(player, def, h.grade, h.finish);
        if (m == null || !m.ok()) {
            player.sendMessage(Text.of("&7It crumbled to dust! &eHere are " + CRUMBLE_TOKENS + " tokens instead."));
            if (plugin.tokens() != null) {
                plugin.tokens().award(player, CRUMBLE_TOKENS, TokenService.Source.HUNT,
                        (def != null ? def.name() : h.miniId) + " crumbled");
            }
            plugin.getLogger().info("Wild hunt: " + player.getName() + " caught " + h.miniId
                    + " but it could not be minted (" + (m == null ? "not in the catalog" : m.error())
                    + ") — paid " + CRUMBLE_TOKENS + " tokens.");
            return true;
        }
        player.sendMessage(Text.of("&b✦ You caught a wild &f" + def.name() + " " + h.grade.symbol()
                + (h.shiny() ? " &f✦Shiny" : "") + "&b! &7(Mint #" + m.mintNumber() + ")"));
        plugin.announce().found(player, def, m.item(), Loot.Trigger.NATURAL_SPAWN.verb());
        if (plugin.quests() != null) {
            plugin.quests().record(player, PluginConfig.QuestType.FIND_WILD_MINI, 1);
        }
        try {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
        } catch (Throwable ignored) {
            // cosmetic
        }
        plugin.getLogger().info("Wild hunt: " + player.getName() + " caught " + def.name() + " #" + m.mintNumber() + ".");
        return true;
    }

    // ---- escaping ----------------------------------------------------------------------

    /**
     * The hunt ended without a catch — the timer ran out, or the head was blown up, pushed,
     * washed away or edited out. Remove the head, count the escape, say so. Nothing is minted
     * and nothing is retired: there was never a copy to lose.
     *
     * @param removeBlock clear the head too (false when the world has already removed it)
     */
    public void escape(Hunt h, boolean removeBlock) {
        if (!forget(h)) {
            return;
        }
        Location loc = h.location();
        if (removeBlock && loc != null) {
            Block block = loc.getBlock();
            if (isWild(block)) {
                block.setType(Material.AIR, false);
            }
        }
        plugin.miniService().recordEscape(h.miniId);
        MiniDef def = plugin.miniService().def(h.miniId);
        plugin.announce().escaped(def != null ? def.rarity() : Rarity.COMMON);
        plugin.getLogger().info("Wild hunt: the " + h.miniId + " at " + h.world + " " + h.x + "," + h.y + "," + h.z
                + " got away.");
    }

    /** The hunt standing at this block, if any. */
    public Hunt at(Block block) {
        return live.get(key(block.getLocation()));
    }

    /** Admin: remove every live spawn. Not an escape — nothing is counted or announced. */
    public int clear() {
        int n = 0;
        for (Hunt h : new ArrayList<>(live.values())) {
            if (!forget(h)) {
                continue;
            }
            Location loc = h.location();
            if (loc != null && isWild(loc.getBlock())) {
                loc.getBlock().setType(Material.AIR, false);
            }
            n++;
        }
        return n;
    }

    /**
     * Drop a hunt from the registry, the table and the effects — releasing its reservation.
     *
     * @return false if it was already gone (so a claim and an escape can never both win)
     */
    private boolean forget(Hunt h) {
        if (live.remove(h.key()) == null) {
            return false;
        }
        try {
            dao.delete(h.world, h.x, h.y, h.z);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to delete a wild Mini spawn: " + e.getMessage());
        }
        Location loc = h.location();
        if (loc != null) {
            plugin.effects().unregisterBlock(loc);
        }
        return true;
    }

    // ---- the 2-second tick: expiry, missing heads, hints -------------------------------

    private void huntTick() {
        long now = System.currentTimeMillis();
        cooldowns.values().removeIf(until -> until <= now);
        for (Hunt h : new ArrayList<>(live.values())) {
            if (h.expiresAt <= now) {
                escape(h, true);
                continue;
            }
            World w = Bukkit.getWorld(h.world);
            if (w == null) {
                continue;
            }
            if (w.isChunkLoaded(h.x >> 4, h.z >> 4) && !isWild(w.getBlockAt(h.x, h.y, h.z))) {
                escape(h, false); // gone some way no event told us about
                continue;
            }
            advanceHints(h, now);
        }
    }

    /** Send every hint stage that has come due, in order, and switch the beam on after the last. */
    private void advanceHints(Hunt h, long now) {
        List<Loot.Hint> hints = natural().hints();
        int due = HuntMath.stagesDue(hints, h.spawnedAt, h.expiresAt, now);
        boolean moved = false;
        while (h.hintStage < due) {
            Loot.Hint hint = hints.get(h.hintStage);
            if (hint.enabled()) {
                MiniDef def = plugin.miniService().def(h.miniId);
                plugin.announce().hint(def != null ? def.rarity() : Rarity.COMMON, render(h, hint));
            }
            h.hintStage++;
            moved = true;
        }
        if (moved) {
            try {
                dao.setHintStage(h.id, h.hintStage);
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to save a wild hunt's hint stage: " + e.getMessage());
            }
            if (beamDue(h)) {
                Location loc = h.location();
                if (loc != null) {
                    plugin.effects().setWildBeam(loc, true);
                }
            }
        }
    }

    /** The beam comes on once the final hint stage has been reached. */
    private boolean beamDue(Hunt h) {
        List<Loot.Hint> hints = natural().hints();
        return !hints.isEmpty() && h.hintStage >= hints.size();
    }

    /** One hint line with its placeholders filled, as a legacy '&' string. */
    private String render(Hunt h, Loot.Hint hint) {
        MiniDef def = plugin.miniService().def(h.miniId);
        String rarity = def == null ? "wild" : plugin.miniService().rarityText(def.rarity()) + "&d";
        String player = "a player";
        if (h.target != null) {
            String name = Bukkit.getOfflinePlayer(h.target).getName();
            if (name != null) {
                player = name;
            }
        }
        if (h.biome == null) {
            Location loc = h.location();
            if (loc != null && loc.getWorld().isChunkLoaded(h.x >> 4, h.z >> 4)) {
                h.biome = biomeAt(loc);
            }
        }
        int minutes = (int) Math.max(1, Math.round((h.expiresAt - h.spawnedAt) / 60_000.0));
        return "&d" + HuntMath.fill(hint.text(), rarity, player, minutes, h.biome,
                HuntMath.direction(h.anchorX, h.anchorZ, h.x, h.z));
    }

    private static String biomeAt(Location loc) {
        try {
            return HuntMath.prettyBiome(loc.getBlock().getBiome().getKey().toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /** A player who joins mid-hunt gets the hints sent so far. */
    public void replayHints(Player player) {
        for (Hunt h : live.values()) {
            for (String line : hintsSoFar(h)) {
                player.sendMessage(Text.of(line));
            }
        }
    }

    private Loot.Natural natural() {
        return plugin.config().miniLoot().natural();
    }

    private static String key(Location loc) {
        return key(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private static String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }
}
