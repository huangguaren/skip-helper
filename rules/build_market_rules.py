"""从社区逐 App 规则库（readme）与聚合规则库生成一版「市场规则包」。

输入（只读）：
  tools/ltt-rules-community/<bucket>/<package>/readme.md   逐 App 规则说明（含快速复制 JSON + 中文说明）
  tools/ltt-rules-community/AllRules.json                 聚合规则（328 条 entry / 788 条 popup_rules）

输出：
  tools/rules/market-rules-2026.11.json        官方格式（Java hashCode → 内层 JSON 字符串）
  tools/rules/market-rules-2026.11.stats.json  统计：包名、条数、分类、来源、Top 市场覆盖

处理原则：
  1. 同一条规则（id + action）只留一次；同包多来源时参数取「或 / 取大」。
  2. 分类统计按 readme 里每条规则自己的中文说明算，不按整篇文本算。
  3. 社区库没覆盖到的高频市场 App，用「模板规则」补：只补更新/通知/开屏/评分四类
     文案极其稳定的弹窗，且标注来源 template，便于用户审阅后关掉。
  4. 内层字段只用原版与 SkipHelper 都认的：keywords / popup_rules / click_way_popup /
     search_times_popup / unite_popup_rules / reverse_traversal / delay / ltt_service。
"""
from __future__ import annotations

import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(r"D:\weihua work\rea-main")
SRC = ROOT / "tools" / "ltt-rules-community"
OUT_DIR = ROOT / "tools" / "rules"

# 市场高频 App（按国内常见装机量粗排）。社区库没覆盖的用模板补。
TOP_APPS = [
    ("com.tencent.mm", "微信"),
    ("com.tencent.mobileqq", "QQ"),
    ("com.taobao.taobao", "淘宝"),
    ("com.tmall.wireless", "天猫"),
    ("com.jingdong.app.mall", "京东"),
    ("com.xunmeng.pinduoduo", "拼多多"),
    ("com.ss.android.ugc.aweme", "抖音"),
    ("com.smile.gifmaker", "快手"),
    ("com.kuaishou.nebula", "快手极速版"),
    ("com.xingin.xhs", "小红书"),
    ("com.sina.weibo", "微博"),
    ("tv.danmaku.bili", "哔哩哔哩"),
    ("com.zhihu.android", "知乎"),
    ("com.sankuai.meituan", "美团"),
    ("me.ele", "饿了么"),
    ("com.dianping.v1", "大众点评"),
    ("ctrip.android.view", "携程旅行"),
    ("com.MobileTicket", "铁路12306"),
    ("com.autonavi.minimap", "高德地图"),
    ("com.baidu.BaiduMap", "百度地图"),
    ("com.baidu.netdisk", "百度网盘"),
    ("com.netease.cloudmusic", "网易云音乐"),
    ("com.tencent.qqmusic", "QQ音乐"),
    ("com.youku.phone", "优酷"),
    ("com.qiyi.video", "爱奇艺"),
    ("com.tencent.qqlive", "腾讯视频"),
    ("com.hunantv.imgo.activity", "芒果TV"),
    ("com.taobao.idlefish", "闲鱼"),
    ("com.shizhuang.duapp", "得物"),
    ("com.dragon.read", "番茄免费小说"),
    ("com.ss.android.article.news", "今日头条"),
    ("com.qidian.QDReader", "起点读书"),
    ("com.UCMobile", "UC浏览器"),
    ("com.tencent.news", "腾讯新闻"),
    ("com.sohu.newsclient", "搜狐新闻"),
    ("com.ifeng.news", "凤凰新闻"),
    ("com.alibaba.android.rimet", "钉钉"),
    ("com.tencent.wework", "企业微信"),
    ("com.jifen.qukan", "趣头条"),
    ("com.zhiliaoapp.musically", "TikTok"),
]

# 模板规则：只覆盖文案极其稳定的四类弹窗。更新时间文案各厂一致度最高。
TEMPLATE_RULES = [
    {"id": "发现新版本", "action": "以后再说"},
    {"id": "版本更新", "action": "暂不更新"},
    {"id": "立即更新", "action": "以后再说"},
    {"id": "开启通知", "action": "以后再说"},
    {"id": "打开通知", "action": "暂不开启"},
    {"id": "接收推送", "action": "以后再说"},
    {"id": "跳过", "action": "跳过"},
    {"id": "跳过广告", "action": "跳过广告"},
    {"id": "给个好评", "action": "以后再说"},
]


def java_hash(s: str) -> int:
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - 2**32 if h >= 2**31 else h


def clean_json(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"(?m)^\s*//.*$", "", text)
    text = re.sub(r",(\s*[\]}])", r"\1", text)
    return text.strip()


HEADING = re.compile(r"^#\s+([A-Za-z0-9_.]+)\s*[（(](.+?)[）)]\s*$", re.M)
FENCE = re.compile(r"```[a-zA-Z]*\n(.*?)```", re.S)
RULE_DOC = re.compile(r"^###\s+(\{.*?\})\s*$\n(.*?)(?=\n###|\n##|\Z)", re.S | re.M)


def classify(desc: str) -> str:
    d = desc or ""
    for key, cat in (
        ("青少年", "青少年"),
        ("更新", "更新"),
        ("开屏", "开屏"),
        ("悬浮", "广告位"),
        ("横幅", "广告位"),
        ("信息流", "广告位"),
        ("通知", "通知"),
        ("推送", "通知"),
        ("评价", "评分"),
        ("评分", "评分"),
        ("红包", "活动"),
        ("活动", "活动"),
    ):
        if key in d:
            return cat
    return "其他"


def parse_readme(path: Path) -> tuple[str, str, dict, Counter] | None:
    text = path.read_text(encoding="utf-8", errors="replace")
    m = HEADING.search(text)
    if not m:
        return None
    pkg, label = m.group(1), m.group(2)

    merged: dict = {}
    for block in FENCE.findall(text):
        try:
            data = json.loads(clean_json(block))
        except Exception:
            continue
        if not isinstance(data, dict):
            continue
        for k, v in data.items():
            if k == "popup_rules" and isinstance(v, list):
                merged.setdefault("popup_rules", []).extend(x for x in v if isinstance(x, dict))
            elif isinstance(v, (bool, int, float)):
                if k in ("click_way_popup", "unite_popup_rules", "reverse_traversal", "ltt_service"):
                    merged[k] = bool(merged.get(k)) or bool(v)
                elif k in ("search_times_popup", "delay", "delay_popup"):
                    merged[k] = max(int(merged.get(k, 0)), int(v))
    if not merged.get("popup_rules"):
        return None

    cats: Counter = Counter()
    for _, desc in RULE_DOC.findall(text):
        cats[classify(desc)] += 1
    return pkg, label, merged, cats


def merge_inner(dst: dict, src: dict) -> dict:
    out = dict(dst)
    rules = {f'{r.get("id")}\x00{r.get("action")}': r for r in out.get("popup_rules", [])}
    for r in src.get("popup_rules", []):
        rid = r.get("id")
        if not rid:
            continue
        key = f'{rid}\x00{r.get("action", "")}'
        if key not in rules:
            rules[key] = {k: r[k] for k in ("id", "action", "times") if k in r}
    out["popup_rules"] = list(rules.values())
    for k in ("click_way_popup", "unite_popup_rules", "reverse_traversal"):
        if src.get(k):
            out[k] = True
    for k in ("search_times_popup", "delay", "delay_popup"):
        if src.get(k):
            out[k] = max(int(out.get(k, 0)), int(src[k]))
    return out


def main() -> int:
    rules: dict[str, dict] = {}
    labels: dict[str, str] = {}
    origin: dict[str, set] = defaultdict(set)
    pkgs: dict[str, str] = {}

    agg = json.loads((SRC / "AllRules.json").read_text(encoding="utf-8"))
    for entry in agg:
        for hash_key, inner_text in entry.items():
            try:
                inner = json.loads(inner_text)
            except Exception:
                continue
            rules[hash_key] = merge_inner(rules.get(hash_key, {}), inner)
            origin[hash_key].add("AllRules.json")

    cat_counter: Counter = Counter()
    for path in sorted(SRC.rglob("readme.md")):
        parsed = parse_readme(path)
        if not parsed:
            continue
        pkg, label, inner, cats = parsed
        key = str(java_hash(pkg))
        rules[key] = merge_inner(rules.get(key, {}), inner)
        labels[key] = label
        pkgs[key] = pkg
        origin[key].add("readme")
        cat_counter.update(cats)

    # 社区库没覆盖的市场 App：模板补
    covered_before = len(rules)
    top_covered, top_filled = [], []
    for pkg, label in TOP_APPS:
        key = str(java_hash(pkg))
        if key in rules:
            top_covered.append(label)
            continue
        rules[key] = merge_inner(rules.get(key, {}), {"popup_rules": TEMPLATE_RULES,
                                                     "search_times_popup": 2})
        labels[key] = label
        pkgs[key] = pkg
        origin[key].add("template")
        top_filled.append(label)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out_rows, stats = [], []
    total_rules = 0
    for hash_key, inner in sorted(rules.items(), key=lambda kv: int(kv[0])):
        inner = dict(inner)
        inner.setdefault("ltt_service", True)
        inner.setdefault("search_times_popup", 1)
        populated = {k: inner[k] for k in
                     ("keywords", "popup_rules", "click_way_popup", "search_times_popup",
                      "unite_popup_rules", "reverse_traversal", "delay", "ltt_service")
                     if k in inner}
        out_rows.append({hash_key: json.dumps(populated, ensure_ascii=False, separators=(",", ":"))})
        n = len(populated.get("popup_rules", []))
        total_rules += n
        stats.append({"hash": int(hash_key), "package": pkgs.get(hash_key),
                      "label": labels.get(hash_key), "rules": n,
                      "sources": sorted(origin[hash_key])})

    out_path = OUT_DIR / "market-rules-2026.11.json"
    out_path.write_text(json.dumps(out_rows, ensure_ascii=False, indent=0), encoding="utf-8")

    summary = {
        "generated_at": "2026-11-12",
        "source_commit": "c30d28d",
        "apps": len(out_rows),
        "popup_rules": total_rules,
        "apps_from_allrules": sum(1 for s in stats if "AllRules.json" in s["sources"]),
        "apps_with_readme": sum(1 for s in stats if "readme" in s["sources"]),
        "apps_from_template": sum(1 for s in stats if s["sources"] == ["template"]),
        "community_apps_before_template": covered_before,
        "top_apps_covered": top_covered,
        "top_apps_template_filled": top_filled,
        "category_hits": dict(cat_counter.most_common()),
    }
    (OUT_DIR / "market-rules-2026.11.stats.json").write_text(
        json.dumps({"summary": summary, "apps": stats}, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"written: {out_path} ({out_path.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
