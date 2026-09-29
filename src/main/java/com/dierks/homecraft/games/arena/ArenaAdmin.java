package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * {@code /hcm games floors status|reset|claim [confirm]|tp} (EVENTS-DROPPER-SPEC §B.3.5): what the
 * arena is doing, rebuild its floors now, claim its box, and go and look.
 *
 * <ul>
 *   <li>{@code status}: the week's floors, the round, the writer, the resets and why it is closed.</li>
 *   <li>{@code reset}: put the floors back and verify them now (a round going finishes first); a
 *       closed arena opens again this way, starting with a reset like a boot.</li>
 *   <li>{@code claim}: whether the box is claimed. {@code claim confirm} claims it even with blocks
 *       in it (the next reset clears them) and opens the arena again: for a box that was refused
 *       because something was in it.</li>
 *   <li>{@code tp}: into the gallery to watch, as an admin (not a game session, nothing taken); only
 *       once the floors have been built and checked, or when the gallery walk is there anyway.</li>
 * </ul>
 * The framework checks {@code hcm.games.admin} and runs this inside the game's guard.
 */
final class ArenaAdmin implements GameAdmin {

    /** The word after {@code /hcm games}. */
    static final String NAME = "floors";
    /** Its subcommands, in help order. */
    static final List<String> SUBS = List.of("status", "reset", "claim", "tp");

    private final FallingFloors game;

    ArenaAdmin(FallingFloors game) {
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
            return why == null || why.isBlank() ? "it is switched off (games.falling_floors.enabled)" : why;
        } catch (RuntimeException e) {
            return "it is switched off";
        }
    }

    /**
     * What a subcommand answers ({@code &}-coded lines), with no server needed.
     *
     * @param s         the running arena, or {@code null} while the game is off
     * @param closedWhy why it isn't running
     */
    static List<String> lines(ArenaService s, String sub, String[] args, String closedWhy) {
        List<String> out = new ArrayList<>();
        switch (sub) {
            case "status" -> {
                out.add("&6Falling Floors");
                if (s == null) {
                    out.add("&7Not running: " + closedWhy);
                } else {
                    for (String line : s.statusLines()) {
                        out.add("&7" + line);
                    }
                }
            }
            case "reset" -> out.add(s == null ? notRunning(closedWhy) : s.requestReset());
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
        return "&cFalling Floors isn't running: " + why + ".";
    }

    /**
     * Into the gallery, as an admin: a plain teleport, refused while in a game, and refused while
     * there may be nothing to stand on there (F review #2): only once a verify has passed, or when
     * the gallery walk under the spot is there anyway (a closed arena an admin goes to look at).
     */
    private void tp(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Text.of("&cOnly a player can go there."));
            return;
        }
        if (game.games().sessions().session(p) != null) {
            p.sendMessage(Text.of("&cLeave your game first (/hcm leave)."));
            return;
        }
        ArenaService s = game.service();
        World w = s == null || s.world().isEmpty() ? null : Bukkit.getWorld(s.world());
        String why = tpRefusal(s, w != null, closedWhy(), () -> walkThere(w, s));
        if (why != null) {
            p.sendMessage(Text.of(why));
            return;
        }
        ArenaSite.Spot spot = s.tpSpot();
        p.teleport(new Location(w, spot.x(), spot.y(), spot.z(), spot.yaw(), 0));
        p.sendMessage(Text.of("&aYou're in the Falling Floors gallery &7(watching as an admin, not playing)."));
    }

    /** Refused while the gallery isn't built and checked: nothing to stand on at y 207. */
    static final String TP_NOT_BUILT = "&cThe gallery isn't built and checked yet, so there's nothing to stand on"
            + " there. See /hcm games floors status (and /hcm games floors reset).";

    /**
     * Why an admin can't be put in the gallery now, or {@code null} when they can.
     *
     * @param worldLoaded the arena's world is loaded
     * @param walkThere   whether the gallery walk is under the tp spot in the world now (asked only
     *                    when no verify has passed)
     */
    static String tpRefusal(ArenaService s, boolean worldLoaded, String closedWhy, BooleanSupplier walkThere) {
        if (s == null) {
            return notRunning(closedWhy);
        }
        if (s.tpSpot() == null || !worldLoaded) {
            return "&cThe arena isn't built yet - see /hcm games floors status.";
        }
        if (!s.verified() && (walkThere == null || !walkThere.getAsBoolean())) {
            return TP_NOT_BUILT;
        }
        return null;
    }

    /** Whether the gallery walk block is under the tp spot in the world now. */
    private static boolean walkThere(World w, ArenaService s) {
        ArenaSite site = s == null ? null : s.site();
        ArenaSite.Spot spot = s == null ? null : s.tpSpot();
        if (w == null || site == null || spot == null) {
            return false;
        }
        Material walk = Material.matchMaterial(ArenaPlanner.WALK);
        return walk != null && w.getBlockAt((int) Math.floor(spot.x()), site.galleryY(),
                (int) Math.floor(spot.z())).getType() == walk;
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

    /** The usage line. */
    static final List<String> HELP = List.of("&e/hcm games floors status|reset|claim [confirm]|tp &7- Falling Floors:"
            + " what its arena is doing, rebuild its floors, claim its box, go and watch");

    @Override
    public List<String> help() {
        return HELP;
    }
}
