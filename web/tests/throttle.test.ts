import { beforeEach, describe, expect, it } from "vitest";
import {
  delayForNextAttempt,
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
    for (let i = 0; i < 4; i++) recordFailure("1.2.3.4");
    const first = delayForNextAttempt("1.2.3.4");
    recordFailure("1.2.3.4");
    const second = delayForNextAttempt("1.2.3.4");

    expect(second).toBe(first * 2);
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
});
