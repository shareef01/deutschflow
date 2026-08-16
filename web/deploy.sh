#!/usr/bin/env bash
#
# deploy.sh — DeutschFlow PWA: one-command production deploy to Vercel.
#
# Sequential pipeline:
#   1. Ensure the Vercel CLI is installed (installs globally if missing).
#   2. Verify authentication (VERCEL_TOKEN or an existing `vercel login`).
#   3. Clean production build — ABORTS immediately if it fails.
#   4. `vercel --prod --yes` — force the production deployment, no prompts.
#   5. Capture and print the resulting production URL.
#
# Usage:
#   chmod +x deploy.sh && ./deploy.sh
#
# First-time setup (once, per account):
#   npx vercel login          # or export VERCEL_TOKEN=xxx for CI/scripted use
#   npx vercel link           # links this directory to your Vercel project
#
# Known platform rule: when this directory sits inside a git repository, Vercel
# attributes the deploy to the repo's git author and BLOCKS it unless that
# author is a member of the team ("Git author must have access to the team").
# Fix: add the git author to the team in the Vercel dashboard, or deploy from a
# git-less copy of this directory (cp the files outside the repo first).
#
# The script works from anywhere in the repo: it resolves the web app root
# (where package.json + vercel.json live) and runs everything from there.

set -euo pipefail

# Resolve the directory this script lives in (web/), wherever it is invoked from.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "==> DeutschFlow PWA deploy pipeline"
echo "    working directory: $SCRIPT_DIR"

# ---------------------------------------------------------------------------
# 1. Vercel CLI — installed pinned to the major at authoring time, so a future
#    unpinned `latest` cannot surprise this pipeline (supply-chain hygiene:
#    the CLI runs with the user's privileges and the token's scope).
# ---------------------------------------------------------------------------
VERCEL_CLI_MAJOR="59"
if ! command -v vercel >/dev/null 2>&1; then
  echo "==> Vercel CLI not found — installing globally (npm i -g vercel@${VERCEL_CLI_MAJOR})…"
  npm install -g "vercel@${VERCEL_CLI_MAJOR}"
fi
VERCEL_BIN="$(command -v vercel)"

# The resolved binary must be the pinned major: a different vercel earlier in
# PATH (or a pre-existing install) must not run with the token's scope.
if ! "$VERCEL_BIN" --version | grep -q "^${VERCEL_CLI_MAJOR}\\."; then
  echo "==> vercel ${VERCEL_CLI_MAJOR}.x required (found $("$VERCEL_BIN" --version)) — installing…"
  npm install -g "vercel@${VERCEL_CLI_MAJOR}"
  VERCEL_BIN="$(command -v vercel)"
fi
# Verify once more after any install — a failed install must stop the pipeline
# rather than deploy with an unverified binary.
if ! "$VERCEL_BIN" --version | grep -q "^${VERCEL_CLI_MAJOR}\\."; then
  echo "ERROR: vercel ${VERCEL_CLI_MAJOR}.x is not available (found $("$VERCEL_BIN" --version)). Aborting."
  exit 1
fi
echo "==> Vercel CLI: $VERCEL_BIN ($("$VERCEL_BIN" --version))"

# ---------------------------------------------------------------------------
# 2. Authentication — fail fast with a helpful message instead of an opaque
#    error halfway through a production deploy.
# ---------------------------------------------------------------------------
if ! "$VERCEL_BIN" whoami >/dev/null 2>&1; then
  echo
  echo "ERROR: not authenticated with Vercel."
  echo "  Run 'vercel login' once (interactive), or export VERCEL_TOKEN for CI:"
  echo "    export VERCEL_TOKEN=<your token>"
  echo "  Then re-run ./deploy.sh"
  exit 1
fi
echo "==> Authenticated as: $("$VERCEL_BIN" whoami)"

# ---------------------------------------------------------------------------
# 3. Clean production build. `set -e` aborts the whole script if this fails.
#    (Next.js 'next build' already runs the TypeScript type check, so a clean
#    build doubles as the type gate.)
# ---------------------------------------------------------------------------
echo "==> Building for production (npm run build)…"
npm run build

# ---------------------------------------------------------------------------
# 4. Production deploy, non-interactive. The CLI reads VERCEL_TOKEN from the
#    environment natively — it is deliberately NOT passed as a --token argv,
#    which would expose the secret in process listings and CI argv records.
# ---------------------------------------------------------------------------
echo "==> Deploying to production (vercel --prod --yes)…"
DEPLOY_OUTPUT="$("$VERCEL_BIN" --prod --yes 2>&1)"
echo "$DEPLOY_OUTPUT"

# ---------------------------------------------------------------------------
# 5. Capture and print the production URL. The deploy itself succeeded (set -e
#    would have aborted otherwise), so a URL we cannot recognise — a custom
#    domain, say — is a warning, not a failure.
# ---------------------------------------------------------------------------
PROD_URL="$(printf '%s\n' "$DEPLOY_OUTPUT" | grep -oE 'https://[^ ]+' | tail -n 1 || true)"

if [ -n "$PROD_URL" ]; then
  echo
  echo "======================================================"
  echo "  Production deployment live: $PROD_URL"
  echo "======================================================"
else
  echo
  echo "WARNING: could not extract a URL from the deploy output above."
  echo "         The deployment succeeded — inspect the output for its URL."
fi
