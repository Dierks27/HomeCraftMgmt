package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.cpu.CpuLevel;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.games.cpu.NpcBodies;
import com.dierks.homecraft.games.cpu.NpcCrowd;
import com.dierks.homecraft.games.cpu.WorldNpcs;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.GhostPath;
import com.dierks.homecraft.games.trial.Laps;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * NPC racers at Race Night (stage 2): a small night gets a few NPC boats on the track beside its real racers, for
 * the fun of a fuller race. They race; they never score.
 *
 * <p><b>Who races.</b> At each race's grid call, while every racer on tonight's list has NPCs on, NPCs fill the field
 * up to {@value #FIELD} racers ({@link #count}), on grid spots no real racer has ({@link NightRunner#takenSpots}): a
 * real racer always gets a spot first, and one who joins later takes the NPCs' places at the next grid call. A racer
 * with NPCs off on the list means no NPC races tonight.
 *
 * <p><b>How they race.</b> As party races' NPC racers do ({@link GhostPath}): a recorded real run of the track if Time
 * Trials has one, else its checkpoints; from the night's own Go, at their level for tonight (easy for everyone when
 * any racer wants easy). Boat NPCs sit on an invisible marker (a real boat would bump real ones).
 *
 * <p><b>Never in anything real.</b> Race Night never hears of them: they are not in the night's racers, its
 * standings, its points, its places, its prizes, its season board or its stored rows ({@link NightRunner} and
 * {@link RacePrizes} never see an NPC), so every real racer's place, points and prize are exactly what they would
 * be without NPCs. An NPC's finish is only told in the night's chat, marked "⚙ NPC" and "no points".
 */
public final class NightNpcs implements WorldNpcs {

    /** NPCs fill a race up to this many racers. */
    static final int FIELD = 4;
    /** The game id NPC levels are asked under. */
    static final String GAME = "race_night";

    /** One NPC racer in this race. */
    private static final class Racer {
        final CpuPlayer npc;
        final CpuLevel level;
        final Course.Spot spot;
        final GhostPath.Run run;
        final boolean onLine;
        boolean done;

        Racer(CpuPlayer npc, CpuLevel level, Course.Spot spot, GhostPath.Run run, boolean onLine) {
            this.npc = npc;
            this.level = level;
            this.spot = spot;
            this.run = run;
            this.onLine = onLine;
        }
    }

    private final RaceNight game;
    private final SplittableRandom rng = new SplittableRandom();
    private final Map<UUID, Racer> racers = new LinkedHashMap<>();
    /** Tonight's NPCs, kept from race to race (the same faces all night). */
    private final List<CpuPlayer> tonight = new ArrayList<>();
    private NightRunner night;
    private int race = -1;
    private boolean told;
    private NpcBodies bodies;
    private CpuPlayers npcs;
    private long ticks;
    private String why = "no night";

    NightNpcs(RaceNight game) {
        this.game = game;
    }

    /**
     * How many NPCs race with {@code reals} real racers when {@code freeSpots} grid spots are left (pure): enough to
     * make {@value #FIELD} racers, never more than the free spots or {@link NpcCrowd#MAX_IN_GAME}, and none unless
     * NPCs are welcome.
     */
    static int count(int reals, int freeSpots, boolean welcome) {
        if (!welcome || reals <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(Math.min(NpcCrowd.MAX_IN_GAME, freeSpots), FIELD - reals));
    }

    /** The grid spots no real racer has, in grid order (pure). */
    static List<Course.Spot> freeSpots(List<Course.Spot> grid, Collection<Course.Spot> taken) {
        List<Course.Spot> out = new ArrayList<>();
        for (Course.Spot s : grid) {
            if (!taken.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    @Override
    public void tick(CpuPlayers c, long tick) {
        npcs = c;
        ticks++;
        if (bodies == null) {
            bodies = new NpcBodies(c);
        }
        NightRunner n = game.night();
        if (n == null || n.phase().over()) {
            if (night != null) {
                clear();
            }
            why = "no night";
            return;
        }
        if (n != night) {
            clear();
            night = n;
        }
        EventMachine.Phase phase = n.phase();
        if (phase == EventMachine.Phase.GRID && race != n.race()) {
            lineUp(c, n);
        }
        if (race != n.race()) {
            return;
        }
        World w = Bukkit.getWorld(n.track().base().world());
        if (w == null) {
            return;
        }
        if (phase == EventMachine.Phase.GRID || phase == EventMachine.Phase.RACING) {
            move(c, n, w, phase == EventMachine.Phase.RACING);
        } else if (!racers.isEmpty() && !told) {
            results(n);
        }
        if (ticks % 20 == 0) {
            bodies.views(w);
        }
    }

    /** The grid call of a race: tonight's NPCs line up on the spots no real racer has, if they are welcome. */
    private void lineUp(CpuPlayers c, NightRunner n) {
        race = n.race();
        told = false;
        downAll();
        List<UUID> reals = new ArrayList<>();
        for (NightRunner.Racer r : n.joined()) {
            reals.add(r.id());
        }
        boolean welcome = NpcCrowd.welcome(reals, c::wants);
        List<Course.Spot> free = freeSpots(n.track().grid(), n.takenSpots());
        int want = count(reals.size(), free.size(), welcome);
        why = welcome ? want + " NPC racer(s) in race " + race : "a racer tonight has NPCs off";
        String where = "night:" + n.plan().id();
        while (tonight.size() > want) {
            c.release(tonight.remove(tonight.size() - 1), where);
        }
        if (tonight.size() < want) {
            Set<UUID> busy = new HashSet<>(c.busy());
            for (CpuPlayer p : tonight) {
                busy.add(p.id());
            }
            for (CpuPlayer p : NpcCrowd.pick(c.roster(), busy, want - tonight.size(), c.rng())) {
                if (c.claim(p, where)) {
                    tonight.add(p);
                }
            }
        }
        if (tonight.isEmpty()) {
            return;
        }
        TimeTrials trials = game.trials();
        Course base = n.track().base();
        long[] splits = recordSplits(trials, base);
        long minMs = base.minSecondsOr(trials == null ? 0 : trials.settings().minSeconds()) * 1000L;
        for (int i = 0; i < tonight.size() && i < free.size(); i++) {
            CpuPlayer p = tonight.get(i);
            Course.Spot spot = free.get(i);
            Laps.Raced raced = Laps.raced(base, spot, n.laps());
            if (raced.course() == null) {
                continue;
            }
            GhostPath path = GhostPath.of(raced.course(), spot, trials == null ? null : trials.trail(base), splits,
                    minMs);
            if (path == null) {
                continue;
            }
            CpuLevel level = c.level(p, reals, GAME);
            racers.put(p.id(), new Racer(p, level, spot, new GhostPath.Run(path, level, true, rng),
                    path.source() != GhostPath.Source.RECORDED));
        }
        if (!racers.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Racer r : racers.values()) {
                names.add(CpuRoster.tagged(c.name(r.npc)) + " &e" + r.level.starText() + "&7");
            }
            tell(n, "&7NPC boats race with you tonight, for fun: " + String.join(", ", names)
                    + ". &7They never get points or prizes.");
        }
    }

    /** The course record holder's checkpoint times on this layout, or {@code null}. */
    private long[] recordSplits(TimeTrials trials, Course base) {
        if (trials == null) {
            return null;
        }
        try {
            GamesDao.ScoreRow rec = trials.record(base.id());
            return rec == null ? null : game.games().dao().splits(rec.player(), base.id(), base.layoutHash());
        } catch (SQLException | RuntimeException e) {
            return null;
        }
    }

    private void move(CpuPlayers c, NightRunner n, World w, boolean racing) {
        long ms = racing ? Math.max(0, (Bukkit.getCurrentTick() - n.goTick()) * 50) : 0;
        for (Racer r : racers.values()) {
            String key = "night:" + r.npc.key();
            GhostPath.Pose p = racing ? r.run.at(ms) : null;
            Location at = p == null ? new Location(w, r.spot.x(), r.spot.y(), r.spot.z(), r.spot.yaw(), 0f)
                    : new Location(w, p.x(), p.y(), p.z(), p.yaw(), p.pitch());
            boolean boat = n.track().base().kind() == TrialKind.BOAT;
            if (p != null && r.onLine) {
                at = NpcBodies.onGround(at); // a checkpoint line: on the ice or the water under it
            }
            bodies.place(key, r.npc, r.level, at, Pose.STANDING, boat, p != null && r.onLine);
            if (p != null && p.done() && !r.done) {
                r.done = true;
                tell(n, "&7" + CpuRoster.tagged(c.name(r.npc)) + " crossed the line &f"
                        + EventCopy.time(r.run.finishMs()) + " &7(an NPC: no points)");
            }
        }
    }

    /** The race is over: the NPCs' times, told once, never with a place. */
    private void results(NightRunner n) {
        told = true;
        List<String> parts = new ArrayList<>();
        for (Racer r : racers.values()) {
            String name = CpuRoster.tagged(npcs == null ? r.npc.name() : npcs.name(r.npc));
            parts.add(r.done ? name + " &f" + EventCopy.time(r.run.finishMs()) + "&7" : name + " - still racing");
        }
        tell(n, "&7NPC racers, just for fun: " + String.join(" &8| &7", parts));
        downAll();
    }

    private static void tell(NightRunner n, String line) {
        for (NightRunner.Racer r : n.joined()) {
            Player p = Bukkit.getPlayer(r.id());
            if (p != null) {
                p.sendMessage(Text.of(line));
            }
        }
    }

    private void downAll() {
        if (bodies != null) {
            for (Racer r : racers.values()) {
                bodies.remove("night:" + r.npc.key());
            }
        }
        racers.clear();
    }

    @Override
    public void clear() {
        downAll();
        if (bodies != null) {
            bodies.clear();
        }
        if (npcs != null && night != null) {
            for (CpuPlayer p : tonight) {
                npcs.release(p, "night:" + night.plan().id());
            }
        }
        tonight.clear();
        night = null;
        race = -1;
        told = false;
    }

    @Override
    public String statusLine() {
        return "Race Night: " + why;
    }
}
