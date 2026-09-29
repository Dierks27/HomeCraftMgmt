package com.dierks.homecraft.games;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Nobody pushes anybody (the orchestrator's "player collisions" decision): one main-scoreboard team,
 * {@value #TEAM}, whose collision rule is NEVER. A game puts a player on it while they share a
 * small space (Falling Floors' floors, a Dropper's shafts, a race's start and stand) and takes them
 * off when that ends.
 *
 * <p><b>Why a team.</b> {@code Player.setCollidable(false)} doesn't stop one player pushing
 * another: the client predicts player collisions itself, and only a team's COLLISION_RULE reaches
 * it (Paper's {@code LivingEntity#setCollidable} javadoc). Games keep their {@code setCollidable}
 * calls too, since those stop mobs pushing.
 *
 * <p><b>Other plugins' teams.</b> A player is on at most one team of a scoreboard, so joining this
 * one takes them off any other main-scoreboard team (another plugin's nametag colour, say).
 * {@link #on} remembers that team and {@link #off} puts them back on it, if it still exists. The
 * remembered teams live only in memory: the team itself is saved with the world, so after a crash
 * its members would stay on it with nothing to restore. {@link #clearAll} (the games' start)
 * empties it and says how many it found, so the caller can WARN that their earlier teams are lost.
 *
 * <p>One holder at a time: a player is in one world game at a time, so {@code on} is idempotent
 * (the team remembered the first time) and {@code off} restores once; a second {@code off} does
 * nothing. Nothing here throws: a scoreboard that isn't there (no server, a test) is a no-op.
 */
public final class NoPush {

    /** The team's name on the main scoreboard. */
    public static final String TEAM = "hcm_nopush";

    /** The main scoreboard's teams, as this needs them: the live one is Bukkit's, a test's is a map. */
    public interface Board {

        /** The team {@code entry} is on, or {@code null}. */
        String teamOf(String entry);

        /** Make sure {@code team} exists and nobody on it collides (COLLISION_RULE NEVER). */
        void ensureNoCollision(String team);

        /** Whether {@code team} exists. */
        boolean exists(String team);

        /** Put {@code entry} on {@code team} (off any other team it was on). */
        void add(String team, String entry);

        /** Take {@code entry} off {@code team}. */
        void remove(String team, String entry);

        /** Everyone on {@code team} (empty when it doesn't exist). */
        Set<String> entries(String team);
    }

    private final Supplier<Board> board;
    /** Who is on the team now → the team they were on before ({@code null}: none), by player id. */
    private final Map<UUID, Held> held = new HashMap<>();

    private record Held(String entry, String before) {
    }

    /** Over the main scoreboard, read when it is used (the server may not have one yet). */
    public static NoPush live() {
        return new NoPush(NoPush::mainBoard);
    }

    /** Over {@code board} ({@code null} from it: no scoreboard, a no-op). */
    public NoPush(Supplier<Board> board) {
        this.board = board;
    }

    // ---- the calls -----------------------------------------------------------------------------

    /** The player can't push or be pushed by other players until {@link #off}. False when it couldn't be done. */
    public boolean on(Player player) {
        return player != null && on(player.getUniqueId(), player.getName());
    }

    /** {@link #on(Player)} by id and scoreboard entry (the player's name). */
    public boolean on(UUID id, String entry) {
        if (id == null || entry == null) {
            return false;
        }
        Board b = board();
        if (b == null) {
            return false;
        }
        try {
            if (held.containsKey(id)) {
                return true; // on already: the team they came from is remembered from the first time
            }
            String before = b.teamOf(entry);
            b.ensureNoCollision(TEAM);
            b.add(TEAM, entry);
            held.put(id, new Held(entry, TEAM.equals(before) ? null : before));
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Collisions back as they were: off the team, and back on the team the player came from. */
    public void off(Player player) {
        if (player != null) {
            off(player.getUniqueId(), player.getName());
        }
    }

    /** {@link #off(Player)} by id and scoreboard entry. */
    public void off(UUID id, String entry) {
        if (id == null) {
            return;
        }
        Held h = held.remove(id);
        Board b = board();
        if (b == null) {
            return;
        }
        String name = h != null ? h.entry() : entry;
        if (name == null) {
            return;
        }
        try {
            if (TEAM.equals(b.teamOf(name))) {
                b.remove(TEAM, name);
            }
            if (h != null && h.before() != null && b.exists(h.before()) && b.teamOf(name) == null) {
                b.add(h.before(), name); // their own team back (another plugin's nametags, say)
            }
        } catch (RuntimeException | LinkageError e) {
            // nothing more to do: a scoreboard that fails can't be put back
        }
    }

    /** Whether the player is on the team through this helper now. */
    public boolean isOn(UUID id) {
        return held.containsKey(id);
    }

    /**
     * Everyone this run put on the team goes back to where they were (the games are stopping).
     * Returns how many.
     */
    public int offAll() {
        int n = 0;
        for (Map.Entry<UUID, Held> e : new HashMap<>(held).entrySet()) {
            off(e.getKey(), e.getValue().entry());
            n++;
        }
        return n;
    }

    /**
     * The plugin is starting: the team is emptied (it survives a crash with the world's scoreboard)
     * and nothing is remembered. Returns how many entries were still on it: their earlier teams were
     * in memory only, so they are lost, and the caller WARNs.
     */
    public int clearAll() {
        held.clear();
        Board b = board();
        if (b == null) {
            return 0;
        }
        try {
            if (!b.exists(TEAM)) {
                return 0;
            }
            Set<String> left = new HashSet<>(b.entries(TEAM));
            for (String entry : left) {
                b.remove(TEAM, entry);
            }
            b.ensureNoCollision(TEAM);
            return left.size();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    private Board board() {
        try {
            return board.get();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    // ---- the live board --------------------------------------------------------------------------

    /** Bukkit's main scoreboard, or {@code null} while there is none (no server yet, a test). */
    private static Board mainBoard() {
        if (Bukkit.getServer() == null) {
            return null;
        }
        ScoreboardManager m = Bukkit.getScoreboardManager();
        Scoreboard s = m == null ? null : m.getMainScoreboard();
        return s == null ? null : new Live(s);
    }

    /** {@link Board} over a real scoreboard. */
    private record Live(Scoreboard s) implements Board {

        @Override
        public String teamOf(String entry) {
            Team t = s.getEntryTeam(entry);
            return t == null ? null : t.getName();
        }

        @Override
        public void ensureNoCollision(String team) {
            Team t = s.getTeam(team);
            if (t == null) {
                t = s.registerNewTeam(team);
            }
            if (t.getOption(Team.Option.COLLISION_RULE) != Team.OptionStatus.NEVER) {
                t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            }
        }

        @Override
        public boolean exists(String team) {
            return s.getTeam(team) != null;
        }

        @Override
        public void add(String team, String entry) {
            Team t = s.getTeam(team);
            if (t != null) {
                t.addEntry(entry);
            }
        }

        @Override
        public void remove(String team, String entry) {
            Team t = s.getTeam(team);
            if (t != null) {
                t.removeEntry(entry);
            }
        }

        @Override
        public Set<String> entries(String team) {
            Team t = s.getTeam(team);
            return t == null ? Set.of() : Set.copyOf(t.getEntries());
        }
    }
}
