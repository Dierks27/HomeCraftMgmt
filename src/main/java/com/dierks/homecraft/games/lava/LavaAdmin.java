package com.dierks.homecraft.games.lava;

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
 * {@code /hcm games lava status|rebuild|claim [confirm]|tp}: what The Floor Is Lava's playroom is doing, check it
 * against its plan now, claim its box, and go and look (Falling Floors' {@code /hcm games floors}, for the playroom).
 *
 * <ul>
 *   <li>{@code status}: the plan, the round, the lava, the builds and why it is closed.</li>
 *   <li>{@code rebuild}: check the room against its plan and put back anything that differs (once no round is
 *       going); a closed room opens again this way, starting with a build like a boot's.</li>
 *   <li>{@code claim}: whether the box is claimed. {@code claim confirm} claims it even with blocks in it (the next
 *       build clears them) and opens the room again: for a box that was refused because something was in it.</li>
 *   <li>{@code tp}: onto the sofa to look round, as an admin (not a game session, nothing taken); only once the
 *       room has been built and checked.</li>
 * </ul>
 * The framework checks {@code hcm.games.admin} and runs this inside the game's guard.
 */
final class LavaAdmin implements GameAdmin {

    /** The word after {@code /hcm games}. */
    static final String NAME = "lava";
    /** Its subcommands, in help order. */
    static final List<String> SUBS = List.of("status", "rebuild", "claim", "tp");
    /** The usage line. */
    static final List<String> HELP = List.of("&e/hcm games lava status|rebuild|claim [confirm]|tp &7- The Floor Is"
            + " Lava: what its playroom is doing, check it against its plan, claim its box, go and look");

    private final FloorIsLava game;

    LavaAdmin(FloorIsLava game) {
        this.game = game;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void handle(CommandSender sender, String[] args) {
        String sub = args == null || args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("tp")) {
            tp(sender);
            return;
        }
        for (String line : lines(game.service(), sub, args, closedWhy())) {
            sender.sendMessage(Text.of(line));
        }
    }

    /** Why the game isn't running, for status: the framework's reason. */
    private String closedWhy() {
        try {
            String why = game.games().closedReason(game);
            return why == null || why.isBlank() ? "it is switched off (games.floor_is_lava.enabled)" : why;
        } catch (RuntimeException e) {
            return "it is switched off";
        }
    }

    /**
     * What a subcommand answers ({@code &}-coded lines), with no server needed.
     *
     * @param s         the running room, or {@code null} while the game is off
     * @param closedWhy why it isn't running
     */
    static List<String> lines(LavaService s, String sub, String[] args, String closedWhy) {
        List<String> out = new ArrayList<>();
        switch (sub) {
            case "status" -> {
                out.add("&6The Floor Is Lava");
                if (s == null) {
                    out.add("&7Not running: " + closedWhy);
                } else {
                    for (String line : s.statusLines()) {
                        out.add("&7" + line);
                    }
                }
            }
            case "rebuild" -> out.add(s == null ? notRunning(closedWhy) : s.requestRebuild());
            case "claim" -> {
                if (s == null) {
                    out.add(notRunning(closedWhy));
                } else {
                    boolean confirm = args != null && args.length > 1 && args[1].equalsIgnoreCase("confirm");
                    out.addAll(s.claim(confirm));
                }
            }
            default -> out.addAll(HELP);
        }
        return out;
    }

    private static String notRunning(String why) {
        return "&cThe Floor Is Lava isn't running: " + why + ".";
    }

    /** Refused while the room isn't built and checked: there may be nothing to stand on. */
    static final String TP_NOT_BUILT = "&cThe playroom isn't built and checked yet. See /hcm games lava status (and"
            + " /hcm games lava rebuild).";

    /** Why an admin can't be put in the room now, or {@code null} when they can. */
    static String tpRefusal(LavaService s, boolean worldLoaded, String closedWhy) {
        if (s == null) {
            return notRunning(closedWhy);
        }
        if (s.site() == null || !worldLoaded || !s.verified()) {
            return TP_NOT_BUILT;
        }
        return null;
    }

    /** Onto the sofa, as an admin: a plain teleport, refused while in a game or while the room isn't checked. */
    private void tp(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Text.of("&cOnly a player can go there."));
            return;
        }
        if (game.games().sessions().session(p) != null) {
            p.sendMessage(Text.of("&cLeave your game first (/hcm leave)."));
            return;
        }
        LavaService s = game.service();
        World w = s == null || s.world().isEmpty() ? null : Bukkit.getWorld(s.world());
        String why = tpRefusal(s, w != null, closedWhy());
        if (why != null) {
            p.sendMessage(Text.of(why));
            return;
        }
        LavaSite.Spot spot = s.site().spawn(0);
        p.teleport(new Location(w, spot.x(), spot.y(), spot.z(), spot.yaw(), 0));
        p.sendMessage(Text.of("&aYou're in The Floor Is Lava's playroom &7(looking round as an admin, not playing)."));
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
        } else if (args.length == 2 && args[0].equalsIgnoreCase("claim") && "confirm".startsWith(last)) {
            out.add("confirm");
        }
        return out;
    }

    @Override
    public List<String> help() {
        return HELP;
    }
}
