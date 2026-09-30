package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.FreshFeed;
import com.dierks.homecraft.games.gen.engine.GenService;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfRun;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.games.trial.BoatHype;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.DropperLayout;
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
 * One Fresh course as the Fresh Courses screen and the parkour tier picker show it (GEN-SPEC
 * §5.4): read from the course's own game ({@link TimeTrials} or {@link MiniGolf}), so the tile and
 * the course's screen can never disagree, and drawn with its key facts in the NAME (Bedrock shows
 * lore only on tap-and-hold): the stars, a golf course's holes and par, and its course code.
 *
 * <p>Every read of a course game runs inside that game's guard: a bug there closes that game, not
 * Fresh Courses, and the slot reads as closed.
 */
final class DailyTiles {

    private DailyTiles() {
    }

    /**
     * A slot as it stands now.
     *
     * @param slot  the slot
     * @param game  the course game that runs it (Time Trials or Mini Golf), or {@code null}
     * @param trial its course when it is a time trial Fresh Courses made, else {@code null}
     * @param golf  its course when it is a golf course Fresh Courses made, else {@code null}
     * @param open  it can be played now (the gate, the game and the course are open)
     */
    record View(Slots.Def slot, Game game, Course trial, GolfCourse golf, boolean open) {

        /** Its layout's tag, or {@code null} while it has none. */
        GenTag tag() {
            return trial != null ? trial.gen() : golf != null ? golf.gen() : null;
        }

        /** Whether Fresh Courses has made it at all (a row with a tag). */
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

    /** The block a slot's tile shows: its colour's concrete (green easy ... magenta golf, blue the Dropper, orange a classic). */
    static Material icon(Slots.Def slot) {
        return switch (DailyText.colour(slot)) {
            case "&6" -> Material.ORANGE_CONCRETE;
            case "&a" -> Material.LIME_CONCRETE;
            case "&e" -> Material.YELLOW_CONCRETE;
            case "&c" -> Material.RED_CONCRETE;
            case "&b" -> Material.LIGHT_BLUE_CONCRETE;
            case "&d" -> Material.MAGENTA_CONCRETE;
            case "&9" -> Material.BLUE_CONCRETE;
            default -> Material.WHITE_CONCRETE;
        };
    }

    /**
     * The name of a slot's tile: its key fact when it can be played ({@link DailyText#slotName}),
     * then its course code (" &amp;8· &amp;7Course code HARD-40", when it has one); "being built, back
     * soon" when it can't be played.
     */
    static String name(Slots.Def slot, int cadence, boolean playable, int stars, int holes, int par, String code) {
        return playable ? DailyText.slotName(slot, cadence, stars, holes, par) + DailyLookup.codeSuffix(code)
                : DailyText.closedName(slot, cadence);
    }

    /** A slot's tile for {@code viewer}: {@code cadence} is the live schedule's (for a slot not up yet). */
    static ItemStack tile(GamesService games, Player viewer, View v, int cadence) {
        Slots.Def slot = v.slot();
        if (!v.playable()) {
            return Menus.icon(Material.GRAY_DYE, name(slot, cadence, false, 0, 0, 0, null),
                    games.generated().closedLine(slot.id()));
        }
        GenTag t = v.tag();
        int setCadence = t.cadence();
        UUID id = viewer.getUniqueId();
        int stars = DailyLookup.stars(games, id, t);
        List<String> lore = new ArrayList<>();
        long now = games.clock().nowMillis();
        long next = games.generated().nextChangeAt();
        if (!DailyLookup.current(games, slot.id())) {
            lore.add(GenCopy.previous(setCadence));
        } else if (next > now) {
            lore.add(GenCopy.newIn(next - now));
        }
        int holes = 0;
        int par = 0;
        if (v.golf() != null && v.game() instanceof MiniGolf golf) {
            GolfCourse c = v.golf();
            holes = c.holes().size();
            par = c.par();
            lore.add(golf.setBestLine(c, viewer));
            Long best = golf.best(id, c.id());
            lore.add(DailyText.yourBest(setCadence, best == null ? null : GolfRun.strokesText(best.intValue())));
            lore.add(DailyText.starStrokes(par, holes));
        } else if (v.trial() != null && v.game() instanceof TimeTrials trials) {
            if (slot.dropper()) {
                holes = DropperLayout.levels(v.trial()); // a dropper's key fact: its levels
            } else if (BoatHype.mountain(v.trial())) {
                holes = BoatHype.drops(v.trial()); // a Mountain Run's: its drops
            }
            String board = TimeTrials.board(v.trial());
            lore.add(trials.setBestLine(trials.recordOn(board), viewer, setCadence));
            Long best = trials.bestOn(viewer, board);
            lore.add(DailyText.yourBest(setCadence, best == null ? null : TrialText.time(best)));
            lore.add(DailyText.starTimes(t.goldMs(), t.silverMs()));
        }
        String first = DailyText.firstFinish(setCadence, DailyLookup.freshClear(games, t), v.game() != null
                && DailyLookup.freshClearPaid(games, id, v.game().id(), t));
        if (first != null) {
            lore.add(first);
        }
        lore.add("&eClick to play");
        return Menus.glint(Menus.icon(icon(slot), name(slot, setCadence, true, stars, holes, par,
                DailyLookup.code(games, t)), lore.toArray(new String[0])), stars >= 3);
    }

    // ---- the Classics (GEN-SPEC-KEEP §3, §8) ---------------------------------------------------------

    /** What a Classics slot is doing: holding a recalled course, being built with one, or empty. */
    enum Classic {
        HOLDING, BUILDING, EMPTY
    }

    /**
     * A Classics slot's state from the engine's two accessors: holding one when
     * {@code GenService.classic(id)} gives one, being built while {@code classicPending(id)}, empty
     * otherwise ({@code closedLine} alone can't tell those two apart).
     */
    static Classic classic(boolean holding, boolean pending) {
        return holding ? Classic.HOLDING : pending ? Classic.BUILDING : Classic.EMPTY;
    }

    /** An empty Classics slot's NAME: "&amp;7Classic Parkour &amp;8- empty". */
    static String emptyClassicName(Slots.Def classic) {
        return "&7" + classic.name() + " &8- empty";
    }

    /**
     * A holding Classics slot's NAME: "&amp;6Classic: Hard Parkour (week of 5 Oct) &amp;8· &amp;7Course
     * code HARD-40" (its original course's name at that set's cadence, re-made or not).
     */
    static String classicName(GenTag tag, boolean remade, String code) {
        Slots.Def d = Slots.of(tag.slot());
        String name = d == null ? tag.slot() : GenCopy.slotName(d, tag.cadence());
        return "&6" + GenCopy.classicName(name, tag.cadence(), tag.day(), remade) + DailyLookup.codeSuffix(code);
    }

    /** "&amp;7Back until Mon 12 Oct 4:02 AM", or until an admin closes it (a recall "forever"). */
    static String backUntil(Long to, java.time.ZoneId zone) {
        return to == null ? "&7Back until an admin closes it" : "&7Back until " + GenCopy.whenDated(to, zone);
    }

    /** A Classics slot's tile: holding a course (click to play it), being built, or empty (with the tip). */
    static ItemStack classicTile(GamesService games, Player viewer, Slots.Def classic) {
        GenService e = DailyLookup.engine(games);
        FreshFeed.Classic held = e == null ? null : e.classic(classic.id());
        Classic state = classic(held != null, e != null && e.classicPending(classic.id()));
        switch (state) {
            case HOLDING -> {
                GenTag tag = e.liveTag(classic.id());
                String rowName = rowName(games, classic, tag);
                boolean remade = rowName != null && rowName.contains("(re-made)");
                List<String> lore = new ArrayList<>();
                lore.add("&7Its old records are the ones to beat.");
                lore.add("&7" + GenCopy.courseCode(held.code()));
                lore.add(backUntil(held.to(), games.clock().zone()));
                int stars = tag == null ? 0 : DailyLookup.stars(games, viewer.getUniqueId(), tag);
                if (stars > 0) {
                    lore.add("&7Your stars: &6" + com.dierks.homecraft.games.gen.api.Stars.text(stars));
                }
                lore.add("&eClick to play");
                String name = tag == null ? "&6" + classic.name() + DailyLookup.codeSuffix(held.code())
                        : classicName(tag, remade, held.code());
                return Menus.glint(Menus.icon(icon(classic), name, lore.toArray(new String[0])), stars >= 3);
            }
            case BUILDING -> {
                return Menus.icon(Material.GRAY_DYE, games.generated().closedLine(classic.id()),
                        "&7A classic course is on its way.");
            }
            default -> {
                return Menus.icon(Material.GRAY_DYE, emptyClassicName(classic), GenCopy.CLASSICS_TIP);
            }
        }
    }

    /** A Classics tile was clicked: play the course it holds ({@code /hcm play <classic id>}), or say why not. */
    static void classicClick(GamesService games, Player viewer, Slots.Def classic, Runnable back) {
        GenService e = DailyLookup.engine(games);
        if (e == null || e.classic(classic.id()) == null) {
            viewer.sendMessage(Text.of(e != null && e.classicPending(classic.id())
                    ? games.generated().closedLine(classic.id()) : GenCopy.CLASSICS_TIP));
            Sounds.refused(viewer);
            return;
        }
        games.open(viewer, classic.id(), back);
    }

    /** The name of the course row a Classics slot holds (it says "(re-made)" when it was made again). */
    private static String rowName(GamesService games, Slots.Def classic, GenTag tag) {
        Game g = games.game(classic.golf() ? Slots.GAME_GOLF : Slots.GAME_TRIALS);
        if (g instanceof TimeTrials trials) {
            Course c = games.guard(trials, () -> trials.course(classic.id()), null);
            return c == null ? null : c.name();
        }
        if (g instanceof MiniGolf golf) {
            GolfCourse c = games.guard(golf, () -> golf.course(classic.id()), null);
            return c == null ? null : c.name();
        }
        return null;
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
