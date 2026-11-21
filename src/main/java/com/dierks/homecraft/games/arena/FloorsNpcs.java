package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.ArenaRound;
import com.dierks.homecraft.games.arena.rules.Cell;
import com.dierks.homecraft.games.arena.rules.CellState;
import com.dierks.homecraft.games.arena.rules.FloorLayout;
import com.dierks.homecraft.games.arena.rules.FloorRules;
import com.dierks.homecraft.games.cpu.CpuLevel;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.games.cpu.NpcBodies;
import com.dierks.homecraft.games.cpu.NpcCrowd;
import com.dierks.homecraft.games.cpu.WorldNpcs;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * NPC players on Falling Floors (stage 2): company in the gallery for a player who waits there alone, and NPCs on the
 * floors in their rounds.
 *
 * <p><b>Joining.</b> A player alone in the gallery with NPCs on gets an NPC after {@code games.cpu.join_after_seconds},
 * then one more now and then, up to {@link NpcCrowd#MAX_IN_GAME}; they stand in the gallery and play every round that
 * player plays (their "Play solo" round, or a round with friends who all have NPCs on). Someone with NPCs off in the
 * gallery sends them away. In a round an NPC takes only a spawn no real player has, and none when there is none.
 *
 * <p><b>Playing.</b> Each NPC steps from cell to cell by the floors' rules ({@link FloorsNpcSim}): onto cells that
 * aren't falling yet, faster and smarter the harder it plays; an easy one sometimes waits too long and drops a floor.
 * Out under the last floor, it goes to the gallery as a player who is out does. Everyone in the round reads how long
 * each NPC lasted ("Ozzie ⚙ NPC lasted 42 s"), and who was still standing at the end.
 *
 * <p><b>Nothing real.</b> The game never knows an NPC is there: never in the round's members, starters, places,
 * results, rewards or wins board, and no NPC ever writes a block (its own steps are kept in {@link FloorsNpcSim}, and
 * shown only as a little red dust). Its mannequin is not collidable and immovable, so it never pushes, blocks or
 * holds up a real player. A real player's round is exactly the round they would have played without NPCs.
 */
public final class FloorsNpcs implements WorldNpcs {

    static final String GAME = "falling_floors";
    static final String WHERE = "floors";

    private final FallingFloors game;
    private final SplittableRandom rng = new SplittableRandom();
    private final NpcCrowd.Alone clock = new NpcCrowd.Alone();
    /** The NPCs keeping the gallery company, in the order they came. */
    private final List<CpuPlayer> company = new ArrayList<>();
    private final Map<UUID, CpuLevel> levels = new LinkedHashMap<>();
    private FloorsNpcSim sim;
    private int simRound = -1;
    private boolean told;
    private NpcBodies bodies;
    private CpuPlayers npcs;
    private long ticks;

    FloorsNpcs(FallingFloors game) {
        this.game = game;
    }

    /**
     * The spawns an NPC may take (pure): every spawn no real starter has, in order; never more than {@code n}.
     */
    static List<Integer> freeSpawns(int spawnCount, Set<Integer> taken, int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < spawnCount && out.size() < n; i++) {
            if (!taken.contains(i)) {
                out.add(i);
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
        ArenaService svc = game.service();
        ArenaRound round = svc == null ? null : svc.round();
        World w = svc == null ? null : Bukkit.getWorld(svc.world());
        if (round == null || w == null || svc.site() == null) {
            clear();
            return;
        }
        List<UUID> reals = round.members();
        if (!NpcCrowd.welcome(reals, c::wants)) {
            if (!company.isEmpty()) {
                tell(reals, "&7The NPCs went home.");
            }
            clear();
            return;
        }
        long now = game.games().clock().nowMillis();
        boolean lobby = round.phase() == ArenaRound.Phase.LOBBY || round.phase() == ArenaRound.Phase.COUNTDOWN;
        int room = NpcCrowd.room(reals.size(), round.settings().maxPlayers());
        if (lobby && company.size() < room && clock.due(reals.size() == 1, company.size(), now, c.joinAfterMs())) {
            Set<UUID> busy = new HashSet<>(c.busy());
            List<CpuPlayer> one = NpcCrowd.pick(c.roster(), busy, 1, c.rng());
            if (!one.isEmpty() && c.claim(one.get(0), WHERE)) {
                CpuPlayer p = one.get(0);
                company.add(p);
                clock.joined(now);
                tell(reals, "&b" + CpuRoster.tagged(c.name(p)) + " &e" + p.level().starText()
                        + " &bcame to play! &7Tap Play solo and the NPCs play with you.");
            }
        }
        while (company.size() > room) {
            c.release(company.remove(company.size() - 1), WHERE); // a real player gets the place
        }
        if (round.phase().inRound()) {
            if (sim == null || simRound != round.roundNo()) {
                startRound(c, svc, round, reals);
            }
            play(c, svc, round, w);
        } else {
            if (sim != null && !told) {
                results(round.members());
            }
            sim = null;
            simRound = -1;
            gallery(c, svc, w);
        }
        if (ticks % 20 == 0) {
            bodies.views(w);
        }
    }

    /** A round started: the NPCs in company take the spawns no real player has. */
    private void startRound(CpuPlayers c, ArenaService svc, ArenaRound round, List<UUID> reals) {
        FloorLayout layout = svc.layout();
        sim = null;
        simRound = round.roundNo();
        told = false;
        if (layout == null || company.isEmpty() || round.starters().isEmpty()) {
            return;
        }
        Set<Integer> taken = new HashSet<>();
        for (UUID s : round.starters()) {
            Integer i = round.spawnOf(s);
            if (i != null) {
                taken.add(i);
            }
        }
        List<Integer> free = freeSpawns(layout.spawns().size(), taken, company.size());
        sim = new FloorsNpcSim(round.settings().fadeTicks(), rng);
        levels.clear();
        FloorsNpcSim.Floors start = whole(layout);
        for (int k = 0; k < free.size(); k++) {
            CpuPlayer p = company.get(k);
            Cell cell = layout.spawns().get(free.get(k));
            CpuLevel level = c.level(p, reals, GAME);
            levels.put(p.id(), level);
            sim.add(p.id(), level, 0, cell.x(), cell.z(), start, 0);
        }
        if (!free.isEmpty()) {
            tell(reals, "&7NPCs on the floors with you: " + names(c, free.size()) + "&7. Last one standing!");
        }
    }

    /** The floors as they stand before a round: every cell whole. */
    private static FloorsNpcSim.Floors whole(FloorLayout layout) {
        return new FloorsNpcSim.Floors() {
            @Override
            public int layers() {
                return layout.layerCount();
            }

            @Override
            public int topY(int layer) {
                return layout.topY(layer);
            }

            @Override
            public int outY() {
                return layout.outY();
            }

            @Override
            public CellState state(int layer, int x, int z) {
                return layout.isCell(layer, x, z) ? CellState.SOLID : CellState.AIR;
            }
        };
    }

    private String names(CpuPlayers c, int n) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < n && k < company.size(); k++) {
            out.add(CpuRoster.tagged(c.name(company.get(k))));
        }
        return String.join(", ", out);
    }

    private void play(CpuPlayers c, ArenaService svc, ArenaRound round, World w) {
        if (sim == null) {
            gallery(c, svc, w);
            return;
        }
        FloorRules floors = svc.floors();
        if (round.phase() == ArenaRound.Phase.PLAYING && floors != null) {
            FloorsNpcSim.Floors f = FloorsNpcSim.Floors.of(floors);
            for (FloorsNpcSim.Walker gone : sim.step(round.playTicks(), f)) {
                tell(round.members(), "&7" + CpuRoster.tagged(c.name(CpuRoster.byId(gone.id()))) + " fell out - lasted "
                        + seconds(gone.outAt()) + ".");
            }
        }
        int g = 0;
        for (FloorsNpcSim.Walker wk : sim.walkers()) {
            CpuPlayer p = CpuRoster.byId(wk.id());
            String key = "floors:" + p.key();
            if (wk.out()) {
                ArenaSite.Spot s = svc.site().gallery(g++ * 3 + 1);
                bodies.place(key, p, wk.level(), new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0f), Pose.STANDING,
                        false, false);
                continue;
            }
            Location at = new Location(w, wk.x(), wk.y(), wk.z(), wk.yaw(), 0f);
            bodies.place(key, p, wk.level(), at, Pose.STANDING, false, false);
            if (ticks % 4 == 0 && !wk.falling()) {
                w.spawnParticle(Particle.DUST, wk.x(), wk.y() + 0.05, wk.z(), 2, 0.2, 0.0, 0.2, 0.0,
                        new Particle.DustOptions(org.bukkit.Color.RED, 0.8f)); // its own steps: dust, never a block
            }
        }
        // a company NPC with no spawn this round waits in the gallery
        for (CpuPlayer p : company) {
            if (!levels.containsKey(p.id())) {
                ArenaSite.Spot s = svc.site().gallery(g++ * 3 + 1);
                bodies.place("floors:" + p.key(), p, p.level(), new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0f),
                        Pose.STANDING, false, false);
            }
        }
    }

    /** Between rounds: the NPCs stand in the gallery. */
    private void gallery(CpuPlayers c, ArenaService svc, World w) {
        int g = 0;
        for (CpuPlayer p : company) {
            ArenaSite.Spot s = svc.site().gallery(g++ * 3 + 1);
            bodies.place("floors:" + p.key(), p, p.level(), new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0f),
                    Pose.STANDING, false, false);
        }
    }

    /** The round ended: how long each NPC lasted, to everyone in it. Never a place, never a win. */
    private void results(List<UUID> reals) {
        told = true;
        List<String> parts = new ArrayList<>();
        for (FloorsNpcSim.Walker wk : sim.walkers()) {
            String name = CpuRoster.tagged(npcs.name(CpuRoster.byId(wk.id())));
            parts.add(wk.out() ? name + " lasted " + seconds(wk.outAt()) : name + " was still standing!");
        }
        if (!parts.isEmpty()) {
            tell(reals, "&7NPCs this round: " + String.join(" &8| &7", parts));
        }
    }

    private static String seconds(long playTicks) {
        return Math.max(0, playTicks / 20) + " s";
    }

    private static void tell(List<UUID> to, String line) {
        for (UUID id : to) {
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
        if (npcs != null) {
            for (CpuPlayer p : company) {
                npcs.release(p, WHERE);
            }
        }
        company.clear();
        levels.clear();
        clock.reset();
        sim = null;
        simRound = -1;
    }

    @Override
    public String statusLine() {
        return "Falling Floors: " + company.size() + " NPC(s) in the gallery" + (sim == null ? ""
                : ", " + sim.walkers().size() + " on the floors");
    }
}
