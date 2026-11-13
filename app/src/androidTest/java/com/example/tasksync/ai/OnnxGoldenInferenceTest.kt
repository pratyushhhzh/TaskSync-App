package com.example.tasksync.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnnxGoldenInferenceTest {
    @Test fun suppliedGoldenCasesRunThroughLocalEncoderDecoder() = runBlocking {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val cases = JSONArray(testContext.assets.open("test_cases.json").bufferedReader().use { it.readText() })
        val model = TaskSyncModel.get(appContext)
        val failures = mutableListOf<String>()
        for (index in 0 until cases.length()) {
            val test = cases.getJSONObject(index)
            val outputLimit = test.getJSONArray("expected_ids").length()
            val result = model.generate(test.getString("input_text"), outputLimit)
            val expected = test.getString("expected_text")
            val actual = result.getOrNull()
            val passed = actual == expected
            Log.i("TaskSyncOnnxTest", "${if (passed) "PASS" else "FAIL"} case ${index + 1}: expected=<$expected> actual=<${actual ?: result.exceptionOrNull()?.message}>")
            if (!passed) failures += "case ${index + 1}: expected=<$expected>, actual=<${actual ?: result.exceptionOrNull()?.message}>"
        }
        assertTrue("${12 - failures.size}/12 inference cases passed. ${failures.joinToString("; ")}", failures.isEmpty())
    }
}
