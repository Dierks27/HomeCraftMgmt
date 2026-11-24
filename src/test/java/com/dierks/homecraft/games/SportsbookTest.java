package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.chance.sportsbook.Board;
import com.dierks.homecraft.games.chance.sportsbook.Kalshi;
import com.dierks.homecraft.games.chance.sportsbook.Lines;
import com.dierks.homecraft.games.chance.sportsbook.Sportsbook;
import com.dierks.homecraft.games.chance.sportsbook.SportsbookSettings;
import com.dierks.homecraft.storage.SportsPicksDao;
import com.dierks.homecraft.storage.SportsPicksDao.Pick;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Sports Board run for real over the framework's gate, the Game Parlor's money rules and the database, with a fake
 * bank and Kalshi's real replies saved under {@code src/test/resources/kalshi/} (2026-11-24, 02:47 UTC). Nothing here
 * touches the network: the board reads through a fetcher that hands back the saved replies, and its "off the main
 * thread" runs at once.
 *
 * <ul>
 *   <li>a refresh reads tonight's games; a pick takes the stake through the Parlor and keeps the price and payout it
 *       was made at, whatever the market does after;</li>
 *   <li>a pick is refused outside the Parlor, without the money permission, with money play off, after the game
 *       starts, over the payout cap, at a price that moved, over the day's limit, with too little money, or while the
 *       board is switched off;</li>
 *   <li>settling pays the right picks once, gives a called-off market's stakes back, gives back a pick with no result
 *       after {@code settle_days}, and keeps what the bank refuses as owed until it takes it;</li>
 *   <li>a restart reads the open picks and the last board back; Kalshi failing closes picks, says so once, and never
 *       touches an open pick.</li>
 * </ul>
 */
class SportsbookTest {

    /** 8:50 PM in Chicago on Monday 23 November 2026: when the replies were saved. */
    static final long NOW = Instant.parse("2026-11-24T02:50:00Z").toEpochMilli();
    static final String SAS = "KXNBAGAME-26NOV23SASSAC-SAS";
    static final String SAC = "KXNBAGAME-26NOV23SASSAC-SAC";
    static final String UVU = "KXNCAAWBGAME-26NOV24BYUUVU-UVU";

    private Host host;
    private GamesService games;
    private Sportsbook sb;
    private Fake alex;
    /** What Kalshi answers to {@code /markets?tickers=}: ticker → {status, result}. */
    private final Map<String, String[]> markets = new LinkedHashMap<>();
    /** Kalshi is down: every request fails. */
    private boolean down;
    private int requests;

    @BeforeEach
    void setUp() {
        host = new Host(NOW);
        host.bankOn = true;
        host.parlorKept = new HashMap<>(); // hcm_meta: the kept board
        build();
        alex = new Fake("Alex");
        host.online.put(alex.id, alex.player);
        host.money.put(alex.id, 300.0);
        games.parlorGuest(alex.id);
    }

    /** The games over the host as it is (a restart builds them again over the same database). */
    private void build() {
        SportsbookSettings s = new SportsbookSettings(true, List.of("nba", "nhl", "ncaawb"), 5, 24, 90, 7,
                SportsbookSettings.API_BASE);
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6), "sportsbook", s);
        games = GamesKit.service(host, List.of(Sportsbook.SPEC));
        sb = (Sportsbook) games.game("sportsbook");
        sb.connect(this::kalshi, new Sportsbook.Runner() {
            @Override
            public void async(Runnable work) {
                work.run();
            }

            @Override
            public void main(Runnable work) {
                work.run();
            }
        });
        sb.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    /** Kalshi, as the saved replies have it. */
    private String kalshi(String path) throws IOException {
        requests++;
        if (down) {
            throw new IOException("Connection refused");
        }
        if (path.startsWith("/events?")) {
            for (String[] f : new String[][] {{"KXNBAGAME", "events_nba.json"}, {"KXNHLGAME", "events_nhl.json"},
                    {"KXNCAAWBGAME", "events_ncaawb.json"}}) {
                if (path.contains("series_ticker=" + f[0] + "&")) {
                    return fixture(f[1]);
                }
            }
            return "{\"cursor\":\"\",\"events\":[],\"milestones\":[]}";
        }
        if (path.startsWith("/markets?tickers=")) {
            StringBuilder out = new StringBuilder("{\"cursor\":\"\",\"markets\":[");
            String asked = java.net.URLDecoder.decode(path.substring("/markets?tickers=".length()).split("&")[0],
                    StandardCharsets.UTF_8);
            boolean first = true;
            for (String t : asked.split(",")) {
                String[] m = markets.getOrDefault(t, new String[] {"active", ""});
                out.append(first ? "" : ",").append("{\"ticker\":\"").append(t).append("\",\"status\":\"")
                        .append(m[0]).append("\",\"result\":\"").append(m[1]).append("\"}");
                first = false;
            }
            return out.append("]}").toString();
        }
        throw new IOException("HTTP 404");
    }

    private static String fixture(String name) {
        try (InputStream in = SportsbookTest.class.getResourceAsStream("/kalshi/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private double money(Fake f) {
        return host.money.getOrDefault(f.id, 0.0);
    }

    private Sportsbook.Quote quote(String ticker, int stake) {
        Board.Found found = sb.board().find(ticker, "yes");
        assertNotNull(found, ticker + " is on the board");
        return sb.quote(found.side(), stake);
    }

    private List<Pick> picks() throws Exception {
        List<Pick> out = new ArrayList<>(games.dao().sports().open());
        return out;
    }

    private Pick pick(long id) throws Exception {
        return games.dao().sports().pick(id);
    }

    // ---- the board and a pick -------------------------------------------------------------------------------

    @Test
    void aRefreshReadsTonightsGamesAndAPickLocksItsPriceAndPayout() throws Exception {
        assertTrue(sb.refresh(), "a reading went out");
        assertTrue(sb.linesOpen(NOW));
        Board b = sb.board();
        assertEquals(NOW, b.fetchedAt());
        assertEquals(4, b.upcoming(NOW, "nba").size());
        assertEquals(2, b.upcoming(NOW, "nhl").size());
        assertEquals(3, b.upcoming(NOW, "ncaawb").size(), "Belmont at LSU started at 7:00");

        Sportsbook.Quote q = quote(SAS, 10);
        assertEquals(new Sportsbook.Quote(SAS, "yes", 725, 10, 1241), q, "$10 at 72.5 in 100 pays $12.41");
        assertNull(sb.place(alex.player, q), "the pick is in");
        assertEquals(290.0, money(alex), 1e-9, "the stake was taken at once");
        List<Pick> open = picks();
        assertEquals(1, open.size());
        Pick p = open.get(0);
        assertEquals(alex.id, p.player());
        assertEquals(SAS, p.ticker());
        assertEquals("yes", p.side());
        assertEquals(10, p.stake());
        assertEquals(725, p.price(), "the price is locked");
        assertEquals(1241, p.payout(), "and so is the payout");
        assertEquals(SportsPicksDao.OPEN, p.status());
        assertEquals("San Antonio vs Sacramento", p.game());
        assertEquals("San Antonio", p.pick());
        assertEquals(Instant.parse("2026-11-24T03:00:00Z").toEpochMilli(), p.startsAt());
        assertEquals(10, games.parlor().inToday(alex.id), "the day's money limit counts it");

        // the market moves; the pick doesn't
        host.move(5 * 60_000L);
        markets.put(SAS, new String[] {"finalized", "yes"});
        sb.refresh();
        Pick paid = pick(p.id());
        assertEquals(SportsPicksDao.WON, paid.status());
        assertEquals(1241, paid.paid(), "paid what was locked, not today's price");
        assertEquals(290.0 + 12.41, money(alex), 1e-9);
        assertTrue(alex.heard().contains("Your pick on San Antonio won: $12.41."), alex.heard());
    }

    @Test
    void thePickIsRefusedOutsideTheParlorWithoutThePermissionOrWithMoneyPlayOff() {
        sb.refresh();
        Sportsbook.Quote q = quote(SAS, 10);
        games.parlorGuests(List.of()); // Alex walked out of the Parlor
        assertSame(Parlor.MONEY_NOT_HERE, sb.place(alex.player, q));
        games.parlorGuest(alex.id);
        alex.denied.add(Parlor.PERMISSION_MONEY);
        assertSame(Parlor.MONEY_NOT_YOURS, sb.place(alex.player, q), "a child never has hcm.games.money");
        alex.denied.clear();
        alex.denied.add(PlayGate.PERMISSION_CHANCE);
        assertSame(Refusal.NO_CHANCE, sb.place(alex.player, q));
        alex.denied.clear();
        games.parlor().money(false);
        assertSame(Parlor.MONEY_CLOSED, sb.place(alex.player, q));
        games.parlor().money(true);
        assertEquals(300.0, money(alex), 1e-9, "nothing was taken");
        assertTrue(sb.tileShown(alex.player), "in the Parlor with money play on: its tile shows");
        games.parlorGuests(List.of());
        assertTrue(sb.tiles(alex.player).isEmpty(), "anywhere else it has no tile at all");
        assertFalse(sb.tileShown(alex.player));
        games.parlorGuest(alex.id);
        alex.denied.add(Parlor.PERMISSION_MONEY);
        assertTrue(sb.tiles(alex.player).isEmpty(), "nor for anyone without the money permission");
        assertTrue(sb.oddsLines().isEmpty(), "and no odds line any child could read");
    }

    @Test
    void thePickIsRefusedAfterTheStartOverTheCapAtAMovedPriceOrWhileSwitchedOff() throws Exception {
        sb.refresh();
        Sportsbook.Quote q = quote(SAS, 10);
        host.move(10 * 60_000L + 1); // 9:00:00.001 PM: San Antonio vs Sacramento has started
        assertSame(Sportsbook.STARTED, sb.place(alex.player, q));
        host.move(-10 * 60_000L - 1);

        Board.Found uvu = sb.board().find(UVU, "yes");
        assertEquals(65, uvu.side().price(), "Utah Valley at 6.5 in 100");
        assertEquals(36, sb.mostFor(65), "its $50 would pay over the $500 payout cap: $36 at most");
        assertNull(sb.quote(uvu.side(), 50));
        Refusal r = sb.place(alex.player, new Sportsbook.Quote(UVU, "yes", 65, 50, Lines.payoutCents(50, 65, 900)));
        assertEquals("At that chance the most you can put in is $36.", r.message());
        Sportsbook.Quote small = sb.quote(uvu.side(), 25);
        assertEquals(34615, small.payout(), "$25 on Utah Valley pays $346.15");
        assertNull(sb.place(alex.player, small), "a smaller stake is offered");
        host.move(1_000);

        Sportsbook.Quote stale = new Sportsbook.Quote(SAS, "yes", 700, 10, Lines.payoutCents(10, 700, 900));
        assertSame(Sportsbook.MOVED, sb.place(alex.player, stale), "the screen showed another price");

        Board.Found byu = sb.board().find("KXNCAAWBGAME-26NOV24BYUUVU-BYU", "yes");
        assertEquals(Sportsbook.State.NOT_OFFERED, sb.state(byu.game(), byu.side(), NOW), "BYU at 93 in 100");
        assertSame(Sportsbook.NOT_OFFERED, sb.place(alex.player,
                new Sportsbook.Quote(byu.side().ticker(), "yes", 930, 10, Lines.payoutCents(10, 930, 900))));

        sb.off(true);
        assertSame(Sportsbook.OFF, sb.place(alex.player, q));
        assertEquals(Sportsbook.State.OFF, sb.state(sb.board().find(SAS, "yes").game(), q == null ? null
                : sb.board().find(SAS, "yes").side(), NOW));
        sb.off(false);
        assertNull(sb.place(alex.player, q));
        assertEquals(300.0 - 25 - 10, money(alex), 1e-9, "only the two picks that went in were taken");
    }

    @Test
    void theParlorsChipsDayLimitAndBalanceHoldForPicks() throws Exception {
        sb.refresh();
        assertSame(Parlor.MONEY_CLOSED, sb.place(alex.player, quote(SAS, 7)), "$7 isn't made of the chips");
        games.parlor().limit(30);
        assertNull(sb.place(alex.player, quote(SAS, 25)));
        host.move(1_000);
        Refusal r = sb.place(alex.player, quote(SAC, 10));
        assertEquals(Refusal.Reason.PERSONAL_LIMIT, r.reason(), "$25 + $10 is over a $30 day");
        games.parlor().limit(Parlor.NO_LIMIT);
        host.money.put(alex.id, 5.0);
        assertEquals(Refusal.Reason.BALANCE, sb.place(alex.player, quote(SAC, 10)).reason());
        assertEquals(5.0, money(alex), 1e-9);
        assertEquals(1, picks().size());
    }

    // ---- settling -------------------------------------------------------------------------------------------

    @Test
    void settlingPaysTheRightPicksOnceAndGivesACalledOffGameBack() throws Exception {
        sb.refresh();
        Fake sam = new Fake("Sam");
        host.online.put(sam.id, sam.player);
        host.money.put(sam.id, 100.0);
        games.parlorGuest(sam.id);
        assertNull(sb.place(alex.player, quote(SAS, 10)));
        assertNull(sb.place(sam.player, quote(SAC, 25)));
        String edm = "KXNHLGAME-26NOV24VGKEDM-EDM";
        host.move(1_000);
        assertNull(sb.place(alex.player, quote(edm, 5)));
        assertEquals(285.0, money(alex), 1e-9);
        assertEquals(75.0, money(sam), 1e-9);
        assertEquals(3, picks().size());

        host.move(4 * 3_600_000L); // the games are played
        markets.put(SAS, new String[] {"finalized", "no"});
        markets.put(SAC, new String[] {"finalized", "yes"});
        markets.put(edm, new String[] {"finalized", "void"});
        sb.refresh();
        assertTrue(picks().isEmpty(), "every pick settled");
        assertEquals(285.0 + 5.0, money(alex), 1e-9, "Alex: San Antonio lost; the Edmonton game's $5 came back");
        long sac = Lines.payoutCents(25, 285, 900);
        assertEquals(7894, sac, "$25 at 28.5 in 100 pays $78.94");
        assertEquals(75.0 + 78.94, money(sam), 1e-9, "Sam's Sacramento won");
        assertTrue(sam.heard().contains("Your pick on Sacramento won: $78.94."), sam.heard());
        assertTrue(alex.heard().contains("Your pick on San Antonio (San Antonio vs Sacramento) didn't win"),
                alex.heard());
        assertTrue(alex.heard().contains("The Vegas vs Edmonton game was called off, so your $5 came back."),
                alex.heard());

        sb.refresh(); // Kalshi still says the same: nothing is paid twice
        sb.settleByHand(SAC, Kalshi.Outcome.YES, "Owner");
        assertEquals(75.0 + 78.94, money(sam), 1e-9, "paid once");
        assertEquals(290.0, money(alex), 1e-9);
        long day = host.clock.dayKey(); // just after midnight: the picks were made yesterday evening
        long[] today = games.dao().sports().today(day, host.clock.startOfDay(day));
        assertEquals(0, today[0], "nothing put in yet today");
        assertEquals(7894 + 500, today[1], "$83.94 back today: a win and a refund");
        assertEquals(0, today[2], "no open picks");
        assertEquals(40, games.dao().sports().today(day - 1, host.clock.startOfDay(day - 1))[0],
                "$40 in yesterday");
    }

    @Test
    void aPickWithNoResultAfterSettleDaysIsGivenBackWithAConsoleLine() throws Exception {
        sb.refresh();
        assertNull(sb.place(alex.player, quote(SAS, 10)));
        host.move(6 * 86_400_000L);
        sb.refresh();
        assertEquals(1, picks().size(), "six days on, still waiting");
        host.move(86_400_000L + 3_600_000L);
        sb.refresh();
        assertTrue(picks().isEmpty());
        Pick p = games.dao().sports().mine(alex.id, 5).get(0);
        assertEquals(SportsPicksDao.LAPSED, p.status());
        assertEquals(1000, p.paid());
        assertEquals(300.0, money(alex), 1e-9, "the stake came back");
        assertTrue(alex.heard().contains("had no result after 7 days, so your $10 came back"), alex.heard());
        assertTrue(host.logs.stream().anyMatch(l -> l.getMessage().contains("had no result after 7 days")),
                "a console line");
    }

    @Test
    void aPayoutTheBankRefusesIsKeptAsOwedAndPaidOnceWhenItTakesIt() throws Exception {
        sb.refresh();
        assertNull(sb.place(alex.player, quote(SAC, 10)));
        host.bankRefusesGive = true;
        markets.put(SAC, new String[] {"finalized", "yes"});
        host.move(4 * 3_600_000L);
        sb.refresh();
        Pick p = games.dao().sports().mine(alex.id, 5).get(0);
        assertEquals(SportsPicksDao.WON, p.status(), "the pick is settled (it can never be paid twice)");
        assertEquals(290.0, money(alex), 1e-9, "the bank took nothing in");
        assertEquals(3157, games.parlor().owed(alex.id), "the $31.57 is kept as owed");
        sb.refresh();
        assertEquals(3157, games.parlor().owed(alex.id), "and not owed twice");
        host.bankRefusesGive = false;
        games.parlor().payOwed(alex.player); // the games' one-minute sweep does this for everyone online
        assertEquals(290.0 + 31.57, money(alex), 1e-9);
        assertEquals(0, games.parlor().owed(alex.id));
        games.parlor().payOwed(alex.player);
        assertEquals(290.0 + 31.57, money(alex), 1e-9, "paid once");
    }

    @Test
    void anOfflineWinnerIsToldAtTheirNextJoin() throws Exception {
        sb.refresh();
        assertNull(sb.place(alex.player, quote(SAC, 10)));
        host.online.remove(alex.id);
        markets.put(SAC, new String[] {"finalized", "yes"});
        host.move(4 * 3_600_000L);
        sb.refresh();
        assertFalse(alex.heard().contains("won"), "not online: nothing said yet");
        assertEquals(300.0 - 10 + 31.57, money(alex), 1e-9, "the bank pays an offline player all the same");
        host.online.put(alex.id, alex.player);
        games.onJoin(alex.player);
        host.runTasks();
        assertTrue(alex.heard().contains("Your pick on Sacramento won: $31.57."), alex.heard());
    }

    // ---- a restart, and Kalshi failing ----------------------------------------------------------------------

    @Test
    void aRestartReadsTheOpenPicksAndTheLastBoardBack() throws Exception {
        sb.refresh();
        assertNull(sb.place(alex.player, quote(SAC, 10)));
        String kept = host.parlorKept.get("gen.sportsbook.board");
        assertNotNull(kept, "the board is kept in hcm_meta");

        down = true;
        host.move(60_000L);
        build(); // the server restarts
        assertEquals(4, sb.board().upcoming(host.clock.nowMillis(), "nba").size(), "the kept board shows at once");
        assertFalse(sb.linesOpen(host.clock.nowMillis()), "but takes no picks until a fresh reading");
        assertEquals(Sportsbook.State.UPDATING, sb.state(sb.board().find(SAS, "yes").game(),
                sb.board().find(SAS, "yes").side(), host.clock.nowMillis()));
        assertEquals(1, picks().size(), "the open pick is read back");
        assertEquals(10, games.parlor().inToday(alex.id));

        down = false;
        markets.put(SAC, new String[] {"finalized", "yes"});
        host.move(4 * 3_600_000L);
        sb.refresh();
        assertTrue(picks().isEmpty());
        assertEquals(290.0 + 31.57, money(alex), 1e-9, "paid after the restart");
    }

    @Test
    void kalshiFailingClosesPicksOnceAndNeverTouchesAnOpenPick() throws Exception {
        sb.refresh();
        assertNull(sb.place(alex.player, quote(SAC, 10)));
        Sportsbook.Quote q = quote(SAS, 10);
        down = true;
        host.move(2 * 60_000L);
        sb.refresh();
        host.move(2 * 60_000L);
        sb.refresh();
        assertFalse(sb.linesOpen(host.clock.nowMillis()));
        assertSame(Sportsbook.UPDATING, sb.place(alex.player, q), "picks are closed");
        assertEquals(1, host.logs.stream().filter(l -> l.getLevel() == Level.WARNING
                && l.getMessage().contains("Kalshi could not be read")).count(), "one WARN for the whole outage");
        assertEquals(1, picks().size(), "the open pick is untouched");
        assertEquals(SportsPicksDao.OPEN, picks().get(0).status());
        assertEquals(290.0, money(alex), 1e-9);
        assertEquals(4, sb.board().upcoming(NOW, "nba").size(), "the last board stays to look at");

        down = false;
        host.move(60_000L);
        sb.refresh();
        assertTrue(sb.linesOpen(host.clock.nowMillis()), "back: picks open again");
        assertTrue(host.logs.stream().anyMatch(l -> l.getMessage().contains("Kalshi answers again")));
    }

    @Test
    void aBoardNotReadForTwoRefreshesTakesNoPicks() {
        sb.refresh();
        Sportsbook.Quote q = quote("KXNBAGAME-26NOV24DETORL-DET", 10); // tomorrow evening's game
        host.move(2 * 5 * 60_000L + 60_001L);
        assertFalse(sb.linesOpen(host.clock.nowMillis()), "11 minutes old: too old to pick from");
        assertSame(Sportsbook.UPDATING, sb.place(alex.player, q));
    }

    @Test
    void whileSwitchedOffOnlyTheOpenPicksMarketsAreRead() throws Exception {
        sb.refresh();
        assertNull(sb.place(alex.player, quote(SAC, 10)));
        sb.off(true);
        requests = 0;
        markets.put(SAC, new String[] {"finalized", "yes"});
        host.move(4 * 3_600_000L);
        sb.refresh();
        assertEquals(1, requests, "one request: the open pick's market, no lines");
        assertEquals(SportsPicksDao.WON, games.dao().sports().mine(alex.id, 1).get(0).status(),
                "settled while the board is off");
        requests = 0;
        assertFalse(sb.refresh(), "nothing open, nothing to read");
        assertEquals(0, requests);
    }

    // ---- the wall board and the words -----------------------------------------------------------------------

    @Test
    void theWallBoardReadsTonightsGamesAtTenDollars() {
        sb.refresh();
        List<Sportsbook.WallGame> wall = sb.wall(3);
        assertEquals(3, wall.size());
        assertEquals(List.of("Minnesota vs San Jose", "San Antonio vs Sacramento", "Utah vs Los Angeles L"),
                wall.stream().map(Sportsbook.WallGame::title).toList(), "the 9:00 PM games first, by name");
        Sportsbook.WallGame spurs = wall.get(1);
        assertEquals("NBA", spurs.league());
        assertEquals("9:00 PM", spurs.starts());
        assertEquals(new Sportsbook.WallSide("San Antonio", 73, "$12.41"), spurs.sides().get(0));
        assertEquals(new Sportsbook.WallSide("Sacramento", 29, "$31.57"), spurs.sides().get(1));
        assertEquals(List.of("Tonight's games - $10 in pays", "NHL 9:00 PM  Minnesota vs San Jose",
                "  San Jose $23.37 · Minnesota $14.40", "NBA 9:00 PM  San Antonio vs Sacramento",
                "  San Antonio $12.41 · Sacramento $31.57"), sb.wallText(2));
        Map<String, String> utah = new HashMap<>();
        for (Sportsbook.WallSide s : wall.get(2).sides()) {
            utah.put(s.label(), s.backAtTen());
        }
        assertEquals(Map.of("Utah", "$62.06", "Los Angeles L", "$10.52"), utah,
                "the Lakers at 85.5 in 100 still pay back more than the $10 put in");
    }

    @Test
    void whatAPlayerIsToldNeverUsesTheBannedWords() {
        Pattern banned = Pattern.compile("(?i)\\bbet\\b|wager|gambl|casino|\\bhouse\\b|so\\s+close|\\balmost\\b"
                + "|\\blucky\\b|\\bhot\\b|\\bdue\\b|big\\s+win|jackpot|try\\s+again|one\\s+more");
        Pick p = new Pick(1, alex.id, SAS, "yes", 10, 725, 1241, NOW, "OPEN", 0, 0, 0, NOW, "nba",
                "San Antonio vs Sacramento", "San Antonio");
        List<String> said = new ArrayList<>();
        for (String status : List.of(SportsPicksDao.WON, SportsPicksDao.LOST, SportsPicksDao.VOID,
                SportsPicksDao.LAPSED)) {
            said.add(Sportsbook.told(p, status, 1241, 7));
        }
        said.addAll(sb.rules());
        said.addAll(Sportsbook.HELP);
        for (Refusal r : List.of(Sportsbook.UPDATING, Sportsbook.OFF, Sportsbook.STARTED, Sportsbook.MOVED,
                Sportsbook.NOT_OFFERED)) {
            said.add(r.message());
        }
        for (String line : said) {
            assertFalse(banned.matcher(line).find(), line);
        }
        assertEquals("&aYour pick on San Antonio won: &6$12.41&a.", said.get(0));
    }
}
