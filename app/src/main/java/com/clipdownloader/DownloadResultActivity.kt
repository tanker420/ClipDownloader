package com.clipdownloader

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/**
 * 透明 Activity — 用于下载通知点击时的过渡
 */
class DownloadResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 直接打开主界面
        val intent = android.content.Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}
