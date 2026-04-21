/*
 * Copyright 2026 Google LLC
 */

package com.aicode.studio.ai.gallery.data

import kotlin.math.abs

/**
 * The types of configuration editors available.
 */
enum class ConfigEditorType {
  LABEL,
  NUMBER_SLIDER,
  BOOLEAN_SWITCH,
  SEGMENTED_BUTTON,
  BOTTOMSHEET_SELECTOR,
}

/** The data types of configuration values. */
enum class ValueType {
  INT,
  FLOAT,
  DOUBLE,
  STRING,
  BOOLEAN,
}

data class ConfigKey(val id: String, val label: String)

object ConfigKeys {
  val MAX_TOKENS = ConfigKey("max_tokens", "Max tokens")
  val TOPK = ConfigKey("topk", "TopK")
  val TOPP = ConfigKey("topp", "TopP")
  val TEMPERATURE = ConfigKey("temperature", "Temperature")
  val SUPPORT_IMAGE = ConfigKey("support_image", "Support image")
  val SUPPORT_AUDIO = ConfigKey("support_audio", "Support audio")
  val SUPPORT_THINKING = ConfigKey("support_thinking", "Support thinking")
  val ENABLE_THINKING = ConfigKey("enable_thinking", "Enable thinking")
  val ACCELERATOR = ConfigKey("accelerator", "Accelerator")
  val VISION_ACCELERATOR = ConfigKey("vision_accelerator", "Vision accelerator")
  val COMPATIBLE_ACCELERATORS = ConfigKey("compatible_accelerators", "Compatible accelerators")
  val RESET_CONVERSATION_TURN_COUNT =
    ConfigKey("reset_conversation_turn_count", "Number of turns before the conversation resets")
}

/**
 * Base class for configuration settings.
 */
open class Config(
  val type: ConfigEditorType,
  open val key: ConfigKey,
  open val defaultValue: Any,
  open val valueType: ValueType,
  open val needReinitialization: Boolean = true,
)

class LabelConfig(override val key: ConfigKey, override val defaultValue: String = "") :
  Config(
    type = ConfigEditorType.LABEL,
    key = key,
    defaultValue = defaultValue,
    valueType = ValueType.STRING,
  )

class NumberSliderConfig(
  override val key: ConfigKey,
  val sliderMin: Float,
  val sliderMax: Float,
  override val defaultValue: Float,
  override val valueType: ValueType,
  override val needReinitialization: Boolean = true,
) :
  Config(
    type = ConfigEditorType.NUMBER_SLIDER,
    key = key,
    defaultValue = defaultValue,
    valueType = valueType,
  )

class BooleanSwitchConfig(
  override val key: ConfigKey,
  override val defaultValue: Boolean,
  override val needReinitialization: Boolean = true,
) :
  Config(
    type = ConfigEditorType.BOOLEAN_SWITCH,
    key = key,
    defaultValue = defaultValue,
    valueType = ValueType.BOOLEAN,
  )

class SegmentedButtonConfig(
  override val key: ConfigKey,
  override val defaultValue: String,
  val options: List<String>,
  val allowMultiple: Boolean = false,
) :
  Config(
    type = ConfigEditorType.SEGMENTED_BUTTON,
    key = key,
    defaultValue = defaultValue,
    valueType = ValueType.STRING,
  )

fun convertValueToTargetType(value: Any, valueType: ValueType): Any {
  return when (valueType) {
    ValueType.INT ->
      when (value) {
        is Int -> value
        is Float -> value.toInt()
        is Double -> value.toInt()
        is String -> value.toIntOrNull() ?: 0
        is Boolean -> if (value) 1 else 0
        else -> 0
      }
    ValueType.FLOAT ->
      when (value) {
        is Int -> value.toFloat()
        is Float -> value
        is Double -> value.toFloat()
        is String -> value.toFloatOrNull() ?: 0f
        is Boolean -> if (value) 1f else 0f
        else -> 0f
      }
    ValueType.DOUBLE ->
      when (value) {
        is Int -> value.toDouble()
        is Float -> value.toDouble()
        is Double -> value
        is String -> value.toDoubleOrNull() ?: 0.0
        is Boolean -> if (value) 1.0 else 0.0
        else -> 0.0
      }
    ValueType.BOOLEAN ->
      when (value) {
        is Int -> value != 0
        is Boolean -> value
        is Float -> abs(value) > 1e-6
        is Double -> abs(value) > 1e-6
        is String -> value.isNotEmpty()
        else -> false
      }
    ValueType.STRING -> value.toString()
  }
}

fun createLlmChatConfigs(
  defaultMaxToken: Int = 1024,
  defaultMaxContextLength: Int? = null,
  defaultTopK: Int = 64,
  defaultTopP: Float = 0.95f,
  defaultTemperature: Float = 1.0f,
  accelerators: List<Accelerator> = listOf(Accelerator.GPU),
  supportThinking: Boolean = false,
): List<Config> {
  var maxTokensConfig: Config =
    LabelConfig(key = ConfigKeys.MAX_TOKENS, defaultValue = "$defaultMaxToken")
  if (defaultMaxContextLength != null) {
    maxTokensConfig =
      NumberSliderConfig(
        key = ConfigKeys.MAX_TOKENS,
        sliderMin = 2000f,
        sliderMax = defaultMaxContextLength.toFloat(),
        defaultValue = defaultMaxToken.toFloat(),
        valueType = ValueType.INT,
      )
  }
  val configs =
    listOf(
        maxTokensConfig,
        NumberSliderConfig(
          key = ConfigKeys.TOPK,
          sliderMin = 5f,
          sliderMax = 100f,
          defaultValue = defaultTopK.toFloat(),
          valueType = ValueType.INT,
        ),
        NumberSliderConfig(
          key = ConfigKeys.TOPP,
          sliderMin = 0.0f,
          sliderMax = 1.0f,
          defaultValue = defaultTopP,
          valueType = ValueType.FLOAT,
        ),
        NumberSliderConfig(
          key = ConfigKeys.TEMPERATURE,
          sliderMin = 0.0f,
          sliderMax = 2.0f,
          defaultValue = defaultTemperature,
          valueType = ValueType.FLOAT,
        ),
        SegmentedButtonConfig(
          key = ConfigKeys.ACCELERATOR,
          defaultValue = accelerators[0].label,
          options = accelerators.map { it.label },
        ),
      )
      .toMutableList()

  if (supportThinking) {
    configs.add(BooleanSwitchConfig(key = ConfigKeys.ENABLE_THINKING, defaultValue = false))
  }
  return configs
}
