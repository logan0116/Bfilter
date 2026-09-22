#!/usr/bin/env python3
"""
靠 uiautomator 语义树驱动 Bfilter 界面 —— 用于真机回归验证。

为什么需要它：Compose 没有 view id，`adb shell input tap` 只能靠坐标，
而坐标会随字号/分辨率变。语义树能直接按文案或 contentDescription 定位，
换设备也不用改脚本。

前置：手机已连 adb（本工程走 tools/adb-wrap.sh，借 privileged 容器）。
用法：
    python3 tools/ui_probe.py dump                 # 打印当前界面的可点元素
    python3 tools/ui_probe.py tap-text 只看关注     # 按文案点击
    python3 tools/ui_probe.py tap-desc 添加 UP 主   # 按描述点击
    python3 tools/ui_probe.py text 946974          # 向当前焦点输入框输入
    python3 tools/ui_probe.py shot shots/x.png     # 截图
    python3 tools/ui_probe.py add-uids 946974 ...  # 批量按 UID 添加白名单
    python3 tools/ui_probe.py tap-xy 540 640       # 按坐标点击（语义树里没有节点时用）
"""

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB_WRAP = os.path.join(REPO, "tools", "adb-wrap.sh")
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)]\[(\d+),(\d+)]")


def adb(*args, timeout=120):
    result = subprocess.run(
        [ADB_WRAP, *args], capture_output=True, text=True, timeout=timeout
    )
    return result.stdout


def adb_bytes(*args, timeout=120):
    """二进制输出（截图）必须走 bytes，不能用 text=True 再编码回去"""
    result = subprocess.run(
        [ADB_WRAP, *args], capture_output=True, timeout=timeout
    )
    return result.stdout


def dump_xml(retries=3):
    # dump 与 cat 合并成一次容器往返 —— adb-wrap 每次调用要起一个 privileged 容器，
    # 拆成两次会让每个动作慢一倍。
    for attempt in range(retries):
        raw = adb(
            "shell",
            "uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1; cat /sdcard/window_dump.xml",
            timeout=45,
        )
        start = raw.find("<?xml")
        if start >= 0:
            raw = raw[start:]
        try:
            return ET.fromstring(raw.strip())
        except ET.ParseError:
            if attempt == retries - 1:
                raise
            time.sleep(1.5)
    raise RuntimeError("unreachable")


def nodes(root):
    out = []
    for node in root.iter("node"):
        match = BOUNDS_RE.match(node.get("bounds") or "")
        if not match:
            continue
        x1, y1, x2, y2 = (int(g) for g in match.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        out.append(
            {
                "text": node.get("text", ""),
                "desc": node.get("content-desc", ""),
                "cls": node.get("class", ""),
                "clickable": node.get("clickable") == "true",
                "cx": (x1 + x2) // 2,
                "cy": (y1 + y2) // 2,
                "bounds": (x1, y1, x2, y2),
            }
        )
    return out


def find(root, text=None, desc=None, contains=True):
    """按文案或描述找最内层（面积最小）的匹配节点 —— 最内层才是真正可点的那个"""
    needle = text if text is not None else desc
    if needle is None:
        return None
    candidates = []
    for node in nodes(root):
        haystack = node["text"] if text is not None else node["desc"]
        if not haystack:
            continue
        hit = needle in haystack if contains else haystack == needle
        if hit:
            x1, y1, x2, y2 = node["bounds"]
            candidates.append(((x2 - x1) * (y2 - y1), node))
    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0])
    return candidates[0][1]


def tap_node(node):
    adb("shell", f"input tap {node['cx']} {node['cy']}")
    time.sleep(0.6)


def main(argv):
    if not argv:
        print(__doc__)
        return 1
    command, rest = argv[0], argv[1:]

    if command == "dump":
        root = dump_xml()
        for node in nodes(root):
            label = node["text"] or node["desc"]
            # 空的 Compose TextField 在语义树里 text 为空，只靠文案会整个漏掉它
            if not label and "EditText" in node["cls"]:
                label = "<空输入框>"
            if label:
                mark = "*" if node["clickable"] else " "
                print(f"{mark} [{node['cx']:>4},{node['cy']:>4}] {node['cls']:<28} {label!r}")
        return 0

    if command == "tap-xy":
        x, y = int(rest[0]), int(rest[1])
        adb("shell", f"input tap {x} {y}")
        time.sleep(0.6)
        print(f"已点击 ({x},{y})")
        return 0

    if command in ("tap-text", "tap-desc", "tap-text-contains", "tap-desc-contains"):
        # 默认**精确匹配**：包含匹配会把 "UP 主" 命中 "UP 主昵称" 这种 label，
        # 点错地方后整个流程会以"找不到按钮"的形式失败，很难查。
        keyword = " ".join(rest)
        contains = command.endswith("-contains")
        is_text = command.startswith("tap-text")
        root = dump_xml()
        node = find(root, contains=contains, **({"text" if is_text else "desc": keyword}))
        if not node:
            print(f"未找到：{keyword}", file=sys.stderr)
            return 2
        tap_node(node)
        print(f"已点击 {keyword!r} @ ({node['cx']},{node['cy']})")
        return 0

    if command == "add-uids":
        # 从确定状态开始：重启到前端页面。
        # （不要用 KEYCODE_BACK 去"关对话框"——对话框早就关了的话，BACK 会直接退出 app。）
        adb("shell", "input keyevent KEYCODE_WAKEUP")
        adb("shell", "am force-stop com.mozinodey.bfilter")
        time.sleep(0.6)
        adb("shell", "am start -n com.mozinodey.bfilter/.MainActivity")
        time.sleep(4.0)

        root = dump_xml()
        tab = find(root, text="UP 主")
        if tab:
            tap_node(tab)

        results = []
        for uid in rest:
            root = dump_xml()
            fab = find(root, desc="添加 UP 主")
            if not fab:
                results.append((uid, "找不到添加按钮"))
                continue
            tap_node(fab)

            field = find(dump_xml(), text="或直接填 UID")
            if not field:
                results.append((uid, "找不到 UID 输入框"))
                continue
            tap_node(field)
            adb("shell", f"input text {uid}")
            time.sleep(0.5)

            button = find(dump_xml(), desc="按 UID 添加")
            if not button:
                results.append((uid, "找不到确认按钮"))
                continue
            tap_node(button)
            time.sleep(3.5)  # 等 card 接口返回

            dialog = dump_xml()
            texts = [n["text"] for n in nodes(dialog) if n["text"]]
            fans = next((t for t in texts if "粉丝" in t), "")
            name = ""
            for i, t in enumerate(texts):
                if "粉丝" in t and i > 0:
                    name = texts[i - 1]
                    break
            if not name:
                name = next((t for t in texts if t not in (
                    "添加 UP 主", "UP 主昵称", "或直接填 UID", "完成", uid) and "粉丝" not in t), "?")
            results.append((uid, f"{name}  {fans}"))

            done = find(dialog, text="完成")
            if done:
                tap_node(done)
            time.sleep(0.8)

        print("批量添加结果：")
        for uid, outcome in results:
            print(f"  {uid:<18} {outcome}")
        return 0

    if command == "text":
        adb("shell", f"input text {rest[0]}")
        time.sleep(0.4)
        return 0

    if command == "key":
        adb("shell", f"input keyevent {rest[0]}")
        time.sleep(0.4)
        return 0

    if command == "shot":
        target = rest[0]
        if not os.path.isabs(target):
            target = os.path.join(REPO, target)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as handle:
            handle.write(adb_bytes("exec-out", "screencap -p"))
        print(f"已保存 {target} ({os.path.getsize(target)} 字节)")
        return 0

    print(f"未知命令：{command}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
