package com.example.tasksync.ui.interpretation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationRequestGuardTest {
    @Test fun concurrentSubmitOrRetryCannotStartDuplicateRequest() {
        val guard = InterpretationRequestGuard()
        assertTrue(guard.begin())
        assertFalse(guard.begin())
        guard.finish()
        assertTrue(guard.begin())
    }
}
