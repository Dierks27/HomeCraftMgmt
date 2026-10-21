package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.CapLook;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.util.TokenPrizes;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import org.bukkit.Particle;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Startup self-test for the collectors on the live API (HALLOWEEN-SPEC §3.1 #11): builds each collector
 * cap and checks the parts of its look that only a server can build — the dye, the trim, unbreakable,
 * an EMPTY attribute list (no armour) and the tags — then checks each collector trail's particle needs
 * no data (one that fails is not sold or given), and that the clock isn't on UTC. Each failure is one
 * warning, never fatal, the pattern of {@code mini.ItemSelfTest}.
 */
public final class CollectorSelfTest {

    private CollectorSelfTest() {
    }

    public static void run(HomeCraftManagement plugin) {
        for (Prize p : Collectors.ROWS) {
            if (p.type() == PrizeType.HAT && p.collector() != null && p.collector().look() != null) {
                cap(plugin, p);
            } else if (p.type() == PrizeType.TRAIL) {
                trail(plugin, p);
            }
        }
        try {
            if (utc(plugin.clock().zone())) {
                plugin.getLogger().warning("Collector self-test: clock.time_zone is UTC, so the collectors' seasons "
                        + "end at midnight UTC (7 PM on 31 October in Chicago). Set clock.time_zone to your "
                        + "players' zone, e.g. America/Chicago.");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Collector self-test: the clock check threw " + t);
        }
    }

    private static void cap(HomeCraftManagement plugin, Prize p) {
        try {
            ItemStack cap = PrizeItems.collectorCap(plugin, p, null);
            if (cap == null) {
                plugin.getLogger().warning("Collector self-test: " + p.id() + " could not be made - it can't be "
                        + "bought or given.");
                return;
            }
            DyedItemColor dye = cap.getData(DataComponentTypes.DYED_COLOR);
            ItemAttributeModifiers attributes = cap.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
            PrizeType kind = PrizeItems.kindOf(cap);
            CapFacts facts = new CapFacts(dye == null ? null : dye.color().asRGB(),
                    cap.hasData(DataComponentTypes.TRIM), cap.hasData(DataComponentTypes.UNBREAKABLE),
                    attributes == null ? -1 : attributes.modifiers().size(),
                    TokenPrizes.prizeId(cap), kind == null ? null : kind.name());
            List<String> problems = capProblems(p, facts);
            for (String problem : problems) {
                plugin.getLogger().warning("Collector self-test: " + problem);
            }
            if (problems.isEmpty()) {
                plugin.getLogger().info("Self-test: collector " + p.id()
                        + " OK (dye, trim, unbreakable, no armour, tagged).");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Collector self-test: building " + p.id() + " threw " + t);
        }
    }

    private static void trail(HomeCraftManagement plugin, Prize p) {
        try {
            Particle particle = null;
            try {
                particle = p.particle() == null ? null
                        : Particle.valueOf(p.particle().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // reported below
            }
            String problem = particleProblem(p, particle != null, particle == null ? null : particle.getDataType());
            if (problem != null) {
                plugin.getLogger().warning("Collector self-test: " + problem);
            } else {
                plugin.getLogger().info("Self-test: collector " + p.id() + " particle " + particle.name() + " OK.");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Collector self-test: checking " + p.id() + " threw " + t);
        }
    }

    /**
     * What a built cap carries: the dye (null for none), whether it has a trim and is unbreakable, how
     * many attribute modifiers it has (-1 for no attribute list at all), and its two tags.
     */
    record CapFacts(Integer rgb, boolean trim, boolean unbreakable, int attributeModifiers, String prizeId,
                    String kind) {
    }

    /** One line per part of the look or tags the built cap is missing; empty when it is all there. */
    static List<String> capProblems(Prize p, CapFacts f) {
        List<String> out = new ArrayList<>();
        CapLook look = p.collector().look();
        int want = look.rgb() & 0xFFFFFF;
        if (f.rgb() == null || (f.rgb() & 0xFFFFFF) != want) {
            out.add(p.id() + " is not dyed " + String.format("#%06X", want)
                    + " (DYED_COLOR) - it shows as plain leather.");
        }
        if (look.trimPattern() != null && look.trimMaterial() != null && !f.trim()) {
            out.add(p.id() + " has no trim (TRIM " + look.trimPattern() + "/" + look.trimMaterial()
                    + ") - it shows without its trim.");
        }
        if (!f.unbreakable()) {
            out.add(p.id() + " is not unbreakable (UNBREAKABLE) - a worn cap would wear out.");
        }
        if (f.attributeModifiers() > 0) {
            out.add(p.id() + " has " + f.attributeModifiers() + " attribute modifier(s) (ATTRIBUTE_MODIFIERS "
                    + "should be empty) - it gives armour.");
        } else if (f.attributeModifiers() < 0) {
            out.add(p.id() + " has no ATTRIBUTE_MODIFIERS at all (it should be an explicit empty list) - check "
                    + "that it gives no armour.");
        }
        if (!p.id().equals(f.prizeId())) {
            out.add(p.id() + " lost its hcm:token_prize tag - it is not a prize, so it could be sold "
                    + "and isn't kept on death.");
        }
        if (!PrizeType.HAT.name().equals(f.kind())) {
            out.add(p.id() + " lost its prize_kind tag - right-clicking it won't put it on.");
        }
        return out;
    }

    /**
     * Null when the trail's particle exists and needs no data; otherwise why it would show nothing, and so is
     * not sold or given here ({@link TrailService#particleUsable}, the same two facts). This is the line at
     * enable that says so (final audit F7). Like {@code /hcm give prize}'s answer it names no version (the
     * Autumn Leaves 2026's particle is new in the 26.3 API; a later trail's may be newer still).
     */
    static String particleProblem(Prize p, boolean known, Class<?> dataType) {
        if (!known) {
            return p.id() + "'s particle " + p.particle() + " is not a particle on this server - the trail "
                    + "is not sold or given (it can't show on this server yet); one already owned shows after "
                    + "a Paper update that has it.";
        }
        if (dataType != Void.class) {
            return p.id() + "'s particle " + p.particle() + " needs data (" + (dataType == null ? "null"
                    : dataType.getSimpleName()) + ") - the trail shows nothing, so it is not sold or given.";
        }
        return null;
    }

    /** True when the zone is UTC under any name ("UTC", "Z", "GMT", "Etc/UTC"): no offset, all year. */
    static boolean utc(ZoneId zone) {
        if (zone == null) {
            return true;
        }
        var rules = zone.getRules();
        return rules.isFixedOffset() && ZoneOffset.UTC.equals(rules.getOffset(Instant.EPOCH));
    }
}
