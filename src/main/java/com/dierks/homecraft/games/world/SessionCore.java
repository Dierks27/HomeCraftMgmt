package com.dierks.homecraft.games.world;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.storage.GamesDao;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The world-session state machine (spec §7.2, §7.5, §7.6): every way into a world game and every
 * way out, with no Bukkit types in it, so each path can be walked in a test through a fake
 * {@link Port}.
 *
 * <p>The rules it keeps, because these are the paths that can lose or duplicate a player's things:
 * <ul>
 *   <li><b>One at a time.</b> A player with any in-memory phase (ENTERING, ACTIVE, LEAVING), a
 *       recovery on the way, or ANY live saved-state row can't enter. ENTERING is set before
 *       anything else; the row is a plain INSERT the database refuses while another live row
 *       exists. Every later write names its {@code session_id}, and every async callback carries a
 *       token, so a late callback from an old session can never touch a newer one.</li>
 *   <li><b>Nothing changes before the row is saved.</b> Nothing may be on the cursor or in the
 *       crafting grid (with a full inventory, closing it would drop them), the inventory is
 *       closed, the player travels, THEN the state is captured and inserted (a capture that throws
 *       changes nothing and sends them back), THEN they are cleared, their own data is marked
 *       "cleared for this session" ({@link Port#mark}) and saved at once.</li>
 *   <li><b>A snapshot is applied only where it was taken</b> (Multiverse-Inventories swaps
 *       inventories per world group), OVERWRITES, marks their own data "applied", is saved, and is
 *       followed in the same tick by RETURN. A RETURN row is never applied again: it only sends
 *       the player home and hands over the carry. If RETURN can't be written, the row counts as
 *       RETURN anyway (in memory for this run, and by the "applied" mark after a restart) and the
 *       player is not sent home until it is written ({@code /hcm leave} tries again). A dead
 *       player is never restored; the row waits for the respawn.</li>
 *   <li><b>Anything that reached the player during the game</b> (an auction delivery, an inbox
 *       Mini) is banked in the row's {@code carry} BEFORE the restore overwrites the inventory,
 *       and handed over once they are home. A crash-join banks what the player holds only when
 *       their own data says it was saved after the clear (the "cleared" mark); without it the
 *       restore is overwrite-only, since that inventory may predate the game. A respawn, an admin
 *       restore and {@code /hcm leave} after a failure always bank what they hold first.</li>
 *   <li><b>Write, then give.</b> The carry is handed over only after the write that records it:
 *       DONE (carry cleared) when it all fits, else what doesn't fit stays in the row (still
 *       RETURN) until the player makes room and types {@code /hcm leave}. A failed write gives
 *       nothing. The carry is never dropped on the ground.</li>
 *   <li><b>Fail in place.</b> A snapshot or carry that can't be read, or a session world that is
 *       gone, is found before anyone is moved: the row is kept for an admin
 *       ({@code /hcm games saved}) and the player stays where they are.</li>
 * </ul>
 *
 * <p>Everything runs on the main thread. {@link Port#later} and {@link Port#teleport} complete
 * there too (and never while the plugin is stopping).
 *
 * @param <P> the player handle ({@code Player} on the server)
 * @param <I> an item stack ({@code ItemStack} on the server)
 */
final class SessionCore<P, I> {

    /** An armed teleport of ours is recognised for this long (R2.8). */
    static final long ARM_TICKS = 200;
    /** How close a teleport's destination must be to ours, on every axis. */
    static final double MATCH = 0.01;
    /** A foreign same-world teleport further than this ends the session. */
    static final double FAR = 16;
    /** Hurt this recently and you can't start ("Stand still and safe"). */
    static final long HURT_TICKS = 100;
    /** Falling further than this and you can't start. */
    static final float FALL = 3f;
    /** An entry still teleporting after this long is dropped (its callback never came). */
    static final long STALE_ENTERING_TICKS = 600;
    /** DONE rows are kept this long for support, then pruned. */
    static final long KEEP_DONE_MS = 7L * 24 * 60 * 60 * 1000;
    /** Respawn restores wait this long, so Multiverse-Inventories' own respawn pass runs first. */
    static final long RESPAWN_DELAY = 5;
    /** One void rescue per this many ticks. */
    static final long VOID_COOLDOWN = 20;

    static final String IN_SESSION = Refusal.IN_SESSION.message();
    static final String STILL_SENDING = "Your last game is still sending you back. Type /hcm leave.";
    static final String SAFE = "Stand still and safe to start.";
    static final String HANDS = "Put down what you're holding first.";
    static final String CANT_START = "Couldn't start the game right now.";
    static final String CANT_REACH = "Couldn't get you to the game right now.";

    static final String BACK = "&aYour things are back.";
    static final String BACK_AFTER_RESTART = "&aYour things are back &7— the server restarted during your game.";
    static final String BACK_BY_ADMIN = "&aYour things are back &7— an admin helped.";
    static final String BACK_STAY = "&aYour things are back. &7Type &e/hcm leave &7in a moment to go home.";
    static final String SAFE_WITH_ADMIN = "&cYour things are safe &7— an admin will help.";
    static final String NOT_HOME = "&cCouldn't send you back yet. &7Type &e/hcm leave &7to try again.";
    static final String NOT_RESTORED = "&cCouldn't bring your things back yet. &7Type &e/hcm leave &7to try again.";
    static final String NOT_DONE = "&cCouldn't finish bringing you back. &7Type &e/hcm leave &7to try again.";
    static final String FULL = "&eSome of your things didn't fit. &7Make room, then type &e/hcm leave &7to get the rest.";
    static final String KEPT = "&eSome of your things are kept for you. &7Type &e/hcm leave &7to get them.";
    static final String NOT_IN_GAME = "&7You're not in a game.";
    static final String ENDED = "&7Your game has ended.";

    /** The player's own data after the clear for a session: what they hold arrived during it. */
    static final String CLEARED = "cleared:";
    /** The player's own data after a restore: the snapshot is on them. */
    static final String APPLIED = "applied:";
    /** A Clubhouse watcher's mode: the session's own, never left on anyone (#6, final gate #19). */
    static final String SPECTATOR = "SPECTATOR";

    // What an admin is told ({@code /hcm games saved <player> restore|return}).
    static final String ADMIN_BUSY = "&7Their things are already on the way back.";
    static final String ADMIN_OFFLINE = "&7They're not online. &7Their things are kept.";
    static final String ADMIN_NONE = "&7No saved things for them.";
    static final String ADMIN_READ = "&cCouldn't read their saved things. &7See the server log.";
    static final String ADMIN_DEAD = "&7They're dead: their things come back when they respawn.";
    static final String ADMIN_UNREADABLE = "&cTheir saved things can't be read. &7Nothing was changed - see the server log.";
    static final String ADMIN_CARRY = "&cThe things kept for them can't be read. &7Nothing was changed - see the server log.";
    static final String ADMIN_APPLY = "&cPutting their things back failed. &7The saved state is kept - see the server log.";
    static final String ADMIN_WRITE = "&cTheir things are back, but that couldn't be saved yet. &7See the server log, then try "
            + "&ereturn &7again.";
    static final String ADMIN_DONE_WRITE = "&cCouldn't save the hand-over. &7Nothing was given - see the server log.";
    static final String ADMIN_MOVED = "&7Their saved things changed meanwhile. &7Look again with &eshow&7.";
    static final String ADMIN_BACK = "&aTheir things are back. &7Sending them home now.";
    static final String ADMIN_SENDING = "&aSending them back now.";
    static final String ADMIN_HANDED = "&aDone: everything kept for them is handed over.";
    static final String ADMIN_FULL = "&eSome of their things don't fit yet. &7They need to make room, then &e/hcm leave&7.";
    static final String ADMIN_KEPT = "&eThey're in a Games world: what's kept for them waits for &e/hcm leave&7.";

    /** Why a recovery runs, which decides what the player is told. */
    enum Why { JOIN, RESPAWN, READY, RETRY, ADMIN }

    /** What a teleport during a session means (R2.8). */
    enum Move {
        /** One of ours (a checkpoint, a rescue, our own dismount). */
        OURS,
        /** Someone else's, but small or harmless: the session goes on (the game may void a run). */
        KEEP,
        /** Out of the session world, or far: the session ends where they stand. */
        END
    }

    /** What the player's body is doing when they ask to start (R2.9). */
    record Standing(boolean dead, boolean sleeping, boolean gliding, boolean riding, boolean ownBoat,
                    boolean otherScreen, float fallDistance, boolean onGround, int fireTicks, boolean inLava,
                    boolean inWater, long ticksSinceHurt) {

        /** Standing still on the ground, hurt long ago, nothing open. */
        static Standing still() {
            return new Standing(false, false, false, false, false, false, 0f, true, 0, false, false, Long.MAX_VALUE);
        }
    }

    /** A snapshot decoded and ready to go on (nothing has changed while it was decoded). */
    interface Restore<P> {
        void applyTo(P player);
    }

    /** What a session tells its game. The world sessions run each inside the game's guard. */
    interface Hooks<P> {
        /** In, saved, cleared: give the kit and start. */
        void ready(P player);

        /** The session ended (after the restore). */
        void ended(P player, EndReason reason);

        /** Fell out of the world, or was moved by someone else a little way. */
        void voided(P player);
    }

    /** Everything the state machine needs from the server. */
    interface Port<P, I> {
        UUID id(P p);

        String name(P p);

        boolean online(P p);

        boolean dead(P p);

        String world(P p);

        Place location(P p);

        boolean worldExists(String world);

        /** The world's spawn, or {@code null} if it is gone. */
        Place spawn(String world);

        /** The main (first) world's spawn. */
        Place mainSpawn();

        /** Whether the world is one of {@code games.worlds} (nothing is ever dropped there). */
        boolean gamesWorld(String world);

        /** Whether the chunk at {@code place} is loaded (a same-tick teleport is possible). */
        boolean loaded(Place place);

        long tick();

        long now();

        /** Shutting down or disabling: no task can run, so no teleport may be started. */
        boolean stopping();

        /** Run on the main thread after {@code ticks}; dropped while stopping. */
        void later(long ticks, Runnable task);

        /** Our async teleport; {@code done} runs later on the main thread (never while stopping). */
        void teleport(P p, Place to, Consumer<Boolean> done);

        /** Our synchronous teleport. */
        boolean teleportNow(P p, Place to);

        Standing standing(P p, String gameId);

        void closeInventory(P p);

        /** Nothing on the cursor and nothing in the crafting grid. */
        boolean handsFree(P p);

        /** The player's whole state now, as a new ACTIVE row. */
        SavedState capture(P p, String sessionId, String gameId, String ref, String sessionWorld, Place from, long now);

        /** Decode a snapshot; throws (and changes nothing) if it can't be read. */
        Restore<P> prepare(SavedState s);

        /** ADVENTURE first, then empty everything (§7.2 step 6). */
        void clearForGame(P p);

        /** Empty the cursor, the crafting grid and every slot; return what wasn't a kit item. */
        List<I> takeExtras(P p);

        /** Throw away what's on the cursor and in the crafting grid (an overwrite restore). */
        void discardHeld(P p);

        /** Remove kit items from the inventory, the cursor and the ender chest. */
        void stripKit(P p);

        /** Add to the inventory; return what didn't fit. */
        List<I> give(P p, List<I> items);

        /**
         * How many of {@code items}, from the first, surely fit in the inventory now, so the write
         * can come before the give. Never too many: only whole empty slots count. Changes nothing.
         */
        int room(P p, List<I> items);

        /**
         * What we last did to the player's body, kept in the player's own data so it is saved (and
         * lost in a crash) together with their inventory: {@link #CLEARED} or {@link #APPLIED}
         * plus the session id, or {@code null}.
         */
        String mark(P p);

        /** Set {@link #mark}; {@code null} removes it. */
        void setMark(P p, String mark);

        /** The item blob for a list ({@code null} for none). */
        byte[] encode(List<I> items);

        /** The list back from a blob; throws if it can't be read. */
        List<I> decode(byte[] blob);

        String describe(I item);

        void drop(P p, List<I> items);

        /** Write the player's data to disk now, so a crash can't undo a restore. */
        void save(P p);

        /**
         * No fall distance and no speed. The server keeps a fall through a teleport, and outside a
         * session nothing cancels its damage: every teleport of ours lands with none (final gate #18).
         */
        void still(P p);

        /** Leave any vehicle. */
        void dismount(P p);

        /** Leave and remove the game vehicle they ride (a race boat), if any. */
        void removeGameVehicle(P p);

        /**
         * WP-CH: a game had put the player in {@code sessionMode} for their session (a Clubhouse
         * watcher's SPECTATOR) and the session ends with nothing put back: if they are still in it,
         * ADVENTURE again, as our own change (the Clubhouse review, #6).
         */
        void resetMode(P p, String sessionMode);

        void tell(P p, String line);
    }

    /** A player's session in memory. */
    final class Live {
        final P player;
        final UUID uuid;
        final long token;
        final String sid;
        /** The game running it: changed only by {@link #passTo} (WP-CH), never mid-entry. */
        String gameId;
        String ref;
        final Place start;
        Hooks<P> hooks;
        final long startedAt;
        final long enteredTick;
        Session.Phase phase = Session.Phase.ENTERING;
        Place from;
        Place safe;
        String world;
        EndReason abort;
        long voidTick = Long.MIN_VALUE / 2;
        boolean deathQueued;
        List<I> deathStash;
        /** Extras the database refused to bank: handed back right after the restore instead. */
        final List<I> unbanked = new ArrayList<>();
        /**
         * WP-CH: a game mode a game set for this session (a Clubhouse watcher's SPECTATOR), or
         * {@code null} for the games' ADVENTURE. It lives and dies with the session.
         */
        String mode;

        Live(P player, UUID uuid, long token, String sid, String gameId, String ref, Place start, Hooks<P> hooks) {
            this.player = player;
            this.uuid = uuid;
            this.token = token;
            this.sid = sid;
            this.gameId = gameId;
            this.ref = ref;
            this.start = start;
            this.hooks = hooks;
            this.startedAt = port.now();
            this.enteredTick = port.tick();
            this.world = start.world();
        }

        Session snapshot() {
            return new Session(uuid, gameId, ref, sid, world, phase, startedAt);
        }
    }

    private record Armed(Place target, long tick) {
    }

    private final GamesDao dao;
    private final Port<P, I> port;
    private final Logger log;
    private final long bootedAt;

    private final Map<UUID, Live> live = new HashMap<>();
    /** Recoveries and hand-overs under way outside a session: player → token. */
    private final Map<UUID, Long> recovering = new HashMap<>();
    /** Players with a live row (O(1) for everyone else); unknown if it couldn't be loaded. */
    private final Set<UUID> rows = new HashSet<>();
    private boolean rowsKnown;
    private final Map<UUID, ArrayDeque<Armed>> armed = new HashMap<>();
    private final Map<UUID, Long> ownDismount = new HashMap<>();
    private final Set<String> reported = new HashSet<>();
    /** Sessions whose snapshot is on the player although RETURN couldn't be written: RETURN for us. */
    private final Set<String> applied = new HashSet<>();
    /** Sessions whose trip home was made this run: only the hand-over is left. */
    private final Set<String> reached = new HashSet<>();
    private long tokens;

    SessionCore(GamesDao dao, Port<P, I> port, Logger log) {
        this.dao = dao;
        this.port = port;
        this.log = log;
        this.bootedAt = port.now();
        try {
            rows.addAll(dao.livePlayers());
            rowsKnown = true;
        } catch (SQLException | RuntimeException e) {
            log.log(Level.SEVERE, "Could not read the games' saved player states - checking each join instead", e);
        }
        try {
            int pruned = dao.pruneDone(port.now() - KEEP_DONE_MS);
            if (pruned > 0) {
                log.info("Games: pruned " + pruned + " finished saved state(s) older than a week.");
            }
        } catch (SQLException | RuntimeException e) {
            log.log(Level.WARNING, "Could not prune old games saved states", e);
        }
    }

    // ---- questions ------------------------------------------------------------------------------

    /** The player's session, or {@code null}. */
    Session session(UUID player) {
        Live s = live.get(player);
        return s == null ? null : s.snapshot();
    }

    Session.Phase phase(UUID player) {
        Live s = live.get(player);
        return s == null ? null : s.phase;
    }

    /** Nobody is in a session: the move and teleport listeners stop at this. */
    boolean none() {
        return live.isEmpty();
    }

    List<Session> sessions() {
        List<Session> out = new ArrayList<>();
        for (Live s : live.values()) {
            out.add(s.snapshot());
        }
        return out;
    }

    /** The players in sessions, for the server's own lookups. */
    List<P> players() {
        List<P> out = new ArrayList<>();
        for (Live s : live.values()) {
            out.add(s.player);
        }
        return out;
    }

    /** Whether the player has a live row (O(1) once loaded; asks the database if it couldn't be). */
    boolean hasRow(UUID player) {
        if (rowsKnown) {
            return rows.contains(player);
        }
        try {
            return dao.loadState(player) != null;
        } catch (SQLException e) {
            return true;
        }
    }

    /** Back from every game: no session, nothing on the way, no live row. */
    boolean home(UUID player) {
        return !live.containsKey(player) && !recovering.containsKey(player) && !hasRow(player);
    }

    /** Whether a recovery or a hand-over is under way for the player. */
    boolean recovering(UUID player) {
        return recovering.containsKey(player);
    }

    /** Why a player standing like this can't start, or {@code null} if they can (R2.9). */
    static String refusal(Standing s) {
        if (s.dead()) {
            return SAFE;
        }
        if (s.sleeping()) {
            return "Get out of bed first.";
        }
        if (s.gliding()) {
            return "Land first.";
        }
        if (s.riding() && !s.ownBoat()) {
            return "Get off first.";
        }
        if (s.otherScreen()) {
            return "Close what you have open first.";
        }
        if (s.fallDistance() > FALL || (!s.onGround() && !s.ownBoat()) || s.fireTicks() > 0 || s.inLava()
                || s.inWater() || s.ticksSinceHurt() < HURT_TICKS) {
            return SAFE;
        }
        return null;
    }

    /** Whether a teleport is the one we armed: cause PLUGIN, armed no longer ago than 200 ticks, within 0.01. */
    static boolean matches(Place armedTarget, long armedTick, long nowTick, String cause, Place to) {
        return "PLUGIN".equals(cause) && nowTick - armedTick <= ARM_TICKS && armedTarget.near(to, MATCH);
    }

    /**
     * What a teleport of a session player means (R2.8): ours by exact match (or our own
     * dismount); otherwise leaving the session world or moving further than 16 blocks ends it,
     * and DISMOUNT, EXIT_BED, UNKNOWN or a short hop keeps it.
     */
    static Move classify(boolean ours, boolean ownDismount, String cause, Place from, Place to, String sessionWorld) {
        if (ours || (ownDismount && "DISMOUNT".equals(cause))) {
            return Move.OURS;
        }
        if (to == null || sessionWorld == null || !sessionWorld.equals(to.world())) {
            return Move.END;
        }
        if ("DISMOUNT".equals(cause) || "EXIT_BED".equals(cause) || "UNKNOWN".equals(cause)) {
            return Move.KEEP;
        }
        return from != null && from.distance(to) <= FAR ? Move.KEEP : Move.END;
    }

    // ---- entering -------------------------------------------------------------------------------

    /**
     * Start taking the player into a game at {@code start}.
     *
     * @return {@code null} if the entry started, else the refusal to tell them
     */
    String enter(P p, String gameId, String ref, Place start, Hooks<P> hooks) {
        UUID id = port.id(p);
        Live cur = live.get(id);
        if (cur != null) {
            if (cur.phase != Session.Phase.ENTERING || port.tick() - cur.enteredTick <= STALE_ENTERING_TICKS) {
                return IN_SESSION;
            }
            live.remove(id);
            log.warning("Games: dropped " + port.name(p) + "'s entry that never arrived (session " + cur.sid + ")");
        }
        if (recovering.containsKey(id)) {
            return STILL_SENDING;
        }
        SavedState row;
        try {
            row = dao.loadState(id);
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Games: could not check " + port.name(p) + "'s saved state", e);
            return CANT_START;
        }
        if (row != null) {
            rows.add(id);
            return STILL_SENDING;
        }
        rows.remove(id);
        if (start == null || !port.worldExists(start.world())) {
            return CANT_START;
        }
        String why = refusal(port.standing(p, gameId));
        if (why != null) {
            return why;
        }
        Live s = new Live(p, id, ++tokens, UUID.randomUUID().toString(), gameId, ref == null ? "" : ref, start, hooks);
        live.put(id, s); // ENTERING, before anything else: a second entry is refused from here on
        // The rest waits a tick: closing an inventory from inside its own click is unsafe.
        port.later(1, () -> depart(s));
        return null;
    }

    private void depart(Live s) {
        P p = s.player;
        if (live.get(s.uuid) != s) {
            return;
        }
        if (!port.online(p) || s.abort != null) {
            live.remove(s.uuid);
            return;
        }
        if (!port.handsFree(p)) {
            // Closing would put the cursor and the crafting grid back, or drop them if the inventory
            // is full (and a drop on the way in can be lost): they put them down themselves first.
            live.remove(s.uuid);
            refuse(p, HANDS);
            return;
        }
        port.closeInventory(p);
        String why = port.handsFree(p) ? refusal(port.standing(p, s.gameId)) : HANDS;
        if (why != null) {
            live.remove(s.uuid);
            refuse(p, why);
            return;
        }
        s.from = port.location(p);
        go(p, s.start, false, ok -> arrived(s, ok));
    }

    private void arrived(Live s, boolean ok) {
        P p = s.player;
        UUID id = s.uuid;
        if (live.get(id) != s) {
            return; // dropped (quit, stop) or replaced: nothing of theirs was touched
        }
        if (!ok) {
            live.remove(id);
            if (port.online(p)) {
                refuse(p, CANT_REACH);
            }
            return;
        }
        if (!port.online(p) || port.dead(p)) {
            live.remove(id);
            return;
        }
        if (s.abort != null) {
            abort(s, null);
            return;
        }
        Place here = port.location(p);
        if (!s.start.sameWorld(here) || here.distance(s.start) > FAR) {
            live.remove(id); // someone else moved them on the way; they still have everything
            refuse(p, CANT_REACH);
            return;
        }
        SavedState existing;
        try {
            existing = dao.loadState(id);
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Games: could not check " + port.name(p) + "'s saved state", e);
            abort(s, CANT_START);
            return;
        }
        if (existing != null) {
            rows.add(id);
            abort(s, STILL_SENDING);
            return;
        }
        if (!port.handsFree(p)) {
            abort(s, HANDS);
            return;
        }
        SavedState state;
        try {
            state = port.capture(p, s.sid, s.gameId, s.ref, here.world(), s.from, port.now());
        } catch (RuntimeException e) {
            // Nothing has changed yet (a stack that won't serialise, say): back as they are.
            log.log(Level.SEVERE, "Games: could not save " + port.name(p) + "'s state for " + s.gameId
                    + " - not starting", e);
            abort(s, CANT_START);
            return;
        }
        boolean saved;
        try {
            saved = dao.saveState(state); // a plain INSERT: refused while any live row exists
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Games: could not save " + port.name(p) + "'s state - not starting", e);
            saved = false;
        }
        if (!saved) {
            abort(s, CANT_START);
            return;
        }
        rows.add(id);
        s.world = here.world();
        s.safe = s.start;
        try {
            port.clearForGame(p);
            // Their own data says "cleared for this session" and is saved at once: from here on the
            // file no longer holds what they had, so what a crash-join finds on them arrived during
            // the game, and is banked rather than thrown away.
            port.setMark(p, CLEARED + s.sid);
            port.save(p);
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "Games: could not ready " + port.name(p) + " for " + s.gameId + " - sending them back", e);
            s.phase = Session.Phase.LEAVING;
            end(s, EndReason.GAME_OFF);
            return;
        }
        s.phase = Session.Phase.ACTIVE;
        s.hooks.ready(p);
    }

    /** An entry that arrived but must not start: nothing was changed; send them back where they were. */
    private void abort(Live s, String why) {
        live.remove(s.uuid);
        if (s.from != null) {
            go(s.player, s.from, false, ok -> {
            });
        }
        if (why != null) {
            refuse(s.player, why);
        }
    }

    // ---- leaving --------------------------------------------------------------------------------

    /**
     * End the player's session for {@code reason}. With no session, {@code /hcm leave} (and an
     * admin) finish a row that is still sending the player back.
     */
    void leave(P p, EndReason reason) {
        UUID id = port.id(p);
        Live s = live.get(id);
        if (s == null) {
            if (reason == EndReason.COMMAND || reason == EndReason.QUIT_ITEM || reason == EndReason.ADMIN) {
                if (recovering.containsKey(id)) {
                    return;
                }
                if (hasRow(id)) {
                    recover(p, reason == EndReason.ADMIN ? Why.ADMIN : Why.RETRY);
                } else if (reason == EndReason.COMMAND) {
                    port.tell(p, NOT_IN_GAME);
                }
            }
            return;
        }
        switch (s.phase) {
            case ENTERING -> {
                if (reason == EndReason.DISCONNECT || port.stopping()) {
                    live.remove(id);
                } else {
                    s.abort = reason; // the arrival sends them back without starting
                }
            }
            case LEAVING -> {
                // already on the way out
            }
            case ACTIVE -> {
                s.phase = Session.Phase.LEAVING;
                end(s, reason);
            }
        }
    }

    /** The quit path (PlayerQuitEvent, LOWEST): restore in place, no teleport. */
    void quit(P p) {
        UUID id = port.id(p);
        recovering.remove(id);
        armed.remove(id);
        Live s = live.get(id);
        if (s != null && s.phase == Session.Phase.ACTIVE) {
            s.phase = Session.Phase.LEAVING;
            end(s, EndReason.DISCONNECT);
        }
        // An entry or a trip home can't finish now; the next join does (the row says where it got to).
        Live left = live.remove(id);
        if (left != null) {
            giveBackUnbanked(left);
        }
        ownDismount.remove(id);
    }

    /**
     * Every session ends. While the server is stopping (or the plugin disabling) it restores in
     * place and marks RETURN without a teleport; otherwise it is the full leave with a synchronous
     * teleport home (R3.12).
     */
    void stop() {
        boolean stopping = port.stopping();
        for (Live s : new ArrayList<>(live.values())) {
            switch (s.phase) {
                case ACTIVE -> {
                    s.phase = Session.Phase.LEAVING;
                    end(s, EndReason.STOP);
                }
                case ENTERING -> {
                    if (stopping) {
                        live.remove(s.uuid);
                    } else {
                        s.abort = EndReason.STOP;
                    }
                }
                case LEAVING -> {
                    if (stopping) {
                        live.remove(s.uuid);
                    }
                }
            }
        }
        if (stopping) {
            live.clear();
            recovering.clear();
            armed.clear();
            ownDismount.clear();
        }
    }

    /**
     * The normal leave. In order: off the game's vehicle; a dead player is left for the respawn;
     * a player outside the session world goes back there first; then extras into the carry,
     * the snapshot on (overwrite), saved, RETURN, and home.
     */
    private void end(Live s, EndReason reason) {
        P p = s.player;
        UUID id = s.uuid;
        boolean inPlace = reason == EndReason.DISCONNECT || reason == EndReason.TELEPORT
                || (reason == EndReason.STOP && port.stopping());
        boolean sync = !inPlace && (reason == EndReason.GAME_OFF || reason == EndReason.STOP);
        ownDismount(p, () -> port.removeGameVehicle(p));
        land(s, reason); // WP-CH: a watcher comes down to a floor first
        if (port.dead(p)) {
            // Never restore a dead player: the row stays ACTIVE for the respawn (or the next join).
            port.stripKit(p);
            live.remove(id);
            s.hooks.ended(p, reason);
            return;
        }
        SavedState row;
        try {
            row = dao.loadState(id);
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Games: could not read " + port.name(p) + "'s saved state (session " + s.sid
                    + ") - leaving them as they are", e);
            port.stripKit(p);
            noRestore(s);
            live.remove(id);
            port.tell(p, SAFE_WITH_ADMIN);
            s.hooks.ended(p, reason);
            return;
        }
        if (row == null || !s.sid.equals(row.sessionId())) {
            // No row for this session (an admin discarded it): nothing to put back.
            log.warning("Games: no saved state for " + port.name(p) + "'s game (session " + s.sid
                    + ") - their things are left as they are");
            port.stripKit(p);
            noRestore(s);
            live.remove(id);
            s.hooks.ended(p, reason);
            port.tell(p, ENDED);
            if (!inPlace && s.from != null) {
                go(p, s.from, sync, ok -> {
                });
            }
            return;
        }
        if (SavedState.RETURN.equals(row.phase()) || alreadyApplied(p, row)) {
            port.stripKit(p);
            s.hooks.ended(p, reason);
            if (!SavedState.RETURN.equals(row.phase()) && !markReturn(p, row)) {
                live.remove(id);
                port.tell(p, BACK_STAY);
                return;
            }
            afterRestore(s, row, reason, inPlace, sync);
            return;
        }
        if (!row.sessionWorld().equals(port.world(p))) {
            detour(s, row, reason);
            return;
        }
        restoreHere(s, row, reason, inPlace, sync);
    }

    /**
     * The world-change backstop (§7.2, §7.5 (1)): at the event, before Multiverse-Inventories
     * swaps, bank the extras and drop the kit; then go back to the session world, restore there,
     * and go home.
     */
    private void detour(Live s, SavedState row, EndReason reason) {
        P p = s.player;
        UUID id = s.uuid;
        s.unbanked.addAll(bank(p, row));
        port.stripKit(p);
        Place back = s.start != null && row.sessionWorld().equals(s.start.world()) ? s.start : port.spawn(row.sessionWorld());
        if (back == null) {
            noRestore(s);
            live.remove(id);
            giveBackUnbanked(s);
            failed(p, row, null, "its world is gone");
            s.hooks.ended(p, reason);
            return;
        }
        try {
            port.prepare(row); // never back into the Games world for a snapshot that can't be put on
        } catch (RuntimeException e) {
            noRestore(s);
            live.remove(id);
            giveBackUnbanked(s);
            failed(p, row, e, "the saved state can't be read");
            s.hooks.ended(p, reason);
            return;
        }
        long token = s.token;
        port.later(1, () -> { // never teleport from inside the world-change event
            if (!owns(id, token)) {
                return;
            }
            if (!port.online(p)) {
                live.remove(id);
                return;
            }
            go(p, back, false, ok -> {
                if (!owns(id, token)) {
                    return;
                }
                if (!ok || !port.online(p) || !row.sessionWorld().equals(port.world(p))) {
                    if (port.online(p)) {
                        noRestore(s);
                    }
                    live.remove(id);
                    giveBackUnbanked(s);
                    if (port.online(p)) {
                        port.tell(p, NOT_RESTORED);
                    }
                    s.hooks.ended(p, reason);
                    return;
                }
                restoreHere(s, row, reason, false, false);
            });
        });
    }

    /** Restore in the session world: extras banked, snapshot on, saved, RETURN; then home (or not). */
    private void restoreHere(Live s, SavedState row, EndReason reason, boolean inPlace, boolean sync) {
        P p = s.player;
        UUID id = s.uuid;
        Restore<P> restore;
        try {
            restore = port.prepare(row);
        } catch (RuntimeException e) {
            // Nothing has changed: the snapshot stays for an admin, the extras go to the carry.
            s.unbanked.addAll(bank(p, row));
            port.stripKit(p);
            noRestore(s);
            live.remove(id);
            giveBackUnbanked(s);
            failed(p, row, e, "the saved state can't be read");
            s.hooks.ended(p, reason);
            return;
        }
        s.unbanked.addAll(bank(p, row));
        try {
            restore.applyTo(p);
        } catch (RuntimeException e) {
            port.stripKit(p);
            noRestore(s); // only if the restore didn't get as far as their mode (it goes first)
            live.remove(id);
            giveBackUnbanked(s);
            failed(p, row, e, "putting it back failed");
            s.hooks.ended(p, reason);
            return;
        }
        port.setMark(p, APPLIED + row.sessionId());
        giveBackUnbanked(s);
        port.save(p);
        boolean returned = markReturn(p, row);
        port.stripKit(p);
        s.hooks.ended(p, reason);
        if (!returned) {
            // Their things are on them but the row still says ACTIVE: they are not sent home until
            // it says RETURN, and until then nothing applies it again. /hcm leave writes it and goes on.
            live.remove(id);
            port.tell(p, BACK_STAY);
            return;
        }
        String line = endLine(reason);
        if (line != null) {
            port.tell(p, line);
        }
        afterRestore(s, row, reason, inPlace, sync);
    }

    /** After the restore: stay (quit, stop, a teleport out) or go home. */
    private void afterRestore(Live s, SavedState row, EndReason reason, boolean inPlace, boolean sync) {
        P p = s.player;
        UUID id = s.uuid;
        if (inPlace) {
            live.remove(id);
            if (reason == EndReason.TELEPORT) {
                // They are going somewhere: hand over the carry once they are there (unless that is
                // inside a Games world: then it waits for /hcm leave).
                long token = ++tokens;
                recovering.put(id, token);
                port.later(1, () -> {
                    if (owns(id, token)) {
                        recovering.remove(id);
                        if (port.online(p)) {
                            handOver(p, row.sessionId(), true);
                        }
                    }
                });
            }
            return;
        }
        returnTrip(p, row, sync, s.token);
    }

    // ---- death, teleports, worlds, the void -----------------------------------------------------

    /**
     * PlayerDeathEvent at LOWEST: a session player's death is cancelled (the listener revives
     * them). What would have dropped is kept aside in case another plugin lets the death happen.
     *
     * @return whether the player is in a session (so the death must be cancelled)
     */
    boolean dying(P p, List<I> nonKitDrops) {
        Live s = live.get(port.id(p));
        if (s == null) {
            return false;
        }
        s.deathStash = new ArrayList<>(nonKitDrops);
        return true;
    }

    /** PlayerDeathEvent at MONITOR: bank the stash if the death went ahead; leave next tick. */
    void died(P p, boolean cancelled) {
        UUID id = port.id(p);
        Live s = live.get(id);
        if (s == null) {
            return;
        }
        List<I> stash = s.deathStash;
        s.deathStash = null;
        if (!cancelled && stash != null && !stash.isEmpty()) {
            try {
                SavedState row = dao.loadState(id);
                if (row != null && s.sid.equals(row.sessionId())) {
                    List<I> left = addCarry(p, row.sessionId(), stash);
                    s.unbanked.addAll(left);
                    if (left.size() < stash.size()) {
                        // Write, then save (final gate #17): once the server has emptied the inventory
                        // (after this event), their file must stop holding what is in the carry now.
                        port.later(1, () -> {
                            if (port.online(p)) {
                                port.save(p);
                            }
                        });
                    }
                }
            } catch (SQLException e) {
                log.log(Level.SEVERE, "Games: could not keep " + port.name(p) + "'s things from a death", e);
            }
        }
        if (s.phase == Session.Phase.ACTIVE && !s.deathQueued) {
            s.deathQueued = true;
            long token = s.token;
            port.later(1, () -> {
                Live cur = live.get(id);
                if (cur != null && cur.token == token && cur.phase == Session.Phase.ACTIVE) {
                    leave(p, EndReason.DEATH);
                }
            });
        }
    }

    /** PlayerTeleportEvent at MONITOR (not cancelled): ours, harmless, or the end of the session. */
    void teleported(P p, Place from, Place to, String cause) {
        UUID id = port.id(p);
        boolean ours = consumeOurs(id, to, cause);
        Live s = live.get(id);
        if (s == null || s.phase != Session.Phase.ACTIVE) {
            return;
        }
        switch (classify(ours, dismountIsOurs(id), cause, from, to, s.world)) {
            case OURS -> {
                if (to != null && to.world().equals(s.world)) {
                    s.safe = to;
                }
            }
            case KEEP -> voidNextTick(s);
            case END -> leave(p, EndReason.TELEPORT);
        }
    }

    /** PlayerChangedWorldEvent at LOWEST: a session player left the session world some other way. */
    void worldChanged(P p) {
        Live s = live.get(port.id(p));
        if (s == null || s.phase != Session.Phase.ACTIVE || s.world.equals(port.world(p))) {
            return;
        }
        s.phase = Session.Phase.LEAVING;
        end(s, EndReason.WORLD_CHANGE);
    }

    /**
     * A move: below the world's floor, back to the last safe point (ours; next tick, not from
     * inside the move), then the game hears of it.
     */
    void moved(P p, Place to, double minY) {
        Live s = live.get(port.id(p));
        if (s == null || s.phase != Session.Phase.ACTIVE || to == null || to.y() >= minY || !s.world.equals(to.world())) {
            return;
        }
        long now = port.tick();
        if (now - s.voidTick < VOID_COOLDOWN) {
            return;
        }
        s.voidTick = now;
        long token = s.token;
        port.later(1, () -> {
            Live cur = live.get(s.uuid);
            if (cur != null && cur.token == token && cur.phase == Session.Phase.ACTIVE) {
                teleport(p, cur.safe != null ? cur.safe : cur.start);
                voidNextTick(cur);
            }
        });
    }

    /** A game's own teleport within the session world; the destination is armed as ours. */
    boolean teleport(P p, Place to) {
        UUID id = port.id(p);
        Live s = live.get(id);
        if (s == null || s.phase != Session.Phase.ACTIVE || to == null || !to.world().equals(s.world)) {
            return false;
        }
        if (port.loaded(to)) {
            boolean[] ok = {false};
            go(p, to, true, done -> ok[0] = done);
            if (!ok[0]) {
                disarm(id, to);
            }
            return ok[0];
        }
        go(p, to, false, done -> {
        });
        return true;
    }

    // ---- WP-CH (the Clubhouse): a session handed from one game to another ---------------------

    /**
     * Hand the player's ACTIVE session to another game ({@code gameId}, with its {@code ref} and its
     * {@code hooks}), in place: nothing is restored, saved or cleared, and the player stays where
     * they are. The saved-state row keeps the game the session began with (it is only what the next
     * join restores, whatever the game). A racer seated from the Clubhouse, or back in it after a
     * race, is one session all along, so their things are saved once and come back once.
     *
     * @return whether it was handed over (false: no ACTIVE session)
     */
    boolean passTo(P p, String gameId, String ref, Hooks<P> hooks) {
        Live s = live.get(port.id(p));
        if (s == null || s.phase != Session.Phase.ACTIVE || gameId == null || gameId.isBlank() || hooks == null) {
            return false;
        }
        s.gameId = gameId;
        s.ref = ref == null ? "" : ref;
        s.hooks = hooks;
        return true;
    }

    /**
     * WP-CH (the Clubhouse review, #3): empty a session player's inventory mid-session for another
     * game's kit without losing anything. Every item that isn't a kit item (an auction win or a Mini
     * delivered mid-session) is banked in the row's carry first, exactly as at the session's end, and
     * comes home with them; what the database refuses goes straight back into the inventory. The
     * player is saved straight after, so a crash can't bring back what was banked (final gate #17).
     *
     * @return whether it was done (false: no ACTIVE session with a row of its own, and nothing changed)
     */
    boolean bankExtras(P p) {
        UUID id = port.id(p);
        Live s = live.get(id);
        if (s == null || s.phase != Session.Phase.ACTIVE) {
            return false;
        }
        SavedState row;
        try {
            row = dao.loadState(id);
        } catch (SQLException | RuntimeException e) {
            log.log(Level.SEVERE, "Games: could not read " + port.name(p) + "'s saved state to keep their things", e);
            return false;
        }
        if (row == null || !s.sid.equals(row.sessionId())) {
            return false;
        }
        give(p, bank(p, row));
        // Write, then save (final gate #17), as every restore does: from here their own data file must
        // stop holding what is in the carry now, or a hard crash brings it back next to the "cleared"
        // mark and the crash-join banks it a second time (two copies of one numbered Mini).
        port.save(p);
        return true;
    }

    /**
     * WP-CH: the game mode the game wants for the player's ACTIVE session ({@code "SPECTATOR"} while a
     * Clubhouse watcher watches live; {@code "ADVENTURE"} or {@code null} is the games' usual). The
     * game-mode guard keeps them in it for THIS session only, and every restore puts back their own.
     *
     * @return whether it was recorded (false: no ACTIVE session)
     */
    boolean mode(P p, String mode) {
        Live s = live.get(port.id(p));
        if (s == null || s.phase != Session.Phase.ACTIVE) {
            return false;
        }
        s.mode = mode == null || "ADVENTURE".equals(mode) ? null : mode;
        return true;
    }

    /** The game mode a game set for the player's session now, or {@code null} (the games' ADVENTURE, or no session). */
    String mode(UUID player) {
        Live s = live.get(player);
        return s == null ? null : s.mode;
    }

    /** A session ends with nothing put back: out of a game's own mode, if they are still in it (#6). */
    private void noRestore(Live s) {
        String m = s.mode;
        s.mode = null;
        if (m != null) {
            try {
                port.resetMode(s.player, m);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Games: could not put " + port.name(s.player) + " back in adventure mode", e);
            }
        }
    }

    /**
     * A recovery outside a session that fails in place leaves the player as they are, but never in
     * spectator mode that wasn't their own (final gate #19). A Clubhouse watcher's SPECTATOR is the
     * session's, and after a crash there is no session left to take it back ({@link #noRestore} only
     * runs for a live one): with their world not loaded yet or a snapshot that no longer reads, they
     * would fly through every base until an admin helped. So, still in spectator mode while the mode
     * they came in with ({@code row}) isn't, they are put in adventure mode, as our own change. With
     * no row to read (the database is failing) that is done only in a Games world, where a live row
     * says a game put them there.
     */
    private void unstick(P p, SavedState row) {
        if (!port.online(p)) {
            return;
        }
        if (row == null ? !port.gamesWorld(port.world(p)) : SPECTATOR.equals(row.gameMode())) {
            return;
        }
        try {
            port.resetMode(p, SPECTATOR);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Games: could not take " + port.name(p) + " out of spectator mode", e);
        }
    }

    /**
     * A watcher (a session in a game's own mode) is somewhere a restore in place would be unsafe
     * (inside terrain, in mid-air where they were flying): first to the session's start, a floor
     * (the Clubhouse's arrival spot), unless the server is stopping (no teleport then) or someone
     * else's teleport is taking them away (the Clubhouse review, #7).
     */
    private void land(Live s, EndReason reason) {
        if (s.mode == null || s.start == null || reason == EndReason.TELEPORT || reason == EndReason.WORLD_CHANGE
                || port.stopping() || !s.start.world().equals(port.world(s.player))) {
            return;
        }
        try {
            port.teleportNow(s.player, s.start);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Games: could not bring " + port.name(s.player) + " down before putting their"
                    + " things back", e);
        }
    }

    /** Run a dismount the game makes itself, past the dismount guard and the teleport rule. */
    void ownDismount(P p, Runnable action) {
        UUID id = port.id(p);
        ownDismount.put(id, port.tick());
        try {
            action.run();
        } finally {
            ownDismount.put(id, port.tick());
        }
    }

    /** Whether a dismount of this player right now is one of ours (this tick or the last). */
    boolean dismountIsOurs(UUID id) {
        Long at = ownDismount.get(id);
        return at != null && port.tick() - at <= 1;
    }

    private void voidNextTick(Live s) {
        long token = s.token;
        port.later(1, () -> {
            Live cur = live.get(s.uuid);
            if (cur != null && cur.token == token && cur.phase == Session.Phase.ACTIVE) {
                cur.hooks.voided(cur.player);
            }
        });
    }

    // ---- recovery (joins, respawns, the worlds-up pass, /hcm leave, admins) ---------------------

    /** One tick after a join: strays out (unconditionally), then finish any row. */
    void joined(P p) {
        UUID id = port.id(p);
        if (live.containsKey(id)) {
            return;
        }
        port.stripKit(p);
        if (hasRow(id)) {
            recover(p, Why.JOIN);
        }
    }

    /** After a respawn: a player who died with an ACTIVE row gets it back (a little later, see RESPAWN_DELAY). */
    void respawned(P p) {
        UUID id = port.id(p);
        if (live.containsKey(id) || !hasRow(id)) {
            return;
        }
        port.later(RESPAWN_DELAY, () -> {
            if (port.online(p) && !port.dead(p)) {
                recover(p, Why.RESPAWN);
            }
        });
    }

    /** Once the worlds are up: finish every online player's row (a /reload, a stop). */
    void worldsReady(Collection<P> online) {
        for (P p : online) {
            UUID id = port.id(p);
            if (!live.containsKey(id) && hasRow(id)) {
                recover(p, Why.READY);
            }
        }
    }

    /**
     * Finish a row outside a session. A RETURN row only goes home (it is never applied again), and
     * so does an ACTIVE row whose snapshot is already on the player. Any other ACTIVE row is
     * restored in its session world, then goes home. Nobody is moved before what is needed has
     * been read (the snapshot and its world, or for a row whose things are back, the carry): a row
     * that can't be finished fails in place, with the player where they are, and waits for an admin.
     *
     * @return what happened, for an admin
     */
    String recover(P p, Why why) {
        UUID id = port.id(p);
        if (live.containsKey(id) || recovering.containsKey(id)) {
            return ADMIN_BUSY;
        }
        if (!port.online(p)) {
            return ADMIN_OFFLINE;
        }
        SavedState row;
        try {
            row = dao.loadState(id);
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Games: could not read " + port.name(p) + "'s saved state", e);
            unstick(p, null);
            return ADMIN_READ;
        }
        if (row == null) {
            rows.remove(id);
            if (why == Why.RETRY) {
                port.tell(p, NOT_IN_GAME);
            }
            return ADMIN_NONE;
        }
        rows.add(id);
        if (SavedState.RETURN.equals(row.phase()) || alreadyApplied(p, row)) {
            return sendHome(p, row, why);
        }
        if (port.dead(p)) {
            return ADMIN_DEAD; // after the respawn
        }
        if (!port.worldExists(row.sessionWorld())) {
            port.stripKit(p);
            unstick(p, row);
            failed(p, row, null, "its world " + row.sessionWorld() + " is gone");
            return "&cTheir game's world (" + row.sessionWorld() + ") is gone. &7Nothing was changed - see the server log.";
        }
        try {
            port.prepare(row); // read it all BEFORE anyone is moved: an unreadable one fails in place
        } catch (RuntimeException e) {
            port.stripKit(p);
            unstick(p, row);
            failed(p, row, e, "the saved state can't be read");
            return ADMIN_UNREADABLE;
        }
        long token = ++tokens;
        recovering.put(id, token);
        if (row.sessionWorld().equals(port.world(p))) {
            return restoreRow(p, row, why, token);
        }
        // Apply only in the session world (§7.5): go there first.
        Place there = port.spawn(row.sessionWorld());
        go(p, there, false, ok -> {
            if (!owns(id, token)) {
                return;
            }
            if (!ok || !port.online(p) || !row.sessionWorld().equals(port.world(p))) {
                recovering.remove(id);
                if (port.online(p)) {
                    unstick(p, row);
                    port.tell(p, NOT_RESTORED);
                }
                return;
            }
            restoreRow(p, row, why, token);
        });
        return "&aTaking them to " + row.sessionWorld() + " to put their things back.";
    }

    /**
     * A row whose things are on the player already (RETURN, or ACTIVE with the snapshot applied):
     * RETURN written if it isn't yet, then home and the carry. {@code /hcm leave} and a join after
     * the trip was made (or outside every Games world) only hand the carry over; an admin's
     * {@code return} always sends them home.
     */
    private String sendHome(P p, SavedState row, Why why) {
        UUID id = port.id(p);
        port.stripKit(p);
        if (!carryReadable(p, row)) {
            return ADMIN_CARRY; // fail in place: no trip home on every join
        }
        if (!SavedState.RETURN.equals(row.phase()) && !markReturn(p, row)) {
            port.tell(p, BACK_STAY);
            return ADMIN_WRITE;
        }
        if (why != Why.ADMIN && (reached.contains(row.sessionId()) || !port.gamesWorld(port.world(p)))) {
            return handOver(p, row.sessionId(), false);
        }
        long token = ++tokens;
        recovering.put(id, token);
        returnTrip(p, row, false, token);
        return ADMIN_SENDING;
    }

    /**
     * The restore of an ACTIVE row outside a session (crash-join, respawn, {@code /hcm leave}, an
     * admin), in its session world. What the player holds is banked first when it arrived after
     * the clear: always after a respawn, an admin's restore or {@code /hcm leave} (things they
     * gathered since a failure), and at a crash-join when their own data says it was saved after
     * the clear. Otherwise it is overwrite-only: that inventory may predate the game.
     */
    private String restoreRow(P p, SavedState row, Why why, long token) {
        UUID id = port.id(p);
        SavedState fresh;
        try {
            fresh = dao.loadState(id);
        } catch (SQLException e) {
            recovering.remove(id);
            log.log(Level.SEVERE, "Games: could not read " + port.name(p) + "'s saved state", e);
            unstick(p, null);
            return ADMIN_READ;
        }
        if (fresh == null || !fresh.sessionId().equals(row.sessionId()) || !SavedState.ACTIVE.equals(fresh.phase())) {
            recovering.remove(id); // it moved on while they travelled
            return ADMIN_MOVED;
        }
        if (alreadyApplied(p, fresh)) {
            recovering.remove(id); // read again here in the session world: never a second apply
            return sendHome(p, fresh, why);
        }
        Restore<P> restore;
        try {
            restore = port.prepare(fresh);
        } catch (RuntimeException e) {
            recovering.remove(id);
            port.stripKit(p);
            unstick(p, fresh);
            failed(p, fresh, e, "the saved state can't be read");
            return ADMIN_UNREADABLE;
        }
        List<I> unbanked = List.of();
        if (why == Why.RESPAWN || why == Why.ADMIN || why == Why.RETRY || clearedBody(p, fresh)) {
            unbanked = bank(p, fresh);
        } else {
            port.discardHeld(p); // overwrite only: what a joining player holds may predate the game
        }
        port.stripKit(p);
        try {
            restore.applyTo(p);
        } catch (RuntimeException e) {
            recovering.remove(id);
            give(p, unbanked);
            unstick(p, fresh); // only if the restore didn't get as far as their mode (it goes first)
            failed(p, fresh, e, "putting it back failed");
            return ADMIN_APPLY;
        }
        port.setMark(p, APPLIED + fresh.sessionId());
        give(p, unbanked);
        port.save(p);
        if (!markReturn(p, fresh)) {
            recovering.remove(id);
            port.stripKit(p);
            port.tell(p, BACK_STAY);
            return ADMIN_WRITE;
        }
        port.stripKit(p);
        port.tell(p, switch (why) {
            case JOIN, READY -> fresh.createdAt() < bootedAt ? BACK_AFTER_RESTART : BACK;
            case ADMIN -> BACK_BY_ADMIN;
            default -> BACK;
        });
        returnTrip(p, fresh, false, token);
        return ADMIN_BACK;
    }

    // ---- home -----------------------------------------------------------------------------------

    /** Teleport home ({@code from}, or the main spawn if its world is gone); then the carry and DONE. */
    private void returnTrip(P p, SavedState row, boolean sync, long token) {
        UUID id = port.id(p);
        Place from = SavedStateCodec.from(row);
        Place home = from != null && port.worldExists(from.world()) ? from : port.mainSpawn();
        if (home != from) {
            log.warning("Games: " + port.name(p) + " came from a world that is gone ("
                    + (from == null ? "none" : from.world()) + ") - sending them to spawn instead");
        }
        go(p, home, sync, ok -> arrivedHome(p, id, row.sessionId(), token, ok));
    }

    private void arrivedHome(P p, UUID id, String sid, long token, boolean ok) {
        if (!owns(id, token)) {
            return; // a quit, a stop or a newer session took over: the next join finishes it
        }
        release(id, token);
        if (!port.online(p)) {
            return;
        }
        if (!ok) {
            port.tell(p, NOT_HOME); // stays RETURN: the next join or /hcm leave tries again
            return;
        }
        reached.add(sid);
        handOver(p, sid, false);
    }

    /**
     * Home: the carry, then DONE — written first, given after. When it all fits, the row is marked
     * DONE (its carry cleared) and only then is the carry handed over; when some of it doesn't
     * fit, the rest is written back to the row (still RETURN, for {@code /hcm leave} once they have
     * made room) and only then is what fits handed over. A failed write gives nothing, so nothing
     * can be handed over twice; nothing is dropped.
     *
     * @param away a foreign teleport took them somewhere instead of our trip home: in a Games world
     *             other than the one they came from, the carry waits for {@code /hcm leave}
     * @return what happened, for an admin
     */
    private String handOver(P p, String sid, boolean away) {
        UUID id = port.id(p);
        SavedState row;
        try {
            row = dao.loadState(id);
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Games: could not read " + port.name(p) + "'s saved state", e);
            port.tell(p, NOT_DONE);
            return ADMIN_READ;
        }
        if (row == null) {
            rows.remove(id);
            return ADMIN_NONE;
        }
        if (!row.sessionId().equals(sid) || !SavedState.RETURN.equals(row.phase())) {
            return ADMIN_MOVED;
        }
        List<I> carry;
        try {
            carry = row.carry() == null ? List.of() : port.decode(row.carry());
        } catch (RuntimeException e) {
            failed(p, row, e, "the things kept for them can't be read");
            return ADMIN_CARRY;
        }
        port.stripKit(p);
        if (!carry.isEmpty() && away && awayInGames(p, row)) {
            port.tell(p, KEPT); // stays RETURN: /hcm leave takes them home and hands it over there
            return ADMIN_KEPT;
        }
        int fits = carry.isEmpty() ? 0 : Math.max(0, Math.min(carry.size(), port.room(p, carry)));
        List<I> now = new ArrayList<>(carry.subList(0, fits));
        List<I> later = new ArrayList<>(carry.subList(fits, carry.size()));
        boolean written;
        if (later.isEmpty()) {
            written = finish(p, sid);
        } else {
            written = now.isEmpty() || setCarry(p, sid, later); // nothing fits: nothing to write or give
        }
        if (!written) {
            port.tell(p, NOT_DONE); // nothing given: the row still holds all of it
            return ADMIN_DONE_WRITE;
        }
        if (!now.isEmpty()) {
            List<I> over = port.give(p, now);
            if (!over.isEmpty()) {
                // room() promised these would fit. Back into the row if it is still live; else at their feet.
                List<I> lost = later.isEmpty() ? over : addCarry(p, sid, over);
                for (I item : lost) {
                    log.severe("Games: no room for " + port.describe(item) + " after all - dropped at "
                            + port.name(p) + "'s feet (session " + sid + ")");
                }
                if (!lost.isEmpty()) {
                    port.drop(p, lost);
                }
            }
            port.save(p);
        }
        if (!later.isEmpty()) {
            port.tell(p, FULL); // stays RETURN until there is room
            return ADMIN_FULL;
        }
        reached.remove(sid);
        applied.remove(sid);
        if ((APPLIED + sid).equals(port.mark(p))) {
            port.setMark(p, null);
        }
        return ADMIN_HANDED;
    }

    // ---- admin ----------------------------------------------------------------------------------

    /** {@code /hcm games saved <player> restore}: put the saved things back now (§7.5 rules). */
    String adminRestore(P p) {
        UUID id = port.id(p);
        Live s = live.get(id);
        if (s != null) {
            if (s.phase == Session.Phase.ACTIVE) {
                leave(p, EndReason.ADMIN);
                return "&aEnded their game: their things are going back now.";
            }
            return "&7They're on their way into or out of a game. Try in a moment.";
        }
        if (recovering.containsKey(id)) {
            return ADMIN_BUSY;
        }
        SavedState row = rowOrNull(id);
        if (row == null) {
            return ADMIN_NONE;
        }
        if (SavedState.RETURN.equals(row.phase()) || alreadyApplied(p, row)) {
            return "&7Their things were put back already. Use &ereturn &7to send them home.";
        }
        if (port.dead(p)) {
            return ADMIN_DEAD;
        }
        reported.remove(row.sessionId());
        return recover(p, Why.ADMIN);
    }

    /** {@code /hcm games saved <player> return}: send them home (only once their things are back). */
    String adminReturn(P p) {
        UUID id = port.id(p);
        Live s = live.get(id);
        if (s != null) {
            return adminRestore(p);
        }
        if (recovering.containsKey(id)) {
            return "&7They're already on the way back.";
        }
        SavedState row = rowOrNull(id);
        if (row == null) {
            return ADMIN_NONE;
        }
        if (SavedState.ACTIVE.equals(row.phase()) && !alreadyApplied(p, row)) {
            return "&cTheir things haven't been put back yet. &7Use &erestore &7first.";
        }
        reported.remove(row.sessionId());
        return recover(p, Why.ADMIN);
    }

    /** {@code /hcm games saved <player> discard confirm}: delete the row for good (logged). */
    String adminDiscard(UUID id, String name, String by) {
        Live s = live.get(id);
        if (s != null) {
            return "&cThey're in a game. &7Use &erestore &7or &ereturn&7.";
        }
        if (recovering.containsKey(id)) {
            return "&7Their things are on the way back. Try in a moment.";
        }
        SavedState row = rowOrNull(id);
        if (row == null) {
            return "&7No saved things for them.";
        }
        try {
            if (!dao.deleteState(id, row.sessionId())) {
                return "&7No saved things for them.";
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Games: could not discard " + name + "'s saved state", e);
            return "&cCouldn't discard it. &7See the server log.";
        }
        rows.remove(id);
        applied.remove(row.sessionId());
        reached.remove(row.sessionId());
        log.warning("Games: " + by + " discarded " + name + "'s saved state (session " + row.sessionId()
                + ", game " + row.gameId() + ", phase " + row.phase() + ")");
        return "&aDiscarded their saved things.";
    }

    /** The player's most recent finished session still kept for support, or {@code null}. */
    SavedState lastDone(UUID id) {
        try {
            return dao.lastDoneState(id);
        } catch (SQLException e) {
            log.log(Level.WARNING, "Games: could not read finished saved states", e);
            return null;
        }
    }

    /** The live row, or {@code null} (also when it can't be read, which is logged). */
    SavedState rowOrNull(UUID id) {
        try {
            return dao.loadState(id);
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Games: could not read a saved state", e);
            return null;
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * Every teleport of ours: off any vehicle, armed, then sent; it lands with no fall and no speed
     * (final gate #18: a Dropper player sent home 30 blocks into a drop would take the whole fall at
     * home, where nothing cancels it, with their real things on them).
     */
    private void go(P p, Place to, boolean sync, Consumer<Boolean> done) {
        UUID id = port.id(p);
        ownDismount(p, () -> port.dismount(p));
        arm(id, to);
        Consumer<Boolean> landed = ok -> {
            if (Boolean.TRUE.equals(ok) && port.online(p)) {
                still(p);
            }
            done.accept(ok);
        };
        if (sync) {
            landed.accept(port.teleportNow(p, to));
        } else {
            port.teleport(p, to, landed);
        }
    }

    /** {@link Port#still}, never a throw into the step that called it. */
    private void still(P p) {
        try {
            port.still(p);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Games: could not stop " + port.name(p) + "'s fall", e);
        }
    }

    private void arm(UUID id, Place to) {
        ArrayDeque<Armed> list = armed.computeIfAbsent(id, k -> new ArrayDeque<>());
        list.addLast(new Armed(to, port.tick()));
        while (list.size() > 8) {
            list.removeFirst();
        }
    }

    private void disarm(UUID id, Place to) {
        ArrayDeque<Armed> list = armed.get(id);
        if (list != null) {
            list.removeIf(a -> a.target().equals(to));
        }
    }

    private boolean consumeOurs(UUID id, Place to, String cause) {
        ArrayDeque<Armed> list = armed.get(id);
        if (list == null || to == null) {
            return false;
        }
        long now = port.tick();
        for (Iterator<Armed> it = list.iterator(); it.hasNext(); ) {
            Armed a = it.next();
            if (now - a.tick() > ARM_TICKS) {
                it.remove();
            } else if (matches(a.target(), a.tick(), now, cause, to)) {
                it.remove();
                return true;
            }
        }
        if (list.isEmpty()) {
            armed.remove(id);
        }
        return false;
    }

    /** Whether the async step with {@code token} still belongs to the player's current session or recovery. */
    private boolean owns(UUID id, long token) {
        Live s = live.get(id);
        if (s != null) {
            return s.token == token;
        }
        Long r = recovering.get(id);
        return r != null && r == token;
    }

    private void release(UUID id, long token) {
        Live s = live.get(id);
        if (s != null && s.token == token) {
            live.remove(id);
        } else {
            Long r = recovering.get(id);
            if (r != null && r == token) {
                recovering.remove(id);
            }
        }
    }

    /**
     * Take everything the player holds that isn't the kit and add it to the row's carry.
     *
     * @return what couldn't be banked (the database refused), to be handed straight back
     */
    private List<I> bank(P p, SavedState row) {
        List<I> extras = port.takeExtras(p);
        if (extras.isEmpty()) {
            return List.of();
        }
        return addCarry(p, row.sessionId(), extras);
    }

    /** Add to the carry already in the row; each item is logged. Returns what couldn't be kept. */
    private List<I> addCarry(P p, String sid, List<I> items) {
        UUID id = port.id(p);
        try {
            SavedState row = dao.loadState(id);
            if (row == null || !row.sessionId().equals(sid)) {
                return items;
            }
            List<I> all = new ArrayList<>();
            if (row.carry() != null) {
                all.addAll(port.decode(row.carry())); // an unreadable carry is never overwritten
            }
            all.addAll(items);
            if (!dao.setCarry(id, sid, port.encode(all))) {
                return items;
            }
        } catch (SQLException | RuntimeException e) {
            log.log(Level.SEVERE, "Games: could not keep " + port.name(p) + "'s things in the carry (session "
                    + sid + ") - handing them straight back", e);
            return items;
        }
        for (I item : items) {
            log.info("Games: kept " + port.describe(item) + " for " + port.name(p) + " until they are home (session " + sid + ")");
        }
        return List.of();
    }

    private boolean setCarry(P p, String sid, List<I> items) {
        try {
            return dao.setCarry(port.id(p), sid, port.encode(items));
        } catch (SQLException | RuntimeException e) {
            log.log(Level.SEVERE, "Games: could not keep " + port.name(p) + "'s things (session " + sid + ")", e);
            return false;
        }
    }

    /** Extras the database refused: straight back into the inventory now the snapshot is on. */
    private void giveBackUnbanked(Live s) {
        List<I> items = new ArrayList<>(s.unbanked);
        s.unbanked.clear();
        give(s.player, items);
    }

    private void give(P p, List<I> items) {
        if (items.isEmpty()) {
            return;
        }
        List<I> left = port.give(p, items);
        if (!left.isEmpty()) {
            for (I item : left) {
                log.warning("Games: dropped " + port.describe(item) + " at " + port.name(p) + "'s feet");
            }
            port.drop(p, left);
        }
    }

    /**
     * The snapshot is on the player now: mark the row RETURN. If that write fails, the session is
     * remembered as applied for this run (and the player's own data says so, see {@link Port#mark}),
     * so nothing applies it again; the caller keeps the player where they are.
     *
     * @return whether the row says RETURN now (or is gone: nothing is left to apply)
     */
    private boolean markReturn(P p, SavedState row) {
        String sid = row.sessionId();
        try {
            if (!dao.setPhase(port.id(p), sid, SavedState.RETURN)) {
                log.severe("Games: " + port.name(p) + "'s saved state (session " + sid + ") was not there to mark RETURN");
            }
            applied.remove(sid);
            return true;
        } catch (SQLException | RuntimeException e) {
            applied.add(sid);
            log.log(Level.SEVERE, "Games: could not mark " + port.name(p) + "'s saved state RETURN (session " + sid
                    + "). Their things are back on them and it won't be applied again; they stay until it is written"
                    + " (/hcm leave tries again).", e);
            return false;
        }
    }

    /** DONE, carry cleared. Whether it was written: the carry is handed over only if it was. */
    private boolean finish(P p, String sid) {
        UUID id = port.id(p);
        try {
            if (dao.finishState(id, sid, port.now())) {
                rows.remove(id);
                return true;
            }
            log.warning("Games: " + port.name(p) + "'s saved state (session " + sid + ") was not there to finish");
            return false;
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Games: could not finish " + port.name(p) + "'s saved state (session " + sid
                    + ") - nothing handed over; it stays for /hcm leave", e);
            return false;
        }
    }

    /** Whether the row's snapshot is on the player already: RETURN couldn't be written (this run, or before a restart). */
    private boolean alreadyApplied(P p, SavedState row) {
        return applied.contains(row.sessionId()) || (APPLIED + row.sessionId()).equals(port.mark(p));
    }

    /** Whether the player's own data says it was saved after the clear for this row's session. */
    private boolean clearedBody(P p, SavedState row) {
        return (CLEARED + row.sessionId()).equals(port.mark(p));
    }

    /** Whether the row's carry reads; if not, it is kept for an admin and the player told (in place). */
    private boolean carryReadable(P p, SavedState row) {
        if (row.carry() == null) {
            return true;
        }
        try {
            port.decode(row.carry());
            return true;
        } catch (RuntimeException e) {
            failed(p, row, e, "the things kept for them can't be read");
            return false;
        }
    }

    /** In a Games world other than the one they came from. */
    private boolean awayInGames(P p, SavedState row) {
        String world = port.world(p);
        Place from = SavedStateCodec.from(row);
        return port.gamesWorld(world) && (from == null || !world.equals(from.world()));
    }

    /** A restore that can't happen: keep the row as it is, say so once in the log, reassure the player. */
    private void failed(P p, SavedState row, Throwable cause, String what) {
        if (reported.add(row.sessionId())) {
            log.log(Level.SEVERE, "Games: could not put back " + port.name(p) + "'s things (session "
                    + row.sessionId() + "): " + what + ". The saved state is kept - /hcm games saved "
                    + port.name(p) + " show", cause);
        }
        if (port.online(p)) {
            port.tell(p, SAFE_WITH_ADMIN);
        }
    }

    private void refuse(P p, String why) {
        if (port.online(p)) {
            port.tell(p, "&c" + why);
        }
    }

    private static String endLine(EndReason reason) {
        return switch (reason) {
            case GAME_OFF -> "&cThat game is taking a break. &7Your things are back.";
            case ADMIN -> "&7An admin ended your game. Your things are back.";
            case DEATH -> "&7You're out of the game. Your things are back.";
            case COMMAND, QUIT_ITEM, TELEPORT, WORLD_CHANGE -> "&7You left the game. Your things are back.";
            default -> null;
        };
    }
}
