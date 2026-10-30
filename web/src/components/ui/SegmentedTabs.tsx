"use client";

import { useRef } from "react";

export interface TabItem<T extends string = string> {
  id: T;
  label: string;
  panelId?: string;
}

/**
 * Reusable accessible segmented tab control following WAI-ARIA Tabs pattern.
 *
 * Provides:
 * - role="tablist" on container
 * - role="tab", aria-selected, aria-controls, stable ID, and roving tabIndex on buttons
 * - Full keyboard navigation: ArrowLeft, ArrowRight, Home, End
 */
export function SegmentedTabs<T extends string>({
  value,
  onValueChange,
  tabs,
  ariaLabel,
  className = "",
}: {
  value: T;
  onValueChange: (val: T) => void;
  tabs: readonly TabItem<T>[];
  ariaLabel?: string;
  className?: string;
}) {
  const tabListRef = useRef<HTMLDivElement>(null);

  const handleKeyDown = (event: React.KeyboardEvent, index: number) => {
    let nextIndex = -1;
    if (event.key === "ArrowRight") {
      event.preventDefault();
      nextIndex = (index + 1) % tabs.length;
    } else if (event.key === "ArrowLeft") {
      event.preventDefault();
      nextIndex = (index - 1 + tabs.length) % tabs.length;
    } else if (event.key === "Home") {
      event.preventDefault();
      nextIndex = 0;
    } else if (event.key === "End") {
      event.preventDefault();
      nextIndex = tabs.length - 1;
    }

    if (nextIndex >= 0) {
      onValueChange(tabs[nextIndex].id);
      requestAnimationFrame(() => {
        const buttons = tabListRef.current?.querySelectorAll<HTMLButtonElement>('[role="tab"]');
        buttons?.[nextIndex]?.focus();
      });
    }
  };

  return (
    <div
      ref={tabListRef}
      role="tablist"
      aria-label={ariaLabel}
      className={`flex w-full shrink-0 justify-center gap-2 border-b border-outline-variant/60 px-[var(--gutter)] sm:gap-6 ${className}`}
    >
      {tabs.map((tab, index) => {
        const isSelected = value === tab.id;
        return (
          <button
            key={tab.id}
            id={`tab-${tab.id}`}
            type="button"
            role="tab"
            aria-selected={isSelected}
            aria-controls={tab.panelId ?? `tabpanel-${tab.id}`}
            tabIndex={isSelected ? 0 : -1}
            onClick={() => onValueChange(tab.id)}
            onKeyDown={(e) => handleKeyDown(e, index)}
            // Sentence case at label size: a tab names a place, and two words in
            // 0.1em-tracked capitals read louder than the page title above them.
            className={`-mb-px min-h-12 flex-1 border-b-2 px-4 text-label-large transition-colors focus-visible:outline-2 focus-visible:outline-azure-glow sm:flex-none sm:px-6 ${
              isSelected
                ? "border-primary text-primary"
                : "border-transparent text-on-surface-variant hover:text-on-surface"
            }`}
          >
            {tab.label}
          </button>
        );
      })}
    </div>
  );
}
