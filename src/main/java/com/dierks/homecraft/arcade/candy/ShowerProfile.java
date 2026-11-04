package com.dierks.homecraft.arcade.candy;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.config.CandyConfig;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.util.Keys;
import org.bukkit.NamespacedKey;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One shower's identity (0.38.6): one engine ({@link CandyService}) runs a Candy Apple shower in October and a
 * Harvest Basket in November, each with its own instance, settings, keys, ledger source, items, words, sounds,
 * treat and reward. Everything persisted keeps its 0.38.5 name for candy: {@code arcade.events.candy},
 * {@code hcm:candy_item}, {@code candy_drop}, the {@code candy:} counters and {@code EVENT_CANDY}.
 *
 * <p>The keys are {@link Supplier}s because {@link Keys} are set when the plugin enables, after a profile may be
 * built; the profile is plain data otherwise, so a test can read it with no server.
 *
 * @param id            the shower's word: "candy", "harvest" ({@code /hcm <id>})
 * @param configPath    its section in config.yml
 * @param label         "the Candy Apple shower", "the Harvest Basket"
 * @param counterPrefix the start of its {@code player_counters} keys ({@code candy:2026})
 * @param dropKey       the tag on its items lying on the ground
 * @param itemKey       the key its items are known by
 * @param source        its token ledger source, whose lines today hold its daily cap
 * @param settings      its settings from the parsed config
 * @param overworldOnly it falls in the Overworld only: never in the Nether or the End
 */
public record ShowerProfile(String id, String configPath, String label, String counterPrefix,
                            Supplier<NamespacedKey> dropKey, Supplier<NamespacedKey> itemKey,
                            TokenService.Source source, Function<PluginConfig, CandyConfig.Candy> settings,
                            ShowerItems items, ShowerText text, ShowerFx fx, ShowerTreat treat, ShowerReward reward,
                            boolean overworldOnly) {

    /**
     * The Candy Apple shower, as 0.38.5 ran it, except that the "candy apples fall every ..." line comes on the
     * first join of a local day only, and that it falls in the Overworld only: the Nether and the End can be
     * economy worlds now, and no child's treats should land by lava.
     */
    public static ShowerProfile candy() {
        ShowerText text = ShowerText.candy();
        return new ShowerProfile("candy", CandyConfig.PATH, text.label(), "candy", () -> Keys.CANDY_DROP,
                () -> Keys.CANDY_ITEM, TokenService.Source.EVENT_CANDY, PluginConfig::candy, new CandyShowerItems(),
                text, ShowerFx.candy(), ShowerTreat.candy(), new CandyBag(), true);
    }

    /**
     * The Harvest Basket (Thanksgiving 2026): cornbread, pumpkin pies and baked potatoes, a Golden Pie, and the
     * Pilgrim Hat at the goal. It falls in the Overworld only.
     */
    public static ShowerProfile harvest() {
        ShowerText text = ShowerText.harvest();
        return new ShowerProfile("harvest", CandyConfig.HARVEST_PATH, text.label(), "harvest",
                () -> Keys.HARVEST_DROP, () -> Keys.HARVEST_ITEM, TokenService.Source.EVENT_HARVEST,
                PluginConfig::harvest, new HarvestShowerItems(), text, ShowerFx.harvest(), ShowerTreat.harvest(),
                new PilgrimHat(), true);
    }

    /** The player's count key for the year. */
    public String counterKey(int year) {
        return CandyRules.counterKey(counterPrefix, year);
    }

    /** The golden-seen mark for the year. */
    public String goldenSeenKey(int year) {
        return CandyRules.goldenSeenKey(counterPrefix, year);
    }

    /** The reward's mark for the year ({@code candy:2026:bag}, {@code harvest:2026:hat}). */
    public String rewardKey(int year) {
        return CandyRules.rewardKey(counterPrefix, reward.markSuffix(), year);
    }
}
