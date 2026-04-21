package com.aicode.studio.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.aicode.studio.ai.gallery.data.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN
import com.aicode.studio.ai.gallery.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import com.aicode.studio.ai.gallery.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import com.aicode.studio.ai.gallery.data.KEY_MODEL_NAME
import com.aicode.studio.ai.gallery.data.KEY_MODEL_TOTAL_BYTES
import com.aicode.studio.ai.gallery.data.KEY_MODEL_URL
import com.aicode.studio.util.PrefsManager
import java.io.File
import java.util.UUID

/**
 * Google AI Edge / LiteRT 전용 모델 관리자.
 * WorkManager 기반으로 다운로드 — Gallery 1.0.11의 DownloadRepository 패턴과 동일.
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val PREFS = "model_prefs"
        const val WORK_TAG_PREFIX = "model_dl_"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val workManager = WorkManager.getInstance(context)

    fun modelsDir(): File =
        (context.getExternalFilesDir("models") ?: context.filesDir.resolve("models"))
            .also { it.mkdirs() }

    fun modelFile(model: InferenceConfig.ModelDef): File = File(modelsDir(), model.modelPath)

    fun isInstalled(model: InferenceConfig.ModelDef): Boolean {
        val dir = modelFile(model)
        if (!dir.exists() || !dir.isDirectory) return false
        val taskFile = File(dir, model.weightName)
        return taskFile.exists() && taskFile.length() >= (model.expectedSizeBytes * 0.98)
    }

    fun markInstalled(model: InferenceConfig.ModelDef) {
        prefs.edit().putBoolean("installed_${model.id}", true).apply()
    }

    var activeModelId: String
        get() = prefs.getString("active_model", "") ?: ""
        set(v) = prefs.edit().putString("active_model", v).apply()

    var executionMode: InferenceConfig.ExecutionMode
        get() = try {
            InferenceConfig.ExecutionMode.valueOf(prefs.getString("exec_mode", "AUTO") ?: "AUTO")
        } catch (_: Exception) { InferenceConfig.ExecutionMode.AUTO }
        set(v) = prefs.edit().putString("exec_mode", v.name).apply()

    /**
     * WorkManager로 다운로드 시작.
     * Gallery의 DownloadRepository.downloadModel() 패턴과 동일.
     * @return 생성된 WorkRequest의 UUID (ViewModel에서 진행률 관찰에 사용)
     */
    fun startSystemDownload(model: InferenceConfig.ModelDef): UUID {
        val hfToken = PrefsManager.getHfToken(context).ifBlank { null }
        val downloadUrl =
            "https://huggingface.co/${model.repoPath}/resolve/${model.commitHash}/${model.weightName}?download=true"

        val inputDataBuilder = Data.Builder()
            .putString(KEY_MODEL_NAME, model.displayName)
            .putString(KEY_MODEL_URL, downloadUrl)
            .putString(KEY_MODEL_DOWNLOAD_MODEL_DIR, model.modelPath)
            .putString(KEY_MODEL_DOWNLOAD_FILE_NAME, model.weightName)
            .putLong(KEY_MODEL_TOTAL_BYTES, model.expectedSizeBytes)

        if (!hfToken.isNullOrBlank()) {
            inputDataBuilder.putString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN, hfToken)
        }

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(inputDataBuilder.build())
            .addTag("${WORK_TAG_PREFIX}${model.id}")
            .build()

        // model.id를 고유 작업 이름으로 사용 → 중복 실행 방지 (Gallery와 동일)
        workManager.enqueueUniqueWork(model.id, ExistingWorkPolicy.REPLACE, request)
        Log.i(TAG, "다운로드 WorkRequest 등록: ${model.displayName} (${request.id})")
        return request.id
    }

    fun cancelDownload(modelId: String) {
        workManager.cancelAllWorkByTag("${WORK_TAG_PREFIX}${modelId}")
    }

    fun deleteModel(model: InferenceConfig.ModelDef): Boolean {
        cancelDownload(model.id)
        val ok = modelFile(model).deleteRecursively()
        if (ok) prefs.edit().remove("installed_${model.id}").apply()
        if (activeModelId == model.id) activeModelId = ""
        return ok
    }
}
