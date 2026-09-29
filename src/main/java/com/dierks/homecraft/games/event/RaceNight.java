package com.dierks.homecraft.games.event;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.gen.NewCoursesNudge;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.gui.games.event.RaceNightMenu;
import com.dierks.homecraft.storage.EventDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Race Night (EVENTS-DROPPER-SPEC §A, EVENTS-RECONCILED decisions 1-2, owner decision D3): boat
 * races for everyone at once, at set times or whenever an admin starts one. Three short races on one
 * track, points for everyone in every race, and small token prizes from the server. Entry is free:
 * nobody can lose tokens.
 *
 * <p>Id {@code race_night}, alias {@code race}; kind {@link GameKind#TRIAL} (a free skill game, no
 * new kind); never the featured game; ledger source {@code GAMES_RACE_NIGHT}; its tile goes on the
 * {@link Game.Tab#TOGETHER Together} tab. Open only while {@code games.race_night.enabled} AND Time
 * Trials are on: the races are Time Trials runs in race mode.
 *
 * <p><b>How it is put together.</b> The pure parts decide everything: {@link EventSchedule} (when,
 * and which nights are skipped), {@link EventMachine} (the night's timeline), {@link NightStandings}
 * (points, countback, the grid), {@link RacePrizes} (who wins what, bounded), {@link RaceTrack}
 * (laps, grids, stands). {@link NightRunner} runs one night through {@link NightPorts} ({@link
 * LivePorts} here, a fake in the tests), and {@link EventDao} keeps it crash-safe. This class is the
 * glue: the one-tick clock, the schedule, the boot that finishes what a stop left (§A.9), the
 * owed-prize loop, the hub and website views, the Watch button and the admin commands.
 *
 * <p>Everything runs on the main thread inside the game's guard.
 */
public final class RaceNight implements Game {

    /** Built: the game follows its config switch (and Time Trials'). */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<RaceNightSettings> SPEC = new GameSpec<>("race_night", GameKind.TRIAL,
            RaceNightSettings.KEYS, RaceNightSettings.defaults(), RaceNightSettings::parse, RaceNight::new, null);

    /** Scheduling stopped by an admin ({@code /hcm games event pause}). */
    static final String PAUSED = EventDao.META + "paused";
    /** A scheduled night an admin skipped: {@code race.skip.<id>}. */
    static final String SKIP = EventDao.META + "skip.";
    /** Owed prizes are tried again this often while their racer is online (ticks). */
    static final long OWED_TICKS = 20L * 60 * 5;
    /** What a night called off by a stop or a restart says. */
    static final String RESTARTED = "the server restarted during Race Night";
    /** What a track problem reads when there is no track at all. */
    static final String NO_TRACK = "no track: turn on Ice Boat or set a grid";

    private final GameContext ctx;
    private final Announcer announcer = new Announcer();
    private final Watchers watchers = new Watchers();
    private final RaceBars bars = new RaceBars();
    private final Tracks tracks = new Tracks(this);
    private final EventAdmin admin = new EventAdmin(this);
    private EventDao dao;
    /** The night being run (scheduled, open, at the track), or {@code null}. */
    private NightRunner night;
    /** The last night that ended, and when: the hub shows its results for 30 minutes. */
    private NightRunner last;
    private long lastEndedAt;
    private List<EventSchedule.Entry> entries = List.of();
    /** The schedule as written when {@link #entries} was read (a reload that changes it is read again). */
    private List<String> entriesFrom = List.of();
    /** Scheduled ids already warned about (a skip is logged once). */
    private final Set<String> warned = new HashSet<>();
    /** Each online player's news toggle, read once a night. */
    private final Map<UUID, Boolean> news = new HashMap<>();
    /** Bumped whenever the hub, the feed or the screens would show something new. */
    private long version;
    private long ticks;
    /** How long the night's tick takes (for {@code event status}): a running average and the most, in ns. */
    private double tickAvgNanos;
    private long tickMaxNanos;

    public RaceNight(GameContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return SPEC.id();
    }

    @Override
    public GameKind kind() {
        return SPEC.kind();
    }

    @Override
    public String name() {
        return "Race Night";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_RACE_NIGHT;
    }

    /** Open only with its own switch on and Time Trials open (the races are Time Trials runs). */
    @Override
    public boolean configEnabled() {
        return IMPLEMENTED && settings().enabled() && trialsOpen();
    }

    @Override
    public List<String> aliases() {
        return List.of("race");
    }

    /** A night at set times is never "today's pick". */
    @Override
    public boolean featurable() {
        return false;
    }

    @Override
    public List<String> rules() {
        return List.of("Boat races for everyone at once.",
                "Three short races: every race gives points.",
                "Free to enter. The top racers win a few tokens.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        List<String> lore = new ArrayList<>();
        for (String r : rules()) {
            lore.add("&7" + r);
        }
        lore.add("&eClick to see it");
        NightRunner n = night;
        return Menus.glint(Menus.icon(Material.OAK_BOAT, tileName(), lore.toArray(new String[0])),
                n != null && n.phase() == EventMachine.Phase.OPEN);
    }

    /**
     * The tile NAME carries the state (§A.6): "&amp;6Race Night &amp;7- Fri 7:00 PM", "&amp;aRace Night
     * &amp;7- join now! 3/8", "&amp;cRace Night &amp;7- racing (race 2 of 3)".
     */
    String tileName() {
        NightRunner n = night;
        if (n != null) {
            return EventCopy.tileName(n.phase(), n.startsAt(), n.joined().size(), n.maxRacers(), n.race(),
                    n.plan().races(), zone());
        }
        EventSchedule.Occurrence next = next();
        return next == null ? "&6Race Night &7- no race set" : "&6Race Night &7- " + EventCopy.when(next.startsAt(), zone());
    }

    /**
     * The Arcade hub's Games tile gains "&amp;7- Race Night: join now!" while a join window is open
     * (§A.6); {@code ""} otherwise. Guarded, never throws.
     */
    public static String hubSuffix(GamesService games) {
        try {
            Game g = games == null ? null : games.game(SPEC.id());
            if (g instanceof RaceNight r && games.enabled(g)) {
                NightRunner n = r.night;
                if (n != null && n.phase() == EventMachine.Phase.OPEN) {
                    return " &7- Race Night: join now!";
                }
            }
        } catch (RuntimeException e) {
            // the tile reads as before
        }
        return "";
    }

    @Override
    public List<GameTile> tiles(Player viewer) {
        return List.of(new GameTile(Tab.TOGETHER, tile(viewer), id(), 0));
    }

    @Override
    public void open(Player player, Runnable back) {
        new RaceNightMenu(ctx.plugin(), this, player, back).open(player);
    }

    @Override
    public GameAdmin admin() {
        return admin;
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    /** Read the schedule, finish what a stop left (§A.9), and start the one-tick clock. */
    @Override
    public void start() {
        if (ctx.plugin() == null) {
            return;
        }
        entries();
        recover();
        games().every(this, 1, 1, this::tick);
    }

    /**
     * The game stops (switched off, a reload closed it, the server stopping). A night at the track is
     * called off at once and settles on the races done (§A.9). An open night is called off too,
     * unless the server itself is stopping: then its row stays OPEN and the next boot resumes it if
     * its start is still far enough off.
     */
    @Override
    public void stop() {
        NightRunner n = night;
        night = null;
        if (n != null && !n.phase().over()) {
            boolean stopping = stopping();
            boolean keep = stopping && (n.phase() == EventMachine.Phase.OPEN || n.phase() == EventMachine.Phase.SCHEDULED);
            if (!keep) {
                n.stopNow("&7Race Night was called off - " + (stopping ? "the server is restarting"
                        : "it was switched off") + ". Points so far count.", stopping ? RESTARTED
                        : "Race Night was switched off");
            }
        }
        bars.clear();
        watchers.clear();
        news.clear();
    }

    @Override
    public void onJoin(Player player) {
        UUID id = player.getUniqueId();
        games().later(this, 60, () -> payOwed(id, true));
        NightRunner n = night;
        if (n != null && n.in(id) && !n.phase().over()) {
            player.sendMessage(Text.of("&bYou're in tonight's Race Night &7- it starts at "
                    + EventCopy.clock(n.startsAt(), zone()) + "."));
        }
    }

    @Override
    public void onQuit(Player player) {
        UUID id = player.getUniqueId();
        bars.hide(id);
        watchers.remove(id);
        news.remove(id);
    }

    /** Every tick: the night; once a second the schedule and the bars; every 5 minutes the owed prizes. */
    private void tick() {
        ticks++;
        NightRunner n = night;
        if (n != null) {
            long t0 = System.nanoTime();
            n.tick();
            long took = System.nanoTime() - t0;
            tickAvgNanos = tickAvgNanos == 0 ? took : tickAvgNanos * 0.98 + took * 0.02;
            tickMaxNanos = Math.max(tickMaxNanos, took);
        } else if (ticks % 20 == 0) {
            schedule();
        }
        if (ticks % 10 == 0) {
            drawBars();
        }
        if (ticks % 20 == 0) {
            for (UUID gone : watchers.expire(now())) {
                bars.hide(gone);
            }
            if (last != null && now() - lastEndedAt > EventMachine.RESULTS_MS) {
                last = null;
                changed();
            }
        }
        if (ticks % OWED_TICKS == 0) {
            for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                payOwed(p.getUniqueId(), false);
            }
        }
    }

    // ---- the schedule ---------------------------------------------------------------------------

    /** Make the next scheduled night when its heads-up (or its join window) is due. */
    private void schedule() {
        if (paused()) {
            return;
        }
        RaceNightSettings s = settings();
        EventSchedule.Occurrence o = next();
        if (o == null) {
            return;
        }
        long now = now();
        long announceAt = s.announceMinutes() > 0 ? o.startsAt() - s.announceMinutes() * 60_000L : o.joinAt();
        if (now < Math.min(announceAt, o.joinAt())) {
            return;
        }
        try {
            if (dao().exists(o.id())) {
                return; // already held (or called off): never made twice
            }
        } catch (SQLException e) {
            log(Level.WARNING, "Race Night: could not check " + o.id(), e);
            return;
        }
        String course = s.autoCourse() ? RaceTrack.pick(tracks.raceable(s.minRacers(), s.maxRacers()), o.id())
                : s.course();
        Tracks.Found t = course == null ? Tracks.Found.no(NO_TRACK)
                : tracks.find(course, s.races(), s.minRacers(), s.maxRacers());
        if (t.problem() != null) {
            if (warned.add(o.id())) {
                log(Level.WARNING, "Race Night " + o.id() + " is skipped: " + t.problem(), null);
            }
            return;
        }
        NightRules rules = NightRules.of(s, t.races(), s.laps(), false,
                Math.min(s.maxRacers(), t.track().grid().size()));
        EventPlan plan = new EventPlan(o.id(), t.track().base().id(), o.joinAt(), o.startsAt(), rules, false, "");
        begin(plan, t.track(), EventMachine.State.scheduled());
    }

    /** The next scheduled night that runs (or {@code null}), as the schedule sees it now. */
    EventSchedule.Occurrence next() {
        if (ctx.plugin() == null || entries().isEmpty() || paused()) {
            return null;
        }
        return EventSchedule.next(entries(), now(), fit());
    }

    /** Every scheduled night from now to {@code days} ahead, each "fits" or skipped with why. */
    List<EventSchedule.Occurrence> upcoming(int days) {
        if (entries().isEmpty()) {
            return List.of();
        }
        long now = now();
        return EventSchedule.between(entries(), now, now + Math.max(1, days) * 86_400_000L, fit());
    }

    /**
     * The schedule's entries, bad ones dropped with one WARN each; read again when a reload changed
     * {@code games.race_night.schedule} (a night already made keeps its own times).
     */
    List<EventSchedule.Entry> entries() {
        List<String> now = settings().schedule();
        if (!now.equals(entriesFrom)) {
            entriesFrom = now;
            entries = EventSchedule.parse(now, w -> log(Level.WARNING, w, null));
        }
        return entries;
    }

    /** What the schedule's nights must fit around now: the restarts, the Fresh rebuild, a track, the skips. */
    EventSchedule.Fit fit() {
        RaceNightSettings s = settings();
        String problem = null;
        boolean fresh = false;
        TimeTrials t = trials();
        if (s.autoCourse()) {
            List<String> ids = tracks.raceable(s.minRacers(), s.maxRacers());
            if (ids.isEmpty()) {
                problem = NO_TRACK;
            }
            for (String id : ids) {
                Course c = t == null ? null : t.course(id);
                fresh |= c != null && c.generated();
            }
        } else {
            Tracks.Found f = tracks.find(s.course(), s.races(), s.minRacers(), s.maxRacers());
            problem = f.problem();
            fresh = f.track() != null && f.track().base().generated();
        }
        NightRules rules = NightRules.of(s, s.races(), s.laps(), false, s.maxRacers());
        LocalTime rebuild = fresh ? DailyLookup.edition(games()).rollover() : null;
        return new EventSchedule.Fit(zone(), s.joinMinutes(), rules.worstMillis(), games().restartHold(), rebuild,
                problem, skipped());
    }

    /** The ids an admin skipped. */
    Set<String> skipped() {
        Set<String> out = new HashSet<>();
        try {
            for (String key : dao().metaLike(SKIP).keySet()) {
                out.add(key.substring(SKIP.length()));
            }
        } catch (SQLException e) {
            log(Level.WARNING, "Race Night: could not read the skipped nights", e);
        }
        return out;
    }

    /** Whether an admin paused the schedule. */
    boolean paused() {
        try {
            return dao().meta(PAUSED) != null;
        } catch (SQLException e) {
            return false;
        }
    }

    /** Pause or resume the schedule. */
    void pause(boolean on) throws SQLException {
        dao().setMeta(PAUSED, on ? "1" : null);
        changed();
    }

    /** Start running {@code plan} on {@code track} from {@code state}. */
    NightRunner begin(EventPlan plan, NightRunner.Track track, EventMachine.State state) {
        RaceNightSettings s = settings();
        String season = s.seasonOn() ? EventCopy.seasonBoard(EventCopy.seasonKey(plan.startsAt(), zone())) : null;
        NightRunner r = new NightRunner(plan, track, dao(), new LivePorts(this, plan.id()), payLoop(), zone(), season,
                s.announceMinutes(), state);
        r.prizeWeek(DailyLookup.weekKey(games()), s.prizeEventsPerWeek());
        r.onEnd(this::ended);
        news.clear();
        night = r;
        changed();
        log(Level.INFO, "Race Night " + plan.id() + " on " + track.base().id() + " starts at "
                + EventCopy.when(plan.startsAt(), zone()) + " (" + plan.races() + " race(s))", null);
        return r;
    }

    private void ended(NightRunner r) {
        if (night == r) {
            night = null;
        }
        last = r;
        lastEndedAt = now();
        watchers.nightEnded(lastEndedAt);
        changed();
    }

    // ---- after a stop (§A.9) --------------------------------------------------------------------

    /** Finish what a stop or a crash left: resume, call off, settle or finish paying each live night. */
    void recover() {
        List<EventDao.EventRow> rows;
        try {
            rows = dao().live();
        } catch (SQLException e) {
            log(Level.SEVERE, "Race Night: could not read the nights a stop left", e);
            return;
        }
        long now = now();
        RaceNightSettings s = settings();
        NightRules fallback = NightRules.of(s, s.races(), s.laps(), false, s.maxRacers());
        for (EventDao.EventRow row : rows) {
            try {
                recover(row, NightRules.decode(row.settings(), fallback), now);
            } catch (SQLException | RuntimeException e) {
                log(Level.SEVERE, "Race Night: could not finish " + row.id() + " after a stop", e);
            }
        }
    }

    private void recover(EventDao.EventRow row, NightRules rules, long now) throws SQLException {
        EventMachine.Boot boot = EventMachine.boot(row.state(), row.startsAt(), row.racesDone(), now);
        switch (boot) {
            case NOTHING -> {
                // over, or never opened
            }
            case RESUME -> {
                Tracks.Found t = night == null && EventPlan.isId(row.id())
                        ? tracks.find(row.course(), rules.races(), rules.minRacers(), rules.maxRacers())
                        : Tracks.Found.no("another night is on");
                if (t.problem() != null) {
                    callOffStored(row, "its track can't be raced now (" + t.problem() + ")", now);
                    return;
                }
                EventPlan plan = new EventPlan(row.id(), row.course(), row.joinAt(), row.startsAt(), rules,
                        EventPlan.adminId(row.id()), row.madeBy());
                NightRunner r = begin(plan, t.track(), EventMachine.State.open(now));
                r.restore(dao().entries(row.id()));
                log(Level.INFO, "Race Night " + row.id() + " resumed after a restart with " + r.joined().size()
                        + " racer(s)", null);
            }
            case CALL_OFF -> callOffStored(row, RESTARTED, now);
            case CALL_OFF_SETTLE -> {
                StoredNight.settle(dao(), row, rules, "called off: " + RESTARTED, now);
                int owed = payLoop().payNight(row.id());
                tellEntrants(row.id(), "&7Race Night was called off - " + RESTARTED + ". The races you finished "
                        + "still count" + (owed > 0 ? ", and prizes are paid when you're back in a world with tokens."
                        : "."));
                log(Level.INFO, "Race Night " + row.id() + " called off after a restart; settled on "
                        + row.racesDone() + " race(s)", null);
            }
            case FINISH_PAYING -> {
                payLoop().payNight(row.id());
                boolean calledOff = row.note() != null && row.note().startsWith("called off");
                dao().setState(row.id(), calledOff ? EventDao.CALLED_OFF : EventDao.DONE, "", now);
                log(Level.INFO, "Race Night " + row.id() + " finished paying after a restart", null);
            }
        }
    }

    private void callOffStored(EventDao.EventRow row, String why, long now) throws SQLException {
        dao().setState(row.id(), EventDao.CALLED_OFF, "called off: " + why, now);
        tellEntrants(row.id(), "&7Race Night was called off - " + why + ". See you next time!");
        log(Level.INFO, "Race Night " + row.id() + " called off: " + why, null);
    }

    private void tellEntrants(String eventId, String line) throws SQLException {
        for (EventDao.EntryRow e : dao().entries(eventId)) {
            games().notice(e.player(), line, true);
        }
    }

    // ---- prizes ---------------------------------------------------------------------------------

    /** The pay loop over the live rewards (EVENT_PRIZE, outside the skill cap, ref {@code event:<id>}). */
    PayLoop payLoop() {
        return new PayLoop(dao(), new PayLoop.Payer() {
            @Override
            public int pay(UUID player, String ref, int tokens, String detail) {
                Player p = Bukkit.getPlayer(player);
                if (p == null || !games().rewards().canEarnHere(p)) {
                    return -1;
                }
                int paid = games().rewards().pay(p, RaceNight.this, source(), RewardKind.EVENT_PRIZE, ref, tokens, -1,
                        detail);
                return paid > 0 ? paid : paid(player, ref) ? 0 : -1;
            }

            @Override
            public boolean paid(UUID player, String ref) {
                try {
                    return games().dao().rewardPaid(player, id(), RewardKind.EVENT_PRIZE, ref);
                } catch (SQLException e) {
                    return false;
                }
            }
        }, this::now, logger());
    }

    /** Pay what the player is owed from past nights; tell them once if some has to wait. */
    private void payOwed(UUID player, boolean tellIfWaiting) {
        int owed = payLoop().payOwed(player);
        if (owed > 0 && tellIfWaiting) {
            Player p = Bukkit.getPlayer(player);
            if (p != null) {
                p.sendMessage(Text.of(PayLoop.WAITING));
            }
        }
    }

    // ---- players --------------------------------------------------------------------------------

    /** The Join button: why not, or {@code null} when they are in. */
    public String join(Player player) {
        NightRunner n = night;
        if (n == null || n.phase().over()) {
            EventSchedule.Occurrence o = next();
            return o == null ? "There's no Race Night set yet." : "Joining opens at "
                    + EventCopy.clock(o.joinAt(), zone()) + " on " + EventCopy.when(o.startsAt(), zone()) + ".";
        }
        String why = n.join(player.getUniqueId(), player.getName());
        if (why == null) {
            changed();
        }
        return why;
    }

    /** The Leave button: off the list before the racing, out for good after. */
    public void leave(Player player) {
        NightRunner n = night;
        if (n != null) {
            n.leave(player.getUniqueId());
            changed();
        }
    }

    /** The Watch button. @return whether they are watching now */
    public boolean watch(Player player) {
        boolean on = watchers.toggle(player.getUniqueId());
        if (!on) {
            bars.hide(player.getUniqueId());
        }
        return on;
    }

    /** Whether the player is watching from anywhere. */
    public boolean watching(UUID player) {
        return watchers.watching(player);
    }

    /** The news toggle, cached for the night. */
    public boolean newsOn(UUID player) {
        return news.computeIfAbsent(player, id -> {
            try {
                return !"off".equalsIgnoreCase(games().dao().pref(id, NewCoursesNudge.PREF_NEWS));
            } catch (SQLException e) {
                return false;
            }
        });
    }

    /** The screen's news bell: turn Race Night news (and the other game news) on or off. */
    public void setNews(UUID player, boolean on) {
        try {
            games().dao().setPref(player, NewCoursesNudge.PREF_NEWS, on ? "on" : "off");
        } catch (SQLException e) {
            log(Level.WARNING, "Race Night: could not save a player's news setting", e);
        }
        news.put(player, on);
        if (!on && !watchers.watching(player)) {
            bars.hide(player);
        }
    }

    /** The join window's bar for everyone with news on; the watchers' bar; nothing for anyone else. */
    private void drawBars() {
        NightRunner n = night;
        long now = now();
        boolean open = n != null && n.phase() == EventMachine.Phase.OPEN;
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            UUID id = p.getUniqueId();
            NightRunner.Racer r = n == null ? null : n.racer(id);
            if (r != null && r.seated()) {
                continue; // the night draws a racer's own bar
            }
            if (watchers.watching(id)) {
                NightRunner shown = n != null ? n : last;
                bars.show(id, shown == null ? "&bRace Night" : Watchers.bar(shown.phase(), shown.race(),
                        shown.plan().races(), shown.leader(), shown.joined().size()), 1f, RaceBars.Tone.WATCH);
            } else if (open && newsOn(id)) {
                long window = Math.max(1, n.startsAt() - n.plan().joinAt());
                bars.show(id, EventCopy.joinBar(n.startsAt() - now, n.joined().size()),
                        (n.startsAt() - now) / (float) window, RaceBars.Tone.JOIN);
            } else if (bars.has(id)) {
                bars.hide(id);
            }
        }
    }

    // ---- admin ----------------------------------------------------------------------------------

    /**
     * {@code /hcm games event start}: a join window now (or in {@code inMinutes}), starting
     * {@code admin_join_minutes} after it opens. Refused while another night is on, near a restart,
     * with Race Night or Time Trials closed, or on a track that can't be raced.
     *
     * @return why not, or {@code null} when it is on
     */
    String adminStart(String by, String course, Integer races, Integer laps, int inMinutes, boolean fun) {
        RaceNightSettings s = settings();
        long now = now();
        long joinAt = now + Math.max(0, inMinutes) * 60_000L;
        long startsAt = joinAt + s.adminJoinMinutes() * 60_000L;
        String pick = course != null ? course : s.autoCourse()
                ? RaceTrack.pick(tracks.raceable(s.minRacers(), s.maxRacers()), "admin-" + now) : s.course();
        int wantRaces = races == null ? s.races() : races;
        Tracks.Found t = pick == null ? Tracks.Found.no(NO_TRACK)
                : tracks.find(pick, wantRaces, s.minRacers(), s.maxRacers());
        int wantLaps = laps == null ? s.laps() : laps;
        String lapsProblem = t.track() == null ? null : RaceTrack.lapsProblem(t.track().base(), wantLaps);
        NightRules rules = NightRules.of(s, t.track() == null ? wantRaces : t.races(), wantLaps, fun,
                t.track() == null ? s.maxRacers() : Math.min(s.maxRacers(), t.track().grid().size()));
        EventSchedule.Occurrence next = next();
        String clash = next != null && next.joinAt() < startsAt + rules.worstMillis()
                && next.startsAt() + rules.worstMillis() > joinAt
                ? "the scheduled night at " + EventCopy.when(next.startsAt(), zone()) + " is too close" : null;
        NightRunner n = night;
        String problem = EventAdmin.startProblem(n != null && !n.phase().over(), s.enabled(), trialsOpen(),
                t.problem() != null ? t.problem() : lapsProblem,
                EventSchedule.restartProblem(joinAt, startsAt, rules.worstMillis(), games().restartHold()), clash);
        if (problem != null) {
            return problem;
        }
        EventPlan plan = new EventPlan(adminId(joinAt), t.track().base().id(), joinAt, startsAt, rules, true, by);
        begin(plan, t.track(), EventMachine.State.scheduled()).step(); // the window opens now (or when due)
        return null;
    }

    /** A fresh admin night id for a night whose window opens at {@code joinAt}: never one used before. */
    private String adminId(long joinAt) {
        LocalDateTime at = LocalDateTime.ofInstant(Instant.ofEpochMilli(joinAt), zone());
        for (int n = 1; n < 1000; n++) {
            String id = EventPlan.adminId(at.toLocalDate(), at.toLocalTime(), n);
            try {
                if (!dao().exists(id)) {
                    return id;
                }
            } catch (SQLException e) {
                return id;
            }
        }
        return EventPlan.adminId(at.toLocalDate(), at.toLocalTime(), 999);
    }

    // ---- what the admin tool, the screens, the hub and the feed read ------------------------------

    /** The night on now (scheduled, open or at the track), or {@code null}. */
    public NightRunner night() {
        return night;
    }

    /** The last night that ended in the last 30 minutes, or {@code null}. */
    public NightRunner last() {
        return last;
    }

    Watchers watchers() {
        return watchers;
    }

    RaceBars bars() {
        return bars;
    }

    Announcer announcer() {
        return announcer;
    }

    Tracks tracks() {
        return tracks;
    }

    /** Something shown changed: the hub redraws within a second. */
    void changed() {
        version++;
    }

    /** Bumped on every change the hub or the feed would show (the hub redraws at most once a second). */
    public long version() {
        return version;
    }

    /** The {@code @event} display's view now (§A.6). */
    public EventBoard.View board() {
        long now = now();
        ZoneId zone = zone();
        NightRunner n = night;
        if (n != null) {
            String track = n.track().name();
            return switch (n.phase()) {
                case SCHEDULED -> new EventBoard.View(EventBoard.Shows.UPCOMING, now, n.startsAt(), track, 0,
                        n.maxRacers(), 0, n.plan().races(), List.of(), zone);
                case OPEN -> new EventBoard.View(EventBoard.Shows.OPEN, now, n.startsAt(), track, n.joined().size(),
                        n.maxRacers(), 0, n.plan().races(), List.of(), zone);
                case WARMUP -> new EventBoard.View(EventBoard.Shows.WARMUP, now, n.startsAt(), track,
                        n.joined().size(), n.maxRacers(), 0, n.plan().races(), List.of(), zone);
                default -> new EventBoard.View(n.phase().over() ? EventBoard.Shows.RESULTS : EventBoard.Shows.RACING,
                        now, n.startsAt(), track, n.joined().size(), n.maxRacers(), Math.max(1, n.race()),
                        n.plan().races(), lines(n), zone);
            };
        }
        NightRunner l = last;
        if (l != null && l.racesDone() > 0) {
            return new EventBoard.View(EventBoard.Shows.RESULTS, now, l.startsAt(), l.track().name(),
                    l.joined().size(), l.maxRacers(), l.racesDone(), l.plan().races(), lines(l), zone);
        }
        EventSchedule.Occurrence o = next();
        if (o == null) {
            return EventBoard.View.nothing(now, zone);
        }
        return new EventBoard.View(EventBoard.Shows.UPCOMING, now, o.startsAt(), nextTrackName(o), 0,
                settings().maxRacers(), 0, settings().races(), List.of(), zone);
    }

    private static List<EventBoard.Line> lines(NightRunner n) {
        List<EventBoard.Line> out = new ArrayList<>();
        for (NightStandings.Ranked s : n.standings()) {
            NightRunner.Racer r = n.racer(s.player());
            out.add(new EventBoard.Line(s.place(), r == null ? null : r.name(), s.points()));
        }
        return out;
    }

    /** The track a scheduled night will race on, by name (auto: its pick, or "a boat track"). */
    String nextTrackName(EventSchedule.Occurrence o) {
        Course c = nextTrack(o);
        return c == null ? "a boat track" : c.name();
    }

    /** The track a scheduled night will race on, or {@code null}. */
    Course nextTrack(EventSchedule.Occurrence o) {
        RaceNightSettings s = settings();
        String id = s.autoCourse() ? RaceTrack.pick(tracks.raceable(s.minRacers(), s.maxRacers()), o.id()) : s.course();
        TimeTrials t = trials();
        return t == null || id == null ? null : t.course(id);
    }

    /** The season board now ({@code rnseason:2026-10}), or {@code null} with the season off. */
    public String seasonBoard() {
        return settings().seasonOn() ? EventCopy.seasonBoard(EventCopy.seasonKey(now(), zone())) : null;
    }

    /** The player's points this season. */
    public long seasonPoints(UUID player) {
        String b = seasonBoard();
        Long p = b == null ? null : games().scores().best(player, id(), b);
        return p == null ? 0 : p;
    }

    /** The last night's board ({@code rnnight:<id>}), or {@code null} before the first night. */
    public String lastBoard() {
        List<EventDao.EventRow> r = recent(1);
        return r.isEmpty() ? null : EventCopy.nightBoard(r.get(0).id());
    }

    /** The last few nights that ended, newest first. */
    List<EventDao.EventRow> recent(int n) {
        try {
            return dao().recent(n);
        } catch (SQLException e) {
            return List.of();
        }
    }

    /** A stored night's entries, in join order. */
    List<EventDao.EntryRow> entries(String eventId) {
        try {
            return dao().entries(eventId);
        } catch (SQLException e) {
            return List.of();
        }
    }

    /** The winner of a stored night by name, or {@code null}. */
    String winner(String eventId) {
        for (EventDao.EntryRow e : entries(eventId)) {
            if (e.place() != null && e.place() == 1 && e.points() > 0) {
                return e.name();
            }
        }
        return null;
    }

    /** A course's name, or its id when it is gone. */
    String courseName(String courseId) {
        TimeTrials t = trials();
        Course c = t == null ? null : t.course(courseId);
        return c == null ? courseId : c.name();
    }

    /** "0.12 ms average, 1.40 ms at most": the night's tick, for {@code event status}. */
    String tickStats() {
        return String.format(Locale.ROOT, "%.2f ms average, %.2f ms at most", tickAvgNanos / 1e6, tickMaxNanos / 1e6);
    }

    /** What the Race Night screen shows the viewer now. */
    public RaceNightMenu.View view(Player viewer) {
        UUID id = viewer.getUniqueId();
        RaceNightSettings s = settings();
        ZoneId zone = zone();
        NightRunner n = night;
        String when = null;
        String state = null;
        String track = null;
        int races = s.races();
        int laps = s.laps();
        List<Integer> prizes = s.prizes();
        int finisher = s.finisherPrize();
        boolean prizeNight = s.prizeEventsPerWeek() > prizedThisWeek();
        RaceNightMenu.Join join = RaceNightMenu.Join.NONE;
        int racers = 0;
        int max = s.maxRacers();
        String opensAt = null;
        if (n != null && !n.phase().over()) {
            when = EventCopy.when(n.startsAt(), zone);
            track = n.track().name();
            races = n.plan().races();
            laps = n.laps();
            prizes = n.plan().rules().prizes();
            finisher = n.plan().rules().finisherPrize();
            prizeNight = n.prizeNight() && (n.started() >= 0 || prizeNight);
            racers = n.joined().size();
            max = n.maxRacers();
            opensAt = EventCopy.clock(n.plan().joinAt(), zone);
            state = switch (n.phase()) {
                case SCHEDULED -> "Joining opens at " + opensAt + ".";
                case OPEN -> "Joining is open: " + EventCopy.racers(racers) + " in so far.";
                case WARMUP -> "Warm-up laps are on.";
                case GRID, RACING -> "Race " + Math.max(1, n.race()) + " of " + races + " is on.";
                case BREAK -> "A break after race " + n.race() + " of " + races + ".";
                default -> null;
            };
            String problem = n.joinProblem(id);
            join = n.in(id) ? RaceNightMenu.Join.IN : n.phase() == EventMachine.Phase.SCHEDULED ? RaceNightMenu.Join.SOON
                    : problem == null ? RaceNightMenu.Join.OPEN : problem.contains("full") ? RaceNightMenu.Join.FULL
                    : RaceNightMenu.Join.STARTED;
        } else {
            EventSchedule.Occurrence o = next();
            if (o != null) {
                when = EventCopy.when(o.startsAt(), zone);
                Course c = nextTrack(o);
                track = c == null ? null : c.name();
                laps = c == null ? laps : RaceTrack.laps(c, laps);
                opensAt = EventCopy.clock(o.joinAt(), zone) + " (" + EventCopy.when(o.startsAt(), zone) + ")";
                join = RaceNightMenu.Join.SOON;
            }
        }
        EventDao.EventRow lastRow = recent(1).stream().findFirst().orElse(null);
        String board = seasonBoard();
        String seasonName = board == null ? null : EventCopy.seasonName(board.substring(EventCopy.SEASON_PREFIX.length()));
        return new RaceNightMenu.View(when, state, track, races, laps, prizes, finisher, prizeNight, join, racers, max,
                opensAt, watchers.watching(id), lastRow == null ? null : winner(lastRow.id()),
                lastRow == null ? null : EventCopy.nightBoard(lastRow.id()), seasonName, board, seasonPoints(id),
                newsOn(id));
    }

    /** Race Night's {@code events} section of the website feed (§A.7). */
    @Override
    public void feed(FeedWriter out) {
        out.events(EventFeed.events(this, out.showNames()));
    }

    /**
     * {@code /hcm games status}: "open · next Fri 2 Oct 7:00 PM on Ice Boat (3 races) · fits before
     * the 4:00 AM restart · prize nights 1/3 this week".
     */
    @Override
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        NightRunner n = night;
        RestartHold hold = games().restartHold();
        String prizes = "prize nights " + prizedThisWeek() + "/" + settings().prizeEventsPerWeek() + " this week";
        if (n != null) {
            out.add(n.phase().name().toLowerCase(Locale.ROOT) + " · " + n.plan().id() + " on " + n.track().name()
                    + " (" + EventCopy.format(n.plan().races(), n.laps()) + ") · " + EventCopy.racers(n.joined().size())
                    + " · " + prizes);
        } else {
            EventSchedule.Occurrence o = next();
            long restart = hold.off() ? -1 : hold.next(now());
            out.add((paused() ? "paused" : "waiting") + " · " + (o == null ? "no night set"
                    : "next " + EventCopy.when(o.startsAt(), zone()) + " on " + nextTrackName(o) + " ("
                    + settings().races() + (settings().races() == 1 ? " race)" : " races)")
                    + (restart > 0 ? " · fits before the " + hold.clock(restart) + " restart" : ""))
                    + " · " + prizes);
        }
        return out;
    }

    /** One {@code /hcm games check} line: fine ({@code fix} {@code null}), or a warning with its fix. */
    public record Check(String what, String fix) {
    }

    /** Whether {@code games.race_night.enabled} is on (Race Night may still be closed with Time Trials off). */
    public boolean switchedOn() {
        return settings().enabled();
    }

    /**
     * E1's rows (§A.11): the schedule reads and fits the restarts, the track can be raced (its grid
     * and its stand), and the stand is where the course is.
     */
    public List<Check> check() {
        List<Check> out = new ArrayList<>();
        RaceNightSettings s = settings();
        if (!trialsOpen()) {
            out.add(new Check("Race Night needs Time Trials, which is closed", "open games.trials, then /hcm reload"));
        }
        List<String> written = s.schedule();
        List<EventSchedule.Entry> read = entries();
        if (written.isEmpty()) {
            out.add(new Check("Race Night has no schedule: nights only when an admin starts one", null));
        } else if (read.size() < written.size()) {
            out.add(new Check("Race Night: " + (written.size() - read.size()) + " schedule entr"
                    + (written.size() - read.size() == 1 ? "y" : "ies") + " can't be read",
                    "write each like \"FRI 19:00\" or \"SAT,SUN 15:00\" in games.race_night.schedule"));
        } else {
            out.add(new Check("Race Night's schedule reads: " + String.join(", ", written), null));
        }
        for (EventSchedule.Occurrence o : upcoming(7)) {
            out.add(o.fits() ? new Check("Race Night " + EventCopy.when(o.startsAt(), zone()) + " fits", null)
                    : new Check("Race Night " + EventCopy.when(o.startsAt(), zone()) + " is skipped: " + o.skip(),
                    o.skip().startsWith("A restart") ? "move it in games.race_night.schedule, or the restart"
                            : o.skip().startsWith("no track") ? "turn on Ice Boat (games.fresh.slots.fresh_boat) or set a grid"
                            : "see /hcm games event list"));
        }
        List<String> ids = s.autoCourse() ? tracks.raceable(s.minRacers(), s.maxRacers()) : List.of(s.course());
        if (ids.isEmpty()) {
            out.add(new Check("Race Night has no track it can race on",
                    "turn on the Ice Boat Fresh course, or set a boat course's grid: /hcm games event grid <course> auto"));
        }
        for (String id : ids) {
            Tracks.Found f = tracks.find(id, s.races(), s.minRacers(), s.maxRacers());
            if (f.problem() != null) {
                out.add(new Check("Race Night can't race on " + id + ": " + f.problem(),
                        "/hcm games event grid " + id + " auto, or pick another course"));
                continue;
            }
            Course c = f.track().base();
            out.add(new Check("Race Night can race on " + c.name() + ": " + f.track().grid().size() + " grid spots", null));
            if (f.track().stand() == null && s.races() > 1) {
                out.add(new Check(c.name() + " has no viewing stand, so nights there are 1 race",
                        c.generated() ? "it comes with the next Ice Boat layout" : "/hcm games event stand " + c.id() + " set"));
            } else if (f.track().stand() != null) {
                out.add(new Check(c.name() + "'s viewing stand is in " + c.world() + ", with the course", null));
            }
        }
        return out;
    }

    /** Prize nights used this week. */
    int prizedThisWeek() {
        try {
            return dao().prizedIn(Long.toString(DailyLookup.weekKey(games())));
        } catch (SQLException e) {
            return 0;
        }
    }

    // ---- plumbing -------------------------------------------------------------------------------

    /** The live settings (read on every use, never cached across a reload). */
    RaceNightSettings settings() {
        return ctx.games().settings(SPEC);
    }

    public GamesService games() {
        return ctx.games();
    }

    HomeCraftManagement plugin() {
        return ctx.plugin();
    }

    /** Time Trials, or {@code null} when it isn't there. */
    TimeTrials trials() {
        Game g = ctx.games().game(TimeTrials.SPEC.id());
        return g instanceof TimeTrials t ? t : null;
    }

    /** Whether Time Trials is open (Race Night needs it). */
    boolean trialsOpen() {
        Game g = ctx.games().game(TimeTrials.SPEC.id());
        return g != null && ctx.games().enabled(g);
    }

    EventDao dao() {
        if (dao == null) {
            dao = new EventDao(ctx.plugin().database(), games().dao());
        }
        return dao;
    }

    long now() {
        return games().clock().nowMillis();
    }

    ZoneId zone() {
        return games().clock().zone();
    }

    private static boolean stopping() {
        try {
            return Bukkit.isStopping();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    Logger logger() {
        HomeCraftManagement p = ctx.plugin();
        return p != null ? p.getLogger() : Logger.getLogger("HomeCraftManagement");
    }

    void log(Level level, String line, Throwable cause) {
        logger().log(level, line, cause);
    }
}
