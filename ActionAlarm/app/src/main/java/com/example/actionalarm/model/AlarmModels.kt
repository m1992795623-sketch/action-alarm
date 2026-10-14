package com.example.actionalarm.model

/**
 * 闹钟关闭所需的指定动作类型（4个指定动作）
 */
enum class ActionType(
    val displayName: String,
    val instruction: String,
    val isDynamicRep: Boolean
) {
    CLAPPING(
        displayName = "鼓掌",
        instruction = "请面对镜头双手张开再合拢击掌（每5秒至少完成1次）",
        isDynamicRep = true
    ),
    SQUAT(
        displayName = "深蹲",
        instruction = "请退后确保全身入镜，完成站立→下蹲→站起（每5秒至少完成1次）",
        isDynamicRep = true
    ),
    STANDING(
        displayName = "持续站立",
        instruction = "请退后确保全身入镜，保持身体直立站姿不中断",
        isDynamicRep = false
    ),
    HIGH_KNEES(
        displayName = "抬腿跳",
        instruction = "请退后确保全身入镜，交替高抬腿跳跃（每5秒至少完成1次）",
        isDynamicRep = true
    );

    companion object {
        fun fromName(name: String): ActionType {
            return entries.find { it.name == name } ?: SQUAT
        }
    }
}

/**
 * 动作持续时长档位（5s, 15s, 1min, 5min 四个档位）
 */
enum class DurationTier(
    val label: String,
    val durationSeconds: Int
) {
    SEC_5("5秒", 5),
    SEC_15("15秒", 15),
    MIN_1("1分钟", 60),
    MIN_5("5分钟", 300);

    val durationMillis: Long
        get() = durationSeconds * 1000L

    companion object {
        fun fromName(name: String): DurationTier {
            return entries.find { it.name == name } ?: SEC_15
        }
    }
}

/**
 * 单个闹钟配置数据模型
 */
data class AlarmItem(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val label: String = "起床闹钟",
    val enabled: Boolean = true,
    val actionType: ActionType = ActionType.SQUAT,
    val durationTier: DurationTier = DurationTier.SEC_15,
    val repeatDays: Set<Int> = emptySet() // 1=周日 ... 7=周六 (Calendar.DAY_OF_WEEK)，空集合代表单次闹钟
) {
    fun formattedTime(): String = String.format("%02d:%02d", hour, minute)
}
