package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.clubhouse.ClubBoard;
import com.dierks.homecraft.games.clubhouse.ClubDoor;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Race mode's side of the Clubhouse (CLUBHOUSE-SPEC §2, §3; WP-CH): a racer seated from the
 * Clubhouse, and a racer taken back to it. {@link RaceMode} gains one line at each place (its
 * {@code race}, its {@code finish} and its trip home); the rules are here.
 *
 * <p><b>Seated from the Clubhouse</b> ({@link #seat}): the racer's Clubhouse session is handed to
 * Time Trials in place (nothing restored, nothing saved again), then they are moved to their grid
 * spot (or the course's start for the shared warm-up) by the run's own teleport, the same way Race
 * Night re-grids a racer on the stand. A boat is seated there by the run's re-seat. A teleport that
 * fails hands the session straight back: they are still in the Clubhouse, and the race hears they
 * couldn't be seated. From then on the run is exactly a run seated from home.
 *
 * <p><b>Back to the Clubhouse</b> ({@link #toClub}): instead of going home at the line (a party race
 * with no stand), at the end of a party race, or at the end of Race Night, the race run ends as it
 * would (the boat goes, collisions and the no-push team back), and the session is handed to the
 * Clubhouse, which moves them there and checks the move. If the Clubhouse can't take them, they go
 * home exactly as before.
 */
public final class ClubRaces {

    private ClubRaces() {
    }

    /**
     * {@code TimeTrials.race} for a racer in the Clubhouse: {@code null} when they are on their way
     * to the grid (or the warm-up), else why not (they stay in the Clubhouse).
     */
    static Refusal seat(TimeTrials trials, RaceMode mode, ClubDoor club, Player p, Course base, Course raced,
                        Course.Spot grid, Location stand, RaceLink link, World world) {
        if (!world.getName().equalsIgnoreCase(club.world())) {
            return Refusal.of(com.dierks.homecraft.games.clubhouse.ClubhouseText.OTHER_WORLD);
        }
        Refusal closed = trials.sessions().entryRefusal(trials);
        if (closed != null) {
            return closed;
        }
        long now = Bukkit.getCurrentTick();
        boolean warm = mode.call(link, () -> link.warmupUntil() > now, false);
        Course.Spot spot = grid != null ? grid : raced.start();
        Course.Spot at = RaceMode.entrySpot(base, spot, warm);
        Point standAt = stand != null && stand.getWorld() != null && stand.getWorld().equals(world)
                ? TimeTrials.point(stand) : null;
        if (!club.handOut(p, trials, base.id())) {
            return Refusal.of("Couldn't take you from the Clubhouse right now.");
        }
        RaceRun rr = new RaceRun(link, base, spot, standAt, warm);
        long until = mode.call(link, link::warmupUntil, 0L);
        boolean warmNow = rr.state == RaceRun.State.WARMUP && until > now;
        TrialRun run = new TrialRun(p.getUniqueId(), warmNow ? rr.base : raced, false, 0);
        run.race = rr;
        trials.replaceRun(run);
        Location to = new Location(world, at.x(), at.y(), at.z(), at.yaw(), at.pitch());
        if (!trials.move(p, run, to)) {
            trials.end(p);
            club.handBack(p, kind(link));
            return Refusal.of(com.dierks.homecraft.games.clubhouse.ClubhouseText.NO_GRID);
        }
        trials.giveKit(p, run.course.kind());
        p.setFallDistance(0f);
        mode.inRace(p, rr);
        if (warmNow && run.beginWarmup(until)) {
            run.progress = new Progress(run.course, TimeTrials.position(p, run), System.nanoTime());
            run.phase = TrialRun.Phase.RUNNING;
            p.getInventory().setItem(Warmup.KIT_SLOT, KitItems.item(trials, Warmup.READY, Material.LIME_DYE,
                    Warmup.READY_NAME, "&7Tap when you're set.", "&7The race starts when the warm-up",
                    "&7ends, or everyone is ready."));
            p.sendMessage(Text.of("&b" + rr.base.name() + " &7- race warm-up"));
            p.sendMessage(Text.of(Warmup.started((int) Warmup.secondsLeft(now, run.warmupEnds))));
            p.sendMessage(Text.of(Warmup.HOW_TO_READY));
            return null;
        }
        rr.state = RaceRun.State.GRID;
        p.sendMessage(Text.of("&b" + rr.base.name() + " &7- on the grid. Wait for Go!"));
        return null;
    }

    /**
     * The racer's race run is over and the race sends them to the Clubhouse: the run ends as a trip
     * home would end it, then the Clubhouse takes their session. False when there is no Clubhouse to
     * take them or it couldn't (the caller sends them home as it always did).
     */
    static boolean toClub(TimeTrials trials, RaceMode mode, ClubDoor club, Player p, TrialRun run, String line) {
        if (club == null || p == null || !p.isOnline()) {
            return false;
        }
        RaceRun rr = run.race;
        trials.removeBoat(run, p);
        mode.restore(p.getUniqueId(), p, rr);
        rr.ended = true;
        rr.due = RaceRun.Due.NONE;
        trials.end(p);
        // a party racer parked here at their finish reads where they are (the checklist pass: BACK_PARTY was never
        // sent); Race Night's calls pass their own line
        String text = line != null && !line.isBlank() ? line
                : kind(rr.link) == ClubVisits.Kind.PARTY ? com.dierks.homecraft.games.clubhouse.ClubhouseText.BACK_PARTY
                : null;
        if (!club.takeIn(p, kind(rr.link), text)) {
            return false;
        }
        trials.riders().toClub(p.getUniqueId(), kind(rr.link)); // a rider comes along
        return true;
    }

    /**
     * The party races going on now, read-only, for the Clubhouse's live board and its watchers: the
     * warm-up, the grid and the race itself (never one that is over).
     */
    public static List<com.dierks.homecraft.games.clubhouse.LiveRace> live(com.dierks.homecraft.games.GamesService games) {
        List<com.dierks.homecraft.games.clubhouse.LiveRace> out = new ArrayList<>();
        if (games == null || !(games.game(TimeTrials.SPEC.id()) instanceof TimeTrials t) || !games.enabled(t)) {
            return out;
        }
        for (PartyRace r : t.party().running()) {
            if (r.state() == PartyRace.State.DONE || r.state() == PartyRace.State.SEATING) {
                continue;
            }
            Course base = r.base();
            com.dierks.homecraft.games.gen.api.Box half = base.gen() == null ? null : games.generated().half(base.gen());
            List<String> rows = new ArrayList<>();
            List<String> names = new ArrayList<>();
            int targets = Math.max(1, base.targets().size());
            long winner = -1;
            for (PartyRace.Live l : r.live()) {
                names.add(l.name());
                if (l.finished()) {
                    winner = winner < 0 ? l.ms() : winner;
                    rows.add(ClubBoard.timeRow(l.place(), l.name(), l.ms(), winner));
                } else if (l.out()) {
                    rows.add(ClubBoard.noTimeRow(l.name(), "out"));
                } else {
                    String where = r.laps() > 1 ? "lap " + l.lap() + "/" + r.laps()
                            : "checkpoint " + Math.min(l.reached(), targets) + "/" + targets;
                    rows.add(ClubBoard.liveRow(l.place(), l.name(), where, null));
                }
            }
            out.add(new com.dierks.homecraft.games.clubhouse.LiveRace("party:" + r.lobbyId(), "&dParty race: &f"
                    + base.name(), base.world(), com.dierks.homecraft.games.clubhouse.WatchArea.forCourse(base, half),
                    new java.util.LinkedHashSet<>(r.racers()), rows,
                    com.dierks.homecraft.games.clubhouse.LiveRace.positions(names, 5), r.clubhouseAfter()));
        }
        return out;
    }

    /** Why the Clubhouse has them: a party racer, or a Race Night racer. */
    static ClubVisits.Kind kind(RaceLink link) {
        return link instanceof PartyRace ? ClubVisits.Kind.PARTY : ClubVisits.Kind.NIGHT;
    }

    /**
     * A party race's results on the board: finishers "1. Sam 1:02.4 (+0.0)" (the gap to the winner),
     * then everyone else's why ("still racing", "didn't count", "left").
     */
    static ClubBoard.Sheet partySheet(String courseName, List<PartyRace.Line> lines, long version) {
        List<String> rows = new ArrayList<>();
        long winner = -1;
        for (PartyRace.Line l : lines) {
            if (l.result() == PartyRace.Result.FINISHED) {
                if (winner < 0) {
                    winner = l.ms();
                }
                rows.add(ClubBoard.timeRow(l.rank(), l.name(), l.ms(), winner));
            }
        }
        for (PartyRace.Line l : lines) {
            String why = switch (l.result()) {
                case STILL_RACING -> "still racing";
                case NOT_COUNTED -> "didn't count";
                case LEFT -> "left";
                default -> null;
            };
            if (why != null) {
                rows.add(ClubBoard.noTimeRow(l.name(), why));
            }
        }
        if (rows.isEmpty()) {
            rows.add("&7Nobody finished this one.");
        }
        return new ClubBoard.Sheet("&6&lParty race: &f" + courseName, rows, false, "party:" + version);
    }
}
