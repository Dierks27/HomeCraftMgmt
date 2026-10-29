package com.dierks.homecraft.games;

import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Invites to two-player games: Coin Flip, and Connect Four or Tic-Tac-Toe against a friend (spec
 * R1.8, R3.8).
 *
 * <p>One pending invite per invitee, answered with {@code /hcm play accept|deny} (Java players
 * also get a clickable "[Accept]"; Bedrock players read the command) or the glinting tile on the
 * Games screen. Expiry, a quit or a world change cancels it. Each player chooses per game whether
 * they take invites at all ({@code invites.<gameId>} in {@code game_prefs}): Coin Flip invites are
 * OFF until the player turns them on; friend games are on. A player who can't be invited is
 * simply not offered — the inviter is never told why.
 *
 * <p>What the INVITER hears after sending is the game's to say, through its answer callback
 * ("Sam didn't take your Coin Flip invite."): this class only speaks to the invitee (the invite,
 * how to answer, that it ran out or was called off), so nobody reads the same news twice.
 *
 * <p><b>Every invite has an owning game.</b> An invite under a key of its own ({@code rider}) still names
 * the game that sent it, and its answer always runs, inside that game's guard (the final gate's #0: a
 * rider invite's answer was looked up by its key, found no game called "rider" and never ran, so
 * nobody could ever ride).
 *
 * <p><b>One switch for every invite.</b> {@code /hcm play invites off} writes a row for each of the
 * {@link #FRIEND_GAMES}; any invite key without a row of its own (one added since: party races, riders,
 * golf together) is off for a player whose rows carry the mark an older "off" left, so it keeps those
 * away too. A game's own screen switch is just for that game: it never turns off a ride, a party race or
 * golf together ({@link #olderInvitesOff}).
 */
public final class Invites {

    /** The {@code game_prefs} key for a player's choice about a game's invites. */
    public static String prefKey(String gameId) {
        return "invites." + gameId;
    }

    /** The game whose invites a player must turn on (from the Take a break screen). */
    public static final String COIN_FLIP = "coin_flip";
    /**
     * The games you can invite a friend to (their invites are on until a player turns them off).
     * {@code trials}: party races (WP-R1, owner decision D4); {@code rider}: a ride in someone's boat
     * (WP-CH); {@code golf}: Mini Golf together. {@code /hcm play invites on|off} writes each of them.
     */
    public static final List<String> FRIEND_GAMES = List.of("connect_four", "tic_tac_toe", "trials", "rider", "golf");
    /**
     * The friend games there were when {@code /hcm play invites off} first shipped: each keeps its own
     * default. Every other invite key with no row yet is off only for a player an older "off" marked
     * ({@link #OFF_MARK}).
     */
    static final List<String> FIRST_FRIEND_GAMES = List.of("connect_four", "tic_tac_toe");
    /**
     * The rows every {@code /hcm play invites off} has written, since it first shipped: both first friend
     * games and Coin Flip, all off, at once. No screen writes all three: Connect Four's and Tic-Tac-Toe's
     * each write only their own row, and Coin Flip's is written only by the Take a break screen. So all
     * three stored "off" is the one sign of an older "off" (the final gate's golf check: following
     * Connect Four alone made its own screen's switch turn off rides, party races and golf together too).
     */
    static final List<String> OFF_MARK = List.of("connect_four", "tic_tac_toe", COIN_FLIP);
    /** The same two players can't be asked again for this long after an invite. */
    public static final long PAIR_COOLDOWN_MS = 30_000L;
    /** The shortest and longest an invite may wait. */
    public static final int MIN_SECONDS = 10, MAX_SECONDS = 600;

    private final GamesService games;
    /** Invitee → their pending invite. */
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    /** The unordered pair → when they last had an invite. */
    private final Map<String, Long> lastPair = new HashMap<>();
    private long nextId = 1;
    /**
     * 0.38.4: invite id → the "Play?" screen's state: 1 while it couldn't open yet (tried again each second by
     * {@link #retry}), {@link #SHOWN} once it opened (never opened again: an Esc is not an answer, and a screen
     * re-opened on a 4-year-old every second would loop).
     */
    private final Map<Long, Integer> screenTries = new HashMap<>();
    /** {@link #screenTries}: the screen opened once. */
    static final int SHOWN = 2;
    /** What cancels the armed retry ({@link #armRetry}), or {@code null} while none is armed. */
    private Runnable retry;

    /**
     * A pending invite, the game that sent it, what to call when it is answered, its expiry task, and the
     * inviter's name (the "Play?" screen's tile says it, tried again later when it couldn't open at once).
     */
    private record Pending(Invite invite, Game owner, BiConsumer<Invite, Boolean> answer, Runnable cancelExpiry,
                           String fromName) {
    }

    /**
     * 0.38.4: the "Play?" screen an invite opens on the invitee ({@code gui/games/InviteAskMenu}), as the
     * framework knows it: which invite it asks about, so it is closed only while it is still that one's.
     */
    public interface AskScreen {
        long inviteId();
    }

    /**
     * 0.38.4: whether the "Play?" screen may open for an invitee now: nothing but their own crafting grid open
     * (so no chest closes and no carried stack drops), an empty cursor, and not in a world game - unless they
     * are hanging out in the Clubhouse, where party and ride invites legitimately reach them
     * ({@code PartyRaces.canInvite}). Otherwise the chat line stays and the screen is tried again each second.
     */
    public static boolean askScreenOpens(boolean noContainerOpen, boolean cursorEmpty, boolean inSession,
                                         boolean hangingOutInClub) {
        return noContainerOpen && cursorEmpty && (!inSession || hangingOutInClub);
    }

    public Invites(GamesService games) {
        this.games = games;
    }

    /**
     * Invite {@code to} to {@code game}. {@code answer} runs once with the invite and true
     * (accepted) or false (denied, expired, cancelled) — the game re-checks everything at that
     * moment (both online, both allowed, same world...) before any token moves.
     *
     * @param summary what the invitee reads ("Coin Flip for 10 tokens each")
     * @return the invite, or {@code null} when it was not sent (the invitee doesn't take them,
     *         already has one pending, the inviter already has one out, or the pair is on
     *         cooldown). The inviter is not told which: a player who can't be asked is simply
     *         not asked.
     */
    public Invite send(Player from, Player to, Game game, String summary, int seconds,
                       BiConsumer<Invite, Boolean> answer) {
        if (game == null) {
            return null;
        }
        return send(from, to, game, game.id(), game.name(), summary, seconds, answer);
    }

    /**
     * {@link #send(Player, Player, Game, String, int, BiConsumer)} under an invite key of its own that
     * isn't a game's id (WP-CH: {@code rider}, a ride in the back of someone's boat), with the same
     * switches, cooldown and one-at-a-time rules. {@code owner} is the game that sent it: its answer runs
     * inside that game's guard, and switching that game off calls it off.
     *
     * @param name what it is an invite to ("Ride along"): the Games screen's tile NAME says it
     */
    public Invite send(Player from, Player to, Game owner, String key, String name, String summary, int seconds,
                       BiConsumer<Invite, Boolean> answer) {
        if (from == null || to == null || owner == null || key == null
                || from.getUniqueId().equals(to.getUniqueId())) {
            return null;
        }
        expireLapsed();
        UUID a = from.getUniqueId();
        UUID b = to.getUniqueId();
        long now = now();
        Long last = lastPair.get(pair(a, b));
        if (!accepts(b, key) || pending.containsKey(b) || outgoing(a) != null
                || (last != null && now - last < PAIR_COOLDOWN_MS)) {
            return null;
        }
        int secs = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));
        String title = name == null || name.isBlank() ? owner.name() : name;
        Invite invite = new Invite(nextId++, a, b, key, summary == null ? title : summary, now,
                now + secs * 1000L, title);
        Runnable cancel = games.host().later(secs * 20L + 1, () -> expire(invite));
        Pending p = new Pending(invite, owner, answer, cancel == null ? () -> {
        } : cancel, from.getName());
        pending.put(b, p);
        lastPair.put(pair(a, b), now);
        offer(to, from.getName(), invite, secs);
        ask(to, p, secs); // 0.38.4: the "Play?" screen, when it can open now
        // The game says "Invite sent" itself, with its own details (how long it lasts, what it is).
        return invite;
    }

    /** {@code /hcm play accept}: accept the player's latest invite. False if there is none. */
    public boolean accept(Player to) {
        Pending p = take(to.getUniqueId());
        if (p == null) {
            return false;
        }
        answer(p, true);
        return true;
    }

    /** {@code /hcm play deny}: turn down the player's latest invite. False if there is none. */
    public boolean deny(Player to) {
        Pending p = take(to.getUniqueId());
        if (p == null) {
            return false;
        }
        to.sendMessage(Text.of("&7Invite turned down."));
        answer(p, false); // the game tells the inviter
        return true;
    }

    /**
     * The invite waiting for {@code to}, or {@code null} (also once its time is up). A pure read:
     * screens call it while they paint, so it never tells anyone anything and never runs an
     * answer — lapsing is the invite's own task's job, and the one-minute sweep's.
     */
    public Invite pending(UUID to) {
        Pending p = pending.get(to);
        return p == null || p.invite().expired(now()) ? null : p.invite();
    }

    /** Cancel every invite from or to the player (quit, world change). */
    public void cancel(UUID player) {
        for (Pending p : new ArrayList<>(pending.values())) {
            Invite i = p.invite();
            if (i.to().equals(player) || i.from().equals(player)) {
                pending.remove(i.to());
                p.cancelExpiry().run();
                closeAsk(i);
                if (i.from().equals(player)) {
                    tell(i.to(), "&7That invite was called off."); // the invitee; the game tells an inviter
                }
                answer(p, false);
            }
        }
    }

    /**
     * The player calls off their OWN invite (the "Waiting for ..." tile): only the one they sent
     * goes, any invite waiting for them stays. The invitee is told; the game's answer is not run,
     * since the game asked for this itself and already knows — so the inviter never reads that
     * their friend "didn't take" an invite they took back.
     *
     * @return whether there was one to call off
     */
    public boolean cancelFrom(UUID from) {
        for (Pending p : new ArrayList<>(pending.values())) {
            Invite i = p.invite();
            if (i.from().equals(from)) {
                pending.remove(i.to());
                p.cancelExpiry().run();
                closeAsk(i);
                tell(i.to(), "&7That invite was called off.");
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the player takes invites to this game (Coin Flip off by default, friend games on).
     * Party races ({@code trials}), riders and golf together joined the friend games later: a player
     * with no choice stored for such a key yet takes them unless an earlier {@code /hcm play invites off}
     * (which wrote rows only for the games there were then) left its mark ({@link #olderInvitesOff}), so
     * that "off" still keeps those invites away. That holds for ANY key but Coin Flip and the first two
     * friend games, so an invite added later is covered by the switch without anyone remembering to list
     * it here. Turning Connect Four or Tic-Tac-Toe off on its own screen is just for that game.
     */
    public boolean accepts(UUID player, String gameId) {
        try {
            String v = games.dao().pref(player, prefKey(gameId));
            if (v == null && followsFriendGames(gameId)) { // WP-R1 fix (R1 #3); WP-CH riders; final gate: golf
                return !olderInvitesOff(player);
            }
            if (v == null) {
                return !COIN_FLIP.equals(gameId);
            }
            return v.equalsIgnoreCase("on");
        } catch (SQLException e) {
            games.host().logger().log(Level.SEVERE, "Could not read a player's invite setting", e);
            return false;
        }
    }

    /** Whether an invite key with no choice stored follows an older {@code /hcm play invites off}. */
    static boolean followsFriendGames(String gameId) {
        return gameId != null && !COIN_FLIP.equals(gameId) && !FIRST_FRIEND_GAMES.contains(gameId);
    }

    /**
     * Whether the player's rows carry the mark of a {@code /hcm play invites off}: {@link #OFF_MARK} all
     * stored "off". One game's own screen switch never leaves it, so it never turns off anything else. A
     * player who later turns one of the three back on (Connect Four's screen, say) has lifted the mark,
     * and takes invites added later again, as a new player does.
     */
    private boolean olderInvitesOff(UUID player) throws SQLException {
        for (String key : OFF_MARK) {
            if (!"off".equalsIgnoreCase(games.dao().pref(player, prefKey(key)))) {
                return false;
            }
        }
        return true;
    }

    /** The player turns this game's invites on or off. */
    public void setAccepts(UUID player, String gameId, boolean on) {
        try {
            games.dao().setPref(player, prefKey(gameId), on ? "on" : "off");
        } catch (SQLException e) {
            games.host().logger().log(Level.SEVERE, "Could not save a player's invite setting", e);
        }
    }

    /**
     * {@code /hcm play invites on|off}: every friend game's row, and for {@code off} Coin Flip's too (only
     * the Take a break screen turns Coin Flip on). An "off" so leaves {@link #OFF_MARK}, which is how an
     * invite key added after it knows to stay off.
     */
    public void setAllFriendGames(UUID player, boolean on) {
        for (String game : FRIEND_GAMES) {
            setAccepts(player, game, on);
        }
        if (!on) {
            setAccepts(player, COIN_FLIP, false);
        }
    }

    // ---- the framework's side -----------------------------------------------------------------

    /** Cancel every invite to one game (it was switched off), keyed ones it sent too. */
    void cancelGame(String gameId) {
        for (Pending p : new ArrayList<>(pending.values())) {
            if (p.invite().gameId().equals(gameId) || p.owner().id().equals(gameId)) {
                pending.remove(p.invite().to());
                p.cancelExpiry().run();
                closeAsk(p.invite());
                answer(p, false);
            }
        }
    }

    /** Drop every invite, answering none of them (the games are stopping). */
    void clear() {
        for (Pending p : pending.values()) {
            p.cancelExpiry().run();
            closeAsk(p.invite());
        }
        pending.clear();
        screenTries.clear();
        if (retry != null) {
            retry.run();
            retry = null;
        }
    }

    /**
     * Lapse every invite whose time is up (their own task normally does it; this is the backstop),
     * and forget pairs whose cooldown is over, so the map doesn't grow with every pair ever asked.
     */
    void expireLapsed() {
        long now = now();
        for (Pending p : new ArrayList<>(pending.values())) {
            if (p.invite().expired(now)) {
                expire(p.invite());
            }
        }
        lastPair.values().removeIf(at -> now - at >= PAIR_COOLDOWN_MS);
    }

    /** How many pairs are still on cooldown (for the tests). */
    int pairsRemembered() {
        return lastPair.size();
    }

    /** The invite {@code from} has out, or {@code null}. */
    public Invite outgoing(UUID from) {
        for (Pending p : pending.values()) {
            if (p.invite().from().equals(from)) {
                return p.invite();
            }
        }
        return null;
    }

    // ---- internals ----------------------------------------------------------------------------

    private void expire(Invite invite) {
        Pending p = pending.get(invite.to());
        if (p == null || p.invite().id() != invite.id()) {
            return; // answered or replaced already
        }
        pending.remove(invite.to());
        p.cancelExpiry().run();
        closeAsk(invite);
        tell(invite.to(), "&7That invite has run out."); // the game tells the inviter
        answer(p, false);
    }

    private Pending take(UUID to) {
        expireLapsed();
        Pending p = pending.remove(to);
        if (p != null) {
            p.cancelExpiry().run();
            closeAsk(p.invite()); // a chat click, the command, the Games screen's book or the screen's own tap
        }
        return p;
    }

    // ---- 0.38.4: the "Play?" screen --------------------------------------------------------------

    /**
     * Open the "Play?" screen for {@code p}'s invitee now if it can ({@link GamesScreens#askInvite}, inside the
     * owning game's guard: a screen that throws fails that game, never the framework); else try again each
     * second until it opens once ({@link #retry}).
     */
    private void ask(Player to, Pending p, int secs) {
        boolean shown = games.guard(p.owner(),
                () -> games.screens().askInvite(to, p.owner(), p.invite(), p.fromName(), secs), false);
        screenTries.put(p.invite().id(), shown ? SHOWN : 1);
        if (!shown) {
            armRetry();
        }
    }

    /** Arm one retry a second from now while any screen is still waiting to open (one chain for all of them). */
    private void armRetry() {
        if (retry != null || !screenTries.containsValue(1)) {
            return;
        }
        retry = games.host().later(20, this::retry); // null: the plugin is disabling, nothing more to try
    }

    /** The chain's second: every screen that couldn't open yet is tried again; shown once, never again. */
    private void retry() {
        retry = null;
        long now = now();
        for (Pending p : new ArrayList<>(pending.values())) {
            Invite i = p.invite();
            if (!Integer.valueOf(1).equals(screenTries.get(i.id())) || i.expired(now)) {
                continue;
            }
            Player to = games.host().online(i.to());
            if (to == null) {
                continue;
            }
            int secs = (int) Math.max(1, (i.expiresAt() - now + 999) / 1000);
            if (games.guard(p.owner(), () -> games.screens().askInvite(to, p.owner(), i, p.fromName(), secs), false)) {
                screenTries.put(i.id(), SHOWN);
            }
        }
        armRetry();
    }

    /** The invite is over, however it ended: its screen closes if the invitee is still looking at it. */
    private void closeAsk(Invite i) {
        screenTries.remove(i.id());
        Player to = games.host().online(i.to());
        if (to != null) {
            games.screens().closeAsk(to, i.id());
        }
    }

    /**
     * Run the answer inside its owning game's guard, once. Always: the owner is kept with the invite, never
     * looked up by its key, so an invite under a key of its own is answered like any other (the final
     * gate's #0).
     */
    private void answer(Pending p, boolean yes) {
        if (p.answer() == null) {
            return;
        }
        games.guard(p.owner(), () -> p.answer().accept(p.invite(), yes));
    }

    /**
     * The invite in chat: Java players get clickable [Accept] and [Deny]; Bedrock players can't
     * click chat, so they read the command (R3.8). The "Play?" screen (0.38.4) comes besides, for
     * everyone, when it can open ({@link #ask}); the typed line stays right whenever it couldn't.
     */
    private void offer(Player to, String fromName, Invite invite, int seconds) {
        to.sendMessage(Text.of("&e" + fromName + " &7invites you: &f" + invite.summary() + " &8(" + seconds + " s)"));
        if (Bedrock.is(to)) {
            to.sendMessage(Text.of("&7Type &e/hcm play accept &7to play, or &e/hcm play deny&7."));
        } else {
            to.sendMessage(Text.of("&a[Accept]").clickEvent(ClickEvent.runCommand("/hcm play accept"))
                    .hoverEvent(HoverEvent.showText(Text.of("&7/hcm play accept")))
                    .append(Text.of("  "))
                    .append(Text.of("&c[Deny]").clickEvent(ClickEvent.runCommand("/hcm play deny"))
                            .hoverEvent(HoverEvent.showText(Text.of("&7/hcm play deny")))));
        }
        try {
            Sounds.received(to);
        } catch (RuntimeException | LinkageError ignored) {
            // cosmetic
        }
    }

    private void tell(UUID player, String line) {
        Player p = games.host().online(player);
        if (p != null) {
            p.sendMessage(Text.of(line));
        }
    }

    private long now() {
        return games.host().clock().nowMillis();
    }

    private static String pair(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }
}
