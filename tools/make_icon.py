#!/usr/bin/env python3
"""把 AI 生成的图标源图加工成 Android 图标资源。

用法：
    python3 tools/make_icon.py <源图.png> [--raw]

做三件事：

1. **alpha 归一化**。AI 导出的 PNG 整张 alpha 是 252/253 而不是 255，
   直接当图标会让整个应用显得比别的图标淡一层。

2. **色阶重建（默认关闭）**。源图是低对比度的柔和风格，默认原样保留。
   只有显式加 `--punch` 才会把底色压平、图案拉开 —— 图案会清楚很多，
   但同时也会毁掉原图那种朦胧的质感。

   > 这里踩过一次：最早的版本默认就开增强，理由是"图案亮度只占 1.8%、
   > 缩小后会看不见"。技术上没错，但**技术适配不该顺手改配色** ——
   > 结果是用户想要的柔和高级感被改成了硬边高对比。默认值必须是忠实原图。

3. **按 Android 规范导出**：
   - 自适应图标前景层：内容缩到画布的 62%，落在中心安全区内
     （108dp 画布里只有中心约 66dp 不会被各种遮罩形状裁掉）
   - 传统 PNG 兜底：API 24–25 用，内容占 88%

源图本身是"已裁成圆形"的成品预览稿，所以它的内容直接就是内容圆的直径。
"""
import os
import sys

from PIL import Image, ImageDraw

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(REPO, "app/src/main/res")

# 传统图标边长（48dp 基准）
LEGACY_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# 自适应图标前景层边长（108dp 基准）
ADAPTIVE_SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# 自适应图标的安全区：内容直径占画布的比例。留到 62% 是为了任何遮罩形状都切不到。
ADAPTIVE_SAFE_RATIO = 0.62
# 传统图标不会被系统裁切，可以占大一些
LEGACY_RATIO = 0.88

# 自适应图标的背景层颜色：比前景底色（重建后约 #FAFAFA）略深一点，
# 这样圆内容在背景上仍有淡淡的边界，不至于糊成一块白。
BACKGROUND = (242, 239, 238)
BACKGROUND_HEX = "#%02X%02X%02X" % BACKGROUND


def build_lut():
    """亮度重建表。断点全部来自对源图的实测采样，不是拍脑袋：

    底色 237–244 / 倒三角 221–232 / 文字笔画 180–205

    所以 236 是"底色下界" —— 卡在这里才能既把底色噪点压平，
    又不把倒三角（226）一起抹掉。上一版把阈值定在 220，
    结果倒三角被当成底噪压平，图标里只剩文字。
    """
    lut = []
    for v in range(256):
        if v >= 236:
            lut.append(250)
        elif v >= 200:
            lut.append(int(110 + (v - 200) * 140 / 36))
        else:
            lut.append(max(0, int(110 - (200 - v) * 1.5)))
    return lut


def load_source(path, punch):
    im = Image.open(path).convert("RGBA")

    # 1) alpha 归一化：<12 视为全透明，其余一律不透明
    alpha = im.getchannel("A").point(lambda v: 0 if v < 12 else 255)
    im.putalpha(alpha)

    # 2) 色阶重建（默认不做，见文件头说明）
    if punch:
        lut = build_lut()
        im = im.point(lut * 3 + list(range(256)))

    # 只保留非透明部分 —— 源图四角是透明的圆形遮罩
    content = im.crop(im.getbbox())
    return content


def write_png(image, relative_path):
    path = os.path.join(RES, relative_path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, "PNG", optimize=True)
    return path


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__)
        return 1
    punch = "--punch" in sys.argv

    content = load_source(args[0], punch)
    print(f"源内容尺寸：{content.size[0]}x{content.size[1]}" + ("（--punch 已增强对比度）" if punch else "（忠实原样）"))

    written = []

    # 自适应图标前景层：透明画布 + 居中的内容
    for dpi, size in ADAPTIVE_SIZES.items():
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        target = max(1, int(round(size * ADAPTIVE_SAFE_RATIO)))
        scaled = content.resize((target, target), Image.LANCZOS)
        offset = (size - target) // 2
        canvas.paste(scaled, (offset, offset), scaled)
        written.append(write_png(canvas, f"mipmap-{dpi}/ic_launcher_foreground.png"))

    # 传统图标：透明背景 + 内容。
    #
    # 这里**不能**用背景色填满整个方形 —— lint 的 IconLauncherShape 会报
    # "launcher icons should not fill every pixel of their square region"，
    # 而且透明四角本来就更贴近源图的形态（圆形内容）。
    for dpi, size in LEGACY_SIZES.items():
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        target = max(1, int(round(size * LEGACY_RATIO)))
        scaled = content.resize((target, target), Image.LANCZOS)
        offset = (size - target) // 2
        canvas.paste(scaled, (offset, offset), scaled)
        written.append(write_png(canvas, f"mipmap-{dpi}/ic_launcher.png"))

        # 圆形版：把方形画布裁成圆
        round_img = canvas.copy()
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        round_img.putalpha(mask)
        written.append(write_png(round_img, f"mipmap-{dpi}/ic_launcher_round.png"))

    # 自适应图标定义（API 26+）。
    #
    # <monochrome> 是 Android 13 主题图标用的单色层，缺了 lint 会报
    # MonochromeLauncherIcon；正好复用通知那颗单色漏斗。
    adaptive_xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@drawable/ic_notification" />\n'
        "</adaptive-icon>\n"
    )
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        path = os.path.join(RES, "mipmap-anydpi-v26", name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(adaptive_xml)
        written.append(path)

    # 背景色
    values_dir = os.path.join(RES, "values")
    os.makedirs(values_dir, exist_ok=True)
    with open(os.path.join(values_dir, "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                f'    <color name="ic_launcher_background">{BACKGROUND_HEX}</color>\n'
                "</resources>\n")
    written.append(os.path.join(values_dir, "ic_launcher_background.xml"))

    print(f"写入 {len(written)} 个文件：")
    for p in written:
        print("  " + os.path.relpath(p, REPO))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
