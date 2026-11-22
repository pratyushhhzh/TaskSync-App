package com.example.tasksync.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tasksync.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceWidgetLaunchIntentTest {
    @Test
    fun widgetTapExplicitlyRoutesToExistingForegroundCaptureFlow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = voiceCaptureWidgetIntent(context)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(TaskSyncWidgetProvider.EXTRA_OPEN_VOICE, false))
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }
}
