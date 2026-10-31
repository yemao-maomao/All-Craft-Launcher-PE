#!/usr/bin/env python3
"""一次性图标生成脚本：把用户提供的 Canva 源图转为 Android 自适应图标 foreground PNG（各密度）。

判定逻辑：
- 四角颜色一致（容差 40）→ 视为纯色底图：contain 适配（留出安全区 72%），背景色取四角色；
- 否则视为满幅图片：中心裁切为正方形，全出血作为 foreground，背景取白色。
生成后打印模式与背景色，用于回填 values/colors.xml 与自适应图标 XML。
"""
import sys
import os
from PIL import Image, ImageOps

SRC = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\vshuf\Downloads\Canva - download_20261030_001848_0000 (1).jpg"
OUT = r"D:\mc1\app\src\main\res"
PREVIEW = r"D:\mc1\docs\assets\icon-preview.png"
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def corner_color(im):
    w, h = im.size
    return [im.getpixel((x, y)) for x, y in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1))]


def close(c1, c2, tol=40):
    return all(abs(a - b) <= tol for a, b in zip(c1[:3], c2[:3]))


def main():
    im = ImageOps.exif_transpose(Image.open(SRC)).convert("RGB")
    w, h = im.size
    corners = corner_color(im)
    uniform = all(close(c, corners[0]) for c in corners[1:])
    bg = corners[0] if uniform else (255, 255, 255)

    if uniform:
        mode = "contain"
        side = max(w, h)
        canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        art = ImageOps.contain(im, (int(side * 0.72), int(side * 0.72)))
        canvas.paste(art, ((side - art.width) // 2, (side - art.height) // 2))
    else:
        mode = "crop"
        side = min(w, h)
        left, top = (w - side) // 2, (h - side) // 2
        canvas = im.crop((left, top, left + side, top + side)).convert("RGBA")

    for d, px in DENSITIES.items():
        p = os.path.join(OUT, "mipmap-" + d, "ic_launcher_foreground.png")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        canvas.resize((px, px), Image.LANCZOS).save(p)

    # 预览图（foreground + 背景合成，512x512）
    prev = Image.new("RGB", (512, 512), bg)
    fg = canvas.resize((368, 368), Image.LANCZOS) if mode == "contain" else canvas.resize((512, 512), Image.LANCZOS)
    if fg.mode == "RGBA":
        prev.paste(fg, ((512 - fg.width) // 2, (512 - fg.height) // 2), fg)
    else:
        prev.paste(fg, ((512 - fg.width) // 2, (512 - fg.height) // 2))
    os.makedirs(os.path.dirname(PREVIEW), exist_ok=True)
    prev.save(PREVIEW)

    print("mode={} bg=#{:02X}{:02X}{:02X} src={}x{}".format(mode, bg[0], bg[1], bg[2], w, h))


if __name__ == "__main__":
    main()
