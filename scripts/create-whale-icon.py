"""Adapt the supplied whale wallpaper into Android launcher artwork."""
import argparse
from pathlib import Path
from PIL import Image, ImageDraw, ImageOps

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
image = ImageOps.exif_transpose(Image.open(args.source)).convert("RGB")
# The head/eye and front flipper remain inside the adaptive-icon safe area.
art = ImageOps.fit(image, (1024, 1024), Image.Resampling.LANCZOS, centering=(1.0, 0.5))
destination = root / "app/src/main/res/drawable-nodpi/ic_launcher_whale.png"
art.save(destination, optimize=True)

output = root / "dist/branding"
output.mkdir(parents=True, exist_ok=True)
art.save(output / "ning-whale-icon-1024.png", optimize=True)
preview = Image.new("RGB", (700, 380), "#08182c")
safe = art.crop((170, 170, 854, 854)).resize((256, 256), Image.Resampling.LANCZOS)
for index, radius in enumerate((128, 64)):
    mask = Image.new("L", (256, 256))
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 255, 255), radius=radius, fill=255)
    preview.paste(safe, (55 + 335 * index, 50), mask)
preview.save(output / "ning-whale-launcher-preview.png")
print(destination)
print(output / "ning-whale-launcher-preview.png")
