# 主题取色接口

面向插件与脚本作者：如何在自己的代码里拿到 WcS 的配色，以及「动态壁纸取色」「同时对微信生效」这两个开关到底改了什么。

入口：WcS 设置 → 界面 → **动态壁纸取色** / **同时对微信生效**（Nuke 外观页同名行）。

## 两个开关各自的作用域

| 开关 | 偏好键 | 状态字段 | 写入 | 影响 |
| --- | --- | --- | --- | --- |
| 动态壁纸取色 | `settings_theme_dynamic_wallpaper` | `ThemeSettings.dynamicWallpaper`（`ThemeSettings.kt:160`） | `updateDynamicWallpaper()`（`:250`） | 决定**种子颜色**的来源：开=系统壁纸强调色，关=手工 `seedColor` |
| 同时对微信生效 | `settings_theme_apply_to_wechat` | `ThemeSettings.applyToWechat`（`:177`） | `updateApplyToWechat()`（`:270`） | 决定**微信侧**是否采用自定义配色（注入的 WcS 界面 + 微信原生绿色组件）；不影响模块自身界面 |

默认值：两个都是 `false`（`getBoolOrFalse`），`seedColor` 默认微信绿 `0xFF07C160`（`ThemeSettings.DEFAULT_SEED_COLOR`），`paletteStyle` 默认 `TONAL_SPOT`（`AppPaletteStyle.fromName` 回落），`colorSpec` 默认 `SPEC_2025`（`AppColorSpec.fromName` 回落）。

## 种子是怎么算出来的

唯一决策点在 `SeedResolver.kt`：

- `customSeed(context, dark)`（`:33-36`）= 开「动态壁纸取色」时取 `wallpaperAccent()`，取不到（含 **SDK < 31**）**静默回落** `ThemeSettings.seedColor`；关时直接 `seedColor`。这是**模块自身界面**用的种子（`ModuleTheme.kt:26-27`、`NukeTheme.kt:157`）。
- `injectedSeed(context, dark)`（`:43-45`）= 开「同时对微信生效」时跟随 `customSeed`，否则恒为微信绿。这是**注入进微信的 WcS 界面**用的种子（`InjectedUiTheme.kt:28`）。
- `materialScheme(seed, dark)`（`:47-52`）= 用当前 `paletteStyle` + `effectiveColorSpec` 把种子展开成 M3 `ColorScheme`。

写进偏好的是**枚举名**（`enum.name`），不是显示名：

- `AppPaletteStyle`：`TONAL_SPOT` `NEUTRAL` `VIBRANT` `EXPRESSIVE` `RAINBOW` `FRUIT_SALAD` `MONOCHROME` `FIDELITY` `CONTENT`（`ThemeSettings.kt:66-78`）
- `AppColorSpec`：`SPEC_2021` `SPEC_2025`（`:89-94`）
- 回落规则：只有 `TONAL_SPOT`/`NEUTRAL`/`VIBRANT`/`EXPRESSIVE` 支持 2025 规格（`supportsSpec2025`，`:80-81`），其余强制按 `SPEC_2021` 计算（`effectiveColorSpec`，`:181-182`）

## 与「微信原生绿色」的关系

`features/items/beautify/MonetEngine.kt` 负责把微信硬编码的品牌绿换成自定义强调色：

- 它是 `ApiFeature`、分类 `API`（`:39-43`），**没有用户开关**，`technicalId` 为「莫奈引擎」，不会出现在功能列表里。
- `onEnable()` 第一句就是 `if (!ThemeSettings.applyToWechat) return`（`:61-64`）——「同时对微信生效」关着，这些 hook 一个都不装。
- 第二句 `if (MonetEngineModuleGenerator.isEnabled) return`（`:66-69`）——**模块生成器（RRO）开启时运行期 hook 让位**，改色由安装的模块负责。插件不要假设一定能看到 hook 后的颜色。
- hook 面：`MMSwitchBtn` 构造后的 Int 字段、`setColor`、`View.setBackgroundDrawable`、`View.onFinishInflate`(Button)、`TextView.onAttachedToWindow`(EditText)（`:71-118+`）。
- 配色在 `lazy` 里**每次微信启动只解析一次**（`:50-57`），`primaryColor` / `onPrimaryColor` 是 `private`。

## 插件里怎么取色（推荐：自己算，别反射私有成员）

Kotlin `object` 在 JVM 侧要走 `INSTANCE`。Python 插件（Chaquopy 运行时）：

```python
from wcs.jvm import class_for_name

ThemeSettings = class_for_name("dev.sherry.wcs.ui.utils.theme.ThemeSettings")
SeedResolver = class_for_name("dev.sherry.wcs.ui.utils.theme.SeedResolver")
HostInfo = class_for_name("dev.sherry.wcs.utils.HostInfo")

ts = ThemeSettings.INSTANCE
sr = SeedResolver.INSTANCE
app = HostInfo.application
dark = (app.resources.configuration.uiMode & 0x1f) == 0x20  # UI_MODE_NIGHT_YES

seed = sr.customSeed(app, dark)          # 跟模块界面一致
scheme = sr.materialScheme(seed, dark)
accent = scheme.primary                  # 想要「替换品牌绿的那个颜色」就取 primary
```

想在插件里判断当前处于哪种组合：

```python
apply_to_wechat = ts.applyToWechat       # 「同时对微信生效」
from_wallpaper = ts.dynamicWallpaper     # 「动态壁纸取色」
```

BeanShell / Java 侧同理：`ThemeSettings.INSTANCE.getDynamicWallpaper()`、`SeedResolver.INSTANCE.customSeed(ctx, dark)`。

## 读取原始偏好（不经模块代码时）

实例是 MMKV `wcs_prefs`（`WePrefs.PREFS_NAME`），以 `MMKV.MULTI_PROCESS_MODE` 打开（`MmkvPrefsImpl.kt:14`），根目录是**宿主** `filesDir/mmkv`（`NativeLoader.kt:44-48`）。

注意作用域：MMKV 在微信 UID 内初始化，**模块独立 App 进程读不到同一份**（`i18n/WcSLocaleController.kt:39-40` 明确记录过这条限制）。所以外部工具/插件要读，要么跑在宿主进程里（Python 插件、BeanShell 脚本都满足），要么走自己那侧的同步，别指望 `/data/data/com.tencent.mm/files/mmkv/wcs_prefs` 能被别的 UID 直接打开。

主题相关的 5 个键（`constants/Preferences.kt:25-29`）：

```
settings_theme_dynamic_wallpaper   Boolean
settings_theme_apply_to_wechat     Boolean
settings_theme_seed_color          Int (ARGB)
settings_theme_palette_style       String (enum name)
settings_theme_color_spec          String (enum name)
```

## 容易踩的点

1. **都不热更**。「同时对微信生效」改完必须重启微信（界面行会弹 `restart_wechat_to_apply` 提示）；`InjectedUiTheme` 在 composition 进入时读一次，`MonetEngine` 的配色是 `lazy`。「动态壁纸取色」对**模块自身界面**是即时的（`mutableStateOf`），但对微信侧仍要重启。
2. **壁纸取色在 Android 12 以下静默失效**，回落手工种子色，不报错也不提示（`SeedResolver.kt:26-36`）。
3. **两个开关管的是不同对象**：`customSeed` 永远跟模块界面一致；`injectedSeed` 才反映「同时对微信生效」。插件里选错就会出现"跟着设置页变但跟微信不一致"或反之。
4. 别反射 `MonetEngine.primaryColor`：它是 `private lazy`，且模块生成器开启时根本不初始化。要颜色就自己走 `materialScheme(customSeed(...))`。
5. `Expressive (2025)` 规格不是所有调色板都支持，命中 `effectiveColorSpec` 回落时算出的色与设置页显示的规格不一致（`ThemeSettings.kt:181-182`）。
6. 深浅色请自己判断或复用宿主逻辑；`utils.android.isDarkMode` 是 Kotlin 顶层扩展函数，反射名不稳。
7. `InjectedUiTheme` **绝不能在模块 App 内调用**（文件头注释），插件在宿主进程里复用主题时才用它。

## 相关实现与文档

- `ui/utils/theme/ThemeSettings.kt`、`SeedResolver.kt`、`ModuleTheme.kt`、`InjectedUiTheme.kt`、`ui/content/nuke/NukeTheme.kt`
- `features/items/beautify/MonetEngine.kt`（运行期改色）、`MonetEngineModuleGenerator.kt` + `utils/monet/MonetModulePackager.kt`（RRO 模块生成）
- 设置界面：`activity/settings/SettingsPager.kt:476-486,549-561`、`activity/nuke/NukeAppearance.kt:154,192`
- 用户向文档：`docs/features/beautify/monet-engine.md`、`docs/features/beautify/monet-engine-module-generator.md`、`docs/module-settings.md`（界面一节）
