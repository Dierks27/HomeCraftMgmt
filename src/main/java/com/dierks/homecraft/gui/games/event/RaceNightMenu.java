package com.dierks.homecraft.gui.games.event;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.event.EventCopy;
import com.dierks.homecraft.games.event.NightRules;
import com.dierks.homecraft.games.event.RaceNight;
import com.dierks.homecraft.games.event.RacePrizes;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The Race Night screen (27, EVENTS-DROPPER-SPEC §A.6): what {@code /hcm play race}, the Race Night
 * tile and an {@code [Arcade] race_night} sign open. Joining is its centre tile, so it is one tap,
 * never an accident.
 *
 * <pre>
 *  4  CLOCK      "Race Night - Fri 7:00 PM"
 *  10 OAK_BOAT   "Ice Boat - 3 races, 2 laps"
 *  12 GOLD_INGOT "Prizes - 5, 3, 2 tokens" (or "Just for fun tonight"), the rules in lore
 *  13 Join       LIME "Join Race Night - 3 of 8 in" / RED "Leave the race list" / GRAY "Joining opens at 6:50 PM"
 *  14 SPYGLASS   "Watch" (a bossbar with the leader, and the finishes in chat)
 *  16 OAK_SIGN   "Last Race Night - won by Sam" (its results)
 *  20 NETHER_STAR "Season points - you: 12" (the season board)
 *  22 Back/Close          26 BELL "Race news: on"
 * </pre>
 *
 * Every build paints every slot; the facts are in the item NAMES (Bedrock shows lore only on
 * tap-and-hold). The layout is {@link #tiles} (pure, tested); the screen only paints it and wires
 * the clicks.
 */
public final class RaceNightMenu extends GameMenu {

    public static final int SIZE = 27;
    public static final int HEADER = 4;
    public static final int TRACK = 10;
    public static final int PRIZES = 12;
    public static final int JOIN = 13;
    public static final int WATCH = 14;
    public static final int LAST = 16;
    public static final int SEASON = 20;
    public static final int EXIT = 22;
    public static final int NEWS = 26;
    /** WP-CH: "Wait in the Clubhouse" (joined) or "Watch from the Clubhouse", while it takes the night. */
    public static final int CLUB = 24;

    /** What the Join tile can do for the viewer. */
    public enum Join {
        /** No night is set. */
        NONE,
        /** A night is set, joining opens later. */
        SOON,
        /** Joining is open and there's room. */
        OPEN,
        /** The viewer is in: the tile takes them off the list. */
        IN,
        /** Joining is open, but it is full. */
        FULL,
        /** The racing has started. */
        STARTED
    }

    /**
     * What the screen shows, from the game (no Bukkit in it, so the layout is tested).
     *
     * @param when          "Fri 7:00 PM", or {@code null} for no night set
     * @param state         a short line on what is on now ("Joining is open", "Race 2 of 3 is on")
     * @param track         the track's name, or {@code null} while it is picked on the night
     * @param opensAt       when joining opens ("6:50 PM"), for {@link Join#SOON}
     * @param lastWinner    the last night's winner, or {@code null}
     * @param lastBoard     the last night's board ({@code rnnight:<id>}), or {@code null}
     * @param seasonName    "October", or {@code null} with the season off
     * @param seasonBoard   the season board, or {@code null}
     */
    public record View(String when, String state, String track, int races, int laps, List<Integer> prizes,
                       int finisherPrize, boolean prizeNight, Join join, int racers, int maxRacers, String opensAt,
                       boolean watching, String lastWinner, String lastBoard, String seasonName, String seasonBoard,
                       long seasonPoints, boolean newsOn) {

        public View {
            prizes = prizes == null ? List.of() : List.copyOf(prizes);
        }
    }

    /** One painted slot. */
    public record Tile(int slot, Material material, String name, List<String> lore) {

        public Tile {
            lore = lore == null ? List.of() : List.copyOf(lore);
        }
    }

    private final RaceNight game;

    public RaceNightMenu(HomeCraftManagement plugin, RaceNight game, Player viewer, Runnable back) {
        super(plugin, game, viewer, back);
        this.game = game;
        init(SIZE, Text.of("&6Race Night"));
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        View v = game.view(viewer);
        for (Tile t : tiles(v, back != null)) {
            if (t.slot() == EXIT || t.material() == Material.GRAY_STAINED_GLASS_PANE) {
                continue; // painted by fill() and exitTile()
            }
            set(t.slot(), Menus.glint(Menus.icon(t.material(), t.name(), t.lore().toArray(new String[0])),
                    t.slot() == JOIN && v.join() == Join.OPEN), e -> click(t.slot(), v));
        }
        if (com.dierks.homecraft.games.event.ClubNight.offered(plugin.games())) { // WP-CH
            boolean joined = v.join() == Join.IN;
            set(CLUB, Menus.icon(joined ? Material.OAK_DOOR : Material.SPYGLASS, joined
                    ? com.dierks.homecraft.games.clubhouse.ClubhouseText.WAIT_BUTTON
                    : com.dierks.homecraft.games.clubhouse.ClubhouseText.WATCH_BUTTON,
                    joined ? "&7Hang out with the other racers;" : "&7Watch the races and the podium",
                    joined ? "&7we take you to the track." : "&7with everyone else."), e -> {
                viewer.closeInventory();
                if (joined) {
                    com.dierks.homecraft.games.clubhouse.Clubhouse.go(plugin.games(), viewer);
                } else {
                    com.dierks.homecraft.games.clubhouse.Clubhouse.watch(plugin.games(), viewer);
                }
            });
        }
    }

    private void click(int slot, View v) {
        switch (slot) {
            case JOIN -> {
                if (v.join() == Join.OPEN) {
                    String why = game.join(viewer);
                    if (why != null) {
                        viewer.sendMessage(Text.of("&c" + why));
                        Sounds.refused(viewer);
                    } else {
                        Sounds.paid(viewer);
                    }
                } else if (v.join() == Join.IN) {
                    game.leave(viewer);
                } else {
                    Sounds.refused(viewer);
                }
                reopen();
            }
            case WATCH -> {
                boolean on = game.watch(viewer);
                viewer.sendMessage(Text.of(on ? "&bWatching Race Night. &7Tap Watch again to stop."
                        : "&7You stopped watching Race Night."));
                reopen();
            }
            case LAST -> {
                if (v.lastBoard() != null) {
                    plugin.games().screens().scores(viewer, game, v.lastBoard(), false, this::reopen);
                }
            }
            case SEASON -> {
                if (v.seasonBoard() != null) {
                    plugin.games().screens().scores(viewer, game, v.seasonBoard(), false, this::reopen);
                }
            }
            case NEWS -> {
                game.setNews(viewer.getUniqueId(), !v.newsOn());
                reopen();
            }
            default -> {
                // looking only
            }
        }
    }

    private void reopen() {
        new RaceNightMenu(plugin, game, viewer, back).open(viewer);
    }

    // ---- the layout (pure) ------------------------------------------------------------------------

    /** Every slot of the screen, in slot order: the tiles, the way out, and filler everywhere else. */
    public static List<Tile> tiles(View v, boolean hasBack) {
        Tile[] out = new Tile[SIZE];
        String prizeFact = RacePrizes.JUST_FOR_FUN.equals(RacePrizes.line(v.prizes(), v.finisherPrize(), v.prizeNight()))
                ? null : prizeList(v.prizes());
        out[HEADER] = new Tile(HEADER, Material.CLOCK, "&6Race Night &7- " + (v.when() == null ? "no race set" : v.when()),
                List.of("&7" + (v.state() == null ? "Boat races for everyone at once." : v.state()),
                        "&7Three short races: every race gives points."));
        out[TRACK] = new Tile(TRACK, Material.OAK_BOAT, v.track() == null ? "&bThe track &7- picked on the night"
                : "&b" + v.track() + " &7- " + EventCopy.format(v.races(), Math.max(1, v.laps())),
                List.of("&7Everyone starts together on a grid.", "&7Boats bump - give each other room!",
                        "&7Finishers watch the rest from the stand."));
        List<String> rules = new ArrayList<>();
        rules.add("&7Free to enter: nobody loses tokens.");
        if (prizeFact != null) {
            rules.add("&7The night's 1st, 2nd and 3rd win " + prizeFact + " tokens.");
            if (v.finisherPrize() > 0) {
                rules.add("&7Everyone else who finished a race: " + EventCopy.tokens(v.finisherPrize()) + ".");
            }
            rules.add("&72nd needs 3 racers, 3rd needs 4.");
        } else {
            rules.add("&7No tokens tonight - the points still count.");
        }
        rules.add("&7Points in every race go on the season board.");
        out[PRIZES] = new Tile(PRIZES, Material.GOLD_INGOT, prizeFact == null ? "&7Just for fun tonight"
                : "&6Prizes &7- " + prizeFact + " tokens", rules);
        out[JOIN] = joinTile(v);
        out[WATCH] = new Tile(WATCH, Material.SPYGLASS, v.watching() ? "&bStop watching" : "&bWatch &7- see who leads",
                List.of("&7A bar at the top shows the leader,", "&7and the finishes come in chat.",
                        v.watching() ? "&eClick to stop" : "&eClick to watch"));
        out[LAST] = new Tile(LAST, Material.OAK_SIGN, v.lastWinner() == null ? "&7Last Race Night &8- none yet"
                : "&bLast Race Night &7- won by " + v.lastWinner(),
                v.lastBoard() == null ? List.of("&7Nothing to show yet.") : List.of("&eClick to see the results"));
        out[SEASON] = new Tile(SEASON, Material.NETHER_STAR, v.seasonName() == null ? "&7Season points &8- off"
                : "&6Season points &7- you: " + v.seasonPoints(),
                v.seasonName() == null ? List.of("&7There's no season board now.")
                        : List.of("&7" + v.seasonName() + "'s Race Night table.", "&eClick to see it"));
        out[EXIT] = new Tile(EXIT, Material.BARRIER, hasBack ? "&cBack" : "&cClose", List.of());
        out[NEWS] = new Tile(NEWS, Material.BELL, "&7Race news: " + (v.newsOn() ? "&aon" : "&coff"),
                List.of("&7Race Night lines in chat and", "&7the join bar at the top.",
                        v.newsOn() ? "&eClick to turn off" : "&eClick to turn on"));
        List<Tile> all = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            all.add(out[i] != null ? out[i] : new Tile(i, Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        }
        return all;
    }

    private static Tile joinTile(View v) {
        return switch (v.join()) {
            case OPEN -> new Tile(JOIN, Material.LIME_CONCRETE, "&aJoin Race Night &7- " + v.racers() + " of "
                    + v.maxRacers() + " in", List.of("&7Keep playing - we take you to", "&7the track when it starts.",
                    "&7Your things are kept safe.", "&eClick to join"));
            case IN -> new Tile(JOIN, Material.RED_CONCRETE, "&cLeave the race list &7- " + v.racers() + " of "
                    + v.maxRacers() + " in", List.of("&7You're in! Click only if you", "&7can't race tonight."));
            case SOON -> new Tile(JOIN, Material.GRAY_DYE, "&7Joining opens at " + v.opensAt(),
                    List.of("&7Come back then, or watch", "&7for the line in chat."));
            case FULL -> new Tile(JOIN, Material.GRAY_DYE, "&7Race Night is full &8(" + v.maxRacers() + ")",
                    List.of("&7Tap Watch to see it."));
            case STARTED -> new Tile(JOIN, Material.GRAY_DYE, "&7The racing has started",
                    List.of("&7Tap Watch to see it,", "&7and join the next one!"));
            case NONE -> new Tile(JOIN, Material.GRAY_DYE, "&7No Race Night set yet",
                    List.of("&7Ask an admin to start one!"));
        };
    }

    /** "5, 3, 2". */
    static String prizeList(List<Integer> prizes) {
        StringBuilder b = new StringBuilder();
        for (int p : prizes) {
            if (!b.isEmpty()) {
                b.append(", ");
            }
            b.append(Math.min(NightRules.MAX_PRIZE_PER_NIGHT, Math.max(0, p)));
        }
        return b.toString();
    }
}
