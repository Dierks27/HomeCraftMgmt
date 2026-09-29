package com.dierks.homecraft.games.arena.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * What Falling Floors says (EVENTS-DROPPER-SPEC §B.3.3, §B.4 kid-friendliness): short, plain,
 * '&amp;'-coloured lines, no emoji, nothing above U+FFFF, and no pressure words. Being out is never
 * a failure here, only how long you lasted and where that put you ("You lasted 0:42 - 3rd of 6!").
 *
 * <p>Pure: names come in through a function, so the lines can be tested without a server.
 */
public final class ArenaText {

    /** The game's name as players read it. */
    public static final String NAME = "Falling Floors";

    private ArenaText() {
    }

    /** Ticks as a clock: 840 ticks is "0:42", 1,440 is "1:12". Whole seconds, rounded down. */
    public static String clock(long ticks) {
        long s = Math.max(0, ticks) / RoundSettings.TICKS_PER_SECOND;
        long sec = s % 60;
        return (s / 60) + ":" + (sec < 10 ? "0" : "") + sec;
    }

    /** 1st, 2nd, 3rd, 4th ... 11th, 12th, 13th ... 21st, 22nd. */
    public static String ordinal(int n) {
        int mod100 = Math.floorMod(n, 100);
        String suffix;
        if (mod100 >= 11 && mod100 <= 13) {
            suffix = "th";
        } else {
            suffix = switch (Math.floorMod(n, 10)) {
                case 1 -> "st";
                case 2 -> "nd";
                case 3 -> "rd";
                default -> "th";
            };
        }
        return n + suffix;
    }

    /**
     * The line for a player going out, sent as they reach the gallery. "You won" only when the win
     * counts ({@link RoundEvent.Out#won()}); a 1st place that isn't a win means everyone else left,
     * and it says so, as {@link #standingLine} does for the one left standing.
     */
    public static String outLine(RoundEvent.Out e) {
        String lasted = clock(e.survivedTicks());
        if (e.reason() == OutReason.LEFT) {
            return "&7You left the round after " + lasted + ".";
        }
        if (e.solo()) {
            return "&eYou lasted " + lasted + "!";
        }
        if (e.won()) {
            return e.tied() ? "&aYou won together - joint 1st of " + e.of() + "! You lasted " + lasted + "."
                    : "&aYou won - 1st of " + e.of() + "! You lasted " + lasted + ".";
        }
        if (e.place() == 1) {
            return walkover(lasted);
        }
        return "&eYou lasted " + lasted + " - " + (e.tied() ? "joint " : "") + ordinal(e.place()) + " of " + e.of()
                + "!";
    }

    private static String walkover(String lasted) {
        return "&eEveryone else left, so the round is over. You lasted " + lasted + ".";
    }

    /**
     * The line for the player still standing when a multiplayer round ends; {@code null} for anyone
     * else (they had their {@link #outLine} already).
     */
    public static String standingLine(RoundResult r, Standing s) {
        if (r.solo() || r.calledOff() || s == null || !s.stillStanding()) {
            return null;
        }
        if (!r.contested()) {
            return walkover(clock(s.survivedTicks()));
        }
        return "&aLast one standing - you won! You lasted " + clock(s.survivedTicks()) + ".";
    }

    /** The results for everyone in the gallery: a headline, then the places (at most 8, then "and N more"). */
    public static List<String> results(RoundResult r, Function<UUID, String> names) {
        List<String> out = new ArrayList<>();
        if (r.calledOff()) {
            out.add(calledOff());
            return out;
        }
        if (r.solo()) {
            Standing s = r.standings().isEmpty() ? null : r.standings().get(0);
            if (s != null) {
                out.add("&6" + NAME + ": &f" + name(names, s.player()) + " &elasted " + clock(s.survivedTicks())
                        + "&6!");
            }
            return out;
        }
        List<UUID> winners = r.winners();
        if (winners.isEmpty()) {
            out.add("&6" + NAME + ": &eround over!");
        } else if (winners.size() == 1) {
            out.add("&6" + NAME + ": &f" + name(names, winners.get(0)) + " &ewon&6!");
        } else {
            List<String> ws = new ArrayList<>();
            for (UUID w : winners) {
                ws.add(name(names, w));
            }
            out.add("&6" + NAME + ": &f" + String.join("&e, &f", ws) + " &ewon together&6!");
        }
        int shown = 0;
        for (Standing s : r.standings()) {
            if (shown == 8) {
                out.add("&7...and " + (r.standings().size() - 8) + " more");
                break;
            }
            out.add("&7" + ordinal(s.place()) + " &f" + name(names, s.player()) + " &7"
                    + clock(s.survivedTicks()) + (s.left() ? " (left)" : ""));
            shown++;
        }
        return out;
    }

    /** The countdown bar's title: "Falling Floors starts in 7". */
    public static String countdown(int ticksLeft) {
        int seconds = (Math.max(0, ticksLeft) + RoundSettings.TICKS_PER_SECOND - 1) / RoundSettings.TICKS_PER_SECOND;
        return "&e" + NAME + " starts in " + seconds;
    }

    /**
     * The lobby's action bar: who is here and ready, and when it starts by itself. It only offers
     * solo play when {@code soloOn} ({@code games.falling_floors.solo}), the same rule as the
     * "Play solo" item.
     */
    public static String lobby(int here, int ready, int max, long autoStartTicks, boolean soloOn) {
        StringBuilder sb = new StringBuilder("&e").append(here).append('/').append(max).append(" here &7- &a")
                .append(ready).append(" ready");
        if (here < 2) {
            sb.append(soloOn ? " &7- waiting for a friend (or play solo)" : " &7- waiting for a friend");
        } else if (autoStartTicks >= 0) {
            sb.append(" &7- starts in ").append(clock(autoStartTicks + RoundSettings.TICKS_PER_SECOND - 1));
        }
        return sb.toString();
    }

    public static String go() {
        return "&aGo! Keep moving!";
    }

    public static String suddenDeath() {
        return "&cThe edges are falling in - keep moving!";
    }

    public static String calledOff() {
        return "&7Not enough players left - back to the gallery.";
    }

    /** No new round before a restart ({@code when} is "4:00 PM"); a round going carries on. */
    public static String hold(String when) {
        return "&eThe server restarts at " + when + " - rounds start again after it.";
    }

    /** Why "Play solo" did nothing ({@code null} when it started). */
    public static String solo(ArenaRound.Solo s) {
        return switch (s) {
            case STARTED -> null;
            case OFF -> "&7Solo play is off right now.";
            case NOT_IN -> "&7Join " + NAME + " first.";
            case NOT_ALONE -> "&7Someone else is here - press Ready and play together!";
            case BUSY -> "&7A round is going - you play the next one!";
            case NOT_READY -> "&7The floors are being fixed - one moment!";
            case HELD -> "&eA restart is coming - rounds start again after it.";
            case CLOSED -> closed();
        };
    }

    /** Why joining did nothing ({@code null} when it worked). */
    public static String join(ArenaRound.Join j, int max) {
        return switch (j) {
            case JOINED -> null;
            case ALREADY_IN -> "&7You're already in " + NAME + ".";
            case FULL -> "&7" + NAME + " is full (" + max + " playing) - come back in a minute!";
            case CLOSED -> closed();
        };
    }

    public static String closed() {
        return "&7" + NAME + " is closed right now.";
    }

    private static String name(Function<UUID, String> names, UUID id) {
        String n = names == null ? null : names.apply(id);
        return n == null || n.isBlank() ? "Someone" : n;
    }
}
