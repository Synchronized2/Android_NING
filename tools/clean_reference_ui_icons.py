from pathlib import Path
import math

from PIL import Image, ImageDraw, ImageFilter, ImageFont


SOURCE = Path(r"C:\Users\LJH\Downloads\all_icons_individually_cut")
OUTPUT = Path("design-assets/chat-ui-icons-v2")


ICONS = [
    ("01_history_selected.png", "01_history_icon_selected.png", (50, 50, 132, 132), 8),
    ("02_dialog_selected.png", "07_dialog_selected.png", (39, 54, 135, 137), 8),
    ("03_dialog_normal.png", "07_dialog_tab_icon_normal.png", (39, 46, 140, 137), 8),
    ("04_image_selected.png", "09_image_selected.png", (42, 58, 130, 132), 1),
    ("05_image_normal.png", "09_image_tab_icon_normal.png", (46, 53, 139, 127), 1),
    ("06_send_selected.png", "16_send_button_selected.png", (62, 53, 119, 109), 1),
]


def cyan_strength(r, g, b):
    return min(g, b) - r * 0.58


def extract_icon(source_path, crop_box, edge_margin):
    source = Image.open(source_path).convert("RGBA").crop(crop_box)
    pixels = source.load()
    for y in range(source.height):
        for x in range(source.width):
            r, g, b, a = pixels[x, y]
            strength = cyan_strength(r, g, b)
            brightness = max(g, b)
            derived = max(0, min(255, round((brightness - 145) * 3.4)))
            if strength < 42:
                derived = 0
            if (x < edge_margin or y < edge_margin
                    or x >= source.width - edge_margin or y >= source.height - edge_margin):
                derived = 0
            pixels[x, y] = (r, g, b, min(a, derived))

    bbox = source.getbbox()
    if bbox:
        source = source.crop(bbox)
    source.thumbnail((100, 100), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (128, 128))
    x = (128 - source.width) // 2
    y = (128 - source.height) // 2
    alpha = source.getchannel("A")
    glow_alpha = alpha.filter(ImageFilter.GaussianBlur(6)).point(lambda value: int(value * 0.55))
    glow = Image.new("RGBA", source.size, (0, 220, 255, 0))
    glow.putalpha(glow_alpha)
    canvas.alpha_composite(glow, (x, y))
    canvas.alpha_composite(source, (x, y))
    return canvas


def clean_input_frame(source_path):
    source = Image.open(source_path).convert("RGBA")
    w, h = source.size
    polygon = [
        (18, 8), (w - 20, 8), (w - 3, 25), (w - 3, h - 31),
        (w - 22, h - 12), (178, h - 12), (159, h - 2),
        (88, h - 2), (74, h - 12), (18, h - 12), (2, h - 28), (2, 24),
    ]
    core = Image.new("L", source.size)
    ImageDraw.Draw(core).polygon(polygon, fill=255)
    glow = core.filter(ImageFilter.GaussianBlur(max(2, h // 24)))
    core_pixels = core.load()
    glow_pixels = glow.load()
    pixels = source.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = pixels[x, y]
            if core_pixels[x, y]:
                pixels[x, y] = (r, g, b, a)
                continue
            cyan = cyan_strength(r, g, b)
            colored = (b - r > 22 or g - r > 16) and cyan > 12
            cleaned_alpha = min(a, glow_pixels[x, y]) if colored else 0
            pixels[x, y] = (r, g, b, cleaned_alpha)
    return source


def checker(size):
    image = Image.new("RGB", size, (4, 17, 31))
    draw = ImageDraw.Draw(image)
    cell = 14
    for y in range(0, size[1], cell):
        for x in range(0, size[0], cell):
            if (x // cell + y // cell) % 2:
                draw.rectangle((x, y, x + cell - 1, y + cell - 1), fill=(7, 29, 48))
    return image


def contact_sheet(items):
    cell_w, cell_h = 300, 210
    columns = 3
    rows = math.ceil(len(items) / columns)
    sheet = Image.new("RGB", (cell_w * columns, cell_h * rows), (1, 8, 18))
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default(18)
    for index, (name, image) in enumerate(items):
        x = index % columns * cell_w
        y = index // columns * cell_h
        panel = checker((cell_w - 24, cell_h - 48))
        sheet.paste(panel, (x + 12, y + 8))
        preview = image.copy()
        preview.thumbnail((cell_w - 38, cell_h - 66), Image.Resampling.LANCZOS)
        px = x + (cell_w - preview.width) // 2
        py = y + 8 + (cell_h - 48 - preview.height) // 2
        sheet.paste(preview, (px, py), preview)
        draw.text((x + 12, y + cell_h - 32), name, fill=(195, 236, 255), font=font)
    sheet.save(OUTPUT / "00_preview.png")


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    results = []
    for output_name, source_name, crop_box, edge_margin in ICONS:
        image = extract_icon(SOURCE / source_name, crop_box, edge_margin)
        image.save(OUTPUT / output_name)
        results.append((output_name, image))

    input_frame = clean_input_frame(SOURCE / "25_input_field_normal.png")
    input_frame.save(OUTPUT / "07_input_field_normal.png")
    results.append(("07_input_field_normal.png", input_frame))
    contact_sheet(results)
    print(OUTPUT.resolve())


if __name__ == "__main__":
    main()
