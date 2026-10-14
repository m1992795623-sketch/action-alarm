package com.example.actionalarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.actionalarm.data.AlarmRepository
import com.example.actionalarm.data.AlarmScheduler

/**
 * 手机重启或应用更新后自动恢复所有已开启的闹钟
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val repo = AlarmRepository(context)
            repo.loadAlarms()
                .filter { it.enabled }
                .forEach { AlarmScheduler.schedule(context, it) }
        }
    }
}
