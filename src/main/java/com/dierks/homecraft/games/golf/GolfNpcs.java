package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.cpu.CpuLevel;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.games.cpu.NpcBodies;
import com.dierks.homecraft.games.cpu.NpcCrowd;
import com.dierks.homecraft.games.cpu.WorldNpcs;
import com.dierks.homecraft.games.trial.PartyLobby;
import com.dierks.homecraft.games.trial.PartyNpcs;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * NPC players in golf together (stage 2): company for a golfer who opened a golf party and waits alone, and NPC
 * golfers in the group's round, on its shared scorecard.
 *
 * <p><b>Joining</b> as in a party race ({@link PartyNpcs#company}): after {@code games.cpu.join_after_seconds} alone,
 * one NPC and then another now and then, only into places of the party's four that no real player has, and all of
 * them away when someone with NPCs off joins. With an NPC in, the host may start alone.
 *
 * <p><b>Playing.</b> Everyone plays a hole at once in golf together, so an NPC plays its hole beside the others: its
 * strokes are drawn when the hole starts ({@link #strokes}: on average par + 2 at easy, par + 1 at medium, about par
 * at hard, never over the pick-up score), and shown one by one: its mannequin at its ball, a swing, the ball flying
 * in a simple arc from the tee towards the cup and into it on the last, then a walk to where it landed. It is not
 * physics and it touches nothing: the ball is a display (no box, nothing can hit it), shown only to players who have
 * NPCs on, and the mannequin is not collidable. The group never waits for an NPC: when the last real ball is in, an
 * NPC still playing finishes on its drawn strokes ({@link GolfGroup#advance}), and it never starts the hole clock.
 *
 * <p><b>Nothing real.</b> Its row is on the shared card and in the group's ranking line, marked "⚙ NPC"; it has no
 * round, no session, no board, no reward and no record, and every real player's round is exactly their own.
 */
public final class GolfNpcs implements WorldNpcs {

    static final String GAME = "mini_golf";
    /** Average strokes over par by level. */
    static final int OVER_EASY = 2;
    static final int OVER_MEDIUM = 1;
    static final int OVER_HARD = 0;

    /** One NPC golfer's hole. */
    private static final class Golfer {
        final CpuPlayer npc;
        final CpuLevel level;
        int plan;
        int taken;
        double bx;
        double by;
        double bz;
        double fromX;
        double fromY;
        double fromZ;
        double toX;
        double toY;
        double toZ;
        int flight;
        int flightLeft;
        long nextAt;
        double wx;
        double wy;
        double wz;
        boolean told;
        UUID ball;

        Golfer(CpuPlayer npc, CpuLevel level) {
            this.npc = npc;
            this.level = level;
        }
    }

    /** A group with NPC golfers. */
    private static final class Playing {
        final GolfGroup group;
        final long partyId;
        final Map<UUID, Golfer> golfers = new LinkedHashMap<>();
        int hole = -1;
        long overAt = -1;

        Playing(GolfGroup group, long partyId) {
            this.group = group;
            this.partyId = partyId;
        }
    }

    private final MiniGolf golf;
    private final Map<Long, NpcCrowd.Alone> clocks = new HashMap<>();
    private final Map<Long, List<UUID>> seen = new HashMap<>();
    private final Map<Long, Playing> playing = new HashMap<>();
    private final SplittableRandom rng = new SplittableRandom();
    private final Map<UUID, Set<UUID>> ballsShown = new HashMap<>();
    private NpcBodies bodies;
    private CpuPlayers npcs;
    private long ticks;

    GolfNpcs(MiniGolf golf) {
        this.golf = golf;
    }

    private GamesService games() {
        return golf.games();
    }

    static String where(long partyId) {
        return "golf:" + partyId;
    }

    /**
     * An NPC's strokes on a hole of {@code par} at {@code level} (pure): on average par + 2 at easy, par + 1 at
     * medium, about par at hard, a stroke either way now and then; at least 1, at most {@code cap} (the pick-up).
     */
    static int strokes(int par, CpuLevel level, int cap, RandomGenerator rng) {
        int over = switch (level) {
            case EASY -> OVER_EASY;
            case MEDIUM -> OVER_MEDIUM;
            case HARD -> OVER_HARD;
        };
        int roll = rng.nextInt(6); // -1 one time in six, +1 one time in six, else none
        int wobble = roll == 0 ? -1 : roll == 5 ? 1 : 0;
        return Math.max(1, Math.min(Math.max(1, cap), par + over + wobble));
    }

    /**
     * Where an NPC's ball is after shot {@code k} of {@code n} along the way from the tee to the cup (pure): most of
     * the way with the first, closer with each, and in the cup ({@code 1}) with the last.
     */
    static double along(int k, int n) {
        if (k >= n) {
            return 1;
        }
        return 1 - Math.pow(0.3, k);
    }

    /**
     * The NPCs who play with {@code players} (the real golfers going to the first tee): the party's NPCs while NPC
     * players are on and every one of them has NPCs on; none otherwise.
     */
    List<UUID> coming(PartyLobby lobby, List<Player> players) {
        CpuPlayers c = CpuPlayers.of(games());
        if (c == null || lobby == null || lobby.npcs().isEmpty()) {
            return List.of();
        }
        List<UUID> reals = new ArrayList<>();
        for (Player p : players) {
            reals.add(p.getUniqueId());
        }
        return NpcCrowd.welcome(reals, c::wants) ? lobby.npcs() : List.of();
    }

    /** The group of {@code partyId} just went to the first tee with {@code reals}: its NPCs join it at their level. */
    void started(long partyId, List<Player> reals, List<UUID> npcIds) {
        CpuPlayers c = CpuPlayers.of(games());
        if (c == null || npcIds.isEmpty() || reals.isEmpty()) {
            return;
        }
        GolfGroup g = golf.rounds().groupOf(reals.get(0).getUniqueId());
        if (g == null || g.id() != partyId) {
            return;
        }
        List<UUID> realIds = new ArrayList<>();
        for (Player p : reals) {
            realIds.add(p.getUniqueId());
        }
        Playing pl = new Playing(g, partyId);
        for (UUID id : npcIds) {
            CpuPlayer p = CpuRoster.byId(id);
            if (p != null && g.addNpc(id, CpuRoster.tagged(c.name(p)))) {
                pl.golfers.put(id, new Golfer(p, c.level(p, realIds, GAME)));
            }
        }
        if (!pl.golfers.isEmpty()) {
            playing.put(partyId, pl);
        }
    }

    // ---- once a tick ----------------------------------------------------------------------------------------

    @Override
    public void tick(CpuPlayers c, long tick) {
        npcs = c;
        ticks++;
        if (bodies == null) {
            bodies = new NpcBodies(c);
        }
        if (ticks % 20 == 0) {
            lobbies(c);
        }
        for (Playing pl : new ArrayList<>(playing.values())) {
            play(c, pl);
        }
    }

    private void lobbies(CpuPlayers c) {
        GamesService g = games();
        Set<Long> open = new HashSet<>();
        long now = g.clock().nowMillis();
        for (PartyLobby lobby : g.parties().all()) {
            if (lobby.kind() != PartyLobby.Kind.GOLF) {
                continue;
            }
            open.add(lobby.id());
            String where = where(lobby.id());
            PartyNpcs.Change ch = PartyNpcs.company(lobby, id -> c.wants(id) && Bukkit.getPlayer(id) != null,
                    clocks.computeIfAbsent(lobby.id(), k -> new NpcCrowd.Alone()), now, c.joinAfterMs(), () -> {
                        List<CpuPlayer> one = NpcCrowd.pick(c.roster(), c.busy(), 1, c.rng());
                        return one.isEmpty() ? null : one.get(0);
                    });
            if (ch.joined() != null) {
                CpuPlayer p = CpuRoster.byId(ch.joined());
                if (c.claim(p, where)) {
                    golf.together().tellParty(lobby, "&b" + CpuRoster.tagged(c.name(p)) + " &e" + p.level().starText()
                            + " &bjoined the golf party! &7Start when you like - or wait for a friend.");
                } else {
                    lobby.removeNpc(ch.joined());
                }
            }
            if (!ch.left().isEmpty()) {
                golf.together().tellParty(lobby, "&7The NPCs left the party.");
            }
            List<UUID> now2 = lobby.npcs();
            List<UUID> was = seen.put(lobby.id(), now2);
            if (was != null) {
                for (UUID id : was) {
                    if (!now2.contains(id) && !playing.containsKey(lobby.id())) {
                        c.release(CpuRoster.byId(id), where);
                    }
                }
            }
        }
        for (Long id : new ArrayList<>(seen.keySet())) {
            if (!open.contains(id)) {
                for (UUID n : seen.remove(id)) {
                    if (!playing.containsKey(id)) {
                        c.release(CpuRoster.byId(n), where(id));
                    }
                }
                clocks.remove(id);
            }
        }
    }

    private void play(CpuPlayers c, Playing pl) {
        GolfGroup g = pl.group;
        GolfCourse course = golf.playableCourse(g.courseId());
        World w = course == null ? null : Bukkit.getWorld(course.world());
        if (g.over() || w == null) {
            if (pl.overAt < 0) {
                pl.overAt = ticks;
            }
            if (ticks - pl.overAt > 60 || w == null) {
                down(c, pl);
            }
            return;
        }
        int hole = g.hole();
        GolfCourse.Hole h = hole < course.holes().size() ? course.holes().get(hole) : null;
        if (h == null || h.tee() == null || h.cup() == null) {
            return;
        }
        int cap = h.par() + golf.settings().maxOverPar();
        if (hole != pl.hole) {
            pl.hole = hole;
            int k = 0;
            for (Golfer gf : pl.golfers.values()) {
                gf.plan = strokes(h.par(), gf.level, cap, rng);
                gf.taken = 0;
                gf.told = false;
                gf.flightLeft = 0;
                g.npcPlan(gf.npc.id(), gf.plan);
                double side = 1.5 + k++; // beside the tee, never on it
                double yaw = Math.toRadians(h.tee().yaw());
                gf.bx = h.tee().x() + Math.cos(yaw) * side;
                gf.by = h.tee().y();
                gf.bz = h.tee().z() + Math.sin(yaw) * side;
                gf.wx = gf.bx;
                gf.wy = gf.by;
                gf.wz = gf.bz;
                gf.nextAt = ticks + 60 + rng.nextInt(60) + gf.npc.personality().thinkMs / 50;
            }
        }
        double cx = h.cup().x() + 0.5;
        double cy = h.cup().y();
        double cz = h.cup().z() + 0.5;
        for (Golfer gf : pl.golfers.values()) {
            if (g.npcDone(gf.npc.id())) {
                finished(pl, gf, hole);
            } else if (g.allDone()) {
                g.npcHoleDone(gf.npc.id()); // the group never waits for an NPC: in on its drawn strokes
                gf.bx = cx;
                gf.by = cy;
                gf.bz = cz;
                finished(pl, gf, hole);
            } else {
                shoot(w, g, gf, cx, cy, cz);
            }
            ball(w, gf);
            float yaw = (float) Math.toDegrees(Math.atan2(-(cx - gf.wx), cz - gf.wz));
            bodies.place("golf:" + pl.partyId + ":" + gf.npc.key(), gf.npc, gf.level,
                    new Location(w, gf.wx, gf.wy, gf.wz, yaw, 0f), Pose.STANDING, false, false);
        }
        if (ticks % 20 == 0) {
            bodies.views(w);
            ballViews(w, pl);
        }
    }

    /** The next stroke when its time comes, the ball's flight, and the walk to where it landed. */
    private void shoot(World w, GolfGroup g, Golfer gf, double cx, double cy, double cz) {
        if (gf.flightLeft > 0) {
            gf.flightLeft--;
            double t = 1 - (double) gf.flightLeft / gf.flight;
            double d = Math.hypot(gf.toX - gf.fromX, gf.toZ - gf.fromZ);
            double up = d > 3 ? Math.min(3, d * 0.2) * Math.sin(Math.PI * t) : 0; // a putt rolls; a drive flies
            gf.bx = gf.fromX + (gf.toX - gf.fromX) * t;
            gf.by = gf.fromY + (gf.toY - gf.fromY) * t + up;
            gf.bz = gf.fromZ + (gf.toZ - gf.fromZ) * t;
            if (gf.flightLeft == 0 && gf.taken >= gf.plan) {
                g.npcHoleDone(gf.npc.id());
                w.playSound(new Location(w, cx, cy, cz), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.6f);
            }
            return;
        }
        double walk = Math.hypot(gf.bx - gf.wx, gf.bz - gf.wz);
        if (walk > 0.6) { // walking to its ball, four blocks a second
            double step = Math.min(walk, 0.2);
            gf.wx += (gf.bx - gf.wx) / walk * step;
            gf.wz += (gf.bz - gf.wz) / walk * step;
            gf.wy = gf.by;
            return;
        }
        if (ticks < gf.nextAt) {
            return;
        }
        gf.taken++;
        double to = along(gf.taken, gf.plan);
        double dx = cx - gf.bx;
        double dz = cz - gf.bz;
        double wobble = gf.taken >= gf.plan ? 0 : (rng.nextDouble() - 0.5) * Math.min(2, Math.hypot(dx, dz) * 0.2);
        double left = gf.taken >= gf.plan ? 1 : (to - along(gf.taken - 1, gf.plan)) / Math.max(1e-6,
                1 - along(gf.taken - 1, gf.plan));
        gf.fromX = gf.bx;
        gf.fromY = gf.by;
        gf.fromZ = gf.bz;
        gf.toX = gf.bx + dx * left + wobble;
        gf.toY = gf.taken >= gf.plan ? cy : gf.by + (cy - gf.by) * left;
        gf.toZ = gf.bz + dz * left - wobble;
        double d = Math.hypot(gf.toX - gf.fromX, gf.toZ - gf.fromZ);
        gf.flight = (int) Math.max(10, Math.min(40, 10 + d * 1.5));
        gf.flightLeft = gf.flight;
        gf.nextAt = ticks + gf.flight + 50 + rng.nextInt(50) + gf.npc.personality().thinkMs / 50;
        g.npcStrokes(gf.npc.id(), gf.taken);
        bodies.swing("golf:" + g.id() + ":" + gf.npc.key());
        w.playSound(new Location(w, gf.bx, gf.by, gf.bz), Sound.BLOCK_WOOD_HIT, 0.4f, 1.4f);
    }

    private void finished(Playing pl, Golfer gf, int hole) {
        if (gf.told) {
            return;
        }
        gf.told = true;
        gf.flightLeft = 0;
        removeBall(gf);
        String line = "&7" + CpuRoster.tagged(npcs.name(gf.npc)) + " finished hole " + (hole + 1) + " in " + gf.plan
                + ".";
        for (UUID id : pl.group.active()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(Text.of(line));
            }
        }
    }

    /** The NPC's ball: a little white display, no box, moved with its flight. */
    private void ball(World w, Golfer gf) {
        if (gf.told) {
            return;
        }
        Entity e = gf.ball == null ? null : Bukkit.getEntity(gf.ball);
        Location at = new Location(w, gf.bx, gf.by + 0.1, gf.bz);
        if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            return;
        }
        if (e == null || !e.isValid()) {
            ItemDisplay d = w.spawn(at, ItemDisplay.class, made -> {
                WorldEntities.tag(made, npcs, null);
                made.setVisibleByDefault(false);
                made.setPersistent(false);
                made.setItemStack(new ItemStack(Material.SNOWBALL));
                made.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(0.3f, 0.3f, 0.3f), new AxisAngle4f()));
                made.setTeleportDuration(1);
            });
            gf.ball = d.getUniqueId();
            for (Set<UUID> s : ballsShown.values()) {
                s.remove(gf.ball);
            }
        } else {
            e.teleport(at);
        }
    }

    private void ballViews(World w, Playing pl) {
        for (Player p : w.getPlayers()) {
            boolean wants = npcs.wants(p.getUniqueId());
            Set<UUID> mine = ballsShown.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
            for (Golfer gf : pl.golfers.values()) {
                Entity e = gf.ball == null ? null : Bukkit.getEntity(gf.ball);
                if (e == null) {
                    continue;
                }
                if (wants && mine.add(gf.ball)) {
                    p.showEntity(golf.plugin(), e);
                } else if (!wants && mine.remove(gf.ball)) {
                    p.hideEntity(golf.plugin(), e);
                }
            }
        }
    }

    private void removeBall(Golfer gf) {
        Entity e = gf.ball == null ? null : Bukkit.getEntity(gf.ball);
        if (e != null) {
            e.remove();
        }
        gf.ball = null;
    }

    private void down(CpuPlayers c, Playing pl) {
        playing.remove(pl.partyId);
        PartyLobby lobby = games().parties().get(pl.partyId);
        for (Golfer gf : pl.golfers.values()) {
            removeBall(gf);
            if (bodies != null) {
                bodies.remove("golf:" + pl.partyId + ":" + gf.npc.key());
            }
            if (lobby == null || !lobby.npcs().contains(gf.npc.id())) {
                c.release(gf.npc, where(pl.partyId));
            }
        }
    }

    @Override
    public void clear() {
        for (Playing pl : playing.values()) {
            for (Golfer gf : pl.golfers.values()) {
                removeBall(gf);
            }
        }
        if (bodies != null) {
            bodies.clear();
        }
        GamesService g = games();
        if (g != null) {
            for (PartyLobby lobby : g.parties().all()) {
                if (lobby.kind() == PartyLobby.Kind.GOLF) {
                    lobby.clearNpcs();
                }
            }
        }
        if (npcs != null) {
            Set<Long> ids = new HashSet<>(seen.keySet());
            ids.addAll(playing.keySet());
            for (Long id : ids) {
                for (CpuPlayer p : CpuRoster.ALL) {
                    npcs.release(p, where(id));
                }
            }
        }
        seen.clear();
        clocks.clear();
        playing.clear();
        ballsShown.clear();
    }

    @Override
    public String statusLine() {
        int n = 0;
        for (Playing pl : playing.values()) {
            n += pl.golfers.size();
        }
        return "Golf together: " + n + " NPC golfer(s) playing";
    }
}
