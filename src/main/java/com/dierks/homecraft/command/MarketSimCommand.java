package com.dierks.homecraft.command;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.MarketLabels;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.market.sim.EventKind;
import com.dierks.homecraft.market.sim.EventPlanner;
import com.dierks.homecraft.market.sim.Headlines;
import com.dierks.homecraft.market.sim.ItemStatus;
import com.dierks.homecraft.market.sim.MarketNewsService;
import com.dierks.homecraft.market.sim.MarketSimService;
import com.dierks.homecraft.market.sim.RealSymbol;
import com.dierks.homecraft.market.sim.RealWorldFetcher;
import com.dierks.homecraft.market.sim.SimLimits;
import com.dierks.homecraft.market.sim.SimSettings;
import com.dierks.homecraft.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code /hcm market news …} and {@code /hcm market sim …}: reading the Crate Market news, and
 * running and testing the live market (spec §8). Routed from {@link HcmCommand}; every call is on
 * the main thread and goes through {@link MarketSimService} / {@link MarketNewsService}, which
 * hold all the rules. This class only parses, checks permissions and reports.
 * <ul>
 *   <li>{@code /hcm market news} — right now, then the latest headlines (hcm.market.price)</li>
 *   <li>{@code /hcm market news on|off} — news in your chat on or off (hcm.market.price)</li>
 *   <li>{@code /hcm market news <item> <up|down|wanted> [percent] [quiet]} — force a flash (hcm.market.sim)</li>
 *   <li>{@code /hcm market sim status [item]}, {@code hot|deal <item> [percent] [hours]},
 *       {@code stop <item|all>}, {@code reset <item|all>} (confirm for {@code all}),
 *       {@code pause|resume}, {@code preview <item> [days]}, {@code audit [days]},
 *       {@code real status|test <symbol>|fetch} (hcm.market.sim)</li>
 * </ul>
 *
 * <p>Nothing here can loosen a limit: sizes are passed through as typed and the service holds
 * them to its code limits (news 10-25%, HOT/DEAL at most 15%, both under 90% of the headroom).
 * The only bound added here is a tighter one: a HOT/DEAL hold is at most a week.
 */
public final class MarketSimCommand {

    /** Run and test the live market: every {@code sim} verb and forced news. */
    public static final String PERMISSION = "hcm.market.sim";
    /** Reading the news and muting it: the same node as {@code /hcm market price}. */
    public static final String READ_PERMISSION = "hcm.market.price";

    /** How long a {@code sim reset all} confirmation stays armed. */
    static final long CONFIRM_WINDOW_MS = 10_000L;
    /** Headlines {@code /hcm market news} lists (spec §8: the last 8 from 7 days). */
    static final int NEWS_LINES = 8;
    /** The longest hold an admin may give a HOT or DEAL: a week. */
    static final double MAX_STORY_HOURS = 168.0;
    static final int DEFAULT_DAYS = 7;
    /** The service previews at most two weeks ({@code MarketSimService.PREVIEW_MAX_DAYS}). */
    static final int MAX_PREVIEW_DAYS = 14;
    static final int MAX_AUDIT_DAYS = 365;

    /** The {@code sim} verbs, in the order help and tab completion list them. */
    static final List<String> VERBS = List.of("status", "hot", "deal", "stop", "reset", "pause", "resume",
            "preview", "audit", "real");

    /** A number an admin typed: digits with an optional fraction and sign (no NaN, hex or exponent). */
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)");

    private final HomeCraftManagement plugin;
    /** Sender name → when they armed a {@code sim reset all}. */
    private final Map<String, Long> confirmations = new HashMap<>();

    public MarketSimCommand(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------------
    //  /hcm market news …
    // ---------------------------------------------------------------------

    /** {@code /hcm market news …}; {@code args} is the whole {@code /hcm} argument list. */
    public void news(CommandSender sender, String[] args) {
        if (args.length <= 2) {
            if (deny(sender, READ_PERMISSION)) {
                return;
            }
            MarketNewsService news = plugin.marketNews();
            if (news == null) {
                sender.sendMessage(Text.of("&7Market news is not available right now."));
                return;
            }
            news.list(sender, NEWS_LINES);
            return;
        }
        String first = args[2].toLowerCase(Locale.ROOT);
        if (args.length == 3 && (first.equals("on") || first.equals("off"))) {
            mute(sender, first.equals("off"));
            return;
        }
        if (!sender.hasPermission(PERMISSION)) {
            // A player who may read the news only has on|off after it; show them that.
            sender.sendMessage(Text.of(sender.hasPermission(READ_PERMISSION)
                    ? "&cUsage: /hcm market news [on|off]" : "&cYou don't have permission."));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(Text.of("&cUsage: /hcm market news <item> <up|down|wanted> [percent] [quiet]"));
            return;
        }
        MarketItem item = item(sender, args[2]);
        if (item == null) {
            return;
        }
        EventKind kind = EventKind.parse(args[3]);
        if (kind != EventKind.UP && kind != EventKind.DOWN && kind != EventKind.WANTED) {
            sender.sendMessage(Text.of("&cA news flash is up, down or wanted - not '" + args[3] + "'."));
            return;
        }
        NewsArgs parsed = newsArgs(args, 4);
        if (parsed.error() != null) {
            sender.sendMessage(Text.of("&c" + parsed.error()));
            sender.sendMessage(Text.of("&7Usage: /hcm market news <item> <up|down|wanted> [percent] [quiet]"));
            return;
        }
        Double percent = parsed.percent();
        if (kind == EventKind.WANTED && percent != null) {
            sender.sendMessage(Text.of("&7A WANTED flash has no size - ignoring the percent."));
            percent = null;
        }
        MarketSimService sim = running(sender);
        if (sim == null) {
            return;
        }
        if (percent != null && (percent < EventPlanner.FORCED_NEWS_MIN_PERCENT || percent > newsMaxPercent())) {
            sender.sendMessage(Text.of("&7A forced flash is held to " + whole(EventPlanner.FORCED_NEWS_MIN_PERCENT)
                    + "-" + whole(newsMaxPercent()) + "%."));
        }
        report(sender, sim.forceNews(sender, item.id(), kind, percent, parsed.quiet()));
    }

    /** {@code /hcm market news on|off}: this player's news in chat. */
    private void mute(CommandSender sender, boolean muted) {
        if (deny(sender, READ_PERMISSION)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.of("&cOnly players can turn market news on or off."));
            return;
        }
        MarketNewsService news = plugin.marketNews();
        if (news == null) {
            sender.sendMessage(Text.of("&7Market news is not available right now."));
            return;
        }
        news.setMuted(player.getUniqueId(), muted);
        player.sendMessage(Text.of(MarketLabels.muteToggle(!muted) + (muted
                ? " &8(/hcm market news on turns it back on)"
                : " &8(/hcm market news shows what you missed)")));
    }

    // ---------------------------------------------------------------------
    //  /hcm market sim …
    // ---------------------------------------------------------------------

    /** {@code /hcm market sim …}; {@code args} is the whole {@code /hcm} argument list. */
    public void sim(CommandSender sender, String[] args) {
        if (deny(sender, PERMISSION)) {
            return;
        }
        if (args.length < 3) {
            simUsage(sender);
            return;
        }
        MarketSimService sim = plugin.marketSim();
        if (sim == null) {
            sender.sendMessage(Text.of("&cThe live market failed to start - check the console."));
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender, sim, args);
            case "hot" -> story(sender, sim, args, EventKind.HOT);
            case "deal" -> story(sender, sim, args, EventKind.DEAL);
            case "stop" -> stop(sender, sim, args);
            case "reset" -> reset(sender, sim, args);
            case "pause" -> pause(sender, sim);
            case "resume" -> resume(sender, sim);
            case "preview" -> preview(sender, sim, args);
            case "audit" -> audit(sender, sim, args);
            case "real" -> real(sender, sim, args);
            default -> simUsage(sender);
        }
    }

    private void status(CommandSender sender, MarketSimService sim, String[] args) {
        String id = null;
        if (args.length >= 4) {
            MarketItem item = item(sender, args[3]);
            if (item == null) {
                return;
            }
            id = item.id();
        }
        send(sender, sim.statusLines(id));
    }

    /** {@code sim hot|deal <item> [percent] [hours]}: full strength now, announced. */
    private void story(CommandSender sender, MarketSimService sim, String[] args, EventKind kind) {
        String verb = kind.id();
        if (args.length < 4) {
            sender.sendMessage(Text.of("&cUsage: /hcm market sim " + verb + " <item> [percent] [hours]"));
            return;
        }
        MarketItem item = item(sender, args[3]);
        if (item == null) {
            return;
        }
        StoryArgs parsed = storyArgs(args, 4);
        if (parsed.error() != null) {
            sender.sendMessage(Text.of("&c" + parsed.error()));
            sender.sendMessage(Text.of("&7Usage: /hcm market sim " + verb + " <item> [percent] [hours] "
                    + "&8(12 or 12% is the size, 24h the hours)"));
            return;
        }
        if (!running(sender, sim)) {
            return;
        }
        if (parsed.percent() != null && parsed.percent() > storyMaxPercent()) {
            sender.sendMessage(Text.of("&7A " + kind.name() + " is held to at most " + whole(storyMaxPercent()) + "%."));
        }
        report(sender, sim.startStory(sender, item.id(), kind, parsed.percent(), parsed.hours()));
    }

    /** {@code sim stop <item|all>}: events fade out over an hour. */
    private void stop(CommandSender sender, MarketSimService sim, String[] args) {
        String target = target(sender, args, "stop");
        if (target == null || !running(sender, sim)) {
            return;
        }
        int n = sim.stop(target);
        log(sender, "stop " + target + ": " + n + " event(s) fading out");
        sender.sendMessage(Text.of(n == 0
                ? "&7Nothing to stop on " + where(target) + "."
                : "&aStopping &f" + n + " &aevent(s) on " + where(target) + " &7- they fade out over the next hour."));
    }

    /** {@code sim reset <item|all>}: drift to 0 and events end now; {@code all} is confirm-gated. */
    private void reset(CommandSender sender, MarketSimService sim, String[] args) {
        String target = target(sender, args, "reset");
        if (target == null || !running(sender, sim)) {
            return;
        }
        if (target.equals("all") && !confirmed(sender)) {
            sender.sendMessage(Text.of("&eThis resets the live market for &fevery &eitem: drift goes back to 0 "
                    + "and every event ends now."));
            sender.sendMessage(Text.of("&eType the command again within &f" + (CONFIRM_WINDOW_MS / 1000)
                    + "s &eto confirm."));
            return;
        }
        // reset() counts the items it reset (every item for "all"), not the events it ended.
        int n = sim.reset(target);
        log(sender, "reset " + target + ": " + n + " item(s), drift 0 and events ended");
        sender.sendMessage(Text.of(n == 0
                ? "&7Nothing to reset on " + where(target) + "."
                : "&aReset " + (target.equals("all") ? "&f" + n + " &aitem(s)" : where(target))
                        + "&a: drift back to 0 and running events ended now."));
    }

    private void pause(CommandSender sender, MarketSimService sim) {
        if (sim.paused()) {
            sender.sendMessage(Text.of("&7The live market is already paused. &f/hcm market sim resume &7starts it again."));
            return;
        }
        sim.pause();
        log(sender, "paused");
        sender.sendMessage(Text.of("&eThe live market is paused: every price is back to its usual price and "
                + "running events ended. It stays paused across restarts."));
        sender.sendMessage(Text.of("&7/hcm market sim resume &7starts it again."));
    }

    private void resume(CommandSender sender, MarketSimService sim) {
        if (!sim.paused()) {
            sender.sendMessage(Text.of("&7The live market is not paused." + (configEnabled() ? ""
                    : " &eIt is off in config.yml (market.sim.enabled: false).")));
            return;
        }
        sim.resume();
        log(sender, "resumed");
        if (sim.active()) {
            sender.sendMessage(Text.of("&aThe live market is running again. &7Drift starts from 0 and new events "
                    + "are scheduled from now."));
        } else {
            sender.sendMessage(Text.of("&eNo longer paused, but market.sim.enabled is false in config.yml, so it "
                    + "stays off until that is true and you /hcm reload."));
        }
    }

    /** {@code sim preview <item> [days]}: one possible future; changes nothing. */
    private void preview(CommandSender sender, MarketSimService sim, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Text.of("&cUsage: /hcm market sim preview <item> [days]"));
            return;
        }
        MarketItem item = item(sender, args[3]);
        if (item == null) {
            return;
        }
        int days = days(sender, args, 4, MAX_PREVIEW_DAYS);
        if (days <= 0) {
            return;
        }
        send(sender, sim.preview(item.id(), days));
    }

    /** {@code sim audit [days]}: what the sim paid out or saved, per item, from the ledger. */
    private void audit(CommandSender sender, MarketSimService sim, String[] args) {
        int days = days(sender, args, 3, MAX_AUDIT_DAYS);
        if (days <= 0) {
            return;
        }
        send(sender, sim.audit(days));
    }

    /** {@code sim real status|test <symbol>|fetch}. */
    private void real(CommandSender sender, MarketSimService sim, String[] args) {
        String sub = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "status";
        RealWorldFetcher fetcher = sim.realWorld();
        if (fetcher == null) {
            sender.sendMessage(Text.of("&cReal-world prices are not available - check the console."));
            return;
        }
        switch (sub) {
            case "status" -> send(sender, fetcher.statusLines());
            case "test" -> {
                if (args.length < 5) {
                    sender.sendMessage(Text.of("&cUsage: /hcm market sim real test <symbol> &7(e.g. "
                            + exampleSymbol() + ")"));
                    return;
                }
                // The fetcher checks the symbol and says what it fetched; nothing is written.
                fetcher.test(args[4].trim(), sender);
            }
            // The fetcher refuses (and says why) while real prices or the live market are off.
            case "fetch" -> fetcher.fetchAll(true, sender);
            default -> sender.sendMessage(Text.of("&cUsage: /hcm market sim real status|test <symbol>|fetch"));
        }
    }

    private void simUsage(CommandSender sender) {
        sender.sendMessage(Text.of("&6Live market &7- /hcm market sim …"));
        sender.sendMessage(Text.of("&e status [item] &7- what the live market is doing"));
        sender.sendMessage(Text.of("&e hot|deal <item> [percent] [hours] &7- start a HOT or DEAL at full strength now"));
        sender.sendMessage(Text.of("&e stop <item|all> &7- end events with a 1 hour fade"));
        sender.sendMessage(Text.of("&e reset <item|all> &7- drift back to 0 and end events now"));
        sender.sendMessage(Text.of("&e pause|resume &7- turn the live market off or back on (kept across restarts)"));
        sender.sendMessage(Text.of("&e preview <item> [days] &7- one possible future; changes nothing"));
        sender.sendMessage(Text.of("&e audit [days] &7- what the live market paid out or saved players"));
        sender.sendMessage(Text.of("&e real status|test <symbol>|fetch &7- real-world prices"));
        sender.sendMessage(Text.of("&e/hcm market news <item> <up|down|wanted> [percent] [quiet] &7- force a news flash now"));
    }

    // ---------------------------------------------------------------------
    //  /hcm market price, usage and help
    // ---------------------------------------------------------------------

    /**
     * {@code /hcm market price}'s extra lines while the live market runs: the usual price and the
     * mood ({@code &7  usual: &f$22.49 &7 mood: &f+11.7%}), then the badge with its time left when
     * one shows. Empty while the market is off, paused or unavailable, so the command then reads
     * exactly as it did before the live market existed. The mood is live: {@code price / usual},
     * the same two numbers the lines above it quote.
     */
    public List<String> priceLines(MarketItem item) {
        ItemStatus status = liveStatus(item.id());
        if (status == null) {
            return List.of();
        }
        MarketService market = plugin.market();
        double usual = market.usualPrice(item.id());
        double pct = usual > 0 ? (market.price(item.id()) / usual - 1.0) * 100.0 : 0.0;
        List<String> out = new ArrayList<>(2);
        out.add(MarketLabels.priceMood(plugin.economy().format(usual), pct));
        long now = System.currentTimeMillis();
        String left = status.endsAt() > now ? Headlines.left(status.endsAt() - now) : "";
        String line = MarketLabels.priceStatus(status.badge(), pct, left);
        if (!line.isEmpty()) {
            out.add(line);
        }
        return out;
    }

    /** The {@code /hcm market} usage parts this sender may run, for {@code HcmCommand.marketUsage}. */
    public static List<String> usageParts(CommandSender sender) {
        List<String> out = new ArrayList<>(3);
        if (sender.hasPermission(READ_PERMISSION)) {
            out.add("news [on|off]");
        }
        if (sender.hasPermission(PERMISSION)) {
            out.add("news <item> <up|down|wanted> [percent] [quiet]");
            out.add("sim <" + String.join("|", VERBS) + "> …");
        }
        return out;
    }

    /** The {@code /hcm} help lines for the news and the live market this sender may run. */
    public static List<String> helpLines(CommandSender sender) {
        List<String> out = new ArrayList<>(3);
        if (sender.hasPermission(READ_PERMISSION)) {
            out.add("&e/hcm market news [on|off] &7- what's new at the Crate Market; news in chat on or off");
        }
        if (sender.hasPermission(PERMISSION)) {
            out.add("&e/hcm market news <item> <up|down|wanted> [percent] [quiet] &7- force a news flash now");
            out.add("&e/hcm market sim " + String.join("|", VERBS) + " &7- run and test the live market");
        }
        return out;
    }

    // ---------------------------------------------------------------------
    //  tab completion
    // ---------------------------------------------------------------------

    /** Completions for {@code /hcm market news …} and {@code /hcm market sim …} ({@code args.length >= 3}). */
    public List<String> complete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length < 3) {
            return out;
        }
        boolean admin = sender.hasPermission(PERMISSION);
        String last = args[args.length - 1];
        int n = args.length;
        if (args[1].equalsIgnoreCase("news")) {
            if (n == 3) {
                if (sender instanceof Player && sender.hasPermission(READ_PERMISSION)) {
                    match(out, last, "on", "off");
                }
                if (admin) {
                    items(out, last);
                }
            } else if (admin && n >= 4 && !isOnOff(args[2])) {
                EventKind kind = EventKind.parse(args[3]);
                if (n == 4) {
                    match(out, last, "up", "down", "wanted");
                } else if (n == 5 && kind == EventKind.WANTED) {
                    match(out, last, "quiet");
                } else if (n == 5 && (kind == EventKind.UP || kind == EventKind.DOWN)) {
                    match(out, last, "10", "15", "20", "25", "quiet");
                } else if (n == 6 && (kind == EventKind.UP || kind == EventKind.DOWN)
                        && !args[4].equalsIgnoreCase("quiet")) {
                    match(out, last, "quiet");
                }
            }
            return out;
        }
        if (!admin || !args[1].equalsIgnoreCase("sim")) {
            return out;
        }
        if (n == 3) {
            match(out, last, VERBS.toArray(new String[0]));
            return out;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                if (n == 4) {
                    items(out, last);
                }
            }
            case "hot", "deal" -> {
                if (n == 4) {
                    items(out, last);
                } else if (n == 5) {
                    match(out, last, "8", "10", "12", "15");
                } else if (n == 6) {
                    match(out, last, "12", "24", "48");
                }
            }
            case "stop", "reset" -> {
                if (n == 4) {
                    match(out, last, "all");
                    items(out, last);
                }
            }
            case "preview" -> {
                if (n == 4) {
                    items(out, last);
                } else if (n == 5) {
                    match(out, last, "1", "3", "7", "14");
                }
            }
            case "audit" -> {
                if (n == 4) {
                    match(out, last, "1", "7", "30");
                }
            }
            case "real" -> {
                if (n == 4) {
                    match(out, last, "status", "test", "fetch");
                } else if (n == 5 && args[3].equalsIgnoreCase("test")) {
                    SimSettings.Real real = settings().real();
                    for (RealSymbol row : real.symbols()) {
                        String symbol = row.symbol(real.provider());
                        if (!symbol.isEmpty() && symbol.toLowerCase(Locale.ROOT).startsWith(last.toLowerCase(Locale.ROOT))) {
                            out.add(symbol);
                        }
                    }
                }
            }
            default -> {
                // nothing to offer
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    //  parsing (pure)
    // ---------------------------------------------------------------------

    /**
     * A size or length an admin typed: {@code 20}, {@code 20%} or {@code 20h}, as its magnitude
     * ({@code -20} reads 20: the direction always comes from the verb). {@code unit} is '%', 'h'
     * or 0 for a bare number.
     */
    record Amount(double value, char unit) {
    }

    /** {@code [percent] [quiet]} after a forced flash's kind, or why they could not be read. */
    record NewsArgs(Double percent, boolean quiet, String error) {
    }

    /** {@code [percent] [hours]} after a HOT/DEAL's item, or why they could not be read. */
    record StoryArgs(Double percent, Double hours, String error) {
    }

    /** {@code raw} as an {@link Amount}; {@code null} when it is not a plain number. */
    static Amount amount(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        char unit = 0;
        if (s.endsWith("%") || s.endsWith("h")) {
            unit = s.charAt(s.length() - 1);
            s = s.substring(0, s.length() - 1);
        }
        if (!NUMBER.matcher(s).matches()) {
            return null;
        }
        double v = Math.abs(Double.parseDouble(s));
        return Double.isFinite(v) ? new Amount(v, unit) : null;
    }

    /** {@code args[from..]} of a forced flash: at most one percent, and {@code quiet}, in any order. */
    static NewsArgs newsArgs(String[] args, int from) {
        Double percent = null;
        boolean quiet = false;
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            if (a.equalsIgnoreCase("quiet")) {
                quiet = true;
                continue;
            }
            Amount x = amount(a);
            if (x == null || x.unit() == 'h') {
                return new NewsArgs(null, false, "'" + a + "' is not a percent (like 20) or 'quiet'.");
            }
            if (percent != null) {
                return new NewsArgs(null, false, "Give the percent once.");
            }
            if (!(x.value() > 0)) {
                return new NewsArgs(null, false, "The percent must be more than 0.");
            }
            percent = x.value();
        }
        return new NewsArgs(percent, quiet, null);
    }

    /**
     * {@code args[from..]} of a HOT/DEAL: a bare number is the percent, then the hours;
     * {@code 12%} and {@code 24h} say which. Hours must be more than 0 and at most
     * {@value #MAX_STORY_HOURS} (a week).
     */
    static StoryArgs storyArgs(String[] args, int from) {
        Double percent = null;
        Double hours = null;
        boolean bareIsPercent = false;
        String tooMany = "Too many numbers: give at most one percent and one number of hours.";
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            Amount x = amount(a);
            if (x == null) {
                return new StoryArgs(null, null, "'" + a + "' is not a number.");
            }
            if (x.unit() == '%') {
                // "24 12%": the bare number read as the percent was the hours after all.
                if (percent != null && bareIsPercent && hours == null) {
                    hours = percent;
                    percent = null;
                    bareIsPercent = false;
                }
                if (percent != null) {
                    return new StoryArgs(null, null, tooMany);
                }
                percent = x.value();
            } else if (x.unit() == 'h') {
                if (hours != null) {
                    return new StoryArgs(null, null, tooMany);
                }
                hours = x.value();
            } else if (percent == null) {
                percent = x.value();
                bareIsPercent = true;
            } else if (hours == null) {
                hours = x.value();
            } else {
                return new StoryArgs(null, null, tooMany);
            }
        }
        if (percent != null && !(percent > 0)) {
            return new StoryArgs(null, null, "The percent must be more than 0.");
        }
        if (hours != null && !(hours > 0 && hours <= MAX_STORY_HOURS)) {
            return new StoryArgs(null, null, "Hours must be more than 0 and at most " + whole(MAX_STORY_HOURS)
                    + " (a week).");
        }
        return new StoryArgs(percent, hours, null);
    }

    /** {@code args[idx]} as a whole number of days in {@code 1..max}; the default when absent. */
    static int parseDays(String[] args, int idx, int max) {
        if (args.length <= idx) {
            return DEFAULT_DAYS;
        }
        try {
            int d = Integer.parseInt(args[idx].trim());
            return d < 1 ? -1 : Math.min(d, max);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------------
    //  helpers
    // ---------------------------------------------------------------------

    /**
     * {@code id}'s live status while the market runs (enabled and not paused), else {@code null}.
     * A failing read is logged and treated as "off", so {@code /hcm market price} always answers.
     */
    private ItemStatus liveStatus(String id) {
        MarketSimService sim = plugin.marketSim();
        if (sim == null) {
            return null;
        }
        try {
            return sim.active() ? sim.status(id) : null;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Live market read failed: " + ex);
            return null;
        }
    }

    /** The live market when it is running; otherwise tells the sender why not and returns null. */
    private MarketSimService running(CommandSender sender) {
        MarketSimService sim = plugin.marketSim();
        if (sim == null) {
            sender.sendMessage(Text.of("&cThe live market failed to start - check the console."));
            return null;
        }
        return running(sender, sim) ? sim : null;
    }

    private boolean running(CommandSender sender, MarketSimService sim) {
        if (sim.active()) {
            return true;
        }
        if (sim.paused()) {
            sender.sendMessage(Text.of("&eThe live market is paused. &f/hcm market sim resume &estarts it again."));
        } else {
            sender.sendMessage(Text.of("&eThe live market is off (market.sim.enabled: false in config.yml)."));
        }
        return false;
    }

    /** The catalog item {@code raw} names (any case); tells the sender and returns null when there is none. */
    private MarketItem item(CommandSender sender, String raw) {
        MarketItem item = plugin.market().item(raw.toLowerCase(Locale.ROOT));
        if (item == null) {
            sender.sendMessage(Text.of("&cNo market item '" + raw + "'."));
        }
        return item;
    }

    /** {@code <item|all>} at {@code args[3]}: {@code "all"} or a real item id; null (and told) otherwise. */
    private String target(CommandSender sender, String[] args, String verb) {
        if (args.length < 4) {
            sender.sendMessage(Text.of("&cUsage: /hcm market sim " + verb + " <item|all>"));
            return null;
        }
        if (args[3].equalsIgnoreCase("all")) {
            return "all";
        }
        MarketItem item = item(sender, args[3]);
        return item == null ? null : item.id();
    }

    private int days(CommandSender sender, String[] args, int idx, int max) {
        int days = parseDays(args, idx, max);
        if (days <= 0) {
            sender.sendMessage(Text.of("&cDays must be a whole number from 1 to " + max + "."));
        }
        return days;
    }

    /**
     * Two-step confirmation for {@code sim reset all}: the first call arms, a second call from the
     * same sender within {@link #CONFIRM_WINDOW_MS} runs it.
     */
    private boolean confirmed(CommandSender sender) {
        String key = sender.getName();
        long now = System.currentTimeMillis();
        Long armed = confirmations.get(key);
        if (armed != null && now - armed <= CONFIRM_WINDOW_MS) {
            confirmations.remove(key);
            return true;
        }
        confirmations.put(key, now);
        return false;
    }

    private static void report(CommandSender sender, MarketSimService.Result result) {
        if (result == null) {
            sender.sendMessage(Text.of("&cThe live market did not answer - check the console."));
            return;
        }
        String message = result.message();
        if (message == null || message.isBlank()) {
            message = result.ok() ? "Done." : "Refused.";
        }
        sender.sendMessage(Text.of((result.ok() ? "&a" : "&c") + message));
    }

    private static void send(CommandSender sender, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            sender.sendMessage(Text.of("&7Nothing to show."));
            return;
        }
        for (String line : lines) {
            sender.sendMessage(Text.of(line));
        }
    }

    /** Who did what, for the console: admin actions on the live market are logged with the sender. */
    private void log(CommandSender sender, String what) {
        plugin.getLogger().info("[Market] sim " + what + " (by " + sender.getName() + ")");
    }

    private static String where(String target) {
        return target.equals("all") ? "&fevery item" : "&f" + target;
    }

    private SimSettings settings() {
        return plugin.config().marketSim().settings();
    }

    private boolean configEnabled() {
        return plugin.config().marketSim().enabled();
    }

    /** A symbol from the configured rows for the configured provider, for help text; {@code gc.f} if none. */
    private String exampleSymbol() {
        SimSettings.Real real = settings().real();
        for (RealSymbol row : real.symbols()) {
            String symbol = row.symbol(real.provider());
            if (!symbol.isEmpty()) {
                return symbol;
            }
        }
        return "gc.f";
    }

    /** The news cap as a percent (25), rounded to 0.01 so {@code 0.25 * 100} reads exactly. */
    static double newsMaxPercent() {
        return Math.round(SimLimits.NEWS_MAX * 10_000.0) / 100.0;
    }

    /** The HOT/DEAL cap as a percent (15), rounded to 0.01 so {@code 0.15 * 100} reads exactly. */
    static double storyMaxPercent() {
        return Math.round(SimLimits.STORY_MAX * 10_000.0) / 100.0;
    }

    /** {@code 25.0} as {@code 25}; a fraction keeps its decimals. */
    static String whole(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private static boolean isOnOff(String s) {
        return s.equalsIgnoreCase("on") || s.equalsIgnoreCase("off");
    }

    private boolean deny(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return false;
        }
        sender.sendMessage(Text.of("&cYou don't have permission."));
        return true;
    }

    private void items(List<String> out, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (MarketItem item : plugin.market().catalog()) {
            if (item.id().startsWith(lower)) {
                out.add(item.id());
            }
        }
    }

    private static void match(List<String> out, String prefix, String... options) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.startsWith(lower)) {
                out.add(option);
            }
        }
    }
}
