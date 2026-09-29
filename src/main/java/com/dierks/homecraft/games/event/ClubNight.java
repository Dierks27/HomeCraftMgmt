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

    /** Whether the Clubhouse takes the night's racers when done: open, {@code race_night_after}, the track's world. */
    static boolean takes(GamesService games, String trackWorld) {
        ClubDoor club = Clubhouse.door(games);
        return club != null && club.nightAfter() && trackWorld != null && trackWorld.equalsIgnoreCase(club.world());
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
