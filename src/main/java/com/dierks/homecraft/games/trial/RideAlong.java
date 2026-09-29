package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Invite;
import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.clubhouse.ClubDoor;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.event.NightRunner;
import com.dierks.homecraft.games.event.RaceNight;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Taking a rider (CLUBHOUSE-SPEC §12; WP-CH): "Take a rider (back seat)" on a boat course's screen,
 * the party screen and the Race Night screen, and {@code /hcm play rider <player>}, through the
 * Invites system (key {@value Riders#INVITE_KEY}: [Accept] on Java, {@code /hcm play accept} on
 * Bedrock, the invite switches and the cooldown). Also the live {@link Riders.Port}.
 *
 * <p><b>Who can ride.</b> Anyone online who isn't racing, watching, riding, in a party race's lobby,
 * on tonight's Race Night list or in any other game, and not during the restart hold (refused with a
 * plain reason). Ride along is part of the Clubhouse: while it isn't open ({@code games.clubhouse.enabled}
 * off, or its room not built and checked) there is no "Take a rider" anywhere. With {@code games.trials.rider_runs_count: false} the driver is told
 * first that a run with a rider is just for fun, and Race Night takes no riders.
 */
public final class RideAlong {

    /** Where a ride is for. */
    public enum Purpose { SOLO, PARTY, NIGHT }

    /** "Take a rider (back seat)": the key fact in the NAME. */
    public static final String BUTTON = "&bTake a rider &7(back seat) - invite a friend to ride along";
    static final String NO_NIGHT = "Race Night takes no riders on this server.";
    static final String FUN_WARNING = "&eRides with a rider are just for fun on this server &7- no board, record,"
            + " rewards or Cup time for that run.";

    private RideAlong() {
    }

    private static TimeTrials trials(GamesService games) {
        return games != null && games.game(TimeTrials.SPEC.id()) instanceof TimeTrials t && games.enabled(t) ? t : null;
    }

    /** Whether "Take a rider" shows for a course of {@code kind}: a boat, with the Clubhouse open. */
    public static boolean offered(GamesService games, TrialKind kind) {
        return kind == TrialKind.BOAT && trials(games) != null && clubhouseOn(games);
    }

    /** Whether "Take a rider" shows on the Race Night screen: a boat track, and Race Night takes riders. */
    public static boolean nightOffered(GamesService games, Course track) {
        TimeTrials t = trials(games);
        return track != null && offered(games, track.kind()) && t != null && t.settings().riderRunsCount();
    }

    /**
     * Ride along is part of the Clubhouse: it is on only while the Clubhouse is OPEN, switched on with
     * its room built and checked (Time Trials' door to it, which a test can fake), as every other
     * Clubhouse button (the review's #9: the switch alone showed "Take a rider" with no room).
     */
    static boolean clubhouseOn(GamesService games) {
        try {
            TimeTrials t = trials(games);
            return t != null && t.raceMode().door() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The picker, then the invite, for {@code purpose} on {@code courseId}. */
    public static void take(GamesService games, Player driver, String courseId, Purpose purpose, Runnable back) {
        TimeTrials t = trials(games);
        if (t == null) {
            return;
        }
        games.guard(t, () -> {
            String why = driverRefusal(games, t, driver, courseId, purpose);
            if (why != null) {
                games.tell(driver, Refusal.of(why));
                return;
            }
            if (!t.settings().riderRunsCount()) {
                driver.sendMessage(Text.of(FUN_WARNING));
            }
            games.screens().pickPlayer(driver, t, other -> riderRefusal(games, t, driver, other, purpose) == null,
                    chosen -> invite(games, t, driver, chosen, courseId, purpose), back);
        });
    }

    /**
     * {@code /hcm play rider <player>}: the ride for where the driver is: their party race, tonight's
     * Race Night, or the boat run they are on.
     */
    public static void command(GamesService games, Player driver, String riderName) {
        TimeTrials t = trials(games);
        if (t == null || !clubhouseOn(games)) {
            games.tell(driver, Refusal.of("Riders aren't on right now."));
            return;
        }
        games.guard(t, () -> {
            Player rider = riderName == null ? null : Bukkit.getPlayerExact(riderName);
            if (rider == null) {
                games.tell(driver, Refusal.of(riderName == null ? "Who rides with you? /hcm play rider <player>"
                        : riderName + " isn't online."));
                return;
            }
            PartyLobby lobby = t.party().lobby(driver.getUniqueId());
            NightRunner night = night(games);
            TrialRun run = t.run(driver.getUniqueId());
            String courseId;
            Purpose purpose;
            if (lobby != null) {
                courseId = lobby.course();
                purpose = Purpose.PARTY;
            } else if (night != null && night.in(driver.getUniqueId())) {
                courseId = night.track().base().id();
                purpose = Purpose.NIGHT;
            } else if (run != null && run.race == null) {
                courseId = run.course.id();
                purpose = Purpose.SOLO;
            } else {
                games.tell(driver, Refusal.of("Open a boat course's screen and tap Take a rider."));
                return;
            }
            String why = driverRefusal(games, t, driver, courseId, purpose);
            if (why == null) {
                why = riderRefusal(games, t, driver, rider, purpose);
            }
            if (why != null) {
                games.tell(driver, Refusal.of(why));
                return;
            }
            if (!t.settings().riderRunsCount()) {
                driver.sendMessage(Text.of(FUN_WARNING));
            }
            invite(games, t, driver, rider, courseId, purpose);
        });
    }

    /** Why this driver can't take a rider here, or {@code null}. */
    static String driverRefusal(GamesService games, TimeTrials t, Player driver, String courseId, Purpose purpose) {
        if (!clubhouseOn(games)) {
            return "Riders aren't on right now.";
        }
        Course c = t.course(courseId);
        if (c == null || c.kind() != TrialKind.BOAT) {
            return "Only a boat has a back seat.";
        }
        if (purpose == Purpose.NIGHT && !t.settings().riderRunsCount()) {
            return NO_NIGHT;
        }
        if (t.riders().ofDriver(driver.getUniqueId()) != null) {
            return "You have a rider already.";
        }
        if (t.riders().isRider(driver.getUniqueId())) {
            return "You're riding with someone yourself.";
        }
        Refusal hold = games.restartRefusal();
        return hold == null ? null : hold.message();
    }

    /**
     * Why {@code rider} can't ride with {@code driver}, or {@code null}: offline, themself, already
     * riding, racing (in a party race's lobby, on tonight's list), in any game (watching too), or
     * during the restart hold.
     */
    static String riderRefusal(GamesService games, TimeTrials t, Player driver, Player rider, Purpose purpose) {
        if (rider == null || !rider.isOnline() || rider.getUniqueId().equals(driver.getUniqueId())) {
            return "That player can't ride with you.";
        }
        UUID id = rider.getUniqueId();
        if (t.riders().isRider(id) || t.riders().ofDriver(id) != null) {
            return rider.getName() + " is on a ride already.";
        }
        if (games.parties().of(id) != null) {
            return rider.getName() + " is in a party - a rider can't also be racing.";
        }
        NightRunner n = night(games);
        if (n != null && n.in(id)) {
            return rider.getName() + " is racing at Race Night.";
        }
        if (t.sessions().session(rider) != null || !t.sessions().home(rider)) {
            return rider.getName() + " is in a game right now.";
        }
        Refusal hold = games.restartRefusal();
        return hold == null ? null : hold.message();
    }

    private static void invite(GamesService games, TimeTrials t, Player driver, Player rider, String courseId,
                               Purpose purpose) {
        Course c = t.course(courseId);
        String summary = "a ride in the back of " + driver.getName() + "'s boat" + (c == null ? "" : " on " + c.name())
                + " - you ride, they drive";
        Invite sent = games.invites().send(driver, rider, Riders.INVITE_KEY, "Ride along", summary,
                Riders.INVITE_SECONDS, (invite, yes) -> games.guard(t, () -> answered(games, t, invite, yes, courseId,
                        purpose)));
        if (sent == null) {
            driver.sendMessage(Text.of("&cThat invite couldn't be sent right now. &7(One invite at a time.)"));
            try {
                Sounds.refused(driver);
            } catch (RuntimeException | LinkageError ignored) {
                // a sound is decoration
            }
            return;
        }
        driver.sendMessage(Text.of("&aRide invite sent to " + rider.getName() + ". &7Waiting for an answer..."));
    }

    private static void answered(GamesService games, TimeTrials t, Invite invite, boolean yes, String courseId,
                                 Purpose purpose) {
        Player driver = Bukkit.getPlayer(invite.from());
        Player rider = Bukkit.getPlayer(invite.to());
        if (!yes) {
            if (driver != null) {
                driver.sendMessage(Text.of("&7" + (rider != null ? rider.getName() : "Your friend") + " didn't hop in."));
            }
            return;
        }
        if (driver == null || rider == null) {
            return;
        }
        String why = driverRefusal(games, t, driver, courseId, purpose);
        if (why == null) {
            why = riderRefusal(games, t, driver, rider, purpose);
        }
        if (why != null) {
            games.tell(rider, Refusal.of(why));
            games.tell(driver, Refusal.of(why));
            return;
        }
        TrialRun run = t.run(driver.getUniqueId());
        Entity boat = run != null && run.boat != null && run.boat.isValid() && TimeTrials.seated(driver, run)
                ? run.boat : null;
        t.riders().paired(driver, rider, run == null ? courseId : run.course.id(), boat);
    }

    private static NightRunner night(GamesService games) {
        Game g = games.game(RaceNight.SPEC.id());
        NightRunner n = g instanceof RaceNight r && games.enabled(r) ? r.night() : null;
        return n == null || n.phase().over() ? null : n;
    }

    // ---- the live port ------------------------------------------------------------------------------

    /** {@link Riders.Port} on the server, for Time Trials. */
    static Riders.Port live(TimeTrials t) {
        return new Riders.Port() {
            @Override
            public Player online(UUID id) {
                try {
                    return id == null ? null : Bukkit.getPlayer(id);
                } catch (RuntimeException | LinkageError e) {
                    return null;
                }
            }

            @Override
            public boolean enter(Player rider, String courseId, Location at, Consumer<Player> ready) {
                return t.sessions().enter(rider, t, courseId, at, ready);
            }

            @Override
            public boolean riding(Player p) {
                Session s = t.sessions().session(p);
                return s != null && t.id().equals(s.gameId()) && s.phase() == Session.Phase.ACTIVE
                        && t.run(p.getUniqueId()) == null;
            }

            @Override
            public boolean teleport(Player p, Location at) {
                return t.sessions().teleport(p, at);
            }

            @Override
            public boolean board(Entity boat, Player rider) {
                return boat.isValid() && boat.addPassenger(rider);
            }

            @Override
            public boolean aboard(Entity boat, Player rider) {
                return boat != null && boat.isValid() && boat.getPassengers().contains(rider);
            }

            @Override
            public void leave(Player p, EndReason why) {
                t.sessions().leave(p, why);
            }

            @Override
            public boolean takeIn(Player rider, ClubVisits.Kind kind, String line) {
                ClubDoor club = t.raceMode().door();
                return club != null && club.takeIn(rider, kind, line);
            }

            @Override
            public void kit(Player rider, String driverName) {
                t.sessions().stripKit(rider); // the kit only: never their things (the Clubhouse review, #3)
                PlayerInventory inv = rider.getInventory();
                KitItems.put(inv, 4, KitItems.item(t, "ride", Material.OAK_BOAT, "&bRiding with " + driverName
                        + " &7- hold on tight!", "&7You're in the back seat.", "&7Your ride isn't timed or counted."));
                KitItems.put(inv, 8, KitItems.item(t, "leave", Material.OAK_DOOR, "&cLeave game &7- the ride goes on",
                        "&7Click twice to go home.", "&7Your things come back."));
                inv.setHeldItemSlot(4);
            }

            @Override
            public void passenger(UUID driver, UUID rider) {
                WorldEntities.passenger(driver, rider);
            }

            @Override
            public void noPush(UUID id, String name, boolean on) {
                NoPush np = t.games().noPush();
                if (np == null) {
                    return;
                }
                if (on) {
                    np.on(id, name);
                } else {
                    np.off(id, name);
                }
            }

            @Override
            public void collidable(UUID id, boolean on) {
                Player p = online(id);
                if (p != null) {
                    p.setCollidable(on);
                }
            }

            @Override
            public Location at(UUID id) {
                Player p = online(id);
                return p == null ? null : p.getLocation();
            }

            @Override
            public boolean inClub(UUID id) {
                return com.dierks.homecraft.games.clubhouse.Clubhouse.inside(t.games(), id);
            }

            @Override
            public boolean fromClub(Player rider, String courseId) {
                ClubDoor club = t.raceMode().door();
                return club != null && club.handOut(rider, t, courseId);
            }

            @Override
            public boolean driving(UUID id) {
                return t.onRun(id);
            }

            @Override
            public void tell(Player p, String line) {
                if (p != null && line != null) {
                    p.sendMessage(Text.of(line));
                }
            }

            @Override
            public long now() {
                return t.games().clock().nowMillis();
            }
        };
    }
}
