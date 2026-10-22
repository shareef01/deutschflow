# DeutschFlow Security Architecture & Threat Model

This document outlines the security architecture, cryptographic controls, authentication lifecycle, threat models, and deployment requirements for the DeutschFlow learning system.

---

## 1. Authentication & Session Security

### 1.1 Stateless HMAC Token Model
DeutschFlow uses a signed, stateless session token architecture stored in an `HttpOnly`, `SameSite=Lax` cookie (`df_access`).
The token format is `<expiresAt>.<hmacSignature>`, where:
- `expiresAt`: Millisecond epoch timestamp when the session expires.
- `hmacSignature`: Base64URL-encoded HMAC-SHA256 signature generated over `expiresAt`.

### 1.2 Multi-Factor Signing Key Derivation
To prevent hardcoded or static key material, the active signing key is derived dynamically:
```
signingKey = HMAC-SHA256(SESSION_SECRET, "${SESSION_SECRET}:${SITE_PASSWORD}:${SESSION_EPOCH}")
```
This multi-factor derivation guarantees:
1. **Password Invalidation**: Changing `SITE_PASSWORD` immediately invalidates all existing sessions across all browsers.
2. **Key Rotation**: Rotating `SESSION_SECRET` immediately invalidates all outstanding sessions.
3. **Instant Global Revocation**: Incrementing `SESSION_EPOCH` (e.g., from `"1"` to `"2"`) immediately invalidates all active sessions without requiring password changes.

### 1.3 Configurable Session Lifetime
- **Default Duration**: 30 days (`2,592,000` seconds).
- **Configuration**: Overridden via the `SESSION_MAX_AGE_SECONDS` environment variable.
- **Safety Bounds**: Enforced between 1 hour (`3,600` seconds) and 90 days (`7,776,000` seconds). Out-of-bound values safely default to 30 days.

### 1.4 State-Changing Logout & CSRF Protection
- The logout endpoint (`POST /api/logout`) strictly requires a POST method and rejects a request whose `Origin` header names another origin (`isAllowedOrigin` in `src/app/api/logout/route.ts`). A request that carries no `Origin` header is allowed: the endpoint only clears this browser's own cookie, so the worst case is a self-inflicted logout, and a non-browser client that omits `Origin` has nothing to forge.
- `SameSite=Lax` on `df_access` is the second line of defence: a cross-site form POST does not carry the cookie at all, so a third-party page cannot even reach a state change here.
- `GET /api/logout` is strictly disabled: it returns HTTP 405 Method Not Allowed (`Allow: POST`) and executes no state changes, preventing prefetch logouts and unauthorized GET CSRF.
- Upon logout, the `df_access` cookie is cleared with `maxAge: 0` and `expires: Epoch 0`, alongside cache-invalidation headers (`Cache-Control: no-store`).

---

## 2. Distributed Authentication Rate Limiting

### 2.1 Multi-Tier Rate Limiting Architecture
To mitigate brute-force password guessing against the gate:
- **Free Attempts**: Up to 3 attempts allowed without delay.
- **Exponential Backoff**: Doubling cooldown starting at 1s for the 4th attempt (`1s, 2s, 4s, 8s, 16s...`) capped at `30s`.
- **Sliding Window**: 15 minutes (`WINDOW_MS = 900,000ms`).

### 2.2 Distributed Edge Store (Upstash Redis)
- **Deployment Strategy**: When deployed on serverless edge environments (e.g., Vercel), in-memory state is not shared across isolated containers.
- **Centralized Integration**: Setting `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN` routes throttle state to a shared atomic Redis store via HTTPS REST.
- **Atomic Lua Script & Multi-Exec**:
  - *Pipeline Non-Atomicity*: Note that per official Upstash documentation, `/pipeline` batches commands over a single HTTP connection but does **not** execute them atomically or isolate them from concurrent operations.
  - *Atomic State Decisions*: To eliminate read-modify-write race conditions when evaluating lockout thresholds, `recordFailure` executes an atomic Lua script over `POST /` (`["EVAL", script, <key count>, countKey, lastKey, ...]`). The script atomically inspects the current attempt count, increments it, applies lockout cooldowns, and updates the sliding expiration in a single isolated step.
  - *Transactional Batching*: For operations without conditional dependencies (e.g. setting records, checking counters, and clearing keys), the engine uses `/multi-exec` to guarantee atomic multi-command execution.
- **Configurable Failure Policy (`THROTTLE_FAILURE_POLICY`)**:
  - `fail-secure-fallback` (default): If Redis times out or is unreachable, the system falls back to an in-memory LRU rate limiter. This provides local burst defense per node while keeping the application accessible.
  - `fail-closed`: If Redis fails or is unreachable, the endpoint strictly rejects authentication requests with HTTP 503 Service Unavailable (`ThrottleStoreError`), ensuring no attacker can bypass distributed lockouts during a store outage.
- **Distributed Protection vs Local Fallback Distinction**: Local LRU fallback protects individual instances from rapid flooding, but cannot synchronize lockouts globally across ephemeral serverless instances. For full production-grade distributed enforcement, a highly available Upstash Redis cluster with valid credentials and network egress is required.

### 2.3 Proxy Trust Boundaries & Client Identity Validation
- Forwarded client-IP headers (`x-forwarded-for`, `x-real-ip`) are **only** trusted when deployed behind a validated edge infrastructure boundary (`process.env.VERCEL === "1"` with edge request provenance).
- **Standards-Compliant IP Validation**: Client IP format is validated using Node.js's complete `node:net.isIP` standard validator rather than permissive regular expressions. This strictly rejects malformed addresses, trailing characters, invalid separators, and IPv4 octets above 255, while correctly accepting valid compressed IPv6 (e.g. `::1`, `::`) and IPv4-mapped IPv6 addresses (`::ffff:192.0.2.128`).
- **Edge Header Precedence & Anti-Spoofing Chain Resolution**:
  - `x-real-ip` is prioritized as Vercel's edge-verified single client socket address.
  - When inspecting `x-forwarded-for`, incoming reverse proxies append the connecting IP to the end of the chain. To prevent callers from evading rate limits by prepending spoofed IPs to their own request headers, the validator selects the edge-appended rightmost hop.
- **Clarification on `x-vercel-id`**: `x-vercel-id` is an internal routing trace header generated by Vercel for diagnostic and deployment identification, **not** an authenticated cryptographic edge signature.
- **Non-Vercel / Multi-Proxy Limitations**: If deployed outside Vercel (or behind arbitrary untrusted reverse proxies), `x-forwarded-for` can easily be forged by clients if the ingress proxy does not strip or overwrite incoming forwarding headers. In unverified or untrusted environments, caller identities default to a safe shared fallback bucket (`unknown`) to prevent attackers from arbitrarily evading rate limits by spoofing random IP headers.
  - **Consequence of the shared bucket**: one attacker's failed guesses put *every* user into the cooldown, and any user's successful login resets the bucket, giving the attacker fresh free attempts. The reset can't simply be skipped: admission counts successful logins too, so without it any three correct logins within the window would lock everyone out. A strong `SITE_PASSWORD` is the real defence here, or set `TRUSTED_PROXY_HEADER`.
  - **Opt-in for self-hosting behind your own proxy**: set `TRUSTED_PROXY_HEADER` to the header your ingress proxy sets or appends (for example `X-Forwarded-For` for nginx `proxy_add_x_forwarded_for`, or `X-Real-IP`). The rightmost valid address in it becomes the caller key. Only set it if that proxy is the only way to reach the app and it overwrites or appends the header; otherwise clients can pick their own throttle identity.

---

## 3. Content Security Policy (CSP) & XSS Defenses

### 3.1 Content Security Policy Tradeoffs
DeutschFlow enforces strict security headers:
```http
Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self' https://api.groq.com; worker-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'
Strict-Transport-Security: max-age=31536000
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: microphone=(self), camera=(), geolocation=()
```

### 3.2 Rationale for `'unsafe-inline'` & Comprehensive XSS Defenses
- DeutschFlow is a 100% client-rendered, local-first PWA that statically pre-renders all application routes and boots completely offline from a service worker precache.
- Next.js static HTML generation injects inline hydration bootstrap scripts that cannot use dynamic per-request nonces without disabling static rendering and breaking offline boot.
- **Crucial Boundary**: Because `script-src` includes `'unsafe-inline'`, the CSP header alone does **not** protect against inline script execution if untrusted HTML reaches the DOM. Comprehensive XSS resistance is instead established through deep defense-in-depth:
  - React/JSX automatic output encoding for all rendered strings.
  - Prohibited Sinks Policy: The codebase strictly prohibits `dangerouslySetInnerHTML`, `innerHTML`, `outerHTML`, `eval()`, `new Function()`, and `document.write()`.
  - Automated Regression Testing: `web/tests/security-sinks.test.ts` statically audits all TypeScript/TSX source files on every test run.
  - Input validation and byte length bounding on all backup imports.

---

## 4. API Credential Protection at Rest

### 4.1 Android Keystore
- Groq API keys on Android are encrypted using the hardware-backed Android Keystore (`KeyGenParameterSpec` with `AES/GCM/NoPadding`).
- Encryption keys never leave the secure hardware element.

### 4.2 Web Crypto Key Vault
- In the Web PWA, the API key is encrypted using WebCrypto `AES-GCM-256` before writing to IndexedDB (`settings` store).
- The AES key is generated as a non-extractable `CryptoKey` and stored in a separate IndexedDB database (`deutschflow-vault`), not in the settings row. It is not derived from any user- or origin-supplied seed: extraction is refused by the key itself, so a copied profile directory is not enough to read the ciphertext. `/api/logout`-time erasure and *Log out and erase this device* both destroy it (`src/lib/db/vault.ts`, `src/lib/db/erase.ts`).
- The plaintext key is reconstructed in memory before each AI request, so script running on this origin — or a malicious extension — can read it. That boundary is stated at the top of `src/lib/db/vault.ts`; it is the reason this is not the analogue of a hardware keystore.
- All AI error messages, URLs, and logs are scrubbed with `redactSensitiveKey` to prevent credential leakage.

---

## 5. Offline Cache & Service Worker Privacy

- The service worker (`sw.js`) caches the static build output **and** the app-shell HTML of the protected routes it precaches, which is what makes offline boot work. Cached pages are only served for a navigation that cannot reach the network, and any navigation answered with a redirect to `/login` purges them.
- Application data (transcripts, vocabulary, review events, roleplay conversations) resides strictly within IndexedDB, never in the HTTP Cache Storage.
- **Offline access outlives a server-side session expiry.** The session is an HttpOnly cookie the worker cannot read, and its logged-out flag is only set by an explicit logout; so a device that goes offline keeps opening the cached shell, and therefore the local library, until the app is used online again (which purges the cache if the cookie has expired) or the device is erased. On a shared device, *Log out and erase this device* is the control that closes this window.
- **Logout does not erase this data.** The access gate is one shared site password, not a per-user account, so on a shared device the next person to log in sees the previous learner's library. Settings → *Log out and erase this device* ends the session and then deletes every IndexedDB table (including the encrypted Groq key), the key vault, the app's `localStorage` entries and its caches (`src/lib/db/erase.ts`). It keeps only the `deutschflow-session` logged-out flag, which the logout itself depends on.
- Logging out clears authentication markers and invalidates any protected cache entries.

---

## 6. Production Deployment Readiness Checklist & Failure Consequences

### 6.1 Environment Variable Checklist
| Variable | Required | Default / Fallback | Consequence if Missing or Misconfigured |
| :--- | :--- | :--- | :--- |
| **`SITE_PASSWORD`** | **YES** | None | **Critical**: The site access gate is rendered inaccessible or fails to unlock. Authentication cannot succeed. Under 16 characters, the server logs a warning at startup (`src/instrumentation.ts`): the throttle still allows about 2,880 guesses a day per caller. |
| **`SESSION_SECRET`** | **YES** | None — there is no fallback | **Critical**: Session tokens cannot be signed, so `/api/login` returns 503 and the gate redirects every request to `/login`. Only `SESSION_SECRET` is read (an `AUTH_SECRET` variable is not), and a value under 32 bytes is treated as unset. |
| **`UPSTASH_REDIS_REST_URL`** | **RECOMMENDED** | `undefined` (Local in-memory Map) | **Medium (Security/Availability)**: Without Upstash Redis, rate-limiting runs in local in-memory fallback mode. Each serverless instance tracks attempts independently, allowing distributed attackers to rotate across instances. No shared state exists between containers. |
| **`UPSTASH_REDIS_REST_TOKEN`** | **RECOMMENDED** | `undefined` | Same as above. If URL is present without valid token, requests to Redis return HTTP 401/403. Under `fail-secure-fallback` mode, it falls back to in-memory limiting; under `fail-closed` mode, it blocks all logins with HTTP 503. |
| **`TRUSTED_PROXY_HEADER`** | NO (self-hosted only) | `undefined` (one shared throttle bucket) | Ignored on Vercel. Self-hosted, it names the header your reverse proxy sets with the client address, giving each client its own throttle bucket. Set it only behind a proxy that overwrites or appends that header, or clients can choose their own identity. |
| **`THROTTLE_FAILURE_POLICY`** | NO | `"fail-secure-fallback"` | If set to `"fail-closed"` and Redis is unreachable, **all login attempts return HTTP 503 Service Unavailable**. If set to `"fail-secure-fallback"`, logins remain available protected by local process-level rate limiting. |
| **`SESSION_MAX_AGE_SECONDS`** | NO | `2592000` (30 days) | Defaults to 30 days. Clamped to `[3600, 7776000]` (1 hour to 90 days) to prevent unsafe infinite or microscopic session windows. |
| **`SESSION_EPOCH`** | NO | `"1"` | Defaults to version `"1"`. Incrementing this value invalidates all active sessions globally without changing passwords. |
| **`GROQ_API_KEY`** | NO | None — not read by the app | There is no server-side AI key: every AI request is made from the browser with the user's own key from the in-app Key Vault, which the client encrypts at rest. Setting this variable has no effect. |
| **`NEXT_PUBLIC_GROQ_MODEL`** | NO | `openai/gpt-oss-120b` | Inlined into the client bundle at build time, so a change needs a rebuild. A value outside `SUPPORTED_GROQ_MODELS` is ignored and the default is used. |

### 6.2 Pre-Launch Verification Checklist
- [ ] Master `SITE_PASSWORD` set with high entropy (>= 16 characters).
- [ ] Random `SESSION_SECRET` generated with at least 32 bytes of secure random entropy (`openssl rand -base64 32`).
- [ ] Upstash Redis provisioned with `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN` to enable atomic distributed rate limiting across serverless edge containers.
- [ ] Ingress proxy boundaries verified: `process.env.VERCEL === "1"` ensures `x-forwarded-for` is injected by the trusted edge. If deployed behind custom reverse proxies, ensure the ingress proxy overwrites or sanitizes untrusted incoming `X-Forwarded-For` headers.
- [ ] Automated regression test suites passing across Web (`npm test`) and Android (`./gradlew.bat testDebugUnitTest`).
