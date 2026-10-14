package com.example.actionalarm.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.actionalarm.data.AlarmRepository
import com.example.actionalarm.data.AlarmScheduler
import com.example.actionalarm.model.ActionType
import com.example.actionalarm.model.AlarmItem
import com.example.actionalarm.model.DurationTier
import java.util.Calendar

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = AlarmRepository(this)

        setContent {
            MaterialTheme {
                AlarmHomeScreen(repository = repository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmHomeScreen(repository: AlarmRepository) {
    val context = LocalContext.current
    var alarms by remember { mutableStateOf(repository.loadAlarms()) }
    var editingAlarm by remember { mutableStateOf<AlarmItem?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("动作唤醒闹钟", fontWeight = FontWeight.Bold)
                        Text(
                            "连续动作识别达标方可关闭（动态动作每5s至少1次）",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val now = Calendar.getInstance()
                    editingAlarm = AlarmItem(
                        id = (System.currentTimeMillis() % 100000).toInt(),
                        hour = now.get(Calendar.HOUR_OF_DAY),
                        minute = (now.get(Calendar.MINUTE) + 1) % 60,
                        label = "动作唤醒闹钟",
                        enabled = true,
                        actionType = ActionType.SQUAT,
                        durationTier = DurationTier.SEC_15
                    )
                    showAddDialog = true
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建闹钟")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            ExactAlarmPermissionBanner(context)

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(alarms, key = { it.id }) { alarm ->
                    AlarmCard(
                        alarm = alarm,
                        onToggle = { enabled ->
                            val updated = alarms.map {
                                if (it.id == alarm.id) it.copy(enabled = enabled) else it
                            }
                            alarms = updated
                            repository.saveAlarms(updated)
                            val target = updated.first { it.id == alarm.id }
                            if (enabled) {
                                AlarmScheduler.schedule(context, target)
                                Toast.makeText(
                                    context,
                                    "已开启闹钟：${target.formattedTime()}（${target.actionType.displayName} ${target.durationTier.label}）",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                AlarmScheduler.cancel(context, target.id)
                            }
                        },
                        onEdit = {
                            editingAlarm = alarm
                            showAddDialog = true
                        },
                        onDelete = {
                            AlarmScheduler.cancel(context, alarm.id)
                            val updated = alarms.filterNot { it.id == alarm.id }
                            alarms = updated
                            repository.saveAlarms(updated)
                        },
                        onTestChallenge = {
                            val testIntent = Intent(context, AlarmChallengeActivity::class.java).apply {
                                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarm.id)
                                putExtra(AlarmScheduler.EXTRA_ACTION_TYPE, alarm.actionType.name)
                                putExtra(AlarmScheduler.EXTRA_DURATION_TIER, alarm.durationTier.name)
                                putExtra(AlarmScheduler.EXTRA_ALARM_LABEL, alarm.label)
                            }
                            context.startActivity(testIntent)
                        }
                    )
                }
            }
        }

        if (showAddDialog && editingAlarm != null) {
            AlarmEditDialog(
                initialAlarm = editingAlarm!!,
                onDismiss = {
                    showAddDialog = false
                    editingAlarm = null
                },
                onConfirm = { savedAlarm ->
                    val exists = alarms.any { it.id == savedAlarm.id }
                    val updated = if (exists) {
                        alarms.map { if (it.id == savedAlarm.id) savedAlarm else it }
                    } else {
                        alarms + savedAlarm
                    }.sortedWith(compareBy({ it.hour }, { it.minute }))

                    alarms = updated
                    repository.saveAlarms(updated)
                    AlarmScheduler.schedule(context, savedAlarm)
                    showAddDialog = false
                    editingAlarm = null
                }
            )
        }
    }
}

@Composable
fun ExactAlarmPermissionBanner(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!alarmManager.canScheduleExactAlarms()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "请授予“精确闹钟”权限以确保准时响铃",
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp
                    )
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                        }
                    ) {
                        Text("去开启")
                    }
                }
            }
        }
    }
}

@Composable
fun AlarmCard(
    alarm: AlarmItem,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTestChallenge: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = alarm.formattedTime(),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = alarm.label,
                        fontSize = 14.sp,
                        color = Color.DarkGray
                    )
                }
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = onToggle
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "解锁动作：${alarm.actionType.displayName} · 连续 ${alarm.durationTier.label}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Row {
                    TextButton(onClick = onTestChallenge) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("演练动作")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "删除闹钟")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlarmEditDialog(
    initialAlarm: AlarmItem,
    onDismiss: () -> Unit,
    onConfirm: (AlarmItem) -> Unit
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialAlarm.hour,
        initialMinute = initialAlarm.minute,
        is24Hour = true
    )
    var label by remember { mutableStateOf(initialAlarm.label) }
    var selectedAction by remember { mutableStateOf(initialAlarm.actionType) }
    var selectedDuration by remember { mutableStateOf(initialAlarm.durationTier) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置动作闹钟", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TimePicker(state = timePickerState)

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("闹钟备注") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("选择关闭闹钟所需动作（4选1）：", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ActionType.entries.forEach { action ->
                        FilterChip(
                            selected = selectedAction == action,
                            onClick = { selectedAction = action },
                            label = { Text(action.displayName) }
                        )
                    }
                }

                Text("选择连续动作时长档位（4选1）：", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DurationTier.entries.forEach { tier ->
                        FilterChip(
                            selected = selectedDuration == tier,
                            onClick = { selectedDuration = tier },
                            label = { Text(tier.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        initialAlarm.copy(
                            hour = timePickerState.hour,
                            minute = timePickerState.minute,
                            label = label.ifBlank { "动作唤醒闹钟" },
                            enabled = true,
                            actionType = selectedAction,
                            durationTier = selectedDuration
                        )
                    )
                }
            ) {
                Text("保存并启用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
