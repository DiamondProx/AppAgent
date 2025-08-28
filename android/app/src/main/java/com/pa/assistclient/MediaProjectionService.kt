package com.pa.assistclient

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MediaProjectionService : Service() {
    companion object {
        private const val TAG = "MediaProjectionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "media_projection_channel"
        const val ACTION_START_PROJECTION = "START_PROJECTION"
        const val ACTION_STOP_PROJECTION = "STOP_PROJECTION"
        const val ACTION_TAKE_SCREENSHOT = "TAKE_SCREENSHOT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        
        // 录屏状态
        private var isRecording = false
    }

    private val binder = MediaProjectionBinder()
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    // 截图专用Handler线程和同步控制
    private var screenshotHandlerThread: HandlerThread? = null
    private var screenshotHandler: Handler? = null
    private val screenshotLock = Any()
    private val isCapturing = AtomicBoolean(false)

    inner class MediaProjectionBinder : Binder() {
        fun getService(): MediaProjectionService = this@MediaProjectionService
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
        
        // 创建截图Handler线程
        screenshotHandlerThread = HandlerThread("ScreenshotThread").also {
            it.start()
            screenshotHandler = Handler(it.looper)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultData != null) {
                    startMediaProjection(resultCode, resultData)
                }
            }
            ACTION_TAKE_SCREENSHOT -> {
                takeScreenshot()
            }
            ACTION_STOP_PROJECTION -> {
                stopMediaProjection()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "录屏服务",
                NotificationManager.IMPORTANCE_DEFAULT  // 提高重要性级别
            ).apply {
                description = "用于截图和录屏功能的后台服务"
                setShowBadge(true)  // 允许显示徽章
                // 保持默认声音设置，但不强制静默
                enableLights(true)
                lightColor = android.graphics.Color.RED
                // 对于荣耀手机，保持默认振动设置
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            
            Log.d(TAG, "已创建通知渠道: $CHANNEL_ID")
        }
    }

    private fun startForeground() {
        updateNotification(true)
        isRecording = true
    }
    
    private fun updateNotification(isActive: Boolean) {
        val title = if (isActive) "🔴 正在录屏" else "录屏服务"
        val content = if (isActive) "录屏功能已激活，点击停止录屏" else "录屏服务已准备就绪"
        
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MediaProjectionService::class.java).apply {
                action = ACTION_STOP_PROJECTION
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(if (isActive) android.R.drawable.ic_media_play else android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)  // 提高优先级
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止录屏",
                stopIntent
            )
            .setAutoCancel(false)
            .setDefaults(NotificationCompat.DEFAULT_ALL)  // 使用默认设置
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // 公开显示
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+ 需要显式调用startForeground
            startForeground(NOTIFICATION_ID, notification)
        } else {
            // Android 8.0以下版本
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // 添加日志来检查通知状态
        Log.d(TAG, "已更新通知: $title, Android版本: ${Build.VERSION.SDK_INT}")
        
        // 检查通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            if (!notificationManager.areNotificationsEnabled()) {
                Log.w(TAG, "警告: 应用通知权限未开启")
            }
        }
    }

    private fun startMediaProjection(resultCode: Int, data: Intent) {
        try {
            startForeground()
            
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            setupImageReader()
            
            // 录屏开始，更新通知状态
            updateNotification(true)
            
            Log.d(TAG, "MediaProjection启动成功，录屏已开始")
        } catch (e: Exception) {
            Log.e(TAG, "启动MediaProjection失败", e)
            // 启动失败时，更新通知显示错误状态
            updateNotification(false)
        }
    }

    private fun setupImageReader() {
        val displayMetrics = DisplayMetrics()
        val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.let { bounds ->
                displayMetrics.widthPixels = bounds.width()
                displayMetrics.heightPixels = bounds.height()
                displayMetrics.densityDpi = resources.displayMetrics.densityDpi
            }
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
        }

        // 增加maxImages提供缓冲，防止资源冲突
        imageReader = ImageReader.newInstance(
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            PixelFormat.RGBA_8888,
            3  // 增加缓冲区大小，防止IllegalStateException
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

    fun takeScreenshot(): Boolean {
        return synchronized(screenshotLock) {
            try {
                Log.d(TAG, "开始截图，当前时间: ${System.currentTimeMillis()}")
                
                if (mediaProjection == null) {
                    Log.e(TAG, "MediaProjection未初始化")
                    return false
                }
                
                if (isCapturing.get()) {
                    Log.w(TAG, "截图正在进行中，跳过此次请求")
                    return false
                }
                
                isCapturing.set(true)
                
                // 首先尝试高效的回调机制
                var success = takeScreenshotWithCallback()
                
                // 回调失败时使用改进的轮询方式
                if (!success) {
                    Log.d(TAG, "回调方式失败，尝试轮询方式")
                    success = takeScreenshotWithPolling()
                }
                
                Log.d(TAG, "截图结果: ${if (success) "成功" else "失败"}")
                success
            } catch (e: Exception) {
                Log.e(TAG, "截图失败", e)
                false
            } finally {
                isCapturing.set(false)
            }
        }
    }
    
    // 使用回调机制的高效截图方法
    private fun takeScreenshotWithCallback(): Boolean {
        return try {
            imageReader?.let { reader ->
                val latch = CountDownLatch(1)
                var resultBitmap: Bitmap? = null
                var callbackSuccess = false
                
                Log.d(TAG, "使用回调机制截图")
                
                // 清理旧图片
                cleanupOldImages(reader)
                
                // 触发VirtualDisplay重新渲染
                triggerDisplayRender()
                
                val listener = ImageReader.OnImageAvailableListener {
                    try {
                        Log.d(TAG, "回调触发：尝试获取图片")
                        val image = it.acquireLatestImage()
                        Log.d(TAG, "回调获取图片结果: $image")
                        image?.let { img ->
                            resultBitmap = imageToBitmap(img)
                            callbackSuccess = true
                            img.close()
                            Log.d(TAG, "回调成功处理图片")
                        } ?: Log.w(TAG, "回调中图片为null")
                    } catch (e: Exception) {
                        Log.e(TAG, "回调处理图片失败", e)
                    } finally {
                        latch.countDown()
                    }
                }
                
                // 在专用线程设置监听器
                reader.setOnImageAvailableListener(listener, screenshotHandler)
                
                // 等待回调完成或超时（3秒）
                val completed = latch.await(3, TimeUnit.SECONDS)
                
                // 清除监听器
                reader.setOnImageAvailableListener(null, null)
                
                if (completed && callbackSuccess && resultBitmap != null) {
                    val success = saveBitmapToFile(resultBitmap!!)
                    Log.d(TAG, "回调截图${if (success) "成功" else "失败"}")
                    success
                } else {
                    if (!completed) Log.w(TAG, "回调超时，未获取到截图")
                    else Log.w(TAG, "回调失败或未获取到图片")
                    false
                }
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "回调截图失败", e)
            false
        }
    }
    
    // 改进的轮询方式作为备选方案
    private fun takeScreenshotWithPolling(): Boolean {
        return try {
            imageReader?.let { reader ->
                Log.d(TAG, "使用轮询机制截图")
                
                // 清理旧图片
                cleanupOldImages(reader)
                
                // 触发VirtualDisplay重新渲染
                triggerDisplayRender()
                
                // 实现智能重试机制（最多10次，间隔50ms）
                repeat(10) { attempt ->
                    try {
                        Thread.sleep(1200)
                        Log.d(TAG, "轮询第${attempt + 1}次尝试获取图片")
                        val image = reader.acquireLatestImage()
                        Log.d(TAG, "轮询获取图片结果: $image")
                        
                        image?.let {
                            val bitmap = imageToBitmap(it)
                            val success = saveBitmapToFile(bitmap)
                            it.close()
                            Log.d(TAG, "轮询截图在第${attempt + 1}次尝试时${if (success) "成功" else "失败"}")
                            return success
                        }
                    } catch (e: Exception) {
                        if (attempt < 9) {
                            Log.d(TAG, "第${attempt + 1}次截图尝试失败，继续重试: ${e.message}")
                        } else {
                            Log.e(TAG, "所有截图重试均失败", e)
                        }
                    }
                }
                
                Log.e(TAG, "轮询截图：10次重试后仍未成功")
                false
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "轮询截图失败", e)
            false
        }
    }
    
    // 清理ImageReader中的旧图片
    private fun cleanupOldImages(reader: ImageReader) {
        try {
            var image: Image?
            var cleanedCount = 0
            // 清理所有待处理的图片
            do {
                image = reader.acquireLatestImage()
                image?.let {
                    it.close()
                    cleanedCount++
                }
            } while (image != null)
            Log.d(TAG, "已清理ImageReader中的${cleanedCount}张旧图片")
        } catch (e: Exception) {
            // 正常情况，表示没有待处理的图片
            Log.d(TAG, "ImageReader无待清理图片")
        }
    }
    
    // 触发VirtualDisplay重新渲染
    private fun triggerDisplayRender() {
        try {
            // 通过延时让VirtualDisplay有足够时间渲染新内容
            Thread.sleep(100)
            Log.d(TAG, "已触发VirtualDisplay重新渲染")
        } catch (e: Exception) {
            Log.w(TAG, "触发渲染时发生异常", e)
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

    private fun saveBitmapToFile(bitmap: Bitmap): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用MediaStore API
                val resolver = contentResolver
                val fileName = "screenshot_${System.currentTimeMillis()}"
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.png")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
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
                        val compressSuccess = bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                        outputStream.flush()
                        
                        if (compressSuccess) {
                            // 完成写入，清除待处理标志
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val updateValues = android.content.ContentValues().apply {
                                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                                }
                                resolver.update(uri, updateValues, null, null)
                            }
                            
                            Log.d(TAG, "✓ 使用MediaStore API保存截图成功: $uri")
                            Log.d(TAG, "✓ 截图文件名: $fileName.png")
                            return true
                        } else {
                            Log.e(TAG, "截图压缩失败")
                        }
                    }
                } ?: Log.e(TAG, "无法创建MediaStore URI")
            } else {
                // Android 9 及以下使用传统方法 + 强化媒体扫描
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                
                // 确保目录存在
                if (!picturesDir.exists()) {
                    picturesDir.mkdirs()
                }
                
                val fileName = "screenshot_${System.currentTimeMillis()}"
                val file = File(picturesDir, "$fileName.png")
                
                FileOutputStream(file).use { outputStream ->
                    val compressSuccess = bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.flush()
                    outputStream.close()
                    
                    if (!compressSuccess) {
                        Log.e(TAG, "传统方式截图压缩失败")
                        return false
                    }
                }
                
                // 验证文件是否成功创建
                if (!file.exists() || file.length() == 0L) {
                    Log.e(TAG, "截图文件创建失败或为空: ${file.absolutePath}")
                    return false
                }
                
                Log.d(TAG, "✓ 截图文件已创建: ${file.absolutePath} (${file.length()} bytes)")
                
                // 多重媒体扫描策略
                try {
                    // 方法1: 广播媒体扫描
                    val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                    mediaScanIntent.data = android.net.Uri.fromFile(file)
                    sendBroadcast(mediaScanIntent)
                    Log.d(TAG, "已发送截图媒体扫描广播")
                    
                    // 方法2: MediaScannerConnection
                    android.media.MediaScannerConnection.scanFile(
                        this,
                        arrayOf(file.absolutePath),
                        arrayOf("image/png")
                    ) { path, uri ->
                        Log.d(TAG, "✓ 截图MediaScannerConnection扫描完成: $path -> $uri")
                    }
                    
                    // 方法3: 强制刷新整个Pictures目录
                    val refreshIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                    refreshIntent.data = android.net.Uri.fromFile(picturesDir)
                    sendBroadcast(refreshIntent)
                    
                    Log.d(TAG, "✓ 传统方式保存截图成功: ${file.absolutePath}")
                    Log.d(TAG, "✓ 已触发多重媒体扫描")
                    return true
                    
                } catch (scanException: Exception) {
                    Log.w(TAG, "截图媒体扫描时发生警告: ${scanException.message}")
                    // 即使媒体扫描出错，文件也已保存成功
                    return true
                }
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "保存截图失败", e)
            false
        }
    }

    private fun stopMediaProjection() {
        synchronized(screenshotLock) {
            try {
                Log.d(TAG, "stopMediaProjection()开始")
                
                // 等待正在进行的截图完成
                var waitCount = 0
                while (isCapturing.get() && waitCount < 50) {
                    Thread.sleep(100)
                    waitCount++
                }
                
                // 清理ImageReader中所有待处理的图片
                imageReader?.let { reader ->
                    try {
                        var image: Image?
                        do {
                            image = reader.acquireLatestImage()
                            image?.close()
                        } while (image != null)
                        Log.d(TAG, "已清理所有待处理图片")
                    } catch (e: Exception) {
                        Log.d(TAG, "清理图片时无待处理项")
                    }
                }
                
                // 按正确顺序关闭资源：VirtualDisplay → ImageReader → MediaProjection
                virtualDisplay?.release()
                virtualDisplay = null
                
                imageReader?.close()
                imageReader = null
                
                mediaProjection?.stop()
                mediaProjection = null
                
                isRecording = false
                
                // 停止录屏，取消通知
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager?.cancel(NOTIFICATION_ID)
                
                Log.d(TAG, "MediaProjection已完全停止，所有资源已释放")
            } catch (e: Exception) {
                Log.e(TAG, "停止MediaProjection时发生异常", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        Log.d(TAG, "MediaProjectionService正在销毁")
        
        // 确保完全停止MediaProjection和释放所有资源
        stopMediaProjection()
        
        // 停止截图Handler线程
        screenshotHandlerThread?.quitSafely()
        screenshotHandlerThread = null
        screenshotHandler = null
        
        // 双重保险：确保服务销毁时取消通知
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "销毁时取消通知失败", e)
        }
        
        Log.d(TAG, "MediaProjectionService已完全销毁")
    }

    fun isProjectionActive(): Boolean {
        return mediaProjection != null && isRecording
    }
}