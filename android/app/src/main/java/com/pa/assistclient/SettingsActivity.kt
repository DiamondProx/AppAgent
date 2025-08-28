package com.pa.assistclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pa.assistclient.ui.theme.AssistClientTheme

class SettingsActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            AssistClientTheme {
                SettingsScreen(
                    onBackClick = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit
) {
    val configManager = ConfigManager.getInstance(androidx.compose.ui.platform.LocalContext.current)
    var config by remember { mutableStateOf(configManager.getConfig()) }
    var showApiKey by remember { mutableStateOf(false) }
    var hasChanges by remember { mutableStateOf(false) }
    
    // 监听配置变化
    LaunchedEffect(config) {
        val originalConfig = configManager.getConfig()
        hasChanges = config != originalConfig
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (hasChanges) {
                        TextButton(
                            onClick = {
                                configManager.saveConfig(config)
                                hasChanges = false
                            }
                        ) {
                            Text("保存")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // 模型选择
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "模型配置",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text("选择AI模型")
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = config.model == "OpenAI",
                                onClick = { config = config.copy(model = "OpenAI") }
                            )
                            Text("OpenAI")
                            Spacer(modifier = Modifier.width(16.dp))
                            
                            RadioButton(
                                selected = config.model == "Qwen",
                                onClick = { config = config.copy(model = "Qwen") }
                            )
                            Text("通义千问")
                        }
                    }
                }
            }
            
            // OpenAI 配置
            if (config.model == "OpenAI") {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "OpenAI 配置",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = config.openaiApiKey,
                                onValueChange = { config = config.copy(openaiApiKey = it) },
                                label = { Text("API Key") },
                                placeholder = { Text("sk-...") },
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    TextButton(
                                        onClick = { showApiKey = !showApiKey }
                                    ) {
                                        Text(if (showApiKey) "隐藏" else "显示")
                                    }
                                }
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = config.openaiApiBase,
                                onValueChange = { config = config.copy(openaiApiBase = it) },
                                label = { Text("API Base URL") },
                                placeholder = { Text("https://api.openai.com/v1/chat/completions") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = config.openaiApiModel,
                                onValueChange = { config = config.copy(openaiApiModel = it) },
                                label = { Text("模型名称") },
                                placeholder = { Text("gpt-4-vision-preview") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            
            // 通义千问配置
            if (config.model == "Qwen") {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "通义千问配置",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = config.dashscopeApiKey,
                                onValueChange = { config = config.copy(dashscopeApiKey = it) },
                                label = { Text("DashScope API Key") },
                                placeholder = { Text("sk-...") },
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation()
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = config.qwenModel,
                                onValueChange = { config = config.copy(qwenModel = it) },
                                label = { Text("模型名称") },
                                placeholder = { Text("qwen-vl-max") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            
            // 通用参数配置
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "任务执行参数",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = config.maxTokens.toString(),
                            onValueChange = { 
                                it.toIntOrNull()?.let { tokens ->
                                    config = config.copy(maxTokens = tokens)
                                }
                            },
                            label = { Text("最大Token数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = config.temperature.toString(),
                            onValueChange = { 
                                it.toFloatOrNull()?.let { temp ->
                                    config = config.copy(temperature = temp)
                                }
                            },
                            label = { Text("温度参数 (0.0-1.0)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = config.requestInterval.toString(),
                            onValueChange = { 
                                it.toIntOrNull()?.let { interval ->
                                    config = config.copy(requestInterval = interval)
                                }
                            },
                            label = { Text("请求间隔 (秒)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = config.maxRounds.toString(),
                            onValueChange = { 
                                it.toIntOrNull()?.let { rounds ->
                                    config = config.copy(maxRounds = rounds)
                                }
                            },
                            label = { Text("最大执行轮数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = config.minDist.toString(),
                            onValueChange = { 
                                it.toIntOrNull()?.let { dist ->
                                    config = config.copy(minDist = dist)
                                }
                            },
                            label = { Text("最小元素距离") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = config.darkMode,
                                onCheckedChange = { config = config.copy(darkMode = it) }
                            )
                            Text("深色模式标注")
                        }
                    }
                }
            }
            
            // 操作按钮
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "操作",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Button(
                            onClick = {
                                config = TaskConfig()
                                configManager.resetToDefault()
                                hasChanges = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("重置为默认配置")
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedButton(
                            onClick = {
                                // TODO: 测试API连接
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("测试API连接")
                        }
                    }
                }
            }
            
            // 说明文档
            item {
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
                            text = "配置说明",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = """
                            • API Key: 从服务商获取的API密钥
                            • 温度参数: 控制模型输出的随机性，0.0最确定，1.0最随机
                            • 请求间隔: 两次API调用之间的等待时间，避免频率限制
                            • 最大执行轮数: 任务最多执行多少轮操作
                            • 最小元素距离: 标注时过滤重叠元素的距离阈值
                            • 深色模式标注: 针对深色界面优化标注颜色
                            """.trimIndent(),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}