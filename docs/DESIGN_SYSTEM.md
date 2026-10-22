# Cerevo / DeutschFlow Design System Specification

**Version:** 2.0 (Modernized Obsidian & Azure)  
**Target Platforms:** Web (Tailwind CSS v4 / Next.js 16) & Android (Jetpack Compose / Material 3)  
**Status:** Living Design System Document  

---

## 1. Design Philosophy

DeutschFlow is an intelligent, calm, and distraction-free German language learning tool. Its design language bridges high-performance productivity software with warm pedagogical focus:

- **Calm, High-Contrast Grounds:** The default dark palette employs Obsidian Black (#0A0E16) with cool, opaque tiered surfaces (#0E131D, #131926, #18202F, #1E2739). Surfaces step forward quietly through subtle elevation and crisp 1px borders rather than noisy drop-shadows or translucent glassmorphism.
- **Purposeful Color Ramp:** Color communicates state, structure, and linguistic feedback. The signature Azure ramp spans from calm transcription cyan (AzureGlow #4EC9E8) to focused action blue (AzureDeep #0A84FF). Tertiary green (#30D158 / #167A3C) marks mastery and success. Warnings (#FFB340) and errors (#FF453A) communicate actionable system status.
- **Dual-Theme Integrity:** The light theme provides an inverted figure-ground structure (#F5F7FA ground, #FFFFFF elevated cards) with tuned color values passing WCAG AA contrast (minimum 4.5:1 for text, 3:1 for UI boundaries).
- **German-First Typography:** German nouns, articles, compounds, and inflections are given primary visual hierarchy. German text uses explicit language tags (`lang="de"`), hyphenation support, and high-legibility sizing.
- **Restrained Motion:** Motion is fast (150-250ms), purposeful, interruptible, and strictly respects `prefers-reduced-motion`.

---

## 2. Design Tokens

### 2.1 Color Tokens & Theming Parity

| Token Name | Dark Theme Value | Light Theme Value | Semantic Role | WCAG Contrast Ratio |
|---|---|---|---|---|
| `background` | #0A0E16 | #F5F7FA | Canvas / root viewport ground | Ground |
| `surface` | #0E131D | #FFFFFF | Base container ground | Tier 1 surface |
| `surface-variant` | #18202F | #E4E9F0 | Elevated sub-container / pill ground | Tier 2 surface |
| `glass-fill` | #131926 | #FFFFFF | Card & input field fill | Default component surface |
| `glass-raised` | #1A2233 | #F0F3F8 | Nested cards, stat tiles, chips | Elevated component surface |
| `primary` | #0A84FF | #0B62C4 | Primary interactive actions, emphasis | > 4.5:1 |
| `primary-light` | #4CC2FF | #3D8FE0 | Secondary highlight, der article badge | > 4.5:1 |
| `secondary / azure-glow` | #4EC9E8 | #0A7288 | Transcription accent, active recording | > 4.5:1 |
| `tertiary` | #30D158 | #167A3C | Success, das article, mastered word | > 4.5:1 |
| `warning` | #FFB340 | #8A5A00 | Caution, review required, streak alert | > 4.5:1 |
| `error` | #FF453A | #C3271C | Error banner, die article badge, again review | > 4.5:1 |
| `on-background` | #F2F5FA | #101623 | Primary text on ground | > 14:1 |
| `on-surface` | #FFFFFF | #0A0E16 | Primary text on cards and inputs | > 15:1 |
| `on-surface-variant` | #98A2B3 | #4A5468 | Secondary labels, captions, metadata | > 7:1 |
| `on-surface-muted` | #788293 | #5C6678 | Muted hints, keyboard shortcuts | > 4.5:1 |
| `outline` | #565F70 | #7A8598 | Card edges, active separators | > 3.0:1 (UI Component) |
| `outline-variant` | #3D4554 | #C3CBD8 | Subtle dividers, hair lines | Decorative divider |

---

## 3. Typography & Text Hierarchy

| Role | Font Family | Size / Line-Height | Weight | Tracking | Usage |
|---|---|---|---|---|---|
| `display-large` | Inter / Roboto / System | 57px / 64px | 800 (Bold) | -0.25px | Hero learning counters |
| `headline-medium` | Inter / Roboto / System | 28px / 36px | 700 (Bold) | 0px | Screen titles, German study words |
| `headline-small` | Inter / Roboto / System | 24px / 32px | 600 (SemiBold) | 0px | Transcribed sentence, card headings |
| `title-medium` | Inter / Roboto / System | 16px / 24px | 600 (SemiBold) | 0.15px | Section headers, card titles |
| `body-large` | Inter / Roboto / System | 16px / 24px | 400 (Regular) | 0.5px | Translations, examples, explanations |
| `body-medium` | Inter / Roboto / System | 14px / 20px | 400 (Regular) | 0.25px | Word details, descriptions |
| `label-large` | Inter / Roboto / System | 14px / 20px | 600 (SemiBold) | 0.1px | Buttons, tabs, interactive labels |
| `label-small` | Inter / Roboto / System | 11px / 16px | 500 (Medium) | 0.5px | Nav bar labels, timestamps |

---

## 4. Spacing, Geometry & Layout Grid

- **Base Unit:** 4px spacing scale (`--space-1`: 4px, `--space-2`: 8px, `--space-3`: 12px, `--space-4`: 16px, `--space-5`: 20px, `--space-6`: 24px, `--space-8`: 32px).
- **Component Geometry:**
  - Interactive Buttons: Minimum height 48px (Web & Android WCAG touch target floor).
  - Search & Text Fields: Minimum height 56px with generous horizontal padding (20px).
  - Border Radii:
    - Small chips / badges: 8px (`--radius-sm`)
    - Cards & dialogs: 16px (`--radius-md`)
    - Inputs & Action Buttons: 24px (`--radius-glass`)
    - Bottom Sheets: 24px top rounded corners (`rounded-t-[24px]`)
- **Responsive Viewport Breakpoints:**
  - Phone Portrait: < 768px (Bottom Navigation Bar, single column layout).
  - Tablet / Phone Landscape: >= 768px with height >= 600px (Desktop Navigation Rail).
  - Large Desktop Split: >= 1024px with height >= 600px (Split master-detail view for Library).
  - Content Max-Widths:
    - Reading / Forms: 640px (`--container-reading`)
    - Workspace / Single Card: 768px (`--container-workspace`)
    - Wide / Split Panes: 1280px (`--container-wide`)

---

## 5. Standard Component Specifications

### 5.1 Buttons (`GlassButton` / `PrimaryActionButton`)
- 48px minimum touch height, smooth scale feedback on press (`active:scale-[0.98]`).
- High-contrast 1px hairline border with glow variants:
  - Default: Primary Azure Deep (#0A84FF).
  - Azure Glow: Transcription / Speak (#4EC9E8).
  - Green: Easy review / Success (#30D158).
  - Amber: Hard review / Warning (#FFB340).
  - Error: Stop recording / Again review (#FF453A).

### 5.2 Inputs (`GlassTextField` / `SearchInput`)
- 56px height, pill container with 1px border.
- Active focus state lights the outline to AzureGlow (#4EC9E8) without layout shift.
- Instant clear button (`✕`) shown whenever input is populated.

### 5.3 Word Details Bottom Sheet
- Max-height constrained to 85dvh on Web, with internal smooth vertical scrolling.
- Explicit close icon button at top right corner.
- Full safe-area inset padding at bottom for mobile gestures.
- Centered elevated dialog modal presentation on desktop viewports (>= 768px).
- Pronunciation audio trigger for immediate acoustic reinforcement.

### 5.4 Study Dashboard Action Hero
- Prominently showcases "Due Today" count and an unmissable "Start Daily Review" CTA.
- Visual status ring with live XP count and current streak indicator.
- 12-week activity heatmap with accessible ARIA descriptions.

### 5.5 Flashcard Card Component
- Distinct front (German word + article badge + prompt to flip) and back (English translation + German reminder + example sentence).
- 4-tier grading controls with interval previews and accessible number shortcuts ([1] Again, [2] Hard, [3] Good, [4] Easy).
