package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.KeeperLook;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.TokenPrizes;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Startup self-test for the Keepers on the live API (SHOP-V2-SPEC §5.2), on {@link CollectorSelfTest}'s pattern: builds
 * each worn Keeper in both of its forms and checks what only a server can build — the dye, the trim, unbreakable, an
 * EMPTY attribute list (no armour, the Knight's Helm's iron included), the tags, and the Legend Crown's golden helmet
 * asset and model for Java. If either of the Crown's Java-only parts didn't take, the Crown is made without them (the
 * plain gold-dyed look on both editions), with one WARN. Then every pattern step's and mark's particle is checked
 * ({@link TrailService#particleUsable}): a step that fails is left out of its trail, a mark that fails draws nothing.
 * Each failure is one warning, never fatal.
 */
public final class KeeperSelfTest {

    private KeeperSelfTest() {
    }

    public static void run(HomeCraftManagement plugin) {
        int problems = 0;
        for (Prize p : Keepers.ROWS) {
            if (p.type() == PrizeType.HAT) {
                for (Keepers.Variant v : Keepers.Variant.values()) {
                    problems += look(plugin, p, v);
                }
                problems += particle(plugin, p.id() + "'s mark", Keepers.mark(p.id(), Keepers.Variant.DEFAULT));
                if (Keepers.CROWN.equals(p.id())) {
                    problems += particle(plugin, p.id() + "'s mark (fallback)",
                            Keepers.mark(p.id(), Keepers.Variant.FALLBACK));
                }
            } else {
                for (Keepers.Variant v : Keepers.Variant.values()) {
                    TrailService.TrailPattern pattern = Keepers.pattern(p.id(), v);
                    for (TrailService.Step step : pattern == null ? List.<TrailService.Step>of() : pattern.steps()) {
                        problems += particle(plugin, p.id() + " (" + v.key() + ")", step);
                    }
                }
            }
        }
        if (problems == 0) {
            plugin.getLogger().info("Self-test: " + Keepers.ROWS.size() + " Keepers OK (looks in both forms, the "
                    + "Crown's gold for Java, every trail step and mark).");
        }
    }

    /** Build one worn Keeper in one form and say what it lacks; the number of problems found. */
    private static int look(HomeCraftManagement plugin, Prize p, Keepers.Variant v) {
        try {
            KeeperLook look = Keepers.look(p.id(), v);
            ItemStack item = PrizeItems.keeperItem(p, new UUID(0, 1), 0, v, plugin.getLogger());
            if (item == null || look == null) {
                plugin.getLogger().warning("Keeper self-test: " + p.id() + " (" + v.key() + ") could not be made - it "
                        + "can't be bought or worn.");
                return 1;
            }
            DyedItemColor dye = item.getData(DataComponentTypes.DYED_COLOR);
            ItemAttributeModifiers attributes = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
            Equippable equippable = item.getData(DataComponentTypes.EQUIPPABLE);
            Key model = item.getData(DataComponentTypes.ITEM_MODEL);
            PrizeType kind = PrizeItems.kindOf(item);
            String bound = item.getItemMeta() == null ? null
                    : item.getItemMeta().getPersistentDataContainer().get(Keys.BOUND_TO, PersistentDataType.STRING);
            LookFacts facts = new LookFacts(dye == null ? null : dye.color().asRGB(),
                    item.hasData(DataComponentTypes.TRIM), item.hasData(DataComponentTypes.UNBREAKABLE),
                    attributes == null ? -1 : attributes.modifiers().size(), TokenPrizes.prizeId(item),
                    kind == null ? null : kind.name(), bound != null,
                    equippable == null || equippable.assetId() == null ? null : equippable.assetId().asString(),
                    model == null ? null : model.asString());
            List<String> problems = lookProblems(p, v, look, facts);
            for (String problem : problems) {
                plugin.getLogger().warning("Keeper self-test: " + problem);
            }
            if (look.javaAsset() != null && PrizeItems.crownJavaParts() && !javaPartsTook(look, facts)) {
                PrizeItems.crownJavaParts(false);
                plugin.getLogger().warning("Keeper self-test: the Legend Crown's golden look for Java didn't take, so "
                        + "it is made without it: the gold-dyed leather crown with its trim, on both editions.");
            }
            return problems.size();
        } catch (Throwable t) {
            plugin.getLogger().warning("Keeper self-test: building " + p.id() + " (" + v.key() + ") threw " + t);
            return 1;
        }
    }

    /** One particle a Keeper draws: a line when this server can't show it; the number of problems (0 or 1). */
    private static int particle(HomeCraftManagement plugin, String what, TrailService.Step step) {
        String problem = particleProblem(what, step);
        if (problem != null) {
            plugin.getLogger().warning("Keeper self-test: " + problem);
            return 1;
        }
        return 0;
    }

    /** Why a Keeper's step or mark shows nothing here ({@link TrailService#particleUsable}), or null when it shows. */
    static String particleProblem(String what, TrailService.Step step) {
        if (step == null) {
            return what + " has no particle.";
        }
        if (!TrailService.particleUsable(step.particle().name())) {
            return what + ": particle " + step.particle().name() + " can't show on this server (it needs data) - "
                    + "it is left out.";
        }
        return null;
    }

    /**
     * What a built Keeper carries: the dye (null for none), whether it has a trim and is unbreakable, how many
     * attribute modifiers it has (-1 for no list), its tags, whether it is bound, and its equipment asset and item
     * model (null for none).
     */
    record LookFacts(Integer rgb, boolean trim, boolean unbreakable, int attributeModifiers, String prizeId,
                     String kind, boolean bound, String asset, String model) {
    }

    /** Whether the Crown's two Java-only parts took. */
    static boolean javaPartsTook(KeeperLook look, LookFacts f) {
        return look.javaAsset().equals(f.asset()) && (look.javaModel() == null || look.javaModel().equals(f.model()));
    }

    /**
     * One line per part of the look or tags the built Keeper is missing; empty when it is all there. The Crown's Java
     * parts are {@link #javaPartsTook}'s to judge (they fall back), so they are not listed here.
     */
    static List<String> lookProblems(Prize p, Keepers.Variant v, KeeperLook look, LookFacts f) {
        List<String> out = new ArrayList<>();
        String what = p.id() + " (" + v.key() + ")";
        if (look.rgb() >= 0) {
            int want = look.rgb() & 0xFFFFFF;
            if (f.rgb() == null || (f.rgb() & 0xFFFFFF) != want) {
                out.add(what + " is not dyed " + String.format("#%06X", want) + " (DYED_COLOR).");
            }
        }
        if (look.trimPattern() != null && look.trimMaterial() != null && !f.trim()) {
            out.add(what + " has no trim (TRIM " + look.trimPattern() + "/" + look.trimMaterial()
                    + ") - it shows without its trim.");
        }
        if (!f.unbreakable()) {
            out.add(what + " is not unbreakable (UNBREAKABLE) - a worn one would wear out.");
        }
        if (f.attributeModifiers() > 0) {
            out.add(what + " has " + f.attributeModifiers() + " attribute modifier(s) (ATTRIBUTE_MODIFIERS should be "
                    + "empty) - it gives armour.");
        } else if (f.attributeModifiers() < 0) {
            out.add(what + " has no ATTRIBUTE_MODIFIERS at all (it should be an explicit empty list) - check that it "
                    + "gives no armour.");
        }
        if (!p.id().equals(f.prizeId())) {
            out.add(what + " lost its hcm:token_prize tag - it is not a prize, so it could be sold.");
        }
        if (!PrizeType.HAT.name().equals(f.kind())) {
            out.add(what + " lost its prize_kind tag - right-clicking it won't put it on.");
        }
        if (!f.bound()) {
            out.add(what + " lost its hcm:bound_to tag - it would not stay with its owner.");
        }
        return out;
    }
}
