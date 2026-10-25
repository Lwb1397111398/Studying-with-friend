package com.studyfriend.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SettingEntity::class,
        BookEntity::class,
        ChapterEntity::class,
        ParagraphEntity::class,
        ParaNoteEntity::class,
        ChapterAssetEntity::class,
        QuizAttemptEntity::class,
        ReviewItemEntity::class,
        VisionQueueEntity::class,
        FigureEntity::class,
        BookmarkEntity::class,
        QaHistoryEntity::class,
        ParaMemoEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class StudyDatabase : RoomDatabase() {
    abstract fun settingDao(): SettingDao
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun paragraphDao(): ParagraphDao
    abstract fun paraNoteDao(): ParaNoteDao
    abstract fun chapterAssetDao(): ChapterAssetDao
    abstract fun quizAttemptDao(): QuizAttemptDao
    abstract fun reviewItemDao(): ReviewItemDao
    abstract fun visionQueueDao(): VisionQueueDao
    abstract fun figureDao(): FigureDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun qaHistoryDao(): QaHistoryDao
    abstract fun paraMemoDao(): ParaMemoDao

    companion object {
        fun build(context: Context): StudyDatabase =
            Room.databaseBuilder(context, StudyDatabase::class.java, "study_friend.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
