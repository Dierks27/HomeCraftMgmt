package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.games.gen.api.GenCopy;
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
    /**
     * The confirm screen's "No, go back" and Yes (fix2-D, D4): on the bottom row either side of the
     * way out (22), on slots no tool uses ({@link #tools}: 10-12, 14-16, 24). The second press of a
     * quick double click lands on the same slot of the next screen, so a Yes where Promote sat ran
     * {@code promote confirm} unread, and a No where Preview sat built a preview over the one being
     * judged.
     */
    public static final int CONFIRM_NO = 19;
    /** See {@link #CONFIRM_NO}. */
    public static final int CONFIRM_YES = 25;
    /**
     * How long the tools and confirm screens drop every click after they open (fix2-D, D4): each is a
     * new screen with a hold of its own, and a vanilla client sends the second press of a double
     * click to it as a plain click. A person reads the screen for longer than this.
     */
    public static final long OPEN_HOLD_MS = 400;

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

    /** "this week's" at the shipped weekly cadence, "today's" when daily, else "this set's". */
    static String thisOnes(int cadence) {
        return cadence == 7 ? "this week's" : cadence == 1 ? "today's" : "this set's";
    }

    /** The course screen's item NAME: what the tools are, in its NAME for Bedrock. */
    public static String itemName() {
        return "&dAdmin tools &7- new course, preview, try it, pick one";
    }

    /** The course screen's item NAME for a slot as it stands: {@link #itemName()}, and the pick's set (D8). */
    public static String itemName(GenOps.Tools t) {
        return itemName() + (t == null || t.chosenSeed() == null ? "" : " &6- picked for " + t.chosenFor()
                + (t.chosenUpNow() ? " (up now)" : ""));
    }

    /** The course's name at the cadence ("Golf of the Day"), or its shipped name when that isn't known (D5). */
    private static String courseName(Slots.Def def, GenOps.Tools t) {
        return t == null ? def.name() : GenCopy.slotName(def, t.cadence());
    }

    /** The tools screen's title (D5: the course's name at the cadence). */
    public static String title(Slots.Def def, GenOps.Tools t) {
        return "&dAdmin tools: " + courseName(def, t);
    }

    /** The tools screen's header NAME (slot 4): the course, and what is picked, for Bedrock (D5, D8). */
    public static String headerName(Slots.Def def, GenOps.Tools t) {
        return "&dAdmin tools &7- " + courseName(def, t) + (t == null || t.chosenSeed() == null ? ""
                : " &6- picked seed " + GenSeed.hex(t.chosenSeed()) + " for " + t.chosenFor()
                + (t.chosenUpNow() ? " (up now)" : ""));
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
            out.add("&6Picked for " + t.chosenFor() + (t.chosenUpNow() ? " (up now)" : "") + ": seed "
                    + GenSeed.hex(t.chosenSeed()));
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
        // D8: what a Yes does to a running Cup is in its NAME too (Bedrock shows lore only on a hold)
        String cupCalledOff = slot.golf() ? "" : " &7- a running Weekly Cup is called off";
        if (!slot.golf()) { // golf runs no Weekly Cup
            finishFirst.add("&7A running Weekly Cup here is called off and refunded.");
        }
        out.add(new Tool(Kind.REGENERATE, 10, "&cAdmin: Make a new course now (regenerate)",
                lines(List.of("&7A brand-new course for this set,", "&7on a fresh board."), finishFirst,
                        "/hcm games gen regenerate " + id + " confirm"),
                List.of("regenerate", id), true, "&aYes: make a new course now" + cupCalledOff));
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
            // D8: which set the preview is for is in the NAMEs; next set's can't be promoted (the
            // command refuses it), so it has no Use it now
            String whose = t.previewNext() ? nextOnes(n) : thisOnes(n);
            if (slot.golf()) {
                out.add(new Tool(Kind.WALK, 14, "&aAdmin: Walk " + whose + " preview (go there)",
                        lines(List.of("&7Takes you to the spare half.", "&7Golf previews are walked:",
                                "&7they can't be test-played yet."), List.of(), "/hcm games gen tp " + id + " idle"),
                        List.of("tp", id, "idle"), false, null));
            } else {
                out.add(new Tool(Kind.TEST, 14, "&aAdmin: Try " + whose + " preview (test run)",
                        lines(List.of("&7A test run on the preview: its real", "&7start, checkpoints, finish, clock and"
                                + " kit.", "&7Nothing is recorded or paid."), List.of(), "/hcm games gen test " + id),
                        List.of("test", id), false, null));
            }
            if (!t.previewNext()) {
                out.add(new Tool(Kind.PROMOTE, 15, "&6Admin: Use it now (promote)", lines(List.of(
                        "&7The preview becomes this set's course,", "&7on a fresh board."), finishFirst,
                        "/hcm games gen promote " + id + " confirm"), List.of("promote", id), true,
                        "&aYes: use the preview now" + cupCalledOff));
            }
            // D6: choosing over a pick still to come replaces it, and the NAMEs say so (the command's own
            // "already has seed ... chosen" warning is skipped: the button sends confirm)
            boolean replaces = t.chosenSeed() != null && !t.chosenUpNow() && !t.chosenSeed().equals(t.previewSeed());
            List<String> choose = new ArrayList<>(List.of("&7The preview's seed becomes " + nextOnes(n) + " course.",
                    "&7It goes up at the change, on fresh boards;", "&7the set after goes back to normal."));
            if (replaces) {
                choose.add("&cReplaces your pick: seed " + GenSeed.hex(t.chosenSeed()) + ".");
            }
            out.add(new Tool(Kind.CHOOSE, 16, "&6Admin: Use it " + nextTime(n)
                    + (replaces ? " instead of your pick" : "") + " (choose)",
                    lines(choose, List.of(), "/hcm games gen choose " + id + " confirm"),
                    List.of("choose", id), true, replaces ? "&aYes: use seed " + GenSeed.hex(t.previewSeed())
                    + " instead of " + GenSeed.hex(t.chosenSeed()) : "&aYes: use it " + nextTime(n)));
        }
        if (t.chosenSeed() != null) {
            // D5: during the chosen set the pick is this set's: letting it go keeps the course up and lets
            // regenerate and promote work on it; a pick still to come is cancelled, as before
            out.add(t.chosenUpNow()
                    ? new Tool(Kind.UNCHOOSE, 24, "&7Admin: Let " + thisOnes(n) + " pick go (unchoose)",
                    lines(List.of("&7Picked: seed " + GenSeed.hex(t.chosenSeed()) + ", up now.",
                            "&7The chosen course stays up; then", "&7regenerate and promote work on it."), List.of(),
                            "/hcm games gen unchoose " + id), List.of("unchoose", id), false, null)
                    : new Tool(Kind.UNCHOOSE, 24, "&7Admin: Cancel " + nextOnes(n) + " pick (unchoose)",
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
