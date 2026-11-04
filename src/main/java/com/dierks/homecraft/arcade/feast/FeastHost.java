package com.dierks.homecraft.arcade.feast;

import com.dierks.homecraft.config.FeastConfig;

import java.util.Collection;
import java.util.UUID;

/**
 * What {@link FeastCore} needs from the server (0.38.6): the clock, the settings, who is online and home, names, and
 * the things a player sees and hears. {@link FeastService} is the live one; {@code FeastCoreTest} has a fake, so the
 * Feast's bookkeeping (the points, the fill, the reward, the moments and the Turkey Mini) is tested against a real
 * database with no server.
 */
public interface FeastHost {

    /** The clock (ms). */
    long now();

    /** Today's local day key ({@code clock.dayKey()}): the day boards and the reward's day. */
    long epochDay();

    /** The Feast's settings now. */
    FeastConfig.Feast settings();

    /** Whether the Feast is open now: switched on, inside its window, and the Arcade on. */
    boolean open();

    /** Everyone online now. */
    Collection<UUID> online();

    /** Whether {@code player} is online and home: back from every game, and not in a Games world. */
    boolean home(UUID player);

    /** {@code player}'s name, online or not, or {@code null} when the server never knew it. */
    String name(UUID player);

    /**
     * Whether {@code player} is online with an empty slot. A Turkey Mini goes straight into the bag or waits: on the
     * ground nothing protects it, and a child with a full bag can't pick it up.
     */
    boolean hasRoom(UUID player);

    /**
     * Mint one {@code miniId} for {@code player} (online and home) through the Minis' event mint, which keeps the
     * Mini's own earn window and cap. @return whether one was minted
     */
    boolean mintMini(UUID player, String miniId);

    /**
     * {@code player}'s own moment: the title, a firework in view and a chime; with {@code withLine}, the Thankful line
     * too (left out at the fill, where everyone is sent it).
     */
    void moment(UUID player, boolean withLine, String thankfulLine);

    /** {@code player} (when online) was just paid {@code tokens}: the "+N tokens" line and its sound. */
    void paid(UUID player, int tokens);

    /** A line for everyone online. */
    void broadcast(String line);

    /** The fill's show over the pot (when its chunk is loaded). */
    void homeShow();

    /** An INFO line for the owner's log. */
    void info(String line);

    /** A WARN line for the owner's log. */
    void warn(String line);
}
