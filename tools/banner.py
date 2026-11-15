# Renders res/drawable-xhdpi/banner.png (320x180dp at xhdpi = 640x360 px).
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter
font_path, out = sys.argv[1], sys.argv[2]
W, H = 640, 360
img = Image.new("RGB", (W, H))
px = img.load()
a, b = (31, 27, 22), (13, 12, 10)
for y in range(H):
    for x in range(W):
        t = (x / W) * 0.7 + (y / H) * 0.3
        px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))
glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
ImageDraw.Draw(glow).ellipse((-120, -200, 360, 260), fill=(200, 155, 109, 70))
img.paste(glow.filter(ImageFilter.GaussianBlur(60)), (0, 0), glow.filter(ImageFilter.GaussianBlur(60)))
d = ImageDraw.Draw(img)
# padlock
cx, cy = 132, 186
d.arc((cx - 34, cy - 92, cx + 34, cy - 24), 180, 360, fill=(244, 235, 217), width=14)
d.line((cx - 34 + 7, cy - 58, cx - 34 + 7, cy - 22), fill=(244, 235, 217), width=14)
d.line((cx + 34 - 7, cy - 58, cx + 34 - 7, cy - 22), fill=(244, 235, 217), width=14)
d.rounded_rectangle((cx - 58, cy - 30, cx + 58, cy + 66), 18, fill=(244, 235, 217))
d.ellipse((cx - 12, cy + 2, cx + 12, cy + 26), fill=(27, 23, 18))
d.rectangle((cx - 5, cy + 18, cx + 5, cy + 44), fill=(27, 23, 18))
f = ImageFont.truetype(font_path, 92)
d.text((236, 112), "TV Lock", font=f, fill=(244, 235, 217))
f2 = ImageFont.truetype(font_path, 30)
d.text((240, 222), "PIN for your TV & apps", font=f2, fill=(200, 155, 109))
img.save(out)
