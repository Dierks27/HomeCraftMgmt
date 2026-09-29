package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.gui.games.ClickHold;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin tools review, group D (fix2-D), on the tools screens: a double click never passes the
 * "Sure?" screen (D4); during the chosen set the cancel tool says it lets this set's pick go, and
 * the screen's title follows the cadence (D5); the Choose button's Yes says when it replaces a pick
 * (D6); someone who lost the admin permission gets no tools screen (D7); and the facts that change
 * what a tap does are in the NAMEs for Bedrock (D8).
 */
class FreshAdminReviewTest {

    private static final Slots.Def PARKOUR = Slots.of("fresh_parkour");
    private static final Slots.Def GOLF = Slots.of("fresh_golf");
    private static final long A = 0x3f2aL;
    private static final long B = 0x5eedL;
    private static final String A_HEX = "0000000000003f2a";
    private static final String B_HEX = "0000000000005eed";
    private static final String NEXT_WEEK = "Mon 5 Oct-Sun 11 Oct";

    /** A slot's tools state: weekly, on, not busy. */
    private static GenOps.Tools tools(Long preview, boolean previewNext, Long chosen, boolean upNow) {
        return new GenOps.Tools(true, false, 7, preview, previewNext, chosen, chosen == null ? null : NEXT_WEEK, false,
                upNow);
    }

    private static FreshAdmin.Tool tool(List<FreshAdmin.Tool> tools, FreshAdmin.Kind kind) {
        return tools.stream().filter(t -> t.kind() == kind).findFirst().orElseThrow(
                () -> new AssertionError("no " + kind + " in " + tools.stream().map(FreshAdmin.Tool::name).toList()));
    }

    private static List<String> names(List<FreshAdmin.Tool> tools) {
        return tools.stream().map(FreshAdmin.Tool::name).toList();
    }

    // ---- D4: a double click never passes the Sure screen -----------------------------------------------

    @Test
    void aDoubleClickNeverPassesTheSureScreen() {
        List<GenOps.Tools> states = List.of(tools(null, false, null, false), tools(A, false, null, false),
                tools(A, true, null, false), tools(A, true, A, false), tools(B, true, A, false),
                tools(A, false, A, true),
                new GenOps.Tools(true, false, 1, A, false, A, "Tue 6 Oct", false, false));
        Set<Integer> toolSlots = new TreeSet<>();
        for (GenOps.Tools st : states) {
            for (Slots.Def def : List.of(PARKOUR, GOLF)) {
                FreshAdmin.tools(def, st).forEach(t -> toolSlots.add(t.slot()));
            }
        }
        assertFalse(toolSlots.contains(FreshAdmin.CONFIRM_YES), "Yes sits on no slot any tool uses, so the second press"
                + " of a double click on Regenerate, Promote or Choose can't land on it: tools " + toolSlots + ", Yes "
                + FreshAdmin.CONFIRM_YES);
        assertFalse(toolSlots.contains(FreshAdmin.CONFIRM_NO), "No sits on no slot any tool uses, so a double click on"
                + " No can't run a tool on the tools screen it reopens: tools " + toolSlots + ", No "
                + FreshAdmin.CONFIRM_NO);
        assertNotEquals(FreshAdmin.CONFIRM_NO, FreshAdmin.CONFIRM_YES, "No and Yes are two places");
        for (int s : List.of(FreshAdmin.CONFIRM_NO, FreshAdmin.CONFIRM_YES)) {
            assertFalse(s == 4 || s == 22, "clear of the tool shown at 4 and the way out at 22: " + s);
            assertTrue(s >= 0 && s < 27, "on the 27-slot screen: " + s);
        }
        assertTrue(FreshAdmin.OPEN_HOLD_MS >= 400, "both screens drop clicks for about 400 ms as they open: "
                + FreshAdmin.OPEN_HOLD_MS);

        long[] clock = {1_000};
        ClickHold opened = new ClickHold(() -> clock[0]);
        opened.hold(FreshAdmin.OPEN_HOLD_MS); // what the screens do as they open (GameMenu#hold)
        clock[0] += 150;
        assertFalse(opened.passes(ClickType.LEFT), "the second press of a double click, 150 ms on, is dropped");
        clock[0] += 350;
        assertTrue(opened.passes(ClickType.LEFT), "a click after reading the screen goes through");
    }

    // ---- D5: the chosen set's own tools, and the title at the cadence -------------------------------------

    @Test
    void duringTheChosenSetTheCancelToolLetsThisSetsPickGoAndSaysWhatThatDoes() {
        List<FreshAdmin.Tool> up = FreshAdmin.tools(PARKOUR, tools(null, false, A, true));
        FreshAdmin.Tool let = tool(up, FreshAdmin.Kind.UNCHOOSE);
        assertEquals("&7Admin: Let this week's pick go (unchoose)", let.name(), "not next week's: it is up now");
        String lore = String.join(" ", let.lore());
        assertTrue(lore.contains("stays up") && lore.contains("regenerate"), "the course stays, and regenerate and"
                + " promote work again: " + lore);
        List<String> upState = FreshAdmin.state(tools(null, false, A, true));
        assertTrue(upState.contains("&6Picked for " + NEXT_WEEK + " (up now): seed " + A_HEX),
                "the header says it is up now: " + upState);

        FreshAdmin.Tool cancel = tool(FreshAdmin.tools(PARKOUR, tools(null, false, A, false)),
                FreshAdmin.Kind.UNCHOOSE);
        assertEquals("&7Admin: Cancel next week's pick (unchoose)", cancel.name(), "a pick still to come, as before");
        assertTrue(String.join(" ", cancel.lore()).contains("gets its own new course"), "as before");

        GenOps.Tools daily = new GenOps.Tools(true, true, 1, null, false, null, null, false, false);
        assertEquals("&dAdmin tools: Golf of the Day", FreshAdmin.title(GOLF, daily), "golf's name at the cadence");
        assertTrue(FreshAdmin.headerName(GOLF, daily).startsWith("&dAdmin tools &7- Golf of the Day"),
                FreshAdmin.headerName(GOLF, daily));
        assertEquals("&dAdmin tools: Parkour", FreshAdmin.title(PARKOUR, daily), "any other course keeps its name");
        assertEquals("&dAdmin tools: Golf of the Week", FreshAdmin.title(GOLF, null), "the shipped name when unknown");
    }

    // ---- D6: Choose says when it replaces a pick --------------------------------------------------------

    @Test
    void chooseSaysInItsNamesWhenItReplacesAPick() {
        FreshAdmin.Tool replace = tool(FreshAdmin.tools(PARKOUR, tools(B, true, A, false)), FreshAdmin.Kind.CHOOSE);
        assertTrue(replace.name().contains("instead of your pick"), "the tool's NAME says a pick is replaced: "
                + replace.name());
        assertEquals("&aYes: use seed " + B_HEX + " instead of " + A_HEX, replace.yes(),
                "the Sure screen's Yes names both seeds (Bedrock reads the NAME only)");
        assertTrue(String.join(" ", replace.lore()).contains("Replaces your pick: seed " + A_HEX), replace.lore()
                .toString());

        FreshAdmin.Tool first = tool(FreshAdmin.tools(PARKOUR, tools(B, true, null, false)), FreshAdmin.Kind.CHOOSE);
        assertEquals("&6Admin: Use it next week (choose)", first.name(), "nothing to replace: as before");
        assertEquals("&aYes: use it next week", first.yes(), "as before");
        FreshAdmin.Tool same = tool(FreshAdmin.tools(PARKOUR, tools(A, true, A, false)), FreshAdmin.Kind.CHOOSE);
        assertFalse(same.name().contains("instead"), "the preview is the pick already: nothing is replaced");
        FreshAdmin.Tool afterUp = tool(FreshAdmin.tools(PARKOUR, tools(B, true, A, true)), FreshAdmin.Kind.CHOOSE);
        assertFalse(afterUp.name().contains("instead"), "the pick up now isn't replaced by next week's");
    }

    // ---- D7: no tools for someone who isn't an admin (any more) -------------------------------------------

    @Test
    void someoneWhoLostTheAdminPermissionGetsNoToolsScreen() {
        List<String> heard = new ArrayList<>();
        int[] closed = {0};
        Player kid = player(false, heard, closed);
        assertFalse(FreshAdminMenu.admin(kid), "not an admin (any more): no tools or confirm screen opens");
        assertEquals(List.of("That's for admins."), heard, "told why");
        assertEquals(1, closed[0], "and the screen they had open closes");

        heard.clear();
        closed[0] = 0;
        assertTrue(FreshAdminMenu.admin(player(true, heard, closed)), "an admin gets the screen");
        assertEquals(List.of(), heard, "with nothing said");
        assertEquals(0, closed[0], "and nothing closed");
    }

    private static Player player(boolean admin, List<String> heard, int[] closed) {
        UUID id = new UUID(7, admin ? 1 : 2);
        return (Player) Proxy.newProxyInstance(FreshAdminReviewTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "hasPermission" -> admin && FreshAdmin.PERMISSION.equals(a[0]);
                    case "sendMessage" -> {
                        if (a[0] instanceof Component c) {
                            heard.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                    .serialize(c));
                        } else {
                            heard.add(String.valueOf(a[0]));
                        }
                        yield null;
                    }
                    case "closeInventory" -> {
                        closed[0]++;
                        yield null;
                    }
                    case "getUniqueId" -> id;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == a[0];
                    default -> m.getReturnType() == boolean.class ? false : null;
                });
    }

    // ---- D8: the key facts in the NAMEs (Bedrock) ----------------------------------------------------

    @Test
    void theFactsThatChangeWhatATapDoesAreInTheNames() {
        List<FreshAdmin.Tool> next = FreshAdmin.tools(PARKOUR, tools(A, true, null, false));
        assertFalse(names(next).stream().anyMatch(n -> n.contains("(promote)")), "next week's preview can't be promoted"
                + " (the command refuses it): no Use it now button: " + names(next));
        assertEquals("&aAdmin: Try next week's preview (test run)", tool(next, FreshAdmin.Kind.TEST).name(),
                "which set the preview is for is in the NAME");
        List<FreshAdmin.Tool> now = FreshAdmin.tools(PARKOUR, tools(A, false, null, false));
        assertEquals("&aAdmin: Try this week's preview (test run)", tool(now, FreshAdmin.Kind.TEST).name(),
                "a preview of the set up now");
        assertEquals("&6Admin: Use it now (promote)", tool(now, FreshAdmin.Kind.PROMOTE).name(),
                "which can be promoted");
        assertEquals("&aAdmin: Walk next week's preview (go there)", tool(FreshAdmin.tools(GOLF, tools(A, true, null,
                false)), FreshAdmin.Kind.WALK).name(), "golf's walk says it too");

        for (FreshAdmin.Kind k : List.of(FreshAdmin.Kind.REGENERATE, FreshAdmin.Kind.PROMOTE)) {
            String yes = tool(now, k).yes();
            assertTrue(yes.contains("a running Weekly Cup is called off"), k + "'s Yes NAME says what happens to the"
                    + " Cup: " + yes);
            String golfYes = tool(FreshAdmin.tools(GOLF, tools(A, false, null, false)), k).yes();
            assertFalse(golfYes.contains("Weekly Cup"), "golf runs no Weekly Cup: " + golfYes);
        }

        GenOps.Tools picked = tools(null, false, A, false);
        assertEquals("&dAdmin tools &7- Parkour &6- picked seed " + A_HEX + " for " + NEXT_WEEK,
                FreshAdmin.headerName(PARKOUR, picked), "the tools screen's header NAME says what is picked");
        assertTrue(FreshAdmin.itemName(picked).contains("picked for " + NEXT_WEEK), "and the course screen's item: "
                + FreshAdmin.itemName(picked));
        assertEquals(FreshAdmin.itemName(), FreshAdmin.itemName(tools(null, false, null, false)), "no pick: as before");
        assertTrue(FreshAdmin.itemName(tools(null, false, A, true)).contains("picked for " + NEXT_WEEK + " (up now)"),
                FreshAdmin.itemName(tools(null, false, A, true)));
    }
}
