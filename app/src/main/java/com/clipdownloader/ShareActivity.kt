package com.clipdownloader

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * 分享入口（透明 Activity）
 *
 * 处理 App 内「分享 → 选择 ClipDownloader」的场景。
 * 现在没有后台监听，统一走带链接打开主界面；
 * 主界面 onResume 会读剪贴板并自动下载。
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        val text = extractText(intent)
        if (text.isNullOrBlank()) {
            toast("没有识别到可下载的内容")
            return
        }

        // 拉起主界面，把分享内容通过 Intent 交过去自动下载
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(mainIntent)
            toast("已转交主界面处理")
        } catch (e: Exception) {
            toast("打开主界面失败")
        }
    }

    private fun extractText(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            }
            Intent.ACTION_PROCESS_TEXT -> {
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            }
            Intent.ACTION_VIEW -> intent.dataString
            else -> intent.getStringExtra(Intent.EXTRA_TEXT)
        }?.trim()
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }
}
