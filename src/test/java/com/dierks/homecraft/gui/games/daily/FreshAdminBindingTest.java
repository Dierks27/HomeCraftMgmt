package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-2 audit, G2 #1, on the tools themselves: Promote's and Choose's buttons carry what their Sure
 * screen showed (the preview's seed, and for Choose the pick it replaces or that there is none), so the
 * command refuses once that has changed; their Yes names the seed it acts on; and while the slot is
 * being built (the admin's own new preview still planning, with the old one still shown) neither is
 * offered, and the NAME says why (Bedrock shows lore only on a hold).
 */
class FreshAdminBindingTest {

    private static final Slots.Def PARKOUR = Slots.of("fresh_parkour");
    private static final long A = 0x3f2aL;
    private static final long B = 0x5eedL;
    private static final String A_HEX = "0000000000003f2a";
    private static final String B_HEX = "0000000000005eed";
    private static final String NEXT_WEEK = "Mon 5 Oct-Sun 11 Oct";

    private static GenOps.Tools tools(Long preview, boolean previewNext, Long chosen, boolean upNow, boolean busy) {
        return new GenOps.Tools(true, false, 7, preview, previewNext, chosen, chosen == null ? null : NEXT_WEEK, busy,
                upNow);
    }

    private static FreshAdmin.Tool tool(GenOps.Tools t, FreshAdmin.Kind kind) {
        return FreshAdmin.tools(PARKOUR, t).stream().filter(x -> x.kind() == kind).findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind));
    }

    @Test
    void promoteAndChooseCarryWhatTheirSureScreenShowed() {
        FreshAdmin.Tool promote = tool(tools(A, false, null, false, false), FreshAdmin.Kind.PROMOTE);
        assertEquals(List.of("promote", "fresh_parkour", A_HEX, "confirm"), Arrays.asList(promote.command()),
                "Promote's Yes names the preview it showed: the command refuses once another stands");
        assertTrue(promote.yes().contains(A_HEX), "and its Yes NAME says which: " + promote.yes());

        FreshAdmin.Tool fresh = tool(tools(B, true, null, false, false), FreshAdmin.Kind.CHOOSE);
        assertEquals(List.of("choose", "fresh_parkour", B_HEX, "none", "confirm"), Arrays.asList(fresh.command()),
                "Choose names the preview, and that it showed no pick to replace");
        assertTrue(fresh.yes().contains(B_HEX), "its Yes NAME says which seed: " + fresh.yes());

        FreshAdmin.Tool replace = tool(tools(B, true, A, false, false), FreshAdmin.Kind.CHOOSE);
        assertEquals(List.of("choose", "fresh_parkour", B_HEX, A_HEX, "confirm"), Arrays.asList(replace.command()),
                "and the pick its Yes said it replaces");

        FreshAdmin.Tool same = tool(tools(A, true, A, false, false), FreshAdmin.Kind.CHOOSE);
        assertEquals(List.of("choose", "fresh_parkour", A_HEX, A_HEX, "confirm"), Arrays.asList(same.command()),
                "the preview that is the pick already");

        FreshAdmin.Tool overUpNow = tool(tools(B, true, A, true, false), FreshAdmin.Kind.CHOOSE);
        assertEquals(List.of("choose", "fresh_parkour", B_HEX, "none", "confirm"), Arrays.asList(overUpNow.command()),
                "the pick up now is no pick still to come: there is none to replace");
    }

    @Test
    void whileTheSlotIsBeingBuiltThePreviewCanBeNeitherPromotedNorChosenAndTheNameSaysSo() {
        GenOps.Tools busy = tools(A, false, null, false, true);
        List<FreshAdmin.Kind> kinds = FreshAdmin.tools(PARKOUR, busy).stream().map(FreshAdmin.Tool::kind).toList();
        assertFalse(kinds.contains(FreshAdmin.Kind.PROMOTE), "no Sure screen for a preview about to be replaced: "
                + kinds);
        assertFalse(kinds.contains(FreshAdmin.Kind.CHOOSE), "nor for choosing it: " + kinds);
        assertTrue(kinds.contains(FreshAdmin.Kind.REGENERATE) && kinds.contains(FreshAdmin.Kind.PREVIEW),
                "the rest are there as ever (their commands say it is busy): " + kinds);
        assertTrue(FreshAdmin.headerName(PARKOUR, busy).contains("being built"), "the header NAME says why: "
                + FreshAdmin.headerName(PARKOUR, busy));
        assertTrue(FreshAdmin.itemName(busy).contains("being built"), "and the course screen's item NAME: "
                + FreshAdmin.itemName(busy));

        GenOps.Tools idle = tools(A, false, null, false, false);
        List<FreshAdmin.Kind> after = FreshAdmin.tools(PARKOUR, idle).stream().map(FreshAdmin.Tool::kind).toList();
        assertTrue(after.contains(FreshAdmin.Kind.PROMOTE) && after.contains(FreshAdmin.Kind.CHOOSE),
                "once it is built, both are back: " + after);
        assertFalse(FreshAdmin.headerName(PARKOUR, idle).contains("being built"), FreshAdmin.headerName(PARKOUR, idle));
    }
}
