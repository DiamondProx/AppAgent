package com.pa.assistclient

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pa.assistclient.ui.theme.AssistClientTheme

class MainActivity : ComponentActivity() {
    private lateinit var deviceController: DeviceController
    private var refreshPermissionState: (() -> Unit)? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        deviceController = DeviceController(this)
        
        setContent {
            AssistClientTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        deviceController = deviceController,
                        onTestClick = {
                            // 检查无障碍权限
                            if (deviceController.isAccessibilityServiceEnabled()) {
                                startActivity(Intent(this@MainActivity, TestActivity::class.java))
                            } else {
                                // 显示权限请求对话框
                                showAccessibilityPermissionDialog()
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    deviceController: DeviceController,
    onTestClick: () -> Unit,
    onRefreshStateCallback: ((() -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var taskInput by remember { mutableStateOf("") }
    var showPermissionStatus by remember { mutableStateOf(false) }
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    
    // 刷新权限状态的函数
    val refreshState = {
        isAccessibilityEnabled = deviceController.isAccessibilityServiceEnabled()
    }
    
    // 初始化时检查权限状态
    LaunchedEffect(Unit) {
        showPermissionStatus = true
        refreshState()
        // 将刷新函数传递给父组件
        onRefreshStateCallback?.invoke(refreshState)
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "智能手机助手",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(vertical = 32.dp)
        )
        
        OutlinedTextField(
            value = taskInput,
            onValueChange = { taskInput = it },
            label = { Text("请输入任务描述") },
            placeholder = { Text("例如：打开设置，发送短信等") },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            maxLines = 3
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 权限状态显示
        if (showPermissionStatus) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAccessibilityEnabled) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isAccessibilityEnabled) "✓ 无障碍权限已启用" else "⚠ 无障碍权限未启用",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (!isAccessibilityEnabled) {
                        TextButton(
                            onClick = { deviceController.openAccessibilitySettings() }
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
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        Button(
            onClick = {
                // TODO: 实现任务执行逻辑
                // 这里可以调用后端API或本地任务执行器
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = "执行任务",
                style = MaterialTheme.typography.titleMedium
            )
        }
        
        OutlinedButton(
            onClick = onTestClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = "测试",
                style = MaterialTheme.typography.titleMedium
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
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
                    text = "• 执行任务：输入任务描述，AI将自动完成\n" +
                          "• 测试：进入手动控制界面，测试各种操作",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    AssistClientTheme {
        // 为预览创建一个模拟的DeviceController
        // 注意：在预览中这个会导致错误，但不影响实际运行
        MainScreen(
            deviceController = DeviceController(androidx.compose.ui.platform.LocalContext.current),
            onTestClick = {},
            onRefreshStateCallback = null
        )
    }
}