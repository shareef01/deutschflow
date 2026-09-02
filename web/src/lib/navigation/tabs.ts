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
 * The five tab destinations.
 *
 * Android's `navItems` has four: History lives there as a pane inside Library
 * (see LibraryScreen.kt's SegmentedTabs), where a wider phone screen has room for
 * the split. Here it is a destination of its own. That is a deliberate difference
 * and the only one in the navigation model.
 *
 * Settings is not a tab on either platform: it is a full-screen detail
 * destination pushed on top, and it sheds both bars while it is open.
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
