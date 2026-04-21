package com.aicode.studio.ai

import java.io.File

/**
 * AI 프롬프트 빌더
 * - 파일 트리 기반 컨텍스트
 * - 관련 파일만 선택적 전송 (토큰 예산 관리)
 * - 시스템/유저/피드백 프롬프트 분리
 */
class PromptBuilder(private val maxChars: Int = 60_000) {

    data class FileCtx(val path: String, val content: String, val size: Int)

    fun buildSystemPrompt(root: File, apiIndex: Int = 1, archiveSummary: String = "", projectContext: String = ""): String {
        val tree = buildTree(root)
        val contextSection = if (projectContext.isNotEmpty()) """

## Project Global Context (Current Status & Goals)
$projectContext
""" else ""
        val archiveSection = if (archiveSummary.isNotEmpty()) """

## Past Conversation Archive Index
$archiveSummary
""" else ""
        return """You are an expert Android developer agent.
Respond to the user as a helpful peer programmer.

## Project Info
- **Package name**: com.aicode.studio
- **Application name**: AI Code Studio
- **Root path**: ${root.absolutePath}
- **Min SDK**: 24  |  **Target SDK**: 28
- **Language**: Java + Kotlin + C/C++ (NDK)

## Core Principles
1. **Conversation First**: If the user greets you (e.g., "Hi", "ㅎㅇ") or asks a general question, just reply in the 'response' field. DO NOT use any tools.
2. **On-Demand Analysis**: Only use tools (grep, read_range, etc.) when the user gives a specific instruction that requires looking at the code.
3. **Be Faithful to User Requests**: Do not perform extra work or analysis that the user didn't ask for.
4. **Internal vs External**: Use 'thought' for planning (in Korean). Use 'response' for your actual reply (in Korean).

## Output Format (STRICT JSON)
- YOUR ENTIRE RESPONSE MUST BE A SINGLE VALID JSON OBJECT.
- DO NOT OMIT COMMAS BETWEEN FIELDS.
- Respond in Korean for BOTH 'thought' and 'response'.

```json
{
  "thought": "(분석 및 계획 - 한국어)",
  "response": "사용자에게 직접 전달할 답변 (한국어)",
  "project_context": "Current task summary and findings",
  "read_archive": null,
  "grep": null,
  "read_range": null,
  "patch": null,
  "updates": [],
  "deletes": [],
  "run_shell": null
}
```

## Rules for Tools
- If no tool is needed, set the field to `null` (for objects/strings) or `[]` (for arrays).
- **Never omit commas** between JSON fields.
- Use `patch` for small, specific code changes (requires exact 'old' string).
- Use `updates` for creating new files or replacing entire file content.
- Use `run_shell` to execute a command in Termux (the app's built-in Linux terminal).
  Format: `{"cmd": "bash command here", "timeout": 30}`
  Examples: `{"cmd": "pkg install python -y", "timeout": 60}`, `{"cmd": "ls ${'$'}PREFIX/bin", "timeout": 10}`
  The command runs in the background with PATH/PREFIX/LD_PRELOAD set correctly.
  stdout (up to 4000 chars) and stderr (up to 1000 chars) are returned as tool feedback.

## Project Structure (Tree)
```
$tree
```$contextSection$archiveSection"""
    }

    fun buildUserPrompt(command: String): String {
        return "## Request\n$command"
    }

    fun buildFeedback(results: List<String>): String =
        "## System Report\n${results.joinToString("\n")}\n\nContinue if needed. If done, return thought with summary and empty updates."

    // ─── Tree Builder ─────────────────────────────

    /** 로컬 AI용 경량 컨텍스트: 파일 트리 (내용은 도구로 읽도록 유도) */
    fun buildLocalContext(root: File): String {
        val tree = buildTree(root)
        return "## Project Files\n```\n$tree\n```"
    }

    /** Directories that are irrelevant noise for AI context — always skipped. */
    private val SKIP_DIRS = setOf("build", ".gradle", ".idea", ".git", "intermediates",
        "__pycache__", "node_modules", ".cxx", "generated", "outputs")

    internal fun buildTree(root: File, prefix: String = "", depth: Int = 0): String {
        val sb = StringBuilder()
        val kids = root.listFiles()
            ?.filter { f ->
                !f.name.startsWith(".") && f.name !in SKIP_DIRS
            }
            ?.sortedWith(compareBy({ it.isFile }, { it.name })) ?: return ""
        for ((i, f) in kids.withIndex()) {
            val last = i == kids.size - 1
            val conn = if (last) "└── " else "├── "
            sb.appendLine("$prefix$conn${f.name}${if (f.isFile) " (${fmtSize(f.length())})" else ""}")
            if (f.isDirectory) sb.append(buildTree(f, prefix + if (last) "    " else "│   ", depth + 1))
        }
        return sb.toString()
    }

    private fun fmtSize(b: Long) = when {
        b < 1024 -> "${b}B"; b < 1048576 -> "${b/1024}KB"; else -> "${"%.1f".format(b/1048576.0)}MB"
    }
}