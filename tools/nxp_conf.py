#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
nxp_conf.py —— libnfc-nxp.conf 的解析 / 校验 / UID 改写工具（独立实现，用于交叉验证）。

为什么单独用 Python 再实现一遍？
    项目主体的 `core-nfcc` 是 Kotlin 模块，本机没有 JDK 17 也没有 Android SDK，
    跑不了 JUnit。于是把同一套算法用 Python 复刻一份，作用有两个：

      1. **离线交叉验证**：`--self-test` 里跑的是和 Kotlin 单元测试完全相同的断言。
         两边都通过，才有理由相信偏移计算没有 off-by-one。
      2. **设备上手工排查**：把手机里的 /vendor/etc/libnfc-nxp.conf 拉下来，
         用这个脚本 dump / patch，比在手机上盲改安全得多。

用法：
    python nxp_conf.py dump  libnfc-nxp.conf
    python nxp_conf.py patch libnfc-nxp.conf 42:4D:44:4A
    python nxp_conf.py patch libnfc-nxp.conf 42:4D:44:4A -o new.conf --write
    python nxp_conf.py self-test

注意：脚本只处理 `KEY={ ... }` 形态的文本区块。若区块内出现 `#` 注释，
会明确报错而不是猜 —— 静默错位会把设备的 NFC 配置写坏。
"""

from __future__ import annotations

import argparse
import difflib
import re
import sys
from typing import List, Optional, Tuple

TAG_ID = 0x33
UID_LENGTHS = (4, 7, 10)
KEY_NXP_CORE_CONF = "NXP_CORE_CONF"
KEY_NXP_CORE_CONF_EXTN = "NXP_CORE_CONF_EXTN"

_SEPARATORS = set(" \t\r\n,:;-_.|/\\")


class ConfError(Exception):
    pass


# --------------------------------------------------------------------------- hex


def compact(text: str) -> str:
    out = []
    i = 0
    while i < len(text):
        c = text[i]
        if c == "0" and i + 1 < len(text) and text[i + 1] in "xX":
            i += 2
            continue
        if c not in _SEPARATORS:
            out.append(c)
        i += 1
    return "".join(out)


def decode(text: str) -> bytes:
    body = compact(text)
    if len(body) % 2:
        raise ConfError(f"十六进制长度为奇数（{len(body)}），无法按字节解析")
    try:
        return bytes.fromhex(body)
    except ValueError as exc:
        raise ConfError(f"非法十六进制：{exc}") from exc


def encode(data: bytes, upper: bool = True, sep: str = "") -> str:
    fmt = "%02X" if upper else "%02x"
    return sep.join(fmt % b for b in data)


# ------------------------------------------------------------------- 字符 ↔ 字节


def index_bytes(text: str) -> Optional[List[Tuple[int, int]]]:
    """把每个字节映射回它在字符串里的 [start, end) 区间。奇数位返回 None。"""
    spans: List[Tuple[int, int]] = []
    pending = -1
    i = 0
    while i < len(text):
        c = text[i]
        if c == "0" and i + 1 < len(text) and text[i + 1] in "xX":
            i += 2
            continue
        if c not in "0123456789abcdefABCDEF":
            i += 1
            continue
        if pending < 0:
            pending = i
        else:
            spans.append((pending, i + 1))
            pending = -1
        i += 1
    if pending >= 0:
        return None
    return spans


# --------------------------------------------------------------------- TLV / 定位


def walk_chain(data: bytes) -> Optional[Tuple[int, int]]:
    i = 0
    while i + 2 <= len(data):
        tag = data[i]
        length = data[i + 1]
        if i + 2 + length > len(data):
            return None
        if tag == TAG_ID and length in UID_LENGTHS:
            return (i, length)
        if tag == 0 and length == 0:
            return None
        i += 2 + length
    return None


def tail_is_coherent(data: bytes, start: int) -> bool:
    if start == len(data):
        return True
    i = start
    while i < len(data):
        if len(data) - i < 2:
            return False
        tag = data[i]
        length = data[i + 1]
        if tag == 0 and length == 0:
            return all(b == 0 for b in data[i:])
        if i + 2 + length > len(data):
            return False
        i += 2 + length
    return True


def locate(data: bytes) -> Optional[Tuple[int, int]]:
    span = walk_chain(data)
    if span:
        return span
    candidates = []
    for i in range(0, len(data) - 1):
        if data[i] != TAG_ID:
            continue
        length = data[i + 1]
        if length in UID_LENGTHS and i + 2 + length <= len(data):
            candidates.append((i, length))
    if not candidates:
        return None
    if len(candidates) == 1:
        return candidates[0]
    coherent = [c for c in candidates if tail_is_coherent(data, c[0] + 2 + c[1])]
    return coherent[0] if coherent else candidates[0]


def read_uid(data: bytes) -> Optional[bytes]:
    span = locate(data)
    if not span:
        return None
    off, length = span
    return data[off + 2 : off + 2 + length]


# ------------------------------------------------------------------ 文本区块定位


def find_block(text: str, key: str) -> Optional[Tuple[int, int]]:
    pattern = re.compile(r"(?<![A-Za-z0-9_.])" + re.escape(key) + r"\s*=\s*\{")
    m = pattern.search(text)
    if not m:
        return None
    brace = m.end() - 1
    depth = 1
    i = brace + 1
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return (brace + 1, i)
        i += 1
    return None


def list_keys(text: str) -> List[str]:
    seen: List[str] = []
    for m in re.finditer(r"(?m)^[ \t]*([A-Z][A-Z0-9_]*)\s*=\s*\{", text):
        k = m.group(1)
        if k not in seen:
            seen.append(k)
    return seen


def _encode_like(inner: str, payload: bytes) -> str:
    body = inner.strip()
    upper = not any(c in "abcdef" for c in body)
    if "," in body:
        sep = ", " if ", " in body else ","
    elif ":" in body:
        sep = ": " if ": " in body else ":"
    elif " " in body or "\t" in body:
        sep = " "
    else:
        sep = ""
    return encode(payload, upper=upper, sep=sep)


def patch_uid(text: str, key: str, uid: bytes) -> Tuple[str, Optional[bytes], bool]:
    """返回 (新文本, 原 UID, 是否走的是精确替换路径)。"""
    block = find_block(text, key)
    if not block:
        raise ConfError(f"找不到 `{key}={{ … }}` 区块")
    vstart, vend = block
    inner = text[vstart:vend]
    data = decode(inner)
    span = locate(data)
    if not span:
        raise ConfError(f"`{key}` 区块里找不到 NXP TAG_ID(0x33)")
    off, length = span
    old_uid = data[off + 2 : off + 2 + length]

    spans = index_bytes(inner)
    if spans is not None and len(spans) == len(data):
        same = len(old_uid) == len(uid)
        replace_from = off + 2 if same else off + 1
        payload = uid if same else bytes([len(uid)]) + uid
        char_start = spans[replace_from][0]
        char_end = spans[off + 2 + length - 1][1]
        new_hex = _encode_like(inner, payload)
        new_inner = inner[:char_start] + new_hex + inner[char_end:]
        return (text[:vstart] + new_inner + text[vend:], old_uid, True)

    # 兜底：整块重排
    head = data[:off] + bytes([TAG_ID, len(uid)]) + uid + data[off + 2 + length :]
    new_inner = _encode_like(inner, head)
    lead = inner[: len(inner) - len(inner.lstrip())]
    trail = inner[len(inner.rstrip()) :]
    return (text[:vstart] + lead + new_inner + trail + text[vend:], old_uid, False)


# ---------------------------------------------------------------------- 主逻辑


def cmd_dump(args) -> int:
    text = open(args.path, "r", encoding="utf-8", errors="replace").read()
    keys = list_keys(text)
    print(f"文件      : {args.path}")
    print(f"大小      : {len(text)} 字符")
    print(f"配置块    : {', '.join(keys) if keys else '(无)'}")

    for key in keys:
        block = find_block(text, key)
        if not block:
            continue
        try:
            data = decode(text[block[0] : block[1]])
        except ConfError as exc:
            print(f"\n[{key}] 解码失败：{exc}")
            continue
        print(f"\n[{key}] {len(data)} 字节")
        uid = read_uid(data)
        if uid:
            print(f"  UID     : {encode(uid, sep=':')}  ({len(uid)} 字节)")
        span = locate(data)
        if span:
            print(f"  TAG_ID  : 偏移 {span[0]}, 长度 {span[1]}")
        else:
            print("  TAG_ID  : 未找到")
    return 0


def cmd_patch(args) -> int:
    text = open(args.path, "r", encoding="utf-8", errors="replace").read()
    try:
        uid = decode(args.uid)
    except ConfError as exc:
        print(f"UID 不合法：{exc}", file=sys.stderr)
        return 2
    if len(uid) not in UID_LENGTHS:
        print(f"UID 必须是 4/7/10 字节，收到 {len(uid)} 字节", file=sys.stderr)
        return 2

    try:
        new_text, old_uid, precise = patch_uid(text, args.key, uid)
    except ConfError as exc:
        print(f"改写失败：{exc}", file=sys.stderr)
        return 1

    print(f"配置块   : {args.key}")
    print(f"原 UID   : {encode(old_uid, sep=':') if old_uid else '(无)'}")
    print(f"新 UID   : {encode(uid, sep=':')}")
    print(f"替换方式 : {'精确替换（排版零扰动）' if precise else '整块重排'}")

    diff = list(
        difflib.unified_diff(
            text.splitlines(keepends=True),
            new_text.splitlines(keepends=True),
            fromfile="原始",
            tofile="改写后",
            n=2,
        )
    )
    if diff:
        sys.stdout.write("".join(diff))
    else:
        print("（内容无变化）")

    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(new_text)
        print(f"\n已写入 {args.output}")
    return 0


# -------------------------------------------------------------------- self-test

SAMPLE = """\
###############################################################################
# NXP NFC 配置（片段，仅用于自检）
###############################################################################
NXP_CORE_CONF={ 20, 02, 2D,
                02, 08, 05,
                33, 04, 42, 4D, 44, 4A,
                35, 06, 01, 01, 01, 02, 07, 46 }

NXP_RF_CONF={ 20, 02, 39,
              33, 04, AA, BB, CC, DD }

NXP_CORE_CONF_EXTN={ 20, 02, 1A }
"""

SAMPLE_BYTES = bytes.fromhex("20022D0208053304424D444A3506010101020746")


def _assert(cond: bool, message: str) -> None:
    if not cond:
        raise AssertionError(message)


def cmd_self_test(_args) -> int:
    failures = 0

    def check(name: str, fn) -> None:
        nonlocal failures
        try:
            fn()
            print(f"  PASS  {name}")
        except AssertionError as exc:
            failures += 1
            print(f"  FAIL  {name}: {exc}")

    # --- hex ---
    check("decode 多分隔符", lambda: _assert(
        decode("42:4D:44:4A") == decode("42,4D,44,4A") == decode("42 4D 44 4A") == b"\x42\x4d\x44\x4a",
        "分隔符处理不一致"))
    check("decode 保留前导 0x00", lambda: _assert(
        decode("00 00 11 22") == b"\x00\x00\x11\x22", "前导 0x00 被吞"))

    # --- 定位 ---
    check("locate 命中 TAG_ID", lambda: _assert(
        locate(SAMPLE_BYTES) == (6, 4), f"实际 {locate(SAMPLE_BYTES)}"))
    check("read_uid", lambda: _assert(
        read_uid(SAMPLE_BYTES) == b"\x42\x4d\x44\x4a", "UID 读取错误"))
    check("纯 TLV 走严格链", lambda: _assert(
        locate(bytes.fromhex("35020101 3304424D444A 280104".replace(" ", ""))) == (4, 4), "严格链定位错误"))

    # --- 文本改写 ---
    def equal_length() -> None:
        new_text, old, precise = patch_uid(SAMPLE, KEY_NXP_CORE_CONF, b"\x11\x22\x33\x44")
        _assert(precise, "应走精确替换")
        _assert(old == b"\x42\x4d\x44\x4a", "原 UID 不对")
        _assert(len(new_text) == len(SAMPLE), f"长度应不变，{len(new_text)} vs {len(SAMPLE)}")
        _assert("33, 04, 11, 22, 33, 44," in new_text, "新值未写入预期位置")
        _assert("42, 4D, 44, 4A" not in new_text, "旧值残留")
        _assert("33, 04, AA, BB, CC, DD" in new_text, "干扰区块被改")
        _assert(len(new_text.splitlines()) == len(SAMPLE.splitlines()), "行数变化")

    check("等长改写零扰动", equal_length)

    def grow_to_seven() -> None:
        uid = bytes.fromhex("424D444A001122")
        new_text, old, precise = patch_uid(SAMPLE, KEY_NXP_CORE_CONF, uid)
        _assert(precise, "应走精确替换")
        _assert(len(new_text) == len(SAMPLE) + 12, f"应只多 12 字符，实际 {len(new_text) - len(SAMPLE)}")
        _assert("33, 07, 42, 4D, 44, 4A, 00, 11, 22," in new_text, "length 字段没跟着改")
        block = find_block(new_text, KEY_NXP_CORE_CONF)
        _assert(read_uid(decode(new_text[block[0]:block[1]])) == uid, "回读不一致")

    check("4 字节改 7 字节", grow_to_seven)

    def round_trip() -> None:
        step1, _, _ = patch_uid(SAMPLE, KEY_NXP_CORE_CONF, bytes.fromhex("424D444A001122"))
        step2, _, _ = patch_uid(step1, KEY_NXP_CORE_CONF, b"\x42\x4d\x44\x4a")
        _assert(step2 == SAMPLE, "往复改写后应逐字符还原")

    check("4→7→4 往复还原", round_trip)

    def ten_bytes() -> None:
        uid = bytes.fromhex("424D444A001122334455")
        new_text, _, _ = patch_uid(SAMPLE, KEY_NXP_CORE_CONF, uid)
        line = new_text.splitlines()[5].strip()
        _assert(line == "33, 0A, 42, 4D, 44, 4A, 00, 11, 22, 33, 44, 55,",
                f"实际第 6 行: {line!r}")

    check("10 字节三长 UID", ten_bytes)

    def colon_style() -> None:
        text = "LISTEN_CONF={ 20:02:2d:02:08:05:33:04:42:4d:44:4a }"
        out, _, _ = patch_uid(text, "LISTEN_CONF", b"\x11\x22\x33\x44")
        _assert(out == "LISTEN_CONF={ 20:02:2d:02:08:05:33:04:11:22:33:44 }", f"实际 {out!r}")

    check("冒号+小写风格", colon_style)

    def space_style() -> None:
        text = "CONF={ 20 02 2D 02 08 05 33 04 42 4D 44 4A }"
        out, _, _ = patch_uid(text, "CONF", b"\x11\x22\x33\x44")
        _assert(out == "CONF={ 20 02 2D 02 08 05 33 04 11 22 33 44 }", f"实际 {out!r}")

    check("纯空格风格", space_style)

    def word_boundary() -> None:
        block = find_block(SAMPLE, KEY_NXP_CORE_CONF)
        inner = SAMPLE[block[0]:block[1]]
        _assert("1A" not in inner, "NXP_CORE_CONF 匹配到了 NXP_CORE_CONF_EXTN")

    check("词边界", word_boundary)

    def keys() -> None:
        got = sorted(list_keys(SAMPLE))
        want = sorted([KEY_NXP_CORE_CONF, KEY_NXP_CORE_CONF_EXTN, "NXP_RF_CONF"])
        _assert(got == want, f"实际 {got}")

    check("list_keys", keys)

    print()
    if failures:
        print(f"{failures} 项失败")
        return 1
    print("全部通过")
    return 0


# ------------------------------------------------------------------------ entry


def main(argv: List[str]) -> int:
    parser = argparse.ArgumentParser(description="libnfc-nxp.conf 解析 / UID 改写工具")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_dump = sub.add_parser("dump", help="列出所有配置块与其中的 UID")
    p_dump.add_argument("path")
    p_dump.set_defaults(func=cmd_dump)

    p_patch = sub.add_parser("patch", help="改写某个配置块里的 UID 并打印 diff")
    p_patch.add_argument("path")
    p_patch.add_argument("uid", help="新 UID，例如 42:4D:44:4A")
    p_patch.add_argument("-k", "--key", default=KEY_NXP_CORE_CONF)
    p_patch.add_argument("-o", "--output", default=None)
    p_patch.set_defaults(func=cmd_patch)

    p_test = sub.add_parser("self-test", help="跑与 Kotlin 单元测试等价的断言")
    p_test.set_defaults(func=cmd_self_test)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
