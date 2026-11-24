package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.cpu.CpuChoice;
import com.dierks.homecraft.games.cpu.CpuLevel;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.games.cpu.NpcBodies;
import com.dierks.homecraft.games.cpu.NpcCrowd;
import com.dierks.homecraft.games.cpu.WorldNpcs;
import com.dierks.homecraft.games.lava.rules.LavaMode;
import com.dierks.homecraft.games.lava.rules.LavaRound;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * NPC players in The Floor Is Lava (stage 2): company for a player who plays Group or Challenge alone, and, in Little
 * (Lilah's own mode), at most one friendly easy NPC, and only for a player who turned NPCs on and wants them easy.
 *
 * <p><b>Joining</b> ({@link #room}). Group and Challenge: a player alone in the room with NPCs on gets an NPC after
 * {@code games.cpu.join_after_seconds}, then one more now and then, up to {@link NpcCrowd#MAX_IN_GAME}, in places no
 * real player has. In Group it plays at once (Group takes players at any time); in Challenge, whose players are
 * gathered first and nobody joins later, it waits in the gallery and plays from the next round. Little: one NPC at
 * most, at easy, never competing (Little can't be lost), and only while every player in the room has NPCs on and
 * set to easy; otherwise Little stays NPC-free. Anyone in the room with NPCs off sends them all away.
 *
 * <p><b>Playing</b> ({@link LavaNpcSim}): they walk the room by its own rule (0.57.0: medium and hard ones hop from
 * piece to piece too), go after stars of their own (pretend ones, a sparkle where they get to: never the round's),
 * climb for dry ground when the warning starts (easy ones later and slower), and touch the lava when they don't make
 * it: put back on dry ground in Group and Little (costing nobody a star), out to the gallery in Challenge, where an
 * NPC that ends a round without its stars is out too. When the furniture moves they ride along or are lifted, as a
 * player is ({@link LavaShift}). Everyone in the round reads how an NPC did ("Ozzie ⚙ NPC lasted 5 rounds").
 *
 * <p><b>Nothing real.</b> The round never knows an NPC is there: never a member, never touching its count, its stars,
 * its lava or its scores ({@link LavaScores}), and no NPC writes a block. A real player's round is exactly what it
 * would be without NPCs.
 */
public final class LavaNpcs implements WorldNpcs {

    static final String GAME = "floor_is_lava";
    static final String WHERE = "lava";

    private final FloorIsLava game;
    private final SplittableRandom rng = new SplittableRandom();
    private final NpcCrowd.Alone clock = new NpcCrowd.Alone();
    /** The NPCs in the room's company, in the order they came. */
    private final List<CpuPlayer> company = new ArrayList<>();
    private LavaNpcSim sim;
    private LavaRound simRound;
    private LavaSite simSite;
    private boolean told;
    private NpcBodies bodies;
    private CpuPlayers npcs;
    private long ticks;

    LavaNpcs(FloorIsLava game) {
        this.game = game;
    }

    /**
     * How many NPCs may be in a round of {@code mode} with these real players (pure): none unless every one wants
     * NPCs; in Little at most one, and only when every one wants them easy; otherwise the places no real player has,
     * up to {@link NpcCrowd#MAX_IN_GAME}.
     */
    static int room(LavaMode mode, List<UUID> reals, Predicate<UUID> wants, Function<UUID, CpuChoice.Pref> pref,
                    int maxPlayers) {
        if (!NpcCrowd.welcome(reals, wants)) {
            return 0;
        }
        if (mode == LavaMode.LITTLE) {
            for (UUID r : reals) {
                if (pref.apply(r) != CpuChoice.Pref.EASY) {
                    return 0;
                }
            }
            return Math.min(1, NpcCrowd.room(reals.size(), maxPlayers));
        }
        return NpcCrowd.room(reals.size(), maxPlayers);
    }

    @Override
    public void tick(CpuPlayers c, long tick) {
        npcs = c;
        ticks++;
        if (bodies == null) {
            bodies = new NpcBodies(c);
        }
        LavaService svc = game.service();
        LavaRound round = svc == null ? null : svc.round();
        LavaSite site = svc == null ? null : svc.site();
        World w = svc == null ? null : Bukkit.getWorld(svc.world());
        if (round == null || site == null || w == null || round.over()) {
            if (sim != null && !told && round != null && round == simRound) {
                results(round);
            }
            if (round == null || round.members().isEmpty()) {
                clear();
            } else {
                dropSim();
            }
            return;
        }
        List<UUID> reals = round.members();
        int room = room(round.mode(), reals, c::wants, id -> c.choice(id).level(), round.rules().maxPlayers());
        if (room == 0 && !company.isEmpty()) {
            tell(reals, "&7The NPCs went home.");
        }
        while (company.size() > room) {
            CpuPlayer gone = company.remove(company.size() - 1);
            c.release(gone, WHERE);
            if (sim != null) {
                bodies.remove(key(gone));
            }
        }
        if (round != simRound) {
            newSim(round, site);
        } else if (site != simSite) {
            moveSim(site, svc.lavaNow());
        }
        long now = game.games().clock().nowMillis();
        boolean alone = reals.size() == 1;
        if (company.size() < room && clock.due(alone, company.size(), now, c.joinAfterMs())) {
            List<CpuPlayer> one = NpcCrowd.pick(c.roster(), new HashSet<>(c.busy()), 1, c.rng());
            if (!one.isEmpty() && c.claim(one.get(0), WHERE)) {
                CpuPlayer p = one.get(0);
                company.add(p);
                clock.joined(now);
                boolean playsNow = round.mode() != LavaMode.CHALLENGE || round.phase() == LavaRound.Phase.GATHER;
                tell(reals, "&b" + CpuRoster.tagged(c.name(p)) + " &bcame to play!"
                        + (playsNow ? "" : " &7They join the next round."));
                if (playsNow) {
                    enter(c, round, p, reals);
                }
            }
        }
        play(c, round, site, w, reals);
        if (ticks % 20 == 0) {
            bodies.views(w);
        }
    }

    private void newSim(LavaRound round, LavaSite site) {
        dropSim();
        simRound = round;
        simSite = site;
        told = false;
        sim = new LavaNpcSim(LavaNpcSim.Room.of(site), rng);
        if (npcs != null) {
            for (CpuPlayer p : company) {
                enter(npcs, round, p, round.members());
            }
        }
    }

    /**
     * 0.57.0: the furniture moved (or a piece sank, or a cushion went bouncy): every NPC where a player would be now,
     * riding its piece or lifted onto one, else where it stood.
     */
    private void moveSim(LavaSite to, int level) {
        LavaSite from = simSite;
        simSite = to;
        if (sim == null || from == null) {
            return;
        }
        sim.moveRoom(LavaNpcSim.Room.of(to), w -> {
            LavaSite.Spot s = LavaShift.carry(from, to, w.x(), w.y(), w.z(), level);
            return s == null ? to.standUnder(w.x(), w.y(), w.z()) : to.standUnder(s.x(), s.y(), s.z());
        });
    }

    /** {@code p} walks into the room: on the floor, or the nearest dry stand while the lava is up. */
    private void enter(CpuPlayers c, LavaRound round, CpuPlayer p, List<UUID> reals) {
        if (sim == null) {
            return;
        }
        for (LavaNpcSim.Walker wk : sim.walkers()) {
            if (wk.id().equals(p.id())) {
                return;
            }
        }
        LavaSite.Stand at = sim.floorStand();
        if (at == null) {
            return;
        }
        CpuLevel level = round.mode() == LavaMode.LITTLE ? CpuLevel.EASY : c.level(p, reals, GAME);
        if (round.level() > 0 && simSite != null) {
            LavaSite.Spot dry = simSite.nearestSafe(round.level(), at.x() + 0.5, at.y(), at.z() + 0.5);
            if (dry != null) {
                at = new LavaSite.Stand((int) Math.floor(dry.x()), (int) Math.floor(dry.z()), dry.y());
            }
        }
        sim.add(p.id(), level, at, ticks);
    }

    private void play(CpuPlayers c, LavaRound round, LavaSite site, World w, List<UUID> reals) {
        if (sim == null) {
            return;
        }
        if (round.phase() != LavaRound.Phase.OVER) {
            for (LavaNpcSim.Touched t : sim.step(ticks, round.phase(), round.level(), round.nextLevel(), round.mode(),
                    round.round(), round.need())) {
                String name = CpuRoster.tagged(c.name(CpuRoster.byId(t.npc())));
                tell(reals, t.starless() ? "&7" + name + " didn't get a star - out! Lasted " + rounds(t.lasted()) + "."
                        : t.out() ? "&7" + name + " touched the lava - out! Lasted " + rounds(t.lasted()) + "."
                        : "&7" + name + " touched the lava - back to a dry spot!");
            }
            for (double[] at : sim.sparkled()) { // a pretend star: a sparkle, never one of the round's
                try {
                    w.spawnParticle(org.bukkit.Particle.WAX_ON, at[0], at[1], at[2], 8, 0.3, 0.3, 0.3, 0.0);
                } catch (RuntimeException | LinkageError ignored) {
                    // a sparkle is decoration
                }
            }
        }
        Set<UUID> placed = new HashSet<>();
        int g = 0;
        for (LavaNpcSim.Walker wk : sim.walkers()) {
            CpuPlayer p = CpuRoster.byId(wk.id());
            placed.add(p.id());
            Location at;
            if (wk.out()) {
                LavaSite.Spot s = site.gallery(g++);
                at = new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0f);
            } else {
                at = new Location(w, wk.x(), wk.y(), wk.z(), wk.yaw(), 0f);
            }
            bodies.place(key(p), p, wk.level(), at, Pose.STANDING, false, false);
        }
        for (CpuPlayer p : company) { // in company, waiting for the next Challenge
            if (!placed.contains(p.id())) {
                LavaSite.Spot s = site.gallery(g++);
                bodies.place(key(p), p, p.level(), new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0f), Pose.STANDING,
                        false, false);
            }
        }
    }

    /** A round ended: in Challenge, how long each NPC lasted, to everyone in it. Never a place, never a score. */
    private void results(LavaRound round) {
        told = true;
        if (round.mode() != LavaMode.CHALLENGE || sim.walkers().isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        for (LavaNpcSim.Walker wk : sim.walkers()) {
            String name = CpuRoster.tagged(npcs.name(CpuRoster.byId(wk.id())));
            parts.add(wk.out() ? name + " lasted " + rounds(wk.lasted()) : name + " was still standing!");
        }
        tell(round.members(), "&7NPCs this round: " + String.join(" &8| &7", parts));
    }

    private static String rounds(int n) {
        return n + (n == 1 ? " round" : " rounds");
    }

    private static String key(CpuPlayer p) {
        return "lava:" + p.key();
    }

    private void dropSim() {
        if (sim != null && bodies != null) {
            for (LavaNpcSim.Walker wk : sim.walkers()) {
                CpuPlayer p = CpuRoster.byId(wk.id());
                if (!company.contains(p)) {
                    bodies.remove(key(p));
                }
            }
        }
        sim = null;
        simRound = null;
        simSite = null;
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
        clock.reset();
        sim = null;
        simRound = null;
        simSite = null;
        told = false;
    }

    @Override
    public String statusLine() {
        return "The Floor Is Lava: " + company.size() + " NPC(s) in the room";
    }

    /** For the tests. */
    Map<String, Integer> counts() {
        return Map.of("company", company.size(), "walkers", sim == null ? 0 : sim.walkers().size());
    }
}
