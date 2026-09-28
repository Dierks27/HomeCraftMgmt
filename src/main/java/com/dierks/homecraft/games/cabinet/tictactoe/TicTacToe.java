package com.dierks.homecraft.games.cabinet.tictactoe;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.Invite;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.CabinetSettings;
import com.dierks.homecraft.games.cabinet.connect.FriendGames;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.gui.games.cabinet.tictactoe.TicTacToeMenu;
import com.dierks.homecraft.gui.games.cabinet.tictactoe.TicTacToePlayMenu;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tic-Tac-Toe (spec §10b, R1.22, R2.13): three in a row on 3 by 3, against the Arcade or a friend.
 *
 * <p><b>Against the Arcade</b> ({@link TicTacToeAI}): easy is random-ish and can be beaten; hard
 * plays perfectly, so it can't be, and the screens say so up front — a draw is the best result.
 * The day's first win on easy, or draw-or-better on hard, pays {@code daily_reward} (the daily
 * challenge, once a day); the {@code wins} board counts wins against the Arcade. No milestones.
 *
 * <p><b>Against a friend</b>: an invite through the framework's {@code Invites}, exactly as in
 * Connect Four (the same {@link FriendGames} bookkeeping). Friend games pay nothing and count on
 * no board.
 */
public final class TicTacToe extends CabinetGame {

    /** Built and working. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<TicTacToeSettings> SPEC = new GameSpec<>("tic_tac_toe", GameKind.CABINET,
            TicTacToeSettings.KEYS, TicTacToeSettings.defaults(), TicTacToeSettings::parse,
            TicTacToe::new, null);

    /** The one board: wins against the Arcade (more is better). */
    public static final String BOARD = "wins";
    /** The feed's unit. */
    static final String UNIT = "wins";
    /** How long a friend has to answer an invite. */
    static final int INVITE_SECONDS = 60;

    private final FriendGames<TicTacToeMatch> friends = new FriendGames<>();

    public TicTacToe(GameContext ctx) {
        super(ctx);
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
        return "Tic-Tac-Toe";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_TICTACTOE;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Get three in a row.",
                "Play the Arcade (easy or hard) or a friend.",
                "Hard never loses: a draw is the best result!",
                "Today's first easy win, or hard draw, pays a token.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        Long wins = wins(viewer);
        String fact = wins == null || wins == 0 ? "three in a row" : wins + " win" + (wins == 1 ? "" : "s");
        return Menus.icon(Material.ITEM_FRAME, "&b" + name() + " &7- " + fact,
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        new TicTacToeMenu(ctx.plugin(), this, player, back).open(player);
    }

    @Override
    public void feed(FeedWriter out) {
        GamesDao.ScoreRow record = games().scores().record(id(), BOARD, false);
        out.cabinet(id(), name(), BOARD, UNIT, false, record == null ? null : record.score(),
                record != null && out.showNames() ? Bukkit.getOfflinePlayer(record.player()).getName() : null);
    }

    @Override
    public void onQuit(Player player) {
        leaveFriendGame(player, friends.of(player.getUniqueId()), true);
        friends.quit(player.getUniqueId());
    }

    @Override
    public void stop() {
        friends.clear();
    }

    @Override
    protected CabinetSettings cabinetSettings() {
        return settings();
    }

    /** The live settings (read on every use, never cached across a reload). */
    public TicTacToeSettings settings() {
        return games().settings(SPEC);
    }

    // ---- against the Arcade -------------------------------------------------------------------

    /** A new game against the Arcade, with its own tie-break seed. */
    public TicTacToeMatch vsArcade(Player player, TicTacToeAI.Level level) {
        return TicTacToeMatch.vsArcade(player.getUniqueId(), level, ThreadLocalRandom.current().nextLong());
    }

    /** The player's wins against the Arcade, or {@code null}. */
    public Long wins(Player player) {
        return games().scores().best(player.getUniqueId(), id(), BOARD);
    }

    /** Whether today's token is still there for the player. */
    public boolean rewardLeft(Player player) {
        if (settings().dailyReward() <= 0) {
            return false;
        }
        try {
            return !games().dao().rewardPaid(player.getUniqueId(), id(), RewardKind.DAILY_CHALLENGE,
                    SkillRewards.dailyRef(today()));
        } catch (SQLException e) {
            return true;
        }
    }

    /** The {@code wins} board. */
    public void scores(Player player, Runnable back) {
        openScores(player, BOARD, false, back);
    }

    /**
     * A game against the Arcade is over: a win goes on the board, the day's first easy win or
     * hard draw-or-better pays the daily reward, and any finished game can pay today's featured
     * bonus.
     */
    public void finish(Player player, TicTacToeMatch match) {
        UUID id = player.getUniqueId();
        TicTacToeMatch.Outcome outcome = match.outcome(id);
        boolean hard = match.level() == TicTacToeAI.Level.HARD;
        switch (outcome) {
            case WON -> player.sendMessage(Text.of("&aThree in a row! You beat the Arcade."));
            case LOST -> player.sendMessage(Text.of("&7The Arcade got three in a row this time."));
            case DRAW -> player.sendMessage(Text.of(hard
                    ? "&aA draw against the hard Arcade: the best anyone can do!"
                    : "&7A draw: the board is full."));
            default -> {
                return;
            }
        }
        if (outcome == TicTacToeMatch.Outcome.WON) {
            Long wins = wins(player);
            long total = (wins == null ? 0 : wins) + 1;
            Finish f = finishClassic(player, BOARD, total, false);
            player.sendMessage(Text.of("&eWins: &f" + total + (f.result().record() ? " &6★ Most on the server!" : "")));
        } else {
            featuredBonus(player);
        }
        if (match.earnsDaily(id)) {
            TicTacToeSettings s = settings();
            games().rewards().pay(player, this, source(), RewardKind.DAILY_CHALLENGE, SkillRewards.dailyRef(today()),
                    s.dailyReward(), s.dailyCap(), name() + (hard ? ": today's hard draw" : ": today's win"));
        }
        if (outcome == TicTacToeMatch.Outcome.WON || (hard && outcome == TicTacToeMatch.Outcome.DRAW)) {
            Sounds.won(player);
        } else if (outcome == TicTacToeMatch.Outcome.LOST) {
            Sounds.miss(player);
        }
    }

    /** Today's featured bonus for finishing a game, if this is today's pick (once a day across games). */
    private void featuredBonus(Player player) {
        int bonus = games().config().common().featuredBonus();
        if (bonus > 0 && games().featured().isFeatured(id())) {
            games().rewards().pay(player, this, source(), RewardKind.FEATURED, SkillRewards.featuredRef(today()),
                    bonus, settings().dailyCap(), name() + ": today's pick");
        }
    }

    // ---- against a friend ---------------------------------------------------------------------

    /** Whether {@code other} may be offered to {@code player} in the picker. */
    public boolean canInvite(Player player, Player other) {
        if (other == null || !other.isOnline()) {
            return false;
        }
        UUID them = other.getUniqueId();
        return friends.canInvite(player.getUniqueId(), them, games().invites().accepts(them, id()),
                games().invites().pending(them) != null, games().canOpen(other, this) == null);
    }

    /**
     * Open the shared player picker (only players {@link #canInvite} accepts are listed); the one
     * picked gets an invite, then {@code after} runs.
     */
    public void pickFriend(Player player, Runnable back, Runnable after) {
        games().screens().pickPlayer(player, this, other -> canInvite(player, other), chosen -> {
            invite(player, chosen);
            after.run();
        }, back);
    }

    /** Send {@code to} an invite from {@code from}; the answer starts the game or tells {@code from}. */
    public void invite(Player from, Player to) {
        if (!canInvite(from, to)) {
            from.sendMessage(Text.of("&cThat invite couldn't be sent."));
            Sounds.refused(from);
            return;
        }
        Invite sent = games().invites().send(from, to, this, "a game of Tic-Tac-Toe, just for fun", INVITE_SECONDS,
                (invite, yes) -> games().guard(this, () -> answered(invite, yes)));
        if (sent == null) {
            from.sendMessage(Text.of("&cThat invite couldn't be sent."));
            Sounds.refused(from);
            return;
        }
        friends.invited(from.getUniqueId(), to.getUniqueId());
        from.sendMessage(Text.of("&aInvite sent to " + to.getName() + ". &7Waiting for an answer..."));
    }

    /** Who the player's invite is waiting on, or {@code null}. */
    public String waitingOn(Player player) {
        UUID other = friends.waitingOn(player.getUniqueId());
        if (other == null) {
            return null;
        }
        OfflinePlayer p = Bukkit.getOfflinePlayer(other);
        return p.getName() == null ? "a friend" : p.getName();
    }

    /**
     * Call off the player's own invite — only the one they sent (an invite waiting for them
     * stays), and with no "didn't take your invite" line, since they took it back themselves.
     */
    public void cancelInvite(Player player) {
        friends.answered(player.getUniqueId());
        games().invites().cancelFrom(player.getUniqueId());
    }

    /** Whether the player takes friend invites to this game. */
    public boolean takesInvites(Player player) {
        return games().invites().accepts(player.getUniqueId(), id());
    }

    /** Turn the player's friend invites to this game on or off. */
    public void setTakesInvites(Player player, boolean on) {
        games().invites().setAccepts(player.getUniqueId(), id(), on);
    }

    private void answered(Invite invite, boolean yes) {
        friends.answered(invite.from());
        Player a = Bukkit.getPlayer(invite.from());
        Player b = Bukkit.getPlayer(invite.to());
        if (!yes) {
            if (a != null) {
                a.sendMessage(Text.of("&7" + (b != null ? b.getName() : "Your friend")
                        + " didn't take your Tic-Tac-Toe invite."));
            }
            return;
        }
        for (Player p : new Player[] {a, b}) {
            if (p != null && busyElsewhere(p)) {
                // Don't pull them out of another game; the invite is used up, they can ask again.
                Player other = p == a ? b : a;
                p.sendMessage(Text.of("&7You're in another game right now, so the Tic-Tac-Toe game didn't start."));
                if (other != null) {
                    other.sendMessage(Text.of("&7" + p.getName()
                            + " is playing another game right now - ask again in a bit."));
                }
                return;
            }
        }
        boolean here = a != null && b != null && a.isOnline() && b.isOnline() && games().enabled(this)
                && games().canOpen(a, this) == null && games().canOpen(b, this) == null;
        TicTacToeMatch match = here ? TicTacToeMatch.friends(a.getUniqueId(), b.getUniqueId()) : null;
        if (match == null || !friends.start(a.getUniqueId(), b.getUniqueId(), match, true)) {
            for (Player p : new Player[] {a, b}) {
                if (p != null) {
                    p.sendMessage(Text.of("&7The Tic-Tac-Toe game couldn't start."));
                }
            }
            return;
        }
        Player[] players = {a, b};
        TicTacToePlayMenu[] boards = new TicTacToePlayMenu[2];
        for (int i = 0; i < 2; i++) {
            Player p = players[i];
            boards[i] = new TicTacToePlayMenu(ctx.plugin(), this, p, () -> open(p, null), match);
            boards[i].open(p);
        }
        if (!boards[0].showing() || !boards[1].showing()) {
            // Something cancelled a board opening (another plugin): nobody waits on a board whose
            // other side never comes. The game ends now, quietly, and both are told.
            match.leave(players[boards[0].showing() ? 1 : 0].getUniqueId());
            friends.end(a.getUniqueId());
            for (int i = 0; i < 2; i++) {
                if (boards[i].showing()) {
                    open(players[i], null); // back to the lobby; the board's close says nothing more
                }
                players[i].sendMessage(Text.of("&7The Tic-Tac-Toe game couldn't start."));
            }
        }
    }

    /** In a world session, or looking at another game's screen: an accepted invite mustn't pull them out. */
    private boolean busyElsewhere(Player player) {
        Game on = GameMenu.gameOnScreen(player);
        return FriendGames.elsewhere(id(), on == null ? null : on.id(), games().sessions().session(player) != null);
    }

    /**
     * The player leaves a friend game (closed its board, pressed Back, quit): if it was still on,
     * it ends for both and the other player is told. Only that game — a board closing because a
     * newer game opened must not end the newer one.
     *
     * @param quit whether they left the server (the other player's line says so)
     */
    public void leaveFriendGame(Player player, TicTacToeMatch match, boolean quit) {
        UUID id = player.getUniqueId();
        if (match == null) {
            return;
        }
        if (match.leave(id)) {
            UUID other = match.other(id);
            Player o = other == null ? null : Bukkit.getPlayer(other);
            if (o != null) {
                o.sendMessage(Text.of("&7" + player.getName() + (quit ? " left the server" : " left the game")
                        + ". The game is over."));
            }
        }
        if (friends.of(id) == match) {
            friends.end(id);
        }
    }

    /** A friend game ended on the board: tell both how it went (no tokens, no scores). */
    public void finishFriendGame(TicTacToeMatch match) {
        friends.end(match.first());
        for (UUID id : new UUID[] {match.first(), match.second()}) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            UUID otherId = match.other(id);
            Player other = otherId == null ? null : Bukkit.getPlayer(otherId);
            String them = other != null ? other.getName() : "Your friend";
            switch (match.outcome(id)) {
                case WON -> {
                    p.sendMessage(Text.of("&aThree in a row! You beat " + them + "."));
                    Sounds.won(p);
                }
                case LOST -> p.sendMessage(Text.of("&7" + them + " got three in a row this time."));
                case DRAW -> p.sendMessage(Text.of("&7A draw: the board is full."));
                default -> {
                }
            }
        }
    }
}
