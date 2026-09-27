package com.clipdownloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.clipdownloader.util.LogFile
import com.clipdownloader.util.PreferencesManager

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        applyTheme(PreferencesManager(this).getThemeMode())
        createNotificationChannels()
        LogFile.init(this)
    }

    companion object {
        const val CHANNEL_ID_DOWNLOAD = "download_progress"
        const val CHANNEL_ID_RESULT = "download_result"

        /** 0=跟随系统 1=浅色 2=深色 */
        @JvmStatic fun applyTheme(mode: Int) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // 下载进度通知渠道
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_DOWNLOAD,
                    "下载进度",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "视频/图片下载进度通知"
                }
            )

            // 下载结果通知渠道
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_RESULT,
                    "下载完成",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "下载完成或失败通知"
                    enableVibration(true)
                }
            )
        }
    }
}
