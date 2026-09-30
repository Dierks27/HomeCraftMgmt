package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.QuestType;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameCatalog;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameProgress;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.gen.api.Slots;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Where the skill games' finishes become quests and achievements (EXTRAS E4): the one
 * {@link GameProgress} listener, registered with the Games when the plugin enables.
 *
 * <p>Each finish becomes at most one quest step and a few achievement counters:
 * <ul>
 *   <li>a finished cabinet run (practice included) is one {@link QuestType#FINISH_CABINET} and one
 *       {@value #CABINET_FINISHES}; a gold medal on a scored run is one {@value #CABINET_GOLDS}; the
 *       first finish of each cabinet game is one {@value #CABINETS} (distinct cabinets);</li>
 *   <li>a counted time-trial run or a finished round of golf is one {@link QuestType#FINISH_COURSE}
 *       and one {@value #COURSE_FINISHES}; a course record (a time trial's or a golf course's) is one
 *       {@value #COURSE_RECORDS}; a round
 *       with a hole-in-one adds its holes-in-one to {@value #HOLES_IN_ONE}, and a round under par is
 *       one {@value #UNDER_PAR};</li>
 *   <li>Fresh Courses stars are {@link QuestType#EARN_STARS} (one step a star); finishing every
 *       course of a set is one {@value #FRESH_SETS}; the week's top Star Chart goal is one
 *       {@value #STAR_CHART_TOPS};</li>
 *   <li>a counted Dropper run with no bonks is one {@value #DROPPER_CLEAN} (its finish is a course
 *       finish as for any trial);</li>
 *   <li>a Race Night raced is one {@value #RACE_NIGHTS}, and a Race Night won one
 *       {@value #RACE_NIGHT_WINS} (EVENTS-DROPPER-SPEC §A.8);</li>
 *   <li>lasting a whole minute in a Falling Floors round is one {@value #FLOORS_MINUTES}.</li>
 * </ul>
 *
 * <p><b>Why counters.</b> Courses are played in the Games world, which is not an economy world, so
 * no tokens are paid there. An EVENT achievement fired there could never unlock; a counter is kept
 * wherever it happened and unlocks at the next sweep back home. Quests work the same way
 * ({@link QuestService#settle}).
 *
 * <p><b>Where it counts.</b> Only where the games pay tokens ({@link #countsHere}: an economy world,
 * a Games world or a play world, and never in creative or spectator), the games' own rule. The one
 * exception is Race Night, which settles after the racing: it is counted by id, wherever the racer
 * is when it settles ({@link #raceNightFinished(UUID, boolean)}).
 *
 * <p><b>Never a game of chance.</b> The games of chance never call {@link GameProgress}; as a second
 * lock, a cabinet finish is taken only from a game the catalog lists as a {@link GameKind#CABINET}.
 * Nothing here can reward playing a game of chance.
 */
public final class GamesProgress implements GameProgress {

    /** Cabinet runs finished (practice included). */
    public static final String CABINET_FINISHES = "cabinet_finishes";
    /** Gold medals on scored cabinet runs. */
    public static final String CABINET_GOLDS = "cabinet_golds";
    /** Different cabinet games finished at least once. */
    public static final String CABINETS = "cabinets";
    /** A marker counter per cabinet game ({@code cabinet:snake}): its first finish counts toward {@value #CABINETS}. */
    public static final String CABINET_MARK = "cabinet:";
    /** Counted time-trial runs and finished golf rounds. */
    public static final String COURSE_FINISHES = "course_finishes";
    /** Course records set. */
    public static final String COURSE_RECORDS = "course_records";
    /** Holes-in-one. */
    public static final String HOLES_IN_ONE = "holes_in_one";
    /** Golf rounds finished under par. */
    public static final String UNDER_PAR = "golf_under_par";
    /** Fresh Courses sets with every course finished. */
    public static final String FRESH_SETS = "fresh_sets";
    /** Weeks the top Star Chart goal was reached. */
    public static final String STAR_CHART_TOPS = "star_chart_tops";
    /** Counted Dropper runs with no bonks (EVENTS-DROPPER-SPEC §B.1.8). */
    public static final String DROPPER_CLEAN = "dropper_clean";
    /** Race Nights raced (EVENTS-DROPPER-SPEC §A.8). */
    public static final String RACE_NIGHTS = "race_nights";
    /** Race Nights won. */
    public static final String RACE_NIGHT_WINS = "race_night_wins";
    /** Falling Floors rounds lasted a whole minute (EVENTS-DROPPER-SPEC §B.3.4). */
    public static final String FLOORS_MINUTES = "floors_minutes";

    /** Every counter the "Games" achievements read. */
    public static final List<String> COUNTERS = List.of(CABINET_FINISHES, CABINET_GOLDS, CABINETS, COURSE_FINISHES,
            COURSE_RECORDS, HOLES_IN_ONE, UNDER_PAR, FRESH_SETS, STAR_CHART_TOPS, DROPPER_CLEAN, RACE_NIGHTS,
            RACE_NIGHT_WINS, FLOORS_MINUTES);

    /** Where the finishes go: the live quests and achievements, or a test's fake. */
    public interface Sink {

        /** Whether the player's finishes count where they are ({@link #countsHere}). */
        boolean countsHere(Player player);

        /** Whether {@code gameId} is an arcade cabinet (a skill game, never one of chance). */
        boolean cabinet(String gameId);

        /** A step toward the player's dealt quests of {@code type}. */
        void quest(Player player, QuestType type, long amount);

        /** Add to an achievement counter. */
        void count(Player player, String counter, long by);

        /** True only the first time ever the player does {@code marker}. */
        boolean firstTime(Player player, String marker);

        /** Add to an achievement counter by player id, online or not. */
        default void count(UUID player, String counter, long by) {
        }
    }

    private final Sink sink;

    /** The live listener: the plugin's quests and achievements. */
    public GamesProgress(HomeCraftManagement plugin) {
        this(new LiveSink(plugin));
    }

    GamesProgress(Sink sink) {
        this.sink = sink;
    }

    @Override
    public void cabinetFinished(Player player, String gameId, boolean practice, boolean goldMedal) {
        String id = gameId == null ? "" : gameId.trim().toLowerCase(Locale.ROOT);
        if (player == null || id.isEmpty() || !sink.cabinet(id) || !sink.countsHere(player)) {
            return;
        }
        sink.quest(player, QuestType.FINISH_CABINET, 1);
        sink.count(player, CABINET_FINISHES, 1);
        if (goldMedal && !practice) {
            sink.count(player, CABINET_GOLDS, 1); // practice records nothing, so it has no medal
        }
        if (sink.firstTime(player, CABINET_MARK + id)) {
            sink.count(player, CABINETS, 1);
        }
    }

    @Override
    public void courseFinished(Player player, String courseId, boolean fresh, boolean record) {
        if (player == null || !sink.countsHere(player)) {
            return;
        }
        sink.quest(player, QuestType.FINISH_COURSE, 1);
        sink.count(player, COURSE_FINISHES, 1);
        if (record) {
            sink.count(player, COURSE_RECORDS, 1);
        }
    }

    @Override
    public void golfFinished(Player player, String courseId, int strokes, int par, int holesInOne, boolean fresh) {
        golfFinished(player, courseId, strokes, par, holesInOne, fresh, false);
    }

    @Override
    public void golfFinished(Player player, String courseId, int strokes, int par, int holesInOne, boolean fresh,
                             boolean record) {
        if (player == null || !sink.countsHere(player)) {
            return;
        }
        sink.quest(player, QuestType.FINISH_COURSE, 1);
        sink.count(player, COURSE_FINISHES, 1);
        if (record) {
            sink.count(player, COURSE_RECORDS, 1); // a golf course's record is a course record too
        }
        if (holesInOne > 0) {
            sink.count(player, HOLES_IN_ONE, holesInOne);
        }
        if (underPar(strokes, par)) {
            sink.count(player, UNDER_PAR, 1);
        }
    }

    @Override
    public void dropperClean(Player player, String courseId) {
        if (player == null || !sink.countsHere(player)) {
            return;
        }
        sink.count(player, DROPPER_CLEAN, 1);
    }

    @Override
    public void floorsLastedMinute(Player player) {
        if (player == null || !sink.countsHere(player)) {
            return;
        }
        sink.count(player, FLOORS_MINUTES, 1);
    }

    @Override
    public void starsEarned(Player player, int stars) {
        if (player == null || stars <= 0 || !sink.countsHere(player)) {
            return;
        }
        sink.quest(player, QuestType.EARN_STARS, stars);
    }

    @Override
    public void freshSetFinished(Player player, String set) {
        if (player == null || !sink.countsHere(player)) {
            return;
        }
        sink.count(player, FRESH_SETS, 1);
    }

    @Override
    public void starChartTopGoal(Player player, long week) {
        if (player == null || !sink.countsHere(player)) {
            return;
        }
        sink.count(player, STAR_CHART_TOPS, 1);
    }

    /** {@link #raceNightFinished(UUID, boolean)} for the player's id. */
    @Override
    public void raceNightFinished(Player player, boolean won) {
        if (player != null) {
            raceNightFinished(player.getUniqueId(), won);
        }
    }

    /**
     * A Race Night raced (and won), counted by id wherever the racer is now (fx2-C #6). The night
     * settles after its last race, or at the next boot from its stored rows, and it has already said
     * who raced it (a racer who started a race of it, in a Games world, in adventure): a racer who is
     * watching the others live in spectator then, is offline or went home still raced it. So
     * {@link #countsHere}, which is about where a finish happens, isn't asked; the count is kept, and
     * what it reaches unlocks at once where tokens are paid, else at the next sweep back home.
     */
    @Override
    public void raceNightFinished(UUID player, boolean won) {
        if (player == null) {
            return;
        }
        sink.count(player, RACE_NIGHTS, 1);
        if (won) {
            sink.count(player, RACE_NIGHT_WINS, 1);
        }
    }

    /** A whole round finished in fewer strokes than its par (a round with no par is never under). */
    static boolean underPar(int strokes, int par) {
        return par > 0 && strokes > 0 && strokes < par;
    }

    // ---- the rules the quests share ------------------------------------------------------------

    /**
     * Whether a skill-game finish counts where the player is: where the games pay tokens (an economy
     * world, a Games world or a play world), and never in creative or spectator. False with the
     * games missing.
     */
    public static boolean countsHere(GamesService games, Player player) {
        if (games == null || player == null) {
            return false;
        }
        try {
            return games.rewards().canEarnHere(player);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether a game quest of {@code type} can be done right now, so it may be dealt: a cabinet is
     * open (FINISH_CABINET), a time trial or golf game with something to play is open
     * (FINISH_COURSE), Fresh Courses is open (EARN_STARS). Any other type: true.
     */
    public static boolean dealable(GamesService games, QuestType type) {
        if (type == null || !type.game()) {
            return true;
        }
        if (games == null) {
            return false;
        }
        try {
            if (!games.config().enabled()) {
                return false;
            }
            for (Game g : games.games()) {
                if (!games.enabled(g)) {
                    continue;
                }
                switch (type) {
                    case FINISH_CABINET -> {
                        if (g.kind() == GameKind.CABINET) {
                            return true;
                        }
                    }
                    case FINISH_COURSE -> {
                        if ((g.kind() == GameKind.TRIAL || g.kind() == GameKind.GOLF)
                                && !games.guard(g, g::playables, List.<Game.Playable>of()).isEmpty()) {
                            return true;
                        }
                    }
                    case EARN_STARS -> {
                        if (Slots.DAILY.equals(g.id())) {
                            return true;
                        }
                    }
                    default -> {
                        // not a game quest
                    }
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    /** Whether the catalog lists {@code gameId} as an arcade cabinet (never a game of chance). */
    public static boolean isCabinet(String gameId) {
        GameSpec<?> spec = GameCatalog.spec(gameId);
        return spec != null && spec.kind() == GameKind.CABINET;
    }

    /** The plugin's quests and achievements. */
    private static final class LiveSink implements Sink {
        private final HomeCraftManagement plugin;

        LiveSink(HomeCraftManagement plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean countsHere(Player player) {
            return GamesProgress.countsHere(plugin.games(), player);
        }

        @Override
        public boolean cabinet(String gameId) {
            return isCabinet(gameId);
        }

        @Override
        public void quest(Player player, QuestType type, long amount) {
            if (plugin.quests() != null) {
                plugin.quests().record(player, type, amount);
            }
        }

        @Override
        public void count(Player player, String counter, long by) {
            if (plugin.achievements() != null) {
                plugin.achievements().count(player, counter, by);
            }
        }

        @Override
        public boolean firstTime(Player player, String marker) {
            return plugin.achievements() != null && plugin.achievements().firstTime(player, marker);
        }

        /**
         * Online: as {@link #count(Player, String, long)} (it unlocks now where tokens are paid, else at
         * the next sweep). Offline: the count is kept, and their join's sweep unlocks what it reaches.
         */
        @Override
        public void count(UUID player, String counter, long by) {
            if (player == null || counter == null || by <= 0 || plugin.achievements() == null) {
                return;
            }
            Player online = plugin.getServer().getPlayer(player);
            if (online != null) {
                count(online, counter, by);
                return;
            }
            try {
                new com.dierks.homecraft.storage.AchievementDao(plugin.database()).addCounter(player, counter, by);
            } catch (java.sql.SQLException | RuntimeException e) {
                plugin.getLogger().warning("Failed to count '" + counter + "' for an offline player: " + e.getMessage());
            }
        }
    }
}
