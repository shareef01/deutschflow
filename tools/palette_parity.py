# -*- coding: utf-8 -*-
"""Do the two apps still agree on their colours?

Run from the repository root:

    python tools/palette_parity.py

The Android palette in ui/theme/Color.kt and the web one in globals.css are
documented as mirrors, and nothing enforced it. Three tokens - on-surface-muted,
outline and outline-variant - were corrected on Android for measured WCAG failures
and left unchanged on the web, so the same failures survived in the other app for
as long as nobody thought to look.

Both themes. The dark palette pairs Color.kt's bare names against the @theme
block; the light one pairs its Light-prefixed names against the
`@media (prefers-color-scheme: light)` override. The light palette is the more
likely of the two to drift, because it is the newer one and neither app renders
it by default on a developer's machine.

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
    ('TertiaryContainer', 'tertiary-container'),
    ('OnTertiaryContainer', 'on-tertiary-container'),
    ('WarningContainer', 'warning-container'),
    ('OnWarningContainer', 'on-warning-container'),
    ('PrimaryBlue', 'primary'),
    ('PrimaryBlueLight', 'primary-light'),
    ('SecondaryCyan', 'secondary'),
    ('SurfaceContainerLowest', 'surface-container-lowest'),
    ('SurfaceContainerLow', 'surface-container-low'),
    ('SurfaceContainer', 'surface-container'),
    ('SurfaceContainerHigh', 'surface-container-high'),
    ('SurfaceContainerHighest', 'surface-container-highest'),
]


def android():
    s = io.open(KT, encoding='utf-8').read()
    return {m.group(1): m.group(2)[2:].lower()
            for m in re.finditer(r'val\s+(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)', s)}


def _tokens(block):
    return {m.group(1): m.group(2).lstrip('#').lower()
            for m in re.finditer(r'--color-([a-z-]+):\s*(#[0-9A-Fa-f]{6})', block)}


def _brace_block(s, start):
    """The {...} beginning at or after `start`, matched rather than regexed."""
    i = s.index('{', start)
    depth = 0
    for j in range(i, len(s)):
        if s[j] == '{':
            depth += 1
        elif s[j] == '}':
            depth -= 1
            if depth == 0:
                return s[i:j]
    raise ValueError('unbalanced braces from %d' % start)


def web():
    """The dark tokens from @theme, the light ones from the media override.

    Parsed as two separate blocks on purpose: read as one flat file, the light
    values would silently overwrite the dark ones under the same names and every
    dark comparison would be against the wrong palette.
    """
    s = io.open(CSS, encoding='utf-8').read()
    dark = _tokens(_brace_block(s, s.index('@theme')))
    light = _tokens(_brace_block(s, s.index('@media (prefers-color-scheme: light)')))
    return dark, light


A = android()
W_DARK, W_LIGHT = web()


def compare(label, prefix, web_tokens):
    drift, missing = [], []
    for a_name, w_name in PAIRS:
        a_val, w_val = A.get(prefix + a_name), web_tokens.get(w_name)
        if a_val is None or w_val is None:
            missing.append((prefix + a_name, w_name, a_val, w_val))
        elif a_val != w_val:
            drift.append((prefix + a_name, w_name, a_val, w_val))

    print()
    print('== %s ==' % label)
    for a_name, w_name, a_val, w_val in missing:
        print('  MISSING  %-28s android=%s  web=%s' % (a_name, a_val or '-', w_val or '-'))
    for a_name, w_name, a_val, w_val in drift:
        print('  DRIFT    %-28s android=#%s  web=#%s' % (a_name, a_val, w_val))
    bad = len(drift) + len(missing)
    print('  %d paired tokens, %d in agreement, %d to reconcile'
          % (len(PAIRS), len(PAIRS) - bad, bad))
    return bad


bad = compare('dark', '', W_DARK) + compare('light', 'Light', W_LIGHT)
print()
print('RESULT: %s' % ('both palettes agree across the two apps' if not bad
                      else '%d token(s) to reconcile' % bad))
sys.exit(1 if bad else 0)
