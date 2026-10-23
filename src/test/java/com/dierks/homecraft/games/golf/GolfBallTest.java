package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeExtra;
import com.dierks.homecraft.config.PluginConfig.PrizeLimit;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.games.golf.BallPhysics.Ball;
import com.dierks.homecraft.games.golf.BallPhysics.Outcome;
import com.dierks.homecraft.games.golf.BallPhysics.Surface;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golf balls from the Prize Counter (P4, SHOP-V2-SPEC §5.10): a {@code ball:<id>} choice plays as its block on
 * both editions while the player owns it, an owned ball keeps playing when its row is switched off, a ball they
 * don't own (or a row that isn't a ball) plays white, a choice is never read as a Mini, "Pick your ball" lists the
 * bought balls first, and the look never reaches the physics: the same shot takes the same path for every ball.
 */
class GolfBallTest {

    private static Prize ball(String id, Material look, boolean enabled) {
        return new Prize(id, PrizeTab.LOOKS, "&d" + id, List.of(), 40, new Icon("", look), PrizeType.BALL, enabled,
                new PrizeLimit(LimitPer.LIFETIME, 1), 1, null, null, null, null, 0, 0, null, 0, "", List.of(),
                List.of(), null, null, List.of(), null, new PrizeExtra(null, 0, look, OnlyIf.ANY, 0, null));
    }

    private static final Map<String, Prize> ROWS = new HashMap<>(Map.of(
            "ball_pink", ball("ball_pink", Material.PINK_CONCRETE, true),
            "ball_gold", ball("ball_gold", Material.GOLD_BLOCK, false),
            "speed_boost", new Prize("speed_boost", PrizeTab.BOOSTS, "&bSpeed", List.of(), 8,
                    new Icon("", Material.SUGAR), PrizeType.BOOST, true, null, 1, null, null, null, "speed", 1, 10,
                    null, 0, "", List.of(), List.of(), null, null, List.of(), null)));

    @Test
    void aBoughtBallPlaysAsItsBlockOnBothEditions() {
        assertEquals(Material.PINK_CONCRETE, MiniGolf.shopBall("ball:ball_pink", ROWS::get, Set.of("ball_pink")::contains),
                "the choice is the block, whatever the edition (ballItem reads it before the Bedrock rule)");
        assertEquals("ball:ball_pink", MiniGolf.shopBallPref("ball_pink"));
        assertEquals("ball_pink", MiniGolf.shopBallId("ball:ball_pink"));
    }

    @Test
    void anOwnedBallWithItsRowSwitchedOffStillPlays() {
        assertEquals(Material.GOLD_BLOCK, MiniGolf.shopBall("ball:ball_gold", ROWS::get, Set.of("ball_gold")::contains),
                "bought for good: a row switched off doesn't take it away");
    }

    @Test
    void aBallTheyDontOwnPlaysWhite() {
        assertNull(MiniGolf.shopBall("ball:ball_pink", ROWS::get, Set.<String>of()::contains),
                "a ball: pref with no claim behind it (an edited pref, a moved account) plays white");
        assertNull(MiniGolf.shopBall("ball:ball_gone", ROWS::get, id -> true), "a row that is gone");
        assertNull(MiniGolf.shopBall("ball:speed_boost", ROWS::get, id -> true), "a row that isn't a ball");
        assertNull(MiniGolf.shopBall("ball:", ROWS::get, id -> true), "an empty id");
    }

    @Test
    void aMiniChoiceIsNeverReadAsABallNorABallAsAMini() {
        assertNull(MiniGolf.shopBall("creeper_mini", ROWS::get, id -> true), "a Mini id is the old path");
        assertNull(MiniGolf.shopBall(null, ROWS::get, id -> true), "no choice is white");
        assertNull(MiniGolf.shopBallId("creeper_mini"));
        assertTrue(MiniGolf.shopBallPref("ball_pink").startsWith(MiniGolf.SHOP_BALL), "the prefix a Mini id never has");
    }

    @Test
    void pickYourBallListsTheBoughtBallsFirstOnBothEditions() {
        List<String> java = MiniGolf.ballOrder(List.of("ball_pink", "ball_gold"), List.of("fox", "owl"), false);
        assertEquals(java("ball:ball_pink", "ball:ball_gold", "fox", "owl", null), java,
                "Java: the balls, the Minis, then white");
        List<String> bedrock = MiniGolf.ballOrder(List.of("ball_pink"), List.of("fox"), true);
        assertEquals(java("ball:ball_pink", null), bedrock, "Bedrock: the balls, then white (no head textures)");
    }

    private static List<String> java(String... prefs) {
        List<String> out = new ArrayList<>();
        for (String p : prefs) {
            out.add(p);
        }
        return out;
    }

    // ---- the physics never reads the look --------------------------------------------------------

    @Test
    void thePhysicsTakesNoLook() {
        for (Class<?> c : List.of(BallPhysics.class, Ball.class)) {
            for (Field f : c.getDeclaredFields()) {
                assertFalse(f.getType() == ItemStack.class || f.getType() == Material.class,
                        c.getSimpleName() + "." + f.getName() + " would let the look reach the physics");
            }
            for (Method m : c.getDeclaredMethods()) {
                for (Class<?> t : m.getParameterTypes()) {
                    assertFalse(t == ItemStack.class || t == Material.class,
                            c.getSimpleName() + "." + m.getName() + " takes a look");
                }
            }
        }
    }

    @Test
    void theSameShotTakesTheSamePathForEveryLook() {
        List<String> first = null;
        for (Material look : List.of(Material.WHITE_CONCRETE, Material.PINK_CONCRETE, Material.GOLD_BLOCK,
                Material.DIAMOND_BLOCK, Material.PLAYER_HEAD)) {
            List<String> path = putt(look);
            if (first == null) {
                first = path;
            } else {
                assertEquals(first, path, look + " rolls exactly as the white ball does");
            }
        }
        assertTrue(first.size() > 5, "the putt went somewhere: " + first.size() + " ticks");
    }

    /** One putt on a plain lane; {@code look} is what the player picked, which nothing here reads. */
    private static List<String> putt(Material look) {
        BallPhysicsTest.Grid lane = new BallPhysicsTest.Grid().floor(-20, 40, -3, 3, 63, Surface.NORMAL);
        BallPhysics.Hole open = BallPhysics.Hole.of(100, 63, 100, -50, 58, -50, 150, 83, 150);
        Ball b = new Ball(0.5, 64, 0.5);
        b.putt(1, 0.2, 0.6);
        List<String> path = new ArrayList<>();
        for (int i = 0; i < 500 && BallPhysics.tick(b, lane, open) == Outcome.ROLLING; i++) {
            path.add(b.x() + "," + b.y() + "," + b.z());
        }
        return path;
    }
}
