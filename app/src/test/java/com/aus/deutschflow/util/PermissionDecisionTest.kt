package com.aus.deutschflow.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionDecisionTest {

    // The grant decision is separated from Context/Activity introspection into the pure
    // computePermissionState() so the four branches are deterministic and do not need a
    // real permission dialog. (The Activity/ContextWrapper unwrap lives in
    // recordAudioPermissionState(); the non-Activity short-circuit is covered there.)

    @Test
    fun grantedIsGrantedRegardlessOfOtherSignals() {
        assertEquals(PermissionState.GRANTED, computePermissionState(true, canShowRationale = true, askedBefore = true))
        assertEquals(PermissionState.GRANTED, computePermissionState(true, canShowRationale = false, askedBefore = false))
    }

    @Test
    fun firstRequestIsRequestable() {
        // Not granted, no rationale (first time), not asked before -> safe to ask.
        assertEquals(PermissionState.REQUESTABLE, computePermissionState(false, canShowRationale = false, askedBefore = false))
    }

    @Test
    fun rationaleIsAlwaysRequestable() {
        // Denied once but not "Don't ask again" -> must explain, then re-ask.
        assertEquals(PermissionState.REQUESTABLE, computePermissionState(false, canShowRationale = true, askedBefore = false))
        assertEquals(PermissionState.REQUESTABLE, computePermissionState(false, canShowRationale = true, askedBefore = true))
    }

    @Test
    fun permanentDenialRequiresNoRationaleAndAskedBefore() {
        // No rationale AND we already asked -> "Don't ask again"; Settings is the only way.
        assertEquals(PermissionState.DENIED_PERMANENTLY, computePermissionState(false, canShowRationale = false, askedBefore = true))
    }
}
