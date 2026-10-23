package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.looks.GuardRules;
import com.dierks.homecraft.arcade.looks.LookRules;
import com.dierks.homecraft.arcade.looks.LookService;
import com.dierks.homecraft.arcade.looks.LookService.KeeperCommitted;
import com.dierks.homecraft.arcade.looks.LookService.Status;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.LookDao;
import com.dierks.homecraft.storage.PrizeDao;
import com.dierks.homecraft.storage.TokenDao;
import com.dierks.homecraft.trade.ItemFacts;
import com.dierks.homecraft.trade.TradeRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Buying and keeping a Keeper (SHOP-V2-SPEC §5.4, §9 S4): never from a crate, never a present, never traded; a buy
 * is ONE transaction on a real SQLite with the real token store (the claim, the rights kept for good, the Crown's
 * number and the charge), so a double confirm is charged once, a refused charge writes nothing, the Crown writes its
 * Royal Trail too, and a Crown made again (or given back) keeps its number. The Knight's Helm is iron, so no furnace
 * smelts it.
 */
class KeeperRulesTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final PrizeService.Shelf SHELF = new PrizeService.Shelf(true, LocalDate.of(2026, 11, 21), Set.of(),
            true);

    private Connection conn;
    private Database db;
    private LookDao looks;
    private PrizeDao prizes;
    private TokenDao tokens;
    private final UUID owner = UUID.randomUUID();
    private final UUID helper = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        looks = new LookDao(db);
        prizes = new PrizeDao(db);
        tokens = new TokenDao(db);
        tokens.change(owner, 12_000, "ADMIN", "start", 1);
        tokens.change(helper, 100, "ADMIN", "start", 1);
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    private LookService.Spend spend() {
        return (payer, cost, detail) -> tokens.change(payer, -cost, "PRIZE", detail, NOW) != TokenDao.REFUSED;
    }

    private KeeperCommitted buy(UUID who, String id) throws SQLException {
        Prize p = Keepers.row(id);
        return LookService.commitKeeper(db, looks, prizes, spend(), who, p, p.costTokens(), NOW, "");
    }

    @Test
    void neverFromACrate() {
        for (Prize p : Keepers.ROWS) {
            assertFalse(PrizeService.crateable(p), p.id() + ": bought, never won");
            assertFalse(PrizeService.grantable(p, SHELF), p.id() + ": out of every crate's pool, the trail pool too");
        }
    }

    @Test
    void neverAPresent() {
        for (Prize p : Keepers.ROWS) {
            assertFalse(PrizeService.giftable(p), p.id());
        }
        PrizeService.GiftFacts f = new PrizeService.GiftFacts(true, true, true, false, true, true, true, false, true,
                false, false, false, null, false, true, true, 9000, 5000, "Kid", "Legend Crown");
        assertEquals("Keepers can't be given.", PrizeService.giftRefusal(f), "refused before anything else is asked");
    }

    /** The trade window's row 2 (§4.9) asks {@code PrizeService.bound}: a Keeper's id alone binds it to its owner. */
    @Test
    void neverTraded() {
        for (Prize p : Keepers.ROWS) {
            assertTrue(PrizeService.bound(null, p.id()), p.id() + " is bound even without hcm:bound_to");
            assertTrue(PrizeService.bound(UUID.randomUUID().toString(), p.id()), p.id());
        }
        assertFalse(PrizeService.bound(null, "cap_pumpkin_2026"), "a collector cap is not bound");
    }

    /**
     * [X] The trade window itself (§4.9 row 2): every Keeper key, with or without its {@code hcm:bound_to}, and one
     * boxed up in a shulker, is refused with its own name, before the rented-look row could answer.
     */
    @Test
    void theTradeWindowRefusesEveryKeeper() {
        TradeRules.Context ctx = new TradeRules.Context(TradeRules.Refuse.NONE, "Helper",
                id -> TradeRules.Claim.NONE);
        for (Prize p : Keepers.ROWS) {
            for (String boundTo : new String[] {null, owner.toString()}) {
                ItemFacts key = new ItemFacts("Legend Crown", "LEATHER_HELMET", 1, false, p.id(), true,
                        PrizeService.bound(boundTo, p.id()), false, false, false, false, Set.of("hcm:token_prize"),
                        null, null, false, java.util.List.of());
                TradeRules.Refusal r = TradeRules.refusal(key, ctx);
                assertNotNull(r, p.id());
                assertEquals(2, r.row(), p.id() + ": the Keeper row, not the rented-look one");
                assertEquals("&7Legend Crown is yours for good - it can't be traded.", r.message());
                ItemFacts box = ItemFacts.plain("Shulker box", "SHULKER_BOX", 1).holding(java.util.List.of(key));
                assertEquals(2, TradeRules.refusal(box, ctx).row(), p.id() + " inside a shulker box");
            }
        }
    }

    @Test
    void theKnightsHelmNeverGoesIntoAFurnace() {
        assertTrue(GuardRules.smeltRefused(true), "a token prize (the Knight's Helm is iron) is never smelted");
        assertFalse(GuardRules.smeltRefused(false));
    }

    @Test
    void aDoubleConfirmIsChargedOnce() throws Exception {
        KeeperCommitted first = buy(owner, Keepers.WIZARD);
        assertEquals(Status.DONE, first.status());
        assertTrue(first.claimed());
        assertEquals(9_500, tokens.get(owner).tokens());
        int lines = tokens.history(owner, 100).size();
        KeeperCommitted second = buy(owner, Keepers.WIZARD);
        assertEquals(Status.ALREADY, second.status(), "the claim is there: refused before any charge");
        assertEquals(9_500, tokens.get(owner).tokens(), "charged once");
        assertEquals(lines, tokens.history(owner, 100).size(), "no second ledger line");
        assertEquals(1, prizes.purchases(owner, Keepers.WIZARD, "life"));
        LookDao.Look row = looks.look(owner, Keepers.WIZARD);
        assertNotNull(row);
        assertEquals(LookDao.HAT, row.kind());
        assertTrue(LookRules.kept(row, NOW), "kept for good");
        assertFalse(row.enabled(), "a HAT row is never switched on");
    }

    @Test
    void aRefusedChargeWritesNothing() throws Exception {
        KeeperCommitted r = buy(helper, Keepers.AURORA);
        assertEquals(Status.NOT_PAID, r.status());
        assertEquals(100, tokens.get(helper).tokens());
        assertEquals(0, prizes.purchases(helper, Keepers.AURORA, "life"), "the claim rolled back");
        assertNull(looks.look(helper, Keepers.AURORA), "the right rolled back");
    }

    @Test
    void aKeeperTrailIsATrailRow() throws Exception {
        assertEquals(Status.DONE, buy(owner, Keepers.FROST).status());
        LookDao.Look row = looks.look(owner, Keepers.FROST);
        assertEquals(LookDao.TRAIL, row.kind());
        assertTrue(LookRules.kept(row, NOW));
        assertEquals(0, prizes.state(LookService.numberKey(Keepers.FROST, owner), 0), "only the Crown is numbered");
    }

    @Test
    void theCrownWritesBothRowsAndTakesANumber() throws Exception {
        tokens.change(helper, 6_000, "ADMIN", "more", 1);
        KeeperCommitted mine = buy(owner, Keepers.CROWN);
        assertEquals(Status.DONE, mine.status());
        assertEquals(1, mine.number(), "No. 1");
        assertEquals(LookDao.HAT, looks.look(owner, Keepers.CROWN).kind());
        LookDao.Look royal = looks.look(owner, Keepers.ROYAL);
        assertNotNull(royal, "the Royal Trail comes with it");
        assertEquals(LookDao.TRAIL, royal.kind());
        assertTrue(LookRules.kept(royal, NOW));
        assertEquals(0, prizes.purchases(owner, Keepers.ROYAL, "life"), "the companion has no claim of its own");
        assertEquals(7_000, tokens.get(owner).tokens(), "5,000 once");

        KeeperCommitted theirs = buy(helper, Keepers.CROWN);
        assertEquals(2, theirs.number(), "the next one is No. 2");
        assertEquals(2, prizes.state(LookService.serialKey(Keepers.CROWN), 0));
    }

    @Test
    void aCrownMadeAgainKeepsItsNumber() throws Exception {
        buy(owner, Keepers.CROWN);
        tokens.change(helper, 6_000, "ADMIN", "more", 1);
        buy(helper, Keepers.CROWN);
        // given back free (an admin's restore): the claim is there, so nothing is counted again, and the number stays
        Prize crown = Keepers.row(Keepers.CROWN);
        KeeperCommitted again = LookService.commitKeeper(db, looks, prizes, null, owner, crown, 0, NOW + 1_000, "");
        assertEquals(Status.DONE, again.status());
        assertFalse(again.claimed(), "it was theirs already");
        assertEquals(1, again.number(), "still No. 1");
        assertEquals(1, prizes.state(LookService.numberKey(Keepers.CROWN, owner), 0));
        assertEquals(2, prizes.state(LookService.serialKey(Keepers.CROWN), 0), "no number used up");
        assertEquals(7_000, tokens.get(owner).tokens(), "free");
        assertEquals(1, prizes.purchases(owner, Keepers.CROWN, "life"), "counted once");
    }

    @Test
    void aFreeGiveClaimsForSomeoneWithNone() throws Exception {
        Prize knight = Keepers.row(Keepers.KNIGHT);
        KeeperCommitted r = LookService.commitKeeper(db, looks, prizes, null, helper, knight, 0, NOW, owner.toString());
        assertEquals(Status.DONE, r.status());
        assertTrue(r.claimed());
        assertEquals(1, prizes.purchases(helper, Keepers.KNIGHT, "life"));
        assertEquals(owner.toString(), looks.look(helper, Keepers.KNIGHT).givenBy(), "the admin is its giver");
        assertEquals(100, tokens.get(helper).tokens(), "nothing charged");
    }
}
