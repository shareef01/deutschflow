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
- The logout endpoint (`POST /api/logout`) strictly requires a POST method and validates the request origin (`Origin` / `Referer`) against the server origin to eliminate cross-site request forgery.
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
- **Atomic Pipelines**: To prevent race conditions during concurrent authentication spikes, operations are executed atomically using Upstash REST pipelines (`/pipeline`) using transactionally chained `INCR`, `EXPIRE`, `SET ... EX`, and `DEL`.
- **Configurable Failure Policy (`THROTTLE_FAILURE_POLICY`)**:
  - `fail-secure-fallback` (default): If Redis times out or is unreachable, the system falls back to an in-memory LRU rate limiter. This provides local burst defense per node while keeping the application accessible.
  - `fail-closed`: If Redis fails or is unreachable, the endpoint strictly rejects authentication requests with HTTP 503 Service Unavailable (`ThrottleStoreError`), ensuring no attacker can bypass distributed lockouts during a store outage.
- **Distributed Protection vs Local Fallback Distinction**: Local LRU fallback protects individual instances from rapid flooding, but cannot synchronize lockouts globally across ephemeral serverless instances. For full production-grade distributed enforcement, a highly available Upstash Redis cluster with valid credentials and network egress is required.

### 2.3 IP Spoofing Protections
- `x-forwarded-for` and `x-real-ip` headers are **only** trusted when running behind a validated proxy boundary.
- Mere presence of `VERCEL === "1"` in environment variables is insufficient: the request must also present the edge-injected signature header `x-vercel-id`. In untrusted or simulated environments, all requests share a fallback bucket to prevent header forgery.

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
- Key material is derived from an origin-bound seed and decrypted only in memory when making AI requests.
- All AI error messages, URLs, and logs are scrubbed with `redactSensitiveKey` to prevent credential leakage.

---

## 5. Offline Cache & Service Worker Privacy

- The service worker (`sw.js`) caches only public assets and static application shell scripts.
- Application data (transcripts, vocabulary, review events, roleplay conversations) resides strictly within IndexedDB, never in the HTTP Cache Storage.
- Logging out clears authentication markers and invalidates any protected cache entries.

---

## 6. Production Deployment Prerequisites

For full production readiness on Vercel or cloud hosting:
1. `SITE_PASSWORD`: Strong master passphrase for site access.
2. `SESSION_SECRET`: At least 32 bytes of cryptographically random entropy (e.g., `openssl rand -base64 32`).
3. `SESSION_EPOCH`: Integer version string (default `"1"`). Increment to revoke all active sessions.
4. `SESSION_MAX_AGE_SECONDS`: (Optional) Session lifetime in seconds (e.g., `604800` for 7 days).
5. `UPSTASH_REDIS_REST_URL`: (Optional but recommended for serverless) Upstash Redis REST endpoint.
6. `UPSTASH_REDIS_REST_TOKEN`: Upstash Redis REST access token.
