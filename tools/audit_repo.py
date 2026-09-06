# -*- coding: utf-8 -*-
"""Repository health and parity auditor for DeutschFlow.

Checks:
1. Android string resource parity (values/strings.xml vs values-de/strings.xml)
2. Web i18n dictionary parity (en vs de in web/src/lib/i18n.ts)
3. Repository cleanliness (no stray build artifacts, debug dumps, or temporary files)
4. Design token parity and WCAG contrast (via palette_parity.py and contrast.py)

Usage from repository root:
    python tools/audit_repo.py
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Paths
STRINGS_EN = ROOT / "app/src/main/res/values/strings.xml"
STRINGS_DE = ROOT / "app/src/main/res/values-de/strings.xml"
I18N_TS = ROOT / "web/src/lib/i18n.ts"
CONTRAST_PY = ROOT / "tools/contrast.py"
PALETTE_PY = ROOT / "tools/palette_parity.py"

# Forbidden clutter patterns
CLUTTER_PATTERNS = [
    re.compile(r".*\.(orig|bak|tmp)$"),
    re.compile(r".*(\.DS_Store|Thumbs\.db)$"),
    re.compile(r".*device_ui\.xml$"),
]


def audit_android_strings():
    """Verify 1:1 parity of string keys between EN and DE in Android resources."""
    print("== 1. Android String Resource Parity ==")
    if not STRINGS_EN.exists() or not STRINGS_DE.exists():
        print(f"FAIL: Missing string resource files: {STRINGS_EN} or {STRINGS_DE}")
        return False

    tree_en = ET.parse(STRINGS_EN)
    tree_de = ET.parse(STRINGS_DE)

    keys_en = {elem.attrib["name"] for elem in tree_en.getroot().findall("string") if "name" in elem.attrib and elem.attrib.get("translatable") != "false"}
    keys_de = {elem.attrib["name"] for elem in tree_de.getroot().findall("string") if "name" in elem.attrib and elem.attrib.get("translatable") != "false"}

    en_missing_in_de = sorted(keys_en - keys_de)
    de_missing_in_en = sorted(keys_de - keys_en)

    passed = True
    if en_missing_in_de:
        print(f"  FAIL: {len(en_missing_in_de)} keys present in EN but missing in DE:")
        for k in en_missing_in_de[:10]:
            print(f"    - {k}")
        if len(en_missing_in_de) > 10:
            print(f"    ... and {len(en_missing_in_de) - 10} more")
        passed = False

    if de_missing_in_en:
        print(f"  FAIL: {len(de_missing_in_en)} keys present in DE but missing in EN:")
        for k in de_missing_in_en[:10]:
            print(f"    - {k}")
        if len(de_missing_in_en) > 10:
            print(f"    ... and {len(de_missing_in_en) - 10} more")
        passed = False

    if passed:
        print(f"  PASS: All {len(keys_en)} Android string resources match 1:1 across EN and DE.")

    return passed


def audit_web_i18n():
    """Verify 1:1 parity of string keys between EN and DE in web/src/lib/i18n.ts."""
    print("\n== 2. Web i18n Dictionary Parity ==")
    if not I18N_TS.exists():
        print(f"FAIL: Missing i18n file: {I18N_TS}")
        return False

    content = I18N_TS.read_text(encoding="utf-8")

    # Split between en and de sections
    en_match = re.search(r"\ben:\s*\{([^}]+(?:\{[^}]+\}[^}]+)*)\n\s*\},", content)
    de_match = re.search(r"\bde:\s*\{([^}]+(?:\{[^}]+\}[^}]+)*)\n\s*\},", content)

    if not en_match or not de_match:
        print("  FAIL: Unable to locate 'en' or 'de' dictionary blocks in i18n.ts")
        return False

    key_regex = re.compile(r'^\s*"([a-zA-Z0-9_.-]+)":', re.MULTILINE)
    keys_en = set(key_regex.findall(en_match.group(1)))
    keys_de = set(key_regex.findall(de_match.group(1)))

    en_missing_in_de = sorted(keys_en - keys_de)
    de_missing_in_en = sorted(keys_de - keys_en)

    passed = True
    if en_missing_in_de:
        print(f"  FAIL: {len(en_missing_in_de)} keys present in Web EN but missing in DE:")
        for k in en_missing_in_de:
            print(f"    - {k}")
        passed = False

    if de_missing_in_en:
        print(f"  FAIL: {len(de_missing_in_en)} keys present in Web DE but missing in EN:")
        for k in de_missing_in_en:
            print(f"    - {k}")
        passed = False

    if passed:
        print(f"  PASS: All {len(keys_en)} Web translation keys match 1:1 across EN and DE.")

    return passed


def audit_repository_cleanliness():
    """Check for accidental clutter, temporary dumps, or untracked debris."""
    print("\n== 3. Repository Cleanliness ==")
    clutter_found = []

    for root, dirs, files in os.walk(ROOT):
        # Skip node_modules, build directories, and .git
        rel_root = os.path.relpath(root, ROOT)
        if any(part in (".git", "node_modules", ".next", "build", ".gradle", "intermediates") for part in rel_root.split(os.sep)):
            continue

        for f in files:
            rel_path = os.path.join(rel_root, f)
            for pattern in CLUTTER_PATTERNS:
                if pattern.match(f) or pattern.match(rel_path):
                    clutter_found.append(rel_path)

    if clutter_found:
        print(f"  FAIL: Found {len(clutter_found)} accidental / temporary clutter files:")
        for path in clutter_found:
            print(f"    - {path}")
        return False

    print("  PASS: No accidental files, debug dumps, or stale temporary files detected.")
    return True


def audit_design_parity():
    """Execute contrast and palette parity tools to verify design token consistency."""
    print("\n== 4. Design System Parity & Contrast ==")
    passed = True

    if PALETTE_PY.exists():
        result = subprocess.run([sys.executable, str(PALETTE_PY)], cwd=ROOT, capture_output=True, text=True)
        if result.returncode != 0:
            print("  FAIL: palette_parity.py failed:")
            print(result.stdout)
            print(result.stderr)
            passed = False
        else:
            print("  PASS: Color palette tokens agree 100% across Android and Web.")
    else:
        print("  SKIP: palette_parity.py not found.")

    if CONTRAST_PY.exists():
        result = subprocess.run([sys.executable, str(CONTRAST_PY)], cwd=ROOT, capture_output=True, text=True)
        if result.returncode != 0:
            print("  FAIL: contrast.py failed (WCAG failure detected):")
            print(result.stdout)
            print(result.stderr)
            passed = False
        else:
            print("  PASS: All contrast pairings pass WCAG thresholds in both light and dark themes.")
    else:
        print("  SKIP: contrast.py not found.")

    return passed


def main():
    print("Running DeutschFlow Repository Audit...")
    results = [
        audit_android_strings(),
        audit_web_i18n(),
        audit_repository_cleanliness(),
        audit_design_parity(),
    ]

    print("\n========================================")
    if all(results):
        print("ALL AUDITS PASSED: Repository is clean and in full parity.")
        return 0
    else:
        print("AUDIT FAILED: Discrepancies detected.")
        return 1


if __name__ == "__main__":
    sys.exit(main())
