package com.aicode.studio

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aicode.studio.ai.AIAgentManager
import com.aicode.studio.ai.LocalAIManager
import com.aicode.studio.engine.AIInferenceService
import com.aicode.studio.engine.InferenceConfig
import com.aicode.studio.engine.ModelManager
import com.aicode.studio.util.LogManager
import com.aicode.studio.util.PrefsManager
import java.io.File

class AIChatActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnStop: ImageButton
    private lateinit var tvStatus: TextView
    private lateinit var settingsPanel: ScrollView
    private lateinit var btnSettings: ImageButton
    private lateinit var chatApiListContainer: LinearLayout
    private lateinit var btnAddApi: Button
    private lateinit var switchLocalAI: Switch
    private lateinit var btnManageLocalAI: Button
    private lateinit var btnThinking: Button
    private lateinit var btnResetMemory: Button

    private lateinit var chatAdapter: ChatMessageAdapter
    private lateinit var aiAgent: AIAgentManager
    private lateinit var localAIMgr: LocalAIManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val apiConfigsList = mutableListOf<AIAgentManager.ApiConfig>()
    private var localAIEnabled = false
    private var thinkingEnabled = true
    private var pendingMessage: String? = null
    private var currentAiMsgIdx = -1

    /** 사용자가 맨 아래에 있으면 true — 스트리밍 중 자동 스크롤 여부 결정 */
    private var isAtBottom = true
    /** 마지막 thought UI 업데이트 시각 — 쓰로틀링으로 흔들림 방지 */
    private var lastThoughtUpdateMs = 0L

    // Stub logger: LocalAI / AIAgent internal logs go to Android Log only
    private val stubLogger: LogManager by lazy {
        LogManager(TextView(this), ScrollView(this))
    }

    // ── Lifecycle ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_chat)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        bindViews()
        applyInsets()
        setupRecyclerView()
        setupManagers()
        setupListeners()
        loadSettings()
    }

    override fun onResume() {
        super.onResume()
        updateThinkingButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (localAIEnabled) localAIMgr.disconnect()
    }

    // ── Bind ───────────────────────────────────────────────────

    private fun bindViews() {
        recyclerView        = findViewById(R.id.chatRecyclerView)
        etInput             = findViewById(R.id.etChatInput)
        btnSend             = findViewById(R.id.btnChatSend)
        btnStop             = findViewById(R.id.btnChatStop)
        tvStatus            = findViewById(R.id.tvChatStatus)
        settingsPanel       = findViewById(R.id.settingsPanel)
        btnSettings         = findViewById(R.id.btnChatSettings)
        chatApiListContainer= findViewById(R.id.chatApiListContainer)
        btnAddApi           = findViewById(R.id.btnChatAddApi)
        switchLocalAI       = findViewById(R.id.chatSwitchLocalAI)
        btnManageLocalAI    = findViewById(R.id.btnChatManageLocalAI)
        btnThinking         = findViewById(R.id.btnChatThinking)
        btnResetMemory      = findViewById(R.id.btnChatResetMemory)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.chatRootLayout)) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            findViewById<View>(R.id.chatToolbar).setPadding(0, sb.top, 0, 0)
            findViewById<View>(R.id.chatInputArea).setPadding(8, 6, 8, nb.bottom + 6)
            insets
        }
    }

    // ── RecyclerView ───────────────────────────────────────────

    private fun setupRecyclerView() {
        chatAdapter = ChatMessageAdapter()
        val lm = LinearLayoutManager(this).apply { stackFromEnd = true }
        recyclerView.layoutManager = lm
        recyclerView.adapter = chatAdapter
        // 사용자가 스크롤 위치를 바꾸면 isAtBottom 업데이트
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val layout = rv.layoutManager as? LinearLayoutManager ?: return
                val last = layout.findLastVisibleItemPosition()
                isAtBottom = last >= chatAdapter.itemCount - 2
            }
        })
    }

    // ── Managers ───────────────────────────────────────────────

    private fun setupManagers() {
        aiAgent = AIAgentManager(stubLogger)
        aiAgent.setAppContext(this)

        localAIMgr = LocalAIManager(this, stubLogger).also { mgr ->
            mgr.onModelReady = { modelName, backend ->
                mainHandler.post {
                    showStatus("Local AI 준비됨: $modelName ($backend)")
                    updateThinkingButton()
                    // Auto-send pending message after model ready
                    val pending = pendingMessage
                    if (pending != null) {
                        pendingMessage = null
                        aiAgent.setLocalAI(localAIMgr, true)
                        executeSend(pending)
                    }
                }
            }

            // 추론 프로세스(:inference)가 OOM 킬 등으로 갑자기 죽었을 때 호출됨.
            // UI 프로세스는 살아 있으므로 에러를 표시하고 자동 재연결을 시도함.
            mgr.onServiceDied = {
                mainHandler.post {
                    // 진행 중이던 전송 UI 초기화
                    pendingMessage = null
                    setSending(false)
                    hideStatus()
                    if (currentAiMsgIdx >= 0) {
                        chatAdapter.updateMessage(currentAiMsgIdx,
                            "⚠️ Local AI 엔진이 메모리 부족으로 종료되었습니다.\n잠시 후 자동으로 재시작합니다...")
                        currentAiMsgIdx = -1
                    } else {
                        chatAdapter.addMessage(ChatMessage("system",
                            "⚠️ Local AI 엔진이 메모리 부족으로 종료되었습니다. 잠시 후 재시작됩니다."))
                        scrollToBottom()
                    }
                    // BIND_AUTO_CREATE이므로 서비스가 자동 재시작됨.
                    // connectForModel()을 호출해 재바인딩 + 모델 재로드 트리거.
                    if (localAIEnabled) {
                        val modelId = com.aicode.studio.engine.ModelManager(this@AIChatActivity).activeModelId
                        if (modelId.isNotEmpty()) {
                            showStatus("Local AI 재시작 중...")
                            mgr.connectForModel(modelId)
                        }
                    }
                }
            }
        }

        aiAgent.setCallback(object : AIAgentManager.Callback {
            override fun onThought(thought: String) {
                mainHandler.post {
                    if (currentAiMsgIdx < 0) {
                        currentAiMsgIdx = chatAdapter.addMessage(ChatMessage("ai", "..."))
                        isAtBottom = true   // 새 메시지 추가 시 바닥으로
                        scrollToBottom()
                    }
                    // 흔들림 방지: 최대 6회/초만 UI 갱신 (≈150ms throttle)
                    val now = System.currentTimeMillis()
                    if (now - lastThoughtUpdateMs >= 150) {
                        chatAdapter.updateThought(currentAiMsgIdx, thought)
                        lastThoughtUpdateMs = now
                        // thought 업데이트 시 스크롤 강제 안 함 — 사용자 스크롤 자유 보장
                    }
                }
            }

            override fun onResponse(response: String) {
                mainHandler.post {
                    if (currentAiMsgIdx < 0) {
                        currentAiMsgIdx = chatAdapter.addMessage(ChatMessage("ai", response))
                        isAtBottom = true
                        scrollToBottom()
                    } else {
                        chatAdapter.updateMessage(currentAiMsgIdx, response)
                        scrollToBottomIfNeeded()  // 사용자가 아래에 있을 때만 스크롤
                    }
                }
            }

            override fun onFileUpdated(path: String, success: Boolean, msg: String) {
                mainHandler.post {
                    val text = if (success) "✓ 파일 업데이트: $path" else "✗ 파일 실패: $path - $msg"
                    chatAdapter.addMessage(ChatMessage("system", text))
                    scrollToBottom()
                    // Notify editor to reload
                    sendBroadcast(Intent("com.aicode.studio.FILE_UPDATED").putExtra("path", path))
                }
            }

            override fun onDeleteRequested(path: String, onConfirm: (Boolean) -> Unit) {
                mainHandler.post {
                    AlertDialog.Builder(this@AIChatActivity)
                        .setTitle("AI 삭제 요청")
                        .setMessage("다음 파일을 삭제하시겠습니까?\n$path")
                        .setPositiveButton("허용") { _, _ -> onConfirm(true) }
                        .setNegativeButton("거절") { _, _ -> onConfirm(false) }
                        .setCancelable(false).show()
                }
            }

            override fun onCompleted(summary: String) {
                mainHandler.post {
                    setSending(false)
                    hideStatus()
                    if (summary.isNotEmpty() && currentAiMsgIdx >= 0) {
                        val cur = chatAdapter.getMessage(currentAiMsgIdx)
                        if (cur == "...") chatAdapter.updateMessage(currentAiMsgIdx, summary)
                    }
                    currentAiMsgIdx = -1
                }
            }

            override fun onError(error: String) {
                mainHandler.post {
                    setSending(false)
                    hideStatus()
                    if (currentAiMsgIdx >= 0) {
                        chatAdapter.updateMessage(currentAiMsgIdx, "오류: $error")
                        currentAiMsgIdx = -1
                    } else {
                        chatAdapter.addMessage(ChatMessage("system", "오류: $error"))
                        scrollToBottom()
                    }
                }
            }

            override fun onStatusChanged(status: String) {
                mainHandler.post { showStatus(status) }
            }
        })

        // Load project context for AI
        val lastProject = PrefsManager.getLastProject(this)
        if (lastProject.isNotEmpty()) {
            val f = File(lastProject)
            if (f.exists()) {
                aiAgent.loadProject(f)
                // Restore UI history
                val hist = aiAgent.getFormattedHistory()
                if (hist.isNotEmpty()) {
                    chatAdapter.clear()
                    hist.forEach { chatAdapter.addMessage(it) }
                    scrollToBottom()
                }
            }
        }
    }

    // ── Listeners ──────────────────────────────────────────────

    private fun setupListeners() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        btnSettings.setOnClickListener {
            settingsPanel.visibility =
                if (settingsPanel.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        btnSend.setOnClickListener { sendMessage() }

        btnStop.setOnClickListener {
            aiAgent.stop()
            setSending(false)
            hideStatus()
            if (currentAiMsgIdx >= 0) {
                val cur = chatAdapter.getMessage(currentAiMsgIdx)
                if (cur == "...") chatAdapter.updateMessage(currentAiMsgIdx, "(중지됨)")
                currentAiMsgIdx = -1
            }
        }

        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage(); true
            } else false
        }

        btnAddApi.setOnClickListener {
            if (apiConfigsList.size < 7) {
                apiConfigsList.add(AIAgentManager.ApiConfig("", "..."))
                refreshApiUI()
            } else {
                Toast.makeText(this, "최대 7개까지 가능합니다.", Toast.LENGTH_SHORT).show()
            }
        }

        switchLocalAI.setOnCheckedChangeListener { _, isChecked ->
            localAIEnabled = isChecked
            PrefsManager.saveLocalAIMode(this, isChecked)
            if (isChecked) {
                switchLocalAI.setTextColor(Color.parseColor("#4CAF50"))
                showStatus("Local AI 모드 — 첫 메시지 전송 시 자동 시작")
            } else {
                switchLocalAI.setTextColor(Color.parseColor("#AAAAAA"))
                aiAgent.setLocalAI(null, false)
                localAIMgr.stopAndDisconnect()
                hideStatus()
            }
            updateThinkingButton()
        }

        btnManageLocalAI.setOnClickListener { localAIMgr.openManageActivity() }

        btnThinking.setOnClickListener {
            thinkingEnabled = !thinkingEnabled
            localAIMgr.setThinking(thinkingEnabled)
            updateThinkingButton()
        }

        btnResetMemory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("기억 초기화")
                .setMessage("AI의 대화 기억과 프로젝트 맥락을 모두 삭제하시겠습니까?")
                .setPositiveButton("삭제") { _, _ ->
                    aiAgent.resetSession()
                    // Also delete session and archive files from disk
                    val projectRoot = PrefsManager.getLastProject(this)
                    if (projectRoot.isNotEmpty()) {
                        File(projectRoot, ".ai_session.json").delete()
                        File(projectRoot, ".ai_memory").deleteRecursively()
                    }
                    chatAdapter.clear()
                    chatAdapter.addMessage(ChatMessage("system", "대화 기억이 초기화되었습니다."))
                    Toast.makeText(this, "기억 삭제 완료", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null).show()
        }
    }

    // ── Load Settings ──────────────────────────────────────────

    private fun loadSettings() {
        // API configs
        val saved = PrefsManager.getApiConfigs(this)
        apiConfigsList.clear()
        try {
            val arr = org.json.JSONArray(saved)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                apiConfigsList.add(AIAgentManager.ApiConfig(o.getString("key"), o.getString("model")))
            }
        } catch (_: Exception) {}
        if (apiConfigsList.isEmpty()) apiConfigsList.add(AIAgentManager.ApiConfig("", "..."))
        refreshApiUI()

        // Local AI mode (restore state without triggering listener effects)
        localAIEnabled = PrefsManager.getLocalAIMode(this)
        switchLocalAI.setOnCheckedChangeListener(null)
        switchLocalAI.isChecked = localAIEnabled
        switchLocalAI.setTextColor(
            if (localAIEnabled) Color.parseColor("#4CAF50")
            else Color.parseColor("#AAAAAA")
        )
        switchLocalAI.setOnCheckedChangeListener { _, isChecked ->
            localAIEnabled = isChecked
            PrefsManager.saveLocalAIMode(this, isChecked)
            if (isChecked) {
                switchLocalAI.setTextColor(Color.parseColor("#4CAF50"))
                showStatus("Local AI 모드 — 첫 메시지 전송 시 자동 시작")
            } else {
                switchLocalAI.setTextColor(Color.parseColor("#AAAAAA"))
                aiAgent.setLocalAI(null, false)
                localAIMgr.stopAndDisconnect()
                hideStatus()
            }
            updateThinkingButton()
        }

        updateThinkingButton()
    }

    // ── Message Sending ────────────────────────────────────────

    private fun sendMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return

        etInput.text.clear()
        chatAdapter.addMessage(ChatMessage("user", text))
        scrollToBottom()

        if (localAIEnabled) {
            if (!localAIMgr.isConnected()) {
                // Auto-start Local AI on first message
                val modelId = ModelManager(this).activeModelId
                if (modelId.isEmpty()) {
                    chatAdapter.addMessage(ChatMessage("ai",
                        "Local AI 모델이 설정되지 않았습니다.\n설정 → 모델 관리에서 모델을 다운로드해주세요."))
                    scrollToBottom()
                    return
                }
                pendingMessage = text
                showStatus("Local AI 시작 중...")
                try {
                    startForegroundService(Intent(this, AIInferenceService::class.java).apply {
                        action = AIInferenceService.ACTION_START
                        putExtra(InferenceConfig.KEY_MODEL_ID, modelId)
                    })
                } catch (e: Exception) {
                    chatAdapter.addMessage(ChatMessage("system", "Local AI 시작 실패: ${e.message}"))
                    pendingMessage = null
                    scrollToBottom()
                    return
                }
                localAIMgr.connectForModel(modelId)
                // executeSend() called from onModelReady
                return
            }
            aiAgent.setLocalAI(localAIMgr, true)
        } else {
            aiAgent.setLocalAI(null, false)
        }

        executeSend(text)
    }

    private fun executeSend(text: String) {
        currentAiMsgIdx = chatAdapter.addMessage(ChatMessage("ai", "..."))
        scrollToBottom()
        setSending(true)
        aiAgent.execute(text)
    }

    // ── UI Helpers ─────────────────────────────────────────────

    private fun setSending(active: Boolean) {
        btnSend.visibility = if (active) View.GONE else View.VISIBLE
        btnStop.visibility = if (active) View.VISIBLE else View.GONE
        etInput.isEnabled  = !active
    }

    private fun showStatus(msg: String) {
        tvStatus.text       = msg
        tvStatus.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        tvStatus.visibility = View.GONE
    }

    /** 무조건 맨 아래로 — 새 메시지 전송 시 또는 생성 시작 시에만 사용 */
    private fun scrollToBottom() {
        val n = chatAdapter.itemCount
        if (n > 0) {
            isAtBottom = true
            recyclerView.scrollToPosition(n - 1)   // smooth 아닌 즉시 이동 (shake 방지)
        }
    }

    /** 사용자가 이미 아래에 있을 때만 스크롤 — 스트리밍 중 사용 */
    private fun scrollToBottomIfNeeded() {
        if (isAtBottom) scrollToBottom()
    }

    private fun updateThinkingButton() {
        if (!localAIEnabled) { btnThinking.visibility = View.GONE; return }
        val modelId = ModelManager(this).activeModelId
        val model   = InferenceConfig.ALL_MODELS.firstOrNull { it.id == modelId }
        btnThinking.visibility = if (model?.supportsThinking == true) View.VISIBLE else View.GONE
        btnThinking.text = if (thinkingEnabled) "Think: ON" else "Think: OFF"
        btnThinking.setTextColor(
            if (thinkingEnabled) Color.parseColor("#4CAF50") else Color.parseColor("#888888")
        )
    }

    // ── API Config UI ──────────────────────────────────────────

    private fun refreshApiUI() {
        chatApiListContainer.removeAllViews()
        apiConfigsList.forEachIndexed { idx, config -> addApiRow(idx, config) }
        applyApiConfigs()
    }

    private fun addApiRow(index: Int, config: AIAgentManager.ApiConfig) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 4, 0, 4) }
        }

        val etKey = EditText(this).apply {
            hint = "API Key ${index + 1}"
            setText(config.key)
            textSize = 11f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#555555"))
            setBackgroundColor(Color.parseColor("#2D2D2D"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            setPadding(16, 0, 16, 0)
        }

        val spModel = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
                .also { it.marginStart = 8 }
            setBackgroundColor(Color.parseColor("#2D2D2D"))
        }
        val spAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            mutableListOf(config.model.ifEmpty { "..." }))
        spModel.adapter = spAdapter

        etKey.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val key = s.toString().trim()
                if (index < apiConfigsList.size)
                    apiConfigsList[index] = apiConfigsList[index].copy(key = key)
                if (key.length >= 10) fetchModels(key, spModel, spAdapter, index)
                else { spAdapter.clear(); spAdapter.add("..."); spAdapter.notifyDataSetChanged() }
                saveApiConfigs()
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        spModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val sel = spAdapter.getItem(pos) ?: "..."
                if (index < apiConfigsList.size)
                    apiConfigsList[index] = apiConfigsList[index].copy(model = sel)
                saveApiConfigs()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        if (config.key.length >= 10)
            fetchModels(config.key, spModel, spAdapter, index, config.model)

        row.addView(etKey)
        row.addView(spModel)

        if (index > 0) {
            val btnDel = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                setBackgroundResource(0)
                setColorFilter(Color.GRAY)
                setOnClickListener {
                    apiConfigsList.removeAt(index)
                    saveApiConfigs()
                    refreshApiUI()
                }
            }
            row.addView(btnDel)
        }

        chatApiListContainer.addView(row)
    }

    private fun fetchModels(key: String, sp: Spinner, adapter: ArrayAdapter<String>,
                            index: Int, current: String? = null) {
        aiAgent.fetchModels(key) { list ->
            mainHandler.post {
                adapter.clear()
                adapter.addAll(list)
                val target = current ?: apiConfigsList.getOrNull(index)?.model
                val pos    = list.indexOf(target).coerceAtLeast(0)
                val sel    = list.getOrElse(pos) { list.firstOrNull() ?: "gemini-2.0-flash" }
                if (index < apiConfigsList.size)
                    apiConfigsList[index] = apiConfigsList[index].copy(model = sel)
                sp.setSelection(pos)
                adapter.notifyDataSetChanged()
                saveApiConfigs()
            }
        }
    }

    private fun saveApiConfigs() {
        val arr = org.json.JSONArray()
        apiConfigsList.forEach { arr.put(org.json.JSONObject().put("key", it.key).put("model", it.model)) }
        PrefsManager.saveApiConfigs(this, arr.toString())
        applyApiConfigs()
    }

    private fun applyApiConfigs() {
        aiAgent.setApiConfigs(apiConfigsList.filter { it.key.isNotEmpty() && it.model != "..." })
    }
}
