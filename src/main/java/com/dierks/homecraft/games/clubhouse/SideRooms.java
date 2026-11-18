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
    /**
     * A holder's head (0.41.1): drawn {@value #HEAD_SCALE} times, and how far over its pedestal's top the display stands.
     * With the {@code NONE} transform a player head's model (half a block cube) sits wholly <i>under</i> the display's
     * point, so 0.41.0's 0.3 left it half inside the block ("the heads are half in the block").
     */
    static final float HEAD_SCALE = 1.0f;

    /** How far over its block's top a head's display stands so the head rests on the top, drawn at {@code scale}. */
    static double headUp(float scale) {
        return 0.5 * scale;
    }

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
            List<ClubhouseRoom.Cabinet> cabs = room.cabinetPlaces(games);
            for (int n = 0; n < cabs.size(); n++) {
                ClubhouseRoom.Cabinet c = cabs.get(n);
                String key = "cab:" + n;
                keep.add(key);
                put(w, key, c.game() + "@" + c.at(), c.at(), () -> cabinet(w, c.game(), c.at()));
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
            boolean photo = club.photoOnPodium(); // 0.42.0: the podium's cups and heads step down for "Photo time!"
            Set<Integer> filled = new HashSet<>();
            for (TrophyShelf.Cup c : nowCups) {
                if (photo && c.slot() < TrophyShelf.PODIUM) {
                    continue;
                }
                filled.add(c.slot());
                ClubhousePlanner.Pedestal p = pedestals.get(c.slot());
                String key = "cup:" + c.slot();
                keep.add(key);
                put(w, key, c + "@" + p, p.trophy(), () -> cup(w, c, p));
            }
            // 0.42.0: an empty record pedestal has a little glass case, "the next trophy goes here"
            for (int slot = TrophyShelf.PODIUM; slot < pedestals.size(); slot++) {
                if (!filled.contains(slot)) {
                    ClubhousePlanner.Pedestal p = pedestals.get(slot);
                    String key = "case:" + slot;
                    keep.add(key);
                    put(w, key, "case@" + p, p.trophy(), () -> glassCase(w, p));
                }
            }
            for (int slot : TrophyShelf.won(cups, nowCups)) {
                ClubhousePlanner.Pedestal p = pedestals.get(slot);
                trophyBurst(w, loc(w, p.trophy(), 0.6));
            }
        } else {
            pedestals = List.of();
        }
        cups = nowCups;
        upstairs(room, w, s, keep);
        for (String key : new ArrayList<>(shown.keySet())) {
            if (!keep.contains(key)) {
                take(shown.remove(key));
            }
        }
        views(w);
        shimmer(room, w);
        arcadeSounds(room, w);
        secretVisitors(room, w);
        parlorGuests(room, w);
        gates(w);
    }

    // ---- 0.43.0: ways in only their own players see ------------------------------------------------------

    /** Entities seen only by players with a permission (the secret room's star, the Game Parlor's door). */
    private final Map<UUID, String> gatedBy = new HashMap<>();
    /** Each player's gated entities shown to them now. */
    private final Map<UUID, Set<UUID>> gateShown = new HashMap<>();

    /** A way in nobody sees but the players with {@code permission} ({@link #gates}). */
    private void gated(Entity e, String permission) {
        WorldEntities.tag(e, club, null);
        e.setVisibleByDefault(false);
        gatedBy.put(e.getUniqueId(), permission);
    }

    /** Show each gated way in to the players allowed it, and hide it from the rest (a permission can change). */
    private void gates(World w) {
        gatedBy.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
        for (Player p : w.getPlayers()) {
            Set<UUID> mine = gateShown.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
            mine.retainAll(gatedBy.keySet());
            for (Map.Entry<UUID, String> g : gatedBy.entrySet()) {
                boolean may = p.hasPermission(g.getValue());
                if (may && mine.add(g.getKey())) {
                    show(p, g.getKey(), true);
                } else if (!may && mine.remove(g.getKey())) {
                    show(p, g.getKey(), false);
                }
            }
        }
    }

    // ---- 0.43.0: upstairs ------------------------------------------------------------------------------

    /** The secret room's star and door, the Game Parlor's doors and machines. */
    private void upstairs(ClubhouseRoom room, World w, ClubhouseSettings s, Set<String> keep) {
        if (s.secretRoom()) {
            ClubhousePlanner.Place3 star = room.secretStar();
            if (star != null) {
                keep.add("star");
                put(w, "star", "star@" + star, star, () -> star(w, star));
            }
            ClubhousePlanner.Place3 out = room.secretExit();
            if (out != null) {
                keep.add("sexit");
                put(w, "sexit", "sexit@" + out, out, () -> door(w, out, "secret:out"));
            }
        }
        if (s.parlor()) {
            ClubhousePlanner.Place3 in = room.parlorDoor();
            if (in != null) {
                keep.add("pdoor");
                put(w, "pdoor", "pdoor@" + in, in, () -> door(w, in, "parlor:in"));
            }
            ClubhousePlanner.Place3 out = room.parlorExit();
            if (out != null) {
                keep.add("pexit");
                put(w, "pexit", "pexit@" + out, out, () -> door(w, out, "parlor:out"));
            }
            List<ClubhouseRoom.Cabinet> machines = room.parlorMachines();
            for (int n = 0; n < machines.size(); n++) {
                ClubhouseRoom.Cabinet m = machines.get(n);
                if (club.games().game(m.game()) == null) {
                    continue; // a game not built on this server
                }
                String key = "mach:" + n;
                keep.add(key);
                put(w, key, m.game() + "@" + m.at(), m.at(), () -> machine(w, m.game(), m.at()));
            }
        }
    }

    /** The secret room's way in: a little gold star on the wall, and its tap target. */
    private Shown star(World w, ClubhousePlanner.Place3 at) {
        List<UUID> all = new ArrayList<>();
        all.add(w.spawn(loc(w, at, 0), ItemDisplay.class, e -> {
            gated(e, SECRET_PERMISSION);
            e.setItemStack(new ItemStack(Material.NETHER_STAR));
            e.setBillboard(Display.Billboard.CENTER);
            e.setTransformation(scale(0.45f));
            e.setBrightness(new Display.Brightness(15, 15));
        }).getUniqueId());
        Entity target = tapTarget(w, loc(w, at, -0.35), 0.7f, 0.7f, "secret:in");
        gated(target, SECRET_PERMISSION);
        all.add(target.getUniqueId());
        return new Shown("star", all, List.of(), List.of());
    }

    /** A door you tap (a dark-oak door against the wall, two high), to {@code tap}. */
    private Shown door(World w, ClubhousePlanner.Place3 at, String tap) {
        List<UUID> all = new ArrayList<>();
        String facing = facing(at.yaw());
        double bx = Math.floor(at.x());
        double bz = Math.floor(at.z());
        for (int half = 0; half < 2; half++) {
            String data = "minecraft:dark_oak_door[facing=" + facing + ",half=" + (half == 0 ? "lower" : "upper")
                    + ",hinge=left,open=false,powered=false]";
            int h = half;
            all.add(w.spawn(new Location(w, bx, at.y() + h, bz), BlockDisplay.class, e -> {
                WorldEntities.tag(e, club, null);
                e.setBlock(Bukkit.createBlockData(data));
                if ("parlor:in".equals(tap)) {
                    gated(e, com.dierks.homecraft.games.PlayGate.PERMISSION_CHANCE);
                }
            }).getUniqueId());
        }
        Entity target = tapTarget(w, new Location(w, bx + 0.5, at.y(), bz + 0.5), 1.0f, 2.0f, tap);
        if ("parlor:in".equals(tap)) {
            gated(target, com.dierks.homecraft.games.PlayGate.PERMISSION_CHANCE);
        }
        all.add(target.getUniqueId());
        return new Shown(tap, all, List.of(), List.of());
    }

    /** A Game Parlor machine: its model or stand-in, its label, its tap target (the games of chance's own gate). */
    private Shown machine(World w, String gameId, ClubhousePlanner.Place3 at) {
        ClubModels.Model m = ClubModels.machineFor(gameId);
        List<UUID> models = new ArrayList<>();
        List<UUID> plain = new ArrayList<>();
        List<UUID> all = new ArrayList<>();
        boolean table = m != null && m.kind() == ClubModels.Kind.TROPHY;
        all.add(tapTarget(w, loc(w, at, table ? -0.1 : 0), table ? 1.0f : 1.0f, table ? 0.8f : 2.0f,
                "machine:" + gameId).getUniqueId());
        if (m != null) {
            models.add(model(w, m, loc(w, at, m.up())).getUniqueId());
            plain.add(w.spawn(loc(w, at, table ? 0.35 : ICON_UP - 0.6), ItemDisplay.class, e -> {
                hidden(e);
                e.setItemStack(new ItemStack(m.fallback()));
                e.setBillboard(Display.Billboard.CENTER);
                e.setTransformation(scale(table ? 0.6f : 1.0f));
                e.setBrightness(new Display.Brightness(15, 15));
            }).getUniqueId());
            if (!table) {
                double bx = Math.floor(at.x());
                double bz = Math.floor(at.z());
                plain.add(block(w, new Location(w, bx, at.y(), bz), Material.DARK_OAK_PLANKS).getUniqueId());
                plain.add(block(w, new Location(w, bx, at.y() + 1, bz), Material.WAXED_CUT_COPPER).getUniqueId());
            }
        }
        Game g = club.games().game(gameId);
        all.add(label(w, loc(w, at, table ? 0.9 : LABEL_UP), "&a" + (g == null ? gameId : g.name()), 0.5f)
                .getUniqueId());
        all.addAll(models);
        all.addAll(plain);
        return new Shown(gameId, all, models, plain);
    }

    /** An Interaction you tap (either click), carrying what the tap does. */
    private Interaction tapTarget(World w, Location at, float width, float height, String tap) {
        return w.spawn(at, Interaction.class, e -> {
            WorldEntities.tag(e, club, null);
            e.setInteractionWidth(width);
            e.setInteractionHeight(height);
            e.setResponsive(true);
            e.getPersistentDataContainer().set(Keys.CLUB_TAP, PersistentDataType.STRING, tap);
        });
    }

    private static String facing(float yaw) {
        float y = WallBoards.snap(yaw);
        return y == 0f ? "south" : y == 90f ? "west" : y == 180f ? "north" : "east";
    }

    /** What an upstairs tap target does, or {@code null} for any other entity. */
    static String tapOf(Entity e) {
        if (!(e instanceof Interaction) || Keys.CLUB_TAP == null) {
            return null;
        }
        return e.getPersistentDataContainer().get(Keys.CLUB_TAP, PersistentDataType.STRING);
    }

    /**
     * An upstairs tap: the star takes a player with {@code hcm.clubhouse.secretroom} into the secret room (a whoosh
     * and sparkles), anyone else gets nothing at all (it stays decoration, so it stays secret); the doors take a
     * player through (the parlor's only with {@code hcm.games.chance}); a machine opens its game of chance through the
     * games' own entry point and gate.
     */
    void tappedUp(Player p, Entity e, long now) {
        String tap = tapOf(e);
        if (tap == null || p == null) {
            return;
        }
        Long last = tapAt.get(p.getUniqueId());
        if (last != null && now - last < TAP_MS) {
            return;
        }
        tapAt.put(p.getUniqueId(), now);
        ClubhouseRoom room = club.room();
        if (room == null) {
            return;
        }
        switch (tap) {
            case "secret:in" -> {
                if (!SecretShelf.mayEnter(p.hasPermission(SECRET_PERMISSION))) {
                    return; // nothing at all: no message, no sound
                }
                whoosh(p);
                club.moveTo(p, room.secretArrival());
                whoosh(p);
            }
            case "secret:out" -> {
                whoosh(p);
                club.moveTo(p, room.secretReturn());
            }
            case "parlor:in" -> {
                if (!p.hasPermission(com.dierks.homecraft.games.PlayGate.PERMISSION_CHANCE)) {
                    return; // the door stays a door
                }
                p.playSound(p.getLocation(), Sound.BLOCK_WOODEN_DOOR_OPEN, 0.8f, 1.0f);
                club.moveTo(p, room.parlorArrival());
            }
            case "parlor:out" -> {
                p.playSound(p.getLocation(), Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.8f, 1.0f);
                club.moveTo(p, room.parlorReturn());
            }
            default -> {
                if (tap.startsWith("machine:")) {
                    if (!p.hasPermission(com.dierks.homecraft.games.PlayGate.PERMISSION_CHANCE)) {
                        return; // never an error wall: the machine just doesn't answer
                    }
                    if (room.inParlor(p.getWorld().getName(), p.getX(), p.getY(), p.getZ())) {
                        club.games().parlorGuest(p.getUniqueId()); // in now, for this tap's gate
                    }
                    club.games().open(p, tap.substring("machine:".length()), null);
                }
            }
        }
    }

    /** The secret room's permission (0.43.0, default false). */
    static final String SECRET_PERMISSION = "hcm.clubhouse.secretroom";

    private static void whoosh(Player p) {
        p.playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.8f, 1.3f);
        p.spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 20, 0.4, 0.7, 0.4, 0.04);
    }

    /** The Clubhouse's visitors standing in the Game Parlor, told to the games (their gate lets chance through there). */
    private void parlorGuests(ClubhouseRoom room, World w) {
        List<UUID> in = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            if (room.inParlor(w.getName(), p.getX(), p.getY(), p.getZ())) {
                in.add(p.getUniqueId());
            }
        }
        club.games().parlorGuests(in);
    }

    // ---- the secret room's own trophies, one set per visitor -------------------------------------------

    /** Each visitor's trophies in the secret room: their entities (seen by them alone). */
    private final Map<UUID, List<UUID>> secretShown = new HashMap<>();
    /** Visitors whose trophies are being read. */
    private final Set<UUID> secretReading = new HashSet<>();

    /** Who is in the secret room: their own trophies drawn as they come in, taken down as they go. */
    private void secretVisitors(ClubhouseRoom room, World w) {
        Set<UUID> in = new HashSet<>();
        for (Player p : w.getPlayers()) {
            if (room.inSecretRoom(w.getName(), p.getX(), p.getY(), p.getZ())) {
                in.add(p.getUniqueId());
                if (!secretShown.containsKey(p.getUniqueId()) && secretReading.add(p.getUniqueId())) {
                    readSecret(p, room);
                }
            }
        }
        for (UUID id : new ArrayList<>(secretShown.keySet())) {
            if (!in.contains(id)) {
                for (UUID e : secretShown.remove(id)) {
                    Entity x = Bukkit.getEntity(e);
                    if (x != null) {
                        x.remove();
                    }
                }
            }
        }
    }

    /** Read the visitor's bests off the main thread, then draw them on it. */
    private void readSecret(Player p, ClubhouseRoom room) {
        UUID id = p.getUniqueId();
        org.bukkit.plugin.Plugin plugin = club.plugin();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<com.dierks.homecraft.storage.GamesDao.Mine> rows;
            String seen;
            try {
                rows = club.games().dao().scoresOf(id);
                seen = room.meta(SEEN_KEY + id);
            } catch (Exception e) {
                rows = List.of();
                seen = null;
            }
            List<com.dierks.homecraft.storage.GamesDao.Mine> got = rows;
            String seenText = seen;
            Bukkit.getScheduler().runTask(plugin, () -> {
                secretReading.remove(id);
                Player q = Bukkit.getPlayer(id);
                if (q != null && club.room() == room && room.inSecretRoom(q.getWorld().getName(), q.getX(), q.getY(),
                        q.getZ())) {
                    drawSecret(q, room, got, seenText);
                }
            });
        });
    }

    /** Where a visitor's seen trophies are kept (their keys), under {@code gen.clubhouse.}. */
    static final String SEEN_KEY = ClubhouseRoom.META_PREFIX + "secret.seen.";

    private void drawSecret(Player p, ClubhouseRoom room, List<com.dierks.homecraft.storage.GamesDao.Mine> rows,
                            String seenText) {
        World w = p.getWorld();
        GamesService games = club.games();
        List<SecretShelf.Trophy> trophies = SecretShelf.trophies(rows, new SecretShelf.Games() {
            @Override
            public GameKind kind(String game) {
                Game g = games.game(game);
                return g == null ? null : g.kind();
            }

            @Override
            public String name(String game) {
                Game g = games.game(game);
                return g == null ? game : g.name();
            }

            @Override
            public int medal(String game, String board, long score) {
                return games.game(game) instanceof com.dierks.homecraft.games.cabinet.CabinetGame c
                        ? c.medalFor(board, score) : 0;
            }
        });
        List<ClubhousePlanner.Place3> shelf = room.secretShelf();
        boolean models;
        try {
            models = seesModels(packOn(), p.getResourcePackStatus());
        } catch (RuntimeException | LinkageError e) {
            models = false;
        }
        List<UUID> mine = new ArrayList<>();
        Set<String> fresh = new HashSet<>();
        for (SecretShelf.Trophy t : SecretShelf.fresh(trophies, SecretShelf.seen(seenText))) {
            fresh.add(t.key());
        }
        for (int n = 0; n < trophies.size() && n < shelf.size(); n++) {
            SecretShelf.Trophy t = trophies.get(n);
            ClubhousePlanner.Place3 at = shelf.get(n);
            ClubModels.Model m = ClubModels.trophy(t.metal());
            float size = t.big() ? 1.25f : 0.85f;
            boolean useModel = models;
            mine.add(w.spawn(loc(w, at, useModel ? m.up() * size : 0.45), ItemDisplay.class, e -> {
                hidden(e);
                if (useModel) {
                    e.setItemStack(modelItem(m));
                    e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    e.setRotation(at.yaw(), 0f);
                } else {
                    e.setItemStack(new ItemStack(m.fallback()));
                    e.setBillboard(Display.Billboard.VERTICAL);
                }
                e.setTransformation(scale(size));
            }).getUniqueId());
            Game g = t.game() == null ? null : games.game(t.game());
            if (g != null) { // the game's Games-screen icon on the plaque: which game it is, with no reading
                ItemStack icon;
                try {
                    icon = new ItemStack(g.tile(p).getType());
                } catch (RuntimeException ex) {
                    icon = null;
                }
                ItemStack i = icon;
                if (i != null) {
                    mine.add(w.spawn(loc(w, at, 1.15 * size + 0.1), ItemDisplay.class, e -> {
                        hidden(e);
                        e.setItemStack(i);
                        e.setBillboard(Display.Billboard.CENTER);
                        e.setTransformation(scale(0.35f));
                    }).getUniqueId());
                }
            }
            mine.add(w.spawn(loc(w, at, -0.15), TextDisplay.class, e -> {
                hidden(e);
                e.setBillboard(Display.Billboard.CENTER);
                e.setTransformation(scale(0.3f));
                e.setDefaultBackground(false);
                e.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                e.setAlignment(TextDisplay.TextAlignment.CENTER);
                e.text(Text.of(t.label()));
            }).getUniqueId());
            if (fresh.contains(t.key())) { // new since last time: a burst, a fanfare, a shimmer, for them
                Location l = loc(w, at, 0.6);
                p.spawnParticle(Particle.TOTEM_OF_UNDYING, l, 25, 0.25, 0.4, 0.25, 0.25);
                p.spawnParticle(Particle.END_ROD, l, 10, 0.3, 0.4, 0.3, 0.01);
            }
        }
        if (!fresh.isEmpty() && !trophies.isEmpty()) {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.1f);
        }
        ClubhousePlanner.Place3 centre = room.secretCentre();
        if (centre != null) { // the room is theirs: their head on the middle pedestal, their name over it
            mine.add(w.spawn(loc(w, centre, headUp(1.4f)), ItemDisplay.class, e -> {
                hidden(e);
                e.setItemStack(head(p.getUniqueId()));
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setBillboard(Display.Billboard.VERTICAL);
                e.setTransformation(scale(1.4f));
            }).getUniqueId());
            mine.add(w.spawn(loc(w, centre, 1.0), TextDisplay.class, e -> {
                hidden(e);
                e.setBillboard(Display.Billboard.CENTER);
                e.setTransformation(scale(0.8f));
                e.setDefaultBackground(false);
                e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                e.setShadowed(true);
                e.setAlignment(TextDisplay.TextAlignment.CENTER);
                e.text(Text.of("&6\u2605 &e" + p.getName() + " &6\u2605"));
            }).getUniqueId());
        }
        for (UUID e : mine) {
            show(p, e, true); // theirs alone
        }
        secretShown.put(p.getUniqueId(), mine);
        String seenNow = SecretShelf.seenText(trophies);
        Bukkit.getScheduler().runTaskAsynchronously(club.plugin(), () -> {
            try {
                room.meta(SEEN_KEY + p.getUniqueId(), seenNow);
            } catch (Exception ignored) {
                // a new trophy just bursts again next time
            }
        });
    }

    /** 0.42.0: now and then a soft arcade chime for whoever is in the Arcade Room (every few seconds, a random note). */
    private void arcadeSounds(ClubhouseRoom room, World w) {
        if (!club.settings().arcadeRoom() || ++arcadeBeat % ARCADE_BEAT != 0) {
            return;
        }
        Sound[] notes = {Sound.BLOCK_NOTE_BLOCK_CHIME, Sound.BLOCK_NOTE_BLOCK_BELL, Sound.BLOCK_NOTE_BLOCK_BIT,
                Sound.BLOCK_NOTE_BLOCK_XYLOPHONE};
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (Player p : w.getPlayers()) {
            if (room.inArcadeRoom(w.getName(), p.getX(), p.getY(), p.getZ())) {
                p.playSound(p.getLocation(), notes[r.nextInt(notes.length)], 0.25f, 0.6f + r.nextFloat() * 1.2f);
            }
        }
    }

    /** Every this many seconds, an arcade chime. */
    static final int ARCADE_BEAT = 5;
    private int arcadeBeat;

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
        Shown made = make.get();
        // 0.41.1: kept under the signature it was asked for, so the next second finds it as it is. 0.41.0 kept the
        // maker's own label (a game id, a model name), which never matched: every slot was made again every second,
        // and the doors, cups and cabinets flashed
        shown.put(key, new Shown(sig, made.all(), made.models(), made.plain()));
        generation++;
    }

    /** Whether slot {@code sig} would be kept as it is (for the tests): its signature is the one asked for. */
    static boolean keeps(String asked, String stored) {
        return asked != null && asked.equals(stored);
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
            all.add(w.spawn(loc(w, p.head(), headUp(HEAD_SCALE)), ItemDisplay.class, e -> {
                WorldEntities.tag(e, club, null);
                e.setItemStack(head(c.id()));
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setBillboard(Display.Billboard.VERTICAL); // the face always to whoever looks
                e.setTransformation(scale(HEAD_SCALE));
                e.setBrightness(new Display.Brightness(15, 15));
            }).getUniqueId());
        }
        all.add(label(w, loc(w, p.trophy(), CUP_LABEL_UP), c.label(), 0.45f).getUniqueId());
        return new Shown(c.toString(), all, models, plain);
    }

    /** An empty pedestal's little glass case (0.42.0), on its trophy block. */
    private Shown glassCase(World w, ClubhousePlanner.Pedestal p) {
        BlockDisplay b = w.spawn(new Location(w, p.trophy().x() - 0.3, p.trophy().y(), p.trophy().z() - 0.3),
                BlockDisplay.class, e -> {
                    WorldEntities.tag(e, club, null);
                    e.setBlock(Material.GLASS.createBlockData());
                    e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.6f, 0.7f,
                            0.6f), new Quaternionf()));
                });
        return new Shown("case", List.of(b.getUniqueId()), List.of(), List.of());
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
        for (List<UUID> ids : secretShown.values()) {
            for (UUID e : ids) {
                Entity x = Bukkit.getEntity(e);
                if (x != null) {
                    x.remove();
                }
            }
        }
        secretShown.clear();
        gatedBy.clear();
        gateShown.clear();
        if (club.games() != null) {
            club.games().parlorGuests(List.of());
        }
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
        gateShown.remove(id);
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
            int colon = k.indexOf(':');
            String kind = (colon < 0 ? k : k.substring(0, colon)).toLowerCase(Locale.ROOT);
            switch (kind) {
                case "cab", "mach" -> cab++;
                case "door", "star", "sexit", "pdoor", "pexit" -> doors++;
                default -> cupsUp++;
            }
        }
        return cab + " cabinet" + (cab == 1 ? "" : "s") + ", " + doors + " door sign" + (doors == 1 ? "" : "s") + ", "
                + cupsUp + " cup" + (cupsUp == 1 ? "" : "s") + (packOn() ? " (models in the pack)"
                : " (vanilla stand-ins: no CraftBridge pack)");
    }
}
