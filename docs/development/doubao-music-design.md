# 豆包音乐设计与实现说明

状态：首版已通过云端构建；本轮按用户选定的B布局更新原生弹窗，构建结果以对应提交CI为准，仍待真机验证。尚未调用真实豆包音乐接口。起始基线为 `b1f67500`（WCS 分支），参考工作区同级 `../doubao-music/doubao_music.py`。账号复用API配置，界面使用同一弹窗。

## 1. 用户确认的范围

1. 功能名“豆包音乐”，保留在娱乐分类中作为可启停功能；开启后，在聊天输入栏长按加号或发送按钮的菜单中出现“豆包音乐”，点击直接打开创作弹窗。
2. 账号改为“选择账号”，只列出「API配置 → 语音合成 → 豆包」的实际配置档，不另存一份Cookie，不提供“未选择”项。保留有效已选档；首次或原档失效时优先使用Cookie和UA齐全的配置，再回落已有配置；没有任何档时仅显示不可选的账号行及下方说明。
3. User-Agent由用户在上述API配置中手动填写，与Cookie属于同一配置档；音乐弹窗不显示UA行，只保留下方配置位置说明。
4. 音乐临时文件放 `KnownPaths.moduleData / WCS_Cache / doubao_music / 请求UUID`，按手机本地日期保留今天和之前三天。大小写按用户本次要求为WCS_Cache，不迁移或重命名其他功能原有Wcs_Cache目录。
5. 文字转语音设置改为与AI回复相同的“语音合成后端”下拉，只显示Breeze、豆包或配音魔方，不再拼接服务商与档名，避免“豆包 豆包”。切换后端时读取对应配置并保留已有同服务商账号；未配置该后端时也保留选择，不静默显示其他后端。

## 2. 入口与使用流程

```text
娱乐 → 启用豆包音乐
聊天输入栏 → 长按加号或发送按钮 → 豆包音乐
  标题行：豆包音乐 ……………… 作品库图标 · 账号胶囊（最右）
  ├─ 创作：创作描述输入框 / 歌词输入框（头部 AI 作词·自定义 分段按钮）/ 流派·情绪·人声三行点选
  ├─ 播放：封面 + 曲名 + 时长状态 · 最右播放圆钮；进度条；下载音乐 · 发送语音；歌词常显（右上复制歌词）
  ├─ 作品库：三列封面网格，点任意一格进入播放页
  └─ 设置：选择账号下拉 + 两段说明
  非创作页的右下角只有一个「返回」；创作页那里是「关闭」。
```

娱乐功能行点击也可打开同一弹窗。输入栏入口复用WeChatInputBarMenuApi.IActionItemsProvider与已有ChatFooterHooks，不新增宿主Hook或Dex定位；启停功能时注册/注销provider。功能默认关闭，开启后加号与发送按钮共享同一条菜单项。

从聊天进入时，把输入框现有文字带到创作描述中，不自动发送、不清空原输入框。当前会话只作为后续联系人选择的默认项，不把生成操作等同于发送。

弹窗采用showComposeDialog与AlertDialogContent，点击外部不关闭，返回键/关闭按钮可退出；关闭会取消当前本地协程。创作、作品库和设置在同一弹窗内切换，不新建Activity或Manifest入口。

## 3. 账号与User-Agent联动

ApiProfile新增 `userAgent: String = ""`，JSON整表读写包含userAgent。旧档没有该键时保持空值，原有Cookie、模型等字段不变。

API配置仅在豆包语音合成档中显示User-Agent编辑行，确认即保存。豆包音乐只保存引用键 `doubao_music_profile_id`，已纳入ApiProfiles.usageCount，正在被音乐引用的配置不能从管理页直接删除。

音乐生成必须选择豆包配置、具备非空sessionid及手填UA；缺失时给出明确提示，不发真实探测或自动套用其他账号。请求开始时使用不可变ApiProfile快照，表单与账号切换在本弹窗操作期间禁用。

为保持共享配置的含义一致，文字转语音、手动AI回复语音和自动回复语音的豆包TTS调用也透传所选配置UA。旧档UA为空时，TTS继续使用原有默认UA；音乐则要求用户明确填写。自动回复这里只补UA透传，不改变触发或回复规则。

作品库按配置ID与sessionid的SHA-256摘要隔离，不把Cookie写入目录名或作品JSON。更新同一登录会话中的其他Cookie字段不会换库；更换sessionid会进入新空间，旧文件仍保留，但不自动推断两次登录是否同一账号。

## 4. 已接入的创作与结果操作

- 创作页两块输入：创作描述与歌词，默认各 2 行、最多 5 行，超出在框内自己滚。歌词头部放「AI 作词 / 自定义」胶囊开关（选中那半填主色，不用 M3 分段按钮——它带勾，喵体文案还会把标签撑成两行）。AI 作词时歌词框禁用且显示为空，自定义草稿单独留着：切走再切回来还是刚才写的内容。描述必填，自定义模式歌词也必填。切回AI作词，请求lyric为空，不发送隐藏歌词草稿。
- 「主题」输入已删除。协议里 `theme` 是 6 个变量之一、必须全发，因此固定发空串；它与描述不是一回事（描述进消息正文 `text_block.text`，theme 进 `variables`），抓包样本里两者同时存在，实测参数全空也能生成，所以留空安全。
- 流派、情绪、人声为三行点选（`DropDownMenuWidget`），不再是卡片分组。**没有「不指定」**，默认各取第一项（Pop / Happy / Male）。协议值与显示名分开：发送 `Pop`、`Hip Hop/Rap`、`Male`，行里显示「流行」「嘻哈」「男声」，中文标签来自 `db_music_genre_labels` / `db_music_mood_labels` 两个 string-array。
- 标题行右侧是作品库图标 + 设置齿轮两颗图标，不再显示账号名胶囊（账号名在设置页的下拉里看）。
- 生成由用户主动点击，界面提示会使用账号额度；同一进程只运行一个音乐生成请求，不自动重试生成POST。
- 进度显示等待生成、下载保存等真实阶段，不伪造服务器百分比。
- 成功生成的所有任务先保存元数据，再逐首下载。下载失败可从结果或作品库重试下载，不重新提交歌曲生成。
- 播放页只有四个操作：播放（封面行最右的圆形钮，与封面对称）、下载音乐、发送语音、复制歌词。不再有上一首/下一首。
- 「下载音乐」固定写入公共目录 `Download/WcS/`（`KnownPaths.downloads`），不再弹系统文件选择器；重名自动追加 ` (1)`、` (2)`；完成后用 toast 报出实际落点路径。
- 「发送语音」先 `AudioUtils.anyToSilk` 把 m4a 转成 silk，再 `WeMessageApi.sendVoice(talker, silk, duration)`；silk 留在音乐临时目录，交给微信异步上传后由四天规则回收，不立即删除。
- 歌词默认常显、不再折叠；右上角「复制歌词」复制的是剥掉 `[mm:ss.xx]` 时间标记后的纯文本，显示同样不保留时间标记。
- 支持本地试听/暂停/拖动进度，以及选择联系人或群聊发送语音。
- 完成作品按账号保存在本地库，可在列表中确认后删除。正常发送后不会立即删源文件，避免干扰微信异步读取。
- sendVoice 返回仅表示已交给微信处理，不声称服务端送达或对方已读；转码或投递失败都给明确提示。

首次使用步骤：在API配置新建或编辑豆包语音合成档，填Cookie与User-Agent；启用豆包音乐，长按输入栏进入，在右上角选择该账号，再填写描述并生成。

## 5. 原生协议实现

### 5.1 请求结构

DoubaoMusicClient使用现有OkHttp依赖，原生实现参考Python协议，无Python解释器、httpx、浏览器或外部服务依赖。

业务请求固定走豆包站点 `POST /chat/completion`，不走 `/samantha/chat/completion` 或OpenAI兼容接口。保留顶层client_meta、messages、option、chat_ability、user_context、ext及参考实现的39个option键。

变量lyric、theme、generation_type、genre、mood、gender恒定全发；AI模式为AI_lyric，自定义模式保留原始拼写custome_lyric。同一变量对象镜像到ext.input_skill和chat_ability.ability_param.instruction，保留字符串套层、skill_id字符串9、skill_type整数9和ability_type整数2005。

每次生成新会话，不实现续聊改歌。设备标识在同一进程的同一登录配置内复用；CSRF和fp从所选Cookie中提取，不新增msToken或签名生成。业务请求不自动跨域重定向，CDN下载使用不带Cookie的独立客户端。

### 5.2 SSE与业务成功

逐行消费SSE，支持空行事件边界、多行data、心跳及最终事件。递归寻找lyrics_to_songs_music_block，按任务键合并lyrics_to_songs_music_content；嵌套对象保留已返回字段，不只取第一段或最后一段。

同时检查gateway-error、error_code/error_msg和music_gen_failed；JSON错误码兼容数字/字符串。音频URL从video_model.video_list中的Base64主备链接解出，video_model兼容对象或JSON字符串。必须有完成标记及非空任务，且具备音频链接或可解析的vid，才进入下载；HTTP200本身不代表成功。

业务连接20秒、空闲读取300秒、单次总调用360秒，HTTP调用与协程取消绑定。取消只停止本地等待，不代表云端任务撤销或退还额度。初次移植的结束标记、字段形态仍需实际音乐请求验证。

### 5.3 下载

依次使用已有主/备用链接；必要时通过已知 `/alice/media/bigmusic/get_video` 为vid换下载链接，不重新生成歌曲。下载流限制音频50MiB、封面5MiB；文件非空且通过本地格式/时长检查后才发布到作品库。

音频按参考结果保存M4A，不转MP3、不转微信语音（发送语音时才临时转silk），避免不必要的音质损失。封面失败不影响已下载音乐。所有.part和元数据发布中间件位于音乐临时目录，完成或失败清理；崩溃遗留文件在后续使用该目录时执行日期淘汰。

封面域名不固定：抓包里见过 `p26-flow-imagex-sign.byteimg.com`，同一字段也可能换域名或换容器形态。因此取封面时**不认任何域名**，只按「`image_ori` → `image_large` → `image_default` → `image_small` → `cover.url` → 封面对象里第一个可用 https 链接」的顺序取，值本身既接受直链也接受音频那种 Base64 包装。签名链接会过期，生成时下载失败不判死：进播放页再补取一次，仍失败就保持无封面（不谎报有封面）。

## 6. 路径与生命周期

| 数据 | 位置（相对KnownPaths.moduleData） | 规则 |
|---|---|---|
| 临时音频、封面、元数据发布件 | `WCS_Cache/doubao_music/<请求UUID>/` | 用完清理；遗留文件按最后修改日期保留今天及之前3天，下次创建临时目录时清理更早文件。**空目录只有闲置超过10分钟才回收**：从领到目录到写出首个文件之间目录是空的，并发进来的清理会把它当垃圾删掉，写入端随后 `outputStream()` 抛 FileNotFoundException（曾表现为「豆包已生成、模块下载失败」） |
| 完成作品和待重试下载元数据 | `doubao_music/library/<账号摘要>/<作品UUID>/` | 保留audio.m4a、cover.jpg、track.json，由用户从作品库确认删除 |
| 下载音乐 | `Download/WcS/<歌名>.m4a`（公共目录，重名追加序号） | 用户管理，不参加模块缓存清理 |

四天策略只针对临时文件，不自动删除已完成作品。内部文件使用UUID和固定名，不使用远端歌名直接拼路径；导出/发送名称清理路径分隔符和控制字符。作品元数据保存参数结果与临时URL，不保存Cookie或User-Agent。

没有修改KnownPaths.moduleCache，其余功能仍使用现有 `moduleData/Wcs_Cache`。本功能按用户明确要求使用独立的 `moduleData/WCS_Cache`，避免全局改路径造成其他缓存丢失或迁移。

## 7. 源码范围

| 文件（相对app/src/main/java/dev/sherry/wcs） | 职责 |
|---|---|
| features/items/entertain/DoubaoMusic.kt | 可启停功能、输入栏菜单、创作/播放/作品库/设置四屏、试听、下载到公共目录、silk 转码发送与联系人选择 |
| features/items/entertain/DoubaoMusicClient.kt | 请求构造、SSE合并、错误分类、下载链接及音频下载 |
| features/items/entertain/DoubaoMusicStore.kt | 账号隔离、作品元数据、本地音频封面、临时目录及日期清理 |
| features/items/chat/ApiProfiles.kt | UA持久化与音乐账号引用保护 |
| features/items/entertain/ApiProfileManager.kt | 豆包账号User-Agent手填行 |
| features/items/chat/TextToSpeech.kt、TtsProfiles.kt | TTS选择后端并读取对应配置及UA |
| features/items/chat/AiReplyCandidates.kt、AiAutoReply.kt | 给所选豆包TTS配置透传UA |

英语/简体中文资源同步新增提示，目录说明同步至docs/paths.md。没有新增宿主Dex规则、权限或原生库。

## 8. 验证与待测

静态已检查：Kotlin词法括号、重复导入、新增资源引用、双语资源键/格式参数、Android禁用API、参考39个option键覆盖及空白。静态检查不能替代Kotlin编译或真实微信行为。

本轮没有读取工作区凭据、没有请求真实音乐或消耗额度；云端构建状态以本次提交对应CI为准。Android弹窗、网络协议、播放器、下载到公共目录与发送语音仍需用户真机验证。

验收重点：

1. 功能默认关闭；开启后长按加号/发送按钮均有菜单，点击立即进入弹窗，原输入框文字不被发送或清空。
2. 设置只列豆包语音合成配置，不列Breeze/魔方；选档后重开能记住，修改该档Cookie/UA能被下一次请求读取，引用中的账号不能误删。
3. 旧TTS档不填UA仍按原行为合成；音乐未填UA有明确提示；TTS后端当前值与选择列表只显示Breeze、豆包或配音魔方，音乐账号下拉只显示档名且无“未选择”。
4. AI/自定义作词切换正确，单次生成与取消不重复提交；登录过期、验证码、限流、半截SSE不误报成功。
5. 生成后自动进播放页：封面/曲名/时长显示正确，播放圆钮在封面行最右；下载音乐在 `Download/WcS/` 落盘并弹 toast；发送语音在会话里是可播放的语音消息（不是文件）；歌词常显且复制出来没有时间标记；关闭或返回停止播放和本地等待。
6. 下载失败只重试下载；重开能从作品库恢复；缺封面仍可播放音频；删除需确认。
7. 临时件位于大小写准确的WCS_Cache目录，今天和前3天保留、更早文件下次使用时被清理；作品库和用户下载到 `Download/WcS/` 的文件不被误删。
8. 手动AI回复、自动回复与独立文字转语音的原功能无回归，所选账号UA不串到其他TTS配置。

## 9. 暂未添加

音乐卡片、长歌曲转微信语音、字幕视频、云端历史同步、服务端任务恢复/取消、续聊改歌、多账号批量并行生成和后台常驻服务。上游风格候选、接口可用性及账号额度没有在本次开发中重新在线验证。
