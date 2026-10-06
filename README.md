# ClipDownloader — 剪贴板下载器

安卓原生 App（Kotlin）。粘贴或分享抖音/快手/B站/小红书/微博/YouTube/TikTok/Instagram/Twitter-X/Facebook/Pinterest 等平台的链接，解析后选择画质批量下载视频/图片，并支持按平台分文件夹、博主主页批量下载、实况图（动图）保存。

## 核心功能

| 功能 | 说明 |
|------|------|
| 首页解析下载 | 粘贴链接或整段分享文案 → 解析 → 勾选媒体 + 选画质 → 下载 |
| 博主主页批量下载 | 粘贴 B站 / 抖音 / 快手 的主页地址或分享短链，批量下载全部作品（增量去重，已下载的自动跳过） |
| 分享到本应用 | 分享菜单选「下载到本机」，或选中文字后用 `PROCESS_TEXT`，即使不打开首页也能直接解析 |
| 复制后自动解析 | 复制链接后打开 App 自动读取剪贴板并解析（`打开时自动读剪贴板` 可关闭） |
| 实况图（动图）保存 | 平台侧的 live 图（抖音 / 小红书图集中的动图）保存为「`_live.mp4` 视频 + `.jpg` 原图」两个文件，在列表里标注为「动图」 |
| 一链多媒体 | 单条链接内的多图 / 多动图 / 多视频全部下载 |
| 按平台分文件夹 | 可开关；开=按平台（或博主名）分文件夹，关=全部直接存根目录 |
| 解析通道可选 | 国内/国外分别走本地解析器或云端通道；云端通道可增删、可指定每个通道支持的平台 |
| 下载页 | 实时进度 + 历史记录，支持重新下载、清除失败项 |
| 仅 WiFi 下载 | 可选，避免消耗流量 |
| 主题 | 跟随系统 / 浅色 / 深色 |
| 日志导出 | 一键导出或清除解析/下载日志，用于排查解析失败原因 |
| Material Design 3 | 原生 Android 风格，支持深色模式 |

## 项目结构

```
ClipDownloader/
├── build.gradle                    # 项目级（AGP 8.1.0 / Kotlin 1.9.0）
├── settings.gradle
├── gradle.properties
├── local.properties                # 本机 SDK 路径（不入库）
├── clipdownloader.jks              # 固定签名 keystore（debug/release 共用）
├── gradlew
├── gradle/wrapper/
├── docs/
│   ├── 需求文档.md                  # 功能规格与设置项总览
│   ├── 解析源接入指南.md             # 逆向并接入新云端解析源的完整流程
│   └── 项目记忆.md                  # 各平台解析要点、构建踩坑、已废弃结论
├── .github/workflows/
│   ├── ci.yml                      # main/PR 编译校验
│   └── build.yml                   # tag 触发的自动构建与 Release
└── app/
    ├── build.gradle                # 模块级（versionName / versionCode 在此）
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/clipdownloader/
        │   ├── App.kt                        # Application：通知渠道
        │   ├── MainActivity.kt               # 主界面：底部四 Tab + 权限引导 + 剪贴板读取
        │   ├── ShareActivity.kt              # 分享入口（ACTION_SEND / PROCESS_TEXT）
        │   ├── DownloadResultActivity.kt     # 下载完成通知点击跳转
        │   ├── DownloadsAdapter.kt           # 下载页列表（进度 / 历史）
        │   ├── MediaPickAdapter.kt           # 解析结果勾选列表
        │   ├── download/
        │   │   ├── DownloadManager.kt         # 下载调度中心（全局单例，含批量状态与并发控制）
        │   │   ├── PlatformParser.kt          # 链接识别 → Platform 枚举
        │   │   ├── ParsedMedia 相关模型       # 见 model/
        │   │   ├── DouyinParser.kt            # 抖音
        │   │   ├── DouyinUserParser.kt        # 抖音博主主页
        │   │   ├── KuaishouParser.kt          # 快手
        │   │   ├── KuaishouUserParser.kt      # 快手博主主页
        │   │   ├── BilibiliParser.kt          # B站
        │   │   ├── BilibiliUserParser.kt      # B站博主主页
        │   │   ├── XiaohongshuParser.kt       # 小红书
        │   │   ├── WeiboParser.kt             # 微博（PC / 移动端两套端点）
        │   │   ├── YouTubeInnerTubeParser.kt  # YouTube（InnerTube）
        │   │   ├── IiiLabParser.kt            # iiiLab 通道
        │   │   ├── UniversalParser.kt         # 通用兜底解析
        │   │   ├── CloudParser.kt             # 云端通道：多通道轮询 + 各站加密协议实现
        │   │   ├── PoTokenGenerator.kt        # YouTube PoToken 生成
        │   │   └── MotionPhotoUtils.kt        # Motion Photo 打包工具（当前未调用，见「技术要点」）
        │   ├── model/
        │   │   ├── ParsedMedia.kt             # 数据模型 + Platform 枚举
        │   │   └── DownloadRecord.kt          # 下载记录 / 批量状态模型
        │   └── util/
        │       ├── PreferencesManager.kt      # SharedPreferences 封装 + 云端通道默认定义
        │       ├── DownloadRecordStore.kt     # 下载历史（JSON 持久化，异步写 / 内存读）
        │       ├── LogFile.kt                 # 日志文件（单后台线程写入）
        │       └── ThumbLoader.kt             # 统一缩略图加载（本地优先 + 带 Referer）
        └── res/
            ├── layout/
            │   ├── activity_main.xml          # 骨架：Toolbar + 内容区 + 底部导航
            │   ├── view_home.xml              # 首页
            │   ├── view_downloads.xml         # 下载页
            │   ├── view_profile.xml           # 主页下载
            │   ├── view_settings.xml          # 设置页
            │   ├── item_download.xml          # 下载条目（单/多文件两种布局）
            │   ├── item_media_pick.xml        # 解析结果条目
            │   ├── item_setting.xml           # 设置行（标题 + 摘要 + 箭头），被设置页 include
            │   └── item_platform_channel.xml  # 平台首选通道行（代码动态生成）
            ├── menu/menu_bottom_nav.xml
            ├── drawable/                      # bg_badge / ic_music / ic_notification / ic_launcher_foreground
            ├── mipmap-anydpi-v26/             # 自适应图标
            ├── values/                        # colors / strings / themes / ids
            ├── values-night/colors.xml        # 深色模式
            └── xml/                           # file_paths / network_security_config
```

## 构建方法

### 方式一：Android Studio（推荐）

1. Android Studio → File → Open → 选择本项目根目录
2. 等待 Gradle Sync 完成（需本机已装 Android SDK 34）
3. 连接手机或模拟器 → Run

### 方式二：命令行

```bash
./gradlew assembleRelease    # APK 输出到 app/build/outputs/apk/release/
./gradlew assembleDebug      # 调试版 APK
```

APK 文件名固定为 `ClipDownloader_v{versionName}.apk`。若走命令行构建，需设置 `ANDROID_HOME` 环境变量，或在 `local.properties` 里写好 `sdk.dir`（注意用正斜杠）。

### 方式三：GitHub Actions

仓库有两个工作流：

**`.github/workflows/ci.yml` — 日常编译校验**
push 到 `main` 或发起 PR 时触发，跑一次 `assembleDebug` 确认能编译通过。目的是把编译错误拦在打 tag 之前。同一分支连续推送会取消上一次仍在跑的校验（`cancel-in-progress: true`）。

**`.github/workflows/build.yml` — 发版**
打 tag（形如 `v1.3.6`）触发：校验 tag 与 `app/build.gradle` 的 `versionName` 一致 → 构建 Release APK → 上传产物 → 备份版本分支 `v{版本号}` → 创建/更新 Release，更新说明自动取上一版本以来的提交摘要。同一 tag 的重复发版会**串行排队**而非并发，避免 force push 版本分支互相覆盖。

> 发布前记得同步递增 `app/build.gradle` 里的 `versionName` 和 `versionCode`，否则工作流会因 tag 不一致而失败。

## 使用说明

1. **安装后**打开 App，按引导依次授予通知权限（Android 13+）与媒体/存储权限
2. 触发下载有三种方式：
   - **首页粘贴**：复制链接后回到 App 点「剪贴板」填入，或直接粘贴；点「解析」查看作品与画质，勾选媒体后点「下载」
   - **复制后自动解析**：复制链接后打开 App 会自动读取剪贴板（可在设置关闭）
   - **直接分享**：在平台 App 里点「分享 → 下载到本机」
3. 带文案、带口令的整段分享文本都能识别，会自动从中提取链接
4. **博主主页链接**请到「主页下载」栏目解析，可批量下载 TA 的全部作品
5. 下载的文件默认保存在 `/storage/emulated/0/Download/ClipDownloader/`，开启「保存到平台独立文件夹」后按平台（或博主名）分子目录

### 实况图（动图）处理规则

解析器对平台侧带 live 视频的图集项打上「动图」标记后，下载时会**同时保存两个独立文件**，不做合并：

| 情况 | 处理 |
|------|------|
| 视频与原图都下载成功 | 保存 `xxx_live.mp4` + `xxx.jpg` |
| 封面原图下载失败 | 仅保存 `xxx_live.mp4` |
| live 视频下载失败 | 仅保存 `xxx.jpg` |
| 两者都失败 | 该条目记为失败 |

> 不使用「合成单文件 Motion Photo」方案，是为了避免部分机型/相册识别异常导致用户混淆。

## 解析通道说明

设置页把解析分成**国内平台解析**和**国外平台解析**两块，每个平台可单独指定首选通道：

- **本地解析**：App 内直接实现，仅下列平台提供 —— 抖音、快手、B站、小红书、微博、TikTok、YouTube
- **云端通道**：其余平台（Instagram / Twitter-X / Facebook / Pinterest）以及本地解析失败的兜底，走云端通道轮询

内置 8 个云端通道（协议均已在 App 内逆向实现）：MaxHelper、Hellotik、DownCats、SnapAny、Oivo、SnapTok、XiaZaiTool、Kukutool，另有 VidDown（异步任务制）。各通道支持平台不同，可在「云解析通道 → 管理」里新增、删除、勾选支持的平台。

| 平台 | 默认通道 | 备注 |
|------|----------|------|
| 抖音 / 快手 / B站 / 小红书 / 微博 | 本地解析 | 微博可切换 PC 端 / 移动端端点 |
| YouTube | 本地解析（InnerTube） | 含 PoToken 生成 |
| TikTok | 本地解析（tikwm） | — |
| Instagram / Twitter-X / Facebook / Pinterest | 云端通道 | 逐通道回落 |
| 其他（未知链接） | 云端通道 | 分「国内其他 / 国外其他」两个兜底范围 |

接入新解析源的完整逆向流程见 [docs/解析源接入指南.md](docs/解析源接入指南.md)。

## 技术要点

- **剪贴板读取**：Android 12+ 要求 App 拥有输入焦点才能 `getPrimaryClip()`，因此读取放在获焦回调中；已消费的剪贴板内容记入 `consumed_clips` 去重（上限 200），避免同一链接反复触发
- **下载调度**：`DownloadManager` 全局单例，内部维护 `DownloadBatchState` 列表（解析中/下载中/完成/失败/已取消 + 字节级进度），通过监听器推送给 UI
- **历史持久化**：`DownloadRecordStore` 以 JSON 文件保存下载历史，异步写盘、内存直读；带读取失败标志，避免瞬时故障后用空快照覆盖全部历史
- **动态照片打包工具**：`MotionPhotoUtils.pack()` 可把「封面 JPEG + 内嵌 mp4」合成单文件 Motion Photo（JPEG APP1 段写 `Camera:MicroVideo` / `MicroVideoOffset` XMP，尾部拼 mp4），采用流式读写与迭代求 offset、先写临时文件再 rename。**当前未被调用**：实况图改走双文件保存，此工具留作备用
- **缩略图**：`ThumbLoader` 本地文件优先，远程封面按平台带 Referer / 桌面 UA（微博 sinaimg 等图床无来源请求直接 403），失败后冷却重试
- **云端通道协议**：各站加密协议（AES-GCM 信封、HMAC 签名、SHA-256 签名、异步任务轮询等）以 Android 原生加密实现，零额外依赖
- **Material 3 (Material You)** 设计语言，支持深色模式
- **Android 10+ Scoped Storage** 兼容，使用 `Download/` 公共目录

## 权限说明

| 权限 | 用途 |
|------|------|
| INTERNET | 下载视频/图片、访问解析接口 |
| ACCESS_NETWORK_STATE | 判断网络类型（「仅 WiFi 下载」） |
| POST_NOTIFICATIONS | 下载进度与完成通知（Android 13+） |
| READ_MEDIA_IMAGES / READ_MEDIA_VIDEO | 媒体扫描（Android 13+） |
| READ_EXTERNAL_STORAGE | 同上，`maxSdkVersion=32` 兼容旧版本 |
| WRITE_EXTERNAL_STORAGE | 写入公共下载目录，`maxSdkVersion=29` |

Manifest 中另用 `<queries>` 声明了各厂商相册/文件管理器包名，用于「优先相册打开」与「点击跳转文件夹」。

## 注意事项

1. 云端通道均为第三方公共服务，实例地址或接口协议可能随时变更，建议在设置中自建或替换为可用通道
2. 抖音/快手等平台 API 可能随时变化，本地解析逻辑需要相应更新
3. `clipdownloader.jks` 与其中的口令为固定签名，用于保证各版本可覆盖安装；请勿替换，否则老版本无法升级
4. 下载大文件时建议连接 WiFi，或在设置中开启「仅 WiFi 下载」
