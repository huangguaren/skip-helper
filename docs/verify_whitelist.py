"""交叉验证：原版 arrays.xml 的 whitelist 与 SkipHelper 的 DEFAULT_WHITELIST 是否逐条一致。"""
import re
import sys

ARRAYS = r"D:\weihua work\rea-main\tools\ltt-apk\unpacked\res\values\arrays.xml"
STORE = r"D:\weihua work\rea-main\ltt-clone\app\src\main\java\com\example\skiphelper\RuleStore.kt"


def main() -> int:
    arrays = open(ARRAYS, encoding="utf-8").read()
    m = re.search(r'<string-array name="whitelist">(.*?)</string-array>', arrays, re.S)
    orig = re.findall(r"<item>(.*?)</item>", m.group(1))

    store = open(STORE, encoding="utf-8").read()
    blk = re.search(r"DEFAULT_WHITELIST[^=]*=\s*listOf\((.*?)\)\s*\n", store, re.S).group(1)
    clone = re.findall(r'"([^"]+)"', blk)

    print(f"original={len(orig)} clone={len(clone)}")
    print("missing in clone:", [p for p in orig if p not in clone])
    print("extra in clone:", [p for p in clone if p not in orig])
    only_clone = [p for p in clone if p not in orig]
    return 0 if len(orig) == len(clone) and not only_clone and all(p in clone for p in orig) else 1


if __name__ == "__main__":
    sys.exit(main())
