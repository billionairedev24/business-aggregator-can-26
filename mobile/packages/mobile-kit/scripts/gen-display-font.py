#!/usr/bin/env python3
"""
S-188: the native apps' display face, Archivo expanded 800 (theme.json `font.display`, `stretch` 125 %), as one static
TTF: assets/fonts/archivo-expanded/800ExtraBold/ArchivoExpanded_800ExtraBold.ttf (with an
`index.js` that requires it, as an @expo-google-fonts package does).

The web self-hosts the variable Archivo of `@fontsource-variable/archivo` (the `wdth` files) and draws it with
`font-stretch: 125%`. React Native can't set a variable font's width, and `@expo-google-fonts/archivo` ships the normal
width only, so this instances the web's own files at wght 800 / wdth 125 and merges the latin and latin-ext subsets: the
apps draw the same glyphs as the site. Run it again only when the web's Archivo version or the display weight changes:

    python3 -m venv /tmp/fonts && /tmp/fonts/bin/pip install fonttools brotli
    /tmp/fonts/bin/python scripts/gen-display-font.py <dir of @fontsource-variable/archivo>/files

then `pnpm brand`. Licence: SIL Open Font License 1.1 (assets/fonts/archivo-expanded/OFL.txt; Archivo declares no Reserved Font
Name).
"""
import os
import sys

from fontTools.merge import Merger
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

WEIGHT, WIDTH = 800, 125
FAMILY, STYLE, FILE = 'Archivo Expanded', 'ExtraBold', 'ArchivoExpanded_800ExtraBold.ttf'

src = sys.argv[1] if len(sys.argv) > 1 else sys.exit('usage: gen-display-font.py <@fontsource-variable/archivo>/files')
out = os.path.join(os.path.dirname(__file__), '..', 'assets', 'fonts', 'archivo-expanded', '800ExtraBold')
parts = []
for subset in ('latin', 'latin-ext'):
    font = instancer.instantiateVariableFont(TTFont(os.path.join(src, f'archivo-{subset}-wdth-normal.woff2')), {'wght': WEIGHT, 'wdth': WIDTH})
    font.flavor = None
    part = os.path.join(out, f'.{subset}.ttf')
    font.save(part)
    parts.append(part)
merged = Merger().merge(parts)
for rec in merged['name'].names:
    if rec.nameID in (1, 16):
        rec.string = FAMILY
    elif rec.nameID in (2, 17):
        rec.string = STYLE
    elif rec.nameID == 4:
        rec.string = f'{FAMILY} {STYLE}'
    elif rec.nameID == 6:
        rec.string = f'{FAMILY.replace(" ", "")}-{STYLE}'
merged.save(os.path.join(out, FILE))
for part in parts:
    os.remove(part)
print(f'wrote {os.path.join(out, FILE)}')
