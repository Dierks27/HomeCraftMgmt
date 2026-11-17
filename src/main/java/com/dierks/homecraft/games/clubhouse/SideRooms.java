package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.pack.ClubModels;
import com.dierks.homecraft.pack.PackModels;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The Clubhouse's side rooms in the world (0.41.0, the owner: rooms Lilah, four, can use on her own with a controller).
 * Everything here is a picture, a sound or sparkles; text is only ever an extra for the adults.
 *
 * <ul>
 *   <li><b>The Arcade Room's cabinets</b>: one per game of the kid set ({@link KidSet}), each an Interaction entity
 *       (so a left click and a right click both register, on a controller too) and the game's cabinet model. A tap
 *       goes through {@code /hcm play}'s own entry point and gate ({@link GamesService#openKid}): Classic at once,
 *       never today's scored try. The tap chimes and sparkles; a win or a new best ({@link GamesService.Cheer}) plays
 *       a fanfare and a burst at that cabinet.</li>
 *   <li><b>The Trophy Room's cups</b> ({@link TrophyShelf}): a gold, silver or bronze cup on each pedestal with its
 *       holder's head beside it and a small label for the adults. A cup that changes hands bursts with a fanfare
 *       ("getting a trophy"). Walking in, your own cups shimmer for a few seconds, for your eyes only.</li>
 *   <li><b>The door signs</b>: the Arcade Room's joystick and the Trophy Room's cup over their archways.</li>
 * </ul>
 *
 * <p><b>Models or stand-ins, per player.</b> Every model is an item display carrying {@code item_model hcm:<name>}
 * ({@link ClubModels}); each has a vanilla stand-in (a jukebox-and-note-block cabinet with the game's Games-screen icon
 * floating over it, an ingot for a cup, a lever or a totem for a sign). Both are hidden by default; a player who has
 * loaded the pack (CraftBridge is running and took HCM's source, and their last pack status is "loaded") is shown the
 * models, anyone else the stand-ins, so nobody ever sees the missing-texture cube. Checked once a second.
 *
 * <p>Every entity is a game entity ({@link WorldEntities#tag}): never saved, swept at every start, made again only
 * where it changed (its place, its game, its cup or its holder), and only in a loaded chunk.
 */
final class SideRooms {

    /** How high over a cabinet's floor its stand-in icon floats, and its label. */
    static final double ICON_UP = 2.9;
    static final double LABEL_UP = 2.2;
    /** A trophy's label over its pedestal top, and how long a walker's shimmer lasts (seconds). */
    static final double CUP_LABEL_UP = 1.3;
    static final int SHIMMER_SECONDS = 8;
    /** Two taps of one cabinet closer than this are one (a right click sends two packets; a left one may too). */
    static final long TAP_MS = 700;
    /** A cheer is played at the cabinet the player tapped, if they are this near it. */
    static final double CHEER_REACH = 16;

    private final Clubhouse club;
    /** What is up now, by slot ("cab:snake", "door:arcade", "cup:4"). */
    private final Map<String, Shown> shown = new HashMap<>();
    /** Each player's view as last set: models or stand-ins, for this player object, at this generation. */
    private final Map<UUID, Applied> applied = new HashMap<>();
    private long generation;
    private final Map<UUID, Long> tapAt = new HashMap<>();
    private final Map<UUID, Tapped> tapped = new HashMap<>();
    private final Map<UUID, Long> cheerAt = new HashMap<>();
    /** Who was in the Trophy Room at the last second, and how many seconds of shimmer each has left. */
    private final Set<UUID> inTrophy = new HashSet<>();
    private final Map<UUID, Integer> shimmer = new HashMap<>();
    private List<TrophyShelf.Cup> cups = List.of();
    private List<ClubhousePlanner.Pedestal> pedestals = List.of();
    private final Set<String> warned = new HashSet<>();

    /** One slot's entities: what it shows, and which are the model's, the stand-in's or everyone's. */
    private record Shown(String sig, List<UUID> all, List<UUID> models, List<UUID> plain) {
    }

    private record Applied(Object player, boolean models, long generation) {
    }

    private record Tapped(String game, Location at, long when) {
    }

    SideRooms(Clubhouse club) {
        this.club = club;
    }

    // ---- once a second -------------------------------------------------------------------------------

    /** Once a second while the room is open: draw what changed, set each player's view, shimmer. */
    void second(ClubhouseRoom room, long now) {
        World w = room == null ? null : Bukkit.getWorld(room.world());
        if (w == null) {
            remove();
            return;
        }
        ClubhouseSettings s = club.settings();
        Set<String> keep = new HashSet<>();
        if (s.arcadeRoom()) {
            List<String> games = kidSet(room.hand() ? room.handCabinetGames() : s.arcadeGames());
            for (Map.Entry<String, ClubhousePlanner.Place3> e : room.cabinetPlaces(games).entrySet()) {
                String key = "cab:" + e.getKey();
                keep.add(key);
                put(w, key, e.getKey() + "@" + e.getValue(), e.getValue(), () -> cabinet(w, e.getKey(), e.getValue()));
            }
        }
        for (String which : ClubhouseRoom.DOORS) {
            boolean arcade = which.equals("arcade");
            ClubhousePlanner.Place3 at = (arcade ? s.arcadeRoom() : s.trophyRoom()) ? room.doorSign(which) : null;
            if (at != null) {
                String key = "door:" + which;
                keep.add(key);
                put(w, key, which + "@" + at, at, () -> door(w, arcade, at));
            }
        }
        List<TrophyShelf.Cup> nowCups = List.of();
        if (s.trophyRoom()) {
            pedestals = room.pedestals();
            nowCups = TrophyShelf.cups(club.fame(), pedestals.size());
            for (TrophyShelf.Cup c : nowCups) {
                ClubhousePlanner.Pedestal p = pedestals.get(c.slot());
                String key = "cup:" + c.slot();
                keep.add(key);
                put(w, key, c + "@" + p, p.trophy(), () -> cup(w, c, p));
            }
            for (int slot : TrophyShelf.won(cups, nowCups)) {
                ClubhousePlanner.Pedestal p = pedestals.get(slot);
                trophyBurst(w, loc(w, p.trophy(), 0.6));
            }
        } else {
            pedestals = List.of();
        }
        cups = nowCups;
        for (String key : new ArrayList<>(shown.keySet())) {
            if (!keep.contains(key)) {
                take(shown.remove(key));
            }
        }
        views(w);
        shimmer(room, w);
    }

    /** The kid set for {@code ids}: arcade cabinet games only, never a game of chance (each refusal said once). */
    private List<String> kidSet(List<String> ids) {
        GamesService g = club.games();
        Function<String, GameKind> kinds = id -> {
            Game game = g.game(id);
            return game == null ? null : game.kind();
        };
        return KidSet.resolve(ids, kinds, ClubhousePlanner.CABINETS, line -> {
            if (warned.add(line)) {
                club.log().warning("Clubhouse: " + line);
            }
        });
    }

    /** Draw slot {@code key} unless it already shows {@code sig} and every entity is alive; asked, never loaded. */
    private void put(World w, String key, String sig, ClubhousePlanner.Place3 at, java.util.function.Supplier<Shown> make) {
        Shown s = shown.get(key);
        if (s != null && s.sig().equals(sig) && alive(s, w)) {
            return;
        }
        if (!w.isChunkLoaded((int) Math.floor(at.x()) >> 4, (int) Math.floor(at.z()) >> 4)) {
            if (s != null && !s.sig().equals(sig)) {
                take(shown.remove(key)); // a moved slot's old entities don't linger while the new chunk isn't loaded
            }
            return;
        }
        if (s != null) {
            take(s);
        }
        shown.put(key, make.get());
        generation++;
    }

    // ---- the entities --------------------------------------------------------------------------------

    /** A cabinet: the tap target, the model, the stand-in (two blocks and the floating icon), the adults' label. */
    private Shown cabinet(World w, String gameId, ClubhousePlanner.Place3 at) {
        ClubModels.Model m = ClubModels.cabinetFor(gameId);
        List<UUID> models = new ArrayList<>();
        List<UUID> plain = new ArrayList<>();
        List<UUID> all = new ArrayList<>();
        Interaction tap = w.spawn(loc(w, at, 0), Interaction.class, e -> {
            WorldEntities.tag(e, club, null);
            e.setInteractionWidth(1.0f);
            e.setInteractionHeight(2.0f);
            e.setResponsive(true);
            e.getPersistentDataContainer().set(Keys.KID_CABINET, PersistentDataType.STRING, gameId);
        });
        all.add(tap.getUniqueId());
        models.add(model(w, m, loc(w, at, m.up())).getUniqueId());
        double bx = Math.floor(at.x());
        double bz = Math.floor(at.z());
        plain.add(block(w, new Location(w, bx, at.y(), bz), Material.JUKEBOX).getUniqueId());
        plain.add(block(w, new Location(w, bx, at.y() + 1, bz), Material.NOTE_BLOCK).getUniqueId());
        plain.add(w.spawn(loc(w, at, ICON_UP), ItemDisplay.class, e -> {
            hidden(e);
            e.setItemStack(new ItemStack(m.fallback()));
            e.setBillboard(Display.Billboard.CENTER);
            e.setTransformation(scale(1.0f));
            e.setBrightness(new Display.Brightness(15, 15));
        }).getUniqueId());
        Game g = club.games().game(gameId);
        all.add(label(w, loc(w, at, LABEL_UP), "&b" + (g == null ? gameId : g.name()), 0.5f).getUniqueId());
        all.addAll(models);
        all.addAll(plain);
        return new Shown(gameId, all, models, plain);
    }

    /** A door sign: the model, drawn 1.5 times, or its stand-in (a lever: a joystick; a totem: a trophy). */
    private Shown door(World w, boolean arcade, ClubhousePlanner.Place3 at) {
        ClubModels.Model m = ClubModels.door(arcade);
        List<UUID> models = List.of(model(w, m, loc(w, at, 0)).getUniqueId());
        List<UUID> plain = List.of(w.spawn(loc(w, at, 0), ItemDisplay.class, e -> {
            hidden(e);
            e.setItemStack(new ItemStack(m.fallback()));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setTransformation(scale(1.6f));
            e.setRotation(at.yaw(), 0f);
            e.setBrightness(new Display.Brightness(15, 15));
        }).getUniqueId());
        List<UUID> all = new ArrayList<>(models);
        all.addAll(plain);
        return new Shown(m.name(), all, models, plain);
    }

    /** A cup on its pedestal: the model or its ingot, the holder's head beside it, and the adults' label. */
    private Shown cup(World w, TrophyShelf.Cup c, ClubhousePlanner.Pedestal p) {
        ClubModels.Model m = c.model();
        List<UUID> models = List.of(model(w, m, loc(w, p.trophy(), m.up())).getUniqueId());
        List<UUID> plain = List.of(w.spawn(loc(w, p.trophy(), 0.5), ItemDisplay.class, e -> {
            hidden(e);
            e.setItemStack(new ItemStack(m.fallback()));
            e.setBillboard(Display.Billboard.VERTICAL);
            e.setTransformation(scale(0.9f));
            e.setBrightness(new Display.Brightness(15, 15));
        }).getUniqueId());
        List<UUID> all = new ArrayList<>(models);
        all.addAll(plain);
        if (c.id() != null) {
            all.add(w.spawn(loc(w, p.head(), 0.3), ItemDisplay.class, e -> {
                WorldEntities.tag(e, club, null);
                e.setItemStack(head(c.id()));
                e.setBillboard(Display.Billboard.VERTICAL); // the face always to whoever looks
                e.setTransformation(scale(1.0f));
                e.setBrightness(new Display.Brightness(15, 15));
            }).getUniqueId());
        }
        all.add(label(w, loc(w, p.trophy(), CUP_LABEL_UP), c.label(), 0.45f).getUniqueId());
        return new Shown(c.toString(), all, models, plain);
    }

    /** A model's display: {@code item_model hcm:<name>}, drawn as built ({@code NONE}), its north side to its yaw. */
    private ItemDisplay model(World w, ClubModels.Model m, Location at) {
        return w.spawn(at, ItemDisplay.class, e -> {
            hidden(e);
            e.setItemStack(modelItem(m));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setTransformation(scale(m.scale()));
            e.setRotation(at.getYaw(), 0f);
        });
    }

    /** The item a model's display carries: a plain paper drawn as {@code hcm:<name>}. */
    static ItemStack modelItem(ClubModels.Model m) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setItemModel(NamespacedKey.fromString(m.id()));
            it.setItemMeta(meta);
        }
        return it;
    }

    private BlockDisplay block(World w, Location at, Material type) {
        return w.spawn(at, BlockDisplay.class, e -> {
            hidden(e);
            e.setBlock(type.createBlockData());
        });
    }

    private TextDisplay label(World w, Location at, String text, float size) {
        return w.spawn(at, TextDisplay.class, e -> {
            WorldEntities.tag(e, club, null);
            e.setBillboard(Display.Billboard.CENTER);
            e.setTransformation(scale(size));
            e.setDefaultBackground(false);
            e.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            e.setBrightness(new Display.Brightness(15, 15));
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.text(Text.of(text));
        });
    }

    /** A game entity nobody sees until their view is set ({@link #views}). */
    private void hidden(Entity e) {
        WorldEntities.tag(e, club, null);
        e.setVisibleByDefault(false);
    }

    private static ItemStack head(UUID id) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(id));
            head.setItemMeta(skull);
        }
        return head;
    }

    private static Transformation scale(float s) {
        return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf());
    }

    private static Location loc(World w, ClubhousePlanner.Place3 at, double up) {
        return new Location(w, at.x(), at.y() + up, at.z(), at.yaw(), 0f);
    }

    private static boolean alive(Shown s, World w) {
        for (UUID id : s.all()) {
            Entity e = Bukkit.getEntity(id);
            if (e == null || !e.isValid() || !e.getWorld().equals(w)) {
                return false;
            }
        }
        return true;
    }

    private static void take(Shown s) {
        if (s == null) {
            return;
        }
        for (UUID id : s.all()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
    }

    /** Everything comes down (the room closed, moved or stopped). */
    void remove() {
        for (Shown s : shown.values()) {
            take(s);
        }
        shown.clear();
        applied.clear();
        cups = List.of();
        pedestals = List.of();
        inTrophy.clear();
        shimmer.clear();
    }

    /** How many slots are up (cabinets, signs and cups), for the status. */
    int count() {
        return shown.size();
    }

    // ---- who sees the models -------------------------------------------------------------------------

    /**
     * Whether a player sees the models (pure): HCM's models are in CraftBridge's pack, and their last pack status is
     * "loaded". Declined, failed, not answered yet or no CraftBridge: the stand-ins.
     */
    static boolean seesModels(boolean packOn, PlayerResourcePackStatusEvent.Status status) {
        return packOn && status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
    }

    private boolean packOn() {
        PackModels pm = club.plugin() == null ? null : club.plugin().packModels();
        return pm != null && pm.active();
    }

    /** Each player in the room's world: the models or the stand-ins shown, where it changed. */
    private void views(World w) {
        boolean packOn = packOn();
        for (Player p : w.getPlayers()) {
            boolean models;
            try {
                models = seesModels(packOn, p.getResourcePackStatus());
            } catch (RuntimeException | LinkageError e) {
                models = false;
            }
            Applied was = applied.get(p.getUniqueId());
            if (was != null && was.player() == p && was.models() == models && was.generation() == generation) {
                continue;
            }
            for (Shown s : shown.values()) {
                for (UUID id : s.models()) {
                    show(p, id, models);
                }
                for (UUID id : s.plain()) {
                    show(p, id, !models);
                }
            }
            applied.put(p.getUniqueId(), new Applied(p, models, generation));
        }
    }

    private void show(Player p, UUID id, boolean on) {
        Entity e = Bukkit.getEntity(id);
        if (e == null) {
            return;
        }
        if (on) {
            p.showEntity(club.plugin(), e);
        } else {
            p.hideEntity(club.plugin(), e);
        }
    }

    /** A player left: their view is set again when they come back. */
    void forget(UUID id) {
        applied.remove(id);
        tapAt.remove(id);
        tapped.remove(id);
        cheerAt.remove(id);
        inTrophy.remove(id);
        shimmer.remove(id);
    }

    // ---- taps and cheers -----------------------------------------------------------------------------

    /** The game a cabinet's tap target plays, or {@code null} for any other entity. */
    static String cabinetGame(Entity e) {
        if (!(e instanceof Interaction) || Keys.KID_CABINET == null) {
            return null;
        }
        return e.getPersistentDataContainer().get(Keys.KID_CABINET, PersistentDataType.STRING);
    }

    /**
     * A cabinet was tapped (either click): a chime and sparkles, then the game, through {@code /hcm play}'s entry
     * point and gate, in Classic at once ({@link GamesService#openKid}).
     */
    void tapped(Player p, Entity e, long now) {
        String gameId = cabinetGame(e);
        if (gameId == null || p == null) {
            return;
        }
        Long last = tapAt.get(p.getUniqueId());
        if (last != null && now - last < TAP_MS) {
            return; // the same tap's other packet
        }
        tapAt.put(p.getUniqueId(), now);
        Location at = e.getLocation().add(0, 1.2, 0);
        tapped.put(p.getUniqueId(), new Tapped(gameId, at, now));
        p.playSound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.9f, 1.4f);
        p.spawnParticle(Particle.NOTE, at.clone().add(0, 1.0, 0), 6, 0.35, 0.25, 0.35, 1.0);
        p.spawnParticle(Particle.HAPPY_VILLAGER, at, 10, 0.4, 0.6, 0.4, 0.0);
        club.games().openKid(p, gameId);
    }

    /**
     * A cabinet game was won or set a new best: a fanfare and a burst at the cabinet the player tapped for it, if they
     * tapped one and are still near it (a win at the Games screen elsewhere isn't cheered here).
     */
    void cheered(GamesService.Cheer c, long now) {
        Player p = c.player();
        Tapped t = p == null ? null : tapped.get(p.getUniqueId());
        if (t == null || !t.game().equals(c.gameId()) || t.at().getWorld() != p.getWorld()
                || t.at().distance(p.getLocation()) > CHEER_REACH) {
            return;
        }
        Long last = cheerAt.get(p.getUniqueId());
        if (last != null && now - last < 1_500) {
            return; // a win that is also a new best is one cheer
        }
        cheerAt.put(p.getUniqueId(), now);
        World w = t.at().getWorld();
        w.playSound(t.at(), c.best() ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.0f);
        w.spawnParticle(Particle.FIREWORK, t.at().clone().add(0, 1.2, 0), 40, 0.4, 0.5, 0.4, 0.08);
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, t.at(), 30, 0.4, 0.8, 0.4, 0.3);
    }

    /** A cup changed hands: a fanfare and a golden burst at it, for everyone near. */
    private static void trophyBurst(World w, Location at) {
        w.playSound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 40, 0.3, 0.6, 0.3, 0.35);
        w.spawnParticle(Particle.FIREWORK, at.clone().add(0, 0.8, 0), 20, 0.3, 0.3, 0.3, 0.05);
    }

    /** Whoever just walked into the Trophy Room: their own cups shimmer, for them alone, for a few seconds. */
    private void shimmer(ClubhouseRoom room, World w) {
        Set<UUID> now = new HashSet<>();
        for (Player p : w.getPlayers()) {
            if (room.inTrophyRoom(w.getName(), p.getX(), p.getY(), p.getZ())) {
                now.add(p.getUniqueId());
                if (!inTrophy.contains(p.getUniqueId())) {
                    List<Integer> mine = TrophyShelf.mine(cups, p.getUniqueId());
                    if (!mine.isEmpty()) {
                        shimmer.put(p.getUniqueId(), SHIMMER_SECONDS);
                        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f);
                    }
                }
            }
        }
        inTrophy.clear();
        inTrophy.addAll(now);
        for (Map.Entry<UUID, Integer> e : new ArrayList<>(shimmer.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !now.contains(e.getKey()) || e.getValue() <= 0) {
                shimmer.remove(e.getKey());
                continue;
            }
            shimmer.put(e.getKey(), e.getValue() - 1);
            for (int slot : TrophyShelf.mine(cups, e.getKey())) {
                if (slot < pedestals.size()) {
                    Location at = loc(w, pedestals.get(slot).trophy(), 0.6);
                    p.spawnParticle(Particle.END_ROD, at, 8, 0.3, 0.4, 0.3, 0.01);
                    p.spawnParticle(Particle.WAX_ON, at, 6, 0.35, 0.45, 0.35, 0.0);
                }
            }
        }
    }

    /** For the status line: "3 cabinets, 2 door signs, 5 cups". */
    String statusLine() {
        int cab = 0;
        int doors = 0;
        int cupsUp = 0;
        for (String k : shown.keySet()) {
            String kind = k.substring(0, k.indexOf(':')).toLowerCase(Locale.ROOT);
            switch (kind) {
                case "cab" -> cab++;
                case "door" -> doors++;
                default -> cupsUp++;
            }
        }
        return cab + " cabinet" + (cab == 1 ? "" : "s") + ", " + doors + " door sign" + (doors == 1 ? "" : "s") + ", "
                + cupsUp + " cup" + (cupsUp == 1 ? "" : "s") + (packOn() ? " (models in the pack)"
                : " (vanilla stand-ins: no CraftBridge pack)");
    }
}
