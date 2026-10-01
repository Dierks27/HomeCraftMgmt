package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.Slots;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

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
 *
 * <p>Round 2, G2: a Sure screen can stay open while a whole new preview is built (by another admin,
 * the console, or the admin's own one still planning), so Promote's and Choose's words carry what
 * their Sure screen showed (the preview's seed, and the pick still to come or {@code none}) and the
 * command acts on nothing else (#1); they aren't offered while the slot is being built. A pick a
 * config or schedule change dropped is said on the item, the header and its lore (#3).
 *
 * <p>CV final gate: while the course is switched off (Ice Boat ships off) its previews are built and
 * tried before it is switched on, so Preview, Preview next and Try (or Walk) stay, and Use it now and
 * Use it next week aren't offered (their commands refuse them while it is off).
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

    /**
     * The course screen's item NAME for a slot as it stands: {@link #itemName()}, and the pick's set (D8);
     * round 2, G2: that it is being built (#1) and that a pick was dropped (#3), in the NAME for Bedrock.
     */
    public static String itemName(GenOps.Tools t) {
        return itemName() + busyName(t) + (t == null || t.chosenSeed() == null ? droppedName(t)
                : " &6- picked for " + t.chosenFor() + (t.chosenUpNow() ? " (up now)" : ""));
    }

    /** Round 2, G2 #1: " - being built" while the slot is (Promote and Choose wait for it), else nothing. */
    private static String busyName(GenOps.Tools t) {
        return t != null && t.busy() ? " &e- being built" : "";
    }

    /** Round 2, G2 #3: " - your pick was dropped" while that note stands, else nothing. */
    private static String droppedName(GenOps.Tools t) {
        return t != null && t.dropped() != null ? " &c- your pick was dropped" : "";
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
        return "&dAdmin tools &7- " + courseName(def, t) + busyName(t) + (t == null || t.chosenSeed() == null
                ? droppedName(t) : " &6- picked seed " + GenSeed.hex(t.chosenSeed()) + " for " + t.chosenFor()
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
        } else if (t.dropped() != null) {
            // round 2, G2 #3: a pick a config or schedule change dropped says so here, not only in the console
            for (String line : wrap(t.dropped(), 40)) {
                out.add("&c" + line);
            }
            out.add("&7Build one to try, then choose again.");
        }
        out.add("&8Only admins see this.");
        return out;
    }

    /** {@code text} in lines of at most about {@code width} characters, broken between words. */
    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            line.append(line.isEmpty() ? "" : " ").append(word);
        }
        if (!line.isEmpty()) {
            out.add(line.toString());
        }
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
            String seed = GenSeed.hex(t.previewSeed());
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
            // Round 2, G2 #1: while the slot is being built (the admin's own new preview may still be
            // planning, with this one still shown) there is nothing to promote or choose yet: its Sure
            // screen would sit open over a preview about to be replaced. The header NAME says why.
            // CV final gate: while the course is off its preview is only tried (the commands refuse the rest)
            if (!t.previewNext() && !t.busy() && t.on()) {
                // the words carry the preview it showed: the command refuses once another stands
                out.add(new Tool(Kind.PROMOTE, 15, "&6Admin: Use it now (promote)", lines(List.of(
                        "&7The preview becomes this set's course,", "&7on a fresh board."), finishFirst,
                        "/hcm games gen promote " + id + " " + seed + " confirm"), List.of("promote", id, seed), true,
                        "&aYes: use the preview (seed " + seed + ") now" + cupCalledOff));
            }
            // D6: choosing over a pick still to come replaces it, and the NAMEs say so. Round 2, G2 #1:
            // the words carry the preview and the pick still to come the Sure screen showed ("none" for
            // none), so its confirm skips the command's own "already has seed ... chosen" warning only for
            // that pick, and the command refuses once another preview stands
            Long waiting = t.waitingPick();
            boolean replaces = waiting != null && !waiting.equals(t.previewSeed());
            List<String> choose = new ArrayList<>(List.of("&7The preview's seed becomes " + nextOnes(n) + " course.",
                    "&7It goes up at the change, on fresh boards;", "&7the set after goes back to normal."));
            if (replaces) {
                choose.add("&cReplaces your pick: seed " + GenSeed.hex(waiting) + ".");
            }
            String over = waiting == null ? "none" : GenSeed.hex(waiting);
            if (!t.busy() && t.on()) {
                out.add(new Tool(Kind.CHOOSE, 16, "&6Admin: Use it " + nextTime(n)
                        + (replaces ? " instead of your pick" : "") + " (choose)",
                        lines(choose, List.of(), "/hcm games gen choose " + id + " " + seed + " confirm"),
                        List.of("choose", id, seed, over), true, replaces ? "&aYes: use seed " + seed + " instead of "
                        + over : "&aYes: use seed " + seed + " " + nextTime(n)));
            }
        }
        if (t.chosenSeed() != null) {
            // D5: during the chosen set the pick is this set's: letting it go keeps the course up and lets
            // regenerate and promote work on it, unless the slot's own pin holds too (they wait for an unpin);
            // a pick still to come is cancelled, as before
            out.add(t.chosenUpNow()
                    ? new Tool(Kind.UNCHOOSE, 24, "&7Admin: Let " + thisOnes(n) + " pick go (unchoose)",
                    lines(List.of("&7Picked: seed " + GenSeed.hex(t.chosenSeed()) + ", up now."), t.pinned()
                            ? List.of("&7The chosen course stays up.", "&7It is pinned too: regenerate and",
                            "&7promote wait for &e/hcm games gen unpin " + id)
                            : List.of("&7The chosen course stays up; then", "&7regenerate and promote work on it."),
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

    /**
     * How the tools screen and its "Sure?" screen start every paint (fix2-D, D4 and D7), one step both
     * {@code build()}s go through so neither can lose a part of it: drop every click for
     * {@link #OPEN_HOLD_MS} (the rest of the double click that opened the screen: a vanilla client
     * sends its second press to the new screen as a plain click), paint the frame (the filler and the
     * way out), and go on to the tools, the seeds and Yes only for someone who still has
     * {@link #PERMISSION} (the screen stays open while it is taken away).
     *
     * @param admin whether the viewer has {@link #PERMISSION} now
     * @param hold  the screen's click hold ({@code GameMenu#hold})
     * @param frame paints the filler and the way out
     * @return whether to paint the rest
     */
    public static boolean opening(boolean admin, LongConsumer hold, Runnable frame) {
        hold.accept(OPEN_HOLD_MS);
        frame.run();
        return admin;
    }

    /**
     * The one door to a tools or "Sure?" screen, and to a tool's command (fix2-D, D7): {@code go}
     * runs only for someone who still has {@link #PERMISSION}. The course screen checked it when it
     * painted the item, but a screen stays open while it is taken away, and the command itself
     * trusts its caller to have checked; anyone else gets {@code refuse} (told, and their screen
     * closed) and nothing else.
     *
     * @return whether {@code go} ran
     */
    public static boolean allowed(boolean admin, Runnable go, Runnable refuse) {
        if (!admin) {
            refuse.run();
            return false;
        }
        go.run();
        return true;
    }
}
