package com.studyfriend.app

import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.db.SettingEntity
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M0 冒烟：Room 能建库、Setting 能写读（覆盖建库主路径） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmokeTest {

    @Test
    fun roomBuildsAndRoundTrips() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = StudyDatabase.build(context)
        try {
            db.settingDao().upsert(SettingEntity("hello", "世界"))
            assertEquals("世界", db.settingDao().get("hello")?.value)
            db.settingDao().upsert(SettingEntity("hello", "覆盖"))
            assertEquals("覆盖", db.settingDao().get("hello")?.value)
        } finally {
            db.close()
        }
    }
}
