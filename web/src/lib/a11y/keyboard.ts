/**
 * Whether a screen-wide keyboard shortcut may act on this event.
 *
 * A shortcut attached to `window` is the easiest way to make a screen feel fast
 * and the easiest way to break it. Space flips the flashcard — but Space also
 * presses whatever button has focus, and on a study screen that button may be
 * "Again". One keystroke then both reveals the answer and schedules the card as
 * forgotten. Enter is the same story on every link and dialog action.
 *
 * So the decision is not "did the user press Space" but "did the user press
 * Space while something else was already going to handle it". That is what this
 * asks, and it asks it as a pure function of the event so the rule can be tested
 * without a DOM.
 *
 * The rule has two tiers, because the two kinds of key mean different things:
 *
 * - **Typing keys** (a letter, a digit) are ignored whenever focus is in
 *   anything that accepts text. Typing "2" into the search box must not grade a
 *   card.
 * - **Activation keys** (Enter, Space) additionally defer to anything that is
 *   itself activatable — a button, a link, a switch. The focused control wins;
 *   the shortcut is what happens when *nothing* is focused.
 *
 * Both tiers also defer to a modified keypress: Ctrl/Cmd/Alt + key belongs to
 * the browser or the OS (reload, back, copy), never to us.
 */

/** Keys whose native job is to activate the element that has focus. */
const ACTIVATION_KEYS = new Set(["Enter", " ", "Spacebar"]);

/** Elements that consume typed characters. */
const TYPING_TAGS = new Set(["INPUT", "TEXTAREA", "SELECT", "OPTION"]);

/** Elements whose own key handling means "I am already the action". */
const ACTIVATION_TAGS = new Set(["BUTTON", "A", "SUMMARY", "AUDIO", "VIDEO"]);

/** The roles that behave like those elements, however they are drawn. */
const TYPING_ROLES = new Set(["textbox", "searchbox", "combobox"]);

const ACTIVATION_ROLES = new Set([
  "button",
  "link",
  "menuitem",
  "menuitemcheckbox",
  "menuitemradio",
  "option",
  "radio",
  "checkbox",
  "switch",
  "tab",
  "slider",
  "spinbutton",
]);

/**
 * The parts of an event target this needs.
 *
 * Structural rather than `HTMLElement` so the tests can ask the question with a
 * literal. Everything is optional: `window`-level keydowns whose target is the
 * document body report no role and no tag beyond `BODY`.
 */
export interface KeyTargetLike {
  tagName?: string;
  isContentEditable?: boolean;
  getAttribute?: (name: string) => string | null;
}

export interface KeyEventLike {
  key: string;
  ctrlKey?: boolean;
  metaKey?: boolean;
  altKey?: boolean;
  defaultPrevented?: boolean;
  target?: KeyTargetLike | EventTarget | null;
}

function attr(target: KeyTargetLike, name: string): string | null {
  return typeof target.getAttribute === "function" ? target.getAttribute(name) : null;
}

function tagOf(target: KeyTargetLike): string {
  return (target.tagName ?? "").toUpperCase();
}

/** Focus is in something that accepts text, so printable keys belong to it. */
export function isTypingTarget(target: KeyTargetLike | EventTarget | null | undefined): boolean {
  if (!target) return false;
  const like = target as KeyTargetLike;
  if (like.isContentEditable === true) return true;
  if (TYPING_TAGS.has(tagOf(like))) return true;

  const role = attr(like, "role");
  if (role && TYPING_ROLES.has(role)) return true;

  // A text input inside a contenteditable host reports neither the tag nor the
  // editable flag, so the closest editable ancestor is the honest answer.
  return attr(like, "contenteditable") === "true" || attr(like, "contenteditable") === "";
}

/** Focus is on something that already activates on Enter/Space. */
export function isActionableTarget(target: KeyTargetLike | EventTarget | null | undefined): boolean {
  if (!target) return false;
  const like = target as KeyTargetLike;
  if (ACTIVATION_TAGS.has(tagOf(like))) return true;

  const role = attr(like, "role");
  return role !== null && ACTIVATION_ROLES.has(role);
}

export function isActivationKey(key: string): boolean {
  return ACTIVATION_KEYS.has(key);
}

/**
 * True when the shortcut must stand down.
 *
 * @example
 * const onKeyDown = (event: KeyboardEvent) => {
 *   if (shouldIgnoreShortcut(event)) return;
 *   if (event.key === "2") submitReview(ReviewQuality.HARD);
 * };
 */
export function shouldIgnoreShortcut(event: KeyEventLike): boolean {
  // Something earlier in the tree already handled this one.
  if (event.defaultPrevented) return true;

  // Reserved for the browser and the OS.
  if (event.ctrlKey || event.metaKey || event.altKey) return true;

  if (isTypingTarget(event.target)) return true;

  // Enter and Space are the two keys a focused control presses with. Whatever
  // has focus is what the user meant.
  if (isActivationKey(event.key) && isActionableTarget(event.target)) return true;

  return false;
}
