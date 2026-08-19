import { mkdir, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import { createSessionToken, SESSION_COOKIE } from "../src/lib/auth/session";

/**
 * Signs the smoke suite in before it runs.
 *
 * The app is behind a password gate, so every route redirects to /login without a
 * session. That did not announce itself: the login page has an `h1` and the same
 * background the shell uses, so the five "route loads" tests went on passing
 * against it while the five that click something failed. A suite green on the
 * login screen is worse than a red one.
 *
 * The cookie is minted with the app's own signer rather than stubbed, so the
 * middleware verifies a real signature and the gate is part of what is tested.
 */

export const TEST_PASSWORD = "smoke-suite-key";
export const STORAGE_STATE = "tests/.auth/state.json";

export default async function globalSetup() {
  const token = await createSessionToken(TEST_PASSWORD);

  const state = {
    cookies: [
      {
        name: SESSION_COOKIE,
        value: token,
        domain: "localhost",
        path: "/",
        // Long enough to outlive the run; the token carries its own expiry.
        expires: Math.floor(Date.now() / 1000) + 60 * 60,
        httpOnly: true,
        secure: false,
        sameSite: "Lax" as const,
      },
    ],
    origins: [],
  };

  await mkdir(dirname(STORAGE_STATE), { recursive: true });
  await writeFile(STORAGE_STATE, JSON.stringify(state, null, 2));
}
