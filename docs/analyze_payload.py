"""静态分析乐固超级壳载荷 assets/0OO00l111l1l。

目标：判断能否在无设备条件下还原出 dex。
"""
import math
import sys
from collections import Counter

PATH = r"D:\weihua work\rea-main\tools\ltt-apk\unpacked\assets\0OO00l111l1l"


def entropy(buf: bytes) -> float:
    if not buf:
        return 0.0
    c = Counter(buf)
    n = len(buf)
    return -sum((v / n) * math.log2(v / n) for v in c.values())


def main() -> None:
    data = open(PATH, "rb").read()
    print(f"size={len(data)} entropy(whole)={entropy(data):.3f}")

    # 分块熵，看是否是「密文/明文」混排
    step = 65536
    for off in range(0, len(data), step):
        chunk = data[off:off + step]
        print(f"  block @0x{off:06x} len={len(chunk):6d} H={entropy(chunk):.3f}")

    magics = {
        b"dex\n": "dex",
        b"PK\x03\x04": "zip",
        b"\x7fELF": "elf",
        b"\xde\xad\xf2\xe4": "legu-marker",
    }
    for m, name in magics.items():
        hits = []
        start = 0
        while True:
            i = data.find(m, start)
            if i < 0:
                break
            hits.append(i)
            start = i + 1
        print(f"{name:12s} {m!r}: {len(hits)} hits, first={hits[:8]}")

    # 出现次数最多的 4 字节对齐序列（找结构头）
    c = Counter(data[i:i + 8] for i in range(0, len(data) - 8, 4))
    print("top 8-byte (4-aligned):")
    for val, n in c.most_common(6):
        print(f"  {val.hex(' ')} x{n}")

    # 单字节异或扫描：看是否能把头部变成 64 65 78 0a
    target = b"dex\n"
    print("single-byte xor candidates (offset 0..64 window):")
    for key in range(256):
        if bytes(b ^ key for b in data[0:64]).find(target) >= 0:
            print(f"  key=0x{key:02x}")
    print("done")


if __name__ == "__main__":
    sys.exit(main())
