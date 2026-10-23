package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.world.WorldEntities;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ride along (CLUBHOUSE-SPEC §12) on a fake server: the rider sits behind the driver on every seat
 * (the start, the grid, a re-grid, a checkpoint re-seat), is put back if they end up out of the boat,
 * may get into the driver's game boat (and nobody else may), and every way the ride ends sends them
 * the right way: their own Leave ends only their ride; the driver's finish, leave or quit takes them
 * home; the driver going to the Clubhouse takes them there. A rider has no run, so nothing of theirs
 * is ever timed, counted, paid or put on a board; with {@code rider_runs_count: false} the driver's
 * run with a rider aboard is just for fun.
 *
 * <p>Getting out mid-run is the session guard's existing rule (KitGuardListener.ownVehicleExit): the
 * rider's session is Time Trials' and so is the boat, so a dismount that isn't the games' own is
 * cancelled; when the boat goes, {@link Riders#riderIn} makes the rider's getting out the games' own.
 */
class RidersTest {

    /** A player: who they are and the boat they sit in (for a driver). */
    static final class Fake {
        final UUID id = UUID.randomUUID();
        final String name;
        Entity vehicle;
        final Player player;

        Fake(String name) {
            this.name = name;
            this.player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, m, args) -> switch (m.getName()) {
                        case "getUniqueId" -> id;
                        case "getName" -> name;
                        case "isOnline" -> true;
                        case "getVehicle" -> vehicle;
                        case "hashCode" -> id.hashCode();
                        case "equals" -> proxy == args[0];
                        case "toString" -> name;
                        default -> null;
                    });
        }
    }

    /** A game boat at {@code x}. */
    static Entity boat(double x) {
        Location at = new Location(null, x, 65, 0);
        UUID id = UUID.randomUUID();
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getLocation" -> at.clone();
                    case "getUniqueId" -> id;
                    case "isValid" -> true;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "boat@" + x;
                    default -> null;
                });
    }

    /** The server as ride along sees it. */
    static final class Port implements Riders.Port {
        final Map<UUID, Player> online = new HashMap<>();
        /** Riders whose passenger session is running. */
        final Set<UUID> riding = new HashSet<>();
        /** Entries asked for: the rider and where. */
        final List<String> entered = new ArrayList<>();
        final Map<UUID, Consumer<Player>> ready = new HashMap<>();
        boolean enterOk = true;
        final List<String> teleports = new ArrayList<>();
        /** Who sits in which boat. */
        final Map<Entity, List<UUID>> seats = new LinkedHashMap<>();
        final List<String> left = new ArrayList<>();
        boolean clubOk = true;
        final List<String> takenIn = new ArrayList<>();
        final Map<UUID, String> kits = new HashMap<>();
        final Set<UUID> noPush = new HashSet<>();
        final Map<UUID, List<String>> told = new HashMap<>();
        long now = 1_000_000L;

        void add(Fake f) {
            online.put(f.id, f.player);
        }

        String last(Fake f) {
            List<String> l = told.getOrDefault(f.id, List.of());
            return l.isEmpty() ? "" : l.getLast().replaceAll("&[0-9a-fk-or]", "");
        }

        @Override
        public Player online(UUID id) {
            return online.get(id);
        }

        @Override
        public boolean enter(Player rider, String courseId, Location at, Consumer<Player> onReady) {
            entered.add(rider.getName() + "@" + (int) at.getX() + " " + courseId);
            if (!enterOk) {
                return false;
            }
            ready.put(rider.getUniqueId(), onReady);
            return true;
        }

        /** The rider's session is ready (the arrival the framework reports). */
        void arrive(Fake rider) {
            riding.add(rider.id);
            ready.remove(rider.id).accept(rider.player);
        }

        @Override
        public boolean riding(Player p) {
            return riding.contains(p.getUniqueId());
        }

        /** Whether the session's own teleports work (a chunk that won't load, say). */
        boolean teleportOk = true;

        @Override
        public boolean teleport(Player p, Location at) {
            teleports.add(p.getName() + "@" + (int) at.getX());
            if (teleportOk) {
                where.put(p.getUniqueId(), at.clone());
            }
            return teleportOk;
        }

        @Override
        public boolean board(Entity boat, Player rider) {
            seats.values().forEach(l -> l.remove(rider.getUniqueId()));
            seats.computeIfAbsent(boat, b -> new ArrayList<>()).add(rider.getUniqueId());
            return true;
        }

        @Override
        public boolean aboard(Entity boat, Player rider) {
            return seats.getOrDefault(boat, List.of()).contains(rider.getUniqueId());
        }

        @Override
        public void leave(Player p, EndReason why) {
            left.add(p.getName() + " " + why);
            riding.remove(p.getUniqueId());
        }

        @Override
        public boolean takeIn(Player rider, ClubVisits.Kind kind, String line) {
            takenIn.add(rider.getName() + " " + kind);
            if (clubOk) {
                riding.remove(rider.getUniqueId()); // a Clubhouse session now
                club.add(rider.getUniqueId());
            }
            return clubOk;
        }

        @Override
        public void kit(Player rider, String driverName) {
            kits.put(rider.getUniqueId(), driverName);
        }

        @Override
        public void passenger(UUID driver, UUID rider) {
            WorldEntities.passenger(driver, rider);
        }

        @Override
        public void noPush(UUID id, String name, boolean on) {
            if (on) {
                noPush.add(id);
            } else {
                noPush.remove(id);
            }
        }

        /** Who can't be pushed (or collide with a racing boat) now. */
        final Set<UUID> notCollidable = new HashSet<>();
        /** Where each player stands. */
        final Map<UUID, Location> where = new HashMap<>();

        @Override
        public void collidable(UUID id, boolean on) {
            if (on) {
                notCollidable.remove(id);
            } else {
                notCollidable.add(id);
            }
        }

        @Override
        public Location at(UUID id) {
            return where.get(id);
        }

        /** Who is in the Clubhouse now. */
        final Set<UUID> club = new HashSet<>();
        /** Who is on a time-trial run now. */
        final Set<UUID> driving = new HashSet<>();
        boolean fromClubOk = true;
        final List<String> fromClub = new ArrayList<>();

        @Override
        public boolean inClub(UUID id) {
            return club.contains(id);
        }

        @Override
        public boolean fromClub(Player rider, String courseId) {
            fromClub.add(rider.getName() + " " + courseId);
            if (!fromClubOk) {
                return false;
            }
            club.remove(rider.getUniqueId());
            riding.add(rider.getUniqueId());
            return true;
        }

        @Override
        public boolean driving(UUID id) {
            return driving.contains(id);
        }

        @Override
        public void tell(Player p, String line) {
            told.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(line);
        }

        @Override
        public long now() {
            return now;
        }
    }

    private Port port;
    private Riders riders;
    private Fake driver;
    private Fake kid;

    @BeforeEach
    void setUp() {
        port = new Port();
        riders = new Riders(port);
        driver = new Fake("Driver");
        kid = new Fake("Kid");
        port.add(driver);
        port.add(kid);
    }

    @AfterEach
    void tearDown() {
        WorldEntities.passenger(driver.id, null);
    }

    /** Driver in a fresh boat at {@code x}: the seat path Time Trials runs on every spawn and re-seat. */
    private Entity seat(double x) {
        Entity b = boat(x);
        port.seats.put(b, new ArrayList<>(List.of(driver.id)));
        driver.vehicle = b;
        riders.seated(driver.player, b, "loop");
        return b;
    }

    /** Kid rides with Driver, and is in the back of Driver's first boat. */
    private Entity riding() {
        riders.paired(driver.player, kid.player, "loop", null);
        Entity first = seat(10);
        port.arrive(kid);
        return first;
    }

    @Test
    void theRiderSitsBehindTheDriverOnEverySeat() {
        riders.paired(driver.player, kid.player, "loop", null);
        assertTrue(port.last(kid).contains("riding with Driver"), port.told.toString());
        assertTrue(port.entered.isEmpty(), "nothing happens until Driver is in a boat");
        Entity start = seat(10);
        assertEquals(List.of("Kid@10 loop"), port.entered, "the rider's own session starts at Driver's boat");
        port.arrive(kid);
        assertEquals("Driver", port.kits.get(kid.id), "the rider's kit: Leave game, and who they ride with");
        assertTrue(port.noPush.contains(kid.id), "on the no-push team");
        assertTrue(port.last(kid).contains("Riding with Driver - hold on tight!"), port.told.toString());
        assertEquals(List.of(driver.id, kid.id), port.seats.get(start), "seated behind Driver (second)");
        Entity grid = seat(20); // the party's grid, a re-grid, a checkpoint re-seat: each a new boat
        assertEquals(List.of(driver.id, kid.id), port.seats.get(grid), "behind Driver again");
        assertTrue(port.teleports.contains("Kid@20"), "their own session's teleport to the boat, checked");
        Entity regrid = seat(30);
        assertEquals(List.of(driver.id, kid.id), port.seats.get(regrid), "and again");
        assertEquals(1, port.entered.size(), "one session for the whole ride");
        assertSame(kid.player, riders.riderIn(regrid, driver.id), "the rider in Driver's boat now");
        assertNull(riders.riderIn(grid, driver.id), "not in a boat they left");
    }

    @Test
    void onlyTheDriverAndTheirRiderMayGetIntoTheDriversBoat() {
        UUID stranger = UUID.randomUUID();
        assertTrue(WorldEntities.mayEnter(driver.id, driver.id), "the owner");
        assertFalse(WorldEntities.mayEnter(driver.id, kid.id), "no rider yet: nobody else");
        riders.paired(driver.player, kid.player, "loop", null);
        assertTrue(WorldEntities.mayEnter(driver.id, kid.id), "the rider may");
        assertFalse(WorldEntities.mayEnter(driver.id, stranger), "anyone else may not");
        riders.sessionEnded(kid.id);
        assertFalse(WorldEntities.mayEnter(driver.id, kid.id), "once off the ride, not any more");
    }

    @Test
    void aRiderOutOfTheBoatIsPutBackEachSecond() {
        Entity b = riding();
        port.seats.get(b).remove(kid.id); // knocked out somehow
        riders.second();
        assertTrue(port.aboard(b, kid.player), "back in the back seat");
    }

    @Test
    void theRidersLeaveEndsOnlyTheirRide() {
        riding();
        riders.sessionEnded(kid.id); // their Leave game (or a quit): the framework ended THEIR session
        assertTrue(port.left.isEmpty(), "nobody else is sent anywhere: Driver carries on");
        assertTrue(port.last(driver).contains("Kid hopped out - you carry on"), port.told.toString());
        assertFalse(port.noPush.contains(kid.id), "off the no-push team");
        assertNull(riders.ofDriver(driver.id), "the ride is over");
        seat(40);
        assertEquals(1, port.entered.size(), "Driver's next seat brings nobody");
    }

    @Test
    void theDriversFinishLeaveOrQuitTakesTheRiderHome() {
        riding();
        riders.sessionEnded(driver.id); // a solo finish (home), Leave game, a DNF sent home
        assertEquals(List.of("Kid FINISH"), port.left, "the rider goes home too, their things back");
        assertTrue(port.last(kid).contains("Your ride is over"), port.told.toString());
        assertFalse(riders.isRider(kid.id));

        port.left.clear();
        riding();
        port.online.remove(driver.id); // Driver disconnects
        riders.second();
        assertEquals(List.of("Kid FINISH"), port.left, "a driver's quit takes the rider home");
    }

    @Test
    void theDriverGoingToTheClubhouseTakesTheRiderThere() {
        riding();
        riders.toClub(driver.id, ClubVisits.Kind.PARTY);
        assertEquals(List.of("Kid PARTY"), port.takenIn, "the rider's session handed to the Clubhouse with Driver");
        assertTrue(port.left.isEmpty(), "not sent home");
        assertTrue(riders.isRider(kid.id), "still Driver's rider there, for their next race (#11)");
        assertTrue(riders.ofDriver(driver.id).inClub, "waiting in the Clubhouse");

        riders.stop();
        port.clubOk = false; // the Clubhouse can't take them: home
        riding();
        riders.toClub(driver.id, ClubVisits.Kind.NIGHT);
        assertEquals(List.of("Kid FINISH"), port.left, "the Clubhouse couldn't: home, their things back");
        assertFalse(riders.isRider(kid.id), "and the ride is over");
    }

    @Test
    void aRideGoesOnFromTheClubhouseAtTheDriversNextRace() {
        riding();
        port.club.add(driver.id);
        riders.toClub(driver.id, ClubVisits.Kind.NIGHT); // race 1 over, no stand: both wait in the Clubhouse
        assertFalse(port.noPush.contains(kid.id), "off the ride's team: the Clubhouse has its own");
        riders.second();
        assertTrue(riders.isRider(kid.id), "the break: still riding with Driver");
        port.club.remove(driver.id);
        port.driving.add(driver.id); // race 2: Driver is seated from the Clubhouse
        Entity race2 = seat(50);
        assertEquals(List.of("Kid loop"), port.fromClub, "the rider's session handed back to the race, in place");
        assertEquals(List.of(driver.id, kid.id), port.seats.get(race2), "behind Driver again for race 2 (#11)");
        assertTrue(port.noPush.contains(kid.id) && port.notCollidable.contains(kid.id), "on the ride's team again");
        assertEquals(1, port.entered.size(), "the same session all night: their things saved once");
        assertFalse(riders.ofDriver(driver.id).inClub, "riding again");
    }

    @Test
    void aRideWaitingInTheClubhouseEndsWhenEitherLeavesOrNoRaceComes() {
        riding();
        port.club.add(driver.id);
        riders.toClub(driver.id, ClubVisits.Kind.PARTY);
        port.club.remove(kid.id); // Kid's Leave game in the Clubhouse
        riders.second();
        assertFalse(riders.isRider(kid.id), "Kid left the Clubhouse: the ride is over");

        riding();
        riders.toClub(driver.id, ClubVisits.Kind.PARTY);
        port.club.remove(driver.id); // Driver went home (not in the Clubhouse, not racing)
        riders.second();
        assertFalse(riders.isRider(kid.id), "Driver went home: the ride is over");
        assertTrue(port.last(kid).contains("is over"), port.told.toString());
        assertTrue(port.club.contains(kid.id), "Kid stays in the Clubhouse to hang out");

        riding();
        port.club.add(driver.id);
        riders.toClub(driver.id, ClubVisits.Kind.PARTY);
        port.now += Riders.CLUB_LAPSE_MS;
        riders.second();
        assertFalse(riders.isRider(kid.id), "no next race for five minutes: the ride lapses");
    }

    @Test
    void aRideThatEndedOnTheWayInSendsTheRiderHomeWhenTheyArrive() {
        riders.paired(driver.player, kid.player, "loop", null);
        seat(10);
        riders.sessionEnded(driver.id); // Driver left before Kid got there
        assertTrue(port.left.isEmpty(), "Kid's session hasn't started yet");
        port.arrive(kid);
        assertEquals(List.of("Kid FINISH"), port.left, "on arrival: straight home, never left in a session");
        assertFalse(port.kits.containsKey(kid.id), "no rider kit");
    }

    @Test
    void aRiderWhoCantHopInTriesAgainAtTheNextStartAndAnUnusedRideLapses() {
        riders.paired(driver.player, kid.player, "loop", null);
        port.enterOk = false; // not standing still, say: they were told why
        seat(10);
        assertTrue(port.last(driver).contains("get in at your next start"), port.told.toString());
        port.enterOk = true;
        seat(20);
        assertEquals(2, port.entered.size(), "tried again at the next seat");

        Riders fresh = new Riders(port);
        Fake helper = new Fake("Helper");
        port.add(helper);
        fresh.paired(helper.player, kid.player, "loop", null);
        port.now += Riders.LAPSE_MS;
        fresh.second();
        assertNull(fresh.ofDriver(helper.id), "a ride nobody started lapses");
        assertTrue(port.last(kid).contains("didn't start"), port.told.toString());
        WorldEntities.passenger(helper.id, null);
    }

    @Test
    void aRunWithARiderCountsUnlessTheServerSaysRidesAreJustForFun() {
        riders.paired(driver.player, kid.player, "loop", null);
        assertFalse(riders.funOnly(driver.id, false), "nobody aboard yet: a normal run");
        seat(10);
        port.arrive(kid);
        assertFalse(riders.funOnly(driver.id, true), "rider_runs_count true: a passenger changes nothing, it counts");
        assertTrue(riders.funOnly(driver.id, false), "rider_runs_count false: just for fun");
        assertFalse(riders.funOnly(kid.id, false), "the rider has no run at all");
    }

    @Test
    void theRiderStandsByTheDriverAndTimeTrialsStoppingEndsEveryRide() {
        riding();
        riders.follow(driver.player, new Location(null, 5, 70, 5)); // Driver onto the stand
        assertTrue(port.teleports.contains("Kid@5"), "the rider stands by Driver");
        riders.stop(); // Time Trials stopping: the framework ends the sessions
        assertTrue(riders.rides().isEmpty(), "every ride forgotten");
        assertFalse(WorldEntities.mayEnter(driver.id, kid.id), "and the back seat closed");
    }
    @Test
    void theRiderIsOffTheNoPushTeamAndCollidableAgainOnEveryEndOfTheRide() {
        riding();
        assertTrue(port.noPush.contains(kid.id), "on the no-push team for the ride");
        assertTrue(port.notCollidable.contains(kid.id),
                "and not collidable (#4; the stand rule keeps them off the track, #15)");
        riders.sessionEnded(driver.id); // Driver finishes: the ride is over and the rider is sent home
        riders.sessionEnded(kid.id); // then the framework ends the rider's own session
        assertFalse(port.noPush.contains(kid.id), "off the no-push team after the driver's finish (#2)");
        assertFalse(port.notCollidable.contains(kid.id), "collidable again");
        assertFalse(riders.shielded(kid.id), "forgotten");

        riding();
        riders.stop(); // Time Trials stopping
        assertFalse(port.noPush.contains(kid.id), "off the no-push team when Time Trials stops (#2)");
        assertFalse(port.notCollidable.contains(kid.id), "collidable again");

        riding();
        riders.toClub(driver.id, ClubVisits.Kind.PARTY);
        assertFalse(port.noPush.contains(kid.id) || port.notCollidable.contains(kid.id),
                "handed to the Clubhouse (which puts them on its own team)");

        riding();
        port.online.remove(driver.id); // Driver disconnects
        riders.second();
        assertFalse(port.noPush.contains(kid.id) || port.notCollidable.contains(kid.id), "the driver's quit");

        port.online.put(driver.id, driver.player);
        riding();
        riders.sessionEnded(kid.id); // their own Leave
        assertFalse(port.noPush.contains(kid.id) || port.notCollidable.contains(kid.id), "their own Leave");
    }

    @Test
    void aRiderOffTheBoatIsKeptByTheirParkedDriverEvenWhenATeleportFailed() {
        riding();
        driver.vehicle = null; // Driver is parked on the stand: their boat is gone
        Location stand = new Location(null, 5, 70, 5);
        port.where.put(driver.id, stand);
        port.teleportOk = false;
        riders.follow(driver.player, stand); // onto the stand with them... but the teleport failed
        port.where.put(kid.id, new Location(null, 60, 65, 0)); // still on the track
        port.teleportOk = true;
        riders.second();
        assertEquals(5, port.where.get(kid.id).getX(), 1e-9, "brought to Driver on the stand, off the track");
        int before = port.teleports.size();
        port.where.put(kid.id, new Location(null, 7, 70, 6)); // a few steps away: fine
        riders.second();
        assertEquals(before, port.teleports.size(), "within " + Riders.FOLLOW_RADIUS + " blocks: left alone");
        port.where.put(kid.id, new Location(null, 30, 70, 5)); // wandered off the stand
        riders.second();
        assertEquals(5, port.where.get(kid.id).getX(), 1e-9, "brought back by Driver");
    }

    /** Race mode's stand rule for a stand at (5, 70, 5) with Race Night's {@code stand_radius}. */
    private static boolean offStand(Location at, double radius) {
        return Math.hypot(at.getX() - 5, at.getZ() - 5) > radius || Math.abs(at.getY() - 70) > radius + 2;
    }

    @Test
    void aRiderIsHeldToTheStandByTheRacersOwnRuleNotJustNearTheDriver() {
        riding();
        driver.vehicle = null; // Driver finished and is parked on the stand
        Location stand = new Location(null, 5, 70, 5);
        port.where.put(driver.id, new Location(null, 9, 70, 5)); // at the stand's track-side edge: 4 blocks out
        riders.follow(driver.player, stand);
        port.where.put(kid.id, new Location(null, 12.5, 70, 5)); // 3.5 from Driver, 7.5 from the stand: the racing line
        riders.second();
        assertEquals(12.5, port.where.get(kid.id).getX(), 1e-9, "within 4 of Driver, so the once-a-second keep-near"
                + " alone leaves Kid there, where the next heat's boats pass");
        riders.onStand(driver.id, at -> offStand(at, 4), stand); // race mode's stand check, with the race's radius
        assertEquals(5, port.where.get(kid.id).getX(), 1e-9, "the racers' own stand rule puts Kid back on the stand"
                + " (the final gate's #15)");
        assertTrue(port.last(kid).contains("stand"), "and Kid reads why: " + port.last(kid));
        int before = port.teleports.size();
        port.where.put(kid.id, new Location(null, 7, 70, 7)); // a few steps across the stand: fine
        riders.onStand(driver.id, at -> offStand(at, 4), stand);
        assertEquals(before, port.teleports.size(), "on the stand: left alone");
        port.where.put(kid.id, new Location(null, 5, 63, 5)); // jumped down off it, 7 below
        riders.onStand(driver.id, at -> offStand(at, 4), stand);
        assertEquals(70, port.where.get(kid.id).getY(), 1e-9, "down off the stand is off it, as for a racer");
    }

    @Test
    void raceModeHandsTheRiderTheParkedRacersOwnStandAndTheRacesRadius() {
        riding();
        driver.vehicle = null; // Driver finished and is parked on the stand
        RaceRun rr = new RaceRun(new TrialFakes.Link(), LapsTest.loop(6, 2), new Course.Spot(1, 65, -4, 0, 0),
                new Point(5, 70, 5), false);
        rr.parked();
        port.where.put(kid.id, new Location(null, 12.5, 70, 5)); // 7.5 blocks from the stand
        RaceMode.holdRider(riders, driver.id, rr, 8, null); // a Race Night with stand_radius 8
        assertEquals(12.5, port.where.get(kid.id).getX(), 1e-9,
                "inside the race's own 8-block stand: where a racer may stand, so may Kid");
        RaceMode.holdRider(riders, driver.id, rr, 4, null); // the race's radius, passed through: 4 this time
        assertEquals(5, port.where.get(kid.id).getX(), 1e-9, "off a 4-block stand: back onto the race's stand"
                + " (the final gate's #15, as race mode's stand check wires it)");
        assertEquals(70, port.where.get(kid.id).getY(), 1e-9, "at the stand's own height");

        int before = port.teleports.size();
        port.where.put(kid.id, new Location(null, 40, 70, 5));
        RaceMode.holdRider(riders, driver.id, new RaceRun(new TrialFakes.Link(), LapsTest.loop(6, 2),
                new Course.Spot(1, 65, -4, 0, 0), null, false), 4, null);
        assertEquals(before, port.teleports.size(), "a race with no stand holds nobody to one");
        rr.state = RaceRun.State.RACING;
        RaceMode.holdRider(riders, driver.id, rr, 4, null);
        assertEquals(before, port.teleports.size(), "nor a race whose racer isn't parked: the racers' rule, exactly");
    }

    @Test
    void theStandRuleLeavesAloneARiderWhoIsNotOnTheRide() {
        riders.paired(driver.player, kid.player, "loop", null); // paired, but never got in: no session yet
        port.where.put(kid.id, new Location(null, 60, 65, 0));
        riders.onStand(driver.id, at -> true, new Location(null, 5, 70, 5));
        assertTrue(port.teleports.isEmpty(), "a rider not in their ride's session is never moved by it");
        riders.onStand(UUID.randomUUID(), at -> true, new Location(null, 5, 70, 5));
        assertTrue(port.teleports.isEmpty(), "nor anyone for a driver with no rider");
    }
}
