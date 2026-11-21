package com.studyfriend.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.studyfriend.app.data.importer.PhantomTocFilter

// createSql 逐字段抄自 Room 生成的 schema 2.json（含 FK 子句/NOT NULL），漏抄会被迁移校验报红
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `author` TEXT NOT NULL, `sourceType` TEXT NOT NULL, " +
                "`filePath` TEXT NOT NULL, `status` TEXT NOT NULL, `totalChapters` INTEGER NOT NULL, " +
                "`overviewJson` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chapters` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `idx` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                "`readState` TEXT NOT NULL, `gist` TEXT, `keyTermsJson` TEXT, " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_bookId` ON `chapters` (`bookId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `paragraphs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`chapterId` INTEGER NOT NULL, `idx` INTEGER NOT NULL, `text` TEXT NOT NULL, " +
                "`role` TEXT NOT NULL, `aiAction` TEXT NOT NULL, `groupId` INTEGER, `why` TEXT, " +
                "FOREIGN KEY(`chapterId`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_paragraphs_chapterId` ON `paragraphs` (`chapterId`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_paragraphs_chapterId_idx` ON `paragraphs` (`chapterId`, `idx`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `para_notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`chapterId` INTEGER NOT NULL, `paraIds` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`friendly` TEXT NOT NULL, `analogy` TEXT, `keyPointsJson` TEXT NOT NULL, " +
                "`memoryHook` TEXT, `questionsJson` TEXT, `model` TEXT NOT NULL, " +
                "`promptVersion` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`chapterId`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_para_notes_chapterId` ON `para_notes` (`chapterId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chapter_assets` (`chapterId` INTEGER NOT NULL, " +
                "`summaryMd` TEXT NOT NULL, `mindmapTree` TEXT NOT NULL, `mindmapJson` TEXT, " +
                "`memoryMd` TEXT NOT NULL, `chainMd` TEXT NOT NULL, `quizJson` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, `promptVersion` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`chapterId`), " +
                "FOREIGN KEY(`chapterId`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `quiz_attempts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`chapterId` INTEGER NOT NULL, `qIndex` INTEGER NOT NULL, `answer` TEXT NOT NULL, " +
                "`verdict` TEXT NOT NULL, `feedback` TEXT, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`chapterId`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_quiz_attempts_chapterId` ON `quiz_attempts` (`chapterId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `review_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `chapterId` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                "`intervalIdx` INTEGER NOT NULL, `dueAt` INTEGER NOT NULL, `done` INTEGER NOT NULL, " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`chapterId`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_items_bookId_chapterId_title` " +
                "ON `review_items` (`bookId`, `chapterId`, `title`)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_items_dueAt` ON `review_items` (`dueAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_items_chapterId` ON `review_items` (`chapterId`)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 视觉后台队列（OPT-F）：createSql 逐字段抄自 Entity 定义，漏抄会被迁移校验报红
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `vision_queue` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `uri` TEXT NOT NULL, `pageNo` INTEGER NOT NULL, " +
                "`originChars` INTEGER NOT NULL, `status` TEXT NOT NULL, `attempts` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vision_queue_bookId` ON `vision_queue` (`bookId`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_vision_queue_bookId_pageNo` ON `vision_queue` (`bookId`, `pageNo`)",
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // P3b-2 数据前置：4 列全部为加列，默认值保证旧行/旧查询零改动（「加列无人消费」安全态）
        // paragraphs.pageNo=段首页码（P2 pageNo 链路）；chapters.level/parentOrder=节挂接产物；
        // chapters.calibrated=目录校准标志（本期不消费，语义定义权归 P5）
        db.execSQL("ALTER TABLE `paragraphs` ADD COLUMN `pageNo` INTEGER")
        db.execSQL("ALTER TABLE `chapters` ADD COLUMN `level` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `chapters` ADD COLUMN `parentOrder` INTEGER")
        db.execSQL("ALTER TABLE `chapters` ADD COLUMN `calibrated` INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // P4 示意图保留：figures 新表（全 NOT NULL，无旧数据回填问题）
        // createSql 逐字段抄自 Entity 定义，漏抄会被迁移校验报红
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `figures` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `chapterId` INTEGER NOT NULL, `pageNo` INTEGER NOT NULL, " +
                "`ordAfterPara` INTEGER NOT NULL, `bboxY0` REAL NOT NULL, `seqNo` INTEGER NOT NULL, " +
                "`file` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, " +
                "`format` TEXT NOT NULL, `md5` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`chapterId`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_figures_bookId` ON `figures` (`bookId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_figures_chapterId` ON `figures` (`chapterId`)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // P4 低质量放行标记（计划案 §3-C P1-3「以低质量分支标记为准」）：第一道闸放行的
        // 图页入队时置 1，VisionWorker 第二道闸据此放行（Boolean 存 INTEGER 0/1）
        db.execSQL("ALTER TABLE `vision_queue` ADD COLUMN `lowQuality` INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 功能补齐轮：阅读位置记忆（books.lastReadChapterId/lastReadAt + chapters.lastReadParaIdx
        // 加列，nullable 无默认值，旧行 NULL=未记录）+ bookmarks/qa_history 两张新表。
        // createSql 逐字段抄自 Entity 定义，漏抄会被迁移校验报红
        db.execSQL("ALTER TABLE `books` ADD COLUMN `lastReadChapterId` INTEGER")
        db.execSQL("ALTER TABLE `books` ADD COLUMN `lastReadAt` INTEGER")
        db.execSQL("ALTER TABLE `chapters` ADD COLUMN `lastReadParaIdx` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `chapterId` INTEGER NOT NULL, `paraIdx` INTEGER NOT NULL, " +
                "`preview` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`chapterId`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_chapterId` ON `bookmarks` (`chapterId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `qa_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `chapterId` INTEGER NOT NULL, `chapterTitle` TEXT NOT NULL, " +
                "`question` TEXT NOT NULL, `answer` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_qa_history_bookId` ON `qa_history` (`bookId`)")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 学习闭环轮：段落批注新表（用户读书笔记，锚段存 quote 冗余原文片段）。
        // createSql 逐字段抄自 Entity 定义，漏抄会被迁移校验报红
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `para_memos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `chapterId` INTEGER NOT NULL, `paraId` INTEGER NOT NULL, " +
                "`paraIdx` INTEGER NOT NULL, `quote` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`chapterId`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_para_memos_bookId` ON `para_memos` (`bookId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_para_memos_chapterId` ON `para_memos` (`chapterId`)")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 体验增量轮：阅读时长统计新表（一本书一天一行，minutes upsert 递增）。
        // createSql 逐字段抄自 Entity 定义，漏抄会被迁移校验报红
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reading_stats` (`bookId` INTEGER NOT NULL, " +
                "`day` TEXT NOT NULL, `minutes` INTEGER NOT NULL, " +
                "PRIMARY KEY(`bookId`, `day`), " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 批D：自问落错题本——quiz_attempts 加来源列（章末自测=SUMMARY 兼容存量；
        // 讲解卡自问=NOTE），复习中心错题重练按来源取题干
        db.execSQL("ALTER TABLE `quiz_attempts` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'SUMMARY'")
        // 批D：术语进复习——review_items 加排期项类型（CHAPTER 章 / TERM 术语卡），
        // 术语卡翻转卡复用同一排期推进
        db.execSQL("ALTER TABLE `review_items` ADD COLUMN `itemType` TEXT NOT NULL DEFAULT 'CHAPTER'")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // P0-① FSRS 复习排期：review_items 加卡状态 4 列（stability null = 没复习过的新卡）+ review_log 新表。
        // 旧档位一次性折算成 FSRS 初值（未完成行按当前档位天数为稳定性、难度取中位 5.0、上次复习按
        // dueAt 倒推一档间隔）；已完成行无读取方，留默认值不折算。fsrsState=2 即 REVIEW（空学习步，
        // 任意评分后都按天排期，列值恒为 2，保留列位防后续启用学习步）
        db.execSQL("ALTER TABLE `review_items` ADD COLUMN `fsrsState` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `review_items` ADD COLUMN `fsrsStability` REAL")
        db.execSQL("ALTER TABLE `review_items` ADD COLUMN `fsrsDifficulty` REAL")
        db.execSQL("ALTER TABLE `review_items` ADD COLUMN `fsrsLastReviewAt` INTEGER")
        // createSql 逐字段抄自 ReviewLogEntity 定义，漏抄会被迁移校验报红
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `review_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`itemId` INTEGER NOT NULL, `rating` INTEGER NOT NULL, `stateBefore` INTEGER NOT NULL, " +
                "`stabilityBefore` REAL, `difficultyBefore` REAL, `dueBefore` INTEGER NOT NULL, " +
                "`scheduledDays` REAL NOT NULL, `dueAt` INTEGER NOT NULL, `reviewedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`itemId`) REFERENCES `review_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_log_itemId` ON `review_log` (`itemId`)")
        db.execSQL(
            "UPDATE review_items SET fsrsState = 2, " +
                "fsrsStability = CASE intervalIdx WHEN 0 THEN 1.0 WHEN 1 THEN 3.0 WHEN 2 THEN 7.0 WHEN 3 THEN 14.0 ELSE 30.0 END, " +
                "fsrsDifficulty = 5.0, " +
                "fsrsLastReviewAt = dueAt - CAST(CASE intervalIdx WHEN 0 THEN 1 WHEN 1 THEN 3 WHEN 2 THEN 7 WHEN 3 THEN 14 ELSE 30 END AS INTEGER) * 86400000 " +
                "WHERE done = 0",
        )
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 简目假章清理（判据单源 PhantomTocFilter.decide，《民法物权_Kit图包》实录）：
        // kit 章表把书前/书末目录页误切成的「第X章」假章——纯简目章整章剔除（段落连带，
        // figures 挂章 CASCADE 但假章实测零图）；混合章（简目条目+书末注释汇总）只删简目段
        // 并按首个非简目段改名。重导路径同判据由 KitBookParser 链路防御，存量书在这里修。
        val chapterCur = db.query("SELECT id, title FROM chapters")
        val chapterList = buildList {
            while (chapterCur.moveToNext()) add(chapterCur.getLong(0) to (chapterCur.getString(1) ?: ""))
        }
        chapterCur.close()
        for ((chapterId, title) in chapterList) {
            val paraCur = db.query(
                "SELECT id, text FROM paragraphs WHERE chapterId = ? AND role = 'BODY' ORDER BY idx",
                arrayOf(chapterId.toString()),
            )
            val bodyRows = buildList {
                while (paraCur.moveToNext()) add(paraCur.getLong(0) to (paraCur.getString(1) ?: ""))
            }
            paraCur.close()
            when (val d = PhantomTocFilter.decide(title, bodyRows.map { it.second })) {
                PhantomTocFilter.Decision.Keep -> Unit
                PhantomTocFilter.Decision.DropWhole -> {
                    db.execSQL("DELETE FROM paragraphs WHERE chapterId = ?", arrayOf(chapterId))
                    db.execSQL("DELETE FROM chapters WHERE id = ?", arrayOf(chapterId))
                }
                is PhantomTocFilter.Decision.DropEntries -> {
                    val secIds = bodyRows.filter { PhantomTocFilter.isSectionEntry(it.second) }.map { it.first }
                    for (id in secIds) {
                        db.execSQL("DELETE FROM paragraphs WHERE id = ?", arrayOf(id))
                    }
                    db.execSQL("UPDATE chapters SET title = ? WHERE id = ?", arrayOf(d.newTitle, chapterId))
                }
            }
        }
        // 删章后 books.lastReadChapterId 可能悬空（指向被删假章）——点书直达会进不存在的章
        // 显示空内容（模拟器实测复现），悬空引用一律重置回「进章目录页」默认行为
        db.execSQL(
            "UPDATE books SET lastReadChapterId = NULL " +
                "WHERE lastReadChapterId IS NOT NULL AND lastReadChapterId NOT IN (SELECT id FROM chapters)",
        )
        // 章 idx 连续化（删假章后 0/1/4/5... 有洞，章目录页编号观感差；idx 仅排序与展示用，
        // 段落/复习项挂 chapterId 不挂 idx，重排无牵连）
        val bookCur = db.query("SELECT DISTINCT bookId FROM chapters")
        val bookIds = mutableListOf<Long>()
        while (bookCur.moveToNext()) bookIds.add(bookCur.getLong(0))
        bookCur.close()
        for (bookId in bookIds) {
            val idCur = db.query(
                "SELECT id FROM chapters WHERE bookId = ? ORDER BY idx",
                arrayOf(bookId.toString()),
            )
            val orderedIds = mutableListOf<Long>()
            while (idCur.moveToNext()) orderedIds.add(idCur.getLong(0))
            idCur.close()
            orderedIds.forEachIndexed { newIdx, id ->
                db.execSQL("UPDATE chapters SET idx = ? WHERE id = ?", arrayOf(newIdx, id))
            }
        }
    }
}

// 待弄懂清单（实景体验轮第七波）：para_memos 加 kind 列——'note'=普通批注，
// 'doubt'=存疑条目（只存引用不写感想，攒着课后集中问）。存量行全部回填 'note'。
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `para_memos` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'note'")
    }
}

val MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
)
