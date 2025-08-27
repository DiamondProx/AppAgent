package com.pa.assistclient

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.pa.assistclient.ui.theme.AssistClientTheme

class TestActivity : ComponentActivity() {
    private lateinit var deviceController: DeviceController
    private var refreshPermissionState: (() -> Unit)? = null
    
    // 注册ActivityResult回调用于处理截图权限
    private val screenshotPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                deviceController.initMediaProjection(result.resultCode, data)
                // 权限获取成功后，添加日志并保持在当前页面
                Log.d("TestActivity", "截图权限获取成功")
                // 注意：由于权限请求可能导致Activity重建，我们需要在onResume中处理状态更新
            }
        } else {
            // 权限被拒绝
            Log.w("TestActivity", "截图权限被拒绝")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        deviceController = DeviceController(this)
        
        setContent {
            AssistClientTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TestScreen(
                        deviceController = deviceController,
                        onRequestScreenshotPermission = {
                            try {
                                val intent = deviceController.requestScreenshotPermission()
                                screenshotPermissionLauncher.launch(intent)
                            } catch (e: Exception) {
                                Log.e("TestActivity", "请求截图权限失败", e)
                            }
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
        refreshPermissionState?.invoke()
        
        // 检查截图权限是否已经获取
        // 由于MediaProjection的特殊性，我们通过DeviceController来检查状态
        Log.d("TestActivity", "页面恢复，检查权限状态")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        deviceController.cleanup()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(
    deviceController: DeviceController,
    onRequestScreenshotPermission: () -> Unit,
    onRefreshStateCallback: ((() -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var logText by remember { mutableStateOf("设备控制日志:\n") }
    var inputText by remember { mutableStateOf("") }
    var xCoordinate by remember { mutableStateOf("100") }
    var yCoordinate by remember { mutableStateOf("100") }
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var isServiceConnected by remember { mutableStateOf(false) }
    
    // 滑动参数状态变量
    var startX by remember { mutableStateOf("200") }
    var startY by remember { mutableStateOf("800") }
    var endX by remember { mutableStateOf("200") }
    var endY by remember { mutableStateOf("400") }
    
    // 截图权限状态
    var isScreenshotPermissionGranted by remember { mutableStateOf(false) }
    
    // 刷新权限状态的函数
    val refreshState = {
        val newEnabledState = deviceController.isAccessibilityServiceEnabled()
        val newConnectedState = deviceController.isAccessibilityServiceConnected()
        val newScreenshotState = deviceController.isScreenshotPermissionGranted()
        
        if (newEnabledState != isAccessibilityEnabled) {
            isAccessibilityEnabled = newEnabledState
            if (newEnabledState) {
                logText += "✓ 无障碍权限已启用\n"
            } else {
                logText += "⚠ 无障碍权限已禁用\n"
            }
        }
        
        if (newConnectedState != isServiceConnected) {
            isServiceConnected = newConnectedState
            if (newConnectedState) {
                logText += "✓ 无障碍服务已连接\n"
            } else {
                logText += "⚠ 无障碍服务未连接（请稍后再试）\n"
            }
        }
        
        if (newScreenshotState != isScreenshotPermissionGranted) {
            isScreenshotPermissionGranted = newScreenshotState
            if (newScreenshotState) {
                logText += "✓ 录屏服务已启动\n"
            } else {
                logText += "⚠ 录屏服务已停止\n"
            }
        }
    }
    
    // 定期检查服务状态
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000) // 每2秒检查一次
            refreshState()
        }
    }
    
    // 检查权限状态
    LaunchedEffect(Unit) {
        isAccessibilityEnabled = deviceController.isAccessibilityServiceEnabled()
        isServiceConnected = deviceController.isAccessibilityServiceConnected()
        isScreenshotPermissionGranted = deviceController.isScreenshotPermissionGranted()
        
        if (!isAccessibilityEnabled) {
            logText += "⚠ 警告：无障碍服务未启用，请先开启权限\n"
        } else {
            logText += "✓ 无障碍权限已启用\n"
            if (isServiceConnected) {
                logText += "✓ 无障碍服务已连接\n"
            } else {
                logText += "⚠ 无障碍服务正在连接中，请稍后再试...\n"
            }
        }
        
        if (isScreenshotPermissionGranted) {
            logText += "✓ 截图权限已获取\n"
        } else {
            logText += "⚠ 截图权限未获取，请点击申请按钮\n"
        }
        
        // 将刷新函数传递给父组件
        onRefreshStateCallback?.invoke(refreshState)
    }
    
    fun addLog(message: String) {
        logText += "${System.currentTimeMillis()}: $message\n"
    }
    
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "设备控制测试",
                style = MaterialTheme.typography.headlineMedium
            )
        }
        
        // 权限状态显示
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        !isAccessibilityEnabled -> MaterialTheme.colorScheme.errorContainer
                        !isServiceConnected -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when {
                                !isAccessibilityEnabled -> "⚠ 无障碍权限未启用"
                                !isServiceConnected -> "⏳ 权限已启用，服务连接中..."
                                else -> "✓ 无障碍服务已就绪"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        
                        if (!isAccessibilityEnabled) {
                            TextButton(
                                onClick = { 
                                    deviceController.openAccessibilitySettings()
                                    addLog("打开无障碍设置页面")
                                }
                            ) {
                                Text("去设置")
                            }
                        } else {
                            TextButton(
                                onClick = { 
                                    refreshState()
                                    addLog("刷新权限状态")
                                }
                            ) {
                                Text("刷新")
                            }
                        }
                    }
                    
                    // 显示详细状态
                    if (isAccessibilityEnabled) {
                        Text(
                            text = "服务连接状态: ${if (isServiceConnected) "已连接" else "未连接"}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
        
        // 坐标输入
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = xCoordinate,
                    onValueChange = { xCoordinate = it },
                    label = { Text("X坐标") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = yCoordinate,
                    onValueChange = { yCoordinate = it },
                    label = { Text("Y坐标") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        
        // 文本输入
        item {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("输入文本") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // 控制按钮
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (!isAccessibilityEnabled) {
                            addLog("无法执行返回操作：无障碍服务未启用")
                            return@Button
                        }
                        if (!isServiceConnected) {
                            addLog("无法执行返回操作：服务未连接，请稍后再试")
                            return@Button
                        }
                        deviceController.back()
                        addLog("执行返回操作")
                    },
                    modifier = Modifier.weight(1f),
                    enabled = isAccessibilityEnabled && isServiceConnected
                ) {
                    Text("返回")
                }
                
                Button(
                    onClick = {
                        if (!isAccessibilityEnabled) {
                            addLog("无法执行点击操作：无障碍服务未启用")
                            return@Button
                        }
                        if (!isServiceConnected) {
                            addLog("无法执行点击操作：服务未连接，请稍后再试")
                            return@Button
                        }
                        val x = xCoordinate.toIntOrNull() ?: 100
                        val y = yCoordinate.toIntOrNull() ?: 100
                        deviceController.tap(x, y)
                        addLog("点击坐标: ($x, $y)")
                    },
                    modifier = Modifier.weight(1f),
                    enabled = isAccessibilityEnabled && isServiceConnected
                ) {
                    Text("点击")
                }
            }
        }
        
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (!isAccessibilityEnabled) {
                            addLog("无法执行长按操作：无障碍服务未启用")
                            return@Button
                        }
                        val x = xCoordinate.toIntOrNull() ?: 100
                        val y = yCoordinate.toIntOrNull() ?: 100
                        deviceController.longPress(x, y)
                        addLog("长按坐标: ($x, $y)")
                    },
                    modifier = Modifier.weight(1f),
                    enabled = isAccessibilityEnabled
                ) {
                    Text("长按")
                }
                
                Button(
                    onClick = {
                        if (!isAccessibilityEnabled) {
                            addLog("无法执行文本输入：无障碍服务未启用")
                            return@Button
                        }
//                    deviceController.inputText(inputText)
                        deviceController.inputText("123")
                        addLog("输入文本: $inputText")
                    },
                    modifier = Modifier.weight(1f),
                    enabled = isAccessibilityEnabled
                ) {
                    Text("输入文本")
                }
            }
        }
        
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (!isAccessibilityEnabled) {
                            addLog("无法执行滑动操作：无障碍服务未启用")
                            return@Button
                        }
                        deviceController.swipe(100, 100, 500, 500)
                        addLog("滑动从(100,100)到(500,500)")
                    },
                    modifier = Modifier.weight(1f),
                    enabled = isAccessibilityEnabled
                ) {
                    Text("滑动")
                }
                
                Button(
                    onClick = {
                        if (!isAccessibilityEnabled) {
                            addLog("无法获取UI结构：无障碍服务未启用")
                            return@Button
                        }
                        val xml = deviceController.dumpUIXml()
                        addLog("获取UI结构: ${xml.take(100)}...")
                    },
                    modifier = Modifier.weight(1f),
                    enabled = isAccessibilityEnabled
                ) {
                    Text("获取UI")
                }
            }
        }
        
        item {
            Button(
                onClick = {
                    val (width, height) = deviceController.getDeviceSize()
                    val (realWidth, realHeight) = deviceController.getRealDeviceSize()
                    val density = deviceController.getDeviceDensity()
                    val dpi = deviceController.getDeviceDpi()
                    
                    addLog("屏幕尺寸: ${width}x${height}")
                    addLog("真实尺寸: ${realWidth}x${realHeight}")
                    addLog("屏幕密度: $density")
                    addLog("屏幕DPI: $dpi")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("获取屏幕信息")
            }
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isScreenshotPermissionGranted) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "截图权限管理",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isScreenshotPermissionGranted) 
                                "✓ 截图权限已授权" 
                            else 
                                "⚠ 截图权限未授权",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        
                        Button(
                            onClick = {
                                if (isScreenshotPermissionGranted) {
                                    // 停止服务
                                    try {
                                        val stopIntent = Intent(context, MediaProjectionService::class.java).apply {
                                            action = MediaProjectionService.ACTION_STOP_PROJECTION
                                        }
                                        context.startService(stopIntent)
                                        addLog("正在停止录屏服务...")
                                    } catch (e: Exception) {
                                        addLog("停止服务失败: ${e.message}")
                                    }
                                } else {
                                    // 申请权限
                                    try {
                                        onRequestScreenshotPermission()
                                        addLog("正在请求截图权限...")
                                    } catch (e: Exception) {
                                        addLog("请求截图权限失败: ${e.message}")
                                    }
                                }
                            }
                        ) {
                            Text(if (isScreenshotPermissionGranted) "停止服务" else "申请权限")
                        }
                    }
                    
                    Text(
                        text = "注意：授权后可能会暂时回到桌面，这是正常现象。请重新打开应用继续使用。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        
        item {
            Button(
                onClick = {
                    if (isScreenshotPermissionGranted) {
                        val success = deviceController.takeScreenshot()
                        addLog("截图${if (success) "成功" else "失败"}")
                    } else {
                        addLog("请先申请截图权限")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = isScreenshotPermissionGranted
            ) {
                Text("执行截图")
            }
        }
        
        // 滑动测试区域
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "滑动参数配置",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startX,
                            onValueChange = { startX = it },
                            label = { Text("起始X") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = startY,
                            onValueChange = { startY = it },
                            label = { Text("起始Y") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = endX,
                            onValueChange = { endX = it },
                            label = { Text("结束X") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endY,
                            onValueChange = { endY = it },
                            label = { Text("结束Y") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!isAccessibilityEnabled) {
                                    addLog("无法执行向上滑动：无障碍服务未启用")
                                    return@Button
                                }
                                deviceController.swipe(200, 800, 200, 400) // 向上滑动
                                addLog("向上滑动：从(200,800)到(200,400)")
                            },
                            modifier = Modifier.weight(1f),
                            enabled = isAccessibilityEnabled
                        ) {
                            Text("向上滑动")
                        }
                        
                        Button(
                            onClick = {
                                if (!isAccessibilityEnabled) {
                                    addLog("无法执行向下滑动：无障碍服务未启用")
                                    return@Button
                                }
                                deviceController.swipe(200, 400, 200, 800) // 向下滑动
                                addLog("向下滑动：从(200,400)到(200,800)")
                            },
                            modifier = Modifier.weight(1f),
                            enabled = isAccessibilityEnabled
                        ) {
                            Text("向下滑动")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Button(
                        onClick = {
                            if (!isAccessibilityEnabled) {
                                addLog("无法执行自定义滑动：无障碍服务未启用")
                                return@Button
                            }
                            val sx = startX.toIntOrNull() ?: 200
                            val sy = startY.toIntOrNull() ?: 800
                            val ex = endX.toIntOrNull() ?: 200
                            val ey = endY.toIntOrNull() ?: 400
                            deviceController.swipe(sx, sy, ex, ey)
                            addLog("自定义滑动：从($sx,$sy)到($ex,$ey)")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isAccessibilityEnabled
                    ) {
                        Text("执行自定义滑动")
                    }
                }
            }
        }
        
        // 调试说明
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "测试说明",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• 上下滑动本页面来测试滑动功能\n" +
                                "• 确保无障碍服务已启用并连接\n" +
                                "• 滑动坐标基于屏幕像素位置\n" +
                                "• 可以通过调整坐标来控制滑动方向和距离\n" +
                                "• 建议在空白区域测试滑动功能",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        
        // 额外的测试项目
        repeat(5) { index ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "测试项目 ${index + 1}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "这是第${index + 1}个测试项目，用于增加页面高度。\n" +
                                    "您可以上下滑动来测试滑动功能。\n" +
                                    "滑动测试对于验证设备控制功能非常重要。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                addLog("点击了测试项目 ${index + 1}")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("测试按钮 ${index + 1}")
                        }
                    }
                }
            }
        }
        
        // 日志显示
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                Text(
                    text = logText,
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        
        // 底部占位空间
        item {
            Spacer(modifier = Modifier.height(200.dp))
        }
    }
}