# SkipHelper（跳过助手）

基于 Android 无障碍服务的开屏广告 / 弹窗自动跳过工具。规则格式对齐「李跳跳」，
同时支持坐标盲点、小程序容器识别、按应用白名单、市场规则包一键导入。

当前版本 **0.12.2**（versionCode 35，minSdk 26 / targetSdk 34，debug 签名），
产物见 `dist/SkipHelper-0.12.2-debug.apk`。

## 它能做什么

| 能力 | 说明 |
| --- | --- |
| 关键词匹配 | `跳过 / 跳过广告 / 关闭广告 / Skip / Skip Ad / 关闭`，支持 `+前缀`、`-后缀`、`=完全`、`a&b` 同时满足 |
| 弹窗规则 | `popup_rules: id → action`，`times` 点击次数，`times=0` 作联合闸门 |
| 动作 | 点控件 / `GLOBAL_ACTION_BACK` / 坐标 `x1,y1,x2,y2[,0|1]`（第五段是 CheckBox 前置状态） |
| 坐标触发器 | 触发条件也能写坐标，取「盖住该点的最小节点」，避免点到整屏容器 |
| 开屏盲点 | 整屏自绘、无障碍读不到文字的广告（咪咕、京东等），按屏幕百分比在 0.6s / 1.4s / 2.2s 各点一次，2.6s 再补一次上滑 |
| 小程序 | 识别 `AppBrandUI*` / `miniapp` / `miniaio` / `nebula` 容器，微信、QQ 默认进宿主列表；找不到按钮时按返回兜底（8s 窗口 + 3 次空扫 + 10s 冷却） |
| 规则导入 | 社区哈希格式、损坏文件重同步、内置市场规则包（337 App / 854 条） |
| 安全闸 | 排除词优先、面积 ≤40%、边界约束、点击冷却、逐应用白名单（108 条，对齐原版）、系统界面/桌面过滤、青少年弹窗过滤 |

## 构建

```bash
./gradlew :app:assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # 27 项 JVM 单测
```

需要 JDK 17+ 与 Android SDK 34。`local.properties` 里写 `sdk.dir=<你的 SDK 路径>`。

## 目录

```
app/src/main/java/com/example/skiphelper/
  SkipService.kt        无障碍服务：事件去抖、窗口枚举、点击与手势兜底、盲点与小程序逻辑
  SkipTip.kt            跳过提示悬浮窗（TYPE_APPLICATION_OVERLAY，底部居中 700ms）
  RuleStore.kt          SharedPreferences 持久化（键名对齐原版）
  A11ySelfEnable.kt     有 WRITE_SECURE_SETTINGS 时把自己写进无障碍列表
  engine/               规则模型、匹配、JSON 解析、评估器、导入器
  MainActivity.kt       Compose 首页：状态诊断、开关、规则导入、应用列表
rules/                  市场规则包 2026.11 的生成脚本、产物与统计
docs/                   原版 APK 解包报告、载荷分析脚本、无障碍诊断脚本
```

## 规则包

`rules/market-rules-2026.11.json`：337 个 App / 854 条弹窗规则，由
`rules/build_market_rules.py` 从社区逐 App 规则库与聚合库合并去重生成，
`rules/verify_market_rules.py` 可离线预演导入结果（334 应用 / 850 条）。

`app/src/main/assets/AllRules.json` 是社区聚合库原始快照；两份数据均来自社区整理
（AGPL-3.0，见 `rules/README.md` 的溯源说明），本项目不再二次分发其截图资源。

## 已知边界

- 无真机自动化测试：CI 只有编译、单测、lint。
- 完全自绘（Canvas/SurfaceView）且不响应注入触摸的广告，无障碍层无法关闭，只能靠返回键或坐标盲点。
- 规则里的混淆资源 id 与 App 版本强相关，App 更新后失效属正常。
- 部分 ROM（如 HyperOS）对侧载应用有「受限制设置」，需 `appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow`
  或用界面里「允许受限制的设置」放行，否则无障碍开关会自动弹回。

## 许可

代码未声明开源许可，版权归作者。`assets/AllRules.json` 与 `rules/*.json` 派生自社区规则库
（AGPL-3.0），沿用其许可要求。
