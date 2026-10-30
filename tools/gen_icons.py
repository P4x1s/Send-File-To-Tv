#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SEND FILE TO TV / SFTT 图标生成器
=================================

零第三方依赖（只用标准库 zlib + struct）生成多密度 PNG 图标，
并同时输出自适应图标（adaptive icon）所需的矢量前景/背景。

设计要点（贴合「发送文件到电视」这一功能）：
  - 背景：深蓝紫 -> 珊瑚红 对角渐变（与应用主题色 #E94560 一致）
  - 主体：白色电视轮廓（屏幕 + 底座）
  - 焦点：珊瑚红向上箭头（把文件「送进」电视）

用法：
    python tools/gen_icons.py
"""

import math
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.normpath(os.path.join(HERE, "..", "android"))

# ---------------------------------------------------------------- 主题色
BG_START = (0x2E, 0x2A, 0x5C)   # 深紫蓝
BG_END = (0xE9, 0x45, 0x60)     # 珊瑚红
WHITE = (0xFF, 0xFF, 0xFF)
CORAL = (0xFF, 0x5A, 0x72)      # 箭头（比背景终点略亮，保证对比）
DARK = (0x1A, 0x1A, 0x2E)

SS = 4  # 超采样倍率（抗锯齿）


# ---------------------------------------------------------------- PNG 输出
def write_png(path, w, h, rgba):
    """写出 8bit RGBA PNG。rgba 为长度 w*h*4 的 bytearray。"""
    raw = bytearray()
    for y in range(h):
        raw.append(0)  # filter type 0
        raw.extend(rgba[y * w * 4:(y + 1) * w * 4])

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    print("  ->", os.path.relpath(path, HERE), f"({w}x{h})")


# ---------------------------------------------------------------- 几何
def rounded_rect_contains(x, y, x0, y0, x1, y1, r):
    """点是否落在圆角矩形内（归一化坐标 0..1）。"""
    if x < x0 or x > x1 or y < y0 or y > y1:
        return False
    cx = min(max(x, x0 + r), x1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    dx = x - cx
    dy = y - cy
    return dx * dx + dy * dy <= r * r + 1e-9


def rounded_rect_stroke(x, y, x0, y0, x1, y1, r, t):
    """点是否落在圆角矩形描边上（t 为线宽）。"""
    if not rounded_rect_contains(x, y, x0, y0, x1, y1, r):
        return False
    inner = rounded_rect_contains(x, y, x0 + t, y0 + t, x1 - t, y1 - t,
                                   max(r - t, 0.0))
    return not inner


def segment_contains(x, y, ax, ay, bx, by, t):
    """点是否落在线段上（t 为线宽的半宽）。"""
    dx = bx - ax
    dy = by - ay
    len2 = dx * dx + dy * dy
    if len2 <= 0:
        return (x - ax) ** 2 + (y - ay) ** 2 <= t * t
    u = ((x - ax) * dx + (y - ay) * dy) / len2
    u = min(max(u, 0.0), 1.0)
    px = ax + u * dx
    py = ay + u * dy
    return (x - px) ** 2 + (y - py) ** 2 <= t * t


def polygon_contains(x, y, pts):
    """射线法判断点是否在多边形内。"""
    inside = False
    n = len(pts)
    j = n - 1
    for i in range(n):
        xi, yi = pts[i]
        xj, yj = pts[j]
        if (yi > y) != (yj > y):
            x_cross = (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi
            if x < x_cross:
                inside = not inside
        j = i
    return inside


def circle_contains(x, y, cx, cy, r):
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r + 1e-9


# ---------------------------------------------------------------- 图层
def build_layers(mode):
    """
    mode = "legacy"  传统图标（带圆角背景、留白更大）
    mode = "adaptive" 自适应前景（内容限制在安全区内）
    """
    if mode == "legacy":
        # 电视与箭头整体缩小一点，留出圆角背景的呼吸空间
        s = 1.0
        ox = oy = 0.0
    else:
        # 自适应前景：内容缩到中心 ~66%（安全区）
        s = 0.70
        ox = (1 - s) / 2
        oy = (1 - s) / 2

    def P(x, y):
        """把设计坐标（0..1）映射到当前模式下的实际坐标。"""
        return (ox + x * s, oy + y * s)

    def shape_tv_screen(x, y):
        """电视屏幕（圆角矩形描边）。"""
        a = P(0.235, 0.395)
        b = P(0.765, 0.655)
        return rounded_rect_stroke(x, y, a[0], a[1], b[0], b[1], 0.050 * s, 0.040 * s)

    def shape_tv_stand(x, y):
        """底座：两条斜腿 + 一条横杆。"""
        t = 0.023 * s
        l1a, l1b = P(0.365, 0.655), P(0.295, 0.775)
        l2a, l2b = P(0.635, 0.655), P(0.705, 0.775)
        base_a, base_b = P(0.255, 0.785), P(0.745, 0.785)
        return (segment_contains(x, y, l1a[0], l1a[1], l1b[0], l1b[1], t) or
                segment_contains(x, y, l2a[0], l2a[1], l2b[0], l2b[1], t) or
                segment_contains(x, y, base_a[0], base_a[1], base_b[0], base_b[1], t))

    def shape_arrow(x, y):
        """屏幕中央向上的箭头（文件被送进电视）。"""
        shaft_x0, shaft_x1 = P(0.472, 0.0)[0], P(0.528, 0.0)[0]
        shaft_y0, shaft_y1 = P(0.0, 0.500)[1], P(0.0, 0.605)[1]
        head = [P(0.500, 0.435), P(0.570, 0.545), P(0.430, 0.545)]
        if shaft_x0 <= x <= shaft_x1 and shaft_y0 <= y <= shaft_y1:
            return True
        return polygon_contains(x, y, head)

    def shape_spark(x, y):
        """电视上方的无线广播弧（经典 WiFi 符号，表达「无线发送到电视」）。"""
        c = P(0.500, 0.300)
        dx = x - c[0]
        dy = y - c[1]
        d = math.hypot(dx, dy)
        if not (0.095 * s <= d <= 0.132 * s or 0.168 * s <= d <= 0.205 * s):
            return False
        deg = math.degrees(math.atan2(dy, dx))
        # 只保留上半部分（广播向上）。注意 atan2 的值域是 (-180, 180]，
        # 因此「向上」（dy < 0）对应的是负角度区间。
        return -160.0 <= deg <= -20.0

    layers = []
    if mode == "legacy":
        layers.append(("bg", lambda x, y: rounded_rect_contains(
            x, y, 0.0, 0.0, 1.0, 1.0, 0.225)))
    layers.append(("screen", shape_tv_screen))
    layers.append(("stand", shape_tv_stand))
    layers.append(("spark", shape_spark))
    layers.append(("arrow", shape_arrow))
    return layers


def render(size, mode, round_mask=False):
    """渲染指定尺寸的图标，返回 RGBA bytearray。"""
    layers = build_layers(mode)
    ss = SS
    hi = size * ss
    out = bytearray(size * size * 4)

    # 预计算每个子像素的图层颜色
    for py in range(size):
        for px in range(size):
            r = g = b = a = 0.0
            for sy in range(ss):
                for sx in range(ss):
                    x = (px + (sx + 0.5) / ss) / size
                    y = (py + (sy + 0.5) / ss) / size

                    cr, cg, cb, ca = 0.0, 0.0, 0.0, 0.0
                    for name, test in layers:
                        if not test(x, y):
                            continue
                        if name == "bg":
                            # 对角渐变
                            t = min(max((x + y) / 2, 0.0), 1.0)
                            cr = BG_START[0] + (BG_END[0] - BG_START[0]) * t
                            cg = BG_START[1] + (BG_END[1] - BG_START[1]) * t
                            cb = BG_START[2] + (BG_END[2] - BG_START[2]) * t
                            ca = 1.0
                        elif name == "arrow":
                            cr, cg, cb, ca = CORAL[0], CORAL[1], CORAL[2], 1.0
                        else:
                            cr, cg, cb, ca = WHITE[0], WHITE[1], WHITE[2], 1.0

                    if round_mask and ca > 0:
                        if not circle_contains(x, y, 0.5, 0.5, 0.5):
                            cr = cg = cb = 0.0
                            ca = 0.0

                    # 源覆盖式合成
                    cr *= ca
                    cg *= ca
                    cb *= ca
                    r += cr
                    g += cg
                    b += cb
                    a += ca

            n = float(ss * ss)
            idx = (py * size + px) * 4
            alpha = a / n
            # 上面的 r/g/b 是「预乘 alpha」后的平均值，需反预乘回直通 alpha
            if alpha > 0.0001:
                out[idx] = min(255, int(r / n / alpha + 0.5))
                out[idx + 1] = min(255, int(g / n / alpha + 0.5))
                out[idx + 2] = min(255, int(b / n / alpha + 0.5))
            else:
                out[idx] = 0
                out[idx + 1] = 0
                out[idx + 2] = 0
            out[idx + 3] = min(255, int(alpha * 255.0 + 0.5))
    return out


# ---------------------------------------------------------------- 输出
DENSITIES = [
    ("mdpi", 48),
    ("hdpi", 72),
    ("xhdpi", 96),
    ("xxhdpi", 144),
    ("xxxhdpi", 192),
]


def main():
    for module in ("tv", "phone"):
        base = os.path.join(ANDROID, module, "src", "main", "res")
        print(f"[{module}] 生成传统图标 mipmap PNG")
        for dpi, px in DENSITIES:
            d = os.path.join(base, f"mipmap-{dpi}")
            write_png(os.path.join(d, "ic_launcher.png"), px, px, render(px, "legacy"))
            if module == "tv":
                write_png(os.path.join(d, "ic_launcher_round.png"), px, px,
                          render(px, "legacy", round_mask=True))
    print("完成。")


if __name__ == "__main__":
    main()
