package com.pa.assistclient

import android.content.Intent
import android.os.Bundle
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.pa.assistclient.ui.theme.AssistClientTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var deviceController: DeviceController
    private lateinit var taskExecutor: TaskExecutor
    private var refreshPermissionState: (() -> Unit)? = null
    
    // 注册ActivityResult回调用于处理截图权限
    private val screenshotPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                deviceController.initMediaProjection(result.resultCode, data)
                // 权限获取成功后，延迟刷新状态
                lifecycleScope.launch {
                    // 给服务时间初始化
                    delay(1000)
                    refreshPermissionState?.invoke()
                    // 再次检查以确保状态更新
                    delay(1000)
                    refreshPermissionState?.invoke()
                }
            }
        } else {
            // 权限被拒绝，显示提示
            showScreenshotPermissionDeniedDialog()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        deviceController = DeviceController(this)
        taskExecutor = TaskExecutor(this, deviceController)
        
        setContent {
            AssistClientTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        deviceController = deviceController,
                        taskExecutor = taskExecutor,
                        onTestClick = {
                            // 检查无障碍权限
                            if (deviceController.isAccessibilityServiceEnabled()) {
                                startActivity(Intent(this@MainActivity, TestActivity::class.java))
                            } else {
                                // 显示权限请求对话框
                                showAccessibilityPermissionDialog()
                            }
                        },
                        onSettingsClick = {
                            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                        },
                        onRequestScreenshotPermission = {
                            requestScreenshotPermission()
                        },
                        onRefreshStateCallback = { callback ->
                            refreshPermissionState = callback
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 从设置页面返回时刷新权限状态
        lifecycleScope.launch {
            // 延迟一下，确保服务有时间连接
            delay(300)
            refreshPermissionState?.invoke()
        }
    }
    
    private fun showAccessibilityPermissionDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("需要无障碍权限")
            .setMessage("为了实现设备控制功能，需要开启无障碍服务权限。\n\n请在设置中找到 'AssistClient'，然后开启服务。")
            .setPositiveButton("去设置") { _, _ ->
                deviceController.openAccessibilitySettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 申请截图权限
     */
    private fun requestScreenshotPermission() {
        try {
            val intent = deviceController.requestScreenshotPermission()
            screenshotPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            android.app.AlertDialog.Builder(this)
                .setTitle("权限申请失败")
                .setMessage("无法申请截图权限：${e.message}")
                .setPositiveButton("确定", null)
                .show()
        }
    }
    
    /**
     * 截图权限被拒绝的处理
     */
    private fun showScreenshotPermissionDeniedDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("权限被拒绝")
            .setMessage("截图权限被拒绝，无法执行任务。\n\n请再次点击申请按钮或手动开启权限。")
            .setPositiveButton("重新申请") { _, _ ->
                requestScreenshotPermission()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    deviceController: DeviceController,
    taskExecutor: TaskExecutor,
    onTestClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRequestScreenshotPermission: () -> Unit,
    onRefreshStateCallback: ((() -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var taskInput by remember { mutableStateOf("") }
    var showPermissionStatus by remember { mutableStateOf(false) }
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var isScreenshotPermissionGranted by remember { mutableStateOf(false) }
    var isExecutingTask by remember { mutableStateOf(false) }
    var taskLogs by remember { mutableStateOf(listOf<String>()) }
    var taskProgress by remember { mutableStateOf("") }
    
    // 刷新权限状态的函数
    val refreshState = {
        isAccessibilityEnabled = deviceController.isAccessibilityServiceEnabled()
        // 异步检查截图权限状态，增加重试机制
        (context as ComponentActivity).lifecycleScope.launch {
            var retryCount = 0
            val maxRetries = 3
            while (retryCount < maxRetries) {
                val permissionGranted = deviceController.isScreenshotPermissionGranted()
                if (permissionGranted) {
                    isScreenshotPermissionGranted = true
                    break
                } else if (retryCount < maxRetries - 1) {
                    // 等待服务连接，然后重试
                    delay(500)
                    retryCount++
                } else {
                    isScreenshotPermissionGranted = false
                    break
                }
            }
        }
        Unit // 显式返回Unit
    }
    
    // 初始化时检查权限状态
    LaunchedEffect(Unit) {
        showPermissionStatus = true
        refreshState()
        // 将刷新函数传递给父组件
        onRefreshStateCallback?.invoke(refreshState)
        
        // 定期检查服务连接状态（每2秒检查一次）
        while (true) {
            delay(2000)
            // 只在权限未获取时才检查，避免不必要的检查
            if (!isScreenshotPermissionGranted) {
                val currentPermissionState = deviceController.isScreenshotPermissionGranted()
                if (currentPermissionState != isScreenshotPermissionGranted) {
                    isScreenshotPermissionGranted = currentPermissionState
                }
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("智能手机助手") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 任务输入区域
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "任务描述",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = taskInput,
                        onValueChange = { taskInput = it },
                        label = { Text("请输入任务描述") },
                        placeholder = { Text("例如：打开设置，发送短信等") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        maxLines = 3,
                        enabled = !isExecutingTask
                    )
                }
            }
            
            // 权限状态显示
            if (showPermissionStatus) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAccessibilityEnabled && isScreenshotPermissionGranted) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "权限状态",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (isAccessibilityEnabled) "✓ 无障碍权限已启用" 
                                    else "⚠ 无障碍权限未启用",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = if (isScreenshotPermissionGranted) "✓ 截图权限已获取" 
                                    else "⚠ 截图权限未获取",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            
                            if (!isAccessibilityEnabled || !isScreenshotPermissionGranted) {
                                TextButton(
                                    onClick = {
                                        if (!isAccessibilityEnabled) {
                                            deviceController.openAccessibilitySettings()
                                        } else if (!isScreenshotPermissionGranted) {
                                            // 在当前页面申请截图权限
                                            onRequestScreenshotPermission()
                                        }
                                    }
                                ) {
                                    Text("去设置")
                                }
                            } else {
                                TextButton(
                                    onClick = { refreshState() }
                                ) {
                                    Text("刷新")
                                }
                            }
                        }
                    }
                }
            }
            
            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 执行/终止任务按钮
                Button(
                    onClick = {
                        if (isExecutingTask) {
                            // 终止任务
                            taskExecutor.cancelTask()
                            isExecutingTask = false
                            taskProgress = "任务已终止"
                            taskLogs = taskLogs + "任务已被用户终止"
                        } else {
                            // 开始执行任务 - 检查任务描述
                            if (taskInput.isBlank()) {
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("任务描述为空")
                                    .setMessage("请先输入要执行的任务描述，例如：\n• 打开设置\n• 发送短信给联系人\n• 查看天气预报")
                                    .setPositiveButton("确定", null)
                                    .show()
                                return@Button
                            }
                            
                            // 检查无障碍权限
                            if (!isAccessibilityEnabled) {
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("需要无障碍权限")
                                    .setMessage("执行任务需要无障碍服务权限来控制设备。\n\n请点击下方按钮前往设置页面开启权限。")
                                    .setPositiveButton("去设置") { _, _ ->
                                        deviceController.openAccessibilitySettings()
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                                return@Button
                            }
                            
                            // 检查截图权限
                            if (!isScreenshotPermissionGranted) {
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("需要截图权限")
                                    .setMessage("执行任务需要截图权限来获取屏幕内容。\n\n请点击下方按钮申请权限。")
                                    .setPositiveButton("申请权限") { _, _ ->
                                        onRequestScreenshotPermission()
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                                return@Button
                            }
                            
                            // 检查API配置
                            val configManager = ConfigManager.getInstance(context)
                            val config = configManager.getConfig()
                            if ((config.model == "OpenAI" && config.openaiApiKey.isBlank()) || 
                                (config.model == "Qwen" && config.dashscopeApiKey.isBlank())) {
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("需要配置API")
                                    .setMessage("执行任务需要配置AI模型的API密钥。\n\n请点击下方按钮前往设置页面配置。")
                                    .setPositiveButton("去设置") { _, _ ->
                                        onSettingsClick()
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                                return@Button
                            }
                            
                            // 所有检查通过，显示确认弹框
                            android.app.AlertDialog.Builder(context)
                                .setTitle("开始执行AI任务")
                                .setMessage("即将执行任务：\n$taskInput\n\n执行过程中应用将返回后台，AI会自动控制您的设备完成任务。\n\n确认开始执行吗？")
                                .setPositiveButton("确认执行") { _, _ ->
                                    // 用户确认，开始执行任务
                                    isExecutingTask = true
                                    taskLogs = emptyList()
                                    taskProgress = "准备执行任务..."
                                    taskExecutor.resetTaskState() // 重置任务状态
                                    
                                    // 在协程中执行任务
                                    (context as ComponentActivity).lifecycleScope.launch {
                                        try {
                                            // 首先返回桌面，让应用退到后台
                                            taskProgress = "返回桌面，准备开始执行..."
                                            taskLogs = taskLogs + taskProgress
                                            
                                            // 返回桌面
                                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                                addCategory(Intent.CATEGORY_HOME)
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(homeIntent)
                                            
                                            // 等待返回桌面完成
                                            delay(2000)
                                            
                                            taskProgress = "开始执行任务: $taskInput"
                                            taskLogs = taskLogs + taskProgress
                                            
                                            val result = taskExecutor.executeTask(
                                                taskDescription = taskInput,
                                                onProgress = { progress ->
                                                    taskProgress = progress
                                                    taskLogs = taskLogs + progress
                                                },
                                                onActionResult = { actionResult ->
                                                    val logEntry = "动作: ${actionResult.action}\n" +
                                                            "观察: ${actionResult.observation}\n" +
                                                            "思考: ${actionResult.thought}\n" +
                                                            "总结: ${actionResult.summary}"
                                                    taskLogs = taskLogs + logEntry
                                                }
                                            )
                                            
                                            taskProgress = if (result.success) {
                                                if (result.completed) "任务已成功完成!" else result.message
                                            } else {
                                                "任务执行失败: ${result.message}"
                                            }
                                            taskLogs = taskLogs + taskProgress
                                            
                                            // 任务执行完成后，可以选择返回应用
                                            if (result.success && result.completed) {
                                                taskLogs = taskLogs + "任务执行完成，可以返回应用查看结果"
                                            }
                                            
                                        } catch (e: Exception) {
                                            taskProgress = "任务执行出现异常: ${e.message}"
                                            taskLogs = taskLogs + taskProgress
                                        } finally {
                                            isExecutingTask = false
                                        }
                                    }
                                }
                                .setNegativeButton("取消") { _, _ ->
                                    // 用户取消，不执行任务
                                    // 弹框会自动隐藏，不需要额外操作
                                }
                                .setCancelable(false) // 防止误触取消
                                .show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isExecutingTask) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                ) {
                    if (isExecutingTask && !taskProgress.contains("终止")) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (isExecutingTask) "终止任务" else when {
                            taskInput.isBlank() -> "请输入任务"
                            !isAccessibilityEnabled -> "需要无障碍权限"
                            !isScreenshotPermissionGranted -> "需要截图权限"
                            else -> "执行任务"
                        },
                        color = if (isExecutingTask) MaterialTheme.colorScheme.onError
                        else MaterialTheme.colorScheme.onPrimary
                    )
                }
                
                OutlinedButton(
                    onClick = onTestClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("测试")
                }
            }
            
            // 任务执行日志
            if (taskLogs.isNotEmpty() || isExecutingTask) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "执行日志",
                                style = MaterialTheme.typography.titleMedium
                            )
                            
                            if (!isExecutingTask && taskLogs.isNotEmpty()) {
                                TextButton(
                                    onClick = { taskLogs = emptyList() }
                                ) {
                                    Text("清空")
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        if (isExecutingTask) {
                            Text(
                                text = taskProgress,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(taskLogs) { log ->
                                Text(
                                    text = log,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Divider()
                            }
                        }
                    }
                }
            } else {
                // 使用说明
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "使用说明",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "• 输入任务描述，AI将自动完成操作\n" +
                                    "• 需要先启用无障碍权限和截图权限\n" +
                                    "• 点击设置配置API密钥\n" +
                                    "• 测试页面可手动控制设备",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    AssistClientTheme {
        // 为预览创建一个模拟的DeviceController和TaskExecutor
        // 注意：在预览中这个会导致错误，但不影响实际运行
        val context = androidx.compose.ui.platform.LocalContext.current
        val deviceController = DeviceController(context)
        val taskExecutor = TaskExecutor(context, deviceController)
        
        MainScreen(
            deviceController = deviceController,
            taskExecutor = taskExecutor,
            onTestClick = {},
            onSettingsClick = {},
            onRequestScreenshotPermission = {},
            onRefreshStateCallback = null
        )
    }
}