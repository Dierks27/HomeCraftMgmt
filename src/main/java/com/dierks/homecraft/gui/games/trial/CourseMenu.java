package com.dierks.homecraft.gui.games.trial;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.cup.live.CupLink;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.DropperText;
import com.dierks.homecraft.games.trial.PartyRaces;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TimeTrialsSettings;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.games.trial.TrialText;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.gui.games.daily.DailyText;
import com.dierks.homecraft.gui.games.daily.FreshAdmin;
import com.dierks.homecraft.gui.games.daily.FreshAdminMenu;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * One course (27, spec §11): what to know before you start.
 *
 * <p>4 the course ("River Run (Boat · Medium)"); 10 how to play; 11 your best; 12 its high
 * scores; 13 Start; 14 this week's best; 15 the record and who holds it; 16 what it pays (the
 * first finish's amount, or that it's done, in the name); 20 Race with friends (a party race, D4;
 * never on a Dropper); 22 the way out; 24 the Weekly Cup, when the course runs one; 26 Take a rider,
 * on a boat course while ride along is on (WP-CH); 18 Admin tools, for an admin on a Fresh course
 * (WP-ADM). Start runs the
 * gate again (the screen may have been open a while) and then the world session takes the player
 * to the start line.
 *
 * <p>A Fresh course (GEN-SPEC §5.4) shows its set instead of all-time, in the set's words ("this
 * week" as shipped, "today" when daily): 4 its name and course code; 11 your best this week, 12
 * this week's board, 14 your stars this week and the star times, 15 this week's best, 16 what its
 * first finish this week pays. A course recalled into Classic Parkour or Classic Sky Rings shows
 * its original set's board, with its old records to beat. A dropper says "levels" and that its
 * clock keeps running after a bonk, and Start says a practice drop comes first when warm-ups are on.
 * An admin also sees 26, "Admin tools" (WP-ADM: regenerate, preview, try it, pick it), on a Fresh
 * slot's own course.
 */
public final class CourseMenu extends GameMenu {

    /** "Race with friends" (a party race, D4): the bottom row, left of the way out. */
    public static final int PARTY_SLOT = 20;
    /** The slots every course screen fills whatever the course (the way out is 22). */
    public static final List<Integer> FIXED_SLOTS = List.of(4, 10, 11, 12, 13, 14, 15, 16, 22);

    private final TimeTrials trials;
    private final Course course;

    public CourseMenu(HomeCraftManagement plugin, TimeTrials trials, Course course, Player viewer, Runnable back) {
        super(plugin, trials, viewer, back);
        this.trials = trials;
        this.course = course;
        init(27, Text.of("&b" + course.name()));
    }

    @Override
    protected void build() {
        if (course.generated()) {
            buildDaily();
            return;
        }
        fill();
        boolean week = course.id().equals(trials.courseOfWeek());
        List<String> head = new ArrayList<>();
        head.add("&7" + TrialText.route(course));
        if (week) {
            head.add("&6★ Course of the week");
        }
        if (trials.featured(course.id())) {
            head.add("&6★ Today's pick");
        }
        set(4, Menus.icon(TimeTrials.icon(course.kind()), "&e" + course.name() + " &7(" + TrialText.label(course) + ")",
                head.toArray(new String[0])), null);
        List<String> rules = new ArrayList<>(course.kind().rules());
        rules.add(course.kind() == TrialKind.DROPPER ? DropperText.CLOCK_RULE : "The clock keeps running when you go back.");
        set(10, rulesTile(rules), null);
        Long best = trials.best(viewer, course.id());
        set(11, Menus.icon(Material.CLOCK, best == null ? "&7No time yet" : "&eYour best: &f" + TrialText.time(best)),
                null);
        set(12, Menus.icon(Material.OAK_SIGN, "&eHigh scores", "&7The fastest times on " + course.name() + "."),
                e -> trials.showScores(viewer, course.id(), this::reopen));
        set(13, startTile(), e -> trials.startFromScreen(viewer, course.id()));
        GamesDao.ScoreRow weekBest = trials.weekRecord(course.id());
        set(14, Menus.icon(Material.IRON_INGOT, weekBest == null ? "&7No time this week yet"
                : "&eThis week: &f" + TrialText.time(weekBest.score()) + " &7by &f" + trials.holder(weekBest.player())),
                null);
        GamesDao.ScoreRow record = trials.record(course.id());
        set(15, Menus.icon(Material.GOLD_INGOT, record == null ? "&7No record yet - set one!"
                : "&6Record: &f" + TrialText.time(record.score()) + " &7by &f" + trials.holder(record.player())), null);
        set(16, rewards(week), null);
        if (PartyRaces.offered(course.kind())) { // WP-R1 (D4): never on a Dropper
            set(PARTY_SLOT, PartyMenu.tile(trials, viewer), e -> trials.raceWithFriends(viewer, course.id(), this::reopen));
        }
        cupButton();
        riderButton(); // WP-CH
        exitTile();
    }

    /** WP-CH: "Take a rider (back seat)" on a boat course, while ride along is on. */
    public static final int RIDER_SLOT = 26;

    private void riderButton() {
        if (!com.dierks.homecraft.games.trial.RideAlong.offered(plugin.games(), course.kind())) {
            return;
        }
        set(RIDER_SLOT, Menus.icon(Material.OAK_BOAT, com.dierks.homecraft.games.trial.RideAlong.BUTTON,
                "&7A friend rides in the back of your", "&7boat: not timed, counted or paid.",
                "&7Tap, then choose who."), e -> com.dierks.homecraft.games.trial.RideAlong.take(plugin.games(), viewer,
                course.id(), com.dierks.homecraft.games.trial.RideAlong.Purpose.SOLO, this::reopen));
    }

    /**
     * What finishing can pay, and what the player has already had. The first finish — the one
     * reward that differs from player to player — is in the name too, so Bedrock shows it without
     * a tap and hold.
     */
    private ItemStack rewards(boolean week) {
        TimeTrialsSettings s = trials.settings();
        List<String> lore = new ArrayList<>();
        int first = trials.firstClear(course);
        boolean done = first > 0 && trials.firstClearDone(viewer, course);
        if (first > 0) {
            lore.add(done ? "&a✔ First finish" : "&7First finish: &6" + TrialText.tokens(first));
        }
        if (s.weeklyBestBonus() > 0) {
            lore.add("&7Best time this week: &6" + TrialText.tokens(s.weeklyBestBonus()));
        }
        if (week && s.courseOfWeekBonus() > 0) {
            lore.add("&7Course of the week: &6" + TrialText.tokens(s.courseOfWeekBonus()) + " &7a day");
        }
        if (trials.featured(course.id()) && trials.featuredBonus() > 0) {
            lore.add("&7Today's pick: &6" + TrialText.tokens(trials.featuredBonus()));
        }
        lore.add("&7A new best is announced, not paid.");
        String name = first <= 0 ? "&eTokens for finishing"
                : done ? "&eTokens for finishing &7- first finish &a✔ done"
                : "&eTokens for finishing &7- first finish &6" + TrialText.tokens(first);
        return Menus.icon(Material.GOLD_NUGGET, name, lore.toArray(new String[0]));
    }

    /** A Fresh course: its set's layout, its stars and its board (GEN-SPEC §5.4). */
    private void buildDaily() {
        fill();
        GamesService games = plugin.games();
        GenTag t = course.gen();
        int cadence = GenCopy.words(t); // a Classic's board holds its original set's times: no "this week"
        Slots.Def slot = Slots.of(t.slot());
        boolean week = course.id().equals(trials.courseOfWeek());
        List<String> head = new ArrayList<>();
        head.add("&7" + TrialText.route(course));
        long now = games.clock().nowMillis();
        long next = trials.generated().nextChangeAt();
        if (t.recalled()) {
            head.add("&7Its old records are the ones to beat.");
        } else {
            head.add("&7" + GenCopy.schedule(cadence, DailyLookup.edition(games).rebuildDay(), null) + ".");
            if (!DailyLookup.current(games, t.slot())) {
                head.add(GenCopy.previous(cadence));
            } else if (next > now) {
                head.add(GenCopy.newIn(next - now));
            }
        }
        if (week) {
            head.add("&6★ Course of the week");
        }
        if (trials.featured(course.id())) {
            head.add("&6★ Today's pick");
        }
        set(4, Menus.icon(TimeTrials.icon(course.kind()), headerName(course, slot, DailyLookup.code(games, t)),
                head.toArray(new String[0])), null);
        List<String> rules = new ArrayList<>(course.kind().rules());
        rules.add(course.kind() == TrialKind.DROPPER ? DropperText.CLOCK_RULE : "The clock keeps running when you go back.");
        set(10, rulesTile(rules), null);
        String board = TimeTrials.board(course);
        Long best = trials.bestOn(viewer, board);
        set(11, Menus.icon(Material.CLOCK, best == null ? "&7No time " + GenCopy.when(cadence) + " yet"
                : "&e" + GenCopy.yourBest(cadence) + ": &f" + TrialText.time(best)), null);
        String whose = cadence == 1 ? "today's " : cadence == 7 ? "this week's " : "this ";
        set(12, Menus.icon(Material.OAK_SIGN, "&e" + GenCopy.times(cadence), "&7The fastest times on " + whose
                + course.name() + "."), e -> trials.showScores(viewer, course.id(), this::reopen));
        set(13, startTile(), e -> trials.startFromScreen(viewer, course.id()));
        int stars = DailyLookup.stars(games, viewer.getUniqueId(), t);
        long weekStars = DailyLookup.weekStars(games, viewer.getUniqueId(), DailyLookup.weekKey(games));
        set(14, Menus.glint(Menus.icon(Material.NETHER_STAR, DailyText.starsNow(cadence, stars),
                DailyText.starTimes(t.goldMs(), t.silverMs()), "&7Star Chart this week: &6" + weekStars + "★"),
                false), null);
        GamesDao.ScoreRow record = trials.recordOn(board);
        set(15, Menus.icon(Material.GOLD_INGOT, trials.setBestLine(record, viewer, cadence).replaceFirst("^&7", "&6")),
                null);
        set(16, dailyRewards(games, t, week), null);
        if (PartyRaces.offered(course.kind())) { // WP-R1 (D4): never on a Dropper
            set(PARTY_SLOT, PartyMenu.tile(trials, viewer), e -> trials.raceWithFriends(viewer, course.id(), this::reopen));
        }
        cupButton();
        riderButton(); // WP-CH
        adminTools(); // WP-ADM
        exitTile();
    }

    /**
     * A Fresh course's header NAME: "&amp;cHard Parkour &amp;7(Parkour · Hard) &amp;8· &amp;7Course code
     * HARD-40", or "&amp;6Classic: Hard Parkour (week of 5 Oct) ..." for a recalled one.
     */
    static String headerName(Course c, Slots.Def slot, String code) {
        String name = c.gen() != null && c.gen().recalled() ? "&6" + TimeTrials.classicName(c.gen(), c.name())
                : DailyText.colour(slot) + c.name();
        return name + " &7(" + TrialText.label(c) + ")" + DailyLookup.codeSuffix(code);
    }

    /** A Fresh course's rewards: its first finish in the set in the name (Bedrock), the rest in the lore. */
    private ItemStack dailyRewards(GamesService games, GenTag t, boolean week) {
        TimeTrialsSettings s = trials.settings();
        int cadence = GenCopy.words(t);
        int fresh = DailyLookup.freshClear(games, t);
        boolean freshDone = fresh > 0 && DailyLookup.freshClearPaid(games, viewer.getUniqueId(), trials.id(), t);
        List<String> lore = new ArrayList<>();
        String now = DailyText.firstFinish(cadence, fresh, freshDone);
        if (now != null) {
            lore.add(now);
        }
        int first = trials.firstClear(course);
        if (first > 0) {
            lore.add(trials.firstClearDone(viewer, course) ? "&a✔ Very first finish"
                    : "&7Very first finish: &6" + TrialText.tokens(first));
        }
        if (week && s.courseOfWeekBonus() > 0) {
            lore.add("&7Course of the week: &6" + TrialText.tokens(s.courseOfWeekBonus()) + " &7a day");
        }
        if (trials.featured(course.id()) && trials.featuredBonus() > 0) {
            lore.add("&7Today's pick: &6" + TrialText.tokens(trials.featuredBonus()));
        }
        lore.add("&7Stars fill your Star Chart.");
        String firstWords = GenCopy.firstFinish(cadence).toLowerCase(java.util.Locale.ROOT);
        String name = fresh <= 0 ? "&eTokens for finishing"
                : freshDone ? "&eTokens for finishing &7- " + firstWords + " &a✔ done"
                : "&eTokens for finishing &7- " + firstWords + " &6" + TrialText.tokens(fresh);
        return Menus.icon(Material.GOLD_NUGGET, name, lore.toArray(new String[0]));
    }

    /**
     * Start: the start line with only the course kit (and, for a dropper, its one practice drop, said
     * in the NAME so Bedrock shows it).
     */
    private ItemStack startTile() {
        List<String> lore = new ArrayList<>(List.of("&7You go to the start line", "&7with only the course kit.",
                "&7Your things come back when", "&7you finish or leave."));
        TimeTrialsSettings s = trials.settings();
        if (course.kind() == TrialKind.DROPPER && s.warmupsOn()) {
            lore.add(DropperText.PRACTICE_ON_START);
        }
        return Menus.icon(Material.LIME_CONCRETE, DropperText.startName(course, s), lore.toArray(new String[0]));
    }

    /**
     * The Weekly Cup's item (EVENTS-OWNER-DECISIONS D2, WP-C), when the course runs one and the viewer
     * hasn't hidden it: "Enter this week's Cup: 10 tokens. Best time wins the pool." in its NAME, or
     * that they're in with the pool. It opens the Cup screen, whose Back comes here.
     */
    private void cupButton() {
        CupLink.Button b = CupLink.button(plugin.games(), viewer, course, this::reopen);
        if (b != null) {
            set(CupLink.SLOT, b.icon(), e -> b.click().run());
        }
    }

    // ---- WP-ADM: the owner's Fresh Courses tools (hcm.games.admin only) ----
    private void adminTools() {
        FreshAdminMenu.Button b = FreshAdminMenu.button(plugin, viewer, course.id(), this::reopen);
        if (b != null) {
            set(FreshAdmin.SLOT, b.icon(), e -> b.click().run());
        }
    }
    // ---- end WP-ADM ----

    private void reopen() {
        new CourseMenu(plugin, trials, course, viewer, back).open(viewer);
    }
}
