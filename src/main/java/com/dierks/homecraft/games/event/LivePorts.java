package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.PlayGate;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.gen.NewCoursesNudge;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceLink;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import java.util.logging.Level;

/**
 * {@link NightPorts} on the live server (EVENTS-DROPPER-SPEC §A.4.11, §A.6): the clock, who is
 * online and free, Time Trials' race mode, and the chat, titles and bossbars. One per night.
 *
 * <p><b>Race mode</b> is Time Trials' ({@code TimeTrials.race}, {@code regrid}, {@code park},
 * {@code endRace}, {@code reserve}/{@code release}), built by WP-R1: each call here goes straight to
 * its {@code RaceMode}, which never throws into the caller (a link that fails is logged and treated
 * as over). A refusal comes back as its plain line; Time Trials being closed is one too.
 *
 * <p>Everything runs on the main thread inside Race Night's guard; nothing here throws on purpose.
 */
final class LivePorts implements NightPorts {

    private final RaceNight game;
    private final String nightId;

    LivePorts(RaceNight game, String nightId) {
        this.game = game;
        this.nightId = nightId;
    }

    private GamesService games() {
        return game.games();
    }

    @Override
    public long now() {
        return games().clock().nowMillis();
    }

    @Override
    public long tick() {
        return Bukkit.getCurrentTick();
    }

    @Override
    public boolean online(UUID player) {
        return Bukkit.getPlayer(player) != null;
    }

    @Override
    public boolean free(UUID player) {
        Player p = Bukkit.getPlayer(player);
        if (p != null && ClubNight.waiting(games(), player)) {
            return true; // WP-CH: waiting in the Clubhouse: seated from there
        }
        return p != null && games().sessions().session(p) == null && games().sessions().home(p);
    }

    @Override
    public boolean restartHeld() {
        return games().restartHeld() != null;
    }

    @Override
    public String name(UUID player) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            return p.getName();
        }
        OfflinePlayer o = Bukkit.getOfflinePlayer(player);
        return o.getName();
    }

    // ---- race mode ------------------------------------------------------------------------------

    @Override
    public String seat(UUID racer, Course base, Course raced, Course.Spot grid, Point stand, RaceLink link) {
        Player p = Bukkit.getPlayer(racer);
        TimeTrials t = game.trials();
        if (p == null) {
            return "offline";
        }
        if (t == null) {
            return "Time Trials is closed.";
        }
        World w = Bukkit.getWorld(base.world());
        if (w == null) {
            return "The track's world isn't loaded.";
        }
        Location at = stand == null ? null : new Location(w, stand.x(), stand.y(), stand.z());
        Refusal r = t.race(p, base, raced, grid, at, link);
        return r == null ? null : r.message();
    }

    @Override
    public void regrid(UUID racer, Course raced, Course.Spot grid) {
        Player p = Bukkit.getPlayer(racer);
        TimeTrials t = game.trials();
        if (p == null || t == null) {
            return;
        }
        t.regrid(p, raced, grid);
    }

    @Override
    public void park(UUID racer) {
        Player p = Bukkit.getPlayer(racer);
        TimeTrials t = game.trials();
        if (p == null || t == null) {
            return;
        }
        t.park(p);
    }

    @Override
    public void home(UUID racer, EndReason why, String line) {
        TimeTrials t = game.trials();
        if (t != null) {
            t.endRace(racer, why, line); // home on its next tick, reading the line (or on arrival)
            return;
        }
        Player p = Bukkit.getPlayer(racer); // Time Trials closed: end the session the plain way
        if (p != null) {
            Session s = games().sessions().session(p);
            if (s != null && TimeTrials.SPEC.id().equals(s.gameId())) {
                games().sessions().leave(p, why);
            }
            if (line != null && !line.isBlank()) {
                p.sendMessage(Text.of(line));
            }
        }
    }

    // ---- WP-CH: the Clubhouse ----------------------------------------------------------------------

    @Override
    public boolean clubhouse(String trackWorld) {
        return ClubNight.takes(games(), trackWorld);
    }

    @Override
    public void toClubhouse(UUID racer, String line) {
        TimeTrials t = game.trials();
        if (t != null) {
            t.endRaceToClubhouse(racer, EndReason.FINISH, line);
            return;
        }
        home(racer, EndReason.FINISH, line);
    }

    @Override
    public void clubhouseResults(NightRunner night) {
        ClubNight.results(games(), night);
    }

    @Override
    public void offerClubhouse(UUID racer) {
        ClubNight.offer(games(), Bukkit.getPlayer(racer));
    }

    // ---- end WP-CH ------------------------------------------------------------------------------

    @Override
    public boolean reserve(String courseId, Object holder, String line) {
        TimeTrials t = game.trials();
        return t != null && t.reserve(courseId, holder, line);
    }

    @Override
    public void release(String courseId, Object holder) {
        TimeTrials t = game.trials();
        if (t != null) {
            t.release(courseId, holder);
        }
    }

    @Override
    public void endSoloRuns(String courseId, Collection<UUID> racers, String line) {
        for (Player p : soloRiders(courseId, racers)) {
            p.sendMessage(Text.of(line));
            games().sessions().leave(p, EndReason.ADMIN);
        }
    }

    @Override
    public void warnSoloRuns(String courseId, Collection<UUID> racers, String line) {
        for (Player p : soloRiders(courseId, racers)) {
            p.sendMessage(Text.of(line));
        }
    }

    /** Everyone on a solo Time Trials run on {@code courseId} who isn't one of tonight's racers. */
    private java.util.List<Player> soloRiders(String courseId, Collection<UUID> racers) {
        java.util.List<Player> out = new ArrayList<>();
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (racers.contains(p.getUniqueId())) {
                continue;
            }
            Session s = games().sessions().session(p);
            if (s != null && TimeTrials.SPEC.id().equals(s.gameId()) && courseId.equalsIgnoreCase(s.ref())) {
                out.add(p);
            }
        }
        return out;
    }

    // ---- telling --------------------------------------------------------------------------------

    @Override
    public void tell(UUID player, String line, boolean queueIfOffline) {
        if (line == null || line.isBlank()) {
            return;
        }
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            p.sendMessage(Text.of(line));
        } else if (queueIfOffline) {
            games().notice(player, line, true);
        }
    }

    @Override
    public void title(UUID player, String big, String small) {
        Player p = Bukkit.getPlayer(player);
        if (p == null) {
            return;
        }
        try {
            p.showTitle(Title.title(Text.of(big), Text.of(small), Title.Times.times(Duration.ofMillis(100),
                    Duration.ofMillis(2000), Duration.ofMillis(300))));
        } catch (RuntimeException | LinkageError ignored) {
            // a title is decoration
        }
    }

    @Override
    public void bar(UUID player, String line, float progress, boolean lastLap) {
        game.bars().show(player, line, progress, lastLap ? RaceBars.Tone.LAST_LAP : RaceBars.Tone.RACE);
    }

    @Override
    public void watchers(String line) {
        for (UUID id : game.watchers().ids()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(Text.of(line));
            }
        }
    }

    @Override
    public void announce(Announcer.Line line, String text, Collection<UUID> racers) {
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            UUID id = p.getUniqueId();
            Announcer.Who who = who(games(), p, newsOn(id), racers.contains(id));
            if (!game.announcer().tell(nightId, id, line, who)) {
                continue;
            }
            Component c = Text.of(text);
            if (line != Announcer.Line.RESULTS && !Bedrock.is(p)) {
                c = c.append(Text.of(" &a[Join]").clickEvent(ClickEvent.runCommand(EventCopy.COMMAND)));
            }
            p.sendMessage(c);
        }
    }

    /**
     * Who {@code p} is to Race Night's news: the one place a chat line and the join bar ask (the final
     * gate's #1: a player without {@code hcm.games.play} is never nudged, as by every other games nudge).
     */
    static Announcer.Who who(GamesService games, Player p, boolean newsOn, boolean racer) {
        return new Announcer.Who(newsOn, p.hasPermission(PlayGate.PERMISSION_PLAY),
                games.gate().worldAllowed(p.getWorld()), games.sessions().session(p) != null, racer);
    }

    /** The player's news toggle ({@code /hcm play news off} silences Race Night too). */
    boolean newsOn(UUID player) {
        try {
            return !"off".equalsIgnoreCase(games().dao().pref(player, NewCoursesNudge.PREF_NEWS));
        } catch (SQLException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public void changed() {
        game.changed();
    }

    @Override
    public long points(UUID player, String board) {
        Long best = games().scores().best(player, RaceNight.SPEC.id(), board);
        return best == null ? 0L : best;
    }

    @Override
    public void progress(UUID player, boolean won) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            games().tellProgress(g -> g.raceNightFinished(p, won));
        }
    }

    @Override
    public void log(String line, boolean warn) {
        game.log(warn ? Level.WARNING : Level.INFO, line, null);
    }
}
