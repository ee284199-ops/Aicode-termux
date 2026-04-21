package com.aicode.studio.ai

import com.aicode.studio.util.LogManager
import com.aicode.studio.termux.shared.termux.TermuxConstants
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI 에이전트 매니저
 * - Cloud AI (Gemini API) 또는 Local AI (com.aicode.engine) 라우팅
 * - MemoryManager로 기억 파일 관리 (트리밍 + 아카이브 + 요약 인덱스)
 */
class AIAgentManager(private val logger: LogManager) {

    data class ApiConfig(val key: String, val model: String)

    interface Callback {
        fun onThought(thought: String)
        fun onResponse(response: String)
        fun onFileUpdated(path: String, success: Boolean, msg: String)
        fun onDeleteRequested(path: String, onConfirm: (Boolean) -> Unit)
        fun onCompleted(summary: String)
        fun onError(error: String)
        fun onStatusChanged(status: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val prompt = PromptBuilder()
    private var history = JSONArray()
    private var root: File? = null
    private var cb: Callback? = null
    private var running = false

    // Cloud AI
    private var apiConfigs = mutableListOf<ApiConfig>()
    private var currentApiIdx = 0

    // Developer AI (Custom)
    private var devMode: String? = null // null, "grok-4-fast-reasoning", "gpt-oss-120b"
    private val DEV_API_URL = "https://openai.junioralive.workers.dev/v1/chat/completions"
    private val DEV_API_KEY = "ish-7f9e2c1b-5c8a-4b0f-9a7d-1e5c3b2a9f74"

    // Local AI
    private var localAI: LocalAIManager? = null
    private var localMode = false
    private var localModelName = "Local AI"
    private var appContext: android.content.Context? = null

    // Memory
    private var memoryMgr: MemoryManager? = null
    private var archiveSummary: MutableList<MemoryManager.ArchiveEntry> = mutableListOf()
    private var projectContext: String = "" // AI가 직접 관리하는 전역 맥락
    private val localConvHistory = mutableListOf<Pair<String, String>>()
    private var lastSysPrompt: String? = null

    // Session Memory (Claude Code 방식 - 구조화된 마크다운 파일)
    private var sessionMemory: String = ""            // 현재 로드된 세션 메모리
    private var exchangeCountSinceMemUpdate = 0       // 마지막 갱신 이후 교환 횟수
    private val SESSION_MEMORY_UPDATE_INTERVAL = 3    // N번 교환마다 갱신 시도

    private var iter = 0
    private val MAX_ITER = 15

    // Auto Compact (Claude Code autoCompactIfNeeded 이식)
    // Cloud/Dev AI: history > COMPACT_THRESHOLD → 9섹션 요약 후 삽입
    // Local AI: history > LOCAL_COMPACT_THRESHOLD → API 없이 트림 (세션 메모리가 컨텍스트 담당)
    private val COMPACT_THRESHOLD        = 30   // Cloud/Dev: compact 트리거
    private val COMPACT_KEEP_RECENT      = 10   // Cloud/Dev: compact 후 보존할 최근 메시지 수
    private val LOCAL_COMPACT_THRESHOLD  = 20   // Local: 더 엄격 (소형 모델 컨텍스트 제한)
    private val LOCAL_COMPACT_KEEP       = 10   // Local: compact 후 보존 수
    // 세션 메모리 재압축 임계값 (~6000 tokens — 소형 모델 8k window 기준)
    private val SESSION_MEMORY_RECOMPACT_CHARS = 24_000

    // ── 설정 ──────────────────────────────────────────────────

    fun setCallback(c: Callback) { cb = c }

    fun setApiConfigs(configs: List<ApiConfig>) {
        apiConfigs = configs.toMutableList()
        currentApiIdx = 0
    }

    fun setAppContext(ctx: android.content.Context) { appContext = ctx.applicationContext }

    fun setLocalAI(manager: LocalAIManager?, enabled: Boolean) {
        localAI = manager
        localMode = enabled
        if (!enabled) lastSysPrompt = null
    }

    fun setLocalModelName(name: String) { localModelName = name }

    fun isRunning() = running

    fun getFormattedHistory(): List<com.aicode.studio.ChatMessage> {
        val result = mutableListOf<com.aicode.studio.ChatMessage>()
        for (i in 0 until history.length()) {
            val h = history.optJSONObject(i) ?: continue
            val role = h.optString("role")
            val parts = h.optJSONArray("parts") ?: continue
            if (parts.length() == 0) continue
            var text = parts.getJSONObject(0).optString("text", "")
            
            // 0. 내부 피드백 (System Report) 무시
            if (text.startsWith("## System Report")) continue

            // 1. 사용자 메시지에서 '## Request' 접두사 제거
            val r = if (role == "user") {
                if (text.startsWith("## Request\n")) text = text.removePrefix("## Request\n")
                "user"
            } else if (role == "model") "ai" else "system"
            
            // 2. AI 응답 (Provenance 제거)
            if (r == "ai" && text.startsWith("[Result from")) {
                val idx = text.indexOf("]\n")
                if (idx != -1) text = text.substring(idx + 2)
            }

            // 3. JSON 응답 분리
            val jsonStr = extractJson(text)
            if (jsonStr != null) {
                try {
                    val obj = JSONObject(jsonStr)
                    val t = obj.optString("thought")
                    val res = obj.optString("response")
                    // "사용자에게 직접 전달할 답변" 예시 텍스트인 경우 무시 (시스템 프롬프트 잔재 방지)
                    if (res == "사용자에게 직접 전달할 답변") continue
                    result.add(com.aicode.studio.ChatMessage(r, res, if (t.isNotEmpty()) t else null))
                    continue
                } catch (_: Exception) {}
            }

            // 4. XML 태그 분리
            val thoughtMatch = Regex("<thought>([\\s\\S]*?)</thought>").find(text)
                ?: Regex("<think>([\\s\\S]*?)</think>").find(text)
            
            if (thoughtMatch != null) {
                val t = thoughtMatch.groupValues[1].trim()
                val res = text.replace(thoughtMatch.value, "").trim()
                result.add(com.aicode.studio.ChatMessage(r, res, if (t.isNotEmpty()) t else null))
            } else {
                result.add(com.aicode.studio.ChatMessage(r, text))
            }
        }
        return result
    }

    // ── 프로젝트 로드 ──────────────────────────────────────────

    fun loadProject(r: File) {
        root = r
        memoryMgr = MemoryManager(r)
        lastSysPrompt = null
        loadSession()
        // 세션 메모리 파일 로드 (없으면 템플릿으로 초기화)
        sessionMemory = memoryMgr?.loadSessionMemory() ?: MemoryManager.SESSION_MEMORY_TEMPLATE
        exchangeCountSinceMemUpdate = 0
    }

    // ── 실행 ──────────────────────────────────────────────────

    fun execute(command: String) {
        val r = root ?: run { cb?.onError("프로젝트 미선택"); return }

        // Developer Commands
        when (command.lowercase().trim()) {
            "#~#grok4 on" -> {
                devMode = "grok-4-fast-reasoning"
                logger.logSystem("Developer Mode: Grok-4 ON")
                cb?.onCompleted("Grok-4 모드가 활성화되었습니다.")
                return
            }
            "#~#grok4 off", "#~#gpt off" -> {
                devMode = null
                logger.logSystem("Developer Mode: OFF")
                cb?.onCompleted("개발자 모드가 비활성화되었습니다.")
                return
            }
            "#~#gpt on" -> {
                devMode = "gpt-oss-120b"
                logger.logSystem("Developer Mode: GPT ON")
                cb?.onCompleted("GPT 모드가 활성화되었습니다.")
                return
            }
        }

        if (devMode != null) {
            // Developer mode - already checked
        } else if (localMode) {
            if (localAI?.isConnected() != true) { cb?.onError("Local AI 미연결 - 스위치를 켜고 엔진이 준비될 때까지 기다려주세요"); return }
        } else {
            if (apiConfigs.isEmpty()) { cb?.onError("API 키가 설정되지 않았습니다."); return }
        }
        if (running) { cb?.onError("이미 실행 중"); return }

        running = true; iter = 0; currentApiIdx = 0
        cb?.onStatusChanged(
            if (devMode != null) "Developer AI 분석 중..."
            else if (localMode) "Local AI 분석 중..."
            else "AI 분석 중 (API 1)..."
        )

        // 사용자 메시지를 즉시 히스토리에 저장 (내부 템플릿 없이 순수 텍스트로)
        history.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", "## Request\n$command")))
        })
        saveSession()

        // 백그라운드: 메모리 트리밍 → 프롬프트 빌드 → 전송
        Thread {
            val mgr = memoryMgr
            if (mgr != null && mgr.needsTrimming()) {
                logger.logSystem("기억 파일 용량 초과 (${MemoryManager.MAX_LINES}라인) - 2000라인 수준으로 정리 중...")
                
                val summarizer: ((String) -> String?)? = if (devMode != null || apiConfigs.isNotEmpty()) {
                    { text -> summarizeBlocking(text) }
                } else null
                
                val currentSession = mgr.load()
                val updatedSession = mgr.trimToTarget(currentSession, summarizer)
                
                history = updatedSession.messages
                archiveSummary = updatedSession.archiveSummary
                mgr.save(updatedSession)
                
                logger.logSystem("기억 정리 완료 (현재 아카이브: ${archiveSummary.size}개)")
            }

            // 2. 최신 맥락 기반 시스템 프롬프트 빌드
            val archiveCtx = mgr?.buildArchiveIndexText(MemoryManager.Session(archiveSummary, history, emptyList(), projectContext)) ?: ""
            val localPrompt = PromptBuilder(60000)
            val sys = localPrompt.buildSystemPrompt(r, currentApiIdx + 1, archiveCtx, projectContext)

            // ── 로컬 AI: 경량 에이전트 루프 ────────────────────────────────────
            if (localMode && devMode == null) {
                val local = localAI ?: run { running = false; cb?.onError("Local AI 없음"); return@Thread }
                local.importHistory(recentLocalHistory())

                // 세션 메모리가 실제 내용을 가지면 archiveCtx 대신 세션 메모리 주입
                // Claude Code의 session_memory → compact 경로와 동일한 역할
                val mgr2 = memoryMgr
                val memContent = if (mgr2 != null && !mgr2.isSessionMemoryEmpty(sessionMemory))
                    "[SESSION MEMORY]\n$sessionMemory"
                else if (archiveCtx.isNotEmpty())
                    "[Archives]\n$archiveCtx"
                else ""

                val contextForLocal = if (memContent.isNotEmpty()) "$sys\n\n$memContent" else sys
                local.setContext(contextForLocal)

                // Local AI도 히스토리 초과 시 단순 트림 (API 없이)
                compactIfNeeded()
                executeLocalAI(local, "## Request\n$command", r, 0)
                return@Thread
            }

            // ── Auto Compact (Claude Code autoCompactIfNeeded 이식) ──────────────
            // 히스토리가 임계값 초과 시 오래된 메시지를 one-shot 시크릿 요청으로 압축
            compactIfNeeded()

            // ── Cloud / Developer AI: 전송 ─────────────────────────────────────
            val cloudHistory = JSONArray()
            for (i in 0 until history.length()) {
                val h = history.getJSONObject(i)
                // 마지막(현재) 요청에 시스템 프롬프트와 아카이브 인덱스를 주입하여 전송
                if (i == history.length() - 1 && h.getString("role") == "user") {
                    cloudHistory.put(JSONObject().apply {
                        put("role", "user")
                        val originalText = h.getJSONArray("parts").getJSONObject(0).getString("text")
                        put("parts", JSONArray().put(JSONObject().put("text", "$sys\n\n$originalText")))
                    })
                } else {
                    cloudHistory.put(h)
                }
            }

            if (devMode != null) sendDevNext(cloudHistory) else sendNext(cloudHistory)
        }.start()
    }


    fun resetSession() {
        history = JSONArray()
        archiveSummary = mutableListOf()
        localConvHistory.clear()
        sessionMemory = MemoryManager.SESSION_MEMORY_TEMPLATE
        exchangeCountSinceMemUpdate = 0
        iter = 0; running = false
        lastSysPrompt = null
        localAI?.clearHistory()
        root?.let {
            saveSession()
            memoryMgr?.saveSessionMemory(MemoryManager.SESSION_MEMORY_TEMPLATE)
        }
    }


    fun stop() {
        running = false
        if (localMode) localAI?.stopGeneration()
        saveSession()
        cb?.onStatusChanged("중지됨")
    }

    // ── Cloud AI 통신 ──────────────────────────────────────────

    private fun sendNext(cloudHistory: JSONArray) {
        if (!running) return
        if (currentApiIdx >= apiConfigs.size) {
            running = false; cb?.onError("사용 가능한 API 없음 (모두 소진됨)"); return
        }

        val config = apiConfigs[currentApiIdx]
        iter++
        if (iter > MAX_ITER) {
            running = false; cb?.onError("최대 반복(${MAX_ITER})회 도달, 작업 중단")
            saveSession(); return
        }
        logger.logAI("API ${currentApiIdx + 1} (${config.model}) - Iteration $iter")

        val body = JSONObject().apply {
            put("contents", cloudHistory)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2); put("maxOutputTokens", 8192)
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val url = "https://generativelanguage.googleapis.com/v1beta/models/${config.model}:generateContent?key=${config.key}"
        val req = Request.Builder().url(url).post(body).build()

        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onResponse(call: Call, resp: Response) {
                val code = resp.code
                val raw  = resp.body?.string() ?: ""
                if (code != 200) {
                    logger.logError("API ${currentApiIdx + 1} (${config.model}): err code:$code")
                    if (code == 429 || raw.contains("quota", true) || raw.contains("exhausted", true)) {
                        currentApiIdx++
                        mainHandler { cb?.onStatusChanged("API $currentApiIdx 소진됨. 다음 시도..."); sendNext(cloudHistory) }
                    } else {
                        running = false; cb?.onError("err code:$code")
                    }
                    return
                }
                try { handleResp(raw, config.model, currentApiIdx + 1) }
                catch (e: Exception) {
                    logger.logError("Parse error: ${e.message}")
                    running = false; cb?.onError("응답 파싱 실패")
                }
            }
            override fun onFailure(call: Call, e: IOException) {
                logger.logError("Network API ${currentApiIdx + 1}: ${e.message}")
                currentApiIdx++
                mainHandler { sendNext(cloudHistory) }
            }
        })
    }

    // ── Developer AI 통신 (OpenAI Format) ──────────────────────────

    private fun sendDevNext(cloudHistory: JSONArray) {
        if (!running) return
        val model = devMode ?: return

        iter++
        if (iter > MAX_ITER) {
            running = false; cb?.onError("최대 반복(${MAX_ITER})회 도달, 작업 중단")
            saveSession(); return
        }
        logger.logAI("Developer API ($model) - Iteration $iter")

        val oaiHistory = JSONArray()
        
        // 서버에서 기억을 관리하므로, 전체 히스토리 대신 
        // 시스템 프롬프트와 현재 도구 결과/요청이 결합된 '최신 메시지'만 전송합니다.
        // execute() 및 sendFeedback()에서 이미 마지막 메시지에 sys prompt를 주입해두었습니다.
        val lastMsg = cloudHistory.getJSONObject(cloudHistory.length() - 1)
        val content = lastMsg.getJSONArray("parts").getJSONObject(0).getString("text")
        
        oaiHistory.put(JSONObject().apply {
            put("role", "user")
            put("content", content)
        })

        val body = JSONObject().apply {
            put("model", model)
            put("messages", oaiHistory)
            put("temperature", 0.2)
            put("max_tokens", 4096)
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(DEV_API_URL.trim())
            .addHeader("x-proxy-key", DEV_API_KEY)
            .addHeader("User-Agent", "python-requests/2.31.0")
            .addHeader("Accept", "*/*")
            .post(body)
            .build()

        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onResponse(call: Call, resp: Response) {
                val code = resp.code
                val raw = resp.body?.string() ?: ""
                if (code != 200) {
                    logger.logError("Developer API ($model): err code:$code\n$raw")
                    running = false; cb?.onError("Dev API err:$code")
                    return
                }
                try { handleDevResp(raw, model) }
                catch (e: Exception) {
                    logger.logError("Dev Parse error: ${e.message}")
                    running = false; cb?.onError("응답 파싱 실패")
                }
            }
            override fun onFailure(call: Call, e: IOException) {
                logger.logError("Dev Network error: ${e.message}")
                running = false; cb?.onError("네트워크 오류")
            }
        })
    }

    private fun handleDevResp(raw: String, model: String) {
        val rj = JSONObject(raw)
        val choices = rj.getJSONArray("choices")
        if (choices.length() == 0) {
            running = false; saveSession(); cb?.onError("빈 응답"); return
        }
        val msgObj = choices.getJSONObject(0).getJSONObject("message")
        val text = msgObj.getString("content")

        // Gemini 형식 히스토리에 저장
        history.put(JSONObject().apply {
            put("role", "model")
            put("parts", JSONArray().put(JSONObject().put("text", "[Result from Developer AI: $model]\n$text")))
        })
        saveSession()

        processAiResponse(text)
    }

    private fun processAiResponse(originalText: String) {
        var thought: String? = null
        var responseText = originalText

        // 1. JSON check (Cloud/Agent mode)
        val jsonStr = extractJson(originalText)
        if (jsonStr != null) {
            try {
                // 마크다운 블록 제거 루틴 추가 (로컬 AI 대응)
                val cleaned = jsonStr.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val result = JSONObject(cleaned)
                val t = result.optString("thought", "")
                val r = result.optString("response", "")
                if (t.isNotEmpty()) thought = t
                
                // "사용자에게 직접 전달할 답변" 예시 텍스트인 경우 무시
                if (r.isNotEmpty() && r != "사용자에게 직접 전달할 답변") responseText = r
                else if (r == "사용자에게 직접 전달할 답변") responseText = ""
                
                // 생각과 응답을 UI에 전송
                if (thought != null) mainHandler { cb?.onThought(thought!!) }
                if (responseText.isNotEmpty()) mainHandler { cb?.onResponse(responseText) }

                // 도구 실행 (grep, patch, updates 등)
                val feedback = mutableListOf<String>()
                processTools(result, feedback)
                
                // 도구 실행 결과가 있으면 피드백 전송 (루프 계속)
                if (feedback.isNotEmpty()) {
                    sendFeedback(feedback)
                    return
                } else {
                    // 도구 실행이 없으면 완료
                    running = false
                    saveSession()
                    // 세션 메모리 갱신 트리거 (Claude Code post-sampling hook과 동일한 역할)
                    maybeUpdateSessionMemoryAsync()
                    mainHandler { cb?.onCompleted(responseText) }
                    return
                }
            } catch (e: Exception) {
                logger.logError("JSON parsing failed, falling back to text: ${e.message}")
            }
        }

        // 2. RAW thought tags (<thought> or <think>) 파싱 (JSON 실패 시의 폴백)
        val thoughtMatch = Regex("<thought>([\\s\\S]*?)</thought>").find(responseText)
            ?: Regex("<think>([\\s\\S]*?)</think>").find(responseText)
        
        if (thoughtMatch != null) {
            thought = thoughtMatch.groupValues[1].trim()
            responseText = responseText.replace(thoughtMatch.value, "").trim()
        }

        if (thought != null) mainHandler { cb?.onThought(thought!!) }
        if (responseText.isNotEmpty()) {
            mainHandler { cb?.onResponse(responseText) }
        }
        
        // 최종 완료 처리
        running = false
        saveSession()
        maybeUpdateSessionMemoryAsync()
        mainHandler { cb?.onCompleted(responseText) }
    }

    // ── 응답 처리 ──────────────────────────────────────────────

    private fun handleResp(raw: String, modelName: String, apiIdx: Int) {
        val originalText = if (modelName == "Local AI") {
            raw
        } else {
            val rj    = JSONObject(raw)
            val cands = rj.optJSONArray("candidates")
            if (cands == null || cands.length() == 0) {
                running = false; saveSession(); cb?.onError("빈 응답"); return
            }
            val aiContent = cands.getJSONObject(0).getJSONObject("content")
            val text      = aiContent.getJSONArray("parts").getJSONObject(0).getString("text")
            aiContent.getJSONArray("parts").getJSONObject(0)
                .put("text", "[Result from $modelName (API $apiIdx)]\n$text")
            history.put(aiContent)
            saveSession()
            text
        }

        if (modelName == "Local AI") {
            history.put(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().put("text",
                    "[Result from Local AI: $localModelName]\n$originalText")))
            })
            saveSession()
        }

        processAiResponse(originalText)
    }

    // ── 도구 처리 ──────────────────────────────────────────────


    private fun processTools(result: JSONObject, feedback: MutableList<String>) {
        val grep      = result.optJSONObject("grep")
        val readRange = result.optJSONObject("read_range")
        val patch     = result.optJSONObject("patch")
        val updates   = result.optJSONArray("updates")
        val deletes   = result.optJSONArray("deletes")

        // 새 맥락 저장 도구
        val newContext = result.optString("project_context", "")
        if (newContext.isNotEmpty() && newContext != "null") {
            projectContext = newContext
            feedback.add("OK: Project Context Updated")
        }

        // 과거 기억 읽기 도구 (null 체크 추가)
        val archiveToRead = result.optString("read_archive", "")
        if (archiveToRead.isNotEmpty() && archiveToRead != "null") {
            val content = memoryMgr?.readArchive(archiveToRead)
            if (content != null) feedback.add("ARCHIVE CONTENT ($archiveToRead):\n$content")
            else feedback.add("FAIL: Archive not found: $archiveToRead")
        }

        var actionTaken = false
        // 각 필드가 null이 아니고 실제 데이터가 있는 경우에만 실행
        if (grep != null && grep.length() > 0) {
            actionTaken = true
            feedback.add(executeGrep(grep.optString("path","."), grep.optString("pattern",""), grep.optBoolean("recursive",true)))
        }
        if (readRange != null && readRange.length() > 0) {
            actionTaken = true
            feedback.add(executeReadRange(readRange.optString("path"), readRange.optInt("start",1), readRange.optInt("end",100)))
        }
        if (patch != null && patch.length() > 0) {
            actionTaken = true
            feedback.add(executePatch(patch.optString("path"), patch.optString("old"), patch.optString("new")))
        }

        if (updates != null && updates.length() > 0) {
            actionTaken = true
            for (i in 0 until updates.length()) {
                val o = updates.optJSONObject(i) ?: continue
                val p = o.optString("path"); val c = o.optString("content")
                if (p.isEmpty()) continue
                try {
                    val f = File(root, p); f.parentFile?.mkdirs(); f.writeText(c)
                    feedback.add("OK: Wrote $p"); cb?.onFileUpdated(p, true, "완료")
                } catch (e: Exception) { feedback.add("FAIL: $p - ${e.message}") }
            }
        }

        val runShell = result.optJSONObject("run_shell")
        if (runShell != null && runShell.length() > 0) {
            actionTaken = true
            feedback.add(executeShellCommand(runShell.optString("cmd"), runShell.optInt("timeout", 30)))
        }

        if (deletes != null && deletes.length() > 0) {
            actionTaken = true
            processDeletes(deletes, 0, feedback)
        } else {
            if (!actionTaken) {
                running = false
                saveSession()
                mainHandler { cb?.onCompleted("작업 완료") }
                return
            }
            sendFeedback(feedback)
        }
    }

    private fun processDeletes(del: JSONArray, idx: Int, fb: MutableList<String>) {
        if (idx >= del.length()) { sendFeedback(fb); return }
        val p = del.getString(idx)
        cb?.onDeleteRequested(p) { ok ->
            if (ok) { File(root, p).deleteRecursively(); fb.add("OK: Deleted $p") }
            else fb.add("REJECTED: $p")
            processDeletes(del, idx + 1, fb)
        }
    }

    private fun sendFeedback(fb: List<String>) {
        if (!running) return
        val feedbackText = prompt.buildFeedback(fb)
        val msg = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", feedbackText)))
        }
        history.put(msg)
        cb?.onStatusChanged("AI 계속 작업... ($iter/$MAX_ITER)")
        
        if (localMode && devMode == null) {
            val local = localAI ?: return
            compactIfNeeded()
            executeLocalAI(local, feedbackText, root!!, iter)
            return
        }

        // Cloud / Dev mode: rebuild cloud history with system prompt
        compactIfNeeded()

        val mgr = memoryMgr
        val archiveCtx = mgr?.buildArchiveIndexText(MemoryManager.Session(archiveSummary, history, emptyList(), projectContext)) ?: ""
        val localPrompt = PromptBuilder(60000)
        val sys = localPrompt.buildSystemPrompt(root!!, currentApiIdx + 1, archiveCtx, projectContext)

        val cloudHistory = JSONArray()
        for (i in 0 until history.length()) {
            val h = history.getJSONObject(i)
            if (i == history.length() - 1 && h.getString("role") == "user") {
                cloudHistory.put(JSONObject().apply {
                    put("role", "user")
                    val originalText = h.getJSONArray("parts").getJSONObject(0).getString("text")
                    put("parts", JSONArray().put(JSONObject().put("text", "$sys\n\n$originalText")))
                })
            } else {
                cloudHistory.put(h)
            }
        }

        if (devMode != null) sendDevNext(cloudHistory) 
        else sendNext(cloudHistory)
    }

    // ── 도구 실행 ──────────────────────────────────────────────

    private fun executeGrep(path: String, pattern: String, recursive: Boolean): String {
        val base = root ?: return "GREP FAIL: No project"
        return try {
            val target = File(base, path)
            if (!target.exists()) return "GREP FAIL: Path not found: $path"
            val regex = Regex(pattern)
            val sb    = StringBuilder("GREP RESULT ($pattern):\n")
            val files = if (target.isFile) sequenceOf(target) else target.walkTopDown().filter { it.isFile }
            var matchCount = 0
            for (f in files) {
                if (matchCount > 50) { sb.append("... (limit reached)"); break }
                try { f.forEachLine { line ->
                    if (regex.containsMatchIn(line)) { sb.append("${f.relativeTo(base).path}: $line\n"); matchCount++ }
                }} catch (_: Exception) {}
            }
            if (matchCount == 0) "GREP: No matches found." else sb.toString()
        } catch (e: Exception) { "GREP ERROR: ${e.message}" }
    }

    private fun executeReadRange(path: String, start: Int, end: Int): String {
        val base = root ?: return "READ FAIL: No project"
        return try {
            val f = File(base, path)
            if (!f.exists()) return "READ FAIL: File not found: $path"
            val lines = f.readLines()
            val s = (start - 1).coerceAtLeast(0)
            val e = end.coerceAtMost(lines.size)
            if (s >= e) return "READ: Empty range (file has ${lines.size} lines)"
            val sb = StringBuilder("READ $path ($start-$end):\n")
            for (i in s until e) sb.append("${i + 1}: ${lines[i]}\n")
            sb.toString()
        } catch (e: Exception) { "READ ERROR: ${e.message}" }
    }

    private fun executePatch(path: String, oldStr: String, newStr: String): String {
        val base = root ?: return "PATCH FAIL: No project"
        return try {
            val f = File(base, path)
            if (!f.exists()) return "PATCH FAIL: File not found: $path"
            val content = f.readText()
            if (!content.contains(oldStr)) return "PATCH FAIL: 'old' string not found. Ensure exact match including whitespace."
            f.writeText(content.replaceFirst(oldStr, newStr))
            cb?.onFileUpdated(path, true, "Patched")
            "PATCH OK: $path updated."
        } catch (e: Exception) { "PATCH ERROR: ${e.message}" }
    }

    /**
     * AI 에이전트용 Termux 셸 명령어 실행기.
     * - LD_PRELOAD shim으로 경로 리매핑 자동 적용
     * - timeout초 내에 완료 안 되면 프로세스 강제 종료
     * - stdout 4000자 / stderr 1000자 까지만 반환 (토큰 절약)
     * - 사용자가 Termux 창을 열지 않아도 백그라운드에서 실행됨
     */
    private fun executeShellCommand(cmd: String, timeout: Int): String {
        val ctx = appContext ?: return "SHELL_FAIL: context not set (call setAppContext)"
        if (cmd.isBlank()) return "SHELL_FAIL: empty command"
        val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        val home   = TermuxConstants.TERMUX_HOME_DIR_PATH
        val libDir = ctx.applicationInfo.nativeLibraryDir
        return try {
            val env = arrayOf(
                "PREFIX=$prefix",
                "HOME=$home",
                "PATH=$prefix/bin:$prefix/bin/applets:/system/bin",
                "LD_LIBRARY_PATH=$prefix/lib",
                "LD_PRELOAD=$libDir/libtermux-exec.so",
                "LANG=en_US.UTF-8",
                "TERM=xterm-256color",
                "TMPDIR=$prefix/tmp"
            )
            val proc = Runtime.getRuntime().exec(
                arrayOf("$prefix/bin/bash", "-c", cmd.take(4000)),
                env,
                java.io.File(home)
            )
            val outSb = StringBuilder()
            val errSb = StringBuilder()
            val limitMs = (timeout.coerceIn(5, 120) * 1000L)
            val tOut = Thread { outSb.append(proc.inputStream.bufferedReader().readText().take(4000)) }
            val tErr = Thread { errSb.append(proc.errorStream.bufferedReader().readText().take(1000)) }
            tOut.start(); tErr.start()
            tOut.join(limitMs); tErr.join(2000L)
            proc.destroy()
            val exit = try { proc.exitValue() } catch (_: Exception) { -1 }
            buildString {
                append("SHELL EXIT=$exit\n")
                if (outSb.isNotEmpty()) append(outSb)
                if (errSb.isNotEmpty()) append("\nSTDERR: $errSb")
                if (outSb.isEmpty() && errSb.isEmpty()) append("(no output)")
            }
        } catch (e: Exception) { "SHELL_ERROR: ${e.message}" }
    }

    // ── JSON 추출 (중첩 지원) ──────────────────────────────────

    private fun extractJson(text: String): String? {
        // 코드 블록에서 추출 시도 (그리디)
        val codeBlock = Regex("```(?:json)?\\s*\\n?(\\{[\\s\\S]*\\})\\s*```").find(text)
        if (codeBlock != null) {
            val c = codeBlock.groupValues[1]
            return try { JSONObject(c); c } catch (_: Exception) { null }
        }
        // 괄호 깊이 카운팅으로 추출 (중첩 JSON 대응)
        val start = text.indexOf('{')
        if (start == -1) return null
        var depth = 0; var inString = false; var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (!inString) when (c) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) {
                    val candidate = text.substring(start, i + 1)
                    return try { JSONObject(candidate); candidate } catch (_: Exception) { null }
                }}
            }
        }
        return null
    }

    /**
     * 스트리밍 중 아직 닫히지 않은 JSON에서 "thought" 필드의 부분 값만 추출.
     * 예: {"thought": "분석 중..." → "분석 중..."  (닫는 따옴표 없어도 OK)
     * JSON 전체가 thought 패널에 노출되는 버그를 방지하는 핵심 함수.
     */
    private fun extractPartialThought(partialJson: String): String? {
        val match = Regex(""""thought"\s*:\s*"((?:[^"\\]|\\.)*)""").find(partialJson)
            ?: return null
        return match.groupValues.getOrNull(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.takeIf { it.isNotEmpty() }
    }

    // ── 메모리 트리밍용 단발 API 요약 요청 (히스토리에 저장 안 함) ──
    // Claude Code `BASE_COMPACT_PROMPT` 방식으로 업그레이드 — 9섹션 구조화 요약

    private fun summarizeBlocking(text: String): String? {
        // Claude Code buildCompactSummaryPrompt 이식
        val summaryPrompt = MemoryManager.buildCompactSummaryPrompt(text)

        if (devMode != null) {
            val body = JSONObject().apply {
                put("model", devMode)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", summaryPrompt)
                }))
                put("max_tokens", 1024)
            }.toString().toRequestBody("application/json".toMediaType())

            return try {
                val req = Request.Builder().url(DEV_API_URL).addHeader("x-proxy-key", DEV_API_KEY).post(body).build()
                val resp = client.newCall(req).execute()
                val raw = resp.body?.string() ?: return null
                JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content")?.trim()
            } catch (_: Exception) { null }
        }

        val config = apiConfigs.getOrNull(currentApiIdx) ?: return null
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", summaryPrompt)))
            }))
            put("generationConfig", JSONObject().apply { put("maxOutputTokens", 1024) })
        }.toString().toRequestBody("application/json".toMediaType())
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${config.model}:generateContent?key=${config.key}"
        return try {
            val resp = client.newCall(Request.Builder().url(url).post(body).build()).execute()
            val raw  = resp.body?.string() ?: return null
            JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text")?.trim()
        } catch (_: Exception) { null }
    }

    // ── Session Memory 갱신 (Claude Code extractSessionMemory 이식) ──────

    /**
     * 일정 교환 횟수 이후 세션 메모리를 비동기로 갱신.
     * Claude Code의 post-sampling 훅 + shouldExtractMemory() 로직을 단순화.
     * 로컬 전용 모드이거나 API 없을 때는 스킵.
     */
    private fun maybeUpdateSessionMemoryAsync() {
        // 로컬 전용 모드나 API 없는 경우 스킵
        if (localMode && devMode == null) return
        if (devMode == null && apiConfigs.isEmpty()) return

        exchangeCountSinceMemUpdate++
        if (exchangeCountSinceMemUpdate < SESSION_MEMORY_UPDATE_INTERVAL) return
        exchangeCountSinceMemUpdate = 0

        updateSessionMemoryAsync()
    }

    /**
     * 현재 대화 히스토리를 기반으로 세션 메모리 파일을 갱신.
     * Claude Code runForkedAgent(session_memory) 역할.
     * 별도 스레드에서 실행 — 메인 대화 흐름을 블로킹하지 않음.
     */
    private fun updateSessionMemoryAsync() {
        val mgr = memoryMgr ?: return
        val currentNotes = sessionMemory

        Thread {
            try {
                // 대화 내용 + 세션 메모리 갱신 프롬프트 구성
                val updatePrompt = MemoryManager.buildSessionMemoryUpdatePrompt(currentNotes)

                // 현재 히스토리를 컨텍스트로 포함
                val historyForContext = JSONArray()
                val startIdx = (history.length() - 10).coerceAtLeast(0) // 최근 10개 메시지만
                for (i in startIdx until history.length()) {
                    historyForContext.put(history.get(i))
                }
                historyForContext.put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", updatePrompt)))
                })

                val updatedNotes = callAiBlocking(historyForContext, maxTokens = 2048) ?: return@Thread

                // 갱신된 세션 메모리 저장
                if (updatedNotes.length > 100) {
                    sessionMemory = updatedNotes
                    mgr.saveSessionMemory(updatedNotes)
                    logger.logSystem("세션 메모리 갱신 완료 (${updatedNotes.length}자)")

                    // 세션 메모리가 소형 모델 컨텍스트 임계값 초과 시 재압축
                    // ChatGPT 지적: memory 파일도 계속 커지므로 반드시 관리 필요
                    if (updatedNotes.length > SESSION_MEMORY_RECOMPACT_CHARS) {
                        recompressSessionMemory(updatedNotes)
                    }
                }
            } catch (e: Exception) {
                logger.logSystem("세션 메모리 갱신 실패: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * 세션 메모리가 SESSION_MEMORY_RECOMPACT_CHARS를 초과할 때 재압축.
     *
     * Cloud/Dev AI: callAiBlocking()으로 시크릿 one-shot 재요약
     * 로컬 전용: MemoryManager.saveSessionMemory()의 구조적 트런케이션 사용
     *
     * 재압축 후 sessionMemory 필드와 파일 모두 업데이트.
     */
    private fun recompressSessionMemory(currentMemory: String) {
        val mgr = memoryMgr ?: return
        logger.logSystem("세션 메모리 재압축 시작 (${currentMemory.length}자 → 목표 ~${SESSION_MEMORY_RECOMPACT_CHARS / 2}자)...")

        val canSummarize = devMode != null || apiConfigs.isNotEmpty()

        if (!canSummarize) {
            // 로컬 전용 + API 없음: 구조적 트런케이션 (섹션별 잘라내기)
            // saveSessionMemory() 내부에서 truncateSessionMemory()가 자동 적용됨
            mgr.saveSessionMemory(currentMemory)
            sessionMemory = mgr.loadSessionMemory()
            logger.logSystem("세션 메모리 구조적 트런케이션 완료 (${sessionMemory.length}자)")
            return
        }

        // API 있음: 시크릿 one-shot으로 내용 보존 재압축
        val recompressPrompt = MemoryManager.buildSessionMemoryRecompressPrompt(currentMemory)
        val contents = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", recompressPrompt)))
            })
        }
        val result = callAiBlocking(contents, maxTokens = 2048) ?: run {
            logger.logSystem("세션 메모리 재압축 실패 — 구조적 트런케이션으로 폴백")
            mgr.saveSessionMemory(currentMemory)
            sessionMemory = mgr.loadSessionMemory()
            return
        }

        // 유효한 응답인지 확인 (섹션 헤더가 있어야 세션 메모리 형식으로 간주)
        if (result.length > 100 && result.contains("# ")) {
            sessionMemory = result
            mgr.saveSessionMemory(result)
            logger.logSystem("세션 메모리 재압축 완료: ${currentMemory.length}자 → ${result.length}자")
        } else {
            logger.logSystem("세션 메모리 재압축 응답 형식 불량 — 구조적 트런케이션으로 폴백")
            mgr.saveSessionMemory(currentMemory)
            sessionMemory = mgr.loadSessionMemory()
        }
    }

    // ── Auto Compact (Claude Code compactConversation 이식) ─────────────────

    /**
     * 히스토리가 임계값을 초과하면 자동 압축.
     *
     * Claude Code와 다른 점 — 로컬 AI도 compact 대상:
     *   Claude Code: history는 참고 로그, memory가 주 컨텍스트 → history compact 불필요
     *   이 앱: history + sessionMemory 둘 다 컨텍스트로 사용 → 둘 다 관리 필요
     *
     * 로컬 AI: API 없이 단순 트림 (세션 메모리가 이미 내용 흡수했으므로 안전)
     * Cloud/Dev AI: one-shot 시크릿 요청으로 9섹션 요약 생성 후 히스토리 앞에 삽입
     */
    private fun compactIfNeeded() {
        val isLocalOnly = localMode && devMode == null
        val threshold   = if (isLocalOnly) LOCAL_COMPACT_THRESHOLD else COMPACT_THRESHOLD
        val keepRecent  = if (isLocalOnly) LOCAL_COMPACT_KEEP      else COMPACT_KEEP_RECENT

        if (history.length() <= threshold) return

        val totalMsgs = history.length()
        val cutCount  = totalMsgs - keepRecent
        if (cutCount <= 0) return

        logger.logSystem("Auto Compact: ${totalMsgs}개 메시지 중 ${cutCount}개 압축 시작 (${if (isLocalOnly) "Local" else "Cloud"} 모드)...")
        mainHandler { cb?.onStatusChanged("대화 기록 압축 중...") }

        // 1. 압축할 메시지 추출
        val toCompact = JSONArray()
        repeat(cutCount) {
            toCompact.put(history.get(0))
            history.remove(0)
        }

        // 2. 아카이브 파일로 원본 보존 (Claude Code transcriptPath 역할)
        val mgr = memoryMgr
        if (mgr != null) {
            val seq = archiveSummary.size + 1
            val archiveName = "${MemoryManager.ARCHIVE_DIR}/compact_%03d.json".format(seq)
            try {
                File(root, archiveName).also { it.parentFile?.mkdirs() }
                    .writeText(toCompact.toString(2))
                logger.logSystem("Auto Compact: 원본 $archiveName 에 보존됨")
            } catch (_: Exception) {}
        }

        // 3-A. 로컬 전용: API 없이 단순 트림
        //      세션 메모리가 이미 대화 내용을 흡수했으므로 요약 생성 불필요
        if (isLocalOnly) {
            // 세션 메모리에 compact 사실 기록 (다음 memory 갱신에서 반영됨)
            logger.logSystem("Auto Compact (Local): 세션 메모리 기반 트림 완료 — ${history.length()}개 보존")
            saveSession()
            mainHandler { cb?.onStatusChanged("대화 기록 정리 완료") }
            return
        }

        // 3-B. Cloud/Dev: one-shot 시크릿 요청으로 9섹션 요약 생성
        //      callAiBlocking() = 히스토리 저장 없는 임시 세션 (Claude Code forkedAgent 역할)
        val canSummarize = devMode != null || apiConfigs.isNotEmpty()
        val summaryText: String = if (canSummarize) {
            val rawText       = buildMessagesText(toCompact)
            val compactPrompt = MemoryManager.buildConversationCompactPrompt(rawText)
            val contents      = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", compactPrompt)))
                })
            }
            val result = callAiBlocking(contents, maxTokens = 2048)
            if (result != null && result.length > 100) {
                logger.logSystem("Auto Compact: 요약 생성 완료 (${result.length}자)")
                result
            } else {
                logger.logSystem("Auto Compact: 요약 실패, 원문 축약으로 대체")
                buildMessagesText(toCompact).take(3000) + "\n\n[이전 ${cutCount}개 메시지 압축됨]"
            }
        } else {
            buildMessagesText(toCompact).take(3000) + "\n\n[이전 ${cutCount}개 메시지]"
        }

        // 4. 요약을 "model" 역할 메시지로 히스토리 맨 앞에 삽입
        //    Claude Code의 boundaryMarker + summaryMessages 역할
        val wrappedSummary = MemoryManager.wrapCompactSummary(summaryText)
        val summaryMsg = JSONObject().apply {
            put("role", "model")
            put("parts", JSONArray().put(JSONObject().put("text", wrappedSummary)))
        }
        val newHistory = JSONArray().apply {
            put(summaryMsg)
            for (i in 0 until history.length()) put(history.get(i))
        }
        history = newHistory

        saveSession()
        logger.logSystem("Auto Compact 완료: ${history.length()}개 메시지로 정리됨")
        mainHandler { cb?.onStatusChanged("대화 기록 압축 완료") }
    }

    /**
     * JSONArray 메시지들을 텍스트로 변환 (compact 프롬프트 입력용).
     * Claude Code stripImagesFromMessages()의 단순화 버전.
     */
    private fun buildMessagesText(msgs: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            val role = when (m.optString("role")) {
                "user"  -> "User"
                "model" -> "Assistant"
                else    -> "System"
            }
            val text = m.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "") ?: ""
            // 시스템 리포트(도구 피드백)는 요약에서 제외 — 노이즈 감소
            if (text.startsWith("## System Report")) continue
            sb.append("[$role]: ${text.take(1000)}\n\n")
        }
        return sb.toString().trim()
    }

    /**
     * 단발 AI 호출 (히스토리 저장 없음). 세션 메모리 갱신 및 아카이브 요약에 사용.
     */
    private fun callAiBlocking(contents: JSONArray, maxTokens: Int = 512): String? {
        if (devMode != null) {
            val lastMsg = contents.getJSONObject(contents.length() - 1)
            val content = lastMsg.getJSONArray("parts").getJSONObject(0).getString("text")
            val body = JSONObject().apply {
                put("model", devMode)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user"); put("content", content)
                }))
                put("max_tokens", maxTokens)
            }.toString().toRequestBody("application/json".toMediaType())
            return try {
                val req = Request.Builder().url(DEV_API_URL).addHeader("x-proxy-key", DEV_API_KEY).post(body).build()
                val raw = client.newCall(req).execute().body?.string() ?: return null
                JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content")?.trim()
            } catch (_: Exception) { null }
        }

        val config = apiConfigs.getOrNull(currentApiIdx) ?: return null
        val body = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", maxTokens)
                put("temperature", 0.2)
            })
        }.toString().toRequestBody("application/json".toMediaType())
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${config.model}:generateContent?key=${config.key}"
        return try {
            val raw = client.newCall(Request.Builder().url(url).post(body).build()).execute().body?.string() ?: return null
            JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text")?.trim()
        } catch (_: Exception) { null }
    }

    // ── 세션 저장/로드 ─────────────────────────────────────────

    private fun saveSession() {
        val mgr = memoryMgr ?: return
        try { mgr.save(MemoryManager.Session(archiveSummary, history, localConvHistory.toList(), projectContext)) } catch (_: Exception) {}
    }

    private fun loadSession() {
        val mgr = memoryMgr ?: run { history = JSONArray(); return }
        val session    = mgr.load()
        history        = session.messages
        archiveSummary = session.archiveSummary
        projectContext = session.projectContext
        localConvHistory.clear()
        // 디스크에서 복원된 히스토리도 오염 항목 즉시 제거
        session.localHistory.filterNot { (_, a) -> isQwenPoisoned(a) }.forEach { localConvHistory.add(it) }
        // 세션 메모리도 함께 로드 (loadProject()에서 호출되지 않은 경우 대비)
        if (sessionMemory.isBlank() || mgr.isSessionMemoryEmpty(sessionMemory)) {
            sessionMemory = mgr.loadSessionMemory()
        }
    }

    // Qwen 자기소개 오염 여부 판정 (영어+한국어)
    private fun isQwenPoisoned(text: String): Boolean {
        val t = text.lowercase()
        return listOf(
            "alibaba cloud", "i am qwen", "created by alibaba", "qwen, developed",
            "알리바바 클라우드", "언어를 이해하고 생성", "챗봇, 고객 서비스, 콘텐츠 생성",
            "인간과 유사한 텍스트", "다양한 데이터 소스로 학습", "광범위한 지식 기반"
        ).any { t.contains(it) }
    }

    private fun recentLocalHistory(): List<Pair<String, String>> {
        var total = 0
        val result = mutableListOf<Pair<String, String>>()
        for ((u, a) in localConvHistory.reversed()) {
            if (isQwenPoisoned(a)) continue
            val size = u.length + a.length + 50
            if (total + size > 600) break
            result.add(0, u to a)
            total += size
        }
        return result
    }

    private fun addLocalHistory(user: String, assistant: String) {
        val clean = assistant.replace(Regex("<think>[\\s\\S]*?</think>\\s*"), "").trim()
        if (clean.isEmpty() || isQwenPoisoned(clean)) return
        localConvHistory.add(user.take(200) to clean.take(500))
        if (localConvHistory.size > 5) localConvHistory.removeAt(0)
    }

    // ── 로컬 AI 도구 실행 루프 ────────────────────────────────
    private fun executeLocalAI(local: LocalAIManager, prompt: String, r: File, iteration: Int) {
        iter = iteration + 1
        if (iter > MAX_ITER) {
            running = false
            mainHandler { cb?.onCompleted("Local AI: 최대 반복(${MAX_ITER}) 도달") }
            return
        }

        var fullResponse = ""
        local.setStreamCallback(object : LocalAIManager.StreamCallback {
            override fun onToken(token: String) {
                fullResponse += token
                // 실시간 thought 업데이트 — JSON 전체를 노출하지 않도록 주의
                try {
                    val jsonStr = extractJson(fullResponse)
                    if (jsonStr != null) {
                        // JSON 완성: thought 필드만 추출
                        val t = JSONObject(jsonStr).optString("thought")
                        if (t.isNotEmpty()) mainHandler { cb?.onThought(t) }
                    } else {
                        // JSON 미완성: 부분 "thought" 값 추출 시도
                        val partial = extractPartialThought(fullResponse)
                        if (partial != null) {
                            mainHandler { cb?.onThought(partial) }
                        } else {
                            // XML <thought> 태그 파싱 시도 (LiteRT native thinking fallback)
                            val tm = Regex("<thought>([\\s\\S]*?)</thought>").find(fullResponse)
                                ?: Regex("<think>([\\s\\S]*?)</think>").find(fullResponse)
                            if (tm != null) mainHandler { cb?.onThought(tm.groupValues[1].trim()) }
                            // else: 아직 thought 내용 없음 → 아무것도 표시 안 함 (JSON 노출 방지)
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onComplete(response: String) {
                if (iteration == 0) addLocalHistory(prompt, response)
                
                // Local AI 응답을 히스토리에 저장
                history.put(JSONObject().apply {
                    put("role", "model")
                    put("parts", JSONArray().put(JSONObject().put("text", 
                        "[Result from Local AI: $localModelName]\n$response")))
                })
                saveSession()

                mainHandler { processAiResponse(response) }
            }

            override fun onError(error: String) {
                logger.logError("Local AI Error: $error")
                running = false
                mainHandler { cb?.onError("Local AI: $error") }
            }
        })
        local.sendPrompt(prompt)
    }

    private fun continueLocalAI(local: LocalAIManager, originalCommand: String, r: File, iteration: Int, feedback: List<String>) {
        if (!running) return
        val feedbackPrompt = "## Tool Results\n${feedback.joinToString("\n")}\n\nContinue if needed. If done, return thought with summary."
        mainHandler { cb?.onStatusChanged("Local AI 계속 작업… (${iteration + 1}/$MAX_ITER)") }
        executeLocalAI(local, feedbackPrompt, r, iteration + 1)
    }

    // ── 모델 목록 조회 ─────────────────────────────────────────

    fun fetchModels(key: String, onResult: (List<String>) -> Unit) {
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$key").get().build()
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onResponse(call: Call, resp: Response) {
                try {
                    val models = JSONObject(resp.body?.string() ?: "{}").getJSONArray("models")
                    val list   = mutableListOf<String>()
                    for (i in 0 until models.length()) {
                        val n = models.getJSONObject(i).getString("name")
                        if (n.contains("gemini")) list.add(n.removePrefix("models/"))
                    }
                    list.sortByDescending { it }
                    onResult(list)
                } catch (_: Exception) {
                    onResult(listOf("gemini-2.0-flash","gemini-1.5-flash","gemini-1.5-pro"))
                }
            }
            override fun onFailure(call: Call, e: IOException) {
                onResult(listOf("gemini-2.0-flash","gemini-1.5-flash","gemini-1.5-pro"))
            }
        })
    }

    private fun mainHandler(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { action() }
    }
}
