package com.pa.assistclient

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
            }
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
                            val intent = deviceController.requestScreenshotPermission()
                            screenshotPermissionLauncher.launch(intent)
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
    var logText by remember { mutableStateOf("设备控制日志:\n") }
    var inputText by remember { mutableStateOf("") }
    var xCoordinate by remember { mutableStateOf("100") }
    var yCoordinate by remember { mutableStateOf("100") }
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var isServiceConnected by remember { mutableStateOf(false) }
    
    // 刷新权限状态的函数
    val refreshState = {
        val newEnabledState = deviceController.isAccessibilityServiceEnabled()
        val newConnectedState = deviceController.isAccessibilityServiceConnected()
        
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
    }
    
    // 检查权限状态
    LaunchedEffect(Unit) {
        isAccessibilityEnabled = deviceController.isAccessibilityServiceEnabled()
        isServiceConnected = deviceController.isAccessibilityServiceConnected()
        
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
        
        // 将刷新函数传递给父组件
        onRefreshStateCallback?.invoke(refreshState)
    }
    
    fun addLog(message: String) {
        logText += "${System.currentTimeMillis()}: $message\n"
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "设备控制测试",
            style = MaterialTheme.typography.headlineMedium
        )
        
        // 权限状态显示
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
        
        // 坐标输入
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
        
        // 文本输入
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = { Text("输入文本") },
            modifier = Modifier.fillMaxWidth()
        )
        
        // 控制按钮
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
                    deviceController.inputText(inputText)
                    addLog("输入文本: $inputText")
                },
                modifier = Modifier.weight(1f),
                enabled = isAccessibilityEnabled
            ) {
                Text("输入文本")
            }
        }
        
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
        
        Button(
            onClick = {
                // 先请求权限，然后截图
                onRequestScreenshotPermission()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("请求截图权限")
        }
        
        Button(
            onClick = {
                val success = deviceController.takeScreenshot()
                addLog("截图${if (success) "成功" else "失败"}")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("截图")
        }
        
        // 日志显示
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
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
}