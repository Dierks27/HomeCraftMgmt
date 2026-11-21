package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.cpu.CpuLevel;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.games.cpu.NpcBodies;
import com.dierks.homecraft.games.cpu.NpcCrowd;
import com.dierks.homecraft.games.cpu.WorldNpcs;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * NPC players in party races (stage 2, the owner: "racing parkour or any of those"): company for a racer who opened a
 * party race and waits alone, and NPC racers on the track beside them.
 *
 * <p><b>Joining.</b> A party race's lobby whose only real player has NPCs on gets an NPC after
 * {@code games.cpu.join_after_seconds} alone, then one more now and then, up to {@link NpcCrowd#MAX_IN_GAME}
 * ({@link #company}). They are the lobby's NPCs ({@link PartyLobby#npcs}), never members: a friend who joins takes a
 * place an NPC had (the lobby trims them), and a friend with NPCs off sends every NPC away. With an NPC in, the host
 * may start alone.
 *
 * <p><b>Racing.</b> At the start each NPC gets a grid spot behind every real racer (the real racers' grid is exactly
 * as without NPCs: {@link #npcSpots}), a path ({@link GhostPath}: a recorded real run of the course, else its
 * checkpoints) and its level for this race ({@code CpuLevels}: easy for everyone when any racer wants easy). From
 * Go its mannequin moves along that path a step a tick ({@link GhostPath.Run}), reports its progress to the race as
 * a racer would ({@link PartyRace#progress}), and at the line its time ({@link PartyRace#finished}). Elytra NPCs
 * glide, boat NPCs sit (on an invisible marker: a real boat would bump real ones), runners run.
 *
 * <p><b>Nothing real.</b> An NPC is never seated through race mode, never has a run, a session or a Player, and is
 * never written anywhere: its finish is only in this race's own lines, bars and results ({@link PartyRace}), marked
 * "⚙ NPC". The race never waits for one, and every place that rewards a place reads {@link PartyRace#realOnly}.
 */
public final class PartyNpcs implements WorldNpcs {

    /** The game id NPC levels are asked under (Time Trials'). */
    static final String GAME = "time_trials";
    /** A finished race's NPC bodies stay this long (ticks), so a real finisher sees them cross. */
    static final long LINGER_TICKS = 60;

    /** What {@link #company} changed in a lobby. */
    record Change(List<UUID> left, UUID joined) {

        static final Change NONE = new Change(List.of(), null);
    }

    /** One NPC racer in a race. */
    private record Racer(CpuPlayer npc, CpuLevel level, GhostPath.Run run, Course.Spot spot, TrialKind kind,
                         boolean hideInWalls) {
    }

    /** A party race with NPC racers. */
    private static final class Racing {
        final PartyRace race;
        final String world;
        final Map<UUID, Racer> racers = new LinkedHashMap<>();
        long doneAt = -1;

        Racing(PartyRace race, String world) {
            this.race = race;
            this.world = world;
        }
    }

    private final TimeTrials trials;
    private final Map<Long, NpcCrowd.Alone> clocks = new HashMap<>();
    /** Each lobby's NPCs at the last tick (an NPC is released once it is out of every lobby and race). */
    private final Map<Long, List<UUID>> seen = new HashMap<>();
    private final Map<Long, Racing> racing = new HashMap<>();
    private final SplittableRandom rng = new SplittableRandom();
    private NpcBodies bodies;
    private CpuPlayers npcs;
    private long ticks;
    private String lastPath = "none yet";

    PartyNpcs(TimeTrials trials) {
        this.trials = trials;
    }

    private GamesService games() {
        return trials.games();
    }

    /** The claim an NPC in this lobby's party holds ({@link CpuPlayers#claim}). */
    static String where(long lobbyId) {
        return "party:" + lobbyId;
    }

    // ---- the lobby: company for a lone racer --------------------------------------------------------------

    /**
     * One tick of a lobby's company (pure): every NPC leaves while a real member has NPCs off; while the one real
     * member waits alone, one joins after the wait and one more every little while, up to {@link
     * NpcCrowd#MAX_IN_GAME}, into places no real player has. Only an open lobby changes.
     *
     * @param pick the next free NPC, or {@code null} for none
     */
    static Change company(PartyLobby lobby, Predicate<UUID> wants, NpcCrowd.Alone clock, long nowMs,
                          long joinAfterMs, Supplier<CpuPlayer> pick) {
        if (lobby.state() != PartyLobby.State.OPEN) {
            return Change.NONE;
        }
        List<UUID> reals = lobby.members();
        if (!NpcCrowd.welcome(reals, wants)) {
            List<UUID> left = lobby.npcs();
            lobby.clearNpcs();
            clock.reset();
            return left.isEmpty() ? Change.NONE : new Change(left, null);
        }
        boolean alone = reals.size() == 1;
        int in = lobby.npcs().size();
        if (in >= NpcCrowd.MAX_IN_GAME || lobby.size() + in >= lobby.max()
                || !clock.due(alone, in, nowMs, joinAfterMs)) {
            return Change.NONE;
        }
        CpuPlayer c = pick.get();
        if (c == null || !lobby.addNpc(c.id())) {
            return Change.NONE;
        }
        clock.joined(nowMs);
        return new Change(List.of(), c.id());
    }

    /**
     * The NPCs who race with {@code free} (the real racers seated now): the lobby's NPCs while NPC players are on and
     * every one of these racers has NPCs on; none otherwise.
     */
    List<UUID> coming(PartyLobby lobby, List<Player> free) {
        CpuPlayers c = CpuPlayers.of(games());
        if (c == null || lobby == null || lobby.npcs().isEmpty()) {
            return List.of();
        }
        List<UUID> reals = new ArrayList<>();
        for (Player p : free) {
            reals.add(p.getUniqueId());
        }
        return NpcCrowd.welcome(reals, c::wants) ? lobby.npcs() : List.of();
    }

    /**
     * Where {@code n} NPCs start, behind {@code reals} real racers whose grid is {@code grid} (pure over the
     * surface): runners and flyers all start on the start ({@link RaceGrid.Mode#SHARED}); on a boat grid, the spots
     * after the real racers' on a grid laid out for everyone, used only when its first spots are exactly the real
     * racers' and every block it read was loaded (else none: the NPCs give way, and the real grid is never changed).
     */
    static List<Course.Spot> npcSpots(Course base, RaceGrid.Surface surface, RaceGrid.Grid grid, int reals, int n) {
        if (n <= 0) {
            return List.of();
        }
        if (grid.mode() == RaceGrid.Mode.SHARED) {
            List<Course.Spot> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                out.add(base.start());
            }
            return out;
        }
        RaceGrid.Grid all = RaceGrid.forCourse(base, surface, reals + n);
        if (surface instanceof WorldSurface w && !w.missing().isEmpty()) {
            return List.of();
        }
        if (all.size() <= reals) {
            return List.of();
        }
        for (int i = 0; i < reals; i++) {
            if (!all.spot(i).equals(grid.spot(i))) {
                return List.of();
            }
        }
        return new ArrayList<>(all.spots().subList(reals, Math.min(all.size(), reals + n)));
    }

    /**
     * The race is starting with {@code npcIds} (each with its spot in {@code spots}) beside the real racers
     * {@code free}: each joins the race as an NPC racer at its level, on its path. Returns how many joined.
     */
    int racing(PartyRace race, Course base, List<Player> free, List<UUID> npcIds, List<Course.Spot> spots) {
        CpuPlayers c = CpuPlayers.of(games());
        if (c == null || npcIds.isEmpty()) {
            return 0;
        }
        npcs = c;
        List<UUID> reals = new ArrayList<>();
        for (Player p : free) {
            reals.add(p.getUniqueId());
        }
        Racing r = new Racing(race, base.world());
        RunTrails.Trail trail = trials.trail(base);
        long[] splits = recordSplits(base);
        long minMs = base.minSecondsOr(trials.settings().minSeconds()) * 1000L;
        for (int i = 0; i < npcIds.size() && i < spots.size(); i++) {
            CpuPlayer npc = CpuRoster.byId(npcIds.get(i));
            Course.Spot spot = spots.get(i);
            Laps.Raced raced = npc == null ? null : Laps.raced(base, spot, 0);
            if (raced == null || raced.course() == null) {
                continue;
            }
            GhostPath path = GhostPath.of(raced.course(), spot, trail, splits, minMs);
            if (path == null || !race.addNpc(npc.id(), CpuRoster.tagged(c.name(npc)))) {
                continue;
            }
            race.seated(npc.id(), spot);
            CpuLevel level = c.level(npc, reals, GAME);
            boolean canFall = base.kind() != TrialKind.ELYTRA; // a flyer's slip is a wobble, never a fall
            GhostPath.Run run = new GhostPath.Run(path, level, canFall, rng);
            r.racers.put(npc.id(), new Racer(npc, level, run, spot, base.kind(),
                    path.source() != GhostPath.Source.RECORDED));
            lastPath = base.id() + ": " + path.source().words();
        }
        if (!r.racers.isEmpty()) {
            racing.put(race.lobbyId(), r);
        }
        return r.racers.size();
    }

    /** The course record holder's checkpoint times on this layout (a real run's pace), or {@code null}. */
    private long[] recordSplits(Course base) {
        try {
            GamesDao.ScoreRow rec = trials.record(base.id());
            return rec == null ? null : games().dao().splits(rec.player(), base.id(), base.layoutHash());
        } catch (SQLException | RuntimeException e) {
            games().logger().log(Level.FINE, "NPC racers: could not read the record's splits on " + base.id(), e);
            return null;
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
        Set<String> worlds = new HashSet<>();
        for (Racing r : new ArrayList<>(racing.values())) {
            move(c, r);
            worlds.add(r.world);
        }
        if (ticks % 20 == 0) {
            for (String name : worlds) {
                bodies.views(Bukkit.getWorld(name));
            }
        }
    }

    private void lobbies(CpuPlayers c) {
        GamesService g = games();
        Set<Long> open = new HashSet<>();
        long now = g.clock().nowMillis();
        for (PartyLobby lobby : g.parties().all()) {
            if (lobby.kind() != PartyLobby.Kind.RACE) {
                continue;
            }
            open.add(lobby.id());
            NpcCrowd.Alone clock = clocks.computeIfAbsent(lobby.id(), k -> new NpcCrowd.Alone());
            String where = where(lobby.id());
            Change ch = company(lobby, id -> c.wants(id) && online(id), clock, now, c.joinAfterMs(), () -> {
                List<CpuPlayer> one = NpcCrowd.pick(c.roster(), c.busy(), 1, c.rng());
                return one.isEmpty() ? null : one.get(0);
            });
            if (ch.joined() != null) {
                CpuPlayer joined = CpuRoster.byId(ch.joined());
                if (c.claim(joined, where)) {
                    say(lobby, "&b" + CpuRoster.tagged(c.name(joined)) + " &e" + joined.level().starText()
                            + " &bjoined the party! &7Start when you like - or wait for a friend.");
                } else {
                    lobby.removeNpc(ch.joined()); // it went somewhere else this very moment
                }
            }
            if (!ch.left().isEmpty()) {
                say(lobby, "&7The NPCs left the party.");
            }
            List<UUID> now2 = lobby.npcs();
            List<UUID> was = seen.put(lobby.id(), now2);
            if (was != null) {
                for (UUID id : was) {
                    CpuPlayer npc = CpuRoster.byId(id);
                    if (npc == null || now2.contains(id)) {
                        continue;
                    }
                    if (ch.left().isEmpty()) {
                        say(lobby, "&7" + CpuRoster.tagged(c.name(npc)) + " gave its place to a friend.");
                    }
                    if (!inRace(lobby.id(), id)) {
                        c.release(npc, where);
                    }
                }
            }
        }
        for (Long id : new ArrayList<>(seen.keySet())) {
            if (!open.contains(id)) {
                for (UUID n : seen.remove(id)) {
                    CpuPlayer npc = CpuRoster.byId(n);
                    if (npc != null && !inRace(id, n)) {
                        c.release(npc, where(id));
                    }
                }
                clocks.remove(id);
            }
        }
    }

    private boolean inRace(long lobbyId, UUID npc) {
        Racing r = racing.get(lobbyId);
        return r != null && r.racers.containsKey(npc);
    }

    private static boolean online(UUID id) {
        try {
            Player p = Bukkit.getPlayer(id);
            return p != null && p.isOnline();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Each NPC racer's step this tick: on its grid spot before Go, then along its path, then at the line. */
    private void move(CpuPlayers c, Racing r) {
        World w = Bukkit.getWorld(r.world);
        long now = Bukkit.getCurrentTick();
        PartyRace race = r.race;
        if (w == null) {
            down(c, r);
            return;
        }
        if (!race.alive()) {
            if (r.doneAt < 0) {
                r.doneAt = now;
            }
            if (now - r.doneAt >= LINGER_TICKS) {
                down(c, r);
            }
            return;
        }
        boolean going = race.state() == PartyRace.State.RACING;
        long ms = going ? Math.max(0, (now - race.goTick()) * 50) : 0;
        for (Racer n : r.racers.values()) {
            String key = key(race, n.npc());
            if (!race.isNpc(n.npc().id())) {
                continue;
            }
            if (!going) {
                Course.Spot s = n.spot();
                bodies.place(key, n.npc(), n.level(), new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0f), Pose.STANDING,
                        n.kind() == TrialKind.BOAT, false);
                continue;
            }
            GhostPath.Pose p = n.run().at(ms);
            if (race.npcRacing(n.npc().id())) {
                if (p.done()) {
                    race.finished(n.npc().id(), n.run().finishMs(), true, null);
                    cheer(w, p);
                } else {
                    race.progress(n.npc().id(), p.reached(), p.toNext(), System.nanoTime());
                }
            }
            Location at = new Location(w, p.x(), p.y(), p.z(), p.yaw(), p.pitch());
            if (n.hideInWalls() && (n.kind() == TrialKind.BOAT || n.kind() == TrialKind.PARKOUR)) {
                at = onGround(at);
            }
            Pose pose = n.kind() == TrialKind.ELYTRA && !p.done() && ms > 1_000 ? Pose.FALL_FLYING : Pose.STANDING;
            bodies.place(key, n.npc(), n.level(), at, pose, n.kind() == TrialKind.BOAT, n.hideInWalls());
        }
    }

    /**
     * A point on a checkpoint line set on the ground under it, when there is ground within a few blocks (a boat NPC
     * on the ice or water, a runner on the path), so a line drawn between checkpoints doesn't float.
     */
    private static Location onGround(Location at) {
        World w = at.getWorld();
        int x = at.getBlockX();
        int z = at.getBlockZ();
        int top = at.getBlockY() + 1;
        for (int y = top; y >= top - 4; y--) {
            Block b = w.getBlockAt(x, y, z);
            if (b.isLiquid() || !b.isPassable()) {
                Location out = at.clone();
                out.setY(b.isLiquid() ? y + 0.9 : b.getBoundingBox().getMaxY());
                return out;
            }
        }
        return at;
    }

    private static void cheer(World w, GhostPath.Pose p) {
        w.spawnParticle(Particle.HAPPY_VILLAGER, p.x(), p.y() + 2.0, p.z(), 6, 0.3, 0.2, 0.3, 0.0);
    }

    private static String key(PartyRace race, CpuPlayer npc) {
        return "party:" + race.lobbyId() + ":" + npc.key();
    }

    /** A race's NPC bodies come down, and its NPCs are free again unless still in the party. */
    private void down(CpuPlayers c, Racing r) {
        racing.remove(r.race.lobbyId());
        PartyLobby lobby = games().parties().get(r.race.lobbyId());
        for (Racer n : r.racers.values()) {
            if (bodies != null) {
                bodies.remove(key(r.race, n.npc()));
            }
            if (lobby == null || !lobby.npcs().contains(n.npc().id())) {
                c.release(n.npc(), where(r.race.lobbyId()));
            }
        }
    }

    private void say(PartyLobby lobby, String line) {
        for (UUID id : lobby.members()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(Text.of(line));
            }
        }
    }

    @Override
    public void clear() {
        if (bodies != null) {
            bodies.clear();
        }
        for (PartyLobby lobby : games() == null ? List.<PartyLobby>of() : games().parties().all()) {
            if (lobby.kind() == PartyLobby.Kind.RACE) {
                lobby.clearNpcs();
            }
        }
        if (npcs != null) {
            for (Long id : seen.keySet()) {
                for (CpuPlayer c : CpuRoster.ALL) {
                    npcs.release(c, where(id));
                }
            }
            for (Long id : racing.keySet()) {
                for (CpuPlayer c : CpuRoster.ALL) {
                    npcs.release(c, where(id));
                }
            }
        }
        seen.clear();
        clocks.clear();
        racing.clear();
    }

    @Override
    public String statusLine() {
        int inLobbies = 0;
        for (List<UUID> l : seen.values()) {
            inLobbies += l.size();
        }
        int onTrack = 0;
        for (Racing r : racing.values()) {
            onTrack += r.racers.size();
        }
        return "Party races: " + inLobbies + " NPC(s) in parties, " + onTrack + " racing; recorded runs on "
                + trials.trails().size() + " course(s); last path: " + lastPath;
    }

    /** For the tests: the race's NPC racers' runs, by id. */
    Map<UUID, GhostPath.Run> runs(PartyRace race) {
        Racing r = racing.get(race.lobbyId());
        Map<UUID, GhostPath.Run> out = new LinkedHashMap<>();
        if (r != null) {
            r.racers.forEach((id, n) -> out.put(id, n.run()));
        }
        return out;
    }
}
