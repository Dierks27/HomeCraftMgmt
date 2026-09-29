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
import java.util.function.Predicate;

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
 * driver ({@link #follow}) and is held there by the racers' own stand rule ({@link #onStand}). A rider
 * out of the boat while the driver is seated is put back once a second ({@link #second}).
 *
 * <p><b>Getting out.</b> A seated rider can't dismount mid-run (the session guard cancels it: the boat
 * is their game's). Their Leave game ends only THEIR session; the driver carries on. When the driver
 * finishes, is sent home, leaves or disconnects, the rider goes with them: to the Clubhouse when the
 * driver goes there (a party race, Race Night), else home ({@link #driverGone}, {@link #toClub}). A
 * ride that waits in the Clubhouse goes on at the driver's next race from there (Race Night's next
 * race on a track with no stand, a party's Race again), handed back to Time Trials in place.
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
    /**
     * A ride waiting in the Clubhouse between races (a Race Night break, a party's Race again) lapses
     * after this long with no next race.
     */
    static final long CLUB_LAPSE_MS = 5 * 60_000L;
    /** The reason a run with a rider is just for fun ({@code rider_runs_count: false}). */
    public static final String FUN_ONLY = "rides with a rider are just for fun on this server";
    /** A race finish with a rider while rides are just for fun: the place stands, nothing else. */
    public static final String FUN_RACE = "&eJust for fun: &7your place stands, but with a rider aboard the run"
            + " doesn't go on the boards or pay.";

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

        /** Whether the player is in the Clubhouse now. */
        boolean inClub(UUID id);

        /**
         * Hand the rider's Clubhouse session back to Time Trials ({@code courseId} its ref), in place,
         * for the driver's next race; false: it couldn't (they stay in the Clubhouse).
         */
        boolean fromClub(Player rider, String courseId);

        /** Whether the player is on a time-trial run now (racing, warming up, parked on a stand). */
        boolean driving(UUID id);

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
        /**
         * Both are in the Clubhouse between races (Race Night on a track with no stand, a party race):
         * the ride goes on at the driver's next seat (the Clubhouse review, #11).
         */
        boolean inClub;

        Ride(UUID driver, String driverName, UUID rider, String riderName, long now) {
            this.driver = driver;
            this.driverName = driverName;
            this.rider = rider;
            this.riderName = riderName;
            this.since = now;
        }
    }

    /**
     * A rider standing off the boat is kept this near their parked driver (the stand's radius); on a
     * race's stand, the racers' own stand rule holds them too ({@link #onStand}).
     */
    static final double FOLLOW_RADIUS = 4;
    /** What a rider who wandered off the stand reads (they are put back on it). */
    static final String BACK_TO_STAND = "&7Please watch from the stand - the boats race past here.";

    private final Port port;
    /** Told a driver's id each time a rider sits down behind them (Time Trials latches it on the run). */
    private Consumer<UUID> boarded = id -> {
    };
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

    /** Who hears that a rider sat down behind a driver (Time Trials: the run's {@code hadRider}). */
    void boarded(Consumer<UUID> listener) {
        this.boarded = listener == null ? id -> {
        } : listener;
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
        if (r.inClub) {
            backFromClub(r, driver, rider, boat, courseId);
            return;
        }
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

    /**
     * The driver's next race after a Clubhouse stay: the rider's session is handed back to Time Trials
     * in place (their things stay saved once), the rider's kit and team again, then the back seat.
     */
    private void backFromClub(Ride r, Player driver, Player rider, Entity boat, String courseId) {
        if (!port.inClub(r.rider) || !port.fromClub(rider, courseId)) {
            port.tell(driver, "&7" + r.riderName + " couldn't hop in this time.");
            return;
        }
        r.inClub = false;
        port.kit(rider, r.driverName);
        shield(rider.getUniqueId(), rider.getName());
        port.tell(rider, "&bRiding with " + r.driverName + " again &7- hold on tight!");
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
            boarded.accept(r.driver);
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

    /**
     * Each of race mode's stand checks for a parked driver (every 10 ticks, with the race's own stand and
     * {@code stand_radius}): their rider is held to the SAME stand by the SAME rule as the racers, and put
     * back on it when off (the final gate's #15). Being unpushable doesn't stop a player-driven boat (the
     * driver's client decides that), so keeping riders off the racing line is what keeps them safe; kept
     * only within {@value #FOLLOW_RADIUS} blocks of the driver, once a second, a rider could stand some 8
     * blocks from the stand and dash further.
     *
     * @param offStand the race's own rule: whether a spot is off its stand
     * @param stand    the stand, where a rider off it is put back
     */
    void onStand(UUID driver, Predicate<Location> offStand, Location stand) {
        Ride r = byDriver.get(driver);
        if (r == null || r.inClub || !r.inSession || offStand == null || stand == null) {
            return;
        }
        Player rider = port.online(r.rider);
        if (rider == null || !port.riding(rider)) {
            return; // not in the ride's session (on the way in, or gone): not ours to move
        }
        Location here = port.at(r.rider);
        boolean off = here == null || !java.util.Objects.equals(here.getWorld(), stand.getWorld())
                || offStand.test(here);
        if (off && port.teleport(rider, stand)) {
            port.tell(rider, BACK_TO_STAND);
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
     * handed there; home if the Clubhouse can't take them. The ride is KEPT for the driver's next race
     * from the Clubhouse (Race Night's next race on a track with no stand, a party's Race again: the
     * Clubhouse review, #11), and lapses after {@value #CLUB_LAPSE_MS} ms with none, or when either
     * of them leaves the Clubhouse some other way ({@link #second}).
     */
    void toClub(UUID driver, ClubVisits.Kind kind) {
        Ride r = byDriver.get(driver);
        if (r == null) {
            return;
        }
        unshield(r.rider); // the Clubhouse puts them on its own no-push team
        Player rider = port.online(r.rider);
        if (rider == null || !r.inSession || !port.riding(rider)) {
            drop(r);
            return;
        }
        if (!port.takeIn(rider, kind, "&7Back in the Clubhouse with " + r.driverName + "!")) {
            drop(r);
            port.tell(rider, "&7Your ride is over - thanks for riding along! Your things are back.");
            port.leave(rider, EndReason.FINISH);
            return;
        }
        r.inClub = true;
        r.since = port.now();
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
            if (r.inClub) {
                clubSecond(r, rider, now);
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

    /**
     * A ride waiting in the Clubhouse: over when the rider left it, when the driver went home (not in
     * the Clubhouse and not racing), or after {@value #CLUB_LAPSE_MS} ms with no next race.
     */
    private void clubSecond(Ride r, Player rider, long now) {
        if (!port.inClub(r.rider)) {
            drop(r); // they left the Clubhouse (their Leave game): their session end put their things back
            return;
        }
        boolean driverAway = !port.inClub(r.driver) && !port.driving(r.driver);
        if (driverAway || now - r.since >= CLUB_LAPSE_MS) {
            drop(r);
            port.tell(rider, "&7Your ride with " + r.driverName + " is over - thanks for riding along!");
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
