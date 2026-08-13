package com.dierks.homecraft.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Small Adventure helpers so the rest of the plugin can keep working with
 * simple legacy '&amp;'-coded strings from config while producing proper
 * {@link Component}s (Paper's native text type).
 */
public final class Text {

    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    /** Deserialize an '&amp;'-coded string, disabling the default item-name italics. */
    public static Component of(String legacy) {
        return AMPERSAND.deserialize(legacy == null ? "" : legacy)
                .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * A clickable chat link: rendering {@code label} (may carry '&amp;' colour codes)
     * that opens {@code url} in the player's browser when clicked, with the URL shown
     * on hover. Used by the TV block to launch a stream with full video + sound.
     */
    public static Component link(String label, String url) {
        return of(label)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(of("&7Click to open:\n&f" + url)));
    }
}
