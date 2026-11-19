# AI自动回复：实现设计（P0–P1）

状态：已实现（2026-11-19，基线 `af72296`）。以下「实现偏差」先写在前面，其余正文仍是设计依据。基线 `5db8c88`（WCS 分支）。本文把 `ai-auto-reply.md` 的方案落成可编码的结构：类型、线程模型、状态机、校验门槛、配置键与分阶段改动。方案稿里的数值（3秒/15秒/20条等）在此转为定案值，理由写在对应小节。

## 0. 实现偏差

1. `AiReplyEngine` 按第 3.1 节落地，但**手动路径没有改为调用它**：`AiReplyCandidates.requestCandidates`
   原样保留，引擎只服务自动回复。理由是手动候选链路是当前唯一上线并被真机验证过的能力，
   在无法真机回归的条件下搬动它风险高于收益。代价是 prompt 构造与历史查询存在两份实现，
   后续合并以「六类手动回复回归通过」为前提，再按第 11 节做。
2. 自动回复不注册为独立功能：监听器随「AI 回复」功能的 `onEnable/onDisable` 挂载与卸载，
   开关只在 AI 回复配置页第一组。
3. 新增 `AutoReplyLog`（日志）落在 `AiAutoReply.kt` 内，未单开文件。

## 1. 首期范围

做：白名单会话（单聊 + 群聊）的文字、语音、图片、表情、视频输入 → 一条文字回复。群聊任意消息即触发，不需要 @；`不回复 @所有人` 单独可控。默认全部关闭，白名单默认空。

不做：工具调用、网页搜索、图片/语音回复、自动加好友、定时问候、跨会话转发、跨进程常驻、唤醒微信、重启补发。

阶段划分见第 13 节。只剩两步：P0 抽引擎（不改行为），P1 一次做完（2026-11-19 定稿，原 P1/P2/P3 合并）。拆细阶段只服务开发顺序，不对外暴露“先只有文字”“群聊要等下一版”这类中间形态。

## 2. 模块划分

| 文件 | 类型 | 职责 |
|---|---|---|
| 新增 `features/items/chat/AiReplyEngine.kt` | object | 无 Compose 依赖的生成引擎：历史、目标、ASR、视觉、模型请求、结果结构化 |
| 新增 `features/items/chat/AiAutoReply.kt` | object | 功能开关、白名单、轻量入站过滤、任务领取与发送 |
| 新增 `features/items/chat/AutoReplyScheduler.kt` | internal class | 每会话缓冲、防抖、串行队列、全局并发闸、接管与版本 |
| 新增 `features/items/chat/AutoReplyLedger.kt` | object | 去重账本与任务状态持久化（原子写） |
| 改 `AiReplyCandidates.kt` | object | 改为调用引擎，保留候选窗、编辑、发送、TTS 试听 |
| 改 `WeMessageApi.kt` | object | `sendText` 增脱敏日志参数（默认关闭，旧调用方行为不变） |
| 改 `strings.xml` ×2 | 资源 | 自动回复设置、状态、失败原因 |

`AiAutoReply` **不**作为独立功能注册，也不单开设置页：入口是 AI 回复配置页的第一组（第 10 节），在现有第一个 `SegmentedColumn` 之前插入。理由：自动回复不是与 AI 回复并列的能力，它只是 AI 回复的一种触发方式；单开一页会让同一能力出现两个入口，两处状态还得双向同步。

## 3. 核心类型

### 3.1 引擎

```kotlin
object AiReplyEngine {

    /** 一条待回复/待参考的消息，已脱离 MessageInfo 生命周期。 */
    data class Target(
        val msgId: Long,
        val msgSvrId: Long,
        val talker: String,
        val senderWxId: String,      // 单聊=对方，群聊=发送者，本人=""
        val senderLabel: String,     // "我" / "对方" / 群昵称
        val isSend: Boolean,
        val typeCode: Int,
        val type: MessageType?,
        val createTime: Long,
        val content: String,
        val imagePath: String?,
    )

    /** 调用方在领取任务时冻结的设置，引擎内部不再读偏好。 */
    data class Settings(
        val account: String,
        val styleId: String,
        val stylePrompt: String,          // 已解析：用户自定义优先，其次内置
        val contextLimit: Int,            // 0..20
        val mediaContextEnabled: Boolean, // 历史/批次媒体是否识别
        val visionProfile: ApiProfile?,   // 副本，不是引用
        val visionInstruction: String,
    )

    enum class Output { CANDIDATES, SINGLE }

    data class Request(
        val targets: List<Target>,        // 旧→新，至少一条
        val settings: Settings,
        val output: Output,
        val candidateCount: Int = 3,      // 仅 CANDIDATES 使用
        val maxReplyChars: Int = 1000,    // 仅 SINGLE 校验
    )

    data class Failure(val messageId: Long, val stage: String, val reason: String)

    data class Result(
        val candidates: List<String>,
        val single: String?,
        val finishReason: String?,
        val completed: Boolean,
        val promptTokens: Int?,
        val contextChars: Int,
        val mediaTotal: Int,
        val mediaFailed: Int,
        val failures: List<Failure>,
        val elapsedMs: Long,
    )

    suspend fun generate(
        request: Request,
        isStillValid: () -> Boolean = { true },
        onProgress: (Progress) -> Unit = {},
    ): Result
}
```

要点：

- `generate` 不抛业务异常，配置缺失/账号变化/网络失败都以 `completed=false` + `failures` 返回；`CancellationException` 继续向外抛。
- `isStillValid()` 在每次媒体识别后、请求前、流结束后各调一次；返回 false 立即 `CancellationException`。手动路径传 `{ true }`，自动路径接版本与账号校验。
- 引擎只依赖 `WeDatabaseApi`、`AiImageRecognition.recognizeForReply`、`VoiceTranscriber.transcribe`、`ModelProviderManager`，不依赖 Compose、不读 `prefOption`。

### 3.2 调度

```kotlin
internal class AutoReplyScheduler(
    private val scope: CoroutineScope,
    private val settingsProvider: () -> AutoReplySettings,   // 领取时快照
) {
    fun submit(snapshot: InboundSnapshot)          // 只入缓冲，绝不阻塞调用线程
    fun onTakeover(talker: String)                 // 本人在该会话发言
    fun shutdown()                                 // onDisable：取消任务、清缓冲、作废发送许可
}
```

缓冲按 `account + talker` 分桶，每桶一个 actor 协程（`Channel<Event>`），桶内串行 —— 不用多把锁，天然满足“同会话不重入”。

## 4. 运行链路

```text
[宿主数据库线程] onInsert(table, values)
   └ 只做：表名 / 开关 / isSend==0 / 类型 / 白名单 / 新鲜度 / 去重领取
   └ 复制 9 个字段 → InboundSnapshot → scheduler.submit()（非阻塞）

[调度协程] 每会话缓冲
   └ 防抖 3s（有新消息顺延）、最长等待 15s、单批最多 20 条
   └ 到点 → 领取批次 → 全局 Semaphore(1) → 生成任务

[任务] PREPARING
   └ 按 msgSvrId + talker 还原 MessageInfo（失败重试 ≤3 次，共 3s）
   └ 群聊：命中 @所有人 且 skip_announce_all 为真 → 丢弃；其余任意消息继续
   └ 读设置快照 + 版本号

[任务] GENERATING
   └ AiReplyEngine.generate(targets=batch, output=SINGLE)

[任务] READY → 发送前重验（见第 8 节）
   └ 落盘 SENDING → 记 recentSends(talker → 正文摘要)
   └ WeMessageApi.sendText(talker, text, redactLog = true)
   └ QUEUE_ACCEPTED / FAILED / SEND_UNKNOWN
```

线程约束：`onInsert` 在宿主 DB 线程，只允许做字段读取、集合判断、`Channel.trySend`。任何数据库查询、模型请求、文件 IO 都不允许在这个回调里出现。

## 5. 缓冲与并发参数（定案）

| 参数 | 值 | 理由 |
|---|---|---|
| 静默防抖 | 3s | 与 WeAgent 默认一致，连续输入常见间隔 |
| 最长等待 | 15s | 刷屏时不能无限顺延，15s 保证有人回 |
| 单批上限 | 20 条 | 分批而非丢弃，超出进入下一批 |
| 会话待处理上限 | 40 条 | 超限暂停该会话并记录原因，不再接单 |
| 任务有效期 | 5min | 覆盖排队 + 媒体准备；超时整批终止，不发半成品 |
| 会话冷却 | 10s | 回复后 10s 内新消息只合并等待，不直接丢弃 |
| 全局并发 | 1 | 避免并发 ASR/视觉请求打爆缓存与临时文件 |
| 全局频次 | 6 次/分钟 | 只统计真实模型启动次数，不统计消息数 |

达到 20 条时形成批次、后续进入下一批，不做媒体数量截断 —— 批内所有启用类型都处理。

## 6. 身份、去重与接管

**入站快照字段**：`account, talker, msgId, msgSvrId, createTime, type, isSend, content, imgPath, observedAt`。`isSend` 必须真实存在且为 0，缺失按丢弃处理，不默认“收到”。

**去重键**：`account|talker|svr:<msgSvrId>`，缺少服务端 ID 时退化为 `local:<msgId>`。两种 ID 同时存在时，客户端 ID 键作为别名一起写入，避免补齐服务端 ID 后再处理一次。

**领取必须原子**：`ConcurrentHashMap.putIfAbsent(key, now)`，返回值非 null 即已领取。禁止 `contains` 后 `put`。内存表容量 2000，超限按插入序淘汰；磁盘账本保留最近 500 条任务，启动时重建 24 小时内的领取记录。

**新鲜度**：`now - createTime ≤ 30s`，且 `createTime` 不得明显超前于当前时间。仅降低旧同步触发概率，不能证明一定不是历史导入 —— 这条写进配置页说明，并在验收里记为已知限制。

**接管判定**：本人在该会话发出 `isSend=1` 消息时：

1. 若 `recentSends[talker]` 在 60s 内且正文与刚发出的自动回复一致 → 判定为自己的回声，忽略；
2. 否则 → 判定人工接管：`takeoverVersion++`、清空该会话缓冲、取消在途任务，只影响本会话。

无法关联时按接管处理 —— 宁可少回一条，不可重复回。

## 7. 状态机与账本

```text
OBSERVED → BUFFERED → PREPARING → GENERATING → READY → SENDING → QUEUE_ACCEPTED
                                     │           │        └→ FAILED
                                     │           └→ SUPERSEDED / EXPIRED / CANCELLED
                                     └→ SKIPPED（过滤、去重、超限）
SENDING 中断/异常 → SEND_UNKNOWN（重启不自动重发）
```

状态文件：`KnownPaths.moduleData / "auto_reply" / "state.json"`（非 cache，禁止被系统清理），原子写（临时文件 + rename），结构：

```json
{"version":1,"tasks":[{"id":"...","account":"wxid_x","talker":"...","batch":["s:123","l:456"],
  "state":"QUEUE_ACCEPTED","at":1763000000000,"chars":42,"model":"glm-4.5","note":""}]}
```

`sendText` 返回 true 只标 `QUEUE_ACCEPTED`；没有服务端回执前不标 DELIVERED。程序只能降低重复概率，做不到端到端 exactly-once —— 这一条要在配置页写明。

**账本与日志是两回事，别合并**：

| | 账本 `state.json` | 运行日志 `auto-reply/*.log` |
|---|---|---|
| 位置 | `moduleData/auto_reply/`（非 cache） | `moduleCache/auto-reply/<账号>/`（与识别缓存同级） |
| 用途 | 去重与重启恢复，必须长期可靠 | 给人看，排查与统计 |
| 生命周期 | 不参与过期清理，只有手动清空 | 保留今天与前三天，按天滚动 |
| 丢失后果 | 可能重复回复旧消息 | 只丢历史记录，不影响运行 |

## 8. 发送门槛（全部为“与”关系）

| 检查 | 不满足时 |
|---|---|
| `completed == true` | 不发，记 FAILED |
| `finishReason` ∈ {stop, end_turn, stop_sequence}（大小写归一） | 不发。手动允许 null 并提示，自动不放行 |
| 正文非空 | 不发 |
| `finishReason != length` | 不发。被 token 上限截断的半句话比不回更糟；长度不做字符裁剪，要更长就调请求的 `max_tokens` |
| 无候选序号、无“以下是”、无思考标记 | 不发 |
| 账号仍是任务开始的那个 | 不发 |
| 总开关仍开、talker 仍在白名单、群聊开关未关 | 不发 |
| 未过期、未取消、`takeoverVersion` 未变 | 不发 |
| 去重账本未标记发送过 | 不发 |
| 配置版本未变（模型/服务商/指令/视觉档任一项） | 不发，下一批用新配置 |

发送固定使用批次里的 talker，模型输出不能携带新的会话或发送类型。首期不引用、不自动 @。

**群聊触发矩阵**（`group_enabled` 与 `group_at_only` 都在白名单命中之后判断）：

| 群聊回复 | 仅回复 @ 我 | 结果 |
|---|---|---|
| 关 | — | 群里一律不回 |
| 开 | 关 | 白名单群每条消息都回（`skip_announce_all` 仍排除 @所有人） |
| 开 | 开 | 只回点名本人的消息，`@所有人` 与普通消息都不回 |

**语音回复矩阵**（`voice_reply` × 语音配置的「开启语音回复」）：

| 语音配置 | 自动 voice_reply | 结果 |
|---|---|---|
| 开 | 开 | 用语音消息回复，音色取语音配置 |
| 开 | 关 | 只发文字 |
| 关 | 开 | 只发文字（不报错、不降级提示，静默按文字走） |
| 关 | 关 | 只发文字 |

语音合成本身失败（网络、额度、超时）时降级为文字发送，不阻断回复；连续失败 3 次只记一条日志，避免刷屏。

## 9. 配置项

统一前缀 `ai_auto_reply_`，全部存 `WePrefs`：

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | Boolean | false | 总开关，配置页第一行；关闭即注销监听并停队列 |
| `talkers` | Set\<String\> | 空 | 白名单，单聊/群聊都要明确选入 |
| `group_enabled` | Boolean | true | 白名单群里**任意消息**都触发，不要求 @ |
| `group_at_only` | Boolean | false | 打开后群聊只回 @ 我；关闭时白名单群任意消息都回。关闭状态下界面必须给刷屏警示 |
| `skip_announce_all` | Boolean | true | 命中 `@所有人` 的消息不参与。`@所有人` 在微信里同样满足 `isAtMe`，所以它与 `group_at_only` 是两件事，不能合并 |
| `voice_reply` | Boolean | false | 自动回复以语音消息发出。**与语音配置的「开启语音回复」取与**：两者都开才发语音，任一关只发文字 |
| `debounce_ms` | Long | 3000 | |
| `max_wait_ms` | Long | 15000 | |
| `cooldown_ms` | Long | 10000 | |
| ~~`reply_max_chars`~~ | — | — | **已取消**：回复长度按模型输出，不做字符截断。约束交给请求里的 `max_tokens`，超限由模型侧停止，客户端不二次裁剪 |

模型、语气、回复长度、历史窗口、视觉档、识别指令、音色与合成指令一律不另存，运行时读当前 AI 回复配置并快照；配置页只读展示跟随关系。

**能力开关也不再单设**，直接沿用 AI 回复配置里已有的两处：

| 输入 | 依据 | 不可用时的行为 |
|---|---|---|
| 语音 | AI 回复的 ASR 配置档（`AsrProfiles`） | 转写失败或无可用档 → 该条 SKIP，不发猜测 |
| 图片 / 表情 / GIF / 视频 | 「多媒体识别」的 `识别上下文中的多媒体` 开关 | 关闭 → 该类型目标不触发 |
| 文字 | 总是可用 | —— |

这样“自动回复”只剩三个决定：**回谁、什么时候回、群里的哪些消息不参与**。

`enabled`（即 `ai_auto_reply_enabled`）是总开关，由配置页第一组的开关行写入，不走 `ClickableFeature.technicalId`（那个键属于 AI 回复本身）。删档或改配置只比对快照版本，不引入新的凭据存储。

## 10. 界面

### 10.1 位置：AI 回复配置页的第一组

不做独立设置页，也不做首次引导。自动回复插在现有 AI 回复配置页的最前面，其后保持原有顺序：`API 配置` → `语音配置` → `多媒体识别` → `生成参数`（现有代码里是 API → 生成参数 → 多媒体 → 语音，本次一并调整为用户要的顺序）。

```
自动回复                        [开关]     ← 关闭时，本组只有这一行
  已开启 · 2 个会话
  生效会话      张三、产品群                    2 个 ›
  群聊回复      任意消息都触发，不需要 @         [开关]
  仅回复 @ 我   关闭时群内每条都回                [开关]
  不回复 @所有人  @所有人 的消息不参与            [开关]
  语音回复      只用文字 / 以语音消息回复          [开关]
  回复节奏      标准 · 静默 3 秒后回              ›
  自动回复日志  今日 12 条 · 已发送 8             ›

API 配置
语音配置
多媒体识别
生成参数
```

三个约束：

1. **关闭时只剩一行**。不显示灰化的子项，也不显示“未启用”占位——关掉就是关掉，避免用户以为还有东西在跑。
2. **不新增能力、模型、语气、长度、上下文行**。这五项全部沿用下方原有分组，组内只用一句说明写清楚跟随关系。自动回复只决定回谁、何时回、群里哪些不参与。
3. **群聊两个开关分工明确**。`群聊回复` 决定「回不回群」，`仅回复 @ 我` 决定「回哪些」：后者关时白名单群每条消息都回，此时必须在组内显示刷屏警示（原型里是一条黄色提示 + 「建议打开仅回复 @ 我」）；后者开时只回点名本人的消息。`不回复 @所有人` 始终独立，因为 `@所有人` 在微信里同时满足 `isAtMe` 与 `isAnnounceAll`，不能靠「仅 @」把它排除掉。前两行只在 `群聊回复` 打开时出现。
4. **语音回复是双开关与关系**。本行只是「允许自动回复发语音」，还要语音配置里的「开启语音回复」也打开；任一关闭都只发文字，且不提示、不报错。理由是语音比文字更具侵入性，用户关掉任一处都不应该被绕过。合成失败降级文字发送，不因为 TTS 挂了就不回。

加这一组只改一处：`AiReplyCandidates` 的 Compose 内容在第一个 `SegmentedColumn` 之前插入本组，开关写 `ai_auto_reply_enabled`。不需要新功能注册，也不需要在设置页多一行。

### 10.2 子页

- **生效会话**：`ContactsSelector`（`WeDatabaseApi.getFriends() + getGroups()`），默认空，底部「完成（已选 N 个）」，N 为 0 时禁用。
- **回复节奏**：三张卡片（宽松 5/25/30、标准 3/15/10、紧凑 1/8/5），不让用户填毫秒。选中即返回。
- **自动回复日志**：见第 12 节。

关闭功能时不弹确认，直接生效，`onDisable` 必须：注销监听、`scheduler.shutdown()`、作废发送许可、保留账本与日志（下次启用不重复回旧消息）。配置页需检测 `WeDatabaseListenerApi.isActive`，未开启时在组内显示「依赖：数据库监听服务未开启」，因为监听器只有在那层 Hook 生效时才会被调用。

可点击原型：`WCS/AI自动回复-布局原型.html`（配置页关闭/开启两态、会话、节奏、日志、群聊演示五个视图）。

## 11. 与 AI回复的接缝（引擎抽取）

现状：`AiReplyCandidates.requestCandidates`（`:1319`）与 `loadContext`（`:1466`）都是私有成员，直接读 `visionProfileId`、`visionPrompt`、`mediaContextEnabled`、`contextLimit`、`candidateCount` 与单例 `AiReplyApiConfig`。

抽取步骤：

1. 新增 `AiReplyEngine`，把 `requestCandidates` 的媒体准备、历史查询、模型请求、流结束校验搬进去；`CandidateContent` 只保留 UI 状态与 `onStatus` 渲染。
2. `loadContext` 参数化为 `(talker, beforeCreateTime, beforeMsgId, limit, includeMedia, account, recognize)`：批次场景用**最早一条目标**的边界，不是最后一条。当前 `WHERE createTime < ? OR (createTime = ? AND msgId < ?)` 的写法可直接复用，只换锚点。
3. 批量输入时，历史区之后追加“本次待回复（旧→新，带发送者和类型）”，最后一行固定为“请以本人身份作出一条自然回复”。目标不参与历史，避免同一条出现两次。
4. `parseNumbered` 只留给手动；自动用新增 `parseSingle`：整体作为一条正文，检测到编号行或“候选”字样即判失败。
5. 手动行为必须零变化：候选数量、编号格式、缺结束标记提示、媒体失败占位、状态文案全部保持。抽取以“手动回归通过”为完成标准。

## 12. 日志与可观察性

### 12.1 独立一份运行日志

自动回复不写进识别缓存，也不依赖 logcat 靠人抓。单独一份文件日志，位置与 AI 回复缓存同级：

```text
cache/WcS/ai-recognition/<账号>/            识别缓存（已有，不动）
cache/WcS/auto-reply/<账号>/2026-11-19.log  自动回复日志（新增）
```

按账号分目录，与识别缓存的结构保持一致：多开时互不串号，清空也只影响当前账号。

- **只记白名单会话**。未命中白名单的消息在入站过滤阶段就返回，不写文件、不打 logcat，避免把整份日志淹在无关会话里
- 每条只留结论：时间、会话、结果（已发送/跳过/失败）、一句原因。不记去重命中、请求参数、上下文条数这类过程细节
- 按天一个文件 `yyyy-MM-dd.log`，一行一条 JSON，追加写，不重写历史文件
- 保留今天与前三天，与 `AiRecognitionCache.expirationCutoffMillis` 同一规则，启动时清一次过期文件
- 清空只删当前账号的 `auto-reply/<账号>/`，不碰 `ai-recognition/`、`ai_stt_*` 临时文件与任何配置
- 不点清空就完全不动，按天滚动，不占系统回收目录

字段（不含正文）：

| 字段 | 说明 |
|---|---|
| `t` | epoch ms |
| `talker` | 会话 id |
| `stage` | RECEIVED / BUFFERED / SENT / SKIP / FAILED |
| `reason` | `cooldown` / `announce_all` / `at_required` / `asr_failed` / `http_429` / `finish_length` … |
| `kind` | text / voice / image / sticker / video |
| `chars` | 正文字数（不记正文） |
| `costMs` | 静默达标到发送完成的毫秒 |
| `model` | 模型 id |
| `profile` | 配置档 id |

不记正文、转写文本、视觉描述、请求体、Key/Cookie。

### 12.2 日志页

配置页「自动回复日志」进入：顶部四个筛选（全部 / 已发送 / 跳过 / 失败）+ 一行保留说明，列表按时间倒序，每条显示**时间、会话、状态标签、一句明细**（如「2 条消息合并」「冷却中」「429 限流」）。不展示字数、耗时、模型名 —— 那些留在文件里排查用，界面上只回答「回没回、为什么没回」。底部两张只读行（记录范围、保留几份）与一个「清空」按钮；清空前二次确认，文案明确「只清 auto-reply，不动识别缓存与配置」。

日志页的计数直接读当天的日志文件，不另存一份统计，避免两套口径。

### 12.3 logcat

TAG `AiAutoReply` 只记白名单命中的阶段与原因，与文件日志同源；非白名单不打任何日志。`sendText` 现版本会打出完整正文（`WeMessageApi.kt:1500`），新增 `redactLog: Boolean = false` 参数，自动路径传 true，只记 `talker + 长度`。

## 13. 分阶段实施

**P0 抽引擎（不改行为）**

- 新增 `AiReplyEngine.kt`；`AiReplyCandidates` 改调用；手动传 `Output.CANDIDATES`、`isStillValid = { true }`
- 验收：文字、语音、图片、表情、视频、GIF/APNG 六类手动回复与抽取前一致；`finishReason` 缺失时仍展示候选并提示；媒体失败仍占位继续

**P1 自动回复（单聊 + 群聊，文字 + 语音 + 媒体）**

- 新增 `AiAutoReply.kt`、`AutoReplyScheduler.kt`、`AutoReplyLedger.kt`
- 处理 `typeCode == 1` / 语音 / 图片 / 表情 / 视频；能力沿用 AI 回复已有的 ASR 档与「识别上下文中的多媒体」开关，不新设开关
- 媒体目标与历史走 `recognizeForReply`、`VoiceTranscriber`、`AiRecognitionCache`；目标失败整批不发，历史失败只记明细
- 补 ASR 网络调用的可取消绑定（`VoiceTranscriber` 内部是同步 `execute`，协程取消不会立刻停 HTTP）
- 群聊：`group_enabled` 为真才处理群消息；`group_at_only` 为真时要求 `isAtMe`，为假时任意消息都触发；两种模式都受 `skip_announce_all` 约束
- `isAtMe` 与 `isAnnounceAll` 分别取值，不能合成一个布尔；`@所有人` 在微信里两者都为真，混用会让「仅 @」失效
- 群聊不再依赖 `lvbuffer` 判断是否 @，因此触发与单聊同一步；批次内保留 `sender` 与显示名，历史行写“对方(昵称)”，避免把 A 的话当成 B 的
- 语音发送：`voice_reply` 与语音配置的「开启语音回复」取与，都开才发语音；合成失败降级文字；手动回复的试听与发送链路不动
- 界面：配置页第一组（开关 + 四行子项）、会话选择、节奏、独立日志页
- 验收：非白名单零请求；重复回调只领取一次；生成中改时间地点时旧答复不发；手动接管后旧结果作废；视频未落盘、ASR 500、视觉 401/429 均不发猜测正文；群里无人 @ 也能回（仅 @ 关闭时）；`group_at_only` 打开后无 @ 不回、`@所有人` 不回；语音回复双开关四种组合符合矩阵；不同发言人身份不混；`QUEUE_ACCEPTED` 与 `FAILED` 可区分

手动回复的语音发送仍走现有「试听 → 发送」流程，本次不改；自动回复复用同一套合成与写入 voice2 的路径，只是跳过试听。

## 14. 已知风险

1. **回调线程成本**：`onInsert` 在宿主 DB 线程，白名单判断必须放在最前，且只做集合查找。任何一次误加查询都会拖慢聊天入库。
2. **`WeApi.selfWxId` 时机**：早期回调可能取不到账号，取不到即丢弃，不做缓存兜底。
3. **群聊发言人**：不再依赖 `lvbuffer` 判断 @，但批次里可能混入多人发言，必须保留 `sender` 才能把历史写成「对方(昵称)」；拿不到 sender 时不合并该条。
4. **自己的回声**：`sendText` 不返回 msgId，只能靠正文 + 60s 窗口关联；关联失败按接管处理，代价是偶尔少回一条。
5. **配置中途变更**：快照能让在途任务用旧配置跑完，但发送前会被版本校验拦下；表现为“这条不回”，不是“用错配置回”。
6. **5 分钟有效期**：大批视频会话可能超时。P1 要按实测调整，不能靠延长有效期掩盖媒体耗时。
7. **引擎抽取回归**：手动路径是唯一已上线能力，P0 必须整块回归，不能只测文字。

## 15. 与既有文档的关系

- `ai-reply-auto-reply.md`：方案与论证来源，本文不重复其证据链。
- `ai-reply-vision-integration.md`：手动多媒体底座，本文第 11 节在其上做抽取。
- `ai-reply-voice-context.md`：上下文语音转写的决策记录，批次语义与之一致。
