import { AppShell } from "@/components/layout/AppShell";
import { PwaRegister } from "@/components/PwaRegister";

/**
 * The app group layout — every screen renders inside the responsive shell.
 */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <>
      <AppShell>{children}</AppShell>
      <PwaRegister />
    </>
  );
}
