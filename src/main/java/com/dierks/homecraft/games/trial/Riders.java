package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Ride along (CLUBHOUSE-SPEC §12, the owner: "I have a 4 year old that loves to ride in the back of the
 * boat"): a boat driver takes ONE passenger in the back seat, on a solo boat run, a party race or Race
 * Night.
 *
 * <p><b>A passenger, never a racer.</b> The rider is in a lightweight Time Trials session of their own
 * (their things kept safe, the kit only Leave game), with no run: never timed, counted, paid, on a
 * board, a quest, an achievement or in the Cup, and never in a party's or a night's racer list.
 *
 * <p><b>One seat path.</b> Every place the driver's boat is spawned or re-seated goes through Time
 * Trials' one {@code seat}: right after the driver boards, {@link #seated} brings the rider (their
 * session entered at the boat the first time, else their own session teleport, checked) and seats
 * them second. A boat removed ejects both (as the games' own dismount); the driver's teleport and
 * re-seat then brings the rider along on the next {@code seat}. On the stand the rider stands by the
 * driver ({@link #follow}). A rider out of the boat while the driver is seated is put back once a
 * second ({@link #second}).
 *
 * <p><b>Getting out.</b> A seated rider can't dismount mid-run (the session guard cancels it: the boat
 * is their game's). Their Leave game ends only THEIR session; the driver carries on. When the driver
 * finishes, is sent home, leaves or disconnects, the rider goes with them: to the Clubhouse when the
 * driver goes there (a party race, Race Night), else home ({@link #driverGone}, {@link #toClub}).
 *
 * <p><b>Counting.</b> A passenger doesn't change a boat's speed, so the driver's run counts as normal;
 * with {@code games.trials.rider_runs_count: false} a run with a rider is just for fun
 * ({@link #funOnly}), and Race Night refuses riders.
 *
 * <p>Everything the server does comes through {@link Port}, so every rule runs in a test.
 */
public final class Riders {

    /** The invite key ({@code /hcm play invites off} covers it; with no row it follows party invites). */
    public static final String INVITE_KEY = "rider";
    /** How long a rider invite waits for an answer. */
    static final int INVITE_SECONDS = 60;
    /** A pairing nobody rode with lapses after this long. */
    static final long LAPSE_MS = 10 * 60_000L;
    /** The reason a run with a rider is just for fun ({@code rider_runs_count: false}). */
    public static final String FUN_ONLY = "rides with a rider are just for fun on this server";

    /** What riding along needs from the server. */
    interface Port {
        Player online(UUID id);

        /** Enter the rider's own passenger session at {@code at} ({@code courseId} its ref); false: refused (told). */
        boolean enter(Player rider, String courseId, Location at, Consumer<Player> ready);

        /** Whether the player is in an ACTIVE session of Time Trials that is a rider's (no run). */
        boolean riding(Player p);

        /** The session's own teleport (armed), checked. */
        boolean teleport(Player p, Location at);

        /** Seat the rider behind the driver in {@code boat} (the driver is in it already). */
        boolean board(Entity boat, Player rider);

        /** Whether the rider is in {@code boat} now. */
        boolean aboard(Entity boat, Player rider);

        /** End the player's session (their things come back). */
        void leave(Player p, EndReason why);

        /** The Clubhouse takes the rider's session in place; false: it can't (they go home). */
        boolean takeIn(Player rider, ClubVisits.Kind kind, String line);

        /** The rider's kit: Leave game, and who they ride with. */
        void kit(Player rider, String driverName);

        /** Allow (or, with {@code null}, stop allowing) {@code rider} into {@code driver}'s game boat. */
        void passenger(UUID driver, UUID rider);

        /** On (or off) the no-push team, by id: a rider who already left comes off too. */
        void noPush(UUID id, String name, boolean on);

        /**
         * Whether the player can be pushed (and so collide with a racing boat): off for a rider for the
         * whole ride, back on at every end (the Clubhouse review, #4). Nothing for a player gone.
         */
        void collidable(UUID id, boolean on);

        /** Where the player is, or {@code null}. */
        Location at(UUID id);

        void tell(Player p, String line);

        long now();
    }

    /** A driver and their rider. */
    static final class Ride {
        final UUID driver;
        final String driverName;
        final UUID rider;
        final String riderName;
        long since;
        /** The rider's passenger session is on its way or running. */
        boolean inSession;
        /** Seated at least once. */
        boolean seated;

        Ride(UUID driver, String driverName, UUID rider, String riderName, long now) {
            this.driver = driver;
            this.driverName = driverName;
            this.rider = rider;
            this.riderName = riderName;
            this.since = now;
        }
    }

    /** A rider standing off the boat is kept this near their parked driver (the stand's radius). */
    static final double FOLLOW_RADIUS = 4;

    private final Port port;
    private final Map<UUID, Ride> byDriver = new HashMap<>();
    private final Map<UUID, Ride> byRider = new HashMap<>();
    /**
     * Riders on the no-push team and not collidable, by id to their name: from their arrival to EVERY
     * end of the ride, kept apart from the pairing (which ends first when the driver goes), so the
     * rider's own session end still finds them (the Clubhouse review, #2).
     */
    private final Map<UUID, String> shielded = new HashMap<>();

    Riders(Port port) {
        this.port = port;
    }

    // ---- pairing --------------------------------------------------------------------------------

    /** The driver's ride, or {@code null}. */
    Ride ofDriver(UUID driver) {
        return byDriver.get(driver);
    }

    /** The rider's ride, or {@code null}. */
    Ride ofRider(UUID rider) {
        return byRider.get(rider);
    }

    /** Whether {@code player} rides with someone (or is about to). */
    boolean isRider(UUID player) {
        return byRider.containsKey(player);
    }

    /**
     * The invite was accepted: {@code rider} rides with {@code driver} from the driver's next seat (at
     * once if the driver is seated in a boat now).
     *
     * @param boat the driver's boat now, or {@code null}
     */
    void paired(Player driver, Player rider, String courseId, Entity boat) {
        Ride r = new Ride(driver.getUniqueId(), driver.getName(), rider.getUniqueId(), rider.getName(), port.now());
        byDriver.put(r.driver, r);
        byRider.put(r.rider, r);
        port.passenger(r.driver, r.rider);
        port.tell(driver, "&a" + rider.getName() + " rides with you! &7They hop in the back seat when you're in your boat.");
        port.tell(rider, "&aYou're riding with " + driver.getName() + "! &7Hold on tight - you get in when they're in"
                + " their boat.");
        if (boat != null && courseId != null) {
            seated(driver, boat, courseId);
        }
    }

    /**
     * Right after the driver boarded a fresh boat (at the start, on the grid, a re-grid, a checkpoint
     * re-seat): the rider comes along and sits behind them. Their passenger session is entered at the
     * boat the first time; after that it is their own teleport, checked.
     */
    void seated(Player driver, Entity boat, String courseId) {
        Ride r = byDriver.get(driver.getUniqueId());
        if (r == null || boat == null) {
            return;
        }
        Player rider = port.online(r.rider);
        if (rider == null) {
            drop(r);
            return;
        }
        Location at = boat.getLocation();
        if (!r.inSession) {
            r.inSession = true;
            boolean in = port.enter(rider, courseId, at, q -> arrived(q, driver.getUniqueId()));
            if (!in) {
                r.inSession = false; // they were told why (not standing still, say): tried at the next seat
                port.tell(driver, "&7" + r.riderName + " couldn't hop in right now - they get in at your next start.");
            }
            return;
        }
        if (!port.riding(rider)) {
            return; // still on the way in: they board on arrival
        }
        board(r, rider, boat);
    }

    /** The rider's passenger session is ready: the kit, the no-push team, and the back seat. */
    private void arrived(Player rider, UUID driverId) {
        Ride r = byRider.get(rider.getUniqueId());
        if (r == null || !r.driver.equals(driverId)) {
            port.leave(rider, EndReason.FINISH); // the ride ended while they were on the way
            return;
        }
        port.kit(rider, r.driverName);
        shield(rider.getUniqueId(), rider.getName());
        port.tell(rider, "&bRiding with " + r.driverName + " &7- hold on tight! Leave game takes you home.");
        Player driver = port.online(r.driver);
        Entity boat = driver == null ? null : driver.getVehicle();
        if (boat != null) {
            board(r, rider, boat);
        }
    }

    /** Teleport the rider to the boat (checked), then seat them behind the driver. */
    private boolean board(Ride r, Player rider, Entity boat) {
        if (port.aboard(boat, rider)) {
            return true;
        }
        if (!port.teleport(rider, boat.getLocation())) {
            return false;
        }
        if (port.board(boat, rider)) {
            r.seated = true;
            r.since = port.now();
            return true;
        }
        return false;
    }

    /**
     * The driver was moved without a boat (onto the stand): the rider stands by them. A teleport that
     * fails is tried again each second ({@link #second}) while the driver is parked.
     */
    void follow(Player driver, Location at) {
        Ride r = byDriver.get(driver.getUniqueId());
        Player rider = r == null ? null : port.online(r.rider);
        if (rider != null && port.riding(rider) && at != null) {
            port.teleport(rider, at);
        }
    }

    /** On the no-push team and not collidable, for the whole ride. */
    private void shield(UUID id, String name) {
        shielded.put(id, name);
        port.noPush(id, name, true);
        port.collidable(id, false);
    }

    /** Off the no-push team and collidable again (once: every end calls this). */
    private void unshield(UUID id) {
        String name = shielded.remove(id);
        if (name != null) {
            port.noPush(id, name, false);
            port.collidable(id, true);
        }
    }

    /** Whether the rider is on the no-push team and not collidable now (tests). */
    boolean shielded(UUID rider) {
        return shielded.containsKey(rider);
    }

    /** The rider in the driver's boat now (their dismount is the games' own when the boat goes), or {@code null}. */
    Player riderIn(Entity boat, UUID driver) {
        Ride r = byDriver.get(driver);
        Player rider = r == null ? null : port.online(r.rider);
        return rider != null && boat != null && port.aboard(boat, rider) ? rider : null;
    }

    // ---- ending ---------------------------------------------------------------------------------

    /**
     * The driver's session ended (a finish home, a leave, a disconnect, the race's end): the rider goes
     * home with them, their things back. A pairing never used just ends.
     */
    void driverGone(UUID driver) {
        Ride r = byDriver.get(driver);
        if (r == null) {
            return;
        }
        drop(r);
        unshield(r.rider); // the rider's own session end won't find the pairing any more (#2)
        Player rider = port.online(r.rider);
        if (rider != null && r.inSession && port.riding(rider)) {
            port.tell(rider, "&7Your ride is over - thanks for riding along! Your things are back.");
            port.leave(rider, EndReason.FINISH);
        }
    }

    /**
     * The driver went to the Clubhouse (a party race or Race Night): the rider goes too, their session
     * handed there; home if the Clubhouse can't take them.
     */
    void toClub(UUID driver, ClubVisits.Kind kind) {
        Ride r = byDriver.get(driver);
        if (r == null) {
            return;
        }
        drop(r);
        unshield(r.rider); // the Clubhouse puts them on its own no-push team
        Player rider = port.online(r.rider);
        if (rider == null || !r.inSession || !port.riding(rider)) {
            return;
        }
        if (!port.takeIn(rider, kind, "&7Back in the Clubhouse with " + r.driverName + "!")) {
            port.tell(rider, "&7Your ride is over - thanks for riding along! Your things are back.");
            port.leave(rider, EndReason.FINISH);
        }
    }

    /** The rider's own session ended (their Leave game, a quit): off the ride; the driver carries on. */
    void riderGone(UUID rider) {
        Ride r = byRider.get(rider);
        if (r == null) {
            return;
        }
        drop(r);
        unshield(rider);
        Player driver = port.online(r.driver);
        if (driver != null) {
            port.tell(driver, "&7" + r.riderName + " hopped out - you carry on.");
        }
    }

    /** A session ended: the driver's (the rider goes too) or the rider's (the driver carries on). */
    void sessionEnded(UUID player) {
        unshield(player); // a rider whose ride ended first (the driver's finish) comes off here at the latest
        if (byRider.containsKey(player)) {
            riderGone(player);
        } else if (byDriver.containsKey(player)) {
            driverGone(player);
        }
    }

    private void drop(Ride r) {
        byDriver.remove(r.driver, r);
        byRider.remove(r.rider, r);
        port.passenger(r.driver, null);
    }

    /**
     * Once a second: a rider out of the boat while their driver is seated is put back in; a pairing
     * nobody rode with for {@value #LAPSE_MS} ms lapses.
     */
    void second() {
        long now = port.now();
        for (Ride r : new ArrayList<>(byDriver.values())) {
            Player driver = port.online(r.driver);
            Player rider = port.online(r.rider);
            if (driver == null) {
                driverGone(r.driver);
                continue;
            }
            if (rider == null) {
                drop(r);
                continue;
            }
            if (!r.inSession && now - r.since >= LAPSE_MS) {
                drop(r);
                port.tell(rider, "&7Your ride with " + r.driverName + " didn't start, so it's off.");
                continue;
            }
            Entity boat = driver.getVehicle();
            if (!r.inSession || !port.riding(rider)) {
                continue;
            }
            if (boat != null) {
                if (!port.aboard(boat, rider)) {
                    board(r, rider, boat);
                }
            } else if (r.seated) {
                keepNear(rider, r.driver); // the driver is parked (the stand): the rider stays by them (#4)
            }
        }
    }

    /** A rider more than {@value #FOLLOW_RADIUS} blocks from their parked driver is brought back to them. */
    private void keepNear(Player rider, UUID driver) {
        Location d = port.at(driver);
        Location here = port.at(rider.getUniqueId());
        if (d == null) {
            return;
        }
        boolean far = here == null || !java.util.Objects.equals(here.getWorld(), d.getWorld())
                || square(here.getX() - d.getX()) + square(here.getY() - d.getY()) + square(here.getZ() - d.getZ())
                > FOLLOW_RADIUS * FOLLOW_RADIUS;
        if (far) {
            port.teleport(rider, d);
        }
    }

    private static double square(double v) {
        return v * v;
    }

    /** Time Trials is stopping: every pairing ends (the framework ends the sessions), every rider off no-push. */
    void stop() {
        for (Ride r : new ArrayList<>(byDriver.values())) {
            drop(r);
        }
        for (UUID id : new ArrayList<>(shielded.keySet())) {
            unshield(id);
        }
    }

    /**
     * Whether a finish of {@code driver}'s is just for fun: they have a rider aboard, and
     * {@code games.trials.rider_runs_count} is false.
     */
    boolean funOnly(UUID driver, boolean riderRunsCount) {
        Ride r = byDriver.get(driver);
        return !riderRunsCount && r != null && r.seated;
    }

    /** Every ride now (status, tests). */
    List<Ride> rides() {
        return new ArrayList<>(byDriver.values());
    }
}
