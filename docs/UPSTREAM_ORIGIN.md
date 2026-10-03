# 上游源码基线

- 功能来源：Go Music DL / GoMusicDll
- 项目地址：https://github.com/guohuiyuan/go-music-dl
- 基线提交：`c1b188c366ece39cb19a61d108e4fbf7c29271e5`
- 许可证：GNU AGPL v3，完整文本保存在根目录 `LICENSE` 和 `engine/upstream/LICENSE`。

`engine/upstream` 保存上游源码及本项目修改，不依赖外部子仓库。本项目增加 `cmd/carmusic` Android 宿主，将服务限定为设备本机地址；补充原生客户端需要的 JSON 接口、歌词及自动换源功能。

平台账号凭据、音乐文件、运行数据库和签名私钥不属于源码交付内容。
