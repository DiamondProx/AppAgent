package com.pa.assistclient

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class DeviceAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "DeviceAccessibilityService"
        @Volatile
        private var instance: DeviceAccessibilityService? = null
        
        val isServiceRunning: Boolean
            get() = instance != null
        
        /**
         * 获取服务实例，带重连机制
         */
        fun getServiceInstance(): DeviceAccessibilityService? {
            return instance
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DeviceController.accessibilityService = this
        Log.d(TAG, "无障碍服务已连接，服务ID: ${this.hashCode()}")
        
        // 可以在这里发送广播通知应用服务已连接
        sendBroadcast(android.content.Intent("com.pa.assistclient.ACCESSIBILITY_SERVICE_CONNECTED"))
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可以在这里处理无障碍事件
        // 目前主要用于手势和系统操作，不需要特殊处理
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "无障碍服务被解绑，服务ID: ${this.hashCode()}")
        
        // 只有当前实例被解绑时才清空引用
        if (instance == this) {
            instance = null
            DeviceController.accessibilityService = null
        }
        
        // 返回true允许系统重新绑定服务
        return true
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "无障碍服务已销毁，服务ID: ${this.hashCode()}")
        
        // 只有当前实例被销毁时才清空引用
        if (instance == this) {
            instance = null
            DeviceController.accessibilityService = null
        }
    }
}