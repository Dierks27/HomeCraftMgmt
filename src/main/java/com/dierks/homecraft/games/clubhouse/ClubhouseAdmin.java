package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /hcm games clubhouse status|tp|here|podium <1|2|3>|board|generated|rebuild confirm|off}
 * (CLUBHOUSE-SPEC §6), with tab completion.
 *
 * <ul>
 *   <li>{@code status}: the room (generated or owner-built), whether it is built and checked, why it
 *       is closed, who is in it.</li>
 *   <li>{@code tp}: into the Clubhouse to look, as an admin (a plain teleport, nothing taken); refused
 *       until the room is built and checked (the Falling Floors review's lesson: nothing to stand on
 *       otherwise), or, owner-built, until its arrival spot is set.</li>
 *   <li>{@code here}: the room the admin stands in, built by hand, is the Clubhouse: visitors arrive
 *       where they stand, facing their way. Nothing there is built or guarded.</li>
 *   <li>{@code podium <n>} and {@code board}: the owner-built room's podium places and results board,
 *       where the admin stands.</li>
 *   <li>{@code generated}: back to the generated room (built and checked first).</li>
 *   <li>{@code rebuild confirm}: build the generated room again now and check it, claiming the box
 *       even with blocks in it (they are cleared).</li>
 *   <li>{@code off}: closed until {@code generated}, {@code here} or {@code rebuild confirm}; everyone in
 *       it goes home, and every race and round works as before.</li>
 * </ul>
 * The framework checks {@code hcm.games.admin} and runs this inside the game's guard, so a failure is
 * caught there.
 */
final class ClubhouseAdmin implements GameAdmin {

    /** The word after {@code /hcm games}. */
    static final String NAME = "clubhouse";
    /** Its subcommands, in help order. */
    static final List<String> SUBS = List.of("status", "tp", "here", "podium", "board", "generated", "rebuild", "off");

    private final Clubhouse game;

    ClubhouseAdmin(Clubhouse game) {
        this.game = game;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void handle(CommandSender sender, String[] args) {
        String sub = args == null || args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        ClubhouseRoom room = game.room();
        List<String> out = new ArrayList<>();
        switch (sub) {
            case "status" -> out.addAll(status(room, game.visits().size(), closedWhy()));
            case "tp" -> tp(sender, room);
            case "here", "podium", "board" -> {
                if (!(sender instanceof Player p)) {
                    out.add("&cOnly a player can do that - stand in the room first.");
                } else if (room == null) {
                    out.add(notRunning(closedWhy()));
                } else {
                    out.add(place(room, sub, args, p));
                }
            }
            case "generated" -> out.add(room == null ? notRunning(closedWhy()) : room.generated());
            case "rebuild" -> {
                if (room == null) {
                    out.add(notRunning(closedWhy()));
                } else {
                    out.addAll(room.rebuild(args != null && args.length > 1 && args[1].equalsIgnoreCase("confirm")));
                }
            }
            case "off" -> out.add(room == null ? notRunning(closedWhy()) : room.switchOff());
            default -> out.addAll(HELP);
        }
        for (String line : out) {
            sender.sendMessage(Text.of(line));
        }
    }

    /** Why the Clubhouse isn't running, for status: the framework's reason. */
    private String closedWhy() {
        try {
            String why = game.games().closedReason(game);
            return why == null || why.isBlank() ? "it is switched off (games.clubhouse.enabled)" : why;
        } catch (RuntimeException e) {
            return "it is switched off";
        }
    }

    /** {@code status}'s lines ({@code &}-coded), with no server needed. */
    static List<String> status(ClubhouseRoom room, int visitors, String closedWhy) {
        List<String> out = new ArrayList<>();
        out.add("&6The Clubhouse");
        if (room == null) {
            out.add("&7Not running: " + closedWhy);
            return out;
        }
        for (String line : room.statusLines()) {
            out.add("&7" + line);
        }
        out.add("&7" + visitors + " in it now");
        return out;
    }

    /** {@code here}, {@code podium <n>}, {@code board}: where the admin stands. */
    static String place(ClubhouseRoom room, String sub, String[] args, Player p) {
        Location l = p.getLocation();
        String world = l.getWorld() == null ? "" : l.getWorld().getName();
        ClubhouseRoom.Place at = new ClubhouseRoom.Place(world, l.getX(), l.getY(), l.getZ(), l.getYaw());
        return switch (sub) {
            case "here" -> room.here(at);
            case "board" -> room.board(at);
            default -> {
                int n;
                try {
                    n = args != null && args.length > 1 ? Integer.parseInt(args[1]) : 0;
                } catch (NumberFormatException e) {
                    n = 0;
                }
                yield room.podium(n, at);
            }
        };
    }

    private static String notRunning(String why) {
        return "&cThe Clubhouse isn't running: " + why + ".";
    }

    /** Refused while there may be nothing to stand on: not built and checked yet. */
    static final String TP_NOT_BUILT = "&cThe Clubhouse isn't built and checked yet, so there's nothing to stand on"
            + " there. See /hcm games clubhouse status.";

    /**
     * Why an admin can't be put in the Clubhouse now, or {@code null} when they can: only once the
     * generated room has been built and checked in this run, or an owner-built room's arrival spot is
     * set.
     */
    static String tpRefusal(ClubhouseRoom room, boolean worldLoaded, String closedWhy) {
        if (room == null) {
            return notRunning(closedWhy);
        }
        if (!room.open() || (!room.hand() && !room.verified())) {
            return TP_NOT_BUILT;
        }
        if (!worldLoaded) {
            return "&cThe Clubhouse's world isn't loaded.";
        }
        return null;
    }

    /** Into the Clubhouse, as an admin: a plain teleport, refused while in a game or before it is built. */
    private void tp(CommandSender sender, ClubhouseRoom room) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Text.of("&cOnly a player can go there."));
            return;
        }
        if (game.games().sessions().session(p) != null) {
            p.sendMessage(Text.of("&cLeave your game first (/hcm leave)."));
            return;
        }
        World w = room == null ? null : Bukkit.getWorld(room.world());
        String why = tpRefusal(room, w != null, closedWhy());
        if (why != null) {
            p.sendMessage(Text.of(why));
            return;
        }
        ClubhouseSite.Spot s = room.spawn(0);
        if (s == null || !p.teleport(new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0f))) {
            p.sendMessage(Text.of("&cCouldn't take you there right now."));
            return;
        }
        p.sendMessage(Text.of("&aYou're in the Clubhouse &7(looking round as an admin, not a visitor)."));
    }

    @Override
    public List<String> tab(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        String last = args == null || args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args == null || args.length <= 1) {
            for (String s : SUBS) {
                if (s.startsWith(last)) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("rebuild") && "confirm".startsWith(last)) {
            out.add("confirm");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("podium")) {
            for (String n : List.of("1", "2", "3")) {
                if (n.startsWith(last)) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    /** The usage lines. */
    static final List<String> HELP = List.of(
            "&e/hcm games clubhouse status|tp &7- the Clubhouse: how it is, go and look",
            "&e/hcm games clubhouse here|podium <1|2|3>|board &7- use a room you built (stand where it goes)",
            "&e/hcm games clubhouse generated|rebuild confirm|off &7- the built room, build it again, switch it off");

    @Override
    public List<String> help() {
        return HELP;
    }
}
