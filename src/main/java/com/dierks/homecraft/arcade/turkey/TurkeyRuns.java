package com.dierks.homecraft.arcade.turkey;

import com.dierks.homecraft.arcade.turkey.TurkeyRules.Kind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The turkey runs going on now, as a small state machine behind {@link TurkeyHost}: a run starts, each player is
 * offered a turkey of their own, taps catch, and the run ends once when its time is up. Memory only: a restart
 * ends every run, and the claims and welcome marks the host keeps in the database are what stop a second turkey
 * or a second prize afterwards. Pure, so {@code TurkeyRunsTest} drives it with a fake host.
 *
 * <p>In a scheduled run anyone may catch any turkey, once each, and the turkeys stay until the run ends, so a
 * player whose turkey was caught by someone else still catches it and gets exactly the same prize. A welcome
 * turkey is its owner's alone.
 */
public final class TurkeyRuns {

    /** One run: its id, kind, owner (a welcome run's), end, and who has a turkey or has caught one. */
    public static final class Run {
        private final String id;
        private final Kind kind;
        private final UUID owner;
        private final long endsAt;
        private final boolean test;
        /** Each player's own turkey in this run: player to turkey. */
        private final Map<UUID, UUID> turkeys = new LinkedHashMap<>();
        /** Who caught a turkey in this run, in order. */
        private final Set<UUID> catchers = new LinkedHashSet<>();
        /** Who had already claimed this run before it was offered (a restart): never offered again. */
        private final Set<UUID> declined = new HashSet<>();

        Run(String id, Kind kind, UUID owner, long endsAt, boolean test) {
            this.id = id;
            this.kind = kind;
            this.owner = owner;
            this.endsAt = endsAt;
            this.test = test;
        }

        public String id() {
            return id;
        }

        public Kind kind() {
            return kind;
        }

        /** A welcome run's owner; null for a scheduled run. */
        public UUID owner() {
            return owner;
        }

        public long endsAt() {
            return endsAt;
        }

        /** A test run ({@code /hcm turkey now} outside the window): no claim, no prize but the moment. */
        public boolean test() {
            return test;
        }

        /** Player to their turkey, for the live turkeys of this run. */
        public Map<UUID, UUID> turkeys() {
            return Collections.unmodifiableMap(turkeys);
        }

        /** Who caught a turkey in this run, in order. */
        public Set<UUID> catchers() {
            return Collections.unmodifiableSet(catchers);
        }

        /** Milliseconds left at {@code now}, never below 0. */
        public long msLeft(long now) {
            return Math.max(0, endsAt - now);
        }
    }

    /** What offering a player a turkey did. */
    public enum Offer {
        /** A turkey appeared in front of them. */
        SPAWNED,
        /** They have one in this run already. */
        HAS_ONE,
        /** They caught this run already (now, or before a restart): no turkey they can't catch. */
        CAUGHT,
        /** Someone else's welcome run. */
        NOT_YOURS,
        /** They had their welcome turkey today. */
        WELCOME_GIVEN,
        /** {@value TurkeyRules#MAX_TURKEYS_PER_RUN} turkeys are out in this run. */
        CAP,
        /** No spot in front of them fits a turkey (it is tried again later). */
        NO_SPOT,
        /** The run isn't going on. */
        NOT_LIVE
    }

    /** What a tap that counts did. */
    public enum Tap {
        /** Not a turkey of ours (a leftover; the caller removes it). */
        NOT_OURS,
        /** Caught: the prize is given. */
        CAUGHT,
        /** Already caught by this player in this run: a soft cluck. */
        CLUCK,
        /** Someone else's welcome turkey: a soft cluck. */
        NOT_YOURS
    }

    private final TurkeyHost host;
    private final Map<String, Run> live = new LinkedHashMap<>();
    private final Map<UUID, Run> byTurkey = new HashMap<>();
    /** Runs that have ended: never started again (a reload in a slot's first minutes). */
    private final Set<String> ended = new HashSet<>();

    public TurkeyRuns(TurkeyHost host) {
        this.host = host;
    }

    /**
     * Start a run, or the one already going on under that id; null when that id has ended before. A run lasts
     * {@code lengthMs} from {@code now}.
     */
    public Run start(String id, Kind kind, UUID owner, long now, long lengthMs, boolean test) {
        Run r = live.get(id);
        if (r != null) {
            return r;
        }
        if (ended.contains(id)) {
            return null;
        }
        r = new Run(id, kind, kind == Kind.WELCOME ? owner : null, now + Math.max(0, lengthMs), test);
        live.put(id, r);
        return r;
    }

    /**
     * Offer a player a turkey in a run: none in a run they caught (memory, then the host's claim), none in
     * someone else's welcome run, no second welcome turkey in a day, and at most
     * {@value TurkeyRules#MAX_TURKEYS_PER_RUN} out. A welcome turkey that appears is marked at once.
     */
    public Offer offer(String runId, UUID player) {
        Run r = live.get(runId);
        if (r == null) {
            return Offer.NOT_LIVE;
        }
        if (r.kind == Kind.WELCOME && !player.equals(r.owner)) {
            return Offer.NOT_YOURS;
        }
        if (r.turkeys.containsKey(player)) {
            return Offer.HAS_ONE;
        }
        if (r.catchers.contains(player) || r.declined.contains(player)) {
            return Offer.CAUGHT;
        }
        boolean claimed = !r.test && host.claimed(player, r.id);
        boolean welcomeGiven = r.kind == Kind.WELCOME && !r.test && host.welcomeGiven(player);
        if (!TurkeyRules.spawnFor(claimed, welcomeGiven, r.kind)) {
            if (claimed) {
                r.declined.add(player);
                return Offer.CAUGHT;
            }
            return Offer.WELCOME_GIVEN;
        }
        if (r.turkeys.size() >= TurkeyRules.MAX_TURKEYS_PER_RUN) {
            return Offer.CAP;
        }
        UUID turkey = host.spawn(player, r.id);
        if (turkey == null) {
            return Offer.NO_SPOT;
        }
        if (r.kind == Kind.WELCOME && !r.test && !host.markWelcome(player)) {
            // given already (a second join raced this one): this one goes again at once
            host.remove(turkey);
            return Offer.WELCOME_GIVEN;
        }
        r.turkeys.put(player, turkey);
        byTurkey.put(turkey, r);
        return Offer.SPAWNED;
    }

    /**
     * A tap that counts ({@link TurkeyRules#catchStep}) on a turkey: the claim first, then the prize. A player
     * catches each run once; a repeat tap clucks.
     */
    public Tap tap(UUID player, UUID turkey) {
        Run r = byTurkey.get(turkey);
        if (r == null) {
            return Tap.NOT_OURS;
        }
        if (r.kind == Kind.WELCOME && !player.equals(r.owner)) {
            host.cluck(player, turkey);
            return Tap.NOT_YOURS;
        }
        if (r.catchers.contains(player) || r.declined.contains(player)) {
            host.cluck(player, turkey);
            return Tap.CLUCK;
        }
        if (!r.test && !host.claim(player, r.id)) {
            r.declined.add(player);
            host.cluck(player, turkey);
            return Tap.CLUCK;
        }
        r.catchers.add(player);
        host.caught(player, turkey, r.id, r.test);
        return Tap.CAUGHT;
    }

    /** End every run whose time is up at {@code now}, each exactly once: its turkeys go. The ids that ended. */
    public List<String> expire(long now) {
        List<String> out = new ArrayList<>();
        for (Run r : new ArrayList<>(live.values())) {
            if (now >= r.endsAt) {
                end(r);
                out.add(r.id);
            }
        }
        return out;
    }

    /**
     * One turkey ended on its own (wandered off, wet, gone, its owner left): it goes. A welcome run ends with its
     * turkey; in a scheduled run the player can be offered a new one while there is time.
     */
    public void lost(UUID turkey) {
        Run r = byTurkey.remove(turkey);
        if (r == null) {
            return;
        }
        r.turkeys.values().remove(turkey);
        host.remove(turkey);
        if (r.kind == Kind.WELCOME) {
            end(r);
        }
    }

    /** The player left: their turkeys go. */
    public void quit(UUID player) {
        for (Run r : new ArrayList<>(live.values())) {
            UUID turkey = r.turkeys.get(player);
            if (turkey != null) {
                lost(turkey);
            }
        }
    }

    /** A run that never got a turkey out (a welcome turkey with no spot yet): gone, and free to start again. */
    public void abandon(String id) {
        Run r = live.get(id);
        if (r != null && r.turkeys.isEmpty()) {
            live.remove(id);
        }
    }

    /** {@code /hcm turkey clear}, a reload and a stop: every run ends and every turkey goes. How many turkeys went. */
    public int clear() {
        int n = byTurkey.size();
        for (Run r : new ArrayList<>(live.values())) {
            end(r);
        }
        return n;
    }

    /** The runs going on, in the order they started. */
    public Collection<Run> live() {
        return Collections.unmodifiableCollection(live.values());
    }

    /** The run going on under that id, or null. */
    public Run run(String id) {
        return live.get(id);
    }

    /** The run a turkey belongs to, or null when it isn't one of ours. */
    public Run runOf(UUID turkey) {
        return byTurkey.get(turkey);
    }

    /** The turkey's owner (the player it appeared for), or null. */
    public UUID ownerOf(UUID turkey) {
        Run r = byTurkey.get(turkey);
        if (r == null) {
            return null;
        }
        for (Map.Entry<UUID, UUID> e : r.turkeys.entrySet()) {
            if (e.getValue().equals(turkey)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Whether the player has a turkey out in any run. */
    public boolean hasTurkey(UUID player) {
        for (Run r : live.values()) {
            if (r.turkeys.containsKey(player)) {
                return true;
            }
        }
        return false;
    }

    /** Every live turkey. */
    public Set<UUID> turkeys() {
        return Collections.unmodifiableSet(byTurkey.keySet());
    }

    /** Whether nothing is going on. */
    public boolean isEmpty() {
        return live.isEmpty();
    }

    private void end(Run r) {
        for (UUID turkey : new ArrayList<>(r.turkeys.values())) {
            byTurkey.remove(turkey);
            host.remove(turkey);
        }
        r.turkeys.clear();
        live.remove(r.id);
        ended.add(r.id);
    }
}
