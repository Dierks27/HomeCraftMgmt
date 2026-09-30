package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.clubhouse.ClubBench;
import com.dierks.homecraft.games.clubhouse.ClubDoor;
import com.dierks.homecraft.games.world.SessionBench;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Golf together on the cross-feature journeys' bench: the real {@link GolfGroups} flow over a port that
 * plays {@link GolfRounds}' server part (the GolfGroupsTest bench shape), with the trips, the Clubhouse and
 * the record real: a trip to hole 1 is a world session on the {@link SessionBench} rail (or, for a member
 * waiting in the Clubhouse, the real {@link ClubGolf#fromClubhouse}), the group's end is the real
 * {@link ClubGolf#take} through the Clubhouse's door, and a round's record is the real
 * {@link GolfFinish#settle} over the framework's scores, rewards and quests (a copy of
 * {@code GolfRounds.record}/{@code ledger}, whose own read the plugin's clock).
 */
public final class GolfBench implements GolfGroups.Port {

    private final GamesService games;
    private final MiniGolf golf;
    private final SessionBench rail;
    private final Function<UUID, Player> online;
    private ClubDoor door;
    final GolfGroups groups = new GolfGroups(this);
    final Map<UUID, LiveRound> rounds = new HashMap<>();
    private final Map<UUID, GolfGroup> trips = new HashMap<>();
    private final List<Runnable> tasks = new ArrayList<>();
    /** How often each player's round was recorded, and how often they went home or to the Clubhouse. */
    public final Map<UUID, Integer> recorded = new HashMap<>();
    public final Map<UUID, Integer> home = new HashMap<>();
    public final Map<UUID, Integer> toClub = new HashMap<>();
    public final List<Long> roundsOver = new ArrayList<>();
    /** Each group end's card, in order. */
    public final List<GolfGroup.Card> ends = new ArrayList<>();

    public GolfBench(GamesService games, SessionBench rail, ClubBench club, Function<UUID, Player> online) {
        this.games = games;
        this.golf = (MiniGolf) games.game(MiniGolf.SPEC.id());
        this.rail = rail;
        this.online = online;
        this.door = club == null ? null : club.door();
        rail.onEnded(e -> {
            if (MiniGolf.SPEC.id().equals(e.gameId())) {
                groups.leave(e.player(), rounds.remove(e.player())); // GolfRounds.ended
            }
        });
    }

    public MiniGolf golf() {
        return golf;
    }

    /** The Clubhouse's door the bench uses ({@code null}: none, a group goes home). */
    public void door(ClubDoor door) {
        this.door = door;
    }

    /** Save {@code c} as a golf course row (as the golf editor does). */
    public static void save(GamesService games, GolfCourse c) throws SQLException {
        games.dao().saveCourse(CourseCodec.toRow(c, 0, 0), false);
        games.coursesChanged(MiniGolf.SPEC.id());
    }

    /** A course of {@code holes} par-3 holes in the Games world. */
    public static GolfCourse course(String id, String name, int holes) {
        List<GolfCourse.Hole> all = new ArrayList<>();
        for (int i = 0; i < holes; i++) {
            all.add(new GolfCourse.Hole(new GolfCourse.Tee(10.5 + 40 * i, 64.0, -3.5, 90f),
                    new GolfCourse.Spot(18 + 40 * i, 63, -3), 3, new GolfCourse.Spot(8 + 40 * i, 63, -6),
                    new GolfCourse.Spot(20 + 40 * i, 66, 0)));
        }
        return new GolfCourse(id, name, "games", true, 1, all);
    }

    /** Hole {@code n}'s tee (1-based), on the rail. */
    public static SessionBench.Spot tee(GolfCourse c, int n) {
        GolfCourse.Tee t = c.hole(n).tee();
        return new SessionBench.Spot(c.world(), t.x(), t.y(), t.z());
    }

    /** {@code GolfTogether.start}'s loop and {@code GolfRounds.startGroup}: everyone listed goes. */
    public boolean start(long partyId, String courseId, List<Player> players) {
        GolfCourse c = golf.playableCourse(courseId);
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Player p : players) {
            names.put(p.getUniqueId(), p.getName());
        }
        return c != null && groups.start(partyId, c, names);
    }

    /** The group a player is with, or {@code null}. */
    public GolfGroup groupOf(UUID player) {
        return groups.of(player);
    }

    /** The player's round now, or {@code null}. */
    public boolean playing(UUID player) {
        return rounds.containsKey(player);
    }

    /** Whether the player's round is over (every hole played). */
    public boolean done(UUID player) {
        LiveRound r = rounds.get(player);
        return r != null && r.state == LiveRound.State.DONE;
    }

    /** In the cup after {@code strokes} putts: {@code GolfRounds.inCup}, then {@code afterHole}. */
    public void holeIn(UUID player, int strokes) {
        LiveRound r = rounds.get(player);
        for (int i = 0; i < strokes; i++) {
            r.run.stroke();
        }
        r.last = r.run.inCup();
        groups.holeDone(player, r);
    }

    /** The group's scheduled steps (the next tee, the end), all of them. */
    public void runTasks() {
        while (!tasks.isEmpty()) {
            List<Runnable> due = new ArrayList<>(tasks);
            tasks.clear();
            due.forEach(Runnable::run);
        }
    }

    /** Once a second (golf's tick). */
    public void second() {
        groups.second();
    }

    /** The groups playing now. */
    public List<GolfGroup> live() {
        return groups.live();
    }

    /** The golf game stops ({@code MiniGolf.stop}: its rounds and parties) and the bench's groups with it. */
    public void stop() {
        golf.stop();
        groups.stop();
        rounds.clear();
    }

    // ---- the port ------------------------------------------------------------------------------------

    @Override
    public LiveRound round(UUID player) {
        return rounds.get(player);
    }

    @Override
    public boolean inSession(UUID player) {
        return online.apply(player) != null && rail.session(player) != null;
    }

    /** {@code GolfRounds.start} for a group: from the Clubhouse in place, else a world session to hole 1. */
    @Override
    public boolean enter(UUID player, GolfGroup group) {
        Player p = online.apply(player);
        GolfCourse c = golf.playableCourse(group.courseId());
        if (p == null || c == null) {
            return false;
        }
        SessionBench.Spot t = tee(c, 1);
        Location start = new Location(world(c.world()), t.x(), t.y(), t.z());
        Boolean fromClub = ClubGolf.fromClubhouse(door, p, golf, c.id(), start,
                (q, at) -> rail.teleport(q.getUniqueId(), new SessionBench.Spot(c.world(), at.getX(), at.getY(),
                        at.getZ())), q -> games.canOpen(q, golf), (q, r) -> games.tell(q, r));
        if (fromClub != null) {
            if (fromClub) {
                begin(p, c, group);
            }
            return fromClub;
        }
        String why = rail.enter(player, golf, c.id(), t, q -> begin(q, c, group));
        if (why != null) {
            games.tell(p, com.dierks.homecraft.games.Refusal.of(why));
            return false;
        }
        trips.put(player, group);
        return true;
    }

    /** {@code GolfRounds.begin}: the round, its group, and golf's kit. */
    private void begin(Player p, GolfCourse course, GolfGroup group) {
        UUID id = p.getUniqueId();
        LiveRound r = new LiveRound(id, course, new GolfRun(course.pars(), golf.settings().maxOverPar()), null);
        rounds.put(id, r);
        r.group = groups.joins(id, group);
        rail.stripKit(id);
        for (int i = 0; i < 5; i++) {
            rail.putKit(id, i, "kit:golf:club" + (i + 1));
        }
        rail.putKit(id, 8, "kit:golf:leave");
    }

    @Override
    public void tell(UUID player, String line) {
        Player p = online.apply(player);
        if (p != null) {
            p.sendMessage(Text.of(line));
        }
    }

    @Override
    public void showCard(UUID player, GolfGroup.Card card) {
    }

    @Override
    public void nextTee(UUID player, LiveRound round) {
        round.state = LiveRound.State.PLAYING; // GolfRounds.startHole
        rail.teleport(player, tee(round.course, round.run.hole() + 1));
    }

    /** {@code GolfRounds.finishLines} and {@code record}: the board and the rewards, through the real settle. */
    @Override
    public void finished(UUID player, LiveRound round) {
        Player p = online.apply(player);
        if (p == null) {
            return;
        }
        recorded.merge(player, 1, Integer::sum);
        GolfRun run = round.run;
        GolfCourse c = round.course;
        MiniGolfSettings s = golf.settings();
        long day = games.clock().dayKey();
        boolean featured = games.featured().isFeatured(c.id()) || games.featured().isFeatured(golf.id());
        GolfFinish.settle(new GolfFinish.Round(c.id(), golf.name() + ": " + c.name(), run.total(), c.par(),
                c.holes().size(), run.parOrBetter(), run.holesInOne(), day, featured, s.firstClear(), s.parReward(),
                s.holeInOneReward(), games.config().common().featuredBonus(), null), ledger(p, c));
    }

    private GolfFinish.Ledger ledger(Player p, GolfCourse c) {
        UUID id = p.getUniqueId();
        return new GolfFinish.Ledger() {
            @Override
            public ScoreResult submit(String board, int strokes) {
                return games.scores().submit(id, golf.id(), board, strokes, true);
            }

            @Override
            public void announce(ScoreResult result, boolean daily) {
            }

            @Override
            public int pay(RewardKind kind, String ref, int tokens, String detail) {
                return games.rewards().pay(p, golf, golf.source(), kind, ref, tokens, golf.settings().dailyCap(),
                        detail);
            }

            @Override
            public void finished(GolfFinish.Round round, GolfFinish.Summary summary) {
                games.tellProgress(g -> g.golfFinished(p, c.id(), round.strokes(), round.par(), round.holesInOne().size(),
                        c.generated(), summary.result().record()));
            }
        };
    }

    @Override
    public void pickedUp(UUID player, LiveRound round) {
    }

    /** {@code GolfRounds.Server.home}: the Clubhouse ({@link ClubGolf#take}), else home with their things. */
    @Override
    public void home(UUID player, LiveRound round, GolfGroup.Card card) {
        Player p = online.apply(player);
        if (p == null) {
            return;
        }
        ends.add(card);
        SessionBench.Spot at = rail.place(player);
        if (ClubGolf.take(door, p, at == null ? null : at.world(), () -> forget(round), card, null)) {
            toClub.merge(player, 1, Integer::sum);
            return;
        }
        home.merge(player, 1, Integer::sum);
        rail.leave(player, EndReason.FINISH);
    }

    /** {@code GolfRounds.forget}: the finished round is dropped. */
    private void forget(LiveRound r) {
        if (rounds.get(r.player) == r) {
            rounds.remove(r.player);
        }
    }

    @Override
    public void later(long ticks, Runnable task) {
        tasks.add(task);
    }

    @Override
    public void roundOver(long partyId) {
        roundsOver.add(partyId);
        golf.together().roundOver(partyId);
    }

    @Override
    public String name(UUID player) {
        Player p = online.apply(player);
        return p == null ? "a player" : p.getName();
    }

    /** The worlds handed out, held: a {@link Location} keeps its world only weakly ("World unloaded"). */
    private static final Map<String, World> WORLDS = new ConcurrentHashMap<>();

    private static World world(String name) {
        return WORLDS.computeIfAbsent(name, GolfBench::newWorld);
    }

    private static World newWorld(String name) {
        return (World) Proxy.newProxyInstance(GolfBench.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getName" -> name;
                    case "equals" -> a[0] instanceof World w && name.equals(w.getName());
                    case "hashCode" -> name.hashCode();
                    case "toString" -> "World(" + name + ")";
                    default -> m.getReturnType() == boolean.class ? false : m.getReturnType() == int.class ? 0 : null;
                });
    }

    /** The golf row of a course, for a check. */
    public GamesDao.CourseRow row(String id) throws SQLException {
        return games.dao().course(id);
    }
}
