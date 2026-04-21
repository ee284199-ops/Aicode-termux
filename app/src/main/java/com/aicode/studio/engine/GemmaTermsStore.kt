package com.aicode.studio.engine

import android.content.Context

/** Gallery의 DataStoreRepository.isGemmaTermsOfUseAccepted() / acceptGemmaTermsOfUse() 와 동일. */
object GemmaTermsStore {
    private const val PREFS = "gemma_tos"
    private const val KEY = "accepted"

    fun isAccepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun accept(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}

// Gallery의 MODEL_NAMES_TO_SHOW_GEMMA_LICENSES 에 대응.
// model.series 또는 displayName에 "Gemma"가 포함된 경우 TOS 표시.
fun InferenceConfig.ModelDef.needsGemmaTos(): Boolean =
    series.contains("Gemma", ignoreCase = true)
