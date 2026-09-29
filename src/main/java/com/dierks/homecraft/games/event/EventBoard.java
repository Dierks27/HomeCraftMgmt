package com.dierks.homecraft.games.event;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * What the hub's {@code @event} displays say (EVENTS-DROPPER-SPEC §A.6): a sign's four lines of at
 * most {@value #SIGN_CHARS} plain ASCII characters, and a hologram's or TV's longer screen with the
 * racers' names. Pure: the night's state comes in as a {@link View}, times are read in its zone.
 *
 * <pre>
 * Nothing set  RACE NIGHT  / No race set    / Ask an admin! /
 * Upcoming     RACE NIGHT  / Fri 7:00 PM    / in 2h 14m     / Ice Boat
 * Join window  JOIN NOW!   / /hcm play race / 3 of 8 in     / starts 7:00
 * Warm-up      RACE NIGHT  / Warm-up laps   / 5 racers      / Ice Boat
 * Racing       RACE 2 OF 3 / 1. Sam 18      / 2. Ava 16     / 3. Lee 10
 * Results      WINNER      / Sam            / 2. Ava        / 3. Lee      (for 30 minutes)
 * </pre>
 */
public final class EventBoard {

    /** A sign line's most characters. */
    public static final int SIGN_CHARS = 15;
    /** The display target an admin binds a sign, hologram or TV to. */
    public static final String TARGET = "@event";

    private EventBoard() {
    }

    /** What the board shows. */
    public enum Shows {
        NOTHING, UPCOMING, OPEN, WARMUP, RACING, RESULTS
    }

    /** One line of standings or results: a name and the points. */
    public record Line(int place, String name, int points) {
    }

    /**
     * The night as the board sees it.
     *
     * @param startsAt the night's start (UPCOMING, OPEN)
     * @param race     the race on now (RACING)
     * @param lines    the standings (RACING) or the result (RESULTS), best first
     */
    public record View(Shows shows, long now, long startsAt, String track, int racers, int maxRacers, int race,
                       int of, List<Line> lines, ZoneId zone) {

        public View {
            lines = lines == null ? List.of() : List.copyOf(lines);
            track = track == null ? "" : track;
            zone = zone == null ? ZoneId.of("UTC") : zone;
        }

        /** Nothing set. */
        public static View nothing(long now, ZoneId zone) {
            return new View(Shows.NOTHING, now, 0, "", 0, 0, 0, 0, List.of(), zone);
        }
    }

    /** A sign's four lines (plain ASCII, each at most {@value #SIGN_CHARS} characters). */
    public static List<String> sign(View v) {
        List<String> out = switch (v.shows()) {
            case NOTHING -> List.of("RACE NIGHT", "No race set", "Ask an admin!", "");
            case UPCOMING -> List.of("RACE NIGHT", EventCopy.when(v.startsAt(), v.zone()),
                    EventCopy.in(v.startsAt() - v.now()), v.track());
            case OPEN -> List.of("JOIN NOW!", EventCopy.COMMAND, v.racers() + " of " + v.maxRacers() + " in",
                    "starts " + EventCopy.shortClock(v.startsAt(), v.zone()));
            case WARMUP -> List.of("RACE NIGHT", "Warm-up laps", EventCopy.racers(v.racers()), v.track());
            case RACING -> {
                List<String> l = new ArrayList<>();
                l.add("RACE " + v.race() + " OF " + v.of());
                for (int i = 0; i < 3; i++) {
                    l.add(i < v.lines().size() ? standing(v.lines().get(i)) : "");
                }
                yield l;
            }
            case RESULTS -> {
                List<String> l = new ArrayList<>();
                l.add("WINNER");
                l.add(v.lines().isEmpty() ? "No finishers" : name(v.lines().get(0).name()));
                for (int i = 1; i < 3; i++) {
                    l.add(i < v.lines().size() ? v.lines().get(i).place() + ". " + name(v.lines().get(i).name()) : "");
                }
                yield l;
            }
        };
        List<String> fitted = new ArrayList<>();
        for (String s : out) {
            fitted.add(fit(s));
        }
        return fitted;
    }

    /** A hologram's or TV's lines ('&amp;' colour codes): a title, the facts, and the racers' names. */
    public static List<String> screen(View v) {
        List<String> out = new ArrayList<>();
        switch (v.shows()) {
            case NOTHING -> {
                out.add("&6&lRace Night");
                out.add("&7No race set - ask an admin!");
            }
            case UPCOMING -> {
                out.add("&6&lRace Night &7- " + EventCopy.when(v.startsAt(), v.zone()));
                out.add("&7" + v.track() + " &8· &e" + EventCopy.in(v.startsAt() - v.now()));
                out.add("&7Join from " + EventCopy.COMMAND);
            }
            case OPEN -> {
                out.add("&a&lRace Night - join now!");
                out.add("&7" + v.track() + " &8· &7starts " + EventCopy.clock(v.startsAt(), v.zone()));
                out.add("&f" + v.racers() + " of " + v.maxRacers() + " racers in");
                out.add("&e" + EventCopy.COMMAND);
            }
            case WARMUP -> {
                out.add("&6&lRace Night &7- warm-up laps");
                out.add("&7" + v.track() + " &8· &f" + EventCopy.racers(v.racers()));
            }
            case RACING -> {
                out.add("&6&lRace Night &7- race " + v.race() + " of " + v.of());
                for (int i = 0; i < v.lines().size() && i < 8; i++) {
                    Line l = v.lines().get(i);
                    out.add((l.place() == 1 ? "&6" : "&e") + l.place() + ". &f" + name(l.name()) + " &7"
                            + EventCopy.points(l.points()));
                }
            }
            case RESULTS -> {
                out.add("&6&lRace Night winner: " + (v.lines().isEmpty() ? "nobody finished" : name(v.lines().get(0).name())));
                for (int i = 0; i < v.lines().size() && i < 8; i++) {
                    Line l = v.lines().get(i);
                    out.add((l.place() == 1 ? "&6" : "&e") + l.place() + ". &f" + name(l.name()) + " &7"
                            + EventCopy.points(l.points()));
                }
            }
        }
        return out;
    }

    private static String standing(Line l) {
        String head = l.place() + ". ";
        String score = " " + l.points();
        int room = Math.max(1, SIGN_CHARS - head.length() - score.length());
        String who = name(l.name());
        return head + (who.length() > room ? who.substring(0, room) : who) + score;
    }

    private static String name(String n) {
        return n == null || n.isBlank() ? "a racer" : n;
    }

    /** Plain ASCII, colour codes dropped, at most {@value #SIGN_CHARS} characters. */
    static String fit(String s) {
        String plain = s == null ? "" : s.replaceAll("(?i)[&§][0-9a-fk-or]", "");
        StringBuilder b = new StringBuilder();
        for (char c : plain.toCharArray()) {
            b.append(c >= 32 && c < 127 ? c : ' ');
        }
        String t = b.toString().trim();
        return t.length() <= SIGN_CHARS ? t : t.substring(0, SIGN_CHARS).trim();
    }
}
