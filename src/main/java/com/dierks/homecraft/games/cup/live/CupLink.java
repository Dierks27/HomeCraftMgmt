package com.dierks.homecraft.games.cup.live;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.cup.CupPlan;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.FairPlay;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;

/**
 * Where the rest of the plugin calls the Weekly Cup: one line in each place it hooks (Time Trials'
 * finish and tiles, the course screen, the course editor, {@code /hcm play cup}), and the Cup's own
 * code here. Every call finds the Cup game and runs inside ITS guard, so a bug in the Cup switches the
 * Cup off and never Time Trials; with the Cup missing or failed, each call does nothing.
 */
public final class CupLink {

    /**
     * Where the Cup's item sits on the 27-slot course screen: the bottom row, right of the way out
     * (22). "Race with friends" has 20, left of it.
     */
    public static final int SLOT = 24;

    private CupLink() {
    }

    /** The Cup game, unless it is missing or failed. */
    static WeeklyCup cup(GamesService games) {
        Game g = games == null ? null : games.game(WeeklyCup.SPEC.id());
        return g instanceof WeeklyCup w && !games.failed(w) ? w : null;
    }

    /** The Cup game while it is open to players (the games on, it hasn't failed). */
    static WeeklyCup open(GamesService games) {
        WeeklyCup w = cup(games);
        return w != null && games.enabled(w) ? w : null;
    }

    // ---- Time Trials' finish ------------------------------------------------------------------

    /**
     * Whether a finish may set a Cup time: the run COUNTED under every fair-play rule (never a test,
     * a voided run or a run on a course that changed under it), and it was the timed run, not its
     * warm-up (owner decision D3: warm-ups never count toward the Cup).
     */
    public static boolean counts(FairPlay.Verdict verdict, boolean warmup) {
        return verdict != null && verdict.kind() == FairPlay.Kind.COUNTED && !warmup;
    }

    /**
     * Time Trials' finish: a counted, timed run on {@code ranOn} (the course as the run kept it) may set
     * the player's Cup time. Anything else never reaches the Cup.
     */
    public static void finished(GamesService games, Player player, Course ranOn, long ms, FairPlay.Verdict verdict,
                                boolean warmup) {
        if (!counts(verdict, warmup) || player == null || ranOn == null) {
            return;
        }
        WeeklyCup w = open(games);
        if (w != null) {
            games.guard(w, () -> w.counted(player, ranOn, ms));
        }
    }

    // ---- the tiles and the course screen -------------------------------------------------------

    /** This week's Cup on {@code c} for {@code viewer}'s tile or screen, or {@code null} (none shown). */
    static CupDesk.View view(GamesService games, Player viewer, Course c) {
        WeeklyCup w = open(games);
        if (w == null || viewer == null || c == null) {
            return null;
        }
        CupDesk.View v = games.guard(w, () -> w.view(c, viewer.getUniqueId(), false), null);
        return v != null && v.shown() ? v : null;
    }

    /** What {@code c}'s tile adds to its NAME for {@code viewer} (" · Cup pool 35"), or "". */
    public static String tileSuffix(GamesService games, Player viewer, Course c) {
        return CupWords.tileSuffix(view(games, viewer, c));
    }

    /** What {@code c}'s tile adds to its lore for {@code viewer}: the prompt and the pool. */
    public static List<String> tileLines(GamesService games, Player viewer, Course c) {
        return CupWords.tileLines(view(games, viewer, c));
    }

    /** The course screen's Cup item, with what a click does. */
    public record Button(ItemStack icon, Runnable click) {
    }

    /**
     * The course screen's Cup item for {@code c} ("Enter this week's Cup: 5 tokens. Best time wins the
     * pool." in its NAME), or {@code null} when the Cup isn't shown there (off on the course, hidden by
     * the player, closed). A click opens the Cup screen, whose Back returns to {@code back}.
     */
    public static Button button(GamesService games, Player viewer, Course c, Runnable back) {
        WeeklyCup w = open(games);
        CupDesk.View v = view(games, viewer, c);
        if (w == null || v == null) {
            return null;
        }
        ItemStack icon = games.guard(w, () -> Menus.icon(Material.GOLD_BLOCK, CupWords.buttonName(v),
                CupWords.buttonLore(v, w.when(v.endsAt())).toArray(new String[0])), null);
        if (icon == null) {
            return null;
        }
        return new Button(icon, () -> games.guard(w, () -> w.openScreen(viewer, c, back)));
    }

    // ---- the course editor ---------------------------------------------------------------------

    /** How many are in this week's Cup on {@code courseId} (0 without a Cup). */
    public static int entrants(GamesService games, String courseId) {
        WeeklyCup w = cup(games);
        return w == null ? 0 : games.guard(w, () -> w.entrants(courseId), 0);
    }

    /**
     * The line an admin's confirm prompt adds when an edit would call this week's Cup off, or
     * {@code null} when nobody is in it.
     */
    public static String warning(GamesService games, String courseId) {
        int n = entrants(games, courseId);
        return n <= 0 ? null : "&6This week's Cup on it (" + n + " in) is called off and every entry refunded.";
    }

    /**
     * An admin deleted, re-made or closed a course: call this week's Cup on it off now, every entry
     * back with the reason, and tell the admin. The minute watch would find it too; this is at once.
     */
    public static void courseChanged(GamesService games, CommandSender admin, String courseId, String name,
                                     CupPlan.VoidReason reason) {
        WeeklyCup w = cup(games);
        if (w == null) {
            return;
        }
        CupDesk.Closed closed = games.guard(w, () -> w.voidNow(courseId, reason, name), null);
        if (closed != null && admin != null) {
            admin.sendMessage(Text.of("&6This week's Cup on " + name + " was called off: &7" + closed.plan().paidOut()
                    + " tokens went back to " + closed.plan().payouts().size() + " player(s)."));
        }
    }

    // ---- /hcm play cup [on|off] ------------------------------------------------------------------

    /** {@code /hcm play cup [on|off]}: show or switch the Cup prompts; {@code args} is the whole list. */
    public static void command(HomeCraftManagement plugin, Player player, String[] args) {
        GamesService games = plugin.games();
        WeeklyCup w = cup(games);
        if (w == null) {
            player.sendMessage(Text.of("&cThe Weekly Cup isn't available right now."));
            return;
        }
        if (args.length >= 3 && (args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) {
            boolean on = args[2].equalsIgnoreCase("on");
            boolean saved = games.guard(w, () -> {
                try {
                    w.show(player.getUniqueId(), on);
                    return true;
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Could not save a player's Weekly Cup setting", e);
                    return false;
                }
            }, false);
            player.sendMessage(Text.of(saved ? CupWords.promptsSet(on) : "&cThat couldn't be saved - try again in a moment."));
            return;
        }
        for (String line : games.guard(w, () -> w.summary(player.getUniqueId()), List.<String>of())) {
            player.sendMessage(Text.of(line));
        }
    }
}
