# 运行时目录清单

WcS 注入微信进程后，读写的不止自己的目录。这份文档列出模块当前用到的全部设备目录、
每个子目录的归属功能，以及哪些可以直接删。

路径里的两个变量：

- `com.tencent.mm` —— 宿主包名，取自 `HostInfo.packageName`（`KnownPaths` 里取不到时回退
  `PackageNames.WECHAT`）。
- `WcS` —— `BuildConfig.TAG`，也是模块目录名和 logcat tag。

定义集中在 `app/src/main/java/dev/sherry/wcs/utils/fs/KnownPaths.kt`，只有它下面这几个属性。
**新增目录请复用这些属性，不要自己拼 `Environment.getExternalStorageDirectory()`。**

## 根目录

| 属性 | 实际路径 | 可见性 | 定义 |
|------|----------|--------|------|
| `KnownPaths.moduleData` | `/storage/emulated/0/Android/data/com.tencent.mm/WcS/` | 文件管理器可见 | `KnownPaths.kt:16` |
| `KnownPaths.moduleCache` | `/storage/emulated/0/Android/data/com.tencent.mm/cache/WcS/` | 文件管理器可见 | `KnownPaths.kt:26` |
| `KnownPaths.moduleAssets` | `/storage/emulated/0/Android/data/com.tencent.mm/WcS/assets/` | 文件管理器可见 | `KnownPaths.kt:32` |
| `KnownPaths.downloads` | `/storage/emulated/0/Download/WcS/` | 公共下载目录 | `KnownPaths.kt:40` |
| `KnownPaths.codeCacheDir` | `/data/data/com.tencent.mm/cache/` | 需要 root | `KnownPaths.kt:22` |
| 微信内部 files | `/data/data/com.tencent.mm/files/` | 需要 root | 直接用 `application.filesDir` |
| 微信内部 no_backup | `/data/data/com.tencent.mm/no_backup/` | 需要 root | 直接用 `application.noBackupFilesDir` |
| 宿主数据 | `/data/data/com.tencent.mm/MicroMsg/<md5>/` | 需要 root | `RuntimeConfig.kt:27` |

## moduleData —— 跨启动保留的数据

模块自己的数据目录，卸载微信才清。放配置、日志、解析缓存和用户导入的脚本/主题。

| 子项 | 用途 | 出处 |
|------|------|------|
| `logs/` | 日期文件日志 `wcs-<日期>.log` | `WeLogger.kt:68` |
| `crashes/` | 崩溃日志导出 | `CrashLogsManager.kt:222` |
| `dex_cache/` | DexKit 解析缓存 `*.json`，方法体 MD5 命中即跳过重扫 | `DexCacheManager.kt:35,40` |
| `scripts_java/` | BeanShell 脚本插件 | `JavaScriptingHook.kt:70` |
| `scripts_python/`、`python/data/` | Python 插件脚本与其数据目录 | `PythonPluginManager.kt:41,42` |
| `sticker_panel/`、`voice_panel/` | 面板素材 | `PanelPaths.kt:16,17` |
| `themes/` | 主题包 | `Themes.kt:132` |
| `agent/weagent.db`、`agent/skills/<名>/SKILL.md` | WeAgent 数据库副本与技能定义 | `WeAgentDatabase.kt:158`、`SkillStore.kt:45` |
| `extensions/script-deps/`（含 `.staging/`） | 脚本依赖扩展包解压位置 | `ScriptDepsPack.kt:29,32` |
| `safe_mode.flag` | 安全模式标记 | `SafeMode.kt:22` |
| `*.json` | 各功能配置：`auto_accept_transfer_settings`、`auto_save_moments_settings`、`chat_folders`、`conversation_groups`、`custom_avatars_map`、`feature_flag_overrides`、`moments_custom_bottom_details`、`real_names`、`real_names_first_char`（及 `_progress`）、`red_packet_settings`、`red_packet_group_members` | 各功能文件内 `KnownPaths.moduleData / "...json"` |

## moduleCache —— 可再生的缓存

系统存储紧张时会回收，因此**任何删了会丢用户东西的东西都不能放这里**。

| 子项 | 用途 | 出处 |
|------|------|------|
| `wcs_tts/` | 文字转语音产物：魔方 `tts_*.wav`、豆包 `doubao_*.aac` | `TextToSpeech.kt:698,800` |
| `doubao_avatars/` | 豆包音色选择器头像的 Coil 磁盘缓存 | `DoubaoVoicePicker.kt:89` |
| `doubao_voices.json` | 豆包在线音色库缓存（373 条，含头像/试听签名地址） | `DoubaoVoicePicker.kt:134` |
| `monet/` | Monet 取色模块生成中间产物 | `MonetEngineModuleGenerator.kt:157` |
| `notification-media/` | 通知里要显示的媒体副本 | `NotificationsEvolved.kt:215` |
| `panels/` | 面板临时素材 | `PanelPaths.kt:19` |
| `python/` | Python 运行时解压缓存 | `PythonPluginManager.kt:43` |
| `view-sticker-as-image/{decoded,snapshots}` | 贴纸转图片的解码结果与快照 | `ViewStickerAsImage.kt:145,185` |
| 一次性临时件 | `sticker-send-image-*`、`sticker-send-converted-*`、`upload_*`、`group_report_*.png`、`auto_save_moments_*.bin`、`wcs_moments_{temp,thumb,video,probe,probe_live}*.{jpg,mp4}` | `WeMessageApi.kt:1182,1189`、`ApiServer.kt:303`、`GroupReportImage.kt:61`、`AutoSaveMoments.kt:409`、`WeMomentsApi.kt:847-2222` |

## moduleAssets —— 用户导入的素材

删了就没了，属于「用户资产」而不是缓存。

| 子项 | 用途 | 出处 |
|------|------|------|
| `custom_friend_avatars/` | 自定义好友头像图片 | `CustomAvatarAssets.kt:40` |
| `home_page_cards/images/` | 首页图片卡的背景/图片 | `HpcImageAssets.kt:23` |
| `home_side_panel/images/` | 侧栏图片卡的图片 | `HomeSidePanelImageAssets.kt:38` |
| `global_background.png` | 全局背景 | `ApplyGlobalBackground.kt:92,96` |
| `left_bubble.9.png`、`right_bubble.9.png`、`red_packet_{left,right}_bubble.9.png`、`transfer_{left,right}_bubble.9.png` | 自定义消息气泡（含红包、转账） | `CustomMessageBubbles.kt:453,631` |
| `virtual_voip_video.mp4` | 虚拟视频通话画面 | `VirtualVoipVideo.kt:66` |

> `KnownPaths.userAssets`（`assets/user/`，`KnownPaths.kt:36`）目前**全仓零引用**，是保留的
> 死常量；要用之前先确认它和 `moduleAssets` 的边界。

## downloads —— 交付给用户的成品

公共下载目录，用户会自己去翻，所以只放「导出」结果。

| 子项 | 用途 | 出处 |
|------|------|------|
| `Moments/` | 朋友圈自动保存的媒体 | `AutoSaveMomentsSettings.kt:83` |
| `MusicOrder/` | 点歌功能下载的音频 | `QQMusicOrder.kt:89` |
| `ParseVideo/`、`ParseVideoTemp/` | 短视频解析结果与临时件 | `ParseVideo.kt:159,686` |
| `monet_engine_module.zip` | 取色模块成品 | `MonetEngineModuleGenerator.kt:156` |
| 另存的文件 / 语音转码 / 贴纸导出 / 短视频下载 | 文件消息另存、silk 转 mp3·pcm、贴纸导出、`DownloadMedia` | `WeMessageApi.kt:2540-2579,2716`、`DownloadMedia.kt:157` |

## 微信内部目录（需要 root 才能查看）

模块跑在微信进程里，`filesDir`/`cacheDir` 都是微信的，所以这些目录和微信自己的数据混在一起，
名字前缀 `wcs`/`WcS` 是唯一的区分手段。

| 路径 | 用途 | 出处 |
|------|------|------|
| `files/mmkv/wcs_prefs`（及 `.crc`） | 全部偏好设置（`prefOption` 委托） | `WePrefs.kt:56`、`MmkvPrefsImpl.kt:14`、`NativeLoader.kt:44` |
| `files/wcs-agent/weagent.db` | WeAgent Room 数据库私有副本 | `WeAgentDatabase.kt:159` |
| `files/wcs-agent/environment/{instances,native}` | Linux 环境实例与原生运行时 | `ChrootBackend.kt:175`、`ArchLinuxPack.kt:70`、`LinuxEnvironmentManager.kt:516` |
| `files/wcs-extensions/<包 id>` | 扩展包解压位置（archlinux / cloudflared / llama-native / python-runtime / 模型包） | `extensions/ArchLinuxPack.kt:32`、`CloudflaredPack.kt:38`、`LlamaNativePack.kt:35`、`PythonRuntimePack.kt:40`、`QwenModelPack.kt:47` |
| `files/wcs-python/` | Python 崩溃防护标记 | `PythonCrashGuard.kt:28` |
| `files/wcs_skip_rewarded_js/` | 跳过激励视频时注入的 JS | `SkipRewardedAds.kt:187` |
| `files/wcs/` | 已读回执隧道凭据；环境隐藏白名单 | `ReadReceiptsTunnelRuntime.kt:60`、`EnvironmentHider.kt:45` |
| `cache/generated_proxy_classes/` | 动态代理类的 dex 缓存 | `ProxyBuilderUtils.kt:19`（`KnownPaths.codeCacheDir`） |
| `cache/coil3_disk_cache/` | **Coil 3 的默认磁盘缓存**，所有没显式传 `imageLoader` 的联网图片都落这里 | Coil 库默认值，见下文 |
| `cache/plugin-import-<时间戳>.zip` | Python 插件导入的临时包 | `PythonScriptsSettingsActivity.kt:202` |
| `no_backup/<天气素材目录>` | 侧栏天气资源 | `HomeSidePanelWeather.kt:542` |
| `MicroMsg/<md5("mm<uin>")>/` | 读宿主数据：收藏语音 `favorite/<bucket>/` 等 | `RuntimeConfig.kt:27`、`ForwardFavoriteVoices.kt:166`、`WeMessageApi.kt:2412` |

## Coil 图片缓存为什么分两处

Coil 3 的默认磁盘缓存目录是 `java.io.tmpdir / "coil3_disk_cache"`
（`coil-core/src/nonJsCommonMain/kotlin/coil3/disk/utils.kt:14`，`ImageLoader.Builder.build()`
在没显式配置 `diskCache` 时回落到这个单例，`ImageLoader.kt:357`）。Android 把每个应用进程的
`java.io.tmpdir` 设成其内部 cache 目录，所以它落在 `/data/data/com.tencent.mm/cache/` 下，
**文件管理器看不到，要 root**。（这一跳是从库源码 + Android 的 `TMPDIR` 行为推出来的，
没在设备上 `ls` 实测过；要确认用 `ls -d /data/user/0/com.tencent.mm/cache/coil3_disk_cache`。）

只有网络请求会写磁盘缓存（`coil-network-core/.../NetworkFetcher.kt` 是唯一读写它的地方），
本地 `File`/资源不经过磁盘缓存 —— 所以侧栏图片卡、自定义头像这些读本地文件的，
和 `coil3_disk_cache` 无关。

当前分配：

- `GlobalImageLoader`（`ui/content/CoilUtils.kt:40`）**没有**自定义磁盘缓存，与默认单例共用
  `coil3_disk_cache`。显式用它的调用点：`ContactSelectors.kt:859`（联系人选择器的网络头像）、
  `Markdown.kt:263`（AI 回复内嵌图片，调用方是 WeAgent 面板 `WeAgentPanel.kt:915` 与群聊统计报告
  `GroupChatSummary.kt:424`；`GroupReportImage.kt` 那套自绘 Markdown 不走 Coil）、
  `StickerPanelSheet.kt:2619`（贴纸面板）、`HomeSidePanelImageCard.kt:169`（侧栏图片卡，
  读的是本地文件，因此实际不落磁盘缓存）。
- `DoubaoAvatarImageLoader`（`features/items/chat/DoubaoVoicePicker.kt:84`）是豆包头像专用，
  磁盘缓存指向外部 `cache/WcS/doubao_avatars/`（`DoubaoVoicePicker.kt:89`），方便查看和一键清理。
  刻意不复用也不改 `GlobalImageLoader`，避免把无关功能的图片缓存一起搬走。

## 删除安全性

| 目标 | 能不能删 | 后果 |
|------|----------|------|
| `cache/coil3_disk_cache/` | 能 | 联网图片重新下载一次。建议先 `am force-stop com.tencent.mm`，DiskLruCache 持有 journal 句柄，进程活着删会有一次无害的 IO 报错 |
| `cache/WcS/doubao_avatars/` | 能 | 头像重新下载 |
| `cache/WcS/doubao_voices.json` | 能 | 音色列表退回「只有内置」，需点一次 ⟳ 重拉 |
| `cache/WcS/wcs_tts/` | 能 | 丢掉刚生成、还没发送的那条语音（「发送语音」会提示请先生成） |
| `cache/WcS/` 其余临时件 | 能 | 无 |
| `WcS/dex_cache/` | 能 | 下次启动重新跑 DexKit 解析，耗时且更费电，不丢数据 |
| `WcS/` 下的 `*.json` | **不要** | 各功能的配置与用户整理结果（聚合会话、群成员真名、红包设置等），删了不可恢复 |
| `WcS/assets/` | **不要** | 用户导入的头像、背景、气泡、脚本、主题 |
| `files/mmkv/wcs_prefs` | **不要** | 全部偏好设置 |
| `Download/WcS/` | 随用户 | 交付物目录，模块不依赖它 |

## 新增目录时的约定

- 可再生、丢了无所谓 → `KnownPaths.moduleCache`；跨启动要留 → `KnownPaths.moduleData`；
  用户导进来的素材 → `KnownPaths.moduleAssets`；给用户拿走的东西 → `KnownPaths.downloads`。
- 建目录用 `.createDirsSafe()`（`KnownPaths` 各属性自带）或 `File(...).apply { mkdirs() }`。
- **禁止 `Path.of` / `Files.writeString`**（低版本 Android 没有），字符串转路径用
  `dev.sherry.wcs.utils.fs.asPath`，写文本用 `kotlin.io.path.writeText`。
- 路径拼接要 `import kotlin.io.path.div`，漏了是编译期错误但只有 CI 会报（本仓库已踩过两次）。
