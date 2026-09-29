package com.dierks.homecraft.command;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Breaks;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GameCatalog;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Invites;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.games.world.WorldSessions;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.GameClock;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * {@code /hcm play}, {@code /hcm leave} and {@code /hcm games}: playing the Games, and running them
 * (spec §9). Routed from {@link HcmCommand}; every call is on the main thread and goes through
 * {@link GamesService} and {@link Breaks}, which hold the rules. This class only parses, checks
 * permissions, reports and logs.
 * <ul>
 *   <li>{@code /hcm play} — the Games screen; {@code /hcm play <game|course>} — open a game (rules
 *       and odds first) or start a course; {@code /hcm play break} — Take a break;
 *       {@code /hcm play accept|deny} — answer an invite; {@code /hcm play invites [on|off]};
 *       {@code /hcm play news [on|off]} — the "New courses are up!" line;
 *       {@code /hcm play cup [on|off]} — the Weekly Cup's prompts (hcm.games.play)</li>
 *   <li>{@code /hcm play <game|course> <player>} — the same for someone else: NPC plugins, command
 *       blocks, the hub (hcm.games.admin, or the console)</li>
 *   <li>{@code /hcm leave} — leave the world game you are in (hcm.games.play)</li>
 *   <li>{@code /hcm games status|feature|break|scores|saved} and each world game's own
 *       {@code course}/{@code golf} editor (hcm.games.admin); {@code /hcm games check} — is the
 *       server set up for the games? It only reads ({@link GamesCheck})</li>
 * </ul>
 *
 * <p>A player inside a world game may use only {@code play}, {@code leave}, {@code games} and
 * {@code help} ({@link #refuseInSession}): every other {@code /hcm} screen could hand them items
 * or take them somewhere the session can't follow.
 *
 * <p>Parent and admin controls act on offline players too (a child who isn't on), resolved from
 * the server's name cache, and every one of them writes a log line; lifting a player's OWN break
 * needs a {@code confirm} and is logged as a warning, so a self-exclusion is never undone by
 * accident.
 */
public final class GamesCommand {

    /** Playing: the Games screen, games, courses, invites, Take a break, leaving. */
    public static final String PLAY = "hcm.games.play";
    /** Running the Games. */
    public static final String ADMIN = "hcm.games.admin";

    /** What a player in a world session may still use under {@code /hcm}. */
    static final Set<String> IN_SESSION = Set.of("play", "leave", "games", "help");
    /** The {@code /hcm games} verbs, in the order help and tab completion list them. */
    static final List<String> VERBS = List.of("status", "check", "feature", "break", "scores", "saved");
    /** The {@code /hcm games break <player>} verbs. */
    static final List<String> BREAK_VERBS = List.of("show", "pause", "limit", "clear", "clear-own");
    /** The {@code /hcm games saved <player>} verbs (world sessions own them). */
    static final List<String> SAVED_VERBS = List.of("show", "restore", "return", "discard");
    /** Words {@code /hcm play} keeps for itself. */
    static final List<String> PLAY_WORDS = List.of("break", "accept", "deny", "invites", "news", "leave", "cup");

    private final HomeCraftManagement plugin;

    public GamesCommand(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------------
    //  in a world game
    // ---------------------------------------------------------------------

    /**
     * The top of {@code /hcm}: a player in a world session may use only play, leave, games and
     * help. {@code args} is the whole {@code /hcm} argument list.
     *
     * @return true when the command was refused (the player has been told)
     */
    public boolean refuseInSession(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || allowedInSession(args.length == 0 ? "help" : args[0])) {
            return false;
        }
        GamesService games = plugin.games();
        if (games == null) {
            return false;
        }
        Session session;
        try {
            session = games.sessions().session(player);
        } catch (RuntimeException e) {
            return false;
        }
        if (session == null) {
            return false;
        }
        player.sendMessage(Text.of("&cFinish or leave your game first — /hcm leave"));
        return true;
    }

    /** Whether {@code /hcm <sub>} is allowed inside a world game. */
    static boolean allowedInSession(String sub) {
        return sub != null && IN_SESSION.contains(sub.toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------------
    //  /hcm play …
    // ---------------------------------------------------------------------

    /** {@code /hcm play …}; {@code args} is the whole {@code /hcm} argument list. */
    public void play(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Player player = self(sender, "Only players can open the Games screen.");
            if (player == null || deny(sender, PLAY)) {
                return;
            }
            GamesService games = running(sender);
            if (games != null) {
                games.openGamesScreen(player, null);
            }
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "break" -> takeABreak(sender);
            case "accept", "deny" -> answer(sender, args[1].equalsIgnoreCase("accept"));
            case "invites" -> invites(sender, args);
            case "news" -> news(sender, args);
            case "leave" -> leave(sender, args);
            case "race" -> race(sender, args); // WP-R1 (D4): /hcm play race <course> is a party race
            case "cup" -> cup(sender, args);
            default -> open(sender, args);
        }
    }

    /**
     * {@code /hcm play race <course>}: a party race on that time-trial course (owner decision D4).
     * Anything else ({@code /hcm play race}, or an admin's {@code /hcm play race <player>}) opens
     * Race Night as before.
     */
    private void race(CommandSender sender, String[] args) {
        GamesService games = args.length == 3 && sender instanceof Player ? plugin.games() : null;
        if (games == null || !com.dierks.homecraft.games.trial.PartyRaces.isCourse(games, args[2])) {
            open(sender, args);
            return;
        }
        if (deny(sender, PLAY) || running(sender) == null) {
            return;
        }
        com.dierks.homecraft.games.trial.PartyRaces.fromCommand(games, (Player) sender, args[2]);
    }

    /** {@code /hcm play <id> [player]}. */
    private void open(CommandSender sender, String[] args) {
        String id = args[1];
        if (args.length == 3 && sender instanceof Player self && golfCourse(args[1], args[2])) {
            // /hcm play golf <course>: the course's screen, with "Play with friends" (EVENTS-OWNER-DECISIONS D4)
            if (deny(sender, PLAY)) {
                return;
            }
            GamesService games = running(sender);
            if (games != null) {
                games.open(self, args[2], null);
            }
            return;
        }
        if (args.length >= 3) {
            if (sender instanceof Player && deny(sender, ADMIN)) {
                return;
            }
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(Text.of("&cPlayer '" + args[2] + "' is not online."));
                return;
            }
            GamesService games = running(sender);
            if (games == null) {
                return;
            }
            boolean opened = games.open(target, id, null);
            sender.sendMessage(Text.of(opened ? "&aOpened &f" + id + " &afor &f" + target.getName() + "&a."
                    : "&7" + target.getName() + " couldn't play " + id + " right now (they were told why)."));
            return;
        }
        Player player = self(sender, "Only players can play - from the console: /hcm play <game> <player>");
        if (player == null || deny(sender, PLAY)) {
            return;
        }
        GamesService games = running(sender);
        if (games != null) {
            games.open(player, id, null);
        }
    }

    /**
     * Whether {@code /hcm play <first> <second>} names a golf course after {@code golf} (golf together,
     * EVENTS-OWNER-DECISIONS D4) rather than a player: {@code first} is golf and {@code second} one
     * of its open courses. Never throws.
     */
    private boolean golfCourse(String first, String second) {
        GamesService games = plugin.games();
        if (games == null || !"golf".equalsIgnoreCase(first)) {
            return false;
        }
        try {
            GamesService.Target t = games.resolve(second);
            return t != null && t.playable() != null && "golf".equals(t.game().id());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code /hcm play break}: Take a break works even while the games are off. */
    private void takeABreak(CommandSender sender) {
        Player player = self(sender, "Take a break is a screen - open it in game.");
        if (player == null || deny(sender, PLAY)) {
            return;
        }
        GamesService games = plugin.games();
        if (games == null || plugin.breaks() == null) {
            sender.sendMessage(Text.of("&cTake a break isn't available right now."));
            return;
        }
        try {
            games.screens().takeABreak(player, null);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "The Take a break screen failed", e);
            sender.sendMessage(Text.of("&cTake a break isn't available right now."));
        }
    }

    /** {@code /hcm play accept|deny}: the player's latest invite. */
    private void answer(CommandSender sender, boolean accept) {
        Player player = self(sender, "Only players get invites.");
        if (player == null || deny(sender, PLAY)) {
            return;
        }
        GamesService games = running(sender);
        if (games == null) {
            return;
        }
        boolean answered = accept ? games.invites().accept(player) : games.invites().deny(player);
        if (!answered) {
            player.sendMessage(Text.of("&7You have no invite waiting."));
        }
    }

    /**
     * {@code /hcm play invites [on|off]}: show, or switch friend-game invites. {@code off} also
     * turns Coin Flip invites off; only the Take a break screen turns those on (R1.8).
     */
    private void invites(CommandSender sender, String[] args) {
        Player player = self(sender, "Only players get invites.");
        if (player == null || deny(sender, PLAY)) {
            return;
        }
        GamesService games = running(sender);
        if (games == null) {
            return;
        }
        UUID id = player.getUniqueId();
        if (args.length >= 3 && isOnOff(args[2])) {
            boolean on = args[2].equalsIgnoreCase("on");
            for (String game : Invites.FRIEND_GAMES) {
                games.invites().setAccepts(id, game, on);
            }
            if (!on) {
                games.invites().setAccepts(id, Invites.COIN_FLIP, false);
            }
            player.sendMessage(Text.of(on
                    ? "&aInvites to friend games are on. &7Coin Flip invites are set on the Take a break screen."
                    : "&7Game invites are off, Coin Flip too."));
            return;
        }
        List<String> parts = new ArrayList<>();
        for (String game : Invites.FRIEND_GAMES) {
            parts.add(inviteState(games, id, game));
        }
        parts.add(inviteState(games, id, Invites.COIN_FLIP));
        player.sendMessage(Text.of("&eYour invites: " + String.join("&7, ", parts)));
        player.sendMessage(Text.of("&7/hcm play invites on|off &8(Coin Flip: the Take a break screen)"));
    }

    /**
     * {@code /hcm play news [on|off]}: show, or switch the one chat line that says new Fresh Courses
     * are up ({@link com.dierks.homecraft.games.gen.NewCoursesNudge}). It is on unless turned off.
     */
    private void news(CommandSender sender, String[] args) {
        Player player = self(sender, "Only players get the new-courses line.");
        if (player == null || deny(sender, PLAY)) {
            return;
        }
        GamesService games = running(sender);
        if (games == null) {
            return;
        }
        UUID id = player.getUniqueId();
        String key = com.dierks.homecraft.games.gen.NewCoursesNudge.PREF_NEWS;
        try {
            if (args.length >= 3 && isOnOff(args[2])) {
                boolean on = args[2].equalsIgnoreCase("on");
                games.dao().setPref(id, key, on ? null : "off");
                player.sendMessage(Text.of(on ? "&aYou'll see a line in chat when new courses are up."
                        : "&7No more new-course lines in chat. &8(/hcm play news on)"));
                return;
            }
            boolean on = !"off".equalsIgnoreCase(games.dao().pref(id, key));
            player.sendMessage(Text.of("&eNew courses in chat: " + (on ? "&aon" : "&7off")
                    + " &7- /hcm play news on|off"));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read or save a player's news setting", e);
            failed(sender);
        }
    }

    /**
     * {@code /hcm play cup [on|off]}: show, or hide and show again, the Weekly Cup on the course
     * screens and tiles (EVENTS-OWNER-DECISIONS D2). A player who is in a Cup still hears how it went.
     */
    private void cup(CommandSender sender, String[] args) {
        Player player = self(sender, "Only players see the Weekly Cup.");
        if (player == null || deny(sender, PLAY) || running(sender) == null) {
            return;
        }
        com.dierks.homecraft.games.cup.live.CupLink.command(plugin, player, args);
    }

    private static String inviteState(GamesService games, UUID player, String gameId) {
        Game g = games.game(gameId);
        String name = g == null ? gameId : g.name();
        return "&f" + name + " " + (games.invites().accepts(player, gameId) ? "&aon" : "&7off");
    }

    // ---------------------------------------------------------------------
    //  /hcm leave
    // ---------------------------------------------------------------------

    /**
     * {@code /hcm leave}: end the world game you are in and go home with your things. Also retries
     * a return that didn't finish (a failed teleport, or things kept until you made room). It
     * works with the games off or failed to start too: getting your things back never switches
     * off (§7.6).
     */
    public void leave(CommandSender sender, String[] args) {
        Player player = self(sender, "Only players can leave a game.");
        if (player == null || deny(sender, PLAY)) {
            return;
        }
        WorldSessions.leaveCommand(plugin, player); // says "You're not in a game." when there is nothing
    }

    // ---------------------------------------------------------------------
    //  /hcm games …
    // ---------------------------------------------------------------------

    /** {@code /hcm games …}; {@code args} is the whole {@code /hcm} argument list. */
    public void admin(CommandSender sender, String[] args) {
        if (deny(sender, ADMIN)) {
            return;
        }
        String verb = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "help";
        if (verb.equals("break")) {
            breaks(sender, args); // works without the games service: it is its own
            return;
        }
        if (verb.equals("help")) {
            help(sender);
            return;
        }
        if (verb.equals("check")) {
            check(sender); // read-only, and works with the games off or failed to start
            return;
        }
        if (verb.equals("saved")) {
            // Works without the games service too: players' things come back whatever the switches say.
            log(sender, "saved " + String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
            WorldSessions.savedCommand(plugin, sender, Arrays.copyOfRange(args, 2, args.length));
            return;
        }
        GamesService games = plugin.games();
        if (games == null) {
            sender.sendMessage(Text.of("&cThe Games failed to start - check the console."));
            return;
        }
        switch (verb) {
            case "status" -> status(sender, games);
            case "feature" -> feature(sender, games, args);
            case "scores" -> scores(sender, games, args);
            default -> {
                Game owner = adminOwner(games, verb);
                if (owner == null) {
                    help(sender);
                    return;
                }
                GameAdmin tool = games.guard(owner, owner::admin, null);
                if (tool != null) {
                    String[] rest = Arrays.copyOfRange(args, 2, args.length);
                    games.guard(owner, () -> tool.handle(sender, rest));
                }
            }
        }
    }

    private void help(CommandSender sender) {
        for (String line : adminLines()) {
            sender.sendMessage(Text.of(line));
        }
    }

    /** {@code /hcm games status}: what is open, what isn't and why, and what is live right now. */
    private void status(CommandSender sender, GamesService games) {
        sender.sendMessage(Text.of("&6Games &7- " + (games.config().enabled() ? "&aon" : "&coff &7(games.enabled: false)")));
        for (Game g : games.games()) {
            String why = games.closedReason(g);
            sender.sendMessage(Text.of("&f" + g.id() + " &7(" + g.name() + ") - "
                    + (why == null ? "&aopen" : "&7closed: " + why)));
            if (why == null && g.kind().chance()) {
                for (String line : games.guard(g, g::oddsLines, List.<String>of())) {
                    sender.sendMessage(Text.of("  &8" + Text.plain(line)));
                }
            }
            if (why == null) {
                for (String line : games.guard(g, g::statusLines, List.<String>of())) {
                    sender.sendMessage(Text.of("  &7" + line));
                }
            }
        }
        games.unbuilt().forEach((id, error) ->
                sender.sendMessage(Text.of("&c" + id + " could not be built: &7" + error)));
        int inSession = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (games.sessions().session(p) != null) {
                inSession++;
            }
        }
        int saved = -1;
        try {
            saved = games.dao().livePlayers().size();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not count saved states", e);
        }
        int rounds = games.rounds().openCount();
        sender.sendMessage(Text.of("&7In a world game now: &f" + inSession + " &7- saved things waiting to go back: &f"
                + (saved < 0 ? "?" : saved) + " &7- unfinished rounds: &f" + (rounds < 0 ? "?" : rounds)));
        String featured = games.featured().today();
        sender.sendMessage(Text.of("&7Today's pick: &f" + (featured == null ? "none" : featured)
                + (games.config().common().featuredAuto() ? " &8(auto)" : " &8(pinned)")
                + " &7until &f" + Breaks.untilText(plugin.clock(), games.featured().until())));
        long now = games.clock().nowMillis();
        RestartHold hold = games.restartHold();
        sender.sendMessage(Text.of("&7" + hold.status(now) + (hold.holding(now) ? " &c- held now" : "")));
        log(sender, "status");
    }

    /**
     * {@code /hcm games check}: one line per check (OK, WARN or FAIL, with the fix), then "All good"
     * or how many things to fix. It only reads: see {@link GamesCheck}.
     */
    private void check(CommandSender sender) {
        List<String> lines;
        try {
            lines = GamesCheck.render(GamesCheck.run(new GamesCheckLive(plugin)));
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.SEVERE, "The games check failed", e);
            sender.sendMessage(Text.of("&cThe check couldn't run - see the console."));
            return;
        }
        for (String line : lines) {
            sender.sendMessage(Text.of(line));
        }
        log(sender, "check");
    }

    /** {@code /hcm games feature <id|auto>}: pin the featured game, or let the day pick again. */
    private void feature(CommandSender sender, GamesService games, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.of("&cUsage: /hcm games feature <game or course|auto>"));
            return;
        }
        String value = args[2].trim().toLowerCase(Locale.ROOT);
        if (!value.equals("auto")) {
            GamesService.Target t = games.resolve(value);
            if (t == null || !games.enabled(t.game())) {
                sender.sendMessage(Text.of("&cNo open game or course called '" + args[2] + "'."));
                return;
            }
            if (t.game().kind().chance() || !t.game().featurable()) {
                sender.sendMessage(Text.of("&cGames of chance are never featured - pick a skill game or a course."));
                return;
            }
            value = t.playable() == null ? t.game().id() : t.playable().id();
        }
        if (!plugin.writeConfig("games.featured", value)) {
            sender.sendMessage(Text.of("&cCould not write config.yml - see the console."));
            return;
        }
        plugin.config().load();
        // No games.reload(): that would close every open game of chance's screen. Today's pick
        // is worked out again by itself once games.featured reads differently.
        String today = games.featured().today();
        sender.sendMessage(Text.of(value.equals("auto")
                ? "&aThe day picks the featured game again. &7Today: &f" + (today == null ? "none" : today)
                : "&aFeatured every day: &f" + value + "&a."));
        log(sender, "feature " + value);
    }

    // ---- break ------------------------------------------------------------------------------

    /** {@code /hcm games break <player> show|pause <days>|limit <tokens|none>|clear|clear-own confirm}. */
    private void breaks(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Text.of("&cUsage: /hcm games break <player> show|pause <days>|limit <tokens|none>|clear"
                    + "|clear-own confirm"));
            return;
        }
        Breaks breaks = plugin.breaks();
        if (breaks == null) {
            sender.sendMessage(Text.of("&cTake a break isn't available - check the console."));
            return;
        }
        OfflinePlayer target = offline(sender, args[2]);
        if (target == null) {
            return;
        }
        UUID id = target.getUniqueId();
        String name = target.getName() == null ? args[2] : target.getName();
        String verb = args[3].toLowerCase(Locale.ROOT);
        switch (verb) {
            case "show" -> {
                showBreak(sender, breaks, id, name);
                log(sender, "break " + name + " show");
            }
            case "pause" -> {
                int days = args.length >= 5 ? parse(args[4], 1, Breaks.MAX_PAUSE_DAYS) : -1;
                if (days < 0) {
                    sender.sendMessage(Text.of("&cUsage: /hcm games break " + name + " pause <days 1-"
                            + Breaks.MAX_PAUSE_DAYS + ">"));
                    return;
                }
                if (!breaks.setAdminPause(id, days)) {
                    failed(sender);
                    return;
                }
                sender.sendMessage(Text.of("&aGames of chance are paused for &f" + name + " &auntil &f"
                        + Breaks.untilText(plugin.clock(), adminPausedUntil(breaks, id))
                        + "&a. &7Only an admin can lift it (/hcm games break " + name + " clear)."));
                log(sender, "break " + name + " pause " + days);
            }
            case "limit" -> {
                int tokens = args.length >= 5 ? (args[4].equalsIgnoreCase("none") ? Breaks.NO_LIMIT
                        : parse(args[4], 0, com.dierks.homecraft.config.GamesConfig.MAX_CHANCE_DAILY_TOKENS)) : -2;
                if (tokens < Breaks.NO_LIMIT) {
                    sender.sendMessage(Text.of("&cUsage: /hcm games break " + name + " limit <tokens|none>"));
                    return;
                }
                if (!breaks.setAdminLimit(id, tokens)) {
                    failed(sender);
                    return;
                }
                sender.sendMessage(Text.of(tokens == Breaks.NO_LIMIT
                        ? "&aRemoved the admin limit for &f" + name + "&a."
                        : "&aThe most &f" + name + " &acan put into games of chance is now &6" + tokens
                                + " &atokens a day."));
                log(sender, "break " + name + " limit " + (tokens == Breaks.NO_LIMIT ? "none" : tokens));
            }
            case "clear" -> {
                if (!breaks.clearAdmin(id)) {
                    failed(sender);
                    return;
                }
                sender.sendMessage(Text.of("&aCleared the admin limit and pause for &f" + name
                        + "&a. &7Their own settings stay."));
                log(sender, "break " + name + " clear");
            }
            case "clear-own" -> {
                if (args.length < 5 || !args[4].equalsIgnoreCase("confirm")) {
                    sender.sendMessage(Text.of("&eThis lifts the break &f" + name + " &eset for THEMSELVES (their pause,"
                            + " their limit and any change waiting). &7If you are sure: /hcm games break " + name
                            + " clear-own confirm"));
                    return;
                }
                if (!breaks.clearOwn(id)) {
                    failed(sender);
                    return;
                }
                sender.sendMessage(Text.of("&aCleared &f" + name + "&a's own Take a break settings."));
                plugin.getLogger().warning("[Games] " + sender.getName() + " lifted " + name
                        + "'s OWN Take a break settings (pause, limit and pending change)");
            }
            default -> sender.sendMessage(Text.of("&cUsage: /hcm games break <player> show|pause <days>|limit "
                    + "<tokens|none>|clear|clear-own confirm"));
        }
    }

    private void showBreak(CommandSender sender, Breaks breaks, UUID id, String name) {
        GamesDao.BreakRow row = breaks.row(id);
        if (row == null) {
            sender.sendMessage(Text.of("&cCould not read " + name + "'s Take a break settings - games of chance "
                    + "are closed to them until it can be."));
            return;
        }
        GameClock clock = plugin.clock();
        long now = clock.nowMillis();
        GamesService games = plugin.games();
        boolean on = games != null && games.config().enabled();
        int server = on ? plugin.config().games().common().chanceDailyTokens() : 0;
        sender.sendMessage(Text.of("&6Take a break &7- &f" + name));
        String pending = "";
        if (row.pendingTokens() != Breaks.NO_PENDING) {
            pending = " &8(changing to " + limitText(row.pendingTokens()) + " on " + Breaks.dateText(row.pendingDay())
                    + ")";
        }
        sender.sendMessage(Text.of("&7Their own limit: &f" + limitText(row.dailyTokens()) + pending));
        sender.sendMessage(Text.of("&7Admin limit: &f" + limitText(row.adminTokens())));
        sender.sendMessage(Text.of("&7Server limit: &f" + (server > 0 ? server + " a day" : "off")
                + (on ? "" : " &8(it only counts while the games are on)")));
        sender.sendMessage(Text.of("&7Today: &f" + breaks.tokensInToday(id) + " &7put in, limit &f"
                + limitText(Breaks.effectiveLimit(row.dailyTokens(), row.adminTokens(), server))));
        sender.sendMessage(Text.of("&7Their own pause: &f" + pauseText(clock, row.pausedUntil(), now)));
        sender.sendMessage(Text.of("&7Admin pause: &f" + pauseText(clock, row.adminPausedUntil(), now)));
    }

    private static long adminPausedUntil(Breaks breaks, UUID id) {
        GamesDao.BreakRow row = breaks.row(id);
        return row == null ? 0 : row.adminPausedUntil();
    }

    private static String limitText(int tokens) {
        return tokens < 0 ? "none" : tokens + " a day";
    }

    private static String pauseText(GameClock clock, long until, long now) {
        return until > now ? "until " + Breaks.untilText(clock, until) : "none";
    }

    // ---- scores -----------------------------------------------------------------------------

    /**
     * What {@code /hcm games scores reset <game> [board|all] [player] [confirm]} asks for.
     *
     * @param game    the game id
     * @param board   one board, or {@code null} for every board of the game
     * @param player  one player's name, or {@code null} for everyone
     * @param confirm false = a dry run that only counts
     */
    record Reset(String game, String board, String player, boolean confirm) {

        /** Parse the words after {@code reset}; {@code null} when the game is missing. */
        static Reset parse(String[] words) {
            List<String> w = new ArrayList<>(Arrays.asList(words));
            boolean confirm = !w.isEmpty() && w.get(w.size() - 1).equalsIgnoreCase("confirm");
            if (confirm) {
                w.remove(w.size() - 1);
            }
            if (w.isEmpty() || w.get(0).isBlank()) {
                return null;
            }
            String board = w.size() >= 2 && !w.get(1).equalsIgnoreCase("all") ? w.get(1) : null;
            String player = w.size() >= 3 ? w.get(2) : null;
            return new Reset(w.get(0).toLowerCase(Locale.ROOT), board, player, confirm);
        }
    }

    /** {@code /hcm games scores reset …}: a dry run that counts first; {@code confirm} clears. */
    private void scores(CommandSender sender, GamesService games, String[] args) {
        Reset reset = args.length >= 3 && args[2].equalsIgnoreCase("reset")
                ? Reset.parse(Arrays.copyOfRange(args, 3, args.length)) : null;
        if (reset == null) {
            sender.sendMessage(Text.of("&cUsage: /hcm games scores reset <game> [board|all] [player] [confirm]"));
            return;
        }
        Game game = games.game(reset.game());
        String gameId = game != null ? game.id() : GameCatalog.spec(reset.game()) != null ? reset.game() : null;
        if (gameId == null) {
            sender.sendMessage(Text.of("&cNo game called '" + reset.game() + "'."));
            return;
        }
        UUID player = null;
        String who = "everyone";
        if (reset.player() != null) {
            OfflinePlayer p = offline(sender, reset.player());
            if (p == null) {
                return;
            }
            player = p.getUniqueId();
            who = p.getName() == null ? reset.player() : p.getName();
        }
        String what = gameId + " " + (reset.board() == null ? "(every board)" : reset.board()) + " for " + who;
        try {
            if (!reset.confirm()) {
                int n = count(games.dao(), gameId, reset.board(), player);
                sender.sendMessage(Text.of("&eThis would clear &f" + n + " &escore" + (n == 1 ? "" : "s") + ": &f" + what
                        + "&e. &7Add confirm at the end to do it."));
                return;
            }
            int n = games.dao().resetScores(gameId, reset.board(), player);
            sender.sendMessage(Text.of("&aCleared &f" + n + " &ascore" + (n == 1 ? "" : "s") + ": &f" + what + "&a."));
            plugin.getLogger().warning("[Games] " + sender.getName() + " cleared " + n + " score(s): " + what);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not reset scores", e);
            failed(sender);
        }
    }

    /** How many score rows a reset would clear. */
    private static int count(GamesDao dao, String game, String board, UUID player) throws SQLException {
        int n = 0;
        for (String b : board != null ? List.of(board) : dao.boards(game)) {
            for (GamesDao.ScoreRow row : dao.top(game, b, false, Integer.MAX_VALUE)) {
                if (player == null || player.equals(row.player())) {
                    n++;
                }
            }
        }
        return n;
    }

    // ---------------------------------------------------------------------
    //  /hcm arcade odds
    // ---------------------------------------------------------------------

    /**
     * {@code /hcm arcade odds} (hcm.arcade.use, R1.21): players get one line per open game of
     * chance — how much it gives back, from the same engine it plays with; admins also get the
     * per-stake detail and the Scratch Ticket and crate values.
     */
    public void odds(CommandSender sender) {
        if (deny(sender, "hcm.arcade.use")) {
            return;
        }
        boolean admin = sender.hasPermission("hcm.admin");
        if (!admin && !sender.hasPermission(Breaks.PERMISSION_CHANCE)) {
            sender.sendMessage(Text.of("&7Games of chance aren't open to you."));
            return;
        }
        List<String> lines = new ArrayList<>();
        GamesService games = plugin.games();
        if (games != null) {
            for (Game g : games.games()) {
                if (!g.kind().chance() || !games.enabled(g)) {
                    continue;
                }
                List<String> odds = games.guard(g, g::oddsLines, List.<String>of());
                if (!odds.isEmpty()) {
                    lines.addAll(admin ? odds : odds.subList(0, 1));
                }
            }
        }
        if (!lines.isEmpty()) {
            sender.sendMessage(Text.of("&6Games of chance &7- what each gives back:"));
            for (String line : lines) {
                sender.sendMessage(Text.of(line));
            }
        } else if (!admin) {
            sender.sendMessage(Text.of("&7No games of chance are open right now."));
        }
        if (admin && plugin.arcade() != null) {
            for (String line : plugin.arcade().oddsReport()) {
                sender.sendMessage(Text.of(line));
            }
        }
    }

    // ---------------------------------------------------------------------
    //  help and tab completion
    // ---------------------------------------------------------------------

    /** The {@code &e/hcm …} usage lines this sender may use. */
    public List<String> helpLines(CommandSender sender) {
        List<String> out = new ArrayList<>();
        if (sender.hasPermission(PLAY)) {
            out.add("&e/hcm play [game] &7- the Games screen, or open a game or course");
            out.add("&e/hcm play break &7- Take a break: your own limits on games of chance");
            out.add("&e/hcm play accept|deny &7- answer a game invite");
            out.add("&e/hcm play invites [on|off] &7- invites to friend games");
            out.add("&e/hcm play race <course> &7- race a course with friends (free, just for fun)"); // WP-R1 (D4)
            out.add("&e/hcm play news [on|off] &7- a line in chat when new courses are up");
            out.add("&e/hcm play cup [on|off] &7- the Weekly Cup on the course screens");
            out.add("&e/hcm leave &7- leave the world game you're in (your things come back)");
        }
        if (sender.hasPermission(ADMIN)) {
            out.addAll(adminLines());
        }
        return out;
    }

    private List<String> adminLines() {
        List<String> out = new ArrayList<>();
        out.add("&e/hcm play <game> <player> &7- open a game for someone (NPCs, the hub)");
        out.add("&e/hcm games status &7- which games are open, and why not");
        out.add("&e/hcm games check &7- is the server set up for the games? (changes nothing)");
        out.add("&e/hcm games feature <game|course|auto> &7- pin the featured game, or let the day pick");
        out.add("&e/hcm games break <player> show|pause <days>|limit <n|none>|clear|clear-own confirm &7- a player's"
                + " Take a break");
        out.add("&e/hcm games scores reset <game> [board|all] [player] [confirm] &7- clear high scores");
        out.add("&e/hcm games saved <player> show|restore|return|discard confirm &7- a player's saved things");
        GamesService games = plugin.games();
        if (games != null) {
            for (Game g : games.games()) {
                GameAdmin tool = games.guard(g, g::admin, null);
                if (tool != null) {
                    out.addAll(games.guard(g, tool::help, List.<String>of()));
                }
            }
        }
        return out;
    }

    /** Completions for {@code /hcm play|leave|games …}; {@code args} is the whole argument list. */
    public List<String> complete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length < 2) {
            return out;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1];
        int n = args.length;
        GamesService games = plugin.games();
        if (first.equals("play")) {
            if (!sender.hasPermission(PLAY) && !sender.hasPermission(ADMIN)) {
                return out;
            }
            if (n == 2) {
                match(out, last, PLAY_WORDS.toArray(new String[0]));
                if (games != null) {
                    playIds(out, games, last);
                }
            } else if (n == 3 && (args[1].equalsIgnoreCase("invites") || args[1].equalsIgnoreCase("news")
                    || args[1].equalsIgnoreCase("cup"))) {
                match(out, last, "on", "off");
            } else if (n == 3 && args[1].equalsIgnoreCase("race") && games != null) { // WP-R1 (D4)
                match(out, last, com.dierks.homecraft.games.trial.PartyRaces.courseIds(games).toArray(new String[0]));
                if (!(sender instanceof Player) || sender.hasPermission(ADMIN)) {
                    players(out, last);
                }
            } else if (n == 3 && !PLAY_WORDS.contains(args[1].toLowerCase(Locale.ROOT))
                    && (!(sender instanceof Player) || sender.hasPermission(ADMIN))) {
                players(out, last);
            }
            return out;
        }
        if (!first.equals("games") || !sender.hasPermission(ADMIN)) {
            return out;
        }
        if (n == 2) {
            match(out, last, VERBS.toArray(new String[0]));
            match(out, last, "help");
            if (games != null) {
                for (Game g : games.games()) {
                    GameAdmin tool = games.guard(g, g::admin, null);
                    if (tool != null) {
                        match(out, last, tool.name());
                    }
                }
            }
            return out;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "feature" -> {
                if (n == 3) {
                    match(out, last, "auto");
                    if (games != null) {
                        for (String id : games.featured().candidates()) {
                            match(out, last, id);
                        }
                    }
                }
            }
            case "break" -> {
                if (n == 3) {
                    players(out, last);
                } else if (n == 4) {
                    match(out, last, BREAK_VERBS.toArray(new String[0]));
                } else if (n == 5) {
                    switch (args[3].toLowerCase(Locale.ROOT)) {
                        case "pause" -> match(out, last, "1", "7", "30");
                        case "limit" -> match(out, last, "none", "10", "25", "50", "100");
                        case "clear-own" -> match(out, last, "confirm");
                        default -> {
                            // nothing to offer
                        }
                    }
                }
            }
            case "scores" -> scoreCompletions(out, games, args, last);
            case "saved" -> {
                if (n == 3) {
                    players(out, last);
                } else if (n == 4) {
                    match(out, last, SAVED_VERBS.toArray(new String[0]));
                } else if (n == 5 && args[3].equalsIgnoreCase("discard")) {
                    match(out, last, "confirm");
                }
            }
            default -> {
                Game owner = games == null ? null : adminOwner(games, args[1]);
                GameAdmin tool = owner == null ? null : games.guard(owner, owner::admin, null);
                if (tool != null) {
                    String[] rest = Arrays.copyOfRange(args, 2, args.length);
                    List<String> offered = games.guard(owner, () -> tool.tab(sender, rest), List.<String>of());
                    if (offered != null) {
                        out.addAll(offered);
                    }
                }
            }
        }
        return out;
    }

    private void scoreCompletions(List<String> out, GamesService games, String[] args, String last) {
        int n = args.length;
        if (n == 3) {
            match(out, last, "reset");
            return;
        }
        if (!args[2].equalsIgnoreCase("reset") || games == null) {
            return;
        }
        if (n == 4) {
            for (Game g : games.games()) {
                match(out, last, g.id());
            }
        } else if (n == 5) {
            match(out, last, "all");
            Game g = games.game(args[3]);
            if (g != null) {
                try {
                    for (String board : games.dao().boards(g.id())) {
                        match(out, last, board);
                    }
                } catch (SQLException e) {
                    // no boards to offer
                }
            }
            match(out, last, "confirm");
        } else if (n == 6) {
            players(out, last);
            match(out, last, "confirm");
        } else if (n == 7) {
            match(out, last, "confirm");
        }
    }

    /** Every open game's id and every open course's id. */
    private static void playIds(List<String> out, GamesService games, String prefix) {
        for (Game g : games.games()) {
            if (!games.enabled(g)) {
                continue;
            }
            match(out, prefix, g.id());
            for (Game.Playable p : games.guard(g, g::playables, List.<Game.Playable>of())) {
                match(out, prefix, p.id());
            }
        }
    }

    // ---------------------------------------------------------------------
    //  helpers
    // ---------------------------------------------------------------------

    /** The game whose admin tool is called {@code name} ({@code course}, {@code golf}), or {@code null}. */
    private static Game adminOwner(GamesService games, String name) {
        for (Game g : games.games()) {
            GameAdmin tool = games.guard(g, g::admin, null);
            if (tool != null && tool.name().equalsIgnoreCase(name)) {
                return g;
            }
        }
        return null;
    }

    /**
     * A player by name who has played here, online or not (parents set limits for a child who
     * isn't on); tells the sender and returns {@code null} otherwise.
     */
    private static OfflinePlayer offline(CommandSender sender, String name) {
        OfflinePlayer p = Bukkit.getOfflinePlayerIfCached(name);
        if (p == null || !(p.hasPlayedBefore() || p.isOnline())) {
            sender.sendMessage(Text.of("&cNo player called " + name + " has played here."));
            return null;
        }
        return p;
    }

    /** The service, or {@code null} after telling the sender it isn't there. */
    private GamesService running(CommandSender sender) {
        GamesService games = plugin.games();
        if (games == null) {
            sender.sendMessage(Text.of("&cThe games aren't available right now."));
        }
        return games;
    }

    private static Player self(CommandSender sender, String notAPlayer) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(Text.of("&c" + notAPlayer));
        return null;
    }

    /** A whole number in {@code [lo, hi]}, or -2 when it isn't one. */
    static int parse(String raw, int lo, int hi) {
        try {
            int v = Integer.parseInt(raw.trim());
            return v < lo || v > hi ? -2 : v;
        } catch (NumberFormatException e) {
            return -2;
        }
    }

    private static void failed(CommandSender sender) {
        sender.sendMessage(Text.of("&cThat couldn't be saved - see the console."));
    }

    /** Who did what, for the console: every admin action on the Games is logged with the sender. */
    private void log(CommandSender sender, String what) {
        plugin.getLogger().info("[Games] " + what + " (by " + sender.getName() + ")");
    }

    private static boolean isOnOff(String s) {
        return s.equalsIgnoreCase("on") || s.equalsIgnoreCase("off");
    }

    private static boolean deny(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return false;
        }
        sender.sendMessage(Text.of("&cYou don't have permission."));
        return true;
    }

    private static void players(List<String> out, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(p.getName());
            }
        }
    }

    private static void match(List<String> out, String prefix, String... options) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option != null && option.toLowerCase(Locale.ROOT).startsWith(lower) && !out.contains(option)) {
                out.add(option);
            }
        }
    }
}
