package com.nuvio.tv.core.iptv.match


import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Acceptance rules distilled from the live-panel campaign — each case maps to a real incident. */
class VerifyDecisionTest {

    private fun decide(
        signalTmdb: Int? = null,
        signalYear: Int? = null,
        targetTmdb: Int = 155,
        targetYear: Int? = 2008,
        nameYear: Int? = null,
        exactTier: Boolean = true,
        via: String = "primary",
    ) = XtreamTmdbResolver.verifyDecision(VerifySignal(signalTmdb, signalYear), targetTmdb, targetYear, nameYear, exactTier, via)

    @Test
    fun panelTmdbIdDecidesOutright() {
        assertTrue(decide(signalTmdb = 155))
        // id mismatch rejects even when everything else lines up (provider mis-tags exist:
        // "Blind und Haesslich" carried Damage's id)
        assertFalse(decide(signalTmdb = 999, signalYear = 2008, nameYear = 2008))
    }

    @Test
    fun exactTierAllowsOffByOneYear() {
        assertTrue(decide(signalYear = 2008))
        assertTrue(decide(signalYear = 2009)) // panels get years wrong by one all the time
        assertFalse(decide(signalYear = 2011))
    }

    @Test
    fun inexactTiersDemandExactYear() {
        // trunc/skeleton/nodigit matches are guesses — a year off by one is not confirmation
        assertFalse(decide(signalYear = 2009, exactTier = false))
        assertTrue(decide(signalYear = 2008, exactTier = false))
    }

    @Test
    fun nameYearIsTheFallbackSignal() {
        assertTrue(decide(nameYear = 2008))
        // "Wanted (2008)" Jolie vs "Wanted (2009)" Salman Khan: same name, ±1 apart,
        // different films — inexact evidence must NOT bridge them
        assertFalse(decide(nameYear = 2009, exactTier = false))
    }

    @Test
    fun noSignalAcceptsOnlyExactPrimaryOrOriginal() {
        assertTrue(decide(via = "primary"))
        assertTrue(decide(via = "original"))
        // O11CE's alt title "11" once matched an unrelated show with nothing to refute it
        assertFalse(decide(via = "alt"))
        assertFalse(decide(via = "primary+trunc", exactTier = false))
    }

    @Test
    fun infoYearWinsOverNameYear() {
        // panel metadata said 2017 for a "Criminal Minds" entry targeted at the 2005 show
        assertFalse(decide(signalYear = 2017, nameYear = 2005))
    }
}
