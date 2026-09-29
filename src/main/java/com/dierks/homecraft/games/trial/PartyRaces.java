package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Invite;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.gui.games.trial.PartyMenu;
import com.dierks.homecraft.gui.games.trial.PartyResultsMenu;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Party races (owner decision D4: "it would be nice to be able to join several of us together when
 * we want to race so it's not always lonely"): friends racing any time-trial course together,
 * whenever they like, on race mode (the one race engine Race Night uses too).
 *
 * <p><b>The flow.</b> "Race with friends" on a course's screen, or {@code /hcm play race <course>},
 * opens a party ({@link PartyLobby}, one per player across party races and golf together, in
 * {@code GamesService#parties()}). Anyone in it invites through the existing Invites system: [Accept]
 * on Java, {@code /hcm play accept} on Bedrock, with its per-pair cooldown and the invite switches
 * ({@code /hcm play invites off} covers party races too). Up to {@code games.trials.party_max} (8).
 * The lobby shows who's in and who's ready; only the host starts, and the host chooses whether
 * everyone warms up first ({@code games.trials.warmup_seconds}; D3). Then {@link PartyRace} runs:
 * everyone seated through {@code TimeTrials.race}, two a tick, the shared warm-up if chosen, one grid
 * and one go tick for all, live positions on a bossbar ("2nd of 5 · Lap 1/2"), and a group results
 * screen. The lobby then opens again for "Race again".
 *
 * <p><b>Free, and a normal run.</b> No entry, no fees, no prizes: nothing here moves a token. Each
 * racer's finish is also their normal counted run on the course (its boards, first finish, the
 * Weekly Cup), exactly once, through TimeTrials' own finish. Boats bump, as at Race Night.
 *
 * <p><b>Leaving.</b> Anyone can leave at any time: Leave game is a DNF and keeps them in the party;
 * "Leave the party" also takes them out of it; a disconnect is a DNF and takes them out. A party
 * whose host leaves passes to the next who joined. The others carry on. A restart due soon refuses
 * a new start (racers already going finish, and a shared warm-up goes straight to the grid), and a
 * course held for Race Night can't be party-raced: Race Night's hold calls off a party race already
 * on its track ({@link #callOff}), its racers home with a clear line and nothing counted. The Dropper
 * has no party races (its only extra is its own practice drop).
 *
 * <p>Everything runs inside Time Trials' guard; a party is memory only, and every party race ends
 * with Time Trials.
 */
public final class PartyRaces {

    /** How long a party invite waits for an answer. */
    static final int INVITE_SECONDS = 60;
    /** The bars and the race clock run this often (ticks). */
    static final long EVERY = 5;
    /** The results screen waits up to this many checks for a racer to be home. */
    static final int RESULT_TRIES = 30;
    /** Why a Dropper has no party race (the orchestrator's decision after the D review). */
    static final String NO_DROPPER = "The Dropper has no party races - try its practice drop instead.";
    /** What a party racer reads when Race Night takes the track (their race is called off). */
    static final String CALLED_OFF_FOR_NIGHT = "&eYour party race is called off &7- Race Night needs this track now."
            + " Nothing from this race counts. Your things are back.";

    private final TimeTrials trials;
    /** Lobby id → whether the host wants a shared warm-up first. */
    private final Map<Long, Boolean> warmups = new HashMap<>();
    /** Lobby id → its race, while one runs. */
    private final Map<Long, PartyRace> races = new HashMap<>();
    /** Invite id → the lobby it asks to. */
    private final Map<Long, Long> invites = new HashMap<>();
    /** Lobby id → its last race's results (for "Race again" and the screen). */
    private final Map<Long, List<PartyRace.Line>> results = new HashMap<>();
    /** Racer → their position bar. */
    private final Map<UUID, BossBar> bars = new HashMap<>();
    /** Racer → the lap their bar last showed (a new lap gets a title). */
    private final Map<UUID, Integer> laps = new HashMap<>();
    private long ticks;

    PartyRaces(TimeTrials trials) {
        this.trials = trials;
    }

    // ---- lifecycle (TimeTrials' hooks) -------------------------------------------------------------

    void start() {
        games().every(trials, EVERY, EVERY, this::tick);
    }

    /** Time Trials is stopping: every party race ends (their sessions end with the game), every party closes. */
    void stop() {
        for (PartyRace race : new ArrayList<>(races.values())) {
            race.end();
        }
        races.clear();
        for (UUID id : new ArrayList<>(bars.keySet())) {
            hideBar(id);
        }
        GamesService g = trials.games();
        if (g != null) {
            for (PartyLobby l : g.parties().all()) {
                if (l.kind() == PartyLobby.Kind.RACE) {
                    g.parties().close(l.id());
                }
            }
        }
        warmups.clear();
        invites.clear();
        results.clear();
        laps.clear();
    }

    /** A player quit: a DNF in their race, and out of their party (the host passes on). */
    void quit(Player p) {
        UUID id = p.getUniqueId();
        hideBar(id);
        PartyLobby lobby = lobby(id);
        if (lobby == null) {
            return;
        }
        PartyRace race = races.get(lobby.id());
        if (race != null) {
            race.left(id, EndReason.DISCONNECT);
        }
        leaveParty(lobby, id, p.getName());
    }

    // ---- opening and inviting ----------------------------------------------------------------------

    /**
     * "Race with friends" on {@code courseId}: the player's party lobby (a new one, hosted by them,
     * unless they are already in one).
     */
    public void open(Player player, String courseId, Runnable back) {
        GamesService g = games();
        Refusal r = g.canOpen(player, trials);
        if (r != null) {
            g.tell(player, r);
            return;
        }
        UUID id = player.getUniqueId();
        PartyLobby mine = g.parties().of(id);
        if (mine != null) {
            if (mine.kind() != PartyLobby.Kind.RACE) {
                g.tell(player, Refusal.of(PartyLobby.Why.IN_ANOTHER.message()));
                return;
            }
            if (!mine.course().equalsIgnoreCase(courseId == null ? "" : courseId.trim())) {
                player.sendMessage(Text.of("&7You're in a party for " + courseName(mine) + " - here it is."));
            }
            openLobby(player, back);
            return;
        }
        Course c = trials.openCourse(courseId);
        String no = courseProblem(c);
        if (no != null) {
            g.tell(player, Refusal.of(no));
            return;
        }
        PartyLobby lobby = g.parties().create(PartyLobby.Kind.RACE, c.id(), id, trials.settings().partyMax());
        if (lobby == null) {
            g.tell(player, Refusal.of(PartyLobby.Why.IN_ANOTHER.message()));
            return;
        }
        warmups.put(lobby.id(), trials.settings().warmupsOn());
        player.sendMessage(Text.of("&dYour party for " + c.name() + " is open! &7Invite friends, then Start the race."));
        openLobby(player, back);
    }

    /** The player's party screen (nothing when they aren't in a party race). */
    public void openLobby(Player player, Runnable back) {
        PartyLobby lobby = lobby(player.getUniqueId());
        if (lobby == null) {
            player.sendMessage(Text.of("&7You're not in a party."));
            return;
        }
        new PartyMenu(trials.plugin(), trials, player, back).open(player);
    }

    /** "Invite a friend": the player picker, then the invite. */
    public void invite(Player from, Runnable back) {
        PartyLobby lobby = lobby(from.getUniqueId());
        if (lobby == null) {
            return;
        }
        if (lobby.state() != PartyLobby.State.OPEN) {
            games().tell(from, Refusal.of("Wait for this race to end to invite someone."));
            return;
        }
        if (lobby.full()) {
            games().tell(from, Refusal.of(PartyLobby.Why.FULL.message()));
            return;
        }
        games().screens().pickPlayer(from, trials, other -> canInvite(from, other), chosen -> send(from, chosen), back);
    }

    /** Whether {@code other} may be asked: online, free, not in a party, allowed to play. */
    boolean canInvite(Player from, Player other) {
        if (other == null || !other.isOnline() || other.getUniqueId().equals(from.getUniqueId())) {
            return false;
        }
        GamesService g = games();
        return g.parties().of(other.getUniqueId()) == null && g.canOpen(other, trials) == null
                && trials.sessions().session(other) == null;
    }

    private void send(Player from, Player to) {
        PartyLobby lobby = lobby(from.getUniqueId());
        if (lobby == null || !canInvite(from, to)) {
            couldNotSend(from);
            return;
        }
        Invite sent = games().invites().send(from, to, trials, "a party race on " + courseName(lobby)
                        + " - free, just for fun", INVITE_SECONDS,
                (invite, yes) -> games().guard(trials, () -> answered(invite, yes)));
        if (sent == null) {
            couldNotSend(from);
            return;
        }
        invites.put(sent.id(), lobby.id());
        from.sendMessage(Text.of("&aInvite sent to " + to.getName() + ". &7Waiting for an answer..."));
    }

    private static void couldNotSend(Player from) {
        from.sendMessage(Text.of("&cThat invite couldn't be sent right now. &7(One invite at a time.)"));
        Sounds.refused(from);
    }

    private void answered(Invite invite, boolean yes) {
        Long lobbyId = invites.remove(invite.id());
        Player from = Bukkit.getPlayer(invite.from());
        Player to = Bukkit.getPlayer(invite.to());
        if (!yes) {
            if (from != null) {
                from.sendMessage(Text.of("&7" + (to != null ? to.getName() : "Your friend") + " didn't join the party."));
            }
            return;
        }
        PartyLobby lobby = lobbyId == null ? null : games().parties().get(lobbyId);
        if (to == null) {
            return;
        }
        if (lobby == null) {
            to.sendMessage(Text.of("&7That party has ended."));
            return;
        }
        Refusal r = games().canOpen(to, trials);
        if (r != null) {
            games().tell(to, r);
            return;
        }
        PartyLobby.Why no = games().parties().join(lobby.id(), to.getUniqueId());
        if (no != null) {
            games().tell(to, Refusal.of(no.message()));
            return;
        }
        say(lobby, "&a" + to.getName() + " joined the party! &7(" + lobby.size() + " of " + lobby.max() + ")");
        openLobby(to, null);
    }

    // ---- in the lobby ----------------------------------------------------------------------------

    /** Ready, or not ready (it shows on the lobby screen). */
    public void toggleReady(Player p) {
        PartyLobby lobby = lobby(p.getUniqueId());
        if (lobby != null && lobby.state() == PartyLobby.State.OPEN) {
            lobby.ready(p.getUniqueId(), !lobby.isReady(p.getUniqueId()));
        }
    }

    /** The host: warm up first, or not. */
    public void toggleWarmup(Player p) {
        PartyLobby lobby = lobby(p.getUniqueId());
        if (lobby == null || !lobby.isHost(p.getUniqueId()) || lobby.state() != PartyLobby.State.OPEN) {
            return;
        }
        if (!trials.settings().warmupsOn()) {
            p.sendMessage(Text.of("&7Warm-ups are switched off on this server."));
            return;
        }
        warmups.put(lobby.id(), !warmup(lobby));
    }

    /** Whether the party warms up before the grid. */
    public boolean warmup(PartyLobby lobby) {
        return lobby != null && trials.settings().warmupsOn() && warmups.getOrDefault(lobby.id(), true);
    }

    /** "Leave the party": out of it, and out of its race (a DNF) if they are racing. */
    public void leave(Player p) {
        UUID id = p.getUniqueId();
        PartyLobby lobby = lobby(id);
        if (lobby == null) {
            return;
        }
        PartyRace race = races.get(lobby.id());
        if (race != null && race.racers().contains(id)) {
            race.left(id, EndReason.COMMAND);
            trials.endRace(id, EndReason.COMMAND, null);
        }
        hideBar(id);
        p.sendMessage(Text.of("&7You left the party."));
        leaveParty(lobby, id, p.getName());
    }

    private void leaveParty(PartyLobby lobby, UUID id, String name) {
        PartyLobby.Left left = games().parties().leave(id);
        if (!left.wasIn()) {
            return;
        }
        if (left.closed()) {
            forget(lobby.id());
            return;
        }
        say(lobby, "&7" + name + " left the party.");
        if (left.newHost() != null) {
            say(lobby, "&e" + name(left.newHost()) + " is the host now.");
        }
    }

    private void forget(long lobbyId) {
        PartyRace race = races.remove(lobbyId);
        if (race != null) {
            race.end();
        }
        warmups.remove(lobbyId);
        results.remove(lobbyId);
        invites.values().removeIf(l -> l == lobbyId);
    }

    /**
     * Why the host can't start now, or {@code null}: the party's own rules (the host, 2 in, not
     * already racing), a restart due soon, the course closed or held for Race Night.
     */
    public String startProblem(Player host) {
        PartyLobby lobby = lobby(host.getUniqueId());
        if (lobby == null) {
            return "You're not in a party.";
        }
        PartyLobby.Why why = lobby.canStart(host.getUniqueId());
        if (why != null) {
            return why.message();
        }
        Refusal hold = games().restartRefusal();
        if (hold != null) {
            return hold.message();
        }
        Course c = trials.openCourse(lobby.course());
        String no = courseProblem(c);
        if (no != null) {
            return no;
        }
        String held = trials.raceMode().holds().refusal(c.id());
        if (held != null) {
            return Text.plain(held);
        }
        return null;
    }

    /**
     * Why a party race can't be on {@code c} at all: the course is closed, or it is a Dropper
     * ({@link #offered}); {@code null} when it can.
     */
    static String courseProblem(Course c) {
        if (c == null) {
            return "That course is closed right now.";
        }
        return offered(c.kind()) ? null : NO_DROPPER;
    }

    /** The host starts the race: who's free races; grid, warm-up, Go. */
    public void start(Player host) {
        String problem = startProblem(host);
        if (problem != null) {
            games().tell(host, Refusal.of(problem));
            return;
        }
        PartyLobby lobby = lobby(host.getUniqueId());
        Course base = trials.openCourse(lobby.course());
        World world = base == null ? null : Bukkit.getWorld(base.world());
        if (world == null) {
            games().tell(host, Refusal.of("That course isn't ready right now."));
            return;
        }
        List<Player> free = new ArrayList<>();
        for (UUID id : lobby.members()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            if (games().canOpen(p, trials) != null || trials.sessions().session(p) != null
                    || !trials.sessions().home(p)) {
                say(lobby, "&7" + p.getName() + " isn't free to race right now.");
                continue;
            }
            free.add(p);
        }
        if (free.size() < PartyLobby.MIN_PLAYERS) {
            games().tell(host, Refusal.of("The race needs 2 racers who are free right now."));
            return;
        }
        RaceGrid.Grid grid = RaceGrid.forCourse(base, new WorldSurface(world), free.size());
        if (grid.size() < free.size()) {
            games().tell(host, Refusal.of("This track's grid fits " + grid.size() + " boats - race with fewer friends."));
            return;
        }
        if (lobby.start(host.getUniqueId()) != null) {
            return;
        }
        List<PartyRace.Racer> racers = new ArrayList<>();
        for (Player p : free) {
            racers.add(new PartyRace.Racer(p.getUniqueId(), p.getName()));
        }
        int warm = warmup(lobby) ? trials.settings().warmupSeconds() : 0;
        PartyRace race = new PartyRace(lobby.id(), base, racers, warm, Bukkit::getCurrentTick,
                line -> say(lobby, line));
        races.put(lobby.id(), race);
        results.remove(lobby.id());
        Location stand = stand(base, world);
        say(lobby, "&dRace on " + base.name() + "! &7" + free.size() + " racers"
                + (warm > 0 ? " - warm up first (" + Warmup.clock(warm) + ")" : "") + ". Free, just for fun.");
        for (int i = 0; i < free.size(); i++) {
            Player p = free.get(i);
            Course.Spot spot = grid.spot(i);
            int place = i + 1;
            games().later(trials, i / 2, () -> seat(race, p.getUniqueId(), base, spot, stand, place));
        }
        games().later(trials, PartyRace.seatingTicks(free.size()), () -> seatingDone(lobby.id(), race));
    }

    private void seat(PartyRace race, UUID id, Course base, Course.Spot spot, Location stand, int place) {
        Player p = Bukkit.getPlayer(id);
        if (!race.alive()) {
            return;
        }
        if (p == null) {
            race.notSeated(id);
            return;
        }
        Laps.Raced raced = Laps.raced(base, spot, 0);
        Refusal r = raced.course() == null ? Refusal.of(raced.problem())
                : trials.race(p, base, raced.course(), spot, stand, race);
        if (r != null) {
            race.notSeated(id);
            PartyLobby lobby = lobby(id);
            if (lobby != null) {
                say(lobby, "&7" + p.getName() + " couldn't join this race.");
            }
            return;
        }
        race.seated(id, spot);
        if (base.kind() == TrialKind.BOAT) {
            p.sendMessage(Text.of("&7You start &e" + RaceStandings.ordinal(place) + " &7on the grid."));
        }
    }

    private void seatingDone(long lobbyId, PartyRace race) {
        if (races.get(lobbyId) != race) {
            return;
        }
        if (!race.seatingDone()) {
            finish(race, "&7The race needs 2 racers - it's off. Try it again from the party screen.", EndReason.FINISH);
        }
    }

    // ---- the race clock ----------------------------------------------------------------------------

    private void tick() {
        ticks += EVERY;
        if (races.isEmpty()) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        boolean held = trials.games().restartHeld() != null; // a restart soon: warm-ups go straight to the grid
        for (PartyRace race : new ArrayList<>(races.values())) {
            switch (race.tick(now, held)) {
                case TO_GRID -> toGrid(race);
                case END -> finish(race, null, EndReason.FINISH);
                default -> {
                    if (race.state() == PartyRace.State.RACING && ticks % 10 == 0) {
                        bars(race);
                    }
                }
            }
        }
    }

    /** The warm-up is over: every racer still in to their grid spot, held to the one go tick. */
    private void toGrid(PartyRace race) {
        for (UUID id : race.racers()) {
            Player p = Bukkit.getPlayer(id);
            Course.Spot spot = race.grid(id);
            if (p == null || spot == null || !race.racing(id)) {
                continue;
            }
            Laps.Raced raced = Laps.raced(race.base(), spot, 0);
            if (raced.course() != null) {
                trials.regrid(p, raced.course(), spot);
                p.sendMessage(Text.of("&eTo the grid! &7The race starts in a few seconds."));
            }
        }
    }

    /** Each racer's bossbar: "2nd of 5 · Lap 1/2", filled by how far round they are. */
    private void bars(PartyRace race) {
        for (UUID id : race.racers()) {
            Player p = Bukkit.getPlayer(id);
            TrialRun run = trials.run(id);
            boolean onIt = run != null && run.race != null && run.race.link == race && !run.race.ended;
            String line = onIt ? race.bar(id) : null; // gone home (a finisher with no stand): no bar, even on a solo run
            if (p == null || line == null) {
                hideBar(id);
                continue;
            }
            int lap = race.lap(id);
            boolean last = race.laps() > 1 && lap == race.laps();
            BossBar.Color colour = last ? BossBar.Color.YELLOW : BossBar.Color.BLUE;
            String shown = last && race.racing(id) ? line + " &e- LAST LAP!" : line;
            BossBar bar = bars.get(id);
            if (bar == null) {
                bar = BossBar.bossBar(Text.of(shown), race.share(id), colour, BossBar.Overlay.PROGRESS);
                bars.put(id, bar);
                p.showBossBar(bar);
            } else {
                bar.name(Text.of(shown));
                bar.progress(race.share(id));
                bar.color(colour);
            }
            Integer was = laps.put(id, lap);
            if (was != null && lap > was && race.racing(id)) {
                TimeTrials.title(p, "&aLap " + lap + " of " + race.laps() + "!", last ? "&eLast lap!" : "", 30);
            }
        }
    }

    private void hideBar(UUID id) {
        laps.remove(id);
        BossBar bar = bars.remove(id);
        Player p = bar == null ? null : Bukkit.getPlayer(id);
        if (p != null) {
            try {
                p.hideBossBar(bar);
            } catch (RuntimeException | LinkageError ignored) {
                // gone with the player
            }
        }
    }

    /**
     * Race Night holds {@code courseId} ({@code TimeTrials.reserve}): every party race on it is called
     * off now. Its racers go home with their things, reading {@code line}; whatever they hadn't
     * finished is a DNF (nothing more counts), no results are kept, and the party opens again (a new
     * start is refused while the track is held). Returns how many were called off.
     */
    int callOff(String courseId, String line) {
        if (courseId == null || races.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (PartyRace race : new ArrayList<>(races.values())) {
            if (race.base().id().equalsIgnoreCase(courseId.trim())) {
                finish(race, line == null ? CALLED_OFF_FOR_NIGHT : line, EndReason.ADMIN);
                n++;
            }
        }
        return n;
    }

    /** A race running for its lobby (PartyRaces.start's bookkeeping; the tests seat one directly). */
    void running(PartyRace race) {
        races.put(race.lobbyId(), race);
    }

    /**
     * The race is over: everyone still on the track or the stand goes home (things back), the group
     * reads the results and sees them on a screen once home, and the party opens again. With a
     * {@code line} it was called off (too few seated, or Race Night took the track): everyone on it
     * reads that line, and no results are kept.
     */
    private void finish(PartyRace race, String line, EndReason why) {
        races.remove(race.lobbyId());
        race.end();
        List<PartyRace.Line> lines = race.results();
        if (line == null) {
            results.put(race.lobbyId(), lines);
        }
        for (UUID id : race.racers()) {
            hideBar(id);
            TrialRun run = trials.run(id);
            if ((run != null && run.race != null && run.race.link == race) || trials.raceMode().arrivingFor(id, race)) {
                PartyRace.Line mine = lineOf(lines, id);
                String bye = line != null ? line : mine != null && mine.result() == PartyRace.Result.STILL_RACING
                        ? "&7Race over - great racing!" : null;
                trials.endRace(id, why, bye);
            }
        }
        PartyLobby lobby = games().parties().get(race.lobbyId());
        if (lobby != null) {
            lobby.finish();
        }
        if (line != null) {
            return; // called off: no results
        }
        List<String> chat = new ArrayList<>();
        chat.add("&6Race results on " + race.base().name() + ":");
        for (PartyRace.Line l : lines) {
            if (l.result() != PartyRace.Result.NOT_STARTED) {
                chat.add("  " + PartyRace.chatLine(l));
            }
        }
        for (UUID id : race.racers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                for (String c : chat) {
                    p.sendMessage(Text.of(c));
                }
                showResults(id, race.base().name(), lines, RESULT_TRIES);
            }
        }
    }

    private static PartyRace.Line lineOf(List<PartyRace.Line> lines, UUID id) {
        for (PartyRace.Line l : lines) {
            if (l.id().equals(id)) {
                return l;
            }
        }
        return null;
    }

    /** Once the racer is home (their things back), the group's results screen. */
    private void showResults(UUID id, String courseName, List<PartyRace.Line> lines, int tries) {
        games().later(trials, TimeTrials.RESULT_EVERY, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                return;
            }
            if (!trials.home(p)) {
                if (tries > 0) {
                    showResults(id, courseName, lines, tries - 1);
                }
                return;
            }
            InventoryType open = p.getOpenInventory().getType();
            if (open == InventoryType.CRAFTING || open == InventoryType.CREATIVE) {
                new PartyResultsMenu(trials.plugin(), trials, p, courseName, lines).open(p);
            }
        });
    }

    // ---- what the screens read -------------------------------------------------------------------

    /** The player's party race lobby, or {@code null}. */
    public PartyLobby lobby(UUID player) {
        GamesService g = games();
        PartyLobby l = g == null ? null : g.parties().of(player);
        return l != null && l.kind() == PartyLobby.Kind.RACE ? l : null;
    }

    /** Whether the party's race is running now. */
    public boolean racing(PartyLobby lobby) {
        return lobby != null && races.containsKey(lobby.id());
    }

    /** The party's last race's results, or an empty list. */
    public List<PartyRace.Line> lastResults(PartyLobby lobby) {
        return lobby == null ? List.of() : results.getOrDefault(lobby.id(), List.of());
    }

    /** The course's name, or its id when it is gone. */
    public String courseName(PartyLobby lobby) {
        Course c = trials.course(lobby.course());
        return c == null ? lobby.course() : c.name();
    }

    /** A player's name, or "someone". */
    public String name(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            return p.getName();
        }
        OfflinePlayer o = Bukkit.getOfflinePlayer(id);
        return o.getName() == null ? "someone" : o.getName();
    }

    /**
     * The course's stand, where finishers wait: Fresh Ice Boat from algo 2 (checked in the world: a
     * stand that isn't standing is never used), else none, and finishers go home at the line.
     */
    private Location stand(Course base, World world) {
        Box half = base.gen() == null ? null : trials.generated().half(base.gen());
        Point at = RaceStand.of(base, half);
        if (at == null || !RaceStand.standable(new WorldSurface(world), at)) {
            return null;
        }
        return new Location(world, at.x(), at.y(), at.z());
    }

    /** Tell every member of the party online. */
    private void say(PartyLobby lobby, String line) {
        for (UUID id : lobby.members()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(Text.of(line));
            }
        }
    }

    private GamesService games() {
        return trials.games();
    }

    // ---- /hcm play race <course> ----------------------------------------------------------------

    /** Time Trials, when it is loaded. */
    private static TimeTrials trials(GamesService games) {
        return games != null && games.game(TimeTrials.SPEC.id()) instanceof TimeTrials t ? t : null;
    }

    /**
     * Whether a course of this kind has party races at all (the orchestrator's decision after the D
     * review): every kind but the Dropper, whose only extra is its own practice drop. The course
     * screen hides "Race with friends" on a Dropper by this.
     */
    public static boolean offered(TrialKind kind) {
        return kind != null && kind != TrialKind.DROPPER;
    }

    /** Whether {@code id} names a time-trial course (open or not: a closed one is refused with its reason). */
    public static boolean isCourse(GamesService games, String id) {
        TimeTrials t = trials(games);
        return t != null && games.guard(t, () -> t.course(id) != null, false);
    }

    /** The open courses' ids, for tab completion. */
    public static List<String> courseIds(GamesService games) {
        TimeTrials t = trials(games);
        if (t == null || !games.enabled(t)) {
            return List.of();
        }
        return games.guard(t, () -> t.openCourses().stream().map(Course::id).toList(), List.of());
    }

    /** {@code /hcm play race <course>}: "Race with friends" on that course, inside Time Trials' guard. */
    public static void fromCommand(GamesService games, Player player, String courseId) {
        TimeTrials t = trials(games);
        if (t != null) {
            games.guard(t, () -> t.raceWithFriends(player, courseId, null));
        }
    }
}
