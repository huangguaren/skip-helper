# 市场规则包 2026.11（SkipHelper / 李跳跳格式）

生成脚本：`build_market_rules.py`；离线预演：`verify_market_rules.py`。
产物：`market-rules-2026.11.json`（80 KB）+ `market-rules-2026.11.stats.json`。
应用内同名资产：`ltt-clone/app/src/main/assets/market-rules-2026.11.json`，首页「导入市场规则（2026.11）」按钮。

## 数据从哪来

| 来源 | 内容 | 权重 |
| --- | --- | --- |
| 社区逐 App 规则库 `Fatchick233/LiTiaoTiao-Custom-Rules` @ `c30d28d` | 384 份 `readme.md`，每份含该 App 的「基础规则 / 增强规则」快速复制 JSON + 中文说明 + 版本备注 | 主 |
| 同仓库 `AllRules.json` | 把逐 App 规则聚合成 328 条 hash 条目的成品 | 主 |
| 本包自建模板 | 社区库没覆盖的 6 个高频 App，补更新/通知/开屏/评分四类文案稳定的弹窗 | 辅 |

处理链路：

```
readme.md ──(抽 ``` 代码块 / 容错去注释与尾逗号)──┐
AllRules.json ──(解析内层 JSON)──────────────────┤
                                                 ├─ 按 Java hashCode 合并去重 ─→ market-rules-2026.11.json
TOP_APPS 模板（仅未覆盖者）──────────────────────┘
```

合并规则：同包多条来源时，规则按 `(id, action)` 去重；`click_way_popup` /
`unite_popup_rules` / `reverse_traversal` 取「或」，`search_times_popup` / `delay` 取大。

## 规模

| 指标 | 值 |
| --- | --- |
| App 条目 | 337（唯一 hash，无重复键） |
| 弹窗规则 | 854 条，空 id 0 条 |
| 唯一触发锚点 | 626 个（含 `&` 复合写法、`+` 前缀写法、混淆 id） |
| 覆盖市场高频 App | 40 个里 34 个来自社区库，6 个用模板补（大众点评 / 携程 / 12306 / 凤凰新闻 / 趣头条 / TikTok） |
| 全部 `ltt_service` | true |

按中文说明分类（社区条数，一条规则可能被多处引用，仅作分布参考）：

| 类型 | 条数 | 典型规则 |
| --- | --- | --- |
| 其他（信息流/推荐/权限） | 381 | `dislike&feedbackBtn → 不感兴趣`、`想访问你的通讯录 → 拒绝` |
| 通知/推送 | 122 | `开启通知 → 以后再说` |
| 更新 | 89 | `发现新版本 → 忽略` |
| 青少年模式 | 66 | `青少年模式 → 我知道了` |
| 广告位（悬浮/横幅/信息流） | 62 | 混淆 id → 关闭（版本相关） |
| 活动/红包 | 30 | 新人礼包 → 关闭 |
| 开屏 | 18 | 跳过按钮 / 倒计时控件 |
| 评分 | 4 | 给个好评 → 以后再说 |

## 抽样（可直接对照）

```json
"com.tencent.mm":   [{"id":"广告","action":"feedbackIcon"},
                     {"id":"dislike&feedbackBtn","action":"不感兴趣"},
                     {"id":"isdismatch&isduplicate&isbadad","action":"与我无关"}]
"com.ss.android.ugc.aweme": [{"id":"检测到更新","action":"以后再说"},
                     {"id":"青少年模式","action":"关闭"},
                     {"id":"朋友推荐","action":"close"},
                     {"id":"开启朋友通知","action":"暂不"},
                     {"id":"想访问你的通讯录","action":"拒绝"}]
"com.MobileTicket": [{"id":"发现新版本","action":"以后再说"}, … 模板 9 条]
```

## 导入预演（无设备验证）

`verify_market_rules.py` 按 `RuleImport` 的语义跑了一遍：

```
文件条目: 337
预演安装清单: 334 个包名
导入完成：334 个应用 / 850 条弹窗 / 0 条关键词（另有 2 条对应应用未安装）
未启用条目: 0
```

差异原因：`AllRules.json` 有 3 条条目无法反查包名（聚合时丢了包名信息），所以 337 → 334。
真机上「已安装应用」更少，导入后的条数只会比 850 更小。

## 风险与已知边界

1. **青少年模式规则默认不生效**：SkipHelper 的 `filter_young_popups`（对齐原版）会跳过 id 里含
   「青少年」的规则，除非在界面上关掉该开关。这是刻意保留的闸门。
2. **混淆 id 与版本强相关**：社区规则里大量 `action` 是 `h7e` / `qpb` 这类混淆资源 id，
   只对说明里标注的版本成立；App 更新后失效属正常，需要按同样流程重新生成。
3. **模板补充的 6 个 App 未逐版本核验**：只补了「发现新版本 / 开启通知 / 跳过 / 给个好评」这类
   全市场文案一致的弹窗，属于「宁可漏点」的选择；如果误点，把对应 App 加白名单即可。
4. **无文字按钮召回不到**：Canvas 绘制、纯图片的广告位仍需要坐标规则或抓包分析。
5. **不做付费/登录绕过**：规则只点广告关闭按钮与常规弹窗，不触碰会员、登录、支付确认。

## 重新生成

```powershell
# 1) 拉取（只取 readme 与聚合 JSON，不含截图）
git clone --depth 1 --filter=blob:none --sparse https://github.com/Fatchick233/LiTiaotiao-Custom-Rules.git tools/ltt-rules-community
cd tools/ltt-rules-community; git sparse-checkout set --no-cone '**/readme.md' '/AllRules.json'

# 2) 生成
& $PY tools/rules/build_market_rules.py

# 3) 预演 + 装进应用资产
& $PY tools/rules/verify_market_rules.py
Copy-Item tools/rules/market-rules-2026.11.json ltt-clone/app/src/main/assets/ -Force
```

社区规则的许可证见 `tools/ltt-rules-community/LICENSE`（AGPL-3.0），本包只做本地合并与分发，
不再二次分发原始截图资源。
