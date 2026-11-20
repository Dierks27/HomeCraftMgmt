package com.dierks.homecraft.gui.games.chance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 0.46.0: a game of chance's own words are written once, for tokens; on a money screen in the Game Parlor they are
 * passed through {@link MoneyPlay#say}. Every amount gets its dollar sign, a colour code's digit is never taken for
 * part of an amount, and "1 in 1,829 spins" is left alone.
 */
class MoneyPlayTest {

    private static String say(String line) {
        return MoneyPlay.say(true, line);
    }

    @Test
    void aTokensLineIsUntouchedWhenPlayingForTokens() {
        assertEquals("&a17 tokens", MoneyPlay.say(false, "&a17 tokens"));
    }

    @Test
    void amountsOfTokensBecomeDollars() {
        assertEquals("&a$17", say("&a17 tokens"));
        assertEquals("&6+$40", say("&6+40 tokens"));
        assertEquals("&aSpin &7- &6$35 in", say("&aSpin &7- &6" + 35 + " tokens in"));
        assertEquals("&e$5 a spin", say("&e5 tokens a spin"));
        assertEquals("&7At $1 a spin.", say("&7At 1 token a spin."));
        assertEquals("&eGives back about 89 of every $100", say("&eGives back about 89 of every 100 tokens"));
    }

    @Test
    void aColourCodesDigitIsNeverPartOfTheAmount() {
        assertEquals("&6$17", say("&617 tokens"), "gold 17, not 617");
        assertEquals("&6$250", say("&6250 tokens"));
        assertEquals("&7$5 in → &6$18 &7back", say("&75 tokens in → &618 &7back"));
    }

    @Test
    void whatComesBackGetsItsSign() {
        assertEquals("&fYour $10 back", say("&fYour 10 back"));
        assertEquals("&7Your $10 back.", say("&7Your 10 back."));
        assertEquals("&7$3 back.", say("&73 back."));
        assertEquals("&7$5 in → your $5 back", say("&75 tokens in → your 5 back"));
        assertEquals("&7Win: &f$5 in → $9 back", say("&7Win: &f5 in → 9 back"));
        assertEquals("&7You put in $20.", say("&7You put in 20."));
    }

    @Test
    void oddsAreNotAmounts() {
        assertEquals("&7Comes up about 1 in 1,829 spins.", say("&7Comes up about 1 in 1,829 spins."));
        assertEquals("&fThree diamonds &6×40 &7· 1 in 1,829", say("&fThree diamonds &6×40 &7· 1 in 1,829"));
        assertEquals("&f3 of 24 spaces give this", say("&f3 of 24 spaces give this"));
    }

    @Test
    void theWordItselfBecomesMoney() {
        assertEquals("&7The money you put in came back.", say("&7The tokens you put in came back."));
        assertEquals("&7Money put into the Parlor's games today.", say("&7Tokens put into games of chance today."));
    }

    /** Deep Mine's rows and Mob Slots' free spins are written for tokens too, and read right for dollars. */
    @Test
    void theLastTwoSlotMachinesLinesReadInDollars() {
        assertEquals("&7Top row: Three coal → &6$15", say("&7Top row: Three coal → &615"));
        assertEquals("&8Top row: nothing", say("&8Top row: nothing"));
        assertEquals("&a3 rows paid! &6+$28", say("&a3 rows paid! &6+28 tokens"), "3 rows is not an amount");
        assertEquals("&7$5 on a row → &6$50 &7back", say("&75 tokens on a row → &650 &7back"));
        assertEquals("&7$5 on a row → that row's $5 back", say("&75 tokens on a row → that row's 5 back"));
        assertEquals("&7Comes up on a row about 1 in 149 spins.", say("&7Comes up on a row about 1 in 149 spins."));
        assertEquals("&7At least one row pays about 92 spins in 100.",
                say("&7At least one row pays about 92 spins in 100."));
        assertEquals("&eFree spin 2 of 5 &7· $44 back so far", say("&eFree spin 2 of 5 &7· 44 tokens back so far"));
        assertEquals("&aThree creepers! &e5 free spins!", say("&aThree creepers! &e5 free spins!"));
        assertEquals("&aThree creepers and 5 free spins! &6+$166",
                say("&aThree creepers and 5 free spins! &6+166 tokens"));
        assertEquals("&7Three creepers and 2 free spins. Your $2 back.",
                say("&7Three creepers and 2 free spins. Your 2 back."));
        assertEquals("&7Three creepers and 1 free spin. $1 back.", say("&7Three creepers and 1 free spin. 1 back."));
        assertEquals("&fThree creepers &6$83 &7+ &a5 free spins &7· 1 in 176",
                say("&fThree creepers &683 tokens &7+ &a5 free spins &7· 1 in 176"));
        assertEquals("&7And 5 free spins at the same stake, played at once.",
                say("&7And 5 free spins at the same stake, played at once."));
        assertEquals("&7Three Creepers give 5 free spins about 1 spin in 182.",
                say("&7Three Creepers give 5 free spins about 1 spin in 182."));
    }

    @Test
    void amountsReadTheSameEverywhere() {
        assertEquals("$5", MoneyPlay.amount(true, 5));
        assertEquals("5 tokens", MoneyPlay.amount(false, 5));
        assertEquals("1 token", MoneyPlay.amount(false, 1));
    }
}
