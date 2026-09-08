# -*- coding: utf-8 -*-
"""生成 5 个不同风格的 YiwenGeo 应用图标候选（512x512 PNG）"""
import math
from PIL import Image, ImageDraw, ImageFilter

S = 512
OUT = "icon_candidates"
import os
os.makedirs(OUT, exist_ok=True)


def supersample(draw_fn, ss=4):
    """4x 超采样抗锯齿；Draw 用 RGBA 模式，半透明色才会 alpha 混合而非直接覆盖"""
    img = Image.new("RGBA", (S * ss, S * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    draw_fn(d, ss)
    return img.resize((S, S), Image.LANCZOS)


def rounded_rect(d, xy, r, fill, ss=1):
    d.rounded_rectangle([c * ss for c in xy], radius=r * ss, fill=fill)


# ---------- 风格1：极简扁平（深青底 + 白定位针） ----------
def style1(d, ss):
    rounded_rect(d, (0, 0, S, S), 112, (38, 78, 70, 255), ss)          # 深青圆角方
    cx, tip_y, pr = 256 * ss, 380 * ss, 86 * ss                          # 针尖与半径
    # 水滴针：圆 + 长三角尾
    d.ellipse([cx - pr, tip_y - pr * 2.3, cx + pr, tip_y - pr * 0.3], fill=(255, 255, 255, 255))
    d.polygon([(cx - pr * 0.62, tip_y - pr * 1.05), (cx + pr * 0.62, tip_y - pr * 1.05),
               (cx, tip_y)], fill=(255, 255, 255, 255))
    d.ellipse([cx - pr * 0.42, tip_y - pr * 1.72, cx + pr * 0.42, tip_y - pr * 0.88],
              fill=(38, 78, 70, 255))


# ---------- 风格2：渐变现代（蓝青渐变圆底 + 白针 + 信号波） ----------
def style2(d, ss):
    # 垂直渐变圆
    mask = Image.new("L", (S * ss, S * ss), 0)
    ImageDraw.Draw(mask).ellipse([24 * ss, 24 * ss, (S - 24) * ss, (S - 24) * ss], fill=255)
    grad = Image.new("RGBA", (S * ss, S * ss))
    gd = ImageDraw.Draw(grad)
    for y in range(S * ss):
        t = y / (S * ss)
        gd.line([(0, y), (S * ss, y)], fill=(int(30 + 20 * t), int(120 - 30 * t), int(230 - 60 * t), 255))
    img_base = grad
    d._image.paste(img_base, (0, 0), mask)
    cx, cy = 256 * ss, 238 * ss
    pr = 74 * ss
    # 信号波（针后方同心弧，预混合白色到蓝底）
    for rr, col in [(150, (109, 167, 239, 255)), (196, (79, 151, 235, 255))]:
        d.arc([cx - rr * ss, cy - rr * ss + 40 * ss, cx + rr * ss, cy + rr * ss + 40 * ss],
              start=200, end=340, fill=col, width=int(10 * ss))
    # 白针
    tip_y = 366 * ss
    d.ellipse([cx - pr, tip_y - pr * 2.1, cx + pr, tip_y - pr * 0.1], fill=(255, 255, 255, 255))
    d.polygon([(cx - pr * 0.6, tip_y - pr * 0.85), (cx + pr * 0.6, tip_y - pr * 0.85), (cx, tip_y)],
              fill=(255, 255, 255, 255))
    d.ellipse([cx - pr * 0.4, tip_y - pr * 1.5, cx + pr * 0.4, tip_y - pr * 0.7],
              fill=(24, 116, 214, 255))


# ---------- 风格3：几何线条（浅底 + 描线针 + 经纬网格） ----------
def style3(d, ss):
    rounded_rect(d, (0, 0, S, S), 112, (244, 247, 250, 255), ss)
    # 经纬网格（地球仪感）
    grid = (170, 190, 205, 255)
    w = int(5 * ss)
    for i in range(1, 4):
        y = 128 * i * ss
        d.line([(60 * ss, y), ((S - 60) * ss, y)], fill=grid, width=w)
    for i in range(1, 4):
        x = 128 * i * ss
        d.line([(x, 60 * ss), (x, (S - 60) * ss)], fill=grid, width=w)
    # 描线定位针（深青）
    pin = (26, 74, 84, 255)
    cx, tip_y, pr = 256 * ss, 372 * ss, 82 * ss
    lw = int(16 * ss)
    d.ellipse([cx - pr, tip_y - pr * 2.1, cx + pr, tip_y - pr * 0.1], outline=pin, width=lw)
    d.line([(cx - pr * 0.72, tip_y - pr * 1.05), (cx, tip_y)], fill=pin, width=lw)
    d.line([(cx + pr * 0.72, tip_y - pr * 1.05), (cx, tip_y)], fill=pin, width=lw)
    d.ellipse([cx - pr * 0.3, tip_y - pr * 1.4, cx + pr * 0.3, tip_y - pr * 0.8], fill=pin)


# ---------- 风格4：拟物渐变针（深色底 + 红橙渐变针 + 高光） ----------
def style4(d, ss):
    rounded_rect(d, (0, 0, S, S), 112, (24, 28, 38, 255), ss)
    # 地面椭圆阴影（Pillow Draw 不做 alpha 混合，用预混合后的不透明色）
    d.ellipse([150 * ss, 396 * ss, 362 * ss, 436 * ss], fill=(48, 52, 61, 255))
    cx, tip_y, pr = 256 * ss, 384 * ss, 96 * ss
    top = tip_y - pr * 2.3
    # 针形状遮罩（圆头 + 三角尾）
    shape = Image.new("L", (S * ss, S * ss), 0)
    sd = ImageDraw.Draw(shape)
    sd.ellipse([cx - pr, top, cx + pr, tip_y - pr * 0.3], fill=255)
    sd.polygon([(cx - pr * 0.62, tip_y - pr * 1.05), (cx + pr * 0.62, tip_y - pr * 1.05),
                (cx, tip_y)], fill=255)
    # 竖向红→橙渐变，用遮罩裁剪后贴回
    pin_img = Image.new("RGBA", (S * ss, S * ss), (0, 0, 0, 0))
    pd = ImageDraw.Draw(pin_img)
    for y in range(int(top), int(tip_y) + 1):
        t = (y - top) / (tip_y - top)
        pd.line([(cx - pr, y), (cx + pr, y)],
                fill=(int(229 + 26 * t), int(57 + 110 * t), int(53 - 23 * t), 255))
    d._image.paste(pin_img, (0, 0), shape)
    # 左上高光（预混合：白 alpha≈0.25 叠在红橙渐变上）
    d.ellipse([cx - pr * 0.66, top + pr * 0.18, cx - pr * 0.1, top + pr * 0.74],
              fill=(246, 138, 112, 255))
    # 内孔（透出深色底）
    d.ellipse([cx - pr * 0.4, tip_y - pr * 1.62, cx + pr * 0.4, tip_y - pr * 0.82],
              fill=(24, 28, 38, 255))


# ---------- 风格5：字母Y徽章（圆底 + Y + 针尖合一） ----------
def style5(d, ss):
    rounded_rect(d, (0, 0, S, S), 112, (250, 251, 252, 255), ss)
    d.ellipse([40 * ss, 40 * ss, 472 * ss, 472 * ss], fill=(62, 142, 132, 255))
    cx = 256 * ss
    lw = int(44 * ss)
    col = (255, 255, 255, 255)
    # 字母 Y：两臂 + 竖干，竖干末端收成针尖
    d.line([(168 * ss, 150 * ss), (cx, 262 * ss)], fill=col, width=lw)
    d.line([(344 * ss, 150 * ss), (cx, 262 * ss)], fill=col, width=lw)
    d.line([(cx, 262 * ss), (cx, 330 * ss)], fill=col, width=lw)
    d.polygon([(cx - 26 * ss, 322 * ss), (cx + 26 * ss, 322 * ss), (cx, 384 * ss)], fill=col)
    # 针尖落点小圆环（预混合白色到青底）
    d.ellipse([cx - 34 * ss, 396 * ss, cx + 34 * ss, 430 * ss],
              outline=(176, 208, 204, 255), width=int(9 * ss))


def _gradient_pin(d, ss, bg, hole, hi, shadow, top_col, bot_col):
    """#4 的立体渐变针画法，配色参数化：bg底/孔色hole/高光hi/阴影shadow/针顶top_col→针底bot_col"""
    rounded_rect(d, (0, 0, S, S), 112, bg, ss)
    d.ellipse([150 * ss, 396 * ss, 362 * ss, 436 * ss], fill=shadow)      # 地面阴影
    cx, tip_y, pr = 256 * ss, 384 * ss, 96 * ss
    top = tip_y - pr * 2.3
    shape = Image.new("L", (S * ss, S * ss), 0)
    sd = ImageDraw.Draw(shape)
    sd.ellipse([cx - pr, top, cx + pr, tip_y - pr * 0.3], fill=255)
    sd.polygon([(cx - pr * 0.62, tip_y - pr * 1.05), (cx + pr * 0.62, tip_y - pr * 1.05),
                (cx, tip_y)], fill=255)
    pin_img = Image.new("RGBA", (S * ss, S * ss), (0, 0, 0, 0))
    pd = ImageDraw.Draw(pin_img)
    for y in range(int(top), int(tip_y) + 1):
        t = (y - top) / (tip_y - top)
        pd.line([(cx - pr, y), (cx + pr, y)],
                fill=(int(top_col[0] + (bot_col[0] - top_col[0]) * t),
                      int(top_col[1] + (bot_col[1] - top_col[1]) * t),
                      int(top_col[2] + (bot_col[2] - top_col[2]) * t), 255))
    d._image.paste(pin_img, (0, 0), shape)
    d.ellipse([cx - pr * 0.66, top + pr * 0.18, cx - pr * 0.1, top + pr * 0.74], fill=hi)  # 高光
    d.ellipse([cx - pr * 0.4, tip_y - pr * 1.62, cx + pr * 0.4, tip_y - pr * 0.82], fill=hole)  # 内孔


# ---------- 风格6a：拟物渐变针（墨青底，同 #1/#5 色系，青绿渐变针） ----------
def style6a(d, ss):
    _gradient_pin(d, ss,
                  bg=(38, 78, 70, 255),          # 深墨青（= #1 底色）
                  hole=(38, 78, 70, 255),
                  hi=(150, 205, 190, 255),        # 预混合高光
                  shadow=(52, 90, 82, 255),
                  top_col=(46, 125, 110, 255),    # 针顶：深青绿
                  bot_col=(110, 190, 165, 255))   # 针底：亮青绿


# ---------- 风格6b：拟物渐变针（浅底，同 #5 外圈，深青绿针） ----------
def style6b(d, ss):
    _gradient_pin(d, ss,
                  bg=(244, 247, 250, 255),        # 浅底
                  hole=(244, 247, 250, 255),
                  hi=(150, 210, 195, 255),
                  shadow=(222, 230, 233, 255),
                  top_col=(26, 74, 84, 255),      # 针顶：墨青（= #3 针色）
                  bot_col=(62, 142, 132, 255))    # 针底：青绿（= #5 徽章色）


icons = {
    "1_极简扁平_深青白针.png": style1,
    "2_渐变现代_蓝青信号波.png": style2,
    "3_几何线条_经纬网格.png": style3,
    "4_拟物渐变_暗底红针.png": style4,
    "5_字母Y徽章_青底白字.png": style5,
    "6a_拟物渐变_墨青底青绿针.png": style6a,
    "6b_拟物渐变_浅底青绿针.png": style6b,
}

for name, fn in icons.items():
    img = supersample(fn)
    img.save(os.path.join(OUT, name))
    print("saved:", name)
print("done")
