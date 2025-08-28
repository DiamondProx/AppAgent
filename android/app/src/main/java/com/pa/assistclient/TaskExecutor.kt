package com.pa.assistclient

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap

/**
 * 任务执行器类
 * 对应Python版本的task_executor.py功能
 */
class TaskExecutor(private val context: Context, private val deviceController: DeviceController) {
    
    companion object {
        private const val TAG = "TaskExecutor"
    }
    
    private val configManager = ConfigManager.getInstance(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    // 任务终止标志
    @Volatile
    private var isTaskCancelled = false
    
    /**
     * 终止当前正在执行的任务
     */
    fun cancelTask() {
        isTaskCancelled = true
        Log.d(TAG, "任务已被用户终止")
    }
    
    /**
     * 重置任务状态
     */
    fun resetTaskState() {
        isTaskCancelled = false
        Log.d(TAG, "任务状态已重置")
    }
    
    data class TaskResult(
        val success: Boolean,
        val message: String,
        val completed: Boolean = false,
        val error: String? = null
    )
    
    data class ActionResult(
        val action: String,
        val params: Map<String, Any>,
        val observation: String,
        val thought: String,
        val summary: String
    )
    
    /**
     * 执行任务的主要方法
     */
    suspend fun executeTask(
        taskDescription: String,
        onProgress: (String) -> Unit = {},
        onActionResult: (ActionResult) -> Unit = {}
    ): TaskResult = withContext(Dispatchers.IO) {
        
        val config = configManager.getConfig()
        
        // 检查必要的配置
        if (config.openaiApiKey.isEmpty() && config.dashscopeApiKey.isEmpty()) {
            return@withContext TaskResult(
                success = false,
                message = "请先在设置中配置API密钥"
            )
        }
        
        // 检查权限
        if (!deviceController.isAccessibilityServiceEnabled() || !deviceController.isAccessibilityServiceConnected()) {
            return@withContext TaskResult(
                success = false,
                message = "无障碍服务未启用或未连接"
            )
        }
        
        if (!deviceController.isScreenshotPermissionGranted()) {
            return@withContext TaskResult(
                success = false,
                message = "录屏权限未获取，请先申请权限"
            )
        }
        
        var roundCount = 0
        var lastAction = "None"
        var taskComplete = false
        
        onProgress("开始执行任务: $taskDescription")
        
        // 重置任务状态
        resetTaskState()
        
        try {
            while (roundCount < config.maxRounds && !taskComplete && !isTaskCancelled) {
                roundCount++
                onProgress("第 $roundCount 轮操作...")
                
                // 检查是否被终止
                if (isTaskCancelled) {
                    onProgress("任务已被用户终止")
                    return@withContext TaskResult(
                        success = false,
                        message = "任务已被用户终止"
                    )
                }
                // 1. 获取截图
                if (isTaskCancelled) {
                    onProgress("任务已被用户终止")
                    return@withContext TaskResult(
                        success = false,
                        message = "任务已被用户终止"
                    )
                }
                
                val success = deviceController.takeScreenshot()
                if (!success) {
                    return@withContext TaskResult(
                        success = false,
                        message = "获取截图失败1"
                    )
                }
                
                // 等待截图保存完成，期间检查中断状态
                var waitTime = 0L
                val maxWaitTime = 1000L
                val screenshotCheckInterval = 100L
                while (waitTime < maxWaitTime && !isTaskCancelled) {
                    delay(screenshotCheckInterval)
                    waitTime += screenshotCheckInterval
                }
                
                if (isTaskCancelled) {
                    onProgress("任务已被用户终止")
                    return@withContext TaskResult(
                        success = false,
                        message = "任务已被用户终止"
                    )
                }
                
                // 2. 获取带标注的截图和UI元素
                val (annotatedImagePath, elemList) = deviceController.getAnnotatedScreenshot(
                    saveDir = "",
                    prefix = "task_round_${roundCount}_${System.currentTimeMillis()}",
                    uselessList = emptySet(),
                    minDistance = config.minDist.toDouble(),
                    darkMode = config.darkMode
                )
                
                if (annotatedImagePath == null || elemList.isEmpty()) {
                    onProgress("警告: 未能获取UI元素，尝试继续...")
                    delay(config.requestInterval * 1000L)
                    continue
                }
                
                onProgress("找到 ${elemList.size} 个可交互元素")
                
                // 3. 构建提示词
                val prompt = buildTaskPrompt(taskDescription, lastAction, elemList)
                
                // 4. 调用大模型获取决策
                onProgress("正在思考下一步操作...")
                
                if (isTaskCancelled) {
                    onProgress("任务已被用户终止")
                    return@withContext TaskResult(
                        success = false,
                        message = "任务已被用户终止"
                    )
                }
                
                val imageBase64 = convertImageToBase64(annotatedImagePath)
                if (imageBase64 == null) {
                    onProgress("图片转换失败，跳过本轮")
                    continue
                }
                
                if (isTaskCancelled) {
                    onProgress("任务已被用户终止")
                    return@withContext TaskResult(
                        success = false,
                        message = "任务已被用户终止"
                    )
                }
                
                val response = callLLMAPI(prompt, imageBase64, config)
                if (response == null) {
                    return@withContext TaskResult(
                        success = false,
                        message = "API调用失败"
                    )
                }
                
                // 5. 解析响应并执行动作
                if (isTaskCancelled) {
                    onProgress("任务已被用户终止")
                    return@withContext TaskResult(
                        success = false,
                        message = "任务已被用户终止"
                    )
                }
                
                val actionResult = parseAndExecuteAction(response, elemList)
                onActionResult(actionResult)
                
                when (actionResult.action.uppercase()) {
                    "FINISH" -> {
                        taskComplete = true
                        onProgress("任务完成!")
                        break
                    }
                    "CANCELLED" -> {
                        onProgress("任务已被用户终止")
                        return@withContext TaskResult(
                            success = false,
                            message = "任务已被用户终止"
                        )
                    }
                    "ERROR" -> {
                        return@withContext TaskResult(
                            success = false,
                            message = "执行过程中发生错误: ${actionResult.summary}"
                        )
                    }
                    else -> {
                        lastAction = actionResult.summary
                        onProgress("执行操作: ${actionResult.action}")
                    }
                }
                
                // 等待一段时间再进行下一轮，期间检查是否被终止
                val intervalMs = config.requestInterval * 1000L
                val roundCheckInterval = 500L // 每500ms检查一次终止状态
                var waitedTime = 0L
                
                while (waitedTime < intervalMs && !isTaskCancelled) {
                    delay(roundCheckInterval)
                    waitedTime += roundCheckInterval
                }
                
                if (isTaskCancelled) {
                    onProgress("任务已被用户终止")
                    return@withContext TaskResult(
                        success = false,
                        message = "任务已被用户终止"
                    )
                }
            }
            
            if (taskComplete) {
                return@withContext TaskResult(
                    success = true,
                    message = "任务已成功完成",
                    completed = true
                )
            } else {
                return@withContext TaskResult(
                    success = true,
                    message = "任务已达到最大轮数限制 (${config.maxRounds} 轮)，可能未完全完成",
                    completed = false
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "任务执行过程中发生异常", e)
            return@withContext TaskResult(
                success = false,
                message = "任务执行失败: ${e.message}",
                error = e.stackTraceToString()
            )
        }
    }
    
    /**
     * 构建任务提示词
     */
    private fun buildTaskPrompt(taskDescription: String, lastAction: String, elemList: List<DeviceController.AndroidUIElement>): String {
        return """You are an agent that is trained to perform some basic tasks on a smartphone. You will be given a 
smartphone screenshot. The interactive UI elements on the screenshot are labeled with numeric tags starting from 1. The 
numeric tag of each interactive element is located in the center of the element.

You can call the following functions to control the smartphone:

1. tap(element: int)
This function is used to tap an UI element shown on the smartphone screen.
"element" is a numeric tag assigned to an UI element shown on the smartphone screen.
A simple use case can be tap(5), which taps the UI element labeled with the number 5.

2. text(text_input: str)
This function is used to insert text input in an input field/box. text_input is the string you want to insert and must 
be wrapped with double quotation marks. A simple use case can be text("Hello, world!"), which inserts the string 
"Hello, world!" into the input area on the smartphone screen. This function is usually callable when you see a keyboard 
showing in the lower half of the screen.

3. long_press(element: int)
This function is used to long press an UI element shown on the smartphone screen.
"element" is a numeric tag assigned to an UI element shown on the smartphone screen.
A simple use case can be long_press(5), which long presses the UI element labeled with the number 5.

4. swipe(element: int, direction: str, dist: str)
This function is used to swipe an UI element shown on the smartphone screen, usually a scroll view or a slide bar.
"element" is a numeric tag assigned to an UI element shown on the smartphone screen. "direction" is a string that 
represents one of the four directions: up, down, left, right. "direction" must be wrapped with double quotation 
marks. "dist" determines the distance of the swipe and can be one of the three options: short, medium, long. You should 
choose the appropriate distance option according to your need.
A simple use case can be swipe(21, "up", "medium"), which swipes up the UI element labeled with the number 21 for a 
medium distance.

The task you need to complete is to $taskDescription. Your past actions to proceed with this task are summarized as 
follows: $lastAction
Now, given the following labeled screenshot, you need to think and call the function needed to proceed with the task. 
Your output should include three parts in the given format:
Observation: <Describe what you observe in the image>
Thought: <To complete the given task, what is the next step I should do>
Action: <The function call with the correct parameters to proceed with the task. If you believe the task is completed or 
there is nothing to be done, you should output FINISH. You cannot output anything else except a function call or FINISH 
in this field.>
Summary: <Summarize your past actions along with your latest action in one or two sentences. Do not include the numeric 
tag in your summary>
You can only take one action at a time, so please directly call the function."""
    }
    
    /**
     * 调用大模型API
     */
    private suspend fun callLLMAPI(prompt: String, imageBase64: String, config: TaskConfig): String? {
        return try {
            if (config.model == "OpenAI") {
                callOpenAIAPI(prompt, imageBase64, config)
            } else {
                callQwenAPI(prompt, imageBase64, config)
            }
        } catch (e: Exception) {
            Log.e(TAG, "API调用失败", e)
            null
        }
    }
    
    /**
     * 调用OpenAI API
     */
    private suspend fun callOpenAIAPI(prompt: String, imageBase64: String, config: TaskConfig): String? {
        val json = JSONObject().apply {
            put("model", config.openaiApiModel)
            put("max_tokens", config.maxTokens)
            put("temperature", config.temperature)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/png;base64,$imageBase64")
                            })
                        })
                    })
                })
            })
        }
        
        val requestBody = RequestBody.create(
            "application/json; charset=utf-8".toMediaType(),
            json.toString()
        )
        
        val request = Request.Builder()
            .url(config.openaiApiBase)
            .post(requestBody)
            .addHeader("Authorization", "Bearer ${config.openaiApiKey}")
            .addHeader("Content-Type", "application/json")
            .build()
        
        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val responseJson = JSONObject(responseBody ?: "")
                    responseJson.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                } else {
                    Log.e(TAG, "OpenAI API调用失败: ${response.code} ${response.message}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "OpenAI API调用异常", e)
                null
            }
        }
    }
    
    /**
     * 调用通义千问API
     */
    private suspend fun callQwenAPI(prompt: String, imageBase64: String, config: TaskConfig): String? {
        // TODO: 实现通义千问API调用
        // 这里需要根据通义千问的API格式进行实现
        Log.w(TAG, "通义千问API暂未实现")
        return null
    }
    
    /**
     * 将图片转换为Base64
     */
    private fun convertImageToBase64(imagePath: String): String? {
        return try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(imagePath)
            if (bitmap != null) {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                val byteArray = outputStream.toByteArray()
                Base64.encodeToString(byteArray, Base64.NO_WRAP)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "图片转换失败", e)
            null
        }
    }
    
    /**
     * 解析响应并执行动作
     */
    private suspend fun parseAndExecuteAction(response: String, elemList: List<DeviceController.AndroidUIElement>): ActionResult = withContext(Dispatchers.Main) {
        
        // 检查是否被终止
        if (isTaskCancelled) {
            return@withContext ActionResult(
                action = "CANCELLED",
                params = emptyMap(),
                observation = "任务已被用户终止",
                thought = "任务已被用户终止",
                summary = "任务已被用户终止"
            )
        }
        
        // 解析响应中的各个部分
        val observation = extractSection(response, "Observation")
        val thought = extractSection(response, "Thought")
        val actionText = extractSection(response, "Action")
        val summary = extractSection(response, "Summary")
        
        Log.d(TAG, "观察: $observation")
        Log.d(TAG, "思考: $thought")
        Log.d(TAG, "动作: $actionText")
        Log.d(TAG, "总结: $summary")
        
        // 解析动作
        when {
            actionText.uppercase().contains("FINISH") -> {
                return@withContext ActionResult(
                    action = "FINISH",
                    params = emptyMap(),
                    observation = observation,
                    thought = thought,
                    summary = summary
                )
            }
            actionText.contains("tap(") -> {
                if (isTaskCancelled) {
                    return@withContext ActionResult(
                        action = "CANCELLED",
                        params = emptyMap(),
                        observation = observation,
                        thought = thought,
                        summary = "任务已被终止"
                    )
                }
                
                val elementIndex = extractNumber(actionText, "tap")
                if (elementIndex != null && elementIndex > 0 && elementIndex <= elemList.size) {
                    val element = elemList[elementIndex - 1]
                    val tl = element.bbox.first
                    val br = element.bbox.second
                    val x = (tl.first + br.first) / 2
                    val y = (tl.second + br.second) / 2
                    
                    withContext(Dispatchers.Main) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            deviceController.tap(x, y)
                        } else {
                            Log.w(TAG, "tap操作需要Android 7.0+，当前版本不支持")
                        }
                    }
                    
                    return@withContext ActionResult(
                        action = "tap",
                        params = mapOf("element" to elementIndex, "x" to x, "y" to y),
                        observation = observation,
                        thought = thought,
                        summary = summary
                    )
                }
            }
            actionText.contains("text(") -> {
                if (isTaskCancelled) {
                    return@withContext ActionResult(
                        action = "CANCELLED",
                        params = emptyMap(),
                        observation = observation,
                        thought = thought,
                        summary = "任务已被终止"
                    )
                }
                
                val textInput = extractString(actionText, "text")
                if (textInput != null) {
                    withContext(Dispatchers.Main) {
                        deviceController.inputText(textInput)
                    }
                    
                    return@withContext ActionResult(
                        action = "text",
                        params = mapOf("text" to textInput),
                        observation = observation,
                        thought = thought,
                        summary = summary
                    )
                }
            }
            actionText.contains("long_press(") -> {
                if (isTaskCancelled) {
                    return@withContext ActionResult(
                        action = "CANCELLED",
                        params = emptyMap(),
                        observation = observation,
                        thought = thought,
                        summary = "任务已被终止"
                    )
                }
                
                val elementIndex = extractNumber(actionText, "long_press")
                if (elementIndex != null && elementIndex > 0 && elementIndex <= elemList.size) {
                    val element = elemList[elementIndex - 1]
                    val tl = element.bbox.first
                    val br = element.bbox.second
                    val x = (tl.first + br.first) / 2
                    val y = (tl.second + br.second) / 2
                    
                    withContext(Dispatchers.Main) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            deviceController.longPress(x, y)
                        } else {
                            Log.w(TAG, "long_press操作需要Android 7.0+，当前版本不支持")
                        }
                    }
                    
                    return@withContext ActionResult(
                        action = "long_press",
                        params = mapOf("element" to elementIndex, "x" to x, "y" to y),
                        observation = observation,
                        thought = thought,
                        summary = summary
                    )
                }
            }
            actionText.contains("swipe(") -> {
                if (isTaskCancelled) {
                    return@withContext ActionResult(
                        action = "CANCELLED",
                        params = emptyMap(),
                        observation = observation,
                        thought = thought,
                        summary = "任务已被终止"
                    )
                }
                
                val swipeMatch = Regex("""swipe\(\s*(\d+)\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""").find(actionText)
                if (swipeMatch != null) {
                    val elementIndex = swipeMatch.groupValues[1].toIntOrNull()
                    val direction = swipeMatch.groupValues[2]
                    val distance = swipeMatch.groupValues[3]
                    
                    if (elementIndex != null && elementIndex > 0 && elementIndex <= elemList.size) {
                        val element = elemList[elementIndex - 1]
                        val tl = element.bbox.first
                        val br = element.bbox.second
                        val x = (tl.first + br.first) / 2
                        val y = (tl.second + br.second) / 2
                        
                        val swipeDistance = when (distance.lowercase()) {
                            "short" -> 100
                            "medium" -> 300
                            "long" -> 500
                            else -> 300
                        }
                        
                        val (endX, endY) = when (direction.lowercase()) {
                            "up" -> x to (y - swipeDistance)
                            "down" -> x to (y + swipeDistance)
                            "left" -> (x - swipeDistance) to y
                            "right" -> (x + swipeDistance) to y
                            else -> x to y
                        }
                        
                        withContext(Dispatchers.Main) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                deviceController.swipe(x, y, endX, endY)
                            } else {
                                Log.w(TAG, "swipe操作需要Android 7.0+，当前版本不支持")
                            }
                        }
                        
                        return@withContext ActionResult(
                            action = "swipe",
                            params = mapOf(
                                "element" to elementIndex,
                                "direction" to direction,
                                "distance" to distance,
                                "startX" to x,
                                "startY" to y,
                                "endX" to endX,
                                "endY" to endY
                            ),
                            observation = observation,
                            thought = thought,
                            summary = summary
                        )
                    }
                }
            }
        }
        
        // 如果无法解析动作，返回错误
        return@withContext ActionResult(
            action = "ERROR",
            params = mapOf("error" to "无法解析动作: $actionText"),
            observation = observation,
            thought = thought,
            summary = "解析动作失败"
        )
    }
    
    /**
     * 从响应中提取指定段落
     */
    private fun extractSection(response: String, sectionName: String): String {
        val regex = Regex("$sectionName:\\s*(.+?)(?=\\n[A-Z][a-z]+:|$)", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(response)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }
    
    /**
     * 从动作文本中提取数字参数
     */
    private fun extractNumber(actionText: String, functionName: String): Int? {
        val regex = Regex("$functionName\\s*\\(\\s*(\\d+)\\s*\\)")
        val match = regex.find(actionText)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    /**
     * 从动作文本中提取字符串参数
     */
    private fun extractString(actionText: String, functionName: String): String? {
        val regex = Regex("$functionName\\s*\\(\\s*\"([^\"]+)\"\\s*\\)")
        val match = regex.find(actionText)
        return match?.groupValues?.get(1)
    }
}