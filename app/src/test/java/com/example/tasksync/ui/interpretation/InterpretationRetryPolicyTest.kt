package com.example.tasksync.ui.interpretation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationRetryPolicyTest {
    @Test fun retryableFailureOffersManualRetry() {
        assertTrue(InterpretationUiState.Failure("temporary", retryable = true, attempt = 1).canRetryInterpretation())
    }

    @Test fun permanentFailureDoesNotOfferRetry() {
        assertFalse(InterpretationUiState.Failure("permanent", retryable = false, attempt = 1).canRetryInterpretation())
    }

    @Test fun nonFailureStatesDoNotOfferRetry() {
        assertFalse(InterpretationUiState.Idle.canRetryInterpretation())
        assertFalse(InterpretationUiState.Loading(attempt = 2).canRetryInterpretation())
    }
}
