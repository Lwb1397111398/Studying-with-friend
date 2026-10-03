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

    @Test
    fun migrate2To3_visionQueueTableUsable() {
        val dbName = "migration-test-23.db"
        helper.createDatabase(dbName, 2).use { v2 ->
            v2.execSQL(
                "INSERT INTO books (title, author, sourceType, filePath, status, totalChapters, " +
                    "overviewJson, createdAt, updatedAt) " +
                    "VALUES ('民法总则', '', 'PDF', 'uri://x', 'READY', 1, NULL, 1, 1)",
            )
        }
        helper.runMigrationsAndValidate(dbName, 3, true, *MIGRATIONS).use { v3 ->
            // 新表写读回验（vision_queue：单 FK + (bookId,pageNo) 唯一索引）
            v3.execSQL(
                "INSERT INTO vision_queue (bookId, uri, pageNo, originChars, status, attempts, updatedAt) " +
                    "VALUES (1, 'uri://x', 148, 812, 'PENDING', 0, 1)",
            )
            v3.query("SELECT status, originChars FROM vision_queue WHERE bookId = 1 AND pageNo = 148").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("PENDING", cur.getString(0))
                assertEquals(812, cur.getInt(1))
            }
        }
    }

    @Test
    fun migrate3To4_p3b2ColumnsUsable() {
        val dbName = "migration-test-34.db"
        helper.createDatabase(dbName, 3).use { v3 ->
            v3.execSQL(
                "INSERT INTO books (title, author, sourceType, filePath, status, totalChapters, " +
                    "overviewJson, createdAt, updatedAt) " +
                    "VALUES ('民法总则', '', 'PDF', 'uri://x', 'READY', 1, NULL, 1, 1)",
            )
            v3.execSQL(
                "INSERT INTO chapters (bookId, idx, title, readState, gist, keyTermsJson) " +
                    "VALUES (1, 0, '第一章', 'NOT_READ', NULL, NULL)",
            )
            v3.execSQL("INSERT INTO paragraphs (chapterId, idx, text, role, aiAction) VALUES (1, 0, '段落', 'BODY', 'NONE')")
        }
        // 跑 1→4 全链迁移 + schema 校验（4 列加列与 Entity 对齐，失败即抛）
        helper.runMigrationsAndValidate(dbName, 4, true, *MIGRATIONS).use { v4 ->
            // 旧行新列取默认值：pageNo/parentOrder=NULL，level=1（NOT NULL DEFAULT，未校准书同构），calibrated=0
            v4.query("SELECT pageNo FROM paragraphs").use { cur ->
                assertTrue(cur.moveToFirst())
                assertTrue("旧段落 pageNo 应为 NULL", cur.isNull(0))
            }
            v4.query("SELECT level, parentOrder, calibrated FROM chapters").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("旧章 level 默认 1", 1, cur.getInt(0))
                assertTrue(cur.isNull(1))
                assertEquals("旧章 calibrated 默认 0", 0, cur.getInt(2))
            }
            // 新列可写读回
            v4.execSQL("UPDATE paragraphs SET pageNo = 42")
            v4.execSQL("UPDATE chapters SET level = 1, parentOrder = 0, calibrated = 1")
            v4.query("SELECT pageNo FROM paragraphs").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals(42, cur.getInt(0))
            }
            v4.query("SELECT level, parentOrder, calibrated FROM chapters").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals(1, cur.getInt(0))
                assertEquals(0, cur.getInt(1))
                assertEquals(1, cur.getInt(2))
            }
        }
    }
}
