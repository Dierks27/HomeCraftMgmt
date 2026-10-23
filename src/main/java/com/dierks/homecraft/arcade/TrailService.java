package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.storage.PrizeDao;
import com.dierks.homecraft.util.Whereabouts;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Particle trails bought from the Prize Counter: time-limited, one switched on at a time, and
 * quiet — a couple of particles at the feet (leaves fall from the shoulders), only while the
 * player is actually walking, so a trail is something you notice following a friend and not a
 * cloud around everybody standing at the Mall. Picked in My Looks (the Wallet's tile, {@code /hcm trail}); {@code
 * /hcm trail toggle} switches it off and back on.
 *
 * <p>From 0.38 {@code cosmetics_owned} also holds the rights to rented hats ({@code kind = 'HAT'}): only trail rows are
 * trails here. A rented trail's end is said by its heads-up ({@code arcade.looks.LookService}); drawing simply stops.
 *
 * <p>A trail is a pattern (SHOP-V2-SPEC §5.5): steps taken in turn, one per puff. A rental's or a collector's is one
 * step, today's puff every 150 ms; a Keeper trail's rotates its own particles ({@link Keepers#pattern}). Each puff is
 * sent to each viewer on their own ({@link #sees}): never where a trail can't show ({@link #showsHere}: a game, a
 * Games world, spectator mode, invisible), and never to a viewer who hides other people's trails (§5.6, the pref
 * {@value #HIDE_PREF}), though your own always shows to you. A worn Keeper's mark (§5.3a) goes out the same way, at
 * its own pace ({@link Keepers#MARK_MS}); it is not a trail, and never "the trail that's on".
 */
public final class TrailService implements Listener {

    /** At most one puff per this many milliseconds per player: a rental's and a collector's pace. */
    static final long PUFF_MS = 150;
    /** The "your trail is on!" burst: this many particles, shared out between a pattern's steps, with this spread. */
    static final int BURST_COUNT = 12;
    private static final double BURST_SPREAD = 0.6;
    /** How far another player can be and still see a puff, squared (32 blocks). */
    static final double VIEW_SQ = 32 * 32;
    /** The {@code game_prefs} key of the hide-others switch ("1" = hidden; absent = shown). */
    public static final String HIDE_PREF = "arcade.trails.hide_others";

    private record Active(String prizeId, TrailPattern pattern, long expiresAt) {
    }

    /** One puff of a trail: how far above the feet, how many particles, and their spread. */
    public record Puff(double y, int count, double dx, double dy, double dz) {
    }

    /** One step of a pattern: a particle and how it puffs. */
    public record Step(Particle particle, Puff puff) {
    }

    /** A trail's look: its steps, taken in turn one per puff, and the time between puffs. */
    public record TrailPattern(long puffMs, List<Step> steps) {
        public TrailPattern {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /** A worn Keeper's mark: which Keeper it is and its one step. */
    public record Mark(String keeperId, Step step) {
    }

    /** Every trail but leaves: a couple of particles just above the feet. */
    private static final Puff AT_FEET = new Puff(0.15, 2, 0.15, 0.05, 0.15);
    /** Leaves fall from the shoulders: at the feet they would land at once (kid-bedrock #3). */
    private static final Puff FROM_SHOULDERS = new Puff(1.3, 1, 0.3, 0.1, 0.3);

    /** How a trail of this particle puffs: from shoulder height for any particle ending in _LEAVES. */
    static Puff puff(String particle) {
        return particle != null && particle.trim().toUpperCase(Locale.ROOT).endsWith("_LEAVES")
                ? FROM_SHOULDERS : AT_FEET;
    }

    /** A rental's or a collector's pattern: one step, today's puff for its particle, every {@value #PUFF_MS} ms. */
    static TrailPattern single(Particle particle) {
        return new TrailPattern(PUFF_MS, List.of(new Step(particle, puff(particle.name()))));
    }

    /** The step after {@code index} in a pattern of {@code size} steps, round and round. */
    static int nextStep(int index, int size) {
        return size <= 0 ? 0 : (Math.max(0, index) + 1) % size;
    }

    /** How many of each step's particle a burst shows: {@value #BURST_COUNT} shared out, at least one each. */
    static int burstEach(int steps) {
        return Math.max(1, BURST_COUNT / Math.max(1, steps));
    }

    /**
     * Whether a wearer's trail (or mark) shows at all where they are: not in a game session, not in a Games world, not
     * in spectator mode, not invisible (a vanished admin's trail would give them away). Pure.
     */
    static boolean showsHere(boolean inGameSession, boolean gamesWorld, boolean spectator, boolean invisible) {
        return !inGameSession && !gamesWorld && !spectator && !invisible;
    }

    /**
     * Whether one viewer is sent a puff: the wearer always sees their own; anyone else only when they don't hide other
     * people's trails, can see the wearer, and is within 32 blocks of the puff. Pure.
     */
    static boolean sees(boolean wearer, boolean hidesOthers, boolean canSee, double distanceSq) {
        return wearer || (!hidesOthers && canSee && distanceSq <= VIEW_SQ);
    }

    /** Whether a puff (or a mark) is due: never sent before, or {@code gapMs} gone since the last. */
    static boolean due(Long last, long now, long gapMs) {
        return last == null || now - last >= gapMs;
    }

    /**
     * Whether a mark puffs now (§5.3a): marks on ({@code arcade.keepers.marks}), its Keeper's key on the head, walking,
     * where a trail can show, {@link Keepers#MARK_MS} since the last, and for the Crown not while its Royal Trail is the
     * trail that's on (the trail is its mark then). Pure.
     */
    static boolean markPuffs(boolean marksOn, boolean worn, boolean walking, boolean showsHere,
                             boolean crownWithRoyalOn, Long last, long now) {
        return marksOn && worn && walking && showsHere && !crownWithRoyalOn && due(last, now, Keepers.MARK_MS);
    }

    private final HomeCraftManagement plugin;
    private final PrizeDao dao;
    private final Map<UUID, Active> active = new HashMap<>();
    /** Each player's next step in their pattern. */
    private final Map<UUID, Integer> stepIndex = new HashMap<>();
    private final Map<UUID, Long> lastPuff = new HashMap<>();
    /** Each wearer's Keeper mark, set by the wardrobe when a worn Keeper key goes on or comes off. */
    private final Map<UUID, Mark> marks = new HashMap<>();
    private final Map<UUID, Long> lastMark = new HashMap<>();
    /** The players who hide other people's trails, marks and parties (§5.6), loaded at join. */
    private final Set<UUID> hiding = new HashSet<>();
    /** Pattern steps already warned about (a particle this server can't show), so a reload warns once. */
    private final Set<String> warnedSteps = new HashSet<>();
    /** The trail each player last switched off, so one tap on "off" is undone by one more (red team O6). */
    private final Map<UUID, String> lastOn = new HashMap<>();

    public TrailService(HomeCraftManagement plugin, PrizeDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    /** Add {@code days} to a trail (switching it on if the player has none on); false on failure. */
    public boolean grant(Player player, Prize trail, int days) {
        if (trail.type() != PrizeType.TRAIL) {
            return false;
        }
        try {
            dao.grantCosmetic(player.getUniqueId(), trail.id(), days * 86_400_000L, System.currentTimeMillis(), true);
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not grant a trail: " + e.getMessage());
            return false;
        }
        load(player);
        if (plugin.looks() != null) {
            plugin.looks().reload(player.getUniqueId()); // the minute's check sees a crate's day, and its end
        }
        return true;
    }

    /** One owned, unexpired trail and whether it is the one switched on. */
    public record Owned(Prize prize, long expiresAt, boolean on) {
    }

    /** The player's unexpired trails, in config order: trail rows only, never a rented hat's right. */
    public List<Owned> owned(Player player) {
        List<Owned> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        try {
            Map<String, PrizeDao.Cosmetic> mine = new HashMap<>();
            for (PrizeDao.Cosmetic c : dao.cosmetics(player.getUniqueId())) {
                mine.put(c.id(), c);
            }
            for (Prize p : plugin.config().arcade().prizes()) {
                PrizeDao.Cosmetic c = mine.get(p.id());
                if (p.type() == PrizeType.TRAIL && c != null && c.expiresAt() > now
                        && com.dierks.homecraft.storage.LookDao.TRAIL.equals(c.kind())) {
                    out.add(new Owned(p, c.expiresAt(), c.enabled()));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not read trails: " + e.getMessage());
        }
        return out;
    }

    /** The trail switched on right now, or null. */
    public Owned current(Player player) {
        for (Owned o : owned(player)) {
            if (o.on()) {
                return o;
            }
        }
        return null;
    }

    /**
     * Switch trails on or off. Off → on picks the one that was last on ({@link #lastOn}), else the first owned.
     *
     * @return a message for the player
     */
    public String toggle(Player player) {
        List<Owned> mine = owned(player);
        if (mine.isEmpty()) {
            return "&7You don't have a trail. Get one at the Prize Counter!";
        }
        Owned on = current(player);
        Owned back = on == null ? lastOn(player) : null;
        try {
            if (on != null) {
                dao.enableOnly(player.getUniqueId(), null);
                lastOn.put(player.getUniqueId(), on.prize().id());
                load(player);
                return "&7Trail off.";
            }
            dao.enableOnly(player.getUniqueId(), back.prize().id());
        } catch (SQLException e) {
            return "&cCouldn't change your trail — try again.";
        }
        load(player);
        return "&aTrail on: &f" + back.prize().display();
    }

    /**
     * The trail "off → on" switches on: the one last switched off while it still runs, else the first owned; null
     * with none owned. My Looks' trail switch (slot 6) names it.
     */
    public Owned lastOn(Player player) {
        List<Owned> mine = owned(player);
        if (mine.isEmpty()) {
            return null;
        }
        return mine.get(lastOnIndex(mine.stream().map(o -> o.prize().id()).toList(), lastOn.get(player.getUniqueId())));
    }

    /** Which of {@code owned} (ids, in order) "back on" picks: {@code last} while owned, else the first. */
    public static int lastOnIndex(List<String> owned, String last) {
        if (last != null) {
            for (int i = 0; i < owned.size(); i++) {
                if (owned.get(i).equalsIgnoreCase(last)) {
                    return i;
                }
            }
        }
        return 0;
    }

    /**
     * The Wallet's trail tile: off → the first owned trail → the next one … → off again. With one
     * trail it is the same as {@link #toggle}.
     *
     * @return a message for the player
     */
    public String cycle(Player player) {
        List<Owned> mine = owned(player);
        if (mine.isEmpty()) {
            return "&7You don't have a trail. Get one at the Prize Counter!";
        }
        List<String> ids = new ArrayList<>();
        String on = null;
        for (Owned o : mine) {
            ids.add(o.prize().id());
            if (o.on()) {
                on = o.prize().id();
            }
        }
        String next = next(ids, on);
        if (next != null) {
            return select(player, next);
        }
        try {
            dao.enableOnly(player.getUniqueId(), null);
        } catch (SQLException e) {
            return "&cCouldn't change your trail — try again.";
        }
        if (on != null) {
            lastOn.put(player.getUniqueId(), on);
        }
        load(player);
        return "&7Trail off.";
    }

    /**
     * The trail {@link #cycle} switches to after {@code on} among the {@code owned} ids (in order), or
     * null for off: none on → the first; the last → off; one no longer owned → the first.
     */
    static String next(List<String> owned, String on) {
        if (owned == null || owned.isEmpty()) {
            return null;
        }
        if (on == null) {
            return owned.get(0);
        }
        for (int i = 0; i < owned.size(); i++) {
            if (owned.get(i).equalsIgnoreCase(on)) {
                return i + 1 < owned.size() ? owned.get(i + 1) : null;
            }
        }
        return owned.get(0);
    }

    /** Switch on one particular trail by prize id, with its "your trail is on!" burst. */
    public String select(Player player, String prizeId) {
        return select(player, prizeId, true);
    }

    /**
     * {@link #select(Player, String)}, with or without the burst: a kept trail that comes on by itself when a rented
     * one ends comes on quietly (red team O7).
     */
    public String select(Player player, String prizeId, boolean withBurst) {
        for (Owned o : owned(player)) {
            if (o.prize().id().equalsIgnoreCase(prizeId)) {
                try {
                    dao.enableOnly(player.getUniqueId(), o.prize().id());
                } catch (SQLException e) {
                    return "&cCouldn't change your trail — try again.";
                }
                load(player);
                if (withBurst) {
                    burst(player);
                }
                return "&aTrail on: &f" + o.prize().display();
            }
        }
        return "&7You don't have that trail.";
    }

    /**
     * Switch trails off (My Looks' slot 6), remembering the one that was on for "back on".
     *
     * @return a message for the player
     */
    public String off(Player player) {
        Owned on = current(player);
        if (on == null) {
            return "&7Your trail is already off.";
        }
        try {
            dao.enableOnly(player.getUniqueId(), null);
        } catch (SQLException e) {
            return "&cCouldn't change your trail — try again.";
        }
        lastOn.put(player.getUniqueId(), on.prize().id());
        load(player);
        return "&7Trail off.";
    }

    /**
     * One puff of a trail of {@code particle} at {@code at}, for everyone near: the look preview's stand
     * ({@code /hcm looks preview}). False when the particle can't show here.
     */
    public boolean puffAt(Location at, String particle) {
        if (at == null || at.getWorld() == null || !particleUsable(particle)) {
            return false;
        }
        try {
            return puffAt(at, single(Particle.valueOf(particle.trim().toUpperCase(Locale.ROOT))).steps().get(0));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * One puff of step {@code step} of trail {@code p}'s pattern at {@code at}, for everyone near (the preview stand,
     * which runs a Keeper trail in the form the config picks). False when nothing can show.
     */
    public boolean puffAt(Location at, Prize p, int step) {
        TrailPattern pattern = p == null ? null : pattern(p);
        if (pattern == null || pattern.steps().isEmpty()) {
            return false;
        }
        return puffAt(at, pattern.steps().get(Math.floorMod(step, pattern.steps().size())));
    }

    /** One puff of {@code step} at {@code at}, for everyone near (the preview stand's trail or mark). */
    public boolean puffAt(Location at, Step step) {
        if (at == null || at.getWorld() == null || step == null || !particleUsable(step.particle().name())) {
            return false;
        }
        try {
            Puff puff = step.puff();
            at.getWorld().spawnParticle(step.particle(), at.clone().add(0, puff.y(), 0), puff.count(), puff.dx(),
                    puff.dy(), puff.dz(), 0.0);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * "Your trail is on!" (kid-bedrock #3): a trail puffs where the player was, behind a first-person
     * camera, so switching one on shows 12 of its particles once, in front of their eyes, to them only.
     * Every collector trail's grant is followed by a select, so a present or a buy gets it too.
     */
    private void burst(Player player) {
        Active a = active.get(player.getUniqueId());
        if (a == null) {
            return;
        }
        int each = burstEach(a.pattern().steps().size());
        for (Step step : a.pattern().steps()) {
            try {
                player.spawnParticle(step.particle(), burstAt(player.getEyeLocation()), each,
                        BURST_SPREAD, BURST_SPREAD, BURST_SPREAD, 0.0);
            } catch (RuntimeException ignored) {
                // cosmetic: a particle that needs data shows nothing here, as on the move
            }
        }
    }

    /** Where the burst shows: 1.5 blocks along the look from the eyes, and 0.8 up. */
    static Location burstAt(Location eye) {
        return eye.clone().add(eye.getDirection().multiply(1.5)).add(0, 0.8, 0);
    }

    /**
     * Refresh the in-memory switched-on trail for a player: its pattern in the form the config picks, every step
     * checked ({@link #particleUsable}) and a bad one dropped with one WARN.
     */
    public void load(Player player) {
        Owned on = current(player);
        if (on == null) {
            active.remove(player.getUniqueId());
            stepIndex.remove(player.getUniqueId());
            return;
        }
        TrailPattern pattern = pattern(on.prize());
        if (pattern == null || pattern.steps().isEmpty()) {
            active.remove(player.getUniqueId());
            stepIndex.remove(player.getUniqueId());
            return;
        }
        Active before = active.put(player.getUniqueId(), new Active(on.prize().id(), pattern, on.expiresAt()));
        if (before == null || !before.prizeId().equalsIgnoreCase(on.prize().id())) {
            stepIndex.remove(player.getUniqueId());
        }
    }

    /**
     * A trail row's pattern, its steps checked: a Keeper's in the form {@code arcade.keepers.looks} picks, any other one
     * step of its particle. Null when nothing of it can show.
     */
    TrailPattern pattern(Prize p) {
        TrailPattern raw;
        if (p.keeper()) {
            raw = Keepers.pattern(p.id(), Keepers.variant(plugin.config().arcade().keepers(), p.id()));
        } else {
            Particle particle = particle(p.particle());
            raw = particle == null ? null : single(particle);
        }
        return raw == null ? null : usable(p.id(), raw);
    }

    /** {@code pattern} without the steps this server can't show, each warned about once. Null when none is left. */
    private TrailPattern usable(String id, TrailPattern pattern) {
        List<Step> ok = new ArrayList<>();
        for (Step step : pattern.steps()) {
            if (particleUsable(step.particle().name())) {
                ok.add(step);
            } else if (warnedSteps.add(id + ":" + step.particle().name())) {
                plugin.getLogger().warning("Trail '" + id + "': particle " + step.particle().name() + " can't show on "
                        + "this server (it needs data, or isn't here) - that step is left out.");
            }
        }
        return ok.isEmpty() ? null : new TrailPattern(pattern.puffMs(), ok);
    }

    // ---- marks (§5.3a) ------------------------------------------------------------------------------

    /**
     * Set (or, with null, clear) a wearer's Keeper mark: the wardrobe calls this when a worn Keeper key goes on or
     * comes off, and at each sweep. A mark whose particle can't show here is not set (the Keeper self-test said so).
     */
    public void mark(UUID player, Mark mark) {
        if (mark == null || mark.step() == null || !particleUsable(mark.step().particle().name())) {
            marks.remove(player);
            return;
        }
        Mark before = marks.put(player, mark);
        if (before == null || !before.keeperId().equalsIgnoreCase(mark.keeperId())) {
            lastMark.remove(player);
        }
    }

    /** A wearer's mark now, or null. */
    public Mark markOf(UUID player) {
        return marks.get(player);
    }

    // ---- hide other people's trails (§5.6) -----------------------------------------------------------

    /** Whether {@code player} hides other people's trails, marks and parties (the My Looks switch). */
    public boolean hidesOthers(UUID player) {
        return player != null && hiding.contains(player);
    }

    /**
     * Hide or show other people's trails, marks and parties, for this player only: saved as their pref, so it holds
     * across a restart. Their own trail always shows to them.
     *
     * @return a message for the player
     */
    public String hideOthers(Player player, boolean hide) {
        UUID id = player.getUniqueId();
        try {
            dao.setPref(id, HIDE_PREF, hide ? "1" : null);
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not save " + player.getName() + "'s trail switch: " + e.getMessage());
            return "&cCouldn't change that — try again.";
        }
        if (hide) {
            hiding.add(id);
            return "&7Other people's trails and parties are hidden for you now.";
        }
        hiding.remove(id);
        return "&7Other people's trails and parties show again.";
    }

    /**
     * Read the hide-others switch of everyone already online, at enable: after a plugin reload nobody waits for a
     * rejoin to have their switch back (the join reads it for everyone else).
     */
    public void loadOnline() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            loadHide(p);
        }
    }

    /** The switch read at join; a read that fails leaves it shown (the default). */
    private void loadHide(Player player) {
        UUID id = player.getUniqueId();
        try {
            if ("1".equals(dao.pref(id, HIDE_PREF))) {
                hiding.add(id);
            } else {
                hiding.remove(id);
            }
        } catch (SQLException e) {
            hiding.remove(id);
            plugin.getLogger().warning("Could not read " + player.getName() + "'s trail switch: " + e.getMessage());
        }
    }

    private Particle particle(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Trail particle '" + name + "' is not a particle — that trail shows nothing.");
            return null;
        }
    }

    /**
     * Whether a trail of particle {@code name} can show on this server: a particle it has, needing no data
     * (final audit F7). The Autumn Leaves trail's ORANGE_POPLAR_LEAVES is new in the 26.3 API, so on 26.2 it
     * is not, and that collector is not sold or given there ({@link PrizeService#visible}).
     */
    static boolean particleUsable(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        try {
            return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT)).getDataType() == Void.class;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        loadHide(event.getPlayer());
        load(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        active.remove(id);
        stepIndex.remove(id);
        lastPuff.remove(id);
        lastOn.remove(id);
        marks.remove(id);
        lastMark.remove(id);
        hiding.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (active.isEmpty() && marks.isEmpty()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return; // only while walking somewhere, not while looking around
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Active a = active.get(id);
        Mark m = marks.get(id);
        if (a == null && m == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Boolean shows = null;
        if (a != null) {
            if (a.expiresAt() <= now) {
                active.remove(id); // its heads-up says it ended (SHOP-V2-SPEC §3.10)
                stepIndex.remove(id);
                a = null;
            } else if (due(lastPuff.get(id), now, a.pattern().puffMs())) {
                lastPuff.put(id, now);
                shows = showsHere(player);
                if (shows) {
                    List<Step> steps = a.pattern().steps();
                    int i = Math.floorMod(stepIndex.getOrDefault(id, 0), steps.size());
                    stepIndex.put(id, nextStep(i, steps.size()));
                    if (!send(player, steps.get(i), from)) {
                        active.remove(id); // a particle that needs data we do not give — stop trying
                        a = null;
                    }
                }
            }
        }
        if (m != null && due(lastMark.get(id), now, Keepers.MARK_MS)) {
            boolean worn = com.dierks.homecraft.arcade.looks.LookService.wearing(player, m.keeperId());
            if (!worn) {
                marks.remove(id); // the key came off: the wardrobe sets it again when it goes back on
                return;
            }
            boolean royalOn = Keepers.CROWN.equalsIgnoreCase(m.keeperId()) && a != null
                    && Keepers.ROYAL.equalsIgnoreCase(a.prizeId());
            if (shows == null) {
                shows = showsHere(player);
            }
            if (markPuffs(plugin.config().arcade().keepers().marks(), true, true, shows, royalOn,
                    lastMark.get(id), now)) {
                lastMark.put(id, now);
                send(player, m.step(), from);
            }
        }
    }

    /** {@link #showsHere(boolean, boolean, boolean, boolean)} for a real wearer. */
    private boolean showsHere(Player wearer) {
        try {
            return showsHere(Whereabouts.inGameSession(plugin, wearer), Whereabouts.gamesWorld(plugin, wearer.getWorld()),
                    wearer.getGameMode() == GameMode.SPECTATOR,
                    wearer.isInvisible() || wearer.hasPotionEffect(PotionEffectType.INVISIBILITY));
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * One puff of {@code step} at {@code feet}, sent to each viewer in the wearer's world who {@link #sees} it (replaces
     * a puff for the whole world, so hide-others and a vanished wearer hold). False when the particle can't be drawn.
     */
    private boolean send(Player wearer, Step step, Location feet) {
        Puff puff = step.puff();
        Location at = feet.clone().add(0, puff.y(), 0);
        try {
            for (Player viewer : wearer.getWorld().getPlayers()) {
                boolean self = viewer.getUniqueId().equals(wearer.getUniqueId());
                if (sees(self, hidesOthers(viewer.getUniqueId()), self || viewer.canSee(wearer),
                        self ? 0 : viewer.getLocation().distanceSquared(at))) {
                    viewer.spawnParticle(step.particle(), at, puff.count(), puff.dx(), puff.dy(), puff.dz(), 0.0);
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
