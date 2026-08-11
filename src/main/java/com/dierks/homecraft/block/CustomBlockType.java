package com.dierks.homecraft.block;

/**
 * The custom "blocks" HomeCraft manages. Bukkit has no real custom blocks, so
 * each is a tagged vanilla block whose identity + owner is persisted in SQLite.
 *
 * <p>Phase 1 ships two. Later phases add {@code MINI_VENDING_MACHINE} and
 * {@code DISPLAY_CASE} — the enum is the single place they are declared.
 */
public enum CustomBlockType {
    MINI_WORKBENCH,
    PC,
    MINI_VENDING_MACHINE,
    DISPLAY_CASE,
    AUCTION_HOUSE
}
