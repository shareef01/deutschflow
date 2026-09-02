import { useEffect, useRef } from "react";

/**
 * Keyboard behaviour for anything that declares `role="dialog" aria-modal="true"`.
 *
 * The two dialog components said they were modal and then did nothing about it:
 * no initial focus, no trap, no Escape, no restore. `aria-modal` tells a screen
 * reader the rest of the page is hidden, so a keyboard user who tabbed out of the
 * dialog — which they could, immediately — landed somewhere their reader was no
 * longer describing.
 *
 * Returns a ref to put on the dialog container. The container needs `tabIndex={-1}`
 * so focus can rest on it when nothing inside is focusable yet.
 *
 * @param onDismiss called on Escape. Same callback the backdrop uses, so the two
 * ways out stay in step.
 */
export function useDialogFocus<T extends HTMLElement>(onDismiss: () => void) {
  const containerRef = useRef<T>(null);

  // Read through a ref so the effect does not re-run — and re-steal focus — every
  // time the parent re-renders with a fresh closure.
  const dismissRef = useRef(onDismiss);
  dismissRef.current = onDismiss;

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    // Restored on unmount: without it, closing the dialog drops focus to the top
    // of the document and a keyboard user starts the page again.
    const previouslyFocused = document.activeElement as HTMLElement | null;

    const focusable = () =>
      Array.from(
        container.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
        )
      ).filter((el) => el.offsetParent !== null || el === document.activeElement);

    // Anything already asking for focus (the library editor's autoFocus field)
    // keeps it; otherwise the container takes it so the reader announces the
    // dialog rather than continuing from wherever the page was.
    if (!container.contains(document.activeElement)) {
      // Not focusable()[0]: the first focusable descendant is the full-screen
      // dismiss backdrop, so opening a dialog announced "Dismiss, button" and the
      // first Enter cancelled it. The container carries role="dialog" and
      // tabIndex={-1}, so focusing it is what reads the dialog out; a field that
      // wants focus has already taken it above.
      container.focus();
    }

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        dismissRef.current();
        return;
      }
      if (event.key !== "Tab") return;

      const items = focusable();
      if (items.length === 0) {
        event.preventDefault();
        return;
      }

      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;

      // Wrap at both ends, and pull focus back in if it has escaped the dialog.
      if (event.shiftKey && (active === first || !container.contains(active))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (active === last || !container.contains(active))) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener("keydown", onKeyDown, true);
    return () => {
      document.removeEventListener("keydown", onKeyDown, true);
      previouslyFocused?.focus?.();
    };
  }, []);

  return containerRef;
}
