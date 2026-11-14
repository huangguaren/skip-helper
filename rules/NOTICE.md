# 规则数据的来源与许可（AGPL-3.0）

本目录（以及 `../app/src/main/assets/AllRules.json`）里的规则数据不是本项目原创，来源如下：

| 文件 | 来源 | 许可 |
| --- | --- | --- |
| `market-rules-2026.11.json` / `.stats.json` | 由 `build_market_rules.py` 从下面两个来源合并去重生成 | 派生作品，沿用 AGPL-3.0 |
| `AllRules.json`（在 `app/src/main/assets/`） | 社区聚合规则库 `Fatchick233/LiTiaotiao-Custom-Rules` 的 `AllRules.json`，提交 `c30d28d` | AGPL-3.0 |
| 逐 App 规则（readme 来源部分） | 同一仓库的 384 份 `<包名>/readme.md` | AGPL-3.0 |

- 上游仓库：<https://github.com/Fatchick233/LiTiaotiao-Custom-Rules>
- 许可证全文：<https://github.com/Fatchick233/LiTiaotiao-Custom-Rules/blob/main/LICENSE>（GNU AGPL-3.0）
- 数据快照：`AllRules.json` 337 条条目 / 854 条 `popup_rules`（生成于 2026-11-12）

## 这对使用者意味着什么

1. 上面这些 **JSON 数据文件**受 AGPL-3.0 约束：再分发或修改后分发时，需要保留本说明与许可证，并提供对应源码（本目录的生成脚本即为源码）。
2. 本项目的 **Kotlin / Gradle 代码**不受 AGPL 影响，按仓库根目录 `LICENSE` 的 MIT 条款授权。
3. 上游仓库的规则来自社区整理，其中含各 App 的界面文案与资源 id；本项目不再分发上游仓库的截图资源（`assets/*.jpg`）。

如果你要把这些规则数据用于闭源分发，需要自行剔除 `app/src/main/assets/AllRules.json`、
`rules/market-rules-2026.11*.json`，或改用自己编写的规则。
