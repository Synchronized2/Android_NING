from pathlib import Path
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont


OUT = Path("design-assets/chat-ui-v1")
CYAN = (20, 222, 255, 255)
CYAN_SOFT = (48, 163, 220, 255)
WHITE_BLUE = (220, 248, 255, 255)
DARK = (4, 28, 53, 224)


def glow_layer(size, painter, color=CYAN, blur=8):
    glow = Image.new("RGBA", size)
    draw = ImageDraw.Draw(glow)
    painter(draw, (*color[:3], 190), max(3, size[0] // 22))
    return glow.filter(ImageFilter.GaussianBlur(blur))


def icon(size, painter, color, glow=True):
    image = Image.new("RGBA", size)
    if glow:
        image.alpha_composite(glow_layer(size, painter, color))
    painter(ImageDraw.Draw(image), color, max(2, size[0] // 18))
    return image


def clock_painter(draw, color, width):
    draw.ellipse((10, 10, 54, 54), outline=color, width=width)
    draw.line((32, 19, 32, 33, 43, 39), fill=color, width=width, joint="curve")
    draw.arc((3, 4, 30, 31), 125, 260, fill=color, width=width)
    draw.line((8, 8, 8, 20, 20, 20), fill=color, width=width, joint="curve")


def plus_painter(draw, color, width):
    draw.ellipse((8, 8, 56, 56), outline=color, width=width)
    draw.line((32, 19, 32, 45), fill=color, width=width)
    draw.line((19, 32, 45, 32), fill=color, width=width)


def chat_painter(draw, color, width):
    draw.rounded_rectangle((6, 8, 58, 45), radius=8, outline=color, width=width)
    draw.line((19, 45, 19, 57, 32, 45), fill=color, width=width, joint="curve")
    for x in (21, 32, 43):
        draw.ellipse((x - 2, 25, x + 2, 29), fill=color)


def image_painter(draw, color, width):
    draw.rounded_rectangle((7, 8, 57, 56), radius=5, outline=color, width=width)
    draw.ellipse((17, 17, 25, 25), outline=color, width=max(2, width - 1))
    draw.line((9, 49, 23, 34, 33, 43, 42, 31, 56, 47), fill=color, width=width, joint="curve")


def person_painter(draw, color, width):
    draw.ellipse((23, 12, 41, 30), fill=color)
    draw.rounded_rectangle((15, 33, 49, 55), radius=11, fill=color)


def send_painter(draw, color, width):
    draw.polygon([(7, 7), (58, 32), (7, 57), (15, 37), (39, 32), (15, 27)], fill=color)


def cyber_frame(size, active):
    w, h = size
    image = Image.new("RGBA", size)
    points = [(14, 2), (w - 16, 2), (w - 2, 16), (w - 2, h - 19),
              (w - 18, h - 2), (15, h - 2), (2, h - 15), (2, 15)]
    if active:
        glow = Image.new("RGBA", size)
        gd = ImageDraw.Draw(glow)
        gd.polygon(points, outline=(0, 231, 255, 210), width=7)
        image.alpha_composite(glow.filter(ImageFilter.GaussianBlur(7)))
    draw = ImageDraw.Draw(image)
    draw.polygon(points, fill=(4, 36, 67, 232), outline=CYAN if active else CYAN_SOFT,
                 width=3 if active else 2)
    inset = [(18, 7), (w - 20, 7), (w - 7, 19), (w - 7, h - 22),
             (w - 21, h - 7), (19, h - 7), (7, h - 18), (7, 19)]
    draw.line(inset + [inset[0]], fill=(65, 153, 205, 150), width=1)
    draw.line((9, 21, 20, 9, 57, 9), fill=(74, 244, 255, 230 if active else 100), width=2)
    draw.line((w - 62, h - 8, w - 24, h - 8, w - 9, h - 23),
              fill=(64, 222, 255, 190 if active else 80), width=2)
    return image


def bubble(size, user):
    w, h = size
    image = Image.new("RGBA", size)
    if user:
        points = [(24, 2), (w - 16, 2), (w - 2, 16), (w - 2, h - 28),
                  (w - 28, h - 2), (19, h - 2), (2, h - 18), (2, 21)]
    else:
        points = [(16, 2), (w - 28, 2), (w - 2, 28), (w - 2, h - 17),
                  (w - 18, h - 2), (28, h - 2), (2, h - 28), (2, 16)]
    glow = Image.new("RGBA", size)
    gd = ImageDraw.Draw(glow)
    gd.polygon(points, outline=(0, 221, 255, 210), width=7)
    image.alpha_composite(glow.filter(ImageFilter.GaussianBlur(8)))
    draw = ImageDraw.Draw(image)
    draw.polygon(points, fill=(4, 37, 68, 230), outline=(17, 214, 255, 255), width=3)
    inner = [(x + (5 if x < w / 2 else -5), y + (5 if y < h / 2 else -5)) for x, y in points]
    draw.line(inner + [inner[0]], fill=(30, 113, 175, 150), width=1)
    if user:
        draw.line((24, 7, w - 72, 7), fill=(76, 238, 255, 165), width=2)
        draw.line((w - 62, h - 7, w - 31, h - 7), fill=(76, 238, 255, 190), width=2)
    else:
        draw.line((7, 22, 7, h - 66), fill=(76, 238, 255, 190), width=2)
        draw.line((31, h - 7, 83, h - 7), fill=(76, 238, 255, 190), width=2)
    return image


def input_frame():
    image = cyber_frame((720, 108), True)
    draw = ImageDraw.Draw(image)
    draw.line((36, 101, 92, 101), fill=(38, 224, 255, 210), width=3)
    draw.line((630, 7, 684, 7), fill=(38, 224, 255, 180), width=2)
    return image


def octagon_avatar(source, box, output_size):
    crop = source.crop(box).resize(output_size, Image.Resampling.LANCZOS).convert("RGBA")
    w, h = output_size
    mask = Image.new("L", output_size)
    draw = ImageDraw.Draw(mask)
    inset = max(2, w // 22)
    cut = w // 5
    draw.polygon([(cut, inset), (w - cut, inset), (w - inset, cut),
                  (w - inset, h - cut), (w - cut, h - inset), (cut, h - inset),
                  (inset, h - cut), (inset, cut)], fill=255)
    crop.putalpha(mask)
    return crop


def save(name, image):
    image.save(OUT / name, "PNG")


def contact_sheet(entries):
    thumb_w, thumb_h = 300, 210
    columns = 3
    rows = (len(entries) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * thumb_w, rows * thumb_h), (2, 10, 22))
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default(18)
    for index, (name, image) in enumerate(entries):
        x = (index % columns) * thumb_w
        y = (index // columns) * thumb_h
        checker = Image.new("RGB", (thumb_w - 24, thumb_h - 48), (4, 20, 37))
        checker_draw = ImageDraw.Draw(checker)
        for cy in range(0, checker.height, 16):
            for cx in range(0, checker.width, 16):
                if (cx // 16 + cy // 16) % 2:
                    checker_draw.rectangle((cx, cy, cx + 15, cy + 15), fill=(8, 31, 51))
        sheet.paste(checker, (x + 12, y + 8))
        preview = image.copy()
        preview.thumbnail((thumb_w - 42, thumb_h - 72), Image.Resampling.LANCZOS)
        px = x + (thumb_w - preview.width) // 2
        py = y + 8 + (thumb_h - 48 - preview.height) // 2
        sheet.paste(preview, (px, py), preview)
        draw.text((x + 12, y + thumb_h - 34), name, fill=(198, 239, 255), font=font)
    sheet.save(OUT / "00_contact_sheet.png", quality=95)


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: extract_chat_ui_assets.py REFERENCE.png")
    source = Image.open(sys.argv[1]).convert("RGBA")
    OUT.mkdir(parents=True, exist_ok=True)
    entries = []

    assets = {
        "01_history_icon.png": icon((64, 64), clock_painter, CYAN),
        "02_header_frame_active.png": cyber_frame((248, 84), True),
        "03_header_frame_inactive.png": cyber_frame((248, 84), False),
        "04_new_chat_icon.png": icon((64, 64), plus_painter, WHITE_BLUE),
        "05_chat_icon_active.png": icon((64, 64), chat_painter, WHITE_BLUE),
        "06_chat_icon_inactive.png": icon((64, 64), chat_painter, CYAN_SOFT, False),
        "07_image_icon_active.png": icon((64, 64), image_painter, WHITE_BLUE),
        "08_image_icon_inactive.png": icon((64, 64), image_painter, CYAN_SOFT, False),
        "09_avatar_ning.png": octagon_avatar(source, (43, 460, 127, 544), (96, 96)),
        "10_avatar_user.png": octagon_avatar(source, (904, 325, 982, 403), (96, 96)),
        "11_bubble_user.png": bubble((640, 156), True),
        "12_bubble_assistant.png": bubble((640, 250), False),
        "13_input_frame.png": input_frame(),
        "14_send_icon.png": icon((72, 72), send_painter, (197, 251, 255, 255)),
    }
    for name, asset in assets.items():
        save(name, asset)
        entries.append((name, asset))
    contact_sheet(entries)
    print(OUT.resolve())


if __name__ == "__main__":
    main()
