from PIL import Image, ImageDraw
import os

# Base design drawn at high res, then downscaled per density for crisp edges.
BASE = 512

def draw_icon(size, round_mask=False):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    bg_color = (0, 105, 92, 255)   # deep teal, matches Sunmi's hardware-y aesthetic
    accent = (255, 255, 255, 255)
    dark = (0, 77, 64, 255)

    pad = int(size * 0.06)
    # Background shape: rounded square (or circle for round icon)
    if round_mask:
        d.ellipse([pad, pad, size - pad, size - pad], fill=bg_color)
    else:
        radius = int(size * 0.18)
        d.rounded_rectangle([pad, pad, size - pad, size - pad], radius=radius, fill=bg_color)

    # Printer body (centered, slightly lower half)
    body_w = size * 0.56
    body_h = size * 0.24
    body_x0 = (size - body_w) / 2
    body_y0 = size * 0.42
    d.rounded_rectangle(
        [body_x0, body_y0, body_x0 + body_w, body_y0 + body_h],
        radius=size * 0.035, fill=accent
    )

    # Paper slot (dark line across the printer body)
    slot_h = size * 0.03
    d.rectangle(
        [body_x0 + size * 0.05, body_y0 + size * 0.03,
         body_x0 + body_w - size * 0.05, body_y0 + size * 0.03 + slot_h],
        fill=dark
    )

    # Printed paper strip coming out the top of the printer
    paper_w = body_w * 0.72
    paper_x0 = (size - paper_w) / 2
    paper_top = size * 0.20
    d.rectangle(
        [paper_x0, paper_top, paper_x0 + paper_w, body_y0],
        fill=accent
    )
    # Little "photo" square + lines on the paper to suggest an image printout
    photo_pad = paper_w * 0.14
    photo_size = paper_w * 0.34
    photo_x0 = paper_x0 + photo_pad
    photo_y0 = paper_top + size * 0.03
    d.rectangle(
        [photo_x0, photo_y0, photo_x0 + photo_size, photo_y0 + photo_size],
        outline=dark, width=max(2, int(size * 0.012))
    )
    # simple mountain/sun glyph inside the photo square
    d.ellipse(
        [photo_x0 + photo_size * 0.14, photo_y0 + photo_size * 0.14,
         photo_x0 + photo_size * 0.40, photo_y0 + photo_size * 0.40],
        fill=dark
    )
    d.polygon(
        [
            (photo_x0 + photo_size * 0.10, photo_y0 + photo_size * 0.85),
            (photo_x0 + photo_size * 0.45, photo_y0 + photo_size * 0.45),
            (photo_x0 + photo_size * 0.65, photo_y0 + photo_size * 0.65),
            (photo_x0 + photo_size * 0.80, photo_y0 + photo_size * 0.50),
            (photo_x0 + photo_size * 0.92, photo_y0 + photo_size * 0.85),
        ],
        fill=dark
    )
    # text lines next to the photo square
    line_x0 = photo_x0 + photo_size + photo_pad * 0.6
    line_x1 = paper_x0 + paper_w - photo_pad * 0.4
    line_h = max(2, int(size * 0.02))
    for i, frac in enumerate([0.18, 0.42, 0.66]):
        y = photo_y0 + photo_size * frac
        w = line_x1 - line_x0 if i < 2 else (line_x1 - line_x0) * 0.6
        d.rectangle([line_x0, y, line_x0 + w, y + line_h], fill=dark)

    # Two little feet on the printer body for a grounded look
    foot_w = size * 0.05
    foot_h = size * 0.03
    d.rectangle(
        [body_x0 + size * 0.04, body_y0 + body_h,
         body_x0 + size * 0.04 + foot_w, body_y0 + body_h + foot_h],
        fill=dark
    )
    d.rectangle(
        [body_x0 + body_w - size * 0.04 - foot_w, body_y0 + body_h,
         body_x0 + body_w - size * 0.04, body_y0 + body_h + foot_h],
        fill=dark
    )

    return img


densities = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

res_root = "/home/claude/SunmiPhotoPrinter/app/src/main/res"

for folder, px in densities.items():
    out_dir = os.path.join(res_root, folder)
    os.makedirs(out_dir, exist_ok=True)

    square = draw_icon(BASE, round_mask=False).resize((px, px), Image.LANCZOS)
    square.save(os.path.join(out_dir, "ic_launcher.png"))

    round_icon = draw_icon(BASE, round_mask=True).resize((px, px), Image.LANCZOS)
    round_icon.save(os.path.join(out_dir, "ic_launcher_round.png"))

print("done")
