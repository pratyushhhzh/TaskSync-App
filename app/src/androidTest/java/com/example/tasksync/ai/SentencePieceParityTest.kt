package com.example.tasksync.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SentencePieceParityTest {
    @Test fun suppliedTwelveGoldenInputsMatchExactly() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val model = File(appContext.cacheDir, "tasksync-spiece-test.model")
        appContext.assets.open("model/spiece.model").use { input -> model.outputStream().use(input::copyTo) }
        val cases = JSONArray(testContext.assets.open("test_cases.json").bufferedReader().use { it.readText() })
        val failures = mutableListOf<String>()
        TaskSyncTokenizer.fromFile(model).use { tokenizer ->
            for (index in 0 until cases.length()) {
                val test = cases.getJSONObject(index)
                val expected = test.getJSONArray("input_ids").let { array -> (0 until array.length()).map { array.getInt(it) } }
                val actual = tokenizer.encode(test.getString("input_text"))
                val firstDiff = (0 until maxOf(expected.size, actual.size)).firstOrNull { expected.getOrNull(it) != actual.getOrNull(it) }
                val passed = firstDiff == null
                Log.i("TaskSyncTokenizerTest", "${if (passed) "PASS" else "FAIL"} case ${index + 1}${firstDiff?.let { " at token $it expected=${expected.getOrNull(it)} actual=${actual.getOrNull(it)} expectedAll=$expected actualAll=$actual" }.orEmpty()}")
                if (!passed) failures += "case ${index + 1}, first differing position $firstDiff, expected=$expected actual=$actual"
            }
        }
        assertTrue("${12 - failures.size}/12 passed. ${failures.joinToString("; ")}", failures.isEmpty())
        assertEquals(12, cases.length())
    }

    @Test fun requiredBoundaryAndLifecycleCases() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(appContext.cacheDir, "tasksync-spiece-test.model")
        appContext.assets.open("model/spiece.model").use { input -> model.outputStream().use(input::copyTo) }
        repeat(3) {
            TaskSyncTokenizer.fromFile(model).use { tokenizer ->
                assertEquals(listOf(TaskSyncTokenizer.EOS_ID), tokenizer.encode(""))
                val longInput = tokenizer.encode("task ".repeat(400))
                assertEquals(TaskSyncTokenizer.MAX_INPUT_TOKENS, longInput.size)
                assertEquals(TaskSyncTokenizer.EOS_ID, longInput.last())
                val ids = tokenizer.encode("Hello world")
                assertEquals("Hello world", tokenizer.decode(listOf(0) + ids + listOf(0, 1)))
            }
        }
    }
}
