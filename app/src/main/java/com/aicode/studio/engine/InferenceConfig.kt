package com.aicode.studio.engine

/**
 * 지원 모델 + IPC 상수.
 * Google AI Edge / LiteRT (Gallery 1.0.11) 기반 모델 구성.
 */
object InferenceConfig {

    const val ACTION_BIND_AI = "com.aicode.studio.engine.BIND_AI"
    const val ACTION_START   = "com.aicode.studio.engine.START"
    const val ACTION_STOP    = "com.aicode.studio.engine.STOP"

    const val MSG_SEND_PROMPT  = 1
    const val MSG_TOKEN_STREAM = 2
    const val MSG_GEN_COMPLETE = 3
    const val MSG_GET_STATUS   = 4
    const val MSG_STATUS_REPLY = 5
    const val MSG_STOP_GEN     = 6
    const val MSG_SET_CONTEXT   = 7
    const val MSG_SET_THINKING  = 8
    const val MSG_CLEAR_HISTORY = 9
    const val MSG_SET_HISTORY   = 10
    const val MSG_SET_CONFIG    = 11

    const val KEY_PROMPT       = "prompt"
    const val KEY_TOKEN        = "token"
    const val KEY_MODEL_ID     = "model_id"
    const val KEY_CONTEXT_JSON = "context_json"
    const val KEY_THINKING     = "thinking_enabled"
    const val KEY_HISTORY      = "history"
    const val KEY_CONFIG_JSON  = "config_json"

    const val NOTIF_CHANNEL_ID = "ai_inference"
    const val NOTIF_ID         = 1001

    enum class EngineType { LLAMA, LITERT }
    enum class ExecutionMode { AUTO, GPU, CPU }

    data class ModelDef(
        val id              : String,
        val displayName     : String,
        val series          : String,
        val paramsBillion   : Float,
        val downloadSizeGb  : Float,
        val expectedSizeBytes: Long,
        val minRamGb        : Int,
        val supportsThinking: Boolean,
        val repoPath        : String,
        val modelPath       : String,
        val weightName      : String,
        val commitHash      : String,
        val engine          : EngineType = EngineType.LITERT,
        val llmSupportImage : Boolean = false
    )

    // Gallery 1.0.11 model_allowlists/1_0_11.json 기준으로 동기화
    // litert-community/* → 인증 없이 다운로드 가능 (public)
    // google/*          → gated (HF OAuth 로그인 필요)
    val ALL_MODELS = listOf(
        // ── 인증 불필요 (public) ─────────────────────────────────────────────────
        ModelDef(
            id               = "gemma3_1b_it",
            displayName      = "Gemma3 1B IT (Int4)",
            series           = "Gemma 3",
            paramsBillion    = 1.0f,
            downloadSizeGb   = 0.54f,
            expectedSizeBytes = 584417280L,
            minRamGb         = 6,
            supportsThinking = false,
            repoPath         = "litert-community/Gemma3-1B-IT",
            modelPath        = "Gemma3_1B_IT",
            weightName       = "gemma3-1b-it-int4.litertlm",
            commitHash       = "42d538a932e8d5b12e6b3b455f5572560bd60b2c"
        ),
        ModelDef(
            id               = "qwen25_15b_instruct",
            displayName      = "Qwen 2.5 1.5B Instruct (Q8)",
            series           = "Qwen 2.5",
            paramsBillion    = 1.5f,
            downloadSizeGb   = 1.49f,
            expectedSizeBytes = 1597931520L,
            minRamGb         = 6,
            supportsThinking = false,
            repoPath         = "litert-community/Qwen2.5-1.5B-Instruct",
            modelPath        = "Qwen2_5_1_5B_Instruct",
            weightName       = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            commitHash       = "19edb84c69a0212f29a6ef17ba0d6f278b6a1614"
        ),
        ModelDef(
            id               = "deepseek_r1_qwen_15b",
            displayName      = "DeepSeek-R1 1.5B (Q8)",
            series           = "DeepSeek",
            paramsBillion    = 1.5f,
            downloadSizeGb   = 1.71f,
            expectedSizeBytes = 1833451520L,
            minRamGb         = 6,
            supportsThinking = true,
            repoPath         = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
            modelPath        = "DeepSeek_R1_Distill_Qwen_1_5B",
            weightName       = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            commitHash       = "e34bb88632342d1f9640bad579a45134eb1cf988"
        ),
        ModelDef(
            id               = "gemma4_e2b_it",
            displayName      = "Gemma 4 E2B IT",
            series           = "Gemma 4",
            paramsBillion    = 2.0f,
            downloadSizeGb   = 2.41f,
            expectedSizeBytes = 2583085056L,
            minRamGb         = 8,
            supportsThinking = true,
            repoPath         = "litert-community/gemma-4-E2B-it-litert-lm",
            modelPath        = "gemma_4_E2B_it",
            weightName       = "gemma-4-E2B-it.litertlm",
            commitHash       = "7fa1d78473894f7e736a21d920c3aa80f950c0db",
            llmSupportImage  = true
        ),
        ModelDef(
            id               = "gemma4_e4b_it",
            displayName      = "Gemma 4 E4B IT",
            series           = "Gemma 4",
            paramsBillion    = 4.0f,
            downloadSizeGb   = 3.4f,
            expectedSizeBytes = 3654467584L,
            minRamGb         = 12,
            supportsThinking = true,
            repoPath         = "litert-community/gemma-4-E4B-it-litert-lm",
            modelPath        = "gemma_4_E4B_it",
            weightName       = "gemma-4-E4B-it.litertlm",
            commitHash       = "9695417f248178c63a9f318c6e0c56cb917cb837",
            llmSupportImage  = true
        ),
        // ── 인증 필요 (gated — HF OAuth 로그인) ─────────────────────────────────
        ModelDef(
            id               = "gemma_3n_e2b_it",
            displayName      = "Gemma 3n E2B IT (Int4) [로그인 필요]",
            series           = "Gemma 3n",
            paramsBillion    = 2.0f,
            downloadSizeGb   = 3.4f,
            expectedSizeBytes = 3655827456L,
            minRamGb         = 8,
            supportsThinking = false,
            repoPath         = "google/gemma-3n-E2B-it-litert-lm",
            modelPath        = "gemma_3n_E2B_it",
            weightName       = "gemma-3n-E2B-it-int4.litertlm",
            commitHash       = "ba9ca88da013b537b6ed38108be609b8db1c3a16",
            llmSupportImage  = true
        ),
        ModelDef(
            id               = "gemma_3n_e4b_it",
            displayName      = "Gemma 3n E4B IT (Int4) [로그인 필요]",
            series           = "Gemma 3n",
            paramsBillion    = 4.0f,
            downloadSizeGb   = 4.58f,
            expectedSizeBytes = 4919541760L,
            minRamGb         = 12,
            supportsThinking = false,
            repoPath         = "google/gemma-3n-E4B-it-litert-lm",
            modelPath        = "gemma_3n_E4B_it",
            weightName       = "gemma-3n-E4B-it-int4.litertlm",
            commitHash       = "297ed75955702dec3503e00c2c2ecbbf475300bc",
            llmSupportImage  = true
        )
    )
}
