# 横竖屏自适应

v1.5.0 根据应用窗口扣除系统栏、刘海及键盘后的可用宽高布局。宽度至少 600dp 且宽大于高时使用横向结构；高度低于 480dp 时压缩页头和留白，触控区域保持至少 48dp。配色、图标、主题、播放与账号数据共用一套实现。

| 页面 | 横向布局 |
| --- | --- |
| 首页 | Slogan 与标题同行，左侧继续播放卡，右侧模块入口，最近听过独立横向区域 |
| 正在播放 | 左侧封面与歌曲信息，右侧滚动歌词；进度、模式、上一首、播放/暂停、下一首与队列集中在底部 |
| 歌单与收藏 | 文案与标题同行，平台标签、关联账号和刷新共用一行；去除重复分类标题，低高度使用 88dp 封面，网格按空间增加列数；详情头部随歌曲列表滚动 |
| 搜索 | 圆角搜索输入与按钮位于标题右侧，合计不超过 420dp；类型与音源入口共用 48dp 筛选栏，音源在多选弹层中显示，结果列表占满内容宽度；搜索后收起键盘 |
| 系统设置 | 左侧分类，右侧内容滚动；二维码与状态在低高度窗口左右排列 |
| 本地音乐与下载 | 操作自动换行，列表占据剩余空间 |
| 播放队列 | 弹层按可用高度收缩，列表独立滚动，保留右侧拖动手柄与删除 |

旋转与窗口尺寸变化由 Compose 重新布局，保留当前页面、搜索结果、选中歌单、歌词位置与账号会话。播放由同一个后台服务持续管理，不因方向切换重新播放。

HTML 原型：`design/landscape-v1/index.html`。颜色沿用夜间背景 `#151B23`、表面 `#202832`、文字 `#F8FAFC`、次要文字 `#AEBAC7`、主色 `#8EBEF2`；日间沿用已有主题。艺术 Slogan 保持“让好音乐，随心而听。”。

尺寸和状态处理参考 [Android 窗口尺寸指南](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes) 与 [配置变化指南](https://developer.android.com/guide/topics/resources/runtime-changes)。

搜索输入及筛选层次参考 [Android 搜索组件](https://developer.android.com/develop/ui/compose/components/search-bar) 与 [筛选组件](https://developer.android.com/develop/ui/compose/components/chip)。
