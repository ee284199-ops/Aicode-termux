/*
 * Copyright 2026 Google LLC
 */

package com.aicode.studio.ai.gallery.data

import android.content.Context
import com.google.gson.annotations.SerializedName
import java.io.File

const val IMPORTS_DIR = "__imports"
private val NORMALIZE_NAME_REGEX = Regex("[^a-zA-Z0-9]")

enum class RuntimeType {
  @SerializedName("unknown") UNKNOWN,
  @SerializedName("litert_lm") LITERT_LM,
}

data class Model(
  val name: String,
  val displayName: String = "",
  val info: String = "",
  var configs: List<Config> = listOf(),
  val learnMoreUrl: String = "",
  val bestForTaskIds: List<String> = listOf(),
  val minDeviceMemoryInGb: Int? = null,
  val url: String = "",
  val sizeInBytes: Long = 0L,
  val downloadFileName: String = "_",
  val version: String = "_",
  val isLlm: Boolean = false,
  val runtimeType: RuntimeType = RuntimeType.UNKNOWN,
  val localFileRelativeDirPathOverride: String = "",
  val localModelFilePathOverride: String = "",
  val showRunAgainButton: Boolean = true,
  val showBenchmarkButton: Boolean = true,
  val isZip: Boolean = false,
  val unzipDir: String = "",
  val llmSupportImage: Boolean = false,
  val llmSupportAudio: Boolean = false,
  val llmSupportThinking: Boolean = false,
  val llmMaxToken: Int = 0,
  val accelerators: List<Accelerator> = listOf(),
  val visionAccelerator: Accelerator = Accelerator.GPU,
  val imported: Boolean = false,

  var normalizedName: String = "",
  var instance: Any? = null,
  var initializing: Boolean = false,
  var cleanUpAfterInit: Boolean = false,
  var configValues: MutableMap<String, Any> = mutableMapOf(),
  var prevConfigValues: Map<String, Any> = mapOf(),
  var totalBytes: Long = 0L,
  var accessToken: String? = null,
) {
  init {
    normalizedName = NORMALIZE_NAME_REGEX.replace(name, "_")
  }

  fun preProcess() {
    val configValues: MutableMap<String, Any> = mutableMapOf()
    for (config in this.configs) {
      configValues[config.key.label] = config.defaultValue
    }
    this.configValues = configValues
    this.totalBytes = this.sizeInBytes
  }

  fun getPath(context: Context, fileName: String = downloadFileName): String {
    if (imported) {
      return listOf(context.getExternalFilesDir(null)?.absolutePath ?: "", fileName)
        .joinToString(File.separator)
    }
    if (localModelFilePathOverride.isNotEmpty()) {
      return localModelFilePathOverride
    }
    val baseDir =
      listOf(context.getExternalFilesDir(null)?.absolutePath ?: "", normalizedName, version)
        .joinToString(File.separator)
    return listOf(baseDir, fileName).joinToString(File.separator)
  }

  fun getIntConfigValue(key: ConfigKey, defaultValue: Int = 0): Int {
    return getTypedConfigValue(key = key, valueType = ValueType.INT, defaultValue = defaultValue) as Int
  }

  fun getFloatConfigValue(key: ConfigKey, defaultValue: Float = 0.0f): Float {
    return getTypedConfigValue(key = key, valueType = ValueType.FLOAT, defaultValue = defaultValue) as Float
  }

  fun getBooleanConfigValue(key: ConfigKey, defaultValue: Boolean = false): Boolean {
    return getTypedConfigValue(key = key, valueType = ValueType.BOOLEAN, defaultValue = defaultValue) as Boolean
  }

  fun getStringConfigValue(key: ConfigKey, defaultValue: String = ""): String {
    return getTypedConfigValue(key = key, valueType = ValueType.STRING, defaultValue = defaultValue) as String
  }

  private fun getTypedConfigValue(key: ConfigKey, valueType: ValueType, defaultValue: Any): Any {
    return convertValueToTargetType(
      value = configValues.getOrDefault(key.label, defaultValue),
      valueType = valueType,
    )
  }
}
