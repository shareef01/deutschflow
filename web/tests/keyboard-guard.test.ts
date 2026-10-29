import { describe, expect, it } from "vitest";
import {
  isActionableTarget,
  isActivationKey,
  isTypingTarget,
  shouldIgnoreShortcut,
} from "@/lib/a11y/keyboard";

/**
 * The rule that decides whether a screen-wide shortcut may take a keypress.
 *
 * These are the shapes that actually occur: a keydown with nothing focused, with
 * the flashcard button focused, with the search box focused, and with a modifier
 * held. The bug being guarded against is real and was silent — Space flipped the
 * card *and* pressed whichever grade button had focus, so an unrevealed card
 * could be graded as forgotten by one keystroke.
 */

const el = (tagName: string, role?: string, extra: Record<string, unknown> = {}) => ({
  tagName,
  getAttribute: (name: string) => (name === "role" ? (role ?? null) : null),
  ...extra,
});

describe("shouldIgnoreShortcut", () => {
  it("takes the key when nothing is focused", () => {
    expect(shouldIgnoreShortcut({ key: " ", target: el("BODY") })).toBe(false);
  });

  it("takes the key when the target is unknown", () => {
    expect(shouldIgnoreShortcut({ key: "2" })).toBe(false);
  });

  it("defers to the focused button for Enter", () => {
    expect(shouldIgnoreShortcut({ key: "Enter", target: el("BUTTON") })).toBe(true);
  });

  it("defers to the focused button for Space", () => {
    expect(shouldIgnoreShortcut({ key: " ", target: el("BUTTON") })).toBe(true);
  });

  it("allows a digit shortcut while a button has focus", () => {
    // 1–4 are not buttons' own keys, so they stay ours. Otherwise the grading
    // shortcuts would only work with focus parked on the card.
    expect(shouldIgnoreShortcut({ key: "3", target: el("BUTTON") })).toBe(false);
  });

  it("refuses both kinds of key while a text field has focus", () => {
    for (const key of ["Enter", " ", "1", "a"]) {
      expect(shouldIgnoreShortcut({ key, target: el("INPUT") })).toBe(true);
    }
  });

  it("refuses while a textarea, select or contenteditable has focus", () => {
    expect(shouldIgnoreShortcut({ key: "2", target: el("TEXTAREA") })).toBe(true);
    expect(shouldIgnoreShortcut({ key: "2", target: el("SELECT") })).toBe(true);
    expect(shouldIgnoreShortcut({ key: "2", target: el("DIV", undefined, { isContentEditable: true }) })).toBe(
      true
    );
  });

  it("refuses a modified keypress", () => {
    expect(shouldIgnoreShortcut({ key: "2", ctrlKey: true, target: el("BODY") })).toBe(true);
    expect(shouldIgnoreShortcut({ key: "2", metaKey: true, target: el("BODY") })).toBe(true);
    expect(shouldIgnoreShortcut({ key: "2", altKey: true, target: el("BODY") })).toBe(true);
  });

  it("refuses an event something earlier already handled", () => {
    expect(
      shouldIgnoreShortcut({ key: " ", defaultPrevented: true, target: el("BODY") })
    ).toBe(true);
  });

  it("defers to elements that are buttons in all but tag", () => {
    expect(shouldIgnoreShortcut({ key: " ", target: el("DIV", "button") })).toBe(true);
    expect(shouldIgnoreShortcut({ key: "Enter", target: el("A") })).toBe(true);
    expect(shouldIgnoreShortcut({ key: "Enter", target: el("DIV", "menuitem") })).toBe(true);
  });

  it("refuses any key while a combo box has focus", () => {
    expect(shouldIgnoreShortcut({ key: "1", target: el("DIV", "combobox") })).toBe(true);
  });
});

describe("isTypingTarget / isActionableTarget", () => {
  it("separates the two tiers", () => {
    expect(isTypingTarget(el("INPUT"))).toBe(true);
    expect(isTypingTarget(el("BUTTON"))).toBe(false);
    expect(isActionableTarget(el("BUTTON"))).toBe(true);
    expect(isActionableTarget(el("INPUT"))).toBe(false);
  });

  it("treats a null target as neither", () => {
    expect(isTypingTarget(null)).toBe(false);
    expect(isActionableTarget(null)).toBe(false);
  });

  it("knows the activation keys", () => {
    expect(isActivationKey("Enter")).toBe(true);
    expect(isActivationKey(" ")).toBe(true);
    expect(isActivationKey("2")).toBe(false);
  });
});
