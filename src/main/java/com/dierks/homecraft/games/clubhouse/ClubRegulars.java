package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.chance.mobraces.MobRaces;
import com.dierks.homecraft.games.chance.twentyone.TwentyOne;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneTable;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * The Clubhouse's NPC regulars (the owner: "Yes go ahead with the CPU players and regulars"): up to
 * {@code games.cpu.regulars} NPC players in the room as mannequins while at least one real player is visiting, none
 * when nobody is, plus whoever of them sits at the Twenty-One table (they are drawn in its chairs).
 *
 * <p><b>Furniture-like, guarded like Mob Races' racers.</b> Each is a game entity ({@link WorldEntities#tag}): never
 * saved with its chunk, swept when the Clubhouse starts and whenever a chunk loads with one in it, and nobody can hit,
 * push, ride, leash, dress or take one (the games' guard cancels every touch; it is invulnerable, immovable and not
 * collidable). A seated one rides an invisible marker on its seat, tagged the same way.
 *
 * <p><b>What they do.</b> They step (a teleport, never a walk through the build) between places the plan already has
 * ({@link RegularSpots}) every minute or two, and do small things there: a drink at the bar (the bar's drink sound and
 * a sparkle, nothing on anyone), a quiet spin at a slot machine, watching the race and cheering when it is won,
 * playing at the Twenty-One table, a look about, and a wave (a swing of the arm) to a real player who comes near.
 *
 * <p><b>Who sees them.</b> Everyone is hidden one by default and shown it: never a player who has NPCs off, and a
 * regular in the Game Parlor only to a player who may go in there (the lever's own permissions). A Bedrock player may
 * not be shown mannequins at all: nothing here depends on it.
 *
 * <p>The regulars' trouble is never the Clubhouse's: anything that goes wrong takes them down with one WARN and they
 * are tried again a minute later.
 */
final class ClubRegulars {

    /** How near a real player comes for a wave (blocks), and how long before the same regular waves at them again. */
    static final double WAVE_REACH = 6;
    static final long WAVE_AGAIN_MS = 5 * 60_000L;
    /** How long a roamer stays in one place. */
    static final long STAY_MS = 60_000;
    /** How long they rest after trouble. */
    static final long TROUBLE_REST_MS = 60_000;
    /** A seated regular's marker stands this far under the seat's top (the rider sits on it). */
    static final double SIT_DROP = 0.0;

    /** One regular in the room. */
    private static final class Body {
        final CpuPlayer npc;
        UUID mannequin;
        UUID seat;
        RegularSpots.Spot at;
        long moveAt;
        long actAt;
        int looks = -1;
        boolean drinking;
        /** This server would not seat it: it stands on its seat instead (and isn't asked to sit again). */
        boolean standsOnly;

        Body(CpuPlayer npc) {
            this.npc = npc;
        }
    }

    private final Clubhouse club;
    private final Map<String, Body> bodies = new LinkedHashMap<>();
    /** Each player's regulars shown to them now. */
    private final Map<UUID, Set<UUID>> shown = new HashMap<>();
    private final Map<String, Long> waved = new HashMap<>();
    private final SplittableRandom rng = new SplittableRandom();
    private ClubhouseSite siteOf;
    private ClubhousePlanner.Rooms roomsOf;
    private List<RegularSpots.Spot> spots = List.of();
    private MobRaces.Phase racePhase = MobRaces.Phase.IDLE;
    private long restUntil;
    private String trouble;
    private String why = "not drawn yet";

    ClubRegulars(Clubhouse club) {
        this.club = club;
    }

    /** Once a second while the room is open, with the real players visiting it now. */
    void second(ClubhouseRoom room, long now, Collection<Player> visitors) {
        if (now < restUntil) {
            return;
        }
        try {
            look(room, now, visitors);
        } catch (RuntimeException | LinkageError e) {
            restUntil = now + TROUBLE_REST_MS;
            String what = String.valueOf(e);
            why = "trouble (" + what + "); tried again in a minute";
            if (!what.equals(trouble)) {
                trouble = what;
                club.log().log(java.util.logging.Level.WARNING, "Clubhouse: the NPC regulars could not be drawn; the"
                        + " room stays open and they are tried again every minute", e);
            }
            try {
                remove();
            } catch (RuntimeException | LinkageError ignored) {
                // a game entity left behind is swept at the next start or chunk load
            }
        }
    }

    private void look(ClubhouseRoom room, long now, Collection<Player> visitors) {
        CpuPlayers npcs = CpuPlayers.of(club.games());
        World w = room == null ? null : Bukkit.getWorld(room.world());
        if (npcs == null || w == null || room.hand() || room.site() == null || visitors.isEmpty()) {
            why = npcs == null ? "NPC players are off" : room != null && room.hand() ? "an owner-built room has none"
                    : "nobody is visiting";
            remove();
            return;
        }
        ClubhousePlanner.Rooms rooms = room.builtRooms();
        if (room.site() != siteOf || !rooms.equals(roomsOf)) {
            remove();
            siteOf = room.site();
            roomsOf = rooms;
            spots = RegularSpots.of(siteOf, rooms);
        }
        why = null;
        // who is where: the table's NPCs in its chairs, then the roamers
        Map<String, RegularSpots.Spot> placed = new LinkedHashMap<>();
        List<RegularSpots.Spot> chairs = of(RegularSpots.Kind.TABLE);
        TwentyOneTable table = table();
        if (table != null && !chairs.isEmpty()) {
            List<TwentyOneTable.Seat> seats = table.seats();
            for (int i = 0; i < seats.size() && i < chairs.size(); i++) {
                CpuPlayer c = seats.get(i).npc();
                if (c != null) {
                    placed.put(c.key(), chairs.get(i));
                }
            }
        }
        int roamers = Math.max(0, npcs.settings().regulars() - placed.size());
        int start = (int) Math.floorMod(now / (20 * 60_000L), (long) CpuRoster.ALL.size());
        for (int n = 0; n < CpuRoster.ALL.size() && roamers > 0; n++) {
            CpuPlayer c = CpuRoster.ALL.get((start + n) % CpuRoster.ALL.size());
            if (!placed.containsKey(c.key())) {
                placed.put(c.key(), null); // a roamer: its place is its own to pick
                roamers--;
            }
        }
        for (String key : new ArrayList<>(bodies.keySet())) {
            if (!placed.containsKey(key)) {
                drop(bodies.remove(key));
            }
        }
        // taken: the table's chairs in use, and where each roamer stands now
        Set<RegularSpots.Spot> taken = new HashSet<>();
        for (Map.Entry<String, RegularSpots.Spot> e : placed.entrySet()) {
            Body b = bodies.get(e.getKey());
            if (e.getValue() != null) {
                taken.add(e.getValue());
            } else if (b != null && b.at != null && b.at.kind() != RegularSpots.Kind.TABLE) {
                taken.add(b.at);
            }
        }
        for (Map.Entry<String, RegularSpots.Spot> e : placed.entrySet()) {
            CpuPlayer c = CpuRoster.byKey(e.getKey());
            Body b = bodies.computeIfAbsent(e.getKey(), k -> new Body(c));
            RegularSpots.Spot want = e.getValue();
            if (want == null) {
                boolean atTable = b.at != null && b.at.kind() == RegularSpots.Kind.TABLE;
                if (b.at == null || atTable || now >= b.moveAt) {
                    RegularSpots.Spot next = pickSpot(taken, b.at);
                    if (next != null) {
                        taken.remove(b.at);
                        taken.add(next);
                        want = next;
                        b.moveAt = now + STAY_MS + rng.nextInt(90_000);
                    } else {
                        want = b.at;
                    }
                } else {
                    want = b.at;
                }
            }
            if (want == null) {
                continue;
            }
            place(w, npcs, b, want);
            dress(npcs, b);
        }
        views(w, npcs);
        act(w, npcs, now, visitors);
    }

    private List<RegularSpots.Spot> of(RegularSpots.Kind k) {
        List<RegularSpots.Spot> out = new ArrayList<>();
        for (RegularSpots.Spot s : spots) {
            if (s.kind() == k) {
                out.add(s);
            }
        }
        return out;
    }

    /** A free place for a roamer, of a kind picked at random (never the table's chairs, never where it is). */
    private RegularSpots.Spot pickSpot(Set<RegularSpots.Spot> taken, RegularSpots.Spot was) {
        List<RegularSpots.Spot> free = new ArrayList<>();
        for (RegularSpots.Spot s : spots) {
            if (s.kind() != RegularSpots.Kind.TABLE && !taken.contains(s) && !s.equals(was)) {
                free.add(s);
            }
        }
        if (free.isEmpty()) {
            return null;
        }
        // a kind first, so the many hall chairs don't take every turn
        List<RegularSpots.Kind> kinds = new ArrayList<>();
        for (RegularSpots.Spot s : free) {
            if (!kinds.contains(s.kind())) {
                kinds.add(s.kind());
            }
        }
        RegularSpots.Kind k = kinds.get(rng.nextInt(kinds.size()));
        List<RegularSpots.Spot> ofKind = new ArrayList<>();
        for (RegularSpots.Spot s : free) {
            if (s.kind() == k) {
                ofKind.add(s);
            }
        }
        return ofKind.get(rng.nextInt(ofKind.size()));
    }

    private TwentyOneTable table() {
        Game g = club.games().game(TwentyOne.SPEC.id());
        return g instanceof TwentyOne t && club.games().enabled(t) ? t.table() : null;
    }

    // ---- the mannequins -----------------------------------------------------------------------------

    /** Put the body's mannequin at {@code s} (made there, or stepped there), seated on a seat. */
    private void place(World w, CpuPlayers npcs, Body b, RegularSpots.Spot s) {
        Entity e = b.mannequin == null ? null : Bukkit.getEntity(b.mannequin);
        if (e != null && e.isValid() && s.equals(b.at) && (!s.sit() || seated(b) || b.standsOnly)) {
            return;
        }
        if (!w.isChunkLoaded((int) Math.floor(s.x()) >> 4, (int) Math.floor(s.z()) >> 4)) {
            return; // nobody near: nothing is made
        }
        unseat(b);
        Location at = new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0f);
        if (e == null || !e.isValid()) {
            Mannequin m = w.spawn(at, Mannequin.class, made -> {
                WorldEntities.tag(made, club, null);
                made.setVisibleByDefault(false); // shown to each player it is for ({@link #views})
                made.setInvulnerable(true);
                made.setImmovable(true);
                made.setGravity(false);
                made.setSilent(true);
                made.setCollidable(false);
                made.setCanPickupItems(false);
                made.setRemoveWhenFarAway(false);
                made.setCustomNameVisible(true);
            });
            b.mannequin = m.getUniqueId();
            b.looks = -1;
            shownForget(b.mannequin);
            e = m;
        } else {
            e.teleport(at);
        }
        e.setRotation(s.yaw(), 0f);
        b.at = s;
        b.drinking = false;
        if (e instanceof Mannequin m) {
            holdNothing(m);
        }
        if (s.sit()) {
            Entity mannequin = e;
            ArmorStand seat = w.spawn(new Location(w, s.x(), s.y() - SIT_DROP, s.z(), s.yaw(), 0f), ArmorStand.class,
                    st -> {
                        WorldEntities.tag(st, club, null);
                        st.setMarker(true);
                        st.setInvisible(true);
                        st.setSmall(true);
                        st.setGravity(false);
                        st.setInvulnerable(true);
                        st.setSilent(true);
                    });
            b.seat = seat.getUniqueId();
            if (!seat.addPassenger(mannequin)) {
                seat.remove(); // it stands on the seat instead: never through it
                b.standsOnly = true;
                b.seat = null;
            }
        }
    }

    private boolean seated(Body b) {
        Entity st = b.seat == null ? null : Bukkit.getEntity(b.seat);
        Entity m = b.mannequin == null ? null : Bukkit.getEntity(b.mannequin);
        return st != null && st.isValid() && m != null && st.getPassengers().contains(m);
    }

    private void unseat(Body b) {
        Entity st = b.seat == null ? null : Bukkit.getEntity(b.seat);
        if (st != null) {
            st.eject();
            st.remove();
        }
        b.seat = null;
    }

    /** Its name (with the gear), the gear, NPC and its stars under it, and its skin, whenever the owner changed them. */
    private void dress(CpuPlayers npcs, Body b) {
        if (b.looks == npcs.looksVersion() || b.mannequin == null
                || !(Bukkit.getEntity(b.mannequin) instanceof Mannequin m)) {
            return;
        }
        m.customName(Text.of("&f" + npcs.name(b.npc) + " &7" + CpuRoster.MARKER));
        m.setDescription(Text.of("&7" + CpuRoster.MARKER + " " + CpuRoster.WORD + " &e" + b.npc.level().starText()));
        m.setProfile(npcs.profile(b.npc));
        b.looks = npcs.looksVersion();
    }

    private static void holdNothing(Mannequin m) {
        m.getEquipment().setItemInMainHand(null);
    }

    /** Each player in the world sees the regulars meant for them: none with NPCs off; the Parlor's only if allowed. */
    private void views(World w, CpuPlayers npcs) {
        for (Player p : w.getPlayers()) {
            Set<UUID> mine = shown.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
            boolean wants = npcs.wants(p.getUniqueId());
            boolean parlor = SideRooms.allOf(SideRooms.PARLOR_WAY_IN, p::hasPermission);
            for (Body b : bodies.values()) {
                Entity e = b.mannequin == null ? null : Bukkit.getEntity(b.mannequin);
                if (e == null || b.at == null) {
                    continue;
                }
                boolean may = mayShow(wants, b.at.parlor(), parlor);
                if (may && mine.add(b.mannequin)) {
                    p.showEntity(club.plugin(), e);
                } else if (!may && mine.remove(b.mannequin)) {
                    p.hideEntity(club.plugin(), e);
                }
            }
        }
    }

    /** Whether a regular is shown to a player (pure): they have NPCs on, and a Parlor regular only if they may go in. */
    static boolean mayShow(boolean wantsNpcs, boolean inParlor, boolean mayEnterParlor) {
        return wantsNpcs && (!inParlor || mayEnterParlor);
    }

    private void shownForget(UUID mannequin) {
        for (Set<UUID> s : shown.values()) {
            s.remove(mannequin);
        }
    }

    // ---- what they do ---------------------------------------------------------------------------------

    private void act(World w, CpuPlayers npcs, long now, Collection<Player> visitors) {
        MobRaces races = club.games().game(MobRaces.SPEC.id()) instanceof MobRaces m && club.games().enabled(m)
                ? m : null;
        MobRaces.Phase phase = races == null ? MobRaces.Phase.IDLE : races.phase();
        boolean cheer = phase == MobRaces.Phase.RESULT && racePhase != MobRaces.Phase.RESULT;
        racePhase = phase;
        TwentyOneTable table = table();
        TwentyOneTable.Seat turn = table == null ? null : table.turn();
        for (Body b : bodies.values()) {
            if (!(b.mannequin != null && Bukkit.getEntity(b.mannequin) instanceof Mannequin m) || b.at == null) {
                continue;
            }
            Location at = m.getLocation();
            if (b.drinking && now >= b.actAt - 6_000) {
                holdNothing(m); // the glass goes down a moment after the drink
                b.drinking = false;
            }
            if (cheer && b.at.kind() == RegularSpots.Kind.TRACK) {
                m.swingMainHand();
                m.swingOffHand();
                w.spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 2.0, 0), 6, 0.3, 0.2, 0.3, 0.0);
                w.playSound(at, Sound.ENTITY_VILLAGER_CELEBRATE, 0.4f, 1.3f);
                continue;
            }
            if (turn != null && turn.npc() == b.npc && b.at.kind() == RegularSpots.Kind.TABLE && rng.nextInt(2) == 0) {
                m.swingMainHand(); // it plays its move at the table
                w.playSound(at, Sound.ITEM_BOOK_PAGE_TURN, 0.3f, 1.4f);
            }
            if (wave(m, b, now, visitors)) {
                continue;
            }
            if (now < b.actAt) {
                continue;
            }
            b.actAt = now + 8_000 + rng.nextInt(12_000);
            switch (b.at.kind()) {
                case BAR -> {
                    m.getEquipment().setItemInMainHand(new ItemStack(Material.POTION));
                    m.swingMainHand();
                    w.playSound(at, Sound.ENTITY_GENERIC_DRINK, 0.5f, 1.1f);
                    w.spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 1.6, 0), 4, 0.2, 0.2, 0.2, 0.0);
                    b.drinking = true;
                }
                case SLOTS -> {
                    m.swingMainHand();
                    w.playSound(at, Sound.BLOCK_NOTE_BLOCK_HAT, 0.25f, 1.8f); // a quiet spin
                }
                case TABLE -> w.playSound(at, Sound.ITEM_BOOK_PAGE_TURN, 0.25f, 1.2f);
                default -> m.setRotation(b.at.yaw() + (rng.nextInt(61) - 30), 0f); // a look about
            }
        }
    }

    /** A wave (a turn and a swing of the arm) to a real player who just came near, now and then. */
    private boolean wave(Mannequin m, Body b, long now, Collection<Player> visitors) {
        for (Player p : visitors) {
            if (!p.getWorld().equals(m.getWorld()) || p.getLocation().distanceSquared(m.getLocation())
                    > WAVE_REACH * WAVE_REACH) {
                continue;
            }
            String key = b.npc.key() + ":" + p.getUniqueId();
            Long last = waved.get(key);
            if (last != null && now - last < WAVE_AGAIN_MS) {
                continue;
            }
            waved.put(key, now);
            if (rng.nextDouble() > 0.4 + b.npc.personality().friendly) {
                continue;
            }
            Location to = p.getLocation().toVector().subtract(m.getLocation().toVector()).toLocation(m.getWorld());
            float yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
            m.setRotation(yaw, 0f);
            m.swingMainHand();
            return true;
        }
        return false;
    }

    // ---- coming down ----------------------------------------------------------------------------------

    private void drop(Body b) {
        if (b == null) {
            return;
        }
        unseat(b);
        Entity e = b.mannequin == null ? null : Bukkit.getEntity(b.mannequin);
        if (e != null) {
            e.remove();
        }
        if (b.mannequin != null) {
            shownForget(b.mannequin);
        }
    }

    /** Every regular comes down (nobody visiting, the room closed or moved, the Clubhouse stopped). */
    void remove() {
        for (Body b : bodies.values()) {
            drop(b);
        }
        bodies.clear();
        shown.clear();
        siteOf = null;
        roomsOf = null;
        spots = List.of();
    }

    /** A player left: their view is set again when they come back. */
    void forget(UUID player) {
        shown.remove(player);
        waved.keySet().removeIf(k -> k.endsWith(":" + player));
    }

    /** For the admins' status. */
    String statusLine() {
        return "NPC regulars: " + (why == null ? bodies.size() + " in the room, " + spots.size() + " places" : why);
    }
}
