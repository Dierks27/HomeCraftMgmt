package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.chance.mobraces.MobRaces;
import com.dierks.homecraft.games.chance.twentyone.TwentyOne;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneTable;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
import java.util.Iterator;
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
 * <p><b>What they do.</b> They walk (the owner: "I don't know if there is a way for them to walk in instead of just
 * disappearing") between places the plan already has ({@link RegularSpots}) every minute or two, and do small things
 * there: a drink at the bar (the bar's drink sound and a sparkle, nothing on anyone), a quiet spin at a slot machine,
 * watching the race and cheering when it is won, playing at the Twenty-One table, a look about, and a wave (a swing of
 * the arm) to a real player who comes near.
 *
 * <p><b>How they walk.</b> On the plan's own walkable grid ({@link WalkGrid}, {@link RegularWays}): a path round the
 * furniture, the bar and Mob Races' table, at a person's pace, a teleport a small step a tick (the client draws it as a
 * walk, as it does for any mob it sees moving) facing the way they go ({@link RegularWalk}). A seated one gets up
 * first and sits down when it gets there. One that comes in walks in at the Foyer's arrival spot (upstairs, at the
 * Parlor's), one at a time, and one that goes walks out to it and is taken down there; one going up to the Game Parlor
 * walks into the nook by its secret book and comes out upstairs. It never steps into a block a real player stands in:
 * it waits, then goes round, and after a while steps to its place. Where there is no walk (no path, nobody near enough
 * to see, a chunk gone, any trouble) it steps there as it always has.
 *
 * <p><b>Who sees them.</b> Everyone is hidden one by default and shown it: never a player who has NPCs off, and a
 * regular in the Game Parlor only to a player who may go in there (the lever's own permissions). A Bedrock player may
 * not be shown mannequins at all: nothing here depends on it.
 *
 * <p>The regulars' trouble is never the Clubhouse's: anything that goes wrong takes them down with one WARN and they
 * are tried again a minute later; trouble with a walk has them step to their places for a minute instead.
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
    /** How long between two regulars walking in at the same door. */
    static final long DOOR_GAP_MS = 2_500;
    /** How far from the door a visitor still sees a regular walk (blocks); further, it is just put there. */
    static final double SEE_REACH = 96;
    /** Waiting for a player in its way: ticks before it looks for a way round, and before it steps there instead. */
    static final int ROUND_TICKS = 20;
    static final int GIVE_UP_TICKS = 200;

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
        /** Its walk now, and where it ends ({@code to}; also while it waits its turn at the door). */
        RegularWalk walk;
        RegularSpots.Spot to;
        /** It is walking out, to be taken down at the door. */
        boolean leaving;
        /** On the Game Parlor's floor now (drawn only for players who may go in). */
        boolean upstairs;
        /** Ticks it has waited for a player in its way. */
        int paused;

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
    /** The ways about the room ({@code null}: none, they step). */
    private RegularWays ways;
    private String worldName;
    /** When each door is free for the next regular to walk in at (by the door cell's key). */
    private final Map<Long, Long> doorFree = new HashMap<>();
    /** No walking until then, after trouble with a walk: they step. */
    private long walkRestUntil;
    private String walkTrouble;
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
            remove(); // nobody here to see them go: they are taken down where they are
            return;
        }
        ClubhousePlanner.Rooms rooms = room.builtRooms();
        if (room.site() != siteOf || !rooms.equals(roomsOf)) {
            remove();
            siteOf = room.site();
            roomsOf = rooms;
            spots = RegularSpots.of(siteOf, rooms);
            ways = waysOf(siteOf, rooms);
        }
        why = null;
        worldName = w.getName();
        boolean seen = now >= walkRestUntil && seen(w, visitors);
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
        java.util.Set<UUID> away = npcs.busy(); // stage 2: one racing, golfing or playing elsewhere isn't here too
        for (int n = 0; n < CpuRoster.ALL.size() && roamers > 0; n++) {
            CpuPlayer c = CpuRoster.ALL.get((start + n) % CpuRoster.ALL.size());
            if (!placed.containsKey(c.key()) && !away.contains(c.id())) {
                placed.put(c.key(), null); // a roamer: its place is its own to pick
                roamers--;
            }
        }
        for (String key : new ArrayList<>(bodies.keySet())) {
            Body b = bodies.get(key);
            if (!placed.containsKey(key) && !b.leaving && !(seen && leave(w, b))) {
                drop(bodies.remove(key)); // no walk out: taken down where it is
            }
        }
        // taken: the table's chairs in use, and where each roamer is (or is going) now
        Set<RegularSpots.Spot> taken = new HashSet<>();
        for (Map.Entry<String, RegularSpots.Spot> e : placed.entrySet()) {
            Body b = bodies.get(e.getKey());
            RegularSpots.Spot g = b == null || b.leaving ? null : goal(b);
            if (e.getValue() != null) {
                taken.add(e.getValue());
            } else if (g != null && g.kind() != RegularSpots.Kind.TABLE) {
                taken.add(g);
            }
        }
        for (Map.Entry<String, RegularSpots.Spot> e : placed.entrySet()) {
            CpuPlayer c = CpuRoster.byKey(e.getKey());
            Body b = bodies.computeIfAbsent(e.getKey(), k -> new Body(c));
            if (b.leaving) { // wanted again on its way out: it turns round where it is
                b.leaving = false;
                b.walk = null;
                b.to = null;
            }
            RegularSpots.Spot was = goal(b);
            RegularSpots.Spot want = e.getValue();
            if (want == null) {
                boolean atTable = was != null && was.kind() == RegularSpots.Kind.TABLE;
                if (was == null || atTable || now >= b.moveAt) {
                    RegularSpots.Spot next = pickSpot(taken, was);
                    if (next != null) {
                        taken.remove(was);
                        taken.add(next);
                        want = next;
                        b.moveAt = now + STAY_MS + rng.nextInt(90_000);
                    } else {
                        want = was;
                    }
                } else {
                    want = was;
                }
            }
            if (want == null) {
                continue;
            }
            if (b.walk != null && want.equals(b.to)) {
                dress(npcs, b); // on its way there
                continue;
            }
            b.walk = null; // somewhere else now: from where it is
            if (!walkTo(w, b, want, now, seen)) {
                b.to = null;
                place(w, npcs, b, want);
            }
            dress(npcs, b);
        }
        views(w, npcs);
        act(w, npcs, now, visitors);
    }

    /** Where a regular is, or is going (walking there, or waiting its turn at the door to). */
    private static RegularSpots.Spot goal(Body b) {
        return b.at != null ? b.at : b.to;
    }

    private static RegularWays waysOf(ClubhouseSite site, ClubhousePlanner.Rooms rooms) {
        try {
            return RegularWays.of(site, rooms);
        } catch (RuntimeException e) {
            return null; // no walks: they step, as they always have
        }
    }

    /** Whether a visitor is near enough the Clubhouse's door to see a regular walk. */
    private boolean seen(World w, Collection<Player> visitors) {
        if (ways == null || ways.door() == null) {
            return false;
        }
        WalkGrid.Cell d = ways.door();
        Location door = new Location(w, d.cx(), d.h(), d.cz());
        for (Player p : visitors) {
            if (p.getWorld().equals(w) && p.getLocation().distanceSquared(door) <= SEE_REACH * SEE_REACH) {
                return true;
            }
        }
        return false;
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
        if (!loaded(w, s.x(), s.z())) {
            return; // nobody near: nothing is made
        }
        unseat(b);
        Location at = new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0f);
        if (e == null || !e.isValid()) {
            e = make(w, b, at);
        } else {
            e.teleport(at);
        }
        e.setRotation(s.yaw(), 0f);
        b.at = s;
        b.to = null;
        b.walk = null;
        b.leaving = false;
        b.upstairs = s.parlor();
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

    /** A new mannequin for {@code b} at {@code at}: furniture-like, hidden but from those it is shown to. */
    private Mannequin make(World w, Body b, Location at) {
        if (b.mannequin != null) {
            Entity old = Bukkit.getEntity(b.mannequin);
            if (old != null) {
                old.remove();
            }
            shownForget(b.mannequin);
        }
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
        return m;
    }

    private static Entity live(Body b) {
        Entity e = b.mannequin == null ? null : Bukkit.getEntity(b.mannequin);
        return e != null && e.isValid() ? e : null;
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
                if (e == null) {
                    continue;
                }
                boolean may = mayShow(wants, b.upstairs, parlor);
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

    // ---- walking --------------------------------------------------------------------------------------

    /**
     * Start {@code b} walking to {@code want}: in at the door when it isn't in the room yet (one at a time at each
     * door: until its turn it waits, not made yet), else from where it is (getting up first).
     *
     * @return whether it walks (or waits its turn at the door); {@code false}: no walk, it steps there
     */
    private boolean walkTo(World w, Body b, RegularSpots.Spot want, long now, boolean seen) {
        if (ways == null || !seen) {
            return false;
        }
        Entity e = live(b);
        if (e != null && want.equals(b.at)) {
            return false; // there already ({@link #place} keeps it seated)
        }
        WalkGrid.Point to = RegularWays.point(want);
        List<RegularWalk.Leg> legs;
        float yaw;
        if (e == null) {
            legs = ways.walkIn(to);
            WalkGrid.Cell door = ways.startFor(to);
            if (legs == null || door == null) {
                return false;
            }
            WalkGrid.Point start = legs.getFirst().points().getFirst();
            if (!loaded(w, start.x(), start.z()) || !loaded(w, want.x(), want.z())) {
                return false;
            }
            if (now < doorFree.getOrDefault(door.key(), 0L)) {
                b.to = want; // its turn at the door comes
                b.at = null;
                return true;
            }
            doorFree.put(door.key(), now + DOOR_GAP_MS);
            yaw = 0f;
            e = make(w, b, new Location(w, start.x(), start.y(), start.z(), yaw, 0f));
        } else {
            Location here = e.getLocation();
            WalkGrid.Point from = b.at != null ? RegularWays.point(b.at)
                    : new WalkGrid.Point(here.getX(), here.getY(), here.getZ());
            legs = ways.route(from, to, playerCells(w));
            if (legs == null || !loaded(w, want.x(), want.z())) {
                return false;
            }
            unseat(b); // it gets up first
            yaw = here.getYaw();
        }
        b.walk = new RegularWalk(legs, yaw);
        b.to = want;
        b.at = null;
        b.paused = 0;
        b.upstairs = b.walk.upstairs();
        b.drinking = false;
        if (e instanceof Mannequin m) {
            holdNothing(m);
        }
        e.teleport(new Location(w, b.walk.x(), b.walk.y(), b.walk.z(), b.walk.yaw(), 0f));
        return true;
    }

    /** Start {@code b} walking out to the door, where it is taken down; {@code false}: no walk (taken down now). */
    private boolean leave(World w, Body b) {
        Entity e = live(b);
        if (ways == null || e == null) {
            return false;
        }
        Location here = e.getLocation();
        WalkGrid.Point from = b.at != null ? RegularWays.point(b.at)
                : new WalkGrid.Point(here.getX(), here.getY(), here.getZ());
        List<RegularWalk.Leg> legs = ways.walkOut(from, playerCells(w));
        if (legs == null) {
            return false;
        }
        unseat(b);
        b.walk = new RegularWalk(legs, here.getYaw());
        b.leaving = true;
        b.to = null;
        b.at = null;
        b.paused = 0;
        b.drinking = false;
        if (e instanceof Mannequin m) {
            holdNothing(m);
        }
        return true;
    }

    /** Every tick (the Clubhouse's own): each walking regular a step on. */
    void tick() {
        boolean any = false;
        for (Body b : bodies.values()) {
            any |= b.walk != null;
        }
        if (!any) {
            return;
        }
        try {
            walk();
        } catch (RuntimeException | LinkageError e) {
            walkRestUntil = System.currentTimeMillis() + TROUBLE_REST_MS;
            String what = String.valueOf(e);
            if (!what.equals(walkTrouble)) {
                walkTrouble = what;
                club.log().log(java.util.logging.Level.WARNING, "Clubhouse: an NPC regular could not walk; they step"
                        + " to their places instead for a minute", e);
            }
            for (Iterator<Body> it = bodies.values().iterator(); it.hasNext(); ) {
                Body b = it.next();
                if (b.walk == null) {
                    continue;
                }
                b.walk = null;
                b.to = null;
                b.at = null; // stepped to a place at the next second
                if (b.leaving) {
                    it.remove();
                    try {
                        drop(b);
                    } catch (RuntimeException | LinkageError ignored) {
                        // a game entity left behind is swept at the next start or chunk load
                    }
                }
            }
        }
    }

    private void walk() {
        World w = worldName == null ? null : Bukkit.getWorld(worldName);
        if (w == null || ways == null) {
            return;
        }
        Set<Long> players = playerCells(w);
        boolean jumped = false;
        for (Iterator<Body> it = bodies.values().iterator(); it.hasNext(); ) {
            Body b = it.next();
            if (b.walk == null) {
                continue;
            }
            Entity e = live(b);
            if (e == null) { // gone (its chunk unloaded): made again at its place at the next second
                b.walk = null;
                b.at = null;
                if (b.leaving) {
                    it.remove();
                    drop(b);
                }
                continue;
            }
            RegularWalk walk = b.walk;
            WalkGrid.Point next = walk.peek();
            long here = cellKey(walk.x(), walk.y(), walk.z());
            long there = cellKey(next.x(), next.y(), next.z());
            if (!walk.done() && there != here && players.contains(there)) {
                b.paused++; // someone stands there: it waits, then goes round, then steps to its place
                if (b.paused >= GIVE_UP_TICKS) {
                    if (finish(w, b)) {
                        it.remove();
                    }
                } else if (b.paused % ROUND_TICKS == 0) {
                    WalkGrid.Point from = new WalkGrid.Point(walk.x(), walk.y(), walk.z());
                    List<RegularWalk.Leg> round = b.leaving ? ways.walkOut(from, players)
                            : b.to == null ? null : ways.route(from, RegularWays.point(b.to), players);
                    if (round != null) {
                        b.walk = new RegularWalk(round, walk.yaw());
                    }
                }
                continue;
            }
            b.paused = 0;
            walk.step();
            if (!loaded(w, walk.x(), walk.z())) {
                if (finish(w, b)) {
                    it.remove();
                }
                continue;
            }
            if (walk.jumped()) {
                b.upstairs = walk.upstairs();
                jumped = true;
            }
            e.teleport(new Location(w, walk.x(), walk.y(), walk.z(), walk.yaw(), 0f));
            e.setRotation(walk.yaw(), 0f);
            if (walk.done() && finish(w, b)) {
                it.remove();
            }
        }
        if (jumped) {
            CpuPlayers npcs = CpuPlayers.of(club.games());
            if (npcs != null) {
                views(w, npcs); // up or down the Parlor's way: shown at once to whom it is for
            }
        }
    }

    /**
     * The walk is over (there, or given up): one walking out is taken down; one walking to a place is put there,
     * sitting down on a seat.
     *
     * @return whether it was taken down (the caller takes it off the list)
     */
    private boolean finish(World w, Body b) {
        b.walk = null;
        b.paused = 0;
        if (b.leaving) {
            drop(b);
            return true;
        }
        RegularSpots.Spot s = b.to;
        b.to = null;
        b.at = null;
        if (s != null) {
            place(w, CpuPlayers.of(club.games()), b, s);
        }
        return false;
    }

    private static boolean loaded(World w, double x, double z) {
        return w.isChunkLoaded((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
    }

    private static long cellKey(double x, double y, double z) {
        return RegularSpots.key((int) Math.floor(x), (int) Math.floor(y + 1e-6), (int) Math.floor(z));
    }

    /** The blocks real players stand in now (their feet's, and the ones over and under it), in and round the room. */
    private Set<Long> playerCells(World w) {
        Set<Long> out = new HashSet<>();
        if (w == null || siteOf == null) {
            return out;
        }
        Box near = siteOf.box().expand(2);
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            Location l = p.getLocation();
            if (!near.contains(l.getX(), l.getY(), l.getZ())) {
                continue;
            }
            for (int dy = -1; dy <= 1; dy++) {
                out.add(cellKey(l.getX(), l.getY() + dy, l.getZ()));
            }
        }
        return out;
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
            if (!(b.mannequin != null && Bukkit.getEntity(b.mannequin) instanceof Mannequin m) || b.at == null
                    || b.walk != null) {
                continue; // one on its way does nothing but walk
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
        b.mannequin = null;
        b.walk = null;
    }

    /** Every regular comes down at once (nobody visiting, the room closed or moved, the Clubhouse stopped). */
    void remove() {
        for (Body b : bodies.values()) {
            drop(b);
        }
        bodies.clear();
        shown.clear();
        siteOf = null;
        roomsOf = null;
        spots = List.of();
        ways = null;
        doorFree.clear();
    }

    /** A player left: their view is set again when they come back. */
    void forget(UUID player) {
        shown.remove(player);
        waved.keySet().removeIf(k -> k.endsWith(":" + player));
    }

    /** For the admins' status. */
    String statusLine() {
        if (why != null) {
            return "NPC regulars: " + why;
        }
        int walking = 0;
        for (Body b : bodies.values()) {
            walking += b.walk != null ? 1 : 0;
        }
        String walks = ways == null ? "no walks (they step)"
                : System.currentTimeMillis() < walkRestUntil ? "stepping for a minute after trouble"
                : "walking on " + ways.grid().size() + " cells";
        return "NPC regulars: " + bodies.size() + " in the room (" + walking + " walking), " + spots.size()
                + " places, " + walks;
    }
}
