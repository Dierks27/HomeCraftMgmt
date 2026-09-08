package com.dierks.homecraft.block;

import java.util.Locale;

/**
 * The Mailbox colour variants. Each has its own head skin ({@code skins.mailbox.<key>})
 * and its own vanilla recipe ({@code recipes.mailbox.<key>}). The variant travels on
 * the item and on the placed tile in PDC ({@code Keys.MAILBOX_VARIANT}); a placed
 * Mailbox with no variant tag (pre-variant placements) reads as {@link #WOOD}.
 */
public enum MailboxVariant {
    WOOD("Wood"),
    LIGHT_BLUE("Light Blue"),
    BLACK("Black"),
    WHITE("White"),
    PURPLE("Purple"),
    BLUE("Blue"),
    ORANGE("Orange"),
    YELLOW("Yellow");

    private final String label;

    MailboxVariant(String label) {
        this.label = label;
    }

    /** Human label used in the display name ("Blue Mailbox"). */
    public String label() {
        return label;
    }

    /** The config / command key: lower-case enum name (e.g. {@code light_blue}). */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parse a key or enum name leniently; null if it isn't a variant. */
    public static MailboxVariant parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String k = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (MailboxVariant v : values()) {
            if (v.name().equals(k)) {
                return v;
            }
        }
        return null;
    }

    /** As {@link #parse} but never null — unknown/missing reads as {@link #WOOD}. */
    public static MailboxVariant parseOrWood(String raw) {
        MailboxVariant v = parse(raw);
        return v != null ? v : WOOD;
    }
}
