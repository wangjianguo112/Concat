package app.concat.editor.aicut.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.concat.editor.aicut.api.FusionApiClient
import app.concat.editor.aicut.api.FusionApiClient.Artifact
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** AI 生成页状态机 —— 与 gateway GenStatus.status 对齐 */
sealed interface AiGenUiState {
    data object Idle : AiGenUiState
    data class Generating(val genId: String, val progress: Float?) : AiGenUiState
    data class Downloading(val percent: Int) : AiGenUiState
    data class Done(val localFile: File) : AiGenUiState
    data class Failed(val message: String) : AiGenUiState
}

class AiGenerateViewModel(private val cacheDir: File) : ViewModel() {

    private val _uiState = MutableStateFlow<AiGenUiState>(AiGenUiState.Idle)
    val uiState: StateFlow<AiGenUiState> = _uiState

    private var pollJob: Job? = null

    suspend fun checkGateway(): Boolean = FusionApiClient.health()

    /** ①→③：提交任务并轮询 */
    fun submit(prompt: String, aspect: String, aiVideo: Boolean) {
        if (prompt.isBlank()) return
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            try {
                val created = FusionApiClient.createGen(
                    FusionApiClient.GenRequest(prompt = prompt.trim(), aspect = aspect, aiVideo = aiVideo)
                )
                _uiState.value = AiGenUiState.Generating(created.genId, null)
                pollLoop(created.genId, created.pollIntervalS.toLong().coerceIn(1, 10))
            } catch (e: Exception) {
                _uiState.value = AiGenUiState.Failed(e.message ?: "网络错误")
            }
        }
    }

    private suspend fun pollLoop(genId: String, intervalS: Long) {
        while (true) {
            val status = FusionApiClient.getGen(genId)
            when (status.status) {
                "completed" -> {
                    val artifact = status.artifacts.firstOrNull()
                    if (artifact == null) {
                        _uiState.value = AiGenUiState.Failed("任务完成但没有产物")
                        return
                    }
                    _uiState.value = AiGenUiState.Downloading(0)
                    // ④ 下载到应用私有目录 —— engine.importVideo 只认本地路径
                    val target = File(File(cacheDir, "media"), "aicut-$genId.mp4")
                    FusionApiClient.downloadTo(artifact, target) { pct ->
                        _uiState.value = AiGenUiState.Downloading(pct)
                    }
                    _uiState.value = AiGenUiState.Done(target)
                    return
                }
                "failed" -> {
                    _uiState.value = AiGenUiState.Failed(status.error ?: "MPT 生成失败")
                    return
                }
                "cancelled" -> {
                    _uiState.value = AiGenUiState.Idle
                    return
                }
                else -> _uiState.value = AiGenUiState.Generating(genId, status.progress)
            }
            delay(intervalS * 1000)
        }
    }

    fun cancel() {
        pollJob?.cancel()
        val current = _uiState.value
        if (current is AiGenUiState.Generating) {
            viewModelScope.launch { FusionApiClient.deleteGen(current.genId) }
        }
        _uiState.value = AiGenUiState.Idle
    }

    /** 回收成片文件（导入失败/用户放弃时） */
    fun discard(file: File) {
        file.delete()
        _uiState.value = AiGenUiState.Idle
    }

    override fun onCleared() {
        pollJob?.cancel()
    }
}
