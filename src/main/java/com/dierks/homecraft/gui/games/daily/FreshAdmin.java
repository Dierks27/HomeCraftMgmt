package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.Slots;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The owner's Fresh Courses tools on a course's own screen (WP-ADM): the same {@code /hcm games gen}
 * commands as buttons, for {@code hcm.games.admin} only. In the owner's words: "Or regenerate
 * button", "Will I be able to play the course previews?", and "I could do the course preview a
 * week early or whatever and find a good one before posting it for the following week."
 *
 * <p>Why one "Admin tools" item and a small screen of its own: the course screens are shared (the
 * Weekly Cup, "Race with friends", the Clubhouse's buttons) and a player never sees these, so the
 * tools take one bottom-row slot ({@link #SLOT}) and open {@link FreshAdminMenu}. Each tool is its
 * command, run through the command's own path ({@code GenAdmin}: its refusals, its log line with
 * who did it); a tool whose command needs {@code confirm} first opens a small confirm screen
 * ({@link FreshAdminConfirm}), whose Yes runs it with {@code confirm}, once.
 *
 * <p>Pure: the tools as data (their NAMEs, lore and command words), so a test can check who sees
 * them and that each click reaches its command exactly once. Every key fact is in the NAME for
 * Bedrock, which shows lore only on tap-and-hold.
 */
public final class FreshAdmin {

    /** Who sees the tools. */
    public static final String PERMISSION = "hcm.games.admin";
    /**
     * The one "Admin tools" item on a 27-slot course screen: the bottom row's LEFT end, clear of
     * "Race with friends" (20), the way out (22), the Weekly Cup (24) and the Clubhouse's "Take a
     * rider" (26) on the time-trial screen, and of every item on the golf screen (which uses the same
     * slot). On a Fresh boat course an admin sees all five at once.
     */
    public static final int SLOT = 18;

    /** What a tool does. */
    public enum Kind {
        REGENERATE, PREVIEW, PREVIEW_NEXT, TEST, WALK, PROMOTE, CHOOSE, UNCHOOSE
    }

    /**
     * One tool.
     *
     * @param kind    what it does
     * @param slot    where it sits on {@link FreshAdminMenu}
     * @param name    its NAME ("&amp;cAdmin: Make a new course now (regenerate)")
     * @param lore    what it does, in a few lines, and the command it runs
     * @param words   the words after {@code /hcm games gen}, without {@code confirm}
     * @param confirm it asks first, and its command takes {@code confirm}
     * @param yes     the confirm screen's Yes NAME, or {@code null} when it doesn't ask
     */
    public record Tool(Kind kind, int slot, String name, List<String> lore, List<String> words, boolean confirm,
                       String yes) {

        /** The words its command runs with: {@link #words}, and {@code confirm} when it asked first. */
        public String[] command() {
            List<String> out = new ArrayList<>(words);
            if (confirm) {
                out.add("confirm");
            }
            return out.toArray(new String[0]);
        }
    }

    private FreshAdmin() {
    }

    /** Whether the viewer sees the tools on {@code courseId}'s screen: an admin, on a Fresh Course (not a Classic). */
    public static boolean shown(boolean admin, String courseId) {
        return admin && courseId != null && Slots.of(courseId) != null;
    }

    /** "next week's" at the shipped weekly cadence, "tomorrow's" when daily, else "the next set's". */
    static String nextOnes(int cadence) {
        return cadence == 7 ? "next week's" : cadence == 1 ? "tomorrow's" : "the next set's";
    }

    /** "next week", "tomorrow", or "for the next set". */
    static String nextTime(int cadence) {
        return cadence == 7 ? "next week" : cadence == 1 ? "tomorrow" : "for the next set";
    }

    /** The course screen's item NAME: what the tools are, in its NAME for Bedrock. */
    public static String itemName() {
        return "&dAdmin tools &7- new course, preview, try it, pick one";
    }

    /** The course screen's item lore, and the tools screen's header lore: the preview and the pick. */
    public static List<String> state(GenOps.Tools t) {
        List<String> out = new ArrayList<>();
        if (t == null) {
            out.add("&7Fresh Courses isn't running.");
            return out;
        }
        if (!t.on()) {
            out.add("&cThis course is off.");
        }
        if (t.busy()) {
            out.add("&eBeing built right now.");
        }
        out.add(t.preview() ? "&7Preview: seed " + GenSeed.hex(t.previewSeed()) + " (" + (t.previewNext()
                ? nextOnes(t.cadence()) : "this set's") + ")" : "&7No preview yet.");
        if (t.chosenSeed() != null) {
            out.add("&6Picked for " + t.chosenFor() + ": seed " + GenSeed.hex(t.chosenSeed()));
        }
        out.add("&8Only admins see this.");
        return out;
    }

    /** The tools for {@code slot} as it stands ({@code t}): the always-there three, then the preview's, then the pick's. */
    public static List<Tool> tools(Slots.Def slot, GenOps.Tools t) {
        List<Tool> out = new ArrayList<>();
        if (slot == null || t == null) {
            return out;
        }
        String id = slot.id();
        int n = t.cadence();
        List<String> finishFirst = new ArrayList<>(List.of("&7Players on it finish first."));
        if (!slot.golf()) { // golf runs no Weekly Cup
            finishFirst.add("&7A running Weekly Cup here is called off and refunded.");
        }
        out.add(new Tool(Kind.REGENERATE, 10, "&cAdmin: Make a new course now (regenerate)",
                lines(List.of("&7A brand-new course for this set,", "&7on a fresh board."), finishFirst,
                        "/hcm games gen regenerate " + id + " confirm"),
                List.of("regenerate", id), true, "&aYes: make a new course now"));
        out.add(new Tool(Kind.PREVIEW, 11, "&eAdmin: Build one to try (preview)",
                lines(List.of("&7Builds a new course in the spare half.", "&7The current course stays up;",
                        "&7nobody can play the preview."), List.of(), "/hcm games gen preview " + id),
                List.of("preview", id), false, null));
        out.add(new Tool(Kind.PREVIEW_NEXT, 12, "&eAdmin: Build " + nextOnes(n) + " to try (preview next)",
                lines(List.of("&7Builds a course for " + (n == 7 ? "next week" : n == 1 ? "tomorrow" : "the next set")
                        + " with its settings,", "&7in the spare half. Try it, then", "&7use it " + nextTime(n)
                        + " if you like it."), List.of(), "/hcm games gen preview " + id + " next"),
                List.of("preview", id, "next"), false, null));
        if (t.preview()) {
            if (slot.golf()) {
                out.add(new Tool(Kind.WALK, 14, "&aAdmin: Walk the preview (go there)",
                        lines(List.of("&7Takes you to the spare half.", "&7Golf previews are walked:",
                                "&7they can't be test-played yet."), List.of(), "/hcm games gen tp " + id + " idle"),
                        List.of("tp", id, "idle"), false, null));
            } else {
                out.add(new Tool(Kind.TEST, 14, "&aAdmin: Try the preview (test run)",
                        lines(List.of("&7A test run on the preview: its real", "&7start, checkpoints, finish, clock and"
                                + " kit.", "&7Nothing is recorded or paid."), List.of(), "/hcm games gen test " + id),
                        List.of("test", id), false, null));
            }
            List<String> promote = new ArrayList<>(List.of("&7The preview becomes this set's course,",
                    "&7on a fresh board."));
            if (t.previewNext()) {
                promote.add("&cThis preview is " + nextOnes(n) + ": use it " + nextTime(n) + " instead.");
            }
            out.add(new Tool(Kind.PROMOTE, 15, "&6Admin: Use it now (promote)", lines(promote, finishFirst,
                    "/hcm games gen promote " + id + " confirm"), List.of("promote", id), true,
                    "&aYes: use the preview now"));
            out.add(new Tool(Kind.CHOOSE, 16, "&6Admin: Use it " + nextTime(n) + " (choose)",
                    lines(List.of("&7The preview's seed becomes " + nextOnes(n) + " course.", "&7It goes up at the change,"
                            + " on fresh boards;", "&7the set after goes back to normal."), List.of(),
                            "/hcm games gen choose " + id + " confirm"), List.of("choose", id), true,
                    "&aYes: use it " + nextTime(n)));
        }
        if (t.chosenSeed() != null) {
            out.add(new Tool(Kind.UNCHOOSE, 24, "&7Admin: Cancel " + nextOnes(n) + " pick (unchoose)",
                    lines(List.of("&7Picked: seed " + GenSeed.hex(t.chosenSeed()) + ".", "&7The set gets its own new"
                            + " course instead."), List.of(), "/hcm games gen unchoose " + id),
                    List.of("unchoose", id), false, null));
        }
        return out;
    }

    private static List<String> lines(List<String> what, List<String> more, String command) {
        List<String> out = new ArrayList<>(what);
        out.addAll(more);
        out.add("&8" + command);
        return out;
    }

    /**
     * A click on a tool: one whose command needs {@code confirm} opens its confirm screen
     * ({@code ask}); any other runs its command at once ({@code run}), once.
     */
    public static void click(Tool tool, Consumer<Tool> ask, Consumer<String[]> run) {
        if (tool.confirm()) {
            ask.accept(tool);
        } else {
            run.accept(tool.command());
        }
    }

    /** The confirm screen's Yes: the command, with {@code confirm}, once. */
    public static void yes(Tool tool, Consumer<String[]> run) {
        run.accept(tool.command());
    }
}
