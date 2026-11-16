"use client";

import { useEffect, useSyncExternalStore } from "react";
import Link from "next/link";
import Image from "next/image";
import { usePathname, useRouter } from "next/navigation";
import { db, forgetVaultKey, isUpgradeBlocked, subscribeUpgradeBlocked } from "@/lib/db";
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
 * transforms into a left-hand rail (the NavigationRail). Settings
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

  useEffect(() => {
    const endLocalSession = () => {
      db.close();
      forgetVaultKey();
      window.location.href = "/login";
    };
    // 1. Cross-tab session listener via BroadcastChannel
    let authChannel: BroadcastChannel | null = null;
    if (typeof window !== "undefined" && "BroadcastChannel" in window) {
      try {
        authChannel = new BroadcastChannel("deutschflow_auth");
        authChannel.onmessage = (event) => {
          if (event.data?.type === "LOGOUT") {
            endLocalSession();
          }
        };
      } catch {
        // BroadcastChannel may be restricted in some environments
      }
    }

    // 2. Service worker session listener
    const handleSwMessage = (event: MessageEvent) => {
      if (event.data?.type === "SESSION_TERMINATED") {
        endLocalSession();
      }
    };
    if (typeof navigator !== "undefined" && navigator.serviceWorker) {
      navigator.serviceWorker.addEventListener("message", handleSwMessage);
    }

    return () => {
      authChannel?.close();
      if (typeof navigator !== "undefined" && navigator.serviceWorker) {
        navigator.serviceWorker.removeEventListener("message", handleSwMessage);
      }
    };
  }, []);

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

  return (
    // h-dvh, not min-h-dvh. `min-height` lets the shell grow with its content,
    // which silently disables every bounded scroll region inside it: `h-full` and
    // `flex-1` resolve to the *content* height, so a screen that asks for its own
    // scroll area gets a page that scrolls instead. The roleplay conversation was
    // the visible casualty — a long reply pushed the microphone and the text input
    // off the bottom of the document, and the log itself never scrolled, so the
    // "follow the conversation" logic could never fire.
    <div className="flex h-dvh flex-col overflow-hidden bg-background text-on-surface">
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
      <header className="sticky top-0 z-30 border-b border-outline-variant/55 bg-surface-container-low pt-[env(safe-area-inset-top)]">
        <div className="flex h-[var(--header-height)] items-center">
          {showRail && (
            <div className="flex h-full w-[var(--nav-rail-width)] shrink-0 items-center gap-2.5 border-r border-outline-variant/55 px-5">
              <Image src="/icons/icon-192.png" alt="" width={32} height={32} className="rounded-[10px]" loading="eager" unoptimized />
              <span className="text-title-medium tracking-tight">DeutschFlow</span>
            </div>
          )}
          <div className="flex w-full items-center gap-[var(--space-3)] px-[var(--gutter)]">
            {isOnSettings && (
              <button
                type="button"
                onClick={goBack}
                aria-label={t("action.back")}
                className="flex size-11 shrink-0 items-center justify-center rounded-xl text-on-surface-variant transition-colors hover:bg-surface-container-high hover:text-on-surface focus-visible:outline-2 focus-visible:outline-azure-glow"
              >
                <ArrowBackIcon className="size-5" />
              </button>
            )}
            <h1 className="min-w-0 flex-1 truncate text-left text-title-large">
              <span>{title}</span>
            </h1>
            {!isOnSettings && (
              <Link
                href={SETTINGS_ROUTE}
                aria-label={t("nav.settings")}
                className="flex size-11 shrink-0 items-center justify-center rounded-xl border border-outline-variant/60 text-on-surface-variant transition-colors hover:bg-surface-container-high hover:text-on-surface focus-visible:outline-2 focus-visible:outline-azure-glow"
              >
                <SettingsIcon className="size-5" />
              </Link>
            )}
          </div>
        </div>
      </header>

      {/* One row, held to the space the header leaves. An implicit grid row is
          `auto`, which grows to the content's full height however short the
          container is - so inside the fixed-height shell every page's own
          scroll region was as tall as its content and never scrolled. On a
          1440x900 desktop, a 30-word library put its last row at y=3241 with
          nothing the mouse wheel could move. */}
      <div
        className={`grid min-h-0 flex-1 grid-rows-[minmax(0,1fr)] ${
          showRail ? "grid-cols-[auto_minmax(0,1fr)]" : "grid-cols-1"
        }`}
      >
        {/* Desktop rail — hugs the screen's left edge, like the Android
            NavigationRail; the content column is centred in what remains. */}
        {showRail && (
          <nav
            aria-label={t("nav.primary")}
            className="flex min-h-0 w-[var(--nav-rail-width)] shrink-0 flex-col gap-1.5 overflow-y-auto border-r border-outline-variant/55 bg-surface-container-low px-3 py-5"
          >
            {TABS.map((tab) => {
              const selected = pathname === tab.route;
              const label = t(tab.title);
              return (
                <Link
                  key={tab.route}
                  href={tab.route}
                  aria-current={selected ? "page" : undefined}
                  className={`relative flex min-h-11 w-full shrink-0 items-center gap-3 rounded-xl border px-3 text-left transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-azure-glow ${
                    selected
                      ? "border-azure-glow/30 bg-secondary-container text-on-secondary-container"
                      : "border-transparent text-on-surface-variant hover:bg-surface-container-high hover:text-on-surface"
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
                </Link>
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
              !isDesktop && !isOnSettings ? "pb-[calc(var(--nav-bar-height)+env(safe-area-inset-bottom))]" : ""
            }`}
          >
            {children}
          </main>
        </div>
      </div>

      {/* Mobile bottom bar — a solid container with a hairline above it. */}
      {!isDesktop && !isOnSettings && (
        <nav
          aria-label={t("nav.primary")}
          className="fixed inset-x-0 bottom-0 z-30 border-t border-outline-variant/55 bg-surface-container-low pb-[env(safe-area-inset-bottom)]"
        >
          <div className="grid min-h-[var(--nav-bar-height)] grid-cols-5 gap-1 px-1">
            {TABS.map((tab) => {
              const selected = pathname === tab.route;
              const label = t(tab.title);
              return (
                <Link
                  key={tab.route}
                  href={tab.route}
                  aria-current={selected ? "page" : undefined}
                  className="flex min-w-0 flex-col items-center justify-center gap-0.5 rounded-lg py-1.5 transition-colors focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-azure-glow"
                >
                  <span className={`grid h-7 w-11 place-items-center rounded-full ${selected ? "bg-secondary-container text-on-secondary-container" : "text-on-surface-variant"}`}>
                    <tab.icon className="size-5" />
                  </span>
                  <span
                    className={`max-w-full truncate text-label-small ${
                      selected ? "font-bold text-azure-glow" : "text-on-surface-variant"
                    }`}
                  >
                    {label}
                  </span>
                </Link>
              );
            })}
          </div>
        </nav>
      )}
    </div>
  );
}
