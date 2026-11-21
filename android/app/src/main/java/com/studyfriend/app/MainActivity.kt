package com.studyfriend.app

import android.os.Bundle
import android.view.KeyEvent
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
    /** 音量键翻页（第六波）：阅读页注册 hook 时把音量加/减转成翻页指令并拦截系统音量条 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val dir = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> -1
                KeyEvent.KEYCODE_VOLUME_DOWN -> 1
                else -> 0
            }
            if (dir != 0 && (applicationContext as StudyApp).volumeKeyTurnHook?.invoke(dir) == true) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

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
