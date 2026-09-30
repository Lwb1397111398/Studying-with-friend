package com.studyfriend.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.studyfriend.app.data.db.MIGRATIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M1 迁移验证：1→2 跑 MIGRATION_1_2 并对照 2.json 校验全部表结构 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        com.studyfriend.app.data.db.StudyDatabase::class.java,
    )

    @Test
    fun migrate1To2_allTablesUsable() {
        val dbName = "migration-test.db"
        // v1 库只有 settings；写入一条证明 v1 可用
        helper.createDatabase(dbName, 1).use { v1 ->
            v1.execSQL("INSERT INTO settings (`key`, `value`) VALUES ('k', 'v')")
        }
        // 跑迁移 + schema 校验（失败即抛）
        helper.runMigrationsAndValidate(dbName, 2, true, *MIGRATIONS).use { v2 ->
            v2.execSQL(
                "INSERT INTO books (title, author, sourceType, filePath, status, totalChapters, " +
                    "overviewJson, createdAt, updatedAt) " +
                    "VALUES ('民法典', '佚名', 'TXT', '', 'IMPORTED', 0, NULL, 1, 1)",
            )
            v2.execSQL(
                "INSERT INTO chapters (bookId, idx, title, readState, gist, keyTermsJson) " +
                    "VALUES (1, 0, '第一章', 'NOT_READ', NULL, NULL)",
            )
            v2.execSQL(
                "INSERT INTO review_items (bookId, chapterId, title, intervalIdx, dueAt, done) " +
                    "VALUES (1, 1, '第一章', 0, 100, 0)",
            )
            v2.query("SELECT title FROM books").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("民法典", cur.getString(0))
            }
            // 新表写读回验（review_items 为双 FK 表，连带验证 FK 目标行存在）
            v2.query("SELECT title FROM chapters WHERE bookId = 1").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("第一章", cur.getString(0))
            }
            v2.query("SELECT COUNT(*) FROM review_items").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals(1, cur.getInt(0))
            }
            // v1 的 settings 数据仍在
            v2.query("SELECT `value` FROM settings WHERE `key` = 'k'").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("v", cur.getString(0))
            }
        }
    }
}
