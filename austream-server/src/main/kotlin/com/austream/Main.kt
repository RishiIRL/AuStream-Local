package com.austream

import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.austream.ui.MainScreen
import com.austream.ui.theme.AuStreamTheme
import com.austream.util.AppLog

fun main() = application {
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        AppLog.error("Uncaught exception on thread '${t.name}'", e)
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "AuStream Server",
        icon = painterResource("icon.svg"),
        state = rememberWindowState(width = 420.dp, height = 720.dp),
        resizable = false
    ) {
        AuStreamTheme {
            MainScreen()
        }
    }
}
