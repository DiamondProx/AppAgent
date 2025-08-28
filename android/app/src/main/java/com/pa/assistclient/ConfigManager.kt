package com.pa.assistclient

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * 任务执行配置管理类
 * 对应Python版本的config.yaml配置
 */
data class TaskConfig(
    @SerializedName("MODEL")
    val model: String = "OpenAI",
    
    @SerializedName("OPENAI_API_BASE")
    val openaiApiBase: String = "https://api.siliconflow.cn/v1/chat/completions",
    
    @SerializedName("OPENAI_API_KEY")
    val openaiApiKey: String = "sk-ivaqwrekaoomxwyptrqgalddogeqloogkbnnwonthdjyorjv",
    
    @SerializedName("OPENAI_API_MODEL")
    val openaiApiModel: String = "zai-org/GLM-4.5V",
    
    @SerializedName("MAX_TOKENS")
    val maxTokens: Int = 300,
    
    @SerializedName("TEMPERATURE")
    val temperature: Float = 0.0f,
    
    @SerializedName("REQUEST_INTERVAL")
    val requestInterval: Int = 10,
    
    @SerializedName("DASHSCOPE_API_KEY")
    val dashscopeApiKey: String = "",
    
    @SerializedName("QWEN_MODEL")
    val qwenModel: String = "qwen-vl-max",
    
    @SerializedName("MAX_ROUNDS")
    val maxRounds: Int = 20,
    
    @SerializedName("DARK_MODE")
    val darkMode: Boolean = false,
    
    @SerializedName("MIN_DIST")
    val minDist: Int = 30
)

class ConfigManager private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("task_config", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        @Volatile
        private var INSTANCE: ConfigManager? = null
        
        fun getInstance(context: Context): ConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    fun getConfig(): TaskConfig {
        val configJson = prefs.getString("config", null)
        return if (configJson != null) {
            gson.fromJson(configJson, TaskConfig::class.java)
        } else {
            // 返回默认配置
            TaskConfig()
        }
    }
    
    fun saveConfig(config: TaskConfig) {
        val configJson = gson.toJson(config)
        prefs.edit().putString("config", configJson).apply()
    }
    
    fun resetToDefault() {
        prefs.edit().clear().apply()
    }
    
    // 便捷方法
    fun updateOpenAIConfig(apiKey: String, apiBase: String? = null, model: String? = null) {
        val currentConfig = getConfig()
        val newConfig = currentConfig.copy(
            openaiApiKey = apiKey,
            openaiApiBase = apiBase ?: currentConfig.openaiApiBase,
            openaiApiModel = model ?: currentConfig.openaiApiModel
        )
        saveConfig(newConfig)
    }
    
    fun updateQwenConfig(apiKey: String, model: String? = null) {
        val currentConfig = getConfig()
        val newConfig = currentConfig.copy(
            dashscopeApiKey = apiKey,
            qwenModel = model ?: currentConfig.qwenModel
        )
        saveConfig(newConfig)
    }
    
    fun setModel(model: String) {
        val currentConfig = getConfig()
        val newConfig = currentConfig.copy(model = model)
        saveConfig(newConfig)
    }
}