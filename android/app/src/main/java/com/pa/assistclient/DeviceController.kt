package com.pa.assistclient

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.content.Context
import android.content.Intent
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
        accessibilityService?.let { service ->
            val focusedNode = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            focusedNode?.let { node ->
                val arguments = android.os.Bundle().apply {
                    putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                Log.d(TAG, "文本输入完成: $text")
            } ?: Log.e(TAG, "未找到可输入的控件")
        } ?: Log.e(TAG, "无障碍服务未启用")
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
                    buildXmlFromNode(node)
                } ?: "无法获取根节点"
            } ?: "无障碍服务未启用"
        } catch (e: Exception) {
            Log.e(TAG, "获取UI结构失败", e)
            "获取UI结构失败: ${e.message}"
        }
    }
    
    private fun buildXmlFromNode(node: AccessibilityNodeInfo, depth: Int = 0): String {
        val indent = "  ".repeat(depth)
        val className = node.className ?: "unknown"
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        
        val sb = StringBuilder()
        sb.append("$indent<node")
        sb.append(" class=\"$className\"")
        if (text.isNotEmpty()) sb.append(" text=\"$text\"")
        if (contentDesc.isNotEmpty()) sb.append(" content-desc=\"$contentDesc\"")
        sb.append(" bounds=\"[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]\"")
        sb.append(" clickable=\"${node.isClickable}\"")
        sb.append(" enabled=\"${node.isEnabled}\"")
        sb.append(">\n")
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            child?.let {
                sb.append(buildXmlFromNode(it, depth + 1))
            }
        }
        
        sb.append("$indent</node>\n")
        return sb.toString()
    }
    
    /**
     * 初始化MediaProjection用于截图
     */
    fun initMediaProjection(resultCode: Int, data: Intent) {
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        setupImageReader()
    }
    
    /**
     * 设置ImageReader
     */
    private fun setupImageReader() {
        val displayMetrics = DisplayMetrics()
        if (context is Activity) {
            context.windowManager.defaultDisplay.getMetrics(displayMetrics)
        }
        
        imageReader = ImageReader.newInstance(
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            PixelFormat.RGBA_8888,
            1
        )
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "screenshot",
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }
    
    /**
     * 截图功能
     */
    fun takeScreenshot(): Boolean {
        return try {
            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection未初始化，请先请求权限")
                return false
            }
            
            imageReader?.let { reader ->
                val image = reader.acquireLatestImage()
                image?.let {
                    val bitmap = imageToBitmap(it)
                    saveBitmapToFile(bitmap)
                    it.close()
                    Log.d(TAG, "截图成功")
                    true
                } ?: false
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "截图失败", e)
            false
        }
    }
    
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
    
    private fun saveBitmapToFile(bitmap: Bitmap) {
        try {
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "screenshot_${System.currentTimeMillis()}.png"
            )
            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.close()
            Log.d(TAG, "截图保存到: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存截图失败", e)
        }
    }
    
    /**
     * 请求截图权限
     */
    fun requestScreenshotPermission(): Intent {
        return mediaProjectionManager.createScreenCaptureIntent()
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }
}