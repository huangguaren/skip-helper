# SkipHelper 0.12.2

无障碍自动跳过开屏广告 / 弹窗。规则格式对齐「李跳跳」，额外支持坐标盲点与小程序容器兜底。

`app-debug.apk` 为 debug 签名，minSdk 26 / targetSdk 34，versionCode 35。

## 本次内容

- **规则引擎**：关键词（包含 / `+前缀` / `-后缀` / `=完全` / `a&b`）、弹窗规则 `id → action`、`times` 次数、`times=0` 联合闸门、`GLOBAL_ACTION_BACK`、坐标动作（含 CheckBox 前置状态）
- **坐标触发器**：触发条件也能写 `x1,y1,x2,y2`，取「盖住该点中心的最小节点」，避免落到整屏容器
- **开屏盲点**：整屏自绘、无障碍读不到文字的广告（咪咕视频、京东等），按屏幕百分比在 0.6s / 1.4s / 2.2s 各点一次，2.6s 再补一次上滑
- **小程序兜底**：识别 `AppBrandUI*` / `miniapp` / `miniaio` / `nebula` 容器，微信、QQ 默认进宿主列表；找不到按钮时返回（8s 窗口 + 3 次空扫 + 10s 冷却）
- **市场规则包 2026.11**：337 个 App / 854 条弹窗规则，从社区逐 App 规则库与聚合库合并去重生成，应用内一键导入
- **安全闸**：排除词优先、面积 ≤40%、边界约束、点击冷却、108 条逐应用白名单、系统界面/桌面过滤、青少年弹窗过滤

## 已知边界

- 完全自绘且不响应注入触摸的广告，无障碍层无法关闭，只能靠返回键或坐标盲点
- 规则里的混淆资源 id 与 App 版本强相关，App 更新后失效属正常
- HyperOS / MIUI 侧载应用需「允许受限制的设置」（或 `appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow`），否则无障碍开关会自动弹回

## 校验

- `./gradlew :app:testDebugUnitTest` — 27 项 JVM 单测通过
- `./gradlew :app:lintDebug` — 0 error
- 规则包离线预演：`python rules/verify_market_rules.py` → 334 应用 / 850 条弹窗
