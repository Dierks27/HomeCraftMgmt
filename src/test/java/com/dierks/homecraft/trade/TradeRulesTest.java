package com.dierks.homecraft.trade;

import com.dierks.homecraft.trade.TradeRules.Ask;
import com.dierks.homecraft.trade.TradeRules.Claim;
import com.dierks.homecraft.trade.TradeRules.ClaimMove;
import com.dierks.homecraft.trade.TradeRules.Context;
import com.dierks.homecraft.trade.TradeRules.Refusal;
import com.dierks.homecraft.trade.TradeRules.Refuse;
import com.dierks.homecraft.trade.TradeRules.Where;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trade window's rules (SHOP-V2-SPEC §4.2, §4.9, §4.10, D36, D41), as {@code TradeRules} gives them: every
 * refusal row of §4.9 in order, a container refused with its inner reason, a prize six bundles deep and an
 * unreadable container refused (fail closed), T3 both ways, the collector rule in all four cases, and the ask
 * check's seven steps in their order, Creative and Spectator, the cancel cooldown and three requests a minute.
 */
class TradeRulesTest {

    private static final String CAP = "cap_pumpkin_2026";
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID HELPER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    // ---- stand-ins -----------------------------------------------------------------------------------

    private static ItemFacts diamond() {
        return ItemFacts.plain("Diamond", "DIAMOND", 3);
    }

    private static ItemFacts with(ItemFacts f, String what) {
        return new ItemFacts(f.name(), f.material(), f.amount(), what.equals("kit"),
                switch (what) {
                    case "keeper" -> "keeper_legend_crown";
                    case "collector" -> CAP;
                    case "prize", "bag" -> what.equals("bag") ? "bag_trick_or_treat_2026" : "speed_boost";
                    default -> f.prizeId();
                },
                what.equals("prize") || what.equals("kit") || what.equals("collector") || what.equals("bag")
                        || what.equals("keeper"),
                what.equals("keeper") || what.equals("rented"), what.equals("courier"), what.equals("display"),
                what.equals("bag"), what.equals("ghost"), f.keys(), f.miniUid(), f.miniId(), f.unread(), f.inside());
    }

    private static ItemFacts named(String name, String what) {
        return with(ItemFacts.plain(name, "LEATHER_HELMET", 1), what);
    }

    private static ItemFacts mini(String uid) {
        return new ItemFacts("Creeper Mini", "PLAYER_HEAD", 1, false, null, false, false, false, false, false, false,
                Set.of("homecraftmanagement:mini_uid"), uid, "creeper", false, List.of());
    }

    private static ItemFacts shulker(ItemFacts... inside) {
        return ItemFacts.plain("Shulker box", "SHULKER_BOX", 1).holding(List.of(inside));
    }

    private static Context ctx(Claim claim) {
        return new Context(Refuse.NONE, "Helper", id -> claim);
    }

    private static Context ctx() {
        return ctx(new Claim(true, false));
    }

    /** A stand-in item for the reader: its own facts, what it holds, and whether that can be read. */
    private record Box(ItemFacts self, List<Box> inside, boolean unreadable) {

        static Box of(ItemFacts self, Box... inside) {
            return new Box(self, List.of(inside), false);
        }
    }

    private static final ItemFacts.Reader<Box> BOXES = new ItemFacts.Reader<>() {
        @Override
        public ItemFacts self(Box item) {
            return item.self();
        }

        @Override
        public Optional<List<Box>> contents(Box item, int depth) {
            return item.unreadable() ? Optional.empty() : Optional.of(item.inside());
        }
    };

    /** {@code inner} inside {@code n} bundles. */
    private static Box nested(int n, Box inner) {
        Box b = inner;
        for (int i = 0; i < n; i++) {
            b = Box.of(ItemFacts.plain("Bundle", "BUNDLE", 1), b);
        }
        return b;
    }

    // ---- §4.9, row by row ------------------------------------------------------------------------------

    @Test
    void everyRefusalRowInOrderWithItsWords() {
        Context ctx = ctx();
        assertEquals(new Refusal(0, TradeRules.TOO_DEEP), TradeRules.refusal(diamond().unreadable(), ctx));
        assertEquals(new Refusal(1, TradeRules.KIT), TradeRules.refusal(named("Elytra", "kit"), ctx));
        assertEquals(new Refusal(2, "&7Legend Crown is yours for good - it can't be traded."),
                TradeRules.refusal(named("Legend Crown", "keeper"), ctx));
        assertEquals(new Refusal(3, TradeRules.RENTED), TradeRules.refusal(named("Pink Cap", "rented"), ctx));
        assertEquals(new Refusal(4, TradeRules.PACKAGE), TradeRules.refusal(named("Package", "courier"), ctx));
        assertEquals(new Refusal(5, TradeRules.DISPLAY_COPY), TradeRules.refusal(named("Fox Mini", "display"), ctx));
        assertEquals(new Refusal(6, TradeRules.BAG), TradeRules.refusal(named("Trick-or-Treat Bag", "bag"), ctx));
        Context locked = new Context(Refuse.parse(List.of("DRAGON_EGG", "pdc:souls:bound"), null), "Helper",
                id -> new Claim(true, false));
        assertEquals(new Refusal(7, TradeRules.LOCKED),
                TradeRules.refusal(ItemFacts.plain("Dragon egg", "DRAGON_EGG", 1), locked));
        ItemFacts soulbound = new ItemFacts("Sword", "IRON_SWORD", 1, false, null, false, false, false, false, false,
                false, Set.of("souls:bound"), null, null, false, List.of());
        assertEquals(new Refusal(7, TradeRules.LOCKED), TradeRules.refusal(soulbound, locked));
        assertEquals(8, TradeRules.refusal(named("Pumpkin Cap 2026", "collector"), ctx(new Claim(true, true))).row());
        Refusal ghost = TradeRules.refusal(named("Diamond", "ghost"), ctx);
        assertEquals(9, ghost.row());
        assertTrue(ghost.silent(), "a ghost is refused without a word");
        assertNull(TradeRules.refusal(diamond(), ctx), "a plain diamond goes on");
        assertNull(TradeRules.refusal(mini("u-1"), ctx), "and so does a Mini");
        assertNull(TradeRules.refusal(named("Speed Boost", "prize"), ctx), "an Arcade prize may be bartered (D37)");
    }

    @Test
    void theFirstMatchWins() {
        ItemFacts both = new ItemFacts("Crown", "LEATHER_HELMET", 1, true, "keeper_legend_crown", true, true, true,
                true, true, true, Set.of(), null, null, false, List.of());
        assertEquals(1, TradeRules.refusal(both, ctx()).row(), "a kit item that is also a Keeper: row 1");
        ItemFacts rentedPackage = new ItemFacts("Cap", "LEATHER_HELMET", 1, false, "cap_pink", true, true, true,
                false, false, false, Set.of(), null, null, false, List.of());
        assertEquals(3, TradeRules.refusal(rentedPackage, ctx()).row(), "a rented look before a package");
    }

    @Test
    void aContainerIsRefusedWithTheInnerReason() {
        Context ctx = ctx();
        assertEquals(TradeRules.KIT, TradeRules.refusal(shulker(diamond(), named("Elytra", "kit")), ctx).message());
        assertEquals("&7Legend Crown is yours for good - it can't be traded.",
                TradeRules.refusal(shulker(named("Legend Crown", "keeper")), ctx).message());
        assertEquals(TradeRules.PACKAGE,
                TradeRules.refusal(shulker(shulker(named("Package", "courier"))), ctx).message(), "two deep too");
        assertEquals(TradeRules.RENTED, TradeRules.refusal(
                ItemFacts.plain("Bundle", "BUNDLE", 1).holding(List.of(named("Pink Cap", "rented"))), ctx).message());
        assertNull(TradeRules.refusal(shulker(diamond(), mini("u-2")), ctx),
                "a shulker of diamonds and a Mini is fine");
    }

    @Test
    void aMiniInAShulkerPassesAndIsFoundForTheOwnerMove() {
        ItemFacts box = shulker(diamond(), shulker(mini("u-3")), mini("u-4"));
        assertNull(TradeRules.refusal(box, ctx()));
        List<String> uids = new ArrayList<>();
        for (ItemFacts m : box.minis()) {
            uids.add(m.miniUid());
        }
        assertEquals(List.of("u-3", "u-4"), uids, "every Mini at any depth has its owner moved");
    }

    @Test
    void aPrizeSixBundlesDeepIsRefusedAndCountsAsCarrying() {
        Box prize = Box.of(named("Speed Boost", "prize"));
        ItemFacts five = ItemFacts.read(nested(5, prize), 0, BOXES);
        assertNull(TradeRules.refusal(five, ctx()), "five deep is still read: a boost may be bartered");
        assertTrue(five.carriesPrize(), "and it is seen");
        ItemFacts six = ItemFacts.read(nested(6, prize), 0, BOXES);
        assertEquals(new Refusal(0, TradeRules.TOO_DEEP), TradeRules.refusal(six, ctx()), "[X] six deep: refused");
        assertTrue(six.carriesPrize(), "and T3 counts it as a prize (fail closed)");
        ItemFacts dirt = ItemFacts.read(nested(3, Box.of(ItemFacts.plain("Dirt", "DIRT", 64))), 0, BOXES);
        assertNull(TradeRules.refusal(dirt, ctx()), "a plain nested bundle of dirt is fine");
        assertFalse(dirt.carriesPrize());
    }

    @Test
    void anUnreadableContainerIsRefused() {
        Box broken = new Box(ItemFacts.plain("Shulker box", "SHULKER_BOX", 1), List.of(), true);
        ItemFacts f = ItemFacts.read(Box.of(ItemFacts.plain("Bundle", "BUNDLE", 1), broken), 0, BOXES);
        assertEquals(new Refusal(0, TradeRules.TOO_DEEP), TradeRules.refusal(f, ctx()), "[X] unreadable: refused");
        assertTrue(f.carriesPrize(), "and counted as a prize");
    }

    // ---- T3 (§4.10) --------------------------------------------------------------------------------------

    @Test
    void noMoneyWithAnArcadePrizeEitherWay() {
        List<ItemFacts> withPrize = List.of(diamond(), shulker(named("Arcade Trophy", "prize")));
        List<ItemFacts> plain = List.of(diamond());
        assertTrue(TradeRules.prizeOnTable(withPrize, plain));
        assertTrue(TradeRules.prizeOnTable(plain, withPrize), "either table");
        assertFalse(TradeRules.prizeOnTable(plain, List.of(mini("u-5"))));
        // a prize after money: refused
        assertEquals(TradeRules.NO_MONEY_WITH_PRIZES, TradeRules.moneyRule(true, 50, 0));
        // money after a prize: refused the same way, whoever sets it
        assertEquals(TradeRules.NO_MONEY_WITH_PRIZES, TradeRules.moneyRule(true, 0, 10));
        assertNull(TradeRules.moneyRule(true, 0, 0), "a prize with no money: barter");
        assertNull(TradeRules.moneyRule(false, 50, 50), "money with no prize");
    }

    // ---- collectors (D41) -----------------------------------------------------------------------------

    @Test
    void theCollectorRuleInAllFourCases() {
        ItemFacts cap = named("Pumpkin Cap 2026", "collector");
        assertNull(TradeRules.refusal(cap, ctx(new Claim(true, false))), "the giver's claim, the receiver none: moves");
        assertNull(TradeRules.refusal(cap, ctx(new Claim(false, true))), "going home to its claim: allowed");
        assertEquals("&7Helper already has the Pumpkin Cap 2026!",
                TradeRules.refusal(cap, ctx(new Claim(true, true))).message(), "both hold one: refused");
        assertEquals(TradeRules.NO_CLAIM, TradeRules.refusal(cap, ctx(new Claim(false, false))).message(),
                "neither: ask an admin");

        assertEquals(List.of(new ClaimMove(OWNER, HELPER, CAP, true)),
                TradeRules.claimMoves(OWNER, HELPER, List.of(cap), id -> new Claim(true, false)));
        assertEquals(List.of(new ClaimMove(OWNER, HELPER, CAP, false)),
                TradeRules.claimMoves(OWNER, HELPER, List.of(cap), id -> new Claim(false, true)),
                "going home moves no row");
        assertEquals(1, TradeRules.claimMoves(OWNER, HELPER, List.of(cap, cap), id -> new Claim(true, false)).size(),
                "two caps of one collector move one claim");
        assertEquals(List.of(cap), shulker(diamond(), cap).collectors(), "a cap in a shulker is found too");
    }

    // ---- trade.refuse_items -------------------------------------------------------------------------

    @Test
    void refuseItemsReadsMaterialsAndDataKeys() {
        List<String> warned = new ArrayList<>();
        Refuse r = Refuse.parse(List.of("dragon_egg", "pdc:Souls:Bound", "pdc:nope", "two words", " "), warned::add);
        assertEquals(Set.of("DRAGON_EGG"), r.materials());
        assertEquals(Set.of("souls:bound"), r.keys());
        assertEquals(2, warned.size(), "one WARN each for the two bad entries: " + warned);
        assertTrue(warned.get(0).contains("pdc:nope"));
        assertEquals(Refuse.NONE, Refuse.parse(List.of(), warned::add));
    }

    // ---- asking (§4.2) ----------------------------------------------------------------------------------

    private static final Where OK = new Where(true, false, true, true, GameMode.SURVIVAL);

    /** An ask that passes every check, with one field changed by {@code edit}. */
    private static String ask(Function<Map<String, Object>, Map<String, Object>> edit) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("on", true);
        m.put("asker", OK);
        m.put("online", true);
        m.put("seen", true);
        m.put("self", false);
        m.put("npc", false);
        m.put("same", true);
        m.put("distance", 40.0);
        m.put("max", 0);
        m.put("target", OK);
        m.put("takes", true);
        m.put("askerTrading", false);
        m.put("askerPending", false);
        m.put("targetTrading", false);
        m.put("targetPending", false);
        m.put("cooling", false);
        m.put("asked", 0);
        m = edit.apply(m);
        return TradeRules.askRefusal(new Ask((boolean) m.get("on"), (Where) m.get("asker"), (boolean) m.get("online"),
                (boolean) m.get("seen"), (boolean) m.get("self"), (boolean) m.get("npc"), "Helper",
                (boolean) m.get("same"), (double) m.get("distance"), (int) m.get("max"), (Where) m.get("target"),
                (boolean) m.get("takes"), (boolean) m.get("askerTrading"), (boolean) m.get("askerPending"),
                (boolean) m.get("targetTrading"), (boolean) m.get("targetPending"), (boolean) m.get("cooling"),
                (int) m.get("asked")));
    }

    private static Function<Map<String, Object>, Map<String, Object>> set(Object... kv) {
        return m -> {
            for (int i = 0; i < kv.length; i += 2) {
                m.put((String) kv[i], kv[i + 1]);
            }
            return m;
        };
    }

    @Test
    void anAskThatPassesEveryCheckIsSent() {
        assertNull(ask(set()));
        assertNull(ask(set("asker", new Where(true, false, true, true, GameMode.ADVENTURE))), "Adventure trades too");
        assertNull(ask(set("max", 50, "distance", 49.0)), "inside trade.max_distance");
        assertNull(ask(set("asked", 2)), "the third request this minute is fine");
    }

    @Test
    void theAskChecksInTheirOrder() {
        // (1) on, and where the asker is
        assertEquals("Trading is switched off.", ask(set("on", false, "online", false)));
        assertEquals("You can't trade in this world.",
                ask(set("asker", new Where(false, true, false, false, GameMode.CREATIVE))));
        assertEquals("You can't trade in a Games world.",
                ask(set("asker", new Where(true, true, false, false, GameMode.CREATIVE))));
        assertEquals("You can't trade while you're in a game.",
                ask(set("asker", new Where(true, false, false, false, GameMode.CREATIVE))));
        assertEquals("You can't trade right now.",
                ask(set("asker", new Where(true, false, true, false, GameMode.SURVIVAL))));
        assertEquals("You can't trade in Creative or Spectator mode.",
                ask(set("asker", new Where(true, false, true, true, GameMode.CREATIVE))), "[X] Creative clones stacks");
        assertEquals("You can't trade in Creative or Spectator mode.",
                ask(set("asker", new Where(true, false, true, true, GameMode.SPECTATOR))), "and Spectator");
        // (2) the target
        assertEquals("That player isn't online.", ask(set("online", false, "self", true)));
        assertEquals("That player isn't online.", ask(set("seen", false)), "a vanished admin isn't there");
        assertEquals("You can't trade with yourself.", ask(set("self", true, "npc", true)));
        assertEquals("You can only trade with players.", ask(set("npc", true, "same", false)));
        // (3) the same world, close enough
        assertEquals("Helper is in another world.", ask(set("same", false, "target", null)));
        assertEquals("Helper is too far away - come within 20 blocks.", ask(set("max", 20, "distance", 20.5)));
        // (4) where the target is
        assertEquals("Helper can't trade right now (they're in a game or another world).",
                ask(set("target", new Where(true, false, false, true, GameMode.SURVIVAL), "takes", false)));
        assertEquals("Helper can't trade right now (they're in a game or another world).",
                ask(set("target", new Where(true, false, true, true, GameMode.CREATIVE))), "a target in Creative too");
        // (5) the target takes trades
        assertEquals("Helper isn't taking trades right now.", ask(set("takes", false, "askerTrading", true)));
        // (6) busy, and the pair's cooldown
        assertEquals("You're already trading.", ask(set("askerTrading", true, "askerPending", true)));
        assertEquals("You already have a trade request waiting - answer it or cancel it first.",
                ask(set("askerPending", true, "targetTrading", true)));
        assertEquals("Helper is busy - try again in a moment.", ask(set("targetTrading", true, "cooling", true)));
        assertEquals("Helper is busy - try again in a moment.", ask(set("targetPending", true)));
        assertEquals("Wait a moment before asking Helper again.", ask(set("cooling", true, "asked", 9)),
                "after a no, a timeout, or the asker calling it off");
        // (7) three a minute
        assertEquals("That's a lot of requests - wait a minute.", ask(set("asked", 3)), "[X] request spam");
    }

    @Test
    void whereRefusesInTheSameOrder() {
        assertTrue(OK.ok());
        assertNotNull(new Where(true, false, true, true, GameMode.SPECTATOR).refusal());
        assertFalse(new Where(true, false, true, false, GameMode.SURVIVAL).ok(), "dead");
    }

    /**
     * Red-team finding CMD-4: on a server where another plugin owns {@code /trade}, the [Trade] and [No thanks] links
     * ran that plugin's command, no window opened and the pair went on its cooldown. The links run this plugin's own
     * prefixed label, and the hints name {@code /hcm trade} there.
     */
    @Test
    void theRequestsLinksAlwaysReachThisPlugin() throws java.io.IOException {
        assertEquals("/homecraftmanagement:trade accept Helper", TradeRules.click("accept", "Helper"));
        assertEquals("/homecraftmanagement:trade deny Helper", TradeRules.click("deny", "Helper"));
        assertEquals("/trade", TradeRules.typed(true));
        assertEquals("/hcm trade", TradeRules.typed(false), "another plugin owns /trade");
        String yml = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/plugin.yml"));
        assertTrue(yml.startsWith("name: HomeCraftManagement\n"), "the prefix is the plugin's name, lower-cased");
        String service = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/dierks/homecraft/trade/TradeService.java")).replaceAll("\\s+", " ");
        assertFalse(service.contains("runCommand(\"/trade"), "no link runs the bare label");
        assertTrue(service.contains("ClickEvent.runCommand(TradeRules.click(\"accept\", name))"));
        assertTrue(service.contains("ClickEvent.runCommand(TradeRules.click(\"deny\", name))"));
        assertFalse(service.contains("\"&7Type &e/trade accept"), "the Bedrock hint names the command that works");
        assertFalse(service.contains("(/trade cancel takes it back)"), "and so does the asker's");
    }
}
