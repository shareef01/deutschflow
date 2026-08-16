import { redirect } from "next/navigation";

/**
 * The Android app opens on Transcript; so does the installed PWA (the manifest
 * start_url is "/").
 */
export default function Home() {
  redirect("/transcript");
}
