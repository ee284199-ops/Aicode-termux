package com.aicode.studio.engine

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aicode.studio.ai.gallery.data.*
import com.aicode.studio.util.PrefsManager
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONObject

@AndroidEntryPoint
class ModelSelectActivity : ComponentActivity() {
    private val viewModel: ModelManagerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ModelSelectScreen(viewModel = viewModel, onBack = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectScreen(viewModel: ModelManagerViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedModelDef by remember { mutableStateOf<InferenceConfig.ModelDef?>(null) }
    var showConfigDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LiteRT 모델 관리", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
        },
        containerColor = Color(0xFF121212)
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Execution Mode Selection
            Surface(
                color = Color(0xFF1A1A1A),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("실행 모드:", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    
                    InferenceConfig.ExecutionMode.values().forEach { mode ->
                        val isSelected = uiState.executionMode == mode
                        Surface(
                            onClick = { viewModel.setExecutionMode(mode) },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) Color(0xFF007ACC) else Color(0xFF2D2D2D),
                            border = if (!isSelected) BorderStroke(1.dp, Color(0xFF444444)) else null,
                            modifier = Modifier.height(32.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = mode.name,
                                    color = if (isSelected) Color.White else Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            LazyColumn(modifier = Modifier.weight(1f).fillMaxSize()) {
                items(uiState.models) { modelDef ->
                    ModelItemWithTos(
                        modelDef = modelDef,
                        isActive = uiState.activeModelId == modelDef.id,
                        isInstalled = viewModel.isInstalled(modelDef),
                        downloadState = uiState.downloadStates[modelDef.id],
                        viewModel = viewModel,
                        onSelect = { viewModel.selectModel(modelDef.id) },
                        onConfig = { selectedModelDef = modelDef; showConfigDialog = true }
                    )
                }
            }
        }
    }

    if (showConfigDialog && selectedModelDef != null) {
        ModelConfigDialog(modelDef = selectedModelDef!!, onDismiss = { showConfigDialog = false })
    }
}

/**
 * Gallery의 DownloadAndTryButton 플로우 이식:
 *  - Gemma 계열 모델: TOS 다이얼로그 (최초 1회) → Accept → 다운로드
 *  - 그 외 모델: 바로 다운로드
 */
@Composable
fun ModelItemWithTos(
    modelDef: InferenceConfig.ModelDef,
    isActive: Boolean,
    isInstalled: Boolean,
    downloadState: ModelManagerViewModel.DownloadState?,
    viewModel: ModelManagerViewModel,
    onSelect: () -> Unit,
    onConfig: () -> Unit
) {
    val context = LocalContext.current
    val isDownloading = downloadState?.isDownloading == true
    val isFailed = downloadState?.isFailed == true

    // Gallery의 showGemmaTermsOfUseDialog 상태와 동일
    var showTosDialog by remember { mutableStateOf(false) }

    // Gallery의 checkMemoryAndClickDownloadButton → handleClickButton 에 해당
    val handleDownloadClick = {
        if (modelDef.needsGemmaTos() && !GemmaTermsStore.isAccepted(context)) {
            // Gemma 계열 + TOS 미동의 → 다이얼로그 표시
            showTosDialog = true
        } else {
            // TOS 불필요하거나 이미 동의 → 바로 다운로드
            viewModel.startDownload(modelDef)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(enabled = isInstalled && !isDownloading) { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isActive -> Color(0xFF2D2D2D)
                isDownloading -> Color(0xFF1A2A1A)
                isFailed -> Color(0xFF2A1A1A)
                else -> Color(0xFF1E1E1E)
            }
        ),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(modelDef.displayName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${modelDef.series} | ${modelDef.paramsBillion}B | ${modelDef.downloadSizeGb}GB",
                        color = Color.Gray, fontSize = 12.sp
                    )
                    if (isActive) Text("현재 활성화됨", color = Color(0xFF4CAF50), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (isFailed && downloadState != null) Text(downloadState.errorMessage, color = Color(0xFFFF5555), fontSize = 10.sp)
                }

                when {
                    isInstalled -> {
                        IconButton(onClick = onConfig) {
                            Icon(Icons.Default.Settings, contentDescription = "Config", tint = Color.Gray)
                        }
                        if (!isActive) {
                            Button(
                                onClick = onSelect,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007ACC))
                            ) { Text("선택", color = Color.White, fontSize = 12.sp) }
                        }
                    }
                    isDownloading -> {
                        IconButton(onClick = { viewModel.cancelDownload(modelDef) }) {
                            Icon(Icons.Default.Cancel, contentDescription = "취소", tint = Color(0xFFFF5555))
                        }
                    }
                    else -> {
                        Button(
                            onClick = handleDownloadClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isFailed) Color(0xFF8B0000) else Color(0xFF444444)
                            )
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text(if (isFailed) "재시도" else "다운로드", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }

            // 다운로드 진행률
            AnimatedVisibility(visible = isDownloading && downloadState != null) {
                if (downloadState != null) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${downloadState.percent}%  ·  ${downloadState.speedText}", color = Color(0xFF4CAF50), fontSize = 11.sp)
                            if (downloadState.remainingSec > 0) {
                                val r = downloadState.remainingSec
                                Text(
                                    "남은: ${if (r >= 60) "${r / 60}분 ${r % 60}초" else "${r}초"}",
                                    color = Color.Gray, fontSize = 11.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { downloadState.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF4CAF50),
                            trackColor = Color(0xFF333333)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${downloadState.receivedBytes / (1024 * 1024)}MB / ${downloadState.totalBytes / (1024 * 1024)}MB",
                            color = Color.DarkGray, fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }

    // Gallery의 GemmaTermsOfUseDialog 와 동일
    if (showTosDialog) {
        GemmaTermsOfUseDialog(
            onAccepted = {
                GemmaTermsStore.accept(context)
                showTosDialog = false
                viewModel.startDownload(modelDef)
            },
            onCancel = { showTosDialog = false }
        )
    }
}

/** Gallery의 GemmaTermsOfUseDialog 이식 */
@Composable
fun GemmaTermsOfUseDialog(onAccepted: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Card(shape = RoundedCornerShape(28.dp)) {
            Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(
                    "Gemma Terms of Use",
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium
                )
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
                    Text(
                        "Gemma 모델은 Gemma Terms of Service의 적용을 받습니다. 계속하기 전에 이용 약관을 검토하고 동의하는지 확인하세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Text(
                        "https://ai.google.dev/gemma/terms",
                        style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                        color = Color(0xFF4FC3F7),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancel) { Text("취소") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onAccepted) { Text("동의 후 계속") }
                }
            }
        }
    }
}

@Composable
fun ModelConfigDialog(modelDef: InferenceConfig.ModelDef, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val initialConfig = remember(modelDef.id) {
        try { JSONObject(PrefsManager.getModelConfig(context, modelDef.id)) } catch (e: Exception) { JSONObject() }
    }
    
    var limitOverMode by remember { mutableStateOf(initialConfig.optBoolean("limitOverMode", false)) }
    var maxTokens by remember { mutableStateOf(initialConfig.optDouble("maxTokens", 2048.0).toFloat()) }
    var temperature by remember { mutableStateOf(initialConfig.optDouble("temperature", 0.7).toFloat()) }
    var topK by remember { mutableStateOf(initialConfig.optDouble("topK", 40.0).toFloat()) }

    // 제한 초과 모드에 따른 최대 범위 설정
    val maxTokenLimit = if (limitOverMode) 50000f else 8192f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${modelDef.displayName} 설정", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 제한 초과 모드 체크박스
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp).clickable { limitOverMode = !limitOverMode }
                ) {
                    Checkbox(
                        checked = limitOverMode,
                        onCheckedChange = { limitOverMode = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFF5555))
                    )
                    Column {
                        Text("제한 초과 모드 (불안정)", color = if (limitOverMode) Color(0xFFFF5555) else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("주의: 메모리 부족으로 앱이 강제 종료될 수 있습니다.", color = Color.Gray, fontSize = 10.sp)
                    }
                }

                Divider(color = Color(0xFF333333), modifier = Modifier.padding(bottom = 16.dp))

                Text("Max Tokens: ${maxTokens.toInt()}", color = if (maxTokens > 32000) Color(0xFFFF5555) else Color.White, fontSize = 12.sp)
                if (maxTokens > 32000) {
                    Text("⚠️ 현재 모델 하드웨어 한계(32,000)를 초과했습니다. 엔진에 의해 자동 조정될 수 있습니다.", color = Color(0xFFFF5555), fontSize = 9.sp)
                }
                Slider(
                    value = maxTokens.coerceAtMost(maxTokenLimit),
                    onValueChange = { maxTokens = it },
                    valueRange = 128f..maxTokenLimit,
                    colors = SliderDefaults.colors(thumbColor = if (limitOverMode) Color(0xFFFF5555) else Color(0xFF007ACC))
                )
                
                Spacer(Modifier.height(8.dp))
                Text("Temperature: ${"%.2f".format(temperature)}", color = Color.White, fontSize = 12.sp)
                Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..2f)
                
                Spacer(Modifier.height(8.dp))
                Text("Top K: ${topK.toInt()}", color = Color.White, fontSize = 12.sp)
                Slider(value = topK, onValueChange = { topK = it }, valueRange = 1f..100f)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val config = JSONObject().apply {
                        put("limitOverMode", limitOverMode)
                        put("maxTokens", maxTokens.toInt())
                        put("temperature", temperature.toDouble())
                        put("topK", topK.toInt())
                    }
                    PrefsManager.saveModelConfig(context, modelDef.id, config.toString())
                    Toast.makeText(context, "설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007ACC))
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = Color.Gray) } },
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}
