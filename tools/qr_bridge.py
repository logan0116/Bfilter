#!/usr/bin/env python3
"""
把手机 App 里的登录二维码**持续同步**到 PC 上的一张图片，并监控扫码状态。

为什么需要它：
  1. **物理限制** —— 二维码和哔哩哔哩 App 在同一块屏幕上，用户没法用自己扫自己。
     必须把码搬到另一台设备（也就是这台 PC）上才能扫。
  2. **二维码只有约 3 分钟寿命** —— App 过期后会自动换新码，PC 上的静态图会失效。
     所以需要持续同步，而不是截一张就完事。

性能上的关键取舍：`uiautomator dump` 一次约 7 秒，且短时间内反复调用容易挂起
（第一版就是这么卡死的）。所以这里**把两件事拆开**：
  - 抓图只走 `screencap`（约 3 秒），并用上次 dump 到的坐标直接裁剪；
  - 只在每 STATUS_EVERY 轮才 dump 一次，用来读状态、并在换码后刷新坐标。

用法：
    python3 tools/qr_bridge.py                # 持续同步到 <仓库>/qr-login.png
    python3 tools/qr_bridge.py --once         # 只同步一次（调试用）
    python3 tools/qr_bridge.py /path/out.png  # 指定输出路径
"""
from __future__ import annotations

import io
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui_probe as u  # noqa: E402

DEFAULT_OUT = os.path.join(u.REPO, "qr-login.png")

# 账号页出现这些字样 = 已登录（二维码面板已被账号信息替换）
LOGGED_IN_MARKERS = ("退出登录", "UID ")
QR_DESC = "登录二维码"
SCAN_BUTTONS = ("扫码登录", "重新获取")

# 每抓 N 次图做一次 dump（dump 慢，且频繁调用会让 uiautomator 挂住）
STATUS_EVERY = 5
SLEEP_SEC = 2.5


def texts_of(root) -> list[str]:
    return [n["text"] for n in u.nodes(root) if n["text"]]


def find_qr(root):
    return next((n for n in u.nodes(root) if n["desc"] == QR_DESC), None)


def read_state(root) -> tuple[str, str]:
    """返回 (LOGGED_IN | OK, 状态文字)"""
    texts = texts_of(root)
    if any(marker in " ".join(texts) for marker in LOGGED_IN_MARKERS):
        return "LOGGED_IN", ""
    status = next(
        (t for t in texts
         if ("扫码" in t and "扫一扫" not in t) or "确认" in t
         or "刷新" in t or "过期" in t or "失效" in t),
        "等待扫码",
    )
    return "OK", status


def ensure_panel() -> str:
    """确保手机上正显示二维码面板。返回 OK / LOGGED_IN / NO_BUTTON"""
    root = u.dump_xml()
    state, _ = read_state(root)
    if state == "LOGGED_IN":
        return "LOGGED_IN"
    if find_qr(root):
        return "OK"

    tab = u.find(root, text="账号")
    if tab:
        u.tap_node(tab)
        time.sleep(1.5)
        root = u.dump_xml()

    button = next((u.find(root, text=t) for t in SCAN_BUTTONS if u.find(root, text=t)), None)
    if button is None:
        return "NO_BUTTON"
    u.tap_node(button)
    time.sleep(4.5)  # 等二维码生成
    return "OK"


def capture(box):
    """截屏并裁出二维码区域（box 为 None 时只返回整图）"""
    raw = u.adb_bytes("exec-out", "screencap -p")
    from PIL import Image
    img = Image.open(io.BytesIO(raw))
    return img.crop(box) if box else None


def main(argv: list[str]) -> int:
    positional = [a for a in argv if not a.startswith("--")]
    out_path = os.path.abspath(positional[0]) if positional else DEFAULT_OUT

    state = ensure_panel()
    if state == "LOGGED_IN":
        print("手机上已经是登录状态，无需扫码。", flush=True)
        return 0
    if state == "NO_BUTTON":
        print("找不到「扫码登录」按钮，请确认 App 停在前台。", file=sys.stderr, flush=True)
        return 2

    print(f"二维码输出到：{out_path}", flush=True)
    print("App 里二维码过期会自动换新码，本脚本会跟着更新这张图。\n", flush=True)

    box = None
    status = "等待扫码"
    last_written = None
    tick = 0

    while True:
        tick += 1
        try:
            # —— 慢路径：dump 读状态 / 定位（每 STATUS_EVERY 轮一次）——
            if box is None or tick % STATUS_EVERY == 1:
                root = u.dump_xml()
                state, status = read_state(root)
                if state == "LOGGED_IN":
                    print("\n✅ 检测到手机上已完成登录，二维码桥退出。", flush=True)
                    return 0

                qr = find_qr(root)

                # ⚠️ 顺序很关键：二维码**失效后 ImageView 依然留在界面上**（只是变成一张死图），
                # 所以必须先判失效、再取坐标。反过来写会一直命中 `qr`，
                # 「重新获取」永远触发不了 —— 第一版就是这么对着失效码空转了 8 分钟。
                if "失效" in status or "过期" in status:
                    refresh = u.find(root, text="重新获取")
                    if refresh:
                        print(f"\n[{time.strftime('%H:%M:%S')}] 二维码已失效，自动重新获取", flush=True)
                        u.tap_node(refresh)
                        box = None
                        time.sleep(4)
                        continue
                elif qr:
                    box = qr["bounds"]

            # —— 快路径：截图 + 裁剪 + 落盘 ——
            crop = capture(box)
            if crop is not None:
                tmp = out_path + ".tmp"
                # 必须显式指定格式：`.tmp` 后缀不足以让 PIL 推断编码格式
                crop.save(tmp, format="PNG")
                os.replace(tmp, out_path)  # 原子替换，避免看图的人读到半张
                stamp = time.strftime("%H:%M:%S")
                if status != last_written:
                    print(f"[{stamp}] 二维码已更新 → {out_path}", flush=True)
                    print(f"          状态：{status}", flush=True)
                    last_written = status
                else:
                    print(f"[{stamp}] 同步中… {status}", end="\r", flush=True)

        except Exception as exc:  # 单轮失败不该把桥弄断
            print(f"[{time.strftime('%H:%M:%S')}] 本轮出错（继续）：{exc}", flush=True)

        time.sleep(SLEEP_SEC)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
