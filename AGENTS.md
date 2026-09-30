# AGENTS.md

## Superpowers

- All Superpowers workflow artifacts for WcS (plans, specs/designs, SDD ledgers and
  reports, brainstorm sessions) are written, edited, and committed **only** in
  `~/coding/wekit_dev/superpowers` (its own git repo; read its `AGENTS.md` for layout and
  rules). Never create, edit, or commit `.superpowers/` or `docs/superpowers/` inside this
  repo — those paths are gitignored here by design. (The host directory was NOT renamed by
  the WCS rebrand; it still lives under its original name.)

## Build

```bash
./x build           # debug (uses same signing as release)
./x build --release # release (with optimization on)
./x run --zygisk    # build the dual-format APK and install it as a Zygisk module
# (./x is alias to `cargo xtask` which orchestrates the build process)
```

- **When working in a Git worktree, initialize submodules before starting any work:**
  `git submodule update --init --recursive`. Worktrees do not automatically populate submodule
  contents, and builds will fail when `libs/common/bsh` and `libs/common/reflekt` are empty.
- **When working in a Git worktree, work directly on `dev` unless the user explicitly requests
  another branch or isolated history.** This is because commits made on a detached worktree are not automatically
  transferred by Codex's “local checkout” action and can appear to be lost.
- Every standard/legacy debug/release APK is dual-format: install normally as an APK, or rename
  to `.zip` and install through a root manager. Module files enter the APK before AGP signs it;
  never modify or repack the signed output. There is no separate `zygisk build` command.
- JDK 21
- **Gradle does NOT build the Rust native lib.** `./gradlew assemble*` only packages whatever
  prebuilt `libwcs_native.so` already sits in `app/src/main/jniLibs/<abi>/`. Compiling
  `app/src/main/rust/wcs-native` and refreshing those `.so` files is xtask's job
  (`task_build_native`), so **always go through `./x`** — running Gradle directly will silently ship
  a stale native lib. Requires a Rust toolchain + the Android NDK and its Rust targets;
  `./x configure` regenerates `wcs-native/.cargo/config.toml` from the local NDK and is invoked
  automatically by the build tasks.
- `./x build --native-only` prepares both the application and Zygisk native libs in `jniLibs/`
- AGP 9, Gradle version catalog in `gradle/libs.versions.toml`

## Project Structure

- `app/` — main Android module, entrypoints, hooks, UI, native Rust lib
- `libs/common/annotation-scanner/` — KSP processors: source-subtype discovery for
  `BaseFeature`/`ExtensionPack` objects plus the `@AgentTool` scanner
- `libs/common/libxposed-api/` — compileOnly LibXposed API interface stubs (compileOnly since they are provided by user's Xposed framework)
- `libs/common/bsh/` — submodule: forked BeanShell interpreter with snapshot serialization (`BshSnapshot`, `BshSnapshotHelper`); snapshots are encrypted AST byte representations used by the WAuxiliary Xposed module; `app/src/main/java/dev/sherry/wcs/utils/BshSnapshotDecompiler.kt` — decompiles encrypted BeanShell snapshot files back into Java-like source code; the AES key was recovered from WAuxiliary's decompiled source
- `libs/common/reflekt/` — submodule: reflection utility library (`dev.ujhhgtg.reflekt`)
- `libs/common/stubs/` — compileOnly stubs for WeChat and Android hidden classes
- `buildSrc/` — custom Gradle tasks: `GenerateMethodHashesTask` (`IResolveDex` `resolveDex` method MD5 cache), `GenerateNewFeaturesTask` (Kotlin source files added within 30 days of the HEAD commit → `NewFeatures.ADDED_AT_BY_SOURCE_KEY`; KSP joins source keys to discovered features for the 新功能 pseudo-category)
- `xtask/` — build orchestration behind `./x`: native-lib compilation + NDK linker config, APK
  assembly/signing via Gradle, and Zygisk installation of that same APK

## Entry Points & Architecture

- Xposed entry: `dev.sherry.wcs.loader.entry.lxp.LxpHookEntry` (libxposed 101 ~ 102) and legacy Xposed API (51+) entry: `dev.sherry.wcs.loader.entry.xp51.Xp51HookEntry`
- Unified flow: `UnifiedEntryPoint.entry()` → `StartupAgent.startup()` → `WeLauncher.init()`
- Feature objects inherit `BaseFeature`, declare `technicalId`/resource/category metadata as
  override properties, and are auto-discovered by KSP from their source subtype at compile time
- Extension pack objects implement `ExtensionPack`, declare a required `displayOrder`, and are
  auto-discovered by the same KSP processor
- Base classes: `SwitchFeature` (toggle on/off), `ClickableFeature` (toggle on/off with onClick event), `ApiFeature` (always-on), `BaseFeature` (abstract base, do not use directly)
- DEX analysis via DexKit with `IResolveDex` interface; method resolve body MD5-hashed for cache (
  `GenerateMethodHashesTask`)
- DEX-resolved targets DSL: `val methodTarget by dexMethod()` `val classTarget by dexClass()` delegate → `methodTarget.hookBefore { ... }`, `val method: Method = methodTarget.method`, `val clazz = classTarget.clazz`
- UI: Jetpack Compose + Material 3, dialogs written using `showComposeDialog` and
  `AlertDialogContent`; settings screens follow the Material 3 UI Standards section below
  (`ui/content/m3/` widget family, InstallerX-Revived design)
- Config: MMKV via `WePrefs`
- Logging: via `WeLogger`

## Desktop DexKit Validation

- Use `./x dex-test` to run the same `IResolveDex`/DexKit resolution steps used by
  `DexCacheManager.kt` against WeChat APKs on the Linux desktop. Test only the supported host
  range **8.0.65–8.0.78**; APKs outside that range are useful for investigation but must not be
  treated as compatibility gates for the project.
- Add `--workers 4` to exercise the same shared batch scheduler as local Android resolution.
  Use `--workers 1` for its serial comparison; omitting the option preserves per-feature runs.
  Batch runs initialize/reset all selected roots before starting workers and report after joining.
- Test each supported APK version separately, including separate normal and Google Play APKs
  when both are available. Each APK runs in its own JVM worker and must carry its own version code,
  version name, build tag, and Google Play metadata.
- Reports belong under `dex-test-results/<run-id>/` (or an explicitly supplied output directory),
  never under Gradle's `build/reports/`. Preserve the per-APK JSON reports and aggregate summary.
- Resolution classification is strict: an `allowFailure = true` delegate that receives its
  placeholder is `EXPECTED_FAILURE`; an unhandled resolver exception is `UNEXPECTED_FAILURE`;
  delegates that remain pending after that exception are `BLOCKED` and must record the triggering
  delegate; a resolver returning with pending delegates is `INCOMPLETE`.
- A desktop resolution pass does not prove hook-time behavior on a physical device. Initialization,
  worker, native-library, APK metadata, report, unexpected, blocked, or incomplete failures must
  remain visible and make the command fail.
- DexKit desktop testing is intentionally expensive. After a supported-version run has passed,
  do not rerun it for unrelated changes when no Dex declarations or resolution steps changed.
  Rerun the affected supported APK versions after changing `dexMethod`, `dexClass`, `dexField`,
  inline matchers, or the corresponding `resolveDex`/`resolveInlineDex` logic.
- Before reporting a Dex resolver change as complete, run the affected desktop tests plus any
  relevant existing or qualifying Gradle tests (as defined under Testing Strategy), `./x build`,
  and `git diff --check`.

### Desktop-safe Dex resolver rules

- `resolveDex`, `resolveInlineDex`, and inline matcher blocks run in the same
  `DexResolutionContext`. When a matcher needs information from an already-resolved delegate,
  use its DexKit metadata (`delegate.data.name`, `.declaredClassName`, `.returnTypeName`,
  `.paramTypeNames`, `.superClass`, `.interfaces`, etc.), not JVM reflection. In particular, do
  not use another delegate's `.clazz`, `.method`, `.constructor`, `.field`, `asClass`, or
  reflection-derived `Class`/type information to construct a later Dex query: desktop workers
  cannot reliably load WeChat/Android classes.
- Do not hide that reflection behind a `lazy` property or object initialization. A resolver-side
  lazy such as `by lazy { target.method.declaringClass }` is still invalid for desktop testing;
  derive the required descriptor from `target.data` while resolving instead. Reflection properties
  remain valid after resolution for actual hook-time Android behavior; this rule applies only to
  declaration and resolution paths.
- An explicitly user-approved host-version branch, or any build-tag/Google Play branch, inside
  resolution must read `DexResolutionContext.host`, rather than `HostInfo`, so `./x dex-test` uses
  metadata belonging to the APK under test. Android resolution receives equivalent current-host
  metadata through the same context.
- A metadata migration must preserve the intended descriptor/matcher constraints. Do not loosen
  strings, signatures, or structural predicates merely to make a desktop test pass; use stable
  DexKit evidence as normal.
- For an intentional supported-version absence, use `allowFailure = true` only as documented
  below. Its generated generic expected-failure reason is acceptable; provide a more precise reason
  when it materially clarifies a structure-selected compatibility path. Do not convert exceptions
  or uncertain matches into placeholders just to obtain a green report.
- Resolver source is part of the device cache key: even a mechanically equivalent rewrite from
  reflection to `.data` changes the generated `methodHash` and invalidates that feature's old
  cache. Expect one device re-resolution after such a change; never retain or hand-edit an old
  hash to suppress it. Avoid unrelated formatting/refactors in resolver and inline matcher bodies
  when a cache invalidation is not intended.

### Host compatibility path selection

- Prefer structure-based compatibility over host-version checks. In Dex resolution, first probe
  one stable class, method, field, or constructor that exists only on the newer path. If that probe
  produces zero results, record its expected placeholder and fall back to the older path. If it is
  present, keep every other required target on that path strict. Multiple results, matcher errors,
  and failures after the probe must remain visible failures; they are not fallback conditions.
- At hook time or when invoking resolved host members, choose the path from the actual resolved
  structure. Use the new-path probe's `isPlaceholder`, inspect the resolved member's reflection
  signature, or test another directly relevant runtime property. Do not repeat the resolver's
  compatibility decision with a host-version comparison.
- If old and new hosts expose the same semantic member with only a signature difference, accept
  the confirmed signatures structurally (for example, `paramCount(10, 11)`). When invocation
  arguments differ, inspect `Method.parameterCount` or `Constructor.parameterCount` and construct
  the arguments from that actual signature.
- Avoid branches based on the WeChat host's `versionCode`, `versionName`, hard-coded WeChat version
  strings, or equivalent version constants. If a host-version check is genuinely unavoidable, ask
  the user for explicit confirmation before adding or retaining it. Distinguishing a Google Play
  build through `isHostGooglePlay`/`isGooglePlay` is **not** a host-version check and does not require
  that confirmation.

## Testing Strategy

- These repository-specific testing constraints take precedence over the generic Superpowers
  skills' TDD workflow. Do not add tests for host hooks, Compose UI, WeChat runtime behavior, or
  database integration when they fall outside the qualifying conditions below; use the required
  build, static checks, and manual host validation instead.

- TDD and new automated tests are allowed only when all core logic under test lives in WcS,
  has low coupling to WeChat, and does not depend on WeChat host classes, runtime state, UI, or
  behavior.
- Do not add tests for simple logic that is easy to verify by static review, such as constants,
  direct mappings, boolean expressions, identity functions, or straightforward arithmetic. Do not
  add tests merely to satisfy a workflow or a skill such as Superpowers.
- Do not increase production-code complexity to create a test seam. In particular, do not split a
  simple object singleton into an interface plus implementation, introduce unnecessary wrappers or
  dependency injection, or extract simple one-use logic into a standalone function solely so it can
  be unit-tested.
- Keep simple logic inline when it has only one use and does not form a meaningful reusable domain
  boundary. Extract a helper only when it improves readability, is reused, or isolates genuinely
  complex behavior; testability alone is not sufficient justification.
- If work does not meet all of those conditions, do not use TDD and do not add low-value tests
  merely to satisfy a testing workflow. Host hooks, reflection/DexKit glue, and host UI behavior
  are normally in this category.
- Use `./x dex-test` for automated Dex resolution validation as documented above. Apart from Dex
  resolution, manual testing in the real WeChat host is the primary behavioral test method;
  desktop JVM or Gradle tests do not replace it.

## Key Conventions

- Package namespace: `dev.sherry.wcs`
- `app` is an application module, not a library and cannot be consumed by other projects. Do not
  use the `internal` visibility modifier in Kotlin production sources under `app/src/main`; use
  Kotlin's default implicit `public` visibility instead, because `internal` provides no meaningful
  boundary there.
- Min SDK 28, target SDK 37, compile SDK 37
- Target: WeChat `com.tencent.mm`, versions 8.0.65–8.0.78. Current host info in `HostInfo`
- Process targeting via `TargetProcesses`: override `startup()` to check
  `TargetProcesses.isInMain` / `TargetProcesses.currentType`. Default: main process only.
- Device behavior still requires manual testing on real WeChat; desktop JVM tests cover Dex
  resolution only and do not replace device validation.
- NEVER wrap `hookBefore` and `hookAfter` in a `try-catch`/`runCatching` block. They should NOT fail. If they fail, then it's the module developer's problem.
- Use `WePrefs.Companion.prefOption` delegates to declare & use preference items easily.
- Teardown/revert on `onDisable` is **best-effort by design**, not a requirement. Many features
  irreversibly modify the host view tree; fully reverting them would need complex state management
  and syncing for little gain, so having the user restart WeChat is the accepted approach. Do NOT
  report "feature does not undo its changes in `onDisable`" as a bug.
- `allowFailure` on `dexMethod`/`dexClass`/`dexField` is ONLY for structures whose existence
  differs across supported WeChat versions (present in old, absent in new, or vice versa). If a
  declared Dex resolution is expected to succeed on every supported version, do
  NOT set `allowFailure`: a resolution failure must fail that feature loudly instead of silently
  degrading to a no-op.
- JVM reflection over host classes should go through `reflekt` (`libs/common/reflekt/`) by
  default, e.g. `thisObject.reflekt().firstField { ... }` or `.getField(name, true)` — not
  hand-rolled `getDeclaredField`/`getMethod` traversal.
- **NEVER use `Path.of` or `Files.writeString`.** These are frequent mistakes and
  are unavailable on older Android API levels supported by WcS. Convert strings through
  `dev.sherry.wcs.utils.fs.asPath` from `utils/fs/PathUtils.kt` (for example,
  `pathString.asPath` or `base.asPath.resolve(child)`) and write text through
  `kotlin.io.path.writeText`.
- No excessive defensiveness. When e.g. the hooked method and its argument types are
  known to hold, use direct casts: `thisObject as Activity`, `args[0] as View`, `!!`. Do NOT use `as?`
  safe casts, `args.getOrNull(0)`, `?:`, `?.someFun()` or similar guards for values that should always be present/non-null/etc.
  Code that is correct does not need the defense; code that is wrong must throw loudly and get caught by either `HookUtils`' or code's own exception catcher, and these
  guards only swallow the exception and hide the real error. Defenses and guards that are reasonable should still exist.
- The libraries `DexKit` and `reflekt` are NOT something you are familiar with. Do NOT hallucinate their API surfaces. Read their code before using them.
- In Compose, `LocalContext` always means the platform context and is never localized by WcS.
  Use standard Compose resource APIs for composable text and `LocalWcSLocalizedContext` only
  for imperative WcS resource reads. Mixed platform/resource operations must read both locals.
  Use `LocalActivity.current` for Activity-only APIs, and never add AndroidX owner forwarding to
  `WcSLocaleProvider`.

## Material 3 UI Standards

Design reference: `~/coding/InstallerX-Revived` — when unsure how a settings page should
look or behave, read its `app/src/main/java/com/rosan/installer/ui/page/main/widget/setting/`.
WcS's ported widget family lives in `app/src/main/java/dev/sherry/wcs/ui/content/m3/`.

### Layout

- Settings screens are a `LazyColumn` of `SegmentedColumn` groups (inset rounded cards,
  one group per concern, short `title` above each group). Do not hand-roll card layouts
  or use flat lists with dividers.
- Use the shared scaffolds — `M3ListScaffold` (`activity/settings/SettingsActivity.kt`)
  or `AgentSettingsScaffold` (`ui/agent/settings/AgentSettingsCommon.kt`): collapsing
  `LargeFlexibleTopAppBar` + blur + back button. Do not build per-screen scaffolds.
- Multi-screen settings follow the miuix-nav `NavDisplay` pattern of
  `WeAgentSettingsActivity` / `ReadReceiptsSettingsActivity` (sealed `@Serializable`
  routes, predictive-back drill-down).

### Widget choice

Prefer these over raw Compose controls:

- Plain / status / navigation row → `BaseWidget` (chevron or action in `trailingContent`).
- Boolean setting → `SwitchWidget`.
- Exclusive choice → `RadioButtonWidget`; supports dual click areas like the WeAgent
  "Memory" row: `onClick` (main area, e.g. opens the detail screen) + `onSelect` (the
  radio itself) + `trailingDivider`.
- String or number input → `TextFieldDialogWidget`: a standard clickable row showing the
  current value that edits it in a dialog with cancel/confirm. Or for draft & save semantics, place a bare
  `TextField`/`OutlinedTextField` directly in a `BaseSupportingWidget`.
- Value with a natural range and step (counts, seconds, delays) → `IntNumberPickerWidget`
  (slider row with drag tooltip), wrapped in a `BaseItemContainer` inside the group.
  Ports, hostnames, tokens, URLs and other free-form identifiers have no slider
  semantics — use the dialog row instead.
- Compact choice from a fixed set → `DropDownMenuWidget`.

### Interaction semantics

- Prefer **instant apply**: toggles, radios, sliders and dialog confirmations commit on
  change. Avoid "draft state + Save button" page designs — a text row's dialog
  cancel/confirm is its only draft lifecycle. Genuinely transactional flows (connect /
  verify / disconnect) may keep explicit action buttons; those are actions, not saves.
- Buttons that belong together share ONE row (`Modifier.weight(1f)` each, ~12dp gap) —
  not one button per line. Pair an action with its opposite (connect/disconnect,
  save/delete); destructive actions use the error color and a confirm dialog.
- While an operation is in flight, disable the affected rows and show an inline
  progress/feedback line; never leave conflicting controls tappable.
- Blank values show a hint in the row description; the row itself stays clickable.

## Naming Conventions

- 群聊: WeChat: chatroom; WcS: group/群组
- 朋友圈: WeChat: sns; WcS: moment

## Context you need

- WeChat decompiled sources: ~/coding/wechat_80{65,67,69,74,76}
- Decrypted WeChat main database: ./decrypted_wechat.db

## CI

- GitHub Actions: builds on push/PR to `master`/`dev`/`dev-sherry`/`WCS`/`dev2`。纯文档变更（`*.md`/`*.txt` 等 paths-ignore 列表内文件）不会触发 CI；修改 AGENTS.md 无需构建，不会跑 CI
- `WCS` 分支自 `7cede8ea` 起与 `dev-sherry` 同一条流水线（`build` + `upload-telegram`，其余 job skipped），实测 push 到新分支确实自动排 run
- 仓库守卫 `github.repository == 'Ujhhgtg/WeKit-Dev'` 在 `a5a5c390` 统一改成 `sherrys8/WCS`。**新仓库 `sherrys8/WCS` 已建好且是 PRIVATE**，所以那些守卫在它的 run 里真的会命中（实测：改名后的 push 让 `dex-test` 与 `extensions-changes` 开始执行，`dex-test` 单独耗时 28m40s、与 `build` 并行，而私有仓的 Actions 分钟是计费的）。因此 `dex-test` 的条件已在本次收紧回 **`github.event_name == 'pull_request'`**：push 不再跑它，需要桌面 Dex 校验时按 AGENTS 用 `./x dex-test`，或开 PR。其余不变：`build`/`upload-telegram` 只看 `refs/heads/*`；`release-ci` 与 `release-dex-test` 还额外要求 `refs/heads/master`，而 `sherrys8/WCS` 目前**没有 master 分支**，所以不会发布 release；`build-extensions` 只在 `extension-packs/**` 真有改动时才下载 792 MB rootfs 并上传 `Extensions`，且需要新仓还没有的 `RELEASE_TOKEN` secret
- 同一个克隆里 `origin` = 公开的 `sherrys8/WeKit`（那里的 push 依旧只有 `build` + `upload-telegram`），`wcs` = 私有的 `sherrys8/WCS`。改名内容只推 `wcs`，不要顺手推 `origin`
- `build` job 自 `684cb3d2` 起**只构建 standard 一个 flavor**（`./x build --release --flavor standard`）：不带 `--flavor` 会走 `assembleRelease` 把 standard/legacy 各编一遍，实测 21m32s → 13m48s。legacy 需要时本地 `./x build --release --flavor legacy`
- 产物只经 `upload-telegram` 发到 Telegram 频道；本 fork 的 `dev-sherry` **不建 GitHub release**（`gh release list` 只有 Extensions / Dex Test），AGENTS 旧述的「release named CI」在本分支不成立
- APK 仍是双格式：同一个包可直接装为 APK，或改后缀 `.zip` 经 root 管理器装为 Zygisk 模块，flavor 只决定 libxposed 入口有无（standard 有、legacy 无），与双格式无关

## Progress
### Done
- 8 个首页三卡文件全部实现并编译通过，CI 已成功
- 侧栏冲突解决：采用 `dev` 拆分架构
- 配置 pre-push hook（自动备份分支）
- `HomePageCards` 改为 `ClickableFeature`，添加设置弹窗（子卡片开关 + 顺序调整 + 字体颜色）
- CI build job 添加 `dev-sherry` 分支支持
- 日历卡/图片卡支持自定义背景颜色（`ColorPickerWidget`）和背景图片（`PickVisualMedia` + Coil 加载）
- 日历卡天气功能已全部删除
- 字体颜色 5 组独立存储并生效
- 修复网易云搜索 bug：`data.list` 是 JSONArray `[{index, name}]`，非 JSONObject
- 修复网易云歌词 bug：歌曲 ID 用 `optLong` 避免 `Int` 溢出
- 修复 Telegram 推送：CI 配置 `dev-sherry` 分支条件
- 网易云音乐 API 更换：`api3.andeer.top` AuroraAPI → `ffapi.cn/int/v1/dg_netease`（单接口统一搜索/歌词/播放）
- QQ 音乐 → 汽水音乐：移除 `api.ygking.top/api` → 改用 `api.cxzja.cn/api/qishuimusi`，平台标识 `"qs"`
- 汽水音乐 Token 设置：`HomePageCards` 新增 `home_qs_token` 偏好，设置弹窗中增加「汽水音乐设置」分栏，`TextFieldDialogWidget(password=true)` 供填写
- 汽水音乐搜索修复：`n=` 空值参数返回歌曲列表（`data` 为 JSONArray），`n=1` 返回单首详情含 `download_url` 和 `lyric`
- 三卡默认关闭：`calendarCardEnabled` / `imageCardEnabled` / `musicCardEnabled` 默认值 `true` → `false`
- 汽水音乐搜索兼容性修复：`httpGet` 添加 `User-Agent` 和 `Accept` 头部；`search`/`getTrackDetail` 的 `catch` 块增加 `toast` 错误提示
- 群聊智能分析界面重构：长按菜单进入全屏界面（左上「分析报告」+ 右上 API 设置/关闭）；API 设置精简为四参数（API 地址/API 路径/API Key(Bearer Token)/模型名称，去服务商选择，保存时强制 OpenAI Chat Completions 兼容）；主区为时段/容量/自定义主题输入+开始生成；报告流式边生成边展现（`onDelta` 回调）；底部 2x2 操作区（复制文字/发送文字/保存图像/发送图像）
- `showComposeDialog` 增加 `fullScreen` 参数（窗口 MATCH_PARENT + 内容 fillMaxSize）
- `AiModelConfig` 新增 `apiPath` 偏好 + `resolvedBaseUrl()`（baseUrl + apiPath 拼接）
- 群聊智能分析界面按设计稿重构：顶部「分析报告」+ 绿色日期范围（`yyyy/MM/dd ~ yyyy/MM/dd`）+ 右上 Tune 折叠整卡；「智能洞察」分组标签；可折叠智能总结卡片（浅灰绿底 + primary 35% 描边 + 24dp 圆角，头部 36dp 圆形魔法棒图标 + Expand 箭头）；「选择总结时段」行右侧 Tune（展开/收起模型容量）+ Settings（打开 AI 配置）；8 项时段 chips 横向滚动；输入框占位改设计稿原文；开始生成按钮深青底白字魔法图标；报告区嵌套卡片内，主内容 verticalScroll，底部 2x2 操作区保留
- `GroupTimeRange` 枚举扩为 8 项（新增 LAST_WEEK/LAST_MONTH/LAST_YEAR，labelRes 映射 last_week/last_month/last_year），`groupRangeStartEnd` 补边界（上周=上周一~本周一、上月=上月1号~本月1号、去年=去年1月1~今年1月1，均清时分秒）
- 三语言同步 7 个新字符串；`ui_group_analyse_range*`/`ui_group_result`/`ui_tip_ai_only`/`ui_group_model_capacity_tip`/`ui_group_custom_topic` 已无代码引用（保留为资源）
- 全屏沉浸优化：`showComposeDialog` 的 fullScreen 分支设置透明状态栏/导航栏 + `WindowCompat.setDecorFitsSystemWindows(false)`（edge-to-edge，内容背景延伸至系统栏区域，由 Composable 的 `statusBarsPadding` 等做内容避让）
- 右上角图标由 Tune（折叠）改为 Close（关闭弹窗）；折叠交互保留在卡片头部 Expand 箭头
- 修复 HTTP 404：`AiModelConfig.resolvedBaseUrl()` 增加路径去重（baseUrl 已带 `/v1` 且 apiPath 又填 `/v1` 时不再重复拼接）；`OpenAiChatCompletionsClient` 错误信息附带实际请求 endpoint 便于定位
- 状态栏沉浸补全（参照 PanelShell 模式）：fullScreen 分支 clearFlags(TRANSLUCENT_STATUS/NAVIGATION)、`isStatusBarContrastEnforced=false`、`WindowInsetsControllerCompat` 按系统深浅色设置图标明暗
- 布局回滚：动画方案（AnimatedVisibility/animateColorAsState/animateContentSize）在设备上仍有重叠问题，`GroupChatSummary.kt` 布局回滚到 `385d856c` 设计稿版本（纯 `if (!collapsed)` + 无动画），右上角 Close 保留；状态栏沉浸与 404 修复不受影响
- 404 修复增强：`AiModelConfig.resolvedBaseUrl()` 剥离 baseUrl 中误填的完整 `/chat/completions` 端点（如 `https://api.deepseek.com/v1/chat/completions`）；`apiPath` 允许直接填完整端点路径 `/v1/chat/completions` 或前缀 `/v1`，`OpenAiChatCompletionsClient` 检测 baseUrl 已含 `/chat/completions` 后缀时不再重复拼接，避免 HTTP 404
- 文案：「AI 配置」→「API 配置」（`ui_group_ai_settings_title` 三语言同步），保存 toast 改为「已保存 API 配置」
- 全屏沉浸双保险：`showComposeDialog` fullScreen 分支增加 `decorView.systemUiVisibility`（LAYOUT_STABLE/FULLSCREEN/HIDE_NAVIGATION），配合 `setDecorFitsSystemWindows(false)` 确保内容背景延伸到顶部导航栏
- 状态栏改为不沉浸（最终方案）：`showComposeDialog` fullScreen 分支移除 edge-to-edge（decorFits 恢复默认 true，内容从状态栏下方开始），状态栏/导航栏着色为页面背景色——`GroupSummaryDialog` 内用 `SideEffect` 将 `MaterialTheme.colorScheme.surface.toArgb()` 回写到 `DialogWindowProvider.window`，视觉上背景延伸到系统栏而内容不延伸；fullScreen 分支首帧先按深浅色给近似底色（dark=0xFF1C1B1F / light=WHITE）避免闪出宿主界面；仅群聊分析使用 fullScreen=true，其他弹窗不受影响
- 群聊分析默认主题重写：`buildAnalysisPrompt` 深度分析分支改为「联想标题 + 内容概览 + 灵活模块」（主要内容/重点话题/整体氛围/有趣亮点/总结），模块标题与数量（4~6）由模型按聊天内容灵活组织；去掉旧 5 模块（话题总结/情绪评估/关键信息/人物倾向/回复方案）与【】固定格式
- 群聊分析全屏切换 ComponentActivity：新增 `GroupSummaryActivity`（`ComponentActivity` + `@Keep`，经 ActivityProxy 借壳在宿主进程运行，数据库 API 可用）；`showGroupSummaryDialog` 改为 `startActivity`（`FLAG_ACTIVITY_NEW_TASK` + talker extra）；`GroupSummaryDialog` 改 internal、复用为先例 `ReadReceiptsSettingsActivity` 的 `WcSLocaleProvider(InjectedHost)` + `ModuleTheme` 模式；onCreate 配置 window（DRAWS_SYSTEM_BAR_BACKGROUNDS、清 TRANSLUCENT、`SOFT_INPUT_ADJUST_RESIZE`、`isStatusBarContrastEnforced=false`、图标明暗随深浅色）；`SideEffect` 将 `surface.toArgb()` 回写 statusBar/navigationBarColor（背景视觉延伸、内容不延伸）；移除 GroupSummaryDialog 内 DialogWindowProvider 回写死代码；manifest 注册（非 exported，`Theme.Material3.DynamicColors.DayNight.NoActionBar`）；`showComposeDialog` 的 fullScreen 分支保留但已无调用方
- 群聊分析默认主题精简：`buildAnalysisPrompt` else 分支 systemPrompt 整段替换为用户提供的单句提示词（「你是一个微信聊天分析助手……语言幽默生动、排版清晰、记录较少时简短回复」）；userPrompt 仅保留统计数据+聊天记录片段，去掉「请进行深度分析」结尾行
- 编译修复：bd073e7b 因误删 GroupChatSummary.kt 的 `Color` import（时段 chips 的 `Color.Transparent` 在用）导致 build 失败，df2f3c74 补回
- 清理死代码：depth=0（群聊日报）/depth=1（话题热度统计）提示词分支从未被调用（`generateReport` 硬编码 depth=2），删除两分支及 `buildAnalysisPrompt`/`aiGenerateReport` 的 `depth` 参数；`buildAnalysisPrompt` 现在只有自定义主题/默认两条路径
- 群聊分析新增本地统计可视化区（按用户设计稿）：页面顺序改为「核心指标（今日发言人数/今日消息数/历史总消息三列卡）→ 智能摘要卡（原有生成区）→ 深度图表（7 个可折叠模块卡：活跃发言排行/高频语义特征词云/聊天作息图鉴 2x2 四宫格/情绪指数探测/废话程度鉴定/全天活跃频次 24h 柱状图/内容载体偏好）」；新文件 `GroupStats.kt`（结构化 `GroupStats` + `computeGroupStats` + `renderStatsReport` 文本报告 + `loadCoreMetrics`/`loadGroupStats`/`loadGroupMembersMap`，从主文件迁出 `GroupTimeRange`/`groupRangeStartEnd`/extract* 工具/停用词）与 `GroupStatsCards.kt`（全部可视化 Composable，固定强调色进度条、M3 container 色四宫格、FlowRow 词云、24 柱 Canvas-free 柱状图）；统计区跟随时段选择实时加载（`LaunchedEffect(talker, timeRange)`，stats 携带时段标记），`generateReport` 接收 `precomputedStats` 复用统计区数据避免重复查询；活跃时段小时数改用 Calendar 取本地时区（原 `(createTime/1000%86400)/3600` 是 UTC 小时，时区错位 8 小时 bug 一并修复）；`WeDatabaseApi` 新增 `getMessageCountInRange`（COUNT 轻量查询，历史总消息不再全量拉取）；文本报告（AI 输入）格式保持不变；三语言新增 33 个 `ui_group_stat_*`/`ui_group_deep_charts` 字符串，`ui_group_smart_insight` 改「智能摘要/Smart Summary/智慧摘要」（zh-rTW 原值为简体「智能洞察」一并修正）
- 群聊分析后续迭代：① 深度图表 7 个模块默认折叠（`GroupStatsCharts` 各 collapsed 初始 true）；② `extractWords` 词云清洗（剥 XML 标签 `<[^>]*>` 与 HTML 实体、token 只保留 `isLetterOrDigit`、停用词补 `amp/lt/gt/xml/msg/appid/version` 等，修复词云出现 `0&lt`/`version=`/`/>` 等 XML 碎片）；③ API 配置弹窗改为动态行式（`AiSettingsDialog` 用 `SegmentedColumn` 单组 + 4 个 `TextFieldDialogWidget`，点行弹编辑框确认即保存，无保存按钮）；④ 模型容量改为弹窗 `ModelCapacitySamplingDialog`（右上 Tune 打开，「模型上下文容量」5 档分段选择 128K~2M + 「提取消息数量上限」滑块 0~1000 最左=自动，取消/保存）；`ModelCapacity` 枚举加 `M2(2M)` 档；新增 `AiModelConfig.extractLimit` 偏好（0=自动）；`aiGenerateReport` recentLines 由固定 30 条改为：extractLimit>0 取最近 N 条，自动时默认主题取最近 3000 条/自定义主题按 token×1.5 字符预算；三语言新增 `ui_group_capacity_sampling_*`/`ui_group_extract_limit*`/`ui_group_extract_auto` 字符串
- 群聊分析后续迭代（二）：① 删除词云/高频词功能（`extractWords`/`commonStopWords`/`WordCloud`/`GroupStats.words`/`renderStatsReport` 高频词行/`ui_group_stat_words_title` 三语言全删），深度图表 7 模块 → 6 模块；② 提取消息默认值 1000 条（原 3000）、滑块上限 3000（原 1000）；③ 智能摘要卡片与 6 个深度图表卡的展开/折叠改用 `AnimatedVisibility` 过渡动画（`expandVertically`/`shrinkVertically` 默认 clip 裁剪，替代早前重叠方案）+ `fillMaxWidth`；④ 删除 `renderStatsReport` 末尾 Hchat 签名行
- 群聊分析后续迭代（三）：① 折叠动画重叠修复——`expandVertically`/`shrinkVertically` 在 `verticalScroll` 容器内高度测量异常（无限高度约束），改外层卡片 `animateContentSize(tween(220))` 平滑高度 + 内容纯 `fadeIn`/`fadeOut`（不改变布局尺寸），动画保留且不重叠；② 布局切换新设计稿（标题「智能洞察」单行去日期、主卡片浅绿底+渐变描边、时段条浅灰容器+选中白色胶囊、结果空态占位卡）——随后按用户要求恢复标题区「分析报告+日期副标题」与主卡片原色（surfaceVariant + primary 35% 描边），仅保留时段条容器样式与结果空态占位卡；③ 新增 `ui_group_result_placeholder` 三语言（空态占位文案）
- 文字转语音（A 套 `TextToSpeech`）入口拆分：`SwitchFeature` → `ClickableFeature`，新增两个独立入口开关偏好 `tts_entry_bubble`（长按消息气泡菜单）/`tts_entry_input_bar`（长按加号或发送按钮菜单），默认全部 `false`；`getMenuItems()`/`getActionItems()` 在 provider 内直接读偏好，故功能设置弹窗（`onClick` → `SegmentedColumn` + 两行 `SwitchWidget`）拨动即刻生效、无需重启微信；三语言新增 `tts_entry_group_title`/`tts_entry_bubble(+_description)`/`tts_entry_input_bar(+_description)` 5 条，`feature_text_to_speech_description` 改写为双入口说明
- 文字转语音接入豆包后端：新增 `tts_backend`（0=配音魔方 / 1=豆包，默认魔方）+ `tts_doubao_cookie` + `tts_doubao_speaker`（与魔方 `tts_voice_id` 分开持久化，来回切后端各自记住所选）；主弹窗左下角新增 `Compare_arrows` 切换按钮显示当前后端名、点按即切。弹窗布局不随后端变化，只换数据——「系统音色」在豆包下标题改「豆包音色」并填 `DOUBAO_VOICES`（9 个内置 speaker id 常量表），「自定义音色」与「语气」两行在豆包下 `enabled = false` + 空 options（语气用 `description = if (isDoubao) emotion else null` 保持灰显当前值，因为 `DropDownMenuWidget` 的显示规则是 `description ?: selected?.label ?: "未选择"`）。`generateVoiceDoubao()` 用 OkHttp WebSocket 连 `wss://ws-samantha.doubao.com/samantha/audio/tts`：query 拼 `speaker`/`format=aac`/`speech_rate`/`pitch` + `aid=real_aid=497858`、`version_code=20800`、`pc_version=2.46.3` 等网页端指纹，`device_id`/`web_id`/`tea_uuid` 每次随机 19 位、`web_tab_id` 用 uuid4；握手头带 `Origin: https://www.doubao.com` + Chrome UA + `Cookie`；`onOpen` 发 `{"event":"text"}` 与 `{"event":"finish"}`，二进制帧即 ADTS AAC，落 `cacheDir/wcs_tts/doubao_*.aac` 后复用 `AudioUtils.anyToSilk` → `WeMessageApi.sendVoice`。设置弹窗 `showApiKeyDialog` 改名 `showSettingsDialog`，新增「豆包」组 Cookie 密码行
- 豆包协议要点（参考实现见 Critical Context）：鉴权只在 WebSocket 握手期完成，9 个音色全部需要 `sessionid`+`sid_guard`+`uid_tt`（约 30 天）；结束判定不能照抄参考实现的 30 秒 `recv` 超时，改为「连续 2.5 秒无新增字节」或 `onClosing`/`code != 0` 收尾、30 秒兜底上限
- CI 修复 `8fc0e276`：OkHttp **5.5.0** 的 `WebSocketListener.onFailure` 形参是 `Throwable` 而非 `IOException`，按后者覆写会报 `'onFailure' overrides nothing` 并使 `:app:compileStandardReleaseKotlin` 直接失败；`okio.ByteString`（`onMessage` 二进制重载）解析正常，属全仓首次引入 okio
- 魔方/豆包切换冲突修复 `1d2f285a`：原 `LaunchedEffect(Unit)` 只在弹窗打开时取一次魔方音色，从豆包切回魔方时列表只剩 `DEFAULT_VOICES` 三条、`customVoices` 为空导致该行不可选，且残留的豆包 speaker id 会被魔方接口拒绝，而提示是写死的「请检查 API Key 与网络」；改为 `LaunchedEffect(backendMode)` 且仅魔方模式请求，回填前用 `backend` 偏好做竞态二次校验（`return@post`），所选不在魔方列表内时归位到首个音色；`generateVoice` 回调扩为 `(String?, String)`，把 HTTP 状态与服务端 `code`/`msg` 带进 toast
- 「修改文本消息显示」(`ModifyTextMessageDisplay`) 从「仅文本气泡、一次性 setText」升级为「任意气泡 + 持久重放」，commits `c0d4cf0f`→`9e9d549e`→`4fdcc3cc`→`2441990d`→`cc734e08`→`713537c5`→`ce83340b`：
  - 菜单项白名单放开到文本/引用、红包（含专属与封面）、转账、链接/音乐/商品、名片、文件、群公告、拍一拍、APP、系统/位置/视频号；图片、语音、视频、表情不进菜单（`isEditableBubble`）
  - 统一抽象 `TextTarget` 三种宿主：`TextViewTarget`（真 TextView）、`FieldValueTarget`（自绘文本宿主的全部可写 CharSequence/String/Spannable* 字段，按声明类型构造后 `invalidate()`）、`HostFieldTarget`（菜单给的 View 自身「首个 CharSequence 字段 + 同名 setter」，即旧版通路）
  - 弹窗按行列出该消息内每个文本宿主各一行（同原文的多个 View/字段并成一行的多个 target），只改动过的；新增「恢复原文」清除该条记录
  - 持久化：偏好 `modify_text_message_display_overrides` 存 `{"talker|msgSvrId": {"原文": "替换"}}`（serverId 为 0 退回 msgId），实现 `WeChatMessageViewApi.ICreateViewListener` 在**每次绑定**按原文匹配重放，上限 200 条消息、超出丢最旧；重开弹窗按替换值反查原文，避免叠加第二层改写
  - `WeChatMessageViewApi` 新增 `getMessageOfView(view)`，复用其 `WeakHashMap<View, MessageInfo>`，监听方不再每次绑定反射 view tag
  - 行过滤：屏蔽名单存偏好 `modify_text_display_host_blacklist`，采集时现读，**设置里增删即时生效**。键为 `R.id 条目名`（`a44`）或 `类简单名#条目名`（`bkp#a44`），输入端 `@`/`#`、`@id/`、完整包名都会归一（`normalizeEntry`）。默认 15 条由用户真机指定：`MsgTextView#bju`/`#bj2`/`#a4r`，`B#a44`/`#bkn`，`bkp#a44`/`#a46`/`#a4s`/`#a4r`/`#a48`/`#bjp`，`bkq#bkm`，`bkr#bkn`，`bks#bkn`，`bkl#a46`。**带类名才精确**：同一 id 被多个类复用，`bju` 既是可改的文件卡片标题 `MMNeat7extView`，也是该屏蔽的 `MsgTextView`，只写 id 会一起屏蔽。演进：`ce83340b` 硬编码 id 白名单 → `e4053af2` 硬编码「完整类名@id」黑名单（**因嵌套类 `javaClass.name` 是 `chatting_menu$B` 而名单写 `chatting_menu.B`，实际从不命中**）→ `50b18a27` 改为偏好可编辑 + 简单名匹配 → `99d61e54` 分隔符统一为 `#`
  - 三语言新增 `chat_modify_text_no_editable`/`_revert`/`_saved`/`_diag`，`feature_modify_text_message_display_description` 改写
- 宿主文本定位真机结论（详见 Critical Context 同名条目）：文件卡片 `<title>` 在 `MMNeat7extView id=bju`、大小在 `MMTextView id=bj2`；引用消息菜单给的是正文叶子 View `MMNeat7extView id=bkl`，引用块是同行的兄弟 View，故编辑与重放都要上溯到「行根」
- CI 单 flavor 化 `684cb3d2`（见 CI 节）；备份分支 `backup/dev-sherry-20260926` = `b02c2c79` 已推 `sherrys8/WeKit`（远端仓库至今仍叫 `WeKit`；`backup/*` 不在 CI 分支过滤内，不触发构建）
- **WCS 分支改名（彻底换皮）** `478398df`→`0d6a6589`→`a5a5c390`→批次 4：包名 `dev.ujhhgtg.wekit` → `dev.sherry.wcs`，品牌按 `wekit`→`wcs`、`WeKit`→`WcS`、`WEKIT`→`WCS`、`weKit`→`wcS` 四条规则全量替换。改动面：7 棵源码树 git mv（app main/standard/test、annotation-scanner src+bin、python-runtime-api、python-runtime runtime/kotlin+python 包、stubs）+ `app/schemas` 目录；`libs.versions.toml` 的 namespace 同时是 applicationId；两个 xposed 入口清单与 KSP services 文件；keep 规则；manifest taskAffinity；crate `wcs-native`/`wcs-llama`/`wcs-zygisk`、`libwcs_*.so`、go 模块目录、python 容器包 `wcs/`；xtask 与 `verify-dual-apk.py`；ci.yml 的 artifact/keystore/缓存路径与 job 守卫；`module.prop` 的 id/name/author；四个下载源与展示跳转 → `sherrys8/WCS`。全仓 `wekit` 残留只剩 `WeAgentDatabase` 迁移里的 `'WEKIT_ROUTER'`（历史数据值，故意保留）

### In Progress
- 「修改文本消息显示」+ 表情 span 方案**已回退**：`9413da4d`（写回时继承原文的表情 span）装到真机后**模块无法注入微信**，`07fd425b` 单独 revert 掉它，保留可编辑名单 `50b18a27` 与 `#` 分隔符 `99d61e54`。被丢的提交留在本地 ref `backup/dev-sherry-20260927` = `9413da4d`。**未证实是 span 改动导致的注入失败**——若回退包仍无法注入，要往更早查（`b02c2c79` 的 auto-accept/`modifyContactRemark`、上游合并、或设备侧模块开关/版本）
- 豆包后端与魔方切换的真机验证矩阵：豆包生成 → 切回魔方确认音色列表恢复且可生成 → 再切回豆包；魔方若仍失败，需回读 toast 中的 `code=`/`msg` 才能定位是 Key、额度还是音色名问题。CI 侧 `build`/`build_zygisk`/`upload-telegram` 均绿，仅 `dex-test` 的 `Enforce Dex resolution result` 为分支既有失败（1 个 `UNEXPECTED_FAILURE` + 3 个 `BLOCKED`），本轮未改任何 Dex 声明，按约定不追
- 「修改文本消息显示」待真机收尾：① 确认 `99d61e54` 的名单**真的开始屏蔽**了（`e4053af2` 那版因完整类名 + `.` 写法对嵌套类从不命中，等于完全没生效），行集正好、引用段仍可改；② 弹窗底部诊断区块已随 `e4053af2` 移除，`buildSubtreeDump` 只写 `WeLogger.i`（收集新宿主仍可用）；每行的 `类名#id` 标注暂时保留——它就是屏蔽键的可抄来源，等用户确认名单够用后再删，同时删掉已无引用的 `chat_modify_text_diag` 三语言串；③ 菜单正文自身若被写进名单，该条消息会退化成「这条消息没有可修改的文本」toast，需确认是否预期；④ 验证滚动/重进聊天/切会话后替换仍生效，以及「恢复原文」干净

## Key Decisions
- 设置页使用弹窗（`showComposeDialog`）而非独立 Activity
- 音乐 API 直连第三方，不再经过 Wex 密钥分发层
- 日历卡天气功能完全移除
- 网易云音乐改用 `ffapi.cn` 单接口，搜索 `?msg=&limit=20` → `data[{n, title, singer, pic}]`，选歌 `?msg=&n=index` → `data{id, name, singer, pic, url, lrc}`，歌词 `?act=lrcgc&id=ID` 返回 LRC 纯文本，播放 URL 直接传给 `MediaPlayer.setDataSource()`
- 汽水音乐改用 `api.cxzja.cn`，需要 Token 认证，搜索 `?token=&msg=&n=` → `data[{num, identifier, song_name, singers, album_cover}]`，详情 `?token=&msg=&n=num` → `data{download_url, lyric, ...}`
- 三卡默认关闭，减少初始干扰
- 豆包只作为 A 套 `TextToSpeech` 的可切换后端，不并入 B 套语音面板的 `TtsMode`（系统 TTS / Edge TTS / 克隆语音）
- 豆包切换保持弹窗布局完全不变，只换数据来源；语气与自定义音色在豆包模式下禁用而非隐藏（用户明确要求不换布局）
- 文字转语音两个菜单入口默认关闭，由用户在功能设置弹窗中自行开启（老用户升级后气泡菜单会先消失，属预期）
- A 套弹窗新增文案沿用硬编码中文，本轮不做三语言资源化（用户选择「只新增本次需要的字符串」）
- 本地改显示文本要「持久」就用**重绑重放**（`ICreateViewListener.onCreateView` + 按原文匹配），不去 hook 全局 `TextView.setText` 改 `args[0]`：后者每次渲染全进程生效，会污染宿主自身绘制且难定位
- 「修改文本消息显示」的可编辑行按**「`id` 或 `类简单名#id`」黑名单**筛选（存偏好、设置页可增删），而不是按类名/文本形态猜：昵称、时间等同为 TextView，只有资源 entry name 能稳定区分该不该改。试过 id 白名单（`ce83340b`）→ 完整类名 `@` 黑名单（`e4053af2`，因嵌套类 `javaClass.name` 用 `$` 而从不命中）→ 现方案；分隔符用 `#` 是因为真机布局检查器这样显示，输入端 `@` 也兼容
- 该功能的名单**同时约束**菜单自身 View 的「字段 + setter」通路（白名单时代刻意不约束，怕纯文本/拍一拍退化），代价是正文 View 命中名单时整条气泡变成「无可编辑文本」
- CI 只构建 standard flavor（用户要求省时间），legacy 需要时本地单独构建，不再为 PR/推送兜底双包

## Next Steps
1. 群聊分析 ComponentActivity 全屏切换已提交，待 CI 构建验证（本地不构建，CI 负责编译）；真机验证长按菜单 → 全屏界面、状态栏着色、键盘弹起（ADJUST_RESIZE）
2. 文字转语音豆包后端：`1d2f285a` 已推送且 `build` 绿，待真机跑完「豆包 → 魔方 → 豆包」往返验证（音色列表、自定义音色、生成结果）；若魔方 toast 带出 `code=`，据此决定是否补错误分型
3. 豆包未做客户端节流：约 6 次快请求即触发 `710022002` block，如实际使用中频繁撞墙，再补最小间隔与冷却提示
4. 「修改文本消息显示」可编辑屏蔽名单 `50b18a27` + 分隔符 `#` 的 `99d61e54` 已推、CI 均绿：待真机确认名单确实生效且行集干净，再按 In Progress 条目删掉每行标注与失效的 `chat_modify_text_diag` 三语言串

## Critical Context
- **WCS 改名留下的运行期耦合（真机/资产侧，CI 绿了也不代表没事）**：
  - `applicationId` 由 `dev.ujhhgtg.wekit` 变成 `dev.sherry.wcs`（`libs.versions.toml` 的 namespace 就是 applicationId）→ 装上去就是**另一个应用**：MMKV 偏好、Room 库、LSPosed 模块开关与作用域、通知渠道全部不继承，必须先重新启用模块并重新配置
  - `wcs-zygisk/template/module.prop` 的 `id=wcs_zygisk` → KernelSU/APatch 里是**新模块**，旧的 `wekit_zygisk` 条目不会自动消失，需手动卸载，否则两个模块同时注入
  - **下载源现状（`sherrys8/WCS` 已转 PRIVATE，匿名取不到任何 release 资产，实测 404）**：扩展包索引 `ExtensionPacks.kt` 与云端 Dex 报告 `CloudDexResolver.kt` 改为指向**公开的 `sherrys8/WeKit`**（实测 `Extensions/manifest.json` 与 `Dex-Test/wechat-8.0.69-3040-domestic.json` 都是 200）；应用内自动更新（整个 `utils/AppUpdater.kt`、设置页「检查更新」行、Nuke 的 `NukeDestination.Update` 页面）与关于页 GitHub 贡献者列表（`activity/nuke/NukeGitHubContributors.kt` + developers 区块）**已删除**，产物只经 Telegram 下发
  - 但公开 fork 里那份资产是**改名前**构建的，能下载却不匹配：包体内部是 `wekit/` python 包、`libwekit_cloudflared.so`/`libwekit_llama.so`、`script-deps` dex 里的旧类名，而 WCS 代码找的是 `wcs/__init__.py`、`libwcs_*.so`（`PythonRuntimeArchive`/`PythonRuntimePack`/`CloudflaredPack`/`LlamaNativePack`）；Dex 报告更直接——它以我们自己的 feature 类全限定名为键（实测 133 处 `dev.ujhhgtg.wekit.features.*`），methodHash 也因 resolver 源码改动全变，导入匹配不上，只会安静地回落到本地解析。要用上扩展包必须由 WCS 自己 `publish_extensions=true` 重打并重传（`archlinux-arm64` 与模型权重这类不含我们标识的包可以直接用，`cloudflared`/`llama-native`/`python-runtime`/`script-deps` 不行）
  - `PythonPluginManifest.minWcSVersionCode`（原 `minWeKitVersionCode`）是**序列化键**，而 `PythonPluginManager` 的 Json 配置是 `ignoreUnknownKeys = false` → 用户手写的旧 `plugin.json` 现在直接解析失败（不是降级，是抛错）。要兼容只需给该字段加 `@SerialName("minWeKitVersionCode")`
  - 写进宿主查询/共享存储的字符串也换了前缀：聚合会话的 `wcs_folder_`（`ConversationAggregation.FOLDER_PREFIX`、`ConversationGrouping`/`HideContactsSql` 的子串守卫）、插件目录 `_wcs_plugins`、`Downloads/wcs/`、`filesDir/wcs-extensions/`、日志目录 `Android/data/com.tencent.mm/WcS/logs/`、logcat tag 恒为 `WcS`。旧的对应目录/记录不会被新构建识别，需真机逐项确认
  - 唯一**故意没改**的品牌串：`WeAgentDatabase` 迁移 SQL 里的 `'WEKIT_ROUTER'`——那是历史数据值，改名会让迁移指向一个从未写入过的值
- 本克隆 `origin` = `https://github.com/sherrys8/WeKit.git`（**远端仓库名至今仍是 `WeKit`，改名只发生在分支与工作树内**），`dev-sherry` 最新 commit：`07fd425b`（revert 表情 span；备份 ref `backup/dev-sherry-20260927` = `9413da4d`，更早的远端备份分支 `backup/dev-sherry-20260926` = `b02c2c79`）。另有**分叉的** `sherrys7/WeKit:dev-sherry`（不是本克隆的 remote，需 `git fetch <url> dev-sherry` 才能比对），2026-09-26 实测它比本地多 12 个提交、本地多 147 个，且 sherrys8 对其只有读权限，**不要**把它的 SHA 当成本分支的远端状态
- **微信气泡文本宿主分布（真机 dump 结论，做同类功能必读）**：
  - `com.tencent.mm.ui.widget.MMNeat7extView` 是自绘文本 View（微信把 `TextView` 的 `T` 换成 `7`），**既不是 `TextView` 子类也不是 `ViewGroup`**，`is TextView` 与按名含 "Text" 匹配都拿不到它；判定规则用「类名去掉数字位后含 `extView`」。它的文本在自己的 CharSequence 字段上，写字段 + `invalidate()` **确实会重绘**（文件卡片 `<title>` = `id=bju` 已验证）
  - 文件卡片：文件名 `MMNeat7extView id=bju`，大小 `MMTextView id=bj2`
  - 长按菜单回调给的 `args[1]` **可能只是正文叶子 View**（引用消息即 `MMNeat7extView id=bkl`，其子树就它一个），引用块等同行兄弟 View 不在其中；要覆盖整条气泡必须向上爬到「行根」（父级是 `AdapterView` 或类名含 `RecyclerView` 即停，限 6 跳），且**编辑与重绑重放要用同一个根**
  - 反射父类字段若不在 `android.`/`androidx.` 处停下，会扫到 `android.view.View` 自带成员（`handleResultReason` 值就是 `handled by onTouchEvent`、`mContentDescription`、`mTransitionName`、`mAllowedHandwriting*PackageName` 等），造出假的可编辑行，`mContentDescription` 还会与正文重复
  - 纯文本、拍一拍（PAT）气泡的正文**不在子 TextView 上**，而在条目 View 自己的 CharSequence 字段 + 同名 setter 上；只遍历 TextView 会静默改不到它们
- **日志与产物获取**：`WeLogger` 的 logcat tag 恒为 `WcS`（`BuildConfig.TAG`），功能名只是消息前缀（`Log.i(TAG, "$tag: $msg")`），按功能名过滤 tag 搜不到；文件日志在 `/storage/emulated/0/Android/data/com.tencent.mm/WcS/logs/wcs-<日期>.log`。本分支 APK 只经 Telegram 下发，GitHub 上没有 "CI" release
- 豆包 TTS 参考实现在仓库根的 `doubao-tts/`：它是**未被本仓库跟踪的嵌套浅克隆**（自带 `.git`，源 `https://github.com/sherrys7/doubao-tts`，仅 1 个 commit），`git add -A` 只会写入 gitlink 而不会收进文件内容；其中 `_decode.py`/`_serve.py`/`doubao-voice-audition.js` 连内层仓库也未跟踪（本地自写的试听面板与 ADTS 帧校验工具）。实测约束：跨域 `Origin` 会被握手层直接拒（close 1006，症状酷似风控，但原生客户端自设 `Origin` 头不受此限）；约 6 次快速请求触发服务端 `710022002` block；`speech_rate` 量纲未验证，故 WcS 侧固定传 0；README 自述为逆向工程、仅供学习研究，失效时先看 Cookie 是否过期（约 30 天）
- 豆包 Cookie 是等价于账号登录态的凭据（`sessionid`/`sid_guard`/`uid_tt`），存于 `WePrefs` 的 MMKV 明文偏好中，无 cryptKey；设置项以 password 模式输入，日志不打印其内容
- 本仓库提交身份是**仓库级**覆盖：`sherrys8 <323482072+sherrys8@users.noreply.github.com>`；全局 `~/.gitconfig` 是 `sherrys7` / `sherrys7@users.noreply.github.com`（2026-09-26 实测，旧述的 `sherrys7@qq.com` 已过期）。**推送凭据**：github.com 被全局 `credential.https://github.com.helper` 指定为 `gh auth git-credential`，即走已登录的 **sherrys8** gh token；系统级 `credential.helper=manager`（GCM）只对其它主机生效。另一克隆 `D:\1\ZcodeData\dev-sherry\repo` 配的是 sherrys7 + 公司域名邮箱 `monkeycode-ai@chaitin.com`，公开历史中已出现上百次，未做处理，提交前先看 `git config user.email`
- 本分支 `dex-test` job 的 `Enforce Dex resolution result` 长期失败（1 个 `UNEXPECTED_FAILURE` + 3 个 `BLOCKED`），`build`/`build_zygisk`/`upload-telegram` 正常；纯逻辑改动撞到这个红叉不必追
- 网易云 API：`FFAPI = "https://ffapi.cn/int/v1/dg_netease"`
  - 搜索 `GET ?msg={keyword}&limit=20&format=json` → `data[{n, title, singer, pic}]`
  - 选歌 `GET ?msg={keyword}&n={index}&format=json` → `data{id, name, singer, pic, url, lrc}`
  - 歌词 `GET ?act=lrcgc&id={id}&format=json` → LRC 纯文本
  - 播放 URL 取 `data.url` 直接传给 `MediaPlayer`
- 汽水音乐 API：`URL_QISHUI = "https://api.cxzja.cn/api/qishuimusi"`
  - 搜索 `GET ?token={token}&msg={keyword}&n=` → `data[{num, identifier, song_name, singers, album_cover}]`
  - 详情 `GET ?token={token}&msg={keyword}&n={num}` → `data{download_url, lyric, song_name, singers, album_cover, identifier}`
  - Token 存于 `home_qs_token` 偏好，设置弹窗中密码模式输入
  - 无 Token 时搜索返回空 `[]`
- 三卡默认全部关闭，需在设置中手动开启
- API 无账号认证，无法播放会员歌曲
- `httpGet` 已添加 `User-Agent` 和 `Accept` 请求头

## Relevant Files
- `.../home_page_cards/HomePageCards.kt`: 主入口，`ClickableFeature`，弹窗设置含子卡片开关/顺序/字体颜色/背景/汽水音乐 Token
- `.../home_page_cards/HpcMusicCard.kt`: 音乐播放器引擎，API 直连 ffapi.cn（网易云）+ cxzja.cn（汽水音乐）
- `.../home_page_cards/HpcMusicPanels.kt`: 搜索/详情/收藏/历史/定时关闭面板 UI，含平台选择（网易云/汽水音乐）
- `.../home_page_cards/HpcCalendarCard.kt`: 日历卡，读取 5 组字体颜色偏好，无天气
- `.../home_page_cards/HpcCardManager.kt`: 按 `home_cards_order` 顺序注入卡片
- `.../home_page_cards/HpcMediaNotification.kt`: 系统通知栏 + MediaSession 控制
- `.../home_page_cards/HpcFloatLyric.kt`: 桌面悬浮歌词
- `.../home_page_cards/HpcImageCard.kt`: 图片卡，支持自定义背景图
- `.../chat/GroupChatSummary.kt`: 群聊智能分析，全屏界面（`fullScreen` 弹窗）+ 流式报告（`onDelta`）+ 底部 2x2 操作区；`generateReport`/`aiGenerateReport` 走 `AiModelConfig` 四参数配置
- `.../chat/AiModelConfig.kt`: 四参数配置（`baseUrl`/`apiPath`/`apiKey`/`modelId`）持久化到 MMKV，`resolvedBaseUrl()` 拼接；provider 固定 OpenAI Chat Completions
- `.../chat/TextToSpeech.kt`: 文字转语音 A 套，`ClickableFeature` + 双入口开关（`tts_entry_bubble`/`tts_entry_input_bar`，默认关闭）；魔方（HTTP REST + Bearer Key）与豆包（`generateVoiceDoubao()` WebSocket + 登录 Cookie → ADTS AAC）双后端，`tts_backend` 切换、音色各自记忆，`showSettingsDialog` 内配置 Key 与 Cookie。入口分别走 `WeChatInputBarMenuApi`（长按加号/发送按钮，注册见 `ChatFooterHooks.kt`）与 `WeChatMessageContextMenuApi`（长按消息气泡）；B 套语音面板的 `TtsMode` 在 `ui/panel/VoicePanelTtsContent.kt` + `VoicePanel.kt`，与本文件互不相干
- `.../ui/utils/ComposeUtils.kt`: `showComposeDialog` 新增 `fullScreen` 参数（窗口 MATCH_PARENT）
- `.../chat/ModifyTextMessageDisplay.kt`: 修改文本消息显示，`ClickableFeature`（点击功能行 = 屏蔽名单设置弹窗）+ 长按菜单 provider + `WeChatMessageViewApi.ICreateViewListener`；`TextTarget` 三态（TextView / 自绘宿主字段 / 菜单 View 自身字段+setter）、可编辑屏蔽名单（偏好 `modify_text_display_host_blacklist`，键 `id` 或 `类简单名#id`）、偏好 `modify_text_message_display_overrides` 存「原文→替换」并在每次绑定重放；弹窗底部诊断区块已移除，`buildSubtreeDump` 只写 `WeLogger.i`；每行的 `类名#id` 标注仍在（屏蔽键的可抄来源，待删）
- `.../features/api/ui/WeChatMessageViewApi.kt`: 消息 View 绑定监听（`onBindView`/`onViewRecycled`），新增 `getMessageOfView(view)` 供监听方按 View 取 `MessageInfo`
- `.../ui/utils/ViewUtils.kt`: `allViews`（前序 DFS，含自身）与 `findViewsWhich`/`findViewWhich`，遍历气泡子树统一走这里
- `.github/workflows/ci.yml`: CI 配置，含 `upload-telegram` job；`build` 已改为只编 standard flavor
