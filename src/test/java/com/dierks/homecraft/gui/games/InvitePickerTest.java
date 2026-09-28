package com.dierks.homecraft.gui.games;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who the player picker may list (spec §3.1.8, §5.7): paused, limited or non-permitted players
 * never appear, and neither does anyone who can't take the invite right now.
 */
class InvitePickerTest {

    @Test
    void aFriendGameListsAnyoneWhoCanPlayAndTakesInvites() {
        assertTrue(InvitePicker.listable(false, true, false, false, false, true, true, false),
                "a skill game ignores games-of-chance breaks and permissions");
        assertFalse(InvitePicker.listable(true, true, false, true, true, false, true, false),
                "never yourself");
        assertFalse(InvitePicker.listable(false, false, false, true, true, false, true, false),
                "not someone without hcm.games.play");
        assertFalse(InvitePicker.listable(false, true, false, true, true, false, false, false),
                "not someone who turned this game's invites off");
        assertFalse(InvitePicker.listable(false, true, false, true, true, false, true, true),
                "not someone who already has an invite waiting");
    }

    @Test
    void aGameOfChanceAlsoNeedsThePermissionAndNoBreak() {
        assertTrue(InvitePicker.listable(false, true, true, true, true, false, true, false),
                "allowed, readable and not paused: listed");
        assertFalse(InvitePicker.listable(false, true, true, false, true, false, true, false),
                "no hcm.games.chance: never listed");
        assertFalse(InvitePicker.listable(false, true, true, true, true, true, true, false),
                "on a break: never listed");
        assertFalse(InvitePicker.listable(false, true, true, true, false, false, true, false),
                "a break that can't be read: left out (fails closed)");
    }
}
