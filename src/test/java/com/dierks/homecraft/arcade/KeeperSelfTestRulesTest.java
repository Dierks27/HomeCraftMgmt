package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.KeeperSelfTest.LookFacts;
import com.dierks.homecraft.config.PluginConfig.KeeperLook;
import com.dierks.homecraft.config.PluginConfig.Prize;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Keeper self-test's rules (SHOP-V2-SPEC §5.2), without a server: a whole look passes; each missing part is one
 * line; the Crown's Java-only parts are judged on their own (a Crown whose gold didn't take falls back, and is no
 * problem on its own); a step whose particle needs data is named.
 */
class KeeperSelfTestRulesTest {

    private static final Prize CROWN = Keepers.row(Keepers.CROWN);
    private static final Prize KNIGHT = Keepers.row(Keepers.KNIGHT);

    private static LookFacts whole(KeeperLook look, String id) {
        return new LookFacts(look.rgb() >= 0 ? look.rgb() : null, true, true, 0, id, "HAT", true, look.javaAsset(),
                look.javaModel());
    }

    @Test
    void aWholeLookPasses() {
        for (String id : List.of(Keepers.CROWN, Keepers.WIZARD, Keepers.KNIGHT)) {
            for (Keepers.Variant v : Keepers.Variant.values()) {
                KeeperLook look = Keepers.look(id, v);
                assertEquals(List.of(), KeeperSelfTest.lookProblems(Keepers.row(id), v, look, whole(look, id)),
                        id + " " + v);
            }
        }
    }

    @Test
    void eachMissingPartIsOneLine() {
        KeeperLook look = Keepers.look(Keepers.KNIGHT, Keepers.Variant.DEFAULT);
        LookFacts bare = new LookFacts(null, false, false, 2, null, null, false, null, null);
        List<String> problems = KeeperSelfTest.lookProblems(KNIGHT, Keepers.Variant.DEFAULT, look, bare);
        assertEquals(6, problems.size(), "trim, unbreakable, armour, token tag, kind, binding (iron takes no dye): "
                + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("gives armour")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("hcm:bound_to")));
        KeeperLook crown = Keepers.look(Keepers.CROWN, Keepers.Variant.DEFAULT);
        LookFacts undyed = new LookFacts(0xA06540, true, true, 0, Keepers.CROWN, "HAT", true, null, null);
        assertEquals(1, KeeperSelfTest.lookProblems(CROWN, Keepers.Variant.DEFAULT, crown, undyed).size(),
                "the dye; the gold is judged on its own");
    }

    @Test
    void theCrownsGoldIsJudgedOnItsOwn() {
        KeeperLook crown = Keepers.look(Keepers.CROWN, Keepers.Variant.DEFAULT);
        assertTrue(KeeperSelfTest.javaPartsTook(crown, whole(crown, Keepers.CROWN)));
        assertFalse(KeeperSelfTest.javaPartsTook(crown, new LookFacts(crown.rgb(), true, true, 0, Keepers.CROWN, "HAT",
                true, "minecraft:leather", "minecraft:golden_helmet")), "the asset didn't take");
        assertFalse(KeeperSelfTest.javaPartsTook(crown, new LookFacts(crown.rgb(), true, true, 0, Keepers.CROWN, "HAT",
                true, "minecraft:gold", null)), "the model didn't take");
    }

    @Test
    void aStepThatNeedsDataIsNamed() {
        assertNull(KeeperSelfTest.particleProblem("x", new TrailService.Step(Particle.WITCH,
                new TrailService.Puff(0, 1, 0, 0, 0))));
        String dust = KeeperSelfTest.particleProblem("keeper_x", new TrailService.Step(Particle.DUST,
                new TrailService.Puff(0, 1, 0, 0, 0)));
        assertTrue(dust != null && dust.contains("DUST"), String.valueOf(dust));
        assertTrue(KeeperSelfTest.particleProblem("keeper_x", null).contains("no particle"));
    }
}
