package com.example.actionalarm.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.actionalarm.model.AlarmItem
import com.example.actionalarm.receiver.AlarmReceiver
import com.example.actionalarm.ui.MainActivity
import java.util.Calendar

/**
 * 使用系统 AlarmManager.setAlarmClock 调度高优先级闹钟，支持锁屏精确唤醒
 */
object AlarmScheduler {

    fun schedule(context: Context, alarm: AlarmItem) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!alarm.enabled) {
            cancel(context, alarm.id)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // Android 12+ 若尚未授予精确闹钟权限，由主界面引导授权
            return
        }

        val triggerTimeMillis = computeNextTriggerMillis(alarm)
        val receiverIntent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarm.id)
            putExtra(EXTRA_ACTION_TYPE, alarm.actionType.name)
            putExtra(EXTRA_DURATION_TIER, alarm.durationTier.name)
            putExtra(EXTRA_ALARM_LABEL, alarm.label)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id,
            receiverIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, MainActivity::class.java)
        val showPendingIntent = PendingIntent.getActivity(
            context,
            alarm.id,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val clockInfo = AlarmManager.AlarmClockInfo(triggerTimeMillis, showPendingIntent)
        alarmManager.setAlarmClock(clockInfo, pendingIntent)
    }

    fun cancel(context: Context, alarmId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun computeNextTriggerMillis(alarm: AlarmItem): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (alarm.repeatDays.isEmpty()) {
            if (target.timeInMillis <= now.timeInMillis) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis
        }

        // 若设置了按周重复，寻找最近的一个启用星期
        for (offset in 0..7) {
            val candidate = (target.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
            }
            val dayOfWeek = candidate.get(Calendar.DAY_OF_WEEK)
            if (alarm.repeatDays.contains(dayOfWeek) && candidate.timeInMillis > now.timeInMillis) {
                return candidate.timeInMillis
            }
        }
        target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis
    }

    const val EXTRA_ALARM_ID = "extra_alarm_id"
    const val EXTRA_ACTION_TYPE = "extra_action_type"
    const val EXTRA_DURATION_TIER = "extra_duration_tier"
    const val EXTRA_ALARM_LABEL = "extra_alarm_label"
}
