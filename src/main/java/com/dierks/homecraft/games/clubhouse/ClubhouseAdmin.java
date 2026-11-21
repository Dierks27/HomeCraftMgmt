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
 * {@code /hcm games clubhouse status|tp|here|podium <1|2|3>|board [fame|playing|stats]|trophies|jukebox|feast|generated|rebuild confirm|off}
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
 *       where the admin stands; {@code board fame|playing|stats} (0.38.3) the walls' boards (this week's best,
 *       now playing, my stats). A board hangs facing the way the admin faces: their back to the wall.</li>
 *   <li>{@code trophies} and {@code jukebox} (0.38.4): the owner-built room's trophy case (its first frame where
 *       the admin stands, their back to the wall; the row runs to their right) and its jukebox (the real jukebox
 *       the admin stands on). Both optional: without them there is no trophy case and no music.</li>
 *   <li>{@code feast} (0.38.6): the owner-built room's feast table, where the admin stands on it facing across
 *       it (ten plates to their right, ten more ahead). Optional: without it the Family Feast shows only at the
 *       pot.</li>
 *   <li>{@code cabinet <game> [clear]}, {@code trophyroom [clear]} and {@code door arcade|trophy [clear]} (0.41.0):
 *       the owner-built room's Arcade Room cabinets (one per game, on the block the admin stands on, facing their
 *       way; {@code cabinet all clear} takes every one down), its trophy shelf (the first trophy where the admin stands
 *       on their shelf, back to the wall, the row to their right) and the signs over its two doorways. Optional.</li>
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
    static final List<String> SUBS = List.of("status", "tp", "here", "podium", "board", "trophies", "jukebox",
            "feast", "cabinet", "trophyroom", "door", "secret", "parlor", "edit", "save", "discard", "saves", "restore",
            "spot", "models", "generated", "rebuild", "off");

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
            case "cabinet", "trophyroom", "door" -> {
                if (!(sender instanceof Player p)) {
                    out.add("&cOnly a player can do that - stand where it goes first.");
                } else if (room == null) {
                    out.add(notRunning(closedWhy()));
                } else {
                    out.add(sideRoom(room, sub, args, p, id -> {
                        var g = game.games().game(id);
                        return g == null ? null : g.kind();
                    }));
                    game.sideRoomsChanged();
                }
            }
            case "here", "podium", "board", "trophies", "jukebox", "feast" -> {
                if (!(sender instanceof Player p)) {
                    out.add("&cOnly a player can do that - stand in the room first.");
                } else if (room == null) {
                    out.add(notRunning(closedWhy()));
                } else {
                    if (sub.equals("here") || sub.equals("jukebox")) {
                        game.quietMusic(); // the room's spots change: the disc out of the old jukebox first
                    }
                    out.add(place(room, sub, args, p));
                }
            }
            case "generated" -> {
                if (room != null) {
                    game.quietMusic();
                }
                boolean confirm = args != null && args.length > 1 && args[1].equalsIgnoreCase("confirm");
                out.add(room == null ? notRunning(closedWhy()) : room.generated(confirm));
            }
            case "secret", "parlor" -> {
                if (!(sender instanceof Player p)) {
                    out.add("&cOnly a player can do that - stand where it goes first.");
                } else if (room == null) {
                    out.add(notRunning(closedWhy()));
                } else {
                    String a1 = args != null && args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null;
                    boolean clear = args != null && args.length > 1 && args[args.length - 1].equalsIgnoreCase("clear");
                    if ("clear".equals(a1)) {
                        a1 = null;
                    }
                    Location l = p.getLocation();
                    ClubhouseRoom.Place at = clear ? null : new ClubhouseRoom.Place(l.getWorld() == null ? ""
                            : l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw());
                    var g = a1 == null ? null : game.games().game(a1);
                    out.add(room.upstairs(sub, a1, at, g != null && g.kind().chance()));
                }
            }
            case "edit" -> out.add(room == null ? notRunning(closedWhy()) : room.edit());
            case "discard" -> out.add(room == null ? notRunning(closedWhy()) : room.discard());
            case "saves" -> {
                if (room == null) {
                    out.add(notRunning(closedWhy()));
                } else {
                    out.addAll(room.savesList());
                }
            }
            case "restore" -> {
                int n;
                try {
                    n = args != null && args.length > 1 ? Integer.parseInt(args[1]) : 0;
                } catch (NumberFormatException e) {
                    n = 0;
                }
                out.add(room == null ? notRunning(closedWhy()) : room.restore(n));
            }
            case "save" -> {
                if (room == null) {
                    out.add(notRunning(closedWhy()));
                } else {
                    boolean confirm = args != null && args.length > 1 && args[1].equalsIgnoreCase("confirm");
                    room.save(confirm, lines -> {
                        for (String line : lines) {
                            sender.sendMessage(Text.of(line));
                        }
                    });
                    out.add("&7Reading the Clubhouse's blocks...");
                }
            }
            case "models" -> out.addAll(models(game.plugin() == null ? null : game.plugin().packModels()));
            case "spot" -> {
                if (!(sender instanceof Player p)) {
                    out.add("&cOnly a player can do that - stand where it goes first.");
                } else if (room == null) {
                    out.add(notRunning(closedWhy()));
                } else {
                    out.add(spot(room, args, p, id -> {
                        var g = game.games().game(id);
                        return g == null ? null : g.kind();
                    }));
                    game.sideRoomsChanged();
                }
            }
            case "rebuild" -> {
                if (room == null) {
                    out.add(notRunning(closedWhy()));
                } else {
                    boolean confirm = args != null && args.length > 1 && args[1].equalsIgnoreCase("confirm");
                    if (confirm) {
                        game.quietMusic(); // while the room is still open: the disc out before the phase changes
                    }
                    out.addAll(room.rebuild(confirm));
                }
            }
            case "off" -> {
                if (room != null) {
                    game.quietMusic();
                }
                out.add(room == null ? notRunning(closedWhy()) : room.switchOff());
            }
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

    /**
     * {@code here}, {@code podium <n>}, {@code board}, {@code trophies}, {@code jukebox}, {@code feast}: where the admin
     * stands.
     */
    static String place(ClubhouseRoom room, String sub, String[] args, Player p) {
        Location l = p.getLocation();
        String world = l.getWorld() == null ? "" : l.getWorld().getName();
        ClubhouseRoom.Place at = new ClubhouseRoom.Place(world, l.getX(), l.getY(), l.getZ(), l.getYaw());
        return switch (sub) {
            case "here" -> room.here(at);
            case "trophies" -> room.trophies(at);
            case "jukebox" -> room.jukebox(at);
            case "feast" -> room.feast(at);
            case "board" -> args != null && args.length > 1 ? room.board(args[1].toLowerCase(Locale.ROOT), at)
                    : room.board(at);
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

    /**
     * 0.41.0: {@code cabinet <game> [clear]}, {@code cabinet all clear}, {@code trophyroom [clear]} and
     * {@code door arcade|trophy [clear]}, where the admin stands. A cabinet must be an arcade cabinet game, never a
     * game of chance ({@link KidSet#refusal}).
     */
    static String sideRoom(ClubhouseRoom room, String sub, String[] args, Player p,
                           java.util.function.Function<String, com.dierks.homecraft.games.GameKind> kindOf) {
        Location l = p.getLocation();
        String world = l.getWorld() == null ? "" : l.getWorld().getName();
        return sideRoom(room, sub, args, new ClubhouseRoom.Place(world, l.getX(), l.getY(), l.getZ(), l.getYaw()),
                kindOf);
    }

    /** {@link #sideRoom(ClubhouseRoom, String, String[], Player, java.util.function.Function)} at a place (tests). */
    static String sideRoom(ClubhouseRoom room, String sub, String[] args, ClubhouseRoom.Place at,
                           java.util.function.Function<String, com.dierks.homecraft.games.GameKind> kindOf) {
        String a1 = args != null && args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        boolean clear = args != null && args.length > 1 && args[args.length - 1].equalsIgnoreCase("clear");
        return switch (sub) {
            case "cabinet" -> {
                if (a1.isEmpty() || a1.equals("clear")) {
                    yield "&cWhich game? &e/hcm games clubhouse cabinet <game> &7(or &ecabinet all clear&7)";
                }
                if (clear) {
                    yield room.clearCabinet(a1);
                }
                // The Floor Is Lava has a cabinet too (its tap starts Little), though it isn't a cabinet game
                String why = a1.equals(SideRooms.LAVA_GAME) && kindOf.apply(a1) != null ? null
                        : KidSet.refusal(a1, kindOf);
                yield why != null ? "&c" + Character.toUpperCase(why.charAt(0)) + why.substring(1) + "."
                        : room.cabinet(a1, at);
            }
            case "trophyroom" -> room.trophyRoom(clear ? null : at);
            default -> {
                if (!ClubhouseRoom.DOORS.contains(a1)) {
                    yield "&cWhich door? &e/hcm games clubhouse door arcade|trophy [clear]";
                }
                yield room.door(a1, clear ? null : at);
            }
        };
    }

    /**
     * 0.42.0, {@code spot <name> [...] [clear]}: move a spot of the edited or saved room to where the admin stands
     * (their blocks covered it, or they want it elsewhere): {@code arrival}, {@code podium <1|2|3>}, {@code board
     * [fame|playing|stats]}, {@code trophies}, {@code jukebox}, {@code feast}, {@code cabinet <game>},
     * {@code trophyroom}, {@code door arcade|trophy}; {@code clear} gives one back to the generated room's place.
     */
    static String spot(ClubhouseRoom room, String[] args, Player p,
                       java.util.function.Function<String, com.dierks.homecraft.games.GameKind> kindOf) {
        String[] rest = args == null || args.length < 2 ? new String[0] : java.util.Arrays.copyOfRange(args, 1,
                args.length);
        if (rest.length == 0) {
            return "&cWhich spot? &earrival, podium <n>, board [which], trophies, jukebox, feast, cabinet <game>,"
                    + " trophyroom, door <arcade|trophy> &7(add &eclear &7to put it back)";
        }
        String which = rest[0].toLowerCase(Locale.ROOT);
        boolean clear = rest[rest.length - 1].equalsIgnoreCase("clear");
        Location l = p.getLocation();
        String world = l.getWorld() == null ? "" : l.getWorld().getName();
        ClubhouseRoom.Place at = new ClubhouseRoom.Place(world, l.getX(), l.getY(), l.getZ(), l.getYaw());
        return switch (which) {
            case "arrival" -> room.spotArrival(clear ? null : at);
            case "cabinet", "trophyroom", "door" -> sideRoom(room, which, rest, at, kindOf);
            default -> {
                if (clear) {
                    yield room.clearSpot(which, rest.length > 2 ? rest[1] : null);
                }
                yield place(room, which, rest, p);
            }
        };
    }

    /** 0.42.0, {@code models}: each model's id, its size, and whether it's the owner's own or HCM's placeholder. */
    static List<String> models(com.dierks.homecraft.pack.PackModels pack) {
        List<String> out = new ArrayList<>();
        out.add("&6The Clubhouse's models &7(build them in Blockbench; see the README)");
        java.util.Set<String> owners = pack == null ? java.util.Set.of() : pack.owners();
        for (com.dierks.homecraft.pack.ClubModels.Model m : com.dierks.homecraft.pack.ClubModels.all()) {
            out.add("&e" + m.id() + " &7- " + m.size() + " &8| " + (owners.contains(m.name()) ? "&ayours"
                    : "&7placeholder"));
        }
        out.add(pack == null || !pack.active() ? "&cCraftBridge isn't taking HCM's models: everyone sees the vanilla"
                + " stand-ins." : "&7Drop yours in plugins/CraftBridge/pack/sources/<folder>/assets/hcm/...");
        return out;
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
        } else if (args.length == 2 && args[0].equalsIgnoreCase("board")) {
            for (String w : WallBoards.WALLS) {
                if (w.startsWith(last)) {
                    out.add(w);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("door")) {
            for (String w : ClubhouseRoom.DOORS) {
                if (w.startsWith(last)) {
                    out.add(w);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("cabinet")) {
            for (com.dierks.homecraft.games.Game g : game.games().games()) {
                if ((g.kind() == com.dierks.homecraft.games.GameKind.CABINET || g.id().equals(SideRooms.LAVA_GAME))
                        && g.id().startsWith(last)) {
                    out.add(g.id());
                }
            }
            if ("all".startsWith(last)) {
                out.add("all");
            }
        } else if ((args.length == 2 && args[0].equalsIgnoreCase("trophyroom"))
                || (args.length == 3 && (args[0].equalsIgnoreCase("door") || args[0].equalsIgnoreCase("cabinet")))) {
            if ("clear".startsWith(last)) {
                out.add("clear");
            }
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
            "&e/hcm games clubhouse here|podium <1|2|3>|board [fame|playing|stats]|trophies|jukebox|feast &7- use a"
                    + " room you built (stand where it goes, facing into the room)",
            "&e/hcm games clubhouse cabinet <game>|trophyroom|door arcade|trophy [clear] &7- your room's Arcade Room"
                    + " cabinets, trophy shelf and doorway signs (stand where it goes, facing out)",
            "&e/hcm games clubhouse secret [room] [clear]|parlor star|room|<game>|ticket|mob_races [clear] &7- your"
                    + " room's secret trophy room (its door, its room) and Game Parlor (its star, room, machines, ticket"
                    + " booth, and Mob Races' track: stand on a corner of a table 8 x 3, facing along it)",
            "&e/hcm games clubhouse edit|save [confirm]|discard|saves|restore <n> &7- decorate HCM's room yourself and"
                    + " save it as the Clubhouse",
            "&e/hcm games clubhouse spot <name> [clear] &7- move a spot of your edited room; &emodels &7- the model list",
            "&e/hcm games clubhouse generated [confirm]|rebuild confirm|off &7- the built room, build it again, switch"
                    + " it off");

    @Override
    public List<String> help() {
        return HELP;
    }
}
