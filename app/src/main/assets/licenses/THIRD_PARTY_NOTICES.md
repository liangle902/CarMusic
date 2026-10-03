# 第三方组件与来源

星河音乐项目仓库：[liangle902/CarMusic](https://github.com/liangle902/CarMusic)（私有）。本文件与源码、构建脚本和许可证一起交付，记录当前构建的第三方来源。

| 组件 | 来源 | 说明 |
| --- | --- | --- |
| Go Music DL / GoMusicDll | https://github.com/guohuiyuan/go-music-dl | 本地源码在 `engine/upstream`，许可证 GNU AGPL v3，原许可证在 `engine/upstream/LICENSE`。包含星河音乐的 Android 宿主命令和原生 JSON 接口修改。 |
| music-lib | Go Music DL 的 `go.mod` 固定版本 | 由 Go 模块机制解析，具体版本和校验记录在 `go.mod` / `go.sum`。 |
| FFmpeg / ffprobe ARM64 | 上游 `.github/scripts/download_android_ffmpeg.ps1` 指定的 SourceForge Xabe mirror | 使用 https://sourceforge.net/projects/xabe-ffmpeg.mirror/files/executables/ffmpeg-android-arm64.zip/download 包；含 x264 编码能力。FFmpeg 及其编译依赖的具体许可、相应源码和构建选项须按该二进制配置核对，不能将整包视为 MIT。 |
| libc++_shared.so | 上游官方 v1.1.1 ARM64 APK | 从 https://github.com/guohuiyuan/go-music-dl/releases/download/v1.1.1/music-dl_arm64-v8a.apk 提取，SHA256 `d523468d62d9b603cb3354294d70d4b2feabf2c3f1e43b0c96c9aabf32813708`。NDK LLVM C++ 运行库。 |
| AndroidX / Compose / Media3 | Google AndroidX Maven | 原生界面、媒体会话、播放和缓存；Apache 2.0。版本在 `app/build.gradle.kts`。 |
| OkHttp / Gson / ZXing | 各组件官方 Maven 发布 | 请求、JSON、二维码；Apache 2.0。 |
| Coil | Coil 官方 Maven 发布 | 封面加载；Apache 2.0。 |

目前 APK 为本地开发验证构建，不代表已完成面向公众的分发材料。源码和二进制来源应在发布时保持一致。
