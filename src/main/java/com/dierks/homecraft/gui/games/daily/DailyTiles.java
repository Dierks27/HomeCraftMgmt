package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfRun;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TrialText;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One daily course as the Today's Courses screen and the parkour tier picker show it (GEN-SPEC
 * §5.4): read from the course's own game ({@link TimeTrials} or {@link MiniGolf}), so the tile and
 * the course's screen can never disagree, and drawn with its key fact in the NAME.
 *
 * <p>Every read of a course game runs inside that game's guard: a bug there closes that game, not
 * Daily Courses, and the slot reads as closed.
 */
final class DailyTiles {

    private DailyTiles() {
    }

    /**
     * A slot as it stands now.
     *
     * @param slot  the slot
     * @param game  the course game that runs it (Time Trials or Mini Golf), or {@code null}
     * @param trial its course when it is a time trial Daily Courses made, else {@code null}
     * @param golf  its course when it is a golf course Daily Courses made, else {@code null}
     * @param open  it can be played now (the gate, the game and the course are open)
     */
    record View(Slots.Def slot, Game game, Course trial, GolfCourse golf, boolean open) {

        /** Its layout's tag, or {@code null} while it has none. */
        GenTag tag() {
            return trial != null ? trial.gen() : golf != null ? golf.gen() : null;
        }

        /** Whether Daily Courses has made it at all (a row with a tag). */
        boolean exists() {
            return tag() != null;
        }

        /** Whether it can be played and has a layout to show. */
        boolean playable() {
            return open && tag() != null;
        }
    }

    /**
     * Whether a slot has a place on the screens: it can be played, it has been made, or it ships
     * on (so an enabled slot that isn't up yet says it's being built, and the ice boat, shipped
     * off, stays out of sight until it is switched on and built).
     */
    static boolean shown(Slots.Def slot, boolean exists, boolean open) {
        return slot != null && (open || exists || slot.enabled());
    }

    /** The slot as it stands, read from its game (inside that game's guard). */
    static View view(GamesService games, Slots.Def slot) {
        String gameId = slot.golf() ? Slots.GAME_GOLF : Slots.GAME_TRIALS;
        Game g = games.game(gameId);
        if (g == null) {
            return new View(slot, null, null, null, false);
        }
        View closed = new View(slot, g, null, null, false);
        if (g instanceof MiniGolf golf) {
            return games.guard(golf, () -> {
                GolfCourse c = golf.course(slot.id());
                GolfCourse ours = c != null && c.generated() ? c : null;
                boolean open = ours != null && games.enabled(golf) && golf.playableCourse(slot.id()) != null;
                return new View(slot, golf, null, ours, open);
            }, closed);
        }
        if (g instanceof TimeTrials trials) {
            return games.guard(trials, () -> {
                Course c = trials.course(slot.id());
                Course ours = c != null && c.generated() ? c : null;
                boolean open = ours != null && games.enabled(trials) && trials.openCourse(slot.id()) != null;
                return new View(slot, trials, ours, null, open);
            }, closed);
        }
        return closed;
    }

    /** Every slot that has a place on the screens, in slot order. */
    static List<View> shownViews(GamesService games, List<Slots.Def> slots) {
        List<View> out = new ArrayList<>();
        for (Slots.Def s : slots) {
            View v = view(games, s);
            if (shown(s, v.exists(), v.open())) {
                out.add(v);
            }
        }
        return out;
    }

    /** The block a slot's tile shows: its colour's concrete (green easy ... magenta golf). */
    static Material icon(Slots.Def slot) {
        return switch (DailyText.colour(slot)) {
            case "&a" -> Material.LIME_CONCRETE;
            case "&e" -> Material.YELLOW_CONCRETE;
            case "&c" -> Material.RED_CONCRETE;
            case "&b" -> Material.LIGHT_BLUE_CONCRETE;
            case "&d" -> Material.MAGENTA_CONCRETE;
            default -> Material.WHITE_CONCRETE;
        };
    }

    /**
     * The name of a slot's tile: its key fact when it can be played ({@link DailyText#slotName}),
     * "being built, back soon" when it can't.
     */
    static String name(Slots.Def slot, boolean playable, int stars, int holes, int par) {
        return playable ? DailyText.slotName(slot, stars, holes, par) : DailyText.closedName(slot);
    }

    /** A slot's tile for {@code viewer}. */
    static ItemStack tile(GamesService games, Player viewer, View v, long today) {
        Slots.Def slot = v.slot();
        if (!v.playable()) {
            return Menus.icon(Material.GRAY_DYE, name(slot, false, 0, 0, 0), games.generated().closedLine(slot.id()));
        }
        GenTag t = v.tag();
        UUID id = viewer.getUniqueId();
        int stars = DailyLookup.stars(games, id, slot.id(), t.day());
        List<String> lore = new ArrayList<>();
        long now = games.clock().nowMillis();
        long next = games.generated().nextChangeAt();
        if (t.day() < today) {
            lore.add(GenCopy.YESTERDAY);
        } else if (next > now) {
            lore.add(GenCopy.newIn(next - now));
        }
        int holes = 0;
        int par = 0;
        if (v.golf() != null && v.game() instanceof MiniGolf golf) {
            GolfCourse c = v.golf();
            holes = c.holes().size();
            par = c.par();
            lore.add(golf.todaysBestLine(c, viewer));
            Long best = golf.best(id, c.id());
            lore.add(DailyText.yourBestToday(best == null ? null : GolfRun.strokesText(best.intValue())));
            lore.add(DailyText.starStrokes(par, holes));
        } else if (v.trial() != null && v.game() instanceof TimeTrials trials) {
            String board = TimeTrials.board(v.trial());
            lore.add(trials.todaysBestLine(trials.recordOn(board), viewer));
            Long best = trials.bestOn(viewer, board);
            lore.add(DailyText.yourBestToday(best == null ? null : TrialText.time(best)));
            lore.add(DailyText.starTimes(t.goldMs(), t.silverMs()));
        }
        String first = DailyText.firstToday(games.generated().dailyClear(slot.id()), v.game() != null
                && DailyLookup.dailyClearPaid(games, id, v.game().id(), slot.id(), t.day()));
        if (first != null) {
            lore.add(first);
        }
        lore.add("&eClick to play");
        return Menus.glint(Menus.icon(icon(slot), name(slot, true, stars, holes, par), lore.toArray(new String[0])),
                stars >= 3);
    }

    /** A slot's tile was clicked: its course's own screen (through the gate), or why it's closed. */
    static void click(GamesService games, Player viewer, View v, Runnable back) {
        if (!v.playable()) {
            viewer.sendMessage(Text.of(games.generated().closedLine(v.slot().id())));
            Sounds.refused(viewer);
            return;
        }
        games.open(viewer, v.slot().id(), back);
    }
}
