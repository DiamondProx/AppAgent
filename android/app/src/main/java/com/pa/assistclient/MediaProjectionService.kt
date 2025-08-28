package com.pa.assistclient

import android.app.*
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
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

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

    inner class MediaProjectionBinder : Binder() {
        fun getService(): MediaProjectionService = this@MediaProjectionService
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
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

    fun takeScreenshot(): Boolean {
        return try {
            Log.d(TAG, "takeScreenshot")
            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection未初始化")
                return false
            }

            imageReader?.let { reader ->
                Log.d(TAG, "reader.acquireLatestImage.start()")
                val image = reader.acquireLatestImage()
                Log.d(TAG, "reader.acquireLatestImage.end() image：" + image)
                image?.let {
                    val bitmap = imageToBitmap(it)
                    val success = saveBitmapToFile(bitmap)
                    it.close()
                    Log.d(TAG, "截图${if (success) "成功" else "失败"}")
                    success
                } ?: false
            } ?: false

        } catch (e: Exception) {
            Log.d(TAG, "takeScreenshot Exception:"+e.message)
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
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        mediaProjection = null
        isRecording = false
        
        // 停止录屏，取消通知
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.cancel(NOTIFICATION_ID)
        
        Log.d(TAG, "MediaProjection已停止，通知已取消")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMediaProjection()
        
        // 确保服务销毁时取消通知
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.cancel(NOTIFICATION_ID)
        
        Log.d(TAG, "MediaProjectionService已销毁")
    }

    fun isProjectionActive(): Boolean {
        return mediaProjection != null && isRecording
    }
}