package com.studyfriend.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.studyfriend.app.data.db.MIGRATION_10_11
import com.studyfriend.app.data.db.MIGRATION_11_12
import com.studyfriend.app.data.db.MIGRATION_8_9
import com.studyfriend.app.data.db.MIGRATION_9_10
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

    @Test
    fun migrate4To5_figuresTableUsable() {
        val dbName = "migration-test-45.db"
        helper.createDatabase(dbName, 4).use { v4 ->
            v4.execSQL(
                "INSERT INTO books (title, author, sourceType, filePath, status, totalChapters, " +
                    "overviewJson, createdAt, updatedAt) " +
                    "VALUES ('民法物权', '王泽鉴', 'PDF', 'uri://x', 'READY', 1, NULL, 1, 1)",
            )
            v4.execSQL(
                "INSERT INTO chapters (bookId, idx, title, readState, gist, keyTermsJson, " +
                    "level, parentOrder, calibrated) VALUES (1, 0, '第一章', 'NOT_READ', NULL, NULL, 1, NULL, 0)",
            )
        }
        // 跑 1→5 全链迁移 + schema 校验（figures 建表与 Entity 对齐，失败即抛）
        helper.runMigrationsAndValidate(dbName, 5, true, *MIGRATIONS).use { v5 ->
            // 双 FK 表写读回验（book/chapter 目标行存在）
            v5.execSQL(
                "INSERT INTO figures (bookId, chapterId, pageNo, ordAfterPara, bboxY0, seqNo, " +
                    "file, width, height, format, md5, createdAt) " +
                    "VALUES (1, 1, 86, 12, 210.5, 1, 'figures/1/p86_f1.png', 1180, 447, 'png', " +
                    "'d41d8cd98f00b204e9800998ecf8427e', 1)",
            )
            v5.query("SELECT pageNo, seqNo, format FROM figures WHERE bookId = 1").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals(86, cur.getInt(0))
                assertEquals(1, cur.getInt(1))
                assertEquals("png", cur.getString(2))
            }
            // 索引存在（index_figures_bookId / index_figures_chapterId）
            v5.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name LIKE 'index_figures_%'",
            ).use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals(2, cur.getInt(0))
            }
        }
    }

@Test
fun migrate5To6_visionQueueLowQualityColumn() {
    val dbName = "migration-test-56.db"
    helper.createDatabase(dbName, 5).use { v5 ->
        v5.execSQL(
            "INSERT INTO vision_queue (bookId, uri, pageNo, originChars, status, attempts, updatedAt) " +
                "VALUES (1, 'uri://x', 12, 300, 'PENDING', 0, 1)",
        )
    }
    helper.runMigrationsAndValidate(dbName, 6, true, *MIGRATIONS).use { v6 ->
        v6.execSQL(
            "INSERT INTO vision_queue (bookId, uri, pageNo, originChars, status, attempts, lowQuality, updatedAt) " +
                "VALUES (1, 'uri://x', 13, 80, 'PENDING', 0, 1, 2)",
        )
        v6.query("SELECT pageNo, lowQuality FROM vision_queue ORDER BY pageNo").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals(12, cur.getInt(0))
            assertEquals("旧行默认 0", 0, cur.getInt(1))
            assertTrue(cur.moveToNext())
            assertEquals(13, cur.getInt(0))
            assertEquals("新行写入 1", 1, cur.getInt(1))
        }
    }
}

@Test
fun migrate6To7_positionColumnsAndNewTables() {
    val dbName = "migration-test-67.db"
    helper.createDatabase(dbName, 6).use { v6 ->
        v6.execSQL(
            "INSERT INTO books (title, author, sourceType, filePath, status, totalChapters, " +
                "overviewJson, createdAt, updatedAt) VALUES ('旧书', '', 'TXT', '', 'READY', 1, NULL, 1, 1)",
        )
        v6.execSQL(
            "INSERT INTO chapters (bookId, idx, title, readState, gist, keyTermsJson, level, parentOrder, calibrated) " +
                "VALUES (1, 0, '第一章', 'NOT_READ', NULL, NULL, 1, NULL, 0)",
        )
    }
    // 跑 1→7 全链迁移 + schema 校验（加列与两新表和 Entity 对齐，失败即抛）
    helper.runMigrationsAndValidate(dbName, 7, true, *MIGRATIONS).use { v7 ->
        // 旧行新列取 NULL=未记录
        v7.query("SELECT lastReadChapterId, lastReadAt FROM books").use { cur ->
            assertTrue(cur.moveToFirst())
            assertTrue("旧书 lastReadChapterId 应为 NULL", cur.isNull(0))
            assertTrue(cur.isNull(1))
        }
        v7.query("SELECT lastReadParaIdx FROM chapters").use { cur ->
            assertTrue(cur.moveToFirst())
            assertTrue("旧章 lastReadParaIdx 应为 NULL", cur.isNull(0))
        }
        // 新列可写读回（位置记忆 + 最近在读）
        v7.execSQL("UPDATE chapters SET lastReadParaIdx = 7 WHERE id = 1")
        v7.execSQL("UPDATE books SET lastReadChapterId = 1, lastReadAt = 123 WHERE id = 1")
        v7.query("SELECT lastReadParaIdx FROM chapters").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals(7, cur.getInt(0))
        }
        v7.query("SELECT lastReadChapterId, lastReadAt FROM books").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals(1, cur.getInt(0))
            assertEquals(123, cur.getLong(1))
        }
        // bookmarks 双 FK 表写读回验
        v7.execSQL(
            "INSERT INTO bookmarks (bookId, chapterId, paraIdx, preview, createdAt) VALUES (1, 1, 7, '读到代理权', 99)",
        )
        v7.query("SELECT preview FROM bookmarks").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("读到代理权", cur.getString(0))
        }
        // qa_history 单 FK 表写读回验
        v7.execSQL(
            "INSERT INTO qa_history (bookId, chapterId, chapterTitle, question, answer, createdAt) " +
                "VALUES (1, 1, '第一章', '什么是代理？', '以他人名义……', 100)",
        )
        v7.query("SELECT question FROM qa_history").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("什么是代理？", cur.getString(0))
        }
    }
}

@Test
fun migrate7To8_paraMemosTableUsable() {
    val dbName = "migration-test-78.db"
    helper.createDatabase(dbName, 7).use { v7 ->
        v7.execSQL(
            "INSERT INTO books (title, author, sourceType, filePath, status, totalChapters, " +
                "overviewJson, createdAt, updatedAt) VALUES ('旧书', '', 'TXT', '', 'READY', 1, NULL, 1, 1)",
        )
        v7.execSQL(
            "INSERT INTO chapters (bookId, idx, title, readState, gist, keyTermsJson, level, parentOrder, calibrated) " +
                "VALUES (1, 0, '第一章', 'NOT_READ', NULL, NULL, 1, NULL, 0)",
        )
        v7.execSQL(
            "INSERT INTO paragraphs (chapterId, idx, text, role, aiAction) VALUES (1, 0, '代理权授与他人', 'BODY', 'NONE')",
        )
    }
    // 跑 1→8 全链迁移 + schema 校验（para_memos 建表与 Entity 对齐，失败即抛）
    helper.runMigrationsAndValidate(dbName, 8, true, *MIGRATIONS).use { v8 ->
        // 双 FK 表写读回验（book/chapter 目标行存在；paraId 无 FK 不校验）
        v8.execSQL(
            "INSERT INTO para_memos (bookId, chapterId, paraId, paraIdx, quote, text, createdAt) " +
                "VALUES (1, 1, 1, 0, '代理权授与他人', '这里和委托合同的区别要记牢', 99)",
        )
        v8.query("SELECT quote, text FROM para_memos").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("代理权授与他人", cur.getString(0))
            assertEquals("这里和委托合同的区别要记牢", cur.getString(1))
        }
        // 索引存在（index_para_memos_bookId / index_para_memos_chapterId）
        v8.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name LIKE 'index_para_memos_%'",
        ).use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals(2, cur.getInt(0))
        }
    }
}

@Test
fun migrate8To9_readingStatsUsable() {
    val dbName = "migration-test-89.db"
    helper.createDatabase(dbName, 8).use { v8 ->
        v8.execSQL(
            "INSERT INTO books (title, author, sourceType, filePath, status, totalChapters, " +
                "overviewJson, createdAt, updatedAt) VALUES ('旧书', '', 'TXT', '', 'READY', 1, NULL, 1, 1)",
        )
    }
    // 跑 8→9 迁移 + schema 校验（reading_stats 建表与 Entity 对齐，失败即抛）
    helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9).use { v9 ->
        // 写读回验：同书同天递增（DAO 层的 addMinutes 走 INSERT OR IGNORE + UPDATE，
        // 这里手工 SQL 等价复刻——Robolectric legacy SQLite 不认 UPSERT 语法）
        v9.execSQL("INSERT INTO reading_stats (bookId, day, minutes) VALUES (1, '2026-10-25', 12)")
        v9.execSQL(
            "INSERT OR IGNORE INTO reading_stats (bookId, day, minutes) VALUES (1, '2026-10-25', 3)",
        )
        v9.execSQL(
            "UPDATE reading_stats SET minutes = minutes + 3 WHERE bookId = 1 AND day = '2026-10-25'",
        )
        v9.query("SELECT day, minutes FROM reading_stats").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("2026-10-25", cur.getString(0))
            assertEquals(15, cur.getInt(1))
        }
        // 删书 CASCADE 清统计的语义在 DataV9Test 内存库验证——helper 库不开 FK
        // enforcement，级联删除不会触发（Room 打开真实库时才启用 FK）
    }
}

    /** 批D：9→10 加列迁移——quiz_attempts.source / review_items.itemType 存量行落默认值 */
    @Test
    fun migrate9To10_newColumnsUsable() {
        val dbName = "migration-test-910.db"
        helper.createDatabase(dbName, 9).use { v9 ->
            v9.execSQL(
                "INSERT INTO books (title, author, sourceType, filePath, status, totalChapters, " +
                    "overviewJson, createdAt, updatedAt) VALUES ('书', '', 'TXT', '', 'READY', 1, NULL, 1, 1)",
            )
            v9.execSQL(
                "INSERT INTO chapters (bookId, idx, title, readState, gist, keyTermsJson, level, calibrated) " +
                    "VALUES (1, 0, '第一章', 'NOT_READ', NULL, NULL, 1, 0)",
            )
            v9.execSQL(
                "INSERT INTO quiz_attempts (chapterId, qIndex, answer, verdict, feedback, createdAt) " +
                    "VALUES (1, 0, '我的作答', 'WRONG', NULL, 1)",
            )
            v9.execSQL(
                "INSERT INTO review_items (bookId, chapterId, title, intervalIdx, dueAt, done) " +
                    "VALUES (1, 1, '第一章', 0, 1, 0)",
            )
        }
        helper.runMigrationsAndValidate(dbName, 10, true, MIGRATION_9_10).use { v10 ->
            // 存量行 source/itemType 自动落默认值，新行可显式写新值
            v10.query("SELECT source FROM quiz_attempts").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("SUMMARY", cur.getString(0))
            }
            v10.execSQL(
                "INSERT INTO quiz_attempts (chapterId, qIndex, answer, verdict, feedback, createdAt, source) " +
                    "VALUES (1, 10000, '自问', 'WRONG', NULL, 2, 'NOTE')",
            )
            v10.query("SELECT itemType FROM review_items").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("CHAPTER", cur.getString(0))
            }
            v10.execSQL(
                "INSERT INTO review_items (bookId, chapterId, title, intervalIdx, dueAt, done, itemType) " +
                    "VALUES (1, 1, '善意取得', 0, 1, 0, 'TERM')",
            )
        }
    }

    @Test
    fun migrate10To11_fsrsColumnsBackfilledAndLogTableUsable() {
        val dbName = "migration-test-1011.db"
        helper.createDatabase(dbName, 10).use { v10 ->
            v10.execSQL(
                "INSERT INTO books (title, author, sourceType, filePath, status, totalChapters, " +
                    "overviewJson, createdAt, updatedAt) VALUES ('书', '', 'TXT', '', 'READY', 1, NULL, 1, 1)",
            )
            v10.execSQL(
                "INSERT INTO chapters (bookId, idx, title, readState, gist, keyTermsJson, level, calibrated) " +
                    "VALUES (1, 0, '第一章', 'NOT_READ', NULL, NULL, 1, 0)",
            )
            // 未完成行（档 2 = 7 天档）+ 已完成行：前者折算 FSRS 初值，后者不折算
            v10.execSQL(
                "INSERT INTO review_items (bookId, chapterId, title, intervalIdx, dueAt, done, itemType) " +
                    "VALUES (1, 1, '第一章', 2, 1000, 0, 'CHAPTER')",
            )
            v10.execSQL(
                "INSERT INTO review_items (bookId, chapterId, title, intervalIdx, dueAt, done, itemType) " +
                    "VALUES (1, 1, '第一章完', 4, 1000, 1, 'CHAPTER')",
            )
        }
        helper.runMigrationsAndValidate(dbName, 11, true, MIGRATION_10_11).use { v11 ->
            // 未完成行：fsrsState=REVIEW、稳定性=旧档天数、难度中位、上次复习=dueAt 倒推一档
            v11.query("SELECT fsrsState, fsrsStability, fsrsDifficulty, fsrsLastReviewAt FROM review_items WHERE done = 0").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals(2, cur.getInt(0))
                assertEquals(7.0, cur.getDouble(1), 1e-9)
                assertEquals(5.0, cur.getDouble(2), 1e-9)
                assertEquals(1000L - 7L * 86_400_000L, cur.getLong(3))
            }
            // 已完成行：保持 LEARNING 默认态（无读取方，不折算）
            v11.query("SELECT fsrsState, fsrsStability FROM review_items WHERE done = 1").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals(1, cur.getInt(0))
                assertTrue(cur.isNull(1))
            }
            // review_log 新表写读
            v11.execSQL(
                "INSERT INTO review_log (itemId, rating, stateBefore, stabilityBefore, difficultyBefore, " +
                    "dueBefore, scheduledDays, dueAt, reviewedAt) VALUES (1, 3, 2, 7.0, 5.0, 1000, 10.0, 2000, 1500)",
            )
            v11.query("SELECT rating FROM review_log WHERE itemId = 1").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals(3, cur.getInt(0))
            }
        }
    }

    /** 简目假章清理（《民法物权_Kit图包》实录）：纯假章整章剔除、混合章删简目段改名、真章/法条章零误杀 */
    @Test
    fun migrate11To12_phantomTocChaptersCleaned() {
        val dbName = "migration-test-1112.db"
        helper.createDatabase(dbName, 11).use { v11 ->
            v11.execSQL(
                "INSERT INTO books (id, title, author, sourceType, filePath, status, totalChapters, " +
                    "overviewJson, createdAt, updatedAt, lastReadChapterId) VALUES (1, '民法物权', '', 'TXT', '', 'READY', 1, NULL, 1, 1, 10)",
            )
            // 四类章：假章(idx2) / 真文章(idx4) / 混合章(idx31) / 法条章(idx14)
            v11.execSQL(
                "INSERT INTO chapters (id, bookId, idx, title, readState, level, calibrated) " +
                    "VALUES (10, 1, 2, '第六章　担保物权', 'NOT_READ', 1, 0)",
            )
            v11.execSQL(
                "INSERT INTO chapters (id, bookId, idx, title, readState, level, calibrated) " +
                    "VALUES (11, 1, 4, '第一章　总　　论', 'NOT_READ', 1, 0)",
            )
            v11.execSQL(
                "INSERT INTO chapters (id, bookId, idx, title, readState, level, calibrated) " +
                    "VALUES (12, 1, 31, '第七章　占有', 'NOT_READ', 1, 0)",
            )
            v11.execSQL(
                "INSERT INTO chapters (id, bookId, idx, title, readState, level, calibrated) " +
                    "VALUES (13, 1, 14, '第一章　通　　则', 'NOT_READ', 1, 0)",
            )
            // 假章：4 段全「第X节」目录条目
            val secEntries = listOf("第一节　概说", "第二节　抵押权", "第三节　质权", "第四节　留置权")
            secEntries.forEachIndexed { i, t ->
                v11.execSQL("INSERT INTO paragraphs (chapterId, idx, text, role, aiAction) VALUES (10, $i, '$t', 'BODY', 'NONE')")
            }
            // 真文章：第一段「第一节」+ 大量非简目段
            v11.execSQL("INSERT INTO paragraphs (chapterId, idx, text, role, aiAction) VALUES (11, 0, '第一节　物权法的意义和性质', 'BODY', 'NONE')")
            v11.execSQL("INSERT INTO paragraphs (chapterId, idx, text, role, aiAction) VALUES (11, 1, '人类须利用物质以谋生活，物质有限，为定分止争，促进对物有效率的使用。', 'BODY', 'NONE')")
            v11.execSQL("INSERT INTO paragraphs (chapterId, idx, text, role, aiAction) VALUES (11, 2, '物权，顾名思义，系指对物的权利。', 'BODY', 'NONE')")
            // 混合章：8 段简目 + 「注释」 + 2 万字引注大段
            val bigNote = "1.（一）私法性质与公法规定。" + "引注内容".repeat(700)
            val mixedSecs = listOf("第一节　概说", "第二节　占有的基本理论", "第三节　占有的分类", "第四节　占有的取得和消灭", "第五节　占有的效力", "第六节　占有人与恢复请求人的权利义务", "第七节　占有的保护", "第八节　准占有附录一　土地权状及登记誊本")
            mixedSecs.forEachIndexed { i, t ->
                v11.execSQL("INSERT INTO paragraphs (chapterId, idx, text, role, aiAction) VALUES (12, $i, '$t', 'BODY', 'NONE')")
            }
            v11.execSQL("INSERT INTO paragraphs (chapterId, idx, text, role, aiAction) VALUES (12, 8, '注释', 'BODY', 'NONE')")
            v11.execSQL("INSERT INTO paragraphs (chapterId, idx, text, role, aiAction) VALUES (12, 9, '$bigNote', 'BODY', 'NONE')")
            // 法条章：「第X条」+ 条文，无「第X节」段
            v11.execSQL("INSERT INTO paragraphs (chapterId, idx, text, role, aiAction) VALUES (13, 0, '第757条　（物权法定主义）', 'BODY', 'NONE')")
            v11.execSQL("INSERT INTO paragraphs (chapterId, idx, text, role, aiAction) VALUES (13, 1, '物权除依法律或习惯外，不得创设。', 'BODY', 'NONE')")
            v11.execSQL("INSERT INTO paragraphs (chapterId, idx, text, role, aiAction) VALUES (13, 2, '第758条　（登记生效要件主义）', 'BODY', 'NONE')")
        }
        helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12).use { v12 ->
            // 假章：章与段落全没
            v12.query("SELECT COUNT(*) FROM chapters WHERE id = 10").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals(0, cur.getInt(0))
            }
            v12.query("SELECT COUNT(*) FROM paragraphs WHERE chapterId = 10").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals(0, cur.getInt(0))
            }
            // 真文章：章与段落原样
            v12.query("SELECT COUNT(*) FROM paragraphs WHERE chapterId = 11").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals(3, cur.getInt(0))
            }
            // 混合章：改名「注释」、简目段删净、注释段保留
            v12.query("SELECT title FROM chapters WHERE id = 12").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("注释", cur.getString(0))
            }
            v12.query("SELECT COUNT(*) FROM paragraphs WHERE chapterId = 12").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals(2, cur.getInt(0))
            }
            v12.query("SELECT text FROM paragraphs WHERE chapterId = 12 ORDER BY idx").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("注释", cur.getString(0))
            }
            // 法条章：零误杀
            v12.query("SELECT COUNT(*) FROM paragraphs WHERE chapterId = 13").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals(3, cur.getInt(0))
            }
            // 悬空的 lastReadChapterId（指向被删假章 id=10）重置 NULL；真章引用不动
            v12.query("SELECT lastReadChapterId FROM books WHERE id = 1").use { cur ->
                assertTrue(cur.moveToFirst())
                assertTrue("悬空阅读位置应重置", cur.isNull(0))
            }
            // 章 idx 连续化：0..2（开篇不在测试数据里，剩目录删掉的假章腾位后真章重排）
            v12.query("SELECT idx FROM chapters WHERE bookId = 1 ORDER BY idx").use { cur ->
                val idxs = mutableListOf<Int>()
                while (cur.moveToNext()) idxs.add(cur.getInt(0))
                assertEquals("idx 应从 0 起连续", List(idxs.size) { it }, idxs)
            }
        }
    }

@Test
fun migrate12To13_paraMemosKindColumnBackfilled() {
    val dbName = "migration-test-1213.db"
    helper.createDatabase(dbName, 12).use { v12 ->
        v12.execSQL(
            "INSERT INTO books (title, author, sourceType, filePath, status, totalChapters, " +
                "overviewJson, createdAt, updatedAt) VALUES ('旧书', '', 'TXT', '', 'READY', 1, NULL, 1, 1)",
        )
        v12.execSQL(
            "INSERT INTO chapters (bookId, idx, title, readState, gist, keyTermsJson, level, parentOrder, calibrated) " +
                "VALUES (1, 0, '第一章', 'NOT_READ', NULL, NULL, 1, NULL, 0)",
        )
        v12.execSQL(
            "INSERT INTO para_memos (bookId, chapterId, paraId, paraIdx, quote, text, createdAt) " +
                "VALUES (1, 1, 1, 0, '不当得利的构成要件', '四要件背熟', 99)",
        )
    }
    // 12→13（实景体验轮第七波）：para_memos 加 kind 列，存量批注全部回填 'note'
    helper.runMigrationsAndValidate(dbName, 13, true, *MIGRATIONS).use { v13 ->
        v13.execSQL(
            "INSERT INTO para_memos (bookId, chapterId, paraId, paraIdx, quote, text, createdAt, kind) " +
                "VALUES (1, 1, 2, 1, '善意取得的例外', '', 100, 'doubt')",
        )
        v13.query("SELECT text, kind FROM para_memos ORDER BY id").use { cur ->
            assertTrue(cur.moveToFirst())
            // 存量行回填 'note'
            assertEquals("四要件背熟", cur.getString(0))
            assertEquals("note", cur.getString(1))
            // 新写入的存疑行 kind 原样保留
            assertTrue(cur.moveToNext())
            assertEquals("doubt", cur.getString(1))
        }
    }
}
}

