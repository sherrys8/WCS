# cache/WcS 使用清单

基线 `5db8c88`。`cache/WcS` 的真实路径曾为
`/storage/emulated/0/Android/data/com.tencent.mm/cache/WcS`
（`KnownPaths.moduleCache`，`BuildConfig.TAG = "WcS"`）。

> 后续变更：`moduleCache` 已改为 `KnownPaths.moduleData / "Wcs_Cache"`，即
> `/storage/emulated/0/Android/data/com.tencent.mm/WcS/Wcs_Cache`，不再落在微信的
> 外部缓存目录里。下表路径中的 `cache/WcS/` 一律理解为新根目录；未做数据迁移，
> 旧目录内容需手动清理。

模块跑在微信进程里，所以写的是微信目录下的子目录 —— 不是 WCS 自己的目录，卸载微信会一起没。

## 一、按目录

| 路径 | 用途 | 产生者 | 清理策略 |
|---|---|---|---|
| `ai-recognition/<账号>/` | AI 识别结果缓存（图片/表情/视频识别、语音转写） | `AiRecognitionCache` | 保留今天与前 3 天，写入时按天清一次 |
| `ai-image-recognition/` | 识别时的临时图 `media-*.tmp`、视频 `video-*.mp4` | `AiImageRecognition` | `finally` 立即删 |
| `ai_stt_*.pcm/.mp3/.silk` | 语音转写中间产物 | `VoiceTranscriber` | `finally` 立即删 |
| `panels/` | 面板临时：贴纸转图 `sticker-*`、语音发送 `voice-*/send-*`、克隆音色 `*.pcm/*.mp3`、`edge-*`/`system-tts-*` 合成 | `StickerPanel`、`VoicePanel`、`VoiceSendUtils`、`CloneVoiceRepository`、`TextToSpeech` | `cleanupStalePanelCache()`：普通 1 天，`edge-*`/`system-tts-*` 3 天 |
| `wcs_tts/` | AI 回复与文字转语音的合成结果 | `TextToSpeech`（`TtsCache.generatedDir`） | 3 天，取目录时顺带清 |
| `breeze_previews/` | Breeze 音色试听 mp3 | `BreezeVoicePicker`（`TtsCache.previewDir`） | 3 天 |
| `doubao_voices.json` | 豆包音色列表 | `DoubaoVoicePicker` | 不清，只靠 mtime 判断新鲜度 |
| `doubao_avatars/` | 豆包音色头像 | Coil `DiskCache` | Coil 按可用空间比例 LRU |
| `notification-media/` | 通知栏的大图/缩略图/表情 | `NotificationsEvolved` | 7 天 |
| `view-sticker-as-image/{decoded,snapshots}` | 「表情存为图片」的解码结果与快照 | `ViewStickerAsImage` | 每种扩展名只留最近 10 个 |
| `python/<pluginId>/` | Python 插件运行缓存 | `PythonPluginManager` | 删除插件时连带清理 |
| `monet/` | 莫奈取色模块生成的工作目录 | `MonetEngineModuleGenerator` | 未实现清理，交给生成器 |
| `sticker-send-*` | 贴纸转普通图片后发送 | `WeMessageApi` | 24 小时 |
| `wcs_moments_probe_*` `wcs_moments_thumb*` `wcs_moments_temp_*` | 朋友圈视频探测与缩略图 | `WeMomentsApi` | 有 `deleteTempFile`，正常删 |
| `wcs_moments_img_*` `wcs_moments_video_*` | 交给微信相册编辑器的媒体文件 | `WeMomentsApi` | **不删**，编辑器后续还要读 |
| `group_report_*.png` | 群聊分析长图 | `GroupReportImage` | **不删**（泄漏点） |
| `auto_save_moments_*.bin` | 朋友圈自动保存的中间文件 | `AutoSaveMoments` | **不删**（泄漏点） |
| `upload_*` | MCP / HTTP 接口上传的临时文件 | `ApiServer` | **不删**（泄漏点） |
| `auto-reply/<账号>/` | 自动回复日志（设计稿规划，未实现） | — | 4 天 |

另：`JavaEngine` 把 `cacheDir` 作为变量暴露给用户脚本，脚本可以往这里写任意文件，无回收机制。

## 二、三个确定会堆积的点

1. **`upload_*`** —— `ApiServer.saveUploadedFile` 在 8 处被调用（发朋友圈、上传媒体等），全文件没有一处 `delete`。每次经 HTTP/MCP 上传都会永久留一份，大小等于原始文件。
2. **`group_report_*.png`** —— 「发送长图」每点一次生成一张，发送后不删；长图动辄几 MB。
3. **`auto_save_moments_*.bin`** —— 需要中转时拷一份，转存到 SAF 后不删。

三处都是「用完即弃」的文件却没有回收。建议统一走一个 `CacheJanitor`：启动时按前缀 + 年龄清一次（`upload_*` 1 天、`group_report_*` 1 天、`auto_save_moments_*` 1 天），并给 `saveUploadedFile` 的调用点补 `finally { delete() }`。前者兜底，后者治本。

## 三、另外两处「有用途但无回收」

- `wcs_moments_img_*` / `wcs_moments_video_*`：交出去给相册编辑器，编辑器何时用完无法感知，只能按年龄清（建议 1 天）。
- `monet/`：生成器内部使用，是否清理取决于生成器实现；模块生成完成后可以整目录删，但需先确认识别流程没在同时用。

## 四、结论

- 真正需要长期保留的只有 `ai-recognition/`（3 天）、`wcs_tts/`、`breeze_previews/`、`doubao_voices.json`。
- 其余都是临时文件，理论上都可以「用完即删 + 启动兜底清」。
- 现在没有全局清理入口，各功能各自为政，三个泄漏点谁都不管。
