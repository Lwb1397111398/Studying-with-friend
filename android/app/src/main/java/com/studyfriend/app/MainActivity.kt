package com.studyfriend.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.ui.nav.AppNav
import com.studyfriend.app.ui.theme.StudyFriendTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = applicationContext as StudyApp
        setContent {
            // 外观三态（功能补齐轮）：watch settings 键，设置页改完即时生效；
            // null（未设置/DB 未就绪）= 跟随系统
            val mode by app.database.settingDao().watch(SettingsRepository.KEY_THEME)
                .collectAsStateWithLifecycle(initialValue = null)
            val dark = when (mode) {
                SettingsRepository.THEME_DARK -> true
                SettingsRepository.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            StudyFriendTheme(darkTheme = dark) {
                AppNav()
            }
        }
    }
}
