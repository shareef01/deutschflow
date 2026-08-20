# -*- coding: utf-8 -*-
"""Do the two apps still agree on their colours?

Run from the repository root:

    python tools/palette_parity.py

The Android palette in ui/theme/Color.kt and the web one in globals.css are
documented as mirrors, and nothing enforced it. Three tokens - on-surface-muted,
outline and outline-variant - were corrected on Android for measured WCAG failures
and left unchanged on the web, so the same failures survived in the other app for
as long as nobody thought to look.

Exits non-zero on a mismatch, so it can be wired into CI.
"""
import io, re, sys

KT = 'app/src/main/java/com/aus/deutschflow/ui/theme/Color.kt'
CSS = 'web/src/app/globals.css'

# Android name -> web custom property suffix. Hand-listed rather than derived: the
# two naming schemes genuinely differ in places (GlassFillRaised / glass-raised),
# and a clever transform would hide the pairs it failed to match.
PAIRS = [
    ('Background', 'background'),
    ('Surface', 'surface'),
    ('SurfaceVariant', 'surface-variant'),
    ('OnBackground', 'on-background'),
    ('OnSurface', 'on-surface'),
    ('OnSurfaceVariant', 'on-surface-variant'),
    ('OnSurfaceMuted', 'on-surface-muted'),
    ('AzureGlow', 'azure-glow'),
    ('AzureDeep', 'azure-deep'),
    ('GlassFill', 'glass-fill'),
    ('GlassFillRaised', 'glass-raised'),
    ('Outline', 'outline'),
    ('OutlineVariant', 'outline-variant'),
    ('ErrorRed', 'error'),
    ('TertiaryGreen', 'tertiary'),
    ('WarningAmber', 'warning'),
    ('PrimaryContainer', 'primary-container'),
    ('OnPrimaryContainer', 'on-primary-container'),
    ('SecondaryContainer', 'secondary-container'),
    ('OnSecondaryContainer', 'on-secondary-container'),
    ('ErrorContainer', 'error-container'),
    ('OnErrorContainer', 'on-error-container'),
]


def android():
    s = io.open(KT, encoding='utf-8').read()
    return {m.group(1): m.group(2)[2:].lower()
            for m in re.finditer(r'val\s+(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)', s)}


def web():
    s = io.open(CSS, encoding='utf-8').read()
    return {m.group(1): m.group(2).lstrip('#').lower()
            for m in re.finditer(r'--color-([a-z-]+):\s*(#[0-9A-Fa-f]{6})', s)}


A, W = android(), web()
drift, missing = [], []

for a_name, w_name in PAIRS:
    a_val, w_val = A.get(a_name), W.get(w_name)
    if a_val is None or w_val is None:
        missing.append((a_name, w_name, a_val, w_val))
    elif a_val != w_val:
        drift.append((a_name, w_name, a_val, w_val))

for a_name, w_name, a_val, w_val in missing:
    print('  MISSING  %-22s android=%s  web=%s' % (a_name, a_val or '-', w_val or '-'))
for a_name, w_name, a_val, w_val in drift:
    print('  DRIFT    %-22s android=#%s  web=#%s' % (a_name, a_val, w_val))

total = len(PAIRS)
bad = len(drift) + len(missing)
print('%d paired tokens, %d in agreement, %d to reconcile' % (total, total - bad, bad))
sys.exit(1 if bad else 0)
