package com.dierks.homecraft.games.cup.live;

import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.cup.CupEntry;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.games.cup.CupOptIn;
import com.dierks.homecraft.games.cup.CupPayout;
import com.dierks.homecraft.games.cup.CupPlan;
import com.dierks.homecraft.games.cup.CupRules;
import com.dierks.homecraft.games.cup.CupText;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TrialText;
import com.dierks.homecraft.storage.CupDao;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.command.CommandSender;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * {@code /hcm games cup ...} (EVENTS-OWNER-DECISIONS §D2; the framework has checked
 * {@code hcm.games.admin}):
 * <ul>
 *   <li>{@code status [course]}: this week's Cups, or one course's entrants and Cup times;</li>
 *   <li>{@code settle <course> confirm}: pay the course's running Cup out now, as at the rollover
 *       (without {@code confirm} it shows what it would pay);</li>
 *   <li>{@code void <course> confirm}: call this week's Cup on the course off, every entry back;</li>
 *   <li>{@code on|off|default <course>}: the course's own Cup switch (hand-built courses start off,
 *       Fresh parkour, Sky Rings, Ice Boat and Dropper courses on). Switching off a Cup that has
 *       entrants calls it off, after a {@code confirm}.</li>
 * </ul>
 * Every change is logged with who made it. A command that goes wrong says so here and in the
 * console; it never reaches the framework's guard.
 */
final class CupAdmin implements GameAdmin {

    static final List<String> VERBS = List.of("status", "settle", "void", "on", "off", "default");

    private final WeeklyCup cup;

    CupAdmin(WeeklyCup cup) {
        this.cup = cup;
    }

    @Override
    public String name() {
        return "cup";
    }

    @Override
    public List<String> help() {
        return List.of(
                "&e/hcm games cup status [course] &7- this week's Weekly Cups, or one course's",
                "&e/hcm games cup settle <course> confirm &7- pay a course's running Cup out now",
                "&e/hcm games cup void <course> confirm &7- call this week's Cup off, every entry back",
                "&e/hcm games cup on|off|default <course> &7- a course's Cup switch");
    }

    @Override
    public void handle(CommandSender sender, String[] args) {
        try {
            String verb = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
            boolean confirm = args.length > 0 && args[args.length - 1].equalsIgnoreCase("confirm");
            String course = args.length >= 2 && !args[1].equalsIgnoreCase("confirm")
                    ? args[1].toLowerCase(Locale.ROOT) : null;
            switch (verb) {
                case "status" -> {
                    if (course == null) {
                        status(sender);
                    } else {
                        status(sender, course);
                    }
                }
                case "settle" -> settle(sender, course, confirm, args);
                case "void" -> voidCup(sender, course, confirm, args);
                case "on", "off", "default" -> choose(sender, verb, course, confirm, args);
                default -> help().forEach(line -> sender.sendMessage(Text.of(line)));
            }
        } catch (SQLException e) {
            cup.logger().log(Level.SEVERE, "Weekly Cup: a command failed", e);
            sender.sendMessage(Text.of("&cCouldn't reach the database - see the console."));
        } catch (RuntimeException e) {
            cup.logger().log(Level.SEVERE, "Weekly Cup: /hcm games cup " + String.join(" ", args) + " failed", e);
            sender.sendMessage(Text.of("&cThat didn't work - see the console."));
        }
    }

    // ---- status ---------------------------------------------------------------------------------

    private void status(CommandSender sender) throws SQLException {
        CupDesk desk = cup.desk();
        CupSettings s = cup.settings();
        sender.sendMessage(Text.of("&6Weekly Cup &7- " + (s.enabled() ? "&aentries open" : "&centries closed "
                + "&7(games.cup.enabled: false)") + "&7: " + CupText.tokens(s.entry()) + " to enter, top-up "
                + s.serverTopup() + " with 2 or more; paid &f" + cup.when(desk.endsAt())));
        sender.sendMessage(Text.of(desk.freshEligible() ? "&7Fresh courses run a Cup by default."
                : "&7Fresh courses run no Cup: they change more often than once a week."));
        int shown = 0;
        for (Course c : courses()) {
            CupDesk.View v = desk.view(c, null);
            if (!v.on() && v.pool().in() == 0) {
                continue;
            }
            shown++;
            sender.sendMessage(Text.of("&f" + c.id() + " &7(" + c.name() + ") - " + state(c, v)));
        }
        if (shown == 0) {
            sender.sendMessage(Text.of("&7No course runs a Cup. &e/hcm games cup on <course>"));
        }
        int waiting = 0;
        long week = desk.week();
        for (CupKey k : desk.dao().openKeys()) {
            if (k.week() != week) {
                waiting++;
            }
        }
        if (waiting > 0) {
            sender.sendMessage(Text.of("&e" + waiting + " past Cup(s) waiting to be settled &7- within a minute,"
                    + " or at the next start."));
        }
    }

    private void status(CommandSender sender, String id) throws SQLException {
        CupDesk desk = cup.desk();
        Course c = course(id);
        String name = c == null ? desk.name(id) : c.name();
        CupKey key = desk.key(id);
        if (c == null) {
            sender.sendMessage(Text.of("&7There's no time-trial course called '" + id + "' now."));
        } else {
            CupDesk.View v = desk.view(c, null);
            sender.sendMessage(Text.of("&6Weekly Cup on " + name + " &7(" + id + ") - " + switchState(c) + "; "
                    + state(c, v)));
        }
        List<CupEntry> entries = desk.dao().entries(key);
        entries = new ArrayList<>(entries);
        entries.sort(CupRules.BY_CUP_TIME.thenComparing(CupRules.BY_ENTRY));
        int place = 0;
        for (CupEntry e : entries) {
            String who = holder(e);
            if (e.hasTime()) {
                sender.sendMessage(Text.of("&7" + (++place) + ". &f" + who + " &7" + TrialText.time(e.bestMs())));
            } else {
                sender.sendMessage(Text.of("&7- &f" + who + " &7no Cup time yet"));
            }
        }
        for (CupDao.Settlement st : desk.dao().settlements(id, 3)) {
            sender.sendMessage(Text.of("&7Week of " + Edition.date(st.key().week()) + ": " + CupWords.outcome(st.outcome())
                    + ", pool " + st.pool()));
        }
    }

    private String state(Course c, CupDesk.View v) {
        if (v.settledAs() == CupPlan.Outcome.VOIDED) {
            return "&ccalled off this week";
        }
        if (v.settledAs() != null) {
            return "&7settled early this week (" + CupWords.outcome(v.settledAs()) + ")";
        }
        String pool = CupText.poolLine(v.pool().tokens(), v.pool().in());
        return (v.on() ? "&aon" : "&coff") + " &7- " + pool + ", paid " + cup.when(v.endsAt());
    }

    private String switchState(Course c) throws SQLException {
        Boolean chosen = cup.desk().dao().chosen(c.id());
        boolean def = CupOptIn.byDefault(CupDesk.optInView(c), cup.desk().freshEligible());
        boolean on = cup.desk().runsCup(c);
        return (on ? "&aon" : "&coff") + " &7(" + (chosen == null ? "default" : "set by an admin; default "
                + (def ? "on" : "off")) + ")";
    }

    // ---- settle and void --------------------------------------------------------------------------

    private void settle(CommandSender sender, String id, boolean confirm, String[] args) throws SQLException {
        if (id == null) {
            sender.sendMessage(Text.of("&cUsage: /hcm games cup settle <course> confirm"));
            return;
        }
        CupDesk desk = cup.desk();
        List<CupKey> keys = new ArrayList<>();
        for (CupKey k : desk.dao().openKeys()) {
            if (k.course().equals(id)) {
                keys.add(k);
            }
        }
        if (keys.isEmpty()) {
            sender.sendMessage(Text.of("&7Nobody is in a running Cup on '" + id + "'."));
            return;
        }
        if (!confirm) {
            for (CupKey k : keys) {
                CupPlan plan = CupRules.settle(k, desk.dao().entries(k), cup.settings().serverTopup());
                sender.sendMessage(Text.of("&eSettling the Cup on " + desk.name(id) + " (week of "
                        + Edition.date(k.week()) + ") now: &f" + CupWords.outcome(plan.outcome()) + "&7, pool "
                        + plan.pool() + preview(plan)));
            }
            sender.sendMessage(Text.of("&7It then takes no more entries or times this week. Type &f/hcm games cup settle "
                    + id + " confirm"));
            return;
        }
        for (CupKey k : keys) {
            CupDesk.Closed closed = desk.settle(k);
            sender.sendMessage(Text.of(closed == null ? "&cThe Cup on " + id + " (week of " + Edition.date(k.week())
                    + ") wasn't settled - see the console."
                    : "&aSettled the Cup on " + closed.name() + ": &7" + CupWords.outcome(closed.plan().outcome())
                    + ", " + closed.plan().paidOut() + " tokens paid."));
        }
        logged(sender, args);
    }

    private void voidCup(CommandSender sender, String id, boolean confirm, String[] args) throws SQLException {
        if (id == null) {
            sender.sendMessage(Text.of("&cUsage: /hcm games cup void <course> confirm"));
            return;
        }
        CupDesk desk = cup.desk();
        List<CupEntry> entries = desk.dao().entries(desk.key(id));
        if (entries.isEmpty()) {
            sender.sendMessage(Text.of("&7Nobody is in this week's Cup on '" + id + "': nothing to call off."));
            return;
        }
        if (!confirm) {
            int back = 0;
            for (CupEntry e : entries) {
                back += e.paid();
            }
            sender.sendMessage(Text.of("&eThis calls off this week's Cup on " + desk.name(id) + ": " + entries.size()
                    + " in get their " + back + " tokens back, and nobody can enter it again this week. &7Type &f/hcm"
                    + " games cup void " + id + " confirm"));
            return;
        }
        CupDesk.Closed closed = desk.voidNow(id, CupPlan.VoidReason.STOPPED, null);
        sender.sendMessage(Text.of(closed == null ? "&cThat Cup couldn't be called off - see the console."
                : "&aCalled off the Cup on " + closed.name() + ": &7" + closed.plan().paidOut() + " tokens back to "
                + closed.plan().payouts().size() + " player(s)."));
        logged(sender, args);
    }

    private static String preview(CupPlan plan) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (CupPayout l : plan.payouts()) {
            if (n++ >= 5) {
                b.append(", ...");
                break;
            }
            b.append(n == 1 ? ": " : ", ").append(l.place() > 0 ? CupText.ordinal(l.place()) + " " : "")
                    .append(l.tokens());
        }
        return b.toString();
    }

    // ---- a course's switch ---------------------------------------------------------------------

    private void choose(CommandSender sender, String verb, String id, boolean confirm, String[] args)
            throws SQLException {
        if (id == null) {
            sender.sendMessage(Text.of("&cUsage: /hcm games cup " + verb + " <course>"));
            return;
        }
        Course c = course(id);
        if (c == null) {
            sender.sendMessage(Text.of("&cNo time-trial course called '" + id + "'. &7/hcm games course list"));
            return;
        }
        CupDesk desk = cup.desk();
        Boolean chosen = verb.equals("on") ? Boolean.TRUE : verb.equals("off") ? Boolean.FALSE : null;
        boolean eligible = desk.freshEligible();
        if (Boolean.TRUE.equals(chosen)) {
            String why = CupOptIn.cantSwitchOn(CupDesk.optInView(c), eligible);
            if (why != null) {
                sender.sendMessage(Text.of("&c" + why));
                return;
            }
        }
        boolean willRun = CupOptIn.on(chosen, CupDesk.optInView(c), eligible);
        int in = cup.entrants(id);
        if (!willRun && in > 0 && !confirm) {
            sender.sendMessage(Text.of("&eThat stops the Cup on " + c.name() + ": this week's Cup (" + in + " in) is"
                    + " called off and every entry refunded. &7Type &f/hcm games cup " + String.join(" ", args)
                    + " confirm"));
            return;
        }
        desk.dao().choose(id, chosen);
        if (!willRun && in > 0) {
            desk.voidNow(id, CupPlan.VoidReason.STOPPED, c.name());
        }
        sender.sendMessage(Text.of(willRun ? "&a" + c.name() + " runs a Weekly Cup." + (cup.settings().enabled() ? ""
                : " &7(once games.cup.enabled is true)")
                : "&a" + c.name() + " runs no Weekly Cup." + (in > 0 ? " &7This week's was called off and refunded." : "")));
        logged(sender, args);
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Every time-trial course, open or not, as its row says. */
    private List<Course> courses() throws SQLException {
        List<Course> out = new ArrayList<>();
        for (GamesDao.CourseRow row : cup.games().dao().courses(TimeTrials.SPEC.id())) {
            Course c = CourseCodec.decode(row.id(), row.data()).course();
            if (c != null) {
                out.add(c.withRev(row.rev()));
            }
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    private Course course(String id) throws SQLException {
        return cup.desk().course(id);
    }

    private String holder(CupEntry e) {
        TimeTrials t = cup.trials();
        return t == null ? e.player().toString().substring(0, 8) : t.holder(e.player());
    }

    private void logged(CommandSender sender, String[] args) {
        cup.logger().info("Games: " + sender.getName() + " - /hcm games cup " + String.join(" ", args));
    }

    @Override
    public List<String> tab(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        int n = args.length;
        if (n == 0) {
            return out;
        }
        String last = args[n - 1].toLowerCase(Locale.ROOT);
        if (n == 1) {
            for (String v : VERBS) {
                if (v.startsWith(last)) {
                    out.add(v);
                }
            }
            return out;
        }
        if (n == 2) {
            try {
                for (Course c : courses()) {
                    if (c.id().startsWith(last)) {
                        out.add(c.id());
                    }
                }
            } catch (SQLException e) {
                // no completions
            }
            return out;
        }
        if (n == 3 && !args[0].equalsIgnoreCase("status") && !args[0].equalsIgnoreCase("on")
                && "confirm".startsWith(last)) {
            out.add("confirm");
        }
        return out;
    }
}
