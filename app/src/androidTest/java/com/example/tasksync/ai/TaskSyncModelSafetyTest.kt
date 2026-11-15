package com.example.tasksync.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskSyncModelSafetyTest {
    @Test
    fun inputBeyondTokenizerLimitFailsInsteadOfUsingTruncatedText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = TaskSyncModel.get(context).generate("task ".repeat(400))

        assertTrue("Oversized transcript unexpectedly succeeded: ${result.getOrNull()}", result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty(),
            result.exceptionOrNull()?.message.orEmpty().contains("input limit")
        )
    }

    @Test
    fun outputReachingTokenCapWithoutEosFails() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = TaskSyncModel.get(context).generate(
            input = "Create a task to call the dentist",
            maxOutputTokens = 1
        )

        assertFalse("A one-token response should not contain a completed action.", result.isSuccess)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty(),
            result.exceptionOrNull()?.message.orEmpty().contains("token limit")
        )
    }
}
