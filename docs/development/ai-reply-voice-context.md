# AI 回复：上下文里的语音也转写并进入分析

> 状态：**已实现（未提交）**。基线 `4a9d2e92`。
> 目标文件：`features/items/chat/AiReplyCandidates.kt`、`features/items/chat/VoiceTranscriber.kt`、
> `features/api/core/WeDatabaseApi.kt`、`features/api/core/models/WeContact.kt`。

## 1. 现状

AI 回复候选的生成链：长按消息 → `showCandidateDialog` → 点「生成」→ `requestCandidates`
（IO 协程）→ `loadContext(talker)` 取最近 N 条历史 → 拼进 system prompt。

- `loadContext`（`AiReplyCandidates.kt:1299`）用
  `WeDatabaseApi.getMessagesInRangeDesc(talker, now-7d, now, limit)`，返回元素是
  `WeMessage`（`msgId/msgSvrId/talker/content/typeCode/createTime/isSend`，**没有 imgPath**）。
- 过滤规则现在是 `typeCode == 1 || type == VOICE`：语音保留，但只渲染成
  `[语音 N 秒]` 占位（`voicePlaceholder`，:1325），**从不转写**。注释写明理由：逐条转写=按条计费。
- 只有**被长按的那条**语音会经 `VoiceTranscriber.transcribe(msgInfo)` 真转写，成为 `source`
  （「对方说」），路径是 `imagePath → resolveVoicePath → 解码 → POST SiliconFlow`。
- `VoiceTranscriber.transcribe` 需要 `MessageInfo` 实例，因为 `WeMessageApi.resolveVoicePath`
  先走「按消息的新版接口」(`getVoiceFullPathByMessage(nativeMsgInfo, encPath)`)，失败再回落
  「按名称」(`getVoiceFullPath(encPath)`)。上下文里只有 `WeMessage`，没有 native `MsgInfo`。

## 2. 目标

把 `loadContext` 里出现的语音消息也转写成文字，用转写文本替代 `[语音 N 秒]` 占位，一起送进
「最近聊天记录参考」给模型分析。失败/取不到文件时退回占位，不影响整体生成。

## 3. 可行性结论

**能。**

三段能力都已存在：取语音加密路径（`message.imgPath` 列已随 `SELECT *` 拿到，只是没映射进
`WeMessage`）、转写（`VoiceTranscriber` 的解码 + POST 链路现成）、拼上下文（`loadContext`
就是字符串拼接）。缺的只是：① `WeMessage` 补 `imgPath` 字段；② 一个「只按名称/按加密路径」
转写的入口；③ 缓存与上限控制。

## 4. 关键约束与决策（需要锁定）

| # | 决策点 | 建议 | 理由 |
|---|---|---|---|
| D1 | 转写是否做成开关 | **不做开关，直接常开** | 用户已定：只要触发 AI 回复，上下文里的语音条就转写 |
| D2 | 缓存转写结果 | 文件缓存 `cache/WcS/ai_stt_cache.json`（`KnownPaths.moduleCache`），`msgSvrId → 文本` 的 JSON，LRU 上限 ~200 条 | 同一条语音不该每次点「生成」都重新计费；进程重启也不该再花一次钱；放文件里用户可随手删 |
| D3 | 单次转写条数 | **不设上限**（上下文里出现几条语音就转几条；总条数自然被 `contextLimit`≤20 框住） | 用户已定：条数不用限制 |
| D4 | 串行 vs 并行 | 先串行 | 避免瞬间并发触发 SiliconFlow 429；后续要提速再 `async` 并发 |
| D5 | 转写结果在上下文里的形态 | `对方：[语音] <转写文本>`（正文截 MAX_CONTEXT_LINE_CHARS） | 保留「这是语音」的标记，避免和纯文本混淆 |
| D6 | 取不到文件/转写失败 | 回退 `[语音 N 秒]` 占位 | 与现状一致，单条失败不拖垮生成 |
| D7 | 上下文语音无 native MsgInfo | 新增**只按名称/加密路径**的重载 | 见 §5 步骤 2 |

## 5. 实现步骤

### 步骤 1：`WeMessage` 补 `imgPath`（`features/api/core/models/WeContact.kt` 内的 WeMessage）

- 加字段 `val imgPath: String? = null`（带默认值，其余 3 个构造点不用改）。
- 在 `WeDatabaseApi.getMessagesInRangeDesc`（:787 的 `.map { row -> WeMessage(...)`）里
  补 `imgPath = row["imgPath"]?.toString()?.takeIf { it.isNotBlank() }`（`row.str` 对缺失返回
  `""`，这里要 null，所以直接读 map。`SELECT *` 已含该列，不用改 SQL）。

### 步骤 2：`VoiceTranscriber` 加一个按路径转写的入口

- 新增 `suspend fun transcribeByEncPath(encPath: String): String`：
  复用现有的 `decodeToPcm → pcmToMp3 → post`，但路径解析用
  `WeMessageApi.getVoiceFullPath(encPath)` + 落地校验（文件存在且 >0），失败抛异常或返回 null
  （由调用方决定）。
- 顺带把现有 `transcribe(msgInfo)` 与 `transcribeByEncPath` 共用的「解码+POST」抽成一个
  private 函数，避免两份拷贝。

### 步骤 3：`VoiceTranscriber` 加文件缓存

- 缓存文件 `KnownPaths.moduleCache.resolve("ai_stt_cache.json")`，存 `msgSvrId → text` 的 JSON
  （`DefaultJson`，与本仓序列化一致）。目录用 `moduleCache`，用户文件管理器可见、可手动删。
- `get(msgSvrId)` / `put(msgSvrId, text)`：读时解析成 `LinkedHashMap`；写时插入并在
  `size > 200` 时移除最旧，序列化回写。读失败（文件缺失/坏 JSON）当作空缓存，不抛。
- AI 语音转文字（`AiSpeechToText.kt`）的 `transcribe` 也接到这层缓存？——**接**，同一条语音
  两个入口只计一次费。（可选，单独一步）

### 步骤 4：`loadContext` 改为 suspend 并在语音行转写

- 签名 `private fun loadContext(talker: String): String` → `private suspend fun loadContext(...)`；
  调用点 `requestCandidates` 里已是 suspend + IO 上下文，直接改即可。
- 过滤不变（text or VOICE），但遍历时对 VOICE 行：
  1. `imgPath == null` → 占位；
  2. 查缓存，命中 → 用缓存文本；
  3. 未命中 → `runCatching { transcribeByEncPath(msgSvrId, imgPath) }`：成功且非空 → 存缓存并渲染
     `[语音] <text>`；null/异常 → 占位（`WeLogger.w` 记一条）。
- 渲染行仍是 `"$who：$body"`，body 长度仍裁 `MAX_CONTEXT_LINE_CHARS`。

### 步骤 5：（无开关，跳过）

### 步骤 6：静态校验

- 无新增字符串（`[语音] ` 沿用 `voicePlaceholder` 的硬编码中文，与 system prompt 一致）。
- 静态校验：括号配平、无超 116 列行、`WeMessage` 新字段默认值不破坏其余构造点。本机无 SDK，
  **不编译**；推上去 CI 验。

## 6. 影响面 / 风险

- **计费与延迟**：首次生成时上下文里的每条语音（≤`contextLimit`，现最多 20）都要串行转写，
  体验会明显变慢；靠 D2 缓存摊薄，第二次起只花 LLM 的时间。
- **文件缺失**：语音原文件没在本地（未下载、被清），按名称解析失败 → 占位，属优雅降级。
- **不转写待发中语音**：正在上传/未下载完成的语音拿不到文件，同样退占位。
- **上下文变长**：转写文本会比「[语音 N 秒]」长，挤占 `MAX_CONTEXT_LINE_CHARS` 截断之外没变化，
  但整体 prompt 会变长，注意 `contextLimit` 别开太大。
- **隐私**：上下文语音内容会出网到 SiliconFlow；这是「AI 回复」本身已隐含的路径（长按语音转写
  已存在），上下文语音只是顺带多转几条。

## 7. 验证清单（真机）

1. 上下文里有 1 条语音 → 生成后模型能引用该语音内容（用一句只有语音里出现的话验证）。
2. 同一对话再点一次「生成」→ 不重复计费（看 SiliconFlow 用量/日志只有一次 POST）。
3. 语音文件已删除/未下载的那条 → 显示 `[语音 N 秒]`，生成不中断。
4. 多条语音的上下文 → 全部转写（≤`contextLimit`，现最多 20）。

## 8. 已定 / 待定

- 无开关，直接常开；转写条数不限；缓存 `cache/WcS/ai_stt_cache.json`（200 条）；候选最多 6 条、
  上下文最多 20 条。