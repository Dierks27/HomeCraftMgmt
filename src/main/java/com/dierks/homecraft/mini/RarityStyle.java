package com.dierks.homecraft.mini;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

/**
 * The visual style + smart defaults for a rarity tier. You assign the tier; the
 * colour, glint, and default cap/price follow — edit the palette once in config.
 *
 * @param pane        the stained-glass pane that frames the Mini in a GUI
 * @param nameColor   the Mini's display-name colour
 * @param glint       whether the Mini item shows an enchant glint (shiny)
 * @param defaultCap  default mint cap when an entry doesn't set its own (-1 = uncapped)
 * @param defaultPrice default mint price when an entry doesn't set its own
 */
public record RarityStyle(Material pane, NamedTextColor nameColor, boolean glint,
                          long defaultCap, double defaultPrice) {
}
