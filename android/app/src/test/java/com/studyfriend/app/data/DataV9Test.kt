package com.studyfriend.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.db.computeStreak
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** 体验增量轮：阅读统计 upsert 递增 / 按天合计 / 连续天数纯函数（内存库） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataV9Test {

    private fun db(): StudyDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StudyDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun book(title: String) = BookEntity(
        title = title, author = "", sourceType = "TXT", filePath = "", status = "READY",
        totalChapters = 0, overviewJson = null, createdAt = 1_000L, updatedAt = 1_000L,
    )

    @Test
    fun addMinutesUpsertsAndAggregates() = runBlocking {
        val db = db()
        try {
            val b1 = db.bookDao().insert(book("书一"))
            val b2 = db.bookDao().insert(book("书二"))
            val dao = db.readingStatDao()
            dao.addMinutes(b1, "2026-10-24", 12)
            dao.addMinutes(b1, "2026-10-24", 8) // 同书同天递增
            dao.addMinutes(b1, "2026-10-25", 5)
            dao.addMinutes(b2, "2026-10-24", 30) // 另一本书同天

            assertEquals(50, dao.minutesOnDay("2026-10-24")) // 跨书合计 12+8+30
            assertEquals(5, dao.minutesOnDay("2026-10-25"))
            assertEquals(0, dao.minutesOnDay("2026-10-01")) // 无行=0

            val byDay = dao.minutesByDay()
            assertEquals(2, byDay.size)
            assertEquals("2026-10-25", byDay[0].day) // day 降序
            assertEquals(5, byDay[0].minutes)
            assertEquals("2026-10-24", byDay[1].day)
            assertEquals(50, byDay[1].minutes)

            // 删书 CASCADE 清该书统计
            db.bookDao().delete(db.bookDao().get(b1)!!)
            assertEquals(30, dao.minutesOnDay("2026-10-24"))
        } finally {
            db.close()
        }
    }

    @Test
    fun streakCountsBackFromTodayOrYesterday() {
        val d = LocalDate.of(2026, 10, 25)
        // 今天有 → 从今天起算
        assertEquals(1, computeStreak(setOf("2026-10-25"), d))
        // 今天没有、昨天有 → 不打断，从昨天起算
        assertEquals(1, computeStreak(setOf("2026-10-24"), d))
        // 连续 4 天（今天到 10-22）
        assertEquals(4, computeStreak(setOf("2026-10-25", "2026-10-24", "2026-10-23", "2026-10-22"), d))
        // 中间断档即停（10-23 缺）
        assertEquals(2, computeStreak(setOf("2026-10-25", "2026-10-24", "2026-10-22"), d))
        // 今天昨天都没有 → 0（再早也不算连续）
        assertEquals(0, computeStreak(setOf("2026-10-20"), d))
        // 空集 → 0
        assertEquals(0, computeStreak(emptySet(), d))
    }
}
