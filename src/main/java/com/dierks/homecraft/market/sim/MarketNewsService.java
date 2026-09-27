package com.dierks.homecraft.market.sim;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.MarketLabels;
import com.dierks.homecraft.mini.AnnounceService;
import com.dierks.homecraft.storage.MarketSimDao;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * How market news reaches players (spec §6.1-6.3): the broadcast a tick (or an admin) chose, the
 * join catch-up, the per-player mute, the {@code /hcm market news} list, and who is online for the
 * announcement gate. Every exact string comes from {@link MarketLabels}; this class only decides
 * who gets which line, and how (chat, title, action bar, sound, particles).
 *
 * <p><b>Delivery.</b> Chat, title, action bar (three times, about 5 s) and sound go to every online
 * player who has not muted the news; Bedrock players get the same lines, which all read fine
 * without the hover. The price line carries the hover {@code Click to see the price} and runs
 * {@code /hcm market price <id>}. Particles go to the item's displays. One INFO console line per
 * announcement. A {@code quiet} admin flash is only logged. Last calls and endings are one quiet
 * chat line each.
 *
 * <p><b>Catch-up and mute.</b> 60 ticks after a join the player gets "While you were away" (at most
 * once per 10 minutes): news with an id above their mark from the last {@code catch_up_hours},
 * then the "Right now" line. A player with no row yet only gets "Right now", and their mark starts
 * at the newest event, so an upgrade does not replay old news. A live broadcast moves the mark of
 * everyone who heard it. Muted players get no chat, title, action bar, sound or catch-up.
 *
 * <p>Main thread only.
 */
public final class MarketNewsService {

    /** The catch-up runs this many ticks after a join (3 s). */
    static final long CATCH_UP_DELAY_TICKS = 60L;
    /** At most one catch-up per player per this long. */
    static final long CATCH_UP_EVERY_MS = 10 * SimMath.MINUTE_MS;
    /** Players already online when the plugin (re)starts count as on for this long. */
    static final long RESTART_SESSION_MS = 10 * SimMath.MINUTE_MS;
    /** HOT/DEAL and the other chimes play at this pitch. */
    static final double STORY_PITCH = 1.2;
    /** {@code /hcm market news} shows at most this many entries … */
    static final int LIST_MAX = 50;
    /** … from this far back. */
    static final long LIST_WINDOW_MS = 7 * SimMath.DAY_MS;

    /** What one announcement looks and sounds like. */
    private record Delivery(List<String> lines, String hoverId, MarketLabels.Title title, String actionBar,
                            String sound, double pitch, Boolean particlesUp, String console, boolean advance) {
    }

    private final HomeCraftManagement plugin;
    private final MarketSimDao dao;
    private MarketSimService sim;
    /** When each online player joined (their session length gates announcements). */
    private final Map<UUID, Long> joins = new HashMap<>();
    /** Each online player's mute, read once per session. */
    private final Map<UUID, Boolean> muted = new HashMap<>();

    public MarketNewsService(HomeCraftManagement plugin, MarketSimDao dao) {
        this.plugin = plugin;
        this.dao = dao;
        prime();
    }

    /** The sim this service announces for (set by its constructor). */
    void attach(MarketSimService service) {
        this.sim = service;
    }

    /** Players online at a (re)start count as having joined 10 minutes ago. */
    void prime() {
        long now = System.currentTimeMillis();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            joins.putIfAbsent(p.getUniqueId(), now - RESTART_SESSION_MS);
        }
    }

    // ---- broadcasts -------------------------------------------------------------------------

    /**
     * Deliver one announcement (§6.2): the lines for its type, the title and action bar for a
     * flash or a HOT/DEAL, the sound, the particles, and one INFO console line. {@code quiet}
     * (an admin's quiet flash) only logs it.
     */
    public void broadcast(AnnounceGate.Pending p, boolean quiet) {
        if (p == null) {
            return;
        }
        MarketSimService s = sim;
        SimSettings.Announce a = settings().announce();
        long now = System.currentTimeMillis();
        Delivery d = delivery(p, s, a, now);
        if (d == null) {
            return;
        }
        plugin.getLogger().info(d.console() + (quiet ? " (quiet)" : ""));
        if (quiet) {
            return;
        }
        List<Player> to = recipients();
        List<Component> chat = new ArrayList<>(d.lines().size());
        for (int i = 0; i < d.lines().size(); i++) {
            Component c = Text.of(d.lines().get(i));
            if (d.hoverId() != null && i == d.lines().size() - 1) {
                c = c.hoverEvent(HoverEvent.showText(Text.of(MarketLabels.PRICE_HOVER)))
                        .clickEvent(ClickEvent.runCommand(MarketLabels.priceCommand(d.hoverId())));
            }
            chat.add(c);
        }
        Title title = d.title() == null || !a.title() ? null : Title.title(Text.of(d.title().title()),
                Text.of(d.title().subtitle()), Title.Times.times(Duration.ofMillis(MarketLabels.TITLE_FADE_IN_MS),
                        Duration.ofMillis(MarketLabels.TITLE_STAY_MS), Duration.ofMillis(MarketLabels.TITLE_FADE_OUT_MS)));
        boolean bar = a.actionBar() && d.actionBar() != null && !d.actionBar().isEmpty();
        List<UUID> heard = new ArrayList<>(to.size());
        for (Player pl : to) {
            heard.add(pl.getUniqueId());
            try {
                if (a.chat()) {
                    for (Component c : chat) {
                        pl.sendMessage(c);
                    }
                }
                if (title != null) {
                    pl.showTitle(title);
                }
                if (bar) {
                    pl.sendActionBar(Text.of(d.actionBar()));
                }
                if (d.sound() != null) {
                    pl.playSound(pl.getLocation(), AnnounceService.sound(d.sound()), (float) a.volume(),
                            (float) d.pitch());
                }
            } catch (RuntimeException ex) {
                // one player's client is never worth the rest of the broadcast
            }
        }
        if (bar) {
            repeatActionBar(heard, d.actionBar());
        }
        if (a.particles() && d.particlesUp() != null && d.hoverId() != null && plugin.displayService() != null) {
            try {
                plugin.displayService().celebrate(d.hoverId(), d.particlesUp());
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Live market: could not show the news particles.", ex);
            }
        }
        MarketEvent e = p.event();
        if (d.advance() && e != null && e.id() > 0 && !heard.isEmpty()) {
            try {
                dao.advanceSeen(heard, e.id());
            } catch (SQLException ex) {
                plugin.getLogger().warning("Live market: could not mark the news as seen: " + ex.getMessage());
            }
        }
    }

    /** One chat line to everyone listening (no title, no sound); nothing while chat news is off. */
    public void oneLiner(String legacy) {
        if (legacy == null || MarketLabels.plain(legacy).isEmpty() || !settings().announce().chat()) {
            return;
        }
        Component c = Text.of(legacy);
        for (Player p : recipients()) {
            try {
                p.sendMessage(c);
            } catch (RuntimeException ignored) {
                // cosmetic
            }
        }
    }

    private Delivery delivery(AnnounceGate.Pending p, MarketSimService s, SimSettings.Announce a, long now) {
        MarketEvent e = p.event();
        if (p.type() == AnnounceGate.Type.INTRO) {
            return new Delivery(MarketLabels.intro(), null, null, "", a.soundOther(), STORY_PITCH, null,
                    "[Market] INTRO The Crate Market is LIVE!", false);
        }
        if (e == null) {
            return null;
        }
        String id = e.itemId();
        String name = s == null ? nz(id) : s.displayName(id);
        String headline = nz(e.headline());
        boolean priced = e.kind().mood() || e.kind() == EventKind.REAL;
        String before = s == null || !priced ? "" : s.money(e.priceBefore());
        String after = s == null || !priced ? "" : s.money(e.priceAfter());
        String console = MarketLabels.consoleLine(e, id != null ? id : nz(e.tag()), before, after);
        switch (p.type()) {
            case FLASH, STORY -> {
                switch (e.kind()) {
                    case UP -> {
                        List<String> lines = MarketLabels.newsLines(e, name, headline,
                                s == null ? before : s.money(s.bid(id, e.priceBefore())),
                                s == null ? after : s.money(s.bid(id, e.priceAfter())), 0L);
                        return new Delivery(lines, id, MarketLabels.titleFor(e.kind(), name).orElse(null),
                                MarketLabels.actionBar(e.kind(), name, plural(s, id)), a.soundUp(), a.pitchUp(),
                                Boolean.TRUE, console, true);
                    }
                    case DOWN -> {
                        List<String> lines = MarketLabels.newsLines(e, name, headline,
                                s == null ? before : s.money(s.ask(id, e.priceBefore())),
                                s == null ? after : s.money(s.ask(id, e.priceAfter())),
                                s == null ? 0L : s.buyLimit(id, EventKind.DOWN));
                        return new Delivery(lines, id, MarketLabels.titleFor(e.kind(), name).orElse(null),
                                MarketLabels.actionBar(e.kind(), name, plural(s, id)), a.soundDown(), a.pitchDown(),
                                Boolean.FALSE, console, true);
                    }
                    case HOT, DEAL -> {
                        String left = Headlines.left(Math.max(0L, e.endsAt() - now));
                        List<String> lines = MarketLabels.storyLines(e, name, headline, left,
                                s == null ? 0L : s.buyLimit(id, e.kind()));
                        return new Delivery(lines, id, MarketLabels.titleFor(e.kind(), name).orElse(null),
                                MarketLabels.actionBar(e.kind(), name, plural(s, id)), a.soundStory(), STORY_PITCH,
                                Boolean.TRUE, console, true);
                    }
                    case WANTED -> {
                        return new Delivery(MarketLabels.wantedLines(headline), id, null,
                                MarketLabels.actionBar(e.kind(), name, plural(s, id)), a.soundOther(), STORY_PITCH,
                                null, console, true);
                    }
                    default -> {
                        return null;
                    }
                }
            }
            case SEASON -> {
                String effects = s == null ? "" : s.seasonEffects(e);
                String until = s == null ? "" : MarketLabels.untilDate(s.lastDay(e));
                return new Delivery(MarketLabels.seasonLines(headline, effects, until), null, null, "",
                        a.soundOther(), STORY_PITCH, null, console, true);
            }
            case REAL -> {
                return new Delivery(MarketLabels.realLines(e, name, headline), id, null, "", a.soundOther(),
                        STORY_PITCH, null, console, true);
            }
            case LAST_CALL -> {
                String line = MarketLabels.lastCall(e.kind(), name, Math.max(0L, e.holdEndsAt() - now));
                return new Delivery(List.of(line), null, null, "", null, 1.0, null,
                        "[Market] LAST CALL " + e.kind().name() + " " + nz(id), false);
            }
            case ENDING -> {
                String line = MarketLabels.ending(e.kind(), name);
                return new Delivery(List.of(line), null, null, "", null, 1.0, null,
                        "[Market] ENDING " + e.kind().name() + " " + nz(id), false);
            }
            default -> {
                return null;
            }
        }
    }

    private void repeatActionBar(List<UUID> players, String text) {
        for (long delay : MarketLabels.ACTION_BAR_TICKS) {
            if (delay <= 0) {
                continue;
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                Component c = Text.of(text);
                for (UUID u : players) {
                    Player p = plugin.getServer().getPlayer(u);
                    if (p != null && p.isOnline() && !muted(u)) {
                        try {
                            p.sendActionBar(c);
                        } catch (RuntimeException ignored) {
                            // cosmetic
                        }
                    }
                }
            }, delay);
        }
    }

    // ---- catch-up, list, mute ---------------------------------------------------------------

    /**
     * "While you were away" (§6.3): the news this player has not seen from the last
     * {@code catch_up_hours}, newest first, at most {@code catch_up_lines}, then the "Right now"
     * line. Once per 10 minutes at most; never for a muted player.
     */
    public void catchUp(Player p) {
        MarketSimService s = sim;
        if (p == null || !p.isOnline() || s == null || !s.active()) {
            return;
        }
        SimSettings.Announce a = s.settings().announce();
        if (!a.catchUp()) {
            return;
        }
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        Optional<MarketSimDao.Seen> seen;
        try {
            seen = dao.seen(u);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Live market: could not read " + p.getName() + "'s news mark: " + ex.getMessage());
            return;
        }
        if (seen.isPresent() && seen.get().muted()) {
            muted.put(u, true);
            return;
        }
        if (seen.isPresent() && now - seen.get().seenAt() < CATCH_UP_EVERY_MS) {
            return;
        }
        String rightNow = s.rightNowLine(now);
        List<String> lines;
        long mark;
        try {
            if (seen.isEmpty()) {
                lines = MarketLabels.catchUp(List.of(), 0, rightNow);
                mark = dao.maxEventId();
            } else {
                long since = now - a.catchUpHours() * SimMath.HOUR_MS;
                List<MarketEvent> fresh = new ArrayList<>();
                mark = seen.get().lastEventId();
                for (MarketEvent e : s.recentNews(Integer.MAX_VALUE, since)) {
                    if (e.id() > seen.get().lastEventId()) {
                        fresh.add(e);
                        mark = Math.max(mark, e.id());
                    }
                }
                int shown = Math.min(a.catchUpLines(), fresh.size());
                List<String> bullets = new ArrayList<>(shown);
                for (int i = 0; i < shown; i++) {
                    MarketEvent e = fresh.get(i);
                    bullets.add(MarketLabels.catchUpLine(s.summary(e),
                            Headlines.ago(Math.max(0L, now - MarketSimService.newsTime(e)))));
                }
                lines = MarketLabels.catchUp(bullets, fresh.size() - shown, rightNow);
            }
            for (String line : lines) {
                p.sendMessage(Text.of(line));
            }
            dao.setSeen(u, mark, now);
            muted.put(u, false);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Live market: could not update " + p.getName() + "'s news mark: "
                    + ex.getMessage());
        }
    }

    /**
     * {@code /hcm market news}: what is going on right now (HOT/DEAL/news with time left, the
     * season), then the last {@code n} headlines of the last 7 days with their ages, then how to
     * mute or unmute.
     */
    public void list(CommandSender to, int n) {
        if (to == null) {
            return;
        }
        int count = Math.max(1, Math.min(LIST_MAX, n <= 0 ? 8 : n));
        List<String> out = new ArrayList<>();
        out.add(MarketLabels.NEWS_MENU_HEADER);
        MarketSimService s = sim;
        long now = System.currentTimeMillis();
        if (s == null || !s.active()) {
            out.add("&7 The live market is off right now - every price is its usual price.");
        } else {
            String right = s.rightNowLine(now);
            out.add(right.isEmpty() ? "&7 Right now: &f" + MarketLabels.CALM : right);
            List<MarketEvent> recent = s.recentNews(count, now - LIST_WINDOW_MS);
            if (recent.isEmpty()) {
                out.add("&7 No market news in the last 7 days.");
            }
            for (MarketEvent e : recent) {
                String h = MarketLabels.plain(e.headline());
                String text = h.isEmpty() ? s.summary(e) : "&f" + h;
                out.add(MarketLabels.catchUpLine(text, Headlines.ago(Math.max(0L, now - MarketSimService.newsTime(e)))));
            }
        }
        if (to instanceof Player p) {
            out.add(muted(p.getUniqueId())
                    ? "&8News in chat is OFF for you. Turn it on: /hcm market news on"
                    : "&8News in chat is ON for you. Turn it off: /hcm market news off");
        } else {
            out.add("&8Players turn news in chat off or on with /hcm market news off|on.");
        }
        for (String line : out) {
            to.sendMessage(Text.of(line));
        }
    }

    /** True when this player turned the news off ({@code /hcm market news off}). */
    public boolean muted(UUID u) {
        if (u == null) {
            return false;
        }
        Boolean m = muted.get(u);
        if (m != null) {
            return m;
        }
        boolean value = false;
        try {
            value = dao.seen(u).map(MarketSimDao.Seen::muted).orElse(false);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Live market: could not read a news mute: " + ex.getMessage());
        }
        if (plugin.getServer().getPlayer(u) != null) {
            muted.put(u, value); // cache for online players only
        }
        return value;
    }

    /**
     * {@code /hcm market news on|off}: {@code mute} true turns the news OFF for this player
     * ({@code off}), false turns it back on. Remembered across sessions.
     */
    public void setMuted(UUID u, boolean mute) {
        if (u == null) {
            return;
        }
        try {
            dao.setMuted(u, mute);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Live market: could not save a news mute: " + ex.getMessage());
        }
        if (plugin.getServer().getPlayer(u) != null) {
            muted.put(u, mute);
        } else {
            muted.remove(u);
        }
    }

    // ---- presence ---------------------------------------------------------------------------

    /** A player joined: their session starts now, and the catch-up follows in 60 ticks. */
    public void onJoin(Player p) {
        if (p == null) {
            return;
        }
        UUID u = p.getUniqueId();
        joins.put(u, System.currentTimeMillis());
        muted.remove(u); // read again (lazily) this session
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) {
                catchUp(p);
            }
        }, CATCH_UP_DELAY_TICKS);
    }

    /** A player left: forget their session and cached mute. */
    public void onQuit(Player p) {
        if (p == null) {
            return;
        }
        joins.remove(p.getUniqueId());
        muted.remove(p.getUniqueId());
    }

    /**
     * Who could hear an announcement right now: online players who have not muted the news, and
     * the longest of their sessions (the join delay gate).
     */
    public OnlineInfo online() {
        long now = System.currentTimeMillis();
        int count = 0;
        long longest = 0L;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (muted(u)) {
                continue;
            }
            count++;
            Long joined = joins.get(u);
            longest = Math.max(longest, joined == null ? 0L : Math.max(0L, now - joined));
        }
        return new OnlineInfo(count, longest);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private List<Player> recipients() {
        List<Player> out = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (!muted(p.getUniqueId())) {
                out.add(p);
            }
        }
        return out;
    }

    private SimSettings settings() {
        MarketSimService s = sim;
        return s != null ? s.settings() : plugin.config().marketSim().settings();
    }

    private static String plural(MarketSimService s, String id) {
        return s == null ? nz(id) : s.plural(id);
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
