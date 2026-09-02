"use client";

import { useSyncExternalStore } from "react";
import { usePathname, useRouter } from "next/navigation";
import { isUpgradeBlocked, subscribeUpgradeBlocked } from "@/lib/db";
import type { ReactNode } from "react";
import { useHasRail } from "@/hooks/useViewport";
import { useI18n } from "@/hooks/useI18n";
import { SETTINGS_ROUTE, TABS, tabForRoute } from "@/lib/navigation/tabs";
import { ArrowBackIcon, SettingsIcon } from "@/components/icons";

/**
 * AppShell — ui/navigation/Navigation.kt port.
 *
 * Below 768px the five destinations live in a fixed bottom bar (the Compose
 * NavigationBar, with the hairline above it); at 768px and above the bar
 * transforms into a glassmorphic left-hand rail (the NavigationRail). Settings
 * is a pushed detail destination: it keeps the back arrow and sheds both bars.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const isDesktop = useHasRail();
  const { t } = useI18n();
  const upgradeBlocked = useSyncExternalStore(
    subscribeUpgradeBlocked,
    isUpgradeBlocked,
    () => false
  );

  const currentTab = tabForRoute(pathname);
  const isOnSettings = pathname === SETTINGS_ROUTE;
  /** The rail occupies a grid column only when it is actually rendered. */
  const showRail = isDesktop && !isOnSettings;
  const title = isOnSettings ? t("nav.settings") : (currentTab ? t(currentTab.title) : "DeutschFlow");

  const goBack = () => {
    // The analogue of popping the navigation stack.
    if (window.history.state?.idx > 0) router.back();
    else router.push("/transcript");
  };

  const navigate = (route: string) => {
    if (pathname !== route) router.push(route);
  };

  return (
    <div className="flex min-h-dvh flex-col bg-background text-on-surface">
      {/* First thing in the tab order, visible only on focus. With a sticky header
          and a five-item rail, a keyboard user otherwise tabs through the whole
          navigation on every page before reaching anything they came for. */}
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-lg focus:bg-surface-container-high focus:px-4 focus:py-2 focus:text-on-surface focus:outline-2 focus:outline-azure-glow"
      >
        {t("action.skipToContent")}
      </a>

      {/* Blocked upgrades are silent by default: IndexedDB waits forever for the
          older tab rather than failing, so the page just never loads its data. */}
      {upgradeBlocked && (
        <p role="alert" className="bg-error-container px-4 py-2 text-center text-label-medium text-on-error-container">
          {t("db.upgradeBlocked")}
        </p>
      )}
      {/* Top bar — full-width, like the Android bar: a transparent bar lets
          content scroll straight through the title. */}
      <header className="sticky top-0 z-30 border-b border-azure-glow/15 bg-background/95 backdrop-blur-xl pt-[env(safe-area-inset-top)]">
        <div className="flex h-[var(--header-height)] items-center">
          <div className="flex w-full items-center gap-[var(--space-3)] px-[var(--gutter)]">
            {isOnSettings && (
              <button
                type="button"
                onClick={goBack}
                aria-label={t("action.back")}
                className="glass-button flex size-11 shrink-0 items-center justify-center text-on-surface-variant transition-all duration-200 hover:text-azure-glow hover:shadow-lg hover:shadow-azure-glow/20 active:scale-95"
              >
                <ArrowBackIcon className="size-5" />
              </button>
            )}
            <h1 className="min-w-0 flex-1 truncate text-left text-headline-small md:text-headline-medium">
              <span>{title}</span>
            </h1>
            {!isOnSettings && (
              <button
                type="button"
                onClick={() => navigate(SETTINGS_ROUTE)}
                aria-label={t("nav.settings")}
                className="glass-button flex size-11 shrink-0 items-center justify-center text-on-surface-variant transition-all duration-200 hover:text-azure-glow hover:shadow-lg hover:shadow-azure-glow/20 active:scale-95"
              >
                <SettingsIcon className="size-5" />
              </button>
            )}
          </div>
        </div>
      </header>

      <div
        className={`grid min-h-0 flex-1 ${
          showRail ? "grid-cols-[auto_minmax(0,1fr)]" : "grid-cols-1"
        }`}
      >
        {/* Desktop rail — hugs the screen's left edge, like the Android
            NavigationRail; the content column is centred in what remains. */}
        {showRail && (
          <nav
            aria-label="Primary"
            className="sticky top-[var(--header-height)] flex h-[calc(100dvh-var(--header-height))] w-[var(--nav-rail-width)] shrink-0 flex-col gap-[var(--space-1)] overflow-y-auto border-r border-on-surface/[0.06] bg-on-surface/[0.02] px-[var(--space-3)] py-[var(--space-5)] backdrop-blur-xl"
          >
            {TABS.map((tab) => {
              const selected = pathname === tab.route;
              const label = t(tab.title);
              return (
                // Label beside the icon, one consistent 44px row. The vertical
                // icon-over-label tile was shaped for a 128px column and made
                // each destination as tall as a card, which is what spread the
                // five of them down the whole screen. The active state is a
                // filled row plus an edge marker rather than a heavy pill, so
                // it reads at a glance without shouting.
                <button
                  key={tab.route}
                  type="button"
                  onClick={() => navigate(tab.route)}
                  aria-current={selected ? "page" : undefined}
                  className={`group relative flex h-11 w-full items-center gap-[var(--space-3)] rounded-xl px-[var(--space-3)] text-left transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-azure-glow ${
                    selected
                      ? "bg-azure-glow/10 text-on-surface"
                      : "text-on-surface-variant hover:bg-on-surface/[0.05] hover:text-on-surface"
                  }`}
                >
                  {/* Not colour alone: the selected row carries a marker. */}
                  <span
                    aria-hidden="true"
                    className={`absolute left-0 top-1/2 h-5 w-0.5 -translate-y-1/2 rounded-r bg-azure-glow transition-opacity duration-150 ${
                      selected ? "opacity-100" : "opacity-0"
                    }`}
                  />
                  <tab.icon
                    className={`size-5 shrink-0 transition-colors duration-150 ${
                      selected ? "text-azure-glow" : "text-current"
                    }`}
                  />
                  <span className="min-w-0 truncate text-label-large">{label}</span>
                </button>
              );
            })}
          </nav>
        )}

        {/* The content column — left-aligned to match the title; the max width
            the cards may use. */}
        <div className="flex w-full min-w-0 min-h-0 flex-1 flex-col">
          <main
            id="main"
            tabIndex={-1}
            className={`flex min-w-0 min-h-0 flex-1 flex-col outline-none ${
              !isDesktop && !isOnSettings ? "pb-[calc(6rem+env(safe-area-inset-bottom))]" : ""
            }`}
          >
            {children}
          </main>
        </div>
      </div>

      {/* Mobile bottom bar — a solid container with a hairline above it. */}
      {!isDesktop && !isOnSettings && (
        <nav
          aria-label="Primary"
          className="fixed inset-x-0 bottom-0 z-30 bg-background pb-[env(safe-area-inset-bottom)]"
        >
          <div className="hairline-azure" />
          <div className="grid grid-cols-5">
            {TABS.map((tab) => {
              const selected = pathname === tab.route;
              const label = t(tab.title);
              return (
                <button
                  key={tab.route}
                  type="button"
                  onClick={() => navigate(tab.route)}
                  className="flex flex-col items-center gap-0.5 py-2"
                >
                  <tab.icon
                    className={selected ? "size-6 text-azure-glow" : "size-6 text-on-surface-variant"}
                  />
                  <span
                    className={`max-w-full truncate text-label-small ${
                      selected ? "font-bold text-azure-glow" : "text-on-surface-variant"
                    }`}
                  >
                    {label}
                  </span>
                </button>
              );
            })}
          </div>
        </nav>
      )}
    </div>
  );
}
