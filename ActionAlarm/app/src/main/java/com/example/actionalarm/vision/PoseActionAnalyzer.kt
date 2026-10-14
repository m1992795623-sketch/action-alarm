package com.example.actionalarm.vision

import android.annotation.SuppressLint
import android.graphics.PointF
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.actionalarm.model.ActionType
import com.example.actionalarm.model.DurationTier
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 实时传递至 UI 层的骨骼关键点与识别状态快照
 */
data class ChallengeUiState(
    val actionType: ActionType,
    val durationTier: DurationTier,
    val elapsedMillis: Long = 0L,
    val targetMillis: Long = durationTier.durationMillis,
    val repCount: Int = 0,
    val remainingRepWindowMillis: Long = PoseActionAnalyzer.MAX_REP_INTERVAL_MS,
    val isBodyVisible: Boolean = false,
    val statusHint: String = "请将身体置于取景框内",
    val wasResetRecently: Boolean = false,
    val isCompleted: Boolean = false,
    val normalizedLandmarks: List<Pair<PointF, PointF>> = emptyList(),
    val normalizedPoints: List<PointF> = emptyList()
) {
    val progress: Float
        get() = (elapsedMillis.toFloat() / targetMillis.toFloat()).coerceIn(0f, 1f)

    val repWindowProgress: Float
        get() = (remainingRepWindowMillis.toFloat() / PoseActionAnalyzer.MAX_REP_INTERVAL_MS.toFloat())
            .coerceIn(0f, 1f)
}

/**
 * 核心视觉动作识别与连续计时引擎
 *
 * 规则说明：
 * 1. 采用【连续计时】机制：只有当动作有效维持时，计时器才连续累加；一旦中断超时，已累计时间立即清零重置。
 * 2. 动态动作（鼓掌、深蹲、抬腿跳）：严格执行【每 5 秒至少完成 1 次有效动作】。
 *    - 完成第 1 次动作（或进入预备动作并在 5 秒内完成第 1 次动作）后，连续计时持续推进；
 *    - 每次完成新的有效动作，都会将 5 秒倒计时窗口刷新回 5.0 秒；
 *    - 如果距离上一次完成有效动作超过 5.0 秒（> 5000ms），或者人体离开画面超过 1.2 秒，连续计时与次数立即归零！
 * 3. 静态动作（持续站立）：
 *    - 实时检测全身直立站姿（双肩、双髋、双膝、双踝入镜，躯干直立，双膝角度 >= 152°）；
 *    - 站姿保持期间连续累加计时；若离开画面或坐下/蹲下超过 1.0 秒，连续计时立即归零！
 */
class PoseActionAnalyzer(
    private val actionType: ActionType,
    private val durationTier: DurationTier,
    private val isFrontCamera: Boolean = true,
    private val onStateUpdated: (ChallengeUiState) -> Unit
) : ImageAnalysis.Analyzer {

    private val options = AccuratePoseDetectorOptions.Builder()
        .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
        .build()

    private val detector = PoseDetection.getClient(options)

    // 连续计时核心状态
    private var elapsedMillis: Long = 0L
    private var lastFrameTimestampMs: Long = 0L
    private var lastValidRepTimestampMs: Long = 0L
    private var lastStandingValidTimestampMs: Long = 0L
    private var lastBodyVisibleTimestampMs: Long = 0L
    private var isContinuousSessionActive: Boolean = false
    private var repCount: Int = 0
    private var isCompleted: Boolean = false

    // 四个动作的状态机阶段标记（带防抖迟滞阈值）
    private var clapHandsApart: Boolean = false
    private var squatDownPhase: Boolean = false
    private var highKneeLiftedPhase: Boolean = false

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        if (isCompleted) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        // 计算旋转后的有效图像宽高，用于骨骼坐标归一化
        val isRotated90or270 = rotationDegrees == 90 || rotationDegrees == 270
        val frameWidth = if (isRotated90or270) mediaImage.height.toFloat() else mediaImage.width.toFloat()
        val frameHeight = if (isRotated90or270) mediaImage.width.toFloat() else mediaImage.height.toFloat()

        detector.process(inputImage)
            .addOnSuccessListener { pose ->
                processPose(pose, frameWidth, frameHeight)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun processPose(pose: Pose, frameWidth: Float, frameHeight: Float) {
        val now = SystemClock.elapsedRealtime()
        val deltaMs = if (lastFrameTimestampMs == 0L) 0L else (now - lastFrameTimestampMs).coerceAtMost(250L)
        lastFrameTimestampMs = now

        val skeletonLines = extractNormalizedSkeleton(pose, frameWidth, frameHeight)
        val keyPoints = extractNormalizedPoints(pose, frameWidth, frameHeight)

        if (actionType.isDynamicRep) {
            processDynamicRepAction(pose, now, deltaMs, skeletonLines, keyPoints)
        } else {
            processContinuousStanding(pose, now, deltaMs, skeletonLines, keyPoints)
        }
    }

    /**
     * 处理动态动作（鼓掌、深蹲、抬腿跳）：连续计时 + 每5秒至少完成1次
     */
    private fun processDynamicRepAction(
        pose: Pose,
        now: Long,
        deltaMs: Long,
        skeletonLines: List<Pair<PointF, PointF>>,
        keyPoints: List<PointF>
    ) {
        val (isVisible, repCompletedNow, phaseHint) = when (actionType) {
            ActionType.CLAPPING -> detectClappingRep(pose)
            ActionType.SQUAT -> detectSquatRep(pose)
            ActionType.HIGH_KNEES -> detectHighKneesRep(pose)
            ActionType.STANDING -> Triple(false, false, "")
        }

        if (isVisible) {
            lastBodyVisibleTimestampMs = now
        }

        // 若人体完全离开镜头超过 1.5 秒，重置连续计时
        if (!isVisible && isContinuousSessionActive && (now - lastBodyVisibleTimestampMs > BODY_LOST_RESET_MS)) {
            resetSession()
            emitState(
                isBodyVisible = false,
                remainingWindowMs = MAX_REP_INTERVAL_MS,
                statusHint = "未检测到身体关键点，连续计时已归零！请回到镜头内",
                wasReset = true,
                skeletonLines = skeletonLines,
                keyPoints = keyPoints
            )
            return
        }

        // 若本帧刚好完成 1 次有效动作
        if (repCompletedNow) {
            repCount += 1
            lastValidRepTimestampMs = now
            if (!isContinuousSessionActive) {
                isContinuousSessionActive = true
            }
        }

        if (!isContinuousSessionActive) {
            // 尚未激活连续计时：等待用户完成第1次动作开启连续计时窗口
            val waitHint = if (isVisible) {
                "请完成第 1 次${actionType.displayName}以启动连续计时（$phaseHint）"
            } else {
                actionType.instruction
            }
            emitState(
                isBodyVisible = isVisible,
                remainingWindowMs = MAX_REP_INTERVAL_MS,
                statusHint = waitHint,
                wasReset = false,
                skeletonLines = skeletonLines,
                keyPoints = keyPoints
            )
            return
        }

        // 已处于连续计时会话中：检查距离上一次完成动作是否超过 5000ms
        val timeSinceLastRep = now - lastValidRepTimestampMs
        if (timeSinceLastRep > MAX_REP_INTERVAL_MS) {
            // 超过 5 秒未完成新动作 -> 连续计时立即归零！
            resetSession()
            emitState(
                isBodyVisible = isVisible,
                remainingWindowMs = 0L,
                statusHint = "超过 5 秒未完成${actionType.displayName}，计时已归零！请重新开始",
                wasReset = true,
                skeletonLines = skeletonLines,
                keyPoints = keyPoints
            )
            return
        }

        // 在 5 秒有效窗口内，连续累加计时
        elapsedMillis = (elapsedMillis + deltaMs).coerceAtMost(durationTier.durationMillis)
        val remainingWindow = (MAX_REP_INTERVAL_MS - timeSinceLastRep).coerceAtLeast(0L)

        if (elapsedMillis >= durationTier.durationMillis) {
            isCompleted = true
            emitState(
                isBodyVisible = true,
                remainingWindowMs = remainingWindow,
                statusHint = "挑战成功！正在关闭闹钟...",
                wasReset = false,
                skeletonLines = skeletonLines,
                keyPoints = keyPoints
            )
        } else {
            val secLeftInWindow = String.format("%.1f", remainingWindow / 1000f)
            emitState(
                isBodyVisible = isVisible,
                remainingWindowMs = remainingWindow,
                statusHint = "连续计时中！请在 ${secLeftInWindow}s 内完成下一次${actionType.displayName}（$phaseHint）",
                wasReset = false,
                skeletonLines = skeletonLines,
                keyPoints = keyPoints
            )
        }
    }

    /**
     * 处理静态动作（持续站立）：全身直立保持，中断超过 1.0 秒即归零
     */
    private fun processContinuousStanding(
        pose: Pose,
        now: Long,
        deltaMs: Long,
        skeletonLines: List<Pair<PointF, PointF>>,
        keyPoints: List<PointF>
    ) {
        val (isVisible, isStandingValid, standHint) = detectStandingPosture(pose)

        if (isStandingValid) {
            lastStandingValidTimestampMs = now
            isContinuousSessionActive = true
            elapsedMillis = (elapsedMillis + deltaMs).coerceAtMost(durationTier.durationMillis)

            if (elapsedMillis >= durationTier.durationMillis) {
                isCompleted = true
                emitState(
                    isBodyVisible = true,
                    remainingWindowMs = MAX_REP_INTERVAL_MS,
                    statusHint = "持续站立达标！正在关闭闹钟...",
                    wasReset = false,
                    skeletonLines = skeletonLines,
                    keyPoints = keyPoints
                )
            } else {
                emitState(
                    isBodyVisible = true,
                    remainingWindowMs = MAX_REP_INTERVAL_MS,
                    statusHint = "站姿标准，请继续保持直立站立！",
                    wasReset = false,
                    skeletonLines = skeletonLines,
                    keyPoints = keyPoints
                )
            }
        } else {
            // 允许最多 1.0 秒的单帧抖动容差；超过 1.0 秒未保持站姿则立即归零
            val graceElapsed = now - lastStandingValidTimestampMs
            if (isContinuousSessionActive && graceElapsed > STANDING_INTERRUPT_GRACE_MS) {
                resetSession()
                emitState(
                    isBodyVisible = isVisible,
                    remainingWindowMs = 0L,
                    statusHint = "站立中断（$standHint），连续计时已归零！",
                    wasReset = true,
                    skeletonLines = skeletonLines,
                    keyPoints = keyPoints
                )
            } else {
                emitState(
                    isBodyVisible = isVisible,
                    remainingWindowMs = (STANDING_INTERRUPT_GRACE_MS - graceElapsed).coerceAtLeast(0L),
                    statusHint = standHint,
                    wasReset = false,
                    skeletonLines = skeletonLines,
                    keyPoints = keyPoints
                )
            }
        }
    }

    /**
     * 动作 1：【鼓掌】检测
     * 原理：计算左右手腕（或食指）之间的欧氏距离，并用双肩宽度进行尺度归一化。
     * 状态机：双手分开（距离 > 0.85 倍肩宽） -> 双手合拢击掌（距离 < 0.40 倍肩宽） 计为 1 次有效鼓掌。
     */
    private fun detectClappingRep(pose: Pose): Triple<Boolean, Boolean, String> {
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val leftWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
        val rightWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)

        if (!areLandmarksConfident(leftShoulder, rightShoulder, leftWrist, rightWrist)) {
            return Triple(false, false, "请确保上半身与双手在镜头内")
        }

        val shoulderWidth = distance(leftShoulder!!, rightShoulder!!).coerceAtLeast(20f)
        val wristDistance = distance(leftWrist!!, rightWrist!!)
        val normalizedDist = wristDistance / shoulderWidth

        return if (!clapHandsApart) {
            if (normalizedDist > 0.80f) {
                clapHandsApart = true
                Triple(true, false, "双手已张开，请合拢击掌")
            } else {
                Triple(true, false, "请先将双手向两侧张开")
            }
        } else {
            if (normalizedDist < 0.42f) {
                clapHandsApart = false
                Triple(true, true, "完成一次击掌！请继续张开双手")
            } else {
                Triple(true, false, "请双手合拢击掌")
            }
        }
    }

    /**
     * 动作 2：【深蹲】检测
     * 原理：计算左/右腿的髋-膝-踝夹角（Knee Angle）。
     * 状态机：直立状态（膝角 > 152°） -> 下蹲状态（膝角 < 118°） -> 回到直立状态（膝角 > 150°） 计为 1 次有效深蹲。
     */
    private fun detectSquatRep(pose: Pose): Triple<Boolean, Boolean, String> {
        val lHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        val lKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)
        val rKnee = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE)
        val lAnkle = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
        val rAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)

        if (!areLandmarksConfident(lHip, rHip, lKnee, rKnee, lAnkle, rAnkle)) {
            return Triple(false, false, "请退后一步，确保髋部、膝盖和脚踝完整入镜")
        }

        val leftKneeAngle = angleDegrees(lHip!!, lKnee!!, lAnkle!!)
        val rightKneeAngle = angleDegrees(rHip!!, rKnee!!, rAnkle!!)
        val avgKneeAngle = (leftKneeAngle + rightKneeAngle) / 2f

        return if (!squatDownPhase) {
            if (avgKneeAngle < 118f) {
                squatDownPhase = true
                Triple(true, false, "下蹲到位（膝角 ${avgKneeAngle.toInt()}°），请站起！")
            } else {
                Triple(true, false, "请弯曲膝盖向下深蹲（当前膝角 ${avgKneeAngle.toInt()}°）")
            }
        } else {
            if (avgKneeAngle > 150f) {
                squatDownPhase = false
                Triple(true, true, "完成一次深蹲！请继续下蹲")
            } else {
                Triple(true, false, "请向上站直完成本次深蹲（当前膝角 ${avgKneeAngle.toInt()}°）")
            }
        }
    }

    /**
     * 动作 3：【持续站立】检测
     * 原理：检测双肩、双髋、双膝、双踝均在画面内，膝关节伸直（>= 152°），且躯干与腿部纵向伸展比例正常（非躺姿或坐姿）。
     */
    private fun detectStandingPosture(pose: Pose): Triple<Boolean, Boolean, String> {
        val lShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val lHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        val lKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)
        val rKnee = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE)
        val lAnkle = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
        val rAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)

        if (!areLandmarksConfident(lShoulder, rShoulder, lHip, rHip, lKnee, rKnee, lAnkle, rAnkle)) {
            return Triple(false, false, "请退后确保全身（从肩到脚踝）完整入镜")
        }

        val leftKneeAngle = angleDegrees(lHip!!, lKnee!!, lAnkle!!)
        val rightKneeAngle = angleDegrees(rHip!!, rKnee!!, rAnkle!!)
        val avgKneeAngle = (leftKneeAngle + rightKneeAngle) / 2f

        val shoulderMidY = (lShoulder!!.position.y + rShoulder!!.position.y) / 2f
        val hipMidY = (lHip.position.y + rHip.position.y) / 2f
        val ankleMidY = (lAnkle.position.y + rAnkle.position.y) / 2f
        val shoulderWidth = distance(lShoulder, rShoulder).coerceAtLeast(20f)

        val torsoVerticalSpan = hipMidY - shoulderMidY
        val legVerticalSpan = ankleMidY - hipMidY

        val isUpright = torsoVerticalSpan > shoulderWidth * 0.75f && legVerticalSpan > torsoVerticalSpan * 0.85f
        val isKneesStraight = avgKneeAngle >= 152f

        return when {
            !isUpright -> Triple(true, false, "请保持身体直立站姿，勿弯腰或卧躺")
            !isKneesStraight -> Triple(true, false, "请站直双腿（当前膝角 ${avgKneeAngle.toInt()}°）")
            else -> Triple(true, true, "站姿标准，保持中")
        }
    }

    /**
     * 动作 4：【抬腿跳】检测
     * 原理：追踪左右膝盖相对于髋部的高抬幅度（或单侧膝角弯曲上提）。
     * 状态机：单侧或双侧膝盖高抬至靠近髋部水平线（膝盖与髋部纵向距离 < 0.55 倍躯干长，或单腿高抬膝角 < 115°） -> 落地恢复（> 0.78 倍躯干长） 计为 1 次有效抬腿跳。
     */
    private fun detectHighKneesRep(pose: Pose): Triple<Boolean, Boolean, String> {
        val lShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val lHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        val lKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)
        val rKnee = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE)
        val lAnkle = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
        val rAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)

        if (!areLandmarksConfident(lShoulder, rShoulder, lHip, rHip, lKnee, rKnee, lAnkle, rAnkle)) {
            return Triple(false, false, "请退后确保全身入镜以识别高抬腿动作")
        }

        val shoulderMidY = (lShoulder!!.position.y + rShoulder!!.position.y) / 2f
        val hipMidY = (lHip!!.position.y + rHip!!.position.y) / 2f
        val torsoLen = (hipMidY - shoulderMidY).coerceAtLeast(30f)

        val leftKneeLiftRatio = (lKnee!!.position.y - lHip.position.y) / torsoLen
        val rightKneeLiftRatio = (rKnee!!.position.y - rHip.position.y) / torsoLen
        val minKneeRatio = minOf(leftKneeLiftRatio, rightKneeLiftRatio)

        val leftKneeAngle = angleDegrees(lHip, lKnee, lAnkle!!)
        val rightKneeAngle = angleDegrees(rHip, rKnee, rAnkle!!)
        val minKneeAngle = minOf(leftKneeAngle, rightKneeAngle)

        return if (!highKneeLiftedPhase) {
            if (minKneeRatio < 0.55f || minKneeAngle < 115f) {
                highKneeLiftedPhase = true
                Triple(true, false, "高抬腿到位，请落地交替！")
            } else {
                Triple(true, false, "请向上高抬膝盖跳跃")
            }
        } else {
            if (minKneeRatio > 0.76f && minKneeAngle > 142f) {
                highKneeLiftedPhase = false
                Triple(true, true, "完成一次抬腿跳！请继续交替高抬腿")
            } else {
                Triple(true, false, "请放下一侧腿并继续交替高抬腿")
            }
        }
    }

    private fun resetSession() {
        elapsedMillis = 0L
        repCount = 0
        isContinuousSessionActive = false
        clapHandsApart = false
        squatDownPhase = false
        highKneeLiftedPhase = false
    }

    private fun emitState(
        isBodyVisible: Boolean,
        remainingWindowMs: Long,
        statusHint: String,
        wasReset: Boolean,
        skeletonLines: List<Pair<PointF, PointF>>,
        keyPoints: List<PointF>
    ) {
        onStateUpdated(
            ChallengeUiState(
                actionType = actionType,
                durationTier = durationTier,
                elapsedMillis = elapsedMillis,
                targetMillis = durationTier.durationMillis,
                repCount = repCount,
                remainingRepWindowMillis = remainingWindowMs,
                isBodyVisible = isBodyVisible,
                statusHint = statusHint,
                wasResetRecently = wasReset,
                isCompleted = isCompleted,
                normalizedLandmarks = skeletonLines,
                normalizedPoints = keyPoints
            )
        )
    }

    private fun areLandmarksConfident(vararg landmarks: PoseLandmark?): Boolean {
        return landmarks.all { it != null && it.inFrameLikelihood >= MIN_CONFIDENCE }
    }

    private fun distance(a: PoseLandmark, b: PoseLandmark): Float {
        return hypot(a.position.x - b.position.x, a.position.y - b.position.y)
    }

    private fun angleDegrees(first: PoseLandmark, mid: PoseLandmark, last: PoseLandmark): Float {
        val rad = atan2(last.position.y - mid.position.y, last.position.x - mid.position.x) -
                atan2(first.position.y - mid.position.y, first.position.x - mid.position.x)
        var deg = abs(Math.toDegrees(rad.toDouble()).toFloat())
        if (deg > 180f) {
            deg = 360f - deg
        }
        return deg
    }

    private fun extractNormalizedSkeleton(
        pose: Pose,
        frameWidth: Float,
        frameHeight: Float
    ): List<Pair<PointF, PointF>> {
        val connections = listOf(
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
            PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
            PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
            PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE
        )

        return connections.mapNotNull { (startType, endType) ->
            val s = pose.getPoseLandmark(startType)
            val e = pose.getPoseLandmark(endType)
            if (s != null && e != null && s.inFrameLikelihood >= 0.5f && e.inFrameLikelihood >= 0.5f) {
                normalizePoint(s, frameWidth, frameHeight) to normalizePoint(e, frameWidth, frameHeight)
            } else {
                null
            }
        }
    }

    private fun extractNormalizedPoints(
        pose: Pose,
        frameWidth: Float,
        frameHeight: Float
    ): List<PointF> {
        val targetTypes = listOf(
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE
        )
        return targetTypes.mapNotNull { type ->
            val lm = pose.getPoseLandmark(type)
            if (lm != null && lm.inFrameLikelihood >= 0.5f) {
                normalizePoint(lm, frameWidth, frameHeight)
            } else {
                null
            }
        }
    }

    private fun normalizePoint(landmark: PoseLandmark, width: Float, height: Float): PointF {
        val rawX = (landmark.position.x / width).coerceIn(0f, 1f)
        val x = if (isFrontCamera) 1f - rawX else rawX
        val y = (landmark.position.y / height).coerceIn(0f, 1f)
        return PointF(x, y)
    }

    companion object {
        private const val MIN_CONFIDENCE = 0.55f
        const val MAX_REP_INTERVAL_MS = 5000L // 每 5 秒至少完成 1 次动作
        private const val STANDING_INTERRUPT_GRACE_MS = 1000L // 持续站立中断容差 1.0 秒
        private const val BODY_LOST_RESET_MS = 1500L // 离开画面超时重置阈值
    }
}
