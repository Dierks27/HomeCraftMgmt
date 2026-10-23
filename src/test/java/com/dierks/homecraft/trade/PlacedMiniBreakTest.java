package com.dierks.homecraft.trade;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Mini head placed as a block drops its exact copy only once the break is final (0.37.3, red team D-M6), the rule
 * the shop blocks and placed prizes follow: {@link MiniHeadListener} decides at HIGH and acts at MONITOR. It used to
 * drop the copy at HIGH, so a break a protection plugin cancelled at HIGHEST left the head standing with its copy
 * still on it, and breaking it again dropped a second one.
 */
class PlacedMiniBreakTest {

    /** One placed head and the ground around it. */
    private static final class Head {
        boolean standing = true;
        boolean holdsCopy = true; // its skull data and its placed_minis row
        final List<String> ground = new ArrayList<>();

        /**
         * A player's break, as Bukkit runs it: our HIGH decision, then another plugin at HIGHEST, then our MONITOR
         * handler, which Bukkit skips for a cancelled event because it is {@code ignoreCancelled}.
         */
        void breakBy(boolean wild, boolean mayBreak, boolean cancelledAtHighest, EventHandler finalHandler) {
            boolean cancelled = PlacedMiniRules.decide(wild, mayBreak) == PlacedMiniRules.Decision.REFUSE;
            cancelled |= cancelledAtHighest;
            if (cancelled && finalHandler.ignoreCancelled()) {
                return;
            }
            if (PlacedMiniRules.dropsCopy(wild) && holdsCopy) {
                ground.add("Mint #7");
                holdsCopy = false;
            }
            if (!cancelled) {
                standing = false;
            }
        }
    }

    @Test
    void theBreakDecidesAtHighAndDropsOnlyOnceItIsFinal() throws Exception {
        EventHandler decide = handler("onBreak");
        EventHandler act = handler("onBreakFinal");
        assertEquals(EventPriority.HIGH, decide.priority());
        assertTrue(decide.ignoreCancelled());
        assertEquals(EventPriority.MONITOR, act.priority(), "nothing drops until no plugin can cancel the break");
        assertTrue(act.ignoreCancelled(), "a break cancelled at any priority never reaches it");
    }

    @Test
    void aBreakCancelledAfterHighDropsNothingAndTheNextBreakDropsOne() throws Exception {
        EventHandler act = handler("onBreakFinal");
        Head head = new Head();
        head.breakBy(false, true, true, act);
        assertTrue(head.standing, "a protection plugin said no: the head stays");
        assertTrue(head.holdsCopy, "with its copy and its row");
        assertEquals(List.of(), head.ground, "and nothing on the ground");

        head.breakBy(false, true, false, act);
        assertFalse(head.standing);
        assertEquals(List.of("Mint #7"), head.ground, "the break that stands drops the exact copy, once");
    }

    @Test
    void whoMayTakeOneIsDecidedAtHigh() {
        assertEquals(PlacedMiniRules.Decision.WAIT, PlacedMiniRules.decide(false, true),
                "allowed: nothing happens until the break is final");
        assertEquals(PlacedMiniRules.Decision.REFUSE, PlacedMiniRules.decide(false, false), "not allowed here");
        assertEquals(PlacedMiniRules.Decision.CATCH, PlacedMiniRules.decide(true, true), "a wild Mini is caught");
        assertEquals(PlacedMiniRules.Decision.CATCH, PlacedMiniRules.decide(true, false));
    }

    @Test
    void aRefusedBreakOrACaughtWildMiniDropsNoCopy() throws Exception {
        EventHandler act = handler("onBreakFinal");
        Head refused = new Head();
        refused.breakBy(false, false, false, act);
        assertTrue(refused.standing);
        assertTrue(refused.holdsCopy);
        assertEquals(List.of(), refused.ground);

        assertFalse(PlacedMiniRules.dropsCopy(true), "a wild head carries no copy: it was caught at HIGH");
        assertTrue(PlacedMiniRules.dropsCopy(false));
        Head wild = new Head();
        wild.breakBy(true, false, false, act);
        assertEquals(List.of(), wild.ground, "the catch is the Mini; no copy drops as well");
    }

    @Test
    void aWitherIsHandledLikeAFlowAndOnlyWhileTheChangeStands() throws Exception {
        EventHandler h = MiniHeadListener.class.getMethod("onEntityChangeBlock", EntityChangeBlockEvent.class)
                .getAnnotation(EventHandler.class);
        assertNotNull(h, "a wither breaking a placed Mini head has a handler, so the copy drops, not a plain head");
        assertTrue(h.ignoreCancelled(), "a change another plugin refused leaves the head and its copy alone");
    }

    private static EventHandler handler(String name) throws Exception {
        EventHandler h = MiniHeadListener.class.getMethod(name, BlockBreakEvent.class)
                .getAnnotation(EventHandler.class);
        assertNotNull(h, name + " is a handler");
        return h;
    }
}
