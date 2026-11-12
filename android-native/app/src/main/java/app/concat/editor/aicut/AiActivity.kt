package app.concat.editor.aicut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import app.concat.editor.aicut.ui.AiGenerateScreen

/**
 * 独立的 AI 生成入口 Activity。
 *
 * MainActivity 点「AI 成片」启动本页；成片下载完成后 setResult 返回本地路径，
 * 由 MainActivity 调 engine.importVideo 导入时间线（⑤ 关键融合点）。
 * 独立 Activity 而不是对话框 = 对 Concat 的 MainActivity diff 最小化，
 * 上游更新时冲突面最小。
 */
class AiActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AiGenerateScreen(
                    cacheDir = cacheDir,
                    onImportToTimeline = { file ->
                        setResult(RESULT_OK, android.content.Intent().putExtra("imported_path", file.absolutePath))
                        finish()
                    },
                )
            }
        }
    }
}
