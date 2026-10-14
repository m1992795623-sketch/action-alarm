package com.example.actionalarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.actionalarm.data.AlarmRepository
import com.example.actionalarm.data.AlarmScheduler
import com.example.actionalarm.service.AlarmService

/**
 * 接收系统闹钟触发广播，启动前台响铃服务并弹出相机动作挑战页面
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
        val actionType = intent.getStringExtra(AlarmScheduler.EXTRA_ACTION_TYPE) ?: "SQUAT"
        val durationTier = intent.getStringExtra(AlarmScheduler.EXTRA_DURATION_TIER) ?: "SEC_15"
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_LABEL) ?: "动作唤醒闹钟"

        // 若是单次闹钟，触发后置为关闭；若是周期重复闹钟，自动注册下一次
        if (alarmId != -1) {
            val repo = AlarmRepository(context)
            val alarms = repo.loadAlarms().map { item ->
                if (item.id == alarmId) {
                    if (item.repeatDays.isEmpty()) {
                        item.copy(enabled = false)
                    } else {
                        AlarmScheduler.schedule(context, item)
                        item
                    }
                } else {
                    item
                }
            }
            repo.saveAlarms(alarms)
        }

        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_ACTION_TYPE, actionType)
            putExtra(AlarmScheduler.EXTRA_DURATION_TIER, durationTier)
            putExtra(AlarmScheduler.EXTRA_ALARM_LABEL, label)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
