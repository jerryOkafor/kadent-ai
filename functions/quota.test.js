import assert from "node:assert/strict";
import { test } from "node:test";
import { nextQuota } from "./quota.js";

const LIMITS = { perMinute: 3, perDay: 5 };
// 2026-10-27 12:00:30 UTC: thirty seconds into a minute, so the wait to the next one is 30 s.
const NOW = Date.UTC(2026, 9, 27, 12, 0, 30);

test("a user's first call is allowed and counted", () => {
  const next = nextQuota(null, NOW, LIMITS);
  assert.equal(next.allowed, true);
  assert.equal(next.value.minuteCount, 1);
  assert.equal(next.value.dayCount, 1);
});

test("a call past the minute's limit is refused until the next minute", () => {
  let value = null;
  for (let i = 0; i < 3; i++) value = nextQuota(value, NOW, LIMITS).value;
  const next = nextQuota(value, NOW, LIMITS);
  assert.equal(next.allowed, false);
  assert.equal(next.retryAfterSeconds, 30);
  assert.deepEqual(next.value, value, "a refused call is not counted");
});

test("a new minute starts the minute's count again but not the day's", () => {
  let value = null;
  for (let i = 0; i < 3; i++) value = nextQuota(value, NOW, LIMITS).value;
  const next = nextQuota(value, NOW + 60_000, LIMITS);
  assert.equal(next.allowed, true);
  assert.equal(next.value.minuteCount, 1);
  assert.equal(next.value.dayCount, 4);
});

test("a call past the day's limit is refused until midnight UTC", () => {
  let value = null;
  let at = NOW;
  for (let i = 0; i < 5; i++, at += 60_000) value = nextQuota(value, at, LIMITS).value;
  const next = nextQuota(value, at, LIMITS);
  assert.equal(next.allowed, false);
  const midnight = Date.UTC(2026, 9, 28);
  assert.equal(next.retryAfterSeconds, Math.ceil((midnight - at) / 1000));
});

test("a new day starts both counts again", () => {
  let value = null;
  let at = NOW;
  for (let i = 0; i < 5; i++, at += 60_000) value = nextQuota(value, at, LIMITS).value;
  const next = nextQuota(value, Date.UTC(2026, 9, 28, 0, 0, 1), LIMITS);
  assert.equal(next.allowed, true);
  assert.equal(next.value.dayCount, 1);
});
