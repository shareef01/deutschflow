/**
 * Tailwind CSS v4 is CSS-first: the Obsidian & Azure design tokens live in
 * `src/app/globals.css` inside `@theme` — that file is the real configuration.
 *
 * This file exists so tooling that still looks for a `tailwind.config.js` finds
 * one, and to state the content roots explicitly. In v4, content detection is
 * automatic and this file is optional; the tokens here are NOT duplicated,
 * because CSS `@theme` is the single source of truth.
 *
 * @type {import('tailwindcss').Config}
 */
export default {
  content: ["./src/**/*.{ts,tsx}"],
};
