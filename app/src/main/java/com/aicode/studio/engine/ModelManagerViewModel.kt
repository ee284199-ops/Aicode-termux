package com.aicode.studio.engine

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.aicode.studio.ai.gallery.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import com.aicode.studio.ai.gallery.data.KEY_MODEL_DOWNLOAD_RATE
import com.aicode.studio.ai.gallery.data.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES
import com.aicode.studio.ai.gallery.data.KEY_MODEL_DOWNLOAD_REMAINING_MS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val ctx = application.applicationContext
    private val modelManager = ModelManager(ctx)
    private val workManager = WorkManager.getInstance(ctx)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    data class DownloadState(
        val isDownloading: Boolean = false,
        val receivedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val bytesPerSecond: Long = 0L,
        val remainingMs: Long = 0L,
        val isFailed: Boolean = false,
        val errorMessage: String = ""
    ) {
        val percent: Int get() = if (totalBytes > 0) (receivedBytes * 100 / totalBytes).toInt() else 0
        val speedText: String get() {
            val kbps = bytesPerSecond / 1024f
            return if (kbps > 1024) "${"%.1f".format(kbps / 1024)} MB/s" else "${kbps.toInt()} KB/s"
        }
        val remainingSec: Int get() = (remainingMs / 1000).toInt()
    }

    data class UiState(
        val models: List<InferenceConfig.ModelDef> = InferenceConfig.ALL_MODELS,
        val activeModelId: String = "",
        val executionMode: InferenceConfig.ExecutionMode = InferenceConfig.ExecutionMode.AUTO,
        val downloadStates: Map<String, DownloadState> = emptyMap()
    )

    init {
        _uiState.value = _uiState.value.copy(
            activeModelId = modelManager.activeModelId,
            executionMode = modelManager.executionMode
        )
    }

    fun selectModel(modelId: String) {
        modelManager.activeModelId = modelId
        _uiState.value = _uiState.value.copy(activeModelId = modelId)
    }

    fun setExecutionMode(mode: InferenceConfig.ExecutionMode) {
        modelManager.executionMode = mode
        _uiState.value = _uiState.value.copy(executionMode = mode)
    }

    /** Gallery의 downloadModel() — TOS 확인 후 이 함수 호출 */
    fun startDownload(model: InferenceConfig.ModelDef) {
        updateDownloadState(model.id, DownloadState(isDownloading = true, totalBytes = model.expectedSizeBytes))
        val workId = modelManager.startSystemDownload(model)
        observeDownload(model.id, workId, model.expectedSizeBytes)
    }

    fun cancelDownload(model: InferenceConfig.ModelDef) {
        modelManager.cancelDownload(model.id)
        removeDownloadState(model.id)
    }

    private fun observeDownload(modelId: String, @Suppress("UNUSED_PARAMETER") workId: UUID, totalBytes: Long) {
        workManager.getWorkInfosForUniqueWorkFlow(modelId)
            .onEach { workInfoList ->
                val workInfo = workInfoList.firstOrNull() ?: return@onEach
                when (workInfo.state) {
                    WorkInfo.State.ENQUEUED ->
                        updateDownloadState(modelId, DownloadState(isDownloading = true, totalBytes = totalBytes))
                    WorkInfo.State.RUNNING -> {
                        val received = workInfo.progress.getLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, 0L)
                        val rate = workInfo.progress.getLong(KEY_MODEL_DOWNLOAD_RATE, 0L)
                        val remaining = workInfo.progress.getLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, 0L)
                        updateDownloadState(modelId, DownloadState(
                            isDownloading = true,
                            receivedBytes = received,
                            totalBytes = totalBytes,
                            bytesPerSecond = rate,
                            remainingMs = remaining
                        ))
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        InferenceConfig.ALL_MODELS.find { it.id == modelId }
                            ?.let { modelManager.markInstalled(it) }
                        removeDownloadState(modelId)
                    }
                    WorkInfo.State.FAILED -> {
                        val errorMsg = workInfo.outputData.getString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE) ?: "알 수 없는 오류"
                        updateDownloadState(modelId, DownloadState(isFailed = true, errorMessage = errorMsg))
                    }
                    WorkInfo.State.CANCELLED -> removeDownloadState(modelId)
                    else -> {}
                }
            }
            .launchIn(viewModelScope)
    }

    private fun updateDownloadState(modelId: String, state: DownloadState) {
        val cur = _uiState.value
        _uiState.value = cur.copy(downloadStates = cur.downloadStates + (modelId to state))
    }

    private fun removeDownloadState(modelId: String) {
        val cur = _uiState.value
        _uiState.value = cur.copy(downloadStates = cur.downloadStates - modelId)
    }

    fun isInstalled(model: InferenceConfig.ModelDef): Boolean = modelManager.isInstalled(model)
}
