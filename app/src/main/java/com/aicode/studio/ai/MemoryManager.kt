package com.aicode.studio.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 프로젝트별 AI 대화 기억 관리.
 *
 * 파일 구조:
 *   .ai_session.json             → 현재 활성 대화 (archive_summary + messages)
 *   .ai_memory/archive_001.json  → 잘려나간 과거 대화 원본
 *   .ai_memory/session_memory.md → Claude Code 방식의 구조화된 세션 메모리 (9개 섹션)
 *
 * Session Memory 섹션 (Claude Code SessionMemory 이식):
 *   Session Title / Current State / Task Specification / Files and Functions /
 *   Workflow / Errors & Corrections / Codebase Documentation / Learnings /
 *   Key Results / Worklog
 */
class MemoryManager(private val root: File) {

    companion object {
        const val SESSION_FILE        = ".ai_session.json"
        const val ARCHIVE_DIR         = ".ai_memory"
        const val SESSION_MEMORY_FILE = ".ai_memory/session_memory.md"
        const val MAX_LINES           = 4000   // 트리밍 시작 임계점
        const val TARGET_LINES        = 2000   // 트리밍 후 목표 라인 수
        const val KEEP_HEAD           = 1      // 초기 시스템 지시문 보존
        const val SESSION_MEMORY_MAX_CHARS = 48_000  // ~12,000 tokens
        const val SECTION_MAX_CHARS        =  8_000  // ~2,000 tokens per section

        /**
         * Claude Code `DEFAULT_SESSION_MEMORY_TEMPLATE`에서 이식.
         * 각 섹션의 이탤릭 설명줄은 템플릿 지침 — AI가 수정하면 안 됨.
         */
        val SESSION_MEMORY_TEMPLATE: String = """
# Session Title
_5-10단어 압축 세션 제목 (핵심 정보 밀도 최대, 불필요한 말 금지)_

# Current State
_지금 작업 중인 것. 미완료 태스크. 바로 다음 할 일._

# Task Specification
_사용자가 무엇을 만들어달라고 했나? 설계 결정 및 추가 맥락_

# Files and Functions
_중요 파일 목록 — 각 파일이 무엇을 하는지, 왜 중요한지, 관련 함수명 포함_

# Workflow
_보통 어떤 순서로 어떤 명령/도구를 실행하나? 출력 해석 방법_

# Errors & Corrections
_발생한 오류와 수정 방법. 사용자가 수정한 것. 다시 시도하면 안 되는 접근법_

# Codebase and System Documentation
_중요 시스템 컴포넌트. 동작 방식. 컴포넌트 간 연결 구조_

# Learnings
_잘 된 것. 안 된 것. 피해야 할 것. (다른 섹션 내용과 중복 금지)_

# Key Results
_사용자가 요청한 정확한 결과물(코드, 답변, 표 등)이 있으면 여기에 정확히_

# Worklog
_단계별 시도/완료 사항 — 극도로 간결하게_
""".trimIndent()

        /**
         * Claude Code `BASE_COMPACT_PROMPT`에서 이식.
         * 트리밍 시 아카이브 요약 생성에 사용 (9섹션 구조화 포맷).
         */
        fun buildCompactSummaryPrompt(conversationText: String): String = """
Create a structured archive summary of this conversation. Be thorough and specific.

Reply in Korean. Include exact file names, function names, error messages, code snippets where relevant.

Format:
1. Primary Request and Intent: (사용자의 명시적 요청과 의도)
2. Key Technical Concepts: (중요 기술 개념, 프레임워크, 패턴)
3. Files Modified: (수정/조회한 파일명 + 변경 내용 + 이유)
4. Errors and Fixes: (발생한 오류 + 해결 방법)
5. Problem Solving: (해결한 문제, 진행 중인 트러블슈팅)
6. User Messages: (모든 사용자 메시지 요약)
7. Pending Tasks: (미완료 태스크)
8. Final State: (이 대화가 끝날 때 상태)

Conversation:
$conversationText
""".trimIndent()

        /**
         * Claude Code `getCompactUserSummaryMessage()`에서 이식.
         * compact 후 히스토리 맨 앞에 삽입되는 요약 메시지 포맷.
         */
        fun wrapCompactSummary(summary: String): String =
            "This session is being continued from a compacted conversation. " +
            "The summary below covers the earlier portion of the conversation.\n\n$summary"

        /**
         * Claude Code `BASE_COMPACT_PROMPT`에서 이식 (한국어 대화 특화).
         * 오래된 메시지들을 one-shot 시크릿 요청으로 요약할 때 사용.
         * 반환값은 요약 텍스트 (히스토리 저장 없음).
         */
        fun buildConversationCompactPrompt(conversationText: String): String = """
CRITICAL: Respond with TEXT ONLY. Do NOT include any JSON, tool calls, or code blocks.
Your entire response must be a plain-text structured summary.

Your task is to create a detailed summary of the conversation below. This summary will replace the original messages, so be thorough — technical details, code snippets, file names, error messages must all be preserved.

Your summary MUST include these sections:

1. Primary Request and Intent
   (사용자의 명시적 요청과 의도를 상세하게)

2. Key Technical Concepts
   (중요 기술 개념, 프레임워크, 패턴, 라이브러리)

3. Files and Code Sections
   (수정/조회된 파일명, 변경 내용 요약, 중요 코드 스니펫)

4. Errors and Fixes
   (발생한 오류와 해결 방법, 사용자 피드백)

5. Problem Solving
   (해결된 문제와 진행 중인 트러블슈팅)

6. All User Messages
   (모든 사용자 메시지 목록)

7. Pending Tasks
   (미완료 태스크)

8. Current Work
   (요약 직전 작업하던 내용 — 파일명, 코드 포함)

9. Next Step
   (가장 최근 작업과 직결된 다음 단계)

Conversation to summarize:
$conversationText
""".trimIndent()

        /**
         * 세션 메모리가 SESSION_MEMORY_RECOMPACT_CHARS를 초과할 때 재압축용 프롬프트.
         * Claude Code의 microcompact + section budget 초과 시 condensation 로직에서 이식.
         * 핵심 정보는 모두 보존하면서 토큰을 절반 이하로 줄이는 것이 목표.
         */
        fun buildSessionMemoryRecompressPrompt(currentMemory: String): String = """
The session memory below has grown too large. Aggressively condense it to fit within a small context window, while preserving ALL critical information.

CRITICAL RULES (same as the original template):
- Keep ALL section headers exactly as-is (lines starting with '#')
- Keep ALL italic _description_ lines exactly as-is (lines starting and ending with '_')
- ONLY condense the actual content below each description line
- "# Current State" must remain detailed — this is most critical for continuity
- "# Errors & Corrections" must remain detailed — prevents repeated mistakes
- For other sections: merge related items, remove redundant/outdated entries
- Remove details that are no longer relevant to current work
- Target: reduce to under half the current length
- Return ONLY the complete condensed session memory (all sections intact)

Current session memory to condense:
$currentMemory
""".trimIndent()

        /**
         * Claude Code `buildSessionMemoryUpdatePrompt`에서 이식.
         * 세션 메모리 파일 갱신 시 AI에게 전달하는 프롬프트.
         */
        fun buildSessionMemoryUpdatePrompt(currentNotes: String): String = """
Based on the conversation above, update the session notes below.

CRITICAL RULES:
- NEVER modify, delete, or add section headers (lines starting with '#')
- NEVER modify the italic _section description_ lines (lines starting/ending with '_')
- ONLY update actual content that appears BELOW the italic descriptions
- Always update "# Current State" to reflect the most recent work status
- Include specifics: file paths, function names, error messages, exact code snippets
- Keep each section under ~500 words; condense older entries if approaching limit
- Prioritize keeping "Current State" and "Errors & Corrections" accurate and detailed
- Return ONLY the complete updated notes (all sections, exact same markdown structure)
- Do NOT add any text outside the session notes structure

Current session notes:
$currentNotes

Return the complete updated session notes:
""".trimIndent()
    }

    private val sessionFile    get() = File(root, SESSION_FILE)
    private val archiveDir     get() = File(root, ARCHIVE_DIR)
    private val sessionMemFile get() = File(root, SESSION_MEMORY_FILE)

    data class ArchiveEntry(
        val seq      : Int,
        val time     : String,
        val summary  : String,
        val fileName : String
    )

    data class Session(
        val archiveSummary: MutableList<ArchiveEntry> = mutableListOf(),
        val messages      : JSONArray = JSONArray(),
        val localHistory  : List<Pair<String, String>> = emptyList(),
        val projectContext: String = ""
    )

    // ── 세션 로드/저장 ──────────────────────────────────────────
    fun load(): Session {
        if (!sessionFile.exists()) return Session()
        return try {
            val text = sessionFile.readText()
            val obj = JSONObject(text)
            val summaries = mutableListOf<ArchiveEntry>()
            val arr = obj.optJSONArray("archive_summary") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                summaries.add(ArchiveEntry(
                    seq      = e.getInt("seq"),
                    time     = e.getString("time"),
                    summary  = e.getString("summary"),
                    fileName = e.getString("file")
                ))
            }
            val localHistory = mutableListOf<Pair<String, String>>()
            val lhArr = obj.optJSONArray("local_history") ?: JSONArray()
            for (i in 0 until lhArr.length()) {
                val e = lhArr.getJSONObject(i)
                localHistory.add(e.getString("u") to e.getString("a"))
            }
            Session(
                summaries,
                obj.optJSONArray("messages") ?: JSONArray(),
                localHistory,
                obj.optString("project_context", "")
            )
        } catch (_: Exception) { Session() }
    }

    fun save(session: Session) {
        try {
            val summaryArr = JSONArray()
            session.archiveSummary.forEach { e ->
                summaryArr.put(JSONObject().apply {
                    put("seq",     e.seq)
                    put("time",    e.time)
                    put("summary", e.summary)
                    put("file",    e.fileName)
                })
            }
            val localArr = JSONArray()
            session.localHistory.forEach { (u, a) ->
                localArr.put(JSONObject().apply { put("u", u); put("a", a) })
            }
            val obj = JSONObject().apply {
                put("archive_summary", summaryArr)
                put("messages", session.messages)
                put("local_history", localArr)
                put("project_context", session.projectContext)
            }
            sessionFile.writeText(obj.toString(2))
        } catch (_: Exception) {}
    }

    // ── Session Memory 로드/저장 (Claude Code 방식) ────────────
    /**
     * .ai_memory/session_memory.md 로드.
     * 파일이 없으면 템플릿으로 초기화 후 반환.
     */
    fun loadSessionMemory(): String {
        archiveDir.mkdirs()
        return try {
            if (!sessionMemFile.exists()) {
                sessionMemFile.writeText(SESSION_MEMORY_TEMPLATE)
                SESSION_MEMORY_TEMPLATE
            } else {
                val content = sessionMemFile.readText()
                if (content.isBlank()) SESSION_MEMORY_TEMPLATE else content
            }
        } catch (_: Exception) { SESSION_MEMORY_TEMPLATE }
    }

    /**
     * 세션 메모리 저장. 섹션별 최대 길이 초과 시 자동 트런케이션.
     */
    fun saveSessionMemory(content: String) {
        try {
            archiveDir.mkdirs()
            val truncated = truncateSessionMemory(content)
            sessionMemFile.writeText(truncated)
        } catch (_: Exception) {}
    }

    /**
     * 세션 메모리가 실질적으로 비어 있는지 확인 (템플릿만 있는 경우).
     * Claude Code `isSessionMemoryEmpty()`에서 이식.
     */
    fun isSessionMemoryEmpty(content: String): Boolean =
        content.trim() == SESSION_MEMORY_TEMPLATE.trim()

    /**
     * Claude Code `truncateSessionMemoryForCompact()`에서 이식.
     * 섹션별 최대 길이를 초과하는 경우 잘라냄.
     */
    private fun truncateSessionMemory(content: String): String {
        val lines = content.split("\n")
        val output = mutableListOf<String>()
        var currentHeader = ""
        val currentLines = mutableListOf<String>()

        fun flushSection() {
            if (currentHeader.isEmpty()) { output.addAll(currentLines); return }
            val sectionContent = currentLines.joinToString("\n")
            if (sectionContent.length <= SECTION_MAX_CHARS) {
                output.add(currentHeader)
                output.addAll(currentLines)
            } else {
                output.add(currentHeader)
                var charCount = 0
                for (line in currentLines) {
                    if (charCount + line.length + 1 > SECTION_MAX_CHARS) break
                    output.add(line)
                    charCount += line.length + 1
                }
                output.add("\n[... 섹션이 길이 제한으로 잘림 ...]")
            }
        }

        for (line in lines) {
            if (line.startsWith("# ")) {
                flushSection()
                currentHeader = line
                currentLines.clear()
            } else {
                currentLines.add(line)
            }
        }
        flushSection()

        val result = output.joinToString("\n")
        return if (result.length > SESSION_MEMORY_MAX_CHARS)
            result.take(SESSION_MEMORY_MAX_CHARS) + "\n\n[... 전체 세션 메모리가 길이 제한으로 잘림 ...]"
        else result
    }

    // ── 트리밍 로직 (Dynamic Scaling) ──────────────────────────

    fun needsTrimming(): Boolean {
        if (!sessionFile.exists()) return false
        return sessionFile.readLines().size > MAX_LINES
    }

    /**
     * 세션 파일을 TARGET_LINES 수준으로 줄이기 위해 오래된 메시지들을 아카이브.
     */
    fun trimToTarget(session: Session, summarizer: ((String) -> String?)? = null): Session {
        if (!needsTrimming()) return session

        val currentLines = sessionFile.readLines().size
        val msgs = session.messages
        val totalMsgs = msgs.length()

        if (totalMsgs <= KEEP_HEAD + 5) return session

        val linesToCut = currentLines - TARGET_LINES
        val avgLinesPerMsg = currentLines.toFloat() / totalMsgs
        var msgsToCutCount = (linesToCut / avgLinesPerMsg).toInt().coerceAtLeast(1)
        msgsToCutCount = msgsToCutCount.coerceAtMost(totalMsgs - KEEP_HEAD - 5)
        if (msgsToCutCount <= 0) return session

        val cutMsgs = JSONArray()
        for (i in 0 until msgsToCutCount) {
            cutMsgs.put(msgs.get(KEEP_HEAD))
            msgs.remove(KEEP_HEAD)
        }

        archiveDir.mkdirs()
        val seq = session.archiveSummary.size + 1
        val archiveName = "$ARCHIVE_DIR/archive_%03d.json".format(seq)
        try { File(root, archiveName).writeText(cutMsgs.toString(2)) } catch (_: Exception) {}

        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val rawText = buildTextForSummary(cutMsgs)
        val summaryText = summarizer?.invoke(rawText)
            ?: "${msgsToCutCount}개 대화 아카이브 ($time)"

        val newSummaries = session.archiveSummary.toMutableList()
        newSummaries.add(ArchiveEntry(seq, time, summaryText, archiveName))

        return session.copy(archiveSummary = newSummaries, messages = msgs)
    }

    private fun buildTextForSummary(msgs: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            val role = m.optString("role", "?")
            val text = m.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "") ?: ""
            sb.appendLine("[$role]: ${text.take(300)}")
        }
        return sb.toString().trim()
    }

    fun buildArchiveIndexText(session: Session): String {
        if (session.archiveSummary.isEmpty()) return ""
        return session.archiveSummary.joinToString("\n") {
            "ID:${it.seq} | Time:${it.time} | Summary:${it.summary} | File:${it.fileName}"
        }
    }

    fun readArchive(fileName: String): String? =
        try { File(root, fileName).readText() } catch (_: Exception) { null }
}
