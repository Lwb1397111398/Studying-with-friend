package com.studyfriend.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
