package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.clubhouse.ClubBoard;
import com.dierks.homecraft.games.clubhouse.ClubDoor;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseText;
import com.dierks.homecraft.gui.games.golf.GolfGroupCardMenu;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Golf together's side of the Clubhouse (CLUBHOUSE-SPEC §3; WP-CH): when a group's round ends, the
 * group goes to the Clubhouse instead of home, and its board shows the group ranking. One line in
 * {@link GolfRounds}'s group end ({@code Port.home}); a round alone still goes home as before, and
 * with the Clubhouse off or not built (or {@code golf_after} off) so does the group, and while the
 * Clubhouse is closing for a restart ({@link ClubDoor#closingForRestart}, the hold's last minute or
 * so: nobody may be in the Clubhouse across a restart).
 */
public final class ClubGolf {

    private ClubGolf() {
    }

    /**
     * The group's round is over: {@code p} goes to the Clubhouse (their round was recorded at their own
     * last hole already), and the board shows the ranking. False when the Clubhouse can't take them:
     * the caller sends them home as before.
     */
    static boolean toClubhouse(MiniGolf golf, GolfRounds rounds, Player p, LiveRound round, GolfGroup.Card card) {
        return take(Clubhouse.door(golf.games()), p, p.getWorld().getName(), () -> rounds.forget(round), card,
                q -> new GolfGroupCardMenu(golf.plugin(), golf, q, card, null).open(q));
    }

    /**
     * {@link #toClubhouse} with the server's parts passed in: the Clubhouse's door ({@code null} while
     * it is off), the world the player is in, how to forget their finished round, and the results
     * screen. The board gets the group ranking. While the Clubhouse is closing for a restart nothing
     * is touched and they read why they go home.
     */
    static boolean take(ClubDoor club, Player p, String world, Runnable forget, GolfGroup.Card card,
                        java.util.function.Consumer<Player> opener) {
        if (club == null || !club.golfAfter() || world == null || !world.equalsIgnoreCase(club.world())) {
            return false;
        }
        if (club.closingForRestart()) {
            p.sendMessage(com.dierks.homecraft.util.Text.of(ClubhouseText.HOLD_NOT_TAKEN));
            return false; // home with the group's card, as with no Clubhouse
        }
        forget.run();
        club.result(sheet(card), opener);
        return club.takeIn(p, ClubVisits.Kind.GOLF, ClubhouseText.BACK_GOLF);
    }

    /**
     * Whether a golf party member is waiting in the Clubhouse, where "Play again together" takes them
     * straight to hole 1 (their session handed over in place): in it, not a spectator, and the course in
     * the Clubhouse's world (a session teleport stays in its world).
     */
    public static boolean waiting(ClubDoor club, UUID player, String courseWorld) {
        return club != null && player != null && club.seatable(player) && courseWorld != null
                && courseWorld.equalsIgnoreCase(club.world());
    }

    /** {@link #waiting(ClubDoor, UUID, String)} with the Clubhouse's own door. */
    public static boolean waiting(com.dierks.homecraft.games.GamesService games, UUID player, String courseWorld) {
        try {
            return waiting(Clubhouse.door(games), player, courseWorld);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * "Play again together" for a member waiting in the Clubhouse (the checklist pass: a group that ended
     * there could never play again): their Clubhouse session is handed to golf in place (their things
     * stay saved once; anything that arrived meanwhile is banked), the gate asked again, then the
     * session's own teleport to hole 1. On any failure they are handed back to the Clubhouse.
     *
     * @return {@code null} when they aren't waiting in the Clubhouse (the usual entry applies), else
     *         whether they are on their way to hole 1
     */
    static Boolean fromClubhouse(ClubDoor club, Player p, com.dierks.homecraft.games.Game golf, String courseId,
                                 org.bukkit.Location tee, java.util.function.BiPredicate<Player, org.bukkit.Location> teleport,
                                 java.util.function.Function<Player, com.dierks.homecraft.games.Refusal> gate,
                                 java.util.function.BiConsumer<Player, com.dierks.homecraft.games.Refusal> tell) {
        String world = tee == null || tee.getWorld() == null ? null : tee.getWorld().getName();
        if (p == null || !waiting(club, p.getUniqueId(), world)) {
            return null;
        }
        if (!club.handOut(p, golf, courseId)) {
            tell.accept(p, com.dierks.homecraft.games.Refusal.of("Couldn't take you from the Clubhouse right now."));
            return false;
        }
        com.dierks.homecraft.games.Refusal why = gate.apply(p);
        if (why != null || !teleport.test(p, tee)) {
            club.handBack(p, ClubVisits.Kind.GOLF);
            tell.accept(p, why != null ? why : com.dierks.homecraft.games.Refusal.of("Couldn't take you to hole 1 right now."));
            return false;
        }
        return true;
    }

    /**
     * Golf together's groups playing now, read-only, for the Clubhouse's live board and its watchers:
     * the shared card's ranking so far and the hole being played.
     */
    public static List<com.dierks.homecraft.games.clubhouse.LiveRace> live(com.dierks.homecraft.games.GamesService games) {
        List<com.dierks.homecraft.games.clubhouse.LiveRace> out = new ArrayList<>();
        if (games == null || !(games.game(MiniGolf.SPEC.id()) instanceof MiniGolf golf) || !games.enabled(golf)) {
            return out;
        }
        for (java.util.Map.Entry<GolfGroup, GolfGroup.Card> e : golf.rounds().liveGroups().entrySet()) {
            GolfGroup g = e.getKey();
            GolfGroup.Card card = e.getValue();
            GolfCourse c = golf.course(g.courseId());
            if (c == null || c.holes().isEmpty()) {
                continue;
            }
            com.dierks.homecraft.games.gen.api.Box half = c.gen() == null ? null : games.generated().half(c.gen());
            List<String> rows = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (GolfGroup.Standing s : GolfGroup.ranking(card.rows(), card.pars().size())) {
                names.add(s.name());
                rows.add(s.place() > 0 ? ClubBoard.strokesRow(s.place(), s.name(), s.total(), s.vsPar())
                        : ClubBoard.noTimeRow(s.name(), "left"));
            }
            out.add(new com.dierks.homecraft.games.clubhouse.LiveRace("golf:" + g.id(), "&dGolf together: &f"
                    + card.courseName() + " &7- hole " + (card.hole() + 1) + " of " + card.pars().size(), c.world(),
                    com.dierks.homecraft.games.clubhouse.WatchArea.forGolf(c, half), new java.util.LinkedHashSet<>(g.active()),
                    rows, com.dierks.homecraft.games.clubhouse.LiveRace.positions(names, 4), true));
        }
        return out;
    }

    /** The group ranking on the board: "1. Sam 24 strokes (-2)". */
    static ClubBoard.Sheet sheet(GolfGroup.Card card) {
        List<String> rows = new ArrayList<>();
        for (GolfGroup.Standing s : GolfGroup.ranking(card.rows(), card.pars().size())) {
            rows.add(s.place() > 0 ? ClubBoard.strokesRow(s.place(), s.name(), s.total(), s.vsPar())
                    : ClubBoard.noTimeRow(s.name(), "left"));
        }
        return new ClubBoard.Sheet("&d&lGolf together: &f" + card.courseName(), rows, false,
                "golf:" + card.courseId() + ":" + rows.hashCode());
    }
}
