package app.concat.editor.aicut.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * AI 一键成片页（方案 §4，链路 ①→④ 全在云端，⑤ 下载到本地由 MainActivity 调
 * engine.importVideo 导入时间线）。
 *
 * 依赖：compose-material3、lifecycle-viewmodel-compose、okhttp（均为普通库，
 * 不需要新 Gradle 插件）。
 */
@Composable
fun AiGenerateScreen(
    cacheDir: java.io.File,
    onImportToTimeline: (java.io.File) -> Unit, // ⑤ 本地成片文件 → engine.importVideo
    viewModel: AiGenerateViewModel = viewModel { AiGenerateViewModel(cacheDir) },
) {
    val state by viewModel.uiState.collectAsState()
    var prompt by remember { mutableStateOf("") }
    var aspect by remember { mutableStateOf("9:16") }
    var aiVideo by remember { mutableStateOf(false) }
    var gatewayUrl by remember { mutableStateOf(FusionConfig.baseUrl) }
    var reachable by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(gatewayUrl) {
        FusionApiClient.baseUrl = gatewayUrl.trim().removeSuffix("/")
        reachable = viewModel.checkGateway()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI 一键成片", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = gatewayUrl,
            onValueChange = { gatewayUrl = it },
            label = { Text("fusion-gateway 地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = {
                when (reachable) {
                    true -> Text("✅ 已连接", color = MaterialTheme.colorScheme.primary)
                    false -> Text("❌ 无法连接 —— 确认电脑上 gateway 已启动（uvicorn app.main:app --host 0.0.0.0）", color = MaterialTheme.colorScheme.error)
                    null -> Text("检测中…")
                }
            },
        )

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("一句话描述你想生成的视频") },
            placeholder = { Text("例：三分钟讲清楚人工智能如何改变普通人的生活") },
            minLines = 3,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("9:16", "16:9", "1:1").forEach { a ->
                FilterChip(selected = aspect == a, onClick = { aspect = a }, label = { Text(a) })
            }
        }

        FilterChip(
            selected = aiVideo,
            onClick = { aiVideo = !aiVideo },
            label = { Text("AI 生成素材（需 MPT 侧配置文生视频服务）") },
        )

        when (val s = state) {
            is AiGenUiState.Idle -> {
                Button(
                    onClick = { viewModel.submit(prompt, aspect, aiVideo) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = prompt.isNotBlank() && reachable == true,
                ) { Text("开始生成") }
            }
            is AiGenUiState.Generating -> {
                LinearProgressIndicator(progress = { (s.progress ?: 0f) / 100f }, modifier = Modifier.fillMaxWidth())
                Text("云端生成中… ${s.progress?.let { "$it%" } ?: "进度未知"}")
                OutlinedButton(onClick = viewModel::cancel) { Text("取消") }
            }
            is AiGenUiState.Downloading -> {
                LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text("下载成片到本地… ${s.percent}%")
            }
            is AiGenUiState.Done -> {
                Text("✅ 成片已就绪：${s.localFile.name}")
                Button(onClick = { onImportToTimeline(s.localFile) }, modifier = Modifier.fillMaxWidth()) {
                    Text("加入时间线")
                }
                OutlinedButton(onClick = { viewModel.discard(s.localFile) }) { Text("丢弃重来") }
            }
            is AiGenUiState.Failed -> {
                Text("❌ ${s.message}", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { viewModel.submit(prompt, aspect, aiVideo) }) { Text("重试") }
            }
        }
    }
}
