# 上游源码基线

- 项目：Go Music DL / GoMusicDll
- 来源：https://github.com/guohuiyuan/go-music-dl
- 基线提交：`c1b188c366ece39cb19a61d108e4fbf7c29271e5`
- 原许可证：GNU AGPL v3，完整文本保存在根目录 `LICENSE` 和 `engine/upstream/LICENSE`。

`engine/upstream` 是完整源码快照，不依赖仅存在于本机的子仓库引用。本项目在此基线上增加 `cmd/carmusic` Android 宿主，绑定本机地址与独立测试端口，并修改 Web 服务及音乐搜索 JSON 返回；相关接口回归测试保存在 `internal/web/native_json_test.go`。

本机原上游 Git 历史保存在忽略目录 `engine/upstream.git`，不属于上传内容。平台 Cookie、下载文件和运行数据库也不属于源码快照。
