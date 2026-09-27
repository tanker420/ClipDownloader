# ClipDownloader — 剪贴板自动下载器

安卓原生 App，后台监听剪贴板，自动识别抖音/快手/B站/小红书/微博/YouTube/TikTok/Instagram/Twitter/Pinterest 等国内外主流平台链接，自动下载视频/图片到指定文件夹，按平台分类。

## 核心功能

| 功能 | 说明 |
|------|------|
| 后台剪贴板监听 | 前台服务常驻，复制即触发下载 |
| 下拉控制中心开关 | Quick Settings Tile + 通知栏开关 |
| 全平台支持 | 国内：抖音/快手/B站/小红书/微博；海外：YouTube/TikTok/Instagram/Twitter-X/Facebook/Pinterest |
| 动态照片智能处理 | 图片优先原样存 JPEG 动图，存不了才提取内嵌视频；视频优先转 JPEG 动图，太长/不适合/失败才存 MP4 |
| 一链多媒体 | 单条链接内的多图 / 多动图 / 多视频全部下载 |
| 分享到本应用 | App 内「分享 → 剪贴板下载器」直接触发下载 |
| 按平台分文件夹 | 可独立开关各平台 |
| 最高画质 | 默认最高画质，可切换 1080p/720p/480p |
| 分享口令识别 | 支持带文字/口令的完整分享文本 |
| 开机自启 | 可选 |
| Material Design 3 | 原生 Android 风格 |

## 项目结构

```
android_app/
├── build.gradle                    # 项目级
├── settings.gradle
├── gradle.properties
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── app/
│   ├── build.gradle                # 模块级
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/clipdownloader/
│       │   ├── App.kt                      # Application, 通知渠道
│       │   ├── MainActivity.kt             # 设置界面
│       │   ├── ClipboardService.kt          # 前台服务 + 剪贴板监听
│       │   ├── ClipboardTileService.kt      # Quick Settings Tile
│       │   ├── BootReceiver.kt             # 开机自启
│       │   ├── DownloadResultActivity.kt   # 通知点击跳转
│       │   ├── ShareActivity.kt            # 分享入口（ACTION_SEND / PROCESS_TEXT）
│       │   ├── download/
│       │   │   ├── PlatformParser.kt       # 全平台 URL 识别
│       │   │   ├── DouyinParser.kt         # 抖音解析
│       │   │   ├── KuaishouParser.kt       # 快手解析
│       │   │   ├── BilibiliParser.kt       # B站解析
│       │   │   ├── XiaohongshuParser.kt    # 小红书解析
│       │   │   ├── WeiboParser.kt          # 微博解析
│       │   │   ├── UniversalParser.kt      # 通用解析器 (Cobalt API)
│       │   │   ├── MotionPhotoProcessor.kt # 动态照片双向处理（存/提取/视频转动图）
│       │   │   └── DownloadManager.kt     # 下载管理器
│       │   ├── model/
│       │   │   ├── ParsedMedia.kt          # 数据模型 + Platform 枚举
│       │   │   └── AppSettings.kt          # 设置模型 + Quality 枚举
│       │   └── util/
│       │       └── PreferencesManager.kt  # SharedPreferences 封装
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml
│           │   ├── item_setting.xml
│           │   └── item_platform.xml
│           ├── values/
│           │   ├── strings.xml
│           │   ├── colors.xml
│           │   └── themes.xml
│           ├── values-night/colors.xml      # 深色模式
│           ├── xml/
│           │   └── network_security_config.xml
│           ├── drawable/
│           │   ├── ic_notification.xml
│           │   ├── ic_tile.xml
│           │   ├── ic_pause.xml
│           │   └── ic_launcher_foreground.xml
│           └── mipmap-anydpi-v26/
│               ├── ic_launcher.xml
│               └── ic_launcher_round.xml
```

## 构建方法

### 方式一：Android Studio（推荐）

1. 把整个 `android_app` 文件夹拷到你的电脑上
2. Android Studio → File → Open → 选择 `android_app` 目录
3. 等待 Gradle Sync 完成
4. 连接手机或模拟器 → Run

### 方式二：命令行

```bash
cd android_app
./gradlew assembleRelease    # 生成 APK 在 app/build/outputs/apk/release/
./gradlew assembleDebug      # 调试版 APK
```

> 注意：如果用命令行构建，需要本机安装 Android SDK 并设置 `ANDROID_HOME` 环境变量。

## 使用说明

1. **安装后**打开 App，授予通知权限（Android 13+）
2. 在设置页确认监听已开启
3. 触发下载有两种方式：
   - **复制链接**：在抖音/快手/B站等 App 中点「分享 → 复制链接」，本 App 自动识别并下载
   - **直接分享**：在这些 App 中点「分享 → 剪贴板下载器」，即使监听关闭也会立即下载
4. 带文案、带口令的整段分享文本都能识别，会自动从中提取链接
5. 下拉通知栏可看到「剪贴板监听」Tile 开关，随时暂停/开启
6. 下载的文件保存在 `/Download/ClipDownloader/{平台名}/` 目录

### 动态照片处理规则

| 来源 | 处理顺序 |
|------|----------|
| 图片 | ① 能作为 JPEG 动图直接保存 → 存 `.jpg`　② 不能 → 提取内嵌视频存 `_motion.mp4`　③ 普通静态图 → 按原格式保存 |
| 视频 | ① 时长 ≤ 上限（默认 10s）且体积合适 → 首帧 + 内嵌原视频转成 `.jpg` 动图　② 超时长/超体积/转换失败 → 直接保存 `.mp4` |

时长上限可在设置页「视频转动图时长上限」中调整（5/10/15/30 秒），整套逻辑受「动态照片智能处理」总开关控制。

## 平台解析说明

| 平台 | 解析方式 | 备注 |
|------|----------|------|
| 抖音 | 短链重定向 + Web API | 无水印 |
| 快手 | 短链重定向 + 页面解析 | — |
| B站 | 短链/直链 + API | 最高清晰度 |
| 小红书 | 页面解析 | 图文+视频+动图 |
| 微博 | 页面解析 | 视频+图片 |
| YouTube | iiiLab 通道 | 最高画质；失败回落云端通道 |
| TikTok | 本地通道（tikwm）/ iiiLab / 云端 | 「TikTok 本地解析」开关控制首选；其余逐级回落 |
| Instagram | iiiLab 通道 | Reels/图文；失败回落云端通道 |
| Twitter/X | iiiLab 通道 | 视频+图片；失败回落云端通道 |
| Facebook | iiiLab 通道 | 失败回落云端通道 |
| Pinterest | iiiLab 通道 | 失败回落云端通道 |
| 其他 | iiiLab 通道兜底 | 失败回落云端通道 |

## 技术要点

- **前台服务** + `ClipboardManager.OnPrimaryClipChangedListener` 实现后台监听
- **Quick Settings TileService** 实现下拉控制中心开关
- **OkHttp** 负责所有网络请求，支持重定向跟踪
- **动态照片双向处理**：
  - 检测：XMP 标记（GCamera:MotionPhoto / Samsung / Microsoft）+ JPEG FFD9 之后的 MP4 `ftyp` box 扫描
  - 生成：`MediaMetadataRetriever` 取首帧压 JPEG → 插入 Google Motion Photo XMP APP1 段 → 尾部追加原 MP4
- **Material 3 (Material You)** 设计语言，支持深色模式
- **Android 10+ Scoped Storage** 兼容，使用 `Download/` 公共目录

## 权限说明

| 权限 | 用途 |
|------|------|
| INTERNET | 下载视频/图片 |
| FOREGROUND_SERVICE | 后台监听不被杀 |
| POST_NOTIFICATIONS | 显示监听状态通知 (Android 13+) |
| READ_MEDIA_IMAGES/VIDEO | 媒体扫描 |
| RECEIVE_BOOT_COMPLETED | 开机自启 |

## 注意事项

1. Cobalt API 是开源项目，实例地址可能变更，建议自建或替换为其他实例
2. 抖音/快手等平台 API 可能随时变化，解析逻辑可能需要更新
3. Android 14+ 对前台服务类型有严格要求，已在 Manifest 中声明 `specialUse`
4. 下载大文件时建议连接 WiFi
