"use client";

import { usePathname, useRouter } from "next/navigation";
import type { ReactNode } from "react";
import { useIsDesktop } from "@/hooks/useViewport";
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
  const isDesktop = useIsDesktop();
  const { t } = useI18n();

  const currentTab = tabForRoute(pathname);
  const isOnSettings = pathname === SETTINGS_ROUTE;
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
      {/* Top bar — full-width, like the Android bar: a transparent bar lets
          content scroll straight through the title. */}
      <header className="sticky top-0 z-30 border-b border-azure-glow/15 bg-background/95 backdrop-blur-xl pt-[env(safe-area-inset-top)]">
        <div className="flex h-16 items-center">
          <div className="flex w-full items-center gap-3 px-6">
            {isOnSettings && (
              <button
                type="button"
                onClick={goBack}
                aria-label={t("action.back")}
                className="glass-button flex size-10 shrink-0 items-center justify-center text-on-surface-variant transition-all duration-200 hover:text-azure-glow hover:shadow-lg hover:shadow-azure-glow/20 active:scale-95"
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
                className="glass-button flex size-10 shrink-0 items-center justify-center text-on-surface-variant transition-all duration-200 hover:text-azure-glow hover:shadow-lg hover:shadow-azure-glow/20 active:scale-95"
              >
                <SettingsIcon className="size-5" />
              </button>
            )}
          </div>
        </div>
      </header>

      <div className="flex flex-1 min-h-0">
        {/* Desktop rail — hugs the screen's left edge, like the Android
            NavigationRail; the content column is centred in what remains. */}
        {isDesktop && !isOnSettings && (
          <nav
            aria-label="Primary"
            className="sticky top-16 flex h-[calc(100dvh-4rem)] w-32 shrink-0 flex-col items-center justify-evenly border-r border-white/[0.06] bg-white/[0.02] backdrop-blur-xl"
          >
            {TABS.map((tab) => {
              const selected = pathname === tab.route;
              const label = t(tab.title);
              return (
                <button
                  key={tab.route}
                  type="button"
                  onClick={() => navigate(tab.route)}
                  aria-current={selected ? "page" : undefined}
                  className={`relative flex w-28 flex-col items-center gap-2 rounded-3xl px-3 py-3 transition-[background-color,transform] duration-200 active:scale-[0.97] ${
                    selected ? "" : "hover:bg-white/[0.05] active:bg-white/[0.1]"
                  }`}
                >
                  <span
                    className={`flex size-12 items-center justify-center ${
                      selected ? "glass-nav-active shadow-lg shadow-azure-glow/10" : ""
                    }`}
                  >
                    <tab.icon
                      className={`size-6 transition-colors duration-200 ${
                        selected ? "text-azure-glow" : "text-on-surface-variant"
                      }`}
                    />
                  </span>
                  <span
                    className={`max-w-full truncate text-xs font-bold leading-none transition-colors duration-200 ${
                      selected ? "text-on-surface" : "text-on-surface-variant"
                    }`}
                  >
                    {label}
                  </span>
                </button>
              );
            })}
          </nav>
        )}

        {/* The content column — left-aligned to match the title; the max width
            the cards may use. */}
        <div className="flex w-full max-w-6xl min-w-0 min-h-0 flex-1 flex-col">
          <main
            className={`flex min-w-0 min-h-0 flex-1 flex-col ${
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
