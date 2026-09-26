package com.dierks.homecraft.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Small Adventure helpers so the rest of the plugin can keep working with
 * simple legacy '&amp;'-coded strings from config while producing proper
 * {@link Component}s (Paper's native text type).
 */
public final class Text {

    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    /** A legacy colour/format code, in either the '&amp;' or the section-sign form. */
    private static final java.util.regex.Pattern CODES = java.util.regex.Pattern.compile("(?i)[&\u00a7][0-9a-fk-or]");

    private Text() {
    }

    /** Deserialize an '&amp;'-coded string, disabling the default item-name italics. */
    public static Component of(String legacy) {
        return AMPERSAND.deserialize(legacy == null ? "" : legacy)
                .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * The words without the colours — for a log line, a ledger entry or anywhere else a
     * config display string ends up that is not a chat message or an item.
     */
    public static String plain(String legacy) {
        return legacy == null ? "" : CODES.matcher(legacy).replaceAll("").trim();
    }

    /**
     * The '&amp;' code for one of the sixteen named colours, so a colour that lives in config as a
     * {@link NamedTextColor} (a rarity's, say) can open a legacy string. Anything else — a hex
     * colour has no code — falls back to white rather than to nothing.
     */
    public static String code(TextColor color) {
        if (color == null) {
            return "&f";
        }
        NamedTextColor named = color instanceof NamedTextColor n ? n : NamedTextColor.nearestTo(color);
        String legacy = AMPERSAND.serialize(Component.text("x", named));
        return legacy.length() >= 3 && legacy.charAt(0) == '&' ? legacy.substring(0, 2) : "&f";
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
