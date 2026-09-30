package com.studyfriend.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.studyfriend.app.ui.nav.AppNav
import com.studyfriend.app.ui.theme.StudyFriendTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudyFriendTheme {
                AppNav()
            }
        }
    }
}
