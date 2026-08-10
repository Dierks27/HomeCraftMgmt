package com.dierks.homecraft.util;

import net.kyori.adventure.text.Component;
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
}
