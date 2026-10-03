# 最终验证记录

设备：Redmi K20 Pro，Android 14，ARM64，1080×2340，独立测试包 `com.carmusic.app.smoke`。正式包通过覆盖安装保留现有数据。

| 检查 | 结果 | 记录 |
| --- | --- | --- |
| 完整真机回归 | 21 项，0 失败/错误 | android-full-regression-results.xml |
| 补齐黑胶、真实 PCM 频谱、逐字歌词后的视频回归 | 10 项，0 失败/错误 | android-video-effects-regression-results.xml |
| 队列定位、窄屏歌词字号、导航与歌词增量 | 5 项，0 失败/错误 | android-final-navigation-queue-results.xml |
| Kotlin 单元测试 | 14 项通过 | unit/ |
| Go 上游 Web/API 完整测试 | 通过 | `go test ./internal/web -count=1` |
| HTML 语法、队列替换/单曲置顶去重 | 通过 | `node --check design/portrait-v1/app.js`、`node scripts/verify-prototype-queue.cjs` |

21 项完整回归在 2026-10-02 运行，视频和界面增量在 2026-10-03 运行。三批真机数量有重叠，不将它们相加为独立测试总数。当前最终构建包含全部增量及真实 GitHub 关于链接。上游源码完整性核对未遗漏其原 Git 跟踪文件；上传候选未包含 Cookie、运行数据库、私钥、签名或 SDK 本机配置。

扫码可解码不代表最终账号授权。QQ 正式包已观察到关联后的真实个人歌单；其他账号，尤其汽水在限流后的确认，尚需平台签发真实会话。私人 WebDAV、长曲导出性能和实车媒体按键/导航焦点尚未实测。功能覆盖与用户确认的原版差异见 ../UPSTREAM_ANDROID_FEATURE_AUDIT.md。
