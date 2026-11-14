# 跳过助手（无障碍自动跳过开屏广告）

一个独立的实现，用无障碍服务识别并点击「跳过 / 关闭」按钮。

## 与李跳跳的关系

参考对象 `hello.litiaotiao.app`（派大星 2.2）。0.5.0 起对这个 APK 做了**静态解包**：它是腾讯乐固
"超级壳"（`libshell-super.2019.so` + 加密的 `assets/0OO00l111l1l`），真实 dex 运行时才解密，
静态还原不出来（详见 `../tools/ltt-apk/解包报告.md`）。可静态读出的部分——组件清单、无障碍配置、
108 条默认白名单、全部配置键名、各页面布局——已经逐项对照，并落到下面的功能里。

规则库（`assets/AllRules.json`）来自社区整理的李跳跳规则集，只在用户点「导入内置社区规则」时读取，
不会自动启用、不联网更新。

## 机制

```
AccessibilityEvent (typeAllMask)
      │  120ms 去抖 + 系统界面/白名单过滤
      ▼
SkipService.scan()  ── 枚举所有窗口的根节点
      │
      ▼
RuleEvaluator.evaluateCombined(应用规则, 全局规则)
      │  广度遍历 / 关键词 / 弹窗 / 联合闸门 / 几何约束 / 排除词 / 青少年过滤
      ▼
delay（规则 delay + 全局延迟）──►  ACTION_CLICK ──失败则── dispatchGesture(tap)
```

三个关键取舍：

**为什么监听全事件而不是只看窗口切换**：开屏广告的按钮通常在 window state changed 之后才渲染出来，
只看窗口事件会漏。用 `notificationTimeout=100` 把事件频率压住。

**为什么要枚举所有窗口**：悬浮窗形式的广告根本不在 `rootInActiveWindow` 里，
必须开 `flagRetrieveInteractiveWindows`。

**为什么打分而不是命中就点**：同一屏可能出现「跳过」「跳过登录」「关闭」多个候选，
精确匹配 > 前缀 > 包含，再加"贴边角"加分项——但面积和位置是硬约束。

## 安全设计

无障碍服务一旦乱点，后果比不工作严重得多。这里做了五道闸：

1. **排除词优先**：命中「跳过登录 / 跳过支付 / 跳过验证」直接放弃，优先级高于所有正向匹配。
   判定发生在关键词、弹窗规则、动作目标三处，且**在界面上可增删**——排除词是最后的保险，不能写死在代码里。
2. **面积约束**：候选节点面积超过屏幕 40% 视为容器，不点——避免整屏误触。
3. **边界约束**：节点必须完整落在屏幕内，且宽高不小于 8px。
4. **点击冷却**：一次点击后 1 秒内不再扫描，防止把广告页连续点穿。
5. **逐应用白名单**：默认预置原版那份 108 条白名单（相机、时钟、通讯录、文件管理、支付宝、
   Netflix、Spotify 等），首启写入后可以在任意应用上关掉；另有「全部应用白名单」一键暂停。
6. **系统界面过滤**：`android`、`systemui`、桌面、设置、安装器、输入法直接忽略，避免误点系统 UI。
7. **青少年弹窗过滤**：`filter_young_popups`，规则 id 里带「青少年」的弹窗默认不自动关闭。

另外服务只读取节点文本和描述，不截图、不联网、不上传——应用没有 INTERNET 权限。
应用列表只申请「有启动图标的应用」可见性（`<queries>` MAIN/LAUNCHER），不申请 `QUERY_ALL_PACKAGES`。

## 全局规则与小程序

全局规则（`GlobalRulesActivity`）对所有应用生效，应用自己的规则命中时优先，全局规则负责兜底：
没有单独规则的应用也能被「跳过广告 / 关闭广告」这类关键词接住。

小程序宿主（微信、QQ 等）里跑的开屏广告往往**没有**可点的跳过按钮，只有一个容器。把宿主包名加进
「小程序宿主」列表并打开「找不到按钮时自动返回」，评估结果为空时直接触发系统返回——这是原版
`big_smart_pg` / 自动返回那套兜底逻辑的等价实现。

## 规则文件导入

社区流通的规则格式（李跳跳 2.x 及其衍生版本）是这样一种结构：

```json
[ { "<javaHash>": "{\"popup_rules\":[{\"id\":\"青少年模式\",\"action\":\"我知道了\"}],\"ltt_service\":true}" } ]
```

- key 是**包名的 Java `String.hashCode()`**（已用 12 个常见包名验证，11 个命中，
  没命中的是 Uber Eats —— 不在中文规则集里，符合预期）。
- `popup_rules[].id` 是锚点文字或资源 id，`action` 是要点的按钮。
- `ltt_service` 是该 App 的开关。

导入器（`engine/RuleImport.kt`）做三件事：把哈希还原成已安装的包名、解析 `popup_rules`、
按包名合并去重。**应用不内置任何规则集**，规则由你自己的文件导入——这样既能跟着社区更新，
也不是在分发别人的数据集。

实测三个样本文件的解析结果：

| 文件 | 文档数 | 重同步 | 条目 | 覆盖包 |
| --- | --- | --- | --- | --- |
| 881条规则.txt | 1 | 0 | 881 | 881 |
| 规则.txt | 1 | 0 | 2088 | 881 |
| 最新2986条规则.txt | 2 | 1 | 3843 | 884 |

最后那个文件是**损坏的**：第一个数组在 275769 处提前闭合，后面跟着 `}"` 残片再接一个新数组，
直接 `JSON.parse` 会报 "Extra data" 只读到 2964 条。导入器做了括号配平 + 失败重同步
（向前找下一个 `[{`），把剩余的 879 条也救回来了。

## 代码

| 文件 | 作用 |
| --- | --- |
| `SkipService.kt` | 无障碍服务：事件去抖、系统界面/白名单过滤、窗口枚举、延迟执行、点击与手势兜底 |
| `RuleStore.kt` | SharedPreferences + org.json 持久化（键名对齐原版：`is_main_switch_on`/`hide_toast`/`filter_*`/`whitelist`…） |
| `SkipTip.kt` | 跳过提示悬浮窗（typeApplicationOverlay，非 Toast） |
| `engine/RuleMatcher.kt` | 匹配语义、排除词判定、节点身份（含 10 项 JVM 单测） |
| `engine/RuleJson.kt` | 规则 JSON 纯解析层（原版字段、`delay`、`keywords_append`；9 项 JVM 单测） |
| `engine/RuleEvaluator.kt` | 规则评估：全局 + 应用规则、关键词、弹窗、联合闸门、坐标 CheckBox、过滤开关 |
| `engine/RuleImport.kt` | 规则文件/内置资源导入、哈希还原、全局规则读写、规则缓存 |
| `engine/RuleModel.kt` | 规则数据结构 + `EvalConfig` + `GLOBAL_RULE_PKG` |
| `RuleEditActivity.kt` | 单应用规则编辑界面（含 delay） |
| `GlobalRulesActivity.kt` | 全局关键词/弹窗规则、小程序宿主与自动返回 |
| `MainActivity.kt` | Compose 界面：服务状态、开关、关键词/排除词、识别细节、规则导入、应用白名单 |

## 规则缓存

服务每次扫描（最快 120ms 一次）都要取规则。最初版本每次 `RuleImport.load()` 都读盘 + 解析整份 JSON，
规则库上千条时会直接卡住主线程。现在按「文件最后修改时间 + 长度」判断缓存是否失效，保存/导入时主动清空；
实测扫描路径不再有磁盘 IO。

## 坐标规则的 CheckBox 前置状态

`action` 写成 `x1,y1,x2,y2,1`（或 `0`）时，表示「该坐标处的 CheckBox 必须处于选中/未选中状态才点击」。
评估阶段会先在节点树里找覆盖该坐标的 `isCheckable` 节点：状态不符、或找不到该节点，都不点击——
宁可漏，不可错。

## 构建

```bash
./gradlew :app:assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

已构建：`dist/SkipHelper-0.9.0-debug.apk`（8.9 MB，debug 签名，minSdk 26 / targetSdk 34，versionCode 14）。
`./gradlew :app:testDebugUnitTest` 27 项通过；`./gradlew :app:lintDebug` 0 error。
仓库镜像：`repo.maven.apache.org` 在本机不可达，`settings.gradle.kts` 里显式加了 aliyun / repo1 镜像。

## 内置规则

`assets/AllRules.json` 是社区整理的规则集（328 个包名哈希 / 788 条 `popup_rules` / 0 条关键词，
约 62 KB，SHA-256 前缀 `0623ae01…`）。界面上的
「导入内置社区规则」按当前已安装应用把哈希还原成包名后写入——没装的跳过，装了的下次扫描就生效。
这份数据只随 APK 分发、不联网更新，也不再默认自动导入。

**装完是什么状态（0.6.0）**：只有两样东西生效——`RuleStore.DEFAULT_KEYWORDS`
（跳过 / 跳过广告 / 关闭广告 / Skip / Skip Ad / 关闭，作为全局关键词兜底）和首启写入的 108 条白名单。
应用规则一条都没有：`filesDir/rules_v2.json` 要等用户点一次「导入内置社区规则」（或自己选文件导入）才生成，
在此之前 `RuleImport.load()` 返回空表，首页显示「已导入 0 个应用 / 0 条弹窗规则」。
导入时按**当前已安装应用**的哈希还原，没装的应用条目直接丢弃（`unresolved` 只体现在 Toast 里）。

## 市场规则包 2026.11

`assets/market-rules-2026.11.json`（80 KB）：**337 个 App / 854 条弹窗规则**，来自社区逐 App 规则库
（`readme.md` 里逐条标注了适用版本与截图说明）与聚合库合并去重，另给社区未覆盖的 6 个高频 App
补了模板规则（更新/通知/开屏/给个好评）。首页「导入市场规则（2026.11）」按钮写入同一个
`filesDir/rules_v2.json`，与内置规则合并去重，不会覆盖用户自建规则。

生成与预演脚本在 `../tools/rules/`：

```powershell
& $PY tools/rules/build_market_rules.py      # 重新生成
& $PY tools/rules/verify_market_rules.py     # 离线预演导入：334 应用 / 850 条弹窗
```

注意事项：

- 含「青少年」的规则默认被 `filter_young_popups` 过滤（对齐原版），要生效得在界面关掉该开关。
- 大量 `action` 是混淆资源 id（`h7e`、`qpb`），只对社区说明里标注的版本成立。
- 模板补充的 6 个 App 未逐版本核验；误点就把该 App 加白名单。

构建环境（本机）：`tools/android-sdk`（platform-tools + platforms;android-34 + build-tools;34.0.0），
`local.properties` 指向该 SDK；JDK 21。

## 使用

1. 安装后打开，点「去开启无障碍服务」，在系统设置里找到「跳过助手」并启用。
2. 回到应用，确认状态变为「无障碍服务已开启」。
3. 打开任意有开屏广告的 App，广告右上角出现「跳过」时会被自动点击。
4. 关键词不够用时在界面上自己加（例如某些 App 写的是「跳过 5s」——「跳过」这个前缀已经能命中）。

## 跳过提示

跳过成功后会在屏幕**下方**闪现一条提示（距底 160dp、居中），默认文案「已跳过」，可在界面上改成任意文字。

实现用的是 **`TYPE_APPLICATION_OVERLAY` 悬浮窗**，不是 Toast。原因很实际：
targetSdk 30+ 上后台服务弹出的 Toast 会被系统静默拦截（Android 11 起的后台 Toast 限制），
在无障碍服务里调 `Toast.show()` 用户根本看不见。悬浮窗是唯一可靠的路径，代价是需要
`SYSTEM_ALERT_WINDOW` 权限。

几条实现上的取舍：

- **没权限就静默不显示**，不弹 Toast 提示去授权（提示是附加效果，不该打断跳过流程）。
- **连续跳只刷新文案、重置计时**，不会叠出多个窗口。
- **服务销毁时清理窗口**，避免残留一个点不掉的浮层。
- `addView` 外面包了 try/catch —— 部分 ROM 会拒绝非系统应用添加该类型窗口，失败也不影响主流程。

## 自定义规则

点应用列表里的任意一行进入编辑页，可以直接增删规则。语法对齐原版：

| 写法 | 含义 |
| --- | --- |
| `跳过` | 包含（默认） |
| `+跳过` | 以「跳过」开头 |
| `-关闭` | 以「关闭」结尾 |
| `=跳过` | 完全等于 |
| `跳过&广告` | 两个子串都要有（`&` 分隔，全部满足） |

匹配字段依次尝试 **text → contentDescription → 资源 id 名**，所以没有文字的图标按钮也能靠 id 命中。

触发条件也可以直接写坐标 `x1,y1,x2,y2`：评估时挑「盖住该点中心的最小节点」，避免落到整屏容器上。

动作支持三种：控件文字（留空表示点触发器自身）、`GLOBAL_ACTION_BACK`（触发系统返回键）、
坐标 `100,200,300,400`（第五段可选 `,0`/`,1` 表示要求 CheckBox 处于该状态）。

五个参数：`click_way_popup`（找不到目标时强点触发器）、`search_times_popup`（重复扫描次数）、
`unite_popup_rules`（存在 `times=0` 的规则时，必须先命中它才执行其它规则）、
`reverse_traversal`（子树逆序遍历）、`delay` / `delay_popup`（命中后延迟再点，取两者较大值）。

对真实规则库的统计（6340 条规则 / 5861 个应用条目）：

| 维度 | 分布 |
| --- | --- |
| 匹配算子 | 包含 5759 / `&` 且 439 / `=` 完全 113 / `+` 前缀 29 |
| 动作类型 | 点其它控件 5566 / 点自身 478 / 返回键 287 / 坐标 9 |

也就是说 `&` 和返回键不是边角料，是主力用法之一——只做「包含」匹配的引擎会漏掉约 7% 的规则。

## 无障碍开不起来（MIUI / HyperOS）

侧载安装的 App 在 HyperOS 上默认被「受限制的设置」拦住，表现是开关一点就弹回。顺序：

1. 应用信息 → 右上角 `⋮` → **允许受限制的设置**；
2. 设置 → 无障碍 → **已下载的应用** → 跳过助手 → 打开；
3. 应用信息 → 省电策略 **无限制**、自启动 **允许**、最近任务下拉加锁；
4. 开发者选项里「MIUI 优化」若被关闭，部分机型会拒绝绑定第三方无障碍服务，恢复默认再试。

仓库自带 platform-tools，不用装 adb、也不依赖 PATH：

```powershell
.\tools\diagnose-accessibility.cmd            # 只读诊断：开关状态 / 是否绑定 / 包 flags / 悬浮窗
.\tools\diagnose-accessibility.cmd -Enable    # 直接把服务写进 enabled_accessibility_services
.\tools\diagnose-accessibility.cmd -Watch     # 跟 logcat，看绑定期崩溃
```

判定口径：`enabled_accessibility_services` 里有组件但 `dumpsys accessibility` 没有
`Bound services` → 列表里有、绑不上（受限制设置或省电策略）；两者都有才是真的在跑。

HyperOS 3 上实测到的拦截点是一条 appop，不是开关本身：

```powershell
adb shell appops get com.example.skiphelper ACCESS_RESTRICTED_SETTINGS
# ACCESS_RESTRICTED_SETTINGS: ignore; rejectTime=+13m58s   ← 就是它挡的
adb shell appops set com.example.skiphelper ACCESS_RESTRICTED_SETTINGS allow
adb shell appops set com.example.skiphelper SYSTEM_ALERT_WINDOW allow   # 顺带把提示浮层的权限也给了
```

放开后开关才能保持住。这台机上 shell 名义上持有 `WRITE_SECURE_SETTINGS` / `INJECT_EVENTS` /
`GRANT_RUNTIME_PERMISSIONS`（`dumpsys package com.android.shell` 里都是 granted=true），但实际调用
`settings put` / `input tap` / `pm grant` 仍被 HyperOS 的策略层拒掉——只有 `appops set` 能过。
所以最后一步只能手动拨开关。

0.8.0 起带「无人值守开启」：如果设备允许 `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`，
应用启动时会把自已写进 `enabled_accessibility_services`；拿不到权限就什么都不做。

## 版本变更

| 版本 | 变更 |
| --- | --- |
| 0.9.6 | 跳过提示从顶部挪到底部（距底 160dp、居中），避开状态栏与开屏广告右上角的「跳过」按钮 |
| 0.9.0 | 小程序开屏广告：识别小程序上下文（Activity 类名含 appbrand / miniapp / miniaio / nebula），微信、QQ 默认进宿主列表；「找不到按钮就返回」限定为进入小程序后 6 秒窗口 + 10 秒冷却，避免把聊天页点出去 |
| 0.8.1 | 桌面误点修复：忽略名单补 12 个桌面/系统 UI 包名（HyperOS `com.miui.home`、MIUI `com.miui.newhome`、EMUI/ColorOS/OriginOS/OneUI/Flyme 等），并用 `resolveActivity(ACTION_HOME)` 动态识别默认桌面。真机上确实误点过桌面一次（`stats={"com.miui.home":1}`） |
| 0.8.0 | 「无人值守开启」：拿到 `WRITE_SECURE_SETTINGS` 时启动把自己写进无障碍列表（HyperOS 零售版 `pm grant` 被策略层拒，只有别的机型用得上） |
| 0.7.1 | 无障碍诊断面板：四态状态（未开启 / 列表有但没绑上 / 已连接）+ SDK/悬浮窗/电池优化诊断行 + 「打开应用信息（受限制设置）」入口；`onServiceConnected` 全包 try/catch，白名单初始化挪到子线程 |
| 0.7.0 | 市场规则包 2026.11（337 个 App / 854 条弹窗，社区逐 App 规则 + 模板补充，见 `../tools/rules/README.md`）内置进 assets，首页一键导入；新增 3 项 JVM 校验（结构、跨语言哈希一致、键唯一） |
| 0.6.0 | `bounds` 也能当触发器（对应原版 id 输入框的「文字、id、\nbounds」）：坐标条件几何命中，取盖住该点的**最小**节点，避免点到整屏容器；新增 5 项单测 |
| 0.5.0 | 解包对照原版：全局规则 + 小程序自动返回、108 条默认白名单（首启写入、可改）、`delay/delay_popup`、`filter_invisible_nodes`、`filter_young_popups`、内置社区规则一键导入、系统界面过滤、去重按资源 id 认；解析层拆到 `RuleJson`（9 项新单测） |
| 0.4.0 | 排除词真正接入引擎（关键词/弹窗/目标三处）并可在界面增删；坐标规则支持 CheckBox 前置状态；规则缓存（扫描不再读盘）；补应用图标（含 monochrome）；编辑规则后首页计数自动刷新；权限收敛到 `<queries>`；lint 0 error |
| 0.3.0 | 规则导入（哈希还原、损坏文件重同步）、单应用规则编辑页、跳过提示悬浮窗 |
| 0.2.0 | Compose 首页、逐应用开关、统计 |
| 0.1.x | 无障碍服务 + 关键词匹配 |

## 与 2.2 原版的已知差异

- **没有真机验证**：构建环境没有 KVM、也没有连接设备（`adb devices` 为空），只有编译、单测、
  lint、打包校验这一层证据。
- **「圆形倒计时」开关语义未确认**：原版 AppSetting 有该开关（`aiswitch_app_setting_a`，文案
  「🐽️(圆形倒计时)」），反编译不出对应逻辑，没有臆造。
- 召回完全靠关键词/规则。用图片当按钮、自定义 View 无文字、Canvas 绘制的广告取不到文本就点不了。
- 反爬较强的 App 可能把「跳过」做成不可见节点或加随机文本，需要针对性加规则。
- 部分 ROM 会对无障碍服务做额外限制（省电策略、后台清理），需要加白名单。
- 不做「跳过」以外的任何自动化，也不绕过任何 App 的付费或登录验证。
