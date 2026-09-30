package com.dierks.homecraft.gui.games;

/**
 * Marks a screen as part of the Games (spec R2.5, R3.4). A player in a world session may open
 * only these: every other HomeCraft screen could hand them items the session would then have to
 * account for, so the kit guard refuses any menu that isn't a {@code GameScreen}. Every games
 * screen gets it by extending {@link GameMenu}.
 */
public interface GameScreen {
}
