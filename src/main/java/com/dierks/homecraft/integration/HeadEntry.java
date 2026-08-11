package com.dierks.homecraft.integration;

/**
 * One head fetched from the web head library: a display name, its Base64 texture
 * {@code value} (the only field we actually need to render it natively), the
 * category it came from, and any tags (for keyword filtering).
 */
public record HeadEntry(String name, String texture, String category, String tags) {

    /** True if the keyword appears in the name or tags (case-insensitive). */
    public boolean matches(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String k = keyword.toLowerCase();
        return (name != null && name.toLowerCase().contains(k))
                || (tags != null && tags.toLowerCase().contains(k));
    }
}
