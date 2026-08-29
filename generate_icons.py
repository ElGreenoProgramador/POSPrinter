from PIL import Image, ImageDraw
import os

# Base design drawn at high res, then downscaled per density for crisp edges.
BASE = 512

def draw_icon(size, round_mask=False):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    bg_color = (103, 80, 164, 255)   # M3 Primary Purple (#6750A4)
    paper_color = (234, 221, 255, 255) # M3 Primary Container (#EADDFF)
    body_color = (255, 255, 255, 255) # White
    slot_color = (103, 80, 164, 255) # Same as background

    pad = int(size * 0.06)
    # Background shape: rounded square (or circle for round icon)
    if round_mask:
        d.ellipse([pad, pad, size - pad, size - pad], fill=bg_color)
    else:
        radius = int(size * 0.18)
        d.rounded_rectangle([pad, pad, size - pad, size - pad], radius=radius, fill=bg_color)

    # Minimal Printer Design (Material 3 style)

    # Paper emerging from slot
    pw = size * 0.35
    ph = size * 0.25
    px0 = (size - pw) / 2
    py0 = size * 0.25
    d.rounded_rectangle([px0, py0, px0 + pw, py0 + ph + 10], radius=int(size * 0.02), fill=paper_color)

    # Printer body
    bw = size * 0.55
    bh = size * 0.35
    bx0 = (size - bw) / 2
    by0 = size * 0.45
    d.rounded_rectangle([bx0, by0, bx0 + bw, by0 + bh], radius=int(size * 0.06), fill=body_color)

    # Slot
    sw = bw * 0.85
    sh = size * 0.04
    sx0 = (size - sw) / 2
    sy0 = by0 + size * 0.04
    d.rectangle([sx0, sy0, sx0 + sw, sy0 + sh], fill=slot_color)

    return img


densities = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# Updated to new project directory
res_root = "app/src/main/res"

for folder, px in densities.items():
    out_dir = os.path.join(res_root, folder)
    os.makedirs(out_dir, exist_ok=True)

    square = draw_icon(BASE, round_mask=False).resize((px, px), Image.LANCZOS)
    square.save(os.path.join(out_dir, "ic_launcher.png"))

    round_icon = draw_icon(BASE, round_mask=True).resize((px, px), Image.LANCZOS)
    round_icon.save(os.path.join(out_dir, "ic_launcher_round.png"))

print("Icons generated")
