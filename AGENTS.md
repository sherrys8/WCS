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
- **界面资源只有两种语言**：`values/`（英语源）与 `values-zh-rCN/`（简体中文）。不要再建繁体目录——
  `xtask/src/i18n_check.rs` 把任何其它 `values-zh*` 目录判为 "unexpected Chinese resource directory"，
  `app/build.gradle.kts` 的 `localeFilters` 也只留 `zh-rCN`。`LanguageSelection`/`SupportedLocale`
  枚举只有 跟随系统/英语/简体中文/喵体中文；任何中文系统区域（含 Hant 与 TW/HK/MO）都解析为简体中文
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
- 仓库守卫 `github.repository == 'Ujhhgtg/WeKit-Dev'` 在 `a5a5c390` 统一改成 `sherrys8/WCS`。**新仓库 `sherrys8/WCS` 建好时是 PRIVATE**（2026-10-19 实测已转 **PUBLIC**：`gh api repos/sherrys8/WCS` 的 `private=false`、匿名拉仓库主页 200；它仍然**一个 release 都没有**，所以 `releases/download/Extensions/manifest.json` 匿名 404 是「无 release」而非「不可见」，别拿这个 404 当私有性的证据），所以守卫在它的 run 里真的会命中——实测代价：改名后的 push 让 `dex-test` 跑了起来（单 job 28m40s，与 `build` 并行），`extensions-changes` 也随之命中并拉起 `build-extensions`（下载约 792 MB Arch rootfs）。现在两道都收紧了：`dex-test` 只在 `pull_request` 跑；`extensions-changes` job **已删除**，`build-extensions` 改成 `if: github.event_name == 'workflow_dispatch' && inputs.publish_extensions`，要发新扩展包必须显式 `gh workflow run ci.yml -f publish_extensions=true`（并且需要新仓还没有的 `RELEASE_TOKEN` secret）。另外 `paths-ignore` 加了 `.github/**`，**只改 workflow 文件的 push 不再触发任何 action**
- `build`/`upload-telegram` 只看 `refs/heads/*`；`release-ci` 与 `release-dex-test` 还额外要求 `refs/heads/master`，而 `sherrys8/WCS` **没有 master 分支**，所以不会发布 release
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
- **删除「清理缓存垃圾」功能**（`features/items/system/AutoCleanCache.kt`，`ClickableFeature`，technicalId「清理缓存垃圾」）：它自带 30 分钟轮询协程，`deleteRecursively` 删宿主 `cache`/`appbrand`/`liteapp`/`tinker*`/`MicroMsg/crash` 与外部存储的 `cache`/`xlog`/`onelog`/`tbslog`/`tbs_*_log`。代码里**没有任何外部引用**（功能靠 KSP 从源码发现，删文件即下线），连带删 4 条字符串（`feature_auto_clean_cache_name`/`_description`、`system_auto_clean_next`/`_complete`，两语言）与 `docs/features/system/auto-clean-cache.md` + `docs/SUMMARY.md` 条目。`formatBytesSize`/`formatEpoch` 仍被别处使用所以保留；`SYSTEM_PRIVACY` 分类删完还剩 28 个功能不会空。旧设备上 MMKV 里那条开关值成为无人读取的死键，按仓库惯例不做迁移
- **语言裁剪为「英语 + 简体中文」**：删除 `values-zh-rTW/strings.xml`（唯一繁体资源文件）、`values/strings.xml` 里的 `language_traditional_chinese`、`LanguageSelection.TRADITIONAL_CHINESE` 与 `SupportedLocale.TRADITIONAL_CHINESE`；`LocaleResolver.mapSystemLocale` 把 `"zh"` 一律映射到简体（原先按 Hant / TW / HK / MO 分繁体），所以繁体设备升级后仍看中文而不是掉回英文。设置页与 Nuke 的语言选项列表各去掉繁体项，`app/build.gradle.kts` 的 `localeFilters` 只剩 `zh-rCN`。同步：`xtask/src/i18n_check.rs`（目录守卫不再豁免 rTW、目标清单只剩 rCN、4 个单测的 fixture 与断言重排）、`LocaleResolverTest`（删繁体断言，新测试 `allChineseSystemLocalesResolveToSimplifiedChinese` 覆盖 9 个中文 tag）、`docs/development/i18n.md`、`docs/translations/{index,TRANSLATORS,GLOSSARY}.md`、`docs/module-settings.md`
- CI 单 flavor 化 `684cb3d2`（见 CI 节）；备份分支 `backup/dev-sherry-20260926` = `b02c2c79` 已推 `sherrys8/WeKit`（远端仓库至今仍叫 `WeKit`；`backup/*` 不在 CI 分支过滤内，不触发构建）
- **WCS 分支改名（彻底换皮）** `478398df`→`0d6a6589`→`a5a5c390`→批次 4：包名 `dev.ujhhgtg.wekit` → `dev.sherry.wcs`，品牌按 `wekit`→`wcs`、`WeKit`→`WcS`、`WEKIT`→`WCS`、`weKit`→`wcS` 四条规则全量替换。改动面：7 棵源码树 git mv（app main/standard/test、annotation-scanner src+bin、python-runtime-api、python-runtime runtime/kotlin+python 包、stubs）+ `app/schemas` 目录；`libs.versions.toml` 的 namespace 同时是 applicationId；两个 xposed 入口清单与 KSP services 文件；keep 规则；manifest taskAffinity；crate `wcs-native`/`wcs-llama`/`wcs-zygisk`、`libwcs_*.so`、go 模块目录、python 容器包 `wcs/`；xtask 与 `verify-dual-apk.py`；ci.yml 的 artifact/keystore/缓存路径与 job 守卫；`module.prop` 的 id/name/author；四个下载源与展示跳转 → `sherrys8/WCS`。全仓 `wekit` 残留只剩 `WeAgentDatabase` 迁移里的 `'WEKIT_ROUTER'`（历史数据值，故意保留）
- **短视频无水印解析（ParseVideo）** 自 `4c49acff`（2026-09-08 移植）到 `588f0e2f`（2026-10-15），横跨改名前后：解析结果 UI + 自动解析白名单（`7210819d`，起初只匹配抖音正则，`cc489179` 扩到小红书）、图集解析（`e0f67234`，视频/图片自动发送）；小红书第三备用线 `c49d67af`→`4d4eb13e`（直连 dovis，不再经前两条逐级回退）/`854d353d`（imgurl 字段映射与 UA，去外层引号），后在 `38870fa9` 整条删除；弹窗右上角线路切换（`5ab3114d`，偏好 `parse_video_route`）；混合内容两段式布局（`5f2693c2`/`9ab09f75`：图集在上、视频在下，各带下载按钮）；备用线路多视频直链全选下载（`e2885809`）；群聊自动回复全量发送（`81f366d9`：多视频直链与图集全部发出，单条失败跳过）；主线路 pid 1~7（`0e632631`，偏好 `parse_video_pid`；封面与图集重复只留图集）；备用线路 `video_cover` 充图集（`65a1140e`）；发送临时目录改外部存储 `Download/WeKit/ParseVideoTemp`（`f44af801`）；补移动端浏览器请求头过 51web 来源打分（`0232f24b`）；主线路 token 改用户自带（`c470a33a`，偏好 `parse_video_primary_token`，空则跳过主线路）；设置弹窗按 M3 分组重排、白名单为空 = 全不解析（`89a13d4f`）；底部按钮改 FlowRow 修文字竖排（`a12ccfc1`）；归入娱乐分类（`4ae8919d`）；**线路最终形态** `38870fa9`——主线路换域名 `dy.51web.eu.org`→`api.51web.eu.org`（旧域名 TLS 已死）、删小红书、备用线路 1（kit9 聚合）/2（bug 抖音原画）/3（bug 聚合，需 API Key，未填自动跳过）；`588f0e2f` 补齐图集/实况——`coerceInputValues` 容忍 JSON null、`live_photo` 按独立视频并入「全部下载」、`hasVideo()` 判「上图集下视频」分区、档位名带分辨率与体积、备用 2/3 补回封面
- **品牌与上游身份清理** `336cfb2e`/`c40888c0`（2026-10-01）：改名四条规则不含 ujhhgtg，这批是漏网的上游身份——删 `.github/FUNDING.yml`、README 爱发电徽章与「联系」链接、Nuke 关于页「赞赏我们」行（ifdian.net/a/ujhhgtg 是活链接，等于在 WCS 里替上游收钱）；删设置首页「了解更多」整组（唯一入口 docs.wcs.ujhhgtg.dev 域名 NXDOMAIN，App 内是死链）；`module.prop` `author=ujhhgtg`→`sherrys8`、`app_description`/`module_app_about_authors` 署名 Ujhhgtg→sherrys8 并去 cwuom 联署、`MonetModulePackager` 硬写 author 同改；删 `docs/wrangler.jsonc` 与文档站 Workers 自动构建段（生产域挂上游主域，且 Production branch=master 本仓不存在）；中英两侧同键成对删改，保持 i18n-check 通过
- **早期环境隐藏统一** `1697cba3`（2026-10-04）：从 wekitpatch 分支（基线 `3a89b3d8`）按 tip 整棵移植 `932e23d8..cff83f39`（含 install 失败不打断宿主启动、afterNativeLoad 可空两处修复）；品牌字面量逐条对齐本分支出货名——`filesDir "wekit"`→`"wcs"`、`libwekit_native.so`/`libwekit_zygisk.so`→`libwcs_*`、测试夹具的模块包名与 files/ 子目录同批改（否则 assertFalse 恒真成假通过）、删无人创建的 `/memfd:wekit-inline-hook`（本仓只创建 `wk` 与 `jit-cache`），`/memfd:wk` 与 `WKCD0001` 魔数保持不动；三个 SwitchFeature 并入常驻安装后不再有开关，旧 MMKV 键成死键不迁移，连带删 7 条字符串与 3 篇文档；113 个测试夹具与 413880 字节 tables 资源逐 blob 比对与源分支一致（SHA-256 等于 `EnvironmentHider.kt` 常量）
- **自定义头像改本地资产存储** `94ef4f7a`（2026-10-05）：原实现只在 `custom_avatars_map.json` 存 content:// URI，相册原图删除/媒体库重建/微信重装即静默失效；拆出 `CustomAvatarAssets`，导入时复制进 `moduleAssets/custom_friend_avatars`——`.raw`（50MB 闸门）→ `inJustDecodeBounds` 探边界 → 按 EXIF 算真实宽高 → 5000 万像素与 100:1 比例闸门 → `inSampleSize` 有界解码（长边 ≤1024）→ Matrix 把方向烘进像素 → 居中裁方 → 含 alpha 走 PNG 否则 JPEG 95 → `.part` + `fd.sync` + `ATOMIC_MOVE`；换/删头像改「先提交映射、再删被替换的资产」；读侧 `file(value) ?: value` 兼容未迁移旧值；onEnable 只在主进程做一次性迁移，导入挪到后台线程；删 `persistReadPermission`，新增 5 条导入失败分型文案
- **「AI回复候选」** `b244e42d`→`9851e13f`（2026-10-07~10-11）：合并上游两套回复功能（AI 聊天助手 + 帮我回），18 语气预设、候选默认 3 条；`a7f1de29` 候选弹窗布局收紧、API 配置与群聊分析拆开（`AiApiConfig` 拆出 `ai_reply_cand_*`，首次打开从群聊分析继承一次）；`08ea9a40` 原文改引用条 + 300 字截断 + 展开收起、语气并入标题行；`fe0c3245` 修好展开按钮永不出现的溢出判据（`lineCount >= 4 && isLineEllipsized`）并收窄标题点击区；`b308ed42` 模型名改选择弹窗、支持在线拉取模型列表；`321b9c2c` 两处下拉改从手指按下处弹出；`f900bf06` 接入语音回复（凭据与音色独立一套 `ai_reply_tts_*`）；`9851e13f` API 四行补组标题
- **二维码记录（QrCodeRecord）** `9b3030c7`→`86290941`（2026-10-08~10-10）：打开改走微信原生识别流程（`WebviewScanImageActivity.handleCode`）并记录 codeType，`593334db` 按内容识别 12 种码类型；记录列表改独立设置页（`f114c18e`：200 条上限、1.5s 去重、首页快捷入口）；回放几次翻车——`1cf27f27` 因 `plugin.qrcode.model` NPE 撤掉回放（两个打开入口只给 http(s)），`5a1dee91` wxp:// 归回收付款码、codeType=0 不回放，`52ee611f` 换外部实现验证闪退是否移植引入 → `1ebcc453` revert 回上一版并删 handleCode 探针；`23901288` 改反射直调 `handleCode`（按参数表填位置）→ `5ac58def` 修 invoke 少写展开运算符（16 个参数被当成 1 个传），真机可用；`06f46878` 分类认宿主码类型 + 全部/自动分类/收藏三视图 + 搜索 + `qr_code_record_rules` 规则（⋮ 菜单），`ff232b7a` 自动分类改可展开组卡，`86290941` 组内记录并成一张卡、组头染莫奈主容器色；`95ba6e9e` 规则弹窗显式接 `onDismiss`
- **语音合成试听与豆包在线音色选择器**（2026-09-30~10-14）：`4f80b522` 填完魔方 API Key 立即重拉音色列表；`ec5b04eb`/`a3e3177a` 音色下拉改为点外面不关（只认选中项或再点一次行）、滚动保住圆角、外部点击行为拆成卡片与弹窗两层（`directlyDismissable`→`dismissOnTouchOutside` 拆分；`dismissOnClickOutside` 开关）；`dc6e8487` 合成与音色拉取参数化、开放试听与发送入口；`d30cb80b` 豆包音色改联网选择器（拉 373 条、`POST /alice/user_voice/recommend`、试听链约 6~7 天过期且不做自动刷新）；`69588643` 真实头像 + 英语分类；`a34c5695` 内置豆包音色去掉与线上重名的桃子；`8ccea297` 头像缓存与临时音频移到模块外部缓存目录 `cache/WcS/doubao_avatars`；`50fc40a9` AI回复配置接入同一选择器
- **朋友圈发布通知 + 自动保存朋友圈**（2026-10-11~10-12）：`1343ac46` 新增朋友圈发布通知（监听 `SnsInfo` 入库、22 类内容类型）；`a07962ef` 三行模板直接印出默认文案；`0254843b` 修正通知落点类名、新增两个互斥落点开关（`ImproveSnsTimelineUI`/`SnsUserUI`）；`e0cf4a11` 可用变量提示改非组合 `localized`（`joinToString` 里不能调 `stringResource`）。autoSaveMoments 修复批次：`8d87927c` 按魔数补文件后缀、实况图改用 Live Photo 命名；`1a31f795` 配置写入后立刻同步界面状态；`2151d9e1` 补存扫描改用朋友圈库 + 落盘校验（防假成功永久跳过）；`2fc788e1` 刷到时保存注册滚动监听（`startAutomation` 缺失，WHEN_SEEN 路径原本全死）、媒体失败不再被文案判成成功；`1edf8ef5`/`beeff5e1` 清空已保存记录的入口从两处收敛到全局一处、目录子弹窗改为自关
- **10-13 三件事**：`ed63888c` QQ点歌（`QQMusicClient` 只走 QQ 音乐公开接口——搜索/歌词/限免 ppurl/`UrlGetVkey` 兜底，不读写微信账号数据；`QQMusicOrder` 插入监听只认纯文本 30 秒内消息，白名单默认空、开关关闭零拦截；「点歌 歌名」返回音乐卡片或语音；缓存 `Download/WcS/MusicOrder` 超 30 分钟自动清理；`shareMusic` 增补 `songLyric`/`songAlbumUrl`）；`f090f2fb` 点歌白名单可选群聊；`051d6f48` 气泡槽位两格→「类型 × 方向」六格（新增红包含专属/封面、转账；颜色仍只有普通消息可编辑，未导入图片时不改这两类消息）+ 转发修复——语音时长改测解码后的 amr（`voicelength` 是 XML 元素不是属性，原 `extractXmlAttr().toInt()` 必抛 `NumberFormatException`）、新增 FILE 分支（`downloadFile` 取本地路径 + `sendFile` 重发，对方上传中跳过）、`sendTo` 改 suspend、未测类型提示改 `showToastSuspend`（裸 Toast 在 IO 上抛异常，兜底的 `sendXmlAppMsg` 从未执行，名片/位置/链接全部静默失败）；`b7207499` Nuke 分类页显示脚本 (Python) 并重排分类顺序
- **群聊报告长图与表情渲染**（2026-10-14）：`4fc787d2` 长图分块布局对齐屏幕端 `MarkdownText`（baseline、嵌套缩进）；`714dd2a4` 长图改渲染行内 Markdown 样式（不再删符号）；`2837705b` 正文显示微信表情代码对应的 emoji（复制/发送仍原串）；`60100646` `EMOJI_MAP` 补齐 33 条（对照 PC 微信 dump 出的 105 条经典表情表）；`17d2f1a8` 喂模型前把表情代码换成真 emoji 并修代理对截断
- **群成员行为监控** `2536c187`→`9b898444`（2026-10-14）：`SwitchFeature`→`ClickableFeature`，设置弹窗分「会话内提示/生效范围/自动回复」三组、全部即时生效；退群判定不再要求 memberCount 下降，改新旧成员表双向差分（堵同时一进一出的漏判）并支持进群提示，三类事件各自 5s 去重、登录整表重写按噪声丢弃；自动回复以用户身份真实发到群里、默认全关——文本模板/卡片（网页分享伪装，缩略图取对方头像）/两者按选定顺序发，段间固定 300ms；语音可选在提示前后（先复制进模块资源目录再 `anyToSilk`，时长自动取）；变量语法用 `{userName}` 花括号（`%` 会被 Android 资源编译的格式串校验拦下）；修复：`3303e324` 英文文案撇号（aapt2）、`8561f523` 四处编译错误、`adb68fb2` 可用变量提示改 \n 转义、`9b898444` 弹窗关不掉（onDismiss 被同名遮蔽→`dismissParent`）与模板行分组错位
- 杂项（2026-10-01~10-14）：宿主菜单/首页图标修正——`e2b587c1` 等比缩放留一成内缩（不再铺满宿主图标位）、`ee1f68d0` `fakeResId` 强制为正奇数（负号会让微信整块丢掉图标位）、`85c520e8` 扫一扫图标改圆角二维码字形（墨迹铺满 80..880）；`e932653c` 文档增补主题取色接口说明；`c873054c` 新增 `docs/paths.md` 汇总模块读写的目录；`4a443164` dependabot 版本更新改每周日；README 三次清理（`b9a3b13a`/`7fe97bf3`/`6153de0b`）；`84cc3197` 短视频解析与语音试听弹窗放开返回键；`ec32af21` 群聊分析 API 设置保存后行内数值不刷新
- **视频解析弹窗紧凑化** `57001ad2`（2026-10-15，**已被下一条目整体取代**）：标题 24→22sp、齿轮图标 24→20dp + 36dp 盒、九类按钮统一 34dp + `ExtraSmallContentPadding` + `labelMedium`、字段→按钮与按钮→卡片 8→6dp、`textTopSpacing` 12→6dp，六处关掉 M3 的 48dp 最小交互盒。CI 绿（`build` 13m3s）
- **视频解析弹窗三段式重构 + 主线路实况修复** `619e2809`→`29be121c`（2026-10-15）：按已批准的 HTML 稿把 `showParseDialog` 从共享 `AlertDialogContent` 换成本文件内手搓的 `Surface(shapes.extraLarge, tonalElevation 6dp, 外衬 4dp)` **三段式**——头部（标题 `titleMedium+Bold` + 线路药丸 + 右上 ✕）／滚动正文（填充式 `TextField` minLines1-maxLines3 + 尾部粘贴按钮、转换 tonal + 发送 filled 各 `weight(1f)`、加载与错误改容器卡）／吸底操作条（进度条 4dp 全宽贴顶、左状态文字、右**唯一主操作 40dp** + 删除图标）。齿轮与左下角「线路N」两个下拉**合并成一个 `DropdownMenu`**（pid 1~7 内联在选中线路那一行、选完不关菜单）；药丸只显示线路短名（`stringResource(...).substringBefore('（').substringBefore('(').trim()`，中英都适用，且喵化把「喵」插在结尾括号**之前**所以切出来仍干净），**零新增字符串**。结果卡改图集/视频各一张子卡（段头 = 节名 + `检测到图集/视频：N` + 该段自己的下载文字按钮，去掉分割线），档位从 `readOnly OutlinedTextField` 换 `FilterChip`，缩略图 96×128→72×96 且超 4 张在末张盖「+N」蒙层。`29be121c`：粘贴图标 `Content_copy`→`Content_paste_go`（前者两个重叠方框，真机读作「复制」）；填充式 `TextField` 的底部指示器（未聚焦 = `FilledTextFieldTokens.ActiveIndicatorColor` = `onSurfaceVariant`，近黑）按**整宽直线**绘制，容器改 16dp 圆角后线露在圆角外像一道黑条，遂把 `TextFieldDefaults.colors` 的四个 indicator 色设透明（代价：聚焦不再有 1dp→2dp + 变色的线动画，只剩标签浮起与容器变色）。多视频形态按钮文案统一「下载视频」，数量由段头负责说明，`parse_video_download_all_videos_btn` 在本文件不再引用（资源保留，`i18n_check` 不管未使用键）。**逻辑修复**：`parseByPrimary` 补 `downloadAllVideos = images.size > 1 && videoEntries.size > 1`——主线路的实况笔记此前把 N 支独立动图当成同一视频的 N 个档位，下载与自动回复只取选中的那一条，其余静默丢失（真机同一条抖音实况链接复现）。只动 `ParseVideo.kt`，共享件未碰，设置弹窗 `onClick` 仍是 `AlertDialogContent` + `SegmentedColumn`；两笔 CI 均绿
- **备用线路2/3 单条实况动图修复** `7c45dc3d`（2026-10-16）：真机日志 `备用线路3 parse ok, qualities=[实况喵], images=1, live=1` 之后没有 `sending video` —— bug 系线路 `downloadAllVideos = livePhotos.size > 1` 对单动图不置位，于是下游只读 `video_link`，而实况笔记的 `data.url` 是 null（coerce 成空串）→ 一条视频都不发。改为 `isNotEmpty()` + `video_link` 在原画缺失时回落首条动图 + 有动图时不收 `video_backup`（降级档与动图混在一起会让「全部下载」发出重复视频）。三处消费点都带 `&& qualityList.size > 1` 闸，所以单动图正好落到单视频分支、拿到回落值。
- **气泡按压缩放反馈** `1b70c109`（2026-10-16）：把 FkWeChat 的 `StateListAnimator` 方案并入 `CustomMessageBubbles`，新偏好 `custom_bubbles_press_scale` **默认关闭**；挂在 `applyBubble` 开头，覆盖文本/链接/群公告/引用/语音/VOIP，红包与转账走 `applyShapeImage` 天然不参与。`scale = 1 - 0.05 × min(600/height, 0.95)`，按下与回弹各 100ms。相对原件多三条防护：已有原生 animator 不接管、高度未变不重建、`isPressed` 期间不重建（`setStateListAnimator` 会取消正在跑的动画，否则气泡会卡在缩放态），并用 view tag 防同一 View 重复挂 listener。设置弹窗底部一行 `SwitchWidget`，跟随该弹窗草稿语义、点确定才落盘。
- **虚拟定位随机瞬移 + 清除已选位置** `fb6c7c8e`（2026-10-19）：`FakeLocation` 点功能行从「直接选点」改为设置弹窗（两组：定位点 / 随机瞬移），新增 4 个偏好；`fake_jump_enabled` **默认关闭**，判据写在 hook 体内所以切换不必重进微信。随机点**按 location 实例缓存一帧并整体 `synchronized`** —— 微信分两次调用读经纬度，逐次抽取会拼出一个从未取过的坐标；`fake_jump_dwell_s` 兜底同一对象被长期复用；圆内 `√u` 均匀取点、极区 `cos` 有下限保护；`fake_jump_accuracy` 与坐标同帧随机 10–50 m（按 `getAccuracy` 返回类型分别给 int/double）。「清除已选位置」把中心坐标恢复成默认值、已是默认时置灰，坐标行读局部 `remember` 状态所以当场刷新（偏好委托直接读进组合不会自己刷新）。顺手把 `getLatitude/getLongitude` 换成 `firstMethodOrNull` + 拒绝集单次告警。
- **外部件逆向与移植分析（部分未开工）**：`D:\1\ZcodeData\fkwechat`（FkWeChat 1.2.6 + jadx 输出 + `气泡按压动画逆向分析.md`）已落地成上面的按压反馈。两份 Hchat BeanShell 脚本只完成分析、**尚未移植**：`表情转自拍`（借原生 EmojiMix 合成 + 上传，坑在混淆名与上传回调卡锁）与 `表情应用标识`（把表情改走应用表情通道并挂第三方 AppID，属于对外可见的假来源，等用户定合规口径）。共同移植模板：混淆名 → DexKit matcher、Hchat 弹窗 → M3 组件族、catch-all → 显式条件 + 失败放行。
- **@all 与贴纸失败诊断轮（2026-10-21）** `2ff06674`→`24390034`：从设备日志（20337 行，全天只有 12 条 E）判读三件事。① `MentionMembers` 的裸 CGI 522 只注册 `onSuccess`，`WeRequestDsl.onFailure` 没注册 handler 就是**空操作**，所以 `WePacketHelper` 明明算出了 errType/errCode 也被丢掉——先补了 `onFailure` + toast 带码，**真机证明这条通路本来正常**（用户报的是「隐蔽@所有人」），撤销只留了 15 行的删除（`24390034` 的 body 记着这件事，别再去找那段回执代码）。② 贴纸保存失败那句 `failed to decode sticker image` 唯一抛点是我方 `detectImageMime` 的 check，不含任何现场数据 → 先只加诊断（size/head/isWxGF/wxam2PicBuf 判定），读数 `encryptedSize=38053 decodedSize=38053 isWxGF=false decodedHead=77 78 67 66(=ASCII "wxgf") wxam2PicBuf=image/png` 一次排掉「文件没下载」和「解密选错方法」两个叉，坐实是**容器判定**问题 → 同笔把 `wxam2PicBuf` 从诊断升级成兜底（仅当 `isWxGF=false` 且原样也不是图片时才用，成功路径逐字节不变），并记 `animated=`（PNG 里探 `acTL` 块，新加的 `ByteArray.containsSequence` 私有件）回答「动画有没有丢」——这问题已经答完，那对探针属于一次性脚手架，2026-10-22 已删。③ 隐蔽@ 此前**零日志**：`pendingStealthAt` 在 `:255` 是先清标记再比 talker，上次发送失败的残留会被下一条同会话文本吃掉；补 armed / injected / dropped 三行 `WeLogger`。④ 顺带查明 `群聊@所有人通知拦截服务` 那 4 次 `IllegalStateException: Buffer String Length Error` 是 `MessageInfo.kt:90/:100` **我们自己抛的**，被 `BaseFeature.executeHookAction` 兜住 ⇒ 不影响宿主发送，只是该条 @all 拦截静默失效；`error()` 改 `return ""` 与 `bb.short` 有符号负数绕过检查这两处**尚未动**。
- **动画表情 md5 在 `emoticonmd5`** `e8f980c7`→`03279ec6`（2026-10-22，真机已验）：上一轮那条 `failed to decode sticker image` 的现场日志（把 `content` 截断从 120 放宽到 1200 + 补 `imgPath=`）一次就定位到真因——`SO_GOU_EMOJI(1048625)` 且 `field_imgPath=null` 的 appmsg `<type>8</type>` 动画表情，md5 放在 **`<emoticonmd5>`**（`<attachid>0:0:<md5></attachid>` 是同值），既不是 `md5="…"` 属性也不是 `<md5>` 标签（`extractXmlAttr` 的 `\b` 边界也不会匹配到 `emoticonmd5` 里的尾巴），所以 `MessageInfo.stickerMd5` 原有三条回退必然全空。补第四条 `extractXmlTag(content, "emoticonmd5")` 后，长按保存 + 转发 + 复读（共用同一入口）一并修好：设备 `git+03279ec6` 上三条 `I/WcS WeMessageApi: sending registered emoji`、`sticker md5 is null` 不再出现、该时段零 E。改在共享层而不是功能层是因为 `ForwardMessages.kt:173`/`RepeatMessages.kt:118`/`ViewStickerAsImage.kt:141`/`NotificationsEvolved.kt:390` 都在读同一个字段，它们此前对这类表情同样静默失败。
- **隐蔽@改为 522 原文发送，废弃入库注入** （2026-10-22，接 `3013aa1e` 之后的真机结论）：`@所有人` 文件头的三条通路收敛成一条 CGI——默认正文拼 `@昵称 `（可见@）、隐蔽正文只发原文、卡片壳 type=49；atuserlist 一律写在请求自己的 msgsource（`NewSendMsgItemProto` 第 6 字段）里，不再碰入库。删掉的东西：`pendingStealthAt` 标记、`MsgSourceHelper` 合并方法的 `dexMethod`（该对象因此不再 `IResolveDex`）、`methodMsgInfoHandleApiInsertMessage` 那个 `hookBefore`，以及 armed/injected/dropped 三行日志（理由与证据见 CC「不要再往入库注入方向走」）。顺带把 `MAX_AT_USERS=200` 的截断统一到可见/隐蔽共用（原先只管 stealth），并让 `atNicknames` 只在可见分支里算（不再白拼）。同一轮修掉 `MessageInfo.msgSource` 的 `error("Buffer String Length Error")`（改为无符号长度 + 越界返回空串，见 CC），以及 `BlockAtAllNotifications` 新增一行 `incoming @all msgsource: …` 用于采集原生 @all 的真值。

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
5. 视频解析弹窗三段式 `619e2809`+`29be121c` 已推、CI 均绿，**布局本身真机已确认**（标题/线路药丸/✕/填充输入框/转换发送行渲染正确）：仍待复验 ① 同一条抖音实况链接是否 3 支动图**全部**落盘（`downloadAllVideos` 那处修复的正题）② 圆角输入框下方那道黑线是否消失（`29be121c`）③ 关掉 48dp 触控盒后线路药丸与 pid 小方块难点不 ④ 「下载中」时右上 ✕ 仍可关窗是否符合预期（按原逻辑只在 loading/sending 禁用）
6. 三笔待真机（CI 绿只证明能编译）：`7c45dc3d` 单条实况链接现在是否真发出那条动图；`1b70c109` 按压反馈在长气泡/滚动列表里不掉帧、红包与转账不受影响、开关关闭时零行为变化；`fb6c7c8e` 瞬移下微信读到的经纬度**成对**变化（不是各抽一次的拼接）、停留时间内保持不动、「清除已选位置」当场刷新坐标行。
7. 短视频解析主线路遗留（用户明确「另开一轮」，均**未动代码**）：① `parseByPrimary` 把 `levels=[]`（该线路这次根本没给视频位）当解析成功返回，自动回复因此不会回落备用线路——要区分「解析失败」与「解析成功但没有视频」；② `sendDownloadedFiles` 用 `downloadedFiles.all {}`（`ParseVideo.kt:1281`），第一个文件发送失败即短路、其余文件静默不发；③ 主线路的 `downloadAllVideos` 仍靠 `level` 文案猜哪些条目是独立动图，而文案见过 `[动图N]` 与 `【第N张实况】` 两种且都带残缺括号，需要更稳的判据；④ `parse_video_pid` 对结果无影响，属待删 UI。
8. 两份 Hchat 脚本移植（表情转自拍 / 表情应用标识）分析已完成但**未开工**；后者制造对外可见的假来源，等用户定合规口径再动。Hchat 虚拟定位的三个差距（选点结果解析、坐标合法性校验、appbrand 进程覆盖）同样待用户点单。
9. APK 缩减候选已分层测完（见 Critical Context），等用户点具体哪一项：`packaging.resources.excludes` 三行最便宜且零行为变化；assets 转按需 / proot 链进扩展包 / 图标包 / `kotlin-reflect` 需要真机回归；把已读回执服务端从 `libwcs_native.so` 拆出是独立工程项。
10. 贴纸那条真机读数**已收口**（`git+03279ec6`：保存/转发/复读全部正常，动画走 GIF，见 Done 与上面 WXAM 分工条）。@所有人剩下的：① 入库注入那版隐蔽@已删除、换成 522 正文原文版，待真机确认「正文不带 @ 文本时对方还能不能收到有人@我」；② 已知新缺口——可见@那条**送达但不触发「有人@你」**，需要别人发的**原生 @all msgsource 真值**：新包的 `BlockAtAllNotifications` 会打一行 `incoming @all msgsource=…`，等群里出现一条原生 @所有人 就能照抄补齐（先猜的是补 `<atall>1</atall>`）；③ 待点单：`forwardEmoji`/`repeatEmoji` 的 `stickerMd5 ?: return false`（`ForwardMessages.kt:173`、`RepeatMessages.kt:118`）**完全不打日志**（返回 false 不进 `sendTo` 的 catch），要不要补一行 `WeLogger.w`。原第④项（`MessageInfo.msgSource` 的 3072 上限 + `bb.short` 有符号）已在本轮修掉。
11. 卡片@所有人（偏好 `mention_members_card_all`，**默认关闭**）**未结案**：真机三次（12:47/13:38 自建壳、13:41 与 13:45 用户提供的 `type=19` 模板）都是**服务器接受、无 `cgi 522 failed`、onSuccess 里我们自己插的提示正常入库**，但两端都不显示、也不推 @；而同一段 type=19 XML 走「群发助手」CARD 模式（宿主 AppMsgLogic）能正常渲染 → 差异在 522 的请求组装，不在 XML 内容。继续要付的代价：抓一次原生发卡片的出网包做字段对照（临时 hook `a65.f16`）。或者按「送达不渲染」直接把这个开关摘掉——等你发话。

## Critical Context
- **WCS 改名留下的运行期耦合（真机/资产侧，CI 绿了也不代表没事）**：
  - `applicationId` 由 `dev.ujhhgtg.wekit` 变成 `dev.sherry.wcs`（`libs.versions.toml` 的 namespace 就是 applicationId）→ 装上去就是**另一个应用**：MMKV 偏好、Room 库、LSPosed 模块开关与作用域、通知渠道全部不继承，必须先重新启用模块并重新配置
  - `wcs-zygisk/template/module.prop` 的 `id=wcs_zygisk` → KernelSU/APatch 里是**新模块**，旧的 `wekit_zygisk` 条目不会自动消失，需手动卸载，否则两个模块同时注入
  - **下载源现状（当初因 `sherrys8/WCS` 是 PRIVATE、匿名取不到 release 资产而绕行；2026-10-19 实测该仓已 PUBLIC，但它自身没有任何 release，绕行依旧必要）**：扩展包索引 `ExtensionPacks.kt` 与云端 Dex 报告 `CloudDexResolver.kt` 指向**公开的 `sherrys8/WeKit`**（实测 `Extensions/manifest.json` 与 `Dex-Test/wechat-8.0.69-3040-domestic.json` 都是 200）；应用内自动更新（整个 `utils/AppUpdater.kt`、设置页「检查更新」行、Nuke 的 `NukeDestination.Update` 页面）与关于页 GitHub 贡献者列表（`activity/nuke/NukeGitHubContributors.kt` + developers 区块）**已删除**，产物只经 Telegram 下发
  - 但公开 fork 里那份资产是**改名前**构建的，能下载却不匹配：包体内部是 `wekit/` python 包、`libwekit_cloudflared.so`/`libwekit_llama.so`、`script-deps` dex 里的旧类名，而 WCS 代码找的是 `wcs/__init__.py`、`libwcs_*.so`（`PythonRuntimeArchive`/`PythonRuntimePack`/`CloudflaredPack`/`LlamaNativePack`）；Dex 报告更直接——它以我们自己的 feature 类全限定名为键（实测 133 处 `dev.ujhhgtg.wekit.features.*`），methodHash 也因 resolver 源码改动全变，导入匹配不上，只会安静地回落到本地解析。要用上扩展包必须由 WCS 自己 `publish_extensions=true` 重打并重传（`archlinux-arm64` 与模型权重这类不含我们标识的包可以直接用，`cloudflared`/`llama-native`/`python-runtime`/`script-deps` 不行）
  - `PythonPluginManifest.minWcSVersionCode`（原 `minWeKitVersionCode`）是**序列化键**，而 `PythonPluginManager` 的 Json 配置是 `ignoreUnknownKeys = false` → 用户手写的旧 `plugin.json` 现在直接解析失败（不是降级，是抛错）。要兼容只需给该字段加 `@SerialName("minWeKitVersionCode")`
  - 写进宿主查询/共享存储的字符串也换了前缀：聚合会话的 `wcs_folder_`（`ConversationAggregation.FOLDER_PREFIX`、`ConversationGrouping`/`HideContactsSql` 的子串守卫）、插件目录 `_wcs_plugins`、`Downloads/wcs/`、`filesDir/wcs-extensions/`、日志目录 `Android/data/com.tencent.mm/WcS/logs/`、logcat tag 恒为 `WcS`。旧的对应目录/记录不会被新构建识别，需真机逐项确认
  - 唯一**故意没改**的品牌串：`WeAgentDatabase` 迁移 SQL 里的 `'WEKIT_ROUTER'`——那是历史数据值，改名会让迁移指向一个从未写入过的值
- **已删除的界面与随之到期的清理**：应用内更新（`AppUpdater`）、关于页贡献者列表、设置页与 Nuke 关于页的 捐赠/开放源代码许可/GitHub/Telegram 行、`MainActivity` 首页的两张 `LinkCard`，以及**两套开源许可界面本身**（`SettingsPager.LicenseScreen` + `SettingsRoute.License` + `onOpenLicense` 链路；`NukeLicensesPage` + `NukeDestination.Licenses` + `NukeLibraryRow`/`NukeLibraryGroup`/`nukeLibraryAuthor`）。连带删掉 17 条 `licenses_*`/`settings_open_source_licenses_*`/`settings_donate_*`/`brand_github`/`brand_telegram`/`nuke_about_links` 字符串（三语言）。**AboutLibraries 插件与 `R.raw.aboutlibraries` 生成物暂时留着**：现在没有任何消费方，要彻底去掉得动 `app/build.gradle.kts:12,13,320,321` 与根 `build.gradle.kts:6,7` 的插件声明，属于独立决定
- 本克隆 `origin` = `https://github.com/sherrys8/WeKit.git`（**远端仓库名至今仍是 `WeKit`，改名只发生在分支与工作树内**），`dev-sherry` 最新 commit：`07fd425b`（revert 表情 span；备份 ref `backup/dev-sherry-20260927` = `9413da4d`，更早的远端备份分支 `backup/dev-sherry-20260926` = `b02c2c79`）。另有**分叉的** `sherrys7/WeKit:dev-sherry`（不是本克隆的 remote，需 `git fetch <url> dev-sherry` 才能比对），2026-09-26 实测它比本地多 12 个提交、本地多 147 个，且 sherrys8 对其只有读权限，**不要**把它的 SHA 当成本分支的远端状态
- **微信气泡文本宿主分布（真机 dump 结论，做同类功能必读）**：
  - `com.tencent.mm.ui.widget.MMNeat7extView` 是自绘文本 View（微信把 `TextView` 的 `T` 换成 `7`），**既不是 `TextView` 子类也不是 `ViewGroup`**，`is TextView` 与按名含 "Text" 匹配都拿不到它；判定规则用「类名去掉数字位后含 `extView`」。它的文本在自己的 CharSequence 字段上，写字段 + `invalidate()` **确实会重绘**（文件卡片 `<title>` = `id=bju` 已验证）
  - 文件卡片：文件名 `MMNeat7extView id=bju`，大小 `MMTextView id=bj2`
  - 长按菜单回调给的 `args[1]` **可能只是正文叶子 View**（引用消息即 `MMNeat7extView id=bkl`，其子树就它一个），引用块等同行兄弟 View 不在其中；要覆盖整条气泡必须向上爬到「行根」（父级是 `AdapterView` 或类名含 `RecyclerView` 即停，限 6 跳），且**编辑与重绑重放要用同一个根**
  - 反射父类字段若不在 `android.`/`androidx.` 处停下，会扫到 `android.view.View` 自带成员（`handleResultReason` 值就是 `handled by onTouchEvent`、`mContentDescription`、`mTransitionName`、`mAllowedHandwriting*PackageName` 等），造出假的可编辑行，`mContentDescription` 还会与正文重复
  - 纯文本、拍一拍（PAT）气泡的正文**不在子 TextView 上**，而在条目 View 自己的 CharSequence 字段 + 同名 setter 上；只遍历 TextView 会静默改不到它们
- **改名时的第三种包名写法最容易漏**：JNI 导出符号用**下划线**编码包名（`Java_dev_sherry_wcs_utils_AudioUtils_nativeAnyToSilk`，嵌套类是 `_00024Native`），既不是 `dev.sherry.wcs` 也不是 `dev/sherry/wcs`。只改前两种会让 Kotlin `external fun` 在运行时抛 `UnsatisfiedLinkError`，而 **CI 编译期完全查不出来**。现存 47 处分布在 `app/src/main/rust/wcs-native/src/lib.rs`(25)、`wcs-llama/src/lib.rs`(4)、`app/src/main/go/wcs-cloudflared/main.go`(18)。受影响能力：语音转 silk 与时长（`AudioUtils`）、Markdown 渲染、贴纸转换、native 崩溃处理、WeAgent 进程/PTY/本地模型、已读回执服务端与隧道。改完要对照 `git grep -l 'external fun' app/src/main/java` 逐个核类名
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
- **M3 组件 48dp 最小交互尺寸会顶住布局**：Button/IconButton 自带 `minimumInteractiveComponentSize()`，只写 `Modifier.height(34.dp)` 时视觉会缩（内部 `defaultMinSize` 的 40dp 被固定高度压住），但布局盒仍占 48dp，Spacer 间隙改小了也看不出效果。要真正收紧需把组件裹进 `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp)`（先例 `ui/panel/StickerPanelSheet.kt:2173`，公开 API、无需 @OptIn；`Menu.kt` 不用该 Local，DropdownMenu 不受影响），代价是触控区 <48dp。`ParseVideo.kt` 的 `showParseDialog` 在药丸/转换发送行/段内文字按钮/保存封面/底部操作条共 5 处这么做（齿轮已随三段式删除）
- **51web 主线路的 pid 与线路号已不受客户端控制**（2026-10-15 用真 token 连打 14 次实测）：`pid=2/4/11/12/999/abc` 六次响应体 **md5 完全相同**，`isDisclaimer` 里那条状态行自报「当前使用: 线路9／线路11／线路2」随请求轮转、与 pid 无关，且它自报「可用线路: 11」已超出我们「线路N」选择器的 1~7 → **`parse_video_pid` 目前只是把数字写进 MMKV，对解析结果无影响**（UI 仍保留，属待删项）。同一条抖音链接还会漂成三种形态：`3 静帧 + 3 实况动图`、`3 静帧 + 0 视频 + 有标题`（该线路不给视频位，动图整个丢失，客户端无法补救）、`0 图 + 1 原画`；`level` 文案也不统一（见过 `[动图N]` 与 `【第N张实况】`，都带残缺括号）。做解析相关功能时不要假设「一次解析得到一个确定结果」
- **5 条抖音链接 × 4 条线路实测矩阵**（2026-10-16，`D:\1\ZcodeData\pvtest\`，每条间隔 1 分钟、真 token 与真 Key）：`视频 / 图集 / 单实况图 / 多实况图 / 实况+图集` 各一条。结论——**只有 bug 系备用线路 2/3 会给实况动图**（单实况：备 2/3 = 1 视频 + 1 图，主线路与 kit9 = 0 视频 + 1 图，动图整个丢失，客户端无法补救）；kit9 对图集类链接只回 `video_link` + `video_cover` 一套字段，根本没有 `images` 位；主线路在这轮反而表现最好（多实况 2 视频 / 实况+图集 2 视频 4 图，与备 2/3 持平），和上一轮「主线路丢视频」的观察**互相矛盾**，进一步坐实它是上游线路轮转而非代码缺陷。
- **`原画` 直链本身是可用的**（同期实测）：对 5 条链接各线路返回的直链发 `curl --range 0-0` + douyin referer 探测，**14/14 拿到 206 + `video/mp4`**，Content-Range 总长 0.18~1.24 MB。所以上一轮「选原画下回来只有不到 1 KB」不是直链普遍失效，是那一次漂到了不给可用直链的线路；做解析诊断时先重探直链、别先改代码。
- **WcS Crash Report 判锅法**：报告头部 `Module Information / Version Name: git+<sha>` 就是设备上装的**确切提交**，先读它再谈是谁的 bug；判归属只看「Exception Stack Trace」里有没有我们的帧——2026-10-16 那份（`git+1b70c109`）全栈是 `com.tencent.mm.plugin.exdevice.model.o0.k` → `Uri.parse(null)` 加宿主自身的 `r8-map-id-…` 行号映射，**没有一行属于模块**，所以按压缩放那笔不背这个锅。宿主帧的 `Unknown Source` / `r8-map-id` 前缀不代表是我们的代码。
- **APK 体积画像（2026-10-16 首次审计，`7c45dc3d` 的 CI 产物）**：standard release = **12.90 MB / 754 项**。dex 7.03 MB（52%，原始 14.9 MB、压缩率 0.47）、`lib/arm64-v8a` 4.06 MB（30%，`libwcs_native.so` 独占 2.66 MB）、`resources.arsc` 1.09 MB（8%，STORED 压不动、1:1 计入下载）、`res/` 0.60 MB、`assets/` 0.51 MB（`environment/normsg-tables.bin` 413 KB）。三个被测量**否掉**的常见猜想：AboutLibraries 保留类为 0（删它省不了一个字节，AGENTS 里那条「暂时留着」的待办可以就此关闭）、大 assets 全部在用（`eruda.js`/funbox 贴纸快照/`xiaomi_weather.db`）、没有任何「低垂配置」（Rust 侧已 `opt-level=z`+lto+strip+panic=abort、dex 与 .so 都已 deflate、单 ABI、语言只剩 zh-rCN）。可缩候选按风险分层≈1.10 MB（packaging excludes + assets 转按需 + proot 链进扩展包）／0.3~1.2 MB 级（`kotlin-reflect` 1016 个保留类却只用到 stdlib 就有的 `KClass`/`KProperty`；图标包只保留 2 个类但 ~1300 个成员而代码只用 200 个图标；BouncyCastle `pqc` 158 类）／最大的工程项（`wcs-native` 把已读回执**服务端** axum+libsql+tokio 静态链进常驻 .so，`.eh_frame`+hdr 还有 694 KB）；不砍功能的地板约 **8~9 MB**。
- **移植用外部工作区**（都不在本仓 git 里，改代码前先确认路径还在）：`D:\1\ZcodeData\fkwechat`（FkWeChat 1.2.6 APK + jadx 输出 + 气泡按压逆向文档）、`D:\1\ZcodeData\hchat\Hchat-alt-entry`（Hchat 分支，虚拟定位与两套 bsh 脚本插件 API 的出处）、`D:\1\ZcodeData\pvtest`（解析实测数据与 HTML 报告）。Hchat 虚拟定位值得借的三点我们仍缺：四级地址选择器的**结果解析与校验**、`FakeLocation` 无坐标合法性检查（选点失败仍会用默认中心）、只覆盖主进程而**未跟 appbrand**。
- 备份分支 `WCS-backup` 目前**只存在于本地**（2026-10-16 从 tip 创建，未推送、用户未确认是否要推），别把它当成远端状态；远端对照用 `wcs/WCS`。
- **贴纸 / WXAM 三个解码入口的分工（真机定论，修正上一轮的错误推断）**：`MMWXGFJNI.isWxGF(bytes, size)` **不认小写 `wxgf` magic**（真机 `decodedHead=77 78 67 66` = ASCII `wxgf` + `13 00 00 00` 时它给 false），所以只靠它挑路会把这类容器原样送去查魔数而必然失败。但 **`nativeWxamToGif` 是认这个容器的**——上一轮「小写 magic 只有 `wxam2PicBuf` 解得开、`nativeWxamToGif` 无效」是错的：按魔数放行后 `nativeWxamToGif` 直接给出 **`image/gif`，动画保住**（真机三条 1623104 / 167086 / 169853 字节）。`wxam2PicBuf(bytes, 0, WXAM_SCENE_MISC=5)` 也认它，但出的是 **PNG-magic 位图**（动图为带 `acTL` 的 APNG，不是 GIF），因此只当 GIF 通路失败后的第二档；`materializeImageBytes` 与 `decodeAndSave` 用它是因为那两条路本来要位图而非动画。结论：**容器判定别拿 `isWxGF` 当权威**，按魔数（小写 `wxgf`/`wxcf`；`wxcf` 至今无真机样本）放行并优先保动画。另记两处实现约束：① `nativeWxamToGif` 是**可返回 null 的 host `native byte[]`**，`decodeStickerToFile` 现在用 `viaGif?.takeIf { detectImageMime(it) != null } ?: encryptedBytes` 接住——null 或非图片字节都算「这档没解出来」，picBuf 才真能当上第二档；直接喂给非空形参会当场 NPE、跳过兜底，错误还退化成一句没有现场数据的 NPE（失败文本里的 `nativeWxamToGif=null|unrecognized|skipped` 就是这档的判据）。**同一隐患在 `convertWxgfToGif`（表情发送/转换路径）里仍然存在**，它直接 `converted.hasMagic(...)`。② 两档各成功时各打一行日志（`nativeWxamToGif on lowercase container` / `wxam2PicBuf`）用于归因；`animated=` 探针已删，将来要判 APNG 还是静帧 PNG，把 `Downloads/WcS` 里落盘的那个文件 pull 下来看 `acTL`/`fcTL` 块，比日志字段更权威（它看的是真正写出去的字节）。
- **`E executeHookAction: failed to execute hook of <technicalId>` 的正确判读**：`BaseFeature.executeHookAction`（`BaseFeature.kt:198-201`）用 `runCatching` 包住所有 hook 动作，且 `hookAfter` 跑在宿主原方法**之后**——所以这行 E 只表示「该 feature 在这一次静默失效」，**不表示宿主功能失败**。要证明后者得看同一次操作里别的 hook/打点是否正常。配套的另一条判锅法（崩溃报告读栈）见上面 WcS Crash Report 条目。
- **凡是只写 `onSuccess` 的 `WePacketHelper.sendCgi` 调用点都没有失败可观测性**：`WeRequestDsl.onFailure` 在没注册 handler 时是**空操作**（`WeRequestDsl.kt:23`），`WePacketHelper` 已经把 errType/errCode/errMsg 算出来并调进去了（`WePacketHelper.kt:547`/`576`），丢弃发生在调用方。排查「点了没反应、日志里零痕迹」的发包类功能先看这一点；`WeTransferApi.kt:94` 是正确写法。注意：**@所有人（隐蔽关闭那条）实测正常**，不要拿这条去解释它的失败。
- **@所有人：不要再往「入库注入」方向走**（真机定论）。隐蔽@原实现是在 `methodMsgInfoHandleApiInsertMessage.hookBefore` 里用 `MsgSourceHelper` 的合并方法把 `<atuserlist>` 写进内存 MsgInfo，指望 doScene 组装出网请求时带上——`24390034` 加的 armed/injected/dropped 三行日志证明钩子**干净执行、零异常**，但微信端**直接红色感叹号**（宿主发送被打断）。所以那条机制连同 `pendingStealthAt`、合并方法的 dexMethod、入库钩子已全部删除，隐蔽改成「同一条 522、正文只发原文不拼 @昵称」。这些符号**不存在了**，别再去 SearchAgent 找；`armed/injected/dropped` 也搜不到了，现在唯一的发送日志是 `cgi 522 send: card=… stealth=… allSelected=… targets=…` 加失败时的 `cgi 522 failed: …`。
- **@所有人仍没解决的正面问题：送达但不触发「有人@我」**。可见@那条（522 + `atuserlist=notify@all`）确认送达、对方看到 @所有人，但**没有「有人@你」提示**——差别应在 msgsource 少字段，Hchat 的解析器额外认 `<atall>` 正值（`WeChatMessage.kt:471-479` `hasPositiveAtAllFlag`），我们的模板没有这个节点。为拿到原生真值：`MessageInfo.msgSource` 那道 `> 3072 → error("Buffer String Length Error")` 已删（它拦的**恰好就是别人发的原生 @all 消息**，10-21 那 4 次异常即此），并把 `bb.short` 改成 `and 0xFFFF` 无符号读（原写法 ≥32768 会变负数绕过检查、崩在 `position()`）；`BlockAtAllNotifications` 现在对每条入站原生 @all 打一行 `incoming @all msgsource: …`，拿到真值即可照抄补齐。

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
- `.../chat_input_bar_menu/ParseVideo.kt`: 视频解析弹窗 `showParseDialog`（主线路 + 备用线路1/2/3、图集/实况/混合结果卡、下载与保存封面）；**三段式手搓外壳**（头部线路药丸 + ✕／滚动正文／吸底唯一主操作），不再走共享 `AlertDialogContent`；设置弹窗 `onClick` 仍是 `AlertDialogContent` + `SegmentedColumn`
- `.../beautify/CustomMessageBubbles.kt`: 自定义气泡（类型 × 方向六格、亮暗两套色、`.9.png` 九宫格导入/删除）；`applyBubble` 开头按 `custom_bubbles_press_scale`（默认关）挂 `StateListAnimator` 按压缩放，缩放系数随气泡高度动态计算、高度未变不重建、`isPressed` 期间不重建
- `.../system/FakeLocation.kt`: 虚拟定位，hook `TencentLocation` 的 `getLatitude`/`getLongitude`（+ 可选 `getAccuracy`）返回设定值；点功能行是两组设置弹窗（定位点 / 随机瞬移），瞬移由 `fake_jump_enabled`（默认关）控制、随机点按 location 实例缓存一帧保证经纬度成对；「清除已选位置」把中心恢复默认
- `.../chat_input_bar_menu/MentionMembers.kt`: @所有人，三条正文形态共用同一个裸 CGI `newsendmsg`(522)——默认可见@（正文拼 `@昵称 `）、隐蔽（正文只发原文）、卡片壳（type=49）；atuserlist 一律在请求自己的 msgsource 里，`cgi 522 send: card=… stealth=…` / `cgi 522 failed: …` 是唯一可观测面。入库注入那版已删除，理由见 CC
- `.github/workflows/ci.yml`: CI 配置，含 `upload-telegram` job；`build` 已改为只编 standard flavor
