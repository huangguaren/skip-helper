"""按 SkipHelper/RuleImport 的语义对市场规则包做一次「离线导入预演」。

用途：在没有设备的情况下验证规则包能被导入器吃下去，并给出导入后会写进
`filesDir/rules_v2.json` 的结果（应用数 / 弹窗条数 / 未安装条目数）。

预演用的「已安装应用」清单来自生成阶段记录的 stats（package 字段），
相当于假设用户装了全部 337 个应用；真实设备上条数只会更少。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(r"D:\weihua work\rea-main")
BASE = ROOT / "tools" / "rules"


def java_hash(s: str) -> int:
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - 2**32 if h >= 2**31 else h


def main() -> int:
    rows = json.loads((BASE / "market-rules-2026.11.json").read_text(encoding="utf-8"))
    stats = json.loads((BASE / "market-rules-2026.11.stats.json").read_text(encoding="utf-8"))

    installed = {java_hash(a["package"]): a["package"] for a in stats["apps"] if a.get("package")}

    matched, unresolved, popup_total, keyword_total = {}, 0, 0, 0
    for row in rows:
        for key, inner_text in row.items():
            h = int(key)
            inner = json.loads(inner_text)
            pkg = installed.get(h)
            if pkg is None:
                if inner.get("popup_rules") or inner.get("keywords"):
                    unresolved += 1
                continue
            matched[pkg] = inner
            popup_total += len(inner.get("popup_rules") or [])
            keyword_total += len(inner.get("keywords") or [])

    print(f"文件条目: {len(rows)}")
    print(f"预演安装清单: {len(installed)} 个包名")
    print(f"导入完成：{len(matched)} 个应用 / {popup_total} 条弹窗 / {keyword_total} 条关键词"
          + (f"（另有 {unresolved} 条对应应用未安装）" if unresolved else ""))

    bad = [p for p, inner in matched.items() if not inner.get("ltt_service")]
    print(f"未启用条目: {len(bad)}")

    # 常见 App 抽查：确认包名 → 规则确实落到了对应应用
    for probe in ("com.tencent.mm", "com.ss.android.ugc.aweme", "com.MobileTicket", "com.dianping.v1"):
        inner = matched.get(probe)
        ids = [r["id"] for r in inner.get("popup_rules", [])] if inner else []
        print(f"  {probe}: {len(ids)} 条 -> {ids[:4]}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
