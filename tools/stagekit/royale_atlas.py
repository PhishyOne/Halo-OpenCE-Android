"""Bake the Android map from the same metre coordinates and faces used by the compiled island."""
import json
from pathlib import Path

from PIL import Image, ImageDraw

from island_ground import HALF, UNIT
from orchard import OUTPOSTS, build

DEST = Path(__file__).resolve().parents[2] / 'port/android/app/src/main/assets/royale'
TILES = [('island', (-HALF, -HALF, HALF, HALF), 640), ('millworks', (-340, -165, 1410, 535), 1400)]
LAND, FIELD, WATER, ROAD, LANE, ROOF, TREES = ('#394634', '#6a6847', '#406d7a', '#c4b588', '#8f8767', '#a3846c', '#213e32')


def bake(folder):
    folder.mkdir(parents=True, exist_ok=True)
    sector = build()
    tiles = []
    for name, (west, south, east, north), width in TILES:
        scale = width / (east - west)
        image = Image.new('RGB', (width, round((north - south) * scale)), LAND)
        draw = ImageDraw.Draw(image)

        def point(x, y):
            return (round((x - west) * scale), round((north - y) * scale))

        for material, color in [('field', FIELD), ('water', WATER), ('lane', LANE), ('road', ROAD)]:
            for tri in sector.faces[material]:
                draw.polygon([point(x, y) for x, y, _ in tri], color)
        for x, y, _, w, d, h in sector.solids:
            if w == .52 and d == .52 and h >= 4:  # tree trunk collision boxes
                px, py = point(x + w/2, y + d/2)
                r = max(1, round(2.5 * scale))
                draw.ellipse((px-r, py-r, px+r, py+r), TREES)
        for x, y, w, d in sector.buildings:
            draw.rectangle([point(x, y+d), point(x+w, y)], ROOF)
        image.convert('P', palette=Image.Palette.ADAPTIVE, colors=16).save(folder / (name + '.png'), optimize=True)
        tiles.append([name, west, south, east, north])
    landmarks = [('East Millworks', 485, -35), ('Orchard Crossing', -12, 15),
                 *((name.replace('_', ' ').title(), x, y) for name, x, y, _ in OUTPOSTS),
                 ('Bridge', 365, 0), ('Shelter', 598, 58)]
    (folder / 'atlas.json').write_text(json.dumps(dict(metresPerUnit=UNIT, tiles=tiles, landmarks=landmarks),
                                                separators=(',', ':')) + '\n')


if __name__ == '__main__':
    bake(DEST)
