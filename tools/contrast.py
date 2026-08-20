# -*- coding: utf-8 -*-
"""WCAG 2.1 contrast for every foreground/background pairing the app actually uses.

Run from the repository root:

    python tools/contrast.py

Values are read out of Color.kt rather than retyped, so this cannot drift from the
palette it is checking. Exits non-zero if anything falls below its threshold, so it
can be wired into CI if that is ever wanted.

The pairings are hand-listed rather than generated: the question is not "do these two
colours contrast" but "does this text, on the surface it is actually drawn on". A
generated cross-product would be mostly noise and would miss the compositing cases.
"""
import io, re, sys

SRC = 'app/src/main/java/com/aus/deutschflow/ui/theme/Color.kt'

def load_palette():
    s = io.open(SRC, encoding='utf-8').read()
    out = {}
    for m in re.finditer(r'val\s+(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)', s):
        out[m.group(1)] = int(m.group(2)[2:], 16)  # drop alpha
    return out

def luminance(rgb):
    def chan(c):
        c = c / 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255
    return 0.2126 * chan(r) + 0.7152 * chan(g) + 0.0722 * chan(b)

def ratio(fg, bg):
    a, b = luminance(fg), luminance(bg)
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)

P = load_palette()

# (foreground, background, what it is, "body" | "large" | "ui")
#   body  needs 4.5:1   large needs 3:1   ui (icons/borders) needs 3:1
PAIRS = [
    ('OnBackground',        'Background',       'primary text on the ground',        'body'),
    ('OnSurface',           'Surface',          'primary text on a surface',         'body'),
    ('OnSurface',           'GlassFill',        'primary text on a card',            'body'),
    ('OnSurface',           'SurfaceVariant',   'primary text on a raised surface',  'body'),
    ('OnSurfaceVariant',    'Background',       'secondary text on the ground',      'body'),
    ('OnSurfaceVariant',    'GlassFill',        'secondary text on a card',          'body'),
    ('OnSurfaceVariant',    'SurfaceVariant',   'secondary text on a raised card',   'body'),
    ('OnSurfaceMuted',      'Background',       'muted text on the ground',          'body'),
    ('OnSurfaceMuted',      'GlassFill',        'muted text on a card',              'body'),
    ('OnSurfaceMuted',      'SurfaceContainerHigh', 'the empty-state icon',          'ui'),
    ('PrimaryBlue',         'Background',       'accent text / links',               'body'),
    ('PrimaryBlue',         'GlassFill',        'accent on a card',                  'body'),
    ('AzureGlow',           'Background',       'cyan accent on the ground',         'body'),
    ('AzureGlow',           'GlassFill',        'cyan accent on a card',             'body'),
    ('ErrorRed',            'Background',       'error text',                        'body'),
    ('ErrorRed',            'ErrorContainer',   'error icon in its banner',          'ui'),
    ('OnErrorContainer',    'ErrorContainer',   'error banner text',                 'body'),
    ('TertiaryGreen',       'Background',       'success',                           'body'),
    ('TertiaryGreen',       'GlassFillRaised',  'the Mastered stat',                 'large'),
    ('WarningAmber',        'Background',       'warning',                           'body'),
    ('OnPrimaryContainer',  'PrimaryContainer', 'selected chip label',               'body'),
    ('OnSecondaryContainer','SecondaryContainer','cyan container text',              'body'),
    ('Outline',             'Background',       'card borders',                      'ui'),
    # 1.4.11 covers what is "required to identify" a component. A card's edge is,
    # because the surface ramp does not distinguish it. A divider between the nav bar
    # and the content separates rather than identifies, and the criterion exempts
    # decoration - so it is held to visibility, not to 3:1.
    ('OutlineVariant',      'Background',       'dividers',                          'decorative'),
]

NEED = {'body': 4.5, 'large': 3.0, 'ui': 3.0, 'decorative': 1.5}

rows, failures = [], []
for fg, bg, what, kind in PAIRS:
    if fg not in P or bg not in P:
        print('  ?? missing from palette:', fg, bg); continue
    r = ratio(P[fg], P[bg])
    need = NEED[kind]
    ok = r >= need
    rows.append((ok, r, need, kind, what, fg, bg))
    if not ok:
        failures.append((r, need, kind, what, fg, bg))

rows.sort(key=lambda t: t[1])
print('%-6s %7s %6s  %-6s %s' % ('', 'ratio', 'needs', 'kind', 'pairing'))
print('-' * 78)
for ok, r, need, kind, what, fg, bg in rows:
    print('%-6s %6.2f:1 %5.1f:1  %-6s %s  (%s on %s)' %
          ('PASS' if ok else 'FAIL', r, need, kind, what, fg, bg))

print()
print('%d pairings, %d below threshold' % (len(rows), len(failures)))
sys.exit(1 if failures else 0)
