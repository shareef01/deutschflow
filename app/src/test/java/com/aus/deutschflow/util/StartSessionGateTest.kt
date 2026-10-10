package com.aus.deutschflow.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartSessionGateTest {

    @Test
    fun firstStartIsConsumedAndSecondIsRejected() {
        val gate = StartSessionGate()
        assertTrue(gate.tryStart())     // first start consumed
        assertFalse(gate.tryStart())    // recogniser has not committed yet -> rejected
    }

    @Test
    fun terminalPathRearmsGate() {
        val gate = StartSessionGate()
        assertTrue(gate.tryStart())
        gate.release()                 // recogniser committed / errored / abandoned
        assertTrue(gate.tryStart())    // next start allowed
    }

    @Test
    fun doubleReleaseIsIdempotent() {
        val gate = StartSessionGate()
        assertTrue(gate.tryStart())
        gate.release()
        gate.release()                 // no throw, gate stays re-armed
        assertTrue(gate.tryStart())
    }
}
