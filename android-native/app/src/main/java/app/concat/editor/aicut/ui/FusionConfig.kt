package app.concat.editor.aicut.ui

/**
 * fusion-gateway 地址配置。
 *
 * 真机默认填局域网里跑 gateway 的电脑地址；模拟器用 10.0.2.2 访问宿主机。
 * AI 生成页里可随时改（FusionApiClient.baseUrl 同步更新）。
 */
object FusionConfig {
    @Volatile
    var baseUrl: String = "http://10.0.2.2:8000"
}
