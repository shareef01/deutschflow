import { beforeEach, describe, expect, it } from "vitest";
import {
  delayForNextAttempt,
  getTrackedCount,
  MAX_TRACKED,
  recordFailure,
  recordSuccess,
  resetThrottle,
} from "@/lib/auth/throttle";

/**
 * The gate's strength is one shared secret, and until this existed the number of
 * guesses an attacker could make was bounded only by how fast the host would serve.
 */

beforeEach(() => resetThrottle());

describe("login throttle", () => {
  it("lets the first few honest mistakes through free", () => {
    for (let i = 0; i < 3; i++) {
      expect(delayForNextAttempt("1.2.3.4")).toBe(0);
      recordFailure("1.2.3.4");
    }
    expect(delayForNextAttempt("1.2.3.4")).toBeGreaterThan(0);
  });

  it("doubles the wait with each further failure", () => {
    const now = 1_000_000;
    for (let i = 0; i < 4; i++) recordFailure("1.2.3.4", now);
    const first = delayForNextAttempt("1.2.3.4", now);
    recordFailure("1.2.3.4", now);
    const second = delayForNextAttempt("1.2.3.4", now);

    expect(second).toBe(first * 2);
  });

  it("counts a cooldown down instead of holding the caller for the whole window", () => {
    const start = 1_000_000;
    for (let i = 0; i < 3; i++) recordFailure("1.2.3.4", start);

    expect(delayForNextAttempt("1.2.3.4", start)).toBe(1_000);
    expect(delayForNextAttempt("1.2.3.4", start + 400)).toBe(600);
    expect(delayForNextAttempt("1.2.3.4", start + 1_000)).toBe(0);
  });

  it("caps the wait so a legitimate user is not locked out forever", () => {
    for (let i = 0; i < 40; i++) recordFailure("1.2.3.4");
    expect(delayForNextAttempt("1.2.3.4")).toBe(30_000);
  });

  it("throttles each caller separately", () => {
    for (let i = 0; i < 10; i++) recordFailure("1.2.3.4");
    expect(delayForNextAttempt("1.2.3.4")).toBeGreaterThan(0);
    expect(delayForNextAttempt("5.6.7.8")).toBe(0);
  });

  it("forgives a caller after a quiet window", () => {
    const start = 1_000_000;
    for (let i = 0; i < 10; i++) recordFailure("1.2.3.4", start);
    expect(delayForNextAttempt("1.2.3.4", start)).toBeGreaterThan(0);
    // Sixteen minutes later.
    expect(delayForNextAttempt("1.2.3.4", start + 16 * 60_000)).toBe(0);
  });

  it("clears the record once the caller proves who they are", () => {
    for (let i = 0; i < 10; i++) recordFailure("1.2.3.4");
    recordSuccess("1.2.3.4");
    expect(delayForNextAttempt("1.2.3.4")).toBe(0);
  });

  it("normalizes malformed and empty keys safely", () => {
    recordFailure("");
    recordFailure("   ");
    expect(delayForNextAttempt("")).toBe(0); // 2 failures on "unknown"
    recordFailure("");
    // 3rd failure on "unknown" triggers cooldown
    expect(delayForNextAttempt("")).toBeGreaterThan(0);
    expect(delayForNextAttempt("unknown")).toBeGreaterThan(0);
  });

  it("handles excessively long keys without breaking", () => {
    const longKey = "a".repeat(1000);
    for (let i = 0; i < 4; i++) recordFailure(longKey);
    expect(delayForNextAttempt(longKey)).toBeGreaterThan(0);
  });

  it("evicts oldest entries without wiping active throttled attackers on key flood", () => {
    const now = 1_000_000;
    const attackerIp = "bad-actor-ip";
    for (let i = 0; i < 5; i++) recordFailure(attackerIp, now);
    expect(delayForNextAttempt(attackerIp, now)).toBeGreaterThan(0);

    // Flood with distinct keys newer than attackerIp
    for (let i = 0; i < 10_005; i++) {
      recordFailure(`fake-ip-${i}`, now + 10);
    }

    // Active map size is bounded
    expect(getTrackedCount()).toBeLessThanOrEqual(MAX_TRACKED);

    // CRITICAL: Attacker must STILL be locked out; eviction must not have freed them!
    expect(delayForNextAttempt(attackerIp, now + 10)).toBeGreaterThan(0);
  });

  it("adversarial flood of unthrottled keys purges unlocked keys first, retaining active lockouts", () => {
    const now = 2_000_000;
    const lockedIp = "locked-target";
    // 6 failures -> locked out for several seconds
    for (let i = 0; i < 6; i++) recordFailure(lockedIp, now);
    expect(delayForNextAttempt(lockedIp, now)).toBeGreaterThan(0);

    // Adversary attempts to clear lockout by flooding dummy keys (1 failure each, 0 cooldown)
    for (let i = 0; i < MAX_TRACKED + 500; i++) {
      recordFailure(`dummy-${i}`, now + 1);
    }

    // Locked IP must still be in cooldown at now + 100ms
    expect(delayForNextAttempt(lockedIp, now + 100)).toBeGreaterThan(0);
  });

  it("evicts earliest-expiring lockouts when all tracked entries are actively locked", () => {
    const now = 3_000_000;
    // Saturate the map with entries that are ALL actively locked out
    // Give each distinct failure counts so their nextAllowed values differ
    for (let i = 0; i < MAX_TRACKED + 100; i++) {
      // 5 failures -> exponential backoff
      for (let f = 0; f < 5; f++) {
        recordFailure(`all-locked-${i}`, now + i);
      }
    }

    // Size must be bounded to MAX_TRACKED
    expect(getTrackedCount()).toBeLessThanOrEqual(MAX_TRACKED);

    // The most recently added entry with a longer future lockout must still be tracked and locked
    const recentKey = `all-locked-${MAX_TRACKED + 50}`;
    expect(delayForNextAttempt(recentKey, now + MAX_TRACKED + 50)).toBeGreaterThan(0);
  });
});

