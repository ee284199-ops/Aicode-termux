package com.aicode.studio.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.util.Log
import com.aicode.studio.engine.AIInferenceService
import com.aicode.studio.engine.InferenceConfig
import com.aicode.studio.engine.ModelSelectActivity
import com.aicode.studio.util.LogManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * com.aicode.studio.engine.AIInferenceService와 IPC 통신하는 클라이언트.
 * - bindService로 Messenger 연결 (로컬 서비스)
 * - sendPrompt로 프롬프트 전송, 토큰 스트리밍 수신
 * - stopAndDisconnect로 서비스 종료 (앱 종료 / 스위치 OFF 시 호출)
 */
class LocalAIManager(private val context: Context, private val logger: LogManager) {

    companion object {
        private const val TAG = "LiteRT_AIManager"
    }

    interface StreamCallback {
        fun onToken(token: String)
        fun onComplete(fullResponse: String)
        fun onError(error: String)
    }

    private var engineMessenger       : Messenger? = null
    private var replyMessenger        : Messenger? = null
    private var streamCallback        : StreamCallback? = null
    private val responseBuffer        = StringBuilder()
    private var _connected            = false
    private var pendingStatusModelId  : String? = null

    // ── 수신 핸들러 ────────────────────────────────────────────
    private val replyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                InferenceConfig.MSG_TOKEN_STREAM -> {
                    // 실제 LLM 토큰만 처리 (PROGRESS/ERROR는 MSG_STATUS_REPLY 채널로 분리됨)
                    val token = msg.data.getString(InferenceConfig.KEY_TOKEN) ?: ""
                    if (token.isNotEmpty()) {
                        responseBuffer.append(token)
                        streamCallback?.onToken(token)
                    }
                }
                InferenceConfig.MSG_GEN_COMPLETE -> {
                    val full = responseBuffer.toString()
                    responseBuffer.clear()
                    streamCallback?.onComplete(full)
                }
                InferenceConfig.MSG_STATUS_REPLY -> {
                    val info = msg.data.getString("info") ?: ""
                    when {
                        info.startsWith("ERROR:") -> {
                            responseBuffer.clear()
                            streamCallback?.onError(info.removePrefix("ERROR:").trim())
                        }
                        info.startsWith("READY:") -> {
                            val parts = info.removePrefix("READY:").split("|")
                            val modelName = parts.getOrElse(0) { "" }
                            val backend   = parts.getOrElse(1) { "CPU" }
                            logger.logSystem("Local AI 준비됨: $modelName ($backend)")
                            onModelReady?.invoke(modelName, backend)
                        }
                        info.startsWith("MODEL_CHANGED:") ->
                            logger.logSystem("모델 변경: ${info.removePrefix("MODEL_CHANGED:")}")
                        info.startsWith("PROGRESS:") ->
                            logger.logSystem(info)
                        // arg1=1 이면 로드 완료 (info 없는 레거시 경로)
                        msg.arg1 == 1 && info.isEmpty() ->
                            onModelReady?.invoke("", "CPU")
                        else ->
                            if (info.isNotEmpty()) logger.logSystem(info)
                    }
                }
            }
        }
    }

    // ── 서비스 연결 ────────────────────────────────────────────
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            engineMessenger = Messenger(binder)
            _connected = true
            logger.logSystem("Local AI 엔진 연결됨")
            // Immediately query status so the service can call sendStatus(true) with our replyTo set
            pendingStatusModelId?.let { requestModelStatus(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            engineMessenger = null
            _connected = false
            logger.logSystem("Local AI 엔진 연결 끊김 (프로세스 종료 — OOM 가능성)")
            // onServiceDisconnected는 우리가 직접 unbindService()를 호출할 때는 불리지 않음.
            // 오직 서비스 프로세스(:inference)가 예기치 않게 죽었을 때만 호출되므로
            // onServiceDied 콜백을 무조건 호출해도 안전.
            onServiceDied?.invoke()
        }
    }

    fun connect() {
        if (_connected) return
        replyMessenger = Messenger(replyHandler)
        val intent = Intent(context, AIInferenceService::class.java)
        try {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            logger.logError("Local AI 연결 실패: ${e.message}")
        }
    }

    /**
     * Connect to the service AND immediately send MSG_GET_STATUS after binding,
     * so the service can respond with READY: even if the model is already loaded.
     * This fixes the first-message bug where startForegroundService(ACTION_START)
     * calls sendStatus(true) before replyTo is set.
     */
    fun connectForModel(modelId: String) {
        pendingStatusModelId = modelId
        if (_connected) {
            requestModelStatus(modelId)
            return
        }
        replyMessenger = Messenger(replyHandler)
        val intent = Intent(context, AIInferenceService::class.java)
        try {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            logger.logError("Local AI 연결 실패: ${e.message}")
        }
    }

    private fun requestModelStatus(modelId: String) {
        val msg = Message.obtain(null, InferenceConfig.MSG_GET_STATUS).apply {
            data = Bundle().apply { putString(InferenceConfig.KEY_MODEL_ID, modelId) }
            replyTo = replyMessenger
        }
        try { engineMessenger?.send(msg) } catch (_: Exception) {}
    }

    /** 서비스 바인드만 해제 (서비스는 계속 실행 — 액티비티 종료 시 사용) */
    fun disconnect() {
        try { context.unbindService(conn) } catch (_: Exception) {}
        engineMessenger = null
        _connected = false
    }

    /** 서비스 바인드 해제 + 서비스 종료 (스위치 OFF 시 사용) */
    fun stopAndDisconnect() {
        try {
            val stopIntent = Intent(context, AIInferenceService::class.java).apply {
                action = AIInferenceService.ACTION_STOP
            }
            context.startService(stopIntent)
        } catch (_: Exception) {}
        try { context.unbindService(conn) } catch (_: Exception) {}
        engineMessenger = null
        _connected = false
    }

    fun isConnected() = _connected && engineMessenger != null

    /** 모델 로드 완료 시 (modelName, "CPU"/"GPU") 콜백 */
    var onModelReady: ((modelName: String, backend: String) -> Unit)? = null

    /**
     * 추론 서비스 프로세스(:inference)가 OOM 킬 등으로 예기치 않게 종료됐을 때 콜백.
     * AIChatActivity에서 에러 메시지 표시 및 UI 초기화에 사용.
     * (정상 stopAndDisconnect/disconnect 시에는 호출되지 않음)
     */
    var onServiceDied: (() -> Unit)? = null

    fun setStreamCallback(cb: StreamCallback) { streamCallback = cb }
    fun clearStreamCallback() { streamCallback = null }

    // ── 컨텍스트(시스템 프롬프트) 주입 ──────────────────────────
    fun setContext(contextStr: String) {
        val msg = Message.obtain(null, InferenceConfig.MSG_SET_CONTEXT).apply {
            data = Bundle().apply { putString(InferenceConfig.KEY_CONTEXT_JSON, contextStr) }
        }
        try { engineMessenger?.send(msg) } catch (e: Exception) {
            Log.e(TAG, "컨텍스트 전송 실패", e)
        }
    }

    // ── 설정 변경 (Max Tokens 등) ──────────────────────────────
    fun setMaxTokens(maxTokens: Int) {
        val config = JSONObject().apply { put("maxTokens", maxTokens) }
        val msg = Message.obtain(null, InferenceConfig.MSG_SET_CONFIG).apply {
            data = Bundle().apply { putString(InferenceConfig.KEY_CONFIG_JSON, config.toString()) }
        }
        try { engineMessenger?.send(msg) } catch (e: Exception) {
            Log.e(TAG, "설정 전송 실패", e)
        }
    }

    // ── 프롬프트 전송 ─────────────────────────────────────────
    fun sendPrompt(prompt: String) {
        if (!isConnected()) { streamCallback?.onError("Local AI 미연결"); return }
        responseBuffer.clear()
        val msg = Message.obtain(null, InferenceConfig.MSG_SEND_PROMPT).apply {
            data = Bundle().apply { putString(InferenceConfig.KEY_PROMPT, prompt) }
            replyTo = replyMessenger
        }
        try { engineMessenger?.send(msg) } catch (e: Exception) {
            streamCallback?.onError("전송 실패: ${e.message}")
        }
    }

    // ── 대화 기록 주입 (매 요청 전 AIAgentManager가 호출) ─────
    fun importHistory(history: List<Pair<String, String>>) {
        val arr = JSONArray()
        history.forEach { (u, a) ->
            arr.put(JSONObject().apply { put("u", u); put("a", a) })
        }
        val msg = Message.obtain(null, InferenceConfig.MSG_SET_HISTORY).apply {
            data = Bundle().apply { putString(InferenceConfig.KEY_HISTORY, arr.toString()) }
        }
        try { engineMessenger?.send(msg) } catch (_: Exception) {}
    }

    // ── 대화 기록 초기화 ──────────────────────────────────────
    fun clearHistory() {
        val msg = Message.obtain(null, InferenceConfig.MSG_CLEAR_HISTORY)
        try { engineMessenger?.send(msg) } catch (_: Exception) {}
    }

    // ── 생각(thinking) 모드 토글 ─────────────────────────────
    fun setThinking(enabled: Boolean) {
        val msg = Message.obtain(null, InferenceConfig.MSG_SET_THINKING).apply {
            data = Bundle().apply { putBoolean(InferenceConfig.KEY_THINKING, enabled) }
        }
        try { engineMessenger?.send(msg) } catch (e: Exception) {
            Log.e(TAG, "thinking 설정 실패", e)
        }
    }

    // ── 생성 중단 ─────────────────────────────────────────────
    fun stopGeneration() {
        try { engineMessenger?.send(Message.obtain(null, InferenceConfig.MSG_STOP_GEN)) } catch (_: Exception) {}
    }

    /** 모델 관리 화면 열기 (로컬 ModelSelectActivity) */
    fun openManageActivity() {
        try {
            val intent = Intent(context, ModelSelectActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            logger.logError("Local AI 관리 화면 열기 실패: ${e.message}")
        }
    }
}
