package com.example.actionalarm.data

import android.content.Context
import com.example.actionalarm.model.ActionType
import com.example.actionalarm.model.AlarmItem
import com.example.actionalarm.model.DurationTier
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地持久化存储所有闹钟配置
 */
class AlarmRepository(context: Context) {
    private val prefs = context.getSharedPreferences("action_alarms_prefs", Context.MODE_PRIVATE)

    fun loadAlarms(): List<AlarmItem> {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return defaultSampleAlarms()
        return try {
            val array = JSONArray(raw)
            val result = mutableListOf<AlarmItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val daysArray = obj.optJSONArray("repeatDays") ?: JSONArray()
                val days = mutableSetOf<Int>()
                for (j in 0 until daysArray.length()) {
                    days.add(daysArray.getInt(j))
                }
                result.add(
                    AlarmItem(
                        id = obj.getInt("id"),
                        hour = obj.getInt("hour"),
                        minute = obj.getInt("minute"),
                        label = obj.optString("label", "动作闹钟"),
                        enabled = obj.optBoolean("enabled", true),
                        actionType = ActionType.fromName(obj.optString("actionType", ActionType.SQUAT.name)),
                        durationTier = DurationTier.fromName(obj.optString("durationTier", DurationTier.SEC_15.name)),
                        repeatDays = days
                    )
                )
            }
            result.sortedWith(compareBy({ it.hour }, { it.minute }))
        } catch (e: Exception) {
            defaultSampleAlarms()
        }
    }

    fun saveAlarms(alarms: List<AlarmItem>) {
        val array = JSONArray()
        alarms.forEach { alarm ->
            val obj = JSONObject().apply {
                put("id", alarm.id)
                put("hour", alarm.hour)
                put("minute", alarm.minute)
                put("label", alarm.label)
                put("enabled", alarm.enabled)
                put("actionType", alarm.actionType.name)
                put("durationTier", alarm.durationTier.name)
                val days = JSONArray()
                alarm.repeatDays.forEach { days.put(it) }
                put("repeatDays", days)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_ALARMS, array.toString()).apply()
    }

    fun getAlarmById(id: Int): AlarmItem? {
        return loadAlarms().find { it.id == id }
    }

    private fun defaultSampleAlarms(): List<AlarmItem> {
        return listOf(
            AlarmItem(
                id = 1001,
                hour = 7,
                minute = 30,
                label = "晨间深蹲唤醒",
                enabled = false,
                actionType = ActionType.SQUAT,
                durationTier = DurationTier.SEC_15
            ),
            AlarmItem(
                id = 1002,
                hour = 8,
                minute = 0,
                label = "活力抬腿跳",
                enabled = false,
                actionType = ActionType.HIGH_KNEES,
                durationTier = DurationTier.MIN_1
            )
        )
    }

    companion object {
        private const val KEY_ALARMS = "alarms_json"
    }
}
