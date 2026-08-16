import type { ComponentType } from "react";
import {
  BookIcon,
  HistoryIcon,
  MicIcon,
  RecordVoiceOverIcon,
  SchoolIcon,
} from "@/components/icons";
import type { TKey } from "@/lib/i18n";

/**
 * The five tab destinations — ui/navigation/Screen.kt `navItems`.
 * Settings is deliberately NOT here: like the Android app, it is a full-screen
 * detail destination pushed on top of the tabs, not a tab itself.
 */
export interface TabConfig {
  route: string;
  title: TKey;
  icon: ComponentType<{ className?: string }>;
}

export const TABS: TabConfig[] = [
  { route: "/transcript", title: "nav.transcript", icon: MicIcon },
  { route: "/history", title: "nav.history", icon: HistoryIcon },
  { route: "/vocabulary", title: "nav.library", icon: BookIcon },
  { route: "/study", title: "nav.study", icon: SchoolIcon },
  { route: "/practice", title: "nav.practice", icon: RecordVoiceOverIcon },
];

export const SETTINGS_ROUTE = "/settings";

export function tabForRoute(route: string | null): TabConfig | undefined {
  return TABS.find((tab) => tab.route === route);
}
