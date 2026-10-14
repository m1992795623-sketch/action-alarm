package com.example.actionalarm.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.actionalarm.data.AlarmScheduler
import com.example.actionalarm.model.ActionType
import com.example.actionalarm.model.DurationTier
import com.example.actionalarm.service.AlarmService
import com.example.actionalarm.vision.ChallengeUiState
import com.example.actionalarm.vision.PoseActionAnalyzer
import java.util.concurrent.Executors

/**
 * 闹钟响铃时的全屏视觉识别挑战页面。
 * 屏蔽返回键，必须完成指定时长（5s/15s/1min/5min）的连续指定动作后，才自动停止闹钟并退出。
 */
class AlarmChallengeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLockScreenWakeUp()

        // 禁用系统返回键，防止未做动作直接关闭闹钟
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 不允许返回退出，必须完成视觉动作挑战
            }
        })

        val actionName = intent.getStringExtra(AlarmScheduler.EXTRA_ACTION_TYPE) ?: ActionType.SQUAT.name
        val durationName = intent.getStringExtra(AlarmScheduler.EXTRA_DURATION_TIER) ?: DurationTier.SEC_15.name
        val alarmLabel = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_LABEL) ?: "起床闹钟"

        val actionType = ActionType.fromName(actionName)
        val durationTier = DurationTier.fromName(durationName)

        setContent {
            MaterialTheme {
                ChallengeScreen(
                    alarmLabel = alarmLabel,
                    actionType = actionType,
                    durationTier = durationTier,
                    onChallengeCompleted = {
                        AlarmService.stopService(this)
                        finish()
                    }
                )
            }
        }
    }

    private fun enableLockScreenWakeUp() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }
}

@Composable
fun ChallengeScreen(
    alarmLabel: String,
    actionType: ActionType,
    durationTier: DurationTier,
    onChallengeCompleted: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("需要相机权限以进行动作识别关闭闹钟", color = Color.White, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予相机权限")
                }
            }
        }
        return
    }

    var useFrontCamera by remember { mutableStateOf(true) }
    var uiState by remember {
        mutableStateOf(ChallengeUiState(actionType = actionType, durationTier = durationTier))
    }

    LaunchedEffect(uiState.isCompleted) {
        if (uiState.isCompleted) {
            onChallengeCompleted()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 底层相机预览与 ML Kit 实时骨骼分析
        CameraPreviewWithPoseAnalysis(
            actionType = actionType,
            durationTier = durationTier,
            useFrontCamera = useFrontCamera,
            onStateUpdated = { newState -> uiState = newState }
        )

        // 2. 中层人体骨骼关键点实时绘制层
        PoseSkeletonOverlay(uiState = uiState)

        // 3. 顶层状态面板与连续计时进度条
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部任务卡片
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xCC121212)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = alarmLabel,
                                color = Color(0xFFFFB74D),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "目标动作：${actionType.displayName}（连续 ${durationTier.label}）",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = { useFrontCamera = !useFrontCamera }) {
                            Icon(
                                imageVector = Icons.Default.Cameraswitch,
                                contentDescription = "切换前后摄像头",
                                tint = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = uiState.statusHint,
                        color = if (uiState.wasResetRecently) Color(0xFFFF5252) else Color(0xFF80CBC4),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 底部实时计时与 5秒防中断倒计时面板
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xCC121212)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val elapsedSec = uiState.elapsedMillis / 1000f
                    val targetSec = uiState.targetMillis / 1000f

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = String.format("连续达标时长：%.1fs / %.0fs", elapsedSec, targetSec),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (actionType.isDynamicRep) {
                            Text(
                                text = "已完成次数：${uiState.repCount} 次",
                                color = Color(0xFFFFD54F),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 总目标时长连续进度条
                    LinearProgressIndicator(
                        progress = { uiState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                        color = Color(0xFF4CAF50),
                        trackColor = Color(0xFF424242)
                    )

                    // 动态动作专属：每 5 秒至少完成 1 次的剩余时间窗口指示条
                    if (actionType.isDynamicRep) {
                        Spacer(modifier = Modifier.height(12.dp))
                        val windowSec = uiState.remainingRepWindowMillis / 1000f
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "5秒动作保活窗口（超时归零）",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                            Text(
                                text = String.format("剩余 %.1fs", windowSec),
                                color = if (windowSec < 1.8f) Color(0xFFFF5252) else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { uiState.repWindowProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = if (windowSec < 1.8f) Color(0xFFFF5252) else Color(0xFF29B6F6),
                            trackColor = Color(0xFF424242)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreviewWithPoseAnalysis(
    actionType: ActionType,
    durationTier: DurationTier,
    useFrontCamera: Boolean,
    onStateUpdated: (ChallengeUiState) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(actionType, durationTier, useFrontCamera) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analyzer = PoseActionAnalyzer(
                actionType = actionType,
                durationTier = durationTier,
                isFrontCamera = useFrontCamera,
                onStateUpdated = onStateUpdated
            )

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, analyzer)
                }

            val selector = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun PoseSkeletonOverlay(uiState: ChallengeUiState) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        uiState.normalizedLandmarks.forEach { (start, end) ->
            drawLine(
                color = Color(0xFF00E676),
                start = Offset(start.x * w, start.y * h),
                end = Offset(end.x * w, end.y * h),
                strokeWidth = 8f
            )
        }

        uiState.normalizedPoints.forEach { pt ->
            drawCircle(
                color = Color(0xFFFFEB3B),
                radius = 10f,
                center = Offset(pt.x * w, pt.y * h)
            )
        }
    }
}
