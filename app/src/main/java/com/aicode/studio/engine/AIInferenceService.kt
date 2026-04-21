package com.aicode.studio.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aicode.studio.ai.gallery.data.Accelerator
import com.aicode.studio.ai.gallery.data.Model
import com.aicode.studio.ai.gallery.ui.llmchat.LlmChatModelHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 백그라운드 AI 추론 서비스 (Google AI Edge / LiteRT 버전).
 * Gallery 1.0.11 아키텍처와 동일하게 구현됨.
 */
class AIInferenceService : Service() {

    companion object {
        private const val TAG = "LiteRT_Service"
        const val ACTION_START = InferenceConfig.ACTION_START
        const val ACTION_STOP  = InferenceConfig.ACTION_STOP
    }

    private val messenger = Messenger(IncomingHandler())
    private var replyTo: Messenger? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val inferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineMutex = Mutex()
    
    private var currentModelId: String? = null
    private var currentModel: Model? = null
    
    private var isThinkingEnabled = true

    @Volatile private var stopRequested = false

    private var pendingPrompt: String? = null
    /** importHistory()로 받은 최근 대화 포맷 텍스트 */
    @Volatile private var pendingHistory: String = ""
    /** setContext()로 받은 시스템 프롬프트 — 모델 준비 후 history와 합쳐서 적용 */
    @Volatile private var pendingContext: String = ""

    enum class State { IDLE, LOADING, GENERATING, ERROR }
    private var state = State.IDLE

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                InferenceConfig.NOTIF_ID,
                createNotification("AI 엔진 대기 중…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(InferenceConfig.NOTIF_ID, createNotification("AI 엔진 대기 중…"))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.getBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            val modelId = intent.getStringExtra(InferenceConfig.KEY_MODEL_ID)
            if (modelId != null) checkAndLoad(modelId)
        } else if (action == ACTION_STOP) {
            releaseCurrentModel()
            stopForeground(true)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        inferScope.cancel()
        releaseCurrentModel()
        super.onDestroy()
    }

    /** 앱을 최근 목록에서 스와이프로 제거할 때 → GPU 자원 즉시 해제 후 서비스 종료 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        currentModel?.let { LlmChatModelHelper.stopResponse(it) } // 생성 중이면 즉시 중단
        stopForeground(true)
        stopSelf()
    }

    private fun releaseCurrentModel() {
        val model = currentModel ?: return
        currentModel = null
        currentModelId = null
        // engine.close() JNI 호출이 오래 걸릴 수 있으므로 별도 스레드에서 비동기 처리
        // (onDestroy 메인스레드 블로킹 → system ANR 방지)
        Thread {
            try { LlmChatModelHelper.cleanUp(model) {} } catch (_: Throwable) {}
        }.also { it.isDaemon = true }.start()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                InferenceConfig.MSG_SEND_PROMPT -> {
                    replyTo = msg.replyTo
                    val prompt = msg.data.getString(InferenceConfig.KEY_PROMPT) ?: ""
                    submitPrompt(prompt)
                }
                InferenceConfig.MSG_GET_STATUS -> {
                    replyTo = msg.replyTo
                    val modelId = msg.data.getString(InferenceConfig.KEY_MODEL_ID) ?: ""
                    checkAndLoad(modelId)
                }
                InferenceConfig.MSG_SET_THINKING -> {
                    isThinkingEnabled = msg.data.getBoolean(InferenceConfig.KEY_THINKING, true)
                }
                InferenceConfig.MSG_CLEAR_HISTORY -> {
                    inferScope.launch {
                        engineMutex.withLock {
                            currentModel?.let { LlmChatModelHelper.resetConversation(it) }
                        }
                    }
                }
                InferenceConfig.MSG_SET_HISTORY -> {
                    val historyJson = msg.data.getString(InferenceConfig.KEY_HISTORY) ?: ""
                    pendingHistory = formatHistoryForContext(historyJson)
                }
                InferenceConfig.MSG_SET_CONTEXT -> {
                    val contextStr = msg.data.getString(InferenceConfig.KEY_CONTEXT_JSON) ?: return
                    pendingContext = contextStr
                    // resetConversation은 submitPrompt 내부 mutex 블록에서 원자적으로 처리
                    // (별도 coroutine 금지 — sendMessageAsync와의 race condition → SIGSEGV)
                }
                InferenceConfig.MSG_STOP_GEN -> {
                    // cancelProcess()는 inference 중 어느 스레드에서도 안전하게 호출 가능
                    // mutex 안에서 호출하면 inference 완료까지 block되어 stop이 동작 안 함
                    currentModel?.let { LlmChatModelHelper.stopResponse(it) }
                }
                InferenceConfig.MSG_SET_CONFIG -> {
                    val configJson = msg.data.getString(InferenceConfig.KEY_CONFIG_JSON) ?: return
                    try {
                        val obj = org.json.JSONObject(configJson)
                        val maxTokens = obj.optInt("maxTokens", -1)
                        if (maxTokens > 0) {
                            currentModel?.let { model ->
                                model.configValues[com.aicode.studio.ai.gallery.data.ConfigKeys.MAX_TOKENS.label] = maxTokens
                                Log.i(TAG, "Max tokens updated to: $maxTokens")
                            }
                        }
                    } catch (e: Exception) { Log.e(TAG, "Config parse error", e) }
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    private fun checkAndLoad(modelId: String) {
        if (currentModelId == modelId && currentModel != null) {
            sendStatus(true)
            return
        }
        val modelDef = InferenceConfig.ALL_MODELS.find { it.id == modelId } ?: return
        loadModel(modelDef) { sendStatus(true) }
    }

    private fun loadModel(modelDef: InferenceConfig.ModelDef, afterLoad: (() -> Unit)? = null) {
        if (state == State.LOADING) return
        
        setState(State.LOADING)
        broadcastRaw("PROGRESS: 5% - ${modelDef.displayName} 로딩 시작…")
        mainHandler.post { updateNotif("${modelDef.displayName} 로드 중…") }

        inferScope.launch {
            engineMutex.withLock {
                try {
                    val mm = ModelManager(applicationContext)
                    val modelDir = mm.modelFile(modelDef)
                    
                    val configStr = com.aicode.studio.util.PrefsManager.getModelConfig(applicationContext, modelDef.id)
                    val configJson = org.json.JSONObject(configStr)
                    
                    // 기본값 1024 — Gallery 참조 앱과 동일 (RAM 절약: 4096 대비 ~800MB 감소)
                    // 사용자가 설정에서 늘릴 수 있음. 최대 8192로 제한
                    val userMaxToken = configJson.optInt("maxTokens", 1024).coerceAtMost(8192)
                    val userTopK = configJson.optInt("topK", com.aicode.studio.ai.gallery.data.DEFAULT_TOPK)
                    val userTopP = configJson.optDouble("topP", com.aicode.studio.ai.gallery.data.DEFAULT_TOPP.toDouble()).toFloat()
                    val userTemp = configJson.optDouble("temperature", com.aicode.studio.ai.gallery.data.DEFAULT_TEMPERATURE.toDouble()).toFloat()

                    // Execution Mode 반영
                    val mode = mm.executionMode
                    val preferredAccelerator = when (mode) {
                        InferenceConfig.ExecutionMode.GPU -> Accelerator.GPU
                        InferenceConfig.ExecutionMode.CPU -> Accelerator.CPU
                        else -> Accelerator.GPU // AUTO -> GPU 우선 시도
                    }

                    val galleryModel = Model(
                        name = modelDef.id,
                        displayName = modelDef.displayName,
                        localModelFilePathOverride = File(modelDir, modelDef.weightName).absolutePath,
                        llmSupportImage = modelDef.llmSupportImage,
                        isLlm = true,
                        runtimeType = com.aicode.studio.ai.gallery.data.RuntimeType.LITERT_LM,
                        configs = com.aicode.studio.ai.gallery.data.createLlmChatConfigs(
                            defaultMaxToken = userMaxToken,
                            defaultTopK = userTopK,
                            defaultTopP = userTopP,
                            defaultTemperature = userTemp,
                            accelerators = listOf(preferredAccelerator, Accelerator.CPU),
                            supportThinking = modelDef.supportsThinking
                        )
                    )
                    galleryModel.preProcess()

                    broadcastRaw("PROGRESS: 20% - LiteRT 엔진 초기화 중 (${mode.name})…")
                    
                    var loadError: String? = null
                    var actualBackend: String = "CPU"
                    val completionDeferred = CompletableDeferred<Unit>()

                    LlmChatModelHelper.initialize(
                        context = applicationContext,
                        model = galleryModel,
                        supportImage = galleryModel.llmSupportImage,
                        supportAudio = false,
                        onDone = { result ->
                            if (result == "GPU" || result == "CPU" || result == "NPU") {
                                actualBackend = result
                            } else if (result.isNotEmpty()) {
                                loadError = result
                            }
                            completionDeferred.complete(Unit)
                        },
                        coroutineScope = inferScope
                    )

                    completionDeferred.await()

                    if (loadError != null) {
                        throw Exception(loadError)
                    }

                    broadcastRaw("PROGRESS: 100% - ${modelDef.displayName} 준비 완료! ($actualBackend)")
                    currentModel = galleryModel
                    currentModelId = modelDef.id
                    setState(State.IDLE)
                    // pendingContext/pendingHistory는 submitPrompt(pendingPrompt) 시 적용됨

                    mainHandler.post {
                        updateNotif("${modelDef.displayName} 준비됨 ($actualBackend)")
                        sendStatus(true)
                        afterLoad?.invoke()
                    }

                    pendingPrompt?.let { pending ->
                        pendingPrompt = null
                        submitPrompt(pending)
                    }

                } catch (e: Throwable) {
                    Log.e(TAG, "로드 중 에러 발생", e)
                    broadcastRaw("ERROR: 모델 로드 실패 - ${e.message}")
                    setState(State.ERROR)
                }
            }
        }
    }

    private fun submitPrompt(prompt: String) {
        if (state == State.LOADING) {
            pendingPrompt = prompt
            broadcastRaw("INFO: 엔진 로딩 중…")
            return
        }
        val model = currentModel ?: run {
            broadcastRaw("ERROR: 엔진이 준비되지 않았습니다.")
            return
        }

        inferScope.launch {
            engineMutex.withLock {
                try {
                    // ① context + history를 conversation에 적용 (resetConversation)
                    //    sendMessageAsync와 같은 mutex 블록 안에서 처리해야 race condition 방지
                    val ctx = pendingContext
                    val hist = pendingHistory
                    if (ctx.isNotEmpty() || hist.isNotEmpty()) {
                        // 히스토리를 시스템 프롬프트 앞쪽에 배치:
                        // 소형 모델은 lost-in-the-middle 현상으로 긴 컨텍스트 후반부를 잘 무시함
                        // hist → ctx 순서로 배치하여 히스토리가 더 강하게 반영되도록 함
                        val fullContext = when {
                            hist.isNotEmpty() && ctx.isNotEmpty() -> "$hist\n\n$ctx"
                            hist.isNotEmpty() -> hist
                            else -> ctx
                        }
                        pendingHistory = ""
                        pendingContext = ""
                        LlmChatModelHelper.resetConversation(
                            model,
                            systemInstruction = com.google.ai.edge.litertlm.Contents.of(fullContext)
                        )
                    }

                    // ② inference 실행 — CompletableDeferred로 완료까지 mutex 유지
                    //    mutex를 완료 전에 해제하면 다음 resetConversation이
                    //    GPU 스레드가 쓰는 conversation을 해제 → SIGSEGV
                    setState(State.GENERATING)
                    val inferDone = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val extraContext = if (isThinkingEnabled) mapOf("enable_thinking" to "true") else null

                    LlmChatModelHelper.runInference(
                        model = model,
                        input = prompt,
                        resultListener = { partial, done, thinking ->
                            if (done) {
                                // MSG_GEN_COMPLETE + setState는 아래 inferDone.await() 이후
                                // 코루틴 블록에서 처리 (try-catch 보장, GPU 스레드에서 직접 전송 금지)
                                inferDone.complete(Unit)
                            } else {
                                if (thinking != null && thinking.isNotEmpty()) {
                                    broadcastToken("<thought>$thinking</thought>")
                                }
                                if (partial.isNotEmpty()) {
                                    broadcastToken(partial)
                                }
                            }
                        },
                        cleanUpListener = {},
                        onError = { error ->
                            broadcastRaw("ERROR: $error")
                            setState(State.ERROR)
                            inferDone.complete(Unit)
                        },
                        coroutineScope = inferScope,
                        extraContext = extraContext
                    )

                    inferDone.await() // inference 완료까지 mutex 유지 (SIGSEGV 방지 핵심)

                    // ③ 생성 완료 직후 KV cache 즉시 해제
                    //    사용자가 키보드를 열기 전에 GPU 메모리를 돌려줌으로써 OOM 방지.
                    //    다음 sendPrompt() 시 submitPrompt()에서 pendingContext/pendingHistory로
                    //    resetConversation(systemInstruction) 재주입되므로 컨텍스트 손실 없음.
                    try { LlmChatModelHelper.resetConversation(model) } catch (_: Throwable) {}

                    setState(State.IDLE)

                    // ④ 완료 알림 — DeadObjectException 등 바인더 오류를 조용히 무시
                    //    (AIChatActivity가 백 버튼으로 종료됐을 경우 replyTo가 dead 상태일 수 있음)
                    try {
                        replyTo?.send(Message.obtain(null, InferenceConfig.MSG_GEN_COMPLETE))
                    } catch (_: Exception) {}

                } catch (e: Exception) {
                    Log.e(TAG, "추론 에러", e)
                    broadcastRaw("ERROR: ${e.message}")
                    setState(State.ERROR)
                }
            }
        }
    }

    private fun setState(s: State) { state = s }

    private fun broadcastToken(token: String) {
        try {
            replyTo?.send(Message.obtain(null, InferenceConfig.MSG_TOKEN_STREAM).apply {
                data = Bundle().apply { putString(InferenceConfig.KEY_TOKEN, token) }
            })
        } catch (_: Exception) {
            // AIChatActivity가 종료된 경우(DeadObjectException 등) 무시
            // inferDone.complete()는 onDone()에서 정상 호출되므로 데드락 없음
        }
    }

    private fun broadcastRaw(text: String) {
        try {
            replyTo?.send(Message.obtain(null, InferenceConfig.MSG_STATUS_REPLY).apply {
                arg1 = 0
                data = Bundle().apply { putString("info", text) }
            })
        } catch (_: Exception) {
            // AIChatActivity가 종료된 경우(DeadObjectException 등) 무시
        }
    }

    private fun sendStatus(loaded: Boolean) {
        val actualLoaded = loaded && currentModel != null && currentModel?.instance != null
        val modelName = InferenceConfig.ALL_MODELS.find { it.id == currentModelId }?.displayName ?: ""
        
        replyTo?.send(Message.obtain(null, InferenceConfig.MSG_STATUS_REPLY).apply {
            arg1 = if (actualLoaded) 1 else 0
            data = Bundle().apply {
                putString("info", if (actualLoaded) "READY:$modelName|LITERT" else "NOT_READY")
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(InferenceConfig.NOTIF_CHANNEL_ID, "AI Inference", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    private fun createNotification(content: String) = NotificationCompat.Builder(this, InferenceConfig.NOTIF_CHANNEL_ID)
        .setContentTitle("AIDE 로컬 AI 엔진 (LiteRT)")
        .setContentText(content)
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun updateNotif(content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(InferenceConfig.NOTIF_ID, createNotification(content))
    }

    /**
     * JSON 히스토리 배열([{"u":"...","a":"..."}, ...])을
     * LLM 시스템 프롬프트에 삽입할 텍스트 블록으로 변환.
     *
     * 소형 모델(Gemma 4B급)은 약한 지시를 무시하는 경향이 있으므로
     * 영어로 강한 명령 + 명시적 규칙을 사용하여 히스토리 준수를 강제함.
     * AIAgentManager.recentLocalHistory()가 max ~600자로 이미 제한하므로 토큰 걱정 없음.
     */
    private fun formatHistoryForContext(historyJson: String): String {
        if (historyJson.isEmpty()) return ""
        return try {
            val arr = org.json.JSONArray(historyJson)
            if (arr.length() == 0) return ""
            buildString {
                append("=== CONVERSATION HISTORY (MUST FOLLOW) ===\n")
                append("You MUST strictly follow the conversation history below.\n")
                append("Do NOT ignore any instructions or context from previous turns.\n\n")
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val u = item.optString("u", "").trim()
                    val a = item.optString("a", "").trim()
                    if (u.isNotEmpty()) append("User: $u\n")
                    if (a.isNotEmpty()) append("Assistant: $a\n")
                    if (i < arr.length() - 1) append("\n")
                }
                append("\nRules:\n")
                append("- Always answer consistently with the above conversation history\n")
                append("- Honor any instructions or agreements made in previous turns\n")
                append("- Maintain context and continuity from prior exchanges\n")
                append("=== END OF HISTORY ===")
            }
        } catch (e: Exception) {
            Log.e(TAG, "히스토리 포맷 실패", e)
            ""
        }
    }
}
