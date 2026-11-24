package com.dierks.homecraft.games.chance.holdem;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Texas Hold'em, the Game Parlor's card table for a group (0.57.0; the owner: "It would be pretty great if we could
 * play as a group around the table!! In fact that would be amazing for any Table game so its much more a social
 * event"). Reachable as {@code /hcm play holdem} (or {@code poker}) and as the Parlor machine {@code machine:holdem}.
 *
 * <p>Two to six players at a table play no-limit Hold'em against each other ({@link HoldemHand}); the Arcade only deals,
 * and keeps nothing. Opening it shows its three tables ({@link HoldemTable.Kind}): the friendly table plays free play
 * chips with NPC players welcome, the tokens and money tables are played for stakes, real players only. A player sits
 * at one table at a time; opening the game again while seated goes straight back to that table.
 *
 * <p>It is a game of chance in every way the rules care about: the games-of-chance permission, the Parlor, Take a
 * break, the click cooldown and, at a stakes table, the buy-ins a day ({@code daily_limit}), the day's token or money
 * limit, and the balance, all at the buy-in. A stakes seat is a crash-safe round ({@link HoldemTable}), finished by
 * {@link HoldemTable#settleOnExit} when it is left anywhere but the table.
 */
public final class Holdem implements Game {

    public static final GameSpec<HoldemSettings> SPEC = new GameSpec<>(HoldemSettings.ID, GameKind.CHANCE,
            HoldemSettings.KEYS, HoldemSettings.defaults(), HoldemSettings::parse, Holdem::new,
            HoldemTable::settleOnExit);

    /** The player-facing name. */
    public static final String NAME = "Texas Hold'em";

    private final GameContext ctx;
    private final Map<HoldemTable.Kind, HoldemTable> tables = new EnumMap<>(HoldemTable.Kind.class);
    private final Admin admin = new Admin();

    public Holdem(GameContext ctx) {
        this.ctx = ctx;
        for (HoldemTable.Kind k : HoldemTable.Kind.values()) {
            tables.put(k, new HoldemTable(this, k));
        }
    }

    @Override
    public String id() {
        return SPEC.id();
    }

    @Override
    public GameKind kind() {
        return SPEC.kind();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.ARCADE_HOLDEM;
    }

    @Override
    public List<String> aliases() {
        return List.of("poker", "texas_holdem");
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled();
    }

    @Override
    public List<String> rules() {
        return List.of("Make the best five cards from your two and the five on the table.",
                "Each round: Fold, Check or Call, Raise, or go All in.",
                "The blinds move one seat round the table every hand.",
                "You play the other players: the Arcade keeps nothing.",
                "Friendly table: free play chips, and NPC players join in.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        HoldemSettings s = settings();
        List<String> lore = new ArrayList<>();
        for (String line : rules()) {
            lore.add("&7" + line);
        }
        lore.add("&7Blinds " + s.blinds() + " · tokens table: " + buyInText(s) + " tokens in");
        lore.add("&e" + s.dailyLimit() + " buy-ins a day");
        return Menus.icon(Material.PAPER, "&aTexas Hold'em &7- 2 to 6 players", lore.toArray(new String[0]));
    }

    static String buyInText(HoldemSettings s) {
        List<Integer> b = s.buyIns();
        return b.isEmpty() ? "no" : b.size() == 1 ? Integer.toString(b.get(0)) : b.get(0) + " to " + b.get(b.size() - 1);
    }

    /** Back to the table they sit at, or the three tables to pick from. */
    @Override
    public void open(Player player, Runnable back) {
        if (ctx.plugin() == null) {
            return; // the framework's own tests: no screens
        }
        HoldemTable at = tableOf(player.getUniqueId());
        if (at != null) {
            new com.dierks.homecraft.gui.games.chance.HoldemMenu(ctx.plugin(), this, at, player, back).open(player);
            return;
        }
        new com.dierks.homecraft.gui.games.chance.HoldemLobbyMenu(ctx.plugin(), this, player, back).open(player);
    }

    /** A player who leaves the server is out of their hand and up from their table, with their chips. */
    @Override
    public void onQuit(Player player) {
        for (HoldemTable t : tables.values()) {
            t.quit(player.getUniqueId());
        }
    }

    @Override
    public void start() {
        if (ctx.plugin() != null) {
            games().every(this, 20, 20, this::second);
        }
    }

    /**
     * Stopped (switched off, failed, the server stopping): the tables are forgotten. Every stakes seat's round still
     * says its chips as of the end of the last hand, and the framework finishes it by the exit rule (now, or at the
     * next start).
     */
    @Override
    public void stop() {
        for (HoldemTable t : tables.values()) {
            t.forget();
        }
    }

    /** Once a second: every table's clocks and deals, and the friendly table's NPCs while they are on. */
    public void second() {
        CpuPlayers npcs = CpuPlayers.of(games());
        for (HoldemTable t : tables.values()) {
            t.tick(npcs);
        }
    }

    /** No odds line: nothing is kept, and what comes back depends on the other players, not on the Arcade. */
    @Override
    public List<String> oddsLines() {
        return List.of("&6Texas Hold'em &7— players play each other; every chip put in goes back to someone at the"
                + " table");
    }

    @Override
    public GameAdmin admin() {
        return admin;
    }

    @Override
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        for (HoldemTable t : tables.values()) {
            out.add(Text.plain(t.statusLine()));
        }
        return out;
    }

    // ---- what the screens read ----------------------------------------------------------------------------

    public HoldemTable table(HoldemTable.Kind kind) {
        return tables.get(kind);
    }

    /** The table {@code player} sits at, or {@code null}. */
    public HoldemTable tableOf(java.util.UUID player) {
        for (HoldemTable t : tables.values()) {
            if (t.seat(player) != null) {
                return t;
            }
        }
        return null;
    }

    /** The live settings (read on every use, never cached across a reload). */
    public HoldemSettings settings() {
        return ctx.games().settings(SPEC);
    }

    public GamesService games() {
        return ctx.games();
    }

    // ---- the admin commands -------------------------------------------------------------------------------

    /** {@code /hcm games holdem status|stop [friendly|tokens|money|all]}. */
    final class Admin implements GameAdmin {

        @Override
        public String name() {
            return SPEC.id();
        }

        @Override
        public void handle(CommandSender sender, String[] args) {
            String verb = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
            switch (verb) {
                case "status" -> {
                    sender.sendMessage(Text.of("&6Texas Hold'em &7(blinds " + settings().blinds() + ")"));
                    for (HoldemTable t : tables.values()) {
                        sender.sendMessage(Text.of("&7- " + t.statusLine()));
                    }
                }
                case "stop" -> {
                    String which = args.length < 2 ? "all" : args[1].toLowerCase(Locale.ROOT);
                    HoldemTable.Kind k = HoldemTable.Kind.of(which);
                    if (k == null && !which.equals("all")) {
                        sender.sendMessage(Text.of("&cUsage: /hcm games holdem stop [friendly|tokens|money|all]"));
                        return;
                    }
                    int n = 0;
                    for (HoldemTable t : tables.values()) {
                        if (k == null || t.kind() == k) {
                            n += t.stopAll();
                        }
                    }
                    games().logger().info("[Hold'em] " + sender.getName() + " stopped " + which + ": " + n
                            + " seat(s) got up");
                    sender.sendMessage(Text.of("&aStopped " + (k == null ? "every table" : "the " + k.label)
                            + ": &f" + n + " &aseat" + (n == 1 ? "" : "s") + " got up, every chip paid back."));
                }
                default -> {
                    for (String line : help()) {
                        sender.sendMessage(Text.of(line));
                    }
                }
            }
        }

        @Override
        public List<String> tab(CommandSender sender, String[] args) {
            List<String> out = new ArrayList<>();
            String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> options = args.length <= 1 ? List.of("status", "stop")
                    : args.length == 2 && args[0].equalsIgnoreCase("stop")
                    ? List.of("friendly", "tokens", "money", "all") : List.of();
            for (String o : options) {
                if (o.startsWith(last)) {
                    out.add(o);
                }
            }
            return out;
        }

        @Override
        public List<String> help() {
            return List.of("&e/hcm games holdem status &7- each table: its seats, chips and hand",
                    "&e/hcm games holdem stop [friendly|tokens|money|all] &7- close a table: a hand in play is called"
                            + " off and everyone gets up with their chips");
        }
    }
}
