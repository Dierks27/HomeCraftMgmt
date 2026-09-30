package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.clubhouse.ClubBoard;
import com.dierks.homecraft.games.clubhouse.ClubDoor;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseText;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Race Night's side of the Clubhouse (CLUBHOUSE-SPEC §2, §3; WP-CH), for {@link LivePorts} and the
 * Race Night screen: whether a racer waits there, whether the Clubhouse takes the night's racers when
 * they are done, the night's standings on its board with the top three on the podium, and the "Wait
 * in the Clubhouse" offer. Nothing else about the night's timeline changes: between races the
 * viewing stand stays as it is. With the Clubhouse off or not built, every answer here is "no".
 */
public final class ClubNight {

    private ClubNight() {
    }

    /** Whether the racer is in the Clubhouse now (seated from there when the night seats them). */
    static boolean waiting(GamesService games, UUID racer) {
        ClubDoor club = Clubhouse.door(games);
        return club != null && club.seatable(racer);
    }

    /**
     * Whether the Clubhouse takes the night's racers when done: open, {@code race_night_after}, the
     * track's world, and not in the restart hold.
     */
    static boolean takes(GamesService games, String trackWorld) {
        return takes(Clubhouse.door(games), trackWorld);
    }

    /**
     * {@link #takes(GamesService, String)} through {@code club} ({@code null}: none). Never during the
     * restart hold ({@link ClubDoor#closingForRestart}): a night that ends in it (a race that ran long)
     * sends its racers home with its own home line, never "Everyone to the Clubhouse!" to a Clubhouse
     * that would have them there across the restart.
     */
    public static boolean takes(ClubDoor club, String trackWorld) {
        return club != null && club.nightAfter() && !club.closingForRestart() && trackWorld != null
                && trackWorld.equalsIgnoreCase(club.world());
    }

    /** The night's standings on the board ("1. Sam 28 pts"), and its top three on the podium, in its own order. */
    static void results(GamesService games, NightRunner night) {
        ClubDoor club = Clubhouse.door(games);
        if (club == null) {
            return;
        }
        ClubBoard.Sheet sheet = sheet(night);
        club.result(sheet, null);
        club.podium(podium(night.standings()));
    }

    /** The board for a night: its standings by the night's own order ("1. Sam 28 pts"). */
    public static ClubBoard.Sheet sheet(NightRunner night) {
        List<String> rows = new ArrayList<>();
        for (NightStandings.Ranked s : night.standings()) {
            NightRunner.Racer r = night.racer(s.player());
            rows.add(ClubBoard.pointsRow(s.place(), r == null ? null : r.name(), s.points()));
        }
        if (rows.isEmpty()) {
            rows.add("&7Nobody raced tonight.");
        }
        return new ClubBoard.Sheet("&6&lRace Night: &f" + night.track().name(), rows, false,
                "night:" + night.plan().id());
    }

    /**
     * The podium's three: the first three of the night's standings, in the night's own order (its
     * tie-breaks decide, never a second ranking), and only racers who scored.
     */
    public static List<UUID> podium(List<NightStandings.Ranked> standings) {
        List<UUID> out = new ArrayList<>();
        for (NightStandings.Ranked s : standings) {
            if (out.size() >= 3) {
                break;
            }
            if (s.points() > 0) {
                out.add(s.player());
            }
        }
        return out;
    }

    /**
     * Race Night going on now, read-only, for the Clubhouse's live board and its watchers: the warm-up,
     * the grid, a race (its live places) and the breaks (the points so far).
     */
    public static List<com.dierks.homecraft.games.clubhouse.LiveRace> live(GamesService games) {
        Game g = games == null ? null : games.game(RaceNight.SPEC.id());
        NightRunner n = g instanceof RaceNight r && games.enabled(r) ? r.night() : null;
        if (n == null || !n.phase().running()) {
            return List.of();
        }
        List<String> rows = new ArrayList<>();
        List<String> names = new ArrayList<>();
        java.util.Set<UUID> racers = new java.util.LinkedHashSet<>();
        for (NightRunner.Racer r : n.joined()) {
            racers.add(r.id());
        }
        if (n.phase() == EventMachine.Phase.RACING) {
            int targets = Math.max(1, n.targets());
            int place = 0;
            for (LivePlaces.Row row : n.live()) {
                NightRunner.Racer r = n.racer(row.player());
                String name = r == null ? null : r.name();
                names.add(name);
                place++;
                String where = switch (row.state()) {
                    case FINISHED -> "finished";
                    case OUT -> "out";
                    default -> n.laps() > 1 ? "lap " + LivePlaces.lap(row.reached(), targets, n.laps()) + "/" + n.laps()
                            : "checkpoint " + Math.min(row.reached(), targets) + "/" + targets;
                };
                rows.add(ClubBoard.liveRow(place, name, where, null));
            }
        } else {
            for (NightStandings.Ranked s : n.standings()) {
                NightRunner.Racer r = n.racer(s.player());
                names.add(r == null ? null : r.name());
                rows.add(ClubBoard.pointsRow(s.place(), r == null ? null : r.name(), s.points()));
            }
        }
        com.dierks.homecraft.games.trial.Course base = n.track().base();
        com.dierks.homecraft.games.gen.api.Box half = base.gen() == null ? null : games.generated().half(base.gen());
        return List.of(new com.dierks.homecraft.games.clubhouse.LiveRace("night:" + n.plan().id(), "&6Race Night: &f"
                + n.track().name() + " &7- race " + Math.max(1, n.race()) + " of " + n.plan().races(), base.world(),
                com.dierks.homecraft.games.clubhouse.WatchArea.forCourse(base, half), racers, rows,
                com.dierks.homecraft.games.clubhouse.LiveRace.positions(names, 5), takes(games, base.world())));
    }

    /** After joining: "[Wait in the Clubhouse]", while the Clubhouse is open in the track's world. */
    static void offer(GamesService games, Player p) {
        if (p == null || !offered(games)) {
            return;
        }
        Component c = Text.of(ClubhouseText.WAIT_OFFER);
        if (!Bedrock.is(p)) {
            c = c.clickEvent(ClickEvent.runCommand("/hcm play clubhouse"))
                    .hoverEvent(HoverEvent.showText(Text.of("&7" + ClubhouseText.WAIT_HOVER)));
        }
        p.sendMessage(c);
    }

    /** Whether "Wait in the Clubhouse" is offered: a night is on, and the Clubhouse takes its racers. */
    public static boolean offered(GamesService games) {
        Game g = games == null ? null : games.game(RaceNight.SPEC.id());
        NightRunner n = g instanceof RaceNight r ? r.night() : null;
        if (n == null || n.phase().over()) {
            return false;
        }
        ClubDoor club = Clubhouse.door(games);
        return club != null && n.track().base().world().equalsIgnoreCase(club.world());
    }
}
