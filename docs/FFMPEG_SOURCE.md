# FFmpeg 二进制来源记录

当前 ARM64 程序由上游 GoMusicDll 的 Android 构建脚本下载自 [SourceForge Xabe 镜像](https://sourceforge.net/projects/xabe-ffmpeg.mirror/files/executables/ffmpeg-android-arm64.zip/download)。程序与许可证均不属于 MIT。

## 已核实的构建信息

设备执行 `ffmpeg -version` 返回 `v4.3-dev-2955`，Android clang 9.0.8，静态链接，启用 `--enable-gpl --enable-version3`。包括 x264、x265、xvid、vid.stab、rubberband 等 GPL 组件及其他第三方库。

| 文件 | SHA256 |
| --- | --- |
| libffmpeg.so | e056a614d2993bcc9d94cee950486136ec154213d890362779cb7f2026294802 |
| libffprobe.so | 56caf9baed25b043e64f49533eb22488dd74ebdc6172bb2e9d002e9403aa8140 |

APK 随附 `licenses/FFmpeg-GPL-3.0.txt` 和 `licenses/MobileFFmpeg-GPL-3.0.txt` 原文。

## 源码线索与交付限制

版本号和构建路径指向 MobileFFmpeg 4.3.2 系列，可参阅 [版本对应表](https://github.com/tanersener/mobile-ffmpeg/blob/master/docs/index.md)、[4.3.2 源码与构建脚本](https://github.com/tanersener/mobile-ffmpeg/tree/v4.3.2)、[GPL 许可说明](https://github.com/tanersener/mobile-ffmpeg/wiki/License) 和 [FFmpeg 分发要求](https://ffmpeg.org/legal.html)。该版本对应关系是构建来源线索，不能证明镜像中的自定义静态程序与原标签完全一致。

目前镜像未提供这两个文件的完整对应源码、所有依赖版本和实际构建补丁。补充 GPL 文本与项目链接不等于完成对应源码交付。新的公开分发应先取得这一构建的完整源码材料，或以可复现的源码构建替换程序，并一同交付所有依赖的许可证与对应源码。当前版本沿用已验证的编码器，该对应源码材料的缺口仍待解决。
