package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;

/**
 * One game in the Arcade's Games module (spec §3.1, R3.3): a game of chance, a menu cabinet, the
 * time trials or mini golf.
 *
 * <p>Every game is a plain class built from a {@link GameContext} and listed once in
 * {@link GameCatalog}; the framework ({@link GamesService}) owns everything around it. It decides
 * whether the game is open ({@code games.enabled}, the game's own {@link #configEnabled()}, and
 * whether it has failed), gates every play ({@link PlayGate}), settles rounds, pays capped
 * rewards, runs world sessions, and catches anything the game throws — a game that throws is
 * switched off on its own until {@code /hcm reload}, never the plugin. So a game only has to be a
 * game: it draws its screens, runs its engine, and calls the framework for anything that moves
 * tokens or touches the player's things.
 *
 * <p>Everything here runs on the main thread. Only the first eight methods are required; the
 * defaults describe a game with nothing extra to do.
 */
public interface Game {

    /** The config key under {@code games:} and the {@code /hcm play} id, snake_case. */
    String id();

    /** What sort of game it is. */
    GameKind kind();

    /** The player-facing name ("Ore Slots"), no colour codes. */
    String name();

    /**
     * The ledger source for this game's stakes, payouts and rewards. The time trials pay under
     * each course's own source ({@link Playable#source()}) and return their default here.
     */
    TokenService.Source source();

    /**
     * The game's own switch, read from its live settings on every call (never cached): its
     * {@code enabled} key, and for a game of chance whether its odds could be solved inside the
     * code limits. The framework adds {@code games.enabled} and the failed state.
     */
    boolean configEnabled();

    /** Two to five short plain lines: its tile lore and its rules screen. */
    List<String> rules();

    /** Its tile on the Games screen. The NAME carries the key fact; lore is extra. */
    ItemStack tile(Player viewer);

    /**
     * Open its screen (rules and, for a game of chance, how much it gives back, BEFORE the first
     * play), or its course list. Only called after the framework's gate allowed it.
     *
     * @param back what "Back" does, or {@code null} for "Close"
     */
    void open(Player player, Runnable back);

    /** Other names {@code /hcm play} accepts ({@code blackjack} for Twenty-One). */
    default List<String> aliases() {
        return List.of();
    }

    /** Whether it may be the featured game of the day. Never a game of chance. */
    default boolean featurable() {
        return kind() != GameKind.CHANCE;
    }

    /**
     * Enable and every reload while the game is open: validate what it needs. Listeners and tasks
     * go through {@link GamesService#on} and {@link GamesService#every}, which the framework
     * removes again at {@link #stop()}.
     */
    default void start() {
    }

    /** Disable, reload and switch-off: stop anything live. Settling rounds is the framework's. */
    default void stop() {
    }

    /** A player joined (after the framework settled their rounds and restored any session). */
    default void onJoin(Player player) {
    }

    /** A player quit: end anything live for them. */
    default void onQuit(Player player) {
    }

    /** Lines for {@code /hcm arcade odds} (games of chance), from the same engine it plays with. */
    default List<String> oddsLines() {
        return List.of();
    }

    /** Its {@code /api/arcade} entry or entries (one per course for the world games). */
    default void feed(FeedWriter out) {
    }

    /**
     * Extra lines for {@code /hcm games status} under the game's own line, plain words (Daily
     * Courses: what is up and what is being built). Asked only while the game is open.
     */
    default List<String> statusLines() {
        return List.of();
    }

    /**
     * Its courses were changed outside its own admin commands (Fresh Courses flipped a layout):
     * forget any cached course so the next read sees the new rows.
     */
    default void coursesChanged() {
    }

    /** Its {@code /hcm games <name> ...} admin commands, or {@code null} for none. */
    default GameAdmin admin() {
        return null;
    }

    /** Its tiles on the Games screen: one for the game, or one per course for the world games. */
    default List<GameTile> tiles(Player viewer) {
        return List.of(new GameTile(Tab.of(kind()), tile(viewer), id(), 0));
    }

    /** What {@code /hcm play <id>} can start inside it (courses). */
    default Collection<Playable> playables() {
        return List.of();
    }

    /**
     * Start one of its {@link #playables()} (after the gate allowed it).
     *
     * @return false if it has no such playable or could not start it
     */
    default boolean play(Player player, String playableId, Runnable back) {
        return false;
    }

    /** The player's world session in this game ended (after their things were restored). */
    default void onSessionEnd(Player player, EndReason reason) {
    }

    /** The player fell out of the world during its session (back to the last checkpoint). */
    default void onVoid(Player player) {
    }

    /**
     * The player used one of its kit items ({@code action} is the kit item's action; the click is
     * already cancelled). Right and left clicks both arrive: a Bedrock tap is a plain click.
     */
    default void onKitUse(Player player, String action, boolean leftClick) {
    }

    /** The Games screen's tabs, besides All (which shows every tile). */
    enum Tab {
        /** Games of chance (plus links to the Scratch Ticket and Crates). */
        LUCK,
        /** Menu games. */
        CABINETS,
        /** Time-trial courses. */
        COURSES,
        /** Mini golf courses. */
        GOLF,
        /**
         * Playing with others at the same time (EVENTS-DROPPER-SPEC §A.6): Race Night and Falling
         * Floors put their tiles here through {@link Game#tiles}. No {@link GameKind} maps to it
         * ({@link #of}): a game chooses it for its own tiles.
         */
        TOGETHER;

        /** The tab a game of this kind shows on. */
        public static Tab of(GameKind kind) {
            return switch (kind) {
                case CHANCE -> LUCK;
                case CABINET -> CABINETS;
                case TRIAL -> COURSES;
                case GOLF -> GOLF;
            };
        }
    }

    /**
     * One tile on the Games screen. Most games show one; the world games show one per course. The
     * NAME carries the key fact ("&amp;aOre Slots &amp;7- 1-5 tokens"): Bedrock shows lore only on
     * tap-and-hold.
     *
     * @param tab    the tab it belongs to
     * @param icon   the item shown
     * @param playId what {@code /hcm play <id>} opens: the game id or a course id
     * @param order  its order among the game's own tiles (catalog order comes first)
     */
    record GameTile(Tab tab, ItemStack icon, String playId, int order) {
    }

    /**
     * Something inside a game that {@code /hcm play <id>} starts directly: a time-trial or golf
     * course. Course ids share the {@code /hcm play} namespace with game ids, so a clash (or a
     * reserved word, {@link GameCatalog#RESERVED}) is refused when the course is made.
     *
     * @param id     the course id
     * @param name   its player-facing name, no colour codes
     * @param game   the game that runs it ({@link Game#play})
     * @param source the ledger source its rewards are paid under (a boat course: {@code GAMES_BOAT})
     * @param tier   its difficulty ({@code easy}, ...), or {@code ""} for none
     */
    record Playable(String id, String name, Game game, TokenService.Source source, String tier) {
    }
}
