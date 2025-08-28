package com.pa.assistclient

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import java.io.*
import java.nio.ByteBuffer

class DeviceController(private val context: Context) {
    
    companion object {
        private const val TAG = "DeviceController"
        private const val SCREENSHOT_REQUEST_CODE = 1001
        var accessibilityService: DeviceAccessibilityService? = null
    }
    
    /**
     * 检查无障碍服务是否已启用
     * 使用系统API检查，不依赖服务实例状态
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            
            // 方法1：检查系统设置中是否启用了服务
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            
            val packageName = context.packageName
            val serviceName = "$packageName/${DeviceAccessibilityService::class.java.name}"
            val shortServiceName = "$packageName/.DeviceAccessibilityService"
            
            val isEnabledInSettings = !enabledServices.isNullOrEmpty() && 
                (enabledServices.contains(serviceName) || enabledServices.contains(shortServiceName))
            
            if (!isEnabledInSettings) {
                Log.d(TAG, "无障碍服务未在系统设置中启用")
                return false
            }
            
            // 方法2：检查服务是否在运行列表中
            val runningServices = accessibilityManager.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            
            val isServiceRunning = runningServices.any { serviceInfo ->
                serviceInfo.resolveInfo.serviceInfo.packageName == packageName &&
                serviceInfo.resolveInfo.serviceInfo.name == DeviceAccessibilityService::class.java.name
            }
            
            Log.d(TAG, "权限检查结果 - 设置中已启用: $isEnabledInSettings, 服务运行中: $isServiceRunning")
            
            // 只要系统设置中启用了，就认为有权限（即使服务实例还未连接）
            return isEnabledInSettings
            
        } catch (e: Exception) {
            Log.e(TAG, "检查无障碍服务状态失败", e)
            false
        }
    }
    
    /**
     * 检查服务实例是否已连接并可用
     */
    fun isAccessibilityServiceConnected(): Boolean {
        return accessibilityService != null
    }
    
    /**
     * 打开无障碍设置页面
     */
    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val mediaProjectionManager by lazy {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    
    // MediaProjectionService连接
    private var mediaProjectionService: MediaProjectionService? = null
    private var isServiceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MediaProjectionService.MediaProjectionBinder
            mediaProjectionService = binder.getService()
            isServiceBound = true
            Log.d(TAG, "MediaProjectionService已连接")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            mediaProjectionService = null
            isServiceBound = false
            Log.d(TAG, "MediaProjectionService已断开")
        }
    }
    
    /**
     * 返回操作 - 通过无障碍服务
     */
    fun back() {
        if (!isAccessibilityServiceEnabled()) {
            Log.e(TAG, "无障碍服务未启用，请先开启权限")
            return
        }
        
        if (!isAccessibilityServiceConnected()) {
            Log.w(TAG, "无障碍服务未连接，尝试等待连接...")
            // 等待一个短暂时间再试
            Thread.sleep(500)
        }
        
        accessibilityService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            ?: Log.e(TAG, "无障碍服务实例未连接，请稍后再试")
    }
    
    /**
     * 点击操作
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun tap(x: Int, y: Int) {
        if (!isAccessibilityServiceEnabled()) {
            Log.e(TAG, "无障碍服务未启用")
            return
        }
        
        accessibilityService?.let { service ->
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
                
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "点击操作完成: ($x, $y)")
                }
                
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.e(TAG, "点击操作取消")
                }
            }, null)
        } ?: run {
            Log.w(TAG, "无障碍服务实例未连接，请稍后再试")
        }
    }
    
    /**
     * 长按操作
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun longPress(x: Int, y: Int) {
        accessibilityService?.let { service ->
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 1000)) // 1秒长按
                .build()
                
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "长按操作完成: ($x, $y)")
                }
                
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.e(TAG, "长按操作取消")
                }
            }, null)
        } ?: Log.e(TAG, "无障碍服务未启用")
    }
    
    /**
     * 文本输入
     */
    fun inputText(text: String) {
        if (!isAccessibilityServiceEnabled()) {
            Log.e(TAG, "无障碍服务未启用")
            return
        }
        
        accessibilityService?.let { service ->
            // 查找EditText类型且当前有焦点的控件
            val inputNode = findFocusedEditText(service.rootInActiveWindow)
            Log.e(TAG, "查找EditText+焦点结果：${inputNode}")

            inputNode?.let { node ->
                Log.d(TAG, "找到输入控件: className=${node.className}, text=${node.text}, editable=${node.isEditable}, focused=${node.isFocused}")

                // 清空现有文本（如果有的话）
                if (!node.text.isNullOrEmpty()) {
                    val deleteArgs = android.os.Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text.length)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, deleteArgs)
                    Log.d(TAG, "清空现有文本")
                }

                // 输入新文本
                val arguments = android.os.Bundle().apply {
                    putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

                if (success) {
                    Log.d(TAG, "文本输入完成: $text")
                } else {
                    Log.e(TAG, "文本输入失败，尝试使用粘贴方式")
                    // 备用方案：使用剪贴板
                    tryInputWithClipboard(node, text)
                }
            } ?: Log.e(TAG, "未找到EditText类型且有焦点的控件")
        } ?: Log.e(TAG, "无障碍服务未启用")
    }
    
    /**
     * 查找EditText类型且当前有焦点的控件
     */
    private fun findFocusedEditText(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        
        // 检查当前节点是否同时满足：EditText类型 + 有焦点
        if (isEditTextType(root) && root.isFocused && root.isEnabled && root.isVisibleToUser) {
            Log.d(TAG, "找到匹配的EditText控件: ${root.className}")
            return root
        }
        
        // 递归检查子节点
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            val result = findFocusedEditText(child)
            if (result != null) {
                return result
            }
        }
        
        return null
    }
    
    /**
     * 检查节点是否是EditText类型
     */
    private fun isEditTextType(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        
        val className = node.className?.toString() ?: ""
        return className.contains("EditText") || 
               className.contains("TextInputEditText") ||
               className.contains("AutoCompleteTextView") ||
               node.isEditable
    }
    
    /**
     * 使用剪贴板的备用输入方案
     */
    private fun tryInputWithClipboard(node: AccessibilityNodeInfo, text: String) {
        try {
            // 将文本复制到剪贴板
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("input_text", text)
            clipboard.setPrimaryClip(clip)
            
            // 执行粘贴操作
            val success = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (success) {
                Log.d(TAG, "剪贴板输入成功: $text")
            } else {
                Log.e(TAG, "剪贴板输入也失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "剪贴板操作失败", e)
        }
    }
    
    /**
     * 滑动操作
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 500) {
        accessibilityService?.let { service ->
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
                
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "滑动操作完成: ($startX, $startY) -> ($endX, $endY)")
                }
                
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.e(TAG, "滑动操作取消")
                }
            }, null)
        } ?: Log.e(TAG, "无障碍服务未启用")
    }
    
    /**
     * 获取界面XML结构
     */
    fun dumpUIXml(): String {
        return try {
            accessibilityService?.let { service ->
                val rootNode = service.rootInActiveWindow
                rootNode?.let { node ->
                    val xmlHeader = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"
                    val hierarchyStart = "<hierarchy rotation=\"0\">"
                    val hierarchyEnd = "</hierarchy>"
                    val nodeXml = buildXmlFromNode(node, 0)
                    "$xmlHeader$hierarchyStart$nodeXml$hierarchyEnd"
                } ?: "无法获取根节点"
            } ?: "无障碍服务未启用"
        } catch (e: Exception) {
            Log.e(TAG, "获取UI结构失败", e)
            "获取UI结构失败: ${e.message}"
        }
    }
    
    private fun buildXmlFromNode(node: AccessibilityNodeInfo, index: Int = 0): String {
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val resourceId = node.viewIdResourceName ?: ""
        val packageName = node.packageName?.toString() ?: ""
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        
        val sb = StringBuilder()
        sb.append("<node")
        
        // 检查是否是NAF (Not Accessible by Framework)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try {
                val isVisibleToUser = node.isVisibleToUser
                if (!isVisibleToUser && text.isEmpty() && contentDesc.isEmpty()) {
                    sb.append(" NAF=\"true\"")
                }
            } catch (e: Exception) {
                // 忽略异常，继续处理
            }
        }
        
        sb.append(" index=\"$index\"")
        sb.append(" text=\"${escapeXml(text)}\"")
        sb.append(" resource-id=\"${escapeXml(resourceId)}\"")
        sb.append(" class=\"${escapeXml(className)}\"")
        sb.append(" package=\"${escapeXml(packageName)}\"")
        sb.append(" content-desc=\"${escapeXml(contentDesc)}\"")
        sb.append(" checkable=\"${node.isCheckable}\"")
        sb.append(" checked=\"${node.isChecked}\"")
        sb.append(" clickable=\"${node.isClickable}\"")
        sb.append(" enabled=\"${node.isEnabled}\"")
        sb.append(" focusable=\"${node.isFocusable}\"")
        sb.append(" focused=\"${node.isFocused}\"")
        sb.append(" scrollable=\"${node.isScrollable}\"")
        sb.append(" long-clickable=\"${node.isLongClickable}\"")
        sb.append(" password=\"${node.isPassword}\"")
        sb.append(" selected=\"${node.isSelected}\"")
        sb.append(" bounds=\"[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]\"")
        
        val childCount = node.childCount
        if (childCount > 0) {
            sb.append(">")
            for (i in 0 until childCount) {
                val child = node.getChild(i)
                child?.let {
                    sb.append(buildXmlFromNode(it, i))
                }
            }
            sb.append("</node>")
        } else {
            sb.append(" />")
        }
        
        return sb.toString()
    }
    
    /**
     * 转义XML中的特殊字符
     */
    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&apos;")
    }
    
    /**
     * 检查截图权限是否已获取
     */
    fun isScreenshotPermissionGranted(): Boolean {
        return try {
            // 方法1：检查服务是否活跃
            val serviceActive = mediaProjectionService?.isProjectionActive() == true
            
            // 方法2：检查服务是否已绑定
            val serviceConnected = isServiceBound && mediaProjectionService != null
            
            // 记录检查结果
            Log.d(TAG, "权限检查结果 - 设置中已启用: $serviceActive, 服务运行中: $serviceConnected")
            
            // 只要服务活跃或者服务已连接，就认为有权限
            serviceActive || serviceConnected
            
        } catch (e: Exception) {
            Log.e(TAG, "检查截图权限状态失败", e)
            false
        }
    }
    
    /**
     * 初始化MediaProjection用于截图
     */
    fun initMediaProjection(resultCode: Int, data: Intent) {
        // 启动MediaProjectionService
        val serviceIntent = Intent(context, MediaProjectionService::class.java).apply {
            action = MediaProjectionService.ACTION_START_PROJECTION
            putExtra(MediaProjectionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MediaProjectionService.EXTRA_RESULT_DATA, data)
        }
        
        // 绑定并启动服务
        context.bindService(
            Intent(context, MediaProjectionService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        
        // 根据Android版本启动服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        
        Log.d(TAG, "MediaProjection初始化完成")
    }
    
    /**
     * 截图功能
     * 修复：避免重复调用，只使用直接调用服务实例的方式
     */
    fun takeScreenshot(): Boolean {
        return if (isServiceBound && mediaProjectionService != null) {
            // 直接调用服务实例方法，避免通过Intent重复触发
            Log.d(TAG, "通过服务实例直接执行截图")
            mediaProjectionService?.takeScreenshot() ?: false
        } else {
            Log.e(TAG, "MediaProjectionService未初始化，请先请求权限")
            false
        }
    }
    
    /**
     * 请求截图权限
     */
    fun requestScreenshotPermission(): Intent {
        return mediaProjectionManager.createScreenCaptureIntent()
    }
    
    /**
     * 获取屏幕尺寸
     * 返回屏幕的宽度和高度（像素）
     */
    fun getDeviceSize(): Pair<Int, Int> {
        return try {
            val displayMetrics = context.resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            
            Log.d(TAG, "获取屏幕尺寸: ${width}x${height}")
            Pair(width, height)
        } catch (e: Exception) {
            Log.e(TAG, "获取屏幕尺寸失败", e)
            Pair(0, 0)
        }
    }
    
    /**
     * 获取屏幕尺寸（使用WindowManager）
     * 这个方法可以获取更准确的屏幕尺寸，包括系统栏等
     */
    fun getRealDeviceSize(): Pair<Int, Int> {
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ 使用新API
                val windowMetrics = windowManager.currentWindowMetrics
                val bounds = windowMetrics.bounds
                val width = bounds.width()
                val height = bounds.height()
                
                Log.d(TAG, "获取真实屏幕尺寸(Android 11+): ${width}x${height}")
                Pair(width, height)
            } else {
                // Android 10及以下使用旧API
                val displayMetrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(displayMetrics)
                val width = displayMetrics.widthPixels
                val height = displayMetrics.heightPixels
                
                Log.d(TAG, "获取真实屏幕尺寸(Android 10-): ${width}x${height}")
                Pair(width, height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取真实屏幕尺寸失败", e)
            // 备用方案：使用基本方法
            getDeviceSize()
        }
    }
    
    /**
     * 获取屏幕密度信息
     */
    fun getDeviceDensity(): Float {
        return try {
            val density = context.resources.displayMetrics.density
            Log.d(TAG, "获取屏幕密度: $density")
            density
        } catch (e: Exception) {
            Log.e(TAG, "获取屏幕密度失败", e)
            1.0f // 默认密度
        }
    }
    
    /**
     * 获取屏幕DPI
     */
    fun getDeviceDpi(): Int {
        return try {
            val dpi = context.resources.displayMetrics.densityDpi
            Log.d(TAG, "获取屏幕DPI: $dpi")
            dpi
        } catch (e: Exception) {
            Log.e(TAG, "获取屏幕DPI失败", e)
            160 // 默认DPI
        }
    }
    fun cleanup() {
        // 停止MediaProjectionService
        if (isServiceBound) {
            val serviceIntent = Intent(context, MediaProjectionService::class.java).apply {
                action = MediaProjectionService.ACTION_STOP_PROJECTION
            }
            context.startService(serviceIntent)
            context.unbindService(serviceConnection)
            isServiceBound = false
        }
    }
    
    /**
     * Android版本的UI元素类
     */
    data class AndroidUIElement(
        val uid: String,
        val bbox: Pair<Pair<Int, Int>, Pair<Int, Int>>, // ((left, top), (right, bottom))
        val attrib: String, // "clickable" or "focusable"
        val className: String = "",
        val text: String = "",
        val contentDesc: String = "",
        val resourceId: String = ""
    )
    
    /**
     * 遍历无障碍节点树，收集可交互的UI元素
     */
    private fun traverseAccessibilityTree(
        rootNode: AccessibilityNodeInfo?, 
        elementList: MutableList<AndroidUIElement>,
        targetAttribute: String, // "clickable" or "focusable"
        uselessList: Set<String> = emptySet()
    ) {
        if (rootNode == null) return
        
        try {
            // 检查当前节点是否符合条件
            val isTarget = when (targetAttribute) {
                "clickable" -> rootNode.isClickable
                "focusable" -> rootNode.isFocusable
                else -> false
            }
            
            if (isTarget && rootNode.isVisibleToUser && rootNode.isEnabled) {
                val bounds = android.graphics.Rect()
                rootNode.getBoundsInScreen(bounds)
                
                // 生成元素ID
                val elemId = generateElementId(rootNode, bounds)
                
                // 检查是否在无用列表中
                if (elemId !in uselessList) {
                    val element = AndroidUIElement(
                        uid = elemId,
                        bbox = Pair(
                            Pair(bounds.left, bounds.top),
                            Pair(bounds.right, bounds.bottom)
                        ),
                        attrib = targetAttribute,
                        className = rootNode.className?.toString() ?: "",
                        text = rootNode.text?.toString() ?: "",
                        contentDesc = rootNode.contentDescription?.toString() ?: "",
                        resourceId = rootNode.viewIdResourceName ?: ""
                    )
                    elementList.add(element)
                }
            }
            
            // 递归处理子节点
            for (i in 0 until rootNode.childCount) {
                val child = rootNode.getChild(i)
                traverseAccessibilityTree(child, elementList, targetAttribute, uselessList)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "遍历无障碍树时发生错误", e)
        }
    }
    
    /**
     * 生成元素ID（类似Python版本的逻辑）
     */
    private fun generateElementId(node: AccessibilityNodeInfo, bounds: android.graphics.Rect): String {
        val elemW = bounds.width()
        val elemH = bounds.height()
        
        var elemId = if (!node.viewIdResourceName.isNullOrEmpty()) {
            node.viewIdResourceName!!.replace(":", ".").replace("/", "_")
        } else {
            "${node.className}_${elemW}_${elemH}"
        }
        
        // 添加内容描述（如果存在且较短）
        val contentDesc = node.contentDescription?.toString()
        if (!contentDesc.isNullOrEmpty() && contentDesc.length < 20) {
            val cleanDesc = contentDesc.replace("/", "_").replace(" ", "").replace(":", "_")
            elemId += "_$cleanDesc"
        }
        
        return elemId
    }
    
    /**
     * 计算两个元素中心点之间的距离
     */
    private fun calculateDistance(elem1: AndroidUIElement, elem2: AndroidUIElement): Double {
        val center1 = Pair(
            (elem1.bbox.first.first + elem1.bbox.second.first) / 2,
            (elem1.bbox.first.second + elem1.bbox.second.second) / 2
        )
        val center2 = Pair(
            (elem2.bbox.first.first + elem2.bbox.second.first) / 2,
            (elem2.bbox.first.second + elem2.bbox.second.second) / 2
        )
        
        val dx = center1.first - center2.first
        val dy = center1.second - center2.second
        return kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
    }
    
    /**
     * 获取带标注的截图
     * 实现与Python版本相同的逻辑：获取可点击和可聚焦元素，去重，然后添加标注
     * 最终图片保存到相册根目录 (/storage/emulated/0/Pictures/)
     */
    fun getAnnotatedScreenshot(
        saveDir: String,
        prefix: String,
        uselessList: Set<String> = emptySet(),
        minDistance: Double = 50.0,
        darkMode: Boolean = false
    ): Pair<String?, List<AndroidUIElement>> {
        
        if (!isAccessibilityServiceEnabled() || !isAccessibilityServiceConnected()) {
            Log.e(TAG, "无障碍服务未启用或未连接")
            return Pair(null, emptyList())
        }
        
        try {
            // 1. 获取截图
            val success = takeScreenshot()
            if (!success) {
                Log.e(TAG, "获取截图失败2")
                return Pair(null, emptyList())
            }
            
            // 等待截图保存完成
            Thread.sleep(500)
            
            // 2. 获取无障碍节点树
            val rootNode = accessibilityService?.rootInActiveWindow
            if (rootNode == null) {
                Log.e(TAG, "无法获取根节点")
                return Pair(null, emptyList())
            }
            
            // 3. 收集可点击和可聚焦的元素
            val clickableList = mutableListOf<AndroidUIElement>()
            val focusableList = mutableListOf<AndroidUIElement>()
            
            traverseAccessibilityTree(rootNode, clickableList, "clickable", uselessList)
            traverseAccessibilityTree(rootNode, focusableList, "focusable", uselessList)
            
            Log.d(TAG, "找到可点击元素: ${clickableList.size}, 可聚焦元素: ${focusableList.size}")
            
            // 4. 合并元素列表并去重（类似Python逻辑）
            val elemList = mutableListOf<AndroidUIElement>()
            elemList.addAll(clickableList)
            
            // 添加不与可点击元素重叠的可聚焦元素
            for (focusableElem in focusableList) {
                var tooClose = false
                for (clickableElem in clickableList) {
                    val distance = calculateDistance(focusableElem, clickableElem)
                    if (distance <= minDistance) {
                        tooClose = true
                        break
                    }
                }
                if (!tooClose) {
                    elemList.add(focusableElem)
                }
            }
            
            Log.d(TAG, "合并后的元素总数: ${elemList.size}")
            
            // 5. 在截图上添加标注
            val annotatedImagePath = drawBboxMulti(
                getLastScreenshotPath(),
                saveDir,
                "${prefix}_labeled",
                elemList,
                darkMode
            )
            
            return Pair(annotatedImagePath, elemList)
            
        } catch (e: Exception) {
            Log.e(TAG, "获取带标注截图时发生错误", e)
            return Pair(null, emptyList())
        }
    }
    
    /**
     * 获取最后一次截图的路径
     */
    private fun getLastScreenshotPath(): String {
        // 这里应该返回最新截图的路径
        // 简化实现：假设截图保存在外部存储的Pictures目录
        val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
        val files = picturesDir.listFiles { file -> file.name.startsWith("screenshot_") && file.name.endsWith(".png") }
        return files?.maxByOrNull { it.lastModified() }?.absolutePath ?: ""
    }
    
    /**
     * 在图片上绘制边界框和标签（类似Python的draw_bbox_multi）
     */
    private fun drawBboxMulti(
        inputImagePath: String,
        saveDir: String,
        outputPrefix: String,
        elemList: List<AndroidUIElement>,
        darkMode: Boolean = false
    ): String? {
        
        if (inputImagePath.isEmpty() || !java.io.File(inputImagePath).exists()) {
            Log.e(TAG, "输入图片不存在: $inputImagePath")
            return null
        }
        
        try {
            // 使用Android Canvas API绘制标注
            val originalBitmap = android.graphics.BitmapFactory.decodeFile(inputImagePath)
            val mutableBitmap = originalBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(mutableBitmap)
            
            // 设置画笔
            val textPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = 30f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                color = if (darkMode) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
            
            val backgroundPaint = android.graphics.Paint().apply {
                color = if (darkMode) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                alpha = 128 // 50% 透明度
            }
            
            val borderPaint = android.graphics.Paint().apply {
                color = if (darkMode) android.graphics.Color.WHITE else android.graphics.Color.RED
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f
            }
            
            // 为每个元素绘制标注
            elemList.forEachIndexed { index, element ->
                try {
                    val label = (index + 1).toString()
                    val left = element.bbox.first.first.toFloat()
                    val top = element.bbox.first.second.toFloat()
                    val right = element.bbox.second.first.toFloat()
                    val bottom = element.bbox.second.second.toFloat()
                    
                    // 计算标签位置（元素中心偏移）
                    val centerX = (left + right) / 2
                    val centerY = (top + bottom) / 2
                    val labelX = centerX
                    val labelY = centerY
                    
                    // 绘制边界框（可选）
//                    canvas.drawRect(left, top, right, bottom, borderPaint)
                    
                    // 测量文本尺寸
                    val textBounds = android.graphics.Rect()
                    textPaint.getTextBounds(label, 0, label.length, textBounds)
                    
                    // 绘制背景矩形
                    val padding = 8f
                    val bgLeft = labelX - padding
                    val bgTop = labelY - textBounds.height() - padding
                    val bgRight = labelX + textBounds.width() + padding
                    val bgBottom = labelY + padding
                    
                    canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, backgroundPaint)
                    
                    // 绘制文本
                    canvas.drawText(label, labelX, labelY, textPaint)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "绘制元素标注时发生错误: ${e.message}")
                }
            }
            
            // 保存标注后的图片到相册根目录
            val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES
            )
            
            val outputPath = "${picturesDir.absolutePath}/${outputPrefix}.png"
            val success = saveBitmapToGallery(mutableBitmap, outputPrefix)
            
            if (success) {
                Log.d(TAG, "带标注的截图已保存到相册: $outputPath")
                return outputPath
            } else {
                Log.e(TAG, "保存带标注截图失败")
                return null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "绘制标注时发生错误", e)
            return null
        }
    }
    
    /**
     * 快速获取当前屏幕的UI元素列表（用于调试和测试）
     */
    fun getCurrentUIElements(): List<AndroidUIElement> {
        if (!isAccessibilityServiceEnabled() || !isAccessibilityServiceConnected()) {
            Log.w(TAG, "无障碍服务未启用或未连接")
            return emptyList()
        }
        
        val rootNode = accessibilityService?.rootInActiveWindow ?: return emptyList()
        
        val clickableList = mutableListOf<AndroidUIElement>()
        val focusableList = mutableListOf<AndroidUIElement>()
        
        traverseAccessibilityTree(rootNode, clickableList, "clickable")
        traverseAccessibilityTree(rootNode, focusableList, "focusable")
        
        val elemList = mutableListOf<AndroidUIElement>()
        elemList.addAll(clickableList)
        
        // 添加不重叠的可聚焦元素
        for (focusableElem in focusableList) {
            var tooClose = false
            for (clickableElem in clickableList) {
                if (calculateDistance(focusableElem, clickableElem) <= 50.0) {
                    tooClose = true
                    break
                }
            }
            if (!tooClose) {
                elemList.add(focusableElem)
            }
        }
        
        return elemList
    }
    
    /**
     * 使用MediaStore API保存图片到相册（支持Android 10+）
     * 修复版本：确保图片能在相册中立即显示
     */
    private fun saveBitmapToGallery(bitmap: android.graphics.Bitmap, fileName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用MediaStore API
                val resolver = context.contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.png")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
                    // 添加更多元数据确保正确索引
                    put(android.provider.MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(android.provider.MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    put(android.provider.MediaStore.Images.Media.WIDTH, bitmap.width)
                    put(android.provider.MediaStore.Images.Media.HEIGHT, bitmap.height)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1) // 标记为待处理
                    }
                }
                
                val imageUri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                imageUri?.let { uri ->
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        val compressSuccess = bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outputStream)
                        outputStream.flush()
                        
                        if (compressSuccess) {
                            // 完成写入，清除待处理标志
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val updateValues = android.content.ContentValues().apply {
                                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                                }
                                resolver.update(uri, updateValues, null, null)
                            }
                            
                            Log.d(TAG, "✓ 使用MediaStore API保存图片成功: $uri")
                            Log.d(TAG, "✓ 图片文件名: $fileName.png")
                            Log.d(TAG, "✓ 图片尺寸: ${bitmap.width}x${bitmap.height}")
                            return true
                        } else {
                            Log.e(TAG, "图片压缩失败")
                        }
                    }
                } ?: Log.e(TAG, "无法创建MediaStore URI")
            } else {
                // Android 9 及以下使用传统方法 + 强化媒体扫描
                val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES
                )
                
                // 确保目录存在
                if (!picturesDir.exists()) {
                    picturesDir.mkdirs()
                }
                
                val file = java.io.File(picturesDir, "$fileName.png")
                
                java.io.FileOutputStream(file).use { outputStream ->
                    val compressSuccess = bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.flush()
                    outputStream.close()
                    
                    if (!compressSuccess) {
                        Log.e(TAG, "传统方式图片压缩失败")
                        return false
                    }
                }
                
                // 验证文件是否成功创建
                if (!file.exists() || file.length() == 0L) {
                    Log.e(TAG, "文件创建失败或为空: ${file.absolutePath}")
                    return false
                }
                
                Log.d(TAG, "✓ 文件已创建: ${file.absolutePath} (${file.length()} bytes)")
                
                // 多重媒体扫描策略
                try {
                    // 方法1: 广播媒体扫描
                    val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                    mediaScanIntent.data = android.net.Uri.fromFile(file)
                    context.sendBroadcast(mediaScanIntent)
                    Log.d(TAG, "已发送媒体扫描广播")
                    
                    // 方法2: MediaScannerConnection
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        arrayOf("image/png")
                    ) { path, uri ->
                        Log.d(TAG, "✓ MediaScannerConnection扫描完成: $path -> $uri")
                    }
                    
                    // 方法3: 强制刷新整个Pictures目录
                    val refreshIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                    refreshIntent.data = android.net.Uri.fromFile(picturesDir)
                    context.sendBroadcast(refreshIntent)
                    
                    Log.d(TAG, "✓ 传统方式保存图片成功: ${file.absolutePath}")
                    Log.d(TAG, "✓ 已触发多重媒体扫描")
                    return true
                    
                } catch (scanException: Exception) {
                    Log.w(TAG, "媒体扫描时发生警告: ${scanException.message}")
                    // 即使媒体扫描出错，文件也已保存成功
                    return true
                }
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "保存图片到相册失败", e)
            false
        }
    }
}