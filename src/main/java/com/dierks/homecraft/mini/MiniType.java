package com.dierks.homecraft.mini;

/**
 * The <em>form</em> a Mini takes. HEAD is a textured player head (fully supported).
 * ARMOR_STAND is reserved for posed decorative stands — minted/tracked the same
 * way; spawning the posed entity on placement lands in a later Minis sub-phase.
 */
public enum MiniType {
    HEAD,
    ARMOR_STAND
}
